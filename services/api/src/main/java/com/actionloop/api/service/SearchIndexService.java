package com.actionloop.api.service;

import com.actionloop.api.ai.AiProperties;
import com.actionloop.api.ai.MaaSEmbeddingProvider;
import com.actionloop.api.ai.MaaSRerankerProvider;
import com.actionloop.api.domain.SearchDocument;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.api.repository.SearchDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 混合检索：PostgreSQL FTS + pg_trgm + pgvector 语义召回，再用 MaaS rerank 重排。
 * 向量列由默认 Flyway 迁移创建；embedding/rerank 不可用时记录日志并退化为关键词排序，但核心检索不阻塞。
 */
@Service
public class SearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);
    private static final int RERANK_CANDIDATES = 80;

    private final SearchDocumentRepository documentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MaaSEmbeddingProvider embeddingProvider;
    private final MaaSRerankerProvider rerankerProvider;
    private final AiProperties aiProperties;

    public SearchIndexService(SearchDocumentRepository documentRepository, JdbcTemplate jdbcTemplate,
                              MaaSEmbeddingProvider embeddingProvider, MaaSRerankerProvider rerankerProvider,
                              AiProperties aiProperties) {
        this.documentRepository = documentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
        this.rerankerProvider = rerankerProvider;
        this.aiProperties = aiProperties;
    }

    @Transactional
    public UUID index(UUID workspaceId, UUID sourceItemId, UUID assetId, String title,
                      String content, String docType, UUID ownerId, boolean personalOnly) {
        SearchDocument doc = sourceItemId == null ? null
                : documentRepository.findByWorkspaceIdAndSourceItemId(workspaceId, sourceItemId).orElse(null);
        if (doc == null) {
            doc = new SearchDocument();
            doc.setWorkspaceId(workspaceId);
            doc.setSourceItemId(sourceItemId);
        }
        doc.setAssetId(assetId);
        doc.setTitle(trim(title, 200));
        // 原文保留用于片段展示与三元组相似度；tsvector 单独存分词结果
        doc.setContentTokens(trim(content, 20000));
        doc.setDocType(docType);
        doc.setOwnerScope(personalOnly
                ? "{\"visibility\":\"PERSONAL\",\"ownerId\":\"" + ownerId + "\"}"
                : "{\"visibility\":\"WORKSPACE\",\"ownerId\":\"" + ownerId + "\"}");
        doc.setUpdatedAt(Instant.now());
        doc.setRawTextHash(sha256(content == null ? "" : content));
        // saveAndFlush：确保 @PrePersist 已分配真实 id 且行已插入，后续原生 UPDATE 才能命中
        doc = documentRepository.saveAndFlush(doc);
        UUID docId = doc.getId();
        String embeddingTitle = doc.getTitle();
        String embeddingContent = doc.getContentTokens();
        // tsvector 由数据库维护，避免应用侧分词与 FTS 词典不一致
        jdbcTemplate.update("UPDATE search_documents SET content_tsv = to_tsvector('simple', ?) WHERE id = ?",
                tokenize(embeddingTitle + " " + (content == null ? "" : content)), docId);
        // embedding 走外部 HTTP，放到事务提交后执行，避免慢调用长期占用数据库连接与事务
        afterCommit(() -> refreshEmbedding(docId, embeddingTitle, embeddingContent));
        return docId;
    }

    public List<Hit> search(UUID workspaceId, UUID userId, String query, String docType, int offset, int limit) {
        String safeQuery = query == null ? "" : query.trim();
        String tokens = tokenize(query);
        String tsQuery = tokens.isBlank() ? null : String.join(" | ", tokens.split("\\s+"));
        String like = "%" + safeQuery + "%";
        String queryVector = embedForQuery(safeQuery);

        List<Hit> candidates;
        try {
            candidates = queryCandidates(queryVector, tsQuery, safeQuery, like,
                    workspaceId, userId, docType, offset, limit);
        } catch (Exception e) {
            if (queryVector == null) {
                throw e;
            }
            // 向量维度漂移/列缺失等异常时退化为纯关键词检索，而不是把整个检索打挂成 500
            log.warn("hybrid search vector path failed, fallback to keyword code={}", e.getClass().getSimpleName());
            candidates = queryCandidates(null, tsQuery, safeQuery, like,
                    workspaceId, userId, docType, offset, limit);
        }

        List<Hit> reranked = rerank(safeQuery, candidates);
        int from = Math.max(offset, 0);
        int to = Math.min(reranked.size(), from + Math.max(limit, 1));
        return from >= reranked.size() ? List.of() : reranked.subList(from, to);
    }

    private List<Hit> queryCandidates(String queryVector, String tsQuery, String safeQuery, String like,
                                      UUID workspaceId, UUID userId, String docType, int offset, int limit) {
        // 候选池不下推 offset：rerank 会重排顺序，必须先取够 offset+limit 条再在内存分页
        int candidateLimit = Math.min(RERANK_CANDIDATES, Math.max(limit, 1) + Math.max(offset, 0));
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (SELECT d.id, d.title, d.content_tokens, d.doc_type, d.source_item_id, d.asset_id,
                       d.owner_scope, d.created_at,
                       CASE WHEN ?::text IS NULL THEN 0
                            ELSE ts_rank(d.content_tsv, to_tsquery('simple', ?::text)) END AS rank,
                       similarity(coalesce(d.title,'') || ' ' || coalesce(d.content_tokens,''), ?) AS sim,
                       CASE WHEN ?::text IS NULL OR d.embedding IS NULL THEN 0
                            ELSE 1 - (d.embedding <=> ?::vector) END AS vector_score
                FROM search_documents d
                WHERE d.workspace_id = ? AND d.deleted_at IS NULL
                  AND (d.owner_scope->>'visibility' <> 'PERSONAL'
                       OR d.owner_scope->>'ownerId' = ?)
                  AND (coalesce(d.title,'') ILIKE ? OR coalesce(d.content_tokens,'') ILIKE ?
                       OR (?::text IS NOT NULL AND d.content_tsv @@ to_tsquery('simple', ?::text))
                       OR similarity(coalesce(d.title,'') || ' ' || coalesce(d.content_tokens,''), ?) > 0.12
                       OR (?::text IS NOT NULL AND d.embedding IS NOT NULL
                           AND (1 - (d.embedding <=> ?::vector)) > ?))
                """);
        List<Object> args = new ArrayList<>();
        args.add(tsQuery);
        args.add(tsQuery);
        args.add(safeQuery);
        args.add(queryVector);
        args.add(queryVector);
        args.add(workspaceId);
        args.add(userId.toString());
        args.add(like);
        args.add(like);
        args.add(tsQuery);
        args.add(tsQuery);
        args.add(safeQuery);
        args.add(queryVector);
        args.add(queryVector);
        args.add(aiProperties.getSemanticThreshold());
        if (docType != null && !docType.isBlank()) {
            sql.append(" AND d.doc_type = ? ");
            args.add(docType);
        }
        sql.append(" ) s ORDER BY (0.45 * s.vector_score + 0.35 * coalesce(s.rank,0) + 0.20 * s.sim) DESC,")
                .append(" s.created_at DESC LIMIT ? OFFSET 0 ");
        args.add(candidateLimit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new Hit(
                UUID.fromString(rs.getString("id")),
                rs.getString("title"),
                snippet(rs.getString("content_tokens"), safeQuery),
                rs.getString("doc_type"),
                rs.getString("source_item_id") == null ? null : UUID.fromString(rs.getString("source_item_id")),
                rs.getString("asset_id") == null ? null : UUID.fromString(rs.getString("asset_id")),
                Math.max(Math.max(rs.getDouble("rank"), rs.getDouble("sim")), rs.getDouble("vector_score")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("owner_scope")
        ), args.toArray());
    }

    public record Hit(UUID id, String title, String snippet, String docType, UUID sourceItemId,
                      UUID assetId, double score, Instant createdAt, String ownerScope) {
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void refreshEmbedding(UUID docId, String title, String content) {
        if (!embeddingProvider.available()) {
            return;
        }
        try {
            float[] embedding = embeddingProvider.embed(title + "\n" + (content == null ? "" : content));
            jdbcTemplate.update("""
                    UPDATE search_documents
                    SET embedding = ?::vector, model_version = ?
                    WHERE id = ?
                    """, embeddingProvider.toVectorLiteral(embedding),
                    aiProperties.getEmbeddingModel() + ":" + aiProperties.getEmbeddingDim(), docId);
        } catch (BusinessException e) {
            log.warn("search embedding refresh skipped doc={} code={}", docId, e.code());
        } catch (Exception e) {
            log.warn("search embedding refresh failed doc={} code={}", docId, e.getClass().getSimpleName());
        }
    }

    private String embedForQuery(String query) {
        if (query.isBlank() || !embeddingProvider.available()) {
            return null;
        }
        try {
            return embeddingProvider.toVectorLiteral(embeddingProvider.embed(query));
        } catch (Exception e) {
            log.warn("search query embedding failed code={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private List<Hit> rerank(String query, List<Hit> candidates) {
        if (!rerankerProvider.available() || query.isBlank() || candidates.size() < 2) {
            return candidates;
        }
        List<String> documents = candidates.stream()
                .map(hit -> safe(hit.title()) + " " + safe(hit.snippet()))
                .toList();
        List<MaaSRerankerProvider.RerankHit> ranked = rerankerProvider.rerank(query, documents, documents.size());
        List<Hit> result = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (MaaSRerankerProvider.RerankHit hit : ranked) {
            if (hit.index() >= 0 && hit.index() < candidates.size() && used.add(hit.index())) {
                result.add(candidates.get(hit.index()));
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!used.contains(i)) {
                result.add(candidates.get(i));
            }
        }
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 中文按单字 + 二元组切分，英文/数字按词；输出小写空格串，兼容 PostgreSQL simple 词典。 */
    static String tokenize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase();
        StringBuilder out = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        List<String> cjk = new ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean isCjk = Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
            if (isCjk) {
                if (!latin.isEmpty()) {
                    out.append(latin).append(' ');
                    latin.setLength(0);
                }
                cjk.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(c);
            } else {
                if (!latin.isEmpty()) {
                    out.append(latin).append(' ');
                    latin.setLength(0);
                }
                flushCjk(cjk, out);
            }
        }
        if (!latin.isEmpty()) {
            out.append(latin).append(' ');
        }
        flushCjk(cjk, out);
        return out.toString().trim();
    }

    private static void flushCjk(List<String> chars, StringBuilder out) {
        if (chars.isEmpty()) {
            return;
        }
        for (int i = 0; i < chars.size(); i++) {
            out.append(chars.get(i)).append(' ');
            if (i + 1 < chars.size()) {
                out.append(chars.get(i)).append(chars.get(i + 1)).append(' ');
            }
        }
        chars.clear();
    }

    private static String snippet(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String q = query == null ? "" : query.trim();
        int idx = q.isEmpty() ? -1 : content.indexOf(q);
        int start = idx < 0 ? 0 : Math.max(0, idx - 30);
        int end = Math.min(content.length(), start + 120);
        return (start > 0 ? "…" : "") + content.substring(start, end) + (end < content.length() ? "…" : "");
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String sha256(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

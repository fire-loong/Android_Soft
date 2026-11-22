package com.actionloop.api.service;

import com.actionloop.api.domain.SearchDocument;
import com.actionloop.api.repository.SearchDocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 关键词索引与中文兼容分词（指导书 12.3）。
 * 无 pgvector 时先保证 FTS/三元组检索可用；embedding 由迁移 006 在 vector profile 下补充。
 */
@Service
public class SearchIndexService {

    private final SearchDocumentRepository documentRepository;
    private final JdbcTemplate jdbcTemplate;

    public SearchIndexService(SearchDocumentRepository documentRepository, JdbcTemplate jdbcTemplate) {
        this.documentRepository = documentRepository;
        this.jdbcTemplate = jdbcTemplate;
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
        documentRepository.save(doc);
        // tsvector 由数据库维护，避免应用侧分词与 FTS 词典不一致
        jdbcTemplate.update("UPDATE search_documents SET content_tsv = to_tsvector('simple', ?) WHERE id = ?",
                tokenize(doc.getTitle() + " " + (content == null ? "" : content)), doc.getId());
        return doc.getId();
    }

    public List<Hit> search(UUID workspaceId, UUID userId, String query, String docType, int offset, int limit) {
        String tokens = tokenize(query);
        String tsQuery = tokens.isBlank() ? null : String.join(" | ", tokens.split("\\s+"));
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.title, d.content_tokens, d.doc_type, d.source_item_id, d.asset_id,
                       d.owner_scope, d.created_at,
                       CASE WHEN ?::text IS NULL THEN 0
                            ELSE ts_rank(d.content_tsv, to_tsquery('simple', ?::text)) END AS rank,
                       similarity(coalesce(d.title,'') || ' ' || coalesce(d.content_tokens,''), ?) AS sim
                FROM search_documents d
                WHERE d.workspace_id = ? AND d.deleted_at IS NULL
                  AND (d.owner_scope->>'visibility' <> 'PERSONAL'
                       OR d.owner_scope->>'ownerId' = ?)
                  AND (coalesce(d.title,'') ILIKE ? OR coalesce(d.content_tokens,'') ILIKE ?
                       OR (?::text IS NOT NULL AND d.content_tsv @@ to_tsquery('simple', ?::text))
                       OR similarity(coalesce(d.title,'') || ' ' || coalesce(d.content_tokens,''), ?) > 0.12)
                """);
        List<Object> args = new ArrayList<>();
        args.add(tsQuery);
        args.add(tsQuery);
        args.add(query == null ? "" : query.trim());
        args.add(workspaceId);
        args.add(userId.toString());
        args.add(like);
        args.add(like);
        args.add(tsQuery);
        args.add(tsQuery);
        args.add(query == null ? "" : query.trim());
        if (docType != null && !docType.isBlank()) {
            sql.append(" AND d.doc_type = ? ");
            args.add(docType);
        }
        sql.append(" ORDER BY rank DESC, sim DESC, d.created_at DESC LIMIT ? OFFSET ? ");
        args.add(limit);
        args.add(offset);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new Hit(
                UUID.fromString(rs.getString("id")),
                rs.getString("title"),
                snippet(rs.getString("content_tokens"), query),
                rs.getString("doc_type"),
                rs.getString("source_item_id") == null ? null : UUID.fromString(rs.getString("source_item_id")),
                rs.getString("asset_id") == null ? null : UUID.fromString(rs.getString("asset_id")),
                Math.max(rs.getDouble("rank"), rs.getDouble("sim")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("owner_scope")
        ), args.toArray());
    }

    public record Hit(UUID id, String title, String snippet, String docType, UUID sourceItemId,
                      UUID assetId, double score, Instant createdAt, String ownerScope) {
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

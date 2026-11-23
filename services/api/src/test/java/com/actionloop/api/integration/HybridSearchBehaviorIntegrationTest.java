package com.actionloop.api.integration;

import com.actionloop.api.ai.AiProperties;
import com.actionloop.api.ai.MaaSEmbeddingProvider;
import com.actionloop.api.ai.MaaSRerankerProvider;
import com.actionloop.api.repository.SearchDocumentRepository;
import com.actionloop.api.service.SearchIndexService;
import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖外部 MaaS 的检索行为测试：rerank 重排、embedding 不可用降级、无向量行、
 * 跨工作空间数据隔离与 cursor 分页（防止 offset 双重应用导致跨页丢结果）。
 */
class HybridSearchBehaviorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SearchDocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MaaSEmbeddingProvider embeddingProvider;

    @Autowired
    private AiProperties aiProperties;

    @Test
    void rerankProviderReorderIsApplied() {
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), unique("rerank-ws"));
        UUID workspaceId = UUID.fromString(wsId);
        UUID ownerId = UUID.fromString(owner.get("userId"));

        indexThreeExpenseDocs(workspaceId, ownerId);

        List<UUID> baseOrder = ids(inOrderService().search(workspaceId, ownerId, "报销", null, 0, 10));
        List<UUID> rerankedOrder = ids(reversedRerankService().search(workspaceId, ownerId, "报销", null, 0, 10));

        assertEquals(3, baseOrder.size(), "三条文档都应被召回");
        List<UUID> expectedReversed = new ArrayList<>(baseOrder);
        Collections.reverse(expectedReversed);
        assertEquals(expectedReversed, rerankedOrder, "reranker 返回的顺序必须真正作用到最终结果");
    }

    @Test
    void cursorPaginationHasNoDuplicateOrLoss() {
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), unique("page-ws"));
        UUID workspaceId = UUID.fromString(wsId);
        UUID ownerId = UUID.fromString(owner.get("userId"));

        indexThreeExpenseDocs(workspaceId, ownerId);

        SearchIndexService service = inOrderService();
        Set<UUID> seen = new HashSet<>();
        int pages = 0;
        for (int offset = 0; offset < 3; offset++) {
            List<UUID> page = ids(service.search(workspaceId, ownerId, "报销", null, offset, 1));
            assertEquals(1, page.size(), "offset=" + offset + " 应返回恰好一条");
            assertTrue(seen.add(page.get(0)), "offset=" + offset + " 返回了重复文档");
            pages++;
        }
        assertEquals(3, pages);
        assertEquals(3, seen.size(), "三页必须覆盖三条不同文档");
    }

    @Test
    void keywordSearchDegradesWithNullEmbeddingsAndStaysWorkspaceScoped() {
        var owner = register();
        String wsA = createWorkspace(owner.get("access"), unique("iso-a"));
        String wsB = createWorkspace(owner.get("access"), unique("iso-b"));
        UUID wsAId = UUID.fromString(wsA);
        UUID wsBId = UUID.fromString(wsB);
        UUID ownerId = UUID.fromString(owner.get("userId"));

        // 测试环境默认不启用真实 MaaS，索引不会写入向量：检索必须仍可用的同时不跨工作空间泄露
        UUID docA = inOrderService().index(wsAId, null, null,
                "报销单提交", "差旅报销请在周五前提交", "SOURCE", ownerId, false);
        UUID docB = inOrderService().index(wsBId, null, null,
                "独有词汇丙", "仅属于工作空间 B 的独有词汇丙", "SOURCE", ownerId, false);

        assertFalse(embeddingProvider.available(), "本测试前提是 embedding 不可用，用于验证降级路径");
        Boolean embeddingIsNull = jdbcTemplate.queryForObject(
                "SELECT embedding IS NULL FROM search_documents WHERE id = ?", Boolean.class, docA);
        assertEquals(Boolean.TRUE, embeddingIsNull, "无向量行必须能正常存在");

        List<UUID> inA = ids(inOrderService().search(wsAId, ownerId, "报销", null, 0, 10));
        assertTrue(inA.contains(docA), "embedding 为空时关键词检索仍应命中");
        assertFalse(inA.contains(docB), "不得返回其他工作空间的文档");

        List<UUID> inB = ids(inOrderService().search(wsBId, ownerId, "独有词汇丙", null, 0, 10));
        assertTrue(inB.contains(docB), "工作空间 B 内应能检索到自己的文档");
        assertFalse(inB.contains(docA), "不得串到工作空间 A");
    }

    private void indexThreeExpenseDocs(UUID workspaceId, UUID ownerId) {
        SearchIndexService service = inOrderService();
        service.index(workspaceId, null, null, "报销流程甲", "报销流程说明甲", "SOURCE", ownerId, false);
        service.index(workspaceId, null, null, "报销流程乙", "报销流程说明乙", "SOURCE", ownerId, false);
        service.index(workspaceId, null, null, "报销流程丙", "报销流程说明丙", "SOURCE", ownerId, false);
    }

    /** 保持 SQL 候选顺序，用于取基准顺序。 */
    private SearchIndexService inOrderService() {
        return serviceWith(new FixedReranker(aiProperties, json, false));
    }

    /** 反转候选顺序，用于验证 rerank 结果确实参与最终排序。 */
    private SearchIndexService reversedRerankService() {
        return serviceWith(new FixedReranker(aiProperties, json, true));
    }

    private SearchIndexService serviceWith(MaaSRerankerProvider reranker) {
        return new SearchIndexService(documentRepository, jdbcTemplate, embeddingProvider, reranker, aiProperties);
    }

    private static List<UUID> ids(List<SearchIndexService.Hit> hits) {
        return hits.stream().map(SearchIndexService.Hit::id).toList();
    }

    private static final class FixedReranker extends MaaSRerankerProvider {

        private final boolean reverse;

        FixedReranker(AiProperties properties, ObjectMapper objectMapper, boolean reverse) {
            super(properties, objectMapper);
            this.reverse = reverse;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, int topN) {
            List<RerankHit> hits = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                hits.add(new RerankHit(i, 0d));
            }
            if (reverse) {
                Collections.reverse(hits);
            }
            return hits;
        }
    }
}

package com.actionloop.api.integration;

import com.actionloop.api.ai.MaaSEmbeddingProvider;
import com.actionloop.api.service.SearchIndexService;
import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MaaS embedding + pgvector + rerank 集成：维度实测、索写入、混合检索不越权。 */
@TestPropertySource(properties = {
        "app.ai.provider=maas",
        "app.ai.model=spark-x2.6",
        "app.ai.embedding-enabled=true",
        "app.ai.rerank-enabled=true"
})
class HybridSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SearchIndexService searchIndexService;

    @Autowired
    private MaaSEmbeddingProvider embeddingProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void indexes768VectorAndHybridSearchReturnsAuthorizedDoc() {
        // 真实 MaaS 调用：无凭证时跳过而不是失败，保证 CI 在未配置密钥时仍可通过
        assumeTrue(embeddingProvider.available(), "MaaS embedding 凭证未配置，跳过真实向量检索测试");

        var owner = register();
        var outsider = register();
        String wsId = createWorkspace(owner.get("access"), "search-ws");
        UUID workspaceId = UUID.fromString(wsId);
        UUID ownerId = UUID.fromString(owner.get("userId"));

        float[] probe = embeddingProvider.embed("混合检索维度探测");
        assertEquals(768, probe.length, "MaaS embedding 必须可用且返回 768 维");

        UUID expenseDoc = searchIndexService.index(workspaceId, null, null,
                "季度报销截止提醒", "请在周五下班前提交差旅报销单，逾期不再受理。",
                "SOURCE", ownerId, false);

        Integer dims = jdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM search_documents WHERE id = ?", Integer.class, expenseDoc);
        assertEquals(768, dims, "pgvector 列必须与实测 embedding 维度一致");
        String modelVersion = jdbcTemplate.queryForObject(
                "SELECT model_version FROM search_documents WHERE id = ?", String.class, expenseDoc);
        assertEquals("xop3qwen8bembedding:768", modelVersion, "必须如实登记实际模型与维度，不得标成 BGE-M3");

        searchIndexService.index(workspaceId, null, null,
                "实验室组会安排", "周五下午三点在会议室召开组会，讨论下周实验计划。",
                "SOURCE", ownerId, false);

        Resp exact = req("GET", "/api/v1/memory/search?workspaceId=" + wsId + "&q=报销&limit=10",
                owner.get("access"), null, null);
        assertEquals(200, exact.status);
        assertTrue(exact.json.path("data").toString().contains("季度报销截止提醒"),
                "关键词召回应返回报销文档");

        Resp semantic = req("GET", "/api/v1/memory/search?workspaceId=" + wsId
                + "&q=差旅报销单什么时候交&limit=10", owner.get("access"), null, null);
        assertEquals(200, semantic.status);
        assertTrue(semantic.json.path("data").size() >= 1, "语义召回在关键词不完全相同时仍应返回候选");

        Resp forbidden = req("GET", "/api/v1/memory/search?workspaceId=" + wsId + "&q=报销&limit=10",
                outsider.get("access"), null, null);
        assertEquals(403, forbidden.status, "非成员不能检索其他空间向量/关键词索引");
    }
}

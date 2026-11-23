package com.actionloop.api.it;

import com.actionloop.api.ai.AiResultCacheService;
import com.actionloop.api.ai.CandidateExtraction;
import com.actionloop.api.ai.ExtractionRequest;
import com.actionloop.api.ai.QwenAiProvider;
import com.actionloop.api.ai.RuleExtractor;
import com.actionloop.api.domain.Job;
import com.actionloop.api.domain.SourceItem;
import com.actionloop.api.job.JobDispatcher;
import com.actionloop.api.repository.SourceItemRepository;
import com.actionloop.api.service.JobService;
import com.actionloop.api.test.AbstractIntegrationTest;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "app.ai.provider=maas",
        "app.ai.model=spark-x2.6",
        "app.worker.jobs.enabled=true",
        "app.worker.jobs.interval-ms=600000",
        "app.worker.jobs.backoff-base-millis=1"
})
class AiExtractionTransactionIntegrationTest extends AbstractIntegrationTest {

    private static final String TEXT = "2026年11月30日18:00前提交竞赛报名表，需要携带学生证。";

    @MockBean
    private QwenAiProvider provider;

    @Autowired
    private SourceItemRepository sourceRepository;

    @Autowired
    private AiResultCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobDispatcher dispatcher;

    @BeforeEach
    void selectProductionProviderAndClearJobs() {
        lenient().when(provider.name()).thenReturn("maas");
        lenient().when(provider.production()).thenReturn(true);
        jdbcTemplate.update("DELETE FROM job_payloads");
        jdbcTemplate.update("DELETE FROM jobs");
    }

    @Test
    void invalidAiOutputKeepsSourceAndDeadLetterRetryCanCreateCandidateAndCache() {
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("AI事务"));
        Map<String, Object> share = share(workspaceId);
        when(provider.extract(any())).thenThrow(new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "invalid"))
                .thenAnswer(this::validExtraction);

        Resp accepted = req("POST", "/api/v1/sources/share", owner.get("access"), share, null);

        assertEquals(200, accepted.status, accepted.raw);
        String sourceId = accepted.json.path("data").path("id").asText();
        assertEquals(0, accepted.json.path("data").path("candidateIds").size(), "云端抽取必须异步，响应不得等待候选");
        assertEquals("PENDING", state(sourceId));
        assertTrue(sourceRepository.findById(UUID.fromString(sourceId)).isPresent(),
                "AI 输出无效发生在异步任务中，来源事实不能回滚");

        assertTrue(dispatcher.dispatchOne());
        assertEquals("DEAD_LETTER", state(sourceId));
        String jobId = jobService.latestForReference(ref(sourceId)).map(Job::getId).map(UUID::toString).orElseThrow();

        Resp retried = req("POST", "/api/v1/jobs/" + jobId + "/retry?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(200, retried.status, retried.raw);
        assertEquals("PENDING", retried.json.path("data").path("state").asText());

        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", state(sourceId));

        Resp source = req("GET", "/api/v1/sources/" + sourceId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(1, source.json.path("data").path("candidateIds").size(), source.raw);
        Integer cacheRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_result_cache WHERE source_item_id = ?",
                Integer.class, UUID.fromString(sourceId));
        assertEquals(1, cacheRows, "异步任务成功后必须真实写入缓存");
        verify(provider, times(2)).extract(any());
    }

    @Test
    void deletingSourceInvalidatesPendingAiJobBeforeWorkerCanWriteCandidates() {
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("AI删除失效"));
        when(provider.extract(any())).thenAnswer(this::validExtraction);
        Resp shared = req("POST", "/api/v1/sources/share", owner.get("access"), share(workspaceId), null);
        assertEquals(200, shared.status, shared.raw);
        String sourceId = shared.json.path("data").path("id").asText();
        assertEquals("PENDING", state(sourceId));

        Resp deleted = req("DELETE", "/api/v1/sources/" + sourceId + "?workspaceId=" + workspaceId
                + "&baseVersion=" + shared.json.path("data").path("version").asInt(),
                owner.get("access"), null, null);
        assertEquals(200, deleted.status, deleted.raw);
        assertEquals("DEAD_LETTER", state(sourceId));
        assertEquals("SOURCE_DELETED", jobError(sourceId));
        assertEquals(0, payloadCount(sourceId));

        assertFalse(dispatcher.dispatchOne(), "已失效任务不应再被消费");
        assertEquals(0, sharedCandidateCount(workspaceId, sourceId, owner.get("access")));
    }

    @Test
    void committedCacheIsReadableOnlyWithSameWorkspaceSourceAndUser() {
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("AI缓存"));
        when(provider.extract(any())).thenAnswer(this::validExtraction);
        Resp shared = req("POST", "/api/v1/sources/share", owner.get("access"), share(workspaceId), null);
        assertEquals(200, shared.status, shared.raw);
        String sourceId = shared.json.path("data").path("id").asText();
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", state(sourceId));
        assertEquals(1, sharedCandidateCount(workspaceId, sourceId, owner.get("access")));

        UUID sourceUuid = UUID.fromString(sourceId);
        SourceItem source = sourceRepository.findById(sourceUuid).orElseThrow();
        ExtractionRequest request = new ExtractionRequest(TEXT, source.getReceivedAt(), "Asia/Shanghai",
                source.getSourceApp(), source.getSourceTitle(), source.getWorkspaceId(), sourceUuid,
                UUID.fromString(owner.get("userId")), "trace-cache-it");

        assertTrue(cacheService.find(request, "spark-x2.6", QwenAiProvider.PROMPT_VERSION,
                QwenAiProvider.SCHEMA_VERSION).isPresent());
        assertTrue(cacheService.find(copy(request, UUID.randomUUID(), sourceUuid, request.requestedBy()),
                "spark-x2.6", QwenAiProvider.PROMPT_VERSION, QwenAiProvider.SCHEMA_VERSION).isEmpty());
        assertTrue(cacheService.find(copy(request, request.workspaceId(), UUID.randomUUID(), request.requestedBy()),
                "spark-x2.6", QwenAiProvider.PROMPT_VERSION, QwenAiProvider.SCHEMA_VERSION).isEmpty());
        assertTrue(cacheService.find(copy(request, request.workspaceId(), sourceUuid, UUID.randomUUID()),
                "spark-x2.6", QwenAiProvider.PROMPT_VERSION, QwenAiProvider.SCHEMA_VERSION).isEmpty());

        cacheService.deleteForSource(request.workspaceId(), sourceUuid);
        assertTrue(cacheService.find(request, "spark-x2.6", QwenAiProvider.PROMPT_VERSION,
                QwenAiProvider.SCHEMA_VERSION).isEmpty());
    }

    private int sharedCandidateCount(String workspaceId, String sourceId, String token) {
        Resp source = req("GET", "/api/v1/sources/" + sourceId + "?workspaceId=" + workspaceId, token, null, null);
        return source.json.path("data").path("candidateIds").size();
    }

    private String state(String sourceId) {
        return jobService.latestForReference(ref(sourceId)).map(Job::getState).orElse("NONE");
    }

    private String jobError(String sourceId) {
        return jobService.latestForReference(ref(sourceId)).map(Job::getLastErrorCode).orElse(null);
    }

    private int payloadCount(String sourceId) {
        UUID jobId = jobService.latestForReference(ref(sourceId)).map(Job::getId).orElse(null);
        if (jobId == null) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM job_payloads WHERE job_id = ?",
                Integer.class, jobId);
        return count == null ? 0 : count;
    }

    private static String ref(String sourceId) {
        return "source:" + sourceId;
    }

    private CandidateExtraction validExtraction(InvocationOnMock invocation) {
        ExtractionRequest input = invocation.getArgument(0);
        CandidateExtraction rule = new RuleExtractor().extract(input);
        return new CandidateExtraction(rule.candidates(), "spark-x2.6", QwenAiProvider.PROMPT_VERSION,
                QwenAiProvider.SCHEMA_VERSION, "AI", List.of());
    }

    private static Map<String, Object> share(String workspaceId) {
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", workspaceId);
        share.put("sourceType", "NOTIFICATION");
        share.put("sourceApp", "com.actionloop.ai.transaction.it");
        share.put("sourceKey", "ai-transaction-" + System.nanoTime());
        share.put("title", "竞赛报名通知");
        share.put("text", TEXT);
        share.put("receivedAt", "2026-11-22T02:00:00Z");
        share.put("timezone", "Asia/Shanghai");
        share.put("localOnly", false);
        return share;
    }

    private static ExtractionRequest copy(ExtractionRequest request, UUID workspaceId, UUID sourceId, UUID userId) {
        return new ExtractionRequest(request.text(), request.receivedAt(), request.timezone(), request.sourceApp(),
                request.sourceTitle(), workspaceId, sourceId, userId, request.traceId());
    }
}

package com.actionloop.api.it;

import com.actionloop.api.ai.OcrProvider;
import com.actionloop.api.domain.Job;
import com.actionloop.api.job.JobDispatcher;
import com.actionloop.api.service.JobService;
import com.actionloop.api.service.PermissionService;
import com.actionloop.api.service.storage.FileStorageProvider;
import com.actionloop.api.test.AbstractIntegrationTest;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 真实 PG/Redis + 确定性 OCR Provider 的异步 API 集成；不冒充云端或离线模型正式评测。 */
@TestPropertySource(properties = {
        "app.ocr.provider=maas",
        "app.ocr.model=xoppaddleocrv16",
        "app.ocr.allowed-models=xoppaddleocrv16,xopdeepseekocr,xophunyuanocr",
        "app.worker.jobs.enabled=true",
        "app.worker.jobs.interval-ms=600000",
        "app.worker.jobs.backoff-base-millis=1"
})
class OcrIntegrationTest extends AbstractIntegrationTest {

    private static final byte[] IMAGE = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    @MockBean
    private OcrProvider provider;

    @SpyBean
    private FileStorageProvider storage;

    @SpyBean
    private PermissionService permissionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobDispatcher dispatcher;

    @BeforeEach
    void clearJobs() {
        jdbcTemplate.update("DELETE FROM job_payloads");
        jdbcTemplate.update("DELETE FROM jobs");
    }

    @Test
    void requiresExplicitCloudAuthorizationAndModelAllowlist() {
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-consent"));
        String assetId = upload(owner.get("access"), workspaceId);

        Resp denied = req("POST", ocrPath(assetId), owner.get("access"), body(workspaceId, false, null), null);
        assertEquals(403, denied.status, denied.raw);
        assertEquals("PERMISSION_DENIED", errCode(denied));

        Resp unknown = req("POST", ocrPath(assetId), owner.get("access"),
                body(workspaceId, true, "spark-x2.6"), null);
        assertEquals(400, unknown.status, unknown.raw);
        verifyNoInteractions(provider);

        Resp asset = req("GET", "/api/v1/assets/" + assetId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("PENDING", asset.json.path("data").path("scanState").asText());
    }

    @Test
    void successCachesPerModelAndPreservesOriginalObject() {
        when(provider.recognize(any())).thenAnswer(invocation -> {
            OcrProvider.OcrInput input = invocation.getArgument(0);
            return input.model().equals("xoppaddleocrv16") ? "课程设计PDF" : "第二模型文本";
        });
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-success"));
        String assetId = upload(owner.get("access"), workspaceId);

        Resp first = req("POST", ocrPath(assetId), owner.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, first.status, first.raw);
        assertEquals("PENDING", first.json.path("data").path("state").asText());
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", jobState(assetId));

        Resp firstResult = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("课程设计PDF", firstResult.json.path("data").path("text").asText());
        assertEquals("xoppaddleocrv16", firstResult.json.path("data").path("model").asText());
        assertFalse(firstResult.json.path("data").path("coordinatesAvailable").asBoolean(true));
        assertEquals(sha256(IMAGE), firstResult.json.path("data").path("inputSha256").asText());
        assertEquals(owner.get("userId"), firstResult.json.path("data").path("createdBy").asText());

        Resp cached = req("POST", ocrPath(assetId), owner.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, cached.status, cached.raw);
        assertEquals(first.json.path("data").path("id").asText(), cached.json.path("data").path("id").asText());
        verify(provider, times(1)).recognize(any());

        Resp second = req("POST", ocrPath(assetId), owner.get("access"),
                body(workspaceId, true, "xopdeepseekocr"), null);
        assertEquals(202, second.status, second.raw);
        assertTrue(dispatcher.dispatchOne());
        Resp secondResult = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId
                + "&model=xopdeepseekocr", owner.get("access"), null, null);
        assertEquals("第二模型文本", secondResult.json.path("data").path("text").asText());
        verify(provider, times(2)).recognize(any());

        assertEquals(2, count("SELECT count(*) FROM ocr_results WHERE asset_id = ?", assetId));
        assertEquals(0, count("SELECT count(*) FROM ocr_results WHERE asset_id = ? AND coordinates_available = true", assetId));

        Resp asset = req("GET", "/api/v1/assets/" + assetId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("INDEXED", asset.json.path("data").path("scanState").asText());
        assertArrayEquals(IMAGE, download(owner.get("access"), workspaceId, assetId));
    }

    @Test
    void providerFailurePersistsAndSuccessfulRetryRecovers() {
        doThrow(new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 服务暂不可用"))
                .when(provider).recognize(any());
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-failure"));
        String assetId = upload(owner.get("access"), workspaceId);

        Resp accepted = req("POST", ocrPath(assetId), owner.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, accepted.status, accepted.raw);
        String requestId = accepted.json.path("requestId").asText();
        assertTrue(dispatcher.dispatchOne());
        assertEquals("PENDING", jobState(assetId));
        assertEquals("OCR_UNAVAILABLE", jobError(assetId));
        Resp failedAsset = req("GET", "/api/v1/assets/" + assetId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("OCR_FAILED", failedAsset.json.path("data").path("scanState").asText());
        assertEquals("OCR_UNAVAILABLE", failedAsset.json.path("data").path("ocrFailureCode").asText());
        Integer failureAudits = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs "
                        + "WHERE object_id = CAST(? AS text) AND action = 'asset.ocr' "
                        + "AND result = 'FAILURE' AND request_id = ?", Integer.class,
                UUID.fromString(assetId), requestId);
        assertEquals(1, failureAudits);
        Resp missing = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(404, missing.status, missing.raw);
        assertArrayEquals(IMAGE, download(owner.get("access"), workspaceId, assetId));

        makeJobDueNow(assetId);
        reset(provider);
        when(provider.recognize(any())).thenReturn("重试后的文本");
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", jobState(assetId));
        Resp retry = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("重试后的文本", retry.json.path("data").path("text").asText());
        Resp recovered = req("GET", "/api/v1/assets/" + assetId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("INDEXED", recovered.json.path("data").path("scanState").asText());
        assertTrue(recovered.json.path("data").path("ocrFailureCode").isMissingNode()
                || recovered.json.path("data").path("ocrFailureCode").isNull());
    }

    @Test
    void storageRuntimeFailureIsMappedAndPersisted() throws Exception {
        var owner = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-storage"));
        String assetId = upload(owner.get("access"), workspaceId);
        doThrow(new IllegalStateException("simulated S3 SDK failure")).when(storage).getObject(anyString());

        Resp accepted = req("POST", ocrPath(assetId), owner.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, accepted.status, accepted.raw);
        assertTrue(dispatcher.dispatchOne());
        assertEquals("PENDING", jobState(assetId));
        assertEquals("OCR_UNAVAILABLE", jobError(assetId));

        Resp asset = req("GET", "/api/v1/assets/" + assetId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("OCR_FAILED", asset.json.path("data").path("scanState").asText());
        assertEquals("OCR_UNAVAILABLE", asset.json.path("data").path("ocrFailureCode").asText());
        verify(provider, never()).recognize(any());
    }

    @Test
    void revocationDuringProviderFailurePersistsEvidenceButJobFailsForbidden() throws Exception {
        var owner = register();
        var member = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-revoke-race"));
        String assetId = upload(owner.get("access"), workspaceId);
        invite(owner.get("access"), workspaceId, member.get("email"), "MEMBER");
        accept(member.get("access"), workspaceId);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.recognize(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "provider failed after revocation");
        });

        Resp accepted = req("POST", ocrPath(assetId), member.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, accepted.status, accepted.raw);
        CompletableFuture<Boolean> pending = CompletableFuture.supplyAsync(dispatcher::dispatchOne);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Resp removed = req("DELETE", "/api/v1/workspaces/" + workspaceId + "/members/" + member.get("userId"),
                    owner.get("access"), null, null);
            assertEquals(200, removed.status, removed.raw);
        } finally {
            release.countDown();
        }
        assertTrue(pending.get(10, TimeUnit.SECONDS));

        assertEquals("DEAD_LETTER", jobState(assetId));
        assertEquals("PERMISSION_DENIED", jobError(assetId));
        Resp asset = req("GET", "/api/v1/assets/" + assetId + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals("OCR_FAILED", asset.json.path("data").path("scanState").asText());
        assertEquals("OCR_UNAVAILABLE", asset.json.path("data").path("ocrFailureCode").asText());
    }

    @Test
    void successCommitAndRevocationAreSerializedByMembershipLock() throws Exception {
        when(provider.recognize(any())).thenReturn("串行化后的文本");
        var owner = register();
        var member = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-revoke-success"));
        String assetId = upload(owner.get("access"), workspaceId);
        invite(owner.get("access"), workspaceId, member.get("email"), "MEMBER");
        accept(member.get("access"), workspaceId);
        CountDownLatch memberLocked = new CountDownLatch(1);
        CountDownLatch finishOcr = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object membership = invocation.callRealMethod();
            memberLocked.countDown();
            assertTrue(finishOcr.await(5, TimeUnit.SECONDS));
            return membership;
        }).when(permissionService).requireActiveMemberForUpdate(
                UUID.fromString(workspaceId), UUID.fromString(member.get("userId")));

        Resp accepted = req("POST", ocrPath(assetId), member.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, accepted.status, accepted.raw);
        CompletableFuture<Boolean> pending = CompletableFuture.supplyAsync(dispatcher::dispatchOne);
        CompletableFuture<Resp> removal;
        try {
            assertTrue(memberLocked.await(5, TimeUnit.SECONDS));
            removal = CompletableFuture.supplyAsync(() -> req("DELETE",
                    "/api/v1/workspaces/" + workspaceId + "/members/" + member.get("userId"),
                    owner.get("access"), null, null));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> removal.get(250, TimeUnit.MILLISECONDS));
        } finally {
            finishOcr.countDown();
        }

        assertTrue(pending.get(10, TimeUnit.SECONDS));
        Resp removed = removal.get(10, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", jobState(assetId));
        assertEquals(200, removed.status, removed.raw);
        assertEquals(1, count("SELECT count(*) FROM ocr_results WHERE asset_id = ?", assetId));
        Resp ownerRead = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(200, ownerRead.status, ownerRead.raw);
    }

    @Test
    void crossWorkspaceAndRevokedMemberCannotRunOrReadOcr() {
        when(provider.recognize(any())).thenReturn("空间内文本");
        var owner = register();
        var outsider = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("ocr-acl"));
        String otherWorkspace = createWorkspace(outsider.get("access"), unique("ocr-other"));
        String assetId = upload(owner.get("access"), workspaceId);

        Resp cross = req("POST", ocrPath(assetId), outsider.get("access"), body(otherWorkspace, true, null), null);
        assertEquals(404, cross.status, cross.raw);
        Resp nonMember = req("POST", ocrPath(assetId), outsider.get("access"), body(workspaceId, true, null), null);
        assertEquals(403, nonMember.status, nonMember.raw);

        invite(owner.get("access"), workspaceId, outsider.get("email"), "MEMBER");
        accept(outsider.get("access"), workspaceId);
        Resp allowed = req("POST", ocrPath(assetId), outsider.get("access"), body(workspaceId, true, null), null);
        assertEquals(202, allowed.status, allowed.raw);
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", jobState(assetId));

        Resp removed = req("DELETE", "/api/v1/workspaces/" + workspaceId + "/members/" + outsider.get("userId"),
                owner.get("access"), null, null);
        assertEquals(200, removed.status, removed.raw);
        Resp readAfterRevoke = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId,
                outsider.get("access"), null, null);
        assertEquals(403, readAfterRevoke.status, readAfterRevoke.raw);
        Resp runAfterRevoke = req("POST", ocrPath(assetId), outsider.get("access"), body(workspaceId, true, null), null);
        assertEquals(403, runAfterRevoke.status, runAfterRevoke.raw);
        verify(provider, times(1)).recognize(any());

        Resp ownerCanRead = req("GET", ocrPath(assetId) + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(200, ownerCanRead.status, ownerCanRead.raw);
    }

    private String jobState(String assetId) {
        return jobService.latestForReference(ref(assetId)).map(Job::getState).orElse("NONE");
    }

    private String jobError(String assetId) {
        return jobService.latestForReference(ref(assetId)).map(Job::getLastErrorCode).orElse(null);
    }

    private void makeJobDueNow(String assetId) {
        UUID jobId = jobService.latestForReference(ref(assetId)).map(Job::getId).orElseThrow();
        jdbcTemplate.update("UPDATE jobs SET available_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), jobId);
    }

    private static String ref(String assetId) {
        return "asset:" + assetId;
    }

    private String upload(String token, String workspaceId) {
        String sha = sha256(IMAGE);
        Resp init = req("POST", "/api/v1/assets/uploads", token,
                Map.of("workspaceId", workspaceId, "sha256", sha, "sizeBytes", IMAGE.length,
                        "mime", "image/png", "fileName", "notice.png"), null);
        assertEquals(200, init.status, init.raw);
        String sessionId = init.json.path("data").path("sessionId").asText();
        Resp put = putBytes("/api/v1/assets/uploads/" + sessionId + "/content", token, IMAGE);
        assertEquals(200, put.status, put.raw);
        Resp complete = req("POST", "/api/v1/assets/uploads/" + sessionId + "/complete", token,
                Map.of("sha256", sha, "sizeBytes", IMAGE.length), null);
        assertEquals(200, complete.status, complete.raw);
        return complete.json.path("data").path("id").asText();
    }

    private byte[] download(String token, String workspaceId, String assetId) {
        Resp url = req("GET", "/api/v1/assets/" + assetId + "/download-url?workspaceId=" + workspaceId,
                token, null, null);
        assertEquals(200, url.status, url.raw);
        ResponseEntity<byte[]> response = http.getForEntity(URI.create(url.json.path("data").asText()), byte[].class);
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    private int count(String sql, String assetId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, UUID.fromString(assetId));
        return value == null ? 0 : value;
    }

    private static Map<String, Object> body(String workspaceId, boolean authorized, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspaceId);
        body.put("cloudAuthorized", authorized);
        if (model != null) {
            body.put("model", model);
        }
        return body;
    }

    private static String ocrPath(String assetId) {
        return "/api/v1/assets/" + assetId + "/ocr";
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

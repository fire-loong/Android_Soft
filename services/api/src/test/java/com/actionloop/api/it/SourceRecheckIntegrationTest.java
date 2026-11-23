package com.actionloop.api.it;

import com.actionloop.api.service.SourceFetchService;
import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * C-05 来源变更（指导书 17/26 章）：
 * 来源复查检测到内容版本变化时，必须新增证据并明确报告差异，
 * 但不得篡改已确认事项的标题/截止时间/版本。
 *
 * <p>抓取器以测试替身注入，保证断言确定可重复；真实公网 https 抓取未在 CI 验证。</p>
 */
class SourceRecheckIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private SourceFetchService sourceFetchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void recheckDetectsNewVersionWithoutMutatingConfirmedTask() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("来源复查空间"));
        String link = "https://example.edu/notice/" + System.nanoTime();

        String original = "请于2027年12月30日18:00前提交助学金申请表";
        JsonNode source = shareWithLink(token, ws, link, original);
        String sourceId = source.path("id").asText();
        assertEquals(1, source.path("evidence").size(), "首次导入只有一版证据");
        String candidateId = source.path("candidateIds").get(0).asText();

        JsonNode candidate = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws,
                token, null, null).json.path("data");
        Resp confirm = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                Map.of("baseVersion", candidate.path("version").asInt(), "dueDateConfirmed", true),
                unique("conf"));
        assertEquals(200, confirm.status, confirm.raw);
        String taskId = confirm.json.path("data").path("taskId").asText();

        Resp taskBeforeResp = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws, token, null, null);
        assertEquals(200, taskBeforeResp.status, taskBeforeResp.raw);
        JsonNode taskBefore = taskBeforeResp.json.path("data");

        String updated = original + "（官方通知已更新，材料增加身份证复印件）";
        when(sourceFetchService.fetch(anyString())).thenReturn(new SourceFetchService.Fetched(
                link, updated, sha256(updated), 200, "text/html"));

        Resp recheck = req("POST", "/api/v1/sources/" + sourceId + "/recheck?workspaceId=" + ws,
                token, null, null);
        assertEquals(200, recheck.status, recheck.raw);
        assertTrue(recheck.json.path("data").path("changed").asBoolean(), "内容变化必须被检测到");
        assertTrue(recheck.json.path("data").path("requiresReconfirmation").asBoolean(),
                "来源变更后必须要求用户重新确认关键日期");
        assertFalse(recheck.json.path("data").path("previousHash").asText("").isBlank());
        assertTrue(recheck.json.path("data").path("preview").asText("").contains("官方通知已更新"),
                "复查后必须展示新版本内容");

        Resp sourceAfter = req("GET", "/api/v1/sources/" + sourceId + "?workspaceId=" + ws, token, null, null);
        assertEquals(200, sourceAfter.status, sourceAfter.raw);
        assertEquals(2, sourceAfter.json.path("data").path("evidence").size(),
                "新版本必须追加证据，旧版本证据保留");

        JsonNode taskAfter = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws, token, null, null)
                .json.path("data");
        assertEquals(taskBefore.path("title").asText(), taskAfter.path("title").asText(),
                "来源复查不得篡改历史事项标题");
        assertEquals(taskBefore.path("dueAt").asText(), taskAfter.path("dueAt").asText(),
                "来源复查不得篡改历史事项截止时间");
        assertEquals(taskBefore.path("version").asInt(), taskAfter.path("version").asInt(),
                "来源复查不得推进历史事项版本");

        Resp tasks = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(1, tasks.json.path("data").path("content").size(), "来源复查不得自动新建事项");
    }

    @Test
    void recheckWithoutLinkIsRejectedExplicitly() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("无链接来源空间"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", ws);
        body.put("sourceType", "NOTIFICATION");
        body.put("sourceApp", "com.example.scheduler");
        body.put("sourceKey", unique("key"));
        body.put("text", "请于2027年12月30日18:00前提交竞赛报名表");
        body.put("localOnly", true);
        Resp share = req("POST", "/api/v1/sources/share", token, body, null);
        assertEquals(200, share.status, share.raw);
        String sourceId = share.json.path("data").path("id").asText();

        Resp recheck = req("POST", "/api/v1/sources/" + sourceId + "/recheck?workspaceId=" + ws,
                token, null, null);
        assertEquals(400, recheck.status, recheck.raw);
        assertEquals("VALIDATION_FAILED", errCode(recheck), "无链接来源复查必须给出可解释的拒绝");
    }

    @Test
    void blockedSsrfFetchIsRecordedAsSecurityEvent() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("SSRF 来源空间"));
        String link = "https://example.edu/notice/" + System.nanoTime();
        JsonNode source = shareWithLink(token, ws, link, "请于2027年12月30日18:00前提交助学金申请表");
        String sourceId = source.path("id").asText();

        when(sourceFetchService.fetch(anyString())).thenThrow(new SourceFetchService.BlockedException(
                "internal_address", "来源链接指向内网地址，已拒绝", "example.edu -> 10.0.0.1"));

        Resp recheck = req("POST", "/api/v1/sources/" + sourceId + "/recheck?workspaceId=" + ws,
                token, null, null);
        assertEquals(400, recheck.status, recheck.raw);
        assertEquals("VALIDATION_FAILED", errCode(recheck));

        Integer blockedEvents = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_logs
                 WHERE action = 'source.fetch.blocked' AND object_id = ? AND result = 'DENIED'
                """, Integer.class, sourceId);
        assertEquals(1, blockedEvents, "SSRF 拒绝必须写入安全事件审计");

        String metadata = jdbcTemplate.queryForObject("""
                SELECT metadata::text FROM audit_logs
                 WHERE action = 'source.fetch.blocked' AND object_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, sourceId);
        assertTrue(metadata.contains("internal_address"), metadata);
        assertTrue(metadata.contains("10.0.0.1"), metadata);

        String state = jdbcTemplate.queryForObject(
                "SELECT source_state FROM source_items WHERE id = ?", String.class, UUID.fromString(sourceId));
        assertEquals("FETCH_FAILED", state, "被拒绝的来源不得被标记为可用");
    }

    private JsonNode shareWithLink(String token, String ws, String link, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", ws);
        body.put("sourceType", "LINK");
        body.put("sourceApp", "web");
        body.put("sourceKey", unique("key"));
        body.put("title", "官方助学金通知");
        body.put("text", text);
        body.put("linkUrl", link);
        body.put("timezone", "Asia/Shanghai");
        body.put("localOnly", true);
        Resp share = req("POST", "/api/v1/sources/share", token, body, null);
        assertEquals(200, share.status, share.raw);
        return share.json.path("data");
    }

    private static String sha256(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

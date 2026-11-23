package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据回溯 + 完成核验队列（指导书 3.1 / 9.2 / WP07 / WP13）。
 * 覆盖：事项来源引文、提交凭证、跨事项待核验、驳回必须填理由、通过后步骤完成、无权限隔离。
 */
class ReviewEvidenceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void evidenceTraceAndWorkspaceReviewQueue() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("核验空间"));

        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", ws);
        share.put("sourceType", "NOTIFICATION");
        share.put("sourceApp", "com.example.scheduler");
        share.put("sourceKey", unique("key"));
        share.put("title", "助学金申请通知");
        share.put("text", "明天下午3点前提交助学金申请表，需要携带学生证和成绩单");
        share.put("localOnly", true);
        Resp shareResp = req("POST", "/api/v1/sources/share", token, share, null);
        assertEquals(200, shareResp.status, shareResp.raw);
        String candidateId = shareResp.json.path("data").path("candidateIds").get(0).asText();

        Resp cand = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws, token, null, null);
        int version = cand.json.path("data").path("version").asInt();
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("baseVersion", version);
        confirm.put("dueDateConfirmed", true);
        confirm.put("steps", List.of("打印并填写申请表", "到学生事务中心提交"));
        Resp confirmResp = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token, confirm, unique("conf"));
        assertEquals(200, confirmResp.status, confirmResp.raw);
        String taskId = confirmResp.json.path("data").path("taskId").asText();

        // 事项证据回溯：必须返回来源关系与原文引文
        Resp sources = req("GET", "/api/v1/tasks/" + taskId + "/sources?workspaceId=" + ws, token, null, null);
        assertEquals(200, sources.status, sources.raw);
        JsonNode links = sources.json.path("data");
        assertTrue(links.size() >= 1, "事项必须能回溯到来源");
        boolean hasOrigin = false;
        for (JsonNode link : links) {
            if ("ORIGIN".equals(link.path("relationType").asText())) {
                hasOrigin = true;
                assertFalse(link.path("quote").asText("").isBlank(), "来源关系必须保留原文引文");
                assertEquals("USER_PROVIDED", link.path("sourceState").asText(), "通知来源不得冒充官方");
            }
        }
        assertTrue(hasOrigin, "必须存在 ORIGIN 来源关系");

        // 提交完成凭证 → 进入待核验
        Resp steps = req("GET", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws, token, null, null);
        assertEquals(200, steps.status, steps.raw);
        String stepId = steps.json.path("data").get(0).path("id").asText();
        Resp submit = req("POST", "/api/v1/steps/" + stepId + "/completions?workspaceId=" + ws, token,
                Map.of("statement", "已提交并拿到回执"), unique("comp"));
        assertEquals(200, submit.status, submit.raw);
        String completionId = submit.json.path("data").path("id").asText();
        assertEquals("PENDING", submit.json.path("data").path("reviewState").asText());

        Resp queue = req("GET", "/api/v1/workspaces/" + ws + "/completions/pending", token, null, null);
        assertEquals(200, queue.status, queue.raw);
        assertEquals(1, queue.json.path("data").size(), "待核验队列必须包含刚提交的凭证");
        assertEquals(taskId, queue.json.path("data").get(0).path("taskId").asText());
        assertEquals("打印并填写申请表", queue.json.path("data").get(0).path("stepTitle").asText());

        // 驳回必须填写理由
        Resp badReject = req("POST", "/api/v1/completions/" + completionId + "/review", token,
                Map.of("approve", false), unique("rev0"));
        assertEquals(400, badReject.status, badReject.raw);

        // 通过后步骤完成、队列清空
        Resp approve = req("POST", "/api/v1/completions/" + completionId + "/review", token,
                Map.of("approve", true, "reason", "回执齐全"), unique("rev1"));
        assertEquals(200, approve.status, approve.raw);
        assertEquals("APPROVED", approve.json.path("data").path("reviewState").asText());

        Resp stepsAfter = req("GET", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws, token, null, null);
        assertEquals("DONE", stepsAfter.json.path("data").get(0).path("status").asText());

        Resp queueAfter = req("GET", "/api/v1/workspaces/" + ws + "/completions/pending", token, null, null);
        assertEquals(0, queueAfter.json.path("data").size(), "核验后不得继续出现在待核验队列");

        // 审计必须记录提交与核验
        Resp audit = req("GET", "/api/v1/audit?workspaceId=" + ws + "&objectType=completion&limit=50", token, null, null);
        assertEquals(200, audit.status, audit.raw);
        assertTrue(audit.json.path("data").path("items").size() >= 2, "提交与核验都应写入审计");

        // 非成员不得读取事项来源或待核验队列
        Map<String, String> outsider = register();
        String outsiderToken = outsider.get("access");
        Resp denied = req("GET", "/api/v1/tasks/" + taskId + "/sources?workspaceId=" + ws, outsiderToken, null, null);
        assertTrue(denied.status == 403 || denied.status == 404, "非成员必须被拒绝: " + denied.status);
        Resp deniedQueue = req("GET", "/api/v1/workspaces/" + ws + "/completions/pending", outsiderToken, null, null);
        assertTrue(deniedQueue.status == 403 || deniedQueue.status == 404, "非成员不得读取核验队列");
    }
}

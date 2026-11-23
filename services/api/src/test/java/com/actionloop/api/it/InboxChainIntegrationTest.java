package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主链路集成测试（指导书 2.x / WP04 / WP05）：
 * 信息进入 → 原文证据 → 规则候选 → 用户确认 → 统一事项（步骤/材料/提醒/来源关系）。
 * 只走真实 HTTP + 真实数据库，不使用固定返回值。
 */
class InboxChainIntegrationTest extends AbstractIntegrationTest {

    @Test
    void shareSourceProducesEvidenceCandidateAndConfirmedTask() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("宿舍"));

        String text = "明天下午3点前提交助学金申请表，需要携带学生证和成绩单，地点：学生事务中心";
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", ws);
        share.put("sourceType", "NOTIFICATION");
        share.put("sourceApp", "com.example.scheduler");
        share.put("sourceKey", unique("key"));
        share.put("title", "助学金申请通知");
        share.put("text", text);
        share.put("timezone", "Asia/Shanghai");
        share.put("localOnly", true);
        Resp shareResp = req("POST", "/api/v1/sources/share", token, share, null);
        assertEquals(200, shareResp.status, shareResp.raw);

        JsonNode source = shareResp.json.path("data");
        String sourceId = source.path("id").asText();
        assertFalse(sourceId.isBlank(), "来源必须落库");
        assertTrue(source.path("evidence").size() >= 1, "来源必须生成可回溯的证据引用");
        assertTrue(source.path("candidateIds").size() >= 1, "规则抽取必须产出候选");

        // 原文可回放：证据引用必须带原文片段
        JsonNode evidence = source.path("evidence").get(0);
        assertNotNull(evidence.path("quote").asText(null), "证据必须保留原文引文");

        String candidateId = source.path("candidateIds").get(0).asText();
        Resp getCand = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws, token, null, null);
        assertEquals(200, getCand.status, getCand.raw);
        JsonNode candidate = getCand.json.path("data");
        assertEquals("PENDING", candidate.path("status").asText());
        assertFalse(candidate.path("title").asText().isBlank(), "候选必须有标题");
        assertTrue(candidate.path("sourceItemId").asText().equals(sourceId), "候选必须指回来源");
        assertTrue(candidate.path("confidence").asDouble() > 0, "候选必须带置信度");
        assertFalse(candidate.path("fieldConfidenceJson").asText("").isBlank(), "候选必须带逐字段置信");

        // 未确认前不得产生任何提醒
        Resp beforeList = req("GET", "/api/v1/inbox/candidates?workspaceId=" + ws + "&status=PENDING", token, null, null);
        assertEquals(200, beforeList.status);
        assertEquals(1, beforeList.json.path("data").path("items").size());

        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("baseVersion", candidate.path("version").asInt());
        confirm.put("dueDateConfirmed", true);
        confirm.put("notes", "已与辅导员核对");
        confirm.put("steps", List.of("打印并填写申请表", "到学生事务中心提交"));
        confirm.put("materials", List.of("学生证", "成绩单"));
        confirm.put("reminders", List.of(Map.of(
                "triggerAt", Instant.now().plus(2, ChronoUnit.HOURS).toString(),
                "channel", "NOTIFICATION")));
        Resp confirmResp = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token, confirm, unique("conf"));
        assertEquals(200, confirmResp.status, confirmResp.raw);
        JsonNode confirmed = confirmResp.json.path("data");
        String taskId = confirmed.path("taskId").asText();
        assertEquals(2, confirmed.path("stepCount").asInt());
        assertTrue(confirmed.path("reminderCount").asInt() >= 1, "确认时创建的提醒必须落库");
        assertTrue(confirmed.path("evidenceRefIds").size() >= 1, "确认后事项仍保留证据引用");

        Resp taskResp = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws, token, null, null);
        assertEquals(200, taskResp.status, taskResp.raw);
        JsonNode task = taskResp.json.path("data");
        assertEquals("ACTIVE", task.path("status").asText());
        assertEquals("USER_CONFIRMED_AI", task.path("derivation").asText());
        assertFalse(task.path("dueAt").isNull(), "确认后事项必须有截止时间");

        // 重复确认必须失败（不可重复创建事项）
        Resp again = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token, confirm, unique("conf"));
        assertEquals(409, again.status, again.raw);

        Resp candAfter = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws, token, null, null);
        assertEquals("CONFIRMED", candAfter.json.path("data").path("status").asText());
        assertEquals(taskId, candAfter.json.path("data").path("confirmedTaskId").asText());
    }

    @Test
    void dismissCandidateNeverCreatesTaskOrReminder() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("小组"));

        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", ws);
        share.put("sourceType", "SHARE");
        share.put("sourceApp", "com.example.chat");
        share.put("sourceKey", unique("key"));
        share.put("text", "周五前把实验报告发到群里");
        share.put("localOnly", true);
        Resp shareResp = req("POST", "/api/v1/sources/share", token, share, null);
        assertEquals(200, shareResp.status, shareResp.raw);
        String candidateId = shareResp.json.path("data").path("candidateIds").get(0).asText();

        Resp cand = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws, token, null, null);
        int version = cand.json.path("data").path("version").asInt();

        Resp dismiss = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/dismiss", token,
                Map.of("baseVersion", version, "reason", "不是我要办的事"), unique("dis"));
        assertEquals(200, dismiss.status, dismiss.raw);
        assertEquals("DISMISSED", dismiss.json.path("data").path("status").asText());

        Resp tasks = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(200, tasks.status);
        assertEquals(0, tasks.json.path("data").path("content").size(), "忽略候选不得创建事项");
    }

    @Test
    void lowConfidenceDateRequiresExplicitConfirmation() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("模糊"));

        // 含两个不同日期：规则必须标记 MULTIPLE_DATES，确认时必须显式确认日期
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", ws);
        share.put("sourceType", "NOTIFICATION");
        share.put("sourceApp", "com.example.scheduler");
        share.put("sourceKey", unique("key"));
        share.put("text", "11月25日上午9点提交报名表，11月27日下午3点提交缴费单");
        share.put("localOnly", true);
        Resp shareResp = req("POST", "/api/v1/sources/share", token, share, null);
        assertEquals(200, shareResp.status, shareResp.raw);
        String candidateId = shareResp.json.path("data").path("candidateIds").get(0).asText();

        Resp cand = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws, token, null, null);
        int version = cand.json.path("data").path("version").asInt();
        String reasons = cand.json.path("data").path("reasonsJson").asText("");
        assertTrue(reasons.contains("MULTIPLE_DATES"), "多日期必须被规则识别: " + reasons);

        Resp unconfirmed = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                Map.of("baseVersion", version), unique("conf"));
        assertEquals(200, unconfirmed.status, unconfirmed.raw);
        // 多日期无法自动确定截止时间：不得静默挑选一个日期，也不得生成强提醒
        assertEquals(0, unconfirmed.json.path("data").path("reminderCount").asInt());
        Resp taskResp = req("GET", "/api/v1/tasks/" + unconfirmed.json.path("data").path("taskId").asText()
                + "?workspaceId=" + ws, token, null, null);
        assertEquals(200, taskResp.status);
        // null 字段按 non_null 序列化策略会被省略，缺失等价于 null
        JsonNode undated = taskResp.json.path("data").path("dueAt");
        assertTrue(undated.isMissingNode() || undated.isNull(), "多日期候选不得自动写入截止时间");
    }
}

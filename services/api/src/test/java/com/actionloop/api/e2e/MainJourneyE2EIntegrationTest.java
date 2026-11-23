package com.actionloop.api.e2e;

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
 * WP14 主链路端到端集成（指导书 17.3 / 19 / 26.1）：
 * 来源 → 证据 → 规则候选 → 用户确认 → 事项/步骤/材料/提醒 → 完成凭证 → 核验 → 个人记忆。
 * 全部通过真实 HTTP + 真实 PostgreSQL/Redis 执行，不使用桩返回值；无基础设施时由基类跳过。
 * 负向覆盖：权限撤销、低置信日期必须显式确认、来源删除后候选不可再确认。
 */
class MainJourneyE2EIntegrationTest extends AbstractIntegrationTest {

    /** 唯一的检索关键词，避免不同测试空间/来源互相命中。 */
    private static final String SOURCE_TEXT =
            "请于11月30日18:00前在课程平台提交课程设计PDF，需要携带学生证和成绩单，地点：教务处";

    @Test
    void sourceToVerifiedCompletionAndPersonalMemory() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("主链路空间"));

        // 1) 信息进入 → 原文证据 → 规则候选（localOnly=true 走确定性规则，不依赖云端）
        JsonNode source = shareSource(token, ws, SOURCE_TEXT);
        String sourceId = source.path("id").asText();
        String candidateId = source.path("candidateIds").get(0).asText();
        assertFalse(sourceId.isBlank(), "来源必须落库");
        assertTrue(source.path("evidence").size() >= 1, "来源必须生成可回溯证据");
        assertFalse(source.path("evidence").get(0).path("quote").asText("").isBlank(),
                "证据必须保留原文引文");

        JsonNode candidate = getCandidate(token, ws, candidateId);
        assertEquals("PENDING", candidate.path("status").asText(), "AI/规则只产候选，不得自动确认");
        assertEquals("RULE", candidate.path("derivation").asText(), "localOnly 抽取必须是规则来源");
        assertTrue(candidate.path("needsUserConfirmation").asBoolean(), "候选必须要求用户确认");
        assertFalse(candidate.path("fieldConfidenceJson").asText("").isBlank(), "候选必须带逐字段置信");
        assertEquals(sourceId, candidate.path("sourceItemId").asText(), "候选必须指回来源");

        // 未确认前不得产生事项与提醒
        Resp noTaskYet = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(0, noTaskYet.json.path("data").path("content").size(), "未确认候选不得创建事项");

        // 2) 用户确认 → 事项/步骤/材料/提醒 + 来源关系
        int version = candidate.path("version").asInt();
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("baseVersion", version);
        confirm.put("dueDateConfirmed", true);
        confirm.put("notes", "已与教务处电话核对");
        confirm.put("steps", List.of("打印并填写课程设计封面", "在课程平台上传 PDF"));
        confirm.put("materials", List.of("学生证", "成绩单"));
        confirm.put("reminders", List.of(Map.of(
                "triggerAt", Instant.now().plus(2, ChronoUnit.HOURS).toString(),
                "channel", "NOTIFICATION")));
        Resp confirmResp = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                confirm, unique("conf"));
        assertEquals(200, confirmResp.status, confirmResp.raw);
        JsonNode confirmed = confirmResp.json.path("data");
        String taskId = confirmed.path("taskId").asText();
        assertEquals(2, confirmed.path("stepCount").asInt(), "确认必须创建全部步骤");
        assertEquals(1, confirmed.path("reminderCount").asInt(), "确认必须创建显式提醒");
        assertTrue(confirmed.path("evidenceRefIds").size() >= 1, "确认后事项保留证据引用");

        JsonNode task = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws, token, null, null)
                .json.path("data");
        assertEquals("ACTIVE", task.path("status").asText());
        assertEquals("USER_CONFIRMED_AI", task.path("derivation").asText(), "以用户确认的 AI/规则候选派生");
        assertFalse(task.path("dueAt").isNull(), "用户已确认日期，事项必须有截止时间");

        Resp steps = req("GET", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws, token, null, null);
        assertEquals(200, steps.status, steps.raw);
        assertEquals(2, steps.json.path("data").size(), "步骤必须落库");
        String firstStepId = steps.json.path("data").get(0).path("id").asText();

        Resp materials = req("GET", "/api/v1/tasks/" + taskId + "/materials?workspaceId=" + ws, token, null, null);
        assertEquals(200, materials.status, materials.raw);
        assertEquals(2, materials.json.path("data").size(), "材料必须落库");

        Resp reminders = req("GET", "/api/v1/tasks/" + taskId + "/reminders?workspaceId=" + ws, token, null, null);
        assertEquals(200, reminders.status, reminders.raw);
        assertEquals(1, reminders.json.path("data").size(), "提醒必须落库且不被静默丢弃");

        Resp taskSources = req("GET", "/api/v1/tasks/" + taskId + "/sources?workspaceId=" + ws, token, null, null);
        assertEquals(200, taskSources.status, taskSources.raw);
        JsonNode links = taskSources.json.path("data");
        boolean hasOrigin = false;
        for (JsonNode link : links) {
            if ("ORIGIN".equals(link.path("relationType").asText())) {
                hasOrigin = true;
                assertFalse(link.path("quote").asText("").isBlank(), "来源关系必须带原文引文");
                assertEquals("USER_PROVIDED", link.path("sourceState").asText(), "通知不得冒充官方来源");
            }
        }
        assertTrue(hasOrigin, "事项必须能回溯到原始来源");

        // 3) 完成凭证 → 待核验 → 核验通过 → 步骤完成
        Resp submit = req("POST", "/api/v1/steps/" + firstStepId + "/completions?workspaceId=" + ws, token,
                Map.of("statement", "已打印并用印，回执已拍照留档"), unique("comp"));
        assertEquals(200, submit.status, submit.raw);
        String completionId = submit.json.path("data").path("id").asText();
        assertEquals("PENDING", submit.json.path("data").path("reviewState").asText(),
                "完成凭证必须进入待核验，不得自动通过");

        Resp queue = req("GET", "/api/v1/workspaces/" + ws + "/completions/pending", token, null, null);
        assertEquals(200, queue.status, queue.raw);
        assertEquals(1, queue.json.path("data").size(), "待核验队列必须包含刚提交的凭证");

        Resp approve = req("POST", "/api/v1/completions/" + completionId + "/review", token,
                Map.of("approve", true, "reason", "回执齐全"), unique("rev"));
        assertEquals(200, approve.status, approve.raw);
        assertEquals("APPROVED", approve.json.path("data").path("reviewState").asText());

        Resp stepsAfter = req("GET", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws, token, null, null);
        assertEquals("DONE", stepsAfter.json.path("data").get(0).path("status").asText(),
                "核验通过后步骤必须完成");

        // 4) 个人记忆：检索命中 + 情境重现关联到事项与原文证据
        Resp search = req("GET", "/api/v1/memory/search?workspaceId=" + ws + "&q=课程设计",
                token, null, null);
        assertEquals(200, search.status, search.raw);
        JsonNode hits = search.json.path("data").path("items");
        assertTrue(hits.size() >= 1, "导入的来源必须可被个人记忆检索到");
        JsonNode hit = null;
        for (JsonNode h : hits) {
            if (h.path("sourceItemId").asText().equals(sourceId)) {
                hit = h;
                break;
            }
        }
        assertNotNull(hit, "检索必须返回刚导入来源对应的文档");
        String documentId = hit.path("documentId").asText();

        Resp context = req("GET", "/api/v1/memory/" + documentId + "/context?workspaceId=" + ws,
                token, null, null);
        assertEquals(200, context.status, context.raw);
        JsonNode ctx = context.json.path("data");
        assertTrue(ctx.path("evidence").size() >= 1, "情境重现必须带原文证据");
        boolean related = false;
        for (JsonNode t : ctx.path("relatedTasks")) {
            if (taskId.equals(t.path("id").asText())) {
                related = true;
            }
        }
        assertTrue(related, "情境重现必须关联已确认事项");

        // 5) 审计：确认、提交、核验均可追溯
        Resp audit = req("GET", "/api/v1/audit?workspaceId=" + ws + "&objectType=completion&limit=50",
                token, null, null);
        assertEquals(200, audit.status, audit.raw);
        JsonNode auditItems = audit.json.path("data").path("items");
        assertTrue(auditItems.size() >= 2, "提交与核验必须写入审计");
        boolean sawSubmit = false;
        boolean sawReview = false;
        for (JsonNode a : auditItems) {
            String action = a.path("action").asText();
            if ("completion.submit".equals(action)) {
                sawSubmit = true;
            }
            if ("completion.review".equals(action)) {
                sawReview = true;
            }
        }
        assertTrue(sawSubmit, "审计必须包含 completion.submit");
        assertTrue(sawReview, "审计必须包含 completion.review");
    }

    @Test
    void lowConfidenceDateRequiresExplicitConfirmationBeforeCreatingTask() throws Exception {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("低置信空间"));

        // 两个不同日期 → 规则不得自动挑选截止时间（MULTIPLE_DATES）
        JsonNode source = shareSource(token, ws, "11月25日上午9点提交报名表，11月27日下午3点提交缴费单");
        String candidateId = source.path("candidateIds").get(0).asText();
        JsonNode candidate = getCandidate(token, ws, candidateId);
        assertTrue(candidate.path("reasonsJson").asText("").contains("MULTIPLE_DATES"),
                "多日期必须被标记为不确定");
        assertTrue(candidate.path("dueAt").isMissingNode() || candidate.path("dueAt").isNull(),
                "多日期候选不得自动写入截止时间");
        JsonNode fieldConfidence = json.readTree(candidate.path("fieldConfidenceJson").asText("{}"));
        assertTrue(fieldConfidence.path("due").asDouble(1.0) < 0.6,
                "日期字段置信度必须如实标记为低置信: " + candidate.path("fieldConfidenceJson").asText());

        int version = candidate.path("version").asInt();
        Map<String, Object> unconfirmed = new LinkedHashMap<>();
        unconfirmed.put("baseVersion", version);
        unconfirmed.put("dueAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        Resp rejected = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                unconfirmed, unique("conf"));
        assertEquals(400, rejected.status, rejected.raw);
        assertEquals("VALIDATION_FAILED", errCode(rejected), "低置信日期必须显式确认，不得静默接受");
        assertEquals("dueDateConfirmed", rejected.json.path("error").path("details").path("field").asText(),
                "拒绝原因必须明确指向需要确认的日期字段，而不是泛化校验失败");

        Resp tasksAfterReject = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(0, tasksAfterReject.json.path("data").path("content").size(),
                "日期未确认不得创建事项");

        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("baseVersion", version);
        confirmed.put("dueAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        confirmed.put("dueDateConfirmed", true);
        confirmed.put("reminders", List.of(Map.of(
                "triggerAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                "channel", "NOTIFICATION")));
        Resp ok = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                confirmed, unique("conf"));
        assertEquals(200, ok.status, ok.raw);
        assertEquals(1, ok.json.path("data").path("reminderCount").asInt(),
                "用户显式确认后必须按显式提醒创建");
    }

    @Test
    void lowConfidenceCandidateDoesNotSilentlyCreateDefaultReminder() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("默认提醒空间"));

        JsonNode source = shareSource(token, ws, "11月25日上午9点提交报名表，11月27日下午3点提交缴费单");
        String candidateId = source.path("candidateIds").get(0).asText();
        JsonNode candidate = getCandidate(token, ws, candidateId);
        int version = candidate.path("version").asInt();

        // 用户只确认日期、不显式给提醒：低置信候选不得被静默补出强提醒
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("baseVersion", version);
        confirm.put("dueAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        confirm.put("dueDateConfirmed", true);
        Resp ok = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                confirm, unique("conf"));
        assertEquals(200, ok.status, ok.raw);
        assertEquals(0, ok.json.path("data").path("reminderCount").asInt(),
                "低置信候选未显式给提醒时不得静默生成默认提醒");
        // 交叉校验：截止时间确实落到事项，排除「dueAt 丢失导致默认提醒本就为空」的空转
        String taskId = ok.json.path("data").path("taskId").asText();
        JsonNode task = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws, token, null, null)
                .json.path("data");
        assertFalse(task.path("dueAt").isMissingNode() || task.path("dueAt").isNull(),
                "用户确认的截止时间必须真正落到事项");
        Resp reminders = req("GET", "/api/v1/tasks/" + taskId + "/reminders?workspaceId=" + ws,
                token, null, null);
        assertEquals(200, reminders.status, reminders.raw);
        assertEquals(0, reminders.json.path("data").size(), "低置信候选不得产生任何默认提醒");
    }

    @Test
    void highConfidenceSingleDateCreatesDefaultReminderWithoutExplicitList() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("高置信默认提醒空间"));

        JsonNode source = shareSource(token, ws, "请于2027年12月30日18:00前提交竞赛报名表");
        String candidateId = source.path("candidateIds").get(0).asText();
        JsonNode candidate = getCandidate(token, ws, candidateId);

        // 高置信单日期、用户不显式给提醒：默认提醒必须正常生成（与低置信用例形成对照）
        Resp ok = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                Map.of("baseVersion", candidate.path("version").asInt()), unique("conf"));
        assertEquals(200, ok.status, ok.raw);
        assertEquals(1, ok.json.path("data").path("reminderCount").asInt(),
                "高置信单日期候选应生成默认提醒");
    }

    @Test
    void deletedSourceInvalidatesPendingCandidate() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("源删除空间"));

        JsonNode source = shareSource(token, ws, SOURCE_TEXT);
        String sourceId = source.path("id").asText();
        String candidateId = source.path("candidateIds").get(0).asText();
        int sourceVersion = source.path("version").asInt();

        Resp del = req("DELETE", "/api/v1/sources/" + sourceId + "?workspaceId=" + ws
                + "&baseVersion=" + sourceVersion, token, null, null);
        assertEquals(200, del.status, del.raw);

        Resp gone = req("GET", "/api/v1/sources/" + sourceId + "?workspaceId=" + ws, token, null, null);
        assertEquals(404, gone.status, "删除后来源不可再读取: " + gone.raw);

        int version = getCandidate(token, ws, candidateId).path("version").asInt();
        Resp confirm = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                Map.of("baseVersion", version, "dueDateConfirmed", true), unique("conf"));
        assertEquals(410, confirm.status, confirm.raw);
        assertEquals("SOURCE_EXPIRED", errCode(confirm), "来源删除后候选不得再生成事项");

        Resp tasks = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(0, tasks.json.path("data").path("content").size(), "失效来源不得产生事项");
    }

    @Test
    void revokedMemberLosesCandidateAndMemoryAccess() {
        Map<String, String> owner = register();
        String ownerToken = owner.get("access");
        String ws = createWorkspace(ownerToken, unique("撤权空间"));
        JsonNode source = shareSource(ownerToken, ws, SOURCE_TEXT);
        String candidateId = source.path("candidateIds").get(0).asText();

        Map<String, String> member = register();
        String memberToken = member.get("access");
        invite(ownerToken, ws, member.get("email"), "MEMBER");
        accept(memberToken, ws);

        // 撤权前先由 owner 读取候选真实版本，撤权后用同一版本确认，证明拒绝来自权限而非版本冲突
        int candidateVersion = getCandidate(ownerToken, ws, candidateId).path("version").asInt();

        // 撤权前：成员可读候选与记忆
        assertEquals(200, req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws,
                memberToken, null, null).status);
        Resp beforeSearch = req("GET", "/api/v1/memory/search?workspaceId=" + ws + "&q=课程设计",
                memberToken, null, null);
        assertEquals(200, beforeSearch.status, beforeSearch.raw);
        assertTrue(beforeSearch.json.path("data").path("items").size() >= 1, "成员撤权前应能检索空间记忆");

        // 撤权
        Resp remove = req("DELETE", "/api/v1/workspaces/" + ws + "/members/" + member.get("userId"),
                ownerToken, null, null);
        assertEquals(200, remove.status, remove.raw);

        // 撤权后：候选、确认、记忆检索全部拒绝
        Resp cand = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws,
                memberToken, null, null);
        assertEquals(403, cand.status, "撤权后不得读取候选: " + cand.raw);

        Resp confirm = req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", memberToken,
                Map.of("baseVersion", candidateVersion), unique("conf"));
        assertEquals(403, confirm.status, "撤权后不得确认候选（版本有效仍应被拒）: " + confirm.raw);

        Resp search = req("GET", "/api/v1/memory/search?workspaceId=" + ws + "&q=课程设计",
                memberToken, null, null);
        assertEquals(403, search.status, "撤权后不得检索空间记忆: " + search.raw);
    }

    // ---- helpers ----

    private JsonNode shareSource(String token, String ws, String text) {
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", ws);
        share.put("sourceType", "NOTIFICATION");
        share.put("sourceApp", "com.example.scheduler");
        share.put("sourceKey", unique("key"));
        share.put("title", "课程设计提交通知");
        share.put("text", text);
        share.put("timezone", "Asia/Shanghai");
        share.put("localOnly", true);
        Resp resp = req("POST", "/api/v1/sources/share", token, share, null);
        assertEquals(200, resp.status, resp.raw);
        assertTrue(resp.json.path("data").path("candidateIds").size() >= 1, "规则抽取必须产出候选");
        return resp.json.path("data");
    }

    private JsonNode getCandidate(String token, String ws, String candidateId) {
        Resp resp = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws,
                token, null, null);
        assertEquals(200, resp.status, resp.raw);
        return resp.json.path("data");
    }
}

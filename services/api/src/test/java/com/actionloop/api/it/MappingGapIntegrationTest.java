package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指导书第 17/26 章映射缺口的行为验证（真实 HTTP + 真实 PostgreSQL/Redis）：
 * - A-02 同一通知更新三次：不重复建事项，实质变更仍要求再确认。
 * - A-04 闲聊/广告/验证码：不生成强提醒，高敏内容不产候选、不上传。
 * - D-04 并发改派/并发核验：不静默覆盖，冲突返回 409。
 * 事件信封契约（seq/aggregateType/eventType/payloadJson）也在此校验。
 */
class MappingGapIntegrationTest extends AbstractIntegrationTest {

    // ---- A-02 ----

    @Test
    void sameNotificationUpdatedThriceNeverDuplicatesTaskAndRequiresReconfirmation() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("通知去重空间"));
        String sourceKey = unique("notif");

        String original = "请于2027年12月30日18:00前提交竞赛报名表";
        JsonNode first = shareNotification(token, ws, sourceKey, original);
        String sourceId = first.path("id").asText();
        assertEquals(1, first.path("candidateIds").size(), "首次导入必须产出一个候选");

        String candidateId = first.path("candidateIds").get(0).asText();
        JsonNode candidate = getCandidate(token, ws, candidateId);
        Resp confirm = confirm(token, candidateId, candidate.path("version").asInt());
        assertEquals(200, confirm.status, confirm.raw);
        String taskId = confirm.json.path("data").path("taskId").asText();
        JsonNode taskBefore = getTask(token, ws, taskId);
        String titleBefore = taskBefore.path("title").asText();
        assertEquals(1, taskCount(token, ws), "确认后应只有一个事项");

        // 第 2 次：完全相同的通知（指纹去重）→ 复用来源，不新增候选/事项
        JsonNode duplicate = shareNotification(token, ws, sourceKey, original);
        assertEquals(sourceId, duplicate.path("id").asText(), "相同指纹必须复用原来源");
        assertEquals(1, duplicate.path("candidateIds").size(), "相同通知不得重复产候选");
        assertEquals(1, taskCount(token, ws), "相同通知不得重复建事项");

        // 第 3 次：同 sourceKey、内容变更（实质更新）→ 复用来源但新增候选；
        // 旧事项保持不变，新候选仍为 PENDING，必须用户再次确认。
        String updated = "【更新】请于2027年12月31日20:00前提交竞赛报名表，地点改为线上";
        JsonNode changed = shareNotification(token, ws, sourceKey, updated);
        assertEquals(sourceId, changed.path("id").asText(), "同 key 更新应复用来源，避免刷屏");
        assertEquals(2, changed.path("candidateIds").size(), "实质变更必须产生新候选供用户再确认");

        JsonNode taskAfter = getTask(token, ws, taskId);
        assertEquals(titleBefore, taskAfter.path("title").asText(), "来源更新不得篡改历史事项标题");
        assertEquals(taskBefore.path("dueAt").asText(), taskAfter.path("dueAt").asText(),
                "来源更新不得篡改历史事项截止时间");
        assertEquals(taskBefore.path("version").asInt(), taskAfter.path("version").asInt(),
                "来源更新不得推进历史事项版本");
        assertEquals(1, taskCount(token, ws), "实质变更在用户确认前不得自动建第二个事项");

        String newCandidateId = changed.path("candidateIds").get(0).asText();
        JsonNode newCandidate = getCandidate(token, ws, newCandidateId);
        assertEquals("PENDING", newCandidate.path("status").asText(), "更新后的候选必须等待用户确认");
        assertEquals(sourceId, newCandidate.path("sourceItemId").asText());
    }

    // ---- A-04 ----

    @Test
    void plainChatNeverCreatesStrongReminder() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("闲聊空间"));

        JsonNode source = share(token, ws, "今天食堂新出了糖醋排骨，晚上一起去尝尝吧", true);
        assertEquals(1, source.path("candidateIds").size(), "规则抽取可能形成低置信候选");
        String candidateId = source.path("candidateIds").get(0).asText();
        JsonNode candidate = getCandidate(token, ws, candidateId);
        assertFalse(candidate.path("dueAt").isMissingNode() || candidate.path("dueAt").isNull(),
                "「今天」必须被规则解析出日期，否则本用例无法证明提醒被抑制");
        assertTrue(candidate.path("confidence").asDouble() < 0.6, "闲聊候选不得伪装成高置信");
        assertTrue(candidate.path("reasonsJson").asText("").contains("POSSIBLE_NON_TASK"),
                "降级必须显式标注原因，不能静默不提醒: " + candidate.path("reasonsJson").asText());

        Resp confirm = confirm(token, candidateId, candidate.path("version").asInt());
        assertEquals(200, confirm.status, confirm.raw);
        assertEquals(0, confirm.json.path("data").path("reminderCount").asInt(),
                "无日期闲聊不得生成强提醒");

        String taskId = confirm.json.path("data").path("taskId").asText();
        Resp reminders = req("GET", "/api/v1/tasks/" + taskId + "/reminders?workspaceId=" + ws,
                token, null, null);
        assertEquals(200, reminders.status, reminders.raw);
        assertEquals(0, reminders.json.path("data").size(), "提醒列表必须为空，不得静默补默认提醒");
    }

    @Test
    void adsAndVerificationCodesNeverProduceCandidates() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("高敏空间"));

        JsonNode ad = share(token, ws, "【中奖通知】恭喜您获得一等奖，点击链接领取 https://x.example/prize", true);
        assertEquals(0, ad.path("candidateIds").size(), "广告不得生成候选");
        assertEquals("S3", ad.path("privacyLevel").asText(), "广告链接内容必须按高敏处理");
        assertEquals("USER_PROVIDED", ad.path("sourceState").asText(), "通知不得冒充官方来源");

        JsonNode otp = share(token, ws, "您的验证码是 483920，5 分钟内有效，请勿泄露", true);
        assertEquals(0, otp.path("candidateIds").size(), "验证码不得生成候选");
        assertEquals("S3", otp.path("privacyLevel").asText(), "验证码必须按高敏处理");

        Resp candidates = req("GET", "/api/v1/inbox/candidates?workspaceId=" + ws, token, null, null);
        assertEquals(200, candidates.status, candidates.raw);
        assertEquals(0, candidates.json.path("data").path("items").size(), "高敏内容不得进入待确认收件箱");

        Resp tasks = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(200, tasks.status, tasks.raw);
        assertEquals(0, tasks.json.path("data").path("content").size(), "高敏内容不得产生事项");
    }

    // ---- D-04 ----

    @Test
    void concurrentAssigneeUpdatesWithSameBaseVersionYieldExactlyOneConflict() throws Exception {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("并发改派空间"));
        String taskId = createTask(token, ws, unique("并发改派"));

        // 必须用两个「不同于当前负责人」的目标，否则 PATCH 无实际变更、不会推进版本，测不到冲突。
        Map<String, String> memberA = register();
        invite(token, ws, memberA.get("email"), "MEMBER");
        accept(memberA.get("access"), ws);
        Map<String, String> memberB = register();
        invite(token, ws, memberB.get("email"), "MEMBER");
        accept(memberB.get("access"), ws);

        JsonNode task = getTask(token, ws, taskId);
        int baseVersion = task.path("version").asInt();
        String first = memberA.get("userId");
        String second = memberB.get("userId");
        String path = "/api/v1/tasks/" + taskId + "?workspaceId=" + ws;

        List<Resp> results = runConcurrently(
                () -> req("PATCH", path, token, Map.of("baseVersion", baseVersion, "assigneeId", first), null),
                () -> req("PATCH", path, token, Map.of("baseVersion", baseVersion, "assigneeId", second), null));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long conflict = results.stream().filter(r -> r.status == 409).count();
        assertEquals(1, ok, "同一 baseVersion 的并发改派只能有一个成功: " + describe(results));
        assertEquals(1, conflict, "另一个必须返回冲突而不是静默覆盖: " + statuses(results));
        String conflictCode = results.stream().filter(r -> r.status == 409).findFirst()
                .map(this::errCode).orElse("");
        assertEquals("VERSION_CONFLICT", conflictCode, "冲突必须是可解释的版本冲突");

        JsonNode finalTask = getTask(token, ws, taskId);
        assertEquals(baseVersion + 1, finalTask.path("version").asInt(), "版本只能推进一次");
        String winnerAssignee = results.get(0).status == 200
                ? results.get(0).json.path("data").path("assigneeId").asText()
                : results.get(1).json.path("data").path("assigneeId").asText();
        assertEquals(winnerAssignee, finalTask.path("assigneeId").asText(), "最终负责人必须是成功写入的值");
    }

    @Test
    void concurrentCompletionReviewsNeverDoubleApprove() throws Exception {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("并发核验空间"));
        String taskId = createTask(token, ws, unique("并发核验"));
        Resp stepCreate = req("POST", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws, token,
                Map.of("title", "提交材料"), null);
        assertEquals(200, stepCreate.status, stepCreate.raw);
        String stepId = stepCreate.json.path("data").path("id").asText();

        Resp submit = req("POST", "/api/v1/steps/" + stepId + "/completions?workspaceId=" + ws, token,
                Map.of("statement", "回执已上传"), unique("comp"));
        assertEquals(200, submit.status, submit.raw);
        String completionId = submit.json.path("data").path("id").asText();

        String reviewPath = "/api/v1/completions/" + completionId + "/review?workspaceId=" + ws;
        Map<String, Object> review = Map.of("approve", true, "reason", "材料齐全");
        List<Resp> results = runConcurrently(
                () -> req("POST", reviewPath, token, review, unique("rev")),
                () -> req("POST", reviewPath, token, review, unique("rev")));

        long ok = results.stream().filter(r -> r.status == 200).count();
        assertEquals(1, ok, "同一凭证并发核验只能一次成功: " + statuses(results));
        Resp rejected = results.stream().filter(r -> r.status != 200).findFirst().orElseThrow();
        assertTrue(rejected.status == 409 || rejected.status == 400,
                "重复核验必须被明确拒绝: " + rejected.status + " " + rejected.raw);

        long approved = results.stream()
                .filter(r -> r.status == 200)
                .filter(r -> "APPROVED".equals(r.json.path("data").path("reviewState").asText()))
                .count();
        assertEquals(1, approved, "只能产生一条通过记录");
    }

    // ---- 事件信封契约 ----

    @Test
    void workspaceEventEnvelopeCarriesRequiredFields() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("事件信封空间"));
        JsonNode share = share(token, ws, "请于2027年12月30日18:00前提交竞赛报名表", true);
        String sourceId = share.path("id").asText();

        Resp events = req("GET", "/api/v1/sync/events?workspaceId=" + ws + "&afterSeq=0&limit=200",
                token, null, null);
        assertEquals(200, events.status, events.raw);
        JsonNode created = null;
        for (JsonNode e : events.json.path("data")) {
            if ("SOURCE_CREATED".equals(e.path("eventType").asText())
                    && sourceId.equals(e.path("aggregateId").asText())) {
                created = e;
                break;
            }
        }
        assertNotNull(created, "来源创建必须写入空间事件流");
        assertTrue(created.path("seq").asLong() > 0, "事件必须有单调 seq");
        assertEquals("SOURCE", created.path("aggregateType").asText());
        assertFalse(created.path("aggregateId").asText("").isBlank(), "事件必须携带聚合 id");
        assertTrue(created.hasNonNull("version"), "事件必须携带聚合版本");
        assertNotNull(created.path("createdAt").asText(null), "事件必须携带时间");
        String payload = created.path("payloadJson").asText("");
        assertFalse(payload.isBlank(), "事件必须携带最小化 payload");
        assertTrue(payload.contains(sourceId), "payload 必须关联来源 id");
    }

    // ---- helpers ----

    private JsonNode shareNotification(String token, String ws, String sourceKey, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", ws);
        body.put("sourceType", "NOTIFICATION");
        body.put("sourceApp", "com.example.scheduler");
        body.put("sourceKey", sourceKey);
        body.put("title", "竞赛报名通知");
        body.put("text", text);
        body.put("timezone", "Asia/Shanghai");
        body.put("localOnly", true);
        Resp resp = req("POST", "/api/v1/sources/share", token, body, null);
        assertEquals(200, resp.status, resp.raw);
        return resp.json.path("data");
    }

    private JsonNode share(String token, String ws, String text, boolean localOnly) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", ws);
        body.put("sourceType", "NOTIFICATION");
        body.put("sourceApp", unique("com.example.app"));
        body.put("sourceKey", unique("key"));
        body.put("text", text);
        body.put("timezone", "Asia/Shanghai");
        body.put("localOnly", localOnly);
        Resp resp = req("POST", "/api/v1/sources/share", token, body, null);
        assertEquals(200, resp.status, resp.raw);
        return resp.json.path("data");
    }

    private JsonNode getCandidate(String token, String ws, String candidateId) {
        Resp resp = req("GET", "/api/v1/inbox/candidates/" + candidateId + "?workspaceId=" + ws,
                token, null, null);
        assertEquals(200, resp.status, resp.raw);
        return resp.json.path("data");
    }

    private Resp confirm(String token, String candidateId, int baseVersion) {
        return req("POST", "/api/v1/inbox/candidates/" + candidateId + "/confirm", token,
                Map.of("baseVersion", baseVersion, "dueDateConfirmed", true), unique("conf"));
    }

    private JsonNode getTask(String token, String ws, String taskId) {
        Resp resp = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws, token, null, null);
        assertEquals(200, resp.status, resp.raw);
        return resp.json.path("data");
    }

    private int taskCount(String token, String ws) {
        Resp resp = req("GET", "/api/v1/tasks?workspaceId=" + ws, token, null, null);
        assertEquals(200, resp.status, resp.raw);
        return resp.json.path("data").path("content").size();
    }

    private List<Resp> runConcurrently(java.util.concurrent.Callable<Resp> first,
                                       java.util.concurrent.Callable<Resp> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Resp> a = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Resp> b = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private String statuses(List<Resp> results) {
        return results.stream().map(r -> String.valueOf(r.status)).toList().toString();
    }

    private String describe(List<Resp> results) {
        return results.stream()
                .map(r -> r.status + "(v=" + r.json.path("data").path("version").asText("?")
                        + ",assignee=" + r.json.path("data").path("assigneeId").asText("?") + ")")
                .toList().toString();
    }
}

package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账户注销（指导书 13.2 DELETE /me、15.3 近期重新认证、15.5 冷却与取消）：
 * 请求进入冷却期、错误密码拒绝、取消生效、共享空间所有者被阻塞。到期清除见 AccountPurgeIntegrationTest。
 */
class AccountDeletionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void requestSchedulesCooldownAndIsIdempotent() {
        var u = register();

        Resp first = req("DELETE", "/api/v1/me", u.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(202, first.status, first.raw);
        assertEquals("PENDING", first.json.path("data").path("state").asText());
        String scheduledAt = first.json.path("data").path("scheduledAt").asText();
        assertTrue(Instant.parse(scheduledAt).isAfter(Instant.now().plus(Duration.ofDays(6))),
                "默认冷却 7 天，计划清除时间应在一周附近: " + scheduledAt);

        // 重复请求幂等：不延长冷却期（数据库按微秒存储，允许 1s 内的精度差）
        Resp again = req("DELETE", "/api/v1/me", u.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(202, again.status, again.raw);
        assertTrue(withinOneSecond(scheduledAt, again.json.path("data").path("scheduledAt").asText()),
                "重复请求不得延长冷却期: " + again.raw);

        Resp me = req("GET", "/api/v1/me", u.get("access"), null, null);
        assertEquals(200, me.status, me.raw);
        assertEquals("PENDING", me.json.path("data").path("accountDeletion").path("state").asText());
        assertTrue(withinOneSecond(scheduledAt,
                        me.json.path("data").path("accountDeletion").path("scheduledAt").asText()),
                "GET /me 应返回同一计划清除时间: " + me.raw);
    }

    private boolean withinOneSecond(String expected, String actual) {
        long delta = Math.abs(Duration.between(Instant.parse(expected), Instant.parse(actual)).toMillis());
        return delta <= 1000;
    }

    @Test
    void wrongPasswordIsRejectedAndDoesNotSchedule() {
        var u = register();

        Resp denied = req("DELETE", "/api/v1/me", u.get("access"), Map.of("password", "WrongPass1!"), null);
        assertEquals(401, denied.status, denied.raw);
        assertEquals("AUTH_INVALID", errCode(denied));

        Resp me = req("GET", "/api/v1/me", u.get("access"), null, null);
        assertEquals(200, me.status, me.raw);
        assertEquals("NONE", me.json.path("data").path("accountDeletion").path("state").asText());
        assertTrue(me.json.path("data").path("accountDeletion").path("scheduledAt").isMissingNode()
                        || me.json.path("data").path("accountDeletion").path("scheduledAt").isNull(),
                "未进入冷却期时不应返回计划清除时间: " + me.raw);
    }

    @Test
    void cancelClearsPendingDeletionAndIsIdempotent() {
        var u = register();
        Resp requested = req("DELETE", "/api/v1/me", u.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(202, requested.status, requested.raw);

        Resp cancelled = req("POST", "/api/v1/me/deletion/cancel", u.get("access"), null, null);
        assertEquals(200, cancelled.status, cancelled.raw);
        assertEquals("NONE", cancelled.json.path("data").path("state").asText());

        // 再次取消幂等
        Resp again = req("POST", "/api/v1/me/deletion/cancel", u.get("access"), null, null);
        assertEquals(200, again.status, again.raw);
        assertEquals("NONE", again.json.path("data").path("state").asText());

        // 取消后可重新请求
        Resp reRequested = req("DELETE", "/api/v1/me", u.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(202, reRequested.status, reRequested.raw);
        assertEquals("PENDING", reRequested.json.path("data").path("state").asText());
    }

    @Test
    void ownerOfSharedWorkspaceMustTransferBeforeDeletion() {
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), unique("team"));
        var member = register();
        invite(owner.get("access"), wsId, member.get("email"), "MEMBER");
        accept(member.get("access"), wsId);

        Resp blocked = req("DELETE", "/api/v1/me", owner.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(409, blocked.status, blocked.raw);
        assertEquals("INVALID_STATE_TRANSITION", errCode(blocked));

        Resp me = req("GET", "/api/v1/me", owner.get("access"), null, null);
        assertEquals("NONE", me.json.path("data").path("accountDeletion").path("state").asText(),
                "被阻塞时不得写入冷却计划: " + me.raw);

        // 非所有者成员不受影响，可正常进入冷却
        Resp memberReq = req("DELETE", "/api/v1/me", member.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(202, memberReq.status, memberReq.raw);
        assertNotNull(memberReq.json.path("data").path("scheduledAt").asText());
    }
}

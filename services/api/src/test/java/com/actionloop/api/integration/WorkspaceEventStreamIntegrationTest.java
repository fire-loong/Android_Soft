package com.actionloop.api.integration;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REST cursor 恢复：WS 断线后客户端按 afterSeq 从 /api/v1/sync/events 补洞，且越权访问被拒。 */
class WorkspaceEventStreamIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void nonMemberCannotReadAndMemberCanRecoverMissedEventsByAfterSeq() {
        var owner = register();
        var outsider = register();
        String wsId = createWorkspace(owner.get("access"), "stream-ws");
        UUID workspaceId = UUID.fromString(wsId);

        Resp forbidden = req("GET", "/api/v1/sync/events?workspaceId=" + wsId + "&afterSeq=0&limit=200",
                outsider.get("access"), null, null);
        assertEquals(403, forbidden.status, "非成员不能读取空间事件流");

        long before = maxSeq(workspaceId);
        emitWorkspaceEvent(wsId, "STREAM_FIRST");
        emitWorkspaceEvent(wsId, "STREAM_SECOND");

        Resp events = req("GET", "/api/v1/sync/events?workspaceId=" + wsId + "&afterSeq=" + before + "&limit=200",
                owner.get("access"), null, null);
        assertEquals(200, events.status);
        assertEquals(2, events.json.path("data").size(), "afterSeq 之前的事件应被排除，只返回漏掉的两条");

        long firstSeq = events.json.path("data").get(0).path("seq").asLong();
        long secondSeq = events.json.path("data").get(1).path("seq").asLong();
        assertTrue(firstSeq > before);
        assertTrue(secondSeq > firstSeq, "cursor 必须按 seq 升序返回");

        Resp afterLatest = req("GET", "/api/v1/sync/events?workspaceId=" + wsId + "&afterSeq=" + secondSeq
                + "&limit=200", owner.get("access"), null, null);
        assertEquals(200, afterLatest.status);
        assertEquals(0, afterLatest.json.path("data").size(), "最新 seq 之后应无事件");
    }

    private long maxSeq(UUID workspaceId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM server_events WHERE workspace_id = ?",
                Long.class, workspaceId);
        return value == null ? 0L : value;
    }
}

package com.actionloop.api.integration;

import com.actionloop.api.job.OutboxRelay;
import com.actionloop.api.realtime.WorkspaceEventMessage;
import com.actionloop.api.realtime.WorkspaceEventPublisher;
import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** 验证 outbox relay 失败时不丢消息、重试后标记 published_at，并携带 server_seq。 */
@TestPropertySource(properties = {
        "app.worker.outbox.enabled=true",
        "app.worker.outbox.interval-ms=600000",
        "app.websocket.relay-enabled=false"
})
class WorkspaceEventRelayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WorkspaceEventPublisher publisher;

    @BeforeEach
    void clearOutboxAndEvents() {
        reset(publisher);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM server_events");
    }

    @Test
    void publishFailureLeavesUnpublishedAndRetryMarksPublished() {
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), "relay-ws");
        emitWorkspaceEvent(wsId, "RELAY_TEST_EVENT");
        UUID workspaceId = UUID.fromString(wsId);

        Long outboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_events WHERE workspace_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, workspaceId);
        assertNotNull(outboxId);
        assertTrue(isUnpublished(outboxId));

        doThrow(new RuntimeException("redis down"))
                .when(publisher).publish(argThat(message -> outboxId.equals(message.outboxId())));
        outboxRelay.relayBatch();
        assertTrue(isUnpublished(outboxId), "发布失败时 published_at 必须保持空，等待下次重试");
        assertEquals(1, attempts(outboxId), "失败必须记录 attempts，不能吞掉状态");
        assertEquals("RuntimeException", lastErrorCode(outboxId));

        reset(publisher);
        jdbcTemplate.update("UPDATE outbox_events SET next_retry_at = now() WHERE id = ?", outboxId);
        outboxRelay.relayBatch();
        assertFalse(isUnpublished(outboxId), "发布成功后必须标记 published_at");
        verify(publisher, atLeastOnce()).publish(argThat(message ->
                outboxId.equals(message.outboxId())
                        && workspaceId.equals(message.workspaceId())
                        && message.seq() != null
                        && message.seq() > 0));
        verify(publisher, atLeastOnce()).publish(any(WorkspaceEventMessage.class));
    }

    private boolean isUnpublished(Long outboxId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT published_at IS NULL FROM outbox_events WHERE id = ?", Boolean.class, outboxId));
    }

    private int attempts(Long outboxId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, outboxId);
        return value == null ? 0 : value;
    }

    private String lastErrorCode(Long outboxId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM outbox_events WHERE id = ?", String.class, outboxId);
    }
}

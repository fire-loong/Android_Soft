package com.actionloop.api.integration;

import com.actionloop.api.job.OutboxRelay;
import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端：API 事务写入 server/outbox → OutboxRelay → Redis → 订阅 → 业务 WebSocket，客户端拿到真实 seq。 */
@TestPropertySource(properties = {
        "app.worker.outbox.enabled=true",
        "app.worker.outbox.interval-ms=600000",
        "app.websocket.relay-enabled=true"
})
class WorkspaceEventEndToEndIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final WebSocketClient client = new StandardWebSocketClient();

    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM server_events");
    }

    @Test
    void relayPublishesDbBackedEventToWebSocketWithRealSeq() throws Exception {
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), "e2e-ws");
        UUID workspaceId = UUID.fromString(wsId);
        long beforeSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM server_events WHERE workspace_id = ?", Long.class, workspaceId);

        CaptureHandler handler = new CaptureHandler();
        WebSocketSession session = connect(owner.get("access"), wsId, handler);
        try {
            emitWorkspaceEvent(wsId, "E2E_RELAY_PUSH");
            outboxRelay.relayBatch();

            assertTrue(awaitMessage(handler, "E2E_RELAY_PUSH"), "relay 必须把数据库事件投递到 WebSocket");
            long expectedSeq = beforeSeq + 1;
            assertTrue(handler.lastMessage().contains("\"seq\":" + expectedSeq), "客户端收到的必须是持久化 seq");
            assertEquals(0, unpublishedCount(workspaceId), "relay 成功后该空间不应再有未发布 outbox");
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    private int unpublishedCount(UUID workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE workspace_id = ? AND published_at IS NULL",
                Integer.class, workspaceId);
        return count == null ? 0 : count;
    }

    private WebSocketSession connect(String token, String wsId, CaptureHandler handler) throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/ws/events?workspaceId=" + wsId
                + "&access_token=" + token);
        return client.execute(handler, new WebSocketHttpHeaders(), uri).get(10, TimeUnit.SECONDS);
    }

    private boolean awaitMessage(CaptureHandler handler, String fragment) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (handler.messages.stream().anyMatch(message -> message.contains(fragment))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static final class CaptureHandler extends AbstractWebSocketHandler {
        final List<String> messages = new CopyOnWriteArrayList<>();

        String lastMessage() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        }
    }
}

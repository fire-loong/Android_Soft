package com.actionloop.api.integration;

import com.actionloop.api.realtime.WorkspaceEventMessage;
import com.actionloop.api.realtime.WorkspaceEventPublisher;
import com.actionloop.api.test.AbstractIntegrationTest;
import com.actionloop.api.web.WorkspaceSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务 WebSocket：成员可接收 Redis relay 事件；撤权后会话被关闭；非成员握手被拒。 */
class WorkspaceEventWebSocketIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WorkspaceEventPublisher publisher;

    @Autowired
    private WorkspaceSessionRegistry registry;

    private final WebSocketClient client = new StandardWebSocketClient();

    @Test
    void memberReceivesEventAndRevokedSessionIsClosed() throws Exception {
        var owner = register();
        var member = register();
        String wsId = createWorkspace(owner.get("access"), "ws-events");
        invite(owner.get("access"), wsId, member.get("email"), "MEMBER");
        accept(member.get("access"), wsId);
        UUID workspaceId = UUID.fromString(wsId);

        CaptureHandler handler = new CaptureHandler();
        WebSocketSession session = connect(member.get("access"), wsId, handler);
        try {
            session.sendMessage(new TextMessage("ping"));
            assertTrue(awaitMessage(handler, "pong"), "心跳必须返回 pong");

            publisher.publish(new WorkspaceEventMessage(null, workspaceId, 12345L,
                    workspaceId, "WORKSPACE", "TEST_PUSH", "{\"hello\":\"world\"}", Instant.now()));
            assertTrue(awaitMessage(handler, "TEST_PUSH"), "成员必须收到 Redis relay 投递的业务事件");
            assertTrue(handler.lastMessage().contains("\"seq\":12345"), "客户端需要 seq 去重");

            Resp removed = req("DELETE", "/api/v1/workspaces/" + wsId + "/members/" + member.get("userId"),
                    owner.get("access"), null, null);
            assertEquals(200, removed.status);

            registry.closeRevokedSessions();
            assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS), "撤权后服务端必须关闭会话");
            assertFalse(session.isOpen());
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    @Test
    void nonMemberHandshakeIsRejected() {
        var outsider = register();
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), "ws-no-access");

        CaptureHandler handler = new CaptureHandler();
        assertThrows(ExecutionException.class, () -> connect(outsider.get("access"), wsId, handler),
                "非成员不能建立空间事件 WebSocket");
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
        final CountDownLatch closeLatch = new CountDownLatch(1);
        volatile CloseStatus closeStatus;

        String lastMessage() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeStatus = status;
            closeLatch.countDown();
        }
    }
}

package com.actionloop.api.web;

import com.actionloop.api.security.JwtService;
import com.actionloop.api.service.AssistBroadcaster;
import com.actionloop.api.service.AssistService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * 协助 WebSocket：路径 /ws/assist/{sessionId}?access_token=...
 * 只做服务端推送；入站消息一律忽略（信令必须走 REST 白名单校验），避免绕过审计。
 */
@Component
public class AssistWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_SESSION_ID = "assistSessionId";
    public static final String ATTR_USER_ID = "assistUserId";

    private final AssistBroadcaster broadcaster;

    public AssistWebSocketHandler(AssistBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID sessionId = (UUID) session.getAttributes().get(ATTR_SESSION_ID);
        if (sessionId != null) {
            broadcaster.register(sessionId, session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 仅接受心跳；任何业务信令必须经 REST 校验，防止伪造 CLICK 等绕过白名单
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID sessionId = (UUID) session.getAttributes().get(ATTR_SESSION_ID);
        if (sessionId != null) {
            broadcaster.unregister(sessionId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 握手鉴权：校验 access token + 会话参与者身份。失败返回 false，不建立连接。
     */
    @Component
    public static class AuthHandshakeInterceptor
            implements org.springframework.web.socket.server.HandshakeInterceptor {

        private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

        private final JwtService jwtService;
        private final AssistService assistService;

        public AuthHandshakeInterceptor(JwtService jwtService, AssistService assistService) {
            this.jwtService = jwtService;
            this.assistService = assistService;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            UUID sessionId = sessionId(request);
            String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().getFirst("access_token");
            if (sessionId == null || token == null || token.isBlank()) {
                return false;
            }
            try {
                Claims claims = jwtService.parseAccessToken(token);
                UUID userId = UUID.fromString(claims.getSubject());
                if (!assistService.canJoin(sessionId, userId)) {
                    return false;
                }
                attributes.put(AssistWebSocketHandler.ATTR_SESSION_ID, sessionId);
                attributes.put(AssistWebSocketHandler.ATTR_USER_ID, userId);
                return true;
            } catch (Exception e) {
                log.debug("assist ws handshake rejected");
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }

        private UUID sessionId(ServerHttpRequest request) {
            String path = request.getURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 2) {
                return null;
            }
            try {
                return UUID.fromString(parts[parts.length - 1]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}

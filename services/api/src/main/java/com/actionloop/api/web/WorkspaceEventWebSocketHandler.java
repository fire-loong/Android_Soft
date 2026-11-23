package com.actionloop.api.web;

import com.actionloop.api.security.JwtService;
import com.actionloop.api.service.PermissionService;
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
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * 空间业务事件 WebSocket：/ws/events?workspaceId=...&access_token=...
 * 握手即校验活跃成员；服务端只推送，忽略客户端业务消息，仅响应 ping。
 */
@Component
public class WorkspaceEventWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_WORKSPACE_ID = "workspaceEventWorkspaceId";
    public static final String ATTR_USER_ID = "workspaceEventUserId";

    private final WorkspaceSessionRegistry registry;

    public WorkspaceEventWebSocketHandler(WorkspaceSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID workspaceId = (UUID) session.getAttributes().get(ATTR_WORKSPACE_ID);
        UUID userId = (UUID) session.getAttributes().get(ATTR_USER_ID);
        if (workspaceId != null && userId != null) {
            registry.register(workspaceId, userId, session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Component
    public static class AuthHandshakeInterceptor implements HandshakeInterceptor {

        private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

        private final JwtService jwtService;
        private final PermissionService permissionService;

        public AuthHandshakeInterceptor(JwtService jwtService, PermissionService permissionService) {
            this.jwtService = jwtService;
            this.permissionService = permissionService;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            Map<String, String> params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().toSingleValueMap();
            String workspaceParam = params.get("workspaceId");
            String token = params.get("access_token");
            if (workspaceParam == null || token == null || token.isBlank()) {
                return false;
            }
            UUID workspaceId;
            try {
                workspaceId = UUID.fromString(workspaceParam);
                Claims claims = jwtService.parseAccessToken(token);
                UUID userId = UUID.fromString(claims.getSubject());
                permissionService.requireActiveMember(workspaceId, userId);
                attributes.put(ATTR_WORKSPACE_ID, workspaceId);
                attributes.put(ATTR_USER_ID, userId);
                return true;
            } catch (Exception e) {
                log.debug("workspace event ws handshake rejected workspace={}", workspaceParam);
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}

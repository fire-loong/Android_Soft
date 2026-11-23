package com.actionloop.api.web;

import com.actionloop.api.realtime.WorkspaceEventMessage;
import com.actionloop.api.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 空间事件本地会话注册表：只保存当前 API 进程的 WebSocket 连接。
 * 每次广播和定时重检都会复核成员关系，撤权后连接会被关闭，防止旧订阅继续接收。
 */
@Component
public class WorkspaceSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSessionRegistry.class);

    private final ConcurrentMap<UUID, ConcurrentMap<UUID, Set<WebSocketSession>>> sessions = new ConcurrentHashMap<>();
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    public WorkspaceSessionRegistry(PermissionService permissionService, ObjectMapper objectMapper) {
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    public void register(UUID workspaceId, UUID userId, WebSocketSession session) {
        sessions.computeIfAbsent(workspaceId, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.values().forEach(byUser -> byUser.values().forEach(set -> set.remove(session)));
    }

    public void broadcast(WorkspaceEventMessage message) {
        ConcurrentMap<UUID, Set<WebSocketSession>> byUser = sessions.get(message.workspaceId());
        if (byUser == null || byUser.isEmpty()) {
            return;
        }
        for (var entry : new ArrayList<>(byUser.entrySet())) {
            boolean active;
            try {
                active = isActiveMember(message.workspaceId(), entry.getKey());
            } catch (Exception e) {
                log.debug("workspace membership check failed workspace={} user={}", message.workspaceId(), entry.getKey());
                continue;
            }
            if (!active) {
                closeAll(entry.getValue());
                byUser.remove(entry.getKey());
                continue;
            }
            for (WebSocketSession session : new ArrayList<>(entry.getValue())) {
                send(message, session);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.websocket.session-recheck-ms:30000}")
    public void closeRevokedSessions() {
        for (var workspaceEntry : new ArrayList<>(sessions.entrySet())) {
            UUID workspaceId = workspaceEntry.getKey();
            for (var userEntry : new ArrayList<>(workspaceEntry.getValue().entrySet())) {
                if (!isActiveMember(workspaceId, userEntry.getKey())) {
                    closeAll(userEntry.getValue());
                    workspaceEntry.getValue().remove(userEntry.getKey());
                }
            }
            if (workspaceEntry.getValue().isEmpty()) {
                sessions.remove(workspaceId);
            }
        }
    }

    private boolean isActiveMember(UUID workspaceId, UUID userId) {
        return permissionService.activeMembership(workspaceId, userId).isPresent();
    }

    private void send(WorkspaceEventMessage message, WebSocketSession session) {
        if (!session.isOpen()) {
            unregister(session);
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (IOException e) {
            log.debug("workspace ws send failed workspace={}", message.workspaceId());
            unregister(session);
        }
    }

    private void closeAll(Set<WebSocketSession> target) {
        List<WebSocketSession> snapshot = new ArrayList<>(target);
        for (WebSocketSession session : snapshot) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("membership revoked"));
                }
            } catch (IOException ignored) {
                // 关闭失败不影响其他连接清理
            } finally {
                unregister(session);
            }
        }
    }
}

package com.actionloop.api.service;

import com.actionloop.api.web.dto.AssistEventView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 协助信令 WebSocket 推送。只广播已持久化、已白名单校验的事件；不缓存屏幕帧。 */
@Component
public class AssistBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AssistBroadcaster.class);

    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public AssistBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(UUID sessionId, WebSocketSession ws) {
        sessions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(ws);
    }

    public void unregister(UUID sessionId, WebSocketSession ws) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        if (set != null) {
            set.remove(ws);
            if (set.isEmpty()) {
                sessions.remove(sessionId);
            }
        }
    }

    public void broadcast(UUID sessionId, AssistEventView event) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("type", "assist.event", "data", event));
        } catch (Exception e) {
            return;
        }
        for (WebSocketSession ws : set) {
            try {
                if (ws.isOpen()) {
                    synchronized (ws) {
                        ws.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (IOException e) {
                log.debug("ws send failed session={}", sessionId);
                unregister(sessionId, ws);
            }
        }
    }
}

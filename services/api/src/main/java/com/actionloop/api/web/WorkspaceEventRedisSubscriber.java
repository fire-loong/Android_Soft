package com.actionloop.api.web;

import com.actionloop.api.realtime.WorkspaceEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅 worker 发布的空间事件，并投递给当前 API 进程的本地 WebSocket 会话。 */
@Component
public class WorkspaceEventRedisSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceEventRedisSubscriber.class);

    private final ObjectMapper objectMapper;
    private final WorkspaceSessionRegistry registry;

    public WorkspaceEventRedisSubscriber(ObjectMapper objectMapper, WorkspaceSessionRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            WorkspaceEventMessage event = objectMapper.readValue(message.getBody(), WorkspaceEventMessage.class);
            registry.broadcast(event);
        } catch (Exception e) {
            log.warn("workspace redis event ignored: {}", e.getClass().getSimpleName());
        }
    }
}

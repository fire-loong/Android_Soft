package com.actionloop.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 将空间事件发布到 Redis 频道，供 API 进程订阅并投递本地 WebSocket。 */
@Component
public class RedisWorkspaceEventPublisher implements WorkspaceEventPublisher {

    public static final String CHANNEL = "actionloop:workspace-events";

    private static final Logger log = LoggerFactory.getLogger(RedisWorkspaceEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisWorkspaceEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(WorkspaceEventMessage message) {
        try {
            Long receivers = redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
            if (receivers == null || receivers <= 0) {
                throw new IllegalStateException("OUTBOX_NO_REDIS_SUBSCRIBER");
            }
        } catch (Exception e) {
            log.warn("workspace event publish failed outboxId={} seq={} code={}",
                    message.outboxId(), message.seq(), e.getClass().getSimpleName());
            throw new IllegalStateException("workspace event publish failed: " + e.getMessage(), e);
        }
    }
}

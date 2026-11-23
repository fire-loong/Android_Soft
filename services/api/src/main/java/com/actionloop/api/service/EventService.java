package com.actionloop.api.service;

import com.actionloop.api.domain.OutboxEvent;
import com.actionloop.api.domain.ServerEvent;
import com.actionloop.api.repository.OutboxEventRepository;
import com.actionloop.api.repository.ServerEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 增量事件与 outbox（指导书 11.3/11.4）。
 * 先锁空间行，保证 (workspaceId, seq) 在并发下单调唯一；outbox 与业务同事务写入，由 worker relay。
 */
@Service
public class EventService {

    private final ServerEventRepository serverEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EventService(ServerEventRepository serverEventRepository,
                        OutboxEventRepository outboxEventRepository,
                        JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.serverEventRepository = serverEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void emit(UUID workspaceId, UUID aggregateId, String aggregateType, String eventType,
                     Integer version, Map<String, Object> payload) {
        if (workspaceId == null) {
            return;
        }
        String json = toJson(payload);
        // 行锁串行化同空间事件序号；server_event 与 outbox 必须在同一业务事务内成功或回滚。
        jdbcTemplate.queryForObject("SELECT id FROM workspaces WHERE id = ? FOR UPDATE",
                UUID.class, workspaceId);
        long nextSeq = serverEventRepository.maxSeq(workspaceId) + 1;
        ServerEvent event = new ServerEvent();
        event.setWorkspaceId(workspaceId);
        event.setSeq(nextSeq);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setEventType(eventType);
        event.setVersion(version);
        event.setPayloadMinimized(json);
        ServerEvent persisted = serverEventRepository.saveAndFlush(event);

        OutboxEvent outbox = new OutboxEvent();
        outbox.setAggregateId(aggregateId);
        outbox.setAggregateType(aggregateType);
        outbox.setEventType(eventType);
        outbox.setWorkspaceId(workspaceId);
        outbox.setPayload(json);
        outbox.setServerEventId(persisted.getId());
        outbox.setCreatedAt(Instant.now());
        outboxEventRepository.save(outbox);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}

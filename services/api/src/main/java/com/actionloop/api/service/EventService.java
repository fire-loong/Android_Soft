package com.actionloop.api.service;

import com.actionloop.api.domain.OutboxEvent;
import com.actionloop.api.repository.OutboxEventRepository;
import com.actionloop.api.repository.ServerEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

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
        try {
            // 行锁串行化同空间事件序号，避免并发下 (workspace_id, seq) 唯一冲突
            jdbcTemplate.queryForObject("SELECT id FROM workspaces WHERE id = ? FOR UPDATE",
                    UUID.class, workspaceId);
            Long nextSeq = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) + 1 FROM server_events WHERE workspace_id = ?",
                    Long.class, workspaceId);
            jdbcTemplate.update("""
                            INSERT INTO server_events
                                (workspace_id, seq, aggregate_id, aggregate_type, event_type, version, payload_minimized)
                            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    workspaceId, nextSeq, aggregateId, aggregateType, eventType, version, json);
        } catch (RuntimeException e) {
            // 事件失败不阻断主业务，但必须记录（worker 后续按 outbox 补齐）
            log.warn("server_event emit failed type={} workspace={}", eventType, workspaceId);
        }
        OutboxEvent outbox = new OutboxEvent();
        outbox.setAggregateId(aggregateId);
        outbox.setAggregateType(aggregateType);
        outbox.setEventType(eventType);
        outbox.setWorkspaceId(workspaceId);
        outbox.setPayload(json);
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

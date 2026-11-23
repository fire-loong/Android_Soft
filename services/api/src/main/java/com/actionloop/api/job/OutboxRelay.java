package com.actionloop.api.job;

import com.actionloop.api.realtime.WorkspaceEventMessage;
import com.actionloop.api.realtime.WorkspaceEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbox relay：worker 周期性抢占未发布事件，发布成功后标记 published_at。
 * 抢占时先写 30s 租约（next_retry_at），避免多 worker 重复发布；发布失败按指数退避重试，
 * 超过上限后置 skipped_at，防止单条毒消息永久阻塞后续事件。客户端按 server_seq 去重和补洞。
 */
@Component
@ConditionalOnProperty(prefix = "app.worker.outbox", name = "enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final String workerOwner;

    public OutboxRelay(JdbcTemplate jdbcTemplate,
                       WorkspaceEventPublisher publisher,
                       PlatformTransactionManager transactionManager,
                       @Value("${app.worker.outbox.batch-size:100}") int batchSize,
                       @Value("${app.worker.outbox.max-attempts:20}") int maxAttempts,
                       @Value("${app.worker.owner:worker-local}") String workerOwner) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.workerOwner = workerOwner;
    }

    @Scheduled(fixedDelayString = "${app.worker.outbox.interval-ms:2000}")
    public void scheduled() {
        relayBatch();
    }

    public int relayBatch() {
        int processed = 0;
        Set<Long> failedThisTick = new HashSet<>();
        for (int i = 0; i < batchSize; i++) {
            Optional<Long> claimedId = claimOne();
            if (claimedId.isEmpty()) {
                break;
            }
            Long id = claimedId.get();
            if (failedThisTick.contains(id)) {
                break;
            }
            try {
                relayOne(id);
                processed++;
            } catch (Exception e) {
                recordFailure(id, e);
                failedThisTick.add(id);
            }
        }
        if (processed > 0 || !failedThisTick.isEmpty()) {
            log.debug("outbox relay tick processed={} failed={}", processed, failedThisTick.size());
        }
        return processed;
    }

    private Optional<Long> claimOne() {
        return transactionTemplate.execute(status -> {
            List<Long> ids = jdbcTemplate.queryForList("""
                    WITH ready AS (
                        SELECT id
                        FROM outbox_events
                        WHERE published_at IS NULL
                          AND skipped_at IS NULL
                          AND (next_retry_at IS NULL OR next_retry_at <= now())
                        ORDER BY id ASC
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE outbox_events o
                    SET lease_owner = ?,
                        lease_until = now() + interval '30 seconds',
                        next_retry_at = now() + interval '30 seconds'
                    FROM ready r
                    WHERE o.id = r.id
                    RETURNING o.id
                    """, Long.class, workerOwner);
            return ids.stream().findFirst();
        });
    }

    private void relayOne(Long id) {
        OutboxRow row = loadRow(id);
        if (row.serverEventId() == null) {
            // 历史行无法映射到持久化事件：标记发布但不推送，客户端仍可用 REST cursor 恢复。
            markPublished(id);
            return;
        }
        Long seq = jdbcTemplate.queryForObject(
                "SELECT seq FROM server_events WHERE id = ?", Long.class, row.serverEventId());
        if (seq == null) {
            throw new IllegalStateException("OUTBOX_SERVER_EVENT_MISSING");
        }
        publisher.publish(new WorkspaceEventMessage(id, row.workspaceId(), seq, row.aggregateId(),
                row.aggregateType(), row.eventType(), row.payload(), row.createdAt()));
        markPublished(id);
    }

    private OutboxRow loadRow(Long id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, workspace_id, server_event_id, aggregate_id, aggregate_type, event_type, payload, created_at
                FROM outbox_events
                WHERE id = ?
                """, (rs, rowNum) -> new OutboxRow(
                rs.getLong("id"),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("server_event_id", Long.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()), id);
    }

    private void markPublished(Long id) {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET published_at = now(),
                    lease_owner = NULL,
                    lease_until = NULL,
                    next_retry_at = NULL
                WHERE id = ? AND published_at IS NULL
                """, id);
    }

    private void recordFailure(Long id, Exception e) {
        String code = e.getClass().getSimpleName();
        if (code.length() > 80) {
            code = code.substring(0, 80);
        }
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET attempts = attempts + 1,
                    last_error_code = ?,
                    last_error_at = now(),
                    lease_owner = NULL,
                    lease_until = NULL,
                    next_retry_at = CASE
                        WHEN attempts + 1 >= ? THEN NULL
                        ELSE now() + (LEAST((attempts + 1) * (attempts + 1), 300) || ' seconds')::interval
                    END,
                    skipped_at = CASE WHEN attempts + 1 >= ? THEN now() ELSE NULL END
                WHERE id = ? AND published_at IS NULL
                """, code, maxAttempts, maxAttempts, id);
        log.warn("outbox relay failed id={} code={}", id, code);
    }

    private record OutboxRow(Long id, UUID workspaceId, Long serverEventId, UUID aggregateId,
                             String aggregateType, String eventType, String payload, Instant createdAt) {
    }
}

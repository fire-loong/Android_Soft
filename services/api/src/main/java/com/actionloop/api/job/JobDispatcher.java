package com.actionloop.api.job;

import com.actionloop.api.service.EventService;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** PostgreSQL 事实队列：SKIP LOCKED 抢占、有界租约、崩溃回收、指数退避和死信。 */
@Component
@ConditionalOnProperty(prefix = "app.worker.jobs", name = "enabled", havingValue = "true")
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final EventService eventService;
    private final Map<String, JobHandler> handlers;
    private final String owner;
    private final Duration leaseDuration;
    private final long backoffBaseMillis;
    private final int batchSize;

    public JobDispatcher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager, EventService eventService,
                         List<JobHandler> handlers,
                         @Value("${app.worker.lease-owner:worker-local}") String owner,
                         @Value("${app.worker.jobs.lease-seconds:60}") long leaseSeconds,
                         @Value("${app.worker.jobs.backoff-base-millis:250}") long backoffBaseMillis,
                         @Value("${app.worker.jobs.batch-size:10}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.eventService = eventService;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
        this.owner = owner;
        this.leaseDuration = Duration.ofSeconds(Math.max(5, leaseSeconds));
        this.backoffBaseMillis = Math.max(1, backoffBaseMillis);
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(fixedDelayString = "${app.worker.jobs.interval-ms:500}")
    public void dispatchDue() {
        for (int i = 0; i < batchSize; i++) {
            JobExecution execution = claim();
            if (execution == null) {
                return;
            }
            execute(execution);
        }
    }

    /** 测试与手动触发共用同一真实抢占路径。 */
    public boolean dispatchOne() {
        JobExecution execution = claim();
        if (execution == null) {
            return false;
        }
        execute(execution);
        return true;
    }

    private void execute(JobExecution execution) {
        try {
            JobHandler handler = handlers.get(execution.type());
            if (handler == null) {
                throw new UnsupportedJobTypeException();
            }
            handler.execute(execution);
            complete(execution);
        } catch (RuntimeException e) {
            fail(execution, e);
        }
    }

    private JobExecution claim() {
        return transactionTemplate.execute(status -> {
            List<UUID> ids = jdbcTemplate.queryForList("""
                    SELECT id FROM jobs
                    WHERE attempts < max_attempts
                      AND ((state = 'PENDING' AND available_at <= now())
                           OR (state = 'RUNNING' AND lease_until <= now()))
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """, UUID.class);
            if (ids.isEmpty()) {
                return null;
            }
            UUID id = ids.getFirst();
            Instant leaseUntil = Instant.now().plus(leaseDuration);
            jdbcTemplate.update("""
                    UPDATE jobs SET state = 'RUNNING', lease_owner = ?, lease_until = ?,
                                    attempts = attempts + 1, updated_at = now()
                    WHERE id = ?
                    """, owner, Timestamp.from(leaseUntil), id);
            return jdbcTemplate.queryForObject("""
                    SELECT type, payload_ref, workspace_id, requested_by, attempts, max_attempts,
                           lease_until, p.payload::text
                    FROM jobs j LEFT JOIN job_payloads p ON p.job_id = j.id WHERE j.id = ?
                    """, (rs, rowNum) -> {
                try {
                    String payload = rs.getString(8);
                    JsonNode payloadNode = payload == null ? null : objectMapper.readTree(payload);
                    Timestamp lease = rs.getTimestamp(7);
                    return new JobExecution(id, rs.getString(1), rs.getString(2),
                            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                            rs.getInt(5), rs.getInt(6), owner, lease.toInstant(), payloadNode);
                } catch (Exception e) {
                    throw new IllegalStateException("JOB_CLAIM_READ_FAILED", e);
                }
            }, id);
        });
    }

    private void complete(JobExecution execution) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE jobs SET state = 'SUCCEEDED', lease_owner = null, lease_until = null,
                                    last_error_code = null, last_error_at = null, updated_at = now()
                    WHERE id = ? AND state = 'RUNNING' AND lease_owner = ? AND lease_until = ?
                    """, execution.id(), execution.leaseOwner(), Timestamp.from(execution.leaseUntil()));
            if (updated == 0) {
                return;
            }
            jdbcTemplate.update("DELETE FROM job_payloads WHERE job_id = ?", execution.id());
            emit(execution, "JOB_SUCCEEDED", null, null);
        });
    }

    private void fail(JobExecution execution, RuntimeException failure) {
        String code = errorCode(failure);
        boolean dead = execution.attempts() >= execution.maxAttempts() || !retryable(failure);
        Instant availableAt = Instant.now().plus(backoff(execution.attempts()));
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE jobs SET state = ?, available_at = ?, lease_owner = null, lease_until = null,
                                    last_error_code = ?, last_error_at = now(), updated_at = now()
                    WHERE id = ? AND state = 'RUNNING' AND lease_owner = ? AND lease_until = ?
                    """, dead ? "DEAD_LETTER" : "PENDING", Timestamp.from(availableAt), code,
                    execution.id(), execution.leaseOwner(), Timestamp.from(execution.leaseUntil()));
            if (updated == 0) {
                return;
            }
            emit(execution, dead ? "JOB_FAILED" : "JOB_RETRY_SCHEDULED", code, availableAt);
        });
        if (dead) {
            log.warn("job moved to dead letter id={} type={} attempts={} code={}",
                    execution.id(), execution.type(), execution.attempts(), code);
        } else {
            log.info("job retry scheduled id={} type={} attempts={} code={}",
                    execution.id(), execution.type(), execution.attempts(), code);
        }
    }

    private void emit(JobExecution execution, String type, String errorCode, Instant availableAt) {
        if (execution.workspaceId() == null) {
            return;
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("jobId", execution.id().toString());
        payload.put("jobType", execution.type());
        payload.put("payloadRef", execution.payloadRef());
        payload.put("attempts", execution.attempts());
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        if (availableAt != null) {
            payload.put("availableAt", availableAt.toString());
        }
        eventService.emit(execution.workspaceId(), execution.id(), "JOB", type, null, payload);
    }

    private Duration backoff(int attempts) {
        int shift = Math.max(0, Math.min(attempts - 1, 7));
        return Duration.ofMillis(Math.min(30_000, backoffBaseMillis * (1L << shift)));
    }

    private static boolean retryable(RuntimeException failure) {
        if (failure instanceof BusinessException business) {
            return business.code() == ErrorCode.AI_UNAVAILABLE || business.code() == ErrorCode.OCR_UNAVAILABLE;
        }
        return !(failure instanceof IllegalArgumentException) && !(failure instanceof UnsupportedJobTypeException);
    }

    private static String errorCode(RuntimeException failure) {
        if (failure instanceof BusinessException business) {
            return business.code().name();
        }
        if (failure instanceof UnsupportedJobTypeException) {
            return "JOB_TYPE_UNSUPPORTED";
        }
        if (failure instanceof IllegalArgumentException) {
            return "JOB_PAYLOAD_INVALID";
        }
        return "JOB_EXECUTION_FAILED";
    }

    private static final class UnsupportedJobTypeException extends RuntimeException {
    }
}

package com.actionloop.api.service;

import com.actionloop.api.domain.Job;
import com.actionloop.api.repository.JobRepository;
import com.actionloop.shared.domain.UuidV7;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 与业务写入同事务的 job 入队、授权状态查询和死信人工重试。 */
@Service
@Transactional
public class JobService {

    private final JobRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PermissionService permissionService;

    public JobService(JobRepository repository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                      PermissionService permissionService) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.permissionService = permissionService;
    }

    public Job enqueue(UUID workspaceId, UUID requestedBy, String type, String payloadRef,
                       String idempotencyKey, Map<String, Object> payload) {
        UUID id = UuidV7.generate();
        int inserted = jdbcTemplate.update("""
                INSERT INTO jobs (id, type, payload_ref, workspace_id, requested_by, state,
                                  available_at, attempts, max_attempts, idempotency_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', now(), 0, 5, ?, now(), now())
                ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                """, id, type, payloadRef, workspaceId, requestedBy, idempotencyKey);
        if (inserted == 0) {
            return repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("job idempotency conflict"));
        }
        jdbcTemplate.update("INSERT INTO job_payloads (job_id, payload) VALUES (?, ?::jsonb)",
                id, writePayload(payload));
        return repository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Job getAuthorized(UUID workspaceId, UUID jobId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        return repository.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "异步任务不存在"));
    }

    public Job retry(UUID workspaceId, UUID jobId, UUID userId) {
        Job job = getAuthorized(workspaceId, jobId, userId);
        if (!userId.equals(job.getRequestedBy())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只有任务发起人可以重试");
        }
        if (!"DEAD_LETTER".equals(job.getState())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有死信任务可以人工重试");
        }
        Integer payloads = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_payloads WHERE job_id = ?", Integer.class, jobId);
        if (payloads == null || payloads == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "任务输入已清理，请重新发起请求");
        }
        job.setState("PENDING");
        job.setAttempts(0);
        job.setAvailableAt(Instant.now());
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
        job.setLastErrorCode(null);
        job.setLastErrorAt(null);
        job.setUpdatedAt(Instant.now());
        return repository.save(job);
    }

    @Transactional(readOnly = true)
    public Optional<Job> latestForReference(String payloadRef) {
        return repository.findFirstByPayloadRefOrderByCreatedAtDesc(payloadRef);
    }

    /** 删除来源时使未完成抽取终止，并清除原始输入，禁止后续死信重试还原已删除内容。 */
    public void invalidateSource(UUID workspaceId, UUID sourceId) {
        String ref = "source:" + sourceId;
        jdbcTemplate.update("""
                UPDATE jobs SET state = 'DEAD_LETTER', last_error_code = 'SOURCE_DELETED',
                                last_error_at = now(), lease_owner = null, lease_until = null, updated_at = now()
                WHERE workspace_id = ? AND payload_ref = ? AND state IN ('PENDING', 'RUNNING')
                """, workspaceId, ref);
        jdbcTemplate.update("DELETE FROM job_payloads WHERE job_id IN "
                + "(SELECT id FROM jobs WHERE workspace_id = ? AND payload_ref = ?)", workspaceId, ref);
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "异步任务输入无法序列化");
        }
    }
}

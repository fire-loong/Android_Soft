package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Job;

import java.time.Instant;
import java.util.UUID;

/** 不含原始输入、模型密钥或异常堆栈的可授权任务状态。 */
public record JobView(
        UUID id,
        String type,
        String state,
        UUID workspaceId,
        String payloadRef,
        Integer attempts,
        Integer maxAttempts,
        String lastErrorCode,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static JobView of(Job job) {
        return new JobView(job.getId(), job.getType(), job.getState(), job.getWorkspaceId(), job.getPayloadRef(),
                job.getAttempts(), job.getMaxAttempts(), job.getLastErrorCode(), job.getAvailableAt(),
                job.getCreatedAt(), job.getUpdatedAt());
    }
}

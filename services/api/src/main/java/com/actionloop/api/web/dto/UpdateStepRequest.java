package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TaskStepStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** PATCH：null 字段表示不变（指导书 13.1）。带 baseVersion 乐观锁。 */
public record UpdateStepRequest(
        @NotNull Integer baseVersion,
        String title,
        String instruction,
        Integer position,
        UUID assigneeId,
        TaskStepStatus status,
        String completionRuleJson
) {
}

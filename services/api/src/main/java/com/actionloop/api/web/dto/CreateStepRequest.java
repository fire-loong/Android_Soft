package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TaskStepStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateStepRequest(
        @NotBlank String title,
        String instruction,
        Integer position,
        UUID assigneeId,
        String completionRuleJson,
        TaskStepStatus status
) {
}

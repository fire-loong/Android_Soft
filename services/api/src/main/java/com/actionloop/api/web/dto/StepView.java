package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TaskStep;
import com.actionloop.api.domain.TaskStepStatus;

import java.time.Instant;
import java.util.UUID;

public record StepView(
        UUID id,
        UUID taskId,
        UUID processStepId,
        String title,
        String instruction,
        Integer position,
        UUID assigneeId,
        TaskStepStatus status,
        String completionRuleJson,
        Integer version,
        Instant createdAt,
        Instant updatedAt
) {
    public static StepView of(TaskStep s) {
        return new StepView(s.getId(), s.getTaskId(), s.getProcessStepId(), s.getTitle(),
                s.getInstruction(), s.getPosition(), s.getAssigneeId(), s.getStatus(),
                s.getCompletionRuleJson(), s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
    }
}

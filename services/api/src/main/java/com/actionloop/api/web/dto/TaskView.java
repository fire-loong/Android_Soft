package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.domain.TimePrecision;
import com.actionloop.api.service.TaskStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 事项视图，含乐观锁版本号供客户端回传 baseVersion，以及状态机允许的下一状态集合。 */
public record TaskView(
        UUID id,
        UUID workspaceId,
        String title,
        TaskStatus status,
        UUID ownerId,
        UUID assigneeId,
        Instant dueAt,
        LocalDate dueDate,
        String dueTimezone,
        TimePrecision timePrecision,
        Integer priority,
        String description,
        Derivation derivation,
        BigDecimal confidence,
        String modelVersion,
        String promptVersion,
        Integer version,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        List<TaskStatus> allowedTransitions
) {
    public static TaskView of(Task t) {
        return new TaskView(t.getId(), t.getWorkspaceId(), t.getTitle(), t.getStatus(),
                t.getOwnerId(), t.getAssigneeId(), t.getDueAt(), t.getDueDate(), t.getDueTimezone(),
                t.getTimePrecision(), t.getPriority(), t.getDescription(), t.getDerivation(),
                t.getConfidence(), t.getModelVersion(), t.getPromptVersion(),
                t.getVersion(), t.getCreatedAt(), t.getUpdatedAt(), t.getCreatedBy(),
                new ArrayList<>(TaskStateMachine.allowedTransitions(t.getStatus())));
    }
}

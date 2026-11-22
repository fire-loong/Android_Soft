package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.domain.TimePrecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 新建事项。status 只能是初始允许状态（DRAFT/PENDING_CONFIRMATION/ACTIVE），缺省 ACTIVE。 */
public record CreateTaskRequest(
        @NotNull UUID workspaceId,
        @NotBlank @Size(max = 128) String title,
        TaskStatus status,
        UUID assigneeId,
        Instant dueAt,
        LocalDate dueDate,
        String dueTimezone,
        TimePrecision timePrecision,
        Integer priority,
        @Size(max = 4000) String description
) {
}

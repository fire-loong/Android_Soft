package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TimePrecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 更新事项字段。注意：status 不在这里改，状态变更必须走 /transitions 状态机。
 * baseVersion 必填，用于乐观锁冲突检测（返回 VERSION_CONFLICT）。
 */
public record UpdateTaskRequest(
        @NotNull Integer baseVersion,
        @Size(max = 128) String title,
        UUID assigneeId,
        Instant dueAt,
        LocalDate dueDate,
        String dueTimezone,
        TimePrecision timePrecision,
        Integer priority,
        @Size(max = 4000) String description
) {
}

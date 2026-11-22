package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TimePrecision;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 确认候选：必须带 baseVersion；日期存在冲突或字段置信不足时须显式 dueDateConfirmed=true。
 * 提醒只在确认事务内创建；未确认候选永不产生强提醒。
 */
public record InboxConfirmRequest(
        @NotNull Integer baseVersion,
        String title,
        Instant dueAt,
        LocalDate dueDate,
        String dueTimezone,
        TimePrecision timePrecision,
        UUID assigneeId,
        Boolean dueDateConfirmed,
        String notes,
        List<ReminderInput> reminders,
        List<String> steps,
        List<String> materials
) {
}

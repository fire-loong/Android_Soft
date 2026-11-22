package com.actionloop.api.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** PUT /tasks/{id}/reminders 整体替换：旧计划取消，按 dedupeKey 去重（指导书 13.2）。 */
public record ReplaceRemindersRequest(
        @NotNull List<@Valid ReminderInput> reminders
) {
}

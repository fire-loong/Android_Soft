package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Reminder;
import com.actionloop.api.domain.ReminderChannel;
import com.actionloop.api.domain.ReminderState;

import java.time.Instant;
import java.util.UUID;

public record ReminderView(
        UUID id,
        UUID taskId,
        Instant triggerAt,
        ReminderChannel channel,
        ReminderState state,
        String dedupeKey,
        Instant createdAt
) {
    public static ReminderView of(Reminder r) {
        return new ReminderView(r.getId(), r.getTaskId(), r.getTriggerAt(), r.getChannel(),
                r.getState(), r.getDedupeKey(), r.getCreatedAt());
    }
}

package com.actionloop.api.web.dto;

import com.actionloop.api.domain.ReminderChannel;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ReminderInput(
        @NotNull Instant triggerAt,
        @NotNull ReminderChannel channel
) {
}

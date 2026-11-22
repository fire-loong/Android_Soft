package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TimePrecision;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InstantiateProcessRequest(
        @NotNull UUID workspaceId,
        String title,
        UUID assigneeId,
        Instant dueAt,
        LocalDate dueDate,
        String dueTimezone,
        TimePrecision timePrecision
) {
}

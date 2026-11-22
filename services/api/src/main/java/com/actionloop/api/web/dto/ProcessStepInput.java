package com.actionloop.api.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ProcessStepInput(
        @NotBlank @Size(max = 1000) String instruction,
        Boolean required,
        @Size(max = 2048) String officialEntryUrl,
        List<UUID> sourceRefIds
) {
}

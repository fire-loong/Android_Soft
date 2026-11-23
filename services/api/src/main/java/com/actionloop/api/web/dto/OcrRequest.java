package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OcrRequest(
        @NotNull UUID workspaceId,
        @NotNull Boolean cloudAuthorized,
        @Size(max = 64) String model
) {
}

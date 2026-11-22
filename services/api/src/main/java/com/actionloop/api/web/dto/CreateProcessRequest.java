package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProcessRequest(
        @NotNull UUID workspaceId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 32) String regionCode,
        @Size(max = 4000) String scopeJson
) {
}

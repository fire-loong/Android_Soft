package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateExportRequest(@NotNull UUID workspaceId, String exportType) {
}

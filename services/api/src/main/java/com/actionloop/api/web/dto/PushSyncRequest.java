package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** POST /sync/push 请求体：批量离线操作。 */
public record PushSyncRequest(
        @NotEmpty List<SyncOpInput> operations
) {
}

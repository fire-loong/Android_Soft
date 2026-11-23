package com.actionloop.api.ai;

import java.time.Instant;
import java.util.UUID;

public record ExtractionRequest(
        String text,
        Instant receivedAt,
        String timezone,
        String sourceApp,
        String sourceTitle,
        UUID workspaceId,
        UUID sourceItemId,
        UUID requestedBy,
        String traceId
) {
}

package com.actionloop.api.ai;

import java.time.Instant;

public record ExtractionRequest(
        String text,
        Instant receivedAt,
        String timezone,
        String sourceApp,
        String sourceTitle
) {
}

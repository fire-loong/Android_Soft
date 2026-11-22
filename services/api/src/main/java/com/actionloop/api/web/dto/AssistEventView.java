package com.actionloop.api.web.dto;

import com.actionloop.api.domain.AssistEvent;

import java.time.Instant;
import java.util.UUID;

public record AssistEventView(
        UUID sessionId,
        long seq,
        String eventType,
        String payloadJson,
        Instant createdAt
) {
    public static AssistEventView of(AssistEvent e) {
        return new AssistEventView(e.getSessionId(), e.getSeq(), e.getEventType(),
                e.getPayloadMinimized(), e.getCreatedAt());
    }
}

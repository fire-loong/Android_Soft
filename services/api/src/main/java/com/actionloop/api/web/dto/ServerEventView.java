package com.actionloop.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/** 空间增量事件（指导书 11.4）：Android/Web 断线后按 afterSeq 补洞。 */
public record ServerEventView(
        long seq,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        Integer version,
        String payloadJson,
        Instant createdAt
) {
}

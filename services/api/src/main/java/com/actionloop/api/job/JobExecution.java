package com.actionloop.api.job;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** 一次租约内的不可变执行快照；leaseUntil 防止过期 worker 回写新租约。 */
public record JobExecution(
        UUID id,
        String type,
        String payloadRef,
        UUID workspaceId,
        UUID requestedBy,
        Integer attempts,
        Integer maxAttempts,
        String leaseOwner,
        Instant leaseUntil,
        JsonNode payload
) {
    public String requiredText(String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("JOB_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    public String optionalText(String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    public UUID requiredUuid(String field) {
        try {
            return UUID.fromString(requiredText(field));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("JOB_PAYLOAD_INVALID", e);
        }
    }
}

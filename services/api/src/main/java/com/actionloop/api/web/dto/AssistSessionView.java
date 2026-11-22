package com.actionloop.api.web.dto;

import com.actionloop.api.domain.AssistSession;
import com.actionloop.api.domain.AssistSessionState;

import java.time.Instant;
import java.util.UUID;

public record AssistSessionView(
        UUID id,
        UUID requesterId,
        UUID helperId,
        UUID taskStepId,
        String mode,
        AssistSessionState state,
        String scopesJson,
        Instant expiresAt,
        String stoppedReason,
        Instant createdAt
) {
    public static AssistSessionView of(AssistSession s) {
        return new AssistSessionView(s.getId(), s.getRequesterId(), s.getHelperId(), s.getTaskStepId(),
                s.getMode(), s.getState(), s.getScopesJson(), s.getExpiresAt(), s.getStoppedReason(),
                s.getCreatedAt());
    }
}

package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Completion;
import com.actionloop.api.domain.ReviewState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CompletionView(
        UUID id,
        UUID taskStepId,
        UUID submitterId,
        String statement,
        ReviewState reviewState,
        UUID reviewerId,
        String reviewReason,
        Instant submittedAt,
        Instant reviewedAt,
        List<UUID> assetIds
) {
    public static CompletionView of(Completion c, List<UUID> assetIds) {
        return new CompletionView(c.getId(), c.getTaskStepId(), c.getSubmitterId(), c.getStatement(),
                c.getReviewState(), c.getReviewerId(), c.getReviewReason(), c.getSubmittedAt(),
                c.getReviewedAt(), assetIds);
    }
}

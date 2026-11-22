package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotNull;

/** 核验通过/驳回（指导书 13.2 POST /completions/{id}/review）。仅授权核验人。 */
public record ReviewCompletionRequest(
        @NotNull Boolean approve,
        String reason
) {
}

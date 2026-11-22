package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotNull;

public record DismissCandidateRequest(
        @NotNull Integer baseVersion,
        String reason
) {
}

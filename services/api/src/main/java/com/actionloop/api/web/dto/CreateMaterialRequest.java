package com.actionloop.api.web.dto;

import com.actionloop.api.domain.MaterialState;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateMaterialRequest(
        @NotBlank String name,
        String requiredConditionJson,
        String formatRuleJson,
        MaterialState state,
        UUID evidenceRefId,
        UUID assetId
) {
}

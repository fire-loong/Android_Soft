package com.actionloop.api.web.dto;

import com.actionloop.api.domain.MaterialState;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateMaterialRequest(
        @NotNull Integer baseVersion,
        String name,
        String requiredConditionJson,
        String formatRuleJson,
        MaterialState state,
        UUID evidenceRefId,
        UUID assetId
) {
}

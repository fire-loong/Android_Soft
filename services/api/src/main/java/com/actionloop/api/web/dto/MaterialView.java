package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Material;
import com.actionloop.api.domain.MaterialState;

import java.time.Instant;
import java.util.UUID;

public record MaterialView(
        UUID id,
        UUID taskId,
        String name,
        String requiredConditionJson,
        String formatRuleJson,
        MaterialState state,
        UUID evidenceRefId,
        UUID assetId,
        Integer version,
        Instant createdAt,
        Instant updatedAt
) {
    public static MaterialView of(Material m) {
        return new MaterialView(m.getId(), m.getTaskId(), m.getName(), m.getRequiredConditionJson(),
                m.getFormatRuleJson(), m.getState(), m.getEvidenceRefId(), m.getAssetId(),
                m.getVersion(), m.getCreatedAt(), m.getUpdatedAt());
    }
}

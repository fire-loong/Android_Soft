package com.actionloop.api.web.dto;

import com.actionloop.api.domain.OcrResult;

import java.time.Instant;
import java.util.UUID;

public record OcrResultView(
        UUID id,
        UUID assetId,
        String provider,
        String model,
        String promptVersion,
        String inputSha256,
        String text,
        boolean coordinatesAvailable,
        UUID createdBy,
        Instant createdAt
) {
    public static OcrResultView of(OcrResult result) {
        return new OcrResultView(result.getId(), result.getAssetId(), result.getProvider(), result.getModel(),
                result.getPromptVersion(), result.getInputSha256(), result.getText(),
                result.isCoordinatesAvailable(), result.getCreatedBy(), result.getCreatedAt());
    }
}

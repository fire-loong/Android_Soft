package com.actionloop.api.web.dto;

import com.actionloop.api.domain.EvidenceAsset;
import com.actionloop.api.domain.ScanState;

import java.time.Instant;
import java.util.UUID;

public record AssetView(
        UUID id,
        UUID workspaceId,
        String sha256,
        String mime,
        Long sizeBytes,
        Integer pageCount,
        ScanState scanState,
        String ocrFailureCode,
        String retentionClass,
        Instant createdAt
) {
    public static AssetView of(EvidenceAsset a) {
        return new AssetView(a.getId(), a.getWorkspaceId(), a.getSha256(), a.getMime(),
                a.getSizeBytes(), a.getPageCount(), a.getScanState(), a.getOcrFailureCode(),
                a.getRetentionClass(), a.getCreatedAt());
    }
}

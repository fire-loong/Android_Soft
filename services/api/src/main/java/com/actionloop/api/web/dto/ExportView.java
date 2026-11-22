package com.actionloop.api.web.dto;

import com.actionloop.api.domain.ExportJob;

import java.time.Instant;
import java.util.UUID;

public record ExportView(
        UUID id,
        String state,
        Long sizeBytes,
        String errorCode,
        Instant expiresAt,
        Instant createdAt,
        String downloadPath
) {
    public static ExportView of(ExportJob j) {
        return new ExportView(j.getId(), j.getState(), j.getSizeBytes(), j.getErrorCode(),
                j.getExpiresAt(), j.getCreatedAt(),
                "READY".equals(j.getState()) ? "/api/v1/exports/" + j.getId() + "/download" : null);
    }
}

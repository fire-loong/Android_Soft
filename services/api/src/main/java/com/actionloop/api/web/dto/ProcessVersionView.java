package com.actionloop.api.web.dto;

import com.actionloop.api.domain.ProcessReviewState;
import com.actionloop.api.domain.ProcessVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProcessVersionView(
        UUID id,
        UUID templateId,
        Integer versionNo,
        ProcessReviewState reviewState,
        UUID publishedBy,
        Instant publishedAt,
        String sourceSnapshotHash,
        Integer version,
        List<ProcessStepView> steps
) {
    public static ProcessVersionView of(ProcessVersion v, List<ProcessStepView> steps) {
        return new ProcessVersionView(v.getId(), v.getTemplateId(), v.getVersionNo(), v.getReviewState(),
                v.getPublishedBy(), v.getPublishedAt(), v.getSourceSnapshotHash(), v.getVersion(), steps);
    }
}

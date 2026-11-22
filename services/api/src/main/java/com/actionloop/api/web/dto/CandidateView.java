package com.actionloop.api.web.dto;

import com.actionloop.api.domain.CandidateStatus;
import com.actionloop.api.domain.CandidateTask;
import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.TimePrecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 候选卡片（指导书 4.3）：逐字段置信、原文跳转、冲突原因。 */
public record CandidateView(
        UUID id,
        UUID workspaceId,
        UUID sourceItemId,
        String title,
        String summary,
        String category,
        Instant dueAt,
        LocalDate dueDate,
        String dueTimezone,
        TimePrecision timePrecision,
        String location,
        String materialsJson,
        String nextAction,
        BigDecimal confidence,
        String fieldConfidenceJson,
        String reasonsJson,
        Derivation derivation,
        String modelVersion,
        String promptVersion,
        CandidateStatus status,
        UUID confirmedTaskId,
        String dismissedReason,
        Integer version,
        Instant createdAt,
        String sourceApp,
        String sourcePreview
) {
    public static CandidateView of(CandidateTask c, String sourceApp, String sourcePreview) {
        return new CandidateView(c.getId(), c.getWorkspaceId(), c.getSourceItemId(), c.getTitle(),
                c.getSummary(), c.getCategory(), c.getDueAt(), c.getDueDate(), c.getDueTimezone(),
                c.getTimePrecision(), c.getLocation(), c.getMaterialsJson(), c.getNextAction(),
                c.getConfidence(), c.getFieldConfidenceJson(), c.getReasonsJson(), c.getDerivation(),
                c.getModelVersion(), c.getPromptVersion(), c.getStatus(), c.getConfirmedTaskId(),
                c.getDismissedReason(), c.getVersion(), c.getCreatedAt(), sourceApp, sourcePreview);
    }
}

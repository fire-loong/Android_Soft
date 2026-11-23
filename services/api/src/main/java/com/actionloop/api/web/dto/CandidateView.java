package com.actionloop.api.web.dto;

import com.actionloop.api.domain.CandidateStatus;
import com.actionloop.api.domain.CandidateTask;
import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.TimePrecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 候选卡片：逐字段置信、原文引用、原始日期、冲突原因和模型/Schema 版本。 */
public record CandidateView(
        UUID id,
        UUID workspaceId,
        UUID sourceItemId,
        String title,
        String summary,
        String category,
        String rawDueText,
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
        String evidenceQuotesJson,
        String uncertaintiesJson,
        Derivation derivation,
        String modelVersion,
        String promptVersion,
        String schemaVersion,
        boolean needsUserConfirmation,
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
                c.getSummary(), c.getCategory(), c.getRawDueText(), c.getDueAt(), c.getDueDate(),
                c.getDueTimezone(), c.getTimePrecision(), c.getLocation(), c.getMaterialsJson(),
                c.getNextAction(), c.getConfidence(), c.getFieldConfidenceJson(), c.getReasonsJson(),
                c.getEvidenceQuotesJson(), c.getUncertaintiesJson(), c.getDerivation(), c.getModelVersion(),
                c.getPromptVersion(), c.getSchemaVersion(), c.isNeedsUserConfirmation(), c.getStatus(),
                c.getConfirmedTaskId(), c.getDismissedReason(), c.getVersion(), c.getCreatedAt(),
                sourceApp, sourcePreview);
    }
}

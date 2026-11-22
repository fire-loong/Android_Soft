package com.actionloop.api.web.dto;

import com.actionloop.api.domain.PrivacyLevel;
import com.actionloop.api.domain.SourceItem;
import com.actionloop.api.domain.SourceState;
import com.actionloop.api.domain.SourceType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 来源详情（不含 ciphertext 与完整原文之外的敏感字段）。 */
public record SourceView(
        UUID id,
        UUID workspaceId,
        SourceType sourceType,
        String sourceApp,
        String sourceTitle,
        String preview,
        String linkUrl,
        PrivacyLevel privacyLevel,
        SourceState sourceState,
        Instant receivedAt,
        String contentHash,
        Instant lastCheckedAt,
        Integer version,
        List<EvidenceRefView> evidence,
        List<UUID> candidateIds
) {
    public static SourceView of(SourceItem s, List<EvidenceRefView> evidence, List<UUID> candidateIds) {
        return new SourceView(s.getId(), s.getWorkspaceId(), s.getSourceType(), s.getSourceApp(),
                s.getSourceTitle(), s.getContentPreview(), s.getLinkUrl(), s.getPrivacyLevel(),
                s.getSourceState(), s.getReceivedAt(), s.getContentHash(), s.getLastCheckedAt(),
                s.getVersion(), evidence, candidateIds);
    }
}

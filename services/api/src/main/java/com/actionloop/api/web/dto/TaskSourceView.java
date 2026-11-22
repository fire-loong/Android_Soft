package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.SourceItem;
import com.actionloop.api.domain.TaskSource;
import com.actionloop.api.domain.TaskSourceRelation;

import java.time.Instant;
import java.util.UUID;

/**
 * 事项↔来源关系视图（指导书 3.1 / 9.2“证据回溯”）。
 * 事项详情页据此展示原文引文与来源状态，不暴露来源密文。
 */
public record TaskSourceView(
        UUID id,
        UUID taskId,
        UUID sourceItemId,
        TaskSourceRelation relationType,
        String quote,
        Derivation derivation,
        Instant createdAt,
        String sourceType,
        String sourceTitle,
        String sourceApp,
        String sourceState,
        String preview
) {
    public static TaskSourceView of(TaskSource link, SourceItem source) {
        return new TaskSourceView(link.getId(), link.getTaskId(), link.getSourceItemId(),
                link.getRelationType(), link.getQuote(), link.getDerivation(), link.getCreatedAt(),
                source == null ? null : source.getSourceType().name(),
                source == null ? null : source.getSourceTitle(),
                source == null ? null : source.getSourceApp(),
                source == null ? null : source.getSourceState().name(),
                source == null ? null : source.getContentPreview());
    }
}

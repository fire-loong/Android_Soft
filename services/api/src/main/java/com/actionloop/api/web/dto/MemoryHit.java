package com.actionloop.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/** 记忆检索命中：返回引用坐标，不返回无权限内容。 */
public record MemoryHit(
        UUID documentId,
        String title,
        String snippet,
        String docType,
        UUID sourceItemId,
        UUID assetId,
        double score,
        Instant createdAt,
        String privacyNote
) {
}

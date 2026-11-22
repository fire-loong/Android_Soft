package com.actionloop.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/** 来源复查结果：变化时保留旧引用、标记新快照，要求用户重新确认关键日期。 */
public record SourceRecheckResponse(
        UUID sourceId,
        boolean changed,
        String previousHash,
        String currentHash,
        String preview,
        Instant checkedAt,
        boolean requiresReconfirmation
) {
}

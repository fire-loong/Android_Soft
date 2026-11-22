package com.actionloop.api.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一个来源的确认结果：事项 + 已创建的步骤/提醒数，便于客户端定位。 */
public record InboxConfirmResponse(
        UUID taskId,
        UUID candidateId,
        Integer taskVersion,
        int stepCount,
        int reminderCount,
        List<UUID> evidenceRefIds,
        Instant createdAt
) {
}

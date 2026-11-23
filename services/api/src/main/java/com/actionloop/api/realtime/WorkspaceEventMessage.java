package com.actionloop.api.realtime;

import java.time.Instant;
import java.util.UUID;

/**
 * 跨进程空间事件：Redis 只做传输，seq 来自 server_events，客户端按 seq 去重和补洞。
 */
public record WorkspaceEventMessage(
        Long outboxId,
        UUID workspaceId,
        Long seq,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payload,
        Instant createdAt
) {
}

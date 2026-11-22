package com.actionloop.api.web.dto;

import com.actionloop.api.domain.SyncOperation;

import java.time.Instant;
import java.util.UUID;

/** 同步操作结果视图：重放首次结果时原样返回，客户端据此判断 SYNCED/CONFLICT/FAILED。 */
public record SyncOperationView(
        UUID opId,
        String aggregateType,
        UUID aggregateId,
        String operationType,
        Integer baseVersion,
        Integer serverVersion,
        String state,
        String errorCode,
        Instant createdAt
) {
    public static SyncOperationView of(SyncOperation op) {
        return new SyncOperationView(
                op.getOpId(),
                op.getAggregateType(),
                op.getAggregateId(),
                op.getOperationType(),
                op.getBaseVersion(),
                op.getServerVersion(),
                op.getState().name(),
                op.getErrorCode(),
                op.getCreatedAt()
        );
    }
}

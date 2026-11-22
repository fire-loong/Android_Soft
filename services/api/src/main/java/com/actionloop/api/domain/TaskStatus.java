package com.actionloop.api.domain;

/**
 * 事项状态机，合法转换由服务端 {@code TaskStateMachine} 校验（指导书 3.3）。
 */
public enum TaskStatus {
    DRAFT,
    PENDING_CONFIRMATION,
    ACTIVE,
    IN_PROGRESS,
    BLOCKED,
    PENDING_REVIEW,
    COMPLETED,
    CANCELLED,
    DISMISSED
}

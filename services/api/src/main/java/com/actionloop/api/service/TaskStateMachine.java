package com.actionloop.api.service;

import com.actionloop.api.domain.TaskStatus;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * 事项状态机（指导书 3.3）。服务端强制校验，不允许前端任意赋值 status。
 * 非法转换抛 {@link ErrorCode#INVALID_STATE_TRANSITION}。
 *
 * <pre>
 * [*] → DRAFT
 * DRAFT → PENDING_CONFIRMATION
 * PENDING_CONFIRMATION → ACTIVE | DISMISSED
 * ACTIVE → IN_PROGRESS | PENDING_REVIEW | CANCELLED
 * IN_PROGRESS → BLOCKED | PENDING_REVIEW | CANCELLED
 * BLOCKED → IN_PROGRESS
 * PENDING_REVIEW → COMPLETED | IN_PROGRESS
 * COMPLETED → IN_PROGRESS
 * DISMISSED / CANCELLED：终态
 * </pre>
 */
@Service
public class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.ofEntries(
            entry(TaskStatus.DRAFT, Set.of(TaskStatus.PENDING_CONFIRMATION)),
            entry(TaskStatus.PENDING_CONFIRMATION, Set.of(TaskStatus.ACTIVE, TaskStatus.DISMISSED)),
            entry(TaskStatus.ACTIVE, Set.of(TaskStatus.IN_PROGRESS, TaskStatus.PENDING_REVIEW, TaskStatus.CANCELLED)),
            entry(TaskStatus.IN_PROGRESS, Set.of(TaskStatus.BLOCKED, TaskStatus.PENDING_REVIEW, TaskStatus.CANCELLED)),
            entry(TaskStatus.BLOCKED, Set.of(TaskStatus.IN_PROGRESS)),
            entry(TaskStatus.PENDING_REVIEW, Set.of(TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS)),
            entry(TaskStatus.COMPLETED, Set.of(TaskStatus.IN_PROGRESS))
            // DISMISSED / CANCELLED 无出边（终态）
    );

    /** 创建时允许的初始状态：草稿、待确认候选、或用户直接建立的已确认事项。 */
    private static final Set<TaskStatus> INITIAL_ALLOWED =
            Set.of(TaskStatus.DRAFT, TaskStatus.PENDING_CONFIRMATION, TaskStatus.ACTIVE);

    public boolean canTransition(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 当前状态可转换到的目标状态集合（供 TaskView 下发，前端只渲染合法入口；
     * 服务端 requireTransition 仍是最终强制校验，前端提示不构成安全边界）。
     */
    public static Set<TaskStatus> allowedTransitions(TaskStatus from) {
        if (from == null) {
            return Set.of();
        }
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public void requireTransition(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "不允许将事项从 " + from + " 转换为 " + to);
        }
    }

    public boolean isInitialAllowed(TaskStatus status) {
        return status != null && INITIAL_ALLOWED.contains(status);
    }
}

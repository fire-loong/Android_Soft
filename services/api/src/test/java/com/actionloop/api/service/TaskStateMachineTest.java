package com.actionloop.api.service;

import com.actionloop.api.domain.TaskStatus;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机全分支纯单元测试（无 Spring 上下文，CI/本地均必跑）。
 * 覆盖：初始允许集、矩阵中每条合法转换、所有非法转换、requireTransition 行为。
 */
class TaskStateMachineTest {

    private final TaskStateMachine sm = new TaskStateMachine();

    @Test
    void initialAllowedSet() {
        assertTrue(sm.isInitialAllowed(TaskStatus.DRAFT));
        assertTrue(sm.isInitialAllowed(TaskStatus.PENDING_CONFIRMATION));
        assertTrue(sm.isInitialAllowed(TaskStatus.ACTIVE));
        assertFalse(sm.isInitialAllowed(TaskStatus.IN_PROGRESS));
        assertFalse(sm.isInitialAllowed(TaskStatus.COMPLETED));
        assertFalse(sm.isInitialAllowed(TaskStatus.DISMISSED));
        assertFalse(sm.isInitialAllowed(TaskStatus.CANCELLED));
        assertFalse(sm.isInitialAllowed(null));
    }

    @Test
    void everyDeclaredTransitionIsAllowed() {
        assertCan(TaskStatus.DRAFT, TaskStatus.PENDING_CONFIRMATION);
        assertCan(TaskStatus.PENDING_CONFIRMATION, TaskStatus.ACTIVE);
        assertCan(TaskStatus.PENDING_CONFIRMATION, TaskStatus.DISMISSED);
        assertCan(TaskStatus.ACTIVE, TaskStatus.IN_PROGRESS);
        assertCan(TaskStatus.ACTIVE, TaskStatus.PENDING_REVIEW);
        assertCan(TaskStatus.ACTIVE, TaskStatus.CANCELLED);
        assertCan(TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED);
        assertCan(TaskStatus.IN_PROGRESS, TaskStatus.PENDING_REVIEW);
        assertCan(TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED);
        assertCan(TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS);
        assertCan(TaskStatus.PENDING_REVIEW, TaskStatus.COMPLETED);
        assertCan(TaskStatus.PENDING_REVIEW, TaskStatus.IN_PROGRESS);
        assertCan(TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS);
    }

    @Test
    void forbiddenTransitionsAreRejected() {
        assertCannot(TaskStatus.DRAFT, TaskStatus.ACTIVE);
        assertCannot(TaskStatus.DRAFT, TaskStatus.IN_PROGRESS);
        assertCannot(TaskStatus.DRAFT, TaskStatus.COMPLETED);
        assertCannot(TaskStatus.PENDING_CONFIRMATION, TaskStatus.IN_PROGRESS);
        assertCannot(TaskStatus.ACTIVE, TaskStatus.COMPLETED);
        assertCannot(TaskStatus.ACTIVE, TaskStatus.DISMISSED);
        assertCannot(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);
        assertCannot(TaskStatus.IN_PROGRESS, TaskStatus.ACTIVE);
        assertCannot(TaskStatus.BLOCKED, TaskStatus.COMPLETED);
        assertCannot(TaskStatus.PENDING_REVIEW, TaskStatus.CANCELLED);
        // 终态无出边
        assertCannot(TaskStatus.DISMISSED, TaskStatus.ACTIVE);
        assertCannot(TaskStatus.DISMISSED, TaskStatus.IN_PROGRESS);
        assertCannot(TaskStatus.CANCELLED, TaskStatus.IN_PROGRESS);
        assertCannot(TaskStatus.COMPLETED, TaskStatus.COMPLETED);
        // null 边界
        assertCannot(null, TaskStatus.ACTIVE);
        assertCannot(TaskStatus.ACTIVE, null);
    }

    @Test
    void requireTransitionThrowsInvalidStateOnIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> sm.requireTransition(TaskStatus.ACTIVE, TaskStatus.COMPLETED));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.code());
    }

    @Test
    void requireTransitionPassesOnLegal() {
        assertDoesNotThrow(() -> sm.requireTransition(TaskStatus.ACTIVE, TaskStatus.IN_PROGRESS));
        assertDoesNotThrow(() -> sm.requireTransition(TaskStatus.PENDING_REVIEW, TaskStatus.COMPLETED));
    }

    private void assertCan(TaskStatus from, TaskStatus to) {
        assertTrue(sm.canTransition(from, to), from + " -> " + to + " should be allowed");
        assertDoesNotThrow(() -> sm.requireTransition(from, to));
    }

    private void assertCannot(TaskStatus from, TaskStatus to) {
        assertFalse(sm.canTransition(from, to), from + " -> " + to + " should be forbidden");
        if (from != null && to != null) {
            assertThrows(BusinessException.class, () -> sm.requireTransition(from, to));
        }
    }
}

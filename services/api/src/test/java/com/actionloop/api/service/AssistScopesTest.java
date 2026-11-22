package com.actionloop.api.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协助信令白名单纯单元测试（指导书 13.5，无 Spring 上下文）。
 *
 * 目的：把「DB CHECK 约束允许的事件」与「服务层白名单」锁死在同一集合，
 * 避免出现 DB 允许、服务层拒绝（500/400 混淆）或服务层放行 DB 不认的漂移。
 */
class AssistScopesTest {

    /** 13.5 原文列出的允许事件。 */
    private static final List<String> GUIDE_13_5_ALLOWED = List.of(
            "OFFER", "ANSWER", "ICE_CANDIDATE", "HIGHLIGHT", "TEXT_HINT",
            "PAUSE", "RESUME_REQUEST", "STOP", "HEARTBEAT", "QUALITY");

    private static final List<String> FORBIDDEN_CONTROL = List.of(
            "CLICK", "TAP", "SWIPE", "INPUT", "COMMIT_PAYMENT", "KEY_EVENT", "DISPATCH_GESTURE");

    @Test
    void guideAllowedEventsAreAccepted() {
        for (String event : GUIDE_13_5_ALLOWED) {
            assertTrue(AssistScopes.isAllowedEvent(event), "13.5 要求放行: " + event);
            assertFalse(AssistScopes.isForbiddenEvent(event), "13.5 事件不得被列为禁止: " + event);
        }
    }

    @Test
    void controlEventsAreAlwaysForbiddenAndNeverAllowed() {
        for (String event : FORBIDDEN_CONTROL) {
            assertTrue(AssistScopes.isForbiddenEvent(event), "必须禁止: " + event);
            assertFalse(AssistScopes.isAllowedEvent(event), "禁止类不得出现在白名单: " + event);
            // 大小写与连字符变体同样要拦
            assertTrue(AssistScopes.isForbiddenEvent(event.toLowerCase()));
            assertTrue(AssistScopes.isForbiddenEvent(event.replace('_', '-').toLowerCase()));
        }
    }

    @Test
    void nullAndUnknownEventsAreRejected() {
        assertTrue(AssistScopes.isForbiddenEvent(null));
        assertFalse(AssistScopes.isAllowedEvent(null));
        assertFalse(AssistScopes.isAllowedEvent("FOO"));
        assertFalse(AssistScopes.isAllowedEvent("HIGHLIGHT-CLEAR"));
    }
}

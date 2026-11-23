package com.actionloop.android.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 媒体发送策略契约（指导书 8.3 / 8.5 / 8.7 / 8.10）。
 *
 * 这些断言固定“什么时候允许把屏幕像素发给协助者”：
 * 会话不存在/终止/锁屏必须 STOP（真正释放 MediaProjection），
 * 未接受/暂停/敏感页必须 PAUSE（不发送任何帧）。
 */
class AssistMediaPolicyTest {

    @Test
    fun stopsWhenSessionMissingOrTerminal() {
        assertEquals(
            AssistMediaPolicy.Action.STOP,
            AssistMediaPolicy.decide(null, GuidanceSessionState.IDLE, false, false)
        )
        for (state in listOf("STOPPED", "EXPIRED", "COMPLETED")) {
            assertEquals(
                "assist=$state 必须停止媒体",
                AssistMediaPolicy.Action.STOP,
                AssistMediaPolicy.decide(state, GuidanceSessionState.ACTIVE, false, false)
            )
        }
    }

    @Test
    fun stopsOnLockscreenAndGuideTermination() {
        assertEquals(
            AssistMediaPolicy.Action.STOP,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.ACTIVE, false, true)
        )
        assertEquals(
            AssistMediaPolicy.Action.STOP,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.STOPPED, false, false)
        )
        assertEquals(
            AssistMediaPolicy.Action.STOP,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.COMPLETED, false, false)
        )
    }

    @Test
    fun pausesBeforeHelperAcceptsOrWhenSessionPaused() {
        for (state in listOf("IDLE", "CONSENT_PENDING", "PAUSED")) {
            assertEquals(
                "assist=$state 不得发送像素",
                AssistMediaPolicy.Action.PAUSE,
                AssistMediaPolicy.decide(state, GuidanceSessionState.ACTIVE, false, false)
            )
        }
    }

    @Test
    fun pausesOnSensitivePageOrUserPause() {
        assertEquals(
            AssistMediaPolicy.Action.PAUSE,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.ACTIVE, pageSensitive = true, keyguardLocked = false)
        )
        assertEquals(
            AssistMediaPolicy.Action.PAUSE,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.PAUSED, false, false)
        )
    }

    @Test
    fun sendsOnlyWhenActiveAndSafe() {
        assertEquals(
            AssistMediaPolicy.Action.SEND,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.ACTIVE, false, false)
        )
        // 本地引导未启动（IDLE）不应阻止远程画面共享：两者授权相互独立。
        assertEquals(
            AssistMediaPolicy.Action.SEND,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.IDLE, false, false)
        )
        for (state in listOf(
            GuidanceSessionState.ALIGNING,
            GuidanceSessionState.HIGHLIGHTING,
            GuidanceSessionState.WAITING_USER,
            GuidanceSessionState.TEXT_ONLY
        )) {
            assertEquals(
                "guide=$state 应继续共享",
                AssistMediaPolicy.Action.SEND,
                AssistMediaPolicy.decide("ACTIVE", state, false, false)
            )
        }
    }

    @Test
    fun sensitivePageOutranksActiveSession() {
        assertEquals(
            AssistMediaPolicy.Action.PAUSE,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.IDLE, pageSensitive = true, keyguardLocked = false)
        )
        // 锁屏优先级高于敏感页：必须 STOP 而非 PAUSE。
        assertEquals(
            AssistMediaPolicy.Action.STOP,
            AssistMediaPolicy.decide("ACTIVE", GuidanceSessionState.ACTIVE, pageSensitive = true, keyguardLocked = true)
        )
    }
}

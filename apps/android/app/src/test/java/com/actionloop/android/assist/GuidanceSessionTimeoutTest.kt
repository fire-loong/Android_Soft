package com.actionloop.android.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机会话超时停止（指导书 8.3 / 8.6：ACTIVE → STOPPED 的超时路径）。
 * 纯时钟判定单测；采集服务在渲染循环中调用它并执行 stopCapture("session_timeout")。
 */
class GuidanceSessionTimeoutTest {

    @Test
    fun `未到最大时长不判超时`() {
        val start = 1_000L
        assertFalse(GuidanceSessionTimeout.isExpired(start, start + GuidanceSessionTimeout.MAX_SESSION_MS - 1))
    }

    @Test
    fun `到达最大时长即判超时`() {
        val start = 1_000L
        assertTrue(GuidanceSessionTimeout.isExpired(start, start + GuidanceSessionTimeout.MAX_SESSION_MS))
    }

    @Test
    fun `尚未开始的会话不判超时`() {
        assertFalse(GuidanceSessionTimeout.isExpired(0L, Long.MAX_VALUE / 2))
    }

    @Test
    fun `瞬时黑场不判 FLAG_SECURE`() {
        // 普通应用切换/窗口动画的黑场可能只有一两帧，不能触发安全页暂停。
        assertFalse(SecureFrameLatch.isSustained(1_000L, 1_000L + SecureFrameLatch.HOLD_MS - 1))
        assertFalse(SecureFrameLatch.isSustained(0L, Long.MAX_VALUE / 2))
    }

    @Test
    fun `持续黑场判为 FLAG_SECURE`() {
        assertTrue(SecureFrameLatch.isSustained(1_000L, 1_000L + SecureFrameLatch.HOLD_MS))
    }
}

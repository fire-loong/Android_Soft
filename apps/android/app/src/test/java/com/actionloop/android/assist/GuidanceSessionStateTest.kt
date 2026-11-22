package com.actionloop.android.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态迁移表单测（指导书 8.6 + 8.3/8.10 安全出口）。
 * 表是权威：任何实现若绕过它，会在 GuidanceSessionManagerTest 的 ILLEGAL_TRANSITION 用例里暴露。
 */
class GuidanceSessionStateTest {

    @Test
    fun `非终态都可以被立即停止`() {
        val nonTerminal = GuidanceSessionState.entries.filterNot { it.isTerminal() }
        for (state in nonTerminal) {
            assertTrue("$state 必须允许停止", state.canTransitionTo(GuidanceSessionState.STOPPED))
        }
    }

    @Test
    fun `终态不可迁出`() {
        for (target in GuidanceSessionState.entries) {
            assertFalse(GuidanceSessionState.COMPLETED.canTransitionTo(target))
            assertFalse(GuidanceSessionState.STOPPED.canTransitionTo(target))
        }
    }

    @Test
    fun `指导书8_6主链迁移合法`() {
        val chain = listOf(
            GuidanceSessionState.IDLE to GuidanceSessionState.CONSENT_PENDING,
            GuidanceSessionState.CONSENT_PENDING to GuidanceSessionState.ACTIVE,
            GuidanceSessionState.ACTIVE to GuidanceSessionState.ALIGNING,
            GuidanceSessionState.ALIGNING to GuidanceSessionState.HIGHLIGHTING,
            GuidanceSessionState.HIGHLIGHTING to GuidanceSessionState.WAITING_USER,
            GuidanceSessionState.WAITING_USER to GuidanceSessionState.ALIGNING,
            GuidanceSessionState.ACTIVE to GuidanceSessionState.PAUSED,
            GuidanceSessionState.PAUSED to GuidanceSessionState.CONSENT_PENDING,
            GuidanceSessionState.HIGHLIGHTING to GuidanceSessionState.COMPLETED
        )
        for ((from, to) in chain) {
            assertTrue("$from -> $to 应合法", from.canTransitionTo(to))
        }
    }

    @Test
    fun `越权迁移被拒绝`() {
        assertFalse(GuidanceSessionState.IDLE.canTransitionTo(GuidanceSessionState.ACTIVE))
        assertFalse(GuidanceSessionState.IDLE.canTransitionTo(GuidanceSessionState.HIGHLIGHTING))
        assertFalse(GuidanceSessionState.PAUSED.canTransitionTo(GuidanceSessionState.ACTIVE))
        assertFalse(GuidanceSessionState.PAUSED.canTransitionTo(GuidanceSessionState.HIGHLIGHTING))
        assertFalse(GuidanceSessionState.CONSENT_PENDING.canTransitionTo(GuidanceSessionState.HIGHLIGHTING))
        assertFalse(GuidanceSessionState.TEXT_ONLY.canTransitionTo(GuidanceSessionState.HIGHLIGHTING))
    }
}

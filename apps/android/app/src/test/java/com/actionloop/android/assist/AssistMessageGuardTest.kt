package com.actionloop.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力 E 信令守卫单测（指导书 8.1 / 8.7 / 8.10 / 13.5）。
 * 重点证明：控制类事件在本机一律被拒绝，且拒绝结果可区分「禁止类」与「未知类」，便于审计。
 */
class AssistMessageGuardTest {

    @Test
    fun `禁止类事件在任意大小写与连字符写法下都被拒绝`() {
        val variants = listOf(
            "CLICK", "click", "Click", "c-l-i-c-k",
            "TAP", "SWIPE", "SCROLL", "INPUT", "TYPE",
            "KEY_EVENT", "KEY-EVENT", "keyev-ENT",
            "COMMIT_PAYMENT", "commit_payment",
            "PAYMENT", "DISPATCH_GESTURE", "PERFORM_ACTION", "CONTROL", "REMOTE_CONTROL"
        )
        for (event in variants) {
            val result = AssistMessageGuard.validate(event)
            assertTrue("应被拒绝: $event → $result", result is AssistMessageGuard.Result.Forbidden)
        }
    }

    @Test
    fun `空事件按默认拒绝处理`() {
        assertTrue(AssistMessageGuard.validate(null) is AssistMessageGuard.Result.Disallowed)
        assertTrue(AssistMessageGuard.validate("") is AssistMessageGuard.Result.Disallowed)
        assertTrue(AssistMessageGuard.validate("   ") is AssistMessageGuard.Result.Disallowed)
        assertTrue(AssistMessageGuard.isForbidden(null))
    }

    @Test
    fun `白名单事件全部放行且返回大写规范形式`() {
        for (event in AssistMessageGuard.ALLOWED_EVENTS) {
            assertEquals(
                AssistMessageGuard.Result.Allowed(event),
                AssistMessageGuard.validate(event.lowercase())
            )
        }
    }

    @Test
    fun `连字符变体不会误放行为高亮`() {
        // 归一化只用于禁止表匹配；白名单匹配使用原样大写，因此 "HIGH-LIGHT" 不构成合法 HIGHLIGHT。
        val result = AssistMessageGuard.validate("HIGH-LIGHT")
        assertTrue(result is AssistMessageGuard.Result.Disallowed)
        assertFalse(AssistMessageGuard.isAllowed("HIGH-LIGHT"))
    }

    @Test
    fun `分隔符变体不得逃逸为未知事件`() {
        // 必须落在「禁止类」而不是「未知类」：前者会写入 REJECTED_CONTROL 安全审计。
        for (event in listOf(
            "COMMIT-PAYMENT", "commit_payment", "Commit Payment", "commit-payment",
            "DISPATCH-GESTURE", "perform_action", "REMOTE CONTROL", "remote-control"
        )) {
            assertTrue("应判禁止: $event", AssistMessageGuard.validate(event) is AssistMessageGuard.Result.Forbidden)
        }
    }

    @Test
    fun `未知事件默认拒绝而不放行`() {
        for (event in listOf("FOO", "SCREENSHOT_UPLOAD", "OPEN_SETTINGS", "ACCESSIBILITY_QUERY")) {
            assertTrue("应拒绝: $event", AssistMessageGuard.validate(event) is AssistMessageGuard.Result.Disallowed)
        }
    }

    @Test
    fun `白名单与禁止表不重叠`() {
        for (event in AssistMessageGuard.ALLOWED_EVENTS) {
            assertFalse("白名单事件不得同时属于禁止表: $event", AssistMessageGuard.isForbidden(event))
        }
    }
}

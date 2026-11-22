package com.actionloop.android.assist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 引导会话状态机与入站信令单测（指导书 8.3 / 8.5 / 8.6 / 8.7 / 8.10）。
 *
 * 覆盖的安全断言：
 * - 控制类信令一律拒绝且写入审计；
 * - 敏感页 / FLAG_SECURE 采集窗口内拒绝任何高亮，并清除既有高亮；
 * - 远程 RESUME 不得直接把会话拉回 ACTIVE，必须回到 CONSENT_PENDING 由用户本地再次同意；
 * - 终态不可被外部信令激活。
 */
@RunWith(RobolectricTestRunner::class)
// 使用系统 Application：真实 ActionLoopApp 会在 onCreate 里初始化加密会话与 WorkManager，
// 单测环境没有 WorkManager 初始化器，与本类被测逻辑无关，故不加载应用自定义 Application。
@Config(application = android.app.Application::class)
class GuidanceSessionManagerTest {

    private lateinit var context: Context
    private lateinit var manager: GuidanceSessionManager

    private val normalFrame = GuidanceFrame(
        packageName = "com.actionloop.guidancetest",
        pageHash = "page-home",
        timestampMs = 1_700_000_000_000,
        allowlistedPackage = true
    )

    private fun highlightPayload(
        x: Double = 0.1,
        y: Double = 0.2,
        w: Double = 0.3,
        h: Double = 0.1,
        expiresAt: Long? = System.currentTimeMillis() + 5_000
    ): Map<String, Any?> = mapOf(
        "bounds" to mapOf("x" to x, "y" to y, "width" to w, "height" to h),
        "frameId" to "frame-1",
        "label" to "点击这里",
        "expiresAt" to expiresAt
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AssistSecurityAudit.clear(context)
        manager = GuidanceSessionManager(context)
    }

    @Test
    fun `发起与授权走 CONSENT_PENDING 到 ACTIVE`() = runBlocking {
        assertEquals(GuidanceSessionState.IDLE, manager.snapshot().state)
        manager.start()
        assertEquals(GuidanceSessionState.CONSENT_PENDING, manager.snapshot().state)
        assertTrue(manager.isCollecting())
        manager.consentGranted()
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
        manager.consentDenied()
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state) // 非 CONSENT_PENDING 时拒绝降级
    }

    @Test
    fun `无匹配器时对齐降级为文字步骤而不是猜测高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.onFrame(normalFrame)
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
        assertFalse(manager.isCollecting())
    }

    @Test
    fun `控制类信令被拒绝并且写入安全审计`() = runBlocking {
        manager.start(); manager.consentGranted()
        for (event in listOf("CLICK", "SWIPE", "INPUT", "COMMIT_PAYMENT", null)) {
            assertFalse("必须拒绝: $event", manager.applyIncomingEvent("s-1", event, emptyMap()))
        }
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
        val kinds = AssistSecurityAudit.recent(context).map { it.kind }
        assertTrue("控制类事件应记录 REJECTED_CONTROL", kinds.count { it == "REJECTED_CONTROL" } >= 4)
        assertTrue("空事件应记录 REJECTED_UNKNOWN", kinds.contains("REJECTED_UNKNOWN"))
    }

    @Test
    fun `合法高亮在活跃会话中生效`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        val snap = manager.snapshot()
        assertEquals(GuidanceSessionState.HIGHLIGHTING, snap.state)
        assertNotNull(snap.highlight)
        assertEquals(0.1, snap.highlight!!.bounds.x, 1e-9)
    }

    @Test
    fun `越界与过期高亮被丢弃`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(x = 0.9, w = 0.5)))
        assertFalse(
            manager.applyIncomingEvent(
                "s-1", "HIGHLIGHT", highlightPayload(expiresAt = System.currentTimeMillis() - 1_000)
            )
        )
        assertNull(manager.snapshot().highlight)
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
    }

    @Test
    fun `敏感帧清除高亮并暂停随后拒绝远程高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        manager.onFrame(normalFrame.copy(isSecure = true))
        val snap = manager.snapshot()
        assertEquals(GuidanceSessionState.PAUSED, snap.state)
        assertNull(snap.highlight)
        assertTrue(snap.pageSensitive)
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
    }

    @Test
    fun `采集侧FLAG_SECURE窗口内拒绝高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.markCaptureSecure()
        val snap = manager.snapshot()
        assertTrue(snap.pageSensitive)
        assertEquals("FLAG_SECURE frame", snap.sensitiveReason)
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `远程恢复只能回到待同意而不能直接激活`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.pause("user")
        assertEquals(GuidanceSessionState.PAUSED, manager.snapshot().state)
        assertTrue(manager.applyIncomingEvent("s-1", "RESUME", emptyMap()))
        assertEquals(GuidanceSessionState.CONSENT_PENDING, manager.snapshot().state)
        // 用户本地再次同意后才恢复
        manager.consentGranted()
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
    }

    @Test
    fun `远程停止进入终态且终态拒绝高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertTrue(manager.applyIncomingEvent("s-1", "STOP", emptyMap()))
        assertEquals(GuidanceSessionState.STOPPED, manager.snapshot().state)
        assertTrue(manager.snapshot().state.isTerminal())
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
    }

    @Test
    fun `步骤完成标记为 COMPLETED 并清空高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload())
        manager.userConfirmedStepComplete()
        assertEquals(GuidanceSessionState.COMPLETED, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `用户主动恢复从 PAUSED 直接回到 ACTIVE`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.pause("sensitive")
        manager.userResume()
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
    }

    @Test
    fun `文字提示长度被截断`() = runBlocking {
        manager.start(); manager.consentGranted()
        val long = "请".repeat(1000)
        assertTrue(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to long)))
        assertEquals(500, manager.snapshot().textHint!!.length)
    }

    @Test
    fun `远程恢复请求与恢复同义且都不会直接激活`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.pause("user")
        assertTrue(manager.applyIncomingEvent("s-1", "RESUME_REQUEST", emptyMap()))
        assertEquals(GuidanceSessionState.CONSENT_PENDING, manager.snapshot().state)
    }

    @Test
    fun `心跳与质量信令被接受但不改变状态`() = runBlocking {
        manager.start(); manager.consentGranted()
        for (event in listOf("HEARTBEAT", "QUALITY", "META")) {
            assertTrue("应接受: $event", manager.applyIncomingEvent("s-1", event, emptyMap()))
        }
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
    }

    @Test
    fun `过期高亮在读取快照时被丢弃`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(expiresAt = null)))
        assertNotNull(manager.snapshot().highlight)
        // 无过期时间的高亮常驻；带过期时间的在到期后必须被丢弃（8.7）。
        val expiring = highlightPayload(expiresAt = System.currentTimeMillis() + 60)
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", expiring))
        Thread.sleep(120)
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "HIGHLIGHT_EXPIRED" })
    }

    @Test
    fun `非法状态迁移被拦截并记录而不改变状态`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.userConfirmedStepComplete() // ACTIVE 下不满足完成条件
        manager.pause("user")
        manager.applyIncomingEvent("s-1", "RESUME", emptyMap())
        manager.consentGranted() // CONSENT_PENDING → ACTIVE
        manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload())
        manager.userConfirmedStepComplete()
        assertEquals(GuidanceSessionState.COMPLETED, manager.snapshot().state)
        // 终态不可被 consentGranted 拉回 ACTIVE
        manager.consentGranted()
        assertEquals(GuidanceSessionState.COMPLETED, manager.snapshot().state)
        assertTrue(
            "应记录非法迁移",
            AssistSecurityAudit.recent(context).any { it.kind == "ILLEGAL_TRANSITION" }
        )
    }

    @Test
    fun `待同意状态也允许立即停止`() = runBlocking {
        manager.start()
        manager.stop("user_cancel")
        assertEquals(GuidanceSessionState.STOPPED, manager.snapshot().state)
    }
}

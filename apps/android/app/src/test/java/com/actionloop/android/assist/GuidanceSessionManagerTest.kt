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
        frameId = "frame-1",
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
        expiresAt: Long? = System.currentTimeMillis() + 5_000,
        frameId: String? = "frame-1"
    ): Map<String, Any?> = mapOf(
        "bounds" to mapOf("x" to x, "y" to y, "width" to w, "height" to h),
        "frameId" to frameId,
        "label" to "点击这里",
        "expiresAt" to expiresAt
    )

    private fun pointerPayload(
        frameId: String = "frame-1",
        expiresAt: Long = System.currentTimeMillis() + 5_000
    ): Map<String, Any?> = mapOf(
        "bounds" to mapOf("x" to 0.4, "y" to 0.4, "width" to 0.02, "height" to 0.02),
        "frameId" to frameId,
        "expiresAt" to expiresAt
    )

    /** 进入 ACTIVE 并提交一帧，使会话具备可绑定标注的当前帧。 */
    private suspend fun activeWithFrame(): GuidanceSessionManager {
        manager.start()
        manager.consentGranted()
        manager.onFrame(normalFrame)
        return manager
    }

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
        // 文字降级后仍继续读页面，以便页面变化后重新对齐（8.4）
        assertTrue(manager.isCollecting())
    }

    @Test
    fun `桌面应用名不阻断三类远程标注`() = runBlocking {
        val launcher = "com.bbk.launcher2"
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = launcher
                name = "Launcher"
            }
        }
        org.robolectric.Shadows.shadowOf(context.packageManager)
            .addResolveInfoForIntent(homeIntent, resolveInfo)
        manager.start()
        manager.consentGranted()
        manager.onFrame(normalFrame.copy(
            packageName = launcher,
            textBlocks = listOf(GuidanceFrame.TextBlock(
                "支付宝", NormalizedBounds(0.1, 0.1, 0.2, 0.05)
            ))
        ))
        assertFalse(manager.snapshot().pageSensitive)
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertTrue(manager.applyIncomingEvent("s-1", "POINTER", pointerPayload()))
        assertTrue(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf(
            "text" to "请长按应用图标", "frameId" to "frame-1"
        )))
        val snap = manager.snapshot()
        assertNotNull(snap.highlight)
        assertNotNull(snap.pointer)
        assertEquals("请长按应用图标", snap.textHint)
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
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        val snap = manager.snapshot()
        assertEquals(GuidanceSessionState.HIGHLIGHTING, snap.state)
        assertNotNull(snap.highlight)
        assertEquals(0.1, snap.highlight!!.bounds.x, 1e-9)
        assertEquals("frame-1", snap.highlight!!.frameId)
    }

    @Test
    fun `越界与过期高亮被丢弃`() = runBlocking {
        activeWithFrame()
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(x = 0.9, w = 0.5)))
        assertFalse(
            manager.applyIncomingEvent(
                "s-1", "HIGHLIGHT", highlightPayload(expiresAt = System.currentTimeMillis() - 1_000)
            )
        )
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `敏感帧清除高亮并暂停随后拒绝远程高亮`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        manager.onFrame(normalFrame.copy(frameId = "frame-secure", isSecure = true))
        val snap = manager.snapshot()
        assertEquals(GuidanceSessionState.PAUSED, snap.state)
        assertNull(snap.highlight)
        assertTrue(snap.pageSensitive)
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(frameId = "frame-secure")))
    }

    @Test
    fun `采集侧FLAG_SECURE窗口内拒绝高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.onFrame(normalFrame)
        manager.onCaptureSecure()
        val snap = manager.snapshot()
        assertTrue(snap.pageSensitive)
        assertEquals("FLAG_SECURE frame", snap.sensitiveReason)
        assertEquals(GuidanceSessionState.PAUSED, snap.state)
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `采集侧报告FLAG_SECURE立即清除既有标注并暂停`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertTrue(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "请点这里", "frameId" to "frame-1")))
        // 不换帧直接报告 FLAG_SECURE：必须由 onCaptureSecure 自己清掉高亮/文字提示，而不是依赖换帧。
        manager.onCaptureSecure()
        val snap = manager.snapshot()
        assertEquals(GuidanceSessionState.PAUSED, snap.state)
        assertNull(snap.highlight)
        assertNull(snap.textHint)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "CAPTURE_SECURE" })
    }

    @Test
    fun `敏感帧清除远程文字提示`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "请输入验证码", "frameId" to "frame-1")))
        assertEquals("请输入验证码", manager.snapshot().textHint)
        // 同一 frameId 直接变敏感：必须由敏感分支清掉文字提示，而不是靠换帧。
        manager.onFrame(normalFrame.copy(isSecure = true))
        assertNull(manager.snapshot().textHint)
        assertFalse(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "继续", "frameId" to "frame-1")))
    }

    @Test
    fun `文字提示必须绑定当前帧并在换帧后失效`() = runBlocking {
        activeWithFrame()
        assertFalse(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "无帧标识")))
        assertFalse(
            manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "旧帧", "frameId" to "frame-old"))
        )
        assertTrue(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "当前帧", "frameId" to "frame-1")))
        manager.onFrame(normalFrame.copy(frameId = "frame-2"))
        assertNull(manager.snapshot().textHint)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "TEXT_HINT_STALE" })
    }

    @Test
    fun `TEXT_ONLY下远程暂停也会进入PAUSED`() = runBlocking {
        activeWithFrame()
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertTrue(manager.applyIncomingEvent("s-1", "PAUSE", emptyMap()))
        assertEquals(GuidanceSessionState.PAUSED, manager.snapshot().state)
        assertFalse(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to "继续", "frameId" to "frame-1")))
    }

    @Test
    fun `显示尺寸或旋转变化会作废旧帧并暂停`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        manager.onDisplayChanged()
        val snap = manager.snapshot()
        assertEquals(GuidanceSessionState.PAUSED, snap.state)
        assertNull(snap.highlight)
        assertNull(snap.frameId)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "DISPLAY_CHANGED" })
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
        activeWithFrame()
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
        activeWithFrame()
        val long = "请".repeat(1000)
        assertTrue(manager.applyIncomingEvent("s-1", "TEXT_HINT", mapOf("text" to long, "frameId" to "frame-1")))
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
    fun `缺失过期时间的高亮被拒绝`() = runBlocking {
        activeWithFrame()
        // 13.5：HIGHLIGHT 必须含 expiresAt，缺失即拒，避免标注永久驻留。
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(expiresAt = null)))
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `过期高亮在读取快照时被丢弃`() = runBlocking {
        activeWithFrame()
        val expiring = highlightPayload(expiresAt = System.currentTimeMillis() + 300)
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", expiring))
        assertNotNull(manager.snapshot().highlight)
        // 过期判定走单调时钟；Robolectric 的 elapsedRealtime 不随 Thread.sleep 前进，需显式推进。
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "HIGHLIGHT_EXPIRED" })
    }

    @Test
    fun `非法状态迁移被拦截并记录而不改变状态`() = runBlocking {
        activeWithFrame()
        manager.userConfirmedStepComplete() // TEXT_ONLY 下不满足完成条件
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

    @Test
    fun `未暂停时用户恢复不会激活会话`() = runBlocking {
        manager.userResume()
        assertEquals(GuidanceSessionState.IDLE, manager.snapshot().state)
        manager.start()
        manager.userResume() // CONSENT_PENDING 不是 PAUSED，不得跳过授权直接 ACTIVE
        assertEquals(GuidanceSessionState.CONSENT_PENDING, manager.snapshot().state)
    }

    @Test
    fun `重复发起会重置上一轮会话而非继承授权`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)
        manager.start()
        assertEquals(GuidanceSessionState.CONSENT_PENDING, manager.snapshot().state)
    }

    // ---- 8.7 帧绑定与旧标注失效 ----

    @Test
    fun `缺少frameId的标注被拒绝并审计`() = runBlocking {
        activeWithFrame()
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(frameId = null)))
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(frameId = "  ")))
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "ANNOTATION_NO_FRAME_ID" })
    }

    @Test
    fun `未收到任何帧时标注被拒绝`() = runBlocking {
        manager.start(); manager.consentGranted()
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "ANNOTATION_NO_FRAME" })
    }

    @Test
    fun `非当前帧的标注被拒绝并审计`() = runBlocking {
        activeWithFrame()
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload(frameId = "frame-old")))
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "ANNOTATION_STALE_FRAME" })
    }

    @Test
    fun `新帧到达时绑定旧帧的高亮立即失效`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertNotNull(manager.snapshot().highlight)
        manager.onFrame(normalFrame.copy(frameId = "frame-2"))
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "HIGHLIGHT_STALE" })
    }

    @Test
    fun `空frameId的帧被丢弃且不绑定标注`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        manager.onFrame(normalFrame.copy(frameId = ""))
        assertNull(manager.snapshot().highlight)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "FRAME_INVALID" })
        // 旧帧标识仍在，但新标注不能凭空落到空帧上
        assertFalse(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
    }

    // ---- POINTER ----

    @Test
    fun `指针标注绑定当前帧且必须有过期时间`() = runBlocking {
        activeWithFrame()
        assertTrue(manager.applyIncomingEvent("s-1", "POINTER", pointerPayload()))
        assertEquals(0.4, manager.snapshot().pointer!!.x, 1e-9)
        assertFalse(manager.applyIncomingEvent("s-1", "POINTER", pointerPayload(frameId = "frame-9")))
        assertFalse(
            manager.applyIncomingEvent(
                "s-1", "POINTER",
                mapOf(
                    "bounds" to mapOf("x" to 0.4, "y" to 0.4, "width" to 0.02, "height" to 0.02),
                    "frameId" to "frame-1"
                )
            )
        )
        manager.onFrame(normalFrame.copy(frameId = "frame-2"))
        assertNull(manager.snapshot().pointer)
    }

    // ---- 8.4 本地匹配器 ----

    private fun block(
        text: String,
        x: Double,
        y: Double,
        viewId: String? = null,
        className: String? = "android.widget.Button",
        parentText: String? = null
    ) = GuidanceFrame.TextBlock(
        text = text,
        bounds = NormalizedBounds(x, y, 0.3, 0.08),
        className = className,
        viewId = viewId,
        parentText = parentText
    )

    private fun frameWith(vararg blocks: GuidanceFrame.TextBlock, frameId: String = "frame-1") =
        normalFrame.copy(frameId = frameId, textBlocks = blocks.toList())

    private fun nextTarget() = GuidanceTarget(
        packageName = "com.actionloop.guidancetest",
        labels = listOf("下一步"),
        viewIdHint = "btn_next",
        roleHint = "button",
        expectedBounds = NormalizedBounds(0.1, 0.7, 0.3, 0.08)
    )

    @Test
    fun `本机匹配器按文本与稳定节点命中并绑定当前帧`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.setTarget(nextTarget())
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7, viewId = "com.actionloop.guidancetest:id/btn_next")))
        val snap = manager.snapshot()
        assertNotNull(snap.highlight)
        assertEquals("frame-1", snap.highlight!!.frameId)
        assertEquals(0.1, snap.highlight!!.bounds.x, 1e-9)
        assertTrue(snap.state == GuidanceSessionState.HIGHLIGHTING || snap.state == GuidanceSessionState.WAITING_USER)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "LOCAL_MATCH" })
    }

    @Test
    fun `前两名同分时拒绝指向任何一个按钮`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.setTarget(GuidanceTarget("com.actionloop.guidancetest", listOf("下一步")))
        manager.onFrame(
            frameWith(
                block("下一步", 0.1, 0.6),
                block("下一步", 0.1, 0.8)
            )
        )
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `仅文本包含且无其他证据时降级文字`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.setTarget(GuidanceTarget("com.actionloop.guidancetest", listOf("下一步")))
        manager.onFrame(frameWith(block("下一步请点击这里", 0.1, 0.6)))
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `非白名单包或锁屏帧不做本地匹配`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.setTarget(nextTarget())
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7)).copy(allowlistedPackage = false))
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7)).copy(isLocked = true))
        assertEquals(GuidanceSessionState.PAUSED, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `撤销目标后匹配会话立即降级文字`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.setTarget(nextTarget())
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7, viewId = "btn_next")))
        assertNotNull(manager.snapshot().highlight)
        manager.setTarget(null)
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7, viewId = "btn_next"), frameId = "frame-2"))
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }

    /**
     * R16 App 内入口：会话先进入文字降级（例如停留在本应用页面），用户再设置本地目标，
     * 切换到受控测试 App 的帧必须重新对齐并高亮。
     */
    @Test
    fun `会话进行中设置目标后切换到受控页重新高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        // 尚未设置目标：普通帧按低置信降级为文字。
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7, viewId = "btn_next")))
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        manager.setTarget(nextTarget())
        assertEquals("下一步", manager.snapshot().localTargetLabel)
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7, viewId = "btn_next"), frameId = "frame-2"))
        assertNotNull(manager.snapshot().highlight)
        assertEquals("frame-2", manager.snapshot().highlight!!.frameId)
        assertTrue(AssistSecurityAudit.recent(context).any { it.kind == "LOCAL_TARGET_SET" })
    }

    @Test
    fun `低置信目标只保留文字且快照无高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        // “确认提交”只在包含维度命中“提交”（0.60 < 0.65），必须降级文字。
        manager.setTarget(GuidanceTarget("com.actionloop.guidancetest", listOf("确认提交")))
        manager.onFrame(frameWith(block("提交", 0.1, 0.7, viewId = "btn_submit")))
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
        assertEquals("确认提交", manager.snapshot().localTargetLabel)
    }

    @Test
    fun `会话启动前设置目标被明确拒绝`() = runBlocking {
        // IDLE 下设置会被 start() 的 reset 清空；必须返回 false，避免 UI 乐观提示成功。
        assertFalse(manager.setTarget(nextTarget()))
        assertNull(manager.snapshot().localTargetLabel)
        manager.start()
        // 等待屏幕授权期间同样拒绝。
        assertFalse(manager.setTarget(nextTarget()))
        manager.consentGranted()
        assertTrue(manager.setTarget(nextTarget()))
    }

    @Test
    fun `切换目标立即清除旧高亮`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.setTarget(nextTarget())
        manager.onFrame(frameWith(block("下一步", 0.1, 0.7, viewId = "btn_next")))
        assertNotNull(manager.snapshot().highlight)
        // 页面静止、没有新帧时也必须立刻清掉旧高亮，避免“当前目标 B、画面仍高亮 A”。
        manager.setTarget(GuidanceTarget("com.actionloop.guidancetest", listOf("提交"), viewIdHint = "btn_submit"))
        assertNull(manager.snapshot().highlight)
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertEquals("提交", manager.snapshot().localTargetLabel)
    }

    /**
     * 真机回归（纯远程协助）：协助者标注落下后，本机持续上报同一帧（frameId 不变）时，
     * 不得因为“没有本地目标”而把远程 HIGHLIGHT 清掉或强制降级 TEXT_ONLY。
     */
    @Test
    fun `纯远程协助的标注不会被后续同帧刷新清除`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.onFrame(normalFrame)
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)

        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertEquals(GuidanceSessionState.HIGHLIGHTING, manager.snapshot().state)
        assertNotNull(manager.snapshot().highlight)

        // 同一 frameId 的后续帧（页面内容未变化）必须保留远程标注。
        manager.onFrame(normalFrame)
        assertEquals(GuidanceSessionState.HIGHLIGHTING, manager.snapshot().state)
        assertNotNull(manager.snapshot().highlight)
    }

    /** 远程标注到期后必须回到文字降级，控制条不能长期显示“高亮中”却没有标注。 */
    @Test
    fun `远程标注过期后回到文字降级`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.onFrame(normalFrame)
        assertTrue(
            manager.applyIncomingEvent(
                "s-1", "HIGHLIGHT",
                highlightPayload(expiresAt = System.currentTimeMillis() + 2_000)
            )
        )
        assertEquals(GuidanceSessionState.HIGHLIGHTING, manager.snapshot().state)

        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2_500))
        val snap = manager.snapshot()
        assertNull(snap.highlight)
        assertEquals(GuidanceSessionState.TEXT_ONLY, snap.state)
    }

    /** 换帧（frameId 变化）时远程标注必须失效：这是“标注绑定当前帧”的核心保证。 */
    @Test
    fun `换帧后远程标注立即失效`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.onFrame(normalFrame)
        assertTrue(manager.applyIncomingEvent("s-1", "HIGHLIGHT", highlightPayload()))
        assertNotNull(manager.snapshot().highlight)

        manager.onFrame(normalFrame.copy(frameId = "frame-2", pageHash = "page-next"))
        val snap = manager.snapshot()
        assertNull(snap.highlight)
        assertEquals("frame-2", snap.frameId)
    }

    // ---- 会话间状态隔离 ----

    @Test
    fun `新一轮协助会话不继承上一轮的终态`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.stop("user_done")
        assertTrue(manager.snapshot().state.isTerminal())
        manager.resetIfTerminal()
        assertEquals(GuidanceSessionState.IDLE, manager.snapshot().state)
        // 终态被清理后，新会话可以重新走完整授权流程。
        manager.start()
        assertEquals(GuidanceSessionState.CONSENT_PENDING, manager.snapshot().state)
    }

    @Test
    fun `清理终态不会放宽暂停等安全状态`() = runBlocking {
        manager.start(); manager.consentGranted()
        manager.pause("sensitive")
        manager.resetIfTerminal()
        assertEquals(GuidanceSessionState.PAUSED, manager.snapshot().state)
    }
}

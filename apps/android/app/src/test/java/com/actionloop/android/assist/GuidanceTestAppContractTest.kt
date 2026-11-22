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
 * 受控测试 App（com.actionloop.guidancetest）契约测试。
 *
 * 不改设备、不跑 UI：用与测试 App 完全一致的文本/viewId/布局位置构造 GuidanceFrame，
 * 锁死“稳定节点能命中、按钮移动后旧帧标注失效、密码框暂停、同名列表项不误点”四条契约。
 * 真正的触摸穿透/授权/停止时序仍需设备冒烟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GuidanceTestAppContractTest {

    private lateinit var context: Context
    private lateinit var manager: GuidanceSessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = GuidanceSessionManager(context)
    }

    private fun block(
        text: String,
        x: Double,
        y: Double,
        viewId: String? = null,
        className: String? = "android.widget.Button",
        editable: Boolean = false,
        password: Boolean = false
    ) = GuidanceFrame.TextBlock(
        text = text,
        bounds = NormalizedBounds(x, y, 0.8, 0.08),
        isEditable = editable,
        isPassword = password,
        className = className,
        viewId = viewId
    )

    private fun frame(frameId: String, vararg blocks: GuidanceFrame.TextBlock, pageHash: String = "page-1") =
        GuidanceFrame(
            frameId = frameId,
            packageName = "com.actionloop.guidancetest",
            pageHash = pageHash,
            timestampMs = 1_700_000_000_000,
            textBlocks = blocks.toList(),
            allowlistedPackage = true
        )

    private fun nextTarget(expectedY: Double = 0.30) = GuidanceTarget(
        packageName = "com.actionloop.guidancetest",
        labels = listOf("下一步"),
        viewIdHint = "btn_next",
        roleHint = "button",
        expectedBounds = NormalizedBounds(0.1, expectedY, 0.8, 0.08)
    )

    private suspend fun active() {
        manager.start()
        manager.consentGranted()
    }

    @Test
    fun `稳定 viewId 与文本锚点命中下一步`() = runBlocking {
        active()
        manager.setTarget(nextTarget())
        manager.onFrame(
            frame(
                "frame-1",
                block("ActionLoop 引导测试", 0.1, 0.05, className = "android.widget.TextView"),
                block("下一步", 0.1, 0.30, viewId = "com.actionloop.guidancetest:id/btn_next"),
                block("提交", 0.1, 0.40, viewId = "com.actionloop.guidancetest:id/btn_submit")
            )
        )
        val snap = manager.snapshot()
        assertNotNull(snap.highlight)
        assertEquals("frame-1", snap.highlight!!.frameId)
        assertEquals(0.30, snap.highlight!!.bounds.y, 1e-9)
    }

    @Test
    fun `按钮移动后旧帧标注被拒绝且重新命中新位置`() = runBlocking {
        active()
        manager.setTarget(nextTarget())
        manager.onFrame(
            frame("frame-top", block("下一步", 0.1, 0.30, viewId = "com.actionloop.guidancetest:id/btn_next"))
        )
        assertTrue(
            manager.applyIncomingEvent(
                "s-1", "HIGHLIGHT",
                mapOf(
                    "bounds" to mapOf("x" to 0.1, "y" to 0.30, "width" to 0.8, "height" to 0.08),
                    "frameId" to "frame-top",
                    "expiresAt" to System.currentTimeMillis() + 60_000
                )
            )
        )
        // 测试 App 点击“移动下一步”后按钮位于页面下方，并产生新 frameId。
        manager.setTarget(nextTarget(expectedY = 0.70))
        manager.onFrame(
            frame("frame-bottom", block("下一步", 0.1, 0.70, viewId = "com.actionloop.guidancetest:id/btn_next"))
        )
        val snap = manager.snapshot()
        assertNotNull(snap.highlight)
        assertEquals("frame-bottom", snap.highlight!!.frameId)
        assertEquals(0.70, snap.highlight!!.bounds.y, 1e-9)
        // 旧帧标注即使重放也不能落回旧坐标。
        assertFalse(
            manager.applyIncomingEvent(
                "s-1", "HIGHLIGHT",
                mapOf(
                    "bounds" to mapOf("x" to 0.1, "y" to 0.30, "width" to 0.8, "height" to 0.08),
                    "frameId" to "frame-top",
                    "expiresAt" to System.currentTimeMillis() + 60_000
                )
            )
        )
    }

    @Test
    fun `密码弹窗页暂停并清除标注`() = runBlocking {
        active()
        manager.setTarget(nextTarget())
        manager.onFrame(
            frame(
                "frame-pwd",
                block("密码输入", 0.1, 0.20, className = "android.widget.TextView"),
                block("", 0.1, 0.30, className = "android.widget.EditText", editable = true, password = true)
            )
        )
        assertTrue(manager.snapshot().pageSensitive)
        assertEquals(GuidanceSessionState.PAUSED, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }

    @Test
    fun `同名列表项不会误点`() = runBlocking {
        active()
        manager.setTarget(
            GuidanceTarget(
                packageName = "com.actionloop.guidancetest",
                labels = listOf("事项 7：点击查看详情")
            )
        )
        val rows = (1..30).map { i ->
            block("事项 $i：点击查看详情", 0.05, 0.10 + i * 0.025, className = "android.widget.TextView")
        }
        manager.onFrame(frame("frame-list", *rows.toTypedArray()))
        val snap = manager.snapshot()
        assertNotNull(snap.highlight)
        // 第 7 行：0.10 + 7*0.025 = 0.275
        assertEquals(0.275, snap.highlight!!.bounds.y, 1e-9)
    }

    @Test
    fun `泛化包含匹配面对列表时拒绝猜测`() = runBlocking {
        active()
        manager.setTarget(
            GuidanceTarget(packageName = "com.actionloop.guidancetest", labels = listOf("事项"))
        )
        val rows = (1..30).map { i ->
            block("事项 $i：点击查看详情", 0.05, 0.10 + i * 0.025, className = "android.widget.TextView")
        }
        manager.onFrame(frame("frame-list", *rows.toTypedArray()))
        assertEquals(GuidanceSessionState.TEXT_ONLY, manager.snapshot().state)
        assertNull(manager.snapshot().highlight)
    }
}

package com.actionloop.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地目标匹配器单测（指导书 8.4）。
 * 锁死三件安全事实：文本包含不能单独达标、同分歧义必须拒绝、密码/可编辑字段永不作为候选。
 */
class TargetMatcherTest {

    private fun frame(
        vararg blocks: GuidanceFrame.TextBlock,
        pageHash: String = "page-1",
        pkg: String = "com.actionloop.guidancetest",
        allowlisted: Boolean = true,
        locked: Boolean = false,
        secure: Boolean = false
    ) = GuidanceFrame(
        frameId = "frame-1",
        packageName = pkg,
        pageHash = pageHash,
        timestampMs = 1_700_000_000_000,
        textBlocks = blocks.toList(),
        allowlistedPackage = allowlisted,
        isLocked = locked,
        isSecure = secure
    )

    private fun block(
        text: String,
        x: Double = 0.1,
        y: Double = 0.6,
        w: Double = 0.3,
        h: Double = 0.08,
        viewId: String? = null,
        className: String? = "android.widget.Button",
        editable: Boolean = false,
        password: Boolean = false,
        parentText: String? = null
    ) = GuidanceFrame.TextBlock(
        text = text,
        bounds = NormalizedBounds(x, y, w, h),
        isEditable = editable,
        isPassword = password,
        className = className,
        viewId = viewId,
        parentText = parentText
    )

    private fun target(
        vararg labels: String,
        viewId: String? = null,
        role: String? = null,
        nearby: String? = null,
        expected: NormalizedBounds? = null
    ) = GuidanceTarget(
        packageName = "com.actionloop.guidancetest",
        labels = labels.toList(),
        viewIdHint = viewId,
        roleHint = role,
        nearbyText = nearby,
        expectedBounds = expected
    )

    @Test
    fun `精确文本锚点可单独命中`() {
        val m = TargetMatcher.match(frame(block("提交")), target("提交"))
        assertNotNull(m)
        assertEquals(1.0, m!!.score, 1e-9)
    }

    @Test
    fun `文本包含但没有其他证据时不达标`() {
        // 0.60 < 0.65：必须叠加稳定节点/角色/位置/历史，避免“差不多就点”。
        assertNull(TargetMatcher.match(frame(block("提交材料")), target("提交")))
    }

    @Test
    fun `稳定节点标识与位置可以补足包含匹配`() {
        val m = TargetMatcher.match(
            frame(block("提交材料", viewId = "com.actionloop.guidancetest:id/btn_submit")),
            target("提交", viewId = "btn_submit", expected = NormalizedBounds(0.1, 0.6, 0.3, 0.08))
        )
        assertNotNull(m)
        assertTrue(m!!.score >= TargetMatcher.MIN_CONFIDENCE)
    }

    @Test
    fun `同分候选一律拒绝`() {
        assertNull(
            TargetMatcher.match(
                frame(block("下一步", 0.1, 0.6), block("下一步", 0.1, 0.8)),
                target("下一步")
            )
        )
    }

    @Test
    fun `位置证据可以区分同名按钮`() {
        val m = TargetMatcher.match(
            frame(block("下一步", 0.1, 0.7), block("下一步", 0.1, 0.9)),
            target("下一步", expected = NormalizedBounds(0.1, 0.7, 0.3, 0.08))
        )
        assertNotNull(m)
        assertEquals(0.7, m!!.bounds.y, 1e-9)
    }

    @Test
    fun `密码与可编辑字段永不作为匹配候选`() {
        assertNull(
            TargetMatcher.match(
                frame(block("验证码", password = true), block("验证码", editable = true)),
                target("验证码")
            )
        )
    }

    @Test
    fun `同页历史命中可以补足文本包含分`() {
        val f = frame(block("提交材料"))
        val history = TargetMatcher.History("page-1", TargetMatcher.normalizeText("提交材料"))
        assertNotNull(TargetMatcher.match(f, target("提交"), history))
        // 不同页面哈希时历史不参与评分，仍应拒绝。
        assertNull(TargetMatcher.match(f, target("提交"), history.copy(pageHash = "page-2")))
    }

    @Test
    fun `包名不符与锁屏安全帧不做匹配`() {
        val t = target("提交")
        assertNull(TargetMatcher.match(frame(block("提交"), pkg = "com.other.app"), t))
        assertNull(TargetMatcher.match(frame(block("提交"), locked = true), t))
        assertNull(TargetMatcher.match(frame(block("提交"), secure = true), t))
    }

    @Test
    fun `越界候选边界不参与匹配`() {
        assertNull(
            TargetMatcher.match(
                frame(block("提交", x = 0.9, w = 0.5)),
                target("提交")
            )
        )
    }

    @Test
    fun `归一化文本忽略大小写空白与标点`() {
        assertEquals("nextstep", TargetMatcher.normalizeText(" Next Step! "))
        assertEquals("下一步", TargetMatcher.normalizeText("下一步。"))
        val m = TargetMatcher.match(frame(block("NEXT STEP")), target("next step"))
        assertNotNull(m)
    }
}

package com.actionloop.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感页面判定单测（指导书 8.3 / 8.5 / 8.10）。
 * 原则：宁可误报降级为文字步骤，也不得在敏感页残留高亮或上传帧。
 */
class SensitivePageDetectorTest {

    private fun frame(
        pkg: String = "com.actionloop.guidancetest",
        blocks: List<GuidanceFrame.TextBlock> = emptyList(),
        secure: Boolean = false,
        locked: Boolean = false
    ) = GuidanceFrame(
        packageName = pkg,
        pageHash = "hash-1",
        timestampMs = 1_700_000_000_000,
        textBlocks = blocks,
        isSecure = secure,
        isLocked = locked,
        allowlistedPackage = true
    )

    private fun block(
        text: String,
        editable: Boolean = false,
        password: Boolean = false
    ) = GuidanceFrame.TextBlock(
        text = text,
        bounds = NormalizedBounds(0.1, 0.1, 0.2, 0.05),
        isEditable = editable,
        isPassword = password
    )

    @Test
    fun `锁屏与FLAG_SECURE立即判为敏感`() {
        assertTrue(SensitivePageDetector.evaluate(frame(locked = true)).sensitive)
        assertTrue(SensitivePageDetector.evaluate(frame(secure = true)).sensitive)
    }

    @Test
    fun `密码字段判为敏感且不依赖其文本内容`() {
        val masked = frame(blocks = listOf(block("••••••", editable = true)))
        assertTrue(SensitivePageDetector.evaluate(masked).sensitive)
        val spaced = frame(blocks = listOf(block("• • • •", editable = true)))
        assertTrue("带分隔符的掩码也必须命中", SensitivePageDetector.evaluate(spaced).sensitive)
        val empty = frame(blocks = listOf(block("", editable = true, password = true)))
        assertTrue(SensitivePageDetector.evaluate(empty).sensitive)
    }

    @Test
    fun `支付与验证码关键词判为敏感`() {
        for (text in listOf("确认支付", "立即支付", "请输入短信验证码", "Password", "身份证号", "CVV")) {
            val result = SensitivePageDetector.evaluate(frame(blocks = listOf(block(text))))
            assertTrue("应判为敏感: $text", result.sensitive)
        }
    }

    @Test
    fun `系统锁屏包名兜底判为敏感`() {
        assertTrue(SensitivePageDetector.evaluate(frame(pkg = "com.android.keyguard")).sensitive)
        assertTrue(SensitivePageDetector.evaluate(frame(pkg = "com.android.systemui")).sensitive)
    }

    @Test
    fun `普通可点击文本页不判为敏感`() {
        val normal = frame(
            blocks = listOf(
                block("材料上传"),
                block("下一步"),
                block("课程设计材料提交")
            )
        )
        val result = SensitivePageDetector.evaluate(normal)
        assertFalse(result.sensitive)
        assertEquals(null, result.reason)
    }

    @Test
    fun `普通可编辑输入框不因可编辑而被误判`() {
        val search = frame(blocks = listOf(block("搜索课程名称", editable = true)))
        assertFalse(SensitivePageDetector.evaluate(search).sensitive)
    }
}

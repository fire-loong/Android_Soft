package com.actionloop.android.assist

import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑帧探测回归：安全窗口允许被系统栏、悬浮条、弹窗和 IME 部分遮挡。 */
class SecureFrameDetectorTest {

    private val width = 40
    private val height = 40
    private val pixelStride = 4
    private val rowStride = width * pixelStride + 16 // 刻意加入行尾 padding，验证 stride 换算

    private fun frame(brightRow: (Int) -> Boolean): ByteBuffer {
        val buffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * pixelStride
                val value = if (brightRow(y)) 0xFF else 0
                buffer.put(offset, value.toByte())
                buffer.put(offset + 1, value.toByte())
                buffer.put(offset + 2, value.toByte())
                buffer.put(offset + 3, 0xFF.toByte())
            }
        }
        return buffer
    }

    private fun detect(buffer: ByteBuffer): Boolean = SecureFrameDetector.isMostlyBlack(
        buffer, rowStride, pixelStride, width, height
    )

    @Test
    fun `黑色内容被系统栏弹窗与输入法遮挡仍可探测`() {
        // 顶部 20% 状态栏/控制条、中央 20% 弹窗、底部 40% IME，仅留下 20% 黑行。
        val buffer = frame { y -> y < 8 || y in 12..19 || y >= 24 }
        assertTrue(detect(buffer))
    }

    @Test
    fun `普通亮色页面不误判为安全帧`() {
        assertFalse(detect(frame { true }))
    }

    @Test
    fun `只有零星黑行的页面不误判为安全帧`() {
        val buffer = frame { y -> y % 8 != 0 } // 1/8 黑行，低于 20% 门限
        assertFalse(detect(buffer))
    }

    @Test
    fun `非法尺寸与空缓冲安全返回`() {
        assertFalse(SecureFrameDetector.isMostlyBlack(
            ByteBuffer.allocate(0), rowStride, pixelStride, 0, height
        ))
        assertFalse(detect(ByteBuffer.allocate(16)))
    }
}

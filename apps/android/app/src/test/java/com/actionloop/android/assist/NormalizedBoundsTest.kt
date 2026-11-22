package com.actionloop.android.assist

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 归一化坐标单测（指导书 8.5 / 8.7）。
 * 远程标注坐标必须与分辨率/旋转无关：只接受 [0,1] 内合法矩形，越界一律丢弃，避免误导高亮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NormalizedBoundsTest {

    @Test
    fun `合法矩形通过校验`() {
        assertTrue(NormalizedBounds(0.0, 0.0, 1.0, 1.0).isValid())
        assertTrue(NormalizedBounds(0.25, 0.5, 0.5, 0.25).isValid())
    }

    @Test
    fun `负值越界与超界矩形被拒绝`() {
        assertFalse(NormalizedBounds(-0.1, 0.0, 0.2, 0.2).isValid())
        assertFalse(NormalizedBounds(0.0, -0.1, 0.2, 0.2).isValid())
        assertFalse(NormalizedBounds(0.9, 0.0, 0.2, 0.2).isValid())
        assertFalse(NormalizedBounds(0.0, 0.9, 0.2, 0.2).isValid())
        assertFalse(NormalizedBounds(0.0, 0.0, -0.2, 0.2).isValid())
        assertFalse(NormalizedBounds(Double.NaN, 0.0, 0.2, 0.2).isValid())
    }

    @Test
    fun `像素换算与归一化互为逆运算`() {
        val rect = Rect(108, 216, 432, 324)
        val bounds = NormalizedBounds.fromPixel(rect, 1080, 2160)
        assertNotNull(bounds)
        assertTrue(bounds!!.isValid())
        assertEquals(108, bounds.toPixel(1080, 2160).left)
        assertEquals(216, bounds.toPixel(1080, 2160).top)
        assertEquals(432, bounds.toPixel(1080, 2160).right)
        assertEquals(324, bounds.toPixel(1080, 2160).bottom)
    }

    @Test
    fun `零尺寸屏幕不产生坐标`() {
        assertNull(NormalizedBounds.fromPixel(Rect(0, 0, 10, 10), 0, 1080))
        assertNull(NormalizedBounds.fromPixel(Rect(0, 0, 10, 10), 1080, 0))
    }
}

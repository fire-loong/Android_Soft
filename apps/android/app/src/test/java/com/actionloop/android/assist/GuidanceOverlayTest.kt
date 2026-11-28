package com.actionloop.android.assist

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GuidanceOverlayTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        GuidanceRuntime.overlay?.hide()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun restoreWindowOrder_readdsSameViewsInOriginalOrder() {
        val overlay = GuidanceOverlay(context)
        assertTrue(overlay.show())
        val highlight = overlay.view("highlightView")
        val bar = overlay.view("controlBar") as LinearLayout
        val oldHighlightRoot = highlight.parent
        val oldBarRoot = bar.parent
        assertNotNull(oldHighlightRoot)
        assertNotNull(oldBarRoot)

        overlay.restoreWindowOrder()

        assertSame(highlight, overlay.view("highlightView"))
        assertSame(bar, overlay.view("controlBar"))
        assertNotSame(oldHighlightRoot, highlight.parent)
        assertNotSame(oldBarRoot, bar.parent)
        assertTrue(overlay.isShown())
        assertSame(overlay, GuidanceRuntime.overlay)
    }

    private fun GuidanceOverlay.view(name: String): View {
        val field = GuidanceOverlay::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as View
    }
}

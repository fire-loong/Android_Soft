package com.actionloop.android.assist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 跨 App 悬浮层（指导书 8.5）。
 *
 * - 高亮层默认 FLAG_NOT_TOUCHABLE：触摸完全穿透到用户当前 App，绝不拦截操作；
 * - 控制条是唯一可交互区域，包含 暂停/恢复、求助、停止，且不遮挡系统返回与目标控件；
 * - 高对比描边 + 半透明遮罩 + 目标文字；不只靠颜色区分；
 * - 无高亮时高亮层完全透明，不产生任何遮挡。
 */
class GuidanceOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var highlightView: HighlightView? = null
    private var controlBar: LinearLayout? = null
    private var shown = false

    private class HighlightView(context: Context) : View(context) {
        var snapshot: GuidanceSessionManager.Snapshot? = null
        var displayWidth: Int = 0
        var displayHeight: Int = 0
        private val dimColor: Int = 0x55000000
        private val dimPaint: Paint = Paint().apply { color = dimColor }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676"); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val labelBg = Paint().apply { color = Color.parseColor("#00E676") }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 40f; isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            val hl = snapshot?.highlight ?: return
            if (displayWidth <= 0 || displayHeight <= 0) return
            val rect = hl.bounds.toPixel(displayWidth, displayHeight)
            if (rect.isEmpty) return
            // 半透明遮罩（四块区域绕开目标），保证目标可见
            canvas.drawRect(0f, 0f, width.toFloat(), rect.top.toFloat(), dimPaint)
            canvas.drawRect(0f, rect.bottom.toFloat(), width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, rect.top.toFloat(), rect.left.toFloat(), rect.bottom.toFloat(), dimPaint)
            canvas.drawRect(rect.right.toFloat(), rect.top.toFloat(), width.toFloat(), rect.bottom.toFloat(), dimPaint)
            // 高对比描边
            canvas.drawRect(rect, strokePaint)
            // 目标文字（非仅颜色）
            val label = hl.label
            if (!label.isNullOrBlank()) {
                val text = label.take(16)
                val tw = textPaint.measureText(text)
                val ty = (rect.top - 14).coerceAtLeast(50).toFloat()
                canvas.drawRect(rect.left.toFloat(), ty - 44f, rect.left.toFloat() + tw + 24f, ty, labelBg)
                canvas.drawText(text, rect.left.toFloat() + 12f, ty - 12f, textPaint)
            }
        }
    }

    fun isShown(): Boolean = shown

    fun show(): Boolean {
        if (!GuidanceRuntime.canDrawOverlays(context)) return false
        if (shown) return true
        val dm = context.resources.displayMetrics

        val hv = HighlightView(context).apply {
            setWillNotDraw(false)
        }
        val lpHighlight = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(hv, lpHighlight)
        highlightView = hv

        val bar = buildControlBar()
        val lpBar = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (dm.density * 12).toInt()
        }
        wm.addView(bar, lpBar)
        controlBar = bar

        shown = true
        GuidanceRuntime.attachOverlay(this)
        scope.launch { GuidanceRuntime.render(context) }
        return true
    }

    @SuppressLint("SetTextI18n")
    private fun buildControlBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xDD111111.toInt())
                cornerRadius = 24f
            }
            setPadding(24, 16, 24, 16)
        }
        val stateText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "引导：就绪"
            setPadding(0, 0, 20, 0)
        }
        val pauseBtn = Button(context).apply {
            text = "暂停"
            setOnClickListener {
                scope.launch {
                    val m = GuidanceRuntime.manager(context)
                    val snap = m.snapshot()
                    if (snap.state == GuidanceSessionState.PAUSED) {
                        m.userResume()
                    } else {
                        m.pause("user_overlay")
                    }
                    GuidanceRuntime.render(context)
                }
            }
        }
        val stopBtn = Button(context).apply {
            text = "停止"
            setOnClickListener {
                scope.launch {
                    GuidanceRuntime.manager(context).stop("user_overlay_stop")
                    GuidanceRuntime.render(context)
                }
                ScreenCaptureService.stop(context)
            }
        }
        bar.addView(stateText)
        bar.addView(pauseBtn)
        bar.addView(stopBtn)
        bar.tag = stateText
        return bar
    }

    fun render(snapshot: GuidanceSessionManager.Snapshot) {
        val dm = context.resources.displayMetrics
        val w = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.width()
        } else dm.widthPixels
        val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else dm.heightPixels
        highlightView?.let {
            it.snapshot = snapshot
            it.displayWidth = w
            it.displayHeight = h
            it.invalidate()
        }
        (controlBar?.tag as? TextView)?.let { tv ->
            val stateLabel = when (snapshot.state) {
                GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER -> "高亮中"
                GuidanceSessionState.ALIGNING -> "对齐中"
                GuidanceSessionState.TEXT_ONLY -> "仅文字指引"
                GuidanceSessionState.PAUSED -> "已暂停"
                GuidanceSessionState.STOPPED -> "已停止"
                GuidanceSessionState.COMPLETED -> "本步完成"
                else -> "就绪"
            }
            val sens = if (snapshot.pageSensitive) " · 敏感页已遮罩" else ""
            tv.text = "引导：$stateLabel$sens"
        }
    }

    fun hide() {
        GuidanceRuntime.detachOverlay(this)
        shown = false
        highlightView?.let { runCatching { wm.removeView(it) } }
        controlBar?.let { runCatching { wm.removeView(it) } }
        highlightView = null
        controlBar = null
    }
}

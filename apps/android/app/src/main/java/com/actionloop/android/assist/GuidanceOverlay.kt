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
import android.widget.Toast
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
    private var pauseButton: Button? = null
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
        /** 指针标注：红色实心 + 白色描边，不依赖颜色也能看清。 */
        private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF1744") }
        private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val hintBg = Paint().apply { color = Color.parseColor("#DD000000") }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 38f; isFakeBoldText = true
        }

        private val viewLoc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            val snap = snapshot ?: return
            if (displayWidth <= 0 || displayHeight <= 0) return
            // 标注坐标来自无障碍帧（全屏坐标系），而本 View 的窗口可能从状态栏下方开始（y=133）。
            // 必须把“全屏坐标”换算到“View 局部坐标”，否则高亮会整体下移一行，落到相邻按钮上。
            getLocationOnScreen(viewLoc)
            snap.highlight?.let { hl ->
                val rect = hl.bounds.toPixel(displayWidth, displayHeight)
                rect.offset(-viewLoc[0], -viewLoc[1])
                if (!rect.isEmpty) {
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
            // 指针标注：与高亮可同时存在，仍完全触摸穿透。
            snap.pointer?.let { p ->
                val rect = p.toPixel(displayWidth, displayHeight)
                rect.offset(-viewLoc[0], -viewLoc[1])
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                canvas.drawCircle(cx, cy, 30f, pointerFill)
                canvas.drawCircle(cx, cy, 30f, pointerStroke)
                canvas.drawCircle(cx, cy, 6f, pointerStroke)
            }
            // 远程文字提示：底部高对比条，只展示文字，不接收输入。
            snap.textHint?.takeIf { it.isNotBlank() }?.let { hint ->
                var text = hint.take(80).replace('\n', ' ')
                val maxWidth = width - 96f
                while (text.length > 1 && hintPaint.measureText(text) > maxWidth) {
                    text = text.dropLast(1)
                }
                val tw = hintPaint.measureText(text)
                val top = (height - 260f).coerceAtLeast(160f)
                canvas.drawRoundRect(48f, top, 48f + tw + 48f, top + 104f, 28f, 28f, hintBg)
                canvas.drawText(text, 72f, top + 68f, hintPaint)
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
        if (runCatching { wm.addView(hv, lpHighlight) }.isFailure) return false
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
        val barAdded = runCatching { wm.addView(bar, lpBar) }.isSuccess
        if (!barAdded) {
            // 控制条添加失败时不能留下“无停止入口”的高亮层：整体回滚。
            runCatching { wm.removeView(hv) }
            highlightView = null
            return false
        }
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
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        // 固定状态文字宽度：否则状态文案变长时整条控制条会重排，按钮跳到手指之外；
        // 敏感页长文案还会把“停止”挤出屏幕（真机复跑发现）。
        val stateText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "引导：就绪"
            setPadding(0, 0, (8 * density).toInt(), 0)
            width = (screenWidth * 0.30f).toInt()
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val pauseBtn = Button(context).apply {
            text = "暂停"
            minimumWidth = (56 * density).toInt()
            minWidth = minimumWidth
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setOnClickListener {
                scope.launch {
                    val m = GuidanceRuntime.manager(context)
                    val state = m.snapshot().state
                    when (state) {
                        // 无障碍被撤销后本地不再有帧，恢复/再次同意前必须让用户重新授权，
                        // 否则会“恢复成功但无引导”（PAUSED 与远程 RESUME 落到的 CONSENT_PENDING 都要拦）。
                        GuidanceSessionState.PAUSED, GuidanceSessionState.CONSENT_PENDING ->
                            if (GuidanceRuntime.isAccessibilityEnabled(context)) {
                                // 远程 RESUME 只把状态推到 CONSENT_PENDING，必须由用户在本机再次同意（8.3）。
                                if (state == GuidanceSessionState.PAUSED) m.userResume() else m.consentGranted()
                            } else {
                                Toast.makeText(
                                    context,
                                    "只读无障碍已关闭，请先在系统设置中重新开启",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        else -> m.pause("user_overlay")
                    }
                    GuidanceRuntime.render(context)
                }
            }
        }
        this.pauseButton = pauseBtn
        val helpBtn = Button(context).apply {
            text = "求助"
            minimumWidth = (56 * density).toInt()
            minWidth = minimumWidth
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setOnClickListener { GuidanceRuntime.requestHelp(context) }
        }
        val stopBtn = Button(context).apply {
            text = "停止"
            minimumWidth = (56 * density).toInt()
            minWidth = minimumWidth
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
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
        bar.addView(helpBtn)
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
            // 供 TalkBack 读取当前提示；不依赖颜色传达信息（8.5）。
            it.contentDescription = buildString {
                snapshot.textHint?.takeIf(String::isNotBlank)?.let { hint -> append(hint) }
                snapshot.highlight?.label?.takeIf(String::isNotBlank)?.let { label ->
                    if (isNotEmpty()) append("；")
                    append("高亮目标：").append(label)
                }
                if (snapshot.pageSensitive) {
                    if (isNotEmpty()) append("；")
                    append("检测到敏感页面，已暂停并清除标注")
                }
            }.ifBlank { "引导提示" }
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
                GuidanceSessionState.CONSENT_PENDING -> "待再次同意"
                else -> "就绪"
            }
            val sens = if (snapshot.pageSensitive) " · 敏感页已遮罩" else ""
            tv.text = "引导：$stateLabel$sens"
        }
        pauseButton?.text =
            if (snapshot.state == GuidanceSessionState.PAUSED ||
                snapshot.state == GuidanceSessionState.CONSENT_PENDING
            ) "恢复" else "暂停"
    }

    /**
     * 多窗口切换可能让既有应用窗口越过 TYPE_APPLICATION_OVERLAY 的既有 WindowState。
     * 只在系统明确回调多窗口变化时按原顺序重挂载；失败则停止采集，绝不留下无控制条的会话。
     */
    fun restoreWindowOrder() {
        if (!shown) return
        val hv = highlightView ?: return
        val bar = controlBar ?: return
        val highlightParams = hv.layoutParams
        val barParams = bar.layoutParams
        // 必须同步 detach：普通 removeView 可能只投递异步 die，随后 addView 会报 already added。
        if (runCatching {
                wm.removeViewImmediate(hv)
                wm.removeViewImmediate(bar)
            }.isFailure
        ) {
            hide()
            ScreenCaptureService.stop(context)
            return
        }
        if (runCatching {
                wm.addView(hv, highlightParams)
                wm.addView(bar, barParams)
            }.isFailure
        ) {
            runCatching { wm.removeViewImmediate(hv) }
            runCatching { wm.removeViewImmediate(bar) }
            hide()
            ScreenCaptureService.stop(context)
            return
        }
        scope.launch { GuidanceRuntime.render(context) }
    }

    fun hide() {
        GuidanceRuntime.detachOverlay(this)
        shown = false
        highlightView?.let { runCatching { wm.removeView(it) } }
        controlBar?.let { runCatching { wm.removeView(it) } }
        highlightView = null
        controlBar = null
        pauseButton = null
    }
}

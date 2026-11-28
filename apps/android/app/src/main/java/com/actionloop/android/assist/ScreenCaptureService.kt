package com.actionloop.android.assist

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.actionloop.android.R

/**
 * FLAG_SECURE 黑帧判定：按水平采样行统计，而不是对全屏亮像素取总比例。
 * 系统栏、控制条、弹窗与 IME 可能覆盖大块区域；只要仍有至少 20% 的采样行
 * 基本全黑，就按安全帧处理。完全被亮色窗口覆盖时没有应用像素可判，待遮挡消失后探测。
 */
internal object SecureFrameDetector {
    private const val SAMPLE_ROWS = 40
    private const val SAMPLE_COLUMNS = 30
    private const val DARK_CHANNEL_MAX = 12
    private const val MAX_BRIGHT_RATIO_PER_DARK_ROW = 0.01
    private const val MIN_DARK_ROW_RATIO = 0.20

    fun isMostlyBlack(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): Boolean {
        if (width <= 0 || height <= 0 || pixelStride <= 0 || rowStride <= 0) return false
        var darkRows = 0
        var checkedRows = 0
        repeat(SAMPLE_ROWS) { row ->
            val y = row * height / SAMPLE_ROWS
            var bright = 0
            var checked = 0
            repeat(SAMPLE_COLUMNS) { column ->
                val x = column * width / SAMPLE_COLUMNS
                val offset = y * rowStride + x * pixelStride
                if (offset < 0 || offset + 3 >= buffer.limit()) return false
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                checked++
                if (r > DARK_CHANNEL_MAX || g > DARK_CHANNEL_MAX || b > DARK_CHANNEL_MAX) bright++
            }
            if (checked > 0) {
                checkedRows++
                if (bright.toDouble() / checked < MAX_BRIGHT_RATIO_PER_DARK_ROW) darkRows++
            }
        }
        return checkedRows > 0 && darkRows.toDouble() / checkedRows >= MIN_DARK_ROW_RATIO
    }
}

/**
 * 黑帧持续判定：普通应用切换/窗口动画的瞬时黑场不得判为 FLAG_SECURE。
 * 阈值取 1.2s，覆盖 vivo 等 OEM 较长的窗口转场动画；安全页是静止黑场，仍会被捕获。
 * 离开黑场后由采集侧把起点清零。
 */
internal object SecureFrameLatch {
    const val HOLD_MS = 1200L

    fun isSustained(blackSinceMs: Long, nowMonoMs: Long): Boolean =
        blackSinceMs > 0L && nowMonoMs - blackSinceMs >= HOLD_MS
}

/**
 * 本机会话最长存活时间（指导书 8.3 / 8.6：超时是必须的停止路径）。
 * 与远程协助默认 15 分钟一致；到期由采集循环主动停止，避免长期占用屏幕采集与悬浮层。
 */
internal object GuidanceSessionTimeout {
    const val MAX_SESSION_MS = 15 * 60 * 1000L

    fun isExpired(startedMonoMs: Long, nowMonoMs: Long): Boolean =
        startedMonoMs > 0L && nowMonoMs - startedMonoMs >= MAX_SESSION_MS
}

/**
 * 屏幕采集前台服务（指导书 8.3 / 8.10 / 安全边界）。
 *
 * - 仅在用户当次授予 MediaProjection 后启动，不缓存授权；
 * - 采集到的帧只在内存中用于处理与（后续）远程分享，绝不落盘、绝不录制为视频；
 * - 用户撤销授权（MediaProjection.Callback.onStop）、锁屏、停止通知按钮 → 立即停止；
 * - 前台通知常驻，带“停止引导”动作；
 * - 驱动悬浮层按当前会话状态渲染。
 */
class ScreenCaptureService : Service() {

    private lateinit var scope: CoroutineScope
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: GuidanceOverlay? = null
    private var renderJob: Job? = null

    /**
     * 黑帧闩锁（8.5/8.10）：MediaProjection 对 FLAG_SECURE 窗口输出黑帧。
     * 每个到达帧都采样；只有持续黑帧超过 [SecureFrameLatch.HOLD_MS] 才判为安全窗口，
     * 避免普通应用切换/窗口动画的瞬时黑场误暂停。离开黑场后由非黑帧清零。
     */
    @Volatile private var blackSinceMs = 0L

    /** 资源句柄的整体摘除锁：采集回调线程与主线程可能并发进入，摘除与释放必须原子分界。 */
    private val resourceLock = Any()

    private val TERMINAL_ASSIST_STATES = setOf("STOPPED", "EXPIRED", "COMPLETED")

    /**
     * 会话纪元：每次 onStartCommand 建立新会话时递增。
     * 旧渲染循环只能停掉与自身纪元一致的会话，避免“旧会话看到 STOPPED 就把刚启动的新会话 stopSelf 掉”。
     */
    private var sessionEpoch = 0

    /** 本会话开始的单调时钟，用于 15 分钟超时停止；0 表示尚未开始。 */
    @Volatile private var sessionStartedMonoMs = 0L

    private class SessionResources(
        val display: VirtualDisplay?,
        val reader: ImageReader?,
        val projection: MediaProjection?,
        val overlay: GuidanceOverlay?
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        handlerThread = HandlerThread("actionloop-capture").apply { start() }
        handler = Handler(handlerThread.looper)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 与 AssistMediaService 相同：停止动作可能经 startForegroundService 送达，
            // Android 14+ 必须先 startForeground，否则进程被系统以 FGS 超时异常杀死。
            // 类型校验失败时也不能中断停止流程。
            runCatching { startForegroundCompat() }
            stopCapture("notification_stop")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = parcelableExtra(intent, EXTRA_RESULT_DATA)
        startForegroundCompat()
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection 未授权，停止采集")
            stopCapture("not_authorized")
            return START_NOT_STICKY
        }
        // 悬浮层与权限由启动方保证；这里兜底检查，未授权则不启动采集。
        if (!GuidanceRuntime.canDrawOverlays(this)) {
            stopCapture("no_overlay_permission")
            return START_NOT_STICKY
        }
        val newOverlay = GuidanceOverlay(this)
        if (!newOverlay.show()) {
            stopCapture("overlay_show_failed")
            return START_NOT_STICKY
        }
        // 只在本会话资源整体摘除后挂载，避免旧会话遗留的 teardown 去 hide 新悬浮层。
        releaseResources(detachResources())
        val epoch = synchronized(resourceLock) { sessionEpoch += 1; sessionEpoch }
        sessionStartedMonoMs = android.os.SystemClock.elapsedRealtime()
        blackSinceMs = 0L
        synchronized(resourceLock) { overlay = newOverlay }
        scope.launch {
            GuidanceRuntime.manager(this@ScreenCaptureService).start()
            GuidanceRuntime.manager(this@ScreenCaptureService).consentGranted()
            GuidanceRuntime.render(this@ScreenCaptureService)
        }
        beginCapture(resultCode, data)
        startRenderLoop(epoch)
        return START_NOT_STICKY
    }

    private fun isCurrentEpoch(epoch: Int): Boolean =
        synchronized(resourceLock) { sessionEpoch == epoch }

    private fun isCurrentProjection(projection: MediaProjection): Boolean =
        synchronized(resourceLock) { mediaProjection === projection }

    private fun isCurrentReader(reader: ImageReader): Boolean =
        synchronized(resourceLock) { imageReader === reader }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            stopCapture("projection_null")
            return
        }
        synchronized(resourceLock) { mediaProjection = projection }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 迟到的旧会话回调不得拆掉新会话：仅当当前投影仍是这一个时才停止。
                if (isCurrentProjection(projection)) stopCapture("user_revoked")
            }
        }, handler)

        val dm = resources.displayMetrics
        // 降采样到 720p 降低负载；只用于处理，不落盘。
        val width = 720
        val height = (width * dm.heightPixels / dm.widthPixels).coerceAtLeast(480)
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        synchronized(resourceLock) { imageReader = reader }
        reader.setOnImageAvailableListener({ r ->
            // 旧会话的读帧回调不得影响新会话（例如误判锁屏后停掉新会话）。
            if (!isCurrentReader(r)) {
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            // 锁屏必须优先于“暂停丢帧”判定：否则 PAUSED 期间锁屏不会停掉 MediaProjection（8.3/8.10）。
            val kg = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (runCatching { kg.isKeyguardLocked }.getOrDefault(false)) {
                r.acquireLatestImage()?.close()
                stopCapture("lockscreen")
                return@setOnImageAvailableListener
            }
            // 每个到达帧都采样黑帧（约 40×30 次读取，开销可忽略），并更新闩锁：
            // - 即使处于 PAUSED 也更新，才能在离开安全页后解除闩锁；
            // - 不再用 400ms 节流丢弃转场黑帧（否则静止后无新帧，永久漏判 FLAG_SECURE）。
            val image = r.acquireLatestImage()
            if (image != null) {
                val black = runCatching { isMostlyBlack(image) }.getOrDefault(false)
                val nowMono = android.os.SystemClock.elapsedRealtime()
                // 投影刚建立时的预热帧也可能是全黑；前 2 秒不计入黑场闩锁，避免启动即误暂停。
                val warmupDone = nowMono - sessionStartedMonoMs >= 2000L
                // 即使处于 PAUSED 也更新：离开安全页后必须能清零，恢复才真正生效。
                if (black && warmupDone) {
                    if (blackSinceMs == 0L) blackSinceMs = nowMono
                } else {
                    blackSinceMs = 0L
                }
                // 关键：只在内存处理，立即关闭，绝不写文件/不落盘。
                image.close()
            }
            // 暂停/停止后不再处理其他帧（8.5 暂停媒体），暂停动作由渲染循环统一执行。
        }, handler)

        val display = runCatching {
            projection.createVirtualDisplay(
                "actionloop-guide", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        }.getOrNull()
        if (display == null) {
            // 投影可能已被用户撤销/被并发 teardown 停掉：按失败安全停止，不抛到主线程。
            stopCapture("display_failed")
            return
        }
        val accepted = synchronized(resourceLock) {
            if (mediaProjection === projection) {
                virtualDisplay = display
                true
            } else {
                false
            }
        }
        if (!accepted) runCatching { display.release() }
    }

    private fun startRenderLoop(epoch: Int) {
        renderJob?.cancel()
        renderJob = scope.launch {
            var lastDisplayKey: String? = null
            var wasCollecting = false
            while (isActive) {
                // 新会话已建立：本循环属于旧会话，直接退出，绝不能清理新会话资源或 stopSelf。
                if (!isCurrentEpoch(epoch)) return@launch
                val mgr = GuidanceRuntime.manager(this@ScreenCaptureService)
                val collecting = mgr.isCollecting()
                // 从暂停恢复：先清零黑场起点，等待恢复后的新帧重新确认。
                // 否则转场期间的旧闩锁会让“恢复”后的第一个 tick 立刻再次暂停。
                // 代价是若恢复时仍停留在 FLAG_SECURE 页，最多再等一个闩锁周期才暂停；
                // 密码/支付关键词/锁屏等无障碍硬判定不受影响，仍立即可用。
                if (collecting && !wasCollecting) blackSinceMs = 0L
                wasCollecting = collecting
                // 会话超时（8.3/8.6）：主动停止采集与悬浮层，不能长期占用授权。
                if (GuidanceSessionTimeout.isExpired(
                        sessionStartedMonoMs, android.os.SystemClock.elapsedRealtime()
                    )
                ) {
                    // 超时是停止路径，但不能静默消失：先给用户一条可见提示，再释放采集。
                    android.widget.Toast.makeText(
                        this@ScreenCaptureService,
                        "引导已达 15 分钟上限，已自动停止",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    stopCapture("session_timeout")
                    return@launch
                }
                val dm = resources.displayMetrics
                val orientation = resources.configuration.orientation
                val key = "${dm.widthPixels}x${dm.heightPixels}@$orientation"
                if (lastDisplayKey != null && lastDisplayKey != key) {
                    // 旋转/分屏/分辨率变化：旧归一化坐标立即作废并暂停（8.4/8.5）。
                    mgr.onDisplayChanged()
                }
                lastDisplayKey = key
                // 先渲染：恢复/状态变化会重绘悬浮层，确保虚拟显示产生新帧供黑场采样。
                GuidanceRuntime.render(this@ScreenCaptureService)
                // FLAG_SECURE 黑帧闩锁：持续黑场超过阈值才暂停；离开安全页后由非黑帧清零。
                if (collecting && SecureFrameLatch.isSustained(
                        blackSinceMs, android.os.SystemClock.elapsedRealtime()
                    )
                ) {
                    mgr.onCaptureSecure()
                }
                // 远程停止/会话结束后，服务必须真正释放采集与悬浮层（8.7），不能只改状态。
                if (GuidanceRuntime.manager(this@ScreenCaptureService).snapshot().state == GuidanceSessionState.STOPPED &&
                    isCurrentEpoch(epoch)
                ) {
                    teardown()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                delay(400)
            }
        }
    }

    /**
     * 检测采集帧是否被系统涂黑（FLAG_SECURE 窗口在 MediaProjection 截屏中的标志性表现）。
     * 抽样网格点统计亮像素占比，低于阈值即判为安全帧。
     * 说明：纯黑壁纸会误报，但误报仅触发清除高亮并在窗口过期后自动恢复，不产生安全越权。
     */
    private fun isMostlyBlack(image: android.media.Image): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        return SecureFrameDetector.isMostlyBlack(
            plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height
        )
    }

    /**
     * 原子摘除当前会话的全部资源句柄（置空并返回快照）。
     * 摘除后任何迟到的旧回调都只会看到 null 或新句柄，不会重复释放同一对象。
     */
    private fun detachResources(): SessionResources = synchronized(resourceLock) {
        val snapshot = SessionResources(virtualDisplay, imageReader, mediaProjection, overlay)
        virtualDisplay = null; imageReader = null; mediaProjection = null; overlay = null
        snapshot
    }

    /** 释放已摘除的资源。逐个 runCatching：系统句柄对重复释放不友好，任何一步失败都不影响其余释放。 */
    private fun releaseResources(resources: SessionResources) {
        runCatching { resources.display?.release() }
        runCatching { resources.reader?.close() }
        runCatching { resources.projection?.stop() }
        // WindowManager.removeView 必须在主线程；采集回调线程触发停止时切回主线程，避免悬浮层泄漏。
        resources.overlay?.let { ov ->
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runCatching { ov.hide() }
            } else {
                scope.launch { runCatching { ov.hide() } }
            }
        }
    }

    /** 释放采集与悬浮层资源（不触发状态机/通知）。供正常停止与 onDestroy 兜底复用，避免异常回收泄漏。 */
    private fun teardown() {
        renderJob?.cancel()
        renderJob = null
        releaseResources(detachResources())
    }

    /**
     * 统一停止入口：无论来自主线程、采集回调线程还是通知动作，都在主线程完成
     * 资源释放、状态收敛、悬浮层移除与前台服务退出，避免跨线程操作 WindowManager。
     */
    private fun stopCapture(reason: String) {
        val epoch = synchronized(resourceLock) { sessionEpoch }
        scope.launch {
            // 迟到停止不得误杀之后启动的新会话（旧锁屏/撤权回调可能晚于新会话到达）。
            if (!isCurrentEpoch(epoch)) return@launch
            teardown()
            // 撤权/锁屏/手动停止后不得让协助会话继续显示“进行中”：同时结束远程协助并释放媒体。
            val assist = AssistSignalClient.current()
            if (assist != null && assist.state !in TERMINAL_ASSIST_STATES) {
                AssistSignalClient.stop(this@ScreenCaptureService, "capture_stopped:$reason")
            }
            GuidanceRuntime.manager(this@ScreenCaptureService).stop(reason)
            GuidanceRuntime.render(this@ScreenCaptureService)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        teardown()
        runCatching { handlerThread.quitSafely() }
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun parcelableExtra(intent: Intent?, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(key, Intent::class.java)
        } else {
            intent?.getParcelableExtra(key)
        }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, getString(R.string.assist_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = "引导进行中常驻通知，可随时停止"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.assist_notif_title))
            .setContentText(getString(R.string.assist_notif_text))
            .setSmallIcon(R.drawable.ic_assist)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_assist, getString(R.string.assist_notif_stop), stopPi)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.actionloop.android.assist.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "actionloop_assist"
        private const val NOTIF_ID = 4321
        private const val TAG = "ScreenCaptureService"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            // 同 AssistMediaService：停止不需要前台服务启动契约，避免 Android 14+ 的 FGS 超时崩溃。
            runCatching { context.startService(i) }
                .onFailure { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
        }
    }
}

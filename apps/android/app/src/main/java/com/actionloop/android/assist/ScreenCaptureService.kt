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

    @Volatile private var lastProcessMs = 0L

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
            stopCapture("notification_stop")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = parcelableExtra(intent, EXTRA_RESULT_DATA)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
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
        overlay = GuidanceOverlay(this).also { it.show() }
        scope.launch {
            GuidanceRuntime.manager(this@ScreenCaptureService).start()
            GuidanceRuntime.manager(this@ScreenCaptureService).consentGranted()
            GuidanceRuntime.render(this@ScreenCaptureService)
        }
        beginCapture(resultCode, data)
        startRenderLoop()
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            stopCapture("projection_null")
            return
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture("user_revoked")
            }
        }, handler)

        val dm = resources.displayMetrics
        // 降采样到 720p 降低负载；只用于处理，不落盘。
        val width = 720
        val height = (width * dm.heightPixels / dm.widthPixels).coerceAtLeast(480)
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            if (now - lastProcessMs < 400) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastProcessMs = now
            val image = r.acquireLatestImage()
            if (image != null) {
                // 关键：只在内存处理，立即关闭，绝不写文件/不落盘。
                runCatching {
                    val kg = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (kg.isKeyguardLocked) {
                        image.close()
                        stopCapture("lockscreen")
                        return@setOnImageAvailableListener
                    }
                    // FLAG_SECURE 窗口在 MediaProjection 截屏中会被系统涂黑（8.5/8.10），据此清除高亮。
                    if (isMostlyBlack(image)) {
                        GuidanceRuntime.manager(this@ScreenCaptureService).markCaptureSecure()
                    }
                }
                image.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "actionloop-guide", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun startRenderLoop() {
        renderJob?.cancel()
        renderJob = scope.launch {
            while (isActive) {
                GuidanceRuntime.render(this@ScreenCaptureService)
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
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixelStride <= 0) return false
        val samples = 1200
        var nonZero = 0
        var checked = 0
        val rnd = java.util.Random(0)
        repeat(samples) {
            val x = rnd.nextInt(width)
            val y = rnd.nextInt(height)
            val offset = y * rowStride + x * pixelStride
            if (offset + 3 < buffer.limit()) {
                val rr = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                checked++
                if (rr > 12 || g > 12 || b > 12) nonZero++
            }
        }
        return checked > 0 && nonZero.toDouble() / checked < 0.01
    }

    /** 释放采集与悬浮层资源（不触发状态机/通知）。供正常停止与 onDestroy 兜底复用，避免异常回收泄漏。 */
    private fun teardown() {
        renderJob?.cancel()
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null; imageReader = null; mediaProjection = null
        overlay?.hide(); overlay = null
    }

    private fun stopCapture(reason: String) {
        teardown()
        scope.launch {
            GuidanceRuntime.manager(this@ScreenCaptureService).stop(reason)
            GuidanceRuntime.render(this@ScreenCaptureService)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            ContextCompat.startForegroundService(context, i)
        }
    }
}

package com.actionloop.android.assist

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.actionloop.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程协助媒体通道（指导书 8.3 / 8.7 / 8.10 / 13.5）。
 *
 * - 仅在用户**当次**显式授权 MediaProjection 后启动；每次共享都重新授权，不缓存、不复用旧授权；
 * - 通过 WebRTC（DTLS-SRTP）把屏幕**只读**共享给协助者，信令复用后端 OFFER/ANSWER/ICE_CANDIDATE；
 * - 不录音、不录像、不落盘：只有 PeerConnection 内存管线；
 * - 敏感页（无障碍判定 / 采集侧黑帧探测）、锁屏、协助会话暂停/终止、用户撤权 → 立即停发并释放；
 * - 协助者永远没有点击/输入/支付通道（本服务只发送视频轨，不接收也不执行任何控制信令）。
 */
class AssistMediaService : Service() {

    private lateinit var scope: CoroutineScope
    private var policyJob: Job? = null

    @Volatile private var sessionId: String? = null
    @Volatile private var eglBase: EglBase? = null
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var secureSink: VideoSink? = null
    @Volatile private var capturer: ScreenCapturerAndroid? = null
    @Volatile private var surfaceHelper: SurfaceTextureHelper? = null

    @Volatile private var sending = false
    @Volatile private var answerReceived = false
    @Volatile private var renegotiationCount = 0
    private val frameCount = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var pendingOffer: SessionDescription? = null
    @Volatile private var offerResendCount = 0
    @Volatile private var lastOfferResendMs = 0L
    @Volatile private var lastSecureProbeMs = 0L
    @Volatile private var lastStatsLogMs = 0L
    @Volatile private var lastRenegotiateMs = 0L
    @Volatile private var stopping = false
    @Volatile private var setupDone = false

    private var peerThread: android.os.HandlerThread? = null
    private var peerHandler: android.os.Handler? = null

    private val stopLock = Any()

    /** 媒体事件回调：只由本服务注册；停止时仅移除自己的回调，避免误清其他实例。 */
    private val mediaHandler: (String, Map<String, Any?>) -> Unit = { type, payload ->
        if (type == "ANSWER") applyAnswer(payload)
        else if (type == "ICE_CANDIDATE") applyRemoteCandidate(payload)
        else AssistSecurityAudit.record(this, "MEDIA_EVENT_IGNORED", "type=$type")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // WebRTC 工厂/编解码器初始化可能耗时数百毫秒到数秒，必须离开主线程，避免阻塞系统对话框与策略循环。
        peerThread = android.os.HandlerThread("actionloop-media").also { it.start() }
        peerHandler = android.os.Handler(peerThread!!.looper)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 停止动作也可能通过 startForegroundService 送达（例如服务尚未运行时的“停止共享”）。
            // Android 14+ 要求 startForegroundService 后必须尽快 startForeground，否则进程会被
            // ForegroundServiceDidNotStartInTimeException 杀死（真机已复现）。先满足契约再停止；
            // 若无活动投屏令牌导致类型校验失败，也必须继续执行停止，不能被异常打断。
            runCatching { startForegroundCompat() }
            stopMedia("notification_stop")
            return START_NOT_STICKY
        }
        val sid = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = parcelableExtra(intent, EXTRA_RESULT_DATA)
        sessionId = sid
        startForegroundCompat()
        if (sid.isBlank() || AssistSignalClient.current()?.id != sid || resultCode != Activity.RESULT_OK || data == null) {
            AssistSecurityAudit.record(this, "MEDIA_START_REJECTED", "session=$sid code=$resultCode data=${data != null}")
            stopMedia("invalid_start")
            return START_NOT_STICKY
        }
        AssistSignalClient.mediaEventHandler = mediaHandler
        isRunning = true
        AssistSecurityAudit.record(this, "MEDIA_START", "session=$sid")
        peerHandler?.post {
            runCatching { setupPeerConnection(data) }.onFailure {
                Log.e(TAG, "媒体初始化失败", it)
                AssistSecurityAudit.record(this, "MEDIA_SETUP_FAILED", it.javaClass.simpleName)
                stopMedia("setup_failed")
            }
        }
        if (stopping) return START_NOT_STICKY
        startPolicyLoop()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---- WebRTC 建连 ----

    private fun setupPeerConnection(permissionData: Intent) {
        Log.i(TAG, "setup start")
        initializeFactoryOnce(applicationContext)
        Log.i(TAG, "webrtc initialize done")
        val egl = EglBase.create()
        eglBase = egl
        Log.i(TAG, "egl created")
        val helper = SurfaceTextureHelper.create("ActionLoopMedia", egl.eglBaseContext)
        surfaceHelper = helper
        Log.i(TAG, "surface helper created")
        if (stopping) return
        val pf = PeerConnectionFactory.builder()
            // 必须显式提供编解码工厂：本版本 SDK 的 builder 默认不带视频 codec，
            // 否则生成的 offer 里 m=video 端口为 0、没有任何 rtpmap，媒体永远建立不起来。
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        Log.i(TAG, "peer connection factory created")
        factory = pf
        val source = pf.createVideoSource(true)
        videoSource = source
        val track = pf.createVideoTrack("screen0", source)
        videoTrack = track
        Log.d(TAG, "video source/track ready")
        // 采集侧黑帧探测：FLAG_SECURE 窗口在屏幕共享中会被系统涂黑，命中即停发并暂停引导。
        val sink = VideoSink { frame ->
            runCatching { probeSecureFrame(frame) }
            runCatching {
                val n = frameCount.incrementAndGet()
                // 运行时可观测性：证明采集管线确实在出帧（仅日志，不落盘、不含画面内容）。
                if (n == 1L || n % 60L == 0L) Log.d(TAG, "capture frames=$n")
            }
        }
        secureSink = sink
        track.addSink(sink)

        val pc = pf.createPeerConnection(buildRtcConfig(), peerObserver)
        if (pc == null) {
            throw IllegalStateException("PeerConnection 创建失败")
        }
        peerConnection = pc
        pc.addTrack(track, listOf("actionloop"))

        val screenCapturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户撤销授权：必须立即释放，不允许继续持有 MediaProjection。
                stopMedia("projection_revoked")
            }
        })
        capturer = screenCapturer
        screenCapturer.initialize(helper, applicationContext, source.capturerObserver)
        setupDone = true
        Log.d(TAG, "peer/capturer ready, creating offer")
        createOffer()
    }

    private fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        val creds = AssistSignalClient.turnCredentials
        if (creds != null && creds.urls.isNotEmpty()) {
            val builder = PeerConnection.IceServer.builder(creds.urls)
            if (!creds.username.isNullOrBlank() && !creds.credential.isNullOrBlank()) {
                builder.setUsername(creds.username)
                builder.setPassword(creds.credential)
            }
            iceServers += builder.createIceServer()
        }
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // 使用默认的 BALANCED 打包策略：MAXBUNDLE 在部分设备/版本上会因
            // “offer 无 BUNDLE group”而拒绝 setLocalDescription，导致媒体永远建不起来。
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription?) {
                val offer = description ?: return
                val current = peerConnection ?: return
                current.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (stopping) return
                        pendingOffer = offer
                        publishOffer(offer)
                    }

                    override fun onSetFailure(error: String?) {
                        AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_LOCAL_SDP_FAILED", error.orEmpty())
                        // 协商失败就立即释放：不能让用户以为“正在共享”而实际没有媒体通道。
                        stopMedia("local_sdp_failed")
                    }
                }, offer)
            }

            override fun onCreateFailure(error: String?) {
                AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_OFFER_FAILED", error.orEmpty())
                stopMedia("offer_failed")
            }
        }, MediaConstraints())
    }

    private fun publishOffer(description: SessionDescription) {
        lastOfferResendMs = SystemClock.elapsedRealtime()
        offerResendCount += 1
        scope.launch {
            val payload = buildJsonObject {
                put("type", "offer")
                put("sdp", description.description)
            }
            AssistSignalClient.sendMediaSignal("OFFER", payload)
        }
    }

    private fun applyAnswer(payload: Map<String, Any?>) {
        val sdp = payload["sdp"] as? String
        if (sdp.isNullOrBlank()) {
            AssistSecurityAudit.record(this, "MEDIA_ANSWER_REJECTED", "missing_sdp")
            return
        }
        val type = (payload["type"] as? String)?.lowercase()
        if (type != null && type != "answer") {
            // 请求方只接受 ANSWER；收到 OFFER 视为方向错误，拒绝并审计。
            AssistSecurityAudit.record(this, "MEDIA_ANSWER_REJECTED", "type=$type")
            return
        }
        val current = peerConnection ?: return
        if (answerReceived) {
            // 已协商完成又收到新的 ANSWER：说明协助者重建了 PeerConnection（刷新页面、切换设备、
            // 重新进入协助台）。直接 setRemoteDescription 会因 “wrong state: stable” 失败，
            // 对端将永远停在 have-local-offer、收不到任何画面。此时必须由请求方发起一次
            // ICE 重启协商，让新对端拿到新的 offer/candidates 后重新建立媒体通道。
            renegotiate("duplicate_answer")
            return
        }
        current.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                answerReceived = true
                AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_ANSWER_APPLIED", "session=$sessionId")
            }

            override fun onSetFailure(error: String?) {
                AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_ANSWER_FAILED", error.orEmpty())
                if (error?.contains("wrong state", ignoreCase = true) == true) {
                    renegotiate("answer_wrong_state")
                }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** 对端重建后重新发 offer；ICE 重启保证新的对端能获取本端新候选。 */
    private fun renegotiate(reason: String) {
        if (stopping) return
        val now = SystemClock.elapsedRealtime()
        // 冷却窗口：多个协助台标签页同时轮询时会连续产生 ANSWER，若每次都重启协商，
        // 会在两个对端之间反复切换并迅速耗尽次数。短时间内只接受一次重新协商。
        if (now - lastRenegotiateMs < RENEGOTIATE_COOLDOWN_MS) {
            AssistSecurityAudit.record(this, "MEDIA_RENEGOTIATE_SKIPPED", "reason=$reason cooldown")
            return
        }
        if (renegotiationCount >= MAX_RENEGOTIATIONS) {
            AssistSecurityAudit.record(this, "MEDIA_RENEGOTIATE_EXHAUSTED", "reason=$reason session=$sessionId")
            stopMedia("renegotiate_exhausted")
            return
        }
        val pc = peerConnection ?: return
        renegotiationCount += 1
        lastRenegotiateMs = now
        answerReceived = false
        AssistSecurityAudit.record(
            this, "MEDIA_RENEGOTIATE",
            "reason=$reason attempt=$renegotiationCount session=$sessionId"
        )
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription?) {
                val offer = description ?: return
                val current = peerConnection ?: return
                current.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (stopping) return
                        pendingOffer = offer
                        publishOffer(offer)
                    }

                    override fun onSetFailure(error: String?) {
                        AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_LOCAL_SDP_FAILED", error.orEmpty())
                        stopMedia("local_sdp_failed")
                    }
                }, offer)
            }

            override fun onCreateFailure(error: String?) {
                AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_OFFER_FAILED", error.orEmpty())
                stopMedia("offer_failed")
            }
        }, constraints)
    }

    private fun applyRemoteCandidate(payload: Map<String, Any?>) {
        val candidate = payload["candidate"] as? String
        if (candidate.isNullOrBlank()) return
        val mid = payload["sdpMid"] as? String
        val index = (payload["sdpMLineIndex"] as? Number)?.toInt() ?: 0
        peerConnection?.addIceCandidate(IceCandidate(mid, index, candidate))
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            scope.launch {
                val payload: JsonObject = buildJsonObject {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid ?: "")
                    put("sdpMLineIndex", c.sdpMLineIndex)
                }
                AssistSignalClient.sendMediaSignal("ICE_CANDIDATE", payload)
            }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_ICE_STATE", newState?.name ?: "NULL")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    // ---- 发送策略 ----

    /** 周期性输出出站视频统计（仅计数，不含画面内容），用于真机验证“确实在发帧”。 */
    private fun maybeLogOutboundStats() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsLogMs < STATS_LOG_MS) return
        lastStatsLogMs = now
        val pc = peerConnection ?: return
        runCatching {
            pc.getStats { report ->
                for (stat in report.statsMap.values) {
                    if (stat.type != "outbound-rtp") continue
                    val members = stat.members
                    if (members["kind"]?.toString() != "video") continue
                    Log.d(
                        TAG,
                        "outbound video bytes=${members["bytesSent"]} packets=${members["packetsSent"]} " +
                            "frames=${members["framesEncoded"]} keyFrames=${members["keyFramesEncoded"]}"
                    )
                }
            }
        }
    }

    private fun startPolicyLoop() {
        policyJob?.cancel()
        policyJob = scope.launch {
            // 初始化看门狗：WebRTC 原生库在个别设备上可能卡住；超过 20s 未就绪就安全停止，
            // 绝不能停留在“通知显示正在共享、实际没有任何媒体”的状态。
            var waited = 0L
            while (!setupDone && waited < SETUP_TIMEOUT_MS) {
                if (stopping) return@launch
                delay(500)
                waited += 500
            }
            if (!setupDone) {
                AssistSecurityAudit.record(this@AssistMediaService, "MEDIA_SETUP_TIMEOUT", "session=$sessionId")
                stopMedia("setup_timeout")
                return@launch
            }
            while (isActive) {
                if (stopping) return@launch
                maybeLogOutboundStats()
                val snap = GuidanceRuntime.manager(this@AssistMediaService).snapshot()
                val keyguard = (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
                val action = AssistMediaPolicy.decide(
                    assistState = AssistSignalClient.current()?.state,
                    guideState = snap.state,
                    pageSensitive = snap.pageSensitive,
                    keyguardLocked = keyguard
                )
                when (action) {
                    AssistMediaPolicy.Action.STOP -> {
                        stopMedia("policy_stop")
                        return@launch
                    }
                    AssistMediaPolicy.Action.PAUSE -> pauseSending("policy_pause")
                    AssistMediaPolicy.Action.SEND -> {
                        resumeSending()
                        maybeResendOffer()
                    }
                }
                delay(POLICY_TICK_MS)
            }
        }
    }

    private fun resumeSending() {
        if (sending || stopping || !setupDone) return
        val track = videoTrack ?: return
        val c = capturer ?: return
        val dm = resources.displayMetrics
        val width = CAPTURE_WIDTH
        val height = ((width.toLong() * dm.heightPixels / dm.widthPixels.coerceAtLeast(1)).toInt()).coerceAtLeast(480) and 1.inv()
        track.setEnabled(true)
        val started = runCatching { c.startCapture(width, height, CAPTURE_FPS) }.isSuccess
        if (started) {
            sending = true
            AssistSecurityAudit.record(this, "MEDIA_SEND_RESUME", "session=$sessionId ${width}x$height")
        } else {
            AssistSecurityAudit.record(this, "MEDIA_SEND_FAILED", "startCapture")
        }
    }

    private fun pauseSending(reason: String) {
        if (!sending) return
        sending = false
        runCatching { capturer?.stopCapture() }
        runCatching { videoTrack?.setEnabled(false) }
        AssistSecurityAudit.record(this, "MEDIA_SEND_PAUSE", "reason=$reason session=$sessionId")
    }

    /** Web 端每 2 秒拉取一次事件；未收到 ANSWER 时每 6 秒重发同一 OFFER（最多 30 次）。 */
    private fun maybeResendOffer() {
        if (answerReceived || stopping) return
        val offer = pendingOffer ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOfferResendMs < OFFER_RESEND_MS) return
        if (offerResendCount >= MAX_OFFER_RESEND) {
            AssistSecurityAudit.record(this, "MEDIA_OFFER_TIMEOUT", "session=$sessionId")
            stopMedia("offer_timeout")
            return
        }
        publishOffer(offer)
    }

    /**
     * 黑帧探测：屏幕被 FLAG_SECURE 涂黑时立即停发，并让引导会话进入敏感暂停。
     * 纯黑壁纸可能误报，但误报只会停发/暂停（安全侧），用户可手动恢复。
     */
    private fun probeSecureFrame(frame: VideoFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSecureProbeMs < SECURE_PROBE_INTERVAL_MS) return
        val i420 = frame.buffer.toI420() ?: return
        try {
            val width = i420.width
            val height = i420.height
            val stride = i420.strideY
            val y = i420.dataY
            var nonZero = 0
            var checked = 0
            var row = 0
            while (row < height) {
                var col = 0
                while (col < width) {
                    val index = row * stride + col
                    if (index < y.limit()) {
                        checked++
                        if ((y.get(index).toInt() and 0xFF) > DARK_LUMA_THRESHOLD) nonZero++
                    }
                    col += 17
                }
                row += 17
            }
            if (checked > 50 && nonZero.toDouble() / checked < SECURE_DARK_RATIO) {
                lastSecureProbeMs = now
                scope.launch {
                    pauseSending("secure_frame")
                    GuidanceRuntime.manager(this@AssistMediaService).onCaptureSecure()
                    GuidanceRuntime.render(this@AssistMediaService)
                }
            }
        } finally {
            runCatching { i420.release() }
        }
    }

    // ---- 生命周期 ----

    private fun stopMedia(reason: String) {
        synchronized(stopLock) {
            if (stopping) return
            stopping = true
        }
        isRunning = false
        policyJob?.cancel()
        policyJob = null
        if (AssistSignalClient.mediaEventHandler === mediaHandler) {
            AssistSignalClient.mediaEventHandler = null
        }
        teardown()
        AssistSecurityAudit.record(this, "MEDIA_STOP", "reason=$reason session=$sessionId")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        sending = false
        setupDone = false
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        videoTrack?.let { track -> secureSink?.let { sink -> runCatching { track.removeSink(sink) } } }
        secureSink = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { eglBase?.release() }
        eglBase = null
        pendingOffer = null
    }

    override fun onDestroy() {
        synchronized(stopLock) { stopping = true }
        isRunning = false
        policyJob?.cancel()
        if (AssistSignalClient.mediaEventHandler === mediaHandler) {
            AssistSignalClient.mediaEventHandler = null
        }
        teardown()
        runCatching { peerThread?.quitSafely() }
        peerThread = null
        peerHandler = null
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.assist_media_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "屏幕共享进行中常驻通知，可随时停止"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AssistMediaService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.assist_media_notif_title))
            .setContentText(getString(R.string.assist_media_notif_text))
            .setSmallIcon(R.drawable.ic_assist)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_assist, getString(R.string.assist_media_notif_stop), stopPi)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun parcelableExtra(intent: Intent?, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(key, Intent::class.java)
        } else {
            intent?.getParcelableExtra(key)
        }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        const val ACTION_STOP = "com.actionloop.android.assist.STOP_MEDIA"
        private const val EXTRA_SESSION_ID = "assist_session_id"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "actionloop_assist_media"
        private const val NOTIF_ID = 4322
        private const val TAG = "AssistMediaService"

        private const val POLICY_TICK_MS = 700L
        private const val SETUP_TIMEOUT_MS = 20_000L
        private const val OFFER_RESEND_MS = 6_000L
        private const val MAX_OFFER_RESEND = 30
        /** 对端重建（刷新/换设备）后最多重新协商次数，防止异常对端造成无限协商。 */
        private const val MAX_RENEGOTIATIONS = 5
        /** 重新协商冷却：多标签页同时作答时避免会话在多个对端间抖动。 */
        private const val RENEGOTIATE_COOLDOWN_MS = 15_000L
        private const val STATS_LOG_MS = 5_000L
        private const val SECURE_PROBE_INTERVAL_MS = 1_000L
        private const val CAPTURE_WIDTH = 720
        private const val CAPTURE_FPS = 15
        private const val DARK_LUMA_THRESHOLD = 20
        private const val SECURE_DARK_RATIO = 0.02

        private val initialized = AtomicBoolean(false)

        /** UI 可据此判断屏幕共享是否进行中（服务是唯一写入方）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 屏幕共享只在本机显式授权后启动；sessionId 必须与当前协助会话一致。 */
        fun start(context: Context, sessionId: String, resultCode: Int, data: Intent) {
            val intent = Intent(context, AssistMediaService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AssistMediaService::class.java).setAction(ACTION_STOP)
            // 服务未运行时无需按前台服务启动：startForegroundService 后若没来得及 startForeground，
            // Android 14+ 会直接以 FGS 超时异常杀死进程（真机已复现）。
            // UI/通知的停止动作使用普通 startService；不可用时退化为 stopService。
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(Intent(context, AssistMediaService::class.java)) }
        }

        /** WebRTC 原生库只初始化一次；重复调用是安全的，但避免无谓开销。 */
        private fun initializeFactoryOnce(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            runCatching {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }.onFailure {
                initialized.set(false)
                Log.e(TAG, "WebRTC 初始化失败", it)
            }
        }
    }
}

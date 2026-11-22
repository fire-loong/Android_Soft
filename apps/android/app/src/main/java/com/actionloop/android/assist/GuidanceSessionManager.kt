package com.actionloop.android.assist

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 引导会话协调器（指导书 8.4 / 8.5 / 8.6 / 8.7 / 8.10）。
 *
 * 职责：
 * - 维护 [GuidanceSessionState] 状态机，迁移显式且可审计；
 * - 每帧经 [SensitivePageDetector] 判定：敏感页立即清除高亮并暂停；
 * - 入站信令经 [AssistMessageGuard] 本机校验：禁止类（CLICK/SWIPE/INPUT/COMMIT_PAYMENT…）拒绝并审计，
 *   绝不执行；HIGHLIGHT 仅在会话活跃、边界归一化合法且未过期时渲染；
 * - 匹配未达标时返回 NO_SAFE_TARGET → TEXT_ONLY，绝不选择次优按钮凑答案（指导书 1119）。
 *
 * 本类不直接操作窗口/采集（由 Chunk 2 的 Overlay/MediaProjection 适配层驱动），
 * 只暴露状态与高亮快照，确保安全策略集中、可单测。
 */
class GuidanceSessionManager(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var state: GuidanceSessionState = GuidanceSessionState.IDLE
    private var currentFrame: GuidanceFrame? = null
    private var highlight: ActiveHighlight? = null
    private var pointer: NormalizedBounds? = null
    private var textHint: String? = null

    /**
     * 采集侧（MediaProjection）报告的 FLAG_SECURE 信号到期时间戳；与帧敏感判定取 OR。volatile 无锁写。
     * 使用单调时钟 [android.os.SystemClock.elapsedRealtime]，NTP 校时/手动改时间不会让安全窗口失效。
     */
    @Volatile private var captureSecureUntilMs = 0L

    data class ActiveHighlight(
        val bounds: NormalizedBounds,
        val frameId: String?,
        val expiresAtMs: Long,
        val label: String?
    )

    data class Snapshot(
        val state: GuidanceSessionState,
        val highlight: ActiveHighlight?,
        val pointer: NormalizedBounds?,
        val textHint: String?,
        val pageSensitive: Boolean,
        val sensitiveReason: String?
    )

    suspend fun snapshot(): Snapshot = mutex.withLock {
        val frameSens = currentFrame?.let { SensitivePageDetector.evaluate(it) }
        val captureSens = captureSecureUntilMs > monoNow()
        val sensitive = frameSens?.sensitive == true || captureSens
        val reason = when {
            frameSens?.sensitive == true -> frameSens.reason
            captureSens -> "FLAG_SECURE frame"
            else -> frameSens?.reason
        }
        // 8.7：过期标注必须丢弃，不能继续停留在已经移动/失效的控件上误导用户。
        highlight?.let { active ->
            if (active.expiresAtMs <= System.currentTimeMillis()) {
                highlight = null
                AssistSecurityAudit.record(context, "HIGHLIGHT_EXPIRED", "frameId=${active.frameId ?: "-"}")
            }
        }
        Snapshot(state, highlight, pointer, textHint, sensitive, reason)
    }

    /** 无障碍采集门控（无锁读 volatile 状态，仅用于“是否采集”粗粒度判断）。 */
    fun isCollecting(): Boolean = state in setOf(
        GuidanceSessionState.CONSENT_PENDING, GuidanceSessionState.ACTIVE,
        GuidanceSessionState.ALIGNING, GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER
    )

    /**
     * 采集侧（MediaProjection）报告当前帧命中 FLAG_SECURE：置一个短期窗口，
     * 与 [onFrame] / [snapshot] 的敏感判定取 OR。无锁 volatile 写，可在采集回调线程直接调用。
     * 误报（纯黑壁纸）仅导致清除高亮并在窗口过期后自动恢复，不产生安全越权。
     */
    fun markCaptureSecure() {
        captureSecureUntilMs = monoNow() + CAPTURE_SECURE_WINDOW_MS
    }

    /**
     * 唯一状态写入点：非法迁移不执行、也不抛异常中断会话（避免把安全停止路径搞崩），
     * 而是原样保留当前状态并写入安全审计，供测试与线上排查发现状态机漂移。
     */
    private fun setStateLocked(target: GuidanceSessionState) {
        if (state == target) return
        if (!state.canTransitionTo(target)) {
            AssistSecurityAudit.record(context, "ILLEGAL_TRANSITION", "$state->$target")
            return
        }
        state = target
    }

    /** 显式重置：清空会话数据并回到 IDLE（只允许从这里离开终态）。 */
    private fun resetLocked() {
        state = GuidanceSessionState.IDLE
        highlight = null; pointer = null; textHint = null; currentFrame = null
    }

    /** 发起引导：IDLE → CONSENT_PENDING；若为终态，先重置再发起（终态不可直接迁出）。 */
    suspend fun start() = mutex.withLock {
        if (state.isTerminal()) resetLocked()
        setStateLocked(GuidanceSessionState.CONSENT_PENDING)
    }

    /** 用户完成权限与授权：CONSENT_PENDING → ACTIVE。 */
    suspend fun consentGranted() = mutex.withLock {
        setStateLocked(GuidanceSessionState.ACTIVE)
    }

    /** 用户取消授权：CONSENT_PENDING → IDLE。 */
    suspend fun consentDenied() = mutex.withLock {
        if (state == GuidanceSessionState.CONSENT_PENDING) setStateLocked(GuidanceSessionState.IDLE)
    }

    /** 输入一帧：敏感页清除高亮并暂停；否则进入对齐并尝试匹配。 */
    suspend fun onFrame(frame: GuidanceFrame) = mutex.withLock {
        currentFrame = frame
        val frameSens = SensitivePageDetector.evaluate(frame)
        val captureSens = captureSecureUntilMs > monoNow()
        val sensitive = frameSens.sensitive || captureSens
        if (sensitive) {
            highlight = null
            pointer = null
            if (state in setOf(
                    GuidanceSessionState.ACTIVE, GuidanceSessionState.ALIGNING,
                    GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER
                )
            ) {
                setStateLocked(GuidanceSessionState.PAUSED)
            }
            val reason = if (frameSens.sensitive) frameSens.reason else "FLAG_SECURE frame"
            AssistSecurityAudit.record(context, "SENSITIVE_PAGE", "${frame.packageName}: $reason")
            return@withLock
        }
        if (state == GuidanceSessionState.ACTIVE) setStateLocked(GuidanceSessionState.ALIGNING)
        // 文字降级后仍按帧连续匹配（8.4）：页面变化后允许重新对齐，避免一次低置信锁死整个会话。
        if (state == GuidanceSessionState.TEXT_ONLY) setStateLocked(GuidanceSessionState.ALIGNING)
        if (state in setOf(GuidanceSessionState.ALIGNING, GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER)) {
            when (evaluateTarget(frame)) {
                MatchResult.NO_SAFE_TARGET -> {
                    highlight = null
                    setStateLocked(GuidanceSessionState.TEXT_ONLY)
                }
                MatchResult.MATCHED -> { /* 高亮由本机匹配器或远程标注写入 */ }
            }
        }
    }

    /**
     * 处理入站信令（来自远程协助者或本地匹配器）。
     * @return true 表示已接受，false 表示被拒绝（禁止类/未知类/状态不允许）。
     */
    suspend fun applyIncomingEvent(
        sessionId: String?,
        eventType: String?,
        payload: Map<String, Any?>
    ): Boolean = mutex.withLock {
        when (val r = AssistMessageGuard.validate(eventType)) {
            is AssistMessageGuard.Result.Forbidden -> {
                AssistSecurityAudit.record(
                    context, "REJECTED_CONTROL",
                    "session=${sessionId ?: "-"} event=${r.eventType} norm=${r.normalized} reason=${r.reason}"
                )
                return@withLock false
            }
            is AssistMessageGuard.Result.Disallowed -> {
                AssistSecurityAudit.record(
                    context, "REJECTED_UNKNOWN",
                    "session=${sessionId ?: "-"} event=${r.eventType} reason=${r.reason}"
                )
                return@withLock false
            }
            is AssistMessageGuard.Result.Allowed -> dispatchAllowed(r.eventType, sessionId, payload)
        }
    }

    private fun dispatchAllowed(event: String, sessionId: String?, payload: Map<String, Any?>): Boolean {
        return when (event) {
            "HIGHLIGHT" -> applyHighlight(payload)
            "HIGHLIGHT_CLEAR" -> { highlight = null; true }
            "POINTER" -> { pointer = parseBounds(payload["bounds"]); pointer != null }
            "TEXT_HINT" -> { textHint = payload["text"]?.toString()?.take(500); true }
            "PAUSE" -> {
                highlight = null; pointer = null
                if (state in setOf(GuidanceSessionState.ACTIVE, GuidanceSessionState.ALIGNING,
                        GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER)) {
                    setStateLocked(GuidanceSessionState.PAUSED)
                }
                AssistSecurityAudit.record(context, "PAUSE", "session=${sessionId ?: "-"}")
                true
            }
            "RESUME", "RESUME_REQUEST" -> {
                // 远程恢复不能直接激活：必须回到 CONSENT_PENDING 由用户本地再次同意（8.3）。
                if (state == GuidanceSessionState.PAUSED) setStateLocked(GuidanceSessionState.CONSENT_PENDING)
                true
            }
            "STOP" -> {
                stopLocked("remote")
                AssistSecurityAudit.record(context, "STOP", "session=${sessionId ?: "-"} remote=true")
                true
            }
            "META", "HEARTBEAT", "QUALITY" -> true // 不改变会话状态
            // WebRTC 信令由 WebRTC 适配层处理，此处仅确认其在白名单内
            "OFFER", "ANSWER", "ICE_CANDIDATE" -> true
            else -> false
        }
    }

    private fun applyHighlight(payload: Map<String, Any?>): Boolean {
        if (state.isTerminal() || state in setOf(GuidanceSessionState.PAUSED, GuidanceSessionState.TEXT_ONLY,
                GuidanceSessionState.IDLE, GuidanceSessionState.CONSENT_PENDING)) {
            return false
        }
        // 纵深防御：当前帧敏感（文本/采集侧 FLAG_SECURE）时拒绝任何高亮，即使远程标注先于敏感帧到达也不得落在敏感页（8.5）。
        currentFrame?.let { if (SensitivePageDetector.evaluate(it).sensitive) return false }
        if (captureSecureUntilMs > monoNow()) return false
        val bounds = parseBounds(payload["bounds"]) ?: return false
        if (!bounds.isValid()) return false
        val expiresAt = parseEpochMs(payload["expiresAt"])
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) return false
        highlight = ActiveHighlight(
            bounds = bounds,
            frameId = payload["frameId"]?.toString(),
            expiresAtMs = expiresAt ?: Long.MAX_VALUE,
            label = payload["label"]?.toString()?.take(120)
        )
        if (state == GuidanceSessionState.ALIGNING || state == GuidanceSessionState.ACTIVE) {
            setStateLocked(GuidanceSessionState.HIGHLIGHTING)
        }
        return true
    }

    /** 用户或敏感页触发的暂停：清除高亮，仅活跃子态可暂停。 */
    suspend fun pause(reason: String) = mutex.withLock {
        if (state in setOf(GuidanceSessionState.ACTIVE, GuidanceSessionState.ALIGNING,
                GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER)) {
            setStateLocked(GuidanceSessionState.PAUSED)
            highlight = null; pointer = null
            AssistSecurityAudit.record(context, "PAUSE", "reason=$reason")
        }
    }

    /** 用户主动恢复：PAUSED → CONSENT_PENDING → ACTIVE（用户点击即视为再次同意，8.3）。 */
    suspend fun userResume() = mutex.withLock {
        setStateLocked(GuidanceSessionState.CONSENT_PENDING)
        setStateLocked(GuidanceSessionState.ACTIVE)
    }

    suspend fun stop(reason: String) = mutex.withLock { stopLocked(reason) }

    private fun stopLocked(reason: String) {
        highlight = null
        pointer = null
        textHint = null
        if (!state.isTerminal()) setStateLocked(GuidanceSessionState.STOPPED)
        AssistSecurityAudit.record(context, "STOP", "reason=$reason")
    }

    suspend fun userConfirmedStepComplete() = mutex.withLock {
        if (state in setOf(GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER)) {
            setStateLocked(GuidanceSessionState.COMPLETED)
            highlight = null
        }
    }

    suspend fun reset() = mutex.withLock { resetLocked() }

    private enum class MatchResult { NO_SAFE_TARGET, MATCHED }

    /**
     * 目标匹配。Chunk 1 无匹配器接入，一律返回 NO_SAFE_TARGET → 降级 TEXT_ONLY，
     * 绝不选择次优按钮凑答案（指导书 1119）。真实匹配在受控 App 接入后实现。
     */
    private fun evaluateTarget(@Suppress("UNUSED_PARAMETER") frame: GuidanceFrame): MatchResult =
        MatchResult.NO_SAFE_TARGET

    private fun parseBounds(raw: Any?): NormalizedBounds? {
        val m = raw as? Map<*, *> ?: return null
        val x = (m["x"] as? Number)?.toDouble() ?: return null
        val y = (m["y"] as? Number)?.toDouble() ?: return null
        val w = (m["width"] as? Number)?.toDouble() ?: return null
        val h = (m["height"] as? Number)?.toDouble() ?: return null
        return NormalizedBounds(x, y, w, h)
    }

    private fun parseEpochMs(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }

    /**
     * 本地时限统一走单调时钟：墙钟可被 NTP 校时/用户改时间前后跳变，
     * 而 FLAG_SECURE 敏感窗口属于安全判定，绝不能因时钟回拨而提前失效。
     */
    private fun monoNow(): Long = SystemClock.elapsedRealtime()

    companion object {
        /** 采集侧报告 FLAG_SECURE 后的敏感窗口：采集节流约 400ms，留足余量避免单帧漏判。 */
        private const val CAPTURE_SECURE_WINDOW_MS = 1500L
    }
}

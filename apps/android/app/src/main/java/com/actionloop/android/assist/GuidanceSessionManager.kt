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
    private var pointer: ActivePointer? = null
    private var textHint: ActiveTextHint? = null

    /** 当前引导步骤的目标；为空时一律降级文字，不做任何猜测匹配。 */
    private var target: GuidanceTarget? = null
    /** 最近一次本机匹配结果，用于 8.4 的“历史页面”维度；页面变化后自动失效。 */
    private var lastMatch: TargetMatcher.History? = null

    /**
     * 采集侧（MediaProjection）报告的 FLAG_SECURE 信号到期时间戳；与帧敏感判定取 OR。volatile 无锁写。
     * 使用单调时钟 [android.os.SystemClock.elapsedRealtime]，NTP 校时/手动改时间不会让安全窗口失效。
     */
    @Volatile private var captureSecureUntilMs = 0L

    /** 桌面包名：桌面上的“支付宝/手机银行”只是应用名，不能按敏感关键词处理（真机误暂停）。 */
    private val launcherPackages: Set<String> by lazy {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    data class ActiveHighlight(
        /** 远程/本机标注必须绑定到产生它的 frameId（指导书 8.7）。 */
        val bounds: NormalizedBounds,
        val frameId: String,
        /** 单调时钟截止时间：改系统时间不能延长标注寿命。 */
        val expiresAtMonoMs: Long,
        val label: String?
    )

    data class ActivePointer(
        val bounds: NormalizedBounds,
        val frameId: String,
        val expiresAtMonoMs: Long
    )

    /** 远程文字提示同样绑定当前帧并限时，避免跨页面长期停留（8.7）。 */
    data class ActiveTextHint(
        val text: String,
        val frameId: String,
        val expiresAtMonoMs: Long
    )

    data class Snapshot(
        val state: GuidanceSessionState,
        /** 当前帧标识：远程协助端据此生成标注；无帧时为 null。 */
        val frameId: String?,
        val highlight: ActiveHighlight?,
        val pointer: NormalizedBounds?,
        val textHint: String?,
        val pageSensitive: Boolean,
        val sensitiveReason: String?
    )

    suspend fun snapshot(): Snapshot = mutex.withLock {
        val frameSens = currentFrame?.let { SensitivePageDetector.evaluate(it, context.packageName, launcherPackages) }
        val captureSens = captureSecureUntilMs > monoNow()
        val sensitive = frameSens?.sensitive == true || captureSens
        val reason = when {
            frameSens?.sensitive == true -> frameSens.reason
            captureSens -> "FLAG_SECURE frame"
            else -> frameSens?.reason
        }
        // 8.7：过期标注必须丢弃，不能继续停留在已经移动/失效的控件上误导用户。
        highlight?.let { active ->
            if (active.expiresAtMonoMs <= monoNow()) {
                highlight = null
                AssistSecurityAudit.record(context, "HIGHLIGHT_EXPIRED", "frameId=${active.frameId}")
            }
        }
        pointer?.let { active ->
            if (active.expiresAtMonoMs <= monoNow()) {
                pointer = null
                AssistSecurityAudit.record(context, "POINTER_EXPIRED", "frameId=${active.frameId}")
            }
        }
        textHint?.let { active ->
            if (active.expiresAtMonoMs <= monoNow()) {
                textHint = null
                AssistSecurityAudit.record(context, "TEXT_HINT_EXPIRED", "frameId=${active.frameId}")
            }
        }
        // 纵深防御：即使某个异步路径漏清，敏感页也绝不返回任何标注/文字提示。
        if (sensitive) {
            highlight = null; pointer = null; textHint = null
        }
        // 纯远程协助：远程标注全部到期后回到文字降级，避免控制条长期显示“高亮中”却没有任何标注。
        if (target == null && state == GuidanceSessionState.HIGHLIGHTING
            && highlight == null && pointer == null && textHint == null
        ) {
            setStateLocked(GuidanceSessionState.TEXT_ONLY)
        }
        Snapshot(
            state = state,
            frameId = currentFrame?.frameId,
            highlight = highlight,
            pointer = pointer?.bounds,
            textHint = textHint?.text,
            pageSensitive = sensitive,
            sensitiveReason = reason
        )
    }

    /** 无障碍采集门控（无锁读 volatile 状态，仅用于“是否采集”粗粒度判断）。 */
    fun isCollecting(): Boolean = state in setOf(
        GuidanceSessionState.CONSENT_PENDING, GuidanceSessionState.ACTIVE,
        GuidanceSessionState.ALIGNING, GuidanceSessionState.HIGHLIGHTING,
        GuidanceSessionState.WAITING_USER,
        // 文字降级后仍需继续读页面，否则无法在页面变化后重新对齐（8.4）。
        GuidanceSessionState.TEXT_ONLY
    )

    /**
     * 采集侧（MediaProjection）报告当前帧命中 FLAG_SECURE（8.5：清除高亮、暂停媒体）。
     * 必须在协程中调用：除了置敏感窗口外，还要在同一把锁内清除全部标注并把活跃会话置为 PAUSED，
     * 避免“敏感页已出现但旧高亮继续停留最长 5 分钟”的窗口。误报只会让用户手动恢复。
     */
    suspend fun onCaptureSecure() = mutex.withLock {
        captureSecureUntilMs = monoNow() + CAPTURE_SECURE_WINDOW_MS
        clearAnnotationsLocked()
        if (state in ACTIVE_STATES) setStateLocked(GuidanceSessionState.PAUSED)
        AssistSecurityAudit.record(context, "CAPTURE_SECURE", "FLAG_SECURE frame")
    }

    private fun clearAnnotationsLocked() {
        highlight = null; pointer = null; textHint = null
    }

    /**
     * 显示尺寸/旋转变化（8.4/8.5）：归一化坐标按旧 Insets 换算会画错位置，
     * 因此立即清除标注、作废当前帧并暂停，等待用户重新发起（每帧采集已重新授权）。
     * 由采集渲染循环在检测到 width/height/rotation 变化时调用。
     */
    suspend fun onDisplayChanged() = mutex.withLock {
        if (currentFrame == null) return@withLock
        clearAnnotationsLocked()
        currentFrame = null
        lastMatch = null
        if (state in ACTIVE_STATES) setStateLocked(GuidanceSessionState.PAUSED)
        AssistSecurityAudit.record(context, "DISPLAY_CHANGED", "rotation/resolution changed")
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
        // 新会话不得继承上一轮的目标与匹配历史；目标由调用方在 start() 之后显式设置。
        target = null; lastMatch = null
    }

    /** 发起引导：任何状态都先重置为 IDLE，再进入 CONSENT_PENDING（新会话不得继承上一轮授权）。 */
    suspend fun start() = mutex.withLock {
        if (state != GuidanceSessionState.CONSENT_PENDING) resetLocked()
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

    /** 输入一帧：敏感页清除高亮并暂停；否则先让旧帧标注失效，再进入对齐并尝试匹配。 */
    suspend fun onFrame(frame: GuidanceFrame) = mutex.withLock {
        // 没有 frameId 的帧无法安全绑定标注：丢弃并审计，不与上一帧混用。
        if (frame.frameId.isBlank()) {
            highlight = null
            pointer = null
            // 当前帧本身也失效：否则后续标注仍可能绑定到上一帧的旧坐标。
            currentFrame = null
            AssistSecurityAudit.record(context, "FRAME_INVALID", "blank frameId")
            return@withLock
        }
        currentFrame = frame
        // 8.4 / 8.7：换帧（页面变化、旋转、键盘、窗口 Insets 变化）后，绑定旧帧的标注立即失效。
        highlight?.let { active ->
            if (active.frameId != frame.frameId) {
                highlight = null
                AssistSecurityAudit.record(context, "HIGHLIGHT_STALE", "bound=${active.frameId} current=${frame.frameId}")
            }
        }
        pointer?.let { active ->
            if (active.frameId != frame.frameId) {
                pointer = null
                AssistSecurityAudit.record(context, "POINTER_STALE", "bound=${active.frameId} current=${frame.frameId}")
            }
        }
        textHint?.let { active ->
            if (active.frameId != frame.frameId) {
                textHint = null
                AssistSecurityAudit.record(context, "TEXT_HINT_STALE", "bound=${active.frameId} current=${frame.frameId}")
            }
        }
        val frameSens = SensitivePageDetector.evaluate(frame, context.packageName, launcherPackages)
        val captureSens = captureSecureUntilMs > monoNow()
        val sensitive = frameSens.sensitive || captureSens
        if (sensitive) {
            clearAnnotationsLocked()
            if (state in ACTIVE_STATES) {
                setStateLocked(GuidanceSessionState.PAUSED)
            }
            val reason = if (frameSens.sensitive) frameSens.reason else "FLAG_SECURE frame"
            AssistSecurityAudit.record(context, "SENSITIVE_PAGE", "${frame.packageName}: $reason")
            return@withLock
        }
        // 本机目标匹配只在存在本地目标时运行。
        // 纯远程协助（target == null）没有本地目标可匹配，若照常执行会每帧把远程 HIGHLIGHT
        // 清空并强制降级 TEXT_ONLY，导致协助者的标注刚落下就被删掉。
        if (target != null) {
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
        } else if (highlight == null && pointer == null && textHint == null
            && (state == GuidanceSessionState.ACTIVE || state == GuidanceSessionState.ALIGNING
                || state == GuidanceSessionState.WAITING_USER)
        ) {
            // 无本地目标且无任何标注：按低置信降级为文字步骤，但绝不触碰已存在的远程标注。
            if (state == GuidanceSessionState.ACTIVE) setStateLocked(GuidanceSessionState.ALIGNING)
            if (state == GuidanceSessionState.WAITING_USER) setStateLocked(GuidanceSessionState.ALIGNING)
            setStateLocked(GuidanceSessionState.TEXT_ONLY)
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
            "POINTER" -> applyPointer(payload)
            "TEXT_HINT" -> applyTextHint(payload)
            "PAUSE" -> {
                clearAnnotationsLocked()
                if (state in ACTIVE_STATES) {
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
        if (!acceptsAnnotation()) return false
        // 纵深防御：当前帧敏感（文本/采集侧 FLAG_SECURE）时拒绝任何高亮，即使远程标注先于敏感帧到达也不得落在敏感页（8.5）。
        if (currentFrameHasSensitiveContent()) return false
        if (captureSecureUntilMs > monoNow()) return false
        val frameId = currentFrameIdForAnnotation(payload) ?: return false
        val bounds = parseBounds(payload["bounds"]) ?: return false
        if (!bounds.isValid()) return false
        // 13.5：HIGHLIGHT 必须携带 expiresAt；缺失即拒，避免远端标注永久驻留。
        val expiresAt = parseEpochMs(payload["expiresAt"]) ?: return false
        val nowWall = System.currentTimeMillis()
        if (expiresAt <= nowWall) return false
        highlight = ActiveHighlight(
            bounds = bounds,
            frameId = frameId,
            // 上限封顶 + 单调时钟：远端传很远的未来时间或用户改系统时间都不能延长标注寿命。
            expiresAtMonoMs = monoNow() + minOf(expiresAt - nowWall, MAX_HIGHLIGHT_TTL_MS),
            label = payload["label"]?.toString()?.take(120)
        )
        if (state == GuidanceSessionState.ALIGNING || state == GuidanceSessionState.ACTIVE) {
            setStateLocked(GuidanceSessionState.HIGHLIGHTING)
        } else if (state == GuidanceSessionState.TEXT_ONLY) {
            // 8.4：低置信时可“显示文字步骤或请求协助”，远程协助者的合法标注正是该出口。
            // 仍要求绑定当前帧且未过期，因此不会把标注落到已移动的控件上。
            setStateLocked(GuidanceSessionState.ALIGNING)
            setStateLocked(GuidanceSessionState.HIGHLIGHTING)
        }
        return true
    }

    /** 指针标注与高亮同权：必须绑定当前帧，且不允许长期驻留（8.7）。 */
    private fun applyPointer(payload: Map<String, Any?>): Boolean {
        if (!acceptsAnnotation()) return false
        if (currentFrameHasSensitiveContent()) return false
        if (captureSecureUntilMs > monoNow()) return false
        val frameId = currentFrameIdForAnnotation(payload) ?: return false
        val bounds = parseBounds(payload["bounds"]) ?: return false
        if (!bounds.isValid()) return false
        val expiresAt = parseEpochMs(payload["expiresAt"]) ?: return false
        val nowWall = System.currentTimeMillis()
        if (expiresAt <= nowWall) return false
        pointer = ActivePointer(
            bounds,
            frameId,
            monoNow() + minOf(expiresAt - nowWall, MAX_HIGHLIGHT_TTL_MS)
        )
        return true
    }

    /**
     * 远程文字提示：与高亮同权校验。
     * 必须携带当前 frameId；敏感页 / 采集侧 FLAG_SECURE 窗口 / PAUSED / 终态一律拒绝。
     * TEXT_ONLY 允许（正是低置信降级的出口）。默认 10 秒有效，最长不超过 30 秒。
     */
    private fun applyTextHint(payload: Map<String, Any?>): Boolean {
        if (!acceptsAnnotation()) return false
        if (currentFrameHasSensitiveContent()) return false
        if (captureSecureUntilMs > monoNow()) return false
        val frameId = currentFrameIdForAnnotation(payload) ?: return false
        val text = payload["text"]?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return false
        val nowWall = System.currentTimeMillis()
        val requested = parseEpochMs(payload["expiresAt"])?.takeIf { it > nowWall }?.minus(nowWall)
        val ttl = minOf(requested ?: TEXT_HINT_TTL_MS, MAX_TEXT_HINT_TTL_MS)
        textHint = ActiveTextHint(text.take(500), frameId, monoNow() + ttl)
        return true
    }

    /** 远程高亮、指针与文字共用同一敏感页判定，避免入口间策略漂移。 */
    private fun currentFrameHasSensitiveContent(): Boolean = currentFrame?.let {
        SensitivePageDetector.evaluate(it, context.packageName, launcherPackages).sensitive
    } == true

    /** 标注只允许落在活跃状态；暂停/待同意/终态一律拒绝（IDLE/STOPPED/COMPLETED）。 */
    private fun acceptsAnnotation(): Boolean =
        !state.isTerminal() && state !in setOf(
            GuidanceSessionState.PAUSED,
            GuidanceSessionState.IDLE, GuidanceSessionState.CONSENT_PENDING
        )

    /**
     * 当前帧标识与入站标注的 frameId 必须完全一致（8.7“仅接受与当前帧匹配的标注”）。
     * 缺帧、缺 frameId、帧不匹配都拒绝并写入安全审计，绝不把标注落到已移动的控件上。
     */
    private fun currentFrameIdForAnnotation(payload: Map<String, Any?>): String? {
        val frame = currentFrame
        if (frame == null) {
            AssistSecurityAudit.record(context, "ANNOTATION_NO_FRAME", "no current frame")
            return null
        }
        val incoming = payload["frameId"]?.toString()
        if (incoming.isNullOrBlank()) {
            AssistSecurityAudit.record(context, "ANNOTATION_NO_FRAME_ID", "current=${frame.frameId}")
            return null
        }
        if (incoming != frame.frameId) {
            AssistSecurityAudit.record(
                context, "ANNOTATION_STALE_FRAME",
                "incoming=$incoming current=${frame.frameId}"
            )
            return null
        }
        return frame.frameId
    }

    /** 用户或敏感页触发的暂停：清除全部标注，仅活跃态可暂停（含文字降级）。 */
    suspend fun pause(reason: String) = mutex.withLock {
        if (state in ACTIVE_STATES) {
            setStateLocked(GuidanceSessionState.PAUSED)
            clearAnnotationsLocked()
            AssistSecurityAudit.record(context, "PAUSE", "reason=$reason")
        }
    }

    /** 用户主动恢复：仅 PAUSED 可恢复，且必须经 CONSENT_PENDING（用户点击即视为再次同意，8.3）。 */
    suspend fun userResume() = mutex.withLock {
        if (state != GuidanceSessionState.PAUSED) return@withLock
        setStateLocked(GuidanceSessionState.CONSENT_PENDING)
        setStateLocked(GuidanceSessionState.ACTIVE)
    }

    suspend fun stop(reason: String) = mutex.withLock { stopLocked(reason) }

    private fun stopLocked(reason: String) {
        clearAnnotationsLocked()
        currentFrame = null
        lastMatch = null
        if (!state.isTerminal()) setStateLocked(GuidanceSessionState.STOPPED)
        AssistSecurityAudit.record(context, "STOP", "reason=$reason")
    }

    suspend fun userConfirmedStepComplete() = mutex.withLock {
        if (state in setOf(GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER)) {
            setStateLocked(GuidanceSessionState.COMPLETED)
            clearAnnotationsLocked()
        }
    }

    suspend fun reset() = mutex.withLock { resetLocked() }

    /**
     * 新的协助会话开始时调用：STOPPED/COMPLETED 是上一轮会话的终态，
     * 不得跨会话继承；否则媒体策略会把它当成“本地引导已停止”，新会话一启动就停止媒体。
     * 只清理终态，PAUSED 等安全状态保持不变（仍需用户本地恢复）。
     */
    suspend fun resetIfTerminal() = mutex.withLock {
        if (state.isTerminal()) resetLocked()
    }

    /**
     * 设置当前步骤的引导目标；必须在 [start] 之后调用（新会话会清空上一轮目标）。
     * 传 null 表示撤销目标，后续帧一律降级文字。
     */
    suspend fun setTarget(target: GuidanceTarget?) = mutex.withLock {
        this.target = target
        this.lastMatch = null
        if (target == null && state == GuidanceSessionState.ALIGNING) {
            highlight = null
            setStateLocked(GuidanceSessionState.TEXT_ONLY)
        }
    }

    /** 当前帧标识快照，供协助信令端发布 META（不含任何屏幕内容）。 */
    suspend fun currentFrameId(): String? = mutex.withLock { currentFrame?.frameId }

    private enum class MatchResult { NO_SAFE_TARGET, MATCHED }

    /**
     * 目标匹配（指导书 8.4）。
     *
     * - 未设置目标、非白名单包、锁屏/FLAG_SECURE → 一律 NO_SAFE_TARGET；
     * - 匹配器低置信或前两名歧义 → NO_SAFE_TARGET，降级 TEXT_ONLY，绝不猜相邻按钮；
     * - 命中后写入绑定当前 frameId 的高亮，TTL 有限，换帧即失效。
     */
    private fun evaluateTarget(frame: GuidanceFrame): MatchResult {
        val t = target ?: return MatchResult.NO_SAFE_TARGET
        if (!frame.allowlistedPackage || frame.packageName != t.packageName) return MatchResult.NO_SAFE_TARGET
        if (frame.isLocked || frame.isSecure) return MatchResult.NO_SAFE_TARGET
        val matched = TargetMatcher.match(frame, t, lastMatch)
        if (matched == null) {
            lastMatch = null
            return MatchResult.NO_SAFE_TARGET
        }
        lastMatch = TargetMatcher.History(frame.pageHash, TargetMatcher.normalizeText(matched.text))
        highlight = ActiveHighlight(
            bounds = matched.bounds,
            frameId = frame.frameId,
            expiresAtMonoMs = monoNow() + LOCAL_HIGHLIGHT_TTL_MS,
            label = t.labels.firstOrNull()?.take(120)
        )
        if (state == GuidanceSessionState.ALIGNING) {
            setStateLocked(GuidanceSessionState.HIGHLIGHTING)
            // 8.6：展示唯一步骤后进入等待用户操作；用户点击后页面变化会重新回到 ALIGNING。
            setStateLocked(GuidanceSessionState.WAITING_USER)
        }
        AssistSecurityAudit.record(
            context, "LOCAL_MATCH",
            "frame=${frame.frameId} score=${"%.2f".format(matched.score)}"
        )
        return MatchResult.MATCHED
    }

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
        /** 处于“引导进行中”的状态集合：可暂停、可被敏感页打断、需要清理标注。 */
        private val ACTIVE_STATES = setOf(
            GuidanceSessionState.ACTIVE, GuidanceSessionState.ALIGNING,
            GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER,
            GuidanceSessionState.TEXT_ONLY
        )

        /** 采集侧报告 FLAG_SECURE 后的敏感窗口：采集节流约 400ms，留足余量避免单帧漏判。 */
        private const val CAPTURE_SECURE_WINDOW_MS = 1500L

        /** 远程标注最大存活时间：即使对端给出很远的 expiresAt，也不允许标注长期停留在已变化的页面上。 */
        private const val MAX_HIGHLIGHT_TTL_MS = 5 * 60 * 1000L

        /** 远程文字提示默认/最大存活时间：文字比高亮更易误导，生命周期更短。 */
        private const val TEXT_HINT_TTL_MS = 10_000L
        private const val MAX_TEXT_HINT_TTL_MS = 30_000L

        /**
         * 本机匹配高亮有效期：无障碍事件不是定频心跳，页面静止时可能十几秒没有新帧，
         * 因此给定 10 秒；一旦换帧（页面变化/旋转/键盘）由 onFrame 立即清除，不依赖 TTL。
         */
        private const val LOCAL_HIGHLIGHT_TTL_MS = 10_000L
    }
}

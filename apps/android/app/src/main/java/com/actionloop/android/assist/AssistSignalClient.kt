package com.actionloop.android.assist

import android.content.Context
import android.os.SystemClock
import com.actionloop.android.data.ApiClient
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.AssistEventView
import com.actionloop.android.data.AssistSessionView
import com.actionloop.android.data.AssistTurnCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 协助信令传输端口。
 *
 * 只暴露后端调用，不包含任何状态机/时钟/UI 依赖，便于单测注入假实现验证
 * “会话终态即停、旧代际隔离、连续失败终止”等安全策略。
 */
interface AssistSignalTransport {
    suspend fun createSession(workspaceId: String, taskStepId: String?): AssistSessionView
    suspend fun events(sessionId: String, afterSeq: Long): List<AssistEventView>
    suspend fun mine(): List<AssistSessionView>
    suspend fun signal(sessionId: String, eventType: String, payload: JsonObject): AssistEventView
    suspend fun stop(sessionId: String, reason: String): AssistSessionView
    suspend fun turnCredentials(sessionId: String): AssistTurnCredentials
}

/** 生产实现：直接走 [ApiClient]（真实后端）。 */
object HttpAssistSignalTransport : AssistSignalTransport {
    override suspend fun createSession(workspaceId: String, taskStepId: String?): AssistSessionView =
        ApiClient.createAssistSession(workspaceId, taskStepId)

    override suspend fun events(sessionId: String, afterSeq: Long): List<AssistEventView> =
        ApiClient.assistEvents(sessionId, afterSeq)

    override suspend fun mine(): List<AssistSessionView> = ApiClient.assistMine()

    override suspend fun signal(sessionId: String, eventType: String, payload: JsonObject): AssistEventView =
        ApiClient.assistSignal(sessionId, eventType, payload)

    override suspend fun stop(sessionId: String, reason: String): AssistSessionView =
        ApiClient.assistStop(sessionId, reason)

    override suspend fun turnCredentials(sessionId: String): AssistTurnCredentials =
        ApiClient.assistTurnCredentials(sessionId)
}

private val assistJson = Json { ignoreUnknownKeys = true }

private const val POLL_INTERVAL_MS = 1_500L

/** 同一帧标识的发布节流：换帧立即发布，同帧 2 秒内不重复。 */
private const val META_THROTTLE_MS = 2_000L

/** 约 33 秒（1.5s 轮询 × 20）持续失败才终止，避免瞬时抖动误杀正常会话。 */
private const val MAX_CONSECUTIVE_POLL_FAILURES = 20

/**
 * 协助信令核心（指导书 8.7 / 13.5）。
 *
 * - 只在用户显式发起后创建会话；轮询服务端事件并交给 [GuidanceSessionManager.applyIncomingEvent]
 *   再校验一次（帧绑定/敏感页/白名单），服务端放行的信令不代表本机必须渲染；
 * - 只发布 META/HEARTBEAT（frameId、pageHash、包名），不发布任何截图、控件文本或用户输入；
 * - 收到的 HIGHLIGHT/POINTER/TEXT_HINT 只渲染到悬浮层，绝不执行点击/输入/支付；
 * - 服务端/协助者停止后，本地必须同步 STOP，驱动采集服务释放 MediaProjection 与悬浮层。
 *
 * 构造参数全部可注入：生产由 [AssistSignalClient] 单例持有默认实例（行为与重构前一致），
 * 单测注入假传输层、虚拟单调时钟与测试调度器来验证安全策略。
 */
class AssistSignalCore(
    private val transport: AssistSignalTransport = HttpAssistSignalTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 单调时钟（毫秒）。节流/敏感窗口都用它，避免墙钟回拨影响安全判定。 */
    private val monoClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** 引导状态机来源：生产为 [GuidanceRuntime]，单测可指向独占实例。 */
    private val managerOf: (Context) -> GuidanceSessionManager = { GuidanceRuntime.manager(it) },
    private val render: suspend (Context) -> Unit = { GuidanceRuntime.render(it) },
    /** 会话终止时释放媒体通道；单测注入空实现避免真的起停服务。 */
    private val stopMedia: (Context) -> Unit = { AssistMediaService.stop(it) },
) {

    /** start/stop 串行化：避免“停止后迟到的 start 又把会话建起来”。 */
    private val lifecycle = Mutex()

    /** 会话代际：旧轮询/旧网络响应不得写回新会话。 */
    @Volatile private var epoch: Int = 0

    @Volatile private var session: AssistSessionView? = null
    @Volatile private var lastSeq: Long = 0L
    @Volatile private var pollJob: Job? = null

    @Volatile private var lastPublishedFrameId: String? = null
    @Volatile private var lastPublishedMonoMs: Long = 0L

    @Volatile
    var turnCredentials: AssistTurnCredentials? = null
        private set

    /** 会话变化回调（UI 可据此刷新邀请码/状态）。 */
    @Volatile
    var onSessionChanged: ((AssistSessionView?) -> Unit)? = null

    /**
     * WebRTC 协商事件处理器（由 [AssistMediaService] 注册）。
     * 只有通过本机白名单校验的 OFFER/ANSWER/ICE_CANDIDATE 才会回调；标注事件仍走状态机。
     */
    @Volatile
    var mediaEventHandler: ((eventType: String, payload: Map<String, Any?>) -> Unit)? = null

    fun current(): AssistSessionView? = session

    /** 最近一次本机终止/失败的可解释出口；UI 读取后应提示用户。 */
    @Volatile
    var lastError: String? = null
        private set

    fun clearError() {
        lastError = null
    }

    fun isSessionActive(): Boolean = session?.state in RUNNING_STATES

    /** 发起求助：创建远程协助会话（无指定协助者 → IDLE 开放邀请），并开始轮询。 */
    suspend fun start(context: Context, workspaceId: String, taskStepId: String? = null): AssistSessionView =
        lifecycle.withLock {
            stopInternal(context, "restart")
            lastError = null
            // 上一轮会话的终态（STOPPED/COMPLETED）不得继承：媒体策略会把它当成
            // “本地引导已停止”，导致新会话的屏幕共享刚启动就被判定 STOP。
            managerOf(context).resetIfTerminal()
            val myEpoch = ++epoch
            val created = net { transport.createSession(workspaceId, taskStepId) }
                ?: throw IllegalStateException("创建协助会话失败，请检查网络")
            // create 期间用户可能已停止：代际不一致则丢弃并尽力关闭服务端会话。
            if (myEpoch != epoch) {
                runCatching { transport.stop(created.id, "start_superseded") }
                throw IllegalStateException("求助已被取消")
            }
            session = created
            lastSeq = 0
            lastPublishedFrameId = null
            lastPublishedMonoMs = 0
            AssistSecurityAudit.record(context, "ASSIST_SESSION_CREATED", "session=${created.id} state=${created.state}")
            // TURN 凭证仅用于后续 WebRTC 媒体；未配置 TURN 时后端会返回错误，不影响只读标注链路。
            turnCredentials = net { transport.turnCredentials(created.id) }
            onSessionChanged?.invoke(created)
            startPolling(context.applicationContext, myEpoch)
            created
        }

    /** 主动停止：先本地取消轮询，再尽力通知服务端；任何失败都不阻塞本地释放。 */
    suspend fun stop(context: Context, reason: String) = lifecycle.withLock {
        epoch++
        stopInternal(context, reason)
    }

    private suspend fun stopInternal(context: Context, reason: String) {
        val sid = session?.id
        pollJob?.cancel()
        pollJob = null
        session = null
        lastSeq = 0
        lastPublishedFrameId = null
        lastPublishedMonoMs = 0
        turnCredentials = null
        onSessionChanged?.invoke(null)
        if (sid != null) {
            AssistSecurityAudit.record(context, "ASSIST_SESSION_STOP", "session=$sid reason=$reason")
            net { transport.stop(sid, reason) }
        }
    }

    /**
     * 发布当前帧标识（不含任何画面）：协助者只能对“当前帧”标注，换帧后旧标注自动失效。
     * 敏感页/暂停/停止时不发布；同一帧 2 秒内不重复发送。
     */
    suspend fun publishFrame(context: Context, frame: GuidanceFrame) {
        val s = session ?: return
        if (!isSessionActive()) return
        val snap = managerOf(context).snapshot()
        if (snap.pageSensitive) return
        if (snap.state !in LOCAL_PUBLISH_STATES) return
        val now = monoClock()
        if (frame.frameId == lastPublishedFrameId && now - lastPublishedMonoMs < META_THROTTLE_MS) return
        lastPublishedFrameId = frame.frameId
        lastPublishedMonoMs = now
        val payload = buildJsonObject {
            put("frameId", frame.frameId)
            put("pageHash", frame.pageHash)
            put("packageName", frame.packageName)
        }
        net { transport.signal(s.id, "META", payload) }
    }

    /**
     * 发送 WebRTC 协商信令（OFFER/ANSWER/ICE_CANDIDATE）。
     * 仅在本机会话处于 ACTIVE/PAUSED 时调用；内容只含 SDP/candidate，不含任何画面或控件文本。
     * @return true 表示服务端已接受，false 表示无会话/未激活/网络失败（由媒体层稍后重试）。
     */
    suspend fun sendMediaSignal(eventType: String, payload: JsonObject): Boolean {
        if (eventType !in MEDIA_EVENT_TYPES) return false
        val s = session ?: return false
        if (!isSessionActive()) return false
        return net { transport.signal(s.id, eventType, payload) } != null
    }

    private fun startPolling(context: Context, myEpoch: Int) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var beatCounter = 0
            var refreshCounter = 0
            var failures = 0
            while (isActive) {
                delay(pollIntervalMs)
                if (myEpoch != epoch) return@launch
                val sid = session?.id ?: break
                // 会话状态只有协助者接受后才变 ACTIVE；定期同步，避免在 IDLE 阶段反复发 META 被 409。
                refreshCounter++
                if (refreshCounter % 2 == 0) {
                    val mine: List<AssistSessionView>? = try {
                        transport.mine().also { failures = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures++
                        if (terminalizeIfUnrecoverable(context, sid, e, failures)) return@launch
                        null
                    }
                    val latest = mine?.firstOrNull { it.id == sid }
                    if (latest != null) {
                        if (myEpoch != epoch) return@launch
                        val old = session
                        session = latest
                        if (old?.state != latest.state) {
                            AssistSecurityAudit.record(context, "ASSIST_STATE", "$sid ${old?.state}->${latest.state}")
                            onSessionChanged?.invoke(latest)
                        }
                        if (latest.state in TERMINAL_STATES) {
                            // 8.7：任一方停止/会话失效 → 本地立即停止引导，驱动采集服务释放资源。
                            AssistSecurityAudit.record(context, "ASSIST_REMOTE_TERMINAL", "$sid ${latest.state}")
                            managerOf(context).stop("assist_${latest.state.lowercase()}")
                            render(context)
                            pollJob = null
                            return@launch
                        }
                    }
                }
                if (session?.id != sid || myEpoch != epoch) return@launch
                val events: List<AssistEventView>? = try {
                    transport.events(sid, lastSeq).also { failures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures++
                    if (terminalizeIfUnrecoverable(context, sid, e, failures)) return@launch
                    null
                }
                val manager = managerOf(context)
                for (e in events.orEmpty()) {
                    if (myEpoch != epoch || session?.id != sid) return@launch
                    lastSeq = maxOf(lastSeq, e.seq)
                    val payload = parsePayload(e.payloadJson)
                    val accepted = manager.applyIncomingEvent(sid, e.eventType, payload)
                    AssistSecurityAudit.record(
                        context,
                        if (accepted) "ASSIST_EVENT" else "ASSIST_EVENT_REJECTED",
                        "type=${e.eventType} seq=${e.seq}"
                    )
                    if (accepted && e.eventType in MEDIA_EVENT_TYPES) {
                        // SDP/ICE 不改变状态机，交给媒体层；标注类事件才触发悬浮层重绘。
                        AssistSecurityAudit.record(context, "ASSIST_MEDIA_SIGNAL", "type=${e.eventType} seq=${e.seq}")
                        mediaEventHandler?.invoke(e.eventType, payload)
                    } else if (accepted) {
                        render(context)
                    }
                }
                // 心跳：让协助端知道本端仍在线；同时携带当前帧标识（无画面内容）。
                beatCounter++
                if (beatCounter % 7 == 0) {
                    val frameId = runCatching { managerOf(context).currentFrameId() }.getOrNull()
                    val payload = buildJsonObject {
                        put("frameId", frameId ?: "")
                        put("state", runCatching { managerOf(context).snapshot().state.name }.getOrDefault("UNKNOWN"))
                    }
                    net { transport.signal(sid, "HEARTBEAT", payload) }
                }
            }
        }
    }

    /**
     * 轮询连续失败的处理：登录失效立即终止；普通网络错误连续 [MAX_CONSECUTIVE_POLL_FAILURES] 次终止。
     * 必须让用户看到“会话已停止”的明确出口，而不是界面一直显示进行中、实际早已失联。
     * @return true 表示会话已在本机终止，轮询协程应立即退出。
     */
    private suspend fun terminalizeIfUnrecoverable(
        context: Context,
        sid: String,
        error: Throwable,
        failures: Int
    ): Boolean {
        val authLost = error is ApiException && error.code == "AUTH_INVALID"
        if (!authLost && failures < MAX_CONSECUTIVE_POLL_FAILURES) return false
        val reason = if (authLost) "AUTH_INVALID" else "NETWORK_LOST"
        AssistSecurityAudit.record(context, "ASSIST_LOCAL_TERMINATE", "session=$sid reason=$reason failures=$failures")
        lastError = if (authLost) {
            "登录已失效，协助会话已在本机停止。请重新登录后再发起。"
        } else {
            "网络连续失败，协助会话已在本机停止，请检查网络后重新发起。"
        }
        session = null
        lastSeq = 0
        lastPublishedFrameId = null
        lastPublishedMonoMs = 0
        turnCredentials = null
        pollJob = null
        onSessionChanged?.invoke(null)
        runCatching { stopMedia(context) }
        runCatching { managerOf(context).stop("assist_$reason") }
        render(context)
        return true
    }

    private val RUNNING_STATES = setOf("ACTIVE", "PAUSED")
    private val TERMINAL_STATES = setOf("STOPPED", "EXPIRED", "COMPLETED")
    private val MEDIA_EVENT_TYPES = setOf("OFFER", "ANSWER", "ICE_CANDIDATE")

    /** 只在本地引导活跃且未敏感时发布帧标识。 */
    private val LOCAL_PUBLISH_STATES = setOf(
        GuidanceSessionState.ACTIVE, GuidanceSessionState.ALIGNING,
        GuidanceSessionState.HIGHLIGHTING, GuidanceSessionState.WAITING_USER,
        GuidanceSessionState.TEXT_ONLY
    )

    /**
     * 统一网络封装：取消必须继续向上传播（否则 stop 后旧响应仍会写状态），
     * 其它失败降级为 null，由调用方按“本轮无数据”处理。
     */
    private suspend fun <T> net(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /** 后端 payloadJson → manager 需要的 Map；数字保留为 Long/Double，嵌套对象递归转换。 */
    private fun parsePayload(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        val element = runCatching { assistJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyMap()
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) -> toAny(v) }
    }

    private fun toAny(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
        }
        is JsonObject -> element.mapValues { (_, v) -> toAny(v) }
        is JsonArray -> element.map { toAny(it) }
    }
}

/**
 * 进程内单例门面：生产代码只使用这里，默认实例持有真实传输层与系统时钟。
 * 单测直接实例化 [AssistSignalCore] 并注入假依赖，不触碰该单例。
 */
object AssistSignalClient {

    private val default = AssistSignalCore()

    val turnCredentials: AssistTurnCredentials?
        get() = default.turnCredentials

    var onSessionChanged: ((AssistSessionView?) -> Unit)?
        get() = default.onSessionChanged
        set(value) {
            default.onSessionChanged = value
        }

    var mediaEventHandler: ((eventType: String, payload: Map<String, Any?>) -> Unit)?
        get() = default.mediaEventHandler
        set(value) {
            default.mediaEventHandler = value
        }

    val lastError: String?
        get() = default.lastError

    fun clearError() = default.clearError()

    fun current(): AssistSessionView? = default.current()

    fun isSessionActive(): Boolean = default.isSessionActive()

    suspend fun start(context: Context, workspaceId: String, taskStepId: String? = null): AssistSessionView =
        default.start(context, workspaceId, taskStepId)

    suspend fun stop(context: Context, reason: String) = default.stop(context, reason)

    suspend fun publishFrame(context: Context, frame: GuidanceFrame) = default.publishFrame(context, frame)

    suspend fun sendMediaSignal(eventType: String, payload: JsonObject): Boolean =
        default.sendMediaSignal(eventType, payload)
}

package com.actionloop.android.assist

import android.content.Context
import android.os.SystemClock
import com.actionloop.android.data.ApiClient
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
 * 协助信令客户端（指导书 8.7 / 13.5）。
 *
 * - 只在用户显式发起后创建会话；轮询服务端事件并交给 [GuidanceSessionManager.applyIncomingEvent]
 *   再校验一次（帧绑定/敏感页/白名单），服务端放行的信令不代表本机必须渲染；
 * - 只发布 META/HEARTBEAT（frameId、pageHash、包名），不发布任何截图、控件文本或用户输入；
 * - 收到的 HIGHLIGHT/POINTER/TEXT_HINT 只渲染到悬浮层，绝不执行点击/输入/支付；
 * - 服务端/协助者停止后，本地必须同步 STOP，驱动采集服务释放 MediaProjection 与悬浮层。
 */
object AssistSignalClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun current(): AssistSessionView? = session

    fun isSessionActive(): Boolean = session?.state in RUNNING_STATES

    /** 发起求助：创建远程协助会话（无指定协助者 → IDLE 开放邀请），并开始轮询。 */
    suspend fun start(context: Context, workspaceId: String, taskStepId: String? = null): AssistSessionView =
        lifecycle.withLock {
            stopInternal(context, "restart")
            val myEpoch = ++epoch
            val created = net { ApiClient.createAssistSession(workspaceId, taskStepId) }
                ?: throw IllegalStateException("创建协助会话失败，请检查网络")
            // create 期间用户可能已停止：代际不一致则丢弃并尽力关闭服务端会话。
            if (myEpoch != epoch) {
                runCatching { ApiClient.assistStop(created.id, "start_superseded") }
                throw IllegalStateException("求助已被取消")
            }
            session = created
            lastSeq = 0
            lastPublishedFrameId = null
            lastPublishedMonoMs = 0
            AssistSecurityAudit.record(context, "ASSIST_SESSION_CREATED", "session=${created.id} state=${created.state}")
            // TURN 凭证仅用于后续 WebRTC 媒体；未配置 TURN 时后端会返回错误，不影响只读标注链路。
            turnCredentials = net { ApiClient.assistTurnCredentials(created.id) }
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
            net { ApiClient.assistStop(sid, reason) }
        }
    }

    /**
     * 发布当前帧标识（不含任何画面）：协助者只能对“当前帧”标注，换帧后旧标注自动失效。
     * 敏感页/暂停/停止时不发布；同一帧 2 秒内不重复发送。
     */
    suspend fun publishFrame(context: Context, frame: GuidanceFrame) {
        val s = session ?: return
        if (!isSessionActive()) return
        val snap = GuidanceRuntime.manager(context).snapshot()
        if (snap.pageSensitive) return
        if (snap.state !in LOCAL_PUBLISH_STATES) return
        val now = SystemClock.elapsedRealtime()
        if (frame.frameId == lastPublishedFrameId && now - lastPublishedMonoMs < 2_000) return
        lastPublishedFrameId = frame.frameId
        lastPublishedMonoMs = now
        val payload = buildJsonObject {
            put("frameId", frame.frameId)
            put("pageHash", frame.pageHash)
            put("packageName", frame.packageName)
        }
        net { ApiClient.assistSignal(s.id, "META", payload) }
    }

    private fun startPolling(context: Context, myEpoch: Int) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var beatCounter = 0
            var refreshCounter = 0
            while (isActive) {
                delay(1_500)
                if (myEpoch != epoch) return@launch
                val sid = session?.id ?: break
                // 会话状态只有协助者接受后才变 ACTIVE；定期同步，避免在 IDLE 阶段反复发 META 被 409。
                refreshCounter++
                if (refreshCounter % 2 == 0) {
                    val latest = net { ApiClient.assistMine() }?.firstOrNull { it.id == sid }
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
                            GuidanceRuntime.manager(context).stop("assist_${latest.state.lowercase()}")
                            GuidanceRuntime.render(context)
                            pollJob = null
                            return@launch
                        }
                    }
                }
                if (session?.id != sid || myEpoch != epoch) return@launch
                val events = net { ApiClient.assistEvents(sid, lastSeq) }
                if (events != null) {
                    val manager = GuidanceRuntime.manager(context)
                    for (e in events) {
                        if (myEpoch != epoch || session?.id != sid) return@launch
                        lastSeq = maxOf(lastSeq, e.seq)
                        val payload = parsePayload(e.payloadJson)
                        val accepted = manager.applyIncomingEvent(sid, e.eventType, payload)
                        AssistSecurityAudit.record(
                            context,
                            if (accepted) "ASSIST_EVENT" else "ASSIST_EVENT_REJECTED",
                            "type=${e.eventType} seq=${e.seq}"
                        )
                        if (accepted) GuidanceRuntime.render(context)
                    }
                }
                // 心跳：让协助端知道本端仍在线；同时携带当前帧标识（无画面内容）。
                beatCounter++
                if (beatCounter % 7 == 0) {
                    val frameId = runCatching { GuidanceRuntime.manager(context).currentFrameId() }.getOrNull()
                    val payload = buildJsonObject {
                        put("frameId", frameId ?: "")
                        put("state", runCatching { GuidanceRuntime.manager(context).snapshot().state.name }.getOrDefault("UNKNOWN"))
                    }
                    net { ApiClient.assistSignal(sid, "HEARTBEAT", payload) }
                }
            }
        }
    }

    private val RUNNING_STATES = setOf("ACTIVE", "PAUSED")
    private val TERMINAL_STATES = setOf("STOPPED", "EXPIRED", "COMPLETED")

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
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyMap()
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

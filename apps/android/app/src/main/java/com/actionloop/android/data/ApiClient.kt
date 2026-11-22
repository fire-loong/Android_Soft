package com.actionloop.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 基于 HttpURLConnection 的 API 客户端（不引入额外网络库依赖）。
 * - 统一解包 ApiResponse<T>
 * - 401 时自动 refresh 一次并重放原请求；refresh 失败则抛 AUTH_INVALID 由 UI 登出
 * - 错误统一封装为 ApiException(code, message)
 */
object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private const val DEVICE_NAME = "ActionLoop Android"
    private const val DEVICE_PLATFORM = "ANDROID"

    // 合并并发 401：同时只允许一次 refresh，其余请求复用结果。
    // 否则两个并发请求同时用同一 refresh token 调 /auth/refresh，会触发后端复用检测，
    // 命中 revokeDevice → 整个设备会话失效（AuthService.refresh 的 jti 校验）。
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var refreshDeferred: Deferred<Boolean>? = null

    private suspend inline fun <reified T> call(
        method: String,
        path: String,
        body: String? = null,
        params: Map<String, String?> = emptyMap()
    ): T = withContext(Dispatchers.IO) {
        var resp = rawCall(method, path, body, params)
        if (resp.first == 401 && !path.contains("/auth/")) {
            if (refresh()) {
                resp = rawCall(method, path, body, params)
            }
        }
        val text = resp.second
        if (resp.first !in 200..299) {
            val env = runCatching { json.decodeFromString<ApiErrorEnvelope>(text) }.getOrNull()
            throw ApiException(
                env?.error?.code ?: "HTTP_${resp.first}",
                env?.error?.message ?: "HTTP ${resp.first}"
            )
        }
        json.decodeFromString<ApiResponse<T>>(text).data
    }

    private fun rawCall(
        method: String,
        path: String,
        body: String?,
        params: Map<String, String?>
    ): Pair<Int, String> {
        val url = buildString {
            append(Session.baseUrl).append(path)
            val qs = params.entries
                .filter { it.value != null }
                .joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
            if (qs.isNotEmpty()) append('?').append(qs)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            Session.accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            doOutput = body != null
        }
        try {
            body?.let { conn.outputStream.use { o -> o.write(it.toByteArray()) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun refresh(): Boolean {
        val deferred = refreshMutex.withLock {
            val existing = refreshDeferred
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                val created = refreshScope.async { doRefresh() }
                refreshDeferred = created
                created
            }
        }
        return deferred.await()
    }

    private suspend fun doRefresh(): Boolean = withContext(Dispatchers.IO) {
        val token = Session.refreshToken ?: return@withContext false
        try {
            val body = json.encodeToString(RefreshReq(token))
            val (code, text) = rawCall("POST", "/auth/refresh", body, emptyMap())
            if (code !in 200..299) return@withContext false
            val session = json.decodeFromString<ApiResponse<UserSession>>(text).data
            Session.save(session)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- Auth ----
    suspend fun register(email: String, username: String, password: String, displayName: String?): UserSession =
        call("POST", "/auth/register", json.encodeToString(RegisterReq(email, username, password, displayName, DEVICE_NAME, DEVICE_PLATFORM)))

    suspend fun login(login: String, password: String): UserSession =
        call("POST", "/auth/login", json.encodeToString(LoginReq(login, password, DEVICE_NAME, DEVICE_PLATFORM)))

    suspend fun me(): MeResponse = call("GET", "/me")
    suspend fun logout() { runCatching { call<Unit>("POST", "/auth/logout") }; Session.clear() }

    // ---- Workspace ----
    suspend fun workspaces(): List<Workspace> = call("GET", "/workspaces")

    // ---- Task ----
    suspend fun tasks(workspaceId: String, status: String? = null): TaskPage =
        call("GET", "/tasks", params = mapOf("workspaceId" to workspaceId, "status" to status, "size" to "100"))

    suspend fun createTask(workspaceId: String, title: String, description: String?): Task =
        call("POST", "/tasks", json.encodeToString(CreateTaskReq(workspaceId, title, description = description)))

    suspend fun getTask(id: String, workspaceId: String): Task =
        call("GET", "/tasks/$id", params = mapOf("workspaceId" to workspaceId))

    suspend fun transition(id: String, workspaceId: String, status: String, baseVersion: Int, reason: String?): Task =
        call("POST", "/tasks/$id/transitions", json.encodeToString(TransitionReq(status, baseVersion, reason)), mapOf("workspaceId" to workspaceId))

    // ---- Step ----
    suspend fun steps(taskId: String, workspaceId: String): List<Step> =
        call("GET", "/tasks/$taskId/steps", params = mapOf("workspaceId" to workspaceId))

    suspend fun createStep(taskId: String, workspaceId: String, title: String, instruction: String?): Step =
        call("POST", "/tasks/$taskId/steps", json.encodeToString(CreateStepReq(title, instruction)), mapOf("workspaceId" to workspaceId))

    // ---- Reminder ----
    suspend fun reminders(taskId: String, workspaceId: String): List<Reminder> =
        call("GET", "/tasks/$taskId/reminders", params = mapOf("workspaceId" to workspaceId))

    /** 工作区级批量拉取：一次请求返回空间内全部事项的提醒（消除 N+1）。 */
    suspend fun workspaceReminders(workspaceId: String): List<Reminder> =
        call("GET", "/workspaces/$workspaceId/reminders")

    // ---- Completion ----
    suspend fun completions(stepId: String, workspaceId: String): List<Completion> =
        call("GET", "/steps/$stepId/completions", params = mapOf("workspaceId" to workspaceId))

    suspend fun submitCompletion(stepId: String, workspaceId: String, statement: String): Completion =
        call("POST", "/steps/$stepId/completions", json.encodeToString(SubmitCompletionReq(statement)), mapOf("workspaceId" to workspaceId))

    // ---- Inbox ----
    suspend fun candidates(workspaceId: String, status: String = "PENDING"): CandidatePage =
        call("GET", "/inbox/candidates", params = mapOf("workspaceId" to workspaceId, "status" to status, "limit" to "50"))

    suspend fun candidate(workspaceId: String, candidateId: String): Candidate =
        call("GET", "/inbox/candidates/$candidateId", params = mapOf("workspaceId" to workspaceId))

    suspend fun confirmCandidate(candidateId: String, body: ConfirmCandidateReq): ConfirmResult =
        call("POST", "/inbox/candidates/$candidateId/confirm", json.encodeToString(body))

    suspend fun dismissCandidate(candidateId: String, baseVersion: Int, reason: String?): Candidate =
        call("POST", "/inbox/candidates/$candidateId/dismiss", json.encodeToString(DismissReq(baseVersion, reason)))

    // ---- Source ----
    suspend fun shareSource(req: ShareSourceReq): SourceView =
        call("POST", "/sources/share", json.encodeToString(req))

    // ---- Assist（跨 App 引导 / 远程协助，只读信令） ----
    suspend fun createAssistSession(workspaceId: String, taskStepId: String? = null): AssistSessionView =
        call("POST", "/assist/sessions", json.encodeToString(CreateAssistSessionReq(workspaceId, taskStepId = taskStepId)))

    suspend fun assistEvents(sessionId: String, afterSeq: Long): List<AssistEventView> =
        call("GET", "/assist/sessions/$sessionId/events", params = mapOf("afterSeq" to afterSeq.toString()))

    suspend fun assistMine(): List<AssistSessionView> = call("GET", "/assist/sessions")

    suspend fun assistSignal(sessionId: String, eventType: String, payload: kotlinx.serialization.json.JsonObject): AssistEventView =
        call("POST", "/assist/sessions/$sessionId/signal", json.encodeToString(AssistSignalReq(eventType, payload)))

    suspend fun assistStop(sessionId: String, reason: String): AssistSessionView =
        call("POST", "/assist/sessions/$sessionId/stop", json.encodeToString(mapOf("reason" to reason)))

    suspend fun assistTurnCredentials(sessionId: String): AssistTurnCredentials =
        call("POST", "/assist/sessions/$sessionId/turn-credentials")
}

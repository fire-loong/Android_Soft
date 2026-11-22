package com.actionloop.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// 与后端 shared ApiResponse / domain DTO 对齐（指导书 13：状态/日期格式以后端为准）。
// 状态字段用 String 接收，避免枚举不兼容导致解码失败；展示映射在 UI 层完成。

@Serializable
data class ApiResponse<T>(val data: T, val requestId: String)

@Serializable
data class ApiErrorEnvelope(val error: ApiError)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val details: JsonObject? = null
)

class ApiException(val code: String, override val message: String) : Exception(message)

@Serializable
data class UserSession(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: User
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val status: String,
    val timezone: String? = null,
    val locale: String? = null,
    val createdAt: String
)

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val type: String,
    val role: String,
    val joinedAt: String
)

/** GET /me 返回：当前用户 + 其空间列表（后端 MeResponse）。 */
@Serializable
data class MeResponse(
    val user: User,
    val workspaces: List<Workspace>
)

@Serializable
data class Task(
    val id: String,
    val workspaceId: String,
    val title: String,
    val status: String,
    val ownerId: String,
    val assigneeId: String? = null,
    val dueAt: String? = null,
    val dueDate: String? = null,
    val timePrecision: String? = null,
    val priority: Int? = null,
    val description: String? = null,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    val allowedTransitions: List<String> = emptyList()
)

@Serializable
data class TaskPage(
    val content: List<Task> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0
)

@Serializable
data class Step(
    val id: String,
    val taskId: String,
    val title: String,
    val instruction: String? = null,
    val position: Int? = null,
    val status: String,
    val version: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class Completion(
    val id: String,
    val taskStepId: String,
    val submitterId: String,
    val statement: String? = null,
    val reviewState: String,
    val submittedAt: String,
    val assetIds: List<String> = emptyList()
)

/** 事项提醒视图，与后端 ReminderView 对齐（指导书 13.2 GET /tasks/{id}/reminders）。 */
@Serializable
data class Reminder(
    val id: String,
    val taskId: String,
    val triggerAt: String,
    val channel: String,
    val state: String,
    val dedupeKey: String,
    val createdAt: String
)

// ---- 请求体 ----
@Serializable
data class RegisterReq(
    val email: String,
    val username: String,
    val password: String,
    val displayName: String? = null,
    val deviceName: String,
    val devicePlatform: String
)

@Serializable
data class LoginReq(
    val login: String,
    val password: String,
    val deviceName: String,
    val devicePlatform: String
)

@Serializable
data class RefreshReq(val refreshToken: String)

@Serializable
data class CreateTaskReq(
    val workspaceId: String,
    val title: String,
    val status: String = "ACTIVE",
    val priority: Int? = null,
    val description: String? = null,
    val dueDate: String? = null
)

@Serializable
data class TransitionReq(
    val status: String,
    val baseVersion: Int,
    val reason: String? = null
)

@Serializable
data class CreateStepReq(
    val title: String,
    val instruction: String? = null
)

@Serializable
data class SubmitCompletionReq(
    val statement: String,
    val assetIds: List<String> = emptyList()
)

// ---- 收件箱候选（指导书 4.3 / 13.2）----
@Serializable
data class Candidate(
    val id: String,
    val workspaceId: String,
    val sourceItemId: String? = null,
    val title: String,
    val summary: String? = null,
    val category: String? = null,
    val dueAt: String? = null,
    val dueDate: String? = null,
    val dueTimezone: String? = null,
    val timePrecision: String? = null,
    val location: String? = null,
    val materialsJson: String? = null,
    val nextAction: String? = null,
    val confidence: Double? = null,
    val reasonsJson: String? = null,
    val derivation: String? = null,
    val status: String,
    val confirmedTaskId: String? = null,
    val version: Int,
    val createdAt: String,
    val sourceApp: String? = null,
    val sourcePreview: String? = null
)

@Serializable
data class CandidatePage(
    val items: List<Candidate> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

@Serializable
data class ReminderInput(
    val triggerAt: String,
    val channel: String = "NOTIFICATION",
    val message: String? = null
)

@Serializable
data class ConfirmCandidateReq(
    val baseVersion: Int,
    val dueDateConfirmed: Boolean? = null,
    val title: String? = null,
    val notes: String? = null,
    val steps: List<String> = emptyList(),
    val materials: List<String> = emptyList(),
    val reminders: List<ReminderInput> = emptyList()
)

@Serializable
data class ConfirmResult(
    val taskId: String,
    val candidateId: String,
    val taskVersion: Int = 0,
    val stepCount: Int = 0,
    val reminderCount: Int = 0
)

// ---- 来源分享 ----
@Serializable
data class ShareSourceReq(
    val workspaceId: String,
    val sourceType: String,
    val sourceApp: String? = null,
    val sourceKey: String? = null,
    val title: String? = null,
    val text: String,
    val timezone: String? = null,
    val receivedAt: String? = null
)

@Serializable
data class EvidenceRef(
    val id: String,
    val sourceItemId: String? = null,
    val quote: String? = null,
    val pageRef: String? = null
)

@Serializable
data class SourceView(
    val id: String,
    val candidateIds: List<String> = emptyList(),
    val evidence: List<EvidenceRef> = emptyList()
)

@Serializable
data class DismissReq(val baseVersion: Int, val reason: String? = null)

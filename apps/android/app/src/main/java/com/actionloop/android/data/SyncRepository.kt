package com.actionloop.android.data

import android.content.Context
import com.actionloop.android.data.db.AppDatabase
import com.actionloop.android.data.db.OutboxEntity
import com.actionloop.android.data.db.ReminderEntity
import com.actionloop.android.data.db.StepEntity
import com.actionloop.android.data.db.TaskEntity
import com.actionloop.android.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * 本地优先仓库（指导书 9.4）：先读缓存再刷新，网络失败保留缓存并向上返回失败原因。
 * 写操作（完成提交）在网络不可用时进入 outbox，由 WorkManager 在网络恢复后保序重放。
 */
object SyncRepository {
    private const val KIND_COMPLETION = "COMPLETION"
    private const val MAX_ATTEMPTS = 5
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun observeTasks(context: Context, workspaceId: String): Flow<List<TaskEntity>> =
        AppDatabase.get(context).taskDao().observeTasks(workspaceId)

    fun observeSteps(context: Context, taskId: String): Flow<List<StepEntity>> =
        AppDatabase.get(context).taskDao().observeSteps(taskId)

    suspend fun loadCachedTasks(context: Context, workspaceId: String): List<TaskEntity> =
        AppDatabase.get(context).taskDao().loadTasks(workspaceId)

    /** 列表页拿到服务端数据后写回缓存，供断网时展示。*/
    suspend fun cacheTasks(context: Context, workspaceId: String, tasks: List<Task>) {
        val now = System.currentTimeMillis()
        AppDatabase.get(context).taskDao().replaceTasks(workspaceId, tasks.map { it.toEntity(workspaceId, now) })
    }

    /** @return true 表示已从服务端刷新成功；false 表示使用本地缓存。 */
    suspend fun refreshTasks(context: Context, workspaceId: String): Boolean {
        return try {
            val page = ApiClient.tasks(workspaceId)
            val now = System.currentTimeMillis()
            AppDatabase.get(context).taskDao().replaceTasks(workspaceId, page.content.map { it.toEntity(workspaceId, now) })
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun refreshSteps(context: Context, workspaceId: String, taskId: String): Boolean {
        return try {
            val steps = ApiClient.steps(taskId, workspaceId)
            AppDatabase.get(context).taskDao().replaceSteps(taskId, steps.map {
                StepEntity(
                    id = it.id, taskId = it.taskId, title = it.title, instruction = it.instruction,
                    position = it.position, status = it.status, version = it.version, updatedAt = it.updatedAt
                )
            })
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadCachedSteps(context: Context, taskId: String): List<StepEntity> =
        AppDatabase.get(context).taskDao().loadSteps(taskId)

    /**
     * 单请求拉取当前空间全部事项的提醒（批量接口，消除 N+1），按任务缓存并（重新）调度本机闹钟。
     * 拉取失败时回退到本地缓存，绝不写空、绝不丢闹钟（H1）。
     * @return 已调度的提醒数量；调度精确性由 [ReminderScheduler.canScheduleExact] 决定。
     */
    suspend fun refreshReminders(context: Context, workspaceId: String): Int {
        return try {
            val tasks = loadCachedTasks(context, workspaceId)
            val titles = tasks.associate { it.id to it.title }
            val dao = AppDatabase.get(context).taskDao()
            val fetched = runCatching { ApiClient.workspaceReminders(workspaceId) }
            val all: List<Reminder>
            if (fetched.isSuccess) {
                val list = fetched.getOrThrow()
                all = list
                // 按任务分组缓存：服务端返回的任务写入其提醒；本地有但服务端无返回的任务写空（清除过期）；
                // 服务端有但本地尚未同步到的任务也写入缓存（标题兜底为“事项提醒”）。
                val byTask = list.groupBy { it.taskId }
                val allTaskIds = (tasks.map { it.id } + byTask.keys).toSet()
                val now = System.currentTimeMillis()
                for (taskId in allTaskIds) {
                    val group = byTask[taskId].orEmpty()
                    val title = titles[taskId] ?: "事项提醒"
                    dao.replaceRemindersForTask(
                        taskId,
                        group.map {
                            ReminderEntity(it.id, workspaceId, it.taskId, title, it.triggerAt, it.channel, it.state, it.dedupeKey, now)
                        }
                    )
                }
            } else {
                // 批量拉取失败：整体回退到本地缓存，保留已缓存提醒与已调度闹钟。
                all = dao.loadReminders(workspaceId).map { it.toDomain() }
            }
            ReminderScheduler.rescheduleAll(context, all, titles).first
        } catch (e: Exception) {
            0
        }
    }

    /** 从本地缓存重建提醒闹钟（离线/开机场景），不访问网络。 */
    suspend fun rescheduleRemindersFromCache(context: Context, workspaceId: String): Int {
        val entities = AppDatabase.get(context).taskDao().loadReminders(workspaceId)
        val reminders = entities.map { it.toDomain() }
        val titles = entities.associate { it.taskId to it.taskTitle }
        return ReminderScheduler.rescheduleAll(context, reminders, titles).first
    }

    /**
     * 提交完成凭证；网络失败时写入 outbox（不伪装成功，UI 明确提示“已离线排队”）。
     * @return true 表示已实时提交，false 表示已离线排队。
     */
    suspend fun submitCompletion(context: Context, workspaceId: String, stepId: String, statement: String): Boolean {
        return try {
            ApiClient.submitCompletion(stepId, workspaceId, statement)
            true
        } catch (e: ApiException) {
            // 业务错误（权限/状态冲突）直接抛出，不能进入离线队列
            throw e
        } catch (e: Exception) {
            AppDatabase.get(context).taskDao().enqueue(
                OutboxEntity(
                    kind = KIND_COMPLETION, workspaceId = workspaceId, targetId = stepId,
                    payload = json.encodeToString(CompletionPayload.serializer(), CompletionPayload(statement)),
                    createdAt = System.currentTimeMillis()
                )
            )
            false
        }
    }

    /** 由 WorkManager 调用：按入队顺序重放，业务错误达上限后丢弃并保留审计日志。 */
    suspend fun drainOutbox(context: Context): Int {
        val dao = AppDatabase.get(context).taskDao()
        var done = 0
        for (action in dao.pendingActions()) {
            if (action.kind != KIND_COMPLETION) {
                dao.deleteAction(action.id)
                continue
            }
            try {
                val payload = json.decodeFromString(CompletionPayload.serializer(), action.payload)
                ApiClient.submitCompletion(action.targetId, action.workspaceId, payload.statement)
                dao.deleteAction(action.id)
                done++
            } catch (e: ApiException) {
                if (e.code.startsWith("HTTP_5") || e.code == "NETWORK_ERROR") {
                    dao.bumpAttempts(action.id)
                } else {
                    // 4xx：不可重试（版本冲突/权限/状态），丢弃避免永久阻塞队列
                    dao.deleteAction(action.id)
                }
            } catch (e: Exception) {
                dao.bumpAttempts(action.id)
                if (action.attempts + 1 >= MAX_ATTEMPTS) dao.deleteAction(action.id)
            }
        }
        return done
    }

    private fun Task.toEntity(workspaceId: String, now: Long) = TaskEntity(
        id = id, workspaceId = workspaceId, title = title, description = description, status = status,
        ownerId = ownerId, assigneeId = assigneeId, dueAt = dueAt, dueDate = dueDate,
        timePrecision = timePrecision, version = version, createdAt = createdAt, updatedAt = updatedAt, cachedAt = now
    )
}

private fun com.actionloop.android.data.db.ReminderEntity.toDomain(): Reminder = Reminder(
    id = id, taskId = taskId, triggerAt = triggerAt, channel = channel,
    state = state, dedupeKey = dedupeKey,
    createdAt = java.time.Instant.ofEpochMilli(cachedAt).toString()
)

@kotlinx.serialization.Serializable
data class CompletionPayload(val statement: String)

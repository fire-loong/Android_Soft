package com.actionloop.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地缓存（指导书 9.4 离线优先）：仅缓存展示所需最小字段，敏感原文不落地到 Room。
 * 以服务端 version 作为乐观锁版本，写操作始终回源校验。
 */
@Entity(tableName = "tasks", indices = [Index("workspaceId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val title: String,
    val description: String?,
    val status: String,
    val ownerId: String,
    val assigneeId: String?,
    val dueAt: String?,
    val dueDate: String?,
    val timePrecision: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    val cachedAt: Long
)

@Entity(tableName = "steps", indices = [Index("taskId")])
data class StepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val instruction: String?,
    val position: Int?,
    val status: String,
    val version: Int,
    val updatedAt: String
)

/** 离线写操作队列（完成凭证）：网络恢复后由 WorkManager 保序重放。 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val workspaceId: String,
    val targetId: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0
)

/** 提醒本地缓存：仅调度所需字段（不含原文），用于离线 AlarmManager 重建与开机恢复。 */
@Entity(tableName = "reminders", indices = [Index("taskId"), Index("workspaceId")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val taskId: String,
    val taskTitle: String,
    val triggerAt: String,
    val channel: String,
    val state: String,
    val dedupeKey: String,
    val cachedAt: Long
)

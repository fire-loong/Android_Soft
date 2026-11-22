package com.actionloop.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId ORDER BY (dueDate IS NULL), dueDate ASC, updatedAt DESC")
    fun observeTasks(workspaceId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId ORDER BY (dueDate IS NULL), dueDate ASC, updatedAt DESC")
    suspend fun loadTasks(workspaceId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTask(taskId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE workspaceId = :workspaceId")
    suspend fun clearTasks(workspaceId: String)

    @Transaction
    suspend fun replaceTasks(workspaceId: String, tasks: List<TaskEntity>) {
        clearTasks(workspaceId)
        upsertTasks(tasks)
    }

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position ASC, updatedAt ASC")
    fun observeSteps(taskId: String): Flow<List<StepEntity>>

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position ASC, updatedAt ASC")
    suspend fun loadSteps(taskId: String): List<StepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<StepEntity>)

    @Query("DELETE FROM steps WHERE taskId = :taskId")
    suspend fun clearSteps(taskId: String)

    @Transaction
    suspend fun replaceSteps(taskId: String, steps: List<StepEntity>) {
        clearSteps(taskId)
        upsertSteps(steps)
    }

    @Insert
    suspend fun enqueue(action: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id ASC")
    suspend fun pendingActions(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteAction(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: Long)

    // ---- Reminders ----
    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY triggerAt ASC")
    fun observeReminders(taskId: String): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY triggerAt ASC")
    suspend fun loadRemindersForTask(taskId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE workspaceId = :workspaceId ORDER BY triggerAt ASC")
    suspend fun loadReminders(workspaceId: String): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminders(reminders: List<ReminderEntity>)

    @Query("DELETE FROM reminders WHERE taskId = :taskId")
    suspend fun clearRemindersForTask(taskId: String)

    @Transaction
    suspend fun replaceRemindersForTask(taskId: String, reminders: List<ReminderEntity>) {
        clearRemindersForTask(taskId)
        upsertReminders(reminders)
    }
}

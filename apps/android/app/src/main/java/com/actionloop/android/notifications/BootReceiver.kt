package com.actionloop.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actionloop.android.data.Reminder
import com.actionloop.android.data.Session
import com.actionloop.android.data.db.AppDatabase
import com.actionloop.android.data.db.ReminderEntity
import kotlinx.coroutines.runBlocking
import java.time.Instant

/** 开机后从本地缓存重建提醒闹钟（离线可用，不依赖网络）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Session.isLoggedIn) return
        val workspaceId = Session.currentWorkspaceId ?: return
        val pending = goAsync()
        Thread {
            try {
                val entities = runBlocking { AppDatabase.get(context).taskDao().loadReminders(workspaceId) }
                val reminders = entities.map { it.toReminder() }
                val titles = entities.associate { it.taskId to it.taskTitle }
                ReminderScheduler.rescheduleAll(context, reminders, titles)
            } catch (_: Exception) {
                // 开机重建失败不阻断系统；下次前台同步会再次调度
            } finally {
                pending.finish()
            }
        }.start()
    }
}

private fun ReminderEntity.toReminder() = Reminder(
    id = id,
    taskId = taskId,
    triggerAt = triggerAt,
    channel = channel,
    state = state,
    dedupeKey = dedupeKey,
    createdAt = Instant.ofEpochMilli(cachedAt).toString()
)

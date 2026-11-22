package com.actionloop.android.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.actionloop.android.data.Reminder
import java.time.Instant

/**
 * 本地提醒调度（指导书 9.4 / 13.2）：用 AlarmManager 精确唤起通知，离线可用。
 * - 仅调度 state=SCHEDULED 且 channel 为 NOTIFICATION/HIGH_PRIORITY 的提醒；已触发的不重复调度；
 * - 精确闹钟（setExactAndAllowWhileIdle）在未授权时降级为非精确，并由权限页明确提示，绝不假装精确；
 * - 幂等：每次重建先按 reminder id 取消上一批闹钟（用 action 区分，避免 hashCode 碰撞覆盖），再写入当前集合；
 * - 全程加锁，避免 Today/Inbox/Worker 并发重建产生孤儿或丢失闹钟；
 * - 不发送任何服务端状态变更，触发仅本机通知，事项状态仍以服务端为准。
 */
object ReminderScheduler {
    const val CHANNEL_ID = "actionloop_reminders"
    private const val PREFS = "actionloop_reminder_alarms"
    private const val KEY_SCHEDULED = "scheduled_ids"
    private const val ACTION_PREFIX = "com.actionloop.android.REMINDER_"
    private const val CATCH_UP_WINDOW_MS = 24L * 60 * 60 * 1000 // 过期超过 1 天不再补发
    private val LOCK = Any()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "事项提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "ActionLoop 事项截止与步骤提醒"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** 是否可使用精确闹钟（Android 12+ 需用户在设置中授权）。 */
    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else true

    /** 幂等重建：取消旧闹钟后按当前提醒集合重新调度。返回 (已调度数, 是否使用了精确闹钟)。 */
    fun rescheduleAll(
        context: Context,
        reminders: List<Reminder>,
        taskTitles: Map<String, String>
    ): Pair<Int, Boolean> = synchronized(LOCK) {
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return 0 to false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()

        // 先取消上一批：用 action 精确定位每个 PendingIntent，避免 requestCode 碰撞误取消。
        for (id in previous) {
            val cancelIntent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_PREFIX + id
            }
            val existing = PendingIntent.getBroadcast(
                context, 0, cancelIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (existing != null) {
                am.cancel(existing)
                existing.cancel()
            }
        }

        val now = System.currentTimeMillis()
        val exact = canScheduleExact(context)
        val scheduled = mutableSetOf<String>()
        for (r in reminders) {
            if (r.state != "SCHEDULED") continue
            if (r.channel != "NOTIFICATION" && r.channel != "HIGH_PRIORITY") continue
            if (FiredReminders.contains(context, r.id)) continue // 已触发的不再重复调度
            val trigger = runCatching { Instant.parse(r.triggerAt).toEpochMilli() }.getOrNull() ?: continue
            if (trigger < now - CATCH_UP_WINDOW_MS) continue
            val at = maxOf(trigger, now + 5_000) // 至少 5 秒后，避免立即风暴
            scheduleOne(context, am, r, taskTitles[r.taskId] ?: "事项提醒", at, exact)
            scheduled.add(r.id)
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, scheduled).apply()
        scheduled.size to exact
    }

    /** 调试/权限页测试入口：5 秒后触发一条测试提醒。 */
    fun scheduleTest(context: Context): Boolean = synchronized(LOCK) {
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        val exact = canScheduleExact(context)
        val r = Reminder(
            id = "test-${System.currentTimeMillis()}",
            taskId = "test",
            triggerAt = Instant.now().toString(),
            channel = "NOTIFICATION",
            state = "SCHEDULED",
            dedupeKey = "test",
            createdAt = Instant.now().toString()
        )
        scheduleOne(context, am, r, "测试提醒", System.currentTimeMillis() + 5_000, exact)
        exact
    }

    private fun scheduleOne(
        context: Context,
        am: AlarmManager,
        r: Reminder,
        title: String,
        at: Long,
        exact: Boolean
    ) {
        val highPriority = r.channel == "HIGH_PRIORITY"
        val message = if (highPriority) "重要：「$title」到了提醒时间，请及时处理" else "「$title」到了提醒时间"
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_PREFIX + r.id // 用 action 区分不同提醒，避免 requestCode 碰撞导致 PendingIntent 互相覆盖
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, r.id)
            putExtra(ReminderReceiver.EXTRA_TASK_ID, r.taskId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_HIGH_PRIORITY, highPriority)
        }
        val pi = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

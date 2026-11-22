package com.actionloop.android.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.actionloop.android.MainActivity
import com.actionloop.android.R

/** 闹钟触发后本机发出通知；不修改任何事项状态（状态以服务端为准）。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "ActionLoop 提醒"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "到了该执行事项的时间"
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: title
        val highPriority = intent.getBooleanExtra(EXTRA_HIGH_PRIORITY, false)

        // 本机去重：标记已触发，后续刷新不会再次调度该提醒（不依赖服务端 worker）。
        reminderId.takeIf { it.isNotBlank() }?.let { FiredReminders.add(context, it) }
        ReminderScheduler.ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!taskId.isNullOrBlank() && taskId != "test") putExtra("openTask", taskId)
        }
        val tapPi = PendingIntent.getActivity(
            context, taskId?.hashCode() ?: 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(reminderId.hashCode(), notification)
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_HIGH_PRIORITY = "highPriority"
    }
}

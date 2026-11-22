package com.actionloop.android.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.actionloop.android.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 通知采集（指导书 WP04）：只读取用户授权 App 的通知标题/正文，作为“来源”上传，
 * 供服务端生成待确认候选。绝不读取任何第三方 App 的私有数据库，也不执行任何通知操作。
 *
 * 安全策略（默认拒绝）：
 * 1. 未登录或未选择空间时不上传；
 * 2. 未在本地白名单中的 App 只记录包名，正文不落盘不上传；
 * 3. 命中敏感词的通知在设备端直接丢弃；
 * 4. 同一通知 key + 正文只处理一次，避免重复来源。
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!Session.isLoggedIn) return
        val packageName = notification.packageName ?: return
        if (packageName == applicationContext.packageName) return
        val workspaceId = Session.currentWorkspaceId ?: return

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val content = listOf(title, body).filter { it.isNotBlank() }.joinToString("\n")
        if (content.isBlank()) return

        val context = applicationContext
        scope.launch {
            AllowlistStore.addSeen(context, packageName)
            if (!AllowlistStore.isAllowed(context, packageName)) return@launch
            if (NotificationFilter.isSensitive(content)) return@launch
            if (isDuplicate(notification.key, content)) return@launch
            SourceUploadWorker.enqueue(
                context = context,
                workspaceId = workspaceId,
                packageName = packageName,
                sourceKey = notification.key,
                title = title.ifBlank { packageName },
                text = NotificationFilter.clip(content)
            )
        }
    }

    /** 本地去重：只保存哈希，不保存正文，最多保留 300 条。*/
    private fun isDuplicate(key: String?, content: String): Boolean {
        val prefs = getSharedPreferences("actionloop_notify_dedupe", Context.MODE_PRIVATE)
        val digest = MessageDigest.getInstance("SHA-256").digest("${key ?: ""}|$content".toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(32)
        val seen = prefs.getStringSet("hashes", emptySet()) ?: emptySet()
        if (hash in seen) return true
        val next = if (seen.size >= 300) seen.drop(100).toSet() + hash else seen + hash
        prefs.edit().putStringSet("hashes", next).apply()
        return false
    }
}

package com.actionloop.android.assist

import android.content.Context

/**
 * 协助安全审计（指导书 8.7 / 8.10 / 15.4）。
 * 只记录最小必要事件：被本机拒绝的控制类信令、会话开始/停止、敏感页触发。
 * 绝不记录原始屏幕帧、节点全文或可识别内容；记录有上限并可被用户在设置中清除。
 */
object AssistSecurityAudit {
    private const val PREFS = "actionloop_assist_security_audit"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 2000

    data class Entry(val ts: Long, val kind: String, val detail: String)

    @Synchronized
    fun record(context: Context, kind: String, detail: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        set.add("${System.currentTimeMillis()}|$kind|${detail.take(200)}")
        if (set.size > MAX_ENTRIES) {
            // 淘汰最旧的一半（按时间戳前缀排序）
            set.sortedBy { it.substringBefore('|').toLongOrNull() ?: 0L }
                .take(MAX_ENTRIES / 2).forEach { set.remove(it) }
        }
        prefs.edit().putStringSet(KEY, set).apply()
    }

    @Synchronized
    fun recent(context: Context, limit: Int = 200): List<Entry> {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()
        return set.mapNotNull { raw ->
            val parts = raw.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            Entry(parts[0].toLongOrNull() ?: 0L, parts[1], parts[2])
        }.sortedByDescending { it.ts }.take(limit)
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

package com.actionloop.android.notifications

import android.content.Context

/**
 * 本机“已触发”提醒去重（指导书 4.4 提醒策略 + 安全边界：不依赖服务端可用性）。
 * 即使后端 worker 未把 reminder 状态置为 SENT，Android 也不会在每次刷新时重新调度已触发的提醒，
 * 避免重复通知轰炸。记录保留 7 天并设上限，防止无限增长。
 */
object FiredReminders {
    private const val PREFS = "actionloop_fired_reminders"
    private const val KEY = "fired"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 5000

    @Synchronized
    fun add(context: Context, reminderId: String) {
        val map = read(context)
        map[reminderId] = System.currentTimeMillis()
        if (map.size > MAX_ENTRIES) {
            map.entries.sortedBy { it.value }.take(MAX_ENTRIES / 2).forEach { map.remove(it.key) }
        }
        write(context, map)
    }

    @Synchronized
    fun contains(context: Context, reminderId: String): Boolean {
        val map = read(context)
        val now = System.currentTimeMillis()
        val iter = map.entries.iterator()
        var changed = false
        while (iter.hasNext()) {
            if (now - iter.next().value > TTL_MS) {
                iter.remove()
                changed = true
            }
        }
        if (changed) write(context, map)
        return map.containsKey(reminderId)
    }

    private fun read(context: Context): MutableMap<String, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        val map = HashMap<String, Long>(set.size)
        for (entry in set) {
            val idx = entry.lastIndexOf('=')
            if (idx > 0) {
                val id = entry.substring(0, idx)
                val ts = entry.substring(idx + 1).toLongOrNull() ?: continue
                map[id] = ts
            }
        }
        return map
    }

    private fun write(context: Context, map: Map<String, Long>) {
        val set = map.mapTo(HashSet(map.size)) { (k, v) -> "$k=$v" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, set).apply()
    }
}

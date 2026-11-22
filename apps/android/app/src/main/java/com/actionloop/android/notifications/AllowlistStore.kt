package com.actionloop.android.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notifyDataStore by preferencesDataStore(name = "actionloop_notify")

/**
 * 通知采集白名单（指导书 15 / WP04 安全边界）：
 * - 只有用户显式勾选的 App 才会把通知正文上传为来源；
 * - 未授权 App 仅记录包名用于展示选择列表，绝不保存正文；
 * - 敏感通知（验证码/密码/支付/身份证等）在本地直接丢弃，不上传、不缓存。
 */
object AllowlistStore {
    private val ALLOW = stringSetPreferencesKey("allowlist")
    private val SEEN = stringSetPreferencesKey("seen_packages")

    suspend fun allowed(context: Context): Set<String> =
        context.notifyDataStore.data.first()[ALLOW] ?: emptySet()

    suspend fun isAllowed(context: Context, packageName: String): Boolean =
        packageName in allowed(context)

    suspend fun setAllowed(context: Context, packageName: String, allowed: Boolean) {
        context.notifyDataStore.edit { prefs ->
            val current = prefs[ALLOW] ?: emptySet()
            prefs[ALLOW] = if (allowed) current + packageName else current - packageName
        }
    }

    /** 只记录包名，不含任何通知内容。*/
    suspend fun addSeen(context: Context, packageName: String) {
        context.notifyDataStore.edit { prefs ->
            val current = prefs[SEEN] ?: emptySet()
            if (packageName !in current && current.size < 200) {
                prefs[SEEN] = current + packageName
            }
        }
    }

    fun seen(context: Context): Flow<Set<String>> =
        context.notifyDataStore.data.map { it[SEEN] ?: emptySet() }
}

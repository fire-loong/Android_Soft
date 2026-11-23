package com.actionloop.android.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地会话：access/refresh token 使用 Android Keystore 加密后写入 SharedPreferences，
 * 当前空间 ID 为普通配置。baseUrl 默认为模拟器访问宿主机的地址（10.0.2.2 → 宿主 localhost:8080）。
 */
object Session {
    private const val PREFS = "actionloop_session"
    private const val KEY_ACCESS = "access_token_enc"
    private const val KEY_REFRESH = "refresh_token_enc"
    private lateinit var prefs: SharedPreferences

    /** 可在 Application 初始化时覆盖（例如切到局域网真机地址）。 */
    var baseUrl: String = "http://10.0.2.2:8080/api/v1"

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 真机联调：允许通过本地配置覆盖后端地址（如 http://192.168.137.1:8080/api/v1）。
        // 仅供开发/自托管，用户未显式配置时保持模拟器默认地址。
        val override = context.applicationContext
            .getSharedPreferences("actionloop_debug", Context.MODE_PRIVATE)
            .getString("base_url", null)
        if (!override.isNullOrBlank()) {
            baseUrl = override.trim().trimEnd('/')
        }
    }

    var accessToken: String?
        get() = TokenCrypto.decrypt(prefs.getString(KEY_ACCESS, null))
        set(value) = prefs.edit().putString(KEY_ACCESS, value?.let(TokenCrypto::encrypt)).apply()

    var refreshToken: String?
        get() = TokenCrypto.decrypt(prefs.getString(KEY_REFRESH, null))
        set(value) = prefs.edit().putString(KEY_REFRESH, value?.let(TokenCrypto::encrypt)).apply()

    var currentWorkspaceId: String?
        get() = prefs.getString("workspace_id", null)
        set(value) = prefs.edit().putString("workspace_id", value).apply()

    val isLoggedIn: Boolean get() = refreshToken != null

    fun save(session: UserSession) {
        accessToken = session.accessToken
        refreshToken = session.refreshToken
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

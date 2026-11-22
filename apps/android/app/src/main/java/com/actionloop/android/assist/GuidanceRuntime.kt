package com.actionloop.android.assist

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 引导运行时单例：在 UI、屏幕采集服务、无障碍服务之间共享同一个 [GuidanceSessionManager]，
 * 并持有悬浮层引用。所有状态读写都经由 manager 的 Mutex，保证多入口一致。
 */
object GuidanceRuntime {
    private val lock = Any()

    @Volatile
    private var managerRef: GuidanceSessionManager? = null

    @Volatile
    var overlay: GuidanceOverlay? = null
        private set

    fun manager(context: Context): GuidanceSessionManager = synchronized(lock) {
        managerRef ?: GuidanceSessionManager(context.applicationContext).also { managerRef = it }
    }

    fun attachOverlay(overlay: GuidanceOverlay) {
        this.overlay = overlay
    }

    fun detachOverlay(overlay: GuidanceOverlay) {
        if (this.overlay === overlay) this.overlay = null
    }

    /** 把当前快照渲染到悬浮层（若已附着）。由采集/无障碍在状态变更后调用。 */
    suspend fun render(context: Context) {
        overlay?.render(manager(context).snapshot())
    }

    // ---- 权限与可用性 ----

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, GuidanceAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** MediaProjection 在 minSdk 26 上始终可用；每次会话都需用户当次授权，不缓存。 */
    fun isMediaProjectionAvailable(): Boolean = true
}

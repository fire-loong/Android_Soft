package com.actionloop.android.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.actionloop.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /**
     * 求助入口处理器（由前台 UI 注册）。悬浮层点击“求助”时优先调用；
     * 未注册时回退为拉起 App 的求助页面，保证按钮在任何时刻都不是无响应的。
     */
    @Volatile
    var helpHandler: (() -> Unit)? = null

    fun manager(context: Context): GuidanceSessionManager = synchronized(lock) {
        managerRef ?: GuidanceSessionManager(context.applicationContext).also { managerRef = it }
    }

    fun attachOverlay(overlay: GuidanceOverlay) {
        this.overlay = overlay
    }

    fun detachOverlay(overlay: GuidanceOverlay) {
        if (this.overlay === overlay) this.overlay = null
    }

    /** 悬浮层“求助”按钮：打开 App 内远程协助入口，不在后台静默发起任何网络请求。 */
    fun requestHelp(context: Context) {
        val handler = helpHandler
        if (handler != null) {
            handler()
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_HELP_REQUEST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 把当前快照渲染到悬浮层（若已附着）。由采集/无障碍在状态变更后调用。
     * 无障碍服务在 Dispatchers.Default 上采集，View 更新必须切回主线程，否则会抛
     * CalledFromWrongThreadException（悬浮层直接崩溃，等于安全控制条失效）。
     */
    suspend fun render(context: Context) {
        val manager = manager(context)
        val snapshot = manager.snapshot()
        val view = overlay ?: return
        withContext(Dispatchers.Main.immediate) { view.render(snapshot) }
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

    const val ACTION_HELP_REQUEST = "com.actionloop.android.action.HELP_REQUEST"
}

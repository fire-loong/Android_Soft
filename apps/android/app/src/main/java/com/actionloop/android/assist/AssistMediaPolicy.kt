package com.actionloop.android.assist

/**
 * 远程协助媒体（只读屏幕共享）发送策略（指导书 8.3 / 8.5 / 8.7 / 8.10）。
 *
 * 纯函数：只做判定，不触碰 MediaProjection / WebRTC，便于在无设备环境用单测固定安全语义。
 * 实际启停由 [AssistMediaService] 执行。
 *
 * 关键约束：
 * - 协助会话不存在 / 已终止 / 锁屏 → 立即停止媒体（释放 MediaProjection，不能只暂停）；
 * - 协助会话未 ACTIVE（等待接受、已暂停）→ 不发送任何帧；
 * - 敏感页（无障碍判定或采集侧 FLAG_SECURE 探测）→ 暂停发送；
 * - 本地引导被用户暂停 → 暂停；本地引导被停止/完成 → 停止媒体。
 */
object AssistMediaPolicy {

    enum class Action { SEND, PAUSE, STOP }

    private val TERMINAL_ASSIST_STATES = setOf("STOPPED", "EXPIRED", "COMPLETED")

    /**
     * @param assistState 远程协助会话状态（null 表示无会话）
     * @param guideState 本地引导状态（IDLE 表示本机未启动引导，不影响媒体判定）
     * @param pageSensitive 当前页面是否敏感（密码/支付/FLAG_SECURE）
     * @param keyguardLocked 是否锁屏
     */
    fun decide(
        assistState: String?,
        guideState: GuidanceSessionState?,
        pageSensitive: Boolean,
        keyguardLocked: Boolean
    ): Action {
        // 锁屏/撤权/会话消失优先级最高：必须真正释放媒体，而不是继续持有 MediaProjection。
        if (assistState == null || assistState in TERMINAL_ASSIST_STATES) return Action.STOP
        if (keyguardLocked) return Action.STOP
        if (guideState != null && guideState.isTerminal()) return Action.STOP
        // 协助者尚未接受或会话已暂停：不发送任何像素。
        if (assistState != "ACTIVE") return Action.PAUSE
        if (pageSensitive) return Action.PAUSE
        if (guideState == GuidanceSessionState.PAUSED) return Action.PAUSE
        return Action.SEND
    }
}

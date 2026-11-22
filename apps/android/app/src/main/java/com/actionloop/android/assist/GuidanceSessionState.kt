package com.actionloop.android.assist

/**
 * 引导会话状态机（指导书 8.6）。
 * 状态迁移必须显式、可审计；终态（COMPLETED / STOPPED）只有 reset 才能离开。
 */
enum class GuidanceSessionState {
    IDLE,
    CONSENT_PENDING,
    ACTIVE,
    ALIGNING,
    HIGHLIGHTING,
    WAITING_USER,
    TEXT_ONLY,
    PAUSED,
    COMPLETED,
    STOPPED;

    fun isTerminal(): Boolean = this == COMPLETED || this == STOPPED

    /**
     * 合法迁移表（权威）。以 8.6 状态图为基础，并显式收录以下规范要求：
     * - 任一状态 → STOPPED：8.3/8.10 要求用户、系统通知、撤销 MediaProjection 在**任何时刻**都能停止，
     *   停止是安全出口，不允许被状态机拒绝；
     * - ACTIVE → HIGHLIGHTING：8.7 远程标注可在授权后、首帧对齐前先到达，仍需渲染到当前帧；
     * - HIGHLIGHTING/WAITING_USER → TEXT_ONLY：8.4 页面变化导致匹配不达标时，旧高亮必须立即失效并降级文字；
     * - TEXT_ONLY → ALIGNING：8.4 要求按帧连续匹配，页面变化后允许重新对齐（不放宽任何安全约束）；
     * - TEXT_ONLY → PAUSED：文字降级仍是进行中的引导，8.3/8.5 要求锁屏/敏感页/用户暂停在任何活跃态都能暂停。
     * 终态（COMPLETED/STOPPED）不可迁出，只能由 reset() 重置后重新开始。
     */
    fun canTransitionTo(target: GuidanceSessionState): Boolean = when (this) {
        IDLE -> target == CONSENT_PENDING || target == STOPPED
        CONSENT_PENDING -> target in setOf(ACTIVE, IDLE, STOPPED)
        ACTIVE -> target in setOf(ALIGNING, HIGHLIGHTING, PAUSED, STOPPED)
        ALIGNING -> target in setOf(HIGHLIGHTING, TEXT_ONLY, ACTIVE, PAUSED, STOPPED)
        HIGHLIGHTING -> target in setOf(WAITING_USER, COMPLETED, PAUSED, STOPPED, ALIGNING, TEXT_ONLY)
        WAITING_USER -> target in setOf(ALIGNING, COMPLETED, PAUSED, STOPPED, TEXT_ONLY)
        TEXT_ONLY -> target in setOf(ALIGNING, PAUSED, STOPPED)
        PAUSED -> target == CONSENT_PENDING || target == STOPPED
        COMPLETED, STOPPED -> false
    }

    fun transition(target: GuidanceSessionState): GuidanceSessionState {
        require(canTransitionTo(target)) { "非法状态迁移: $this -> $target" }
        return target
    }
}

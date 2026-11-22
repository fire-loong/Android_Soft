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
     * 合法迁移表。以 8.6 状态图为基础，并按 8.3/8.5 的规范意图扩展：
     * 任一活跃子态（ACTIVE/ALIGNING/HIGHLIGHTING/WAITING_USER）都可因用户停止、
     * 敏感页或锁屏而进入 PAUSED 或 STOPPED；ALIGNING/HIGHLIGHTING 可回到 ACTIVE 等待下一帧。
     * 终态（COMPLETED/STOPPED）不可迁出，只能由 reset() 重置后重新开始。
     */
    fun canTransitionTo(target: GuidanceSessionState): Boolean = when (this) {
        IDLE -> target == CONSENT_PENDING
        CONSENT_PENDING -> target == ACTIVE || target == IDLE
        ACTIVE -> target in setOf(ALIGNING, PAUSED, STOPPED)
        ALIGNING -> target in setOf(HIGHLIGHTING, TEXT_ONLY, ACTIVE, PAUSED, STOPPED)
        HIGHLIGHTING -> target in setOf(WAITING_USER, COMPLETED, PAUSED, STOPPED, ALIGNING)
        WAITING_USER -> target in setOf(ALIGNING, COMPLETED, PAUSED, STOPPED)
        TEXT_ONLY -> target == STOPPED
        PAUSED -> target in setOf(CONSENT_PENDING, STOPPED)
        COMPLETED, STOPPED -> false
    }

    fun transition(target: GuidanceSessionState): GuidanceSessionState {
        require(canTransitionTo(target)) { "非法状态迁移: $this -> $target" }
        return target
    }
}

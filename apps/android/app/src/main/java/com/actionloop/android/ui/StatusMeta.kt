package com.actionloop.android.ui

import androidx.compose.ui.graphics.Color

// 状态展示映射（值以后端为准，这里仅展示），与 Web 端 format.ts 保持一致。
object StatusMeta {
    private val task = mapOf(
        "DRAFT" to ("草稿" to Color.Gray),
        "PENDING_CONFIRMATION" to ("待确认" to Color(0xFFFFA000)),
        "ACTIVE" to ("进行中" to Color(0xFF1976D2)),
        "IN_PROGRESS" to ("执行中" to Color(0xFF0097A7)),
        "BLOCKED" to ("受阻" to Color(0xFFD32F2F)),
        "PENDING_REVIEW" to ("待复核" to Color(0xFFF57C00)),
        "COMPLETED" to ("已完成" to Color(0xFF388E3C)),
        "CANCELLED" to ("已取消" to Color.Gray),
        "DISMISSED" to ("已忽略" to Color.Gray)
    )
    private val step = mapOf(
        "TODO" to ("待办" to Color.Gray),
        "IN_PROGRESS" to ("执行中" to Color(0xFF1976D2)),
        "BLOCKED" to ("受阻" to Color(0xFFD32F2F)),
        "PENDING_REVIEW" to ("待复核" to Color(0xFFF57C00)),
        "DONE" to ("已完成" to Color(0xFF388E3C))
    )
    private val review = mapOf(
        "PENDING" to ("待复核" to Color(0xFFFFA000)),
        "APPROVED" to ("已通过" to Color(0xFF388E3C)),
        "REJECTED" to ("已驳回" to Color(0xFFD32F2F))
    )
    private val role = mapOf(
        "OWNER" to "所有者", "ADMIN" to "管理员", "MEMBER" to "成员", "VIEWER" to "观察者"
    )

    fun taskLabel(s: String): String = task[s]?.first ?: s
    fun taskColor(s: String): Color = task[s]?.second ?: Color.Gray
    fun stepLabel(s: String): String = step[s]?.first ?: s
    fun stepColor(s: String): Color = step[s]?.second ?: Color.Gray
    fun reviewLabel(s: String): String = review[s]?.first ?: s
    fun reviewColor(s: String): Color = review[s]?.second ?: Color.Gray
    fun roleLabel(s: String): String = role[s] ?: s
}

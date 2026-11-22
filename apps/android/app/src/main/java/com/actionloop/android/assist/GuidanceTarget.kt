package com.actionloop.android.assist

/**
 * 引导目标（指导书 8.4 / 8.8）。
 *
 * 只描述“应该点哪个控件”，不包含任何执行动作：匹配成功后仅产生高亮/文字提示，
 * 点击仍由用户亲自完成。目标来自已验证流程步骤或受控测试 App 的标注集。
 *
 * - [labels] 文本锚点（必填，按优先级排列）；
 * - [viewIdHint] 稳定节点标识（如 `btn_next`），比文本更抗文案变化；
 * - [roleHint] 角色提示（button / list / link …），用于类名与资源 id 的层级评分；
 * - [nearbyText] 附近文本（父容器文案），用于层级/相对位置判定；
 * - [expectedBounds] 已验证的归一化位置，仅在文本/层级仍不足时加权；
 * - [minConfidence] 该目标的最低置信阈值，最终仍受匹配器全局下限约束。
 */
data class GuidanceTarget(
    val packageName: String,
    val labels: List<String>,
    val viewIdHint: String? = null,
    val roleHint: String? = null,
    val nearbyText: String? = null,
    val expectedBounds: NormalizedBounds? = null,
    val minConfidence: Double = TargetMatcher.MIN_CONFIDENCE
)

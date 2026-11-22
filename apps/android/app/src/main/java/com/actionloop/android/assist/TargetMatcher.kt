package com.actionloop.android.assist

import java.util.Locale
import kotlin.math.hypot

/**
 * 本地目标匹配器（指导书 8.4）。
 *
 * 初始权重沿用指导书：文本 0.45、层级 0.20、相对位置 0.20、历史页面 0.15。
 * 只有当前帧实际提供的维度参与归一化，避免“缺维度就必然低分”导致的误判。
 *
 * 安全原则：
 * - 可编辑/密码字段永不作为候选；
 * - 最高分低于阈值 → 不做高亮，交给文字步骤；
 * - 前两名分差不足 [AMBIGUITY_MARGIN] → 视为歧义，拒绝指向任何一个（绝不猜相邻按钮）；
 * - 全部计算只在本机完成，不依赖网络或模型。
 */
object TargetMatcher {

    const val W_TEXT = 0.45
    const val W_HIERARCHY = 0.20
    const val W_POSITION = 0.20
    const val W_HISTORY = 0.15

    /** 全局置信下限；目标可提高，但不能降低到该值以下。 */
    const val MIN_CONFIDENCE = 0.65

    /** 前两名分差小于该值即视为歧义，宁可降级文字。 */
    const val AMBIGUITY_MARGIN = 0.05

    /** 包含匹配的上限分：单独出现不足以达标，必须叠加层级/位置/历史证据。 */
    private const val CONTAINS_SCORE = 0.60

    /** 历史命中仅在同一页面哈希内生效，页面变化后不参与评分。 */
    data class History(val pageHash: String, val normalizedText: String)

    data class Match(
        val bounds: NormalizedBounds,
        val text: String,
        val score: Double,
        val breakdown: Breakdown
    )

    data class Breakdown(
        val text: Double,
        val hierarchy: Double?,
        val position: Double?,
        val history: Double?,
        val total: Double
    )

    fun match(frame: GuidanceFrame, target: GuidanceTarget, history: History? = null): Match? {
        if (frame.packageName != target.packageName) return null
        if (frame.isLocked || frame.isSecure) return null
        val labels = target.labels.mapNotNull { normalizeText(it).takeIf(String::isNotEmpty) }
        if (labels.isEmpty()) return null
        val historyUsable = history?.takeIf { it.pageHash == frame.pageHash }

        val scored = frame.textBlocks.asSequence()
            .filterNot { it.isEditable || it.isPassword }
            .filter { it.text.isNotBlank() && it.bounds.isValid() }
            .mapNotNull { block ->
                val normalized = normalizeText(block.text)
                if (normalized.isEmpty()) return@mapNotNull null
                val text = bestTextScore(normalized, labels)
                if (text <= 0.0) return@mapNotNull null
                val hierarchy = hierarchyScore(block, target)
                val position = positionScore(block.bounds, target.expectedBounds)
                val historyScore = historyUsable?.let { if (it.normalizedText == normalized) 1.0 else 0.0 }
                val total = weighted(text, hierarchy, position, historyScore)
                Scored(block, text, hierarchy, position, historyScore, total)
            }
            .sortedByDescending { it.total }
            .toList()

        val best = scored.firstOrNull() ?: return null
        val threshold = maxOf(MIN_CONFIDENCE, target.minConfidence.coerceAtMost(0.95))
        if (best.total < threshold) return null
        // 歧义拒绝：第二名与第一名过于接近时，不选任何候选（含同名按钮重复出现的情况）。
        val second = scored.getOrNull(1)
        if (second != null && best.total - second.total < AMBIGUITY_MARGIN) return null

        return Match(
            bounds = best.block.bounds,
            text = best.block.text,
            score = best.total,
            breakdown = Breakdown(best.text, best.hierarchy, best.position, best.history, best.total)
        )
    }

    /** 文本归一化：小写、去空白与常见标点，保留字母/数字/汉字。 */
    fun normalizeText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch.isWhitespace() -> Unit
                else -> Unit // 标点与符号不参与文本锚点比较
            }
        }
        return sb.toString()
    }

    private fun bestTextScore(candidate: String, labels: List<String>): Double {
        var best = 0.0
        for (label in labels) {
            if (candidate == label) return 1.0
            // 短语包含只给上限分，必须有其他维度支撑才能越过阈值。
            if (label.length >= 2 && (candidate.contains(label) || label.contains(candidate))) {
                best = maxOf(best, CONTAINS_SCORE)
            }
        }
        return best
    }

    private fun hierarchyScore(block: GuidanceFrame.TextBlock, target: GuidanceTarget): Double? {
        val role = target.roleHint
        val viewHint = target.viewIdHint
        val nearby = target.nearbyText
        if (role == null && viewHint == null && nearby == null) return null
        var score = 0.0
        val className = block.className?.lowercase(Locale.ROOT).orEmpty()
        val viewId = block.viewId?.lowercase(Locale.ROOT).orEmpty()
        if (!viewHint.isNullOrBlank()) {
            val hint = viewHint.lowercase(Locale.ROOT)
            if (viewId == hint || viewId.endsWith(":$hint") || viewId.endsWith("/$hint")) score = 1.0
        }
        if (role != null && score < 1.0) {
            val hint = role.lowercase(Locale.ROOT)
            if (className.contains(hint) || viewId.contains(hint)) score = 1.0
        }
        if (nearby != null && score < 1.0) {
            val expected = normalizeText(nearby)
            if (expected.isNotEmpty() && normalizeText(block.parentText.orEmpty()).contains(expected)) {
                score = 1.0
            }
        }
        return score
    }

    private fun positionScore(bounds: NormalizedBounds, expected: NormalizedBounds?): Double? {
        if (expected == null || !expected.isValid()) return null
        val dx = (bounds.x + bounds.width / 2) - (expected.x + expected.width / 2)
        val dy = (bounds.y + bounds.height / 2) - (expected.y + expected.height / 2)
        // 归一化欧氏距离：完全重合 1.0，相距 1 个屏宽/高 0.0。
        return (1.0 - hypot(dx, dy)).coerceIn(0.0, 1.0)
    }

    private fun weighted(
        text: Double,
        hierarchy: Double?,
        position: Double?,
        history: Double?
    ): Double {
        var sum = 0.0
        var weight = 0.0
        sum += text * W_TEXT; weight += W_TEXT
        if (hierarchy != null) { sum += hierarchy * W_HIERARCHY; weight += W_HIERARCHY }
        if (position != null) { sum += position * W_POSITION; weight += W_POSITION }
        if (history != null) { sum += history * W_HISTORY; weight += W_HISTORY }
        return if (weight <= 0.0) 0.0 else sum / weight
    }

    private data class Scored(
        val block: GuidanceFrame.TextBlock,
        val text: Double,
        val hierarchy: Double?,
        val position: Double?,
        val history: Double?,
        val total: Double
    )
}

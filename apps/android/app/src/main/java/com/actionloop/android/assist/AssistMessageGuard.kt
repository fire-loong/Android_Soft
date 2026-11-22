package com.actionloop.android.assist

import java.util.Locale

/**
 * 协助信令本机校验（指导书 8.1 / 8.7 / 8.10）。
 *
 * 服务端 [com.actionloop.api.service.AssistScopes] 是最终裁决者，但 Android 端必须独立复核：
 * 即使协助者/中间人伪造 CLICK、SWIPE、INPUT、COMMIT_PAYMENT 等控制类消息，本机也要拒绝并审计，
 * 绝不下发到任何执行路径。本对象与后端白名单保持一致，作为双保险。
 *
 * 本产品永久不存在远程控制能力：只允许“看 / 标注 / 文字提示”。
 */
object AssistMessageGuard {

    /** 与后端 ALLOWED_EVENTS 完全一致（含指导书 13.5 的 RESUME_REQUEST/HEARTBEAT/QUALITY）。 */
    val ALLOWED_EVENTS: Set<String> = setOf(
        "OFFER", "ANSWER", "ICE_CANDIDATE", "HIGHLIGHT", "HIGHLIGHT_CLEAR",
        "POINTER", "TEXT_HINT", "PAUSE", "RESUME", "RESUME_REQUEST",
        "STOP", "HEARTBEAT", "QUALITY", "META"
    )

    /**
     * 与后端 FORBIDDEN_EVENTS 完全一致；这些能力在本产品中永久不存在。
     * 集合内是「剥离分隔符并大写」的规范形式，匹配前统一 normalize，
     * 使 COMMIT-PAYMENT / commit_payment 同样命中禁止类安全审计，而不是降级成“未知事件”。
     * internal 而非 private：契约单测需要双向比对集合内容（缺项、多项都必须失败）。
     */
    internal val FORBIDDEN_EVENTS: Set<String> = setOf(
        "CLICK", "TAP", "SWIPE", "SCROLL", "INPUT", "TYPE", "KEYEVENT",
        "COMMITPAYMENT", "PAYMENT", "DISPATCHGESTURE", "PERFORMACTION", "CONTROL", "REMOTECONTROL"
    )

    sealed class Result {
        data class Allowed(val eventType: String) : Result()
        /** 明确属于被禁止的控制类事件：必须拒绝并审计，不得执行。 */
        data class Forbidden(val eventType: String, val normalized: String, val reason: String) : Result()
        /** 不在白名单也不在禁止表的未知事件：同样拒绝（默认拒绝原则）。 */
        data class Disallowed(val eventType: String, val reason: String) : Result()
    }

    fun normalize(eventType: String?): String =
        (eventType ?: "").uppercase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "")

    /** 与后端 AssistScopes.isForbiddenEvent 逐字对齐：null 视为禁止。 */
    fun isForbidden(eventType: String?): Boolean {
        if (eventType == null) return true
        return FORBIDDEN_EVENTS.contains(normalize(eventType))
    }

    /**
     * 与后端 AssistScopes.isAllowedEvent 逐字对齐：禁止类先剔除，白名单匹配只用大写（不归一化），
     * 因此连字符/空格变体（如 "HIGH-LIGHT"）不会被误放行，保持默认拒绝。
     */
    fun isAllowed(eventType: String?): Boolean {
        if (eventType == null || isForbidden(eventType)) return false
        return ALLOWED_EVENTS.contains(eventType.uppercase(Locale.ROOT))
    }

    /** 校验入站信令。禁止类与未知类均返回拒绝结果，调用方负责审计并丢弃。 */
    fun validate(eventType: String?): Result {
        if (eventType.isNullOrBlank()) {
            return Result.Disallowed(eventType ?: "(null)", "事件类型为空")
        }
        if (isForbidden(eventType)) {
            return Result.Forbidden(eventType, normalize(eventType), "控制类事件在本产品中永久不存在，已本机拒绝")
        }
        val upper = eventType.uppercase(Locale.ROOT)
        if (ALLOWED_EVENTS.contains(upper)) {
            return Result.Allowed(upper)
        }
        return Result.Disallowed(eventType, "事件类型不在白名单内，已本机拒绝")
    }
}

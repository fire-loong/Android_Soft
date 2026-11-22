package com.actionloop.android.assist

/**
 * 敏感页面判定（指导书 8.3 / 8.5 / 8.10）。
 * 检测到锁屏、FLAG_SECURE、密码框、支付确认、验证码等高风险页面时：
 * 清除高亮、暂停媒体、显示安全说明。宁可误报降级为文字，也绝不在敏感页残留高亮或上传帧。
 */
object SensitivePageDetector {

    private val SENSITIVE_PACKAGES: Set<String> = setOf(
        "com.android.keyguard",
        "com.android.systemui" // 锁屏/系统界面由 isLocked 更精确判定；此处仅兜底
    )

    private val SENSITIVE_KEYWORDS: List<String> = listOf(
        "密码", "口令", "password", "passcode", "pwd",
        "支付", "付款", "转账", "确认支付", "立即支付", "payment", "pay",
        "验证码", "校验码", "短信验证", "verification", "otp",
        "身份证", "证件号", "银行卡", "卡号", "cvv", "安全码"
    )

    data class Result(val sensitive: Boolean, val reason: String?)

    fun evaluate(frame: GuidanceFrame): Result {
        if (frame.isLocked) return Result(true, "锁屏状态")
        if (frame.isSecure) return Result(true, "FLAG_SECURE 安全窗口")
        if (frame.textBlocks.any { it.isPassword || it.isEditable && looksLikePassword(it.text) }) {
            return Result(true, "检测到密码/可编辑敏感字段")
        }
        if (SENSITIVE_PACKAGES.contains(frame.packageName)) {
            return Result(true, "敏感系统界面: ${frame.packageName}")
        }
        for (block in frame.textBlocks) {
            val hit = SENSITIVE_KEYWORDS.firstOrNull { kw ->
                block.text.contains(kw, ignoreCase = true)
            }
            if (hit != null) return Result(true, "命中敏感关键词: $hit")
        }
        return Result(false, null)
    }

    private fun looksLikePassword(text: String): Boolean =
        text.length <= 2 && text.any { it == '•' || it == '●' || it == '*' }
}

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

    /**
     * @param ownPackage 本应用包名。本应用界面由我们控制，安全说明里的“密码 / 支付 / 锁屏”
     *   只是提示文案，不是用户敏感数据；若参与关键词扫描，会让协助/引导页面被自身文案
     *   永久判为敏感页并暂停画面。密码输入框、FLAG_SECURE、锁屏等硬性判定仍然生效。
     * @param launcherPackages 桌面/ launcher 包名集合。桌面上出现的“支付宝”“手机银行”只是
     *   应用名称，不是支付/密码页面，参与关键词扫描会导致一回桌面就误暂停（真机实测）。
     *   锁屏、FLAG_SECURE、密码字段等硬性判定在 launcher 上同样生效。
     */
    fun evaluate(frame: GuidanceFrame, ownPackage: String? = null, launcherPackages: Set<String> = emptySet()): Result {
        if (frame.isLocked) return Result(true, "锁屏状态")
        if (frame.isSecure) return Result(true, "FLAG_SECURE 安全窗口")
        if (frame.textBlocks.any { it.isPassword || it.isEditable && isMaskedInput(it.text) }) {
            return Result(true, "检测到密码/可编辑敏感字段")
        }
        if (SENSITIVE_PACKAGES.contains(frame.packageName)) {
            return Result(true, "敏感系统界面: ${frame.packageName}")
        }
        if (ownPackage != null && frame.packageName == ownPackage) return Result(false, null)
        if (launcherPackages.contains(frame.packageName)) return Result(false, null)
        for (block in frame.textBlocks) {
            val hit = SENSITIVE_KEYWORDS.firstOrNull { kw ->
                block.text.contains(kw, ignoreCase = true)
            }
            if (hit != null) return Result(true, "命中敏感关键词: $hit")
        }
        return Result(false, null)
    }

    /** 掩码占位字符：整段文本只由这些字符组成时，视为已遮蔽的密码/验证码输入。 */
    private val MASK_CHARS = setOf('•', '●', '*', '＊', '·')

    /**
     * 已遮蔽输入判定：部分 App 的自定义密码/验证码框不设置 isPassword 标志，
     * 只把内容渲染成掩码符号。此类页面必须判为敏感（误报只会降级为文字步骤，不产生越权）。
     * 采集侧在记录节点时也调用本方法，把已遮蔽输入直接标记为 isPassword 并清空文本。
     */
    fun isMaskedInput(text: String): Boolean {
        // 去掉空格等分隔符：部分 App 会把掩码渲染成 "• • • •"。
        val compact = text.filterNot { it.isWhitespace() }
        return compact.isNotEmpty() && compact.all { it in MASK_CHARS }
    }
}

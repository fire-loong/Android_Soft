package com.actionloop.android.notifications

/**
 * 本地敏感信息过滤（指导书 15.2）：命中即在设备端丢弃，不进入上传队列、不落盘。
 * 宁可漏采，也不能把验证码/支付信息送到云端。
 */
object NotificationFilter {
    private val SENSITIVE = listOf(
        "验证码", "校验码", "动态码", "动态密码", "密码", "支付", "付款",
        "银行卡", "信用卡", "身份证", "转账", "余额", "扣款", "还款"
    )

    fun isSensitive(text: String): Boolean = SENSITIVE.any { text.contains(it, ignoreCase = true) }

    fun clip(text: String, max: Int = 800): String =
        if (text.length <= max) text else text.take(max)
}

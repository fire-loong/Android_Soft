package com.actionloop.guidancetest

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 受控支付 / FLAG_SECURE 测试页（能力 E 安全验证）。
 *
 * 同时设置 FLAG_SECURE 与支付文案，用于验证引导端在真实支付类安全页面上
 * 立即清除标注、暂停采集并写审计。页面不发生任何真实交易，也不读取任何输入。
 */
class PaymentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 真正的 FLAG_SECURE：MediaProjection 采集到的是黑帧；无障碍侧不依赖窗口安全标志。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "确认支付"
            textSize = 24f
        })
        container.addView(TextView(this).apply {
            text = "金额 ¥0.01（受控测试，不发生真实交易）"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(16))
        })
        container.addView(TextView(this).apply {
            text = "银行卡号 6222 **** **** 0000"
            textSize = 16f
            setPadding(0, 0, 0, dp(24))
        })
        container.addView(Button(this).apply {
            text = "返回（不支付）"
            setOnClickListener { finish() }
        })
        setContentView(container)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

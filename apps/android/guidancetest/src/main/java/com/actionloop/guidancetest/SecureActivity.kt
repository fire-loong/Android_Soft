package com.actionloop.guidancetest

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 独立 FLAG_SECURE 受控页：界面不含敏感关键词、输入框或业务操作。
 * 用于隔离验证采集侧黑帧探测，而不是由文本规则先行触发暂停。
 */
class SecureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "普通说明页"
            textSize = 24f
        })
        container.addView(TextView(this).apply {
            text = "此页仅用于验证系统安全窗口，页面无可填写内容。"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(24))
        })
        container.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        setContentView(container)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

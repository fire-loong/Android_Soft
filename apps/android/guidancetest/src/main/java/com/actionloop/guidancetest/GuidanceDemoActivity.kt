package com.actionloop.guidancetest

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * R16 本地目标高亮演示页（只读引导用）。
 *
 * 刻意只包含“下一步 / 提交 / 取消”三个中性控件，不含支付、密码、验证码等敏感词，
 * 避免演示页本身被判为敏感页而无法验证“高亮 → 用户点击 → 重新匹配 → 低置信降文字”。
 * 页面不执行任何业务操作；点击只更新步骤文字，触发布局/文本变化以生成新 frameId。
 * 稳定 viewId 与主测试页一致（btn_next / btn_submit / btn_cancel），便于本机匹配器命中。
 */
class GuidanceDemoActivity : Activity() {

    private var step = 0
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "ActionLoop 目标匹配演示"
            textSize = 22f
            contentDescription = "目标匹配演示"
        })
        status = TextView(this).apply {
            text = "步骤：0 · 就绪"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(24))
        }
        container.addView(status)
        bind(container, R.id.btn_next, "下一步")
        bind(container, R.id.btn_submit, "提交")
        bind(container, R.id.btn_cancel, "取消")
        setContentView(container)
    }

    private fun bind(parent: LinearLayout, id: Int, label: String) {
        parent.addView(Button(this).apply {
            this.id = id
            text = label
            contentDescription = label
            setOnClickListener {
                step++
                status.text = "步骤：$step · 点击「$label」"
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

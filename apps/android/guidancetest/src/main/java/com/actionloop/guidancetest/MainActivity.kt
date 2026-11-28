package com.actionloop.guidancetest

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 受控测试页（能力 E）。
 *
 * 仅用于验证只读引导的匹配、帧绑定、悬浮层触摸穿透与敏感页处理，不包含任何业务逻辑，
 * 也不自动执行任何操作。所有可引导控件都有稳定 viewId；页面变化（移动按钮、深色、旋转、
 * 滚动）用于验证旧 frameId 标注会被本机丢弃。
 */
class MainActivity : Activity() {

    private var step = 0
    private var dark = false

    private lateinit var status: TextView
    private lateinit var actionContainer: LinearLayout
    private lateinit var moveTarget: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = savedInstanceState?.getBoolean(KEY_DARK) ?: false
        setTheme(
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        step = savedInstanceState?.getInt(KEY_STEP) ?: 0
        status = findViewById(R.id.txt_status)
        actionContainer = findViewById(R.id.action_container)
        moveTarget = findViewById(R.id.move_target)

        bind(R.id.btn_next, "下一步")
        bind(R.id.btn_submit, "提交")
        bind(R.id.btn_cancel, "取消")
        findViewById<Button>(R.id.btn_move).setOnClickListener { moveNextButton() }
        findViewById<Button>(R.id.btn_dialog).setOnClickListener { showConfirmDialog() }
        findViewById<Button>(R.id.btn_password).setOnClickListener { showPasswordDialog() }
        findViewById<Button>(R.id.btn_dark).setOnClickListener { toggleDark() }
        findViewById<Button>(R.id.btn_webview).setOnClickListener { loadWebView() }
        findViewById<Button>(R.id.btn_rotate).setOnClickListener { toggleOrientation() }

        // 支付 / FLAG_SECURE 受控页：验证敏感页撤权即停（指导书 8.3 / 8.5）。
        actionContainer.addView(Button(this).apply {
            text = "支付确认（FLAG_SECURE）"
            contentDescription = "打开受控支付页"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, PaymentActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        actionContainer.addView(Button(this).apply {
            text = "独立安全窗口（黑帧）"
            contentDescription = "打开中性安全窗口"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, SecureActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        actionContainer.addView(Button(this).apply {
            text = "R16 目标匹配演示页"
            contentDescription = "打开中性目标匹配演示页"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, GuidanceDemoActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        buildList()
        updateStatus("就绪")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putBoolean(KEY_DARK, dark)
    }

    private fun bind(id: Int, label: String) {
        findViewById<Button>(id).setOnClickListener {
            step++
            updateStatus("点击「$label」")
        }
    }

    private fun updateStatus(action: String) {
        status.text = "步骤：$step · $action"
    }

    /**
     * 把“下一步”按钮移动到页面下方（改变 bounds）。
     * 受控测试用于验证：控件移动后旧 frameId 的远程标注必须被丢弃，不能停留在旧位置。
     */
    private fun moveNextButton() {
        val button = findViewById<Button>(R.id.btn_next)
        val parent = button.parent as? ViewGroup ?: return
        parent.removeView(button)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (parent.id == R.id.action_container) {
            moveTarget.addView(button, lp)
            updateStatus("「下一步」已移动到页面下方")
        } else {
            actionContainer.addView(button, 0, lp)
            updateStatus("「下一步」已移回操作区")
        }
    }

    private fun showConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("确认提交")
            .setMessage("这是受控测试弹窗，不会提交任何真实数据。")
            .setPositiveButton("确认提交") { _, _ ->
                step++
                updateStatus("弹窗中点击「确认提交」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打开密码框：引导端必须判定为敏感页、清除标注并暂停，绝不读取输入内容。 */
    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = "请输入密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "密码输入框"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("密码输入")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                // 不读取、不记录输入值；仅验证敏感页处理。
                updateStatus("密码框已关闭（内容未记录）")
            }
            .setNegativeButton("取消", null)
            .show()
            .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun toggleDark() {
        dark = !dark
        recreate()
    }

    private fun loadWebView() {
        val container = findViewById<LinearLayout>(R.id.web_container)
        container.removeAllViews()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            loadDataWithBaseURL(
                null,
                "<html><body>" +
                    "<h2>内嵌网页</h2>" +
                    "<p>这是受控测试网页，不加载任何外部资源。</p>" +
                    "<p><a href=\"#\">继续</a></p>" +
                    "</body></html>",
                "text/html",
                "utf-8",
                null
            )
            contentDescription = "受控测试网页"
        }
        container.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
        ))
        updateStatus("已加载内嵌网页")
    }

    private fun buildList() {
        val list = findViewById<LinearLayout>(R.id.list_container)
        for (i in 1..30) {
            val row = TextView(this).apply {
                text = "事项 $i：点击查看详情"
                textSize = 16f
                setPadding(dp(4), dp(12), dp(4), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = "事项 $i"
                setOnClickListener {
                    step++
                    updateStatus("点击「事项 $i」")
                }
            }
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        updateStatus("旋转屏幕")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_STEP = "step"
        private const val KEY_DARK = "dark"
    }
}

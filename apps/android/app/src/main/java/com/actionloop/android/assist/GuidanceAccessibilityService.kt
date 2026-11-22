package com.actionloop.android.assist

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 只读无障碍服务（指导书 8.2 / 8.5 / 安全边界）。
 *
 * 只读取当前窗口控件的文本与屏幕边界，用于：判定敏感页（密码/支付/验证码）、
 * 目标匹配与高亮定位。严格禁止：
 *  - performAction / performGlobalAction（绝不代替用户点击、返回、设置焦点）；
 *  - dispatchGesture（绝不在屏幕上注入手势）；
 *  - 读取并外传密码字段内容（isPassword 节点只记标志，不记文本）。
 *
 * 仅在引导会话活跃时采集，降低无关数据读取。
 */
class GuidanceAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastPushMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val m = GuidanceRuntime.manager(this)
        // 非活跃会话不采集，减少无关读取。
        if (!m.isCollecting()) return
        val now = System.currentTimeMillis()
        if (now - lastPushMs < 350) return
        val root = rootInActiveWindow ?: return
        val blocks = ArrayList<GuidanceFrame.TextBlock>(64)
        collectNodes(root, blocks, 64, 0)
        val dm = resources.displayMetrics
        val normalized = blocks.mapNotNull { b ->
            b.bounds.let { nb ->
                if (nb.isValid()) GuidanceFrame.TextBlock(b.text, nb, b.isEditable, b.isPassword) else null
            }
        }
        val packageName = event?.packageName?.toString() ?: ""
        val pageHash = (packageName + normalized.joinToString("|") { it.text }).hashCode().toString()
        val kg = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        val frame = GuidanceFrame(
            packageName = packageName,
            pageHash = pageHash,
            timestampMs = now,
            textBlocks = normalized,
            isSecure = false, // FLAG_SECURE 由采集侧/窗口标志判定，无障碍无法可靠获取
            isLocked = runCatching { kg.isKeyguardLocked }.getOrDefault(false),
            allowlistedPackage = ALLOWED_PACKAGES.contains(packageName)
        )
        lastPushMs = now
        scope.launch {
            m.onFrame(frame)
            GuidanceRuntime.render(this@GuidanceAccessibilityService)
        }
    }

    /** 递归收集文本节点（只读）。只取 text/contentDescription 与屏幕边界；密码字段不记文本。 */
    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<GuidanceFrame.TextBlock>,
        limit: Int,
        depth: Int
    ) {
        if (out.size >= limit || depth > 40) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val nb = NormalizedBounds.fromPixel(rect, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (nb != null) {
            val isPwd = node.isPassword
            val text = if (isPwd) "" else (node.text ?: node.contentDescription)?.toString()?.take(120) ?: ""
            if (text.isNotBlank() || isPwd) {
                out.add(GuidanceFrame.TextBlock(text, nb, node.isEditable, isPwd))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, limit, depth + 1)
        }
    }

    override fun onInterrupt() { /* 服务被中断时无需特殊处理 */ }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: GuidanceAccessibilityService? = null

        /** 受控允许列表：仅这些包允许进行目标匹配；其它包仍可敏感判定但不做高亮匹配。 */
        val ALLOWED_PACKAGES: Set<String> = setOf(
            "com.actionloop.guidancetest" // 受控测试 App（Chunk 3 引入）
        )
    }
}

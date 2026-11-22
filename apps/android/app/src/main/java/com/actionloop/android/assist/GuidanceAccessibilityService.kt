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
    /** 页面指纹与帧标识：页面静止时复用同一 frameId，任何结构/边界变化才换帧。 */
    private var lastPageFingerprint: String? = null
    private var lastFrameId: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val m = GuidanceRuntime.manager(this)
        // 非活跃会话不采集，减少无关读取。
        if (!m.isCollecting()) return
        // 限流使用单调时钟：墙钟回拨会让采集长期停摆，敏感页判定停留在旧页面。
        val nowMono = android.os.SystemClock.elapsedRealtime()
        if (nowMono - lastPushMs < 350) return
        val root = rootInActiveWindow ?: return
        val blocks = ArrayList<GuidanceFrame.TextBlock>(64)
        collectNodes(root, blocks, 64, 0, parentText = null)
        val dm = resources.displayMetrics
        val normalized = blocks.mapNotNull { b ->
            b.bounds.let { nb ->
                if (nb.isValid()) GuidanceFrame.TextBlock(
                    b.text, nb, b.isEditable, b.isPassword, b.className, b.viewId, b.parentText
                ) else null
            }
        }
        // 包名必须以 rootInActiveWindow 为准：event.packageName 可能来自输入法/系统浮层，
        // 与真实内容不一致时会绕过包白名单校验（用白名单包名承载其它 App 的内容）。
        val packageName = root.packageName?.toString()
            ?: event?.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return
        // 页面指纹只含结构信息（包名/控件 id/文本/尺寸/旋转/边界），不含屏幕位图。
        // 边界参与指纹：控件移动/滚动会改变指纹 → 生成新 frameId，旧标注自动失效。
        val fingerprint = buildString {
            append(packageName).append('|')
                .append(dm.widthPixels).append('x').append(dm.heightPixels).append('|')
                .append(resources.configuration.orientation)
            normalized.forEach { b ->
                append('|').append(b.viewId ?: "-").append(':').append(b.text)
                    .append('@').append((b.bounds.x * 1000).toInt())
                    .append(',').append((b.bounds.y * 1000).toInt())
                    .append(',').append((b.bounds.width * 1000).toInt())
                    .append(',').append((b.bounds.height * 1000).toInt())
            }
        }
        val pageHash = fingerprint.hashCode().toString()
        // 页面静止时复用 frameId：避免每个无障碍事件都换帧导致远程标注永远“过期”；
        // 任何文本/边界/尺寸/旋转变化都会生成新 frameId 并使旧标注失效。
        val frameId = if (fingerprint == lastPageFingerprint && lastFrameId != null) {
            lastFrameId!!
        } else {
            java.util.UUID.randomUUID().toString()
        }
        lastPageFingerprint = fingerprint
        lastFrameId = frameId
        val kg = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        val frame = GuidanceFrame(
            frameId = frameId,
            packageName = packageName,
            pageHash = pageHash,
            timestampMs = System.currentTimeMillis(),
            rotation = displayRotation(),
            densityDpi = dm.densityDpi,
            // 无障碍层拿不到可靠窗口 Insets（0 为保守值）；坐标本身已是全屏归一化，
            // 悬浮层渲染使用 WindowMetrics 尺寸，显示变化时由采集循环触发 onDisplayChanged。
            insets = GuidanceFrame.Insets(0, 0, 0, 0),
            textBlocks = normalized,
            isSecure = false, // FLAG_SECURE 由采集侧/窗口标志判定，无障碍无法可靠获取
            isLocked = runCatching { kg.isKeyguardLocked }.getOrDefault(false),
            allowlistedPackage = ALLOWED_PACKAGES.contains(packageName)
        )
        lastPushMs = nowMono
        scope.launch {
            m.onFrame(frame)
            GuidanceRuntime.render(this@GuidanceAccessibilityService)
            // 只发布 frameId/pageHash，不含画面与控件文本；未发起协助会话时为空操作。
            AssistSignalClient.publishFrame(this@GuidanceAccessibilityService, frame)
        }
    }

    /**
     * 递归收集文本节点（只读）。只取 text/contentDescription、类名、资源 id 与屏幕边界。
     * 安全：密码字段与已遮蔽输入只记 [GuidanceFrame.TextBlock.isPassword] 标志，不记文本；
     * 可编辑字段的父容器文本同样不回传，避免把用户输入经父节点带出。
     */
    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<GuidanceFrame.TextBlock>,
        limit: Int,
        depth: Int,
        parentText: String?
    ) {
        if (out.size >= limit || depth > 40) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val nb = NormalizedBounds.fromPixel(rect, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (nb != null) {
            val raw = (node.text ?: node.contentDescription)?.toString()?.take(120) ?: ""
            // 可编辑字段的内容一律不进入帧（避免把用户输入带出）；已遮蔽输入直接标记为密码。
            val masked = node.isEditable && SensitivePageDetector.isMaskedInput(raw)
            val isPwd = node.isPassword || masked
            val text = if (node.isEditable || isPwd) "" else raw
            if (text.isNotBlank() || isPwd) {
                out.add(
                    GuidanceFrame.TextBlock(
                        text = text,
                        bounds = nb,
                        isEditable = node.isEditable,
                        isPassword = isPwd,
                        className = node.className?.toString()?.take(80),
                        viewId = node.viewIdResourceName?.take(160),
                        parentText = parentText
                    )
                )
            }
        }
        val childParentText = if (node.isPassword || node.isEditable) null
        else (node.text ?: node.contentDescription)?.toString()?.take(60)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, limit, depth + 1, childParentText)
        }
    }

    /** 屏幕旋转（0/90/180/270），用于帧快照与显示变化判定。 */
    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = runCatching {
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    }.getOrDefault(0)

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

package com.actionloop.android.assist

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    /** 搜索聚合页转场宽限上限：覆盖搜索窗口从出现到可枚举的时间，避免转场帧误暂停。 */
    private val SEARCH_TRANSITION_GRACE_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastPushMs = 0L
    /** 页面指纹与帧标识：页面静止时复用同一 frameId，任何结构/边界变化才换帧。 */
    private var lastPageFingerprint: String? = null
    private var lastFrameId: String? = null

    /** 搜索页窗口探测缓存：避免每个无障碍事件都遍历所有窗口层级。 */
    private var searchProbePkg: String? = null
    private var searchProbeAtMs = 0L
    private var searchProbeValue = false

    /** 搜索聚合页转场宽限起点：仅当窗口层级暂时看不到该包时启用，成功探测到搜索框即清除。 */
    private val searchGraceStartMs = HashMap<String, Long>()

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
        // 只对系统搜索聚合包做窗口层级探测：转场帧的 root 可能暂时不含搜索框，
        // 但窗口层级仍能证明这是搜索聚合页，避免“搜索支付宝”单帧误暂停。
        val searchPkg = SensitivePageDetector.isSearchSurfacePackage(packageName)
        val searchInput = searchPkg && hasSearchInputInWindows(packageName, nowMono)
        if (searchInput) searchGraceStartMs.remove(packageName)
        val searchTransition = searchPkg && !searchInput &&
            isSearchSurfaceTransition(packageName, nowMono)
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
            allowlistedPackage = ALLOWED_PACKAGES.contains(packageName),
            hasSearchInput = searchInput,
            // 刚打开系统搜索时窗口层级可能尚未出现该包（真机实测），由短时宽限兜住转场帧。
            searchSurfaceTransition = searchTransition
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
        val raw = (node.text ?: node.contentDescription)?.toString()?.take(120) ?: ""
        val className = node.className?.toString()?.take(80)
        val viewId = node.viewIdResourceName?.take(160)
        // 部分 OEM 的搜索框（如 vivo 全局搜索）不把 isEditable 暴露为 true，但类名仍是
        // EditText；两者都作为“输入框存在”的结构证据，供搜索聚合页语义判定使用。
        val isEdit = node.isEditable || className?.contains("EditText", ignoreCase = true) == true
        // 只保留“搜索框”或密码/遮蔽输入的结构证据：普通表单输入框不进入帧，
        // 既避免用户输入经父文本带出，也不占用 64 条文本预算。
        val isSearchInput = isEdit && (
            viewId?.contains("search", ignoreCase = true) == true ||
                className?.contains("search", ignoreCase = true) == true
            )
        // 可编辑字段的内容一律不进入帧；已遮蔽输入直接标记为密码。
        // 搜索框排除在外：搜索词本身可能就是全掩码字符（如“***”），不能据此判为密码页。
        val masked = !isSearchInput && isEdit && SensitivePageDetector.isMaskedInput(raw)
        val isPwd = node.isPassword || masked
        val text = if (isEdit || isPwd) "" else raw
        if (nb != null && (text.isNotBlank() || isPwd || isSearchInput)) {
            out.add(
                GuidanceFrame.TextBlock(
                    text = text,
                    bounds = nb,
                    isEditable = isPwd || isSearchInput,
                    isPassword = isPwd,
                    className = className,
                    viewId = viewId,
                    parentText = parentText
                )
            )
        }
        // 所有可编辑/密码字段的子节点一律不继承父文本：OEM 自定义输入框可能 isEditable=false，
        // 必须用上面归一化后的 isEdit/isPwd 判断，避免用户输入（甚至未打码密码）经 parentText 带出。
        val childParentText = if (isPwd || isEdit) null
        else (node.text ?: node.contentDescription)?.toString()?.take(60)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, limit, depth + 1, childParentText)
        }
    }

    /**
     * 扫描所有应用窗口，判断指定包是否存在“顶部搜索框”。
     * 只对系统搜索类包调用（调用方已过滤）；缓存 500ms，避免每个事件都遍历窗口层级。
     */
    private fun hasSearchInputInWindows(pkg: String, nowMono: Long): Boolean {
        if (pkg == searchProbePkg && nowMono - searchProbeAtMs < 500) return searchProbeValue
        val value = runCatching {
            windows.any { w ->
                // 真机实测：vivo 全局搜索窗口并不总是 TYPE_APPLICATION（dumpsys 中为独立系统层），
                // 只认 TYPE_APPLICATION 会在打开搜索的转场帧漏判，进而把“支付”关键词误判为敏感页。
                // 这里以“窗口根节点包名等于目标搜索包”为准，只读同包窗口；辅助层窗口（本应用）跳过。
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@any false
                val r = w.root ?: return@any false
                if (r.packageName?.toString() != pkg) return@any false
                containsSearchInput(r, 0, intArrayOf(200))
            }
        }.getOrDefault(false)
        searchProbePkg = pkg
        searchProbeAtMs = nowMono
        searchProbeValue = value
        return value
    }

    /**
     * 搜索聚合页转场宽限（实测 vivo `com.vivo.globalsearch`）：搜索窗口刚出现时，
     * `windows` 层级里还没有该包（只有 launcher/upslide 等系统层），此时既拿不到搜索框结构，
     * 也不应把结果词（如“支付”）当作敏感页。只对搜索聚合包、且探测不到该包窗口时启用，
     * 每包最多 [SEARCH_TRANSITION_GRACE_MS]；密码/FLAG_SECURE/锁屏等硬判定在此之前执行，不受影响。
     */
    private fun isSearchSurfaceTransition(pkg: String, nowMono: Long): Boolean {
        val start = searchGraceStartMs.getOrPut(pkg) {
            AssistSecurityAudit.record(this, "SEARCH_SURFACE_GRACE", pkg)
            nowMono
        }
        return nowMono - start < SEARCH_TRANSITION_GRACE_MS
    }

    /** 深度/节点预算受限的只读搜索框探测：可编辑且类名/资源 id 含 search，位于页面上部。 */
    private fun containsSearchInput(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray
    ): Boolean {
        if (depth > 40 || budget[0] <= 0) return false
        budget[0] -= 1
        val className = node.className?.toString()
        val isEdit = node.isEditable || className?.contains("EditText", ignoreCase = true) == true
        val viewId = node.viewIdResourceName
        if (isEdit &&
            (viewId?.contains("search", ignoreCase = true) == true ||
                className?.contains("search", ignoreCase = true) == true)
        ) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val height = resources.displayMetrics.heightPixels
            if (height > 0 && rect.top.toFloat() / height <= 0.25f) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsSearchInput(child, depth + 1, budget)) return true
        }
        return false
    }

    /** 屏幕旋转（0/90/180/270），用于帧快照与显示变化判定。 */
    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = runCatching {
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    }.getOrDefault(0)

    override fun onInterrupt() { /* 服务被中断时无需特殊处理 */ }

    /**
     * 用户在系统设置里撤销只读无障碍时立即生效（指导书 8.10 撤权即停）：
     * 撤销后本机不再产生任何帧，若继续显示“会话进行中”会让用户误以为仍有引导，
     * 因此清除标注并暂停会话（随会话暂停，远程画面共享也一并暂停，恢复需重新同意）。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        // 不能用服务自身的 scope：onUnbind 后系统会紧接着 onDestroy → scope.cancel()，
        // 未开始执行的协程会被直接取消，暂停回调静默丢失（真机审查发现）。
        val ctx = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            GuidanceRuntime.manager(ctx).pause("a11y_revoked")
            GuidanceRuntime.render(ctx)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: GuidanceAccessibilityService? = null

        /** 受控允许列表：仅这些包允许进行目标匹配；其它包仍可敏感判定但不做高亮匹配。 */
        val ALLOWED_PACKAGES: Set<String> = setOf(
            GUIDANCE_TEST_APP_PACKAGE // 受控测试 App（Chunk 3 引入）
        )
    }
}

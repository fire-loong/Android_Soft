package com.actionloop.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感页面判定单测（指导书 8.3 / 8.5 / 8.10）。
 * 原则：宁可误报降级为文字步骤，也不得在敏感页残留高亮或上传帧。
 */
class SensitivePageDetectorTest {

    private fun frame(
        pkg: String = "com.actionloop.guidancetest",
        blocks: List<GuidanceFrame.TextBlock> = emptyList(),
        secure: Boolean = false,
        locked: Boolean = false,
        hasSearchInput: Boolean = false,
        searchSurfaceTransition: Boolean = false
    ) = GuidanceFrame(
        frameId = "frame-1",
        packageName = pkg,
        pageHash = "hash-1",
        timestampMs = 1_700_000_000_000,
        textBlocks = blocks,
        isSecure = secure,
        isLocked = locked,
        allowlistedPackage = true,
        hasSearchInput = hasSearchInput,
        searchSurfaceTransition = searchSurfaceTransition
    )

    private fun block(
        text: String,
        editable: Boolean = false,
        password: Boolean = false,
        viewId: String? = null,
        className: String? = null,
        y: Double = 0.1
    ) = GuidanceFrame.TextBlock(
        text = text,
        bounds = NormalizedBounds(0.1, y, 0.2, 0.05),
        isEditable = editable,
        isPassword = password,
        className = className,
        viewId = viewId
    )

    @Test
    fun `锁屏与FLAG_SECURE立即判为敏感`() {
        assertTrue(SensitivePageDetector.evaluate(frame(locked = true)).sensitive)
        assertTrue(SensitivePageDetector.evaluate(frame(secure = true)).sensitive)
    }

    @Test
    fun `密码字段判为敏感且不依赖其文本内容`() {
        val masked = frame(blocks = listOf(block("••••••", editable = true)))
        assertTrue(SensitivePageDetector.evaluate(masked).sensitive)
        val spaced = frame(blocks = listOf(block("• • • •", editable = true)))
        assertTrue("带分隔符的掩码也必须命中", SensitivePageDetector.evaluate(spaced).sensitive)
        val empty = frame(blocks = listOf(block("", editable = true, password = true)))
        assertTrue(SensitivePageDetector.evaluate(empty).sensitive)
    }

    @Test
    fun `支付与验证码关键词判为敏感`() {
        for (text in listOf("确认支付", "立即支付", "请输入短信验证码", "Password", "身份证号", "CVV")) {
            val result = SensitivePageDetector.evaluate(frame(blocks = listOf(block(text))))
            assertTrue("应判为敏感: $text", result.sensitive)
        }
    }

    @Test
    fun `系统锁屏包名兜底判为敏感`() {
        assertTrue(SensitivePageDetector.evaluate(frame(pkg = "com.android.keyguard")).sensitive)
        assertTrue(SensitivePageDetector.evaluate(frame(pkg = "com.android.systemui")).sensitive)
    }

    @Test
    fun `普通可点击文本页不判为敏感`() {
        val normal = frame(
            blocks = listOf(
                block("材料上传"),
                block("下一步"),
                block("课程设计材料提交")
            )
        )
        val result = SensitivePageDetector.evaluate(normal)
        assertFalse(result.sensitive)
        assertEquals(null, result.reason)
    }

    @Test
    fun `普通可编辑输入框不因可编辑而被误判`() {
        val search = frame(blocks = listOf(block("搜索课程名称", editable = true)))
        assertFalse(SensitivePageDetector.evaluate(search).sensitive)
    }

    @Test
    fun `桌面应用名不触发支付关键词误判`() {
        val launcher = "com.bbk.launcher2"
        val desktop = frame(pkg = launcher, blocks = listOf(block("支付宝"), block("手机银行")))
        assertFalse("桌面只显示应用名，不应按敏感页处理",
            SensitivePageDetector.evaluate(desktop, launcherPackages = setOf(launcher)).sensitive)
        // 同样的文字出现在第三方页面仍然必须判为敏感。
        assertTrue(SensitivePageDetector.evaluate(
            frame(pkg = "com.example.shop", blocks = listOf(block("立即支付"))),
            launcherPackages = setOf(launcher)
        ).sensitive)
        // 硬性判定在桌面上同样生效。
        assertTrue(SensitivePageDetector.evaluate(
            frame(pkg = launcher, secure = true), launcherPackages = setOf(launcher)
        ).sensitive)
        assertTrue(SensitivePageDetector.evaluate(
            frame(pkg = launcher, locked = true), launcherPackages = setOf(launcher)
        ).sensitive)
    }

    @Test
    fun `本应用自身的说明文案不因安全关键词误判为敏感`() {
        val text = "检测到密码 / 支付 / 锁屏会立即清除高亮并停止"
        val own = frame(pkg = "com.actionloop.android", blocks = listOf(block(text)))
        assertFalse(SensitivePageDetector.evaluate(own, ownPackage = "com.actionloop.android").sensitive)
        // 同类文案出现在第三方页面仍必须判为敏感，例外只针对本应用自身界面。
        val other = frame(pkg = "com.example.bank", blocks = listOf(block(text)))
        assertTrue(SensitivePageDetector.evaluate(other, ownPackage = "com.actionloop.android").sensitive)
    }

    @Test
    fun `本应用界面的密码输入框仍然判为敏感`() {
        val own = frame(
            pkg = "com.actionloop.android",
            blocks = listOf(block("", editable = true, password = true))
        )
        assertTrue(SensitivePageDetector.evaluate(own, ownPackage = "com.actionloop.android").sensitive)
    }

    private fun searchFrame(vararg blocks: GuidanceFrame.TextBlock) =
        frame(pkg = "com.vivo.globalsearch", blocks = blocks.toList())

    /** 搜索聚合页：窗口层级已确认存在顶部搜索框（转场帧即使 root 不含搜索框也生效）。 */
    private fun searchSurfaceFrame(vararg blocks: GuidanceFrame.TextBlock) =
        frame(pkg = "com.vivo.globalsearch", blocks = blocks.toList(), hasSearchInput = true)

    @Test
    fun `转场帧 root 暂不含搜索框时仍按搜索页处理`() {
        // 真机上搜索页转场时 rootInActiveWindow 可能暂时是结果 WebView，不含搜索框；
        // 采集侧用窗口层级确认搜索框，避免单帧误暂停。
        val result = SensitivePageDetector.evaluate(
            searchSurfaceFrame(block("支付宝"), block("钱包与支付"))
        )
        assertFalse(result.sensitive)
    }

    @Test
    fun `搜索窗口尚未可枚举时的转场帧不按关键词暂停`() {
        // 真机实测：vivo 搜索窗口刚出现时 windows 层级里还只有 launcher/upslide 等系统层，
        // 采集侧用短时宽限标记该帧，避免把结果页的“支付”当成敏感页。
        val result = SensitivePageDetector.evaluate(
            frame(
                pkg = "com.vivo.globalsearch",
                blocks = listOf(block("支付宝"), block("钱包与支付")),
                searchSurfaceTransition = true
            )
        )
        assertFalse("搜索转场宽限内不应暂停", result.sensitive)
    }

    @Test
    fun `非搜索包的转场标志不能豁免关键词`() {
        val result = SensitivePageDetector.evaluate(
            frame(
                pkg = "com.example.shop",
                blocks = listOf(block("确认支付")),
                searchSurfaceTransition = true
            )
        )
        assertTrue("宽限只对搜索聚合包生效", result.sensitive)
        assertEquals("命中敏感关键词: 支付", result.reason)
    }

    @Test
    fun `搜索转场宽限下硬性判定仍然生效`() {
        assertTrue(
            SensitivePageDetector.evaluate(
                frame(pkg = "com.vivo.globalsearch", blocks = listOf(block("支付宝")),
                    secure = true, searchSurfaceTransition = true)
            ).sensitive
        )
        assertTrue(
            SensitivePageDetector.evaluate(
                frame(pkg = "com.vivo.globalsearch", blocks = listOf(block("支付宝")),
                    locked = true, searchSurfaceTransition = true)
            ).sensitive
        )
    }

    @Test
    fun `全局搜索含搜索框时不按关键词暂停`() {
        // 真机搜索“支付宝”会同时出现应用名与第三方摘要（电子支付/网上支付/转账收款/钱包与支付）。
        val result = SensitivePageDetector.evaluate(
            searchSurfaceFrame(
                block("支付宝"),
                block("支付宝，全球领先的独立第三方支付平台，提供电子支付/网上支付/安全支付体验，及转账收款等生活服务。"),
                block("钱包与支付")
            )
        )
        assertFalse("搜索框仍在页面上，属搜索聚合页，不应按交易页暂停", result.sensitive)
    }

    @Test
    fun `离开搜索框后同包名内容页恢复关键词判定`() {
        // 搜索框消失（进入搜索 App 内的具体服务/内容页）后不能继续豁免。
        val result = SensitivePageDetector.evaluate(
            searchFrame(block("支付宝，全球领先的独立第三方支付平台，提供电子支付/网上支付体验"))
        )
        assertTrue(result.sensitive)
        assertEquals("命中敏感关键词: 支付", result.reason)
    }

    @Test
    fun `搜索包内的普通输入框不能豁免支付关键词`() {
        // 只认“顶部搜索框”；搜索 App 内的支付/转账页即便有金额/备注输入框也必须暂停。
        val result = SensitivePageDetector.evaluate(
            searchFrame(
                block("", editable = true, viewId = "com.vivo.globalsearch:id/amount_input", y = 0.6),
                block("确认支付")
            )
        )
        assertTrue(result.sensitive)
        assertEquals("命中敏感关键词: 支付", result.reason)
    }

    @Test
    fun `页面中部的搜索样式输入框也不豁免`() {
        val result = SensitivePageDetector.evaluate(
            searchFrame(
                block("", editable = true, viewId = "com.vivo.globalsearch:id/search_edit_text", y = 0.8),
                block("确认支付")
            )
        )
        assertTrue(result.sensitive)
    }

    @Test
    fun `搜索页上的硬性判定仍然生效`() {
        assertTrue(
            SensitivePageDetector.evaluate(searchSurfaceFrame(block("支付宝")).copy(isSecure = true)).sensitive
        )
        assertTrue(
            SensitivePageDetector.evaluate(searchSurfaceFrame(block("支付宝")).copy(isLocked = true)).sensitive
        )
        assertTrue(
            SensitivePageDetector.evaluate(
                searchFrame(block("", editable = true, password = true))
            ).sensitive
        )
    }

    @Test
    fun `同样的支付摘要出现在普通内容页仍然敏感`() {
        val content = frame(
            pkg = "com.example.shop",
            blocks = listOf(block("支付宝，全球领先的独立第三方支付平台，提供电子支付/网上支付体验"))
        )
        assertTrue(SensitivePageDetector.evaluate(content).sensitive)
    }
}

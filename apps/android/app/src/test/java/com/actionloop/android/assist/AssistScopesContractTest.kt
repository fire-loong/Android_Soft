package com.actionloop.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨端契约测试（指导书 13.5）：Android 本机白名单/禁止表必须与后端 AssistScopes 逐字一致。
 *
 * 后端是最终裁决者，Android 是双保险。两边一旦漂移，就出现「服务端放行、端上拒绝」或
 * 更危险的「端上放行、服务端以为已拦」，因此用单测锁死集合内容，而不是靠人工比对。
 */
class AssistScopesContractTest {

    private fun locateAssistScopes(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(dir, "services/api/src/main/java/com/actionloop/api/service/AssistScopes.java")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile ?: return@repeat
        }
        throw AssertionError("未找到后端 AssistScopes.java，工作目录=${System.getProperty("user.dir")}；" +
            "该契约测试要求 monorepo 布局（services/api/...）")
    }

    private fun parseSet(source: String, field: String): Set<String> {
        val match = Regex("""$field\s*=\s*Set\.of\(([\s\S]*?)\);""").find(source)
            ?: throw AssertionError("AssistScopes.java 中未找到字段 $field")
        return Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `AllOWED_EVENTS 与后端完全一致`() {
        val source = locateAssistScopes().readText()
        val backendAllowed = parseSet(source, "ALLOWED_EVENTS")
        assertEquals(backendAllowed, AssistMessageGuard.ALLOWED_EVENTS)
    }

    @Test
    fun `禁止事件表与后端完全一致（双向）`() {
        val source = locateAssistScopes().readText()
        val backendForbidden = parseSet(source, "FORBIDDEN_EVENTS")
        // 双向比对：后端有而端上缺失、端上多出后端没有的禁止项，都必须失败。
        assertEquals(backendForbidden, AssistMessageGuard.FORBIDDEN_EVENTS)
        assertTrue(backendForbidden.isNotEmpty())
    }

    @Test
    fun `指导书13_5列出的允许事件全部在白名单内`() {
        // 13.5 原文：OFFER、ANSWER、ICE_CANDIDATE、HIGHLIGHT、TEXT_HINT、PAUSE、RESUME_REQUEST、STOP、HEARTBEAT、QUALITY
        val required = setOf(
            "OFFER", "ANSWER", "ICE_CANDIDATE", "HIGHLIGHT", "TEXT_HINT",
            "PAUSE", "RESUME_REQUEST", "STOP", "HEARTBEAT", "QUALITY"
        )
        assertTrue(
            "缺少 13.5 要求的允许事件: ${required - AssistMessageGuard.ALLOWED_EVENTS}",
            AssistMessageGuard.ALLOWED_EVENTS.containsAll(required)
        )
        // 后端同集合：DB CHECK 约束已允许这些事件，服务层不得比 DB 更窄。
        val backendAllowed = parseSet(locateAssistScopes().readText(), "ALLOWED_EVENTS")
        assertTrue(backendAllowed.containsAll(required))
    }
}

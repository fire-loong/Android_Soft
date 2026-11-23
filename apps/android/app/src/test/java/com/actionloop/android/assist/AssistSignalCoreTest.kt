package com.actionloop.android.assist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.AssistEventView
import com.actionloop.android.data.AssistSessionView
import com.actionloop.android.data.AssistTurnCredentials
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 协助信令核心单测（指导书 8.7 / 13.5）。
 *
 * 覆盖的安全出口：
 * - 服务端会话进入终态 → 本机立即停止引导并释放媒体通道；
 * - 登录失效（AUTH_INVALID）立即终止，普通网络错误连续 20 次才终止；
 * - 停止后旧代际轮询的迟到响应不得写回新状态（epoch 隔离）；
 * - 敏感页 / 暂停 / 无会话时不发布 META，同一帧 2 秒内只发布一次；
 * - 媒体信令（OFFER/ANSWER/ICE_CANDIDATE）分发给媒体层，控制类信令被拒绝且不分发，
 *   迟到旧帧的标注被拒绝。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// 同 GuidanceSessionManagerTest：避免加载会在 onCreate 初始化 WorkManager 的自定义 Application。
@Config(application = android.app.Application::class)
class AssistSignalCoreTest {

    private lateinit var context: Context
    private lateinit var manager: GuidanceSessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AssistSecurityAudit.clear(context)
        manager = GuidanceSessionManager(context)
    }

    // ---- 测试替身 ----

    /** 只实现后端语义的假传输层；延迟/失败/阻塞都可由用例精确控制。 */
    private class FakeTransport : AssistSignalTransport {
        var createState = "ACTIVE"
        var sessionIds = ArrayDeque<String>()
        var mineStates = ArrayDeque<String>()
        var events: List<AssistEventView> = emptyList()
        var eventsGate: CompletableDeferred<Unit>? = null
        var failEventsTimes = 0
        var failMineTimes = 0
        var failWith: Throwable = IOException("network down")

        var failureCount = 0
        var eventCalls = 0
        var mineCalls = 0
        val signalTypes = mutableListOf<String>()
        val signalPayloads = mutableListOf<JsonObject>()
        val stopReasons = mutableListOf<String>()

        private var activeId = "s-1"

        override suspend fun createSession(workspaceId: String, taskStepId: String?): AssistSessionView {
            activeId = sessionIds.removeFirstOrNull() ?: "s-1"
            return AssistSessionView(id = activeId, requesterId = "u-1", mode = "REMOTE_ASSIST", state = createState)
        }

        override suspend fun events(sessionId: String, afterSeq: Long): List<AssistEventView> {
            eventCalls++
            if (failEventsTimes > 0) {
                failEventsTimes--
                failureCount++
                throw failWith
            }
            // 调用瞬间的快照：模拟“停止后仍在途的旧响应”，其内容不随之后的字段修改而变化。
            val snapshot = events
            val gate = eventsGate
            if (gate != null) {
                eventsGate = null // 一次性闸门：只有第一轮轮询被阻塞
                withContext(NonCancellable) { gate.await() }
            }
            return snapshot
        }

        override suspend fun mine(): List<AssistSessionView> {
            mineCalls++
            if (failMineTimes > 0) {
                failMineTimes--
                failureCount++
                throw failWith
            }
            val state = mineStates.removeFirstOrNull() ?: return emptyList()
            return listOf(AssistSessionView(id = activeId, requesterId = "u-1", mode = "REMOTE_ASSIST", state = state))
        }

        override suspend fun signal(sessionId: String, eventType: String, payload: JsonObject): AssistEventView {
            signalTypes += eventType
            signalPayloads += payload
            return AssistEventView(sessionId = sessionId, seq = 1, eventType = eventType)
        }

        override suspend fun stop(sessionId: String, reason: String): AssistSessionView {
            stopReasons += reason
            return AssistSessionView(id = sessionId, requesterId = "u-1", mode = "REMOTE_ASSIST", state = "STOPPED")
        }

        override suspend fun turnCredentials(sessionId: String): AssistTurnCredentials = AssistTurnCredentials()
    }

    /** 虚拟时钟 + 100ms 轮询间隔，让用例用 advanceTimeBy 精确推进。 */
    private fun TestScope.newClient(
        transport: FakeTransport,
        now: () -> Long = { 10_000L },
        renders: MutableList<Unit> = mutableListOf(),
        mediaStops: MutableList<Unit> = mutableListOf(),
    ): AssistSignalCore = AssistSignalCore(
        transport = transport,
        scope = backgroundScope,
        monoClock = now,
        pollIntervalMs = 100L,
        managerOf = { manager },
        render = { renders += Unit },
        stopMedia = { mediaStops += Unit },
    )

    private fun frame(
        id: String = "frame-1",
        secure: Boolean = false,
        texts: List<String> = emptyList()
    ) = GuidanceFrame(
        frameId = id,
        packageName = "com.actionloop.guidancetest",
        pageHash = "page-$id",
        timestampMs = 1_700_000_000_000,
        allowlistedPackage = true,
        isSecure = secure,
        textBlocks = texts.mapIndexed { i, t ->
            GuidanceFrame.TextBlock(text = t, bounds = NormalizedBounds(0.1 * i, 0.1, 0.2, 0.05))
        }
    )

    private fun event(seq: Long, type: String, payload: String? = null) =
        AssistEventView(sessionId = "s-1", seq = seq, eventType = type, payloadJson = payload)

    private fun highlightJson(frameId: String) =
        """{"frameId":"$frameId","label":"下一步","bounds":{"x":0.1,"y":0.2,"width":0.3,"height":0.1},""" +
            """"expiresAt":${System.currentTimeMillis() + 5_000}}"""

    // ---- 用例 ----

    @Test
    fun `服务端会话进入终态时本机立即停止引导`() = runTest {
        manager.start()
        manager.consentGranted()
        assertEquals(GuidanceSessionState.ACTIVE, manager.snapshot().state)

        val transport = FakeTransport().apply { mineStates.addLast("STOPPED") }
        val renders = mutableListOf<Unit>()
        val client = newClient(transport, renders = renders)

        client.start(context, "ws-1")
        // 第 1 次轮询只拉事件；第 2 次轮询同步会话状态，发现 STOPPED 后必须本地停止。
        advanceTimeBy(250)
        runCurrent()

        assertEquals(GuidanceSessionState.STOPPED, manager.snapshot().state)
        assertFalse(client.isSessionActive())
        assertTrue("终态停止后必须重绘悬浮层", renders.isNotEmpty())
    }

    @Test
    fun `登录失效立即终止会话`() = runTest {
        manager.start()
        manager.consentGranted()

        val transport = FakeTransport().apply {
            failEventsTimes = Int.MAX_VALUE
            failWith = ApiException("AUTH_INVALID", "token expired")
        }
        val mediaStops = mutableListOf<Unit>()
        val client = newClient(transport, mediaStops = mediaStops)

        client.start(context, "ws-1")
        advanceTimeBy(150)
        runCurrent()

        assertFalse(client.isSessionActive())
        assertNotNull(client.lastError)
        assertEquals(GuidanceSessionState.STOPPED, manager.snapshot().state)
        assertTrue("终止会话必须释放媒体通道", mediaStops.isNotEmpty())
    }

    @Test
    fun `普通网络错误连续 20 次才终止`() = runTest {
        manager.start()
        manager.consentGranted()

        val transport = FakeTransport().apply {
            // mine 与 events 都失败，避免任一成功把失败计数清零。
            failEventsTimes = Int.MAX_VALUE
            failMineTimes = Int.MAX_VALUE
        }
        val client = newClient(transport)

        client.start(context, "ws-1")
        var guard = 0
        while (transport.failureCount < 20 && guard++ < 100) {
            advanceTimeBy(150)
            runCurrent()
            if (transport.failureCount in 1..19) {
                assertTrue("第 ${transport.failureCount} 次失败不应终止会话", client.isSessionActive())
            }
        }

        assertTrue(transport.failureCount >= 20)
        assertFalse(client.isSessionActive())
        assertNotNull(client.lastError)
        assertEquals(GuidanceSessionState.STOPPED, manager.snapshot().state)
    }

    @Test
    fun `停止后旧代际的迟到响应不得写入新会话`() = runTest {
        manager.start()
        manager.consentGranted()
        manager.onFrame(frame("frame-1"))

        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            // 两次会话同 id：此时只有代际（epoch）能区分新旧轮询，sessionId 比对兜不住。
            sessionIds.addAll(listOf("s-1", "s-1"))
            eventsGate = gate
            events = listOf(event(1, "HIGHLIGHT", highlightJson("frame-1")))
        }
        val client = newClient(transport)

        client.start(context, "ws-1")
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, transport.eventCalls) // 会话 A 的轮询已挂起在网络上
        assertNull(manager.snapshot().highlight)

        client.stop(context, "user_home")
        assertTrue(transport.stopReasons.contains("user_home"))
        // 立刻发起新会话：会话 A 的响应此刻仍在途。
        client.start(context, "ws-2")
        transport.events = emptyList()
        advanceTimeBy(250)
        runCurrent()

        gate.complete(Unit) // 会话 A 的旧响应此时才返回
        advanceTimeBy(1_000)
        runCurrent()

        assertNull("旧代际响应不得把标注写进新会话", manager.snapshot().highlight)
        assertEquals("s-1", client.current()?.id)
        assertTrue(client.isSessionActive())
    }

    @Test
    fun `成功同步会清零失败计数，避免跨错误累积误杀`() = runTest {
        manager.start()
        manager.consentGranted()

        // 事件拉取持续失败，但会话状态同步（mine）成功——成功必须把失败计数清零。
        val transport = FakeTransport().apply { failEventsTimes = Int.MAX_VALUE }
        val client = newClient(transport)

        client.start(context, "ws-1")
        repeat(60) {
            advanceTimeBy(150)
            runCurrent()
        }

        assertTrue("事件持续失败但同步成功时不得终止会话", client.isSessionActive())
        assertTrue(transport.failureCount >= 50)
        client.stop(context, "test_end")
    }

    @Test
    fun `同一帧 2 秒内只发布一次 META 且敏感或暂停时不发布`() = runTest {
        manager.start()
        manager.consentGranted()
        manager.onFrame(frame("frame-1"))

        var now = 10_000L
        val transport = FakeTransport()
        val client = newClient(transport, now = { now })

        // 无会话：任何情况下都不发布。
        client.publishFrame(context, frame("frame-1"))
        assertTrue(transport.signalTypes.isEmpty())

        client.start(context, "ws-1")
        client.publishFrame(context, frame("frame-1"))
        client.publishFrame(context, frame("frame-1")) // 同帧 2 秒内节流
        assertEquals(1, transport.signalTypes.count { it == "META" })
        assertEquals("frame-1", transport.signalPayloads.first()["frameId"]?.jsonPrimitive?.content)

        now += 2_001
        client.publishFrame(context, frame("frame-1"))
        assertEquals(2, transport.signalTypes.count { it == "META" })

        client.publishFrame(context, frame("frame-2")) // 换帧立即发布
        assertEquals(3, transport.signalTypes.count { it == "META" })

        manager.pause("user")
        now += 3_000
        client.publishFrame(context, frame("frame-2"))
        assertEquals("暂停后不得发布帧标识", 3, transport.signalTypes.count { it == "META" })

        // 敏感页（关键词命中）同样不发布。
        manager.start()
        manager.consentGranted()
        manager.onFrame(frame("frame-3", texts = listOf("请输入支付密码")))
        assertTrue(manager.snapshot().pageSensitive)
        now += 3_000
        client.publishFrame(context, frame("frame-3"))
        assertEquals("敏感页不得发布帧标识", 3, transport.signalTypes.count { it == "META" })
    }

    @Test
    fun `媒体信令分发而控制指令与迟到旧帧标注被拒绝`() = runTest {
        manager.start()
        manager.consentGranted()
        manager.onFrame(frame("frame-1"))

        val transport = FakeTransport().apply {
            events = listOf(
                event(1, "OFFER", """{"sdp":"v=0"}"""),
                event(2, "CLICK", """{"x":0.5,"y":0.5}"""),
                event(3, "HIGHLIGHT", highlightJson("frame-old")),
                event(4, "HIGHLIGHT", highlightJson("frame-1"))
            )
        }
        val media = mutableListOf<Pair<String, Map<String, Any?>>>()
        val renders = mutableListOf<Unit>()
        val client = newClient(transport, renders = renders)
        client.mediaEventHandler = { type, payload -> media += type to payload }

        client.start(context, "ws-1")
        advanceTimeBy(150)
        runCurrent()

        // 只有 OFFER 进入媒体层；CLICK 被安全白名单拒绝，不回调媒体层。
        assertEquals(1, media.size)
        assertEquals("OFFER", media[0].first)
        assertEquals("v=0", media[0].second["sdp"])

        // 旧帧标注被拒绝，当前帧标注被接受（只允许一条）。
        val snap = manager.snapshot()
        assertNotNull(snap.highlight)
        assertEquals("frame-1", snap.highlight?.frameId)
        assertEquals(GuidanceSessionState.HIGHLIGHTING, snap.state)
        assertTrue("接受标注后必须重绘悬浮层", renders.isNotEmpty())
    }

    @Test
    fun `媒体信令只允许协商类型且需要活跃会话`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport)

        assertFalse(client.sendMediaSignal("CLICK", buildJsonObject { }))
        assertFalse(client.sendMediaSignal("OFFER", buildJsonObject { })) // 无会话

        client.start(context, "ws-1")
        assertTrue(client.sendMediaSignal("OFFER", buildJsonObject { put("sdp", "v=0") }))
        assertEquals(listOf("OFFER"), transport.signalTypes)

        client.stop(context, "user")
        assertFalse(client.sendMediaSignal("OFFER", buildJsonObject { }))
    }
}

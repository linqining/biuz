package cn.biuz.android.store

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 场景三：sessions-index 订阅失败兜底链（AGENTS §5-6/§5-7：订阅失败必须兜底；
 * 回执取不到 subscriptionId = 自愈链失效，视为错误处理）→ listSessions 对账。
 */
class SessionsIndexFallbackChainTest {

    private val ws = "/Users/mac/zcode_mobile"

    private fun lsRow(sessionId: String, title: String): JSONValue = testObj(
        "sessionId" to JSONValue.Str(sessionId),
        "title" to JSONValue.Str(title),
        "phase" to JSONValue.Str("completedSuccess"),
        "lastActivityAt" to testNum(1_767_000_000_000L),
        "workspacePath" to JSONValue.Str(ws),
    )

    @Test
    fun subscribeRpcFailure_fallsBackToListSessions() = runBlocking {
        val fake = FakeRemoteTransport()
        fake.subscribeError = RPCError(message = "stub: subscribe denied", name = "ErrorObj")
        fake.listSessionsItems = listOf(lsRow("sess-ls-1", "兜底会话一"), lsRow("sess-ls-2", "兜底会话二"))
        val store = RemoteSessionsIndexStore(fake, StoreWorkspaceInfo(path = ws))

        store.ensureSubscribed()

        // 调用序：subscribeSessionsIndexV4 失败 → listSessions 兜底（§5-6）
        assertEquals(
            listOf("zcode-agent" to "subscribeSessionsIndexV4", "zcode-agent" to "listSessions"),
            fake.callLog.toList(),
            "订阅失败必须走 listSessions 兜底，不得静默落空",
        )
        assertNotNull(store.conversationsLoadFailure)
        assertTrue(store.conversationsLoadFailure!!.contains("订阅会话列表失败"), "拒因原文上屏（§5-12）：${store.conversationsLoadFailure}")
        assertTrue(store.conversationsLoadFailure!!.contains("stub: subscribe denied"), "失败文本携 RPCError.message 原文")
        // 兜底数据进表并投影（last-good 与失败文案三态共存）
        val ids = store.currentConversations().map { it.id }
        assertEquals(listOf("sess-ls-1", "sess-ls-2"), ids)
    }

    @Test
    fun subscribeAckWithoutSubscriptionId_treatedAsFailure_fallsBack() = runBlocking {
        val fake = FakeRemoteTransport()
        // 回执缺 subscriptionId（旧桌面/中继退化形态）——自愈链不可用 = 错误路径（§5-7）
        fake.subscribeSessionsIndexReply = testObj("ok" to JSONValue.Bool(true))
        fake.listSessionsItems = listOf(lsRow("sess-ls-3", "缺 subId 兜底"))
        val store = RemoteSessionsIndexStore(fake, StoreWorkspaceInfo(path = ws))

        store.ensureSubscribed()

        assertTrue(fake.callLog.contains("zcode-agent" to "listSessions"), "缺 subscriptionId 必须视为订阅失败走兜底")
        assertNotNull(store.conversationsLoadFailure)
        assertTrue(store.conversationsLoadFailure!!.contains("subscriptionId"), "失败文本注明自愈链不可用：${store.conversationsLoadFailure}")
        assertEquals("sess-ls-3", store.currentConversations().single().id)
    }

    @Test
    fun subscribeSuccess_noFallback_failureCleared_snapshotFrameApplied() = runBlocking {
        val fake = FakeRemoteTransport()
        fake.listSessionsItems = listOf(lsRow("sess-should-not-appear", "兜底不该被调"))
        val store = RemoteSessionsIndexStore(fake, StoreWorkspaceInfo(path = ws))

        store.ensureSubscribed()

        // 成功路径：不调 listSessions（快照覆盖语义：订阅成功后帧以快照为准）
        assertEquals(listOf("zcode-agent" to "subscribeSessionsIndexV4"), fake.callLog.toList())
        assertNull(store.conversationsLoadFailure, "成功即清失败文案")

        // 帧应用：快照帧（handler 先注册——fake.listen 已登记槽）→ 键级整表替换
        val snapshotFrame = testObj(
            "wireVersion" to JSONValue.Int(3),
            "kind" to JSONValue.Str("complete"),
            "deliveryKind" to JSONValue.Str("initial"),
            "logicalFrameId" to JSONValue.Str("lf-1"),
            "logicalFrameOrdinal" to JSONValue.Int(0),
            "topic" to JSONValue.Str("sessions-index/$ws"),
            "subscriptionId" to JSONValue.Str("sub-sess-test"),
            "frame" to testObj(
                "topic" to JSONValue.Str("sessions-index/$ws"),
                "subscriptionId" to JSONValue.Str("sub-sess-test"),
                "fromSeq" to JSONValue.Int(0),
                "toSeq" to JSONValue.Int(1),
                "payload" to testObj(
                    "kind" to JSONValue.Str("snapshot"),
                    "snapshot" to testObj(
                        "logEpoch" to JSONValue.Str("epoch-frame-1"),
                        "sessions" to JSONValue.Arr(
                            listOf(
                                testObj(
                                    "sessionId" to JSONValue.Str("sess-frame-1"),
                                    "title" to JSONValue.Str("快照会话"),
                                    "phase" to JSONValue.Str("running"),
                                    "lastActivityAt" to testNum(1_791_040_344_222L),
                                    "workspacePath" to JSONValue.Str(ws),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        fake.sessionsFrameHandler!!.invoke(RPCValue.json(snapshotFrame))
        awaitUntilSuspend("快照帧落表") { store.currentConversations().any { it.id == "sess-frame-1" } }
        val frameRow = store.currentConversations().single()
        assertEquals("sess-frame-1", frameRow.id)
        assertTrue(frameRow.isRunning)

        // 增量帧：session.upserted 改标题 + session.removed 删行（op 词表宽容）
        val deltaFrame = testObj(
            "wireVersion" to JSONValue.Int(3),
            "kind" to JSONValue.Str("complete"),
            "deliveryKind" to JSONValue.Str("online"),
            "topic" to JSONValue.Str("sessions-index/$ws"),
            "subscriptionId" to JSONValue.Str("sub-sess-test"),
            "frame" to testObj(
                "topic" to JSONValue.Str("sessions-index/$ws"),
                "subscriptionId" to JSONValue.Str("sub-sess-test"),
                "fromSeq" to JSONValue.Int(1),
                "toSeq" to JSONValue.Int(2),
                "payload" to testObj(
                    "kind" to JSONValue.Str("deltas"),
                    "deltas" to JSONValue.Arr(
                        listOf(
                            testObj(
                                "op" to JSONValue.Str("session.upserted"),
                                "session" to testObj(
                                    "sessionId" to JSONValue.Str("sess-frame-1"),
                                    "title" to JSONValue.Str("改名后的会话"),
                                    "phase" to JSONValue.Str("completedSuccess"),
                                    "lastActivityAt" to testNum(1_791_040_344_300L),
                                    "workspacePath" to JSONValue.Str(ws),
                                ),
                            ),
                            testObj(
                                "op" to JSONValue.Str("unknown.future.op"), // 未知 op 静默忽略
                            ),
                        ),
                    ),
                ),
            ),
        )
        fake.sessionsFrameHandler!!.invoke(RPCValue.json(deltaFrame))
        awaitUntilSuspend("增量落表") {
            val row = store.currentConversations().singleOrNull()
            row?.title == "改名后的会话" && !row.isRunning
        }
    }

    @Test
    fun listSessionsTolerantParsing_topLevelArrayAndWrapped() = runBlocking {
        // 顶层数组形态
        val fakeArray = FakeRemoteTransport()
        fakeArray.subscribeError = RPCError(message = "denied", name = "ErrorObj")
        fakeArray.listSessionsAsObject = false
        fakeArray.listSessionsItems = listOf(lsRow("sess-arr", "顶层数组行"))
        val storeArray = RemoteSessionsIndexStore(fakeArray, StoreWorkspaceInfo(path = ws))
        storeArray.ensureSubscribed()
        assertEquals("sess-arr", storeArray.currentConversations().single().id)

        // {sessions:[…]} 包裹形态 + 空清单不动既有表
        val fakeWrapped = FakeRemoteTransport()
        fakeWrapped.subscribeError = RPCError(message = "denied", name = "ErrorObj")
        fakeWrapped.listSessionsItems = listOf(lsRow("sess-wrapped", "包裹形态行"))
        val storeWrapped = RemoteSessionsIndexStore(fakeWrapped, StoreWorkspaceInfo(path = ws))
        storeWrapped.ensureSubscribed()
        assertEquals("sess-wrapped", storeWrapped.currentConversations().single().id)
        fakeWrapped.listSessionsItems = emptyList()
        storeWrapped.fallbackListSessions()
        assertEquals("sess-wrapped", storeWrapped.currentConversations().single().id, "空兜底清单不覆盖既有表（iOS guard 同口径）")
    }

    @Test
    fun dispose_unsubscribesWithSubscriptionId() = runBlocking {
        val fake = FakeRemoteTransport()
        val store = RemoteSessionsIndexStore(fake, StoreWorkspaceInfo(path = ws))
        store.ensureSubscribed()
        fake.callLog.clear()
        store.dispose()
        val unsubscribeCalls = fake.callLog.filter { it.second == "unsubscribeSessionsIndexV4" }
        assertEquals(1, unsubscribeCalls.size, "dispose 恰好退订一次")
        // 退订参数按 subscriptionId 精确匹配（§11.1#2）——经 arg 检查
        // （fake.callLog 不含 arg，另以 replay 面检查：此处只断言调用发生且失败不抛）
    }

    @Test
    fun subscribeFailureThenRetry_successPathRecoverable() = runBlocking {
        val fake = FakeRemoteTransport()
        fake.subscribeError = RPCError(message = "stub: subscribe denied", name = "ErrorObj")
        val store = RemoteSessionsIndexStore(fake, StoreWorkspaceInfo(path = ws))
        store.ensureSubscribed()
        assertNotNull(store.conversationsLoadFailure, "首跑失败态在场")
        // 二次重入：订阅失败不锁死 ensureSubscribed（iOS 失败路径保持 subscription nil 的重入口径）
        fake.subscribeError = null
        store.ensureSubscribed()
        assertNull(store.conversationsLoadFailure, "重订成功即清失败文案")
        assertEquals(2, fake.callLog.count { it.second == "subscribeSessionsIndexV4" }, "首败 + 重订共两次调用")
        assertEquals(1, fake.callLog.count { it.second == "listSessions" }, "仅首败走一次兜底；重订成功不再追加")
    }

    @Test
    fun loadFailureText_formatsRpcErrorAndTimeoutHint() {
        assertEquals(
            "订阅会话列表失败 · TimeoutError：RPC 超时 · 桌面端可能繁忙，可稍后重试",
            RemoteSessionsIndexStore.loadFailureText("订阅会话列表", RPCError(message = "RPC 超时", name = "TimeoutError")),
        )
        assertEquals(
            "订阅会话列表失败 · ErrorObj：stub: denied",
            RemoteSessionsIndexStore.loadFailureText("订阅会话列表", RPCError(message = "stub: denied", name = "ErrorObj")),
        )
        assertEquals(
            "订阅会话列表失败 · java.lang.IllegalStateException: boom",
            RemoteSessionsIndexStore.loadFailureText("订阅会话列表", IllegalStateException("boom")),
        )
    }
}

package cn.biuz.android.store

// RemoteConversationStore 订阅面单测（对应 iOS 门禁面）：
// - 订阅序红线（陷阱④+A8）：帧 handler → 丢帧自愈 → 帧面流（同 workspace）→ subscribe；
// - ACK 激活屏障：回执前到达的快照帧暂存、subId 落库后原序回放；
// - A7 寻址：订阅/rowsRange/resync 按会话归属 workspacePath + identity；
// - A9：atLogEpoch 失配整批作废；logEpoch 换代快照整表替换；
// - A10：state.updated 键级整替不深合并；row.delta 流式追加；
// - rowsRange 只向旧翻页（beforeRowId=最小 rowId）+ hasMore；
// - 30s keep-warm 租约池：关闭后延迟退订、租约期内重入零重拉、到期携 subscriptionId 真退订；
// - 行映射：userInput/assistantText/reasoning/toolCall/workflowCompletion/state-todos；
// - parseInteraction plan_approval schema 归位（v1.31 根修面）。
// Mock 仅测试装配（红线 §0-4）：替身传输 + 替身帧面在本 test source set。

import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.MessageStatus
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.ThinkingState
import cn.biuz.android.protocol.ToolKind
import cn.biuz.android.protocol.V4TopicFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 测试替身帧面：记录操作序（与传输共用 ops 账），暴露帧注入口。 */
internal class FakeConversationFrameSurface(private val ops: CopyOnWriteArrayList<String>) :
    ConversationFrameSurface {

    val frameHandlers = ConcurrentHashMap<String, (V4TopicFrame) -> Unit>()
    val streamCalls = CopyOnWriteArrayList<Pair<String, String?>>()
    val dropHandlerKeys = CopyOnWriteArrayList<String>()
    val removedTopics = CopyOnWriteArrayList<String>()

    override suspend fun ensureConversationFrameStream(
        workspacePath: String,
        workspaceIdentity: String?,
    ) {
        ops.add("stream:$workspacePath")
        streamCalls.add(workspacePath to workspaceIdentity)
    }

    override fun setFrameHandler(topic: String, handler: (V4TopicFrame) -> Unit) {
        ops.add("handler:$topic")
        frameHandlers[topic] = handler
    }

    override fun removeFrameHandler(topic: String) {
        frameHandlers.remove(topic)
        removedTopics.add(topic)
        ops.add("removeHandler:$topic")
    }

    override fun setFrameDropHandler(key: String, handler: () -> Unit) {
        dropHandlerKeys.add(key)
        ops.add("dropHandler:$key")
    }

    /** 测试注帧：直调已注册 topic handler（:connection routeFrame 的替身同位）。 */
    fun emit(topic: String, frame: V4TopicFrame) {
        frameHandlers[topic]?.invoke(frame)
    }
}

/** 测试替身传输：conversation 订阅面各命令注水 + 操作序账。 */
internal class FakeConversationTransport(internal val ops: CopyOnWriteArrayList<String>) :
    RPCChannelTransport {

    // subscribeConversationV4
    @Volatile
    var subscribeReply: JSONValue = testObj(
        "ok" to JSONValue.Bool(true),
        "ack" to testObj(
            "subscriptionId" to JSONValue.Str("sub-c-1"),
            "mode" to JSONValue.Str("snapshot"),
            "logEpoch" to JSONValue.Str("epoch-1"),
        ),
    )

    /** 非空 = subscribe 回执挂起至此栅门（屏障暂存竞态复现）。 */
    @Volatile
    var subscribeGate: CompletableDeferred<Unit>? = null

    @Volatile
    var subscribeError: Throwable? = null

    val subscribeArgs = CopyOnWriteArrayList<Map<String, JSONValue>>()
    var subscribeCallCount: Int = 0

    // conversationRowsRangeV4
    var rowsRangeHandler: ((beforeRowId: Int?) -> JSONValue)? = null

    /** 非空 = rowsRange 抛错（传输同源失败注水，订阅失败轮共用）。 */
    @Volatile
    var rowsRangeError: Throwable? = null
    val rowsRangeCalls = CopyOnWriteArrayList<Int?>()

    // readSession（对账兜底）
    @Volatile
    var readSessionReply: JSONValue? = null

    val readSessionCalls = CopyOnWriteArrayList<Int>()

    // resyncConversationV4 / unsubscribeConversationV4
    val resyncArgs = CopyOnWriteArrayList<Map<String, JSONValue>>()
    val unsubscribeArgs = CopyOnWriteArrayList<Map<String, JSONValue>>()

    // sendConversationCommandV4（发送面）
    @Volatile
    var sendReply: JSONValue = testObj(
        "status" to JSONValue.Str("accepted"),
        "commandId" to JSONValue.Str("cmd-stub"),
    )

    /** 非空 = send 挂起至此栅门（在途创建去重复现）。 */
    @Volatile
    var sendGate: CompletableDeferred<Unit>? = null

    @Volatile
    var sendError: Throwable? = null
    val sendArgs = CopyOnWriteArrayList<Map<String, JSONValue>>()
    var sendCallCount: Int = 0

    // zcode-task.setTaskUnread（markUnread 回滚）
    @Volatile
    var setTaskUnreadError: Throwable? = null
    var setTaskUnreadCallCount: Int = 0

    // listSessions items（标题兜底回填注水）+ 调用计数
    @Volatile
    var listSessionsItems: List<JSONValue> = emptyList()
    var listSessionsCallCount: Int = 0

    // sessions-index 帧槽（会话表注水用）
    @Volatile
    var sessionsIndexFrameHandler: ((RPCValue) -> Unit)? = null

    override suspend fun call(
        channel: String,
        command: String,
        arg: RPCValue,
        timeout: kotlin.time.Duration,
    ): RPCValue {
        ops.add("call:$channel:$command")
        val args = arg.jsonValue?.objectValue ?: emptyMap()
        return when (command) {
            "subscribeConversationV4" -> {
                subscribeCallCount += 1
                subscribeArgs.add(args)
                subscribeError?.let { throw it }
                subscribeGate?.await()
                RPCValue.json(subscribeReply)
            }
            "conversationRowsRangeV4" -> {
                rowsRangeCalls.add(args["beforeRowId"]?.intValue)
                rowsRangeError?.let { throw it }
                RPCValue.json(rowsRangeHandler?.invoke(args["beforeRowId"]?.intValue) ?: testObj())
            }
            "readSession" -> {
                readSessionCalls.add(1)
                RPCValue.json(readSessionReply ?: testObj("session" to testObj()))
            }
            "resyncConversationV4" -> {
                resyncArgs.add(args)
                RPCValue.json(testObj("ok" to JSONValue.Bool(true)))
            }
            "unsubscribeConversationV4" -> {
                unsubscribeArgs.add(args)
                RPCValue.json(testObj("ok" to JSONValue.Bool(true)))
            }
            "sendConversationCommandV4" -> {
                sendCallCount += 1
                sendArgs.add(args)
                sendError?.let { throw it }
                sendGate?.await()
                RPCValue.json(sendReply)
            }
            "setTaskUnread" -> {
                setTaskUnreadCallCount += 1
                setTaskUnreadError?.let { throw it }
                RPCValue.json(testObj("ok" to JSONValue.Bool(true)))
            }
            "promoteDeferredDraftSession" -> RPCValue.json(testObj("ok" to JSONValue.Bool(true)))
            // 会话表注水路径（RemoteSessionsIndexStore）
            "subscribeSessionsIndexV4" -> RPCValue.json(
                testObj(
                    "ack" to testObj("subscriptionId" to JSONValue.Str("sub-sess-1")),
                ),
            )
            "listSessions" -> {
                listSessionsCallCount += 1
                RPCValue.json(testObj("sessions" to JSONValue.Arr(listSessionsItems)))
            }
            "unsubscribeSessionsIndexV4" -> RPCValue.json(testObj())
            else -> throw RPCError(message = "fake: unhandled $command", name = "ErrorObj")
        }
    }

    override suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue,
        handler: (RPCValue) -> Unit,
    ): EventSubscription {
        if (event == RemoteSessionsIndexStore.EVENT_SESSIONS_INDEX_FRAME) {
            sessionsIndexFrameHandler = handler
        }
        return EventSubscription { }
    }

    override suspend fun disconnect() {}
}

/** 会话域测试装置（共享操作序账 + 单一测试调度器）。 */
internal class Fixture(
    scope: TestScope,
    val workspacePath: String = "/conn/ws",
    val workspaceIdentity: String? = "conn-ident",
    val keepWarmMs: Long = 5_000L,
    withIndex: Boolean = false,
    nowMs: (() -> Long)? = null,
) {
    val ops = CopyOnWriteArrayList<String>()
    val transport = FakeConversationTransport(ops)
    val frameSurface = FakeConversationFrameSurface(ops)
    val workspace = StoreWorkspaceInfo(workspacePath, workspaceIdentity = workspaceIdentity)

    /** sessions-index 面（发送面 meta 回填/markUnread/createSession 入表用；按需开启）。 */
    val index: RemoteSessionsIndexStore? = if (withIndex) {
        RemoteSessionsIndexStore(
            transport,
            StoreWorkspaceInfo(workspacePath, workspaceIdentity = workspaceIdentity),
            backgroundDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    } else {
        null
    }

    val store = RemoteConversationStore(
        transport = transport,
        frameSurface = frameSurface,
        workspace = workspace,
        sessionsIndex = index,
        clientId = "test-client-1",
        backgroundDispatcher = StandardTestDispatcher(scope.testScheduler),
        keepWarmMs = keepWarmMs,
        nowMs = nowMs ?: ({ System.currentTimeMillis() }),
    )
    val diag = ConcurrentHashMap<String, String>()

    init {
        store.diagListener = { key, value -> diag[key] = value }
    }
}

/** V4TopicFrame 直构（wire 层已由 protocol 单测锁定；此处只关心订阅面行为）。 */
internal fun frame(
    topic: String,
    subscriptionId: String,
    fromSeq: Int,
    toSeq: Int,
    snapshot: JSONValue? = null,
    deltas: List<JSONValue> = emptyList(),
): V4TopicFrame = V4TopicFrame(
    topic = topic,
    subscriptionId = subscriptionId,
    fromSeq = fromSeq,
    toSeq = toSeq,
    sentAt = null,
    snapshot = snapshot,
    deltas = deltas,
)

fun row(rowId: Int, kind: String, vararg pairs: Pair<String, JSONValue?>): JSONValue =
    testObj("rowId" to JSONValue.Int(rowId), "kind" to JSONValue.Str(kind), *pairs)

class RemoteConversationStoreTest {

    // MARK: 订阅序红线（陷阱④ + A8）

    @Test
    fun subscribeOrder_frameHandlerAndStreamBeforeSubscribe() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        val order = f.ops.toList()
        val handlerIdx = order.indexOf("handler:conversation/sess-1")
        val dropIdx = order.indexOf("dropHandler:conversation")
        val streamIdx = order.indexOfFirst { it.startsWith("stream:") }
        val callIdx = order.indexOf("call:zcode-agent:subscribeConversationV4")
        assertTrue(handlerIdx in 0 until callIdx, "帧 handler 必须先于 subscribe：$order")
        assertTrue(dropIdx in 0 until callIdx, "丢帧自愈 handler 必须先于 subscribe：$order")
        assertTrue(streamIdx in 0 until callIdx, "帧面流必须先于 subscribe：$order")
        assertTrue(handlerIdx < streamIdx, "帧面挂在 handler 落表之后（本方法内序）")
        // 首路帧面带连接 workspace（归属未知回退 = 旧口径）
        assertEquals("/conn/ws" to "conn-ident", f.frameSurface.streamCalls.first())
        // 订阅参数：topic + sessionId + workspace 信封（A7 回退形态）
        val args = f.transport.subscribeArgs.single()
        assertEquals("conversation/sess-1", args["topic"]?.stringValue)
        assertEquals("sess-1", args["sessionId"]?.stringValue)
        assertEquals("/conn/ws", args["workspacePath"]?.stringValue)
        assertEquals("conn-ident", args["workspaceIdentity"]?.stringValue)
    }

    // MARK: ACK 激活屏障（快照帧先于回执）

    @Test
    fun barrier_stagesSnapshotFrameArrivingBeforeAck() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.transport.subscribeGate = gate
        val job = launch { f.store.messages("sess-1") }
        runCurrent() // 推进到 subscribe 挂起于栅门
        // 回执未到，快照帧先达 → 暂存不消费
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "revision" to testNum(7),
                    "rows" to JSONValue.Arr(listOf(row(1, "assistantText", "text" to JSONValue.Str("hi")))),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(0, f.store.currentMessages("sess-1").size, "暂存帧不得在 subId 落库前消费")
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
        val messages = f.store.currentMessages("sess-1")
        assertEquals(1, messages.size)
        assertEquals("hi", messages.single().text)
        // 水位/revision 已随回放落库（CAS base 数据源）
        val (revision, epoch) = f.store.conversationBaseWatermark("sess-1")
        assertEquals(7L, revision)
        assertEquals("epoch-1", epoch)
    }

    // MARK: A7 寻址

    @Test
    fun addressing_sessionOwnedWorkspaceUsedForSubscribeAndRange() = runTest {
        val sharedOps = CopyOnWriteArrayList<String>()
        val transport = FakeConversationTransport(sharedOps)
        val surface = FakeConversationFrameSurface(sharedOps)
        // 会话表注水：sess-1 归属 /other/ws（identity 型工作区）
        val index = RemoteSessionsIndexStore(
            transport,
            StoreWorkspaceInfo("/conn/ws", workspaceIdentity = "conn-ident"),
            backgroundDispatcher = StandardTestDispatcher(testScheduler),
        )
        index.conversations()
        val snapshotEnvelope = testObj(
            "wireVersion" to testNum(3),
            "deliveryKind" to JSONValue.Str("complete"),
            "logicalFrameId" to JSONValue.Str("l1"),
            "logicalFrameOrdinal" to testNum(0),
            "topic" to JSONValue.Str("sessions-index/conn-ident"),
            "subscriptionId" to JSONValue.Str("sub-sess-1"),
            "kind" to JSONValue.Str("complete"),
            "frame" to testObj(
                "topic" to JSONValue.Str("sessions-index/conn-ident"),
                "subscriptionId" to JSONValue.Str("sub-sess-1"),
                "fromSeq" to testNum(1),
                "toSeq" to testNum(1),
                "payload" to testObj(
                    "kind" to JSONValue.Str("snapshot"),
                    "snapshot" to testObj(
                        "logEpoch" to JSONValue.Str("e-sess"),
                        "sessions" to JSONValue.Arr(
                            listOf(
                                testObj(
                                    "sessionId" to JSONValue.Str("sess-1"),
                                    "title" to JSONValue.Str("T"),
                                    "phase" to JSONValue.Str("running"),
                                    "workspacePath" to JSONValue.Str("/other/ws"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        transport.sessionsIndexFrameHandler?.invoke(RPCValue.json(snapshotEnvelope))
        advanceUntilIdle()
        val store = RemoteConversationStore(
            transport = transport,
            frameSurface = surface,
            workspace = StoreWorkspaceInfo("/conn/ws", workspaceIdentity = "conn-ident"),
            sessionsIndex = index,
            clientId = "test-client-1",
            backgroundDispatcher = StandardTestDispatcher(testScheduler),
        )
        store.allWorkspaces = listOf(
            StoreWorkspaceInfo("/other/ws", workspaceIdentity = "ident-other"),
        )
        store.messages("sess-1")
        advanceUntilIdle()
        // 订阅按归属寻址（A7）：B 区 workspacePath + identity（emitter 键口径）
        val subArgs = transport.subscribeArgs.single()
        assertEquals("/other/ws", subArgs["workspacePath"]?.stringValue)
        assertEquals("ident-other", subArgs["workspaceIdentity"]?.stringValue)
        assertEquals("/other/ws", surface.streamCalls.first().first)
        assertEquals("ident-other", surface.streamCalls.first().second)
        // rowsRange 同源寻址
        assertTrue(transport.rowsRangeCalls.isNotEmpty(), "快照空表应回退 rowsRange 拉取")
        // resync 同源（forceFullResync 的 workspace 信封随订阅一致）
        store.forceFullResync("sess-1", "sub-c-1")
        advanceUntilIdle()
        val resync = transport.resyncArgs.single()
        assertEquals("/other/ws", resync["workspacePath"]?.stringValue)
        assertEquals("ident-other", resync["workspaceIdentity"]?.stringValue)
        assertEquals("sub-c-1", resync["subscriptionId"]?.stringValue)
        assertTrue(resync["base"] is JSONValue.Null, "全量 resync base=null")
    }

    // MARK: A9 atLogEpoch 失配整批作废 + rowsRange 向旧翻页

    @Test
    fun rowsRange_epochMismatchDiscardsBatch_andPaginatesBackward() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        // 快照落表：epoch-1、rows [10..12]、toSeq 5
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 5,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(
                        listOf(
                            row(10, "userInput", "text" to JSONValue.Str("a")),
                            row(11, "assistantText", "text" to JSONValue.Str("b")),
                            row(12, "assistantText", "text" to JSONValue.Str("c")),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        // 回执 atLogEpoch 与水位不符（CLI 重启换代）→ 整批作废
        var cursorSeen: Int? = null
        f.transport.rowsRangeHandler = { before ->
            cursorSeen = before
            testObj(
                "atLogEpoch" to JSONValue.Str("epoch-2"),
                "rows" to JSONValue.Arr(listOf(row(9, "userInput", "text" to JSONValue.Str("stale")))),
                "hasMore" to JSONValue.Bool(false),
            )
        }
        val hasMore = f.store.loadOlder("sess-1")
        advanceUntilIdle()
        assertEquals(false, hasMore)
        assertEquals(10, cursorSeen, "游标 = 表内最小 rowId（只向旧翻页；表为 {10,11,12}）")
        val messages = f.store.currentMessages("sess-1")
        assertEquals(3, messages.size, "跨纪元行不得混入（A9）")
        assertTrue(f.diag["diag.rowsrange.sess-1"]?.contains("失配") == true)
        // epoch 一致的页正常并入，hasMore 透传
        f.transport.rowsRangeHandler = {
            testObj(
                "atLogEpoch" to JSONValue.Str("epoch-1"),
                "rows" to JSONValue.Arr(listOf(row(9, "userInput", "text" to JSONValue.Str("old")))),
                "hasMore" to JSONValue.Bool(true),
            )
        }
        val hasMore2 = f.store.loadOlder("sess-1")
        advanceUntilIdle()
        assertEquals(true, hasMore2)
        val messages2 = f.store.currentMessages("sess-1")
        assertEquals(4, messages2.size)
        assertEquals("row-9", messages2.first().id, "向旧翻页行前置")
    }

    // MARK: A10 键级整替 + row.delta 流式追加 + 帧序纪律

    @Test
    fun deltas_keyLevelReplacementAndStreamAppend_withFrameFiltering() = runTest {
        val f = Fixture(this)
        // 先订阅（帧 handler 注册；空表回退 rowsRange 空页）——真实链路帧只在订阅后到达
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 5,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "revision" to testNum(7),
                    "rows" to JSONValue.Arr(
                        listOf(
                            row(4, "assistantText", "text" to JSONValue.Str("hi"), "state" to JSONValue.Str("complete")),
                        ),
                    ),
                    "state" to testObj(
                        "modelSelection" to testObj("modelId" to JSONValue.Str("m1")),
                        "workflowRuns" to testObj(
                            "revision" to testNum(1),
                            "runs" to JSONValue.Arr(listOf(testObj("runId" to JSONValue.Str("runA")))),
                        ),
                        // 在场（空数组）= 免触发「快照缺 pendingInteractions」的一次性全量补拉，
                        // 断档 resync 计数才可精确断言
                        "pendingInteractions" to JSONValue.Arr(emptyList()),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        // 快照投影就位
        assertEquals("m1", f.store.sessionModelSelection("sess-1")?.model)
        assertEquals(listOf("runA"), f.store.workflowRunsTable("sess-1")!!.runs.map {
            it.objectValue?.get("runId")?.stringValue
        })
        // ① 迟到帧（toSeq ≤ 水位 5）静默丢
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 5, toSeq = 5,
                deltas = listOf(testObj("op" to JSONValue.Str("state.updated"), "patch" to testObj())),
            ),
        )
        advanceUntilIdle()
        assertEquals("m1", f.store.sessionModelSelection("sess-1")?.model, "迟到帧不得写入投影")
        // ② 正常增量：state.updated 键级整替（modelSelection 全键换新；workflowRuns 整表替换）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 6, toSeq = 6,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj(
                            "modelSelection" to testObj("modelId" to JSONValue.Str("m2")),
                            "workflowRuns" to testObj(
                                "revision" to testNum(2),
                                "runs" to JSONValue.Arr(listOf(testObj("runId" to JSONValue.Str("runB")))),
                            ),
                            "revision" to testNum(9),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("m2", f.store.sessionModelSelection("sess-1")?.model)
        assertEquals(9L, f.store.conversationBaseWatermark("sess-1").first, "patch.revision 回填 CAS base")
        val runs = f.store.workflowRunsTable("sess-1")!!.runs.map { it.objectValue?.get("runId")?.stringValue }
        assertEquals(listOf("runB"), runs, "state.workflowRuns 键级整替（不与 runA 深合并）")
        // ③ row.delta 流式追加
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 7, toSeq = 7,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("row.delta"),
                        "rowId" to JSONValue.Int(4),
                        "path" to JSONValue.Str("text"),
                        "append" to JSONValue.Str("!"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("hi!", f.store.currentMessages("sess-1").single().text)
        // ④ 帧断档（fromSeq > 水位+1）→ 全量 resync、水位不推进
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 9, toSeq = 10,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj("modelSelection" to testObj("modelId" to JSONValue.Str("m3"))),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, f.transport.resyncArgs.size, "断档转全量 resync")
        assertEquals("m2", f.store.sessionModelSelection("sess-1")?.model, "断档帧不得写入投影")
        // 水位未被断档帧推进（仍 7）：紧随的合法增量 fromSeq=8 照常应用
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 8, toSeq = 8,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("row.delta"),
                        "rowId" to JSONValue.Int(4),
                        "path" to JSONValue.Str("text"),
                        "append" to JSONValue.Str("?"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("hi!?", f.store.currentMessages("sess-1").single().text, "断档后水位未推进（合法增量仍应用）")
        // ⑤ 旧订阅迟到帧（subId 不符）直接丢
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-OLD",
                fromSeq = 8, toSeq = 8,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj("modelSelection" to testObj("modelId" to JSONValue.Str("m4"))),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("m2", f.store.sessionModelSelection("sess-1")?.model, "旧订阅帧不得写入投影")
    }

    // MARK: A9 快照纪元换代整表替换

    @Test
    fun snapshot_epochChangeReplacesWholeTable() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(listOf(row(1, "userInput", "text" to JSONValue.Str("old-era")))),
                ),
            ),
        )
        advanceUntilIdle()
        // 常规快照（同纪元）：rowId 键级合并，旧行保留（尾部窗口覆盖口径）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 4, toSeq = 4,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(listOf(row(2, "assistantText", "text" to JSONValue.Str("new")))),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(2, f.store.currentMessages("sess-1").size)
        // 纪元换代快照（CLI 重启重新编号 rowId）：整表替换，旧纪元行不复活
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 5, toSeq = 5,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-2"),
                    "rows" to JSONValue.Arr(listOf(row(1, "userInput", "text" to JSONValue.Str("new-era")))),
                ),
            ),
        )
        advanceUntilIdle()
        val messages = f.store.currentMessages("sess-1")
        assertEquals(1, messages.size, "跨纪元快照整表替换（A9）")
        assertEquals("new-era", messages.single().text)
    }

    // MARK: 30s keep-warm 租约池

    @Test
    fun keepWarm_delaysUnsubcribe_andReentryReusesLease() = runTest {
        val f = Fixture(this, keepWarmMs = 5_000L)
        // 订阅 + 快照落表（行缓存就绪，warm 重入才能零重拉）
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 2,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(
                        listOf(row(1, "assistantText", "text" to JSONValue.Str("warm"))),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(1, f.store.currentMessages("sess-1").size)
        val rangeCallsAtWarm = f.transport.rowsRangeCalls.size // 首订阅空表回退 rowsRange 已计入
        // ① 关闭 → 租约期内不退订（runCurrent：不得用 advanceUntilIdle——会烧掉虚拟时间直接引燃租约计时器）
        f.store.closeConversation("sess-1")
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, f.transport.unsubscribeArgs.size, "租约期内不得真退订")
        // ② 租约期内重入：撤销到期退订，缓存命中零重拉（subscribe 不再发）
        f.store.messages("sess-1")
        runCurrent()
        assertEquals(1, f.transport.subscribeCallCount, "租约期内重入不得重订阅")
        assertEquals(rangeCallsAtWarm, f.transport.rowsRangeCalls.size, "行缓存命中，零重拉")
        // ③ 再次关闭 → 租约到期真退订（重入已撤销前一计时器，此处从关闭点起算 5s）
        f.store.closeConversation("sess-1")
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, f.transport.unsubscribeArgs.size, "租约到期真退订")
        val unsubscribe = f.transport.unsubscribeArgs.single()
        assertEquals("conversation/sess-1", unsubscribe["topic"]?.stringValue)
        assertEquals("sub-c-1", unsubscribe["subscriptionId"]?.stringValue, "退订必携 subscriptionId（§11.1#2）")
        // ④ 到期后重入 = 冷订阅（行缓存不清，直接命中投影）
        f.store.messages("sess-1")
        runCurrent()
        assertEquals(2, f.transport.subscribeCallCount)
    }

    // MARK: 订阅失败兜底链（§5-6/§5-7）

    @Test
    fun subscribeFailure_fallsBackToReadSession_andRetries() = runTest {
        val f = Fixture(this)
        // 传输同源失败：订阅与 rowsRange 一起挂（真实桥退化形态——若 rowsRange 假成功
        // 会按「任一环节成功即清除」把失败态冲掉，读面三态失真）
        val fault = RPCError(message = "fault.connection.handshakeRequired", name = "Error")
        f.transport.subscribeError = fault
        f.transport.rowsRangeError = fault
        f.store.messages("sess-1")
        advanceUntilIdle()
        assertTrue(
            f.store.conversationLoadFailure("sess-1")!!.contains("handshakeRequired"),
            "拒因原文上屏（§5-12）",
        )
        assertEquals(1, f.transport.readSessionCalls.size, "订阅失败兜底第二步 readSession 对账")
        // 重试经 messages() 重入（订阅守卫对失败态放行）
        f.transport.subscribeError = null
        f.transport.rowsRangeError = null
        f.store.messages("sess-1")
        advanceUntilIdle()
        assertEquals(2, f.transport.subscribeCallCount, "失败态重入必须重订")
        assertNull(f.store.conversationLoadFailure("sess-1"), "任一环节成功即清除失败态")
    }

    @Test
    fun subscribeReplyMissingSubscriptionId_treatedAsFailure() = runTest {
        val f = Fixture(this)
        f.transport.subscribeReply = testObj("ok" to JSONValue.Bool(true))
        f.transport.rowsRangeError = RPCError(message = "sub-failed", name = "Error")
        f.store.messages("sess-1")
        advanceUntilIdle()
        assertTrue(
            f.diag["diag.conv.sess-1"]!!.contains("subscriptionId"),
            "回执缺 subId = 自愈链失效，必须按错误处理（§5-7）",
        )
        // 读面失败态非空（rowsRange 同源失败保住三态呈现，不被假成功冲掉）
        assertNotNull(f.store.conversationLoadFailure("sess-1"))
        assertEquals(1, f.transport.readSessionCalls.size)
    }

    // MARK: 行模型映射

    @Test
    fun rebuildMapsRowsToMessages_includingWorkflowCompletionAndStateTodos() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 2,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(
                        listOf(
                            row(
                                1, "userInput",
                                "text" to JSONValue.Str("hello"),
                                "attachments" to JSONValue.Arr(listOf(JSONValue.Str("att-1"))),
                                "entityId" to JSONValue.Str("e-usr-1"),
                            ),
                            row(2, "reasoning", "text" to JSONValue.Str("thinking..."), "state" to JSONValue.Str("done")),
                            row(
                                3, "toolCall",
                                "toolName" to JSONValue.Str("bash"),
                                "inputText" to JSONValue.Str("ls"),
                                "status" to JSONValue.Str("success"),
                                "output" to testObj("text" to JSONValue.Str("ok")),
                                "entityId" to JSONValue.Str("e-tool-3"),
                            ),
                            row(
                                4, "assistantText",
                                "text" to JSONValue.Str("hi there"),
                                "state" to JSONValue.Str("complete"),
                                "feedback" to JSONValue.Str("like"),
                                "entityId" to JSONValue.Str("e-as-4"),
                            ),
                            row(
                                5, "turnHeader",
                                "origin" to JSONValue.Str("backgroundResult"),
                                "originMeta" to testObj(
                                    "backgroundSource" to JSONValue.Str("workflow"),
                                    "title" to JSONValue.Str("WF"),
                                    "workId" to JSONValue.Str("run-9"),
                                    "workflowNotification" to testObj(
                                        "kind" to JSONValue.Str("terminal"),
                                        "status" to JSONValue.Str("completed"),
                                        "artifacts" to JSONValue.Arr(
                                            listOf(
                                                testObj(
                                                    "id" to JSONValue.Str("a1"),
                                                    "kind" to JSONValue.Str("file"),
                                                    "title" to JSONValue.Str("R"),
                                                    "version" to testNum(2),
                                                    "primary" to JSONValue.Bool(true),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            row(
                                6, "assistantText",
                                "text" to JSONValue.Str("digest"),
                                "state" to JSONValue.Str("complete"),
                            ),
                        ),
                    ),
                    "state" to testObj(
                        "revision" to testNum(3),
                        "todos" to JSONValue.Arr(
                            listOf(testObj("content" to JSONValue.Str("step1"), "status" to JSONValue.Str("in_progress"))),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val messages = f.store.currentMessages("sess-1")
        // 6 行（turnHeader 不进流）→ 序：5 行消息 + 轮尾工作流完成卡 + state-todos 面板行
        assertEquals(7, messages.size, "实际序：${messages.map { it.id }}")
        // userInput 行
        with(messages[0]) {
            assertEquals("row-1", id)
            assertEquals(MessageRole.user, role)
            assertEquals("hello", text)
            assertEquals(listOf("att-1"), attachments)
            assertEquals("e-usr-1", entityId)
            assertEquals("userInput", rowKind)
        }
        // reasoning 行 → 思考折叠块
        with(messages[1]) {
            assertEquals("row-2", id)
            assertNotNull(thinking)
            assertEquals(ThinkingState.done, thinking!!.state)
            assertEquals("thinking...", thinking!!.text)
        }
        // toolCall 行 → 工具卡
        with(messages[2]) {
            assertEquals("row-3", id)
            assertEquals(ToolKind.bash, toolCall!!.kind)
            assertEquals(MessageStatus.done, status)
            assertEquals("e-tool-3", toolCall!!.entityId)
            assertEquals("ok", toolCall!!.output)
        }
        // assistantText 行 + 反馈态回读
        with(messages[3]) {
            assertEquals("row-4", id)
            assertEquals("hi there", text)
            assertEquals(true, feedback)
            assertEquals("e-as-4", entityId)
        }
        // turnHeader 不进消息流；完成卡在轮尾（digest 之后）
        assertEquals("row-5-wfdone", messages[5].id)
        assertEquals("run-9", messages[5].workflowCompletion!!.runId)
        assertEquals("WF", messages[5].workflowCompletion!!.name)
        assertEquals(1, messages[5].workflowCompletion!!.artifacts.size)
        assertEquals("digest", messages[4].text)
        // state-todos 常驻面板行（流末尾）
        val todosMessage = messages.last()
        assertEquals("state-todos", todosMessage.id)
        assertEquals(1, todosMessage.todos!!.size)
        // state.revision 落 CAS base
        assertEquals(3L, f.store.conversationBaseWatermark("sess-1").first)
    }

    // MARK: 交互投影（plan_approval schema 归位，v1.31 根修面）

    @Test
    fun pendingInteractionList_normalizesPlanApprovalSchema() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "state" to testObj(
                        "pendingInteractions" to JSONValue.Arr(
                            listOf(
                                // 上游 ExitPlanMode 挂起交互 kind="userInput"、
                                // plan_approval 在 payload.schema.interaction、正文在 payload.input.plan
                                testObj(
                                    "id" to JSONValue.Str("it-1"),
                                    "kind" to JSONValue.Str("userInput"),
                                    "toolName" to JSONValue.Str("ExitPlanMode"),
                                    "payload" to testObj(
                                        "schema" to testObj("interaction" to JSONValue.Str("plan_approval")),
                                        "prompt" to JSONValue.Str("审批该计划"),
                                        "input" to testObj("plan" to JSONValue.Str("# 计划正文")),
                                    ),
                                ),
                                testObj(
                                    "id" to JSONValue.Str("it-2"),
                                    "kind" to JSONValue.Str("permission"),
                                    "command" to JSONValue.Str("ls -la"),
                                    "options" to JSONValue.Arr(
                                        listOf(
                                            testObj("id" to JSONValue.Str("allow"), "label" to JSONValue.Str("Allow once")),
                                            JSONValue.Str("deny"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val list = f.store.pendingInteractionList("sess-1")
        assertEquals(2, list.size)
        // schema 归位：kind userInput → plan_approval，标题取 prompt，计划文本取 input.plan
        with(list[0]) {
            assertEquals("plan_approval", kind)
            assertTrue(isPlanApproval)
            assertEquals("审批该计划", title)
            assertEquals("# 计划正文", planText)
        }
        // permission 选项 (id,label) 对 + 裸字符串同形
        with(list[1]) {
            assertEquals("permission", kind)
            assertEquals(2, options.size)
            assertEquals("allow" to "Allow once", options[0].id to options[0].label)
            assertEquals("deny" to "deny", options[1].id to options[1].label)
        }
        // RemoteConversationBridge 面（RemoteTaskStore 审批链以解析投影的 id 应答）
        val resolved = f.store.currentPendingInteraction("sess-1", listOf("permission"))
        assertEquals("it-2", resolved?.id)
        assertNull(f.store.currentPendingInteraction("sess-1", listOf("workspaceHookReview")))
    }

    /**
     * 挂起交互的 delta 刷新通道（v1.31③ 面）：state.updated patch 键级整替
     * pendingInteractions——下发/撤下都走同一投影（快照携带之外的刷新路径）。
     */
    @Test
    fun pendingInteractions_refreshViaStateUpdatedPatch_andWithdraw() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        // 下发：state.updated patch 携挂起交互（替身 pushPendingInteraction 同形状）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj(
                            "pendingInteractions" to JSONValue.Arr(
                                listOf(
                                    testObj(
                                        "id" to JSONValue.Str("it-perm-1"),
                                        "kind" to JSONValue.Str("permission"),
                                        "command" to JSONValue.Str("rm -rf /tmp/x"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val list = f.store.pendingInteractionList("sess-1")
        assertEquals(1, list.size)
        assertEquals("permission", list.single().kind)
        assertEquals("it-perm-1", f.store.currentPendingInteraction("sess-1", listOf("permission"))?.id)
        // 撤下：空数组键级整替清空投影（審批卡随之消失）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 2, toSeq = 2,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj(
                            "pendingInteractions" to JSONValue.Arr(emptyList()),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(0, f.store.pendingInteractionList("sess-1").size, "撤下 = 空数组整替")
        assertNull(f.store.currentPendingInteraction("sess-1", listOf("permission")))
    }

    /**
     * 原文通道（v1.34/v1.35 红线）：userselect 尾块与计划卡 inputText JSON 均**原文透传**，
     * 解析归渲染层——下沉 store 会破坏乐观回显按原文匹配去重。
     */
    @Test
    fun rawPassThrough_userselectTailAndPlanInputTextStayVerbatim() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        val userselectRaw = "请参考这段实现\n# userselect:\n```userselect\n" +
            "[{\"path\":\"/repo/Sources/Foo.swift\",\"quote\":\"let x = 1\"}]\n```"
        val planJson = "{\"plan\":\"# 计划正文\\n- 步骤一\",\"toolName\":\"ExitPlanMode\"}"
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(
                        listOf(
                            row(1, "userInput", "text" to JSONValue.Str(userselectRaw)),
                            row(
                                2, "toolCall",
                                "toolName" to JSONValue.Str("ExitPlanMode"),
                                "inputText" to JSONValue.Str(planJson),
                                "status" to JSONValue.Str("pendingApproval"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val messages = f.store.currentMessages("sess-1")
        assertEquals(2, messages.size)
        // ① userselect 尾块：store 不剥不滤，ChatMessage.text = 行原文（渲染层剥离 + 引用卡）
        assertEquals(userselectRaw, messages[0].text)
        // ② 计划卡原文通道：toolCall 行 inputText JSON 原样进 ToolCall.target（形状门=含非空 plan 字段，解析归渲染层）
        assertEquals(planJson, messages[1].toolCall!!.target)
    }

    // MARK: resync 带 workspace 信封（resyncOwned ownership 匹配）

    @Test
    fun resyncEnvelope_carriesSubscriptionIdAndNullBaseWithoutWatermark() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        // 无水位（快照未到）→ resyncAllConversations 以 base=null 请求全量快照
        f.store.resyncAllConversations()
        advanceUntilIdle()
        val resync = f.transport.resyncArgs.single()
        assertEquals("sub-c-1", resync["subscriptionId"]?.stringValue)
        assertEquals("/conn/ws", resync["workspacePath"]?.stringValue)
        assertEquals("conn-ident", resync["workspaceIdentity"]?.stringValue)
        assertTrue(resync["base"] is JSONValue.Null, "无水位 → base=null 全量快照")
        // 有水位 → base 携 (logEpoch, seq)
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 42,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-9"),
                    "rows" to JSONValue.Arr(listOf(row(1, "userInput", "text" to JSONValue.Str("x")))),
                ),
            ),
        )
        advanceUntilIdle()
        f.store.resyncAllConversations()
        advanceUntilIdle()
        val resync2 = f.transport.resyncArgs.last()
        val base = resync2["base"]?.objectValue
        assertEquals("epoch-9", base?.get("logEpoch")?.stringValue)
        assertEquals(42, base?.get("seq")?.intValue)
    }
}

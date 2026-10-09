package cn.biuz.android.store

// RemoteConversationStore 发送面单测（步骤 2；对应 iOS 门禁面）：
// - sendCommand 唯一委托 ConversationCommandFactory（嵌套信封 + clientId 逐字 + issuedAt
//   毫秒整值 double + workspace 信封按归属寻址）；
// - CAS revision 未就绪本地拒发（client.casRevisionUnavailable 合成拒收，不上线缆）；
// - revisionAtDecision 回填 CAS base；
// - sendText 乐观回显：直发 append + lastYieldedMessages 基线同步（v1.32 教训）、
//   服务端行 sourceCommandId 精确消费、非 accepted 撤回显 + sendRejectionText 原文上屏；
// - 投影 watchdog：accepted 后 2s 投影静默 → 全量 resync；行到位/队列命中即收敛；
// - createSession 主路径（§7.6：draft 后发 sendText 是 proto.sessionNotFound 死路）：
//   firstInput + firstInput.modelSelection、在途创建去重（A12）、失败如实上屏；
// - 标题占位 10min 兜底回填（v1.31④）；
// - markUnread 写失败回滚本地置位（§5-12）。
// Mock 仅测试装配（红线 §0-4）。

import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 发送面回执便捷构造（accepted/rejected 六态词表直构）。 */
private fun ackOf(
    status: String,
    reasonCode: String? = null,
    message: String? = null,
    revisionAtDecision: Long? = null,
    sessionId: String? = null,
): JSONValue = testObj(
    "status" to JSONValue.Str(status),
    "reasonCode" to reasonCode?.let { JSONValue.Str(it) },
    "message" to message?.let { JSONValue.Str(it) },
    "revisionAtDecision" to revisionAtDecision?.let { testNum(it) },
    "result" to sessionId?.let { testObj("sessionId" to JSONValue.Str(it)) },
)

class RemoteConversationSendTest {

    // MARK: sendText 乐观回显 + sourceCommandId 精确消费

    @Test
    fun sendText_echoAppendedThenConsumedBySourceCommandId() = runTest {
        val f = Fixture(this)
        assertTrue(f.store.send("hello", "sess-1"))
        runCurrent()
        // 直发回显在位 + 基线已同步（v1.32 教训面：lastYieldedMessages 与订阅方实收一致）
        val afterSend = f.store.currentMessages("sess-1")
        assertEquals(1, afterSend.size)
        assertTrue(afterSend[0].id.startsWith("local-send-"), "实际：${afterSend.map { it.id }}")
        assertEquals("hello", afterSend[0].text)
        // 信封形状：嵌套形态 + clientId 逐字 + sessionId 键恒在场 + issuedAt 毫秒整值 double
        val args = f.transport.sendArgs.single()
        val envelope = args["envelope"]!!.objectValue!!
        assertEquals("sendText", envelope["type"]?.stringValue)
        assertEquals("sess-1", envelope["sessionId"]?.stringValue)
        assertEquals("test-client-1", envelope["clientId"]?.stringValue)
        assertTrue(envelope["issuedAt"] is JSONValue.Double, "毫秒 issuedAt 恒走整值 double（Int32 值域）")
        assertEquals("/conn/ws", args["workspacePath"]?.stringValue, "workspace 信封按归属寻址")
        assertEquals("hello", envelope["payload"]?.objectValue?.get("text")?.stringValue)
        val commandId = envelope["commandId"]?.stringValue
        // 服务端用户行到达（sourceCommandId = 发送 commandId）→ 精确消费回显
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("row.appended"),
                        "row" to testObj(
                            "rowId" to JSONValue.Int(100),
                            "kind" to JSONValue.Str("userInput"),
                            "text" to JSONValue.Str("hello"),
                            "sourceCommandId" to commandId?.let { JSONValue.Str(it) },
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val messages = f.store.currentMessages("sess-1")
        assertEquals(1, messages.size, "回显被精确消费（同文本多次发送不互吞）")
        assertEquals("row-100", messages.single().id)
        assertTrue(f.store.sendRejectionText("sess-1") == null)
    }

    @Test
    fun sendText_rejectedRevokesEchoAndSurfacesRejection() = runTest {
        val f = Fixture(this)
        f.transport.sendReply = ackOf(
            status = "rejected",
            reasonCode = "proto.sessionNotFound",
            message = "session not found",
        )
        assertFalse(f.store.send("hello", "sess-1"), "非 accepted = 未送达（写面禁假成功 §5-12）")
        runCurrent()
        assertEquals(0, f.store.currentMessages("sess-1").size, "乐观回显已撤销（在途表同步清除）")
        assertTrue(
            f.store.sendRejectionText("sess-1")!!.contains("proto.sessionNotFound"),
            "拒因原文上屏：${f.store.sendRejectionText("sess-1")}",
        )
    }

    @Test
    fun sendText_transportFailureSurfacesAmbiguousDelivery() = runTest {
        val f = Fixture(this)
        f.transport.sendError = RPCError(message = "RPC 超时：zcode-agent.sendConversationCommandV4", name = "TimeoutError")
        assertFalse(f.store.send("hello", "sess-1"))
        runCurrent()
        assertEquals(0, f.store.currentMessages("sess-1").size, "回执缺席同样撤销回显")
        assertTrue(
            f.store.sendRejectionText("sess-1")!!.contains("连接中断或超时"),
            "超时歧义口径（防假阴性下重发双开 turn）：${f.store.sendRejectionText("sess-1")}",
        )
        assertTrue(f.store.lastCommandErrorText().contains("超时"), "传输层原文在案（§5-12）")
    }

    @Test
    fun sendText_modelSelectionRidesFirstSend() = runTest {
        val f = Fixture(this)
        f.store.sendWithAttachments(
            text = "hi",
            attachments = listOf(OutgoingAttachment(ref = "att-1", fileName = "a.png", mime = "image/png", bytes = 3)),
            requestedDelivery = "startNow",
            modelSelection = NewSessionModelSelection(providerId = "prov-1", modelId = "glm-5", reasoningLevel = ""),
            conversationId = "sess-1",
        )
        runCurrent()
        val payload = f.transport.sendArgs.single()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
        assertEquals("startNow", payload["requestedDelivery"]?.stringValue)
        val selection = payload["modelSelection"]!!.objectValue!!
        assertEquals("prov-1", selection["providerId"]?.stringValue)
        assertEquals("glm-5", selection["modelId"]?.stringValue)
        assertNull(selection["options"], "档位缺席时整个 options 略去（web U7e 同形）")
        val attachment = payload["attachments"]!!.arrayValue!!.single().objectValue!!
        assertEquals("att-1", attachment["ref"]?.stringValue)
        assertEquals("image/png", attachment["mime"]?.stringValue)
        assertEquals(3, attachment["bytes"]?.intValue)
        // 档位在场 → options 携带
        f.store.sendWithAttachments(
            text = "hi2", attachments = emptyList(), requestedDelivery = null,
            modelSelection = NewSessionModelSelection("prov-1", "glm-5", "high"),
            conversationId = "sess-1",
        )
        runCurrent()
        val payload2 = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
        assertNull(payload2["requestedDelivery"], "null 不携键")
        assertEquals(
            "high",
            payload2["modelSelection"]!!.objectValue!!["options"]!!.objectValue!!["reasoningLevel"]?.stringValue,
        )
    }

    // MARK: CAS 本地拒发 + revisionAtDecision 回填

    @Test
    fun casRevisionUnavailable_rejectedLocallyWithoutWire() = runTest {
        val f = Fixture(this)
        val ack = f.store.sendCommand(
            type = "pauseGoal",
            sessionId = "sess-1",
            payload = testObj("text" to JSONValue.Str("goal")),
            casRevision = true, // 快照未到（无 revision/水位）→ Factory 构造期拒发
        )
        assertEquals("rejected", ack?.get("status")?.stringValue)
        assertEquals("client.casRevisionUnavailable", ack?.get("reasonCode")?.stringValue)
        assertEquals(0, f.transport.sendCallCount, "本地拒发的命令不空耗线缆")
        assertTrue(f.store.sendRejectionText == null)
    }

    @Test
    fun revisionAtDecision_backfillsCasBase() = runTest {
        val f = Fixture(this)
        f.transport.sendReply = ackOf(status = "accepted", revisionAtDecision = 42)
        f.store.send("hello", "sess-1")
        runCurrent()
        assertEquals(42L, f.store.conversationBaseWatermark("sess-1").first, "回执 revisionAtDecision 回填 CAS base")
    }

    // MARK: 投影 watchdog（accepted 后 2s 静默 → resync）

    @Test
    fun watchdog_resyncsAfter2sProjectionSilence_andConverges() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceTimeBy(1_000)
        runCurrent()
        // 快照落表 1 行（行表非空基线）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "rows" to JSONValue.Arr(listOf(row(1, "userInput", "text" to JSONValue.Str("seed")))),
                    "state" to testObj("pendingInteractions" to JSONValue.Arr(emptyList())),
                ),
            ),
        )
        runCurrent()
        assertEquals(0, f.transport.resyncArgs.size)
        // ① 行在窗口内照常到达 → watchdog 放弃（行数已越锚点）
        assertTrue(f.store.send("hello", "sess-1"))
        runCurrent()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 2, toSeq = 2,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("row.appended"),
                        "row" to testObj(
                            "rowId" to JSONValue.Int(100),
                            "kind" to JSONValue.Str("userInput"),
                            "text" to JSONValue.Str("hello"),
                            "sourceCommandId" to JSONValue.Str("late"),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(0, f.transport.resyncArgs.size, "行数已越锚点，watchdog 不触发")
        // ② 投影静默 2s → 全量 resync
        f.store.send("second", "sess-1")
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(1, f.transport.resyncArgs.size, "sendText accepted 后 2s 投影静默 → resync（v1.23）")
        assertEquals("sub-c-1", f.transport.resyncArgs.single()["subscriptionId"]?.stringValue)
        // ③ 队列命中（sourceCommandId 收口）→ 不触发（queue/guide 投递不产生行）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 3, toSeq = 3,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj(
                            "queue" to testObj(
                                "items" to JSONValue.Arr(
                                    listOf(testObj("sourceCommandId" to JSONValue.Str("queued-cmd"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        f.store.send("queued msg", "sess-1").let { /* accepted */ }
        // 将刚发送的 commandId 对齐队列条目：直接查最近一次发送的 commandId
        val lastCommandId = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["commandId"]?.stringValue
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 4, toSeq = 4,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj(
                            "queue" to testObj(
                                "items" to JSONValue.Arr(
                                    listOf(testObj("sourceCommandId" to (lastCommandId?.let { JSONValue.Str(it) } ?: JSONValue.Str("x")))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(1, f.transport.resyncArgs.size, "队列命中（sourceCommandId）= 收口，不误触发 resync")
    }

    // MARK: createSession 主路径（§7.6）

    @Test
    fun createSession_firstInputWithModelSelectionIsPrimaryPath() = runTest {
        val f = Fixture(this, withIndex = true)
        f.transport.sendReply = ackOf(status = "accepted", sessionId = "sess-new")
        val conversation = f.store.createConversation(
            title = "写个测试",
            directory = "/other/ws",
            executor = ExecutorKind.pairedMac,
            modelSelection = NewSessionModelSelection("prov-1", "glm-5", "high"),
        )
        assertEquals("sess-new", conversation.id, "sessionId 取回执 result.sessionId（假成功 UUID 已废除）")
        assertTrue(conversation.isRunning)
        val args = f.transport.sendArgs.single()
        val envelope = args["envelope"]!!.objectValue!!
        assertEquals("createSession", envelope["type"]?.stringValue)
        assertTrue(envelope.containsKey("sessionId"), "A1：sessionId 键恒在场")
        assertNull(envelope["sessionId"], "createSession 信封 sessionId=null")
        val payload = envelope["payload"]!!.objectValue!!
        assertEquals("/other/ws", payload["workspaceId"]?.stringValue)
        val firstInput = payload["firstInput"]!!.objectValue!!
        assertEquals("写个测试", firstInput["text"]?.stringValue)
        val selection = firstInput["modelSelection"]!!.objectValue!!
        assertEquals("prov-1", selection["providerId"]?.stringValue)
        assertEquals("glm-5", selection["modelId"]?.stringValue)
        assertEquals("high", selection["options"]!!.objectValue!!["reasoningLevel"]?.stringValue)
        assertEquals("/other/ws", args["workspacePath"]?.stringValue, "信封 workspacePath 与 workspaceId 同源")
        // 新会话即刻入表（否则列表一重载新建行消失）
        assertEquals("写个测试", f.index!!.sessionSummaries()["sess-new"]?.title)
        assertEquals("running", f.index!!.sessionSummaries()["sess-new"]?.phase)
    }

    @Test
    fun createSession_draftPathPromotesAndOmitsFirstInput() = runTest {
        val f = Fixture(this)
        f.transport.sendReply = ackOf(status = "accepted", sessionId = "sess-draft")
        val conversation = f.store.createConversation("", "/conn/ws", ExecutorKind.pairedMac, null)
        assertEquals("sess-draft", conversation.id)
        assertFalse(conversation.isRunning)
        val payload = f.transport.sendArgs.single()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
        assertNull(payload["firstInput"], "draft 空会话不携 firstInput")
        assertEquals("/conn/ws", payload["workspaceId"]?.stringValue)
        assertTrue(f.ops.contains("call:zcode-agent:promoteDeferredDraftSession"), "draft 转正写（重启不丢）")
    }

    @Test
    fun createSession_failureSurfacesReasonWithoutFakeId() = runTest {
        val f = Fixture(this)
        f.transport.sendReply = ackOf(status = "rejected", reasonCode = "proto.invalidPayload", message = "providerId too short")
        val conversation = f.store.createConversation("t", "", ExecutorKind.pairedMac, null)
        assertEquals("", conversation.id, "拒绝时禁凭空造本地会话")
        assertTrue(
            f.store.lastCreateFailureText().contains("proto.invalidPayload"),
            "拒因原文上屏：${f.store.lastCreateFailureText()}",
        )
        // 传输失败口径
        f.transport.sendReply = ackOf(status = "accepted", sessionId = "should-not-reach")
        f.transport.sendError = RPCError(message = "等待服务端 Initialize 超时", name = "TimeoutError")
        val conversation2 = f.store.createConversation("t2", "", ExecutorKind.pairedMac, null)
        assertEquals("", conversation2.id)
        assertTrue(f.store.lastCreateFailureText().contains("超时"), "超时专属口径（不纵容连续重试）")
    }

    @Test
    fun createConversation_dedupesInflightCreates() = runTest {
        val f = Fixture(this)
        f.transport.sendReply = ackOf(status = "accepted", sessionId = "sess-dedup")
        val gate = CompletableDeferred<Unit>()
        f.transport.sendGate = gate
        var first: Conversation? = null
        var second: Conversation? = null
        val job1 = launch { first = f.store.createConversation("same", "", ExecutorKind.pairedMac, null) }
        val job2 = launch { second = f.store.createConversation("same", "", ExecutorKind.pairedMac, null) }
        runCurrent()
        assertEquals(1, f.transport.sendCallCount, "同参并发创建合并为一次 createSession（A12 防双写）")
        gate.complete(Unit)
        advanceUntilIdle()
        job1.join()
        job2.join()
        assertEquals("sess-dedup", first?.id)
        assertEquals("sess-dedup", second?.id, "共享同一结果")
    }

    // MARK: 标题占位 10min 兜底回填（v1.31④）

    @Test
    fun metaRefresh_backfillsPlaceholderTitleWithinWindow() = runTest {
        val f = Fixture(this, withIndex = true)
        f.index!!.upsertLocalSession(
            SessionSummary(
                sessionId = "sess-1",
                title = "新会话",
                phase = "running",
                lastActivityAt = 1L,
                workspacePath = "/conn/ws",
            ),
        )
        assertTrue(f.store.send("hello", "sess-1"), "占位标题会话发送 → 登记兜底刷新窗")
        // 窗口未到期不拉取
        f.store.conversations()
        runCurrent()
        assertEquals(0, f.transport.listSessionsCallCount, "首试前（15s 内）不拉取")
        advanceTimeBy(15_001)
        // 到期单区 listSessions 拉取 + 字段级回填
        f.transport.listSessionsItems = listOf(
            testObj(
                "sessionId" to JSONValue.Str("sess-1"),
                "title" to JSONValue.Str("桌面自动命名标题"),
                "phase" to JSONValue.Str("running"),
                "workspacePath" to JSONValue.Str("/conn/ws"),
            ),
        )
        f.store.conversations()
        advanceUntilIdle()
        assertEquals(1, f.transport.listSessionsCallCount)
        assertEquals("桌面自动命名标题", f.index!!.sessionSummaries()["sess-1"]?.title, "标题落定回填")
        // 目标标题落定即注销：再读列表不再拉取
        f.store.conversations()
        advanceUntilIdle()
        assertEquals(1, f.transport.listSessionsCallCount, "标题已落定，注销刷新窗")
    }

    // MARK: markUnread 写失败回滚本地置位（§5-12）

    @Test
    fun markUnread_rollsBackLocalFlagOnWriteFailure() = runTest {
        val f = Fixture(this, withIndex = true)
        val index = f.index!!
        index.upsertLocalSession(
            SessionSummary(
                sessionId = "sess-1",
                title = "T",
                phase = "running",
                lastActivityAt = 1L,
                workspacePath = "/conn/ws",
            ),
        )
        // 成功路径：本地置位 + 远端写 + 失败文本清零
        f.store.markUnread("sess-1")
        advanceUntilIdle()
        assertEquals(true, index.unreadFlags["sess-1"])
        assertEquals(1, f.transport.setTaskUnreadCallCount)
        assertEquals("", f.store.lastTaskWriteFailureText())
        // 写失败：回滚本地置位 + 拒因上屏（回滚=可见失败）
        f.transport.setTaskUnreadError = RPCError(message = "fault.task.notFound", name = "Error")
        f.store.markUnread("sess-1")
        advanceUntilIdle()
        assertNull(index.unreadFlags["sess-1"], "写失败回滚本地置位")
        assertTrue(f.store.lastTaskWriteFailureText().contains("fault.task.notFound"), "拒因原文上屏")
        // 寻址：行归属 workspace + unread:true
        val arg = f.transport.ops.count { it == "call:zcode-task:setTaskUnread" }
        assertEquals(2, arg)
    }

    // MARK: held 队列确认重发（§11.2）

    @Test
    fun heldQueue_pendingThenConfirmedResendCarriesDisposition() = runTest {
        val f = Fixture(this)
        f.transport.sendReply = ackOf(status = "rejected", reasonCode = "heldQueueDispositionRequired")
        assertFalse(f.store.send("hi", "sess-1"))
        runCurrent()
        assertTrue(f.store.heldQueueConfirmationPending("sess-1"), "held 拒因 → 待确认标记")
        f.transport.sendReply = ackOf(status = "accepted")
        val ok = f.store.sendHeldConfirmed(
            text = "hi",
            attachments = emptyList(),
            disposition = "clearQueueAndSend",
            expectedHeldQueueItemIds = listOf("q-1", "q-2"),
            requestedDelivery = null,
            modelSelection = null,
            conversationId = "sess-1",
        )
        assertTrue(ok)
        runCurrent()
        val payload = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
        assertEquals("clearQueueAndSend", payload["heldQueueDisposition"]?.stringValue)
        assertEquals(
            listOf("q-1", "q-2"),
            payload["expectedHeldQueueItemIds"]?.arrayValue?.map { it.stringValue },
        )
        assertFalse(f.store.heldQueueConfirmationPending("sess-1"), "确认重发成功即清")
    }

    @Test
    fun queueHeldItemIds_readsSnapshotQueue() = runTest {
        val f = Fixture(this)
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("epoch-1"),
                    "state" to testObj(
                        "queue" to testObj(
                            "items" to JSONValue.Arr(
                                listOf(
                                    testObj("queueItemId" to JSONValue.Str("q-1")),
                                    testObj("itemId" to JSONValue.Str("q-2")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(listOf("q-1", "q-2"), f.store.queueHeldItemIds("sess-1"), "queueItemId 宽容链")
    }
}

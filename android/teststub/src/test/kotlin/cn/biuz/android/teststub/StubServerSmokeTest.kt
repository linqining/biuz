package cn.biuz.android.teststub

import cn.biuz.android.protocol.ChannelClient
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.ConversationCommandFactory
import cn.biuz.android.protocol.ConversationCommandRequest
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.TopicWireFrame
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.protocol.WebSocketTransportFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * 替身服务器冒烟（E2E 验收基建的首个门禁）：真实本地回环 socket 串起 :protocol 的
 * ChannelClient ↔ Ktor E2EStubServer，覆盖 docs/Android开发交接.md §9-1 的骨架面——
 * 握手（A5）→ 订阅（A8 快照先于回执）→ sendConversationCommandV4（strict 对拍 + A13
 * accepted 回执）→ 帧序与载荷记账断言（用户行携 sourceCommandId、seq 单调、subId 一致）。
 */
class StubServerSmokeTest {

    // MARK: 用例 1：主链路冒烟（握手→订阅→命令→accepted→帧序/记账）

    @Test
    fun smoke_handshake_subscribe_sendText_ack_framesAndLedger() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            val clientId = "zcode-mobile-smoke-1"
            val sink = EventSink()
            client = newClient()

            // ① 连接：服务端连接建立即推 Initialize（channelServer.ts:29-32），ChannelClient.connect 等它
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            assertTrue(server.hasEventListener(E2EStubServer.EVENT_CONVERSATION_FRAME) == false)

            // 帧 handler 先于订阅注册（工程纪律 §5-3：晚注册静默丢帧）
            val convSub = sink.install(client, E2EStubServer.EVENT_CONVERSATION_FRAME, server.workspacePath)
            val sessSub = sink.install(client, E2EStubServer.EVENT_SESSIONS_INDEX_FRAME, server.workspacePath)
            // eventListen 帧异步到达，等服务端登记完成再继续（订阅即发快照，晚一帧即丢）
            awaitUntil("服务端登记 conversation 帧监听") { server.hasEventListener(E2EStubServer.EVENT_CONVERSATION_FRAME) }
            awaitUntil("服务端登记 sessions-index 帧监听") { server.hasEventListener(E2EStubServer.EVENT_SESSIONS_INDEX_FRAME) }

            // ② hello：protocolVersion=3 + clientMode + deliveryProfile（上游 superRefine 配对）+
            //    capabilities 恒等于 host 能力集（A5 回显面）+ serverTime 为数值（core.ts:19 z.number()）
            val hello = client.call("zcode-agent", "helloConversationV4").jsonValue!!
            assertEquals(3, hello["protocolVersion"]?.intValue)
            assertEquals("hello", hello["kind"]?.stringValue)
            assertEquals("web-remote-replayable", hello["clientMode"]?.stringValue)
            assertEquals("replayable", hello["deliveryProfile"]?.stringValue)
            assertEquals(server.serverCapabilities.toMap(), hello["capabilities"]?.objectValue)
            assertNotNull(hello["serverTime"]?.doubleValue, "上游 timestampSchema=z.number()，serverTime 应为数值")

            // ③ clientHello：strict 通过 + 替身登记 clientId（A2 权威源）
            val initAck = client.call(
                "zcode-agent",
                "initializeConversationV4",
                clientHelloJson(clientId),
            ).jsonValue!!
            assertEquals(true, initAck["ok"]?.boolValue)
            val handshake = server.handshakes().single()
            assertTrue(handshake.accepted)
            assertTrue(handshake.errors.isEmpty())
            assertTrue(handshake.capabilityViolations.isEmpty())
            assertEquals(clientId, handshake.clientId)

            // ④ 订阅 sessions-index：A8（快照帧先于订阅回执）+ 回执 subId 与帧一致
            val sessTopic = "sessions-index/${server.workspacePath}"
            val sessAck = client.call(
                "zcode-agent",
                "subscribeSessionsIndexV4",
                RPCValue.json(stubObj("topic" to JSONValue.Str(sessTopic), "workspacePath" to JSONValue.Str(server.workspacePath))),
            ).jsonValue!!
            val markerAfterSessAck = sink.order.incrementAndGet()
            assertEquals(E2EStubServer.SUB_ID_SESSIONS_INDEX, sessAck["subscriptionId"]?.stringValue)
            val sessSnap = awaitEvent(sink, E2EStubServer.EVENT_SESSIONS_INDEX_FRAME)
            assertTrue(sessSnap.order < markerAfterSessAck, "A8：快照事件处理应先于订阅回执返回")
            assertEquals(3, sessSnap.envelope?.wireVersion, "上游 V4_WIRE_PROTOCOL_VERSION=3（wire-codec.ts:122）")
            assertEquals("initial", sessSnap.envelope?.deliveryKind)
            assertEquals(sessTopic, sessSnap.envelope?.topic)
            assertEquals(sessAck["subscriptionId"]?.stringValue, sessSnap.envelope?.subscriptionId, "帧 subId 与回执一致")
            assertEquals(2, sessSnap.logical?.snapshot?.get("sessions")?.arrayValue?.size)
            // 服务端 wire 序：快照帧 order < 订阅回执 order（A8 权威断言）
            val sessLog = server.wireLog()
            val sessSnapOrder = sessLog.first { it.kind == E2EStubServer.WIRE_EVENT_FRAME && it.detail.endsWith("|snapshot") && it.detail.contains("sessions-index") }.order
            val sessAckOrder = sessLog.first { it.kind == E2EStubServer.WIRE_PROMISE_SUCCESS && it.detail == "subscribeSessionsIndexV4" }.order
            assertTrue(sessSnapOrder < sessAckOrder, "A8：sessions-index 快照帧必须先于订阅回执帧（wire 序）")

            // ⑤ 订阅 conversation：A8 同上 + 快照携带 revision/logEpoch/seq（水位回填数据源）
            val convTopic = "conversation/sess-e2e-1"
            val convAck = client.call(
                "zcode-agent",
                "subscribeConversationV4",
                RPCValue.json(stubObj("topic" to JSONValue.Str(convTopic), "workspacePath" to JSONValue.Str(server.workspacePath))),
            ).jsonValue!!
            val markerAfterConvAck = sink.order.incrementAndGet()
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, convAck["subscriptionId"]?.stringValue)
            val convSnap = awaitEvent(sink, E2EStubServer.EVENT_CONVERSATION_FRAME)
            assertTrue(convSnap.order < markerAfterConvAck)
            assertEquals("initial", convSnap.envelope?.deliveryKind)
            assertEquals(convAck["subscriptionId"]?.stringValue, convSnap.envelope?.subscriptionId)
            val convSnapshot = convSnap.logical?.snapshot!!
            assertEquals(StubV4Frames.DEFAULT_REVISION, convSnapshot["revision"]?.intValue)
            assertEquals(StubV4Frames.DEFAULT_LOG_EPOCH, convSnapshot["logEpoch"]?.stringValue)
            assertEquals(0, convSnapshot["seq"]?.intValue)
            assertEquals("sess-e2e-1", convSnapshot["sessionId"]?.stringValue)

            // ⑥ 发命令：信封走 :protocol 的 ConversationCommandFactory 单点构造（发送面与被测客户端同源）
            val commandId = "cmd-e2e-sendtext-0001"
            val built = ConversationCommandFactory.build(
                request = ConversationCommandRequest(
                    type = "sendText",
                    sessionId = "sess-e2e-1",
                    payload = stubObj("text" to JSONValue.Str("你好，替身")),
                    casRevision = null, // sendText 非 CAS 词表
                    workspacePath = server.workspacePath,
                    workspaceIdentity = null,
                    commandId = commandId,
                ),
                clientId = clientId,
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            val ackValue = client.call("zcode-agent", "sendConversationCommandV4", RPCValue.json(built.outer)).jsonValue!!

            // A13：accepted 回执 + commandId 回显 + revisionAtDecision 恒在场（上游 commandAckSchema 必填）
            val ack = CommandAck(ackValue)
            assertEquals(commandId, ackValue["commandId"]?.stringValue)
            assertEquals("accepted", ack.status)
            assertTrue(ack.isAuthoritativeSuccess)
            assertNotNull(ack.revisionAtDecision, "上游 commandAckSchema：revisionAtDecision 必填 number（command.ts:439）")

            // ⑦ strict 对拍断言（必查项）：入站信封通过 strict 校验
            val envelopeRecord = server.envelopes().single { it.type == "sendText" }
            assertTrue(envelopeRecord.schemaErrors.isEmpty(), "入站信封必须通过 strict 校验，实际：${envelopeRecord.schemaErrors}")
            assertTrue(envelopeRecord.schemaWarnings.isEmpty())
            assertEquals(true, envelopeRecord.clientIdMatchesHandshake, "A2：信封 clientId 与握手逐字相等")
            assertEquals(null, envelopeRecord.casGateError)
            assertEquals(server.workspacePath, envelopeRecord.workspacePath)
            assertEquals("sess-e2e-1", envelopeRecord.sessionId?.stringValue)
            assertNotNull(envelopeRecord.issuedAt, "issuedAt 毫秒数恒在场")
            assertEquals("accepted", envelopeRecord.ackStatus)
            assertEquals(1, server.commandCallCount("sendText"))
            assertEquals(1, server.rpcCallCount("sendConversationCommandV4"))

            // ⑧ 载荷记账：sendText 副作用 = 用户行（携 sourceCommandId）+ 助手行
            assertEquals("你好，替身", server.lastSendTextPayload()?.get("text")?.stringValue)
            awaitUntil("会话行记账（2 行）") { server.rowsOf("sess-e2e-1").size == 2 }
            assertEquals(2, server.rowsOf("sess-e2e-1").size)

            // ⑨ 帧序与行形状：用户行先、助手行后；seq 单调（快照后 0→1→2）；subId 一致；
            //    用户行携 sourceCommandId=信封 commandId（上游真实形态，rows.ts:141）
            val deltas = awaitEvents(sink, E2EStubServer.EVENT_CONVERSATION_FRAME, count = 2) { it.logical?.deltas?.isNotEmpty() == true }
            assertEquals(2, deltas.size)
            val userDelta = deltas[0]
            val userRow = userDelta.logical?.deltas?.first()?.get("row")!!
            assertEquals(0, userDelta.logical?.fromSeq)
            assertEquals(1, userDelta.logical?.toSeq)
            assertEquals("online", userDelta.envelope?.deliveryKind)
            assertEquals(commandId, userRow["sourceCommandId"]?.stringValue, "上游 rows.ts:141：realUser 行必携 sourceCommandId")
            assertEquals("realUser", userRow["origin"]?.stringValue)
            assertEquals("userInput", userRow["kind"]?.stringValue)
            assertEquals("你好，替身", userRow["text"]?.stringValue)
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, userDelta.envelope?.subscriptionId)
            val assistantDelta = deltas[1]
            val assistantRow = assistantDelta.logical?.deltas?.first()?.get("row")!!
            assertEquals(1, assistantDelta.logical?.fromSeq, "seq 每 topic 单调")
            assertEquals(2, assistantDelta.logical?.toSeq)
            assertEquals("assistantText", assistantRow["kind"]?.stringValue)
            assertEquals("complete", assistantRow["state"]?.stringValue)

            // ⑩ wire 序：命令 accepted 回执先于两行增量帧
            val cmdLog = server.wireLog()
            val sendAckOrder = cmdLog.last { it.kind == E2EStubServer.WIRE_PROMISE_SUCCESS && it.detail == "sendConversationCommandV4" }.order
            val rowFrameOrders = server.framesSent().filter { it.payloadKind == "deltas" }.map { it.order }
            assertEquals(2, rowFrameOrders.size)
            assertTrue(rowFrameOrders.all { it > sendAckOrder }, "命令回执先于行增量帧")

            convSub.cancel()
            sessSub.cancel()
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: 用例 2：A5 单向宣告负路 + strict 对拍负路

    @Test
    fun a5_capabilityClaimBeyondHostRejected_and_strictEnvelopeRejected() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client1: ChannelClient? = null
        var client2: ChannelClient? = null
        try {
            // host 未宣告 workflowRunDeltas（A5 反向面）
            server.serverCapabilities.remove("workflowRunDeltas")

            client1 = newClient()
            client1.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client1.call("zcode-agent", "helloConversationV4")
            val clientId1 = "zcode-mobile-smoke-2a"
            val overClaimError = runCatching {
                client1.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId1, claimWorkflowRunDeltas = true))
            }.exceptionOrNull()
            assertTrue(overClaimError is RPCError, "host 未宣告却宣告 workflowRunDeltas → clientHello 必须被拒（A5）")
            assertTrue(overClaimError?.message?.contains("stub.clientHelloRejected") == true)
            val rejectedHandshake = server.handshakes().last()
            assertTrue(!rejectedHandshake.accepted)
            assertTrue(rejectedHandshake.capabilityViolations.isNotEmpty(), "记账面记录 A5 违规")

            // 旧客户端语义：不携 capabilities 键 = 合法（transport.ts:77 缺失代表旧客户端）
            client2 = newClient()
            client2.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client2.call("zcode-agent", "helloConversationV4")
            val clientId2 = "zcode-mobile-smoke-2b"
            val legacyAck = client2.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId2, withCapabilities = false)).jsonValue!!
            assertEquals(true, legacyAck["ok"]?.boolValue)

            // strict 对拍负路：信封带未知键（strict）→ rejected + proto.invalidPayload + 记账 schemaErrors 非空
            val badEnvelope = stubObj(
                "commandId" to JSONValue.Str("cmd-e2e-bad-0001"),
                "clientId" to JSONValue.Str(clientId2),
                "type" to JSONValue.Str("sendText"),
                "payload" to stubObj("text" to JSONValue.Str("x")),
                "issuedAt" to stubNum(System.currentTimeMillis()),
                "sessionId" to JSONValue.Str("sess-e2e-1"),
                "phantomKey" to JSONValue.Str("上游信封无此键"),
            )
            val ack = client2.call(
                "zcode-agent",
                "sendConversationCommandV4",
                RPCValue.json(stubObj("envelope" to badEnvelope, "workspacePath" to JSONValue.Str(server.workspacePath))),
            ).jsonValue!!
            val badAck = CommandAck(ack)
            assertEquals("rejected", badAck.status)
            assertEquals("proto.invalidPayload", badAck.reasonCode)
            val badRecord = server.envelopes().last()
            assertTrue(badRecord.schemaErrors.isNotEmpty(), "strict 对拍必须记下未知键错误")
            assertTrue(badRecord.schemaErrors.first().contains("phantomKey"))
        } finally {
            client1?.disconnect()
            client2?.disconnect()
            server.stop()
        }
    }

    // MARK: 用例 3：CAS 门 + stale-once 注水 + applyFileRewind 边界计数 + ACK 六态构词

    @Test
    fun casGate_staleOnceThenAccept_blockedWrite_and_ackVocabulary() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            val clientId = "zcode-mobile-smoke-3"
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client.call("zcode-agent", "helloConversationV4")
            client.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId))

            fun envelope(type: String, payload: JSONValue, withCas: Boolean = false, logEpoch: String? = StubV4Frames.DEFAULT_LOG_EPOCH): RPCValue {
                val fields = LinkedHashMap<String, JSONValue>()
                fields["commandId"] = JSONValue.Str("cmd-e2e-$type")
                fields["clientId"] = JSONValue.Str(clientId)
                fields["type"] = JSONValue.Str(type)
                fields["payload"] = payload
                fields["issuedAt"] = stubNum(System.currentTimeMillis())
                fields["sessionId"] = JSONValue.Str("sess-e2e-1")
                if (withCas) fields["baseRevision"] = JSONValue.Int(42)
                if (withCas && logEpoch != null) fields["baseLogEpoch"] = JSONValue.Str(logEpoch)
                return RPCValue.json(
                    stubObj(
                        "envelope" to JSONValue.Obj(fields),
                        "workspacePath" to JSONValue.Str(server.workspacePath),
                    ),
                )
            }

            // ① CAS 词表命令缺 baseRevision → 上游同文拒收（command.ts:340-367 镜像）
            val casMissing = client.call(
                "zcode-agent",
                "sendConversationCommandV4",
                envelope("switchModelConfig", stubObj("provider" to JSONValue.Str("p"), "model" to JSONValue.Str("m"), "thought" to JSONValue.Str(""))),
            ).jsonValue!!
            assertEquals("rejected", CommandAck(casMissing).status)
            assertTrue(CommandAck(casMissing).message?.contains("CAS commands require baseRevision") == true)

            // ② stale-once 注水（默认行为）：首击 stale + revisionAtDecision=真机实证值；重发 accepted
            val staleAck = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope("switchModelConfig", stubObj("provider" to JSONValue.Str("p"), "model" to JSONValue.Str("m"), "thought" to JSONValue.Str("")), withCas = true),
                ).jsonValue!!,
            )
            assertEquals("stale", staleAck.status)
            assertEquals("proto.staleRevision", staleAck.reasonCode)
            assertEquals(StubAck.DEFAULT_STALE_REVISION_AT_DECISION, staleAck.revisionAtDecision)
            val retryAck = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope("switchModelConfig", stubObj("provider" to JSONValue.Str("p"), "model" to JSONValue.Str("m"), "thought" to JSONValue.Str("")), withCas = true),
                ).jsonValue!!,
            )
            assertEquals("accepted", retryAck.status, "CAS 重试链：原样重发一次即命中（sendCASWithRetry 纪律）")
            assertEquals(3, server.commandCallCount("switchModelConfig"))

            // ③ applyFileRewind：移动端唯一拦截的 v4 type → 边界计数（合规信封需 CAS + baseLogEpoch 双全）
            val rewindAck = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(
                        "applyFileRewind",
                        stubObj("target" to stubObj("rowId" to JSONValue.Int(1), "entityId" to JSONValue.Str("ent-1"))),
                        withCas = true,
                    ),
                ).jsonValue!!,
            )
            assertEquals("accepted", rewindAck.status)
            assertEquals(1, server.blockedWriteCount())

            // ④ ACK 六态构词：全部落在上游词表（command.ts:437-443），reasonCode/message/revision 形状齐备
            val behaviorCases = listOf(
                StubAckBehavior.Accept(),
                StubAckBehavior.Reject("stub.rejected", "拒绝原因"),
                StubAckBehavior.Stale(revisionAtDecision = 7),
                StubAckBehavior.StaleOnceThenAccept,
                StubAckBehavior.Duplicate,
                StubAckBehavior.Noop,
                StubAckBehavior.Failed("stub.failed", "失败原因"),
            )
            for (behavior in behaviorCases) {
                val body = StubAck.body("cmd-vocab", behavior, nthCall = 2, revisionAtDecision = 5)
                val status = StubAck.statusOf(body)
                assertTrue(StubAck.statusVocabulary.contains(status), "六态词表内：$status")
                assertEquals("cmd-vocab", body["commandId"]?.stringValue)
                assertNotNull(body["revisionAtDecision"]?.intValue, "A13：revisionAtDecision 恒在场")
            }
            val absent = StubAck.body("cmd-vocab", StubAckBehavior.StatusAbsent, nthCall = 1, revisionAtDecision = 5)
            assertEquals(null, StubAck.statusOf(absent), "StatusAbsent = 旧桌面无 status 键宽容形态")
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: 夹具

    private fun newClient(): ChannelClient =
        ChannelClient(WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
            JavaNetWebSocketTransport(url, timeoutMs, maximumMessageSize)
        })

    private fun clientHelloJson(
        clientId: String,
        withCapabilities: Boolean = true,
        claimWorkflowRunDeltas: Boolean = false,
    ): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["kind"] = JSONValue.Str("clientHello")
        fields["protocolVersion"] = JSONValue.Int(3)
        fields["clientId"] = JSONValue.Str(clientId)
        fields["clientKind"] = JSONValue.Str("mobileApp")
        fields["appVersion"] = JSONValue.Str("0.1.0-smoke")
        if (withCapabilities) {
            val caps = LinkedHashMap<String, JSONValue>()
            if (claimWorkflowRunDeltas) caps["workflowRunDeltas"] = JSONValue.Bool(true)
            fields["capabilities"] = JSONValue.Obj(caps)
        }
        return RPCValue.json(JSONValue.Obj(fields))
    }

    private class ReceivedEvent(
        val order: Int,
        val eventName: String,
        val envelope: TopicWireFrame.Envelope?,
        val logical: V4TopicFrame?,
    )

    /** 下行帧事件槽：帧 handler 即装（先于订阅，§5-3）；order 供到端序断言。 */
    private class EventSink {
        val order = AtomicInteger(0)
        private val received = ConcurrentLinkedQueue<ReceivedEvent>()

        suspend fun install(client: ChannelClient, eventName: String, workspacePath: String): cn.biuz.android.protocol.EventSubscription {
            return client.listen(
                "zcode-agent",
                eventName,
                RPCValue.json(stubObj("workspacePath" to JSONValue.Str(workspacePath))),
            ) { payload ->
                val json = payload.jsonValue
                val env = json?.let { TopicWireFrame.parse(it) }
                val logical = env?.completeFrame?.let { V4TopicFrame.parse(it) }
                received.add(ReceivedEvent(order.incrementAndGet(), eventName, env, logical))
            }
        }

        fun allOf(eventName: String): List<ReceivedEvent> = received.toList().filter { it.eventName == eventName }
    }

    private fun awaitEvent(
        sink: EventSink,
        eventName: String,
        predicate: (ReceivedEvent) -> Boolean = { true },
    ): ReceivedEvent {
        awaitUntil("事件 $eventName") { sink.allOf(eventName).any(predicate) }
        return sink.allOf(eventName).first(predicate)
    }

    private fun awaitEvents(
        sink: EventSink,
        eventName: String,
        count: Int,
        predicate: (ReceivedEvent) -> Boolean = { true },
    ): List<ReceivedEvent> {
        awaitUntil("事件 $eventName × $count") { sink.allOf(eventName).count(predicate) >= count }
        return sink.allOf(eventName).filter(predicate).take(count)
    }

    private fun awaitUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
            Thread.sleep(20)
        }
    }
}

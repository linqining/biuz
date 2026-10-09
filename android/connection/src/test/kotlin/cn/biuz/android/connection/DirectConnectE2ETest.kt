package cn.biuz.android.connection

import cn.biuz.android.protocol.AckActivationBarrier
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.ConversationCommandFactory
import cn.biuz.android.protocol.ConversationCommandRequest
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.teststub.E2EStubServer
import cn.biuz.android.teststub.StubV4Frames
import cn.biuz.android.teststub.stubObj
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import cn.biuz.android.connection.ZCodeServerConnection.ConnectionState

/**
 * :connection 直连 E2E（任务验收面）：真实 OkHttp WebSocket ↔ Ktor 替身（127.0.0.1 回环），
 * 走 ZCodeServerConnection 五步全链路（协议文档 §3.1/§3.2/§4/§5/§6）：
 * ① 建链（server-info HTTP 探测 + WS 升级）→ v4 握手（hello→clientHello，A5 能力单向
 *    回显 + A2 clientId 单一来源）→ workspace 挂载三路帧面 + workspace-config 订阅；
 * ② 发起一次 call（sendConversationCommandV4，信封走单点 Factory）→ accepted 回执 →
 *    帧序断言（命令回执先于行增量帧；用户行携 sourceCommandId）；
 * ③ ReadOnlyGate 出口拦截（file.writeTextFile 本地即拒、零上线）+ handshakeRequired
 *    捕获分支（LAN 无中继桥 → 如实上抛原文）+ 断线回落通知；
 * ④ A8 屏障场景：快照帧先于订阅回执——连接层帧 handler 先注册不丢帧 + 协议层
 *    AckActivationBarrier 暂存/原序回放（subId 先落库纪律）。
 */
class DirectConnectE2ETest {

    // MARK: 用例 1：五步全链路 + 一次 call + 帧序/回执/边界

    @Test
    fun directConnect_fiveSteps_handshake_call_frameOrder_readOnlyGate_andDrop(): Unit = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        val connection = ZCodeServerConnection(appVersion = "0.1.0-e2e")
        var droppedDetail: String? = null
        connection.onConnectionDropped = { droppedDetail = it }
        try {
            // 未连接态出口守卫：call 必须本地拒（NotConnected），不触网
            val notConnected = runCatching { connection.call("zcode-agent", "listSessions") }.exceptionOrNull()
            assertTrue(notConnected is RPCError && notConnected.name == "NotConnected",
                "未连接 call 应 NotConnected，实际：$notConnected")

            // ① 五步连接（token 空 = 免鉴权口径）
            // 注：handler 注册必须在 connect 之后——connect 首步 disconnect()（iOS
            // swift:293 同构）会 teardown 清空 frameHandlers；§5-3 纪律的时序锚点是
            // 「handler 先于产生帧的 subscribe」，本用例的订阅/发送都发生在其之后。
            val result = connection.connect(
                server = ServerConfig(id = "e2e", name = null, host = "127.0.0.1", port = port, useTLS = false, token = ""),
            )
            // 帧 handler 先于一切会话域 subscribe 注册（工程纪律 §5-3：晚注册静默丢帧）
            val frames = ConcurrentLinkedQueue<V4TopicFrame>()
            connection.setFrameHandler(topic = "conversation/sess-e2e-1") { frames.add(it) }
            val info = (result as? ConnectResult.Success)?.info
                ?: return@runBlocking fail("连接应成功，实际：${(result as? ConnectResult.Failure)?.error}")
            val connected = connection.state.value as? ConnectionState.Connected
                ?: return@runBlocking fail("连接后 state 应 Connected，实际：${connection.state.value}")
            assertEquals("stub-server-e2e", connected.info.serverId)
            assertEquals(server.workspacePath, connected.workspace.path)
            assertEquals(server.workspacePath, connection.workspace?.path)
            assertEquals(1, server.serverInfoProbeCount(), "server-info 首试成功不重试")

            // 步骤序（服务端 wire 序）：Initialize → hello 回执 → clientHello 回执
            val wire = server.wireLog()
            val initOrder = wire.first { it.kind == E2EStubServer.WIRE_INITIALIZE }.order
            val helloOrder = wire.first { it.kind == E2EStubServer.WIRE_PROMISE_SUCCESS && it.detail == "helloConversationV4" }.order
            val clientHelloOrder = wire.first { it.kind == E2EStubServer.WIRE_PROMISE_SUCCESS && it.detail == "initializeConversationV4" }.order
            assertTrue(initOrder < helloOrder && helloOrder < clientHelloOrder, "wire 序必须 initialize → hello → clientHello")

            // A2/A5：clientId 单一来源（连接层生成、握手注册逐字一致）+ 能力单向回显
            val handshake = server.handshakes().single()
            assertTrue(handshake.accepted, "clientHello 应被替身接受，实际拒因：${handshake.errors + handshake.capabilityViolations}")
            assertEquals(connection.registeredClientId, handshake.clientId, "握手注册的 clientId 必须来自连接层（A2）")
            assertTrue(handshake.capabilityViolations.isEmpty())
            val claimedCaps = handshake.raw["capabilities"]?.objectValue
            assertEquals(
                true, claimedCaps?.get("workflowRunDeltas")?.boolValue,
                "host 宣告 workflowRunDeltas=true → clientHello 回显（A5 单向宣告）",
            )
            assertEquals(
                setOf("workflowRunDeltas"), claimedCaps?.keys,
                "clientHello capabilities 键集封闭（transport.ts .strict()）",
            )

            // 连接期挂载面：三路 dynamic eventListen + zcode-task.onError + workspace-config 订阅
            assertEquals(1, server.rpcCallCount("subscribeWorkspaceConfigV4"), "workspace-config 订阅为 promise 面")
            awaitUntil("三路帧面 + onError 登记") {
                val log = server.eventListenLog()
                log.count { it == E2EStubServer.EVENT_CONVERSATION_FRAME } == 1 &&
                    log.count { it == E2EStubServer.EVENT_SESSIONS_INDEX_FRAME } == 1 &&
                    log.count { it == E2EStubServer.EVENT_WORKSPACE_CONFIG_FRAME } == 1 &&
                    log.count { it == "onError" } == 1
            }

            // 帧面按 workspace 多路幂等挂载（v1.23 红线）：同 workspace 重复挂不重复注册，
            // 异 workspace 新挂一路
            connection.ensureConversationFrameStream(server.workspacePath)
            connection.ensureConversationFrameStream(server.workspacePath)
            assertEquals(
                1, server.eventListenLog().count { it == E2EStubServer.EVENT_CONVERSATION_FRAME },
                "同 workspace 的 ensureConversationFrameStream 必须幂等",
            )
            connection.ensureConversationFrameStream("/other/workspace")
            awaitUntil("异 workspace 补挂一路") {
                server.eventListenLog().count { it == E2EStubServer.EVENT_CONVERSATION_FRAME } == 2
            }

            // ② 一次 call：sendConversationCommandV4（信封走 :protocol 单点 Factory）
            val commandId = "cmd-e2e-conn-0001"
            val built = ConversationCommandFactory.build(
                request = ConversationCommandRequest(
                    type = "sendText",
                    sessionId = "sess-e2e-1",
                    payload = stubObj("text" to JSONValue.Str("连接层直连 E2E")),
                    casRevision = null, // sendText 非 CAS 词表
                    workspacePath = server.workspacePath,
                    workspaceIdentity = null,
                    commandId = commandId,
                ),
                clientId = connection.registeredClientId,
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            val ackValue = connection.call("zcode-agent", "sendConversationCommandV4", RPCValue.json(built.outer))
            val ack = CommandAck(ackValue.jsonValue)
            assertEquals(commandId, ackValue.jsonValue?.get("commandId")?.stringValue, "回执回显 commandId")
            assertEquals("accepted", ack.status)
            assertTrue(ack.isAuthoritativeSuccess, "accepted ∈ 权威成功词表（A13）")
            assertEquals(1, server.rpcCallCount("sendConversationCommandV4"))
            val envelopeRecord = server.envelopes().single { it.type == "sendText" }
            assertTrue(envelopeRecord.schemaErrors.isEmpty(), "入站信封 strict 对拍零错：${envelopeRecord.schemaErrors}")
            assertEquals(true, envelopeRecord.clientIdMatchesHandshake, "信封 clientId 与握手逐字相等（A2）")

            // 帧序（服务端 wire 序）：命令 accepted 回执先于两行增量帧
            awaitUntil(
                "两行增量帧到达客户端",
                onTimeout = {
                    "frames=${frames.size} sent=${server.framesSent().map { it.topic + "|" + it.payloadKind }} " +
                        "listeners=${server.eventListenLog()}"
                },
            ) { frames.size >= 2 }
            val sendAckOrder = server.wireLog()
                .last { it.kind == E2EStubServer.WIRE_PROMISE_SUCCESS && it.detail == "sendConversationCommandV4" }
                .order
            val deltaOrders = server.framesSent()
                .filter { it.topic == "conversation/sess-e2e-1" && it.payloadKind == "deltas" }
                .map { it.order }
            assertEquals(2, deltaOrders.size)
            assertTrue(deltaOrders.all { it > sendAckOrder }, "accepted 回执必须先于行增量帧（服务端 wire 序）")
            // 到端行形状：首帧用户行携 sourceCommandId=信封 commandId（上游 rows.ts:141）
            val userFrame = frames.elementAt(0)
            val userRow = userFrame.deltas.first()["row"]!!
            assertEquals("userInput", userRow["kind"]?.stringValue)
            assertEquals(commandId, userRow["sourceCommandId"]?.stringValue)
            assertEquals("assistantText", frames.elementAt(1).deltas.first()["row"]?.get("kind")?.stringValue)

            // ③ ReadOnlyGate 出口拦截：file.writeTextFile 本地即拒、零上线
            val blocked = runCatching {
                connection.call("file", "writeTextFile", RPCValue.json(stubObj("path" to JSONValue.Str("/x/y"))))
            }.exceptionOrNull()
            assertTrue(blocked is RPCError && blocked.name == "ReadOnlyViolation", "直写类必须在出口被拒：$blocked")
            assertEquals(0, server.rpcCallCount("writeTextFile"), "被拦命令不得上线")
            assertTrue(connection.blockedExecutionCallsSnapshot().isNotEmpty(), "拦截记录在案（最近 20 条）")

            // handshakeRequired 捕获分支：LAN 无中继桥 → 如实上抛 message 原文（不自愈不吞）
            server.injectMethodFault("readSession", "fault.connection.handshakeRequired")
            val handshakeRequired = runCatching { connection.call("zcode-agent", "readSession") }.exceptionOrNull()
            assertTrue(handshakeRequired is RPCError, "应上抛 RPCError")
            assertTrue(
                handshakeRequired?.message?.contains("fault.connection.handshakeRequired") == true,
                "message 原文必须保留（协议文档 §5-12），实际：${handshakeRequired?.message}",
            )
            server.clearMethodFault("readSession")

            // 断线回落：曾连接后的真实传输中断 → onConnectionDropped + state Disconnected
            server.stop()
            awaitUntil("断线回落通知") { connection.state.value is ConnectionState.Disconnected }
            assertNotNull(droppedDetail, "曾连接后的真实中断必须回调 onConnectionDropped")
        } finally {
            connection.disconnect()
            server.stop() // 幂等（engine 置空后为 no-op）
        }
    }

    // MARK: 用例 2：A8「快照帧先于订阅回执」屏障场景

    @Test
    fun a8_snapshotBeforeSubscribeAck_handlerFirstAndBarrierStagesInOrder() = runBlocking {
        val server = E2EStubServer() // snapshotBeforeSubscribeAck 默认 true（A8 竞态复现）
        val port = server.start()
        val connection = ZCodeServerConnection()
        try {
            val result = connection.connect(
                server = ServerConfig(id = "e2e", name = null, host = "127.0.0.1", port = port, useTLS = false, token = ""),
            )
            assertTrue(result is ConnectResult.Success, "连接应成功：$result")
            val topic = "conversation/sess-e2e-1"
            val barrierKey = "conversation/$topic"

            // 消费面（RemoteConversationStore 对应物）：帧 handler 先于订阅注册；
            // 屏障 in-flight 期间暂存（不直通），subId 落库后 finish 原序回放
            val barrier = AckActivationBarrier()
            val delivered = ConcurrentLinkedQueue<V4TopicFrame>()
            val staged = ConcurrentLinkedQueue<V4TopicFrame>()
            connection.setFrameHandler(topic = topic) { frame ->
                val inFlight = runBlocking { barrier.isInFlight(barrierKey) }
                if (inFlight) {
                    val accepted = runBlocking { barrier.stage(barrierKey, frame) }
                    if (accepted) staged.add(frame) else fail("屏障暂存被拒（overflow 面，本用例不应触达）")
                } else {
                    delivered.add(frame)
                }
            }
            barrier.begin(barrierKey)

            // 订阅（promise 面）：替身先发快照帧、后发订阅回执（A8）
            val subAck = connection.call(
                "zcode-agent", "subscribeConversationV4",
                RPCValue.json(stubObj(
                    "topic" to JSONValue.Str(topic),
                    "workspacePath" to JSONValue.Str(server.workspacePath),
                )),
            )
            val subscriptionId = subAck.jsonValue?.get("subscriptionId")?.stringValue
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, subscriptionId)

            // A8 权威断言（服务端 wire 序）：快照帧 order < 订阅回执 order
            val wire = server.wireLog()
            val snapOrder = wire.first {
                it.kind == E2EStubServer.WIRE_EVENT_FRAME &&
                    it.detail.endsWith("|snapshot") && it.detail.contains("conversation/sess-e2e-1")
            }.order
            val ackOrder = wire.last { it.kind == E2EStubServer.WIRE_PROMISE_SUCCESS && it.detail == "subscribeConversationV4" }.order
            assertTrue(snapOrder < ackOrder, "A8：快照帧必须先于订阅回执帧（wire 序）")

            // 帧未丢：先注册的 handler 已收到并被屏障暂存（连接层不静默丢帧）
            awaitUntil("快照帧经连接层路由到达") { staged.isNotEmpty() }
            assertEquals(0, delivered.size, "屏障 in-flight 期间帧不得直通消费面")

            // subId 先落库（已由上方 assertEquals 取得回执 subscriptionId）→ finish 原序回放
            val outcome = barrier.finish(barrierKey)
            val outcomeFrames = (outcome as? AckActivationBarrier.FinishOutcome.Frames)?.frames
                ?: return@runBlocking fail("应原序回放暂存帧，实际：$outcome")
            assertEquals(1, outcomeFrames.size)
            val snapshot = outcomeFrames.first()
            assertEquals(topic, snapshot.topic)
            assertEquals(subscriptionId, snapshot.subscriptionId, "帧 subId 与订阅回执一致（v1.31 口径）")
            assertNotNull(snapshot.snapshot, "快照帧 payload.kind=snapshot")
            delivered.add(snapshot)

            // 回执后增量直通消费面（屏障已撤）
            server.pushConversationRows(
                "sess-e2e-1",
                listOf(
                    StubV4Frames.userRow(rowId = 1, turnId = "t1", text = "增量", sourceCommandId = "cmd-x", createdAtSeq = 1),
                ),
            )
            awaitUntil("增量帧直通") { delivered.size >= 2 }
            val delta = delivered.last()
            assertEquals(1, delta.deltas.size)
            assertEquals("userInput", delta.deltas.first()["row"]?.get("kind")?.stringValue)
            assertEquals("增量", delta.deltas.first()["row"]?.get("text")?.stringValue)
        } finally {
            connection.disconnect()
            server.stop()
        }
    }

    // MARK: 夹具

    private fun awaitUntil(
        description: String,
        timeoutMs: Long = 10_000,
        onTimeout: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "等待超时：$description" + (onTimeout?.invoke()?.let { " —— $it" } ?: "")
            }
            Thread.sleep(20)
        }
    }
}

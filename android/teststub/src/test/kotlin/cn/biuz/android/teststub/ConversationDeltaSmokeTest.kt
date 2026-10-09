package cn.biuz.android.teststub

// 替身会话流 delta 注水冒烟（步骤 3 面）：state.updated 键级整替注水（A10）+
// 挂起交互下发/撤下通道（v1.31③ 形状红线：交互形状按上游真实形态原样转发——
// ExitPlanMode 挂起交互 kind 恒 "userInput"、plan_approval 在 payload.schema.interaction、
// 正文在 payload.input.plan；替身美化成顶层 kind=plan_approval 会掩盖客户端 schema 归位缺陷）。
// 真实本地回环 socket：ChannelClient ↔ Ktor E2EStubServer；seq 每 topic 单调、subId 与
// 订阅回执一致（v1.31 口径：两值错配 = 增量全被客户端过滤）。

import cn.biuz.android.protocol.ChannelClient
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.TopicWireFrame
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.protocol.WebSocketTransportFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class ConversationDeltaSmokeTest {

    @Test
    fun statePatchAndPendingInteraction_deltas_keyLevelAndMonotonicSeq() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            val sink = EventSink()
            val convSub = sink.install(client, E2EStubServer.EVENT_CONVERSATION_FRAME, server.workspacePath)
            awaitUntil("服务端登记 conversation 帧监听") {
                server.hasEventListener(E2EStubServer.EVENT_CONVERSATION_FRAME)
            }
            client.call("zcode-agent", "helloConversationV4")
            client.call(
                "zcode-agent",
                "initializeConversationV4",
                clientHelloJson("zcode-mobile-delta-1"),
            )
            // 订阅：快照（subId 基准）
            val ack = client.call(
                "zcode-agent",
                "subscribeConversationV4",
                RPCValue.json(
                    stubObj(
                        "topic" to JSONValue.Str("conversation/sess-e2e-1"),
                        "workspacePath" to JSONValue.Str(server.workspacePath),
                    ),
                ),
            ).jsonValue!!
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, ack["subscriptionId"]?.stringValue)
            val snapshot = awaitEvent(sink) { it.logical?.snapshot != null }
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, snapshot.envelope?.subscriptionId)

            // ① state.updated 键级整替注水（A10）：patch 原样到达、seq 快照后 0→1 起步单调
            server.pushConversationStatePatch(
                sessionId = "sess-e2e-1",
                patch = mapOf(
                    "modelSelection" to stubObj("modelId" to JSONValue.Str("glm-5.3")),
                    "revision" to stubNum(43),
                ),
            )
            val patchDelta = awaitEvent(sink) { ev ->
                ev.logical?.deltas?.firstOrNull()?.get("op")?.stringValue == "state.updated"
            }
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, patchDelta.envelope?.subscriptionId, "帧 subId 与回执一致")
            assertEquals(0, patchDelta.logical?.fromSeq, "快照重置水位后增量 0→1 起步（seq 每 topic 单调）")
            assertEquals(1, patchDelta.logical?.toSeq)
            assertEquals("online", patchDelta.envelope?.deliveryKind)
            val patch = patchDelta.logical?.deltas?.first()?.get("patch")?.objectValue
            assertEquals("glm-5.3", patch?.get("modelSelection")?.objectValue?.get("modelId")?.stringValue)
            assertEquals(43, patch?.get("revision")?.intValue)

            // ② 挂起交互下发：上游真实形状原样转发（kind=userInput + payload.schema.interaction=plan_approval）
            server.pushPendingInteraction(
                sessionId = "sess-e2e-1",
                interaction = stubObj(
                    "id" to JSONValue.Str("it-plan-1"),
                    "kind" to JSONValue.Str("userInput"),
                    "toolName" to JSONValue.Str("ExitPlanMode"),
                    "payload" to stubObj(
                        "schema" to stubObj("interaction" to JSONValue.Str("plan_approval")),
                        "prompt" to JSONValue.Str("请审批该计划"),
                        "input" to stubObj("plan" to JSONValue.Str("# 计划正文")),
                    ),
                ),
            )
            val interactionDelta = awaitEvent(sink) { ev ->
                ev !== patchDelta &&
                    ev.logical?.deltas?.firstOrNull()?.get("op")?.stringValue == "state.updated"
            }
            val items = interactionDelta.logical?.deltas?.first()
                ?.get("patch")?.objectValue?.get("pendingInteractions")?.arrayValue
            assertEquals(1, items?.size)
            val forwarded = items!!.single().objectValue!!
            // 原样转发断言：替身不得美化形状（客户端 parseInteraction 靠它归位 kind）
            assertEquals("userInput", forwarded["kind"]?.stringValue, "上游真实形态：挂起交互 kind 恒 userInput")
            assertEquals(
                "plan_approval",
                forwarded["payload"]?.objectValue?.get("schema")?.objectValue?.get("interaction")?.stringValue,
                "plan_approval 只在 payload.schema.interaction（v1.31③）",
            )
            assertEquals(
                "# 计划正文",
                forwarded["payload"]?.objectValue?.get("input")?.objectValue?.get("plan")?.stringValue,
                "计划正文在 payload.input.plan",
            )
            // seq 继续单调
            assertEquals(1, interactionDelta.logical?.fromSeq)
            assertEquals(2, interactionDelta.logical?.toSeq)

            // ③ 撤下：null = 空数组键级整替
            server.pushPendingInteraction(sessionId = "sess-e2e-1", interaction = null)
            val withdrawDelta = awaitEvent(sink) { ev ->
                ev !== patchDelta && ev !== interactionDelta &&
                    ev.logical?.deltas?.firstOrNull()?.get("op")?.stringValue == "state.updated"
            }
            val withdrawn = withdrawDelta.logical?.deltas?.first()
                ?.get("patch")?.objectValue?.get("pendingInteractions")?.arrayValue
            assertEquals(0, withdrawn?.size, "撤下 = 空数组整替（A10 键级）")
            assertEquals(2, withdrawDelta.logical?.fromSeq, "seq 继续单调（不重置）")
            assertEquals(3, withdrawDelta.logical?.toSeq)

            convSub.cancel()
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: 夹具（StubServerSmokeTest 同构的最小版）

    private fun newClient(): ChannelClient =
        ChannelClient(WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
            JavaNetWebSocketTransport(url, timeoutMs, maximumMessageSize)
        })

    private fun clientHelloJson(clientId: String): RPCValue = RPCValue.json(
        stubObj(
            "kind" to JSONValue.Str("clientHello"),
            "protocolVersion" to JSONValue.Int(3),
            "clientId" to JSONValue.Str(clientId),
            "clientKind" to JSONValue.Str("mobileApp"),
            "appVersion" to JSONValue.Str("0.1.0-delta"),
            "capabilities" to stubObj(),
        ),
    )

    private class ReceivedEvent(
        val envelope: TopicWireFrame.Envelope?,
        val logical: V4TopicFrame?,
    )

    private class EventSink {
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
                received.add(ReceivedEvent(env, logical))
            }
        }

        fun allOf(): List<ReceivedEvent> = received.toList()
    }

    private fun awaitEvent(
        sink: EventSink,
        predicate: (ReceivedEvent) -> Boolean = { true },
    ): ReceivedEvent {
        awaitUntil("匹配事件帧") { sink.allOf().any(predicate) }
        return sink.allOf().first(predicate)
    }

    private fun awaitUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
            Thread.sleep(20)
        }
    }
}

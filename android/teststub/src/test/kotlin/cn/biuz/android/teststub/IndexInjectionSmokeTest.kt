package cn.biuz.android.teststub

import cn.biuz.android.protocol.ChannelClient
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.TopicWireFrame
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.protocol.WebSocketTransportFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * bootstrap.tasks / sessions-index 注水冒烟（M2 store 层验收基建）：
 * ① bootstrap-request 回执携注水的跨工作区任务索引（含幽灵行对——同会话双区行）；
 * ② sessions-index 快照注水经真实线缆到端（relay 形态行——只带 workspaceId 不带
 *    workspacePath，乙-6② 红线样本；phase 缺席行——双源合成判据面）。
 * 毫秒时间戳经线缆往返后为整值 double（JSONValue.Int 32 位值域口径）。
 */
class IndexInjectionSmokeTest {

    @Test
    fun bootstrapRequest_returnsInjectedTasks_withGhostPair() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            val realWs = "/Users/mac/zcode_mobile"
            val ghostWs = "/Users/mac/mtt_mobile"
            val updatedAt = 1_791_040_344_222L
            server.setBootstrapTasks(
                StubIndexFixtures.ghostBootstrapTaskRows(
                    sessionId = "sess-ghost-1",
                    title = "幽灵行样本会话",
                    realWorkspace = realWs,
                    ghostWorkspace = ghostWs,
                    updatedAtMs = updatedAt,
                ) + StubIndexFixtures.bootstrapTaskRow(
                    taskId = "sess-solo",
                    title = "普通跨区行",
                    workspacePath = "/Users/mac/poker_protocol",
                    updatedAtMs = updatedAt,
                    workspaceIdentity = "ident-poker",
                    status = "completed",
                ),
            )
            assertEquals(3, server.bootstrapTasks().size)

            client = ChannelClient(
                WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
                    JavaNetWebSocketTransport(url, timeoutMs, maximumMessageSize)
                },
            )
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            val reply = client.call("zcode-agent", "bootstrap-request").jsonValue!!
            val result = reply["result"]?.objectValue
            assertNotNull(result, "回执携 result 对象（iOS RelayChannelClient.swift:157 同源读取面）")
            assertEquals(E2EStubServer.STUB_DESKTOP_APP_VERSION, result["desktopAppVersion"]?.stringValue)
            val tasks = result["tasks"]?.arrayValue
            assertNotNull(tasks)
            assertEquals(3, tasks.size)

            // 幽灵行对：同 taskId 双行、不同 workspacePath（合并器权威纠正的注水基线）
            val ghostRows = tasks.filter { it["taskId"]?.stringValue == "sess-ghost-1" }
            assertEquals(2, ghostRows.size, "同会话双区行（上游 seedTaskMetaIfMissing 幽灵形态）")
            assertEquals(
                setOf(realWs, ghostWs),
                ghostRows.mapNotNull { it["workspacePath"]?.stringValue }.toSet(),
            )
            // 毫秒时间戳越 Int32 → 线缆整值 double（客户端须走 double/Long 路径读取；
            // intValue 对越界整值 double 返回饱和值 2147483647——32 位路径必丢数据，即
            // M1 残留 deviations 摘要「毫秒时间戳一律走 double/Long 路径」的实证形态）
            val realRow = ghostRows.first { it["workspacePath"]?.stringValue == realWs }
            assertEquals(updatedAt.toDouble(), realRow["updatedAt"]?.doubleValue)
            val intPath = realRow["updatedAt"]?.intValue
            assertNotNull(intPath)
            assertTrue(
                intPath.toLong() != updatedAt,
                "32 位 intValue 路径读毫秒时间戳必丢真值（实测饱和值 $intPath）",
            )
            assertNull(realRow["lastActivityAt"], "bootstrap.tasks 行无 lastActivityAt（上游 zcodeTaskMetaSchema）")
            assertEquals("running", realRow["status"]?.stringValue)

            val solo = tasks.single { it["taskId"]?.stringValue == "sess-solo" }
            assertEquals("ident-poker", solo["workspaceIdentity"]?.stringValue)
            assertEquals("completed", solo["status"]?.stringValue)
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    @Test
    fun sessionsIndexSnapshot_injectionCarriesRelayShapeAndPhaselessRows() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            // relay 形态行：只带 workspaceId 不带 workspacePath（2026-10-05 全键取证；
            // 客户端合并器禁拿 workspaceId 当分组键——乙-6②）
            // phase 缺席行：双源合成判据面（phaseExplicit=false）
            server.setSessionsIndexSnapshot(
                StubIndexFixtures.sessionsIndexSnapshot(
                    logEpoch = "epoch-inject-1",
                    sessions = listOf(
                        StubIndexFixtures.sessionsIndexRow(
                            sessionId = "sess-relay-only",
                            title = "relay 形态行",
                            phase = "running",
                            lastActivityAtMs = 1_791_040_344_222L,
                            workspaceId = "/ws/id-only",
                        ),
                        StubIndexFixtures.sessionsIndexRow(
                            sessionId = "sess-no-phase",
                            title = "phase 缺席行",
                            phase = null,
                            lastActivityIso = "2026-01-15T08:30:00.000Z",
                            workspacePath = server.workspacePath,
                            taskStatus = "running",
                        ),
                        StubIndexFixtures.sessionsIndexRow(
                            sessionId = "sess-pending",
                            title = "待审批行",
                            phase = "completedSuccess",
                            lastActivityAtMs = 1_767_000_000_000L,
                            workspacePath = server.workspacePath,
                            pendingPermissionCount = 2,
                            pendingUserInputCount = 1,
                        ),
                    ),
                ),
            )

            client = ChannelClient(
                WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
                    JavaNetWebSocketTransport(url, timeoutMs, maximumMessageSize)
                },
            )
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}

            // 帧 handler 先于订阅注册（工程纪律 §5-3）；快照帧 A8 先于订阅回执到端
            val received = ConcurrentLinkedQueue<JSONValue>()
            val sub = client.listen(
                "zcode-agent",
                E2EStubServer.EVENT_SESSIONS_INDEX_FRAME,
                RPCValue.json(stubObj("workspacePath" to JSONValue.Str(server.workspacePath))),
            ) { payload -> payload.jsonValue?.let { received.add(it) } }
            awaitUntil("服务端登记 sessions-index 帧监听") {
                server.hasEventListener(E2EStubServer.EVENT_SESSIONS_INDEX_FRAME)
            }

            val topic = "sessions-index/${server.workspacePath}"
            client.call(
                "zcode-agent",
                "subscribeSessionsIndexV4",
                RPCValue.json(
                    stubObj(
                        "topic" to JSONValue.Str(topic),
                        "workspacePath" to JSONValue.Str(server.workspacePath),
                    ),
                ),
            )

            // 到端解析：信封 → 逻辑帧 → 快照体（线缆往返后的注水内容）
            awaitUntil("快照帧到端") { received.isNotEmpty() }
            val envelope = TopicWireFrame.parse(received.first()!!)
            assertNotNull(envelope)
            assertEquals(E2EStubServer.SUB_ID_SESSIONS_INDEX, envelope.subscriptionId)
            val logical = V4TopicFrame.parse(envelope.completeFrame!!)
            assertNotNull(logical)
            val snapshot = logical.snapshot!!
            assertEquals("epoch-inject-1", snapshot["logEpoch"]?.stringValue)
            val sessions = snapshot["sessions"]?.arrayValue ?: emptyList()
            assertEquals(3, sessions.size)

            val relayRow = sessions.single { it["sessionId"]?.stringValue == "sess-relay-only" }
            assertNull(relayRow["workspacePath"], "relay 形态行不带 workspacePath（乙-6②红线样本）")
            assertEquals("/ws/id-only", relayRow["workspaceId"]?.stringValue)
            assertEquals("running", relayRow["phase"]?.stringValue)
            assertEquals(1_791_040_344_222L, relayRow["lastActivityAt"]?.doubleValue?.toLong())

            val noPhase = sessions.single { it["sessionId"]?.stringValue == "sess-no-phase" }
            assertNull(noPhase["phase"], "phase 缺席行（phaseExplicit=false 判据面）")
            assertEquals("running", noPhase["taskStatus"]?.stringValue)
            assertEquals(
                "2026-01-15T08:30:00.000Z",
                noPhase["lastActivityAt"]?.stringValue,
                "ISO 字符串形态（中继快照实测双形态）",
            )

            val pendingRow = sessions.single { it["sessionId"]?.stringValue == "sess-pending" }
            val pending = pendingRow["pendingInteractionSummary"]?.objectValue
            assertNotNull(pending)
            assertEquals(2, pending["permissionCount"]?.intValue)
            assertEquals(1, pending["userInputCount"]?.intValue)

            sub.cancel()
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    private fun awaitUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
            Thread.sleep(20)
        }
    }
}

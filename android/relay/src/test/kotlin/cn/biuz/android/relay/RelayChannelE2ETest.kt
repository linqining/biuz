package cn.biuz.android.relay

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.relay.RelayLinkConfig
import cn.biuz.android.teststub.E2EStubServer
import cn.biuz.android.teststub.StubV4Frames
import cn.biuz.android.teststub.stubObj
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 中继全链替身级验收（任务门禁 1/2：帧面握手 + 开桥重握手单点；真实中继验证需用户桌面
 * 在线且串行——本轮替身级，如实挂账）：
 *
 * ① 帧面握手：RelayTransport（auth_init→challenge→response→ack matched）→ bootstrap →
 *    workspace-bridge-open → 桥内 rpc-frame 承载 hello→clientHello（onBridgeOpened 钩子）→
 *    promise 调用 / eventListen 事件双向通（rpc-frame 编码 + ack + 重组全链路）。
 * ② 开桥重握手单点（A6 红线）：三条开桥路径（断线恢复重建 / 桌面 bridge-degraded 快速
 *    重建 / workspace 切换）全部汇入 onBridgeOpened——替身每桥一个全新 SessionContext
 *    （clientId/eventIds 全空 + assertReadyGate），漏重握手即撞 fault.connection.handshakeRequired；
 *    断言每路重开后 clientHello 记账 +1、bridgeGeneration 递增、recoveryId 携回。
 *
 * 瞬断 1.2s 退避重试（门禁 3）的真身是 :connection 的 performRelayV4Handshake（onBridgeOpened
 * 钩子体）→ 门禁落在 :connection:test RelayConnectE2ETest（测真实单点，不测测试内复制品）。
 */
class RelayChannelE2ETest {

    private val relaySid = "d-relay-test-sid"
    private val relayPassHash = "relay-test-pass-hash"

    private suspend fun awaitUntil(
        desc: String,
        timeoutMs: Long = 15_000,
        dump: (() -> String)? = null,
        cond: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                val detail = dump?.invoke() ?: ""
                error("等待超时（${System.currentTimeMillis() - start}ms）：$desc\n$detail")
            }
            delay(20)
        }
    }

    private class Harness(
        val server: E2EStubServer,
        val client: RelayChannelClient,
        val transport: RelayTransport,
    )

    /** 首连承载：transport + client + onBridgeOpened 钩子（桥内 hello→clientHello，与 :connection 同形）。 */
    private suspend fun connect(
        server: E2EStubServer,
        clientId: String,
        bridgeOpenedCount: AtomicInteger,
    ): Harness {
        val config = RelayLinkConfig(
            wssURL = "ws://127.0.0.1:${server.relayPort}/ws",
            machineName = "stub-desktop",
            deviceSid = server.relayDeviceSid,
            passHash = server.relayPassHash,
            desktopAppVersion = null,
        )
        val transport = RelayTransport(config, onLog = { kind, text -> println("[relay-log][$kind] $text") })
        val client = RelayChannelClient(transport = transport, link = config, appVersion = "1.0.0-test")
        client.setOnBridgeOpened { bridge ->
            bridgeOpenedCount.incrementAndGet()
            // 桥内 v4 握手（:connection performRelayV4Handshake 同形：hello → clientHello）
            val hello = bridge.call("zcode-agent", "helloConversationV4").jsonValue
            check(hello != null && hello["protocolVersion"]?.intValue == 3) { "桥内 hello 失败" }
            val clientHello = RPCValue.json(
                JSONValue.Obj(
                    linkedMapOf(
                        "kind" to JSONValue.Str("clientHello"),
                        "protocolVersion" to JSONValue.Int(3),
                        "clientId" to JSONValue.Str(clientId),
                        "clientKind" to JSONValue.Str("mobileApp"),
                        "appVersion" to JSONValue.Str("1.0.0-test"),
                        // host hello 宣告过 workflowRunDeltas → 回显合法（A5）
                        "capabilities" to JSONValue.Obj(
                            linkedMapOf("workflowRunDeltas" to JSONValue.Bool(true)),
                        ),
                    ),
                ),
            )
            bridge.call("zcode-agent", "initializeConversationV4", clientHello)
        }
        client.connectRelay(timeout = 15.seconds) { }
        return Harness(server, client, transport)
    }

    /** 桥内 promise 面 方法注水（重开后 RPC 可用性断言的靶子）。 */
    private fun installListSessions(server: E2EStubServer) {
        server.methodHandlers["listSessions"] = {
            stubObj("sessions" to JSONValue.Arr(emptyList()))
        }
    }

    // MARK: 门禁①：帧面握手（auth + bootstrap + bridge + 桥内 RPC/事件双向）

    @Test
    fun relayFrameSurface_handshake_bootstrap_bridge_rpcAndEvents() = runBlocking {
        val server = E2EStubServer()
        server.start()
        server.relayDeviceSid = relaySid
        server.relayPassHash = relayPassHash
        val relayPort = server.startRelay()
        installListSessions(server)
        // bootstrap.tasks 注水（bootstrap-response.result.tasks → summary.sessionCount）
        server.setBootstrapTasks(
            listOf(
                stubObj(
                    "sessionId" to JSONValue.Str("sess-t1"),
                    "workspacePath" to JSONValue.Str(server.workspacePath),
                ),
                stubObj(
                    "sessionId" to JSONValue.Str("sess-t2"),
                    "workspacePath" to JSONValue.Str("/Users/e2e/other-ws"),
                ),
            ),
        )
        val bridgeOpened = AtomicInteger(0)
        var harness: Harness? = null
        try {
            harness = connect(server, clientId = "zcode-mobile-relaytest-1", bridgeOpenedCount = bridgeOpened)
            val client = harness.client

            // auth + bootstrap + bridge 面
            assertEquals(1, server.relayAuthCount(), "auth 握手恰一次")
            assertEquals(1, server.relayBridgeOpenCount(), "首连恰开一桥")
            assertEquals(1, bridgeOpened.get(), "onBridgeOpened 钩子首连恰好一次（A6 单点）")
            val handshakes = server.handshakes()
            assertEquals(1, handshakes.size)
            assertTrue(handshakes[0].accepted, "桥内 clientHello 通过替身 strict 对拍")
            assertEquals("zcode-mobile-relaytest-1", handshakes[0].clientId)
            assertTrue(handshakes[0].capabilityViolations.isEmpty(), "A5：capabilities 未越权")

            val summary = client.summary
            assertNotNull(summary)
            assertEquals(E2EStubServer.STUB_DESKTOP_APP_VERSION, summary.desktopAppVersion, "bootstrap-response.desktopAppVersion")
            assertEquals(server.workspacePath, summary.workspacePath, "workspace-bridge-ready.workspacePath")
            assertEquals(2, summary.sessionCount, "bootstrap.tasks 计数")
            assertEquals(1, summary.workspaces.size)
            assertTrue(summary.workspaces[0].canBridge)
            assertEquals(server.workspacePath, client.activeWorkspaceKey)

            // 桥开成功场景①：mobile-view-state-update 单向通知已发（C-11）
            awaitUntil("mobile-view-state-update 抵达替身") { server.relayViewStateCount() >= 1 }

            // 桥内 promise RPC（rpc-frame 上行 + 回执下行重组）
            val listResult = client.call("zcode-agent", "listSessions").jsonValue
            assertNotNull(listResult, "桥内 RPC 回执可解析")
            assertEquals(1, server.rpcCallCount("listSessions"))

            // 桥内事件面：eventListen → stub 增量帧经 rpc-frame 推回
            val frames = Channel<JSONValue>(capacity = Channel.UNLIMITED)
            val subscription = client.listen(
                "zcode-agent",
                E2EStubServer.EVENT_CONVERSATION_FRAME,
                RPCValue.json(
                    JSONValue.Obj(linkedMapOf("workspacePath" to JSONValue.Str(server.workspacePath))),
                ),
            ) { payload ->
                payload.jsonValue?.let { frames.trySend(it) }
            }
            awaitUntil("替身登记 conversation 帧监听") { server.hasEventListener(E2EStubServer.EVENT_CONVERSATION_FRAME) }
            val row = StubV4Frames.userRow(
                rowId = 1,
                turnId = "turn-relay-1",
                text = "中继帧面冒烟",
                sourceCommandId = "cmd-relay-1",
                createdAtSeq = 1,
            )
            server.pushConversationRows("sess-relay-1", listOf(row))
            val envelope = withTimeout(5.seconds) { frames.receive() }
            assertEquals(
                "conversation/sess-relay-1",
                envelope["topic"]?.stringValue,
                "eventFire 载荷经桥内 rpc-frame 透传",
            )

            // rpc-frame 分片可靠层：客户端对我下行帧回 ack
            awaitUntil("客户端 ack 抵达替身") { server.relayDownlinkAckCount() >= 1 }

            assertTrue(harness.transport.isPaired(), "传输 paired")
            subscription.cancel()
        } finally {
            harness?.client?.disconnect()
            server.stopRelay()
            server.stop()
        }
        assertTrue(relayPort > 0)
    }

    // MARK: 门禁②：开桥重握手单点（重开必重握手；断线恢复/degraded/切换三路全汇入）

    @Test
    fun bridgeReopen_allThreePaths_convergeOnBridgeOpened_andRedoHandshake() = runBlocking {
        val server = E2EStubServer()
        server.start()
        server.relayDeviceSid = relaySid
        server.relayPassHash = relayPassHash
        server.startRelay()
        installListSessions(server)
        val bridgeOpened = AtomicInteger(0)
        var harness: Harness? = null
        try {
            harness = connect(server, clientId = "zcode-mobile-relaytest-2", bridgeOpenedCount = bridgeOpened)
            val client = harness.client
            assertEquals(listOf(1), server.relayBridgeGenerations(), "首桥 gen=1")
            assertTrue(
                server.relayBridgeRpcLog().contains("bridge=1 << bridge-open recoveryId=null"),
                "首开无 recoveryId 可携",
            )
            assertEquals(1, server.handshakes().count { it.accepted })

            // 路径 A：断线恢复——服务端断 WS → 指数退避重连（首 delay 500ms）→ matched →
            // pairedRecoveryHandler → rebuildBridgeAfterReconnect → openBridge(gen+1, recoveryId)
            server.dropRelayConnections()
            awaitUntil("断线恢复重建桥 + 重握手", dump = {
                "bridgeOpens=${server.relayBridgeOpenCount()} gens=${server.relayBridgeGenerations()}" +
                    " acceptedHandshakes=${server.handshakes().count { it.accepted }}" +
                    " authCount=${server.relayAuthCount()} rpcLedger=${server.relayBridgeRpcLog()}"
            }) {
                server.relayBridgeOpenCount() >= 2 && server.handshakes().count { it.accepted } >= 2
            }
            assertEquals(listOf(1, 2), server.relayBridgeGenerations(), "重开 bridgeGeneration 递增")
            // 重建开桥携回首桥 recoveryId（客户端 lastRecoveryId → 请求键；桌面铸造值经 ready 回）
            val minted = server.relayBridgeRecoveryIds()
            assertTrue(minted.size >= 2 && minted[0] != null, "桌面铸造 recoveryId")
            assertTrue(
                server.relayBridgeRpcLog().any { it.startsWith("bridge=2 << bridge-open recoveryId=${minted[0]}") },
                "重建请求携回首桥 recoveryId：${server.relayBridgeRpcLog()}",
            )
            assertEquals(2, server.relayAuthCount(), "断线恢复重走 auth")
            assertEquals(2, bridgeOpened.get(), "恢复路径经 onBridgeOpened")
            // A6：重开桥必须重做 hello→clientHello（新 facade）；重开后 RPC 可用
            assertNotNull(client.call("zcode-agent", "listSessions").jsonValue, "重开桥后 promise 调用")

            // 路径 B：桌面宣告 bridge-degraded → 快速重建（同 transport，不重走 auth/pair）
            val authCountBeforeDegraded = server.relayAuthCount()
            server.pushRelayBridgeDegraded("stub: 桥退化注水")
            awaitUntil("degraded 快速重建桥 + 重握手") {
                server.relayBridgeOpenCount() >= 3 && server.handshakes().count { it.accepted } >= 3
            }
            assertEquals(listOf(1, 2, 3), server.relayBridgeGenerations())
            assertEquals(authCountBeforeDegraded, server.relayAuthCount(), "degraded 重建不重走 auth/pair")
            assertEquals(3, bridgeOpened.get(), "degraded 路径经 onBridgeOpened")

            // 路径 C：workspace 切换 → switchBridgeWorkspace → openBridge（第四路语义同点）
            val targetWs = "/Users/e2e/switched-ws"
            client.switchBridgeWorkspace(workspaceKey = targetWs, workspacePath = targetWs)
            awaitUntil("切换开桥 + 重握手") {
                server.relayBridgeOpenCount() >= 4 && server.handshakes().count { it.accepted } >= 4
            }
            assertEquals(listOf(1, 2, 3, 4), server.relayBridgeGenerations())
            assertEquals(4, bridgeOpened.get(), "切换路径经 onBridgeOpened")
            assertEquals(targetWs, client.activeWorkspaceKey, "切换落点（bridge-ready.workspaceKey）")

            assertEquals(4, server.handshakes().count { it.accepted }, "四次开桥 = 四次全新握手（A6 红线）")
        } finally {
            harness?.client?.disconnect()
            server.stopRelay()
            server.stop()
        }
    }

    // MARK: 终态粘滞（AUTH_FAILED 不被读循环关闭事件复活重连）

    @Test
    fun authRejected_enterTerminalFailure_withoutResurrection() = runBlocking {
        val server = E2EStubServer()
        server.start()
        server.relayDeviceSid = relaySid
        server.relayPassHash = relayPassHash
        server.startRelay()
        server.relayFailAuth = true
        val closeReasons = CopyOnWriteArrayList<String>()
        try {
            val config = RelayLinkConfig(
                wssURL = "ws://127.0.0.1:${server.relayPort}/ws",
                machineName = null,
                deviceSid = server.relayDeviceSid,
                passHash = server.relayPassHash,
                desktopAppVersion = null,
            )
            val transport = RelayTransport(config, onLog = { kind, text -> println("[relay-log][$kind] $text") })
            transport.setClosedHandler { reason -> closeReasons.add(reason.codeText) }
            try {
                transport.start(timeout = 10.seconds)
                error("AUTH_FAILED 应令 start 抛错")
            } catch (expected: IllegalStateException) {
                throw expected
            } catch (expected: Exception) {
                assertTrue(
                    expected.message?.contains("AUTH_FAILED") == true,
                    "终态原因上抛：${expected.message}",
                )
            }
            delay(800) // 读循环关闭回调窗口（终态粘滞适配的回归窗）
            assertEquals(0, server.relayAuthCount(), "AUTH_FAILED 不计入成功鉴权")
            assertTrue(closeReasons.isNotEmpty(), "closedHandler 终态上抛")
            assertTrue(closeReasons[0].contains("AUTH_FAILED"))
        } finally {
            server.stopRelay()
            server.stop()
        }
    }

    // MARK: 传输层 args 约定（2026-11-10）：arg=Arr = 完整位置参数数组原样透传
    // （web toService 同形，多参服务 deleteProject(id, hostId) 形态）；其余单参形态
    // 包一层 [arg]。反面实证：Obj(JSON数组) 被再包一层 → 桌面 spread 后 id=整个数组
    // → SQLite "cannot be bound to parameter 3"。

    @Test
    fun rpcBodyConvention_arrArgsPassThrough_objectsWrapped() = runBlocking {
        val server = E2EStubServer()
        server.start()
        server.relayDeviceSid = relaySid
        server.relayPassHash = relayPassHash
        server.startRelay()
        server.methodHandlers["deleteProject"] = { stubObj("ok" to JSONValue.Bool(true)) }
        server.methodHandlers["readTree"] = { stubObj("projects" to JSONValue.Arr(emptyList())) }
        var harness: Harness? = null
        try {
            val bridgeOpened = AtomicInteger(0)
            harness = connect(server, clientId = "zcode-mobile-relaytest-args", bridgeOpenedCount = bridgeOpened)
            val client = harness.client
            // 多参位置形态：Arr 原样透传（桌面 spread 后 deleteProject(id, hostId)）
            client.call(
                "project", "deleteProject",
                RPCValue.Arr(listOf(RPCValue.Str("proj-1"), RPCValue.Str("local"))),
            )
            awaitUntil("deleteProject 抵达替身") { server.rpcCallCount("deleteProject") >= 1 }
            val recorded = server.rpcCallLog().last { it.command == "deleteProject" }
            val bodyArr = recorded.body?.arrayValue
            assertNotNull(bodyArr, "body 应为位置参数数组（而非再包一层的嵌套数组）")
            assertEquals(2, bodyArr.size, "args=[id, hostId] 两参")
            assertEquals("proj-1", bodyArr[0].stringValue)
            assertEquals("local", bodyArr[1].stringValue)
            // 单对象形态：包一层 [obj]（既有语义不变）
            client.call(
                "project", "readTree",
                RPCValue.json(JSONValue.Obj(mapOf("includeDeleted" to JSONValue.Bool(true)))),
            )
            awaitUntil("readTree 抵达替身") { server.rpcCallCount("readTree") >= 1 }
            val readRecorded = server.rpcCallLog().last { it.command == "readTree" }
            val readBodyArr = readRecorded.body?.arrayValue
            assertNotNull(readBodyArr, "单对象参数 wire body = [obj]（桌面 spread 后成单参）")
            assertEquals(1, readBodyArr.size)
            assertEquals(
                true,
                readBodyArr[0].objectValue?.get("includeDeleted")?.boolValue,
                "对象字段原样可达",
            )
        } finally {
            server.stopRelay()
            server.stop()
        }
    }
}

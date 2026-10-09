package cn.biuz.android.connection

import cn.biuz.android.protocol.ConnectURLParser
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.teststub.E2EStubServer
import cn.biuz.android.teststub.stubObj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 中继门禁 3 + 双链路装配替身级验收（2026-10-10 中继轮；真实中继验证需用户桌面在线且
 * 串行，本轮替身级如实挂账）：
 *
 * ① 瞬断 1.2s 退避重试（A6 红线原文）：桥内 v4 握手首击注水失败（真身
 *    performRelayV4Handshake，onBridgeOpened 钩子单点）→ 清注水 → 断言第二次 hello 在
 *    1.2s±调度偏差后发出且连接最终建成——测真实单点，不测复制品（:relay:test 只覆盖
 *    钩子接线面，理由见该文件头注）。
 * ② AppSession 直连/中继双链路装配：connectOnce 中继分派 → connectRelay → mode 落
 *    Connected + Store 装配 + bootstrap.tasks 派生工作区清单。
 */
class RelayConnectE2ETest {

    private val relaySid = "d-relay-conn-sid"
    private val relayPassHash = "relay-conn-pass-hash"

    private suspend fun awaitUntil(desc: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                error("等待超时（${System.currentTimeMillis() - start}ms）：$desc")
            }
            delay(20)
        }
    }

    private fun relayServerConfig(relayPort: Int): ServerConfig =
        ServerConfig(
            id = "relay-stub-1",
            name = null,
            host = "127.0.0.1",
            port = relayPort,
            useTLS = false,
            token = "",
            relay = cn.biuz.android.protocol.relay.RelayLinkConfig(
                wssURL = "ws://127.0.0.1:$relayPort/ws",
                machineName = "stub-desktop",
                deviceSid = relaySid,
                passHash = relayPassHash,
                desktopAppVersion = null,
            ),
        )

    // MARK: 门禁③：瞬态失败 1.2s 退避重试一次（真身 performRelayV4Handshake）

    @Test
    fun relayConnect_handshakeTransientFailure_retriesOnceAfter1200msBackoff(): Unit = runBlocking {
        val server = E2EStubServer()
        server.start()
        server.relayDeviceSid = relaySid
        server.relayPassHash = relayPassHash
        server.startRelay()
        val connection = ZCodeServerConnection(appVersion = "1.0.0-test")
        try {
            // 桥内 v4 握手首击注水失败（fault.connection.handshakeRequired = 上游 assertReady
            // 拒因原文；「旧桌面缺命令」类瞬态失败同型）
            server.injectMethodFault("helloConversationV4", "fault.connection.handshakeRequired")
            var result: ConnectResult? = null
            val connectJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                result = connection.connectRelay(relayServerConfig(server.relayPort))
            }
            awaitUntil("首击 hello 已发") { server.rpcCallCount("helloConversationV4") >= 1 }
            val firstHelloAt = server.rpcCallLog().first { it.command == "helloConversationV4" }.atMillis
            // 清注水：二次尝试即命中（瞬态失败 1.2s 重试一次）
            server.clearMethodFault("helloConversationV4")
            connectJob.join()
            val connectResult = result
            assertTrue(connectResult is ConnectResult.Success, "清注水后连接建成：$connectResult")

            // 恰好重试一次（A6：重试一次；不是无限退避）
            val helloCalls = server.rpcCallLog().filter { it.command == "helloConversationV4" }
            assertEquals(2, helloCalls.size, "首败 + 重试一次")
            val gapMs = helloCalls[1].atMillis - helloCalls[0].atMillis
            assertTrue(
                gapMs in 1_100..3_500,
                "重试间隔须为 1.2s 退避（实测 ${gapMs}ms，首击 $firstHelloAt）",
            )
            // clientHello 随重试补上（hello 通过后 clientHello 才发；clientId 即连接注册值，A2）
            val accepted = server.handshakes().singleOrNull { it.accepted }
            assertNotNull(accepted, "重试后 clientHello 过 strict 对拍")
            assertEquals(connection.registeredClientId, accepted.clientId, "envelope/握手 clientId 逐字相等（A2）")
            assertTrue(connection.isActive)
            assertTrue(connection.supportsWorkspaceSwitching, "中继连接支持工作区切换")
            assertNotNull(connection.relayBootstrapTasks, "bootstrap.tasks 已采集（未注水 = 空清单）")
        } finally {
            connection.disconnect()
            server.stopRelay()
            server.stop()
        }
    }

    // MARK: AppSession 双链路装配（中继配置 → connectRelay → Connected + Store 装配）

    @Test
    fun appSession_relayConfig_assemblesStoresAndBootstrapWorkspaces() = runBlocking {
        val server = E2EStubServer()
        server.start()
        server.relayDeviceSid = relaySid
        server.relayPassHash = relayPassHash
        server.startRelay()
        server.setBootstrapTasks(
            listOf(
                stubObj(
                    "sessionId" to JSONValue.Str("sess-a"),
                    "workspacePath" to JSONValue.Str("/Users/e2e/ws-alpha"),
                ),
                stubObj(
                    "sessionId" to JSONValue.Str("sess-b"),
                    "workspacePath" to JSONValue.Str("/Users/e2e/ws-beta"),
                ),
            ),
        )
        val app = AppSession(appVersion = "1.0.0-test")
        try {
            assertTrue(app.mode.value !is AppSession.Mode.Connected, "起点未连接")
            app.connect(relayServerConfig(server.relayPort))
            val mode = app.mode.value
            assertIs<AppSession.Mode.Connected>(mode, "中继配置连接成功落 Connected：$mode")
            assertTrue(app.isConnected)
            // Store 装配（ConnectionTransport 唯一出口对中继路径同等生效）
            assertNotNull(app.storeSurface.value.sessionsIndex, "sessions-index Store 已装配")
            assertNotNull(app.storeSurface.value.workspace)
            // bootstrap.tasks 派生工作区清单（双 workspace 注水 → 两个派生条目）
            awaitUntil("bootstrap 派生清单就绪") { app.bootstrapWorkspaces.value.size == 2 }
            assertEquals(
                listOf("/Users/e2e/ws-alpha", "/Users/e2e/ws-beta"),
                app.bootstrapWorkspaces.value.map { it.path },
            )
            // 切换器合并清单含连接层 workspace-list-response 条目
            assertTrue(app.switcherWorkspaces.isNotEmpty())
            // 切换工作区（P3-10 全链）：目标取 bootstrap 派生工作区（清单外路径按 web 同款
            // 公式解析为 key——resolvedWorkspaceKey C-12 口径）→ 桥重开 → Store 重装配
            val target = ServerWorkspaceInfo(
                path = "/Users/e2e/ws-alpha",
                label = "ws-alpha",
                workspaceIdentity = null,
            )
            val switchFailure = app.switchWorkspace(target)
            assertNull(switchFailure, "切换合法目标应成功：$switchFailure")
            assertEquals("/Users/e2e/ws-alpha", app.storeSurface.value.workspace?.path, "切换后装配工作区落点")
        } finally {
            app.connection.disconnect()
            server.stopRelay()
            server.stop()
        }
    }

    // MARK: 配对链接解析接线（mid/token 形态，协议文档 §3.3）

    @Test
    fun relayLinkParsing_feedsAssemblyConfig() {
        val link =
            "https://zcode.z.ai/remote/v4?sid=$relaySid&hash=juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4%3D" +
                "&t=1791033721496&mid=4499673e-d0f8-49ef-ada8-623c18767c24&name=MacBook-Pro-8.local&app_version=3.14.4"
        val parsed = ConnectURLParser.parseRelayLink(link)
        assertNotNull(parsed, "mid/token 形态可解析")
        assertEquals("wss://zcode.z.ai/ws?mid=4499673e-d0f8-49ef-ada8-623c18767c24", parsed.wssURL, "WS 端点形态（sid/hash 不进 URL）")
        assertEquals(relaySid, parsed.deviceSid)
        assertEquals("juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4=", parsed.passHash, "hash 为 URL 解码后的 base64 原串")
        assertEquals("3.14.4", parsed.desktopAppVersion)
        assertEquals("MacBook-Pro-8.local", parsed.machineName)
        assertEquals("zcode.z.ai", parsed.endpointHost)
    }
}

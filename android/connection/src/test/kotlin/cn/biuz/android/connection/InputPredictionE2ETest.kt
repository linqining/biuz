package cn.biuz.android.connection

// 3.17.2 输入预测 ghost hint 全链路 E2E（真实 OkHttp WebSocket ↔ Ktor 替身）：
// - 能力门链路：hello.capabilities.inputPrediction=true → connection.inputPredictionAvailable()
//   → Store 构造注入 → 请求真发；能力缺席 → 恒 null 且零 RPC（spec 手机端建议①）；
// - 线格式对拍（spec 线格式表 + contract.ts strict schema）：会话 target（A7）+ revision
//   + draft（≤4000，空省略）+ requestId（≤128）；
// - 回执 decision=show → topText=首候选（不 trim）；RPC 异常 → null（spec 失败语义）。
// Mock 仅测试装配（红线 §0-4）：替身桌面承载回执，客户端为真实实现。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.store.RemoteConversationStore
import cn.biuz.android.teststub.E2EStubServer
import cn.biuz.android.teststub.stubObj
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

class InputPredictionE2ETest {

    /** conversationInputPredictionV4 请求 arg 账（线格式对拍面）。 */
    private val predictionRequests = LinkedBlockingQueue<JSONValue>()

    private fun stubWithPrediction(): E2EStubServer = E2EStubServer().apply {
        // Host 侧总开关缺省开启（mobile-input-prediction.md 开关表：缺省开启）
        serverCapabilities["inputPrediction"] = JSONValue.Bool(true)
        methodHandlers["conversationInputPredictionV4"] = { args ->
            args?.let { predictionRequests.add(it) }
            stubObj(
                "decision" to JSONValue.Str("show"),
                "candidates" to JSONValue.Arr(
                    listOf(
                        stubObj("text" to JSONValue.Str(" 再跑一遍测试"), "confidence" to JSONValue.Double(0.9)),
                    ),
                ),
                "topConfidence" to JSONValue.Double(0.9),
                "requestId" to (args?.get("requestId")?.stringValue ?: "").let { JSONValue.Str(it) },
            )
        }
    }

    private fun predictionStore(server: E2EStubServer, connection: ZCodeServerConnection): RemoteConversationStore =
        RemoteConversationStore(
            transport = ConnectionTransport(connection),
            frameSurface = connection,
            workspace = cn.biuz.android.store.StoreWorkspaceInfo(
                connection.workspace?.path ?: server.workspacePath,
            ),
            clientId = connection.registeredClientId,
            inputPredictionEnabled = connection.inputPredictionAvailable(),
        )

    @Test
    fun capabilityGateToWireShapeToShowReply(): Unit = runBlocking {
        val server = stubWithPrediction()
        val port = server.start()
        val connection = ZCodeServerConnection(appVersion = "0.1.0-e2e")
        try {
            val result = connection.connect(
                server = ServerConfig(id = "e2e", name = null, host = "127.0.0.1", port = port, useTLS = false, token = ""),
            )
            assertTrue(result is ConnectResult.Success, "连接应成功：${(result as? ConnectResult.Failure)?.error}")
            assertTrue(
                connection.inputPredictionAvailable(),
                "hello.capabilities.inputPrediction=true → 能力位开启",
            )

            val store = predictionStore(server, connection)
            val reply = store.inputPrediction("sess-e2e-1", draft = "帮我", requestId = "r-1")
            assertNotNull(reply, "show 决策应产出候选")
            assertEquals("show", reply.decision)
            assertEquals(" 再跑一遍测试", reply.topText, "首候选原样（不 trim）")

            // 线格式对拍（spec 线格式表）
            val request = predictionRequests.poll() ?: fail("替身应收到一次预测请求")
            val fields = request.objectValue ?: fail("请求应为对象")
            assertEquals("sess-e2e-1", fields["sessionId"]?.stringValue, "会话 target（A7）")
            assertEquals(server.workspacePath, fields["workspacePath"]?.stringValue, "workspace 信封（A7）")
            assertEquals(0, fields["revision"]?.intValue, "state 缓存缺省 revision=0（int ≥0 合同）")
            assertEquals("帮我", fields["draft"]?.stringValue)
            assertEquals("r-1", fields["requestId"]?.stringValue)
            assertEquals(1, server.rpcCallCount("conversationInputPredictionV4"))

            // 空草稿省略（= 预测下一条完整输入）+ 超长截断
            store.inputPrediction("sess-e2e-1", draft = "", requestId = "r-2")
            val emptyReq = predictionRequests.poll()?.objectValue ?: fail()
            assertNull(emptyReq["draft"], "空草稿省略 = 预测下一条完整输入")
            store.inputPrediction("sess-e2e-1", draft = "x".repeat(5000), requestId = "r-3")
            val longReq = predictionRequests.poll()?.objectValue ?: fail()
            assertEquals(4000, longReq["draft"]?.stringValue?.length, "超长草稿手机侧截断")
        } finally {
            connection.disconnect()
            server.stop()
        }
    }

    @Test
    fun capabilityAbsentMeansNoRequestAtAll(): Unit = runBlocking {
        val server = E2EStubServer() // 未宣告 inputPrediction（旧 Host 口径）
        val port = server.start()
        val connection = ZCodeServerConnection(appVersion = "0.1.0-e2e")
        try {
            val result = connection.connect(
                server = ServerConfig(id = "e2e", name = null, host = "127.0.0.1", port = port, useTLS = false, token = ""),
            )
            assertTrue(result is ConnectResult.Success, "连接应成功")
            assertEquals(false, connection.inputPredictionAvailable(), "旧 Host 能力缺席 = 关闭")

            val store = predictionStore(server, connection)
            val reply = store.inputPrediction("sess-e2e-1", draft = "帮我", requestId = "r-1")
            assertNull(reply, "能力门关闭恒 null")
            assertEquals(0, server.rpcCallCount("conversationInputPredictionV4"), "不得发出预测 RPC（spec 手机端建议①）")
        } finally {
            connection.disconnect()
            server.stop()
        }
    }
}

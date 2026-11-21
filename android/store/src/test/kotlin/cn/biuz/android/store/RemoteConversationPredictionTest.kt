package cn.biuz.android.store

// 3.17.2 输入预测 ghost hint :store 面（iOS RemoteConversationStore.inputPrediction
// :2924-2964 门禁面直译）：
// - 能力门：inputPredictionEnabled=false 恒 null 且零调用（spec「手机端建议①」：
//   hello.capabilities.inputPrediction 非 true 不得发起）；
// - 请求形状：A7 会话 target（sessionId/workspacePath[/workspaceIdentity]）+ revision
//   （state 缓存缺省 0）+ draft ≤4000（空省略 = 预测下一条完整输入）+ requestId ≤128
//   （contract.ts strict schema 多余字段会被拒）；
// - 失败语义：RPC 异常吞掉回 null（spec「任何异常 = 无 hint」，不提示用户、不重试）；
// - 回执解析：decision 必填 / candidates {text, confidence} 逐元素宽容 / topText 仅
//   decision=="show" 且首候选非纯空白（iOS Models.swift:373-390 同口径）。
// Mock 仅测试装配（红线 §0-4）。

import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** 输入预测注水替身：捕获 conversationInputPredictionV4 请求 arg；其余命令一律抛错。 */
private class PredictionTransport : RPCChannelTransport {

    @Volatile
    var predictionReply: JSONValue? = null

    /** 非空 = 预测请求抛错（spec 失败语义注水：通道断开/超时族）。 */
    @Volatile
    var predictionError: Throwable? = null

    /** 最近一次预测请求 arg（请求形状断言面）。 */
    @Volatile
    var lastPredictionArg: JSONValue? = null

    override suspend fun call(
        channel: String,
        command: String,
        arg: RPCValue,
        timeout: kotlin.time.Duration,
    ): RPCValue {
        if (command == "conversationInputPredictionV4") {
            lastPredictionArg = arg.jsonValue
            predictionError?.let { throw it }
            return RPCValue.json(predictionReply ?: JSONValue.Null)
        }
        throw RPCError(message = "fake: unhandled $command", name = "ErrorObj")
    }

    override suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue,
        handler: (RPCValue) -> Unit,
        onError: ((RPCError) -> Unit)?,
    ): EventSubscription = EventSubscription { }

    override suspend fun disconnect() {}
}

/** 输入预测测试装置（RemoteConversationStoreTest.Fixture 口径；能力门可注入）。 */
private class PredictionFixture(
    scope: TestScope,
    private val enabled: Boolean,
) {
    val transport = PredictionTransport()
    val frameSurface = FakeConversationFrameSurface(CopyOnWriteArrayList())
    val store = RemoteConversationStore(
        transport = transport,
        frameSurface = frameSurface,
        workspace = StoreWorkspaceInfo("/conn/ws", workspaceIdentity = "conn-ident"),
        clientId = "test-client-1",
        inputPredictionEnabled = enabled,
        backgroundDispatcher = StandardTestDispatcher(scope.testScheduler),
    )
}

class RemoteConversationPredictionTest {

    @Test
    fun capabilityGateOffReturnsNullWithoutAnyCall() = runTest {
        val fixture = PredictionFixture(this, enabled = false)
        val result = fixture.store.inputPrediction("sess-1", draft = "帮我", requestId = "r-1")
        assertNull(result)
        assertNull(fixture.transport.lastPredictionArg, "能力门关闭不得发出预测 RPC（spec 手机端建议①）")
    }

    @Test
    fun requestCarriesSessionTargetRevisionDraftAndRequestId() = runTest {
        val fixture = PredictionFixture(this, enabled = true)
        fixture.transport.predictionReply = testObj(
            "decision" to JSONValue.Str("show"),
            "candidates" to JSONValue.Arr(
                listOf(
                    testObj("text" to JSONValue.Str(" 再跑一遍测试"), "confidence" to JSONValue.Double(0.9)),
                ),
            ),
            "topConfidence" to JSONValue.Double(0.9),
            "requestId" to JSONValue.Str("r-1"),
        )
        val result = fixture.store.inputPrediction("sess-1", draft = "帮我", requestId = "r-1")
        assertEquals(" 再跑一遍测试", result?.topText)
        assertEquals("r-1", result?.requestId)
        val arg = fixture.transport.lastPredictionArg?.objectValue
        assertEquals("sess-1", arg?.get("sessionId")?.stringValue, "A7 会话 target 必须在场")
        assertEquals("/conn/ws", arg?.get("workspacePath")?.stringValue)
        assertEquals(0L, arg?.get("revision")?.intValue?.toLong(), "state 缓存缺省 revision=0（int ≥0 合同）")
        assertEquals("帮我", arg?.get("draft")?.stringValue)
        assertEquals("r-1", arg?.get("requestId")?.stringValue)
    }

    @Test
    fun emptyDraftOmittedAndOversizedFieldsClamped() = runTest {
        val fixture = PredictionFixture(this, enabled = true)
        fixture.transport.predictionReply = testObj(
            "decision" to JSONValue.Str("show"),
            "candidates" to JSONValue.Arr(emptyList()),
            "topConfidence" to JSONValue.Double(0.0),
        )
        val longDraft = "x".repeat(5000)
        val longRequestId = "r".repeat(300)
        fixture.store.inputPrediction("sess-1", draft = longDraft, requestId = longRequestId)
        val arg = fixture.transport.lastPredictionArg?.objectValue
        assertEquals(4000, arg?.get("draft")?.stringValue?.length, "超长草稿手机侧截断（spec 线格式表）")
        assertEquals(128, arg?.get("requestId")?.stringValue?.length, "requestId 1..128（contract.ts）")
        fixture.store.inputPrediction("sess-1", draft = "", requestId = "r-1")
        val emptyArg = fixture.transport.lastPredictionArg?.objectValue
        assertFalse(emptyArg?.containsKey("draft") ?: false, "空草稿省略 = 预测下一条完整输入（spec 线格式表）")
    }

    @Test
    fun rpcErrorSwallowedAsNoHint() = runTest {
        val fixture = PredictionFixture(this, enabled = true)
        fixture.transport.predictionError = RPCError(message = "通道断开", name = "ErrorObj")
        val result = fixture.store.inputPrediction("sess-1", draft = "帮我", requestId = "r-1")
        assertNull(result, "spec 失败语义：任何异常 = 无 hint，不提示用户、不重试")
    }

    @Test
    fun parseTopTextOnlyForShowDecisionWithNonBlankCandidate() {
        val store = RemoteConversationStore(
            transport = PredictionTransport(),
            frameSurface = FakeConversationFrameSurface(CopyOnWriteArrayList()),
            workspace = StoreWorkspaceInfo("/ws"),
            clientId = "c",
        )
        fun result(decision: String?, candidates: List<Pair<String, Double>>) =
            store.parseInputPrediction(
                testObj(
                    "decision" to decision?.let { JSONValue.Str(it) },
                    "candidates" to JSONValue.Arr(
                        candidates.map {
                            testObj("text" to JSONValue.Str(it.first), "confidence" to JSONValue.Double(it.second))
                        },
                    ),
                ),
                revision = 7L,
            )

        // show + 非纯空白首候选 → topText
        assertEquals(" 续写文本", result("show", listOf(" 续写文本" to 0.9))?.topText)
        // suppress 族 → 不展示
        assertNull(result("suppress_below_threshold", listOf(" 文本" to 0.5))?.topText)
        assertNull(result("suppress_no_context", emptyList())?.topText)
        assertNull(result("unavailable", emptyList())?.topText)
        assertNull(result("cancelled", listOf(" 文本" to 0.9))?.topText)
        // 纯空白候选 → 不展示（contract.ts：候选不能是纯空白）
        assertNull(result("show", listOf("   " to 0.9))?.topText)
        // decision 缺席/形状不识别 → null（无 hint）
        assertNull(result(null, emptyList()))
    }
}

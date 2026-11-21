package cn.biuz.android.app

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.teststub.E2EStubServer
import cn.biuz.android.teststub.stubNum
import cn.biuz.android.teststub.stubObj
import java.io.File

/**
 * 替身服务器活体宿主（模拟器/真机链路验证专用，非常规单测）：
 * 在宿主机起 E2EStubServer → 注水「置顶/运行转圈/等待确认」三行 sessions-index 快照 →
 * 把端口写 /tmp/m2ui-stub-port → 阻塞等 /tmp/m2ui-stub-stop 哨兵（UI 侧验证完写哨兵收尾）。
 * **默认禁用**（普通套件零开销跳过；否则它会阻塞 10 分钟）——手工启用方式：
 *   gradle -p android :app:testDebugUnitTest --tests cn.biuz.android.app.StubLiveServerTest
 *        -Dbiuz.stubLive=true
 * 模拟器内以 10.0.2.2:<port> 手动连接（宿主机回环映射）。
 */
class StubLiveServerTest {

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "biuz.stubLive", matches = "true")
    fun hostStubUntilSentinel() {
        val server = E2EStubServer()
        // 3.17.2 输入预测：Host 能力宣告 + 回执注水（模拟器 ghost hint 冒烟）
        server.serverCapabilities["inputPrediction"] = JSONValue.Bool(true)
        server.methodHandlers["conversationInputPredictionV4"] = { args ->
            stubObj(
                "decision" to JSONValue.Str("show"),
                "candidates" to JSONValue.Arr(
                    listOf(stubObj("text" to JSONValue.Str(" 替身预测续写文本"), "confidence" to JSONValue.Double(0.9))),
                ),
                "topConfidence" to JSONValue.Double(0.9),
                "requestId" to JSONValue.Str(args?.get("requestId")?.stringValue ?: ""),
            )
        }
        val port = server.start()
        server.setSessionsIndexSnapshot(snapshot(server.workspacePath))
        File(PORT_FILE).writeText(port.toString())
        println("STUB-LIVE port=$port workspace=${server.workspacePath}")
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < deadline && !File(STOP_SENTINEL).exists()) {
            Thread.sleep(500)
        }
        server.stop()
        File(PORT_FILE).delete()
        println("STUB-LIVE stopped")
    }

    /** 三行注水（形状 = E2EStubServer.defaultSessionsIndexSnapshot 同构 + pinned/pending 键宽容读）。 */
    private fun snapshot(workspacePath: String): JSONValue {
        val now = System.currentTimeMillis()
        return stubObj(
            "logEpoch" to JSONValue.Str("e2e-live"),
            "sessions" to JSONValue.Arr(
                listOf(
                    // 置顶行（pinned 行字段宽容读 → isPinned=true；26h 前 = 昨天/更早组）
                    stubObj(
                        "sessionId" to JSONValue.Str("sess-ui-pinned"),
                        "title" to JSONValue.Str("置顶 · 组织态读 membership"),
                        "phase" to JSONValue.Str("completedSuccess"),
                        "lastActivityAt" to stubNum(now - 26 * 60 * 60_000L),
                        "lastAssistantPreview" to JSONValue.Str("置顶行样张。"),
                        "workspacePath" to JSONValue.Str(workspacePath),
                        "pinned" to JSONValue.Bool(true),
                    ),
                    // 运行行（phase=running → 双源合成 isRunning → 行转圈）
                    stubObj(
                        "sessionId" to JSONValue.Str("sess-ui-run"),
                        "title" to JSONValue.Str("运行中 · 冒烟验收"),
                        "phase" to JSONValue.Str("running"),
                        "lastActivityAt" to stubNum(now),
                        "lastAssistantPreview" to JSONValue.Str("替身端持续输出中…"),
                        "workspacePath" to JSONValue.Str(workspacePath),
                    ),
                    // 等待确认行（pendingInteractionSummary → pendingApprovalCount → 橙标 + 转圈）
                    stubObj(
                        "sessionId" to JSONValue.Str("sess-ui-wait"),
                        "title" to JSONValue.Str("等待确认 · 审批在场"),
                        "phase" to JSONValue.Str("running"),
                        "lastActivityAt" to stubNum(now - 60_000L),
                        "lastAssistantPreview" to JSONValue.Str("等待你的确认。"),
                        "workspacePath" to JSONValue.Str(workspacePath),
                        "pendingInteractionSummary" to stubObj(
                            "permissionCount" to stubNum(2),
                            "userInputCount" to stubNum(0),
                        ),
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val PORT_FILE = "/tmp/m2ui-stub-port"
        const val STOP_SENTINEL = "/tmp/m2ui-stub-stop"
    }
}

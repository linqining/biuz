package cn.biuz.android.store

// 子代理转录行映射门（G-021 同构渲染轮，2026-10-08）：toolCallRowMessage 是主会话
// rebuildMessages 与 actorTranscript 共用的唯一 toolCall 行映射——两处漂移在此拦下。
// 只读转录（includeRetryCursor=false）不带 entityId，工具卡「重试」门槛（G-015）
// 使转录内天然无重试死入口。
// 平移自 ios/UnitTests/ActorTranscriptMappingTests.swift（逐用例断言语义一一对应）；
// 被测 API = RemoteConversationStore.toolCallRowMessage（本模块 main，
// RemoteConversationStore.kt:1432，iOS RemoteConversationStore.swift:1874 同构直译）。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.ToolCallStatus
import cn.biuz.android.protocol.ToolKind
import cn.biuz.android.protocol.V4TopicFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 纯映射用例的帧面替身：toolCallRowMessage 不触帧面，四方法全 no-op。 */
private class NoopFrameSurface : ConversationFrameSurface {
    override suspend fun ensureConversationFrameStream(
        workspacePath: String,
        workspaceIdentity: String?,
    ) = Unit

    override fun setFrameHandler(topic: String, handler: (V4TopicFrame) -> Unit) = Unit

    override fun removeFrameHandler(topic: String) = Unit

    override fun setFrameDropHandler(key: String, handler: () -> Unit) = Unit
}

class ActorTranscriptMappingTest {

    /** toolCallRowMessage 是实例方法（非伴生静态）：纯映射用例挂最小装配替身构造。 */
    private val store = RemoteConversationStore(
        transport = FakeRemoteTransport(),
        frameSurface = NoopFrameSurface(),
        workspace = StoreWorkspaceInfo(path = "/conn/ws", workspaceIdentity = "conn-ident"),
        clientId = "test-client-1",
    )

    /** iOS 原文 row（ActorTranscriptMappingTests.swift:9-19）逐键直译。 */
    private val row: Map<String, JSONValue> = mapOf(
        "rowId" to JSONValue.Int(3),
        "kind" to JSONValue.Str("toolCall"),
        "toolCallId" to JSONValue.Str("tool-e2e-1"),
        "toolName" to JSONValue.Str("Bash"),
        "status" to JSONValue.Str("success"),
        "inputText" to JSONValue.Str("grep -rn useUserInfo packages/shared"),
        "output" to JSONValue.Obj(
            mapOf("text" to JSONValue.Str("index.tsx:46:export function useUserInfo")),
        ),
        "display" to JSONValue.Obj(
            mapOf(
                "addedLines" to JSONValue.Int(2),
                "removedLines" to JSONValue.Int(1),
            ),
        ),
        "entityId" to JSONValue.Str("row-entity-abc"),
    )

    @Test
    fun testFullMappingCarriesCommandAndOutput() {
        val message = store.toolCallRowMessage(
            row, rowId = 3, id = "transcript-s-3", includeRetryCursor = false)
        val call = assertNotNull(message.toolCall)
        assertEquals(ToolKind.bash, call.kind)
        assertEquals("grep -rn useUserInfo packages/shared", call.target)
        assertEquals("index.tsx:46:export function useUserInfo", call.output)
        assertEquals(2, call.addedLines)
        assertEquals(1, call.removedLines)
        assertEquals(ToolCallStatus.done, call.status)
        assertEquals("transcript-s-3", message.id)
    }

    @Test
    fun testReadOnlyTranscriptOmitsRetryCursor() {
        val transcript = store.toolCallRowMessage(
            row, rowId = 3, id = "transcript-s-3", includeRetryCursor = false)
        assertNull(transcript.toolCall?.entityId, "只读转录不带 entityId（无重试死入口）")

        val main = store.toolCallRowMessage(
            row, rowId = 3, id = "row-3", includeRetryCursor = true)
        assertEquals("row-entity-abc", main.toolCall?.entityId, "主会话保留重试游标")
    }

    @Test
    fun testFailedStatusMapsFailed() {
        val failedRow = row + ("status" to JSONValue.Str("error"))
        val message = store.toolCallRowMessage(
            failedRow, rowId = 3, id = "row-3", includeRetryCursor = false)
        assertEquals(ToolCallStatus.failed, message.toolCall?.status)
    }

    @Test
    fun testMissingInputFallsBackToToolName() {
        val bare = row - "inputText" - "output" - "display" - "toolCallId"
        val message = store.toolCallRowMessage(
            bare, rowId = 7, id = "row-7", includeRetryCursor = false)
        assertEquals("Bash", message.toolCall?.target, "inputText 缺席回退 toolName（宽容解析）")
        assertNull(message.toolCall?.output)
        assertEquals("tool-7", message.toolCall?.id, "toolCallId 缺席回退 rowId 合成")
    }
}

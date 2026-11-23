package cn.biuz.android.app.ui.chat

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.MessageStatus
import cn.biuz.android.protocol.ThinkingContent
import cn.biuz.android.protocol.ThinkingState
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.protocol.ToolCallStatus
import cn.biuz.android.protocol.ToolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回合分组（web conversationTurnRenderUnits/conversationAssistantWorkItems 子集，2026-11-23）。
 * 断言：按 turnIndex 聚合与顺序、用户/工作项拆分、散行尾单元、运行态识别、摘要分类计数。
 */
class TurnGroupingTest {

    private fun msg(
        id: String,
        turn: Int,
        role: MessageRole = MessageRole.agent,
        status: MessageStatus = MessageStatus.done,
        tool: ToolCall? = null,
        thinking: ThinkingContent? = null,
        text: String = "",
    ) = ChatMessage(
        id = id,
        role = role,
        text = text,
        status = status,
        toolCall = tool,
        thinking = thinking,
        timestamp = 0,
        turnIndex = turn,
    )

    private fun tool(name: String, status: ToolCallStatus = ToolCallStatus.done) = ToolCall(
        id = "tool-$name",
        kind = ToolKind.bash,
        target = "{}",
        status = status,
        toolName = name,
    )

    @Test
    fun groupsByTurnIndexPreservingOrderAndSplitsUserWork() {
        val messages = listOf(
            msg("u0", 0, role = MessageRole.user, text = "第一问"),
            msg("t0a", 0, tool = tool("Bash")),
            msg("a0", 0, text = "第一答"),
            msg("u1", 1, role = MessageRole.user, text = "第二问"),
            msg("t1a", 1, tool = tool("Edit")),
        )
        val turns = TurnGrouping.group(messages)
        assertEquals(listOf("turn-0", "turn-1"), turns.map { it.key })
        assertEquals(listOf("u0"), turns[0].userMessages.map { it.id })
        assertEquals(listOf("t0a", "a0"), turns[0].workItems.map { it.id }, "工作项保持行序")
        assertEquals(listOf("u1"), turns[1].userMessages.map { it.id })
        assertEquals(listOf("t1a"), turns[1].workItems.map { it.id })
    }

    @Test
    fun looseRowsGoToTrailingUnit() {
        val messages = listOf(
            msg("u0", 0, role = MessageRole.user, text = "问"),
            msg("echo", -1, role = MessageRole.user, text = "在途回显"),
            msg("todos", -1, text = ""),
        )
        val turns = TurnGrouping.group(messages)
        assertEquals(2, turns.size)
        assertEquals("turn-0", turns[0].key)
        assertEquals(TurnGrouping.LOOSE_KEY, turns[1].key)
        assertEquals(-1, turns[1].index)
        assertEquals(listOf("echo"), turns[1].userMessages.map { it.id })
        assertEquals(listOf("todos"), turns[1].workItems.map { it.id })
        assertEquals(1, turns[0].userMessages.size, "散行不回灌历史回合")
    }

    @Test
    fun runningTurnDetectedByStreamingStatus() {
        val messages = listOf(
            msg("u0", 0, role = MessageRole.user),
            msg("t0", 0, tool = tool("Bash", ToolCallStatus.running), status = MessageStatus.streaming),
            msg("u1", 1, role = MessageRole.user),
            msg("t1", 1, tool = tool("Read")),
        )
        val turns = TurnGrouping.group(messages)
        assertTrue(turns[0].isRunning, "含 streaming 消息的回合 = 运行中")
        assertTrue(!turns[1].isRunning)
    }

    @Test
    fun summaryCountsWorkItemKinds() {
        val messages = listOf(
            msg("r1", 0, thinking = ThinkingContent(text = "想", state = ThinkingState.done)),
            msg("c1", 0, tool = tool("Bash")),
            msg("c2", 0, tool = tool("Shell")),
            msg("e1", 0, tool = tool("Edit")),
            msg("x1", 0, tool = tool("Read")),
            msg("x2", 0, tool = tool("Grep")),
            msg("x3", 0, tool = tool("WebFetch")),
            msg("o1", 0, text = "正文"),
        )
        val summary = TurnGrouping.group(messages).single().summary()
        assertEquals(1, summary.reasoningCount)
        assertEquals(2, summary.commandCount, "Bash/Shell → 命令层")
        assertEquals(1, summary.changeCount, "Edit → 更改文件层")
        assertEquals(3, summary.exploreCount, "Read/Grep/WebFetch → 探索层")
        assertEquals(1, summary.otherCount, "正文/卡片归 Other（打断工具摘要组）")
        assertEquals(8, summary.total)
    }

    @Test
    fun emptyInputProducesNoTurns() {
        assertEquals(emptyList(), TurnGrouping.group(emptyList()))
    }
}

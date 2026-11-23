package cn.biuz.android.app.ui.chat

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.MessageStatus

/**
 * 回合分组（web `v4/conversationTurnRenderUnits.ts` + `conversationAssistantWorkItems.ts`
 * 的移动端最小子集，2026-11-23）。
 *
 * 目的：会话页按「回合」聚合渲染——用户输入在回合顶部，助手工作项（思考/工具/正文/产物/
 * 卡片）随后；已完成回合默认折叠为一条工作摘要（"已工作 · 探索 N 项 · 运行 N 条命令…"），
 * 运行中回合保持展开。纯函数（零 Compose 依赖）——JVM 单测直测。
 */
enum class WorkItemKind { Explore, Command, Change, Reasoning, Other }

/** 回合工作项统计（web work-items 摘要四类 + 其它）。 */
data class WorkSummary(
    val exploreCount: Int = 0,
    val commandCount: Int = 0,
    val changeCount: Int = 0,
    val reasoningCount: Int = 0,
    val otherCount: Int = 0,
) {
    val total: Int
        get() = exploreCount + commandCount + changeCount + reasoningCount + otherCount

    operator fun plus(other: WorkSummary): WorkSummary = WorkSummary(
        exploreCount = exploreCount + other.exploreCount,
        commandCount = commandCount + other.commandCount,
        changeCount = changeCount + other.changeCount,
        reasoningCount = reasoningCount + other.reasoningCount,
        otherCount = otherCount + other.otherCount,
    )

    companion object {
        val EMPTY = WorkSummary()
    }
}

/** 回合单元：用户消息 + 助手工作项（均保持原始行序）。 */
data class TurnUnit(
    /** 渲染键：`turn-<index>`；散行（回显/会话级卡）为 `turn-loose`。 */
    val key: String,
    val index: Int,
    val userMessages: List<ChatMessage>,
    val workItems: List<ChatMessage>,
    val isRunning: Boolean,
) {
    val isEmpty: Boolean
        get() = userMessages.isEmpty() && workItems.isEmpty()

    /** 只对已完成回合计算（运行中回合展示实时项，不做摘要）。 */
    fun summary(): WorkSummary = workItems.fold(WorkSummary.EMPTY) { acc, item ->
        acc + WorkSummary(
            exploreCount = if (TurnGrouping.kindOf(item) == WorkItemKind.Explore) 1 else 0,
            commandCount = if (TurnGrouping.kindOf(item) == WorkItemKind.Command) 1 else 0,
            changeCount = if (TurnGrouping.kindOf(item) == WorkItemKind.Change) 1 else 0,
            reasoningCount = if (TurnGrouping.kindOf(item) == WorkItemKind.Reasoning) 1 else 0,
            otherCount = if (TurnGrouping.kindOf(item) == WorkItemKind.Other) 1 else 0,
        )
    }
}

object TurnGrouping {

    const val LOOSE_KEY = "turn-loose"

    /** 消息 → 回合单元（输入序稳定；turnIndex < 0 的散行归尾单元）。 */
    fun group(messages: List<ChatMessage>): List<TurnUnit> {
        val ordered = LinkedHashMap<Int, MutableList<ChatMessage>>()
        val loose = ArrayList<ChatMessage>()
        for (message in messages) {
            if (message.turnIndex < 0) {
                loose.add(message)
                continue
            }
            ordered.getOrPut(message.turnIndex) { ArrayList() }.add(message)
        }
        val out = ArrayList<TurnUnit>(ordered.size + if (loose.isEmpty()) 0 else 1)
        for ((index, items) in ordered) {
            val users = items.filter { it.role == MessageRole.user }
            val work = items.filter { it.role != MessageRole.user }
            val running = items.any { it.status == MessageStatus.streaming }
            out.add(
                TurnUnit(
                    key = "turn-$index",
                    index = index,
                    userMessages = users,
                    workItems = work,
                    isRunning = running,
                ),
            )
        }
        if (loose.isNotEmpty()) {
            out.add(
                TurnUnit(
                    key = LOOSE_KEY,
                    index = -1,
                    userMessages = loose.filter { it.role == MessageRole.user },
                    workItems = loose.filter { it.role != MessageRole.user },
                    isRunning = loose.any { it.status == MessageStatus.streaming },
                ),
            )
        }
        return out
    }

    /** 工作项分类（web 摘要四类；agent 文本/卡归 Other——它们打断 web 的工具摘要组）。 */
    fun kindOf(message: ChatMessage): WorkItemKind {
        if (message.thinking != null) return WorkItemKind.Reasoning
        val tool = message.toolCall ?: return WorkItemKind.Other
        return when (ToolDisplay.familyOf(tool.toolName)) {
            ToolFamily.Shell -> WorkItemKind.Command
            ToolFamily.FileWrite -> WorkItemKind.Change
            ToolFamily.FileRead, ToolFamily.Search, ToolFamily.WebFetch, ToolFamily.Mcp ->
                WorkItemKind.Explore
            ToolFamily.Agent, ToolFamily.Todo, ToolFamily.Workflow, ToolFamily.Generic ->
                WorkItemKind.Other
        }
    }
}

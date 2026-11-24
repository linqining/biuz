package cn.biuz.android.app.ui.chat

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.protocol.ToolCallStatus

/**
 * 工具卡展示模型（web `lib/toolIdentity.ts` + `lib/toolDisplay.ts` +
 * `ToolCallBlocks/renderers` 渲染器的移动端子集，2026-11-23）。
 *
 * 目标：工具行不再裸显示 `inputText` JSON——按工具族提取主文案（命令/文件/查询/URL/
 * MCP 工具名），动作词按运行/完成/失败三态本地化，参数与输出结构化展示。
 * 全部纯函数（零 Compose 依赖）——JVM 单测直测；UI 只消费 [ToolDisplayModel]。
 */
enum class ToolFamily {
    Shell,
    FileRead,
    FileWrite,
    Search,
    WebFetch,
    Mcp,
    Agent,
    Todo,
    Workflow,
    Generic,
}

/** 工具卡阶段（ToolCallStatus 三态 + 输入未产出时的 pending 归并）。 */
enum class ToolPhase { Pending, Running, Done, Failed, Stopped }

data class ToolDisplayModel(
    val family: ToolFamily,
    /** 原始工具名（空串 = 行未携带） */
    val toolName: String,
    val phase: ToolPhase,
    /**
     * 主文案：命令 / 文件 basename（含路径 tooltip 语义）/ 查询词 / URL /
     * `server · tool` / 通用标题。
     */
    val primary: String,
    /** 副文案（input.description，web secondary 同源）。 */
    val secondary: String?,
    /** 展开体：结构化参数（`key = value` 行或 JSON 原文；null = 无参数）。 */
    val parameters: String?,
    /** Bash 命令（展开体命令块；null = 非命令类）。 */
    val command: String?,
    /** 输出文本（展开体输出块；null = 无输出）。 */
    val output: String?,
    /** 失败态错误文本（优先 output 中错误、其次 output 全文）。 */
    val errorText: String?,
    val addedLines: Int?,
    val removedLines: Int?,
    /** 展开体文件路径（FileRead/FileWrite；主文案只显示 basename）。 */
    val filePath: String?,
) {
    /** 展示用工具名（行未携带时的兜底文案由 UI 资源层给）。 */
    val hasToolName: Boolean
        get() = toolName.isNotBlank()
}

object ToolDisplay {

    private const val MAX_PRIMARY = 220

    /**
     * 命令主文案：剥掉前导 `cd <目录> && ` 样板再截断。
     *
     * 为什么：桌面执行每条命令都带 `cd <工作区> && …` 前缀，不剥的话同一回合十来张卡
     * **长得一模一样**（真机实证：一个回合 7 张卡全是 `cd /Users/mac/projects/mtt_mobile &&…`），
     * 用户无法区分哪条是哪条。展开体仍显示完整命令（[ToolDisplayModel.command] 不改）。
     * 支持带引号路径（`cd "/a b" && …`）与前导多个 cd。
     */
    internal fun commandPrimary(raw: String): String {
        val trimmed = raw.trim()
        val stripped = LEADING_CD.replace(trimmed, "").trim()
        return (stripped.ifEmpty { trimmed }).take(MAX_PRIMARY)
    }

    /** `cd x &&` / `cd "x y" &&` 前导串（可重复） */
    private val LEADING_CD = Regex("""^(?:cd\s+(?:"[^"]*"|'[^']*'|\S+)\s*&&\s*)+\s*""")

    /** [ToolCall] → 展示模型（唯一入口；UI 禁自行解析 toolName/inputText）。 */
    fun build(call: ToolCall): ToolDisplayModel {
        val family = familyOf(call.toolName)
        val phase = phaseOf(call.status)
        val input = parseInput(call.target)
        val filePath = when (family) {
            ToolFamily.FileRead, ToolFamily.FileWrite -> extractFilePath(input)
            else -> null
        }
        val command = if (family == ToolFamily.Shell) extractCommand(input) else null
        val primary = when (family) {
            ToolFamily.Shell -> command?.let { commandPrimary(it) }
                ?: call.title?.takeIf { it.isNotBlank() }
                ?: call.toolName?.takeIf { it.isNotBlank() }
                ?: ""
            ToolFamily.FileRead, ToolFamily.FileWrite ->
                filePath?.let { fileBasename(it) }
                    ?: call.title?.takeIf { it.isNotBlank() }
                    ?: ""
            ToolFamily.Search -> extractQuery(input)?.let { it.take(MAX_PRIMARY) }
                ?: call.toolName?.takeIf { it.isNotBlank() }
                ?: ""
            ToolFamily.WebFetch -> extractUrl(input)?.take(MAX_PRIMARY)
                ?: call.title?.takeIf { it.isNotBlank() }
                ?: ""
            ToolFamily.Mcp -> mcpDisplayName(call.toolName)
                ?: call.title?.takeIf { it.isNotBlank() }
                ?: ""
            ToolFamily.Agent -> (input?.get("description")?.stringValue
                ?: input?.get("subagentType")?.stringValue
                ?: call.title?.takeIf { it.isNotBlank() }
                ?: "")
            ToolFamily.Todo -> call.title?.takeIf { it.isNotBlank() } ?: ""
            ToolFamily.Workflow -> call.title?.takeIf { it.isNotBlank() }
                ?: call.toolName?.takeIf { it.isNotBlank() } ?: ""
            ToolFamily.Generic -> call.title?.takeIf { it.isNotBlank() }
                ?: call.toolName?.takeIf { it.isNotBlank() }
                ?: ""
        }
        val errorText = if (phase == ToolPhase.Failed) {
            extractError(call.output) ?: call.output?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val output = if (phase == ToolPhase.Failed) null else call.output?.takeIf { it.isNotBlank() }
        return ToolDisplayModel(
            family = family,
            toolName = call.toolName.orEmpty(),
            phase = phase,
            primary = primary,
            secondary = call.description?.takeIf { it.isNotBlank() },
            parameters = formatParameters(input, excludeCommand = command != null),
            command = command,
            output = output,
            errorText = errorText,
            addedLines = call.addedLines,
            removedLines = call.removedLines,
            filePath = filePath,
        )
    }

    /** 工具名 → 族（大小写不敏感；未知归 Generic）。 */
    fun familyOf(toolName: String?): ToolFamily {
        val name = (toolName ?: "").lowercase()
        return when {
            name.startsWith("mcp__") || name.contains("mcp") -> ToolFamily.Mcp
            name.contains("bash") || name.contains("shell") || name.contains("terminal") ||
                name.contains("exec") -> ToolFamily.Shell
            // Todo 必须**先于** FileRead/FileWrite：`TodoList` 含 "list"、`TodoWrite` 含 "write"，
            // 放在后面会被它们吞掉（真机曾把 TodoList 显示成文件读取卡）。web toolIdentity
            // 的 todo 族优先级同样最高。
            name.contains("todo") -> ToolFamily.Todo
            name.contains("read") || name.contains("grep") || name.contains("glob") ||
                name.contains("list") || name.contains("ls") -> ToolFamily.FileRead
            name.contains("edit") || name.contains("write") || name.contains("patch") ||
                name.contains("apply") || name.contains("multiedit") -> ToolFamily.FileWrite
            name.contains("search") || name.contains("find") -> ToolFamily.Search
            name.contains("webfetch") || name.contains("fetch") || name.contains("browser") ||
                name.contains("navigate") -> ToolFamily.WebFetch
            name.contains("task") || name.contains("agent") || name.contains("subagent") ->
                ToolFamily.Agent
            name.contains("workflow") -> ToolFamily.Workflow
            else -> ToolFamily.Generic
        }
    }

    fun phaseOf(status: ToolCallStatus): ToolPhase = when (status) {
        ToolCallStatus.running -> ToolPhase.Running
        ToolCallStatus.done -> ToolPhase.Done
        ToolCallStatus.failed -> ToolPhase.Failed
    }

    /** inputText → JSON 对象（容错：坏 JSON / 非对象返回 null）。 */
    fun parseInput(raw: String?): Map<String, JSONValue>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{")) return null
        return runCatching { JSONValue.parse(text) }.getOrNull()?.objectValue
    }

    /** Bash 命令提取：command/cmd/script 字符串；argv 数组 join；`-lc` 解包（web execute.tsx:101-191 子集）。 */
    fun extractCommand(input: Map<String, JSONValue>?): String? {
        input ?: return null
        for (key in listOf("command", "cmd", "script", "parsed_cmd")) {
            val value = input[key] ?: continue
            value.stringValue?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val argv = value.arrayValue ?: continue
            val parts = argv.mapNotNull { it.stringValue?.takeIf { s -> s.isNotEmpty() } }
            if (parts.isEmpty()) continue
            // bash -lc "<cmd>"：取最后一个非选项参数（web 同口径 unwrap）
            val unwrapped = if (parts.firstOrNull()?.let { it.endsWith("bash") || it.endsWith("sh") || it.endsWith("zsh") } == true) {
                val last = parts.last()
                if (last != parts.first()) last else parts.joinToString(" ")
            } else {
                parts.joinToString(" ")
            }
            return unwrapped
        }
        return null
    }

    /** 文件路径提取（filePath/file_path/path/filename/target）。 */
    fun extractFilePath(input: Map<String, JSONValue>?): String? {
        input ?: return null
        for (key in listOf("filePath", "file_path", "path", "filename", "target", "file")) {
            input[key]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** 搜索词提取（query/pattern/q/glob）。 */
    fun extractQuery(input: Map<String, JSONValue>?): String? {
        input ?: return null
        for (key in listOf("query", "pattern", "q", "glob")) {
            input[key]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** URL 提取（url/uri/target）。 */
    fun extractUrl(input: Map<String, JSONValue>?): String? {
        input ?: return null
        for (key in listOf("url", "uri", "target", "href")) {
            input[key]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** MCP 工具名 → `server · tool`（`mcp__server__tool` 去前缀，web mcp.tsx:132-160 子集）。 */
    fun mcpDisplayName(toolName: String?): String? {
        val raw = toolName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.lowercase().startsWith("mcp__")) return raw
        val rest = raw.removePrefix("mcp__").removePrefix("MCP__")
        val parts = rest.split("__").filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> "${parts[0]} · ${parts.drop(1).joinToString("__")}"
            parts.size == 1 -> parts[0]
            else -> raw
        }
    }

    /** 参数块：命令已单独展示时剔除 command 键；原语键值 → `key = value` 行；其余 JSON。 */
    fun formatParameters(input: Map<String, JSONValue>?, excludeCommand: Boolean): String? {
        input ?: return null
        val excluded = buildSet {
            add("description")
            add("summary")
            if (excludeCommand) {
                add("command")
                add("cmd")
                add("script")
                add("parsed_cmd")
            }
        }
        val filtered = input.filterKeys { it !in excluded }.filterValues { v ->
            when (v) {
                is JSONValue.Obj, is JSONValue.Arr -> false
                else -> true
            }
        }
        if (filtered.isEmpty()) return null
        return filtered.entries.joinToString("\n") { (key, value) ->
            "$key = ${primitiveText(value)}"
        }
    }

    /**
     * 失败态错误提取：优先 `<tool_use_error>` 包裹段，其次 error/message 字段行，
     * 最后原样返回（web `lib/toolError.ts:54` 子集）。
     */
    fun extractError(output: String?): String? {
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return null
        val marker = Regex("""<tool_use_error>([\s\S]*?)</tool_use_error>""").find(text)
        if (marker != null) return marker.groupValues[1].trim().takeIf { it.isNotEmpty() }
        return null
    }

    private fun primitiveText(value: JSONValue): String =
        value.stringValue ?: value.boolValue?.toString()
        ?: value.intValue?.toString()
        ?: value.doubleValue?.toString()
        ?: value.toString()

    private fun fileBasename(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        if (trimmed.isEmpty()) return path
        val idx = trimmed.lastIndexOf('/')
        return if (idx >= 0 && idx < trimmed.length - 1) trimmed.substring(idx + 1) else trimmed
    }
}

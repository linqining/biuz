package cn.biuz.android.app.ui.chat

// 渲染层解析件（iOS MessageViews.swift :402-695 直译；渲染权威规则 = 协议文档 §10.1）：
// - userselect 尾部块剥离 + 引用条目（v1.34）——解析钉死渲染层：store 乐观回显去重按
//   原始文本匹配（rebuildMessages 尾部 serverUserTexts.contains），改写 text 会破坏去重
//   （§10.1 v1.34 移动端口径逐字）；复制/编辑重发恒用 message.text 原文。
// - ExitPlanMode 计划预览卡内容提取（v1.35）——载荷形状门：inputText 解析为 JSON 对象
//   且 `plan` 字段非空串才成卡【移植·上游仓 planToolCall.ts extractPlanToolCallContent +
//   形状门收敛】；官方族内 text/content 兜底不移植（防 bash 命令 / MCP {text:…} 误判）。
// - 超长文本 6000 字截断 + 展开收起（UserBubble :370-373 / MarkdownMessageBody :843-846）。
// 本文件全部为纯函数（零 Android/Compose 依赖）——JVM 单测直测（testDebugUnitTest）。

import cn.biuz.android.protocol.JsonParseException
import cn.biuz.android.protocol.JSONValue

/** 单条选区引用。围栏载荷合同仅 text（必有）+ 可选 path（文件选段才有）
 * 【实证·上游仓】conversationSelectionReference.ts:154（发送只序列化 text/path）；
 * 官方限额：单条 8k 字 / 最多 8 条 / 总计 16k 字（:3-5，移动端只读不重发不强制）。 */
data class UserSelectionReference(
    val id: String,
    val text: String,
    val path: String? = null,
)

/** userselect 解析结果：剥离尾块后的可见正文（空 = 纯引用消息，§10.1 合法形态）+ 引用条目。 */
data class UserSelectionParseResult(
    val visibleText: String,
    val selections: List<UserSelectionReference>,
)

/**
 * 用户消息尾部 userselect 块解析（iOS UserSelectionSupport :425-520 直译）。
 * 返回 null = 不是引用消息，按原样渲染；坏 JSON / 无有效条目 → null 回退原样渲染不崩不吞
 * （宽容解析 ≠ 协议事实——裸围栏变体属【宽容·未取证】防御桌面变体）。
 */
object UserSelectionSupport {

    /** 先官方后宽容：官方头必须先试，否则裸围栏分支会把「# userselect:」头行残留进可见正文。 */
    private val variants = listOf(
        // 官方：【实证·上游仓】conversationSelectionReference.ts:56
        Variant(header = "# userselect:", fence = "```userselect"),
        // 旧版拼写：【实证·上游仓】conversationSelectionReference.ts:57-58
        Variant(header = "# Conversation selections:", fence = "```zcode-conversation-selections"),
        // 裸围栏（无注释头）：【宽容·未取证】防御桌面变体，桌面现行走官方头形态
        Variant(header = null, fence = "```userselect"),
    )

    /** 桌面现行走官方头形态（宽容裸围栏仅作回退防变体）。 */
    private val official = variants[0]

    fun parse(raw: String): UserSelectionParseResult? {
        for (variant in variants) {
            match(raw, variant)?.let { return it }
        }
        return null
    }

    /** 卡片 label 规则词（官方三规则 ConversationSelectionReferenceChip.tsx:24-38）；
     * 中文/数字槽位由 UI 层 stringResource 组装，此处给结构化判别。 */
    sealed class CardLabel {
        /** 单条有 path → 「文件名 · 引用」 */
        data class SingleFile(val filename: String) : CardLabel()

        /** 多条含 path → 「n 条引用」 */
        data class CountReferences(val count: Int) : CardLabel()

        /** 纯对话 → 「n 条对话引用」 */
        data class CountConversationRefs(val count: Int) : CardLabel()
    }

    fun cardLabel(selections: List<UserSelectionReference>): CardLabel {
        val first = selections.first()
        if (selections.size == 1 && first.path != null) {
            return CardLabel.SingleFile(pathFilename(first.path!!))
        }
        return if (selections.any { it.path != null }) {
            CardLabel.CountReferences(selections.size)
        } else {
            CardLabel.CountConversationRefs(selections.size)
        }
    }

    /** 文件名取路径尾段（官方 path.split(/[\\/]/).pop()）。 */
    fun pathFilename(path: String): String =
        path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path

    /** 形态：注释头（可空）+ 围栏语言行。 */
    private data class Variant(val header: String?, val fence: String)

    /**
     * 尾部块正则（镜像上游 USER_SELECT_BLOCK_PATTERN，锚定 \z；载荷惰性匹配至「满足结尾
     * 锚的最早 \n```」——载荷含 ``` 字符时仍能对位真闭合围栏）。
     * (?:\n\n)?[#头\n]?```lang\n(载荷)\n```[空白]*\z —— 头行仅官方/legacy 形态携带。
     */
    private fun match(raw: String, variant: Variant): UserSelectionParseResult? {
        val headerPart = variant.header?.let { Regex.escape(it) + "\\n" } ?: ""
        val pattern = "(?:\\n\\n)?$headerPart${Regex.escape(variant.fence)}\\n([\\s\\S]*?)\\n```[ \\t\\r\\n]*\\z"
        val regex = Regex(pattern)
        val found = regex.find(raw) ?: return null
        val json = found.groupValues[1]
        val selections = decode(json) ?: return null
        // 可见正文 = 整个匹配之前的前缀（匹配已吞掉块前的空行分隔），尾部再收一次空白
        val visible = raw.substring(0, found.range.first).trim()
        return UserSelectionParseResult(visibleText = visible, selections = selections)
    }

    /** 宽容解码：忽略未知键（官方合同仅 text/path）；无有效条目 → null（回退原样渲染）。 */
    private fun decode(json: String): List<UserSelectionReference>? {
        val parsed = try {
            JSONValue.parse(json)
        } catch (_: JsonParseException) {
            return null
        }
        val array = parsed.arrayValue ?: return null
        val items = ArrayList<UserSelectionReference>()
        array.forEachIndexed { index, entry ->
            val obj = entry.objectValue ?: return@forEachIndexed
            val text = obj["text"]?.stringValue ?: return@forEachIndexed
            if (text.isEmpty()) return@forEachIndexed
            val path = obj["path"]?.stringValue?.takeIf { it.isNotEmpty() }
            items.add(UserSelectionReference(id = "userselect-$index", text = text, path = path))
        }
        return items.ifEmpty { null }
    }
}

/** ExitPlanMode 工具行提取出的计划内容（官方 planToolCall.ts PlanToolCallContent 同构）。 */
data class PlanToolCallContent(
    val markdown: String,
    val planFilePath: String? = null,
)

/**
 * ExitPlanMode（switch_mode 族）工具行 → 计划内容提取（iOS PlanToolCallSupport :633-695 直译）。
 * 官方按 tool identity family 分流（toolIdentity.ts ExitPlanMode → switch-mode →
 * SwitchModeToolCallBlock），提取链 input对象 → JSON.parse(inputText) → output → raw
 * 【实证·上游仓 planToolCall.ts extractPlanToolCallContent】。移动端 ToolCall 不带 toolName
 * （store 行映射只留 inputText 作 target），收敛为**载荷形状门**：target 解析为 JSON 对象
 * 且 `plan` 字段为非空串才成卡【移植·上游仓 + 形状门收敛，防 bash 命令 / MCP text 字段误判】；
 * planFilePath 可选。
 */
object PlanToolCallSupport {

    fun parse(target: String): PlanToolCallContent? {
        val trimmed = target.trim()
        if (!trimmed.startsWith("{")) return null
        val parsed = try {
            JSONValue.parse(trimmed)
        } catch (_: JsonParseException) {
            return null
        }
        val obj = parsed.objectValue ?: return null
        val markdown = obj["plan"]?.stringValue ?: return null
        if (markdown.isBlank()) return null
        val path = obj["planFilePath"]?.stringValue?.takeIf { it.isNotEmpty() }
        return PlanToolCallContent(markdown = markdown, planFilePath = path)
    }

    /**
     * 计划标题：首个 H1，否则首个非空行去行首装饰（#{1,6}/引用/列表符）
     * 【实证·上游仓 planToolCall.ts getPlanDirectoryTitle】。
     * JS /m 多行锚点 → Kotlin Regex 必须显式 MULTILINE（缺省时 ^$ 只匹配串首尾，
     * H1 永不命中、恒走首行回退——iOS 单测 testTitleFromFirstH1 实证同坑）。
     */
    fun planTitle(markdown: String): String? {
        val h1Regex = Regex("""^\s{0,3}#(?!#)\s+(.+?)\s*#*\s*$""", RegexOption.MULTILINE)
        val h1 = h1Regex.find(markdown)?.groupValues?.get(1)
        if (h1 != null) return h1
        for (line in markdown.split('\n', '\r')) {
            val title = line.trim()
            if (title.isEmpty()) continue
            return title.replace(Regex("""^(?:#{1,6}\s+|>\s*|[-*+]\s+)"""), "")
        }
        return null
    }

    /** 路径尾段展示名（官方 getPlanFileLabel = getPathLeaf）。 */
    fun planFileLabel(path: String): String =
        path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path
}

/**
 * 超长文本 6000 字截断口径（iOS UserBubble :370-373 / MarkdownMessageBody :843-846）：
 * > 6k 字先截断再进渲染——巨幅粘贴块 / tool 输出 / 日志块的解析与渲染是滚动卡顿源；
 * 「展开全部」按需全文。助手正文流式中不截断（光标语义优先，:849）。
 */
object LongTextDisplay {

    const val DISPLAY_LIMIT = 6_000

    fun shouldTruncate(text: String): Boolean = text.length > DISPLAY_LIMIT

    /** 折叠态展示文本（截断 + 省略号）；不超限时原文。 */
    fun collapsedText(text: String): String =
        if (text.length <= DISPLAY_LIMIT) text else text.take(DISPLAY_LIMIT) + "…"

    /** 流式正文（不截断）或展开正文。 */
    fun displayText(text: String, expanded: Boolean, streaming: Boolean = false): String =
        if (streaming || expanded || !shouldTruncate(text)) text else collapsedText(text)

    /** 「展开全部（N 字）」的 N（截断按钮 label 计数位）。 */
    fun totalCount(text: String): Int = text.length
}

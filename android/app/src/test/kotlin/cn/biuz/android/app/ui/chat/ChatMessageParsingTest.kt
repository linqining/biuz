package cn.biuz.android.app.ui.chat

// 渲染层解析件单测（协议文档 §10.1 v1.34/v1.35 固化形状）：
// - userselect 三形态（官方/legacy/裸围栏【宽容】）+ 坏 JSON 回退 + 纯引用空正文
//   + label 三规则（【实证·上游仓】ConversationSelectionReferenceChip.tsx:24-38）；
// - ExitPlanMode 载荷形状门（对象含非空 plan 字段才成卡；bash 命令/数组/裸串/
//   {text:…} 不触发——iOS PlanToolCallParsingTests 11 例同覆盖面）
//   + planTitle 首个 H1（MULTILINE 锚点——iOS testTitleFromFirstH1 回归坑）与首行回退；
// - 6000 字截断口径（流式不截断）。

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageParsingTest {

    // MARK: userselect 尾部块

    @Test
    fun userselectOfficialShapeStripsBlockAndParsesEntries() {
        val raw = "请看这段代码：\n\n" +
            "# userselect:\n" +
            "```userselect\n" +
            "[{\"text\":\"fun main() {}\",\"path\":\"/proj/src/Main.kt\"}]\n" +
            "```"
        val result = UserSelectionSupport.parse(raw)
        assertTrue(result != null)
        assertEquals("请看这段代码：", result!!.visibleText)
        assertEquals(1, result.selections.size)
        assertEquals("fun main() {}", result.selections[0].text)
        assertEquals("/proj/src/Main.kt", result.selections[0].path)
    }

    @Test
    fun userselectLegacyHeaderAndFenceAlsoParsed() {
        val raw = "正文\n\n" +
            "# Conversation selections:\n" +
            "```zcode-conversation-selections\n" +
            "[{\"text\":\"选中文本\"}]\n" +
            "```"
        val result = UserSelectionSupport.parse(raw)
        assertTrue(result != null)
        assertEquals("正文", result!!.visibleText)
        assertEquals(1, result.selections.size)
        assertNull(result.selections[0].path)
    }

    @Test
    fun userselectBareFenceTolerantVariantAlsoParsed() {
        // 【宽容·未取证】无头的裸围栏也剥离（防御桌面变体）
        val raw = "正文\n\n```userselect\n[{\"text\":\"A\"},{\"text\":\"B\",\"path\":\"x/y.md\"}]\n```"
        val result = UserSelectionSupport.parse(raw)
        assertTrue(result != null)
        assertEquals("正文", result!!.visibleText)
        assertEquals(2, result.selections.size)
    }

    @Test
    fun userselectEmptyVisibleTextIsValidPureReferenceMessage() {
        val raw = "# userselect:\n```userselect\n[{\"text\":\"只有引用\"}]\n```"
        val result = UserSelectionSupport.parse(raw)
        assertTrue(result != null)
        assertEquals("", result!!.visibleText) // 纯引用消息（可见正文可空）
        assertEquals(1, result.selections.size)
    }

    @Test
    fun userselectBadJsonOrNoValidEntriesFallsBackToNull() {
        // 坏 JSON → null（回退原样渲染不崩不吞）
        assertNull(
            UserSelectionSupport.parse(
                "# userselect:\n```userselect\n[{broken\n```",
            ),
        )
        // 非数组载荷 → null
        assertNull(
            UserSelectionSupport.parse(
                "# userselect:\n```userselect\n{\"text\":\"对象不是数组\"}\n```",
            ),
        )
        // 数组内无有效 text 条目 → null
        assertNull(
            UserSelectionSupport.parse(
                "# userselect:\n```userselect\n[{\"path\":\"只带路径没文本\"}]\n```",
            ),
        )
        // 普通消息 → null
        assertNull(UserSelectionSupport.parse("完全普通的用户消息"))
    }

    @Test
    fun userselectUnknownKeysIgnoredPerContract() {
        // 官方合同仅 text/path；内部身份字段（id/sourceRowId/contentType）宽容忽略
        val raw = "```userselect\n" +
            "[{\"text\":\"T\",\"id\":\"sel-1\",\"sourceRowId\":9,\"contentType\":\"code\"}]\n" +
            "```"
        val result = UserSelectionSupport.parse(raw)
        assertTrue(result != null)
        assertEquals(1, result!!.selections.size)
        assertEquals("T", result.selections[0].text)
    }

    @Test
    fun userselectCardLabelThreeRules() {
        val singleFile = listOf(UserSelectionReference("0", "内容", path = "/a/b/View.swift"))
        assertTrue(
            UserSelectionSupport.cardLabel(singleFile) is
                UserSelectionSupport.CardLabel.SingleFile,
        )
        assertEquals(
            "View.swift",
            (UserSelectionSupport.cardLabel(singleFile) as UserSelectionSupport.CardLabel.SingleFile)
                .filename,
        )
        val mixed = listOf(
            UserSelectionReference("0", "A", path = "a.md"),
            UserSelectionReference("1", "B"),
        )
        assertEquals(
            2,
            (UserSelectionSupport.cardLabel(mixed) as UserSelectionSupport.CardLabel.CountReferences)
                .count,
        )
        val dialogOnly = listOf(
            UserSelectionReference("0", "A"),
            UserSelectionReference("1", "B"),
            UserSelectionReference("2", "C"),
        )
        assertEquals(
            3,
            (UserSelectionSupport.cardLabel(dialogOnly) as
                UserSelectionSupport.CardLabel.CountConversationRefs).count,
        )
        // pathFilename 取尾段（官方 path.split(/[\\/]/).pop()）
        assertEquals("View.swift", UserSelectionSupport.pathFilename("/a/b/View.swift"))
        assertEquals("View.swift", UserSelectionSupport.pathFilename("C:\\a\\View.swift"))
    }

    // MARK: ExitPlanMode 计划卡载荷形状门（v1.35）

    @Test
    fun planShapeGateAcceptsObjectWithNonEmptyPlanField() {
        val target = "{\"plan\":\"# 重构计划\\n\\n1. 先写测试\",\"planFilePath\":\"/tmp/plan.md\"}"
        val content = PlanToolCallSupport.parse(target)
        assertTrue(content != null)
        assertEquals("# 重构计划\n\n1. 先写测试", content!!.markdown)
        assertEquals("/tmp/plan.md", content.planFilePath)
    }

    @Test
    fun planShapeGateRejectsNonPlanPayloads() {
        // bash 命令（裸串，非 JSON）
        assertNull(PlanToolCallSupport.parse("git status && ./gradlew test"))
        // JSON 数组（非对象）
        assertNull(PlanToolCallSupport.parse("[\"数组不是计划\"]"))
        // MCP {text:…} 载荷（无 plan 字段——官方族内 text/content 兜底不移植）
        assertNull(PlanToolCallSupport.parse("{\"text\":\"一段文字\"}"))
        // plan 为空串
        assertNull(PlanToolCallSupport.parse("{\"plan\":\"   \"}"))
        // plan 非字符串
        assertNull(PlanToolCallSupport.parse("{\"plan\":123}"))
        // 非 JSON 的花括号噪声
        assertNull(PlanToolCallSupport.parse("{ 不是 JSON }"))
        // planFilePath 空串归一为 null
        val content = PlanToolCallSupport.parse("{\"plan\":\"正文\",\"planFilePath\":\"\"}")
        assertTrue(content != null)
        assertNull(content!!.planFilePath)
    }

    @Test
    fun planTitlePrefersFirstH1AcrossLines() {
        // JS /m 多行锚点 → Kotlin MULTILINE 必须显式开（iOS testTitleFromFirstH1 回归坑：
        // 缺省时 ^$ 只匹配串首尾，H1 永不命中恒走首行回退）
        val markdown = "Some intro text\n# 真正的标题\n## 二级\n正文"
        assertEquals("真正的标题", PlanToolCallSupport.planTitle(markdown))
    }

    @Test
    fun planTitleFallsBackToFirstNonEmptyLineStrippedOfDecorations() {
        assertEquals("无标题计划的正文", PlanToolCallSupport.planTitle("无标题计划的正文\n第二行"))
        // 行首装饰剥离：#{1,6} / 引用 / 列表符
        assertEquals("列表项标题", PlanToolCallSupport.planTitle("- 列表项标题"))
        assertEquals("引用标题", PlanToolCallSupport.planTitle("> 引用标题"))
        // 纯空白 → null
        assertNull(PlanToolCallSupport.planTitle("   \n  "))
    }

    @Test
    fun planFileLabelTakesPathLeaf() {
        assertEquals("plan.md", PlanToolCallSupport.planFileLabel("/tmp/zcode/plans/plan.md"))
        assertEquals("plan.md", PlanToolCallSupport.planFileLabel("C:\\tmp\\plan.md"))
    }

    // MARK: 6000 字截断（UserBubble :370 / MarkdownMessageBody :843 同口径）

    @Test
    fun longTextDisplayTruncatesOverSixThousandChars() {
        val short = "a".repeat(6_000)
        assertFalse(LongTextDisplay.shouldTruncate(short))
        assertEquals(short, LongTextDisplay.collapsedText(short))

        val long = "b".repeat(6_500)
        assertTrue(LongTextDisplay.shouldTruncate(long))
        val collapsed = LongTextDisplay.collapsedText(long)
        assertEquals(6_001, collapsed.length) // 6000 字 + 省略号
        assertTrue(collapsed.endsWith("…"))

        // 流式不截断（光标语义优先）
        assertEquals(long, LongTextDisplay.displayText(long, expanded = false, streaming = true))
        // 展开给全文
        assertEquals(long, LongTextDisplay.displayText(long, expanded = true))
        assertEquals(collapsed, LongTextDisplay.displayText(long, expanded = false))
    }
}

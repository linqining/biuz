package cn.biuz.android.app.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Markdown 块级解析（对位 iOS TinyMarkdown.swift 的解析口径） */
class MarkdownParserTest {

    @Test
    fun headingsLevelsAndParagraphs() {
        val blocks = MarkdownParser.parse("# 一级\n## 二级\n### 三级\n正文一行")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "一级"),
                MdBlock.Heading(2, "二级"),
                MdBlock.Heading(3, "三级"),
                MdBlock.Paragraph("正文一行"),
            ),
            blocks,
        )
    }

    @Test
    fun fencedCodeKeepsLanguageAndContent() {
        val blocks = MarkdownParser.parse("说明\n```kotlin\nval a = 1\nval b = 2\n```\n尾行")
        assertEquals(3, blocks.size)
        val code = blocks[1] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\nval b = 2", code.code)
        assertEquals(MdBlock.Paragraph("尾行"), blocks[2])
    }

    @Test
    fun unclosedFenceSwallowsRestAsCode() {
        val blocks = MarkdownParser.parse("```python\nprint(1)\nprint(2)")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("python", code.language)
        assertEquals("print(1)\nprint(2)", code.code)
    }

    @Test
    fun tableWithHeaderSeparatorAndRows() {
        val blocks = MarkdownParser.parse("| 名称 | 值 |\n|---|:--:|\n| a | 1 |\n| b | 2 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("名称", "值"), table.header)
        assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), table.rows)
    }

    @Test
    fun tableWithoutSeparatorDegradesToParagraphs() {
        val blocks = MarkdownParser.parse("| a | b |")
        assertEquals(listOf(MdBlock.Paragraph("| a | b |")), blocks)
    }

    @Test
    fun listsBulletsAndBlankLines() {
        val blocks = MarkdownParser.parse("- 一\n* 二\n\n- 三")
        assertEquals(
            listOf(MdBlock.ListItem("一"), MdBlock.ListItem("二"), MdBlock.ListItem("三")),
            blocks,
        )
    }

    @Test
    fun emptyAndWhitespaceOnlyYieldNoBlocks() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("\n\n   \n").isEmpty())
    }
}

/** 行内标记（**粗体** / `行内代码`）—— iOS `inline(_:)` 同口径扫描 */
class InlineMarkdownTest {

    @Test
    fun boldSpanApplied() {
        val annotated = inlineAnnotated("前 **粗体** 后")
        assertEquals("前 粗体 后", annotated.text)
        val bold = annotated.spanStyles.filter { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }
        assertEquals(1, bold.size)
        assertEquals("粗体", annotated.text.substring(bold[0].start, bold[0].end))
    }

    @Test
    fun inlineCodeSpanApplied() {
        val annotated = inlineAnnotated("跑 `pnpm test` 看看")
        assertEquals("跑 pnpm test 看看", annotated.text)
        val mono = annotated.spanStyles.filter { it.item.fontFamily != null }
        assertEquals(1, mono.size)
        assertEquals("pnpm test", annotated.text.substring(mono[0].start, mono[0].end))
    }

    @Test
    fun unclosedMarkersKeptLiteral() {
        assertEquals("2 * 3 = 6", inlineAnnotated("2 * 3 = 6").text)
        assertEquals("反引号 `未闭合", inlineAnnotated("反引号 `未闭合").text)
        assertTrue(inlineAnnotated("2 * 3 = 6").spanStyles.isEmpty())
    }

    @Test
    fun emptyPairNotTreatedAsMarkup() {
        assertEquals("a**b", inlineAnnotated("a**b").text)
    }
}

/** 中文斜体回归（真机实测星号外露） */
class InlineItalicRegressionTest {
    @Test
    fun chineseItalicStripsStars() {
        val annotated = inlineAnnotated("提醒一下：*工作流刚经历一次供应商网络抖动*，不需要你做任何操作。")
        assertEquals("提醒一下：工作流刚经历一次供应商网络抖动，不需要你做任何操作。", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
    }

    @Test
    fun boldInSameLine() {
        val annotated = inlineAnnotated("前 **加粗** 后 *斜体* 尾")
        assertEquals("前 加粗 后 斜体 尾", annotated.text)
        assertEquals(2, annotated.spanStyles.size)
    }
}

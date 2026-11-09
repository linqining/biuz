package cn.biuz.android.app.ui.chat

/**
 * 轻量 Markdown 解析（块级）—— iOS `Components/TinyMarkdown.swift:1-90` 的 Kotlin 移植，
 * 块集合同口径：标题(1-3) / 段落 / 列表项 / 代码块 / 管道表格。
 * 行内标记（**粗体** / `行内代码`）由渲染层处理，不在此层。
 *
 * 容错：流式输出时围栏可能未闭合 —— 未闭合的 ``` 之后全部按代码块处理（不丢内容）；
 * 表格不足两行（缺分隔行）逐行降级为段落。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

object MarkdownParser {

    fun parse(text: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val lines = text.split("\n")
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            if (line.startsWith("```")) {
                val language = line.removePrefix("```").trim().ifEmpty { null }
                index += 1
                val code = ArrayList<String>()
                while (index < lines.size && !lines[index].startsWith("```")) {
                    code.add(lines[index])
                    index += 1
                }
                if (index < lines.size) index += 1 // 跳过闭合围栏；未闭合则吃到末尾
                blocks.add(MdBlock.Code(language, code.joinToString("\n")))
                continue
            }

            if (line.trim().startsWith("|")) {
                val tableLines = ArrayList<String>()
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    tableLines.add(lines[index])
                    index += 1
                }
                if (tableLines.size < 2) {
                    tableLines.forEach { blocks.add(MdBlock.Paragraph(it)) }
                    continue
                }
                val header = cellsOf(tableLines[0])
                val rows = ArrayList<List<String>>()
                tableLines.drop(1).forEach { rowLine ->
                    if (SEPARATOR.matches(rowLine.trim())) return@forEach
                    rows.add(cellsOf(rowLine))
                }
                if (header.isNotEmpty()) blocks.add(MdBlock.Table(header, rows))
                continue
            }

            when {
                line.startsWith("### ") -> blocks.add(MdBlock.Heading(3, line.removePrefix("### ")))
                line.startsWith("## ") -> blocks.add(MdBlock.Heading(2, line.removePrefix("## ")))
                line.startsWith("# ") -> blocks.add(MdBlock.Heading(1, line.removePrefix("# ")))
                line.startsWith("- ") || line.startsWith("* ") ->
                    blocks.add(MdBlock.ListItem(line.drop(2)))
                line.isBlank() -> Unit
                else -> blocks.add(MdBlock.Paragraph(line))
            }
            index += 1
        }
        return blocks
    }

    /** 表格分隔行 `|---|:--:|`。 */
    private val SEPARATOR = Regex("^\\|[\\s:|-]+\\|\\s*$")

    private fun cellsOf(row: String): List<String> {
        val parts = row.split("|").map { it.trim() }.toMutableList()
        if (parts.firstOrNull()?.isEmpty() == true) parts.removeAt(0)
        if (parts.lastOrNull()?.isEmpty() == true) parts.removeAt(parts.size - 1)
        return parts
    }
}

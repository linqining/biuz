package cn.biuz.android.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgCode
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * Markdown 渲染（块级 + 行内 `**粗体**` / `` `行内代码` ``）——
 * iOS `Components/TinyMarkdown.swift` 的 Compose 同构：标题/段落/列表/代码块/管道表格。
 * 代码块与表格样式对齐 iOS `CodeBlockView` / `MarkdownTableView`（等宽 + 表头底色 +
 * 横向滚动，窄屏不挤压列）。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    codeFontSize: Float = 12f,
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineAnnotated(block.text),
                    style = TextStyle(
                        fontSize = (if (block.level == 1) 18f else if (block.level == 2) 16f else 14.5f).sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (fontSize + 8f).sp,
                    ),
                    color = BiuZText,
                )

                is MdBlock.Paragraph -> Text(
                    text = inlineAnnotated(block.text),
                    style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize + 8f).sp),
                    color = BiuZText,
                )

                is MdBlock.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = BiuZSpacing.sm)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(BiuZText3),
                    )
                    Text(
                        text = inlineAnnotated(block.text),
                        style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize + 8f).sp),
                        color = BiuZText,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Code -> CodeBlock(block, codeFontSize)

                is MdBlock.Table -> MarkdownTable(block, codeFontSize)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, codeFontSize: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCode)
            .border(1.dp, BiuZBorder, BiuZRadius.medium),
    ) {
        block.language?.let { language ->
            Text(
                text = language,
                modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
                style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                color = BiuZText3,
            )
        }
        val scroll = rememberScrollState()
        Text(
            text = block.code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
            style = TextStyle(
                fontSize = codeFontSize.sp,
                lineHeight = (codeFontSize + 6f).sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = BiuZText2,
            softWrap = false,
        )
    }
}

/** 管道表格：表头强调底色 + 行分隔线 + 单列滚动容器（列对齐与 iOS MarkdownTableView 同构）。 */
@Composable
private fun MarkdownTable(block: MdBlock.Table, cellFontSize: Float) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium),
    ) {
        Column(modifier = Modifier.horizontalScroll(scroll)) {
            Row(
                modifier = Modifier
                    .background(BiuZBgCode)
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.tiny),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                block.header.forEach { cell ->
                    Text(
                        text = inlineAnnotated(cell),
                        style = TextStyle(
                            fontSize = cellFontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = BiuZText,
                    )
                }
            }
            block.rows.forEach { row ->
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BiuZBorder))
                Row(
                    modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.tiny),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    row.forEach { cell ->
                        Text(
                            text = inlineAnnotated(cell),
                            style = TextStyle(fontSize = cellFontSize.sp, fontFamily = FontFamily.Monospace),
                            color = BiuZText2,
                        )
                    }
                }
            }
        }
    }
}

private val CODE_BG = Color(0x14FFFFFF)

/**
 * 行内标记 → AnnotatedString：`**粗体**` 与 `` `行内代码` ``（iOS `inline(_:)` 同口径扫描）。
 * 未闭合标记按字面量保留（流式半截文本不吞字符）。
 */
fun inlineAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty()) {
            append(buffer.toString())
            buffer.clear()
        }
    }
    while (index < text.length) {
        when {
            // **粗体**（先匹配双星，避免被单星规则拆开）
            text.startsWith("**", index) -> {
                val close = text.indexOf("**", index + 2)
                if (close < 0 || close == index + 2) {
                    buffer.append("*")
                    index += 1
                } else {
                    flush()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(index + 2, close))
                    pop()
                    index = close + 2
                }
            }
            // *斜体*
            text[index] == '*' -> {
                val close = text.indexOf('*', index + 1)
                if (close < 0 || close == index + 1) {
                    buffer.append('*')
                    index += 1
                } else {
                    flush()
                    pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    append(text.substring(index + 1, close))
                    pop()
                    index = close + 1
                }
            }
            // `行内代码`
            text[index] == '`' -> {
                val close = text.indexOf('`', index + 1)
                if (close < 0) {
                    buffer.append('`')
                    index += 1
                } else {
                    flush()
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG))
                    append(text.substring(index + 1, close))
                    pop()
                    index = close + 1
                }
            }
            else -> {
                buffer.append(text[index])
                index += 1
            }
        }
    }
    flush()
}

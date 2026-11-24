package cn.biuz.android.app.ui.whiteboard

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 屏 036 · 白板浮层（会话页内全屏覆盖层，web 侧栏面板的移动对位；
 * 语义与常量见 [WhiteboardModel] 头注——零 RPC 纯客户端，1280×800、
 * 六色板、笔宽 2–18、eraser=画背景色、addStroke 清 redo 栈）。
 *
 * 「添加到聊天」：web = PNG dataURL + 自定义事件插入 composer（WhiteboardPane.tsx:205-216）；
 * 原生对位 = 导出 PNG 字节经 [onAddToChat] 直插会话 composer 附件管道
 * （Android `ChatViewModel.enqueueAttachment` / iOS `uploads.add`），完全同效。
 */
@Composable
fun WhiteboardPanel(
    workspaceKey: String,
    onAddToChat: (ByteArray) -> Boolean,
    onClose: () -> Unit,
) {
    var board by remember { mutableStateOf(WhiteboardBoardStore.boardFor(workspaceKey)) }
    fun apply(mutation: (WhiteboardDocument) -> WhiteboardDocument) {
        board = WhiteboardBoardStore.mutate(workspaceKey, mutation)
    }
    var tool by remember { mutableStateOf(WhiteboardTool.pen) }
    var color by remember { mutableStateOf(WhiteboardDocument.DEFAULT_COLOR) }
    var strokeWidth by remember { mutableStateOf(4f) }
    var draft by remember { mutableStateOf<WhiteboardStroke?>(null) }
    var exportNotice by remember { mutableStateOf<String?>(null) }
    var exportIsError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BiuZBg)
            .statusBarsPadding()
            .testTag("36-whiteboard-panel"),
    ) {
        // 头部：关闭 + 名称即改（web Input onBlur 即改同口径；空串忽略）
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = BiuZSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onClose() }
                    .padding(horizontal = BiuZSpacing.md),
            )
            androidx.compose.material3.OutlinedTextField(
                value = board.name,
                onValueChange = { newName -> apply { WhiteboardMutations.rename(it, newName) } },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BiuZText,
                ),
                modifier = Modifier.weight(1f).testTag("36-name-field"),
            )
            val addedText = stringResource(R.string.whiteboard_added_to_chat)
            val addFailedText = stringResource(R.string.whiteboard_add_failed)
            Text(
                text = stringResource(R.string.whiteboard_add_to_chat),
                fontSize = 12.sp,
                color = BiuZAccent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable {
                        val bytes = exportPng(board)
                        val ok = onAddToChat(bytes)
                        exportNotice = if (ok) addedText else addFailedText
                        exportIsError = !ok
                    }
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                    .testTag("36-act-add-to-chat"),
            )
        }
        androidx.compose.material3.HorizontalDivider(color = BiuZBorder)

        exportNotice?.let { notice ->
            BiuzInlineNotice(
                message = notice,
                kind = if (exportIsError) {
                    cn.biuz.android.app.ui.components.BiuzNoticeKind.Error
                } else {
                    cn.biuz.android.app.ui.components.BiuzNoticeKind.Info
                },
                modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
                tag = "36-export-notice",
            )
        }

        // 画布（1280×800 文档空间按宽适配；笔迹在文档坐标存储，渲染时 ×scale）
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(BiuZSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        WhiteboardDocument.CANVAS_WIDTH.toFloat() / WhiteboardDocument.CANVAS_HEIGHT.toFloat(),
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, BiuZBorder, RoundedCornerShape(8.dp))
                    .pointerInput(tool, color, strokeWidth) {
                        val scale = size.width / WhiteboardDocument.CANVAS_WIDTH.toFloat()
                        detectDragGestures(
                            onDragStart = { pos ->
                                draft = WhiteboardStroke(
                                    tool = tool,
                                    color = color,
                                    width = strokeWidth.toDouble(),
                                    points = listOf(WhiteboardPoint((pos.x / scale).toDouble(), (pos.y / scale).toDouble())),
                                )
                            },
                            onDrag = { change, _ ->
                                val current = draft ?: return@detectDragGestures
                                draft = current.copy(
                                    points = current.points + WhiteboardPoint(
                                        (change.position.x / scale).toDouble(),
                                        (change.position.y / scale).toDouble(),
                                    ),
                                )
                            },
                            onDragEnd = {
                                // addStroke = 追加 + 清空 redo 栈（web 同口径）
                                draft?.let { stroke -> apply { WhiteboardMutations.addStroke(it, stroke) } }
                                draft = null
                            },
                            onDragCancel = { draft = null },
                        )
                    },
            ) {
                val scale = size.width / WhiteboardDocument.CANVAS_WIDTH.toFloat()
                val bgColor = Color(android.graphics.Color.parseColor(WhiteboardDocument.BACKGROUND_COLOR))
                drawRect(color = bgColor)
                for (stroke in board.strokes) drawWhiteboardStroke(stroke, scale, bgColor)
                draft?.let { drawWhiteboardStroke(it, scale, bgColor) }
            }
        }

        // 工具行：pen/eraser + 六色板 + 笔宽（2–18 滑条）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = BiuZSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            ToolButton(
                glyph = "✏️",
                selected = tool == WhiteboardTool.pen,
                tag = "36-tool-pen",
            ) { tool = WhiteboardTool.pen }
            ToolButton(
                glyph = "🧽",
                selected = tool == WhiteboardTool.eraser,
                tag = "36-tool-eraser",
            ) { tool = WhiteboardTool.eraser }
            Spacer0()
            WhiteboardDocument.SWATCHES.forEach { swatch ->
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(android.graphics.Color.parseColor(swatch)))
                        .border(
                            if (color == swatch) 2.dp else 1.dp,
                            if (color == swatch) BiuZAccent else BiuZBorder,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { color = swatch }
                        .testTag("36-swatch-$swatch"),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = BiuZSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = strokeWidth,
                onValueChange = { strokeWidth = it },
                valueRange = WhiteboardDocument.MIN_STROKE_WIDTH.toFloat()..
                    WhiteboardDocument.MAX_STROKE_WIDTH.toFloat(),
                // 笔宽滑杆是**中性控件**，不是主操作：只锁 thumb 的话，active track 会落到
                // Material 默认 primary（= 朱磦），真机读作「一条红色告警条 + 右端红点」。
                // 一律取墨（中性强调），与「朱磦只盖在决议/主 CTA 上」一致。
                colors = SliderDefaults.colors(
                    thumbColor = BiuZAccent,
                    activeTrackColor = BiuZAccent,
                ),
                modifier = Modifier.weight(1f).testTag("36-width-slider"),
            )
            Text(
                text = strokeWidth.toInt().toString(),
                fontSize = 11.sp,
                color = BiuZText3,
                modifier = Modifier.padding(start = BiuZSpacing.sm).testTag("36-width-value"),
            )
        }

        // 动作行：撤销/重做/清空（web 同语义：addStroke 清 redo 栈）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
        ) {
            ActionText("↺", board.canUndo, "36-act-undo") { apply { WhiteboardMutations.undo(it) } }
            ActionText("↻", board.canRedo, "36-act-redo") { apply { WhiteboardMutations.redo(it) } }
            ActionText(stringResource(R.string.whiteboard_clear), true, "36-act-clear") { apply { WhiteboardMutations.clear(it) } }
        }
    }
}

@Composable
private fun ToolButton(glyph: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        text = glyph,
        fontSize = 16.sp,
        modifier = Modifier
            .clip(BiuZRadius.small)
            .background(if (selected) BiuZBgCard else Color.Transparent)
            .border(1.dp, if (selected) BiuZAccent else BiuZBorder, BiuZRadius.small)
            .clickable { onClick() }
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.tiny)
            .testTag(tag),
    )
}

@Composable
private fun ActionText(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) BiuZText else BiuZText3,
        modifier = Modifier
            .clip(BiuZRadius.small)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.tiny)
            .testTag(tag),
    )
}

@Composable
private fun Spacer0() = Box(modifier = Modifier.size(2.dp))

/** DrawScope 渲染（drawWhiteboardStroke lib/whiteboard.ts:126-152 直译：eraser=背景色、
 *  round cap/join、单点=实心圆 r=width/2；线宽/坐标 ×scale 落画布像素）。 */
private fun DrawScope.drawWhiteboardStroke(stroke: WhiteboardStroke, scale: Float, backgroundColor: Color) {
    val first = stroke.points.firstOrNull() ?: return
    val paintColor = if (stroke.tool == WhiteboardTool.eraser) backgroundColor
    else Color(android.graphics.Color.parseColor(stroke.color))
    val lineWidth = (stroke.width * scale).toFloat()
    if (stroke.points.size == 1) {
        drawCircle(
            color = paintColor,
            radius = lineWidth / 2f,
            center = Offset(first.x.toFloat() * scale, first.y.toFloat() * scale),
        )
        return
    }
    val path = Path()
    path.moveTo(first.x.toFloat() * scale, first.y.toFloat() * scale)
    for (point in stroke.points.drop(1)) {
        path.lineTo(point.x.toFloat() * scale, point.y.toFloat() * scale)
    }
    drawPath(path, color = paintColor, style = Stroke(width = lineWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** PNG 导出（web exportWhiteboardDataUrl 同构：1280×800 白底 + 全量笔画重绘）。 */
fun exportPng(board: WhiteboardDocument): ByteArray {
    val bitmap = Bitmap.createBitmap(board.width, board.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val bgPaint = Paint().apply { color = android.graphics.Color.WHITE }
    canvas.drawRect(0f, 0f, board.width.toFloat(), board.height.toFloat(), bgPaint)
    val paint = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in board.strokes) {
        val first = stroke.points.firstOrNull() ?: continue
        paint.color = if (stroke.tool == WhiteboardTool.eraser) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.parseColor(stroke.color)
        }
        paint.strokeWidth = stroke.width.toFloat()
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x.toFloat(), first.y.toFloat(), stroke.width.toFloat() / 2f, paint)
            continue
        }
        paint.style = Paint.Style.STROKE
        val path = android.graphics.Path()
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        for (point in stroke.points.drop(1)) {
            path.lineTo(point.x.toFloat(), point.y.toFloat())
        }
        canvas.drawPath(path, paint)
    }
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return out.toByteArray()
}

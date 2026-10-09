package cn.biuz.android.app.ui.chat

// 消息行渲染（iOS MessageViews.swift 直译；行模型权威 = 协议文档 §10.1）：
// - 用户气泡（6000 字截断+展开收起）+ userselect 引用卡（解析钉死渲染层，复制/编辑重发用原文）；
// - 助手正文（6000 字截断；流式不截断+光标）+ 思考折叠块 + 工具卡（ExitPlanMode 计划卡整卡替换）；
// - 交互卡四分支（permission / plan_approval / userInput 提问 / workspaceHookReview）——
//   宿主统一放进「常驻 composer 上方 · 限高 240dp + 内部滚动」容器（ChatScreen），三卡统一口径。
// 应答形状（A-3）：answer 恒为对象按交互族分形——权限 {optionId} / 计划 {action} / 提问
// {freeText}|{optionId}；决议失败如实上屏不撤卡（§3.2-3.4 状态矩阵）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZAccentDim
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgCode
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.MessageStatus
import cn.biuz.android.protocol.RemoteInteractionOption
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.protocol.ThinkingState
import cn.biuz.android.protocol.TodoState
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.protocol.ToolCallStatus
import cn.biuz.android.protocol.ToolKind
import cn.biuz.android.store.ConversationStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 权限族 optionId 词表（web 实证四族 allowOnce/allowAlways/rejectOnce/rejectAlways，降级设计 §3.2）。 */
private val FOUR_FAMILY_IDS = setOf("allowOnce", "allowAlways", "rejectOnce", "rejectAlways")

// MARK: - 消息行路由（iOS MessageView :44-133 同构）

/**
 * 附件渲染上下文（消息行附件条数据源；null = 不挂附件条——演示态/未接线调用方）。
 * G-014：refs 经 attachmentReadV4 分块读（store 面），缩略图/全屏由 AttachmentViews 承载。
 */
data class AttachmentStripContext(
    val store: ConversationStore,
    val sessionId: String,
    val fullscreen: AttachmentFullscreenState,
)

@Composable
fun MessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    /** G-015 失败工具卡重试：携行合成消息（rowId 游标来源 = message.id "row-<n>"）+ 工具卡。 */
    onRetryToolCall: (ChatMessage, ToolCall) -> Unit = { _, _ -> },
    /** P1-3 编辑重发下发（editUserQuery；返回失败面，null = 成功）。null 回调 = 长按菜单无该项。 */
    onEditResend: (suspend (ChatMessage, String) -> ChatViewModel.InteractionFailure?)? = null,
    /** 附件条上下文（null = 不渲染附件位；refs 空的行不渲染占位死块）。 */
    attachmentContext: AttachmentStripContext? = null,
) {
    when (message.role) {
        MessageRole.user -> UserMessageRow(message, modifier, onEditResend, attachmentContext)
        MessageRole.agent -> AgentMessageRow(message, modifier, onRetryToolCall, attachmentContext)
    }
}

// MARK: - 用户行：附件位（气泡上方右对齐）+ userselect 剥离 + 气泡

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onEditResend: (suspend (ChatMessage, String) -> ChatViewModel.InteractionFailure?)?,
    attachmentContext: AttachmentStripContext?,
) {
    var copied by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showEditResend by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copiedLabel = stringResource(R.string.chat_msg_copied)
    // 编辑重发门槛（retryTurn「游标缺失不渲染入口」同纪律）：回调接线（连接态）
    // + 行合成消息（rowId 可解析）+ 行元数据游标在场（iOS canEditResend 同口径）
    val canEditResend = onEditResend != null &&
        message.rowKind == "userInput" &&
        message.entityId != null &&
        message.id.startsWith("row-")
    // userselect 尾部块渲染层剥离 + 引用卡（官方 web 同口径：正文渲染前剥块、气泡外呈现
    // 引用胶囊）。解析必须在渲染层——store 乐观回显去重按原始文本匹配，改写 text 破坏去重
    // （协议文档 §10.1 v1.34 逐字）；「复制」恒复制 message.text 原文（含尾块）。
    val selection = remember(message.text) { UserSelectionSupport.parse(message.text) }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // 附件位：气泡上方、右对齐（iOS 桌面口径 MessageViews.swift:51-55 同构；
        // 无 refs 不渲染占位死块）
        if (attachmentContext != null && message.attachments.isNotEmpty()) {
            AttachmentStripView(
                store = attachmentContext.store,
                sessionId = attachmentContext.sessionId,
                refs = message.attachments,
                fullscreen = attachmentContext.fullscreen,
                rowAlignment = Alignment.End,
            )
        }
        val displayText = selection?.visibleText ?: message.text
        if (displayText.isNotEmpty()) {
            Box {
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(BiuZRadius.large)
                        .background(BiuZAccentDim)
                        .border(1.dp, BiuZBorder, BiuZRadius.large)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        )
                        .padding(horizontal = BiuZSpacing.md, vertical = 10.dp),
                ) {
                    CollapsibleBodyText(text = displayText, streaming = false)
                    if (copied) {
                        Text(
                            text = copiedLabel,
                            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                            color = BiuZAccent,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(start = BiuZSpacing.xs),
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canEditResend) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_edit_resend_title)) },
                            onClick = {
                                menuOpen = false
                                showEditResend = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_msg_copy)) },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(message.text)) // 原文（§10.1 v1.34）
                            copied = true
                        },
                    )
                }
            }
        }
        if (selection != null) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            UserSelectionCard(selections = selection.selections)
        }
        LaunchedEffect(copied) {
            if (copied) {
                delay(2_000)
                copied = false
            }
        }
    }
    // 编辑重发 sheet（呈现期捕获 message.text 原文——§5-15；预填全文不截断）
    if (showEditResend && onEditResend != null) {
        EditResendSheet(
            originalText = message.text,
            onResend = { newText -> onEditResend(message, newText) },
            onClose = { showEditResend = false },
        )
    }
}

/**
 * 编辑重发 sheet（iOS EditResendSheet :226-364 直译）：预填原文全文 + rewind 橙色说明行 +
 * destructive 确认弹层双重确认（rewind 不可撤销）；失败 sheet 内错误行不 dismiss 可改后重试；
 * 成功关闭（关闭由 handler 单路径置否——§5-15）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditResendSheet(
    originalText: String,
    onResend: suspend (String) -> ChatViewModel.InteractionFailure?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(originalText) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ChatViewModel.InteractionFailure?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!sending) onClose() },
        containerColor = cn.biuz.android.app.ui.theme.BiuZBgElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg)
                .padding(bottom = BiuZSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_edit_resend_title),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = BiuZText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.action_close),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = BiuZText2,
                    modifier = Modifier
                        .combinedClickableSafe(enabled = !sending) { onClose() }
                        .padding(BiuZSpacing.sm),
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 14.5.sp, color = BiuZText, lineHeight = 20.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp, max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                    .padding(BiuZSpacing.md),
            )
            // rewind 语义说明行（不可撤销 → 与确认弹层构成双重确认）
            Text(
                text = stringResource(R.string.chat_edit_resend_warning),
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                color = BiuZOrange,
            )
            failure?.let { DecisionFailureRow(it) }
            DecisionButton(
                label = if (sending) {
                    stringResource(R.string.chat_edit_resend_sending)
                } else {
                    stringResource(R.string.chat_edit_resend_title)
                },
                container = BiuZAccent,
                content = BiuZOnAccent,
                enabled = !sending && text.trim().isNotEmpty(),
                onClick = { showConfirm = true },
            )
        }
    }
    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.chat_edit_resend_confirm_title)) },
            text = { Text(stringResource(R.string.chat_edit_resend_confirm_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showConfirm = false
                        sending = true
                        scope.launch {
                            failure = onResend(text.trim())
                            sending = false
                            if (failure == null) onClose() // 成功：关闭；消息流由桌面重建自动刷新
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.chat_edit_resend_confirm),
                        color = cn.biuz.android.app.ui.theme.BiuZRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// MARK: - 助手行：正文 + 思考折叠块 + 工具卡（计划卡优先）+ todo

@Composable
private fun AgentMessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onRetryToolCall: (ChatMessage, ToolCall) -> Unit,
    attachmentContext: AttachmentStripContext?,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        if (message.text.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top) {
                AgentAvatar()
                Spacer(Modifier.size(BiuZSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    CollapsibleBodyText(
                        text = message.text,
                        streaming = message.status == MessageStatus.streaming,
                    )
                }
            }
        }
        message.thinking?.let { ThinkingBlock(it.text, it.state) }
        // G-014 附件缩略图行（无附件数据不渲染任何占位块；iOS MessageViews.swift:91 同构）
        if (attachmentContext != null && message.attachments.isNotEmpty()) {
            AttachmentStripView(
                store = attachmentContext.store,
                sessionId = attachmentContext.sessionId,
                refs = message.attachments,
                fullscreen = attachmentContext.fullscreen,
            )
        }
        message.toolCall?.let { call ->
            // ExitPlanMode 计划预览卡优先——载荷形状门命中即整卡替换（官方 renderer 同为整块替换）
            val plan = remember(call.target) { PlanToolCallSupport.parse(call.target) }
            if (plan != null) {
                PlanToolCallCard(plan = plan)
            } else {
                ToolCallCard(call = call, onRetry = { onRetryToolCall(message, call) })
            }
        }
        message.todos?.takeIf { it.isNotEmpty() }?.let { todos ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgCard)
                    .border(1.dp, BiuZBorder, BiuZRadius.medium)
                    .padding(BiuZSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val mark = when (todo.state) {
                            TodoState.done -> "✓"
                            TodoState.now -> "▶"
                            TodoState.failed -> "✕"
                            TodoState.todo -> "○"
                        }
                        val color = when (todo.state) {
                            TodoState.done -> BiuZAccent
                            TodoState.now -> BiuZBlue
                            TodoState.failed -> BiuZOrange
                            TodoState.todo -> BiuZText3
                        }
                        Text(text = mark, color = color, fontSize = 12.sp)
                        Spacer(Modifier.size(BiuZSpacing.xs))
                        Text(
                            text = todo.title,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                            color = BiuZText,
                        )
                    }
                }
            }
        }
        if (message.status == MessageStatus.streaming) {
            StreamingCursor()
        }
    }
}

/** 助手头像（22dp 圆徽「Z」）。 */
@Composable
private fun AgentAvatar() {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(BiuZAccentDim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Z", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BiuZAccent)
    }
}

/** 流式光标（iOS BlinkingCursor 对位：accent 竖条）。 */
@Composable
private fun StreamingCursor() {
    Box(
        modifier = Modifier
            .size(width = 8.dp, height = 15.dp)
            .background(BiuZAccent, RoundedCornerShape(2.dp)),
    )
}

// MARK: - 正文（6000 字截断 + 展开收起；流式不截断——iOS UserBubble:370 / MarkdownMessageBody:843）

@Composable
private fun CollapsibleBodyText(text: String, streaming: Boolean) {
    var expanded by remember(text) { mutableStateOf(false) }
    val truncated = LongTextDisplay.shouldTruncate(text)
    Column {
        Text(
            text = LongTextDisplay.displayText(text, expanded, streaming),
            style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            color = BiuZText,
        )
        if (truncated && !streaming) {
            androidx.compose.material3.TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.chat_msg_collapse)
                    } else {
                        stringResource(R.string.chat_msg_expand, LongTextDisplay.totalCount(text))
                    },
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZAccent,
                )
            }
        }
    }
}

// MARK: - 思考折叠块（折叠态只留摘要头；中断态显式标出）

@Composable
private fun ThinkingBlock(text: String, state: ThinkingState) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickableSafe { expanded = !expanded }
                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (state) {
                    ThinkingState.streaming -> stringResource(R.string.chat_thinking_header)
                    ThinkingState.done -> stringResource(
                        R.string.chat_thinking_chars,
                        LongTextDisplay.totalCount(text),
                    )
                    ThinkingState.interrupted -> stringResource(R.string.chat_thinking_interrupted)
                },
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▾" else "▸",
                color = BiuZText3,
                fontSize = 11.sp,
            )
        }
        if (expanded) {
            Text(
                text = text,
                style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                color = BiuZText2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BiuZSpacing.md)
                    .padding(bottom = BiuZSpacing.sm),
            )
        }
    }
}

/** combinedClickable 的非 OptIn 包装（折叠块/气泡之外的第三处使用点）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

// MARK: - 工具调用卡（spec 5.3：头 44 最小热区，展开体 mono 弱底；iOS ToolCallCardView 直译）

@Composable
private fun ToolCallCard(call: ToolCall, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .combinedClickableSafe { expanded = !expanded }
                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = kindGlyph(call.kind),
                fontSize = 11.sp,
                color = BiuZText3,
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            Text(
                text = kindLabel(call.kind),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = BiuZText,
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            Text(
                text = call.target,
                style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                color = BiuZText2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            when (call.status) {
                ToolCallStatus.running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = BiuZBlue,
                )
                ToolCallStatus.done -> Text("✓", color = BiuZAccent, fontSize = 12.sp)
                ToolCallStatus.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✕", color = BiuZOrange, fontSize = 12.sp)
                    // G-015：失败且行元数据游标（entityId）在场才出现「重试」——无游标不渲染
                    if (call.entityId != null) {
                        androidx.compose.material3.TextButton(onClick = onRetry) {
                            Text(
                                text = stringResource(R.string.composer_retry),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BiuZAccent,
                            )
                        }
                    }
                }
            }
            Text(
                text = if (expanded) "▾" else "▸",
                color = BiuZText3,
                fontSize = 11.sp,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BiuZBgCode)
                    .padding(BiuZSpacing.md),
            ) {
                // 展开体首行：完整命令/文件路径（头部截断仅是摘要，展开后全量铺开）
                Text(
                    text = call.target,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    ),
                    color = BiuZText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
                call.output?.takeIf { it.isNotEmpty() }?.let { output ->
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Text(
                        text = output,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp,
                        ),
                        color = BiuZText2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/** 工具种类字形（桌面工具行同构；iOS kindIcon 的字符对位——无 SF Symbols 向量资产）。 */
private fun kindGlyph(kind: ToolKind): String = when (kind) {
    ToolKind.bash -> "$"
    ToolKind.read -> "▤"
    ToolKind.edit -> "✎"
    ToolKind.ask -> "?"
    ToolKind.browser -> "◉"
}

/** 工具种类中文动词（桌面「编辑/读取/正在执行」同构；资源化）。 */
@Composable
private fun kindLabel(kind: ToolKind): String = when (kind) {
    ToolKind.bash -> stringResource(R.string.chat_tool_verb_bash)
    ToolKind.read -> stringResource(R.string.chat_tool_verb_read)
    ToolKind.edit -> stringResource(R.string.chat_tool_verb_edit)
    ToolKind.ask -> stringResource(R.string.chat_tool_verb_ask)
    ToolKind.browser -> stringResource(R.string.chat_tool_verb_browser)
}

// MARK: - ExitPlanMode 计划预览卡（官方 SwitchModeToolCallBlock 移动端同构，iOS :697-811 直译）

/** 预览排版上限（>4k 字计划正文截断防卡顿；全文在 sheet——官方 max-h-64 渐隐同思路）。 */
private const val PLAN_PREVIEW_LIMIT = 4_000

/** 渐隐预览窗高（iOS maxHeight 216 对位；底部留胶囊浮钮位）。 */
private val PLAN_PREVIEW_HEIGHT = 180.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanToolCallCard(plan: PlanToolCallContent, modifier: Modifier = Modifier) {
    var showSheet by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val title = remember(plan.markdown) { PlanToolCallSupport.planTitle(plan.markdown) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .combinedClickableSafe { showSheet = true }, // 整卡点按同开（官方整卡可点）
    ) {
        // 头：「📋 计划」+ 可选计划文件尾段 + 复制（复制后 2s 打勾，官方 copy→check 同款）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md)
                .padding(top = BiuZSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "📋", fontSize = 13.sp)
            Spacer(Modifier.size(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.chat_plan_card_title),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = BiuZText3,
            )
            plan.planFilePath?.let { path ->
                Spacer(Modifier.size(BiuZSpacing.xs))
                Text(
                    text = PlanToolCallSupport.planFileLabel(path),
                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(plan.markdown)) // 复制 plan 原文
                    copied = true
                },
            ) {
                Text(
                    text = if (copied) "✓" else "⧉",
                    fontSize = 13.sp,
                    color = if (copied) BiuZAccent else BiuZText3,
                )
            }
        }
        // 预览体：渐隐（三段渐变 mask 同构）+ 居中「查看完整计划」胶囊浮钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BiuZSpacing.sm),
        ) {
            val preview = if (plan.markdown.length > PLAN_PREVIEW_LIMIT) {
                plan.markdown.take(PLAN_PREVIEW_LIMIT) + "…"
            } else {
                plan.markdown
            }
            Text(
                text = preview,
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = BiuZText2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PLAN_PREVIEW_HEIGHT)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // 官方 mask-image 三段渐变同构（0→0.55 不透明，1 透明）
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                0.55f to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            androidx.compose.material3.Button(
                onClick = { showSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = BiuZSpacing.md),
                shape = RoundedCornerShape(999.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = BiuZAccent,
                    contentColor = BiuZOnAccent,
                ),
            ) {
                Text(
                    text = stringResource(R.string.chat_plan_card_open),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(BiuZSpacing.xs))
                Text(text = "→", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LaunchedEffect(copied) {
            if (copied) {
                delay(2_000)
                copied = false
            }
        }
    }
    // 全文 sheet（官方 onOpenPlanDetail 侧栏的移动端形态：全量 markdown 可滚动）
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = cn.biuz.android.app.ui.theme.BiuZBgElevated,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg)
                    .padding(bottom = BiuZSpacing.xl),
            ) {
                Text(
                    text = title ?: stringResource(R.string.chat_plan_card_title),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = BiuZText,
                )
                Spacer(Modifier.height(BiuZSpacing.md))
                Text(
                    text = plan.markdown,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                    color = BiuZText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

// MARK: - userselect 引用卡（官方 ContextAttachmentPill 移动端形态；iOS :522-566 直译）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSelectionCard(selections: List<UserSelectionReference>, modifier: Modifier = Modifier) {
    var showDetail by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .combinedClickableSafe { showDetail = true }
            .padding(BiuZSpacing.md),
    ) {
        // label 三规则（官方 ConversationSelectionReferenceChip.tsx:24-38）
        when (val label = UserSelectionSupport.cardLabel(selections)) {
            is UserSelectionSupport.CardLabel.SingleFile ->
                CardLabelText(stringResource(R.string.chat_userselect_single_ref, label.filename))
            is UserSelectionSupport.CardLabel.CountReferences ->
                CardLabelText(stringResource(R.string.chat_userselect_n_refs, label.count))
            is UserSelectionSupport.CardLabel.CountConversationRefs ->
                CardLabelText(stringResource(R.string.chat_userselect_n_dialog_refs, label.count))
        }
        Spacer(Modifier.height(2.dp))
        // 首条 2 行预览
        Text(
            text = selections.first().text,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = BiuZText3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (showDetail) {
        // 详情 sheet（呈现期捕获 selections；§5-15 关闭单路径置否）
        ModalBottomSheet(
            onDismissRequest = { showDetail = false },
            containerColor = cn.biuz.android.app.ui.theme.BiuZBgElevated,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg)
                    .padding(bottom = BiuZSpacing.xl),
            ) {
                Text(
                    text = stringResource(R.string.chat_userselect_detail_title),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = BiuZText,
                )
                Spacer(Modifier.height(BiuZSpacing.md))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    selections.forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.medium)
                                .background(BiuZBgCard)
                                .border(1.dp, BiuZBorder, BiuZRadius.medium)
                                .padding(BiuZSpacing.md),
                        ) {
                            // 全文（逐条；选中文本可复制由系统选区承担，长按复制用卡面）
                            Text(
                                text = item.text,
                                style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
                                color = BiuZText,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = item.path
                                    ?: stringResource(R.string.chat_userselect_source_dialog),
                                style = TextStyle(fontSize = 11.5.sp),
                                color = BiuZText3,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardLabelText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "❝", fontSize = 12.sp, color = BiuZText3)
        Spacer(Modifier.size(BiuZSpacing.xs))
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = BiuZText,
        )
    }
}

// MARK: - 交互卡（审批/提问/计划/hook 四分支；宿主统一限高 240dp + 内部滚动）

/** 交互卡回调面（决议经 VM 下发 store，失败/未知态由卡内错误行如实呈现）。 */
data class InteractionActions(
    /** 权限族：拼好的 optionId（allowOnce/allowAlways/rejectOnce/rejectAlways 或服务端自定义）。 */
    val onPermissionOption: suspend (RemotePendingInteraction, String) -> ChatViewModel.InteractionFailure?,
    /** 计划族：{action:"accept"|"decline"}。 */
    val onPlanAction: suspend (RemotePendingInteraction, String) -> ChatViewModel.InteractionFailure?,
    /** 提问族自由文本：{freeText}。 */
    val onQuestionFreeText: suspend (RemotePendingInteraction, String) -> ChatViewModel.InteractionFailure?,
    /** 提问族选项：{optionId}。 */
    val onQuestionOption: suspend (RemotePendingInteraction, RemoteInteractionOption) -> ChatViewModel.InteractionFailure?,
    /** hook 信任审核：reviewItemId 列表逐字回传。 */
    val onHookTrust: suspend (RemotePendingInteraction, List<String>) -> ChatViewModel.InteractionFailure?,
)

@Composable
fun InteractionCard(
    interaction: RemotePendingInteraction,
    actions: InteractionActions,
    modifier: Modifier = Modifier,
) {
    when {
        interaction.isPermission -> PermissionInteractionCard(interaction, actions, modifier)
        interaction.isPlanApproval -> PlanInteractionCard(interaction, actions, modifier)
        interaction.isWorkspaceHookReview -> HookReviewInteractionCard(interaction, actions, modifier)
        // userInput / elicitation / question → 提问卡（蓝描边；§3.4）
        else -> QuestionInteractionCard(interaction, actions, modifier)
    }
}

/** 卡面框架（统一视觉：边框色 + 标题行 + 内容；限高滚动由宿主容器承担）。 */
@Composable
private fun InteractionCardFrame(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, accent, BiuZRadius.medium)
            .padding(BiuZSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = BiuZText,
        )
        content()
    }
}

/** 决议失败行（错误路径上屏：服务层拒因原文/未送达/未知态三态；不撤卡控件恢复 §3.2）。 */
@Composable
private fun DecisionFailureRow(failure: ChatViewModel.InteractionFailure) {
    val text = when (failure) {
        is ChatViewModel.InteractionFailure.Rejected ->
            stringResource(R.string.chat_decision_rejected, failure.text)
        ChatViewModel.InteractionFailure.Undelivered ->
            stringResource(R.string.chat_decision_undelivered)
        ChatViewModel.InteractionFailure.UnknownState ->
            stringResource(R.string.chat_decision_unknown)
    }
    Text(
        text = text,
        style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        color = BiuZOrange,
    )
}

/** 44pt 胶囊 chip（选中 accent 实底；两处权限 UI 同构语言，降级设计 §3.6）。 */
@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) BiuZAccent else BiuZBgInput)
            .border(1.dp, if (selected) BiuZAccent else BiuZBorderStrong, RoundedCornerShape(999.dp))
            .combinedClickableSafe(enabled = enabled, onClick = onClick)
            .padding(horizontal = BiuZSpacing.md, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = if (selected) BiuZOnAccent else BiuZText,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafe(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.combinedClickable(enabled = enabled, onClick = onClick)

// MARK: 权限族（§3.2：默认两档 chips + 批准/拒绝；四族混合态方向钮禁用；全非四族单选即决）

@Composable
private fun PermissionInteractionCard(
    interaction: RemotePendingInteraction,
    actions: InteractionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var deciding by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ChatViewModel.InteractionFailure?>(null) }
    // scope 两档（once/always）；四族命中推导可见性（§3.2 规则 3）
    val optionIds = interaction.options.map { it.id }
    val hasOnce = optionIds.any { it == "allowOnce" || it == "rejectOnce" }
    val hasAlways = optionIds.any { it == "allowAlways" || it == "rejectAlways" }
    val singleSelect = optionIds.isNotEmpty() && optionIds.none { it in FOUR_FAMILY_IDS }
    var selectedScope by remember(interaction.id) {
        mutableStateOf(if (hasOnce || optionIds.isEmpty()) "once" else "always")
    }

    fun decide(approved: Boolean) {
        if (deciding) return
        val optionId = (if (approved) "allow" else "reject") +
            (if (selectedScope == "always") "Always" else "Once")
        deciding = true
        scope.launch {
            failure = actions.onPermissionOption(interaction, optionId)
            deciding = false
        }
    }

    InteractionCardFrame(
        title = interaction.title ?: stringResource(R.string.chat_card_permission_title),
        accent = BiuZOrange,
        modifier = modifier,
    ) {
        interaction.command?.takeIf { it.isNotEmpty() }?.let { command ->
            Text(
                text = command,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                ),
                color = BiuZText2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        interaction.impact?.takeIf { it.isNotEmpty() }?.let { impact ->
            Text(
                text = impact,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = BiuZText3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            singleSelect -> {
                // 全非四族 id：chips 即选项（点选即发，自带方向语义；两步矩阵整组隐藏 §3.2 规则 3）
                Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    interaction.options.forEach { option ->
                        SelectChip(label = option.label, selected = false) {
                            deciding = true
                            scope.launch {
                                failure = actions.onPermissionOption(interaction, option.id)
                                deciding = false
                            }
                        }
                    }
                }
            }
            else -> {
                // 默认两档（服务端 options 空或含四族）；可见性按四族命中推导
                Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    if (hasOnce || interaction.options.isEmpty()) {
                        SelectChip(
                            label = stringResource(R.string.chat_card_scope_once),
                            selected = selectedScope == "once",
                            enabled = !deciding,
                        ) { selectedScope = "once" }
                    }
                    if (hasAlways || interaction.options.isEmpty()) {
                        SelectChip(
                            label = stringResource(R.string.chat_card_scope_always),
                            selected = selectedScope == "always",
                            enabled = !deciding,
                        ) { selectedScope = "always" }
                    }
                }
                // 方向钮禁用规则（混合态核心）：选中 scope 的某方向不在四族 → 该钮禁用（§3.2）；
                // deciding 全局拦（括号必须包住方向项——裸 && 优先级会在决议中放开禁用）
                val approveEnabled = !deciding && (
                    interaction.options.isEmpty() ||
                        (if (selectedScope == "always") hasAlways && optionIds.contains("allowAlways")
                         else hasOnce && optionIds.contains("allowOnce"))
                    )
                val rejectEnabled = !deciding && (
                    interaction.options.isEmpty() ||
                        (if (selectedScope == "always") hasAlways && optionIds.contains("rejectAlways")
                         else hasOnce && optionIds.contains("rejectOnce"))
                    )
                Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    DecisionButton(
                        label = stringResource(R.string.chat_card_reject),
                        container = BiuZBgInput,
                        content = BiuZText,
                        enabled = rejectEnabled,
                        onClick = { decide(approved = false) },
                    )
                    DecisionButton(
                        label = stringResource(R.string.chat_card_approve),
                        container = BiuZAccent,
                        content = BiuZOnAccent,
                        enabled = approveEnabled,
                        onClick = { decide(approved = true) },
                    )
                }
            }
        }
        failure?.let { DecisionFailureRow(it) }
    }
}

/** 计划族（§3.3：{action:"accept"|"decline"}；cancel 不设专用按钮——snooze 语义独立）。 */
@Composable
private fun PlanInteractionCard(
    interaction: RemotePendingInteraction,
    actions: InteractionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var deciding by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ChatViewModel.InteractionFailure?>(null) }
    InteractionCardFrame(
        title = interaction.title ?: stringResource(R.string.chat_card_plan_title),
        accent = BiuZAccent,
        modifier = modifier,
    ) {
        // 计划正文预览（renderContext.plan / planText；有界预览，全文随审批流走桌面）
        interaction.planText?.takeIf { it.isNotEmpty() }?.let { plan ->
            Text(
                text = plan,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = BiuZText2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 96.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
            DecisionButton(
                label = stringResource(R.string.chat_card_plan_decline),
                container = BiuZBgInput,
                content = BiuZText,
                enabled = !deciding,
                onClick = {
                    deciding = true
                    scope.launch {
                        failure = actions.onPlanAction(interaction, "decline")
                        deciding = false
                    }
                },
            )
            DecisionButton(
                label = stringResource(R.string.chat_card_plan_accept),
                container = BiuZAccent,
                content = BiuZOnAccent,
                enabled = !deciding,
                onClick = {
                    deciding = true
                    scope.launch {
                        failure = actions.onPlanAction(interaction, "accept")
                        deciding = false
                    }
                },
            )
        }
        failure?.let { DecisionFailureRow(it) }
    }
}

/** 提问族（§3.4：options 点选即发 {optionId}；输入行 {freeText}；蓝描边）。 */
@Composable
private fun QuestionInteractionCard(
    interaction: RemotePendingInteraction,
    actions: InteractionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<ChatViewModel.InteractionFailure?>(null) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    InteractionCardFrame(
        title = interaction.title ?: stringResource(R.string.chat_card_question_title),
        accent = BiuZBlue,
        modifier = modifier,
    ) {
        interaction.command?.takeIf { it.isNotEmpty() }?.let { question ->
            Text(
                text = question,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = BiuZText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 服务端 options chips（点击即发 {optionId}——web「按钮即选项」同构）
        if (interaction.options.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                interaction.options.forEach { option ->
                    SelectChip(label = option.label, selected = false, enabled = !sending) {
                        sending = true
                        scope.launch {
                            failure = actions.onQuestionOption(interaction, option)
                            sending = false
                        }
                    }
                }
            }
        }
        // 自由文本行（空文本禁发）
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = reply,
                onValueChange = { reply = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = BiuZText),
                modifier = Modifier
                    .weight(1f)
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
                decorationBox = { inner ->
                    if (reply.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_card_question_hint),
                            style = TextStyle(fontSize = 13.sp),
                            color = BiuZText3,
                        )
                    }
                    inner()
                },
            )
            Spacer(Modifier.size(BiuZSpacing.sm))
            DecisionButton(
                label = stringResource(R.string.chat_card_question_send),
                container = BiuZAccent,
                content = BiuZOnAccent,
                enabled = !sending && reply.trim().isNotEmpty(),
                onClick = {
                    sending = true
                    scope.launch {
                        failure = actions.onQuestionFreeText(interaction, reply.trim())
                        sending = false
                    }
                },
            )
        }
        failure?.let { DecisionFailureRow(it) }
    }
}

/** workspace hook 信任审核（reviewItemId 逐字回传；拆分面未取证 → 整单信任）。 */
@Composable
private fun HookReviewInteractionCard(
    interaction: RemotePendingInteraction,
    actions: InteractionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var deciding by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ChatViewModel.InteractionFailure?>(null) }
    InteractionCardFrame(
        title = interaction.title ?: stringResource(R.string.chat_card_hook_title),
        accent = BiuZOrange,
        modifier = modifier,
    ) {
        interaction.hookReviewItems.takeIf { it.isNotEmpty() }?.let { items ->
            Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
                items.forEach { item ->
                    Text(
                        text = item.title ?: item.id,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                        color = BiuZText2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DecisionButton(
            label = stringResource(R.string.chat_card_hook_trust),
            container = BiuZAccent,
            content = BiuZOnAccent,
            enabled = !deciding,
            onClick = {
                deciding = true
                scope.launch {
                    failure = actions.onHookTrust(
                        interaction,
                        interaction.hookReviewItems.map { it.id },
                    )
                    deciding = false
                }
            },
        )
        failure?.let { DecisionFailureRow(it) }
    }
}

/** 44pt 热区决定钮（禁用 = 弱底灰字；批准 accent 实底 / 拒绝弱底，§3.2 视觉语言）。 */
@Composable
private fun DecisionButton(
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(BiuZRadius.medium)
            .background(if (enabled) container else BiuZBgInput)
            .combinedClickableSafe(enabled = enabled, onClick = onClick)
            .padding(horizontal = BiuZSpacing.lg, vertical = 11.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = if (enabled) content else BiuZText3,
        )
    }
}

// MARK: - 决议答案构造（A-3 对象分形；纯函数单测直测）

/** 权限应答：{optionId}。 */
fun permissionAnswer(optionId: String): JSONValue =
    JSONValue.Obj(mapOf("optionId" to JSONValue.Str(optionId)))

/** 计划应答：{action}。 */
fun planAnswer(action: String): JSONValue =
    JSONValue.Obj(mapOf("action" to JSONValue.Str(action)))

/** 提问自由文本应答：{freeText}。 */
fun freeTextAnswer(text: String): JSONValue =
    JSONValue.Obj(mapOf("freeText" to JSONValue.Str(text)))

/** 提问选项应答：{optionId}。 */
fun optionAnswer(optionId: String): JSONValue = permissionAnswer(optionId)

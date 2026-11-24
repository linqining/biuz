package cn.biuz.android.app.ui.chat

// 消息行渲染（iOS MessageViews.swift 直译；行模型权威 = 协议文档 §10.1）：
// - 用户气泡（6000 字截断+展开收起）+ userselect 引用卡（解析钉死渲染层，复制/编辑重发用原文）；
// - 助手正文（6000 字截断；流式不截断+光标）+ 思考折叠块 + 工具卡（ExitPlanMode 计划卡整卡替换）；
// - 交互卡四分支（permission / plan_approval / userInput 提问 / workspaceHookReview）——
//   宿主统一放进「常驻 composer 上方 · 限高 240dp + 内部滚动」容器（ChatScreen），三卡统一口径。
// 应答形状（A-3）：answer 恒为对象按交互族分形——权限 {optionId} / 计划 {action} / 提问
// {freeText}|{optionId}；决议失败如实上屏不撤卡。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZAccentDim
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.ctaPress
import cn.biuz.android.app.ui.theme.BiuZBgCode
import cn.biuz.android.app.ui.theme.BiuZBgElevated
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZGreen
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
import cn.biuz.android.protocol.WorkflowCompletionArtifact
import cn.biuz.android.protocol.WorkflowCompletionCard
import cn.biuz.android.protocol.WorkflowRunSummary
import cn.biuz.android.store.ConversationStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 权限族 optionId 词表（web 实证四族 allowOnce/allowAlways/rejectOnce/rejectAlways，降级口径）。 */
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
    /**
     * 工作流完成卡四格 join（活投影按 runId 对位；null = tokens/子代理/阶段三格「—」
     * ——冷恢复 run 被淘汰的桌面同口径）。iOS ChatView :351-355 同位注入。
     */
    workflowCompletionRun: WorkflowRunSummary? = null,
    /** 完成卡 ⤢ 展开工作流面板（宿主门控接线；null = 不渲染入口）。 */
    onOpenWorkflowRun: (() -> Unit)? = null,
    /** 完成卡产物点按 → 产物详情 sheet（宿主接 store.workflowArtifactContent）。 */
    onOpenWorkflowArtifact: ((WorkflowCompletionCard, WorkflowCompletionArtifact) -> Unit)? = null,
    /** P-020 行级反馈（赞/踩/取消）：null = 不渲染入口（游标缺失或宿主未接）。 */
    onFeedback: ((ChatMessage, Boolean?) -> Unit)? = null,
    /** 消息动作「Fork」（web 同入口）：null = 不渲染；宿主负责成功提示/导航。 */
    onFork: (() -> Unit)? = null,
) {
    // v4 timelineMarker 行：时间轴分隔（不做气泡/头像；web ConversationTimelineMarkerDivider 同构）。
    // 不可见类型（web default: return null 同口径）整行不渲染。
    message.timelineMarker?.let { marker ->
        if (marker.isVisible) {
            TimelineMarkerRow(marker = marker, modifier = modifier)
        }
        return
    }
    // hookInvocation 行不进消息流（web 行渲染同样 return null，
    // ConversationShareReadonlyTimeline.tsx:826-828）；由宿主按轮汇总成「Hook 详情」入口。
    if (message.hookInvocation != null) return
    when (message.role) {
        MessageRole.user -> UserMessageRow(message, modifier, onEditResend, attachmentContext)
        MessageRole.agent -> AgentMessageRow(
            message = message,
            modifier = modifier,
            onRetryToolCall = onRetryToolCall,
            attachmentContext = attachmentContext,
            workflowCompletionRun = workflowCompletionRun,
            onOpenWorkflowRun = onOpenWorkflowRun,
            onOpenWorkflowArtifact = onOpenWorkflowArtifact,
            onFeedback = onFeedback,
            onFork = onFork,
        )
    }
}

// MARK: - 时间轴分隔标记（v4 timelineMarker）

/**
 * 时间轴分隔行（web `ConversationTimelineMarkerDivider`
 * ConversationShareReadonlyTimeline.tsx:255-390 的移动端同构）：
 * 左右细横线 + 中间图标与文案，运行中显示小转圈。
 *
 * 文案映射（zh 逐字取自 web locale）：
 * - compact：started / completed / completedAuto / skipped / failed / interrupted
 * - modelChange：`模型已切换 A → B`；source-less（首次使用）→ `正在使用 B`
 * - goalVerify：`第 N 次迭代 · 目标校验中/已完成/未完成`
 * - forkNotice：`从对话中派生`（web 可点跳父会话——移动端无该导航面，只展示）
 */
@Composable
private fun TimelineMarkerRow(
    marker: cn.biuz.android.protocol.TimelineMarkerInfo,
    modifier: Modifier = Modifier,
) {
    val (glyph, label, running) = when (marker.type) {
        "compact" -> {
            val running = marker.status == "running"
            val auto = marker.origin == "auto"
            val text = when (marker.status) {
                "running" -> stringResource(R.string.marker_compact_started)
                "noop" -> stringResource(R.string.marker_compact_skipped)
                "cancelled" -> stringResource(R.string.marker_compact_interrupted)
                "failed" -> stringResource(R.string.marker_compact_failed)
                else -> stringResource(
                    if (auto) R.string.marker_compact_completed_auto
                    else R.string.marker_compact_completed,
                )
            }
            Triple("🗜", text, running)
        }

        "modelChange" -> {
            val to = marker.toModel ?: marker.toProvider ?: ""
            // source-less = 首次使用的模型事实，不是切换（web :318-327 注释逐字）
            val text = if (marker.fromProvider == null || marker.fromModel == null) {
                stringResource(R.string.marker_model_using, to)
            } else {
                stringResource(
                    R.string.marker_model_switched,
                    marker.fromModel ?: "",
                    to,
                )
            }
            Triple("⇄", text, false)
        }

        "goalVerify" -> {
            val statusText = when (marker.status) {
                "running" -> stringResource(R.string.marker_goal_checking)
                "pass" -> stringResource(R.string.marker_goal_complete)
                else -> stringResource(R.string.marker_goal_incomplete)
            }
            val iteration = marker.iteration
            val text = if (iteration != null) {
                stringResource(R.string.marker_goal_iteration, iteration) + " · " + statusText
            } else {
                statusText
            }
            Triple("◎", text, marker.status == "running")
        }

        else -> Triple("⑃", stringResource(R.string.marker_fork_derived), false)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = BiuZSpacing.xs)
            .testTag("05-timeline-marker-${marker.type}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(BiuZBorder),
        )
        Text(text = glyph, fontSize = 10.sp, color = BiuZText3)
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp),
            color = BiuZText3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(3f, fill = false),
        )
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(9.dp),
                strokeWidth = 1.2.dp,
                color = BiuZText3,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(BiuZBorder),
        )
    }
}

// MARK: - 用户行：附件位（气泡上方右对齐）+ userselect 剥离 + 气泡

private val userBubbleShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 4.dp,
    bottomEnd = 16.dp,
    bottomStart = 16.dp,
)

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
                        .clip(userBubbleShape)
                        .background(BiuZAccentDim)
                        .border(1.dp, BiuZBorder, userBubbleShape)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        )
                        .padding(horizontal = BiuZSpacing.md, vertical = 10.dp),
                ) {
                    CollapsiblePlainText(text = displayText)
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
 * 用户气泡正文（web `ConversationUserInputBody.tsx:7-25,104-145` 同构）：
 * **纯文本**（不渲染 markdown，保留空白与换行）+ 超高折叠（≈120dp / 5 行）+ 底部渐隐 +
 * 圆形展开箭头；展开后全量。
 */
@Composable
private fun CollapsiblePlainText(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Box {
        Text(
            text = text,
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            color = BiuZText,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout ->
                if (!expanded) overflowing = layout.didOverflowHeight
            },
        )
        if (!expanded && overflowing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, BiuZAccentDim),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = 18.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(BiuZAccent)
                    .clickable { expanded = true }
                    .testTag("05-user-expand"),
                contentAlignment = Alignment.Center,
            ) {
                Text("⌄", fontSize = 12.sp, color = BiuZOnAccent)
            }
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(BiuZBgInput)
                    .clickable { expanded = false }
                    .testTag("05-user-collapse"),
                contentAlignment = Alignment.Center,
            ) {
                Text("⌃", fontSize = 12.sp, color = BiuZText2)
            }
        }
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
                textStyle = TextStyle(fontSize = 15.sp, color = BiuZText, lineHeight = 20.sp),
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
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
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

/**
 * 消息动作行（P-020）：复制正文 + 发送时间。
 *
 * web 权威源：会话页消息动作行（复制 / 赞 / 踩 / 展开 / 锚点 / 时间）。
 * 本轮只落无副作用的只读项；赞踩属写面（store.setAssistantFeedback 已具备，VM 未接）。
 */
@Composable
private fun MessageActionRow(
    message: ChatMessage,
    onFeedback: ((ChatMessage, Boolean?) -> Unit)? = null,
    onFork: (() -> Unit)? = null,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    // 本地乐观态（服务端行回声到达后以 message.feedback 为准）
    var feedback by remember(message.id, message.feedback) { mutableStateOf(message.feedback) }
    val canFeedback = onFeedback != null && message.rowKind == "assistantText" && message.entityId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp)
            .testTag("05-msg-actions-" + message.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
    ) {
        Text(
            text = if (copied) "已复制" else "复制",
            fontSize = 11.sp,
            color = BiuZText3,
            modifier = Modifier
                .clip(BiuZRadius.small)
                .clickable {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                    copied = true
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("05-msg-copy-" + message.id),
        )
        if (canFeedback) {
            Text(
                text = "赞",
                fontSize = 11.sp,
                color = if (feedback == true) BiuZText else BiuZText3,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable {
                        val next = if (feedback == true) null else true
                        feedback = next
                        onFeedback?.invoke(message, next)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("05-msg-like-" + message.id),
            )
            Text(
                text = "踩",
                fontSize = 11.sp,
                color = if (feedback == false) BiuZText else BiuZText3,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable {
                        val next = if (feedback == false) null else false
                        feedback = next
                        onFeedback?.invoke(message, next)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("05-msg-dislike-" + message.id),
            )
        }
        // Fork 入口（web 同位置；assistantText + 游标 + 非流式才出现）
        if (onFork != null && canFeedback && message.status != MessageStatus.streaming) {
            Text(
                text = stringResource(R.string.chat_fork_action),
                fontSize = 11.sp,
                color = BiuZText3,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onFork() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("05-msg-fork-" + message.id),
            )
        }
        Text(
            text = remember(message.timestamp) { formatMessageTime(message.timestamp) },
            fontSize = 11.sp,
            color = BiuZText3,
        )
    }
}

/** 时间口径：今天 HH:mm；否则 M月d日 HH:mm（会话页动作行同 web）。 */
private fun formatMessageTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val hh = String.format(java.util.Locale.US, "%02d:%02d",
        then.get(java.util.Calendar.HOUR_OF_DAY), then.get(java.util.Calendar.MINUTE))
    return if (sameDay) hh else "${then.get(java.util.Calendar.MONTH) + 1}月${then.get(java.util.Calendar.DAY_OF_MONTH)}日 $hh"
}

@Composable
private fun AgentMessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onRetryToolCall: (ChatMessage, ToolCall) -> Unit,
    attachmentContext: AttachmentStripContext?,
    workflowCompletionRun: WorkflowRunSummary? = null,
    onOpenWorkflowRun: (() -> Unit)? = null,
    onOpenWorkflowArtifact: ((WorkflowCompletionCard, WorkflowCompletionArtifact) -> Unit)? = null,
    onFeedback: ((ChatMessage, Boolean?) -> Unit)? = null,
    onFork: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        val structuredRow = message.subagent != null || message.artifact != null
        if (message.text.isNotEmpty() && !structuredRow) {
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
        // 子代理行（web SubagentRowView 同文案：`type · status — summary`，单行弱化）
        message.subagent?.let { subagent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp)
                    .testTag("05-subagent-row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🤖", fontSize = 12.sp)
                Spacer(Modifier.size(BiuZSpacing.xs))
                Text(
                    text = buildString {
                        append(subagent.type.ifBlank { "subagent" })
                        subagent.status?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        subagent.summary?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                    },
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    color = BiuZText3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 产物行卡（web ArtifactRowView 同构：文件图标 + 名称 + `TYPE · N bytes`）
        message.artifact?.let { artifact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgCard)
                    .border(1.dp, BiuZBorder, BiuZRadius.medium)
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                    .testTag("05-artifact-row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📦", fontSize = 14.sp)
                Spacer(Modifier.size(BiuZSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artifact.name,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = BiuZText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(artifact.kind.uppercase())
                            artifact.bytes?.let { append(" · ").append(byteText(it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())) }
                        },
                        style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = BiuZText3,
                    )
                }
            }
        }
        // 消息动作行（web 会话页为 复制/赞/踩/展开/锚点/时间；本轮先落只读两项：
        // 复制 + 时间。赞/踩需接 setAssistantFeedback 的 VM 写链路，单列后续。
        if (message.text.isNotEmpty() && message.status != MessageStatus.streaming) {
            MessageActionRow(message = message, onFeedback = onFeedback, onFork = onFork)
        }
        // P-019 per-turn 变更条（web 位置：该轮助手消息下方；数据 = turnHeader.fileChanges）
        message.turnChanges?.takeIf { it.files > 0 }?.let { changes ->
            MessageChangeSummaryBar(turnChanges = changes)
        }
        message.thinking?.let { ThinkingBlock(it.text, it.state, it.duration) }
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
                            TodoState.done -> BiuZBlue
                            TodoState.now -> BiuZGreen
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
        // 工作流完成卡（桌面 ConversationWorkflowCompletion 同款，2026-10-09 点单；
        // iOS MessageViews.swift:113-121 同位挂点——整卡替换在 todos 之后、流式光标之前）
        message.workflowCompletion?.let { card ->
            WorkflowCompletionCardView(
                card = card,
                run = workflowCompletionRun,
                onOpenRun = onOpenWorkflowRun,
                onOpenArtifact = onOpenWorkflowArtifact?.let { callback ->
                    { artifact -> callback(card, artifact) }
                },
            )
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
        // P-001 markdown 渲染（标题/列表/代码块/表格 + 行内粗体与行内代码）；
        // 长文折叠仍取 LongTextDisplay 的可见文本，折叠/展开语义不变
        MarkdownText(text = LongTextDisplay.displayText(text, expanded, streaming))
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
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZAccent,
                )
            }
        }
    }
}

// MARK: - 思考折叠块（折叠态只留摘要头；中断态显式标出）

@Composable
private fun ThinkingBlock(
    text: String,
    state: ThinkingState,
    durationSeconds: Double? = null,
) {
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
                    // 完成态：有实测时长显示「思考 · N 秒」（web 同口径），否则退化为字数摘要
                    ThinkingState.done -> if (durationSeconds != null && durationSeconds >= 1.0) {
                        stringResource(R.string.chat_thinking_seconds, durationSeconds.toInt())
                    } else {
                        stringResource(
                            R.string.chat_thinking_chars,
                            LongTextDisplay.totalCount(text),
                        )
                    }
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
        // web reasoning 折叠态预览：流式中显示最后一行非空文本（单行、渐隐）
        if (!expanded && state == ThinkingState.streaming) {
            val preview = remember(text) {
                text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
            }
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = BiuZText3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = BiuZSpacing.md, end = BiuZSpacing.md, bottom = BiuZSpacing.sm),
                )
            }
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

// MARK: - 工具调用卡（头 44 最小热区，展开体 mono 弱底；iOS ToolCallCardView 直译）

@Composable
private fun ToolCallCard(call: ToolCall, onRetry: () -> Unit) {
    // web ToolCallBlocks 移动端同构（2026-11-23）：工具族身份 + 动作词 + 主/副文案 +
    // 展开体（描述 / 文件路径 / 命令块 / 参数 / 输出 / 错误）。**不再裸显示 inputText JSON**；
    // 不渲染 per-tool 重试（web 无此入口；失败以错误块如实展示）。
    @Suppress("UNUSED_PARAMETER")
    val unusedRetry = onRetry
    var expanded by remember { mutableStateOf(false) }
    val model = remember(call) { ToolDisplay.build(call) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .testTag("05-tool-call"),
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
                text = toolFamilyGlyph(model.family),
                fontSize = 11.sp,
                color = BiuZText3,
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            Text(
                text = toolActionLabel(model),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = when (model.phase) {
                    ToolPhase.Failed -> BiuZOrange
                    else -> BiuZText
                },
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            Text(
                text = model.primary.ifBlank {
                    model.toolName.ifBlank { stringResource(R.string.chat_tool_fallback_name) }
                },
                style = TextStyle(
                    fontSize = 12.sp,
                    fontFamily = if (model.family == ToolFamily.Shell || model.filePath != null) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                ),
                color = BiuZText2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (model.addedLines != null || model.removedLines != null) {
                Spacer(Modifier.size(BiuZSpacing.xs))
                Text(
                    text = toolDiffLabel(model),
                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                )
            }
            Spacer(Modifier.size(BiuZSpacing.xs))
            when (model.phase) {
                ToolPhase.Pending, ToolPhase.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = BiuZGreen,
                )
                ToolPhase.Done -> Text("✓", color = BiuZBlue, fontSize = 12.sp)
                ToolPhase.Failed -> Text("✕", color = BiuZOrange, fontSize = 12.sp)
                ToolPhase.Stopped -> Text("■", color = BiuZText3, fontSize = 10.sp)
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
                model.secondary?.let { description ->
                    Text(
                        text = description,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                        color = BiuZText2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                }
                model.filePath?.let { path ->
                    Text(
                        text = path,
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
                    Spacer(Modifier.height(BiuZSpacing.sm))
                }
                model.command?.let { command ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "$ ",
                            style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            color = BiuZGreen,
                        )
                        Text(
                            text = command,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp,
                            ),
                            color = BiuZText,
                        )
                    }
                }
                model.parameters?.let { parameters ->
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    ToolBodyBlock(
                        title = stringResource(R.string.chat_tool_parameters),
                        body = parameters,
                        color = BiuZText2,
                    )
                }
                model.output?.let { output ->
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    ToolBodyBlock(
                        title = stringResource(R.string.chat_tool_output),
                        body = output,
                        color = BiuZText2,
                    )
                }
                model.errorText?.let { error ->
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    ToolBodyBlock(
                        title = stringResource(R.string.chat_tool_failed),
                        body = error,
                        color = BiuZOrange,
                    )
                }
            }
        }
    }
}

/** 展开体块（输出/参数/错误共用：标题 + 限高竖向滚动 mono 文本）。 */
@Composable
private fun ToolBodyBlock(title: String, body: String, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = BiuZText3,
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = body,
            style = TextStyle(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
            ),
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** 工具族字形（web 各 renderer 图标字符对位；无矢量资产，沿用字符口径）。 */
private fun toolFamilyGlyph(family: ToolFamily): String = when (family) {
    ToolFamily.Shell -> "$"
    ToolFamily.FileRead -> "▤"
    ToolFamily.FileWrite -> "✎"
    ToolFamily.Search -> "⌕"
    ToolFamily.WebFetch -> "◉"
    ToolFamily.Mcp -> "⚙"
    ToolFamily.Agent -> "🤖"
    ToolFamily.Todo -> "☑"
    ToolFamily.Workflow -> "◆"
    ToolFamily.Generic -> "•"
}

/** 动作词（族 × 阶段 → 资源；web kind label 的进行/完成时态口径）。 */
@Composable
private fun toolActionLabel(model: ToolDisplayModel): String = when (model.phase) {
    ToolPhase.Pending -> stringResource(R.string.chat_tool_pending)
    ToolPhase.Running -> when (model.family) {
        ToolFamily.Shell -> stringResource(R.string.chat_tool_running_shell)
        ToolFamily.FileRead -> stringResource(R.string.chat_tool_running_read)
        ToolFamily.FileWrite -> stringResource(R.string.chat_tool_running_write)
        ToolFamily.Search -> stringResource(R.string.chat_tool_running_search)
        ToolFamily.WebFetch -> stringResource(R.string.chat_tool_running_fetch)
        ToolFamily.Agent, ToolFamily.Workflow -> stringResource(R.string.chat_tool_running_agent)
        ToolFamily.Mcp, ToolFamily.Todo, ToolFamily.Generic -> stringResource(R.string.chat_tool_running_call)
    }
    ToolPhase.Done -> when (model.family) {
        ToolFamily.Shell -> stringResource(R.string.chat_tool_done_shell)
        ToolFamily.FileRead -> stringResource(R.string.chat_tool_done_read)
        ToolFamily.FileWrite -> stringResource(R.string.chat_tool_done_write)
        ToolFamily.Search -> stringResource(R.string.chat_tool_done_search)
        ToolFamily.WebFetch -> stringResource(R.string.chat_tool_done_fetch)
        ToolFamily.Agent, ToolFamily.Workflow -> stringResource(R.string.chat_tool_done_agent)
        ToolFamily.Mcp, ToolFamily.Todo, ToolFamily.Generic -> stringResource(R.string.chat_tool_done_call)
    }
    ToolPhase.Failed -> stringResource(R.string.chat_tool_failed)
    ToolPhase.Stopped -> stringResource(R.string.chat_tool_stopped)
}

private fun toolDiffLabel(model: ToolDisplayModel): String {
    val added = model.addedLines
    val removed = model.removedLines
    return buildString {
        if (added != null) append("+$added")
        if (removed != null) {
            if (isNotEmpty()) append(" ")
            append("-$removed")
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
            style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
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
                                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                                color = BiuZText,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = item.path
                                    ?: stringResource(R.string.chat_userselect_source_dialog),
                                style = TextStyle(fontSize = 12.sp),
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
        // userInput / elicitation / question → 提问卡（蓝描边）
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

/** 决议失败行（错误路径上屏：服务层拒因原文/未送达/未知态三态；不撤卡、控件恢复）。 */
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

// MARK: 权限族（默认两档 chips + 批准/拒绝；四族混合态方向钮禁用；全非四族单选即决）

@Composable
private fun PermissionInteractionCard(
    interaction: RemotePendingInteraction,
    actions: InteractionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var deciding by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ChatViewModel.InteractionFailure?>(null) }
    // scope 两档（once/always）；四族命中推导可见性
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
                // 全非四族 id：chips 即选项（点选即发，自带方向语义；两步矩阵整组隐藏）
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
                // 方向钮禁用规则（混合态核心）：选中 scope 的某方向不在四族 → 该钮禁用；
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

/** 计划族（{action:"accept"|"decline"}；cancel 不设专用按钮——snooze 语义独立）。 */
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

/** 提问族（options 点选即发 {optionId}；输入行 {freeText}；蓝描边）。 */
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
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
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

/** 44pt 热区决定钮（禁用 = 弱底灰字；批准 accent 实底 / 拒绝弱底）。 */
@Composable
private fun DecisionButton(
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 决议 = 主 CTA：按压实 + 轻击触觉（motion-spec 的「印随批准权走」在触屏的
    // 手指反馈对应物——按下就有回应，落印在 ACK 之后）
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = Modifier
            .ctaPress(interaction)
            .clip(BiuZRadius.medium)
            .background(if (enabled) container else BiuZBgInput)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
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

// MARK: - 工作流完成卡（桌面 WorkflowCompletionCard 同款；iOS MessageViews.swift:1885-2083 直译）
//
// 三段收据式：表头（种类词+run 名+状态灯）→ 交付物行/产物索引（通知载荷 artifacts，
// join 活投影补 bytes/条目）→ 四格数字（时间/tokens/子代理/阶段）。数字的诚实：拿不到
// 的格写「—」不写 0【实证·上游仓 WorkflowCompletionCard.tsx:13 注释 +
// ConversationWorkflowCompletion.tsx:39-48】。

@Composable
fun WorkflowCompletionCardView(
    card: WorkflowCompletionCard,
    /** 活投影 run（按 runId 对位；null = tokens/子代理/阶段三格「—」）。 */
    run: WorkflowRunSummary?,
    /** ⤢ 打开工作流面板（宿主门控接线；null = 不渲染入口）。 */
    onOpenRun: (() -> Unit)?,
    /** 产物点按 → 内容详情 sheet（markdown/plain 渲染全文；其余类型 sheet 内如实标注）。 */
    onOpenArtifact: ((WorkflowCompletionArtifact) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .padding(BiuZSpacing.md)
            .testTag("05-wf-completion"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
    ) {
        // 表头：flowchart 图标 + 「工作流已完成」+ run 名，右侧绿点「已完成」+ [⤢]
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
            Text(text = "⑂", fontSize = 14.sp, color = BiuZText3)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.wf_completion_title),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZText,
                )
                Text(
                    text = card.name,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    color = BiuZText2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
                Box(modifier = Modifier.size(7.dp).background(BiuZAccent, CircleShape))
                Text(
                    text = stringResource(R.string.wf_completion_done),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = BiuZAccent,
                )
                if (onOpenRun != null) {
                    Text(
                        text = "⤢",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BiuZText3,
                        modifier = Modifier
                            .testTag("05-wf-completion-open")
                            .combinedClickableSafe(enabled = true, onClick = onOpenRun)
                            .padding(6.dp),
                    )
                }
            }
        }
        // 交付物行/产物索引（primary 大卡置顶 + 其余索引行；truncated 如实声明）
        val primary = card.artifacts.firstOrNull { it.primary }
        val rest = card.artifacts.filter { !it.primary }
        if (primary != null || rest.isNotEmpty() || card.truncated) {
            Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                primary?.let { artifact ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BiuZRadius.small)
                            .background(BiuZBgElevated)
                            .combinedClickableSafe(enabled = onOpenArtifact != null) {
                                onOpenArtifact?.invoke(artifact)
                            }
                            .padding(BiuZSpacing.sm)
                            .testTag("05-wf-completion-artifact"),
                        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
                    ) {
                        Text(
                            text = artifact.title ?: artifact.id,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            color = BiuZText,
                        )
                        artifact.description?.takeIf { it.isNotEmpty() }?.let {
                            Text(
                                text = it,
                                style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
                                color = BiuZText3,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = artifactMetaText(artifact, run),
                            style = TextStyle(fontSize = 12.sp),
                            color = BiuZText3,
                        )
                    }
                }
                rest.forEach { artifact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .combinedClickableSafe(enabled = onOpenArtifact != null) {
                                onOpenArtifact?.invoke(artifact)
                            }
                            .padding(vertical = BiuZSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                    ) {
                        Text(text = "☰", fontSize = 11.sp, color = BiuZText3)
                        Text(
                            text = artifact.title ?: artifact.id,
                            style = TextStyle(fontSize = 13.sp),
                            color = BiuZText2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = artifactMetaText(artifact, run),
                            style = TextStyle(fontSize = 12.sp),
                            color = BiuZText3,
                        )
                    }
                }
                if (card.truncated) {
                    Text(
                        text = stringResource(R.string.wf_truncated_artifacts),
                        style = TextStyle(fontSize = 12.sp),
                        color = BiuZText3,
                    )
                }
            }
        }
        // 四格数字（桌面 figures 同序：时间/tokens/子代理/阶段；拿不到写「—」不写 0）
        Row(modifier = Modifier.fillMaxWidth()) {
            Figure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.wf_stat_time),
                value = completionDurationText(card.durationMs),
            )
            Figure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.wf_stat_tokens),
                value = run?.spentTokens?.let { compactCount(it) },
            )
            Figure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.wf_stat_subagents),
                value = run?.actors?.size?.toString(),
            )
            Figure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.wf_stat_phases),
                value = run?.phaseCount?.toString(),
            )
        }
    }
}

/** 种类词 + 大小/条目（bytes/itemCount 来自活投影 join，缺席只显种类词）（iOS :2019-2028）。 */
private fun artifactMetaText(artifact: WorkflowCompletionArtifact, run: WorkflowRunSummary?): String {
    val parts = mutableListOf(kindLabel(artifact.kind))
    val joined = run?.artifactBriefs?.firstOrNull { it.id == artifact.id }
    (joined?.bytes ?: artifact.bytes)?.let { parts.add(byteText(it)) }
    (joined?.itemCount ?: artifact.itemCount)?.let { parts.add("${it} 条") }
    return parts.joinToString(" · ")
}

/** 四格数字单格（值缺席显「—」；等宽布局四格均分）。 */
@Composable
private fun Figure(modifier: Modifier = Modifier, label: String, value: String?) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = value ?: "—",
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = BiuZText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp),
            color = BiuZText3,
        )
    }
}

/** tokens 紧凑写法（上游 formatCompactCount 逐字移植：固定位数让四格等宽数字对齐）。 */
fun compactCount(count: Int): String = when {
    count < 1_000 -> "$count"
    count < 1_000_000 -> String.format("%.1fk", count / 1_000.0)
    else -> String.format("%.2fM", count / 1_000_000.0)
}

/** 字节格式化（iOS byteText :2066-2070 同构）。 */
fun byteText(bytes: Int): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> String.format("%.1f KB", bytes / 1_000.0)
    else -> String.format("%.1f MB", bytes / 1_000_000.0)
}

/** 毫秒时长格式化（「N 小时 M 分钟」桌面同款；P2ExtrasViews.durationText :634-643 直译）。 */
fun completionDurationText(ms: Int?): String? {
    if (ms == null || ms <= 0) return null
    val minutes = Math.round(ms / 60_000.0).toInt()
    val hours = minutes / 60
    return if (hours >= 1) "$hours 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
}

/** 产物种类词（六态闭集【实证·上游仓 workflow-row-meta.ts:60】；文档=markdown 桌面同款）。 */
fun kindLabel(kind: String): String = when (kind) {
    "markdown" -> "文档"
    "chart" -> "图表"
    "table" -> "表格"
    "metrics" -> "指标"
    "board" -> "看板"
    else -> "文件"
}


/**
 * P-019 变更摘要条（web `MessageChangeSummaryPanel.tsx:73-140` 折叠头）：
 * 箭头 + `N 个文件已更改` + `+A`(绿) / `-D`(红)。数据来自 turnHeader 行 fileChanges
 * （逐文件明细在持久化快照里，行流不带 → 不渲染展开；`reverted` 轮显「已撤销」）。
 */
@Composable
private fun MessageChangeSummaryBar(turnChanges: cn.biuz.android.protocol.TurnFileChanges) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp)
            .clip(BiuZRadius.medium)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .background(BiuZBgCard)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("05-change-summary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "▸", fontSize = 12.sp, color = BiuZText3)
        Text(
            text = turnChanges.files.toString() + " 个文件已更改",
            fontSize = 13.sp,
            color = BiuZText,
            modifier = Modifier.weight(1f),
        )
        if (turnChanges.reverted) {
            Text(text = "已撤销", fontSize = 12.sp, color = BiuZText3)
        } else {
            Text(text = "+" + turnChanges.additions, fontSize = 13.sp, color = BiuZGreen)
            Text(text = "-" + turnChanges.deletions, fontSize = 13.sp, color = BiuZRed)
        }
    }
}

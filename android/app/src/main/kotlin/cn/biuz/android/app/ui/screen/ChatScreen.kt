package cn.biuz.android.app.ui.screen

// 会话流（屏 05；iOS ChatView 连接态直译的对位）。渲染权威规则 = 协议文档 §10.1；
// 降级规格 = docs/对齐修复与降级体验设计.md §3（交互卡应答）/§5（发送失败三件套）。
//
// 结构（自上而下）：
// - 顶栏（返回 + 标题）；
// - 消息流 LazyColumn（weight 1f；key=message.id 增量复用；新行到达滚底）；
// - 读面失败行（last-good 与失败文案共存 §5-12）；
// - **常驻交互卡区**：composer 上方固定（不随消息滚动顶走，AGENTS §6 审批卡可用性口径），
//   **限高 240dp + 内部滚动（三卡统一）**——无界卡 + 键盘会把 chips 物理顶到键盘下
//   （iOS v1.36④ 实证 P0：chip y=513 < 键盘顶、hittable=false，点击从未发出）；
// - composer（错误行 + 协作模式 chip + 输入框 + 发送）；imePadding 承接键盘避让。
//
// 换源重绑：VM 以 store 实例重绑（attach 幂等），surface.epoch 变化即新实例链路。
// 面板呈现：撤焦先行 + 120ms 延迟（openComposerPanel；焦点在场时面板与键盘收起转场
// 互撞被吞 = 真机「点了没反应」根因）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.LocalAppSession
import cn.biuz.android.app.ui.chat.ChatViewModel
import cn.biuz.android.app.ui.chat.InteractionActions
import cn.biuz.android.app.ui.chat.InteractionCard
import cn.biuz.android.app.ui.chat.MessageRow
import cn.biuz.android.app.ui.chat.ModelOptionSection
import cn.biuz.android.app.ui.chat.ModelRefetchRow
import cn.biuz.android.app.ui.chat.QueueBar
import cn.biuz.android.app.ui.chat.ThoughtOptionSection
import cn.biuz.android.app.ui.chat.freeTextAnswer
import cn.biuz.android.app.ui.chat.optionAnswer
import cn.biuz.android.app.ui.chat.permissionAnswer
import cn.biuz.android.app.ui.chat.planAnswer
import cn.biuz.android.store.ModelSelectionInfo
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import kotlinx.coroutines.launch

/** 常驻交互卡区限高（三卡统一；iOS v1.36④ maxHeight 240 同口径）。 */
private val INTERACTION_ZONE_MAX_HEIGHT = 240.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = LocalAppSession.current
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager: FocusManager = LocalFocusManager.current

    // VM 宿主 = 路由会话；store 换源（StoreSurface.epoch 换装配世代 → conversation 新实例）
    // 走 attach 重绑（同实例幂等短路）
    val viewModel = remember(sessionId) { ChatViewModel(conversationId = sessionId, scope = scope) }
    DisposableEffect(sessionId) {
        onDispose { viewModel.detach() } // 离开会话：撤面板/提示行（真退订交 store 租约池）
    }
    LaunchedEffect(surface.conversation) {
        viewModel.attach(surface.conversation)
    }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val loadFailure by viewModel.loadFailure.collectAsStateWithLifecycle()
    val pendingInteractions by viewModel.pendingInteractions.collectAsStateWithLifecycle()
    val decidedIds by viewModel.decidedInteractionIds.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sending by viewModel.isSending.collectAsStateWithLifecycle()
    val sendFailureReason by viewModel.sendFailureReason.collectAsStateWithLifecycle()
    val slashHint by viewModel.slashHint.collectAsStateWithLifecycle()
    val collabMode by viewModel.collaborationMode.collectAsStateWithLifecycle()
    val collabModeFailure by viewModel.collaborationModeFailure.collectAsStateWithLifecycle()
    val composerPanel by viewModel.composerPanel.collectAsStateWithLifecycle()
    val modelSelection by viewModel.modelSelection.collectAsStateWithLifecycle()
    val thoughtLevels by viewModel.thoughtLevels.collectAsStateWithLifecycle()
    val modelSwitchFailure by viewModel.modelSwitchFailure.collectAsStateWithLifecycle()
    val queueInfoState by viewModel.queueInfo.collectAsStateWithLifecycle()
    val queueActionFailure by viewModel.queueActionFailure.collectAsStateWithLifecycle()
    val readOnly = viewModel.isReadOnly

    val listState = rememberLazyListState()
    // 新行到达滚底（乐观回显 + 服务端行都走同一投影）
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // 交互卡应答（A-3 对象分形；失败/未知态由卡内错误行如实呈现不撤卡）
    val actions = remember(viewModel) {
        InteractionActions(
            onPermissionOption = { interaction, optionId ->
                viewModel.resolveInteraction(interaction, permissionAnswer(optionId))
            },
            onPlanAction = { interaction, action ->
                viewModel.resolveInteraction(interaction, planAnswer(action))
            },
            onQuestionFreeText = { interaction, text ->
                viewModel.resolveInteraction(interaction, freeTextAnswer(text))
            },
            onQuestionOption = { interaction, option ->
                viewModel.resolveInteraction(interaction, optionAnswer(option.id))
            },
            onHookTrust = { interaction, itemIds ->
                viewModel.respondHookReview(interaction, itemIds)
            },
        )
    }
    val visibleInteractions = remember(pendingInteractions, decidedIds) {
        pendingInteractions.filter { it.id !in decidedIds }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cn.biuz.android.app.ui.theme.BiuZBg)
            .statusBarsPadding()
            .imePadding(), // 键盘避让：composer 与其上方 chips/交互卡整体升到键盘上方
    ) {
        // MARK: 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.chat_back),
                    tint = BiuZText,
                )
            }
            Text(
                text = stringResource(R.string.chat_title),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = BiuZText,
            )
        }

        // MARK: 消息流
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = BiuZSpacing.lg,
                vertical = BiuZSpacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
        ) {
            if (messages.isEmpty() && loadFailure == null) {
                item {
                    Text(
                        text = stringResource(R.string.chat_empty_hint),
                        style = TextStyle(fontSize = 13.sp),
                        color = BiuZText3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    onRetryToolCall = { origin: ChatMessage, call: ToolCall ->
                        // G-015：retryTurn 经 store；rowId 来自行合成 id（"row-<n>"），
                        // entityId 由卡片渲染门槛保证在场（无游标 store 拒发如实反馈）
                        scope.launch {
                            val rowId = origin.id.removePrefix("row-").toIntOrNull()
                                ?: return@launch
                            viewModel.attachedStore?.retryTurn(
                                conversationId = viewModel.conversationId,
                                rowId = rowId,
                                entityId = call.entityId,
                            )
                        }
                    },
                    // P1-3 编辑重发（null 回调 = 未接线不渲染入口；未连接态 store 返回
                    // null → VM 判 Undelivered 如实提示，禁假成功）
                    onEditResend = { origin: ChatMessage, newText: String ->
                        viewModel.editUserQuery(origin, newText)
                    },
                )
            }
        }

        // 读面失败三态：与 last-good 共存（§5-12；空会话与失败由此区分）
        loadFailure?.let { failure ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = failure,
                    style = TextStyle(fontSize = 12.sp),
                    color = cn.biuz.android.app.ui.theme.BiuZOrange,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.chat_load_retry),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZAccent,
                    modifier = Modifier
                        .clickable {
                            // 读面失败重试 = 主动全量 resync（服务端重发快照；attach 同实例幂等
                            // 不重拉，重试必须走 resync 通道）
                            scope.launch {
                                runCatching {
                                    surface.conversation.triggerResync(viewModel.conversationId)
                                }
                            }
                        }
                        .padding(BiuZSpacing.sm),
                )
            }
        }

        // MARK: 常驻交互卡区（composer 上方固定 · 限高 240dp + 内部滚动，三卡统一）
        if (visibleInteractions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = INTERACTION_ZONE_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BiuZSpacing.lg)
                    .padding(bottom = BiuZSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                visibleInteractions.forEach { interaction ->
                    InteractionCard(interaction = interaction, actions = actions)
                }
            }
        }

        // MARK: 排队消息条（桌面同构：紧贴 composer 上方，无排队不渲染）
        queueInfoState?.let { queue ->
            QueueBar(
                queue = queue,
                failureText = queueActionFailure?.let { failure ->
                    when (failure) {
                        is ChatViewModel.QueueActionFailure.Rejected -> failure.text
                        ChatViewModel.QueueActionFailure.Undelivered ->
                            stringResource(R.string.queue_fail_undelivered)
                        ChatViewModel.QueueActionFailure.UnknownState ->
                            stringResource(R.string.queue_fail_unknown)
                    }
                },
                onSendNow = { id -> scope.launch { viewModel.sendQueuedNow(id) } },
                onEditSave = { id, text -> scope.launch { viewModel.editQueueItem(id, text) } },
                onMoveUp = { id -> scope.launch { viewModel.moveQueueItemUp(id) } },
                onDelete = { id -> scope.launch { viewModel.deleteQueueItem(id) } },
                onToggleAutoDrain = { enabled -> scope.launch { viewModel.setAutoDrain(enabled) } },
            )
        }

        // MARK: composer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(BiuZBgCard)
                .padding(horizontal = BiuZSpacing.lg)
                .padding(top = BiuZSpacing.sm, bottom = BiuZSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            // 发送失败持久错误行（U-6：不随 3s 消失；重试钮；拒因原文透传）
            sendFailureReason?.let { reason ->
                ComposerErrorRow(
                    text = if (reason.isEmpty()) {
                        stringResource(R.string.chat_send_undelivered_generic)
                    } else {
                        stringResource(R.string.chat_send_undelivered_reason, reason)
                    },
                    actionLabel = stringResource(R.string.composer_retry),
                    onAction = { scope.launch { viewModel.retrySend() } },
                )
            }
            // 协作模式切换失败/未知态常驻错误行（05-composer-mode-fail 同位；禁 3s 闪过）
            collabModeFailure?.let { failure ->
                ComposerErrorRow(
                    text = when (failure) {
                        is ChatViewModel.CollabModeFailure.Rejected ->
                            stringResource(R.string.composer_mode_fail_rejected, failure.text)
                        ChatViewModel.CollabModeFailure.UnknownAckState ->
                            stringResource(R.string.composer_mode_fail_unknown)
                    },
                )
            }
            // 模型/思考切换失败/未知态常驻错误行（05-composer-model-fail 同位；
            // v1.37 口径：失败/未知态常驻，权威成功即清）
            modelSwitchFailure?.let { failure ->
                ComposerErrorRow(
                    text = when (failure) {
                        is ChatViewModel.ModelSwitchFailure.Rejected ->
                            stringResource(R.string.composer_model_fail_rejected, failure.text)
                        ChatViewModel.ModelSwitchFailure.ProviderUnavailable ->
                            stringResource(R.string.composer_model_fail_provider_unavailable)
                        ChatViewModel.ModelSwitchFailure.Undelivered ->
                            stringResource(R.string.composer_model_fail_undelivered)
                        ChatViewModel.ModelSwitchFailure.UnknownAckState ->
                            stringResource(R.string.composer_model_fail_unknown)
                    },
                )
            }
            // 斜杠反馈行（3s 自动清除；Raw = 服务层原文直出）
            slashHint?.let { hint ->
                Text(
                    text = when (hint) {
                        ChatViewModel.SlashHint.PlanBlocked ->
                            stringResource(R.string.slash_plan_blocked)
                        ChatViewModel.SlashHint.PlanSwitched ->
                            stringResource(R.string.slash_plan_switched)
                        is ChatViewModel.SlashHint.Raw -> hint.text
                    },
                    style = TextStyle(fontSize = 12.sp),
                    color = BiuZText2,
                )
            }
            // 协作模式 chip + 模型/思考胶囊（remoteChips 同构：面板统一撤焦先行 +
            // 120ms 延迟呈现；模型胶囊恒开面板——空清单呈「重新获取」行不死路）
            Row {
                ModeChip(
                    label = collaborationModeLabel(collabMode),
                    onClick = {
                        viewModel.openComposerPanel(
                            ChatViewModel.ComposerPanelKind.Collaboration,
                        ) {
                            // 撤焦先行：FocusState 通道唯一稳定撤焦（iOS :2177-2179）；
                            // 面板延迟 120ms 呈现由 VM 承担
                            focusManager.clearFocus(force = true)
                        }
                    },
                )
                Spacer(Modifier.size(BiuZSpacing.sm))
                ModelChip(
                    label = modelSelection?.activeModel ?: "--",
                    onClick = {
                        viewModel.openComposerPanel(ChatViewModel.ComposerPanelKind.Model) {
                            focusManager.clearFocus(force = true)
                        }
                    },
                )
                Spacer(Modifier.size(BiuZSpacing.sm))
                ThoughtChip(
                    label = stringResource(
                        R.string.composer_thought_prefix,
                        modelSelection?.activeThoughtLevel ?: "--",
                    ),
                    onClick = {
                        viewModel.openComposerPanel(ChatViewModel.ComposerPanelKind.Thought) {
                            focusManager.clearFocus(force = true)
                        }
                    },
                )
                if (readOnly) {
                    Spacer(Modifier.size(BiuZSpacing.sm))
                    Text(
                        text = stringResource(R.string.composer_readonly),
                        style = TextStyle(fontSize = 11.sp),
                        color = BiuZText3,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
            // 输入行
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = draft,
                    onValueChange = viewModel::updateDraft,
                    textStyle = TextStyle(fontSize = 14.5.sp, color = BiuZText, lineHeight = 20.sp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(BiuZRadius.medium)
                        .background(BiuZBgInput)
                        .border1()
                        .padding(horizontal = BiuZSpacing.md, vertical = 10.dp),
                    maxLines = 5,
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text(
                                text = stringResource(R.string.composer_hint),
                                style = TextStyle(fontSize = 14.5.sp),
                                color = BiuZText3,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.size(BiuZSpacing.sm))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (draft.isNotBlank() && !sending) BiuZAccent else BiuZBgInput)
                        .clickable(enabled = draft.isNotBlank() && !sending && !readOnly) {
                            scope.launch { viewModel.send() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (sending) "…" else "↑",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (draft.isNotBlank() && !sending) BiuZOnAccent else BiuZText3,
                    )
                }
            }
        }
    }

    // MARK: composer 面板（协作模式/模型/思考三态分派；单路径关闭 §5-15：
    // onDismiss / 选中成功均走 closeComposerPanel）
    val panel = composerPanel
    if (panel != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeComposerPanel() },
            containerColor = cn.biuz.android.app.ui.theme.BiuZBgElevated,
        ) {
            when (panel) {
                ChatViewModel.ComposerPanelKind.Collaboration -> CollaborationPanel(
                    viewModel = viewModel,
                    collabMode = collabMode,
                    onSwitched = { scope.launch { viewModel.closeComposerPanel() } },
                )
                ChatViewModel.ComposerPanelKind.Model -> ModelPanel(
                    viewModel = viewModel,
                    info = modelSelection,
                )
                ChatViewModel.ComposerPanelKind.Thought -> ThoughtPanel(
                    viewModel = viewModel,
                    info = modelSelection,
                    storeLevels = thoughtLevels,
                )
            }
        }
    }
}

/** 协作模式面板（原面板体抽出；选中成功由宿主单路径关闭）。 */
@Composable
private fun CollaborationPanel(
    viewModel: ChatViewModel,
    collabMode: String,
    onSwitched: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiuZSpacing.lg)
            .padding(bottom = BiuZSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.composer_mode_panel_title),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = BiuZText,
        )
        Text(
            text = stringResource(R.string.composer_mode_panel_hint),
            style = TextStyle(fontSize = 12.sp),
            color = BiuZText3,
        )
        ChatViewModel.COLLABORATION_MODES.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .clickable {
                        scope.launch {
                            val failure = viewModel.switchCollaborationMode(mode)
                            if (failure == null) onSwitched()
                        }
                    }
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = collaborationModeLabel(mode),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = BiuZText,
                    modifier = Modifier.weight(1f),
                )
                if (mode == collabMode) {
                    Text(text = "✓", color = BiuZAccent, fontSize = 14.sp)
                }
            }
        }
        // 协作模式失败/未知态常驻错误行（面板内在场呈现，不撤面板）
        viewModel.collaborationModeFailure.collectAsStateWithLifecycle().value?.let { failure ->
            Text(
                text = when (failure) {
                    is ChatViewModel.CollabModeFailure.Rejected -> failure.text
                    ChatViewModel.CollabModeFailure.UnknownAckState ->
                        stringResource(R.string.composer_mode_fail_unknown)
                },
                style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                color = cn.biuz.android.app.ui.theme.BiuZOrange,
            )
        }
    }
}

/**
 * 模型面板（套餐分节 + 复合键勾选；恒开面板——空清单呈「重新获取」行承载重试，
 * 拒开+一次性重试+3s 提示的旧路在 getView 持续失败时表现为「点了没反应」死胡同）。
 * 【平台偏差】iOS「重新获取」经 onDismiss 单路径重开（present-while-dismissing 竞态）；
 * Compose 面板不随重取关闭，行点击后就地刷新内容，竞态不存在。
 */
@Composable
private fun ModelPanel(
    viewModel: ChatViewModel,
    info: ModelSelectionInfo?,
) {
    val scope = rememberCoroutineScope()
    val failure by viewModel.modelSwitchFailure.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()) // 模型清单可超一屏（iOS detent medium/large 同需求）
            .padding(horizontal = BiuZSpacing.lg)
            .padding(bottom = BiuZSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.composer_model_panel_title),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = BiuZText,
        )
        if (info == null || !info.hasSelectableModels) {
            ModelRefetchRow(
                onRefetch = { scope.launch { viewModel.refetchModelSelection() } },
            )
        } else {
            // 会话侧勾跟随桌面绑定（乐观落 chip 后 activeModel/activeProviderId 即新值；
            // 无独立 pickedRowKey——点选成功即关面板，失败勾不动）
            ModelOptionSection(
                info = info,
                pickedRowKey = null,
                onPickModel = { rowKey ->
                    scope.launch {
                        val switchFailure = viewModel.switchModel(rowKey)
                        if (switchFailure == null) viewModel.closeComposerPanel()
                    }
                },
            )
        }
        PanelFailureLine(failure)
    }
}

/** 思考档面板（词表按当前模型查询，缺席退化静态梯；switchThought 沿用当前绑定模型）。 */
@Composable
private fun ThoughtPanel(
    viewModel: ChatViewModel,
    info: ModelSelectionInfo?,
    storeLevels: List<String>,
) {
    val scope = rememberCoroutineScope()
    val failure by viewModel.modelSwitchFailure.collectAsStateWithLifecycle()
    val levels = storeLevels.takeIf { it.isNotEmpty() }
        ?: info?.thoughtLevels?.takeIf { it.isNotEmpty() }
        ?: ChatViewModel.FALLBACK_THOUGHT_LEVELS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BiuZSpacing.lg)
            .padding(bottom = BiuZSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.composer_thought_panel_title),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = BiuZText,
        )
        ThoughtOptionSection(
            levels = levels,
            pickedLevel = null,
            activeLevel = info?.activeThoughtLevel,
            onPickThought = { level ->
                scope.launch {
                    val switchFailure = viewModel.switchThought(level)
                    if (switchFailure == null) viewModel.closeComposerPanel()
                }
            },
        )
        PanelFailureLine(failure)
    }
}

/** 面板内失败/未知态错误行（常驻：拒因原文/未送达/未知三态如实呈现，不撤面板）。 */
@Composable
private fun PanelFailureLine(failure: ChatViewModel.ModelSwitchFailure?) {
    failure?.let {
        Text(
            text = when (it) {
                is ChatViewModel.ModelSwitchFailure.Rejected -> it.text
                ChatViewModel.ModelSwitchFailure.ProviderUnavailable ->
                    stringResource(R.string.composer_model_fail_provider_unavailable)
                ChatViewModel.ModelSwitchFailure.Undelivered ->
                    stringResource(R.string.composer_model_fail_undelivered)
                ChatViewModel.ModelSwitchFailure.UnknownAckState ->
                    stringResource(R.string.composer_model_fail_unknown)
            },
            style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            color = cn.biuz.android.app.ui.theme.BiuZOrange,
        )
    }
}

/** 协作模式档位 → 展示名（§7.2 v1.12 四档词表的移动端呈现）。 */
@Composable
private fun collaborationModeLabel(mode: String): String = when (mode) {
    "plan" -> stringResource(R.string.composer_mode_plan)
    "build" -> stringResource(R.string.composer_mode_build)
    "edit" -> stringResource(R.string.composer_mode_edit)
    "yolo" -> stringResource(R.string.composer_mode_yolo)
    else -> mode
}

/** 协作模式 chip（44pt 最小热区；触发面板经 openComposerPanel 撤焦+延迟）。 */
@Composable
private fun ModeChip(label: String, onClick: () -> Unit) {
    Chip(label = label, onClick = onClick)
}

/** 模型胶囊（05-chip-model 同位；恒开面板——空清单「重新获取」行承载重试）。 */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Chip(label = label, onClick = onClick)
}

/** 思考档胶囊（05-chip-thought 同位；「思考·档位」标签与 iOS 同构）。 */
@Composable
private fun ThoughtChip(label: String, onClick: () -> Unit) {
    Chip(label = label, onClick = onClick)
}

/** 胶囊基形（44pt 最小热区；弱底 + 描边 + 下拉箭头）。 */
@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BiuZBgInput)
            .border1()
            .clickable(onClick = onClick)
            .padding(horizontal = BiuZSpacing.md, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = BiuZText,
            )
            Spacer(Modifier.width(4.dp))
            Text(text = "▾", fontSize = 11.sp, color = BiuZText3)
        }
    }
}

/** composer 错误行（弱底红字 + 可选动作钮；持久语义不自动消失）。 */
@Composable
private fun ComposerErrorRow(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.small)
            .background(cn.biuz.android.app.ui.theme.BiuZRedDim)
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "⚠",
            fontSize = 12.sp,
            color = cn.biuz.android.app.ui.theme.BiuZRed,
        )
        Spacer(Modifier.size(BiuZSpacing.sm))
        Text(
            text = text,
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            color = cn.biuz.android.app.ui.theme.BiuZRed,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = cn.biuz.android.app.ui.theme.BiuZRed,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(BiuZSpacing.xs),
            )
        }
    }
}

/** 输入框/panel 行描边（BiuZBorderStrong 1dp + 卡片圆角）。 */
private fun Modifier.border1(): Modifier = this.border(1.dp, BiuZBorderStrong, BiuZRadius.medium)

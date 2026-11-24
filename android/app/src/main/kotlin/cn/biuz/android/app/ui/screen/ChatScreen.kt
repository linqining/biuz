package cn.biuz.android.app.ui.screen

// 会话流（iOS ChatView 连接态直译的对位）。渲染权威规则 = 协议文档 §10.1；
// 降级呈现：交互卡应答、发送失败如实上屏（失败不静默，AGENTS §5-12）。
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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.LocalAppSession
import cn.biuz.android.app.ui.chat.AttachmentFullscreenState
import cn.biuz.android.app.ui.chat.AttachmentFullscreenHost
import cn.biuz.android.app.ui.chat.AttachmentStripContext
import cn.biuz.android.app.ui.chat.ChatViewModel
import cn.biuz.android.app.ui.chat.InteractionActions
import cn.biuz.android.app.ui.chat.InteractionCard
import cn.biuz.android.app.ui.chat.MessageRow
import cn.biuz.android.app.ui.chat.SealStamp
import cn.biuz.android.app.ui.chat.ModelOptionSection
import cn.biuz.android.app.ui.chat.ModelRefetchRow
import cn.biuz.android.app.ui.chat.PendingAttachmentThumb
import cn.biuz.android.app.ui.chat.QueueBar
import cn.biuz.android.app.ui.chat.SessionPanelKind
import cn.biuz.android.app.ui.chat.SessionPanelsSection
import cn.biuz.android.app.ui.chat.SessionWorkflowTrackView
import cn.biuz.android.app.ui.chat.ThoughtOptionSection
import cn.biuz.android.app.ui.chat.freeTextAnswer
import cn.biuz.android.app.ui.chat.optionAnswer
import cn.biuz.android.app.ui.chat.permissionAnswer
import cn.biuz.android.app.ui.chat.planAnswer
import cn.biuz.android.store.ModelSelectionInfo
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.app.ui.theme.BiuZOnSeal
import cn.biuz.android.app.ui.theme.ctaPress
import cn.biuz.android.app.ui.theme.pressableScale
import cn.biuz.android.app.ui.theme.BiuZAccent
import androidx.compose.foundation.interaction.MutableInteractionSource
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBgElevated
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZGreen
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.BiuZSeal
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 常驻交互卡区限高（三卡统一；iOS v1.36④ maxHeight 240 同口径）。 */
private val INTERACTION_ZONE_MAX_HEIGHT = 240.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTaskOutput: () -> Unit = {},
) {
    val session = LocalAppSession.current
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager: FocusManager = LocalFocusManager.current
    // 输入框聚焦态（ghost hint 触发口径：空草稿仅失焦时预测下一条——spec 手机端建议②）
    var composerFocused by remember { androidx.compose.runtime.mutableStateOf(false) }

    // VM 宿主 = 路由会话；store 换源（StoreSurface.epoch 换装配世代 → conversation 新实例）
    // 走 attach 重绑（同实例幂等短路）
    val viewModel = remember(sessionId) { ChatViewModel(conversationId = sessionId, scope = scope) }
    var overflowOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
    // 036 白板浮层（web 侧栏面板的移动对位；零 RPC 纯客户端——语义见 WhiteboardModel 头注）
    var whiteboardOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
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
    // 3.17.2 输入预测 ghost hint（灰字续写，点按采纳；iOS ComposerBar 05-input-hint 同构）
    val inputHint by viewModel.inputHint.collectAsStateWithLifecycle()
    val sending by viewModel.isSending.collectAsStateWithLifecycle()
    val sendFailureReason by viewModel.sendFailureReason.collectAsStateWithLifecycle()
    val feedbackFailure by viewModel.feedbackFailure.collectAsStateWithLifecycle()
    val slashHint by viewModel.slashHint.collectAsStateWithLifecycle()
    val collabMode by viewModel.collaborationMode.collectAsStateWithLifecycle()
    val collabModeFailure by viewModel.collaborationModeFailure.collectAsStateWithLifecycle()
    val composerPanel by viewModel.composerPanel.collectAsStateWithLifecycle()
    val modelSelection by viewModel.modelSelection.collectAsStateWithLifecycle()
    val thoughtLevels by viewModel.thoughtLevels.collectAsStateWithLifecycle()
    val modelSwitchFailure by viewModel.modelSwitchFailure.collectAsStateWithLifecycle()
    val queueInfoState by viewModel.queueInfo.collectAsStateWithLifecycle()
    val queueActionFailure by viewModel.queueActionFailure.collectAsStateWithLifecycle()
    // 面板区投影（chips 行数据源）+ 聊天页顶迷你轨道数据源（行自带 workflowActivity）
    val workflowRuns by viewModel.workflowRuns.collectAsStateWithLifecycle()
    val currentConversation by viewModel.currentConversation.collectAsStateWithLifecycle()
    // 会话面板展开态（SessionPanelsSection 绑定；iOS ChatView expandedPanel 同位——
    // 键盘弹出即收起的正向联动在 iOS 由 FocusState 承担，Compose 输入框聚焦不冲突，
    // 面板体自身滚动即收键盘，此处保留显式收起单路径）
    var expandedPanel by remember { mutableStateOf<cn.biuz.android.app.ui.chat.SessionPanelKind?>(null) }
    // 工作流完成卡产物详情 sheet（点交付物/产物索引行；null = 收起）
    var artifactSheet by remember { mutableStateOf<WorkflowArtifactSheetRequest?>(null) }

    // 待发附件投影（service 是命令式可变对象——版本计数 + 快照读面驱动重组；
    // 提示行镜像随同一版本号重读）
    val uploadsVersion by viewModel.uploadsVersion.collectAsStateWithLifecycle()
    val uploadItems = remember(uploadsVersion) { viewModel.uploadsSnapshot() }
    val uploadHint = remember(uploadsVersion) { viewModel.uploadHint.value }
    val uploadFailureText = remember(uploadsVersion) { viewModel.uploadFailureText() }
    val uploadsBlocksSend = remember(uploadsVersion) { viewModel.uploadsBlocksSend() }

    // 全屏单一宿主状态（图片/视频合流一个位点——v1.32③ 双 cover 遮蔽坑的对位）
    val fullscreenState = remember { AttachmentFullscreenState() }

    // 消息行附件条上下文（store 读面 + 全屏宿主状态；行 refs 空则行内不渲染）
    val attachmentContext = remember(surface.conversation, sessionId, fullscreenState) {
        AttachmentStripContext(
            store = surface.conversation,
            sessionId = sessionId,
            fullscreen = fullscreenState,
        )
    }

    val context = LocalContext.current
    // 附件选择器（filter 含 video：ImageAndVideo——iOS photosPicker
    // matching .any(of: [.images, .videos]) ChatView.swift:1747 同口径）；
    // 多选上限 8 = AttachmentUploadService.MAX_ITEMS（服务端 staged 64MB 预算闸）
    val pickAttachments = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri -> readPickedAttachmentInto(context, uri, viewModel) }
        }
    }

    val listState = rememberLazyListState()
    // 回合分组（web conversationTurnRenderUnits 子集）：已完成回合折叠为工作摘要，
    // 运行中回合保持展开；散行（回显/会话级卡）独立尾单元
    val turnUnits = remember(messages) { cn.biuz.android.app.ui.chat.TurnGrouping.group(messages) }
    var expandedTurns by remember { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }
    // 新行到达滚底（乐观回显 + 服务端行都走同一投影）
    LaunchedEffect(turnUnits.size) {
        if (turnUnits.isNotEmpty()) listState.animateScrollToItem(turnUnits.size - 1)
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
    // 盖章停驻（motion-spec）：权威成功后卡留 700ms 供落印——投影已撤但停驻未满
    // 的卡由 stampHolds 本地副本顶住，期满 VM 摘除并入账 decidedIds 收卡
    val stampHolds by viewModel.stampHolds.collectAsStateWithLifecycle()
    val visibleInteractions = remember(pendingInteractions, decidedIds, stampHolds) {
        pendingInteractions.filter { it.id !in decidedIds } +
            stampHolds.values.map { it.interaction }
                .filter { hold -> pendingInteractions.none { it.id == hold.id } }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cn.biuz.android.app.ui.theme.BiuZBg)
            .statusBarsPadding()
            .imePadding(), // 键盘避让：composer 与其上方 chips/交互卡整体升到键盘上方
    ) {
        // MARK: 顶栏（web 手机壳 h-11 + border-b：返回 44dp / 标题 14sp medium）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(BiuZBg)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = BiuZText,
                modifier = Modifier.weight(1f),
            )
            // ⋯ 溢出菜单（常驻：项目行是条件渲染，无项目名时仍需入口）
            androidx.compose.foundation.layout.Box {
                Text(
                    text = "⋯",
                    fontSize = 16.sp,
                    color = BiuZText3,
                    modifier = Modifier
                        .clip(BiuZRadius.small)
                        .clickable { overflowOpen = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("05-chat-overflow"),
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_output_title)) },
                        onClick = {
                            overflowOpen = false
                            onOpenTaskOutput()
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.whiteboard_title)) },
                        onClick = {
                            overflowOpen = false
                            whiteboardOpen = true
                        },
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = BiuZBorder)

        // MARK: 项目行（web 第二行：📁 项目名 ⋯ ⤡；无项目整行不渲染）
        currentConversation?.projectName?.takeIf { it.isNotBlank() }?.let { projectName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "📁", fontSize = 14.sp)
                Text(
                    text = projectName,
                    fontSize = 14.sp,
                    color = BiuZText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            androidx.compose.material3.HorizontalDivider(color = BiuZBorder)
        }

        // MARK: 聊天页顶会话迷你轨道（G-007；行自带 workflowActivity 活 run 投影——
        // 只画运行中，全部收尾的会话不渲染轨道）
        val trackActivity = currentConversation?.workflowActivity
        if (trackActivity != null && trackActivity.liveRuns.isNotEmpty()) {
            SessionWorkflowTrackView(
                activity = trackActivity,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg)
                    .padding(bottom = BiuZSpacing.xs),
            )
        }

        // MARK: 工作流运行摘要行（web「工作流运行中 工作流脚本 / N 个阶段 · M 个子代理工作中…」）
        // 完整阶段/子代理明细仍在既有 WorkflowPanelCard；此行点按即展开该面板。
        workflowRuns.firstOrNull { it.rawStatus == "running" }?.let { run ->
            val phases = run.phaseCount ?: 0
            val agents = run.actors.count { it.rawStatus == "running" || it.rawStatus == "active" }
            val meta = buildString {
                if (phases > 0) append("$phases 个阶段")
                if (agents > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("$agents 个子代理工作中…")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg)
                    .padding(bottom = BiuZSpacing.sm)
                    .clip(BiuZRadius.medium)
                    .border(1.dp, BiuZBorder, BiuZRadius.medium)
                    .background(BiuZBgCard)
                    .clickable {
                        expandedPanel = cn.biuz.android.app.ui.chat.SessionPanelKind.Workflow
                    }
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                    .testTag("05-workflow-running"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(text = "⑂", fontSize = 13.sp, color = BiuZSeal)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "工作流运行中 · " + run.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BiuZText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotEmpty()) {
                        Text(text = meta, fontSize = 11.sp, color = BiuZText3)
                    }
                }
                Text(text = "⤢", fontSize = 14.sp, color = BiuZText3)
            }
        }

        // MARK: 会话面板区（goal/plan/工作流/后台/子代理 chips + 单开面板；iOS ChatView
        // content 顶部同位——数据缺席整区不渲染，不占消息流空间）
        SessionPanelsSection(
            viewModel = viewModel,
            expanded = expandedPanel,
            onExpandedChange = { expandedPanel = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg)
                .padding(bottom = BiuZSpacing.sm),
        )

        // 单条消息渲染（回合区块与会话级散行共用）
        val renderMessage: @Composable (ChatMessage) -> Unit = { message ->
            MessageRow(
                message = message,
                // 工作流完成卡四格 join（活投影按 runId 对位；冷恢复 run 被淘汰 = 三格
                // 「—」桌面同口径）+ ⤢ 门控（仅卡对应 run 是面板当前 run 时接线）
                workflowCompletionRun = message.workflowCompletion?.let { card ->
                    workflowRuns.firstOrNull {
                        (it.workId ?: it.id) == card.runId || it.id == card.runId
                    }
                },
                onOpenWorkflowRun = message.workflowCompletion?.let { card ->
                    viewModel.workflowRun
                        ?.takeIf { (it.workId ?: it.id) == card.runId }
                        ?.let { { expandedPanel = cn.biuz.android.app.ui.chat.SessionPanelKind.Workflow } }
                },
                onOpenWorkflowArtifact = { card, artifact ->
                    artifactSheet = WorkflowArtifactSheetRequest(card = card, artifact = artifact)
                },
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
                // G-014 附件条（refs 空的行内部自行不渲染；全屏经单一宿主）
                attachmentContext = attachmentContext,
                // P-020 行级反馈（赞/踩/取消）：onFeedback 接线 → MessageActionRow
                // 渲染入口（MessageActionRow 内部 canFeedback 门槛控制显示——
                // rowKind=assistantText 且 entityId 在场才渲染）
                onFeedback = { origin: ChatMessage, value: Boolean? ->
                    scope.launch { viewModel.setAssistantFeedback(origin, value) }
                },
                // 消息动作「Fork」（web 同入口）：成功后 Toast 新会话 id（失败原因落常驻错误行）
                onFork = {
                    scope.launch {
                        viewModel.forkConversation()?.let { newId ->
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.chat_fork_created_fmt, newId),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
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
            items(turnUnits, key = { it.key }) { turn ->
                TurnSection(
                    turn = turn,
                    expanded = turn.key in expandedTurns,
                    onToggleWork = {
                        expandedTurns = if (turn.key in expandedTurns) {
                            expandedTurns - turn.key
                        } else {
                            expandedTurns + turn.key
                        }
                    },
                    renderMessage = renderMessage,
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
                    val hold = stampHolds[interaction.id]
                    Box {
                        InteractionCard(interaction = interaction, actions = actions)
                        // 盖章动效（motion-spec）：ACK 权威成功后落印「照办/不准」，
                        // 停驻期满由 VM 入账 decidedIds 收卡；失败不落印（U-5）
                        hold?.let {
                            SealStamp(approved = it.approved, modifier = Modifier.align(Alignment.Center))
                        }
                    }
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
            // P-020 行级反馈失败原文（常驻错误行，禁静默）
            feedbackFailure?.let { text -> ComposerErrorRow(text = text) }
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
            // P1-1 待发附件条 + 提示/失败原文行（iOS ChatView.swift:1638-1652 同构：
            // 无待发不渲染整行；hint 优先、失败原文次之——「05-attach-hint / 05-attach-fail-hint」
            // 对位锚点；失败原文 = service errorText 直出，禁折叠成笼统文案）
            if (uploadItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("05-attach-strip"),
                    horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    uploadItems.forEach { item ->
                        PendingAttachmentThumb(
                            item = item,
                            onRemove = { viewModel.removeAttachment(item.id) },
                            onRetry = { viewModel.retryAttachment(item.id) },
                        )
                    }
                }
            }
            val hintValue = uploadHint
            if (hintValue != null) {
                Text(
                    text = hintValue,
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = BiuZOrange,
                    modifier = Modifier.testTag("05-attach-hint"),
                )
            } else if (uploadFailureText != null) {
                Text(
                    text = uploadFailureText ?: "",
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = BiuZOrange,
                    modifier = Modifier.testTag("05-attach-fail-hint"),
                )
            }
            // HIDDEN(H4/H5，隐藏入口待恢复清单)：仓库/语音 chips 与 composer 目标菜单
            // 未移植（iOS ChatView 由 AppSession.isDemoDataEnabled gating 隐藏，
            // HIDDEN 标记在位；E2E 依赖保留，常驻化属产品裁决）。Android 恢复条件 =
            // 对应 chips 落位时同口径以 demo/dev gating 携 HIDDEN 标记接线，勿默认常驻。
            //
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
            }
            // 3.17.2 输入预测 ghost hint（灰字续写，点按采纳拼到草稿尾部）。仅连接态 +
            // Host 能力门开启时 store 才返候选；无 hint 不渲染整行（同附件条「无排队不渲染」）
            val ghostHint = inputHint
            if (ghostHint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.acceptInputHint()
                        }
                        .testTag("05-input-hint"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⌨",
                        fontSize = 11.sp,
                        color = BiuZText3,
                    )
                    Spacer(Modifier.size(BiuZSpacing.sm))
                    Text(
                        text = ghostHint,
                        style = TextStyle(fontSize = 13.sp),
                        color = BiuZText2,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(BiuZSpacing.sm))
                    Text(
                        text = stringResource(R.string.composer_input_hint_accept),
                        style = TextStyle(fontSize = 10.5.sp),
                        color = BiuZText3,
                    )
                }
            }
            // 输入行（附件钮 + 输入框 + 发送钮；iOS ComposerBar inputRow 同构）
            Row(verticalAlignment = Alignment.Bottom) {
                // 附件钮（＋；系统 photo picker 承载——ImageAndVideo 过滤含视频）
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable {
                            pickAttachments.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                ),
                            )
                        }
                        .testTag("05-attach-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "＋",
                        fontSize = 20.sp,
                        color = BiuZText2,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { text ->
                        viewModel.updateDraft(text)
                        // 3.17.2 输入预测：非空草稿停顿即预测续写；空草稿仅失焦+空闲时预测
                        // 下一条（spec「输入框为空、失焦且会话空闲」——聚焦清空〔发送后〕
                        // 不立即触发）。iOS ChatView onChange :1806-1813 同口径
                        if (text.isNotEmpty() || !composerFocused) {
                            viewModel.scheduleInputPrediction()
                        } else {
                            viewModel.invalidateInputHint()
                        }
                    },
                    textStyle = TextStyle(fontSize = 14.5.sp, color = BiuZText, lineHeight = 20.sp),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { composerFocused = it.isFocused }
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
                // 发送闸（iOS canSend :1056 直译）：`!draft.isEmpty && !uploads.blocksSend`
                // ——有待发且存在未 committed 项禁发（防直发文本丢附件）
                // 主 CTA = 朱磦实底（2026-11-24 用户裁决「主 CTA 用朱磦」——与主印/登录页同色；
                // 墨 accent 降级为中性强调）。按压实 + 轻击触觉只给这类「最终提交」动作。
                val sendInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .ctaPress(sendInteraction)
                        .clip(CircleShape)
                        .background(
                            if (draft.isNotBlank() && !sending && !uploadsBlocksSend) BiuZSeal else BiuZBgInput,
                        )
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = draft.isNotBlank() && !sending && !uploadsBlocksSend,
                        ) {
                            scope.launch { viewModel.send() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (sending) "…" else "↑",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (draft.isNotBlank() && !sending) BiuZOnSeal else BiuZText3,
                    )
                }
            }
        }
    }

    // MARK: composer 面板（协作模式/模型/思考三态分派；单路径关闭 §5-15：
    // onDismiss / 选中成功均走 closeComposerPanel）
    val panel = composerPanel
    if (panel != null) {
        // APP-DIFF[D001] platform: web 侧边抽屉（side pane，宽 min(88vw,28rem)）在 Android
        // 投影为 ModalBottomSheet 原生弹层组，手势/焦点/遮罩由系统承担（ChatScreen.kt:775/888）
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

    // 全屏附件单一宿主（图片/视频合流一个 Dialog 位点——v1.32③ 后挂遮蔽先挂坑对位；
    // item == null 不挂载）
    AttachmentFullscreenHost(state = fullscreenState)

    // 工作流完成卡产物详情 sheet（点交付物/产物索引行；store.workflowArtifactContent
    // 分块拼装，markdown/plain 全文渲染；宿主注入 store 读面——VM 内 store 私有的
    // iOS 过桥同位）
    val sheetRequest = artifactSheet
    if (sheetRequest != null) {
        WorkflowArtifactSheet(
            request = sheetRequest,
            sessionId = sessionId,
            load = { request ->
                surface.conversation.workflowArtifactContent(
                    sessionId = sessionId,
                    runId = request.card.runId,
                    artifactId = request.artifact.id,
                    version = request.artifact.version,
                )
            },
            onClose = { artifactSheet = null },
        )
    }

        // 036 白板浮层（覆盖整屏；web 侧栏面板同位。零 RPC 纯客户端——进程内存活、
        // workspace 键同 web buildWhiteboardWorkspaceKey 口径 identity||path）
        if (whiteboardOpen) {
            val ws = surface.workspace
            val whiteboardKey = ws?.workspaceIdentity?.takeIf { it.isNotBlank() }
                ?: ws?.path?.takeIf { it.isNotBlank() }
                ?: "default"
            cn.biuz.android.app.ui.whiteboard.WhiteboardPanel(
                workspaceKey = whiteboardKey,
                onAddToChat = { bytes ->
                    viewModel.enqueueAttachment(
                        name = "白板.png",
                        mediaType = "image/png",
                        data = bytes,
                    )
                },
                onClose = { whiteboardOpen = false },
            )
        }
}

/** 产物详情 sheet 请求（完成卡产物点按；iOS WorkflowArtifactRequest 直译）。 */
data class WorkflowArtifactSheetRequest(
    val card: cn.biuz.android.protocol.WorkflowCompletionCard,
    val artifact: cn.biuz.android.protocol.WorkflowCompletionArtifact,
)

/**
 * 工作流产物详情 sheet（iOS WorkflowArtifactSheetView :2095-2178 直译）：
 * workflowRunArtifactReadV4 分块拼装 markdown/plain 全文渲染；其余 mediaType 如实标注
 * 「暂不支持手机端预览」；加载失败保留错误行 + 重试（读面失败不折叠成空态，§5-12 口径）。
 * 【平台偏差】iOS 正文走 TinyMarkdown 富渲染；Android v1 正文原样呈现（可读性够用，
 * 富文本渲染器未移植，见交付 deviations）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowArtifactSheet(
    request: WorkflowArtifactSheetRequest,
    sessionId: String,
    load: suspend (WorkflowArtifactSheetRequest) -> cn.biuz.android.store.ArtifactContent?,
    onClose: () -> Unit,
) {
    var content by remember(request) { mutableStateOf<String?>(null) }
    var failureText by remember(request) { mutableStateOf<String?>(null) }
    var isLoading by remember(request) { mutableStateOf(true) }
    var reloadTick by remember(request) { mutableStateOf(0) }

    LaunchedEffect(request, reloadTick) {
        isLoading = true
        failureText = null
        content = null
        val result = load(request)
        isLoading = false
        if (result == null) {
            failureText = "" // UI 层映射资源文案（VM 零文案纪律同款：sheet 内自持文案位）
            return@LaunchedEffect
        }
        if (isRenderableArtifact(result.mediaType)) {
            val text = result.data.toString(Charsets.UTF_8)
            // >6k 字内置截断护解析（iOS MarkdownMessageBody 同口径）
            content = if (text.length > 6_000) text.take(6_000) else text.ifEmpty { null }
        } else {
            content = null // 非文本族：不出空态，出如实提示（产物元数据仍可见）
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = BiuZBgElevated) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            // 头部：标题 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = request.artifact.title ?: request.artifact.id,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = BiuZText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.action_close),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = BiuZText2,
                    modifier = Modifier
                        .clickable(onClick = onClose)
                        .testTag("07-wf-artifact-close")
                        .padding(BiuZSpacing.sm),
                )
            }
            when {
                isLoading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = BiuZSpacing.xl),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = BiuZAccent,
                    )
                    Spacer(Modifier.size(BiuZSpacing.sm))
                    Text(
                        text = stringResource(R.string.wf_artifact_loading),
                        style = TextStyle(fontSize = 13.sp),
                        color = BiuZText3,
                    )
                }

                failureText != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.wf_artifact_load_failed),
                        style = TextStyle(fontSize = 13.sp),
                        color = BiuZText3,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.composer_retry),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZAccent,
                        modifier = Modifier
                            .clickable { reloadTick++ }
                            .padding(BiuZSpacing.sm),
                    )
                }

                content != null -> Text(
                    text = content ?: "",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = BiuZText),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiuZSpacing.lg)
                        .verticalScroll(rememberScrollState()),
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = request.artifact.title ?: request.artifact.id,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZText,
                    )
                    Text(
                        text = stringResource(R.string.wf_artifact_unsupported),
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                        color = BiuZText3,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(BiuZSpacing.xl))
        }
    }
}

/** 可渲染判定：markdown/纯文本族（v1 渲染面；二进制族如实体签名出提示页）（iOS :2153-2158）。 */
private fun isRenderableArtifact(mediaType: String): Boolean {
    val mt = mediaType.lowercase()
    return mt.startsWith("text/") || mt.contains("markdown") || mt == "application/json"
}

// MARK: - 附件选择器入队（iOS addPhotoPickerItems ChatView.swift:2457-2476 同构）

/** 读 picker 产物字节并入队（失败落提示行「附件读取失败，已跳过」，不静默丢选）。 */
private suspend fun readPickedAttachmentInto(
    context: android.content.Context,
    uri: android.net.Uri,
    viewModel: ChatViewModel,
) {
    val data = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }
    if (data == null || data.isEmpty()) {
        viewModel.setUploadHint(context.getString(R.string.attach_read_fail))
        return
    }
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
    val name = queryDisplayName(context, uri) ?: fallbackAttachmentName(mime)
    viewModel.enqueueAttachment(name = name, mediaType = mime, data = data)
}

/** 内容名查询（OpenableColumns.DISPLAY_NAME；photo picker 产物大多携带）。 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

/** 展示名兜底（iOS VID_/IMG_+毫秒时间戳 同构；扩展名按 mime 推导）。 */
private fun fallbackAttachmentName(mime: String): String {
    val isVideo = mime.startsWith("video/")
    val ext = when (mime) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> if (isVideo) "mp4" else "jpg"
    }
    return "${if (isVideo) "VID" else "IMG"}_${System.currentTimeMillis()}.$ext"
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
    "guarded" -> stringResource(R.string.composer_mode_guarded)
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
    // 胶囊 = 报告 §4.4 第 2 类：按压实，**无触觉**（芯片按一下就震会把触觉降成噪音）
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressableScale(interaction)
            .clip(RoundedCornerShape(999.dp))
            .background(BiuZBgInput)
            .border1()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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

/**
 * 回合区块（web `ConversationTurnGroup` 简化同构）：用户输入 → 助手工作项；
 * 已完成回合默认折叠为一条工作摘要行（点击展开/收起），运行中回合保持展开。
 * 散行单元（turnIndex < 0：在途回显/会话级卡）恒展开且不出摘要行。
 */
@Composable
private fun TurnSection(
    turn: cn.biuz.android.app.ui.chat.TurnUnit,
    expanded: Boolean,
    onToggleWork: () -> Unit,
    renderMessage: @Composable (ChatMessage) -> Unit,
) {
    val loose = turn.index < 0
    val showWork = loose || turn.isRunning || expanded
    Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.md)) {
        turn.userMessages.forEach { renderMessage(it) }
        if (turn.workItems.isNotEmpty()) {
            if (!loose) {
                // 运行中回合不出摘要行（实时展开）；已完成回合：折叠态摘要 / 展开态可点收起
                if (!turn.isRunning || expanded) {
                    TurnWorkSummaryRow(
                        summary = turn.summary(),
                        collapsed = !expanded,
                        onClick = onToggleWork,
                    )
                } else {
                    TurnWorkSummaryRow(
                        summary = turn.summary(),
                        collapsed = true,
                        onClick = onToggleWork,
                        running = true,
                    )
                }
            }
            if (showWork) {
                turn.workItems.forEach { renderMessage(it) }
            }
        }
    }
}

/** 回合工作摘要行（「已工作 · 探索 N 项 · 运行 N 条命令 · 更改 N 个文件」；运行中=工作中…）。 */
@Composable
private fun TurnWorkSummaryRow(
    summary: cn.biuz.android.app.ui.chat.WorkSummary,
    collapsed: Boolean,
    onClick: () -> Unit,
    running: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("05-turn-summary"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(if (collapsed) "▸" else "▾", fontSize = 11.sp, color = BiuZText3)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = turnWorkSummaryText(summary, running),
            fontSize = 12.sp,
            color = BiuZText3,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun turnWorkSummaryText(
    summary: cn.biuz.android.app.ui.chat.WorkSummary,
    running: Boolean,
): String {
    if (running) return stringResource(R.string.chat_turn_working)
    val parts = ArrayList<String>()
    if (summary.exploreCount > 0) {
        parts.add(stringResource(R.string.chat_turn_summary_explore, summary.exploreCount))
    }
    if (summary.commandCount > 0) {
        parts.add(stringResource(R.string.chat_turn_summary_command, summary.commandCount))
    }
    if (summary.changeCount > 0) {
        parts.add(stringResource(R.string.chat_turn_summary_change, summary.changeCount))
    }
    if (summary.reasoningCount > 0) {
        parts.add(stringResource(R.string.chat_turn_summary_reasoning, summary.reasoningCount))
    }
    val head = stringResource(R.string.chat_turn_worked)
    return if (parts.isEmpty()) head else "$head · ${parts.joinToString(" · ")}"
}

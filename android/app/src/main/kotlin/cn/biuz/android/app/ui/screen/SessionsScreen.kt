package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import kotlinx.coroutines.delay
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.chat.ChatViewModel
import cn.biuz.android.app.ui.chat.ModelOptionSection
import cn.biuz.android.app.ui.chat.ModelRefetchRow
import cn.biuz.android.app.ui.chat.SessionWorkflowTrackView
import cn.biuz.android.app.ui.chat.ThoughtOptionSection
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgElevated
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZOnOrange
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.ctaPress
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.pressableScale
import cn.biuz.android.app.ui.theme.BiuZOnSeal
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZGlyph
import cn.biuz.android.app.ui.theme.BiuZFont
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.TaskGroupStructure
import cn.biuz.android.connection.readTaskGroups
import cn.biuz.android.connection.saveTaskGroup
import cn.biuz.android.connection.pinTaskGroupToTop
import cn.biuz.android.connection.moveTaskGroupUpOne
import cn.biuz.android.connection.groupPredecessorKey
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.ModelSelectionInfo
import cn.biuz.android.store.NewSessionModelSelection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 会话列表（Tab2 根同位）：Store 装配面消费屏。
 * 数据源 = [AppSession.storeSurface]（连接态 sessions-index 订阅直供；未连接/失败态
 * Empty 三件如实空读）。观察流以装配世代 [cn.biuz.android.connection.StoreSurface.epoch]
 * 为 key 重建——Store 换源即重绑，防永久抱空 store 实例（红线）。
 *
 * 呈现（交接裁决）：
 * - LazyColumn 分组「置顶 → 今天 → 昨天 → 更早」（组织态读 membership——isPinned 由
 *   Store 层本地 override → tasks-index membership → 行字段三源 join 定案，UI 直读）；
 * - 行 = 标题首字符圆徽（会话 icon 裁决：icon 用标题首字符）+ 标题 + 摘要 + 时间；
 * - 行级指示（桌面侧栏逐字 parity）：isRunning 双源合成转圈（蓝）+「等待确认」标（橙，
 *   pendingApprovalCount>0）；归档行由 Store 投影滤除；
 * - 未连接空态如实：未连接桌面端 / 连接后同步桌面端的会话与消息 / 连接桌面端。
 * 读面失败三态呈现：last-good 列表与失败文案共存，不折叠成空态（工程纪律 §5-12）。
 */
@Composable
fun SessionsScreen(
    session: AppSession,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBackToConnect: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenPendingApprovals: () -> Unit = {},
    onOpenCommandCenter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showNewSheet by remember { mutableStateOf(false) }
    // 行级动作（归档/标记未读）失败文案：常驻错误行，不静默吞（§5-12）
    var rowActionError by remember(surface.epoch) { mutableStateOf<String?>(null) }

    // Store 换源重绑：列表态/失败文案均以 epoch 为 key（实例级重建，防抱旧 Store）
    var conversations by remember(surface.epoch) { mutableStateOf<List<Conversation>>(emptyList()) }
    var loadFailure by remember(surface.epoch) { mutableStateOf<String?>(null) }
    // P-023 订阅看门狗：桌面 v4 握手超时（helloConversationV4 RPC 超时）时连接层只「降级继续」，
    // 列表会永久停在「正在同步」且无错误行/无重试入口。15s 无首屏 → 如实报错并给重试。
    var subscribeWatchdog by remember(surface.epoch) { mutableStateOf<String?>(null) }
    LaunchedEffect(surface.epoch) {
        // conversations() 触发 sessions-index 订阅（帧 handler 先于 subscribe 注册由
        // Store 内部保证）并取首屏投影；随后按事件流增量维护列表
        conversations = surface.conversation.conversations()
        loadFailure = surface.sessionsIndex?.conversationsLoadFailure
        surface.conversation.observeConversations().collect { event ->
            when (event) {
                is ConversationEvent.ConversationsReplaced -> conversations = event.conversations
                is ConversationEvent.ConversationUpdated -> {
                    conversations = conversations.map { row ->
                        if (row.id == event.conversation.id) event.conversation else row
                    }
                }
                else -> Unit
            }
        }
    }
    // 看门狗：连接态且一直没有首屏、也没有既有失败文案 → 超时上屏（可重试）
    LaunchedEffect(surface.epoch, conversations.size, loadFailure, mode) {
        if (mode !is AppSession.Mode.Connected) return@LaunchedEffect
        if (conversations.isNotEmpty() || loadFailure != null) {
            subscribeWatchdog = null
            return@LaunchedEffect
        }
        delay(15_000)
        if (conversations.isEmpty() && loadFailure == null) {
            subscribeWatchdog = "桌面端暂未返回会话列表（会话初始化未完成）· 点按重试"
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    // 分组边界（今天 00:00 / 昨天 00:00；组合期计算一次，长驻列表跨天偏差可接受）
    val dayBounds = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -1)
        todayStart to cal.timeInMillis
    }

    // LazyColumn key 需唯一非空：空 id 行（未连接态 Empty Store 防御位）不入列
    val rows = conversations.filter { it.id.isNotEmpty() }
    // 列表视图四档（对齐 web `ProjectSidebarListHeader.tsx:87-102`）
    var viewMode by remember { mutableStateOf(ListViewMode.PROJECTS) }
    var showViewMenu by remember { mutableStateOf(false) }
    val pinnedTitle = stringResource(R.string.group_pinned)
    // P-013 按分组视图 task-groups 数据（viewMode 切 GROUPED 时加载）
    var taskGroups by remember { mutableStateOf<TaskGroupStructure?>(null) }
    var taskGroupsLoading by remember { mutableStateOf(false) }
    var taskGroupsError by remember { mutableStateOf<String?>(null) }
    val ungroupedTitle = stringResource(R.string.sessions_section_tasks)
    val timelineTitle = stringResource(R.string.sessions_view_timeline)
    val unreadTitle = stringResource(R.string.sessions_view_unread_label)
    val unreadEmpty = stringResource(R.string.sessions_unread_empty)
    // P-013：切 GROUPED 视图时拉 task-groups.read
    LaunchedEffect(viewMode, surface.conversation) {
        if (viewMode == ListViewMode.GROUPED && taskGroups == null) {
            taskGroupsLoading = true
            taskGroupsError = null
            try {
                val ws = session.storeSurface.value.workspace
                taskGroups = session.readTaskGroups(ws?.path, ws?.workspaceIdentity)
            } catch (e: Exception) {
                taskGroupsError = e.message ?: e::class.simpleName
            } finally {
                taskGroupsLoading = false
            }
        }
    }

    // G34 置顶翻页（web taskList.showMore/showLess 同语义）
    var showAllPinned by rememberSaveable { mutableStateOf(false) }
    val sections = remember(rows, viewMode, pinnedTitle, ungroupedTitle, timelineTitle, unreadTitle, unreadEmpty, taskGroups, showAllPinned) {
        when (viewMode) {
            ListViewMode.PROJECTS -> projectSections(rows, pinnedTitle, ungroupedTitle)
            ListViewMode.TIMELINE -> timelineSections(
                rows, pinnedTitle, timelineTitle,
                pinnedLimit = if (showAllPinned) null else PINNED_PAGE_SIZE,
            )
            ListViewMode.UNREAD -> unreadSections(rows, pinnedTitle, unreadTitle, unreadEmpty)
            ListViewMode.GROUPED -> {
                val tg = taskGroups
                if (tg != null) {
                    taskGroupSections(rows, tg.groups, tg.members, ungroupedTitle)
                } else {
                    emptyList()
                }
            }
        }
    }

    // 项目视图两级看板（web project-sidebar Projects/Tasks 同构）：置顶 → 项目节点（可折叠）→ 任务
    val board = remember(rows) { projectBoard(rows) }
    val projectsTitle = stringResource(R.string.sessions_section_projects)
    // 折叠态（键 = 归属目录全路径）：默认全展开；进程内 + 配置变更内持久
    var collapsedProjects by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    val onArchiveRow: (Conversation) -> Unit = { conversation ->
        scope.launch {
            rowActionError = null
            // setArchived 失败不抛（store 设计：回滚 + lastArchiveFailureText 契约）
            runCatching { surface.conversation.setArchived(true, conversation.id) }
            val failure = surface.conversation.lastArchiveFailureText()
            if (failure.isNotEmpty()) rowActionError = failure
            conversations = runCatching {
                surface.conversation.conversations()
            }.getOrDefault(conversations)
        }
    }
    val onMarkUnreadRow: (Conversation) -> Unit = { conversation ->
        scope.launch {
            rowActionError = null
            val error = runCatching {
                surface.conversation.markUnread(conversation.id)
            }.exceptionOrNull()?.message
            if (error != null) rowActionError = error
            else conversations = runCatching {
                surface.conversation.conversations()
            }.getOrDefault(conversations)
        }
    }
    val renderConversationRow: @Composable (Conversation, Boolean) -> Unit = { conversation, indented ->
        Box(modifier = if (indented) Modifier.padding(start = BiuZSpacing.lg) else Modifier) {
            SessionRow(
                conversation = conversation,
                timeLabel = conversationRowTimeLabel(
                    updatedAtMs = conversation.updatedAt,
                    todayStartMs = dayBounds.first,
                    yesterdayStartMs = dayBounds.second,
                    timeFormat = timeFormat,
                    yesterdayLabel = stringResource(R.string.group_yesterday),
                    earlierPattern = stringResource(R.string.row_time_earlier_pattern),
                ),
                onOpen = { onOpenChat(conversation.id) },
                onArchive = { onArchiveRow(conversation) },
                onMarkUnread = { onMarkUnreadRow(conversation) },
                onSnoozeCountdown = {
                    conversation.pendingInteractionId?.let { interactionId ->
                        scope.launch {
                            runCatching {
                                surface.conversation.snoozeInteractionAutoResolution(
                                    conversation.id, interactionId,
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = BiuZSpacing.lg),
        ) {
            Spacer(Modifier.height(BiuZSpacing.lg))
            // G39 工作区切换（web main.tsx:1603-1613 switchWorkspace 桥重建移动对位；
            // iOS P3-10 工作区切换器同构）：设备行下挂工作区胶囊行，点开枚举
            // conversationStore.allWorkspaces 选切（AppSession.switchWorkspace 桥重建）
            var workspaceSwitchOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
            var workspaceSwitching by remember { androidx.compose.runtime.mutableStateOf(false) }
            var workspaceSwitchError by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            val currentWorkspace = surface.workspace
            val allWorkspaces = remember(surface.epoch) {
                (surface.conversation as? cn.biuz.android.store.RemoteConversationStore)?.allWorkspaces.orEmpty()
            }
            // 设备切换行（对齐 web home 顶部 `aria-label=切换设备`）：图标 + 设备名 + 展开箭头
            val serverName = when (val m = mode) {
                is AppSession.Mode.Connected -> m.server.name ?: m.server.host
                is AppSession.Mode.Disconnected -> m.server.name ?: m.server.host
                is AppSession.Mode.ConnectFailed -> m.server.name ?: m.server.host
                else -> null
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .clickable(onClick = onOpenDevices)
                    // 水平不留缩进：与列表区同一左轨（真机实测原 8dp 让设备行比区头右移）
                    .padding(vertical = BiuZSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "💻", fontSize = 13.sp)
                Spacer(Modifier.size(BiuZSpacing.sm))
                Text(
                    text = serverName ?: stringResource(R.string.sessions_no_device),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "⌃⌄", fontSize = 10.sp, color = BiuZText3)
            }
            // G39 工作区切换弹层（AlertDialog 枚举全工作区；当前项勾标；跨工作区
            // 归属面不同——切换 = 桥重建 + Store 重装配，G52 已让缓存随迁）
            if (workspaceSwitchOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { workspaceSwitchOpen = false },
                    title = { Text("切换工作区") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
                            if (allWorkspaces.isEmpty()) {
                                Text(
                                    text = "桌面端未上报工作区清单（当前区即全部）",
                                    fontSize = 12.sp,
                                    color = BiuZText3,
                                )
                            }
                            allWorkspaces.forEach { ws ->
                                val isCurrent = ws.path == currentWorkspace?.path
                                Text(
                                    text = (if (isCurrent) "✓ " else "") + ws.path +
                                        (if (ws.workspaceIdentity != null) "  ·identity" else ""),
                                    fontSize = 13.sp,
                                    color = if (isCurrent) BiuZAccent else BiuZText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(BiuZRadius.small)
                                        .clickable(enabled = !workspaceSwitching && !isCurrent) {
                                            workspaceSwitching = true
                                            workspaceSwitchError = null
                                            scope.launch {
                                                val failure = session.switchWorkspace(
                                                    cn.biuz.android.connection.ServerWorkspaceInfo(
                                                        path = ws.path,
                                                        label = ws.label,
                                                        workspaceIdentity = ws.workspaceIdentity,
                                                    ),
                                                )
                                                workspaceSwitching = false
                                                if (failure != null) {
                                                    workspaceSwitchError = failure
                                                } else {
                                                    workspaceSwitchOpen = false
                                                }
                                            }
                                        }
                                        .padding(BiuZSpacing.xs)
                                        .testTag("04-workspace-opt-${ws.path}"),
                                )
                            }
                            workspaceSwitchError?.let {
                                Text(text = it, fontSize = 11.sp, color = BiuZRed)
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { workspaceSwitchOpen = false },
                        ) { Text("关闭") }
                    },
                )
            }
            Spacer(Modifier.height(BiuZSpacing.xs))
            // G39 工作区胶囊行（当前工作区 + 展开箭头；未连接不渲染）
            if (mode is AppSession.Mode.Connected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .clickable(enabled = !workspaceSwitching) { workspaceSwitchOpen = true }
                        .padding(vertical = BiuZSpacing.xs)
                        .testTag("04-workspace-chip"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "📁", fontSize = 12.sp)
                    Spacer(Modifier.size(BiuZSpacing.xs))
                    Text(
                        text = currentWorkspace?.path?.split("/")?.lastOrNull { it.isNotEmpty() }
                            ?: currentWorkspace?.path
                            ?: "",
                        style = TextStyle(fontSize = 12.sp),
                        color = BiuZText2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = if (workspaceSwitching) "切换中…" else "⌃⌄",
                        fontSize = 10.sp,
                        color = BiuZText3,
                    )
                }
            }
            // R1 待批入口行（pendingApprovalCount 合计 > 0 才渲染——跨会话审批收件箱）
            val totalPending = remember(conversations) {
                conversations.sumOf { it.pendingApprovalCount }
            }
            if (totalPending > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .clickable(onClick = onOpenPendingApprovals)
                        .padding(vertical = BiuZSpacing.xs)
                        .testTag("04-act-pending-approvals"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "🖊", fontSize = 12.sp, color = BiuZOrange)
                    Spacer(Modifier.size(BiuZSpacing.xs))
                    Text(
                        text = "待批",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZText,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "$totalPending 项待处理 ⌄",
                        fontSize = 11.sp,
                        color = BiuZOrange,
                    )
                }
            }
            Spacer(Modifier.height(BiuZSpacing.xs))
            // 新建任务行（web task-new-button：32dp 高 / padding 0 10 / gap 8 / 14sp）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(BiuZRadius.medium)
                    .clickable { showNewSheet = true }
                    .padding(end = BiuZSpacing.md)
                    .testTag("04-act-new"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "⊕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = stringResource(R.string.sessions_new_task),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.md))
            // 列表头（web sidebar-search-toolbar：标题 14sp/500 α0.6 + 右侧视图菜单）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = BiuZSpacing.sm, bottom = BiuZSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sessions_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BiuZText2,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    Text(
                        text = "≣",
                        fontSize = 14.sp,
                        color = BiuZText2,
                        modifier = Modifier
                            .clip(BiuZRadius.small)
                            .clickable { showViewMenu = true }
                            .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.tiny)
                            .testTag("04-view-menu"),
                    )
                    DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                        listOf(
                            ListViewMode.PROJECTS to R.string.sessions_view_projects,
                            ListViewMode.GROUPED to R.string.sessions_view_grouped,
                            ListViewMode.TIMELINE to R.string.sessions_view_timeline,
                            ListViewMode.UNREAD to R.string.sessions_view_unread,
                        ).forEach { (target, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
                                onClick = {
                                    viewMode = target
                                    showViewMenu = false
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(BiuZSpacing.md))

            when (val m = mode) {
                is AppSession.Mode.Disconnected -> {
                    // 橙横幅：断线保留快照（列表继续显示断线前数据），重连同入口
                    Text(
                        text = stringResource(R.string.connect_disconnected_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Button(
                        onClick = { scope.launch { session.connect(m.server) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        Text(stringResource(R.string.connect_reconnect))
                    }
                    Spacer(Modifier.height(BiuZSpacing.md))
                }

                is AppSession.Mode.ConnectFailed -> {
                    // 红横幅：失败态空数据如实（重连入口同页；详细对照见连接页失败卡）
                    Text(
                        text = stringResource(R.string.sessions_connect_failed_fmt, m.error.headline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Button(
                        onClick = { scope.launch { session.connect(m.server) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        Text(stringResource(R.string.connect_retry))
                    }
                    Spacer(Modifier.height(BiuZSpacing.md))
                }

                else -> Unit
            }

            // 读面失败三态：与 last-good 列表共存（§5-12）
            loadFailure?.let { failure ->
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(BiuZSpacing.sm))
            }

            // 行级动作（归档/标记未读）失败：原因原文上屏（§5-12，不静默吞）
            rowActionError?.let { text ->
                BiuzInlineNotice(message = text, tag = "04-row-action-error")
                Spacer(Modifier.height(BiuZSpacing.sm))
            }

            // P-023：看门狗超时行（常驻，点按重新订阅；首屏到达即清）
            subscribeWatchdog?.let { text ->
                BiuzInlineNotice(
                    message = text,
                    tag = "04-subscribe-watchdog",
                    onRetry = {
                        subscribeWatchdog = null
                        scope.launch { conversations = surface.conversation.conversations() }
                    },
                )
                Spacer(Modifier.height(BiuZSpacing.sm))
            }

            when {
                // 未连接空态（未连接桌面端 / 连接后同步… / 连接桌面端 CTA）
                rows.isEmpty() && (mode is AppSession.Mode.Demo || mode is AppSession.Mode.ConnectFailed) -> {
                    NotConnectedEmpty(
                        onConnect = onBackToConnect,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 连接态首屏：订阅快照未到 = 居中 spinner（5.9 加载态口径，无骨架屏）
                rows.isEmpty() && loadFailure == null && subscribeWatchdog == null &&
                    mode is AppSession.Mode.Connected -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = BiuZSpacing.offsetXl),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        Text(
                            text = stringResource(R.string.sessions_syncing),
                            style = MaterialTheme.typography.labelMedium,
                            color = BiuZText3,
                        )
                    }
                }

                viewMode == ListViewMode.GROUPED -> {
                    when {
                        taskGroupsLoading -> {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = BiuZSpacing.offsetXl),
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(BiuZSpacing.sm))
                                Text(
                                    text = stringResource(R.string.sessions_syncing),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BiuZText3,
                                )
                            }
                        }
                        taskGroupsError != null -> {
                            Text(
                                text = stringResource(R.string.sessions_view_grouped_error, taskGroupsError ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = BiuZText3,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("04-view-grouped-error"),
                            )
                        }
                        sections.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.sessions_view_grouped_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = BiuZText3,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("04-view-grouped-empty"),
                            )
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                sections.forEach { section ->
                                    item(key = "header-${section.title}-${section.rows.firstOrNull()?.id ?: ""}") {
                                        // G36 组色（web TaskGroupedView.tsx:244-245 同构——
                                        // 色点 + 点按循环 7 档 → save {id,color} → 重拉 read；
                                        // 触屏简化交互：菜单式换色弹层成本高，循环即达意）
                                        val groupEntry = taskGroups?.groups
                                            ?.firstOrNull { it.title == section.title }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = {
                                                        // G36 组色循环（保留）
                                                        val entry = groupEntry ?: return@combinedClickable
                                                        scope.launch {
                                                            runCatching {
                                                                val wsTarget = session.storeSurface.value.workspace
                                                                session.saveTaskGroup(
                                                                    wsTarget?.path, wsTarget?.workspaceIdentity,
                                                                    id = entry.id,
                                                                    color = nextGroupColor(entry.color),
                                                                )
                                                            }
                                                            taskGroups = null // 触发重拉
                                                        }
                                                    },
                                                    onLongClick = {
                                                        // G36 组置顶排序（move 组节点——无 anchor
                                                        // = 顶层 index 0；wire 取证见 RemoteTaskGroups）
                                                        val entry = groupEntry ?: return@combinedClickable
                                                        scope.launch {
                                                            runCatching {
                                                                val wsTarget = session.storeSurface.value.workspace
                                                                session.pinTaskGroupToTop(
                                                                    wsTarget?.path, wsTarget?.workspaceIdentity,
                                                                    groupId = entry.id,
                                                                )
                                                            }
                                                            taskGroups = null
                                                        }
                                                    },
                                                )
                                                .testTag("04-group-color-header"),
                                        ) {
                                            groupEntry?.color?.takeIf { it.isNotEmpty() }?.let { color ->
                                                androidx.compose.foundation.Canvas(
                                                    modifier = Modifier.size(8.dp),
                                                ) {
                                                    drawCircle(
                                                        color = taskGroupColorValue(color),
                                                    )
                                                }
                                                Spacer(Modifier.size(BiuZSpacing.xs))
                                            }
                                            Text(
                                                text = section.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = BiuZText2,
                                            )
                                            // G36 组排序「上移一位」（anchor = order 前驱组；
                                            // 首组无语义不渲染——store.moveTaskGroupUpOne）
                                            val entryId = groupEntry?.id
                                            val structure = taskGroups
                                            val predecessorKey = if (entryId != null && structure != null) {
                                                structure.groupPredecessorKey(entryId)
                                            } else null
                                            if (predecessorKey != null) {
                                                val anchorKey: String =
                                                    requireNotNull(predecessorKey)
                                                Text(
                                                    text = "↑",
                                                    fontSize = 12.sp,
                                                    color = BiuZText3,
                                                    modifier = Modifier
                                                        .clickable {
                                                            scope.launch {
                                                                runCatching {
                                                                    val wsTarget = session.storeSurface.value.workspace
                                                                    session.moveTaskGroupUpOne(
                                                                        wsTarget?.path, wsTarget?.workspaceIdentity,
                                                                        groupId = requireNotNull(entryId),
                                                                        beforeGroupKey = anchorKey,
                                                                    )
                                                                }
                                                                taskGroups = null
                                                            }
                                                        }
                                                        .padding(horizontal = BiuZSpacing.xs)
                                                        .testTag("04-group-move-up"),
                                                )
                                            }
                                        }
                                    }
                                    items(section.rows, key = { it.id }) { conversation ->
                                        SessionRow(
                                            conversation = conversation,
                                            timeLabel = conversationRowTimeLabel(
                                                updatedAtMs = conversation.updatedAt,
                                                todayStartMs = dayBounds.first,
                                                yesterdayStartMs = dayBounds.second,
                                                timeFormat = timeFormat,
                                                yesterdayLabel = stringResource(R.string.group_yesterday),
                                                earlierPattern = stringResource(R.string.row_time_earlier_pattern),
                                            ),
                                            onOpen = { onOpenChat(conversation.id) },
                                            onArchive = {
                                                scope.launch {
                                                    rowActionError = null
                                                    runCatching { surface.conversation.setArchived(true, conversation.id) }
                                                    val failure = surface.conversation.lastArchiveFailureText()
                                                    if (failure.isNotEmpty()) rowActionError = failure
                                                    conversations = runCatching {
                                                        surface.conversation.conversations()
                                                    }.getOrDefault(conversations)
                                                }
                                            },
                                            onMarkUnread = {
                                                scope.launch {
                                                    rowActionError = null
                                                    val error = runCatching {
                                                        surface.conversation.markUnread(conversation.id)
                                                    }.exceptionOrNull()?.message
                                                    if (error != null) rowActionError = error
                                                    else conversations = runCatching {
                                                        surface.conversation.conversations()
                                                    }.getOrDefault(conversations)
                                                }
                                            },
                                        )
                                    }
                                }
                                item { Spacer(Modifier.height(BiuZSpacing.sm)) }
                            }
                        }
                    }
                }

                else -> {
                    if (viewMode == ListViewMode.PROJECTS) {
                        // 两级看板（web Projects / Tasks 同构）：置顶 → 项目节点（可折叠）→ 任务
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            if (board.pinned.isNotEmpty()) {
                                item(key = "hdr-pinned") { SessionSectionHeader(pinnedTitle) }
                                items(board.pinned, key = { it.id }) { conversation ->
                                    renderConversationRow(conversation, false)
                                }
                            }
                            if (board.projects.isNotEmpty()) {
                                item(key = "hdr-projects") { SessionSectionHeader(projectsTitle) }
                                board.projects.forEachIndexed { index, group ->
                                    val expanded = group.key !in collapsedProjects
                                    // 项目节点之间的 8dp 分隔：否则「上一个项目的最后一个子行」与
                                    // 「下一个项目节点」的行距和普通子行一致，读起来像同一个项目的子项
                                    if (index > 0) item(key = "proj-gap-${group.key}") {
                                        Spacer(Modifier.height(BiuZSpacing.sm))
                                    }
                                    item(key = "proj-${group.key}") {
                                        ProjectNodeRow(
                                            group = group,
                                            expanded = expanded,
                                            onToggle = {
                                                collapsedProjects =
                                                    if (expanded) collapsedProjects + group.key
                                                    else collapsedProjects - group.key
                                            },
                                        )
                                    }
                                    if (expanded) {
                                        items(group.rows, key = { it.id }) { conversation ->
                                            renderConversationRow(conversation, true)
                                        }
                                    }
                                }
                            }
                            if (board.tasks.isNotEmpty()) {
                                item(key = "hdr-tasks") { SessionSectionHeader(ungroupedTitle) }
                                items(board.tasks, key = { it.id }) { conversation ->
                                    renderConversationRow(conversation, false)
                                }
                            }
                            item { Spacer(Modifier.height(BiuZSpacing.sm)) }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            sections.forEach { section ->
                                item(key = "header-${section.title}-${section.rows.firstOrNull()?.id ?: ""}") {
                                    SessionSectionHeader(section.title)
                                }
                                items(section.rows, key = { it.id }) { conversation ->
                                    renderConversationRow(conversation, false)
                                }
                            }
                            // G34 置顶溢出「显示更多/收起」（web taskList.showMore/showLess 同语义）
                            if (pinnedOverflowCount(rows) > 0 && viewMode != ListViewMode.PROJECTS) {
                                item(key = "pinned-more") {
                                    Text(
                                        text = if (showAllPinned) "收起置顶"
                                        else "显示更多置顶（${pinnedOverflowCount(rows)}）",
                                        fontSize = 13.sp,
                                        color = BiuZText3,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showAllPinned = !showAllPinned }
                                            .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.sm)
                                            .testTag("04-act-pinned-more"),
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(BiuZSpacing.sm)) }
                        }
                    }
                }
            }

            // 工具区（2026-11-24 排版修复）：原实现把「文件 →」「设置 →」直接挂在列表尾部，
            // 且用 bodyLarge(15sp) + colorScheme.primary（最重墨色）——比同屏所有列表文字都大、
            // 又没有分组标题，读起来是两个游离的大字（用户报「排版混乱」）。
            // 改为：与列表区同构的区头（14sp/text3）+ 行（14sp/text2）+ 右侧导航箭头，
            // 并把组间距离提到 xl（24dp）——与「置顶/项目」的分区间隔同档。
            Spacer(Modifier.height(BiuZSpacing.lg))
            SessionSectionHeader(stringResource(R.string.sessions_tools_group))
            // G38 统一搜索入口（web ⌘K CommandCenter 移动对位；会话+文件跨区检索）
            SessionsToolsRow(
                label = "统一搜索",
                tag = "04-act-command-center",
                onClick = onOpenCommandCenter,
            )
            SessionsToolsRow(
                label = stringResource(R.string.sessions_open_files),
                tag = "04-act-open-files",
                onClick = onOpenFiles,
            )
            SessionsToolsRow(
                label = stringResource(R.string.sessions_open_settings),
                tag = null,
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(BiuZSpacing.lg))
        }
    }

    // 新建会话 sheet（模型/思考会话前选择 + 提交门；创建成功导航进会话）
    if (showNewSheet) {
        NewConversationSheet(
            store = surface.conversation,
            onDismiss = { showNewSheet = false },
            onCreated = { conversation ->
                showNewSheet = false
                onOpenChat(conversation.id)
            },
        )
    }
}

/**
 * 新建会话 sheet（iOS NewConversationSheet 连接态直译的 Kotlin 对位）：
 * 标题输入 + 模型/思考会话前选择（getView 词表 + 复合键勾选跟随点选 + 空清单
 * 「重新获取」）+ **提交门**——用户明确做过选择但解析不出完整 provider 映射
 * （getView 失败/行键失效）时弹「用默认模型继续」确认，禁静默裸发（「选了等于
 * 没选：桌面按默认开跑、用户侧零反馈」根因）；纯未选择直通桌面默认不拦。
 * 创建失败如实上屏（空 id + lastCreateFailureText 原文，禁假成功导航）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationSheet(
    store: ConversationStore,
    onDismiss: () -> Unit,
    onCreated: (Conversation) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var modelInfo by remember { mutableStateOf<ModelSelectionInfo?>(null) }
    var modelLoadFailed by remember { mutableStateOf(false) }
    // 行键「套餐|模型」（provider 复合解析；null = 未显式点选，勾跟随桌面绑定预勾）
    var selectedModelRowKey by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var selectedThought by remember { mutableStateOf<String?>(null) }
    var thoughtOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showGateConfirm by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    /** 创建失败详情（null = 无在案失败；空串 = 未连接泛因；非空 = lastCreateFailureText 原文）。 */
    var createFailureDetail by remember { mutableStateOf<String?>(null) }

    suspend fun reloadModelInfo() {
        val info = store.modelSelectionView()
        modelInfo = info
        modelLoadFailed = info?.hasSelectableModels != true
        if (selectedModel == null) {
            selectedModel = info?.activeModel ?: info?.models?.firstOrNull()
        }
        if (selectedThought == null && thoughtOptions.isEmpty()) {
            val model = selectedModel
            thoughtOptions = if (model != null) store.thoughtLevels(model) else emptyList()
            // 未显式选择时回填桌面端当前档（仅当该档位在词表内——iOS :1051-1055）
            val active = info?.activeThoughtLevel
            if (selectedThought == null && active != null && thoughtOptions.contains(active)) {
                selectedThought = active
            }
        }
    }

    /**
     * 实际创建（`selection` 为确认弹层「用默认继续」时的 null 直通；iOS performSubmit
     * :285-331 连接态常规路径同构）。失败如实透出（真实拒收原因优先——上游 schema
     * strict，载荷任一键不合形即整条拒收；空 id 禁假成功导航）。
     */
    suspend fun performSubmit(selection: NewSessionModelSelection?) {
        if (submitting) return
        submitting = true
        try {
            // 空描述 + 已选模型 = **模型无处可带**：createSession 的 firstInput.modelSelection
            // 只在有文本时下发（空 firstInput 会让桌面开空轮）。iOS 早用交接箱修过
            // （NewConversationSheet.swift:316-325 / AGENTS v1.21），Android 此前未移植——
            // 实测「选 ccgithub/deepseek 建会话 → 会话回落 GLM-5.4」即此。改为 draft 创建
            // 并把选择交给会话页，随首条 sendText.modelSelection 下发。
            val trimmedTitle = title.trim()
            val handoffNeeded = trimmedTitle.isEmpty() && selection != null
            val conversation = store.createConversation(
                title = trimmedTitle,
                directory = "",
                executor = ExecutorKind.cloudSandbox,
                // 交接路径下 createSession 不带选择（空 firstInput 无通道）
                modelSelection = if (handoffNeeded) null else selection,
            )
            if (conversation.id.isNotEmpty()) {
                createFailureDetail = null
                if (handoffNeeded) {
                    cn.biuz.android.app.ui.chat.NewSessionHandoffBox.deposit(
                        cn.biuz.android.app.ui.chat.NewSessionHandoff(
                            draftText = "",
                            modelSelection = selection,
                        ),
                        forSessionId = conversation.id,
                    )
                }
                onCreated(conversation)
            } else {
                // 拒因原文透传（空串 = 无在案拒因 → 渲染层用未连接泛因文案）
                createFailureDetail = store.lastCreateFailureText()
            }
        } finally {
            submitting = false
        }
    }

    LaunchedEffect(store) { reloadModelInfo() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BiuZBgElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg)
                .padding(bottom = BiuZSpacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.new_session_title),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = BiuZText,
            )
            // 标题/首条指令（空 = draft 空会话）
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = TextStyle(fontSize = 15.sp, color = BiuZText, lineHeight = 20.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.md),
                maxLines = 5,
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            text = stringResource(R.string.new_session_input_hint),
                            style = TextStyle(fontSize = 15.sp),
                            color = BiuZText3,
                        )
                    }
                    inner()
                },
            )
            // 模型选行（getView 词表分节 + 复合键勾选跟随点选 + 空清单「重新获取」）
            Text(
                text = stringResource(R.string.new_session_model_label),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText2,
            )
            val info = modelInfo
            if (info == null || !info.hasSelectableModels) {
                ModelRefetchRow(
                    onRefetch = { scope.launch { reloadModelInfo() } },
                )
                if (modelLoadFailed) {
                    Text(
                        text = stringResource(R.string.new_session_model_load_failed),
                        style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                        color = BiuZOrange,
                    )
                }
            } else {
                ModelOptionSection(
                    info = info,
                    pickedRowKey = selectedModelRowKey,
                    onPickModel = { rowKey ->
                        // 选中勾跟随用户点选（selectedModelRowKey）；纯模型名落
                        // selectedModel（思考档词表查询 + 行值展示）
                        selectedModelRowKey = rowKey
                        selectedModel = info.modelLabelForRowKey(rowKey)
                        selectedThought = null
                        scope.launch {
                            thoughtOptions = store.thoughtLevels(selectedModel ?: "")
                        }
                    },
                )
            }
            // 思考等级选行（词表按当前模型查询，缺席退化 getView 词表 → 静态梯）
            Text(
                text = stringResource(R.string.new_session_thought_label),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText2,
            )
            ThoughtOptionSection(
                levels = thoughtOptions.takeIf { it.isNotEmpty() }
                    ?: info?.thoughtLevels?.takeIf { it.isNotEmpty() }
                    ?: ChatViewModel.FALLBACK_THOUGHT_LEVELS,
                pickedLevel = selectedThought,
                activeLevel = info?.activeThoughtLevel,
                onPickThought = { selectedThought = it },
            )
            // 思考档页脚（模型清单未就绪时如实标注——所选档位能否随会话下发取决于
            // provider 映射，不静默假可选；iOS sheetFooter :509-513 同口径）
            if (info?.modelProviders?.isEmpty() != false) {
                Text(
                    text = stringResource(R.string.new_session_thought_footer),
                    style = TextStyle(fontSize = 11.sp),
                    color = BiuZText3,
                )
            }
            // 创建失败持久行（空 id + lastCreateFailureText 原文；禁假成功导航）
            createFailureDetail?.let { detail ->
                Text(
                    text = if (detail.isEmpty()) {
                        stringResource(R.string.new_session_create_fail_generic)
                    } else {
                        stringResource(R.string.new_session_create_fail, detail)
                    },
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = cn.biuz.android.app.ui.theme.BiuZRed,
                )
            }
            // 开始任务（提交门在 submit() 内；在途去重防双创建）
            // 主 CTA = 朱磦实底 + 按压实 + 轻击触觉（用户裁决「主 CTA 用朱磦 / 只给主 CTA 轻击」）
            val submitInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .ctaPress(submitInteraction)
                    .clip(BiuZRadius.medium)
                    .background(if (!submitting) BiuZSeal else BiuZBgInput)
                    .clickable(
                        interactionSource = submitInteraction,
                        indication = null,
                        enabled = !submitting,
                    ) {
                        val selection = ChatViewModel.resolveNewSessionSelection(
                            info = modelInfo,
                            selection = ChatViewModel.NewSessionDraftSelection(
                                selectedModelRowKey = selectedModelRowKey,
                                selectedModel = selectedModel,
                                selectedThought = selectedThought,
                            ),
                        )
                        if (ChatViewModel.submitGateBlocked(
                                rowKey = selectedModelRowKey,
                                thought = selectedThought,
                                resolved = selection,
                            )
                        ) {
                            // 提交门：明确做过选择但解析不出——禁静默裸发，弹确认
                            showGateConfirm = true
                        } else {
                            scope.launch { performSubmit(selection) }
                        }
                    }
                    .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (submitting) {
                        stringResource(R.string.new_session_submitting)
                    } else {
                        stringResource(R.string.new_session_submit)
                    },
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (!submitting) BiuZOnSeal else BiuZText3,
                )
            }
        }
    }

    // 提交门确认弹层（「用默认模型继续」是显式选择，不是静默丢弃）
    if (showGateConfirm) {
        AlertDialog(
            onDismissRequest = { showGateConfirm = false },
            title = { Text(stringResource(R.string.new_session_gate_title)) },
            text = { Text(stringResource(R.string.new_session_gate_message)) },
            confirmButton = {
                Text(
                    text = stringResource(R.string.new_session_gate_confirm),
                    color = BiuZAccent,
                    modifier = Modifier
                        .clickable {
                            showGateConfirm = false
                            // 默认模型直通：selection = nil 显式下发
                            scope.launch { performSubmit(selection = null) }
                        }
                        .padding(BiuZSpacing.sm),
                )
            },
            dismissButton = {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = BiuZText2,
                    modifier = Modifier
                        .clickable { showGateConfirm = false }
                        .padding(BiuZSpacing.sm),
                )
            },
        )
    }
}


// MARK: - 会话行（严格对齐 web home 行：图钉 + 标题 + 时间 + 工作流迷你轨道）
//
// 2026-11-08 用户裁决：移除首字符圆徽、运行转圈、摘要、等待确认胶囊 —— 行只保留
// 图钉/标题/时间/轨道四项。web 侧运行态由轨道点表达（`.capture/sync/.../compare-report.md`）。

// APP-DIFF[D007] business: 任务行信息密度按用户裁决「严格对齐 web」——仅图钉 + 14sp 单行
// 标题 + 12sp 时间 + 工作流迷你轨道；首字头像/状态胶囊/预览摘要/agents working 不实现
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    conversation: Conversation,
    timeLabel: String,
    onOpen: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onMarkUnread: (() -> Unit)? = null,
    /** G33 徽标点按回调（null = 不可暂停——徽标退化为纯指示） */
    onSnoozeCountdown: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 会话行 = 报告 §4.4 第 3 类（行）：按压实，无触觉
    val rowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableScale(rowInteraction)
            // APP-DIFF[D006] platform: web 靠 hover 显现行内文件/归档按钮；触屏无 hover，
            // 长按 combinedClickable 弹菜单（行内双按钮会挤压标题宽度）
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { if (onArchive != null || onMarkUnread != null) menuOpen = true },
            )
            // 行内垂直节奏 = sm(8)：原 4dp 让相邻行几乎粘在一起（真机读作「挤」）
            .padding(vertical = BiuZSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // 置顶标记占**固定 14dp 槽**并居中（2026-11-24 排版修复）：原实现用 10sp 文字
        // 自带行高 + 4dp 尾距，emoji 的固有升降部把 📌 顶得比标题高半个字，并与右侧
        // 指示槽（转圈/红点/蓝点）挤在一起——真机上读作「📌 和转圈叠住了」。
        if (conversation.isPinned) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 18.dp)
                    .padding(end = BiuZSpacing.xxs),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "📌", fontSize = 10.sp)
            }
            Spacer(Modifier.size(BiuZSpacing.tiny))
        }
        // 行前置指示槽（web 权威口径：`deriveTaskLeadingIndicator`
        // taskListItemPresentation.ts:24-51——error 红点 > unread 蓝点 > running 转圈 >
        // 无；三态互斥，同一时刻只出一个）。此前该槽整体缺失，isRunning 只参与排序，
        // 用户所见「进行中的项目/会话不转圈」即此。
        SessionRowLeadingIndicator(
            conversation = conversation,
            modifier = Modifier.padding(end = BiuZSpacing.sm, top = BiuZSpacing.xxs),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // G33 行内待批徽标（web TaskInteractionBadge 移动端同构；D007 行密度
                // 裁决不覆盖它——web 行本身就有该徽标，属对齐而非新增）
                val badgeKind = conversation.pendingInteractionKind
                if (badgeKind == "permission" || badgeKind == "userInput") {
                    if (conversation.pendingApprovalCount > 0) {
                        PendingInteractionBadge(
                            kind = badgeKind,
                            deadlineMs = conversation.pendingInteractionDeadline,
                            visibleAtMs = conversation.pendingInteractionVisibleAt,
                            canSnooze = onSnoozeCountdown != null && conversation.pendingInteractionSnoozedAt == null,
                            onSnooze = onSnoozeCountdown,
                        )
                        Spacer(Modifier.size(BiuZSpacing.xs))
                    }
                }
                // G40 行元数据身份（web TaskListItem.tsx:759-779 同位——时间前；
                // cron ⏰ / off-peak 🌙 两态互斥，不能占左侧状态槽）
                if (conversation.isCronTask || conversation.isOffPeakTask) {
                    Text(
                        text = if (conversation.isCronTask) "⏰" else "🌙",
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.size(BiuZSpacing.xs))
                }
                // G40 残面：worktree `<仓> · <桶>` 标签（web worktreeLabel.ts 同构——
                // 切分隔符取末段/次末段；段数不足两段不显示）+ 远端设备名（仅显
                // serverDisplayName，UUID 禁上屏——taskSourceAttribution.ts:19-24 同规）
                conversation.worktreeRoot?.takeIf { it.isNotEmpty() }?.let { root ->
                    val segments = root.split("/", "\\").filter { it.isNotEmpty() }
                    if (segments.size >= 2) {
                        Text(
                            text = "${segments.last()} · ${segments[segments.size - 2]}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = BiuZText3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("04-worktree-label"),
                        )
                        Spacer(Modifier.size(BiuZSpacing.xs))
                    }
                }
                conversation.serverDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { device ->
                    Text(
                        text = device,
                        fontSize = 10.sp,
                        color = BiuZText3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("04-device-label"),
                    )
                    Spacer(Modifier.size(BiuZSpacing.xs))
                }
                Spacer(Modifier.weight(1f))
                if (timeLabel.isNotEmpty()) {
                    Text(
                        text = timeLabel,
                        fontSize = 12.sp,
                        color = BiuZText2,
                    )
                }
            }
            val activity = conversation.workflowActivity
            if (activity != null && activity.liveRuns.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                SessionWorkflowTrackView(activity = activity)
            }
        }
    }
    // 长按行菜单（web 会话行右键/⋯ 的同位动作）：归档 / 标记未读
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        onArchive?.let { archive ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.row_action_archive)) },
                onClick = {
                    menuOpen = false
                    archive()
                },
            )
        }
        onMarkUnread?.let { markUnread ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.row_action_mark_unread)) },
                onClick = {
                    menuOpen = false
                    markUnread()
                },
            )
        }
    }
    }
}

// MARK: - 行前置指示槽（web deriveTaskLeadingIndicator 同构）

/**
 * 会话行前置指示（16dp 固定槽，三态互斥）——web 权威口径逐条移植：
 * `packages/ui/src/lib/taskListItemPresentation.ts:24-51`
 * - ① `phase === "error"` → 红点（失败）；
 * - ② unreadAt 在场 → 蓝点（未读；tasks-index membership 字段，Android 对应
 *   [Conversation.unreadCount] 的本地置位/待交互数）；
 * - ③ `phase ∈ {prewarming, running}` → 转圈（**只认 sessions-index 实时 phase**，
 *   持久化 status=running 不算——web 注释 line 48-50：历史行不得永远转圈）；
 * - ④ 其余 → 无（**不画占位圆点**，web line 662 注释「无状态只保留对齐槽」）。
 *
 * Android 侧 [Conversation.isRunning] 是「phase 活性 ∪ taskStatus=running 且非终态」
 * 双源合成（[cn.biuz.android.store.SessionMergeTable.toConversations]），第 ③ 档因此
 * 比 web 略宽：跨工作区行只携 taskStatus 无 phase，只认 phase 时全库运行行恒不转
 * （2026-10-09 v1.32 报障根因）。第 ① 档 [Conversation.isFailed] 同源宽容词表。
 */
@Composable
private fun SessionRowLeadingIndicator(
    conversation: Conversation,
    modifier: Modifier = Modifier,
) {
    // 无状态也占槽（web 保留对齐槽；缺槽会让标题横向抖动）
    Box(
        modifier = modifier.size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            conversation.isFailed -> Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BiuZRed, CircleShape)
                    .testTag("04-row-indicator-error"),
            )

            conversation.unreadCount > 0 -> Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(BiuZBlue, CircleShape)
                    .testTag("04-row-indicator-unread"),
            )

            conversation.isRunning -> CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .testTag("04-row-indicator-running"),
                strokeWidth = 2.dp,
                color = BiuZText3,
            )
        }
    }
}

// MARK: - 未连接空态（统一骨架：icon 位 + 结论 + 解释 + 唯一主 CTA）
@Composable
private fun NotConnectedEmpty(
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.sessions_avatar_glyph),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.md))
        Text(
            text = stringResource(R.string.state_not_connected),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = stringResource(R.string.sessions_not_connected_detail),
            style = MaterialTheme.typography.bodySmall,
            color = BiuZText3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.lg))
        Button(
            onClick = onConnect,
            modifier = Modifier.height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.action_connect_desktop))
        }
    }
}

/** 分组标题渲染（kind → 资源；zh 参考词面在 ConversationGrouping，禁按 label 匹配）。 */
@Composable
internal fun sectionKindLabel(kind: SectionKind): String = when (kind) {
    SectionKind.PINNED -> stringResource(R.string.group_pinned)
    SectionKind.TODAY -> stringResource(R.string.group_today)
    SectionKind.YESTERDAY -> stringResource(R.string.group_yesterday)
    SectionKind.EARLIER -> stringResource(R.string.group_earlier)
}

/**
 * 工具区行（文件 / 设置）：与列表行同一字阶（`BiuZFont.body`）与层级色（`text2`），
 * 右侧统一导航箭头，44dp 热区。**不得**再用 `bodyLarge`/`primary`——那会让底部导航
 * 在视觉上压过列表内容（2026-11-24 排版修复）。
 */
@Composable
private fun SessionsToolsRow(
    label: String,
    tag: String?,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableScale(interaction)
            .clip(BiuZRadius.small)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = 44.dp)
            // 左缘 0：与区头/列表行前置槽同基线（原 horizontal=xs 让工具行右移 4dp，
            // 真机 60px vs 区头 48px）；右侧留 4dp 供箭头视觉收口
            .padding(end = BiuZSpacing.xs)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.removeSuffix(" →").removeSuffix("→").trim(),
            style = TextStyle(fontSize = BiuZFont.body.sp),
            color = BiuZText2,
            modifier = Modifier.weight(1f),
        )
        Text(text = "›", fontSize = BiuZGlyph.sm.sp, color = BiuZText3)
    }
}

/** 分区标题（置顶 / 项目 / 任务 / 最近更新 / 未读 共用版式）。 */
@Composable
private fun SessionSectionHeader(title: String) {
    // 左缘 = 0（页边距 16dp 即基线）：区头必须与行的**前置槽**同左缘。
    // 原 start=md(12dp) 让区头比 📌/转圈槽右移 12dp（真机实测 88px vs 56px，差值
    // 恰为一档缩进）；缩进不在 8/12/16 三档节奏里，故收敛时归零。
    Text(
        text = title,
        fontSize = BiuZFont.body.sp,
        fontWeight = FontWeight.Medium,
        color = BiuZText3,
        modifier = Modifier.padding(top = BiuZSpacing.lg, bottom = BiuZSpacing.sm),
    )
}

/**
 * 项目节点行（web `ProjectNodeContent` 最简同构）：文件夹图标 + 项目名 + 运行中/
 * 未读汇总 + 展开箭头；整行点按折叠/展开（折叠态由调用方 rememberSaveable 持有）。
 */
@Composable
private fun ProjectNodeRow(
    group: ProjectGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.small)
            .clickable(onClick = onToggle)
            // 与区头/行前置槽同左缘（start=0 → 页边距 16 即基线）；原 md(12) 让它右移 12dp
            .padding(vertical = BiuZSpacing.sm)
            .testTag("04-project-node"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📁", fontSize = 13.sp)
        Spacer(Modifier.size(BiuZSpacing.sm))
        Text(
            text = group.title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (group.runningCount > 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(6.dp))
        }
        // 折叠态未读点（web `ProjectNodeContent.tsx:290-298` 同构：`!isOpen && hasUnread`
        // 才出蓝点 `size-1.5 rounded-full bg-icon-blue`；展开后子行自身带指示，节点不再重复）。
        // 原实现无条件出未读**数字**——web 侧栏从不显示计数，且展开态重复提示。
        if (!expanded && group.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BiuZBlue, CircleShape)
                    .testTag("04-project-unread-dot"),
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(
            text = if (expanded) "▾" else "▸",
            fontSize = 12.sp,
            color = BiuZText3,
        )
    }
}

// MARK: - G33 行内待批徽标（web TaskInteractionBadge.tsx 移动端同构，2026-11-25 与 iOS 同步）
//
// permission → 静态「待批」徽标不可暂停；userInput → 倒计时环 + 点按暂停
// （snoozeInteractionAutoResolution；首次有效操作永久暂停，重复/迟到幂等 noop）。
// 进度 = (deadline-now)/(deadline-visible)（web :44-58）；snoozed/静默期不画环。

@Composable
internal fun PendingInteractionBadge(
    kind: String,
    deadlineMs: Long?,
    visibleAtMs: Long?,
    canSnooze: Boolean,
    onSnooze: (() -> Unit)?,
) {
    val tint = if (kind == "permission") BiuZOrange else BiuZSeal
    val label = if (kind == "permission") "待批" else "提问"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = BiuZAlpha.faint))
            .clickable(enabled = canSnooze) { onSnooze?.invoke() }
            .padding(horizontal = BiuZSpacing.xs, vertical = BiuZSpacing.xxs)
            .testTag("04-badge-pending-$kind"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // 每秒重算（行本体不随秒重渲——进度环自持时钟）
            if (deadlineMs != null && visibleAtMs != null && !canSnooze.not()) {
                val nowState = remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        nowState.longValue = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1000)
                    }
                }
                val span = deadlineMs - visibleAtMs
                if (span > 0) {
                    val nowMs = nowState.longValue
                    if (nowMs >= visibleAtMs) {
                        val progress = ((deadlineMs - nowMs).toDouble() / span).coerceIn(0.0, 1.0)
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(11.dp)) {
                            drawCircle(color = BiuZBorder, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                            drawArc(
                                color = tint,
                                startAngle = -90f,
                                sweepAngle = (360f * progress).toFloat(),
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                            )
                        }
                    }
                }
            }
            Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}


// MARK: - G36 分组组色（web TASK_GROUP_COLORS 逐字词表；2026-11-25 与 iOS 同步）

/** 色词表顺序（web grouped/types.ts:12-20 TASK_GROUP_COLORS）。 */
private val TASK_GROUP_COLOR_CYCLE = listOf("gray", "red", "orange", "yellow", "green", "blue", "purple")

/** 点按循环下一色（当前色缺席 = 从 gray 起）。 */
internal fun nextGroupColor(current: String?): String {
    val index = TASK_GROUP_COLOR_CYCLE.indexOf(current)
    return TASK_GROUP_COLOR_CYCLE[(index + 1).mod(TASK_GROUP_COLOR_CYCLE.size)]
}

/** 色词 → 渲染色（web TASK_GROUP_COLOR_CLASS 移动端近似映射）。 */
internal fun taskGroupColorValue(raw: String): androidx.compose.ui.graphics.Color = when (raw) {
    "red" -> androidx.compose.ui.graphics.Color(0xFFFDA4AF)
    "orange" -> androidx.compose.ui.graphics.Color(0xFFFDBA74)
    "yellow" -> androidx.compose.ui.graphics.Color(0xFFFCD34D)
    "green" -> androidx.compose.ui.graphics.Color(0xFF6EE7B7)
    "blue" -> androidx.compose.ui.graphics.Color(0xFF7DD3FC)
    "purple" -> androidx.compose.ui.graphics.Color(0xFFC4B5FD)
    else -> androidx.compose.ui.graphics.Color(0xFFD4D4D8)
}

package cn.biuz.android.app.ui.screen

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.chat.ChatViewModel
import cn.biuz.android.app.ui.chat.ModelOptionSection
import cn.biuz.android.app.ui.chat.ModelRefetchRow
import cn.biuz.android.app.ui.chat.SessionWorkflowTrackView
import cn.biuz.android.app.ui.chat.ThoughtOptionSection
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgElevated
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZOnOrange
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
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
 * 呈现（design-spec 屏 04 + 交接裁决）：
 * - LazyColumn 分组「置顶 → 今天 → 昨天 → 更早」（组织态读 membership——isPinned 由
 *   Store 层本地 override → tasks-index membership → 行字段三源 join 定案，UI 直读）；
 * - 行 = 标题首字符圆徽（会话 icon 裁决：icon 用标题首字符）+ 标题 + 摘要 + 时间；
 * - 行级指示（桌面侧栏逐字 parity）：isRunning 双源合成转圈（蓝）+「等待确认」标（橙，
 *   pendingApprovalCount>0）；归档行由 Store 投影滤除；
 * - 未连接空态如实（§1.6 表）：未连接桌面端 / 连接后同步桌面端的会话与消息 / 连接桌面端。
 * 读面失败三态呈现：last-good 列表与失败文案共存，不折叠成空态（工程纪律 §5-12）。
 */
@Composable
fun SessionsScreen(
    session: AppSession,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBackToConnect: () -> Unit,
    onOpenFiles: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showNewSheet by remember { mutableStateOf(false) }

    // Store 换源重绑：列表态/失败文案均以 epoch 为 key（实例级重建，防抱旧 Store）
    var conversations by remember(surface.epoch) { mutableStateOf<List<Conversation>>(emptyList()) }
    var loadFailure by remember(surface.epoch) { mutableStateOf<String?>(null) }
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
    val ungroupedTitle = stringResource(R.string.sessions_section_tasks)
    val timelineTitle = stringResource(R.string.sessions_view_timeline)
    val unreadTitle = stringResource(R.string.sessions_view_unread_label)
    val unreadEmpty = stringResource(R.string.sessions_unread_empty)
    val sections = remember(rows, viewMode, pinnedTitle, ungroupedTitle, timelineTitle, unreadTitle, unreadEmpty) {
        when (viewMode) {
            ListViewMode.PROJECTS -> projectSections(rows, pinnedTitle, ungroupedTitle)
            ListViewMode.TIMELINE -> timelineSections(rows, pinnedTitle, timelineTitle)
            ListViewMode.UNREAD -> unreadSections(rows, pinnedTitle, unreadTitle, unreadEmpty)
            ListViewMode.GROUPED -> emptyList()
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
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.sm),
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
            Spacer(Modifier.height(BiuZSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sessions_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 筛选视图四档（对齐 web sidebar-view-menu）
                Box {
                    Text(
                        text = "≣",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { showViewMenu = true }
                            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
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
                // 新建会话入口（iOS 会话列表 + 新建 sheet 同位；提交门在 sheet 内）
                Text(
                    text = stringResource(R.string.new_session_entry),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showNewSheet = true }
                        .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
                )
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

            when {
                // 未连接空态（§1.6 表：未连接桌面端 / 连接后同步… / 连接桌面端 CTA）
                rows.isEmpty() && (mode is AppSession.Mode.Demo || mode is AppSession.Mode.ConnectFailed) -> {
                    NotConnectedEmpty(
                        onConnect = onBackToConnect,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 连接态首屏：订阅快照未到 = 居中 spinner（5.9 加载态口径，无骨架屏）
                rows.isEmpty() && loadFailure == null && mode is AppSession.Mode.Connected -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 64.dp),
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
                    // 数据源未接入：如实提示，不用项目分组冒充
                    Text(
                        text = stringResource(R.string.sessions_view_grouped_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = BiuZText3,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("04-view-grouped-pending"),
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        sections.forEach { section ->
                            item(key = "header-${section.title}-${section.rows.firstOrNull()?.id ?: ""}") {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BiuZText3,
                                    modifier = Modifier.padding(
                                        top = BiuZSpacing.md,
                                        bottom = BiuZSpacing.xs,
                                    ),
                                )
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
                                )
                            }
                        }
                        item { Spacer(Modifier.height(BiuZSpacing.xl)) }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(onClick = onOpenFiles),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sessions_open_files),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("04-act-open-files"),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(onClick = onOpenSettings),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sessions_open_settings),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
            val conversation = store.createConversation(
                title = title.trim(),
                directory = "",
                executor = ExecutorKind.cloudSandbox,
                modelSelection = selection,
            )
            if (conversation.id.isNotEmpty()) {
                createFailureDetail = null
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
                textStyle = TextStyle(fontSize = 14.5.sp, color = BiuZText, lineHeight = 20.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                    .padding(horizontal = BiuZSpacing.md, vertical = 10.dp),
                maxLines = 5,
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            text = stringResource(R.string.new_session_input_hint),
                            style = TextStyle(fontSize = 14.5.sp),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(BiuZRadius.medium)
                    .background(if (!submitting) BiuZAccent else BiuZBgInput)
                    .clickable(enabled = !submitting) {
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
                    .padding(horizontal = BiuZSpacing.lg, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (submitting) {
                        stringResource(R.string.new_session_submitting)
                    } else {
                        stringResource(R.string.new_session_submit)
                    },
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (!submitting) BiuZOnAccent else BiuZText3,
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

@Composable
private fun SessionRow(
    conversation: Conversation,
    timeLabel: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = BiuZSpacing.xs, horizontal = BiuZSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        if (conversation.isPinned) {
            Text(
                text = "📌",
                fontSize = 10.sp,
                modifier = Modifier.padding(end = BiuZSpacing.xs, top = 2.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                if (timeLabel.isNotEmpty()) {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = BiuZText3,
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
}

// MARK: - 未连接空态（§1.6 统一骨架：icon 位 + 结论 + 解释 + 唯一主 CTA）

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

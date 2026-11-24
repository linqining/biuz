package cn.biuz.android.app.ui.chat

// 会话面板区（iOS SessionPanelsView.swift 直译；goal/plan/工作流/btw 后台/side 子代理）。
//
// 三点桌面基准诉求（iOS 文件头 :1-13 逐字语义）：
// 1. 可折叠——默认收起为一行 chips（数据在场的面板才出 chip），点选展开、再点收起；
//    面板自带收起头（chips 行在消息滚动流内会被推出可视区，收起动作必须在面板本体上）；
// 2. 逐项展开——工作流阶段行**无条件可展开**（桌面 WorkflowRunPhaseList.tsx 同构：
//    此前「有子代理实例才可展开」时无实例行点了没反应——用户报障「点击工作流某项不
//    展开」）；带 sessionId 的子代理实例行可下钻只读转录；
// 3. 控制面——目标暂停/继续（pauseGoal/resumeGoal）、子代理模型（amendWorkflowRunSettings
//    .subagentModel）、并发 agent 数（amendWorkflowRunSettings.maxConcurrency）、运行
//    取消/恢复（cancelBackgroundWork/resumeWorkflowRun【实证·上游仓 command.ts:221/:228】）。
// 边界不变：全部为「客户端发命令、桌面代执行」（ReadOnlyGate command 类放行）。
//
// 面板控制失败反馈**常驻**（下次动作或离开会话清；「失败 3s 气泡一律改常驻」任务口径），
// 成功提示 3s 自清（VM ControlSuccessHint 计时）。VM 零文案：枚举/布尔由本层映射资源。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZAccentDim
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgElevated
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBlueDim
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZGreen
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.BiuZViolet
import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.PlanPanelSummary
import cn.biuz.android.protocol.RemoteGoalSummary
import cn.biuz.android.protocol.SubagentSessionSummary
import cn.biuz.android.protocol.WorkflowActorSummary
import cn.biuz.android.protocol.WorkflowNodeSummary
import cn.biuz.android.protocol.WorkflowRunSummary
import cn.biuz.android.protocol.WorkflowStepStatus
import kotlinx.coroutines.launch

/** 面板体限高（iOS maxHeight 292 同口径：小内容贴合、大内容封顶内部滚动）。 */
private val PANEL_BODY_MAX_HEIGHT = 280.dp

/** 会话面板种类（iOS SessionPanelKind :15-38 直译）。 */
enum class SessionPanelKind {
    Goal,
    Plan,
    Workflow,
    Works,
    Subagents,
    ;

    val labelRes: Int
        get() = when (this) {
            Goal -> R.string.panel_goal
            Plan -> R.string.panel_plan
            Workflow -> R.string.panel_workflow
            Works -> R.string.panel_works
            Subagents -> R.string.panel_subagents
        }

    /** 图标位（iOS SF Symbol 对位字符；纯渲染层）。 */
    val glyph: String
        get() = when (this) {
            Goal -> "◎"
            Plan -> "☰"
            Workflow -> "⑂"
            Works -> "◷"
            Subagents -> "⌗"
        }
}

/**
 * 会话面板区（chips 行 + 单开面板；数据缺席的 Panel 不出 chip——iOS availableKinds
 * :164-172 同源判定，chips 与面板体同源不会错位）。
 */
@Composable
fun SessionPanelsSection(
    viewModel: ChatViewModel,
    expanded: SessionPanelKind?,
    onExpandedChange: (SessionPanelKind?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val goal by viewModel.goalSummary.collectAsStateWithLifecycle()
    val plan by viewModel.planPanel.collectAsStateWithLifecycle()
    val runs by viewModel.workflowRuns.collectAsStateWithLifecycle()
    val works by viewModel.backgroundWorks.collectAsStateWithLifecycle()
    val subagents by viewModel.subagentSessions.collectAsStateWithLifecycle()
    val subagentProjection by viewModel.subagentProjection.collectAsStateWithLifecycle()
    val chatMessages by viewModel.messages.collectAsStateWithLifecycle()
    val activityDismissed by viewModel.activitySummaryDismissed.collectAsStateWithLifecycle()
    val completedActivityCount by viewModel.completedActivityCount.collectAsStateWithLifecycle()
    val subagentDirectory by viewModel.subagentDirectory.collectAsStateWithLifecycle()
    val directoryFailure by viewModel.subagentDirectoryFailure.collectAsStateWithLifecycle()
    val directorySupported by viewModel.subagentDirectorySupported.collectAsStateWithLifecycle()
    val controlFailure by viewModel.controlFailure.collectAsStateWithLifecycle()
    val controlSuccess by viewModel.controlSuccess.collectAsStateWithLifecycle()
    val transcript by viewModel.actorTranscriptSheet.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val available = remember(goal, plan, runs, works, subagents) {
        buildList {
            if (goal != null) add(SessionPanelKind.Goal)
            if (plan != null) add(SessionPanelKind.Plan)
            if (runs.isNotEmpty()) add(SessionPanelKind.Workflow)
            if (works.isNotEmpty()) add(SessionPanelKind.Works)
            if (subagents.isNotEmpty()) add(SessionPanelKind.Subagents)
        }
    }

    // 本轮 Hook 详情（hookInvocation 行不进消息流，按轮汇总；web 同构）
    val hooksThisTurn = remember(chatMessages) {
        chatMessages.mapNotNull { it.hookInvocation }
    }
    val hookInvocationCount = hooksThisTurn.sumOf { it.hookCount }

    // 子代理目录能力探测（有历史 + 未探测才打一发；结果缓存到 store）
    LaunchedEffect(subagentProjection, directorySupported) {
        val endedTotal = subagentProjection?.endedTotal ?: 0
        if (directorySupported == null && endedTotal > 0) {
            viewModel.probeSubagentDirectorySupport()
        }
    }

    // v4.0.0 活动摘要行（web ConversationStatusPanel 的 activity 胶囊）：
    // 运行中/已结束/历史目录三块，数据全空整行不渲染。
    val activitySummary = remember(
        runs, works, subagentProjection, activityDismissed, completedActivityCount,
    ) {
        buildActivitySummaryModel(
            workflowRuns = runs,
            backgroundWorks = works,
            subagentProjection = subagentProjection,
            dismissed = activityDismissed,
            completedActivityCount = completedActivityCount,
            // 历史目录条目数：子代理取 endedTotal（v4 权威历史目录总数），
            // 工作流取已结束 run 数（平台侧派生，web endedWorkflowRunCount 同义）
            endedSubagentCount = subagentProjection?.endedTotal ?: 0,
            endedWorkflowRunCount = runs.count { !it.isLive },
            // 子代理目录入口：本端已有 `session/subagents` 查询面（web
            // useSessionSubagents/EndedDirectoryRow 同源）→ 恒可开；是否显示由
            // 「有历史身份（dismissed.subagentIds 非空）+ endedTotal > 0」决定
            // （ActivitySummaryModel 内判，与 web :1252-1273 同口径）。
            // ⚠ 不可写成 `subagentDirectory != null`——那会造成「入口等数据、数据等入口」
            // 的死锁（目录是按需拉取的，点开才加载）。
            // 只在桌面确实暴露 `session/subagents` 时才出入口（能力探测缓存：
            // 桌面 4.0.0 返 `Method not found` → 隐藏，不留坏 affordance）。
            // 未探测（null）时先不出，等探测结果；探测在下方 LaunchedEffect 触发。
            hasDirectoryTargets = directorySupported == true,
        )
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
        if (activitySummary.visible) {
            ActivitySummaryRow(
                model = activitySummary,
                directory = subagentDirectory,
                directoryFailed = directoryFailure,
                onLoadDirectory = { scope.launch { viewModel.loadSubagentDirectory() } },
                onLoadMore = {
                    val cursor = subagentDirectory?.nextCursor
                    if (cursor != null) scope.launch { viewModel.loadSubagentDirectory(cursor) }
                },
            )
        }
        // 本轮 Hook 详情入口（web ConversationHookDetailsAction 同构）：hookInvocation
        // 行不进消息流，只在此汇总。没有 Hook 的轮不渲染任何占位。
        hookInvocationCount.takeIf { it > 0 }?.let { count ->
            HookDetailsRow(hooks = hooksThisTurn)
        }
        // chips 行（默认收起态全部可见面；每 chip 附紧凑进度徽标）
        if (available.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                available.forEach { kind ->
                    PanelChip(
                        kind = kind,
                        selected = expanded == kind,
                        badge = panelBadgeText(kind, goal, runs, works, subagents),
                        onClick = { onExpandedChange(if (expanded == kind) null else kind) },
                    )
                }
            }
        }
        // 展开面板（面板本体自带收起头；有界高度 + 内部滚动）
        val kind = expanded
        if (kind != null && kind in available) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgCard)
                    .border(1.dp, BiuZViolet.copy(alpha = 0.35f), BiuZRadius.medium)
                    .padding(BiuZSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                PanelCollapseHeader(kind = kind, onCollapse = { onExpandedChange(null) })
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = PANEL_BODY_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    when (kind) {
                        SessionPanelKind.Goal -> goal?.let { goalValue ->
                            GoalPanelView(
                                goal = goalValue,
                                onTogglePause = { scope.launch { viewModel.toggleGoalPause() } },
                            )
                        }

                        SessionPanelKind.Plan -> plan?.let { PlanPanelView(plan = it) }

                        SessionPanelKind.Workflow -> {
                            // 多 run 逐卡（store 排序：活 run 在前、新 run 优先；被替换旧 run
                            // 沉底为历史，活→死分界处标「历史 run」；每 run 独立控制条）
                            runs.forEachIndexed { index, run ->
                                if (index > 0 && !run.isLive &&
                                    runs.subList(0, index).any { it.isLive }
                                ) {
                                    HistoryCaption()
                                }
                                WorkflowPanelCard(
                                    viewModel = viewModel,
                                    run = run,
                                    goal = goal,
                                )
                                if (index < runs.size - 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = BiuZSpacing.xs)
                                            .height(1.dp)
                                            .background(BiuZBorder),
                                    )
                                }
                            }
                        }

                        SessionPanelKind.Works -> WorksPanelView(
                            works = works,
                            onCancel = { workId -> scope.launch { viewModel.cancelWork(workId) } },
                            onResume = { workId, name -> scope.launch { viewModel.resumeWork(workId, name) } },
                        )

                        SessionPanelKind.Subagents -> SubagentsPanelView(
                            subagents = subagents,
                            onOpen = { sub ->
                                viewModel.openActorTranscript(
                                    title = sub.name ?: sub.agentType,
                                    sessionId = sub.childSessionId,
                                )
                            },
                        )
                    }
                }
            }
        }
        // 面板控制反馈行：失败常驻（拒因原文/未送达/未知三态），成功 3s（VM 计时）
        controlFailure?.let { failure ->
            Text(
                text = when (failure) {
                    is ChatViewModel.ControlFailure.Rejected -> failure.text
                    ChatViewModel.ControlFailure.Undelivered ->
                        stringResource(R.string.panel_control_undelivered)
                    ChatViewModel.ControlFailure.UnknownState ->
                        stringResource(R.string.panel_control_unknown)
                },
                style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                color = BiuZOrange,
                modifier = Modifier.testTag("05-panel-feedback"),
            )
        }
        if (controlSuccess) {
            Text(
                text = stringResource(R.string.panel_control_success),
                style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                color = BiuZAccent,
            )
        }
    }
    // 子代理转录 sheet（工作流 actor 行 / 子代理面板行共用下钻；单路径关闭）
    val transcriptState = transcript
    if (transcriptState != null) {
        ActorTranscriptSheet(
            title = transcriptState.title,
            isLoading = transcriptState.loading,
            messages = transcriptState.messages,
            onClose = { viewModel.closeActorTranscript() },
        )
    }
}

// MARK: 本轮 Hook 详情（v4 hookInvocation 汇总）

/**
 * 本轮 Hook 详情入口（web `ConversationHookDetailsAction` 同构）：
 * 折叠标题「Hook · N 次」+ 展开明细（事件名 / 来源 / 时长 / 状态）。
 *
 * 只统计 `didExecute == true` 的执行（web buildHookDetailItems 同口径：admission-only
 * blocked 不算一次执行）。整行在无执行时不出（调用方已按 hookCount>0 门控）。
 */
@Composable
private fun HookDetailsRow(hooks: List<cn.biuz.android.protocol.HookInvocationInfo>) {
    var expanded by remember { mutableStateOf(false) }
    // 汇总执行明细（按 hookIndex 升序；只取 didExecute）
    val items = remember(hooks) {
        hooks.flatMap { hook ->
            hook.executions
                .filter { it.didExecute }
                .sortedBy { it.hookIndex }
                .map { it to hook.hookEventName }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(BiuZBgCard)
                .border(1.dp, BiuZBorder, RoundedCornerShape(999.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp)
                .testTag("05-hook-details-trigger"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(text = "⚓", fontSize = 10.sp, color = BiuZText3)
            Text(
                text = stringResource(R.string.hook_details_label),
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText2,
            )
            Text(
                text = "${items.size}",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = BiuZText3,
            )
            Text(text = if (expanded) "▴" else "▾", fontSize = 9.sp, color = BiuZText3)
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.small)
                    .background(BiuZBgCard)
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs)
                    .testTag("05-hook-details-content"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { (exec, eventName) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = eventName,
                            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = BiuZText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = hookSourceLabel(exec.sourceKind),
                            style = TextStyle(fontSize = 9.5.sp),
                            color = BiuZText3,
                        )
                        exec.durationMs?.let { ms ->
                            Text(
                                text = hookDurationText(ms),
                                style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                                color = BiuZText3,
                            )
                        }
                        exec.displayStatus?.let { status ->
                            hookStatusUi(status)?.let { (glyph, color) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    if (status == "running") {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(9.dp),
                                            strokeWidth = 1.2.dp,
                                            color = BiuZText3,
                                        )
                                    } else {
                                        Text(text = glyph, fontSize = 9.sp, color = color)
                                    }
                                    Text(
                                        text = hookStatusLabel(status),
                                        style = TextStyle(fontSize = 9.5.sp),
                                        color = color,
                                    )
                                }
                                exec.blockReason?.let { reason ->
                                    Text(
                                        text = "：$reason",
                                        style = TextStyle(fontSize = 9.5.sp),
                                        color = BiuZOrange,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 来源词（user / plugin / project；未知原样）。 */
@Composable
private fun hookSourceLabel(sourceKind: String): String = when (sourceKind) {
    "user" -> stringResource(R.string.hook_source_user)
    "plugin" -> stringResource(R.string.hook_source_plugin)
    "project" -> stringResource(R.string.hook_source_project)
    else -> sourceKind
}

/** 时长（web formatHookDuration：<1s 用 ms，否则 s 两位/一位小数）。 */
private fun hookDurationText(durationMs: Long): String {
    val ms = durationMs.coerceAtLeast(0)
    if (ms < 1000) return "${ms}ms"
    val seconds = ms / 1000.0
    return if (ms < 10_000) String.format("%.2fs", seconds) else String.format("%.1fs", seconds)
}

/** 状态字形与色（running 另用转圈，调用方处理）。 */
private fun hookStatusUi(status: String): Pair<String, Color>? = when (status) {
    "running" -> "◌" to BiuZText3
    "blocked" -> "⚠" to BiuZOrange
    "timedOut" -> "⏱" to BiuZOrange
    "cancelled" -> "⊘" to BiuZText3
    "failed" -> "✕" to BiuZRed
    else -> null
}

@Composable
private fun hookStatusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.hook_state_running)
    "blocked" -> stringResource(R.string.hook_state_blocked)
    "timedOut" -> stringResource(R.string.hook_state_timed_out)
    "cancelled" -> stringResource(R.string.hook_state_cancelled)
    "failed" -> stringResource(R.string.hook_state_failed)
    else -> status
}

// MARK: v4.0.0 活动摘要行

/**
 * 活动摘要行（web `ConversationStatusPanel` activity 胶囊的移动端同构）：
 * 一行胶囊 = 状态色点/转圈 + 标签（活跃中/已完成/已停止/失败）+ 计数，
 * 点按展开明细（运行中在上、已结束在下，各自带结果点）。
 *
 * 与 web 的**有意简化**（触屏适配，非功能缺失）：
 * - web 头像是多张 agent 面孔叠图 + 结果徽标；移动端用状态色点 + 计数（信息密度等价）；
 * - web 历史目录入口指向侧栏专属页面（子代理目录/工作流目录），移动端无该面 →
 *   [ActivitySummaryModel.hasSubagentDirectory] 恒 false（`hasDirectoryTargets=false`）。
 */
@Composable
private fun ActivitySummaryRow(
    model: ActivitySummaryModel,
    directory: cn.biuz.android.protocol.SessionSubagentDirectory? = null,
    directoryFailed: Boolean = false,
    onLoadDirectory: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var directoryOpen by remember { mutableStateOf(false) }
    val label = stringResource(
        when {
            model.isRunningAny -> R.string.activity_running
            model.result == ActivityResult.Failed -> R.string.activity_failed
            model.result == ActivityResult.Stopped -> R.string.activity_stopped
            else -> R.string.activity_completed
        },
    )
    // 结果色（web：completed 成功色 / failed 破坏色 / stopped 中性色；运行中另用强调色转圈）
    val accentColor = when {
        model.isRunningAny -> BiuZAccent
        model.result == ActivityResult.Failed -> BiuZRed
        model.result == ActivityResult.Stopped -> BiuZText3
        else -> BiuZGreen
    }
    Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(BiuZBgCard)
                .border(1.dp, BiuZBorder, RoundedCornerShape(999.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp)
                .testTag("05-activity-summary"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (model.isRunningAny) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = accentColor,
                )
            } else {
                Box(modifier = Modifier.size(6.dp).background(accentColor, CircleShape))
            }
            Text(
                text = label,
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (model.isRunningAny) BiuZAccent else BiuZText2,
            )
            // 计数：null = 完整历史读失败（显示「若干」而非 0，web count===null 同义）
            val countText = when {
                model.count == null -> stringResource(R.string.activity_completed_unknown)
                model.count == 0 && !model.isRunningAny -> null
                else -> "${model.count}"
            }
            countText?.let {
                Text(
                    text = it,
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = accentColor,
                )
            }
            if (model.ended.isNotEmpty() || model.running.isNotEmpty()) {
                Text(text = if (expanded) "▴" else "▾", fontSize = 9.sp, color = BiuZText3)
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.small)
                    .background(BiuZBgCard)
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                model.running.forEach { item ->
                    ActivityDetailLine(
                        kind = item.kind,
                        title = item.title,
                        result = null,
                        summary = null,
                    )
                }
                model.ended.forEach { item ->
                    ActivityDetailLine(
                        kind = item.kind,
                        title = item.title,
                        result = item.result,
                        summary = item.summary,
                    )
                }
            }
        }
        // 子代理目录入口（web EndedDirectoryRow「子代理目录 · N」同构）：
        // 点开按需拉 `session/subagents`；拉到后展开历史清单 + 「加载更多」。
        if (model.hasSubagentDirectory && onLoadDirectory != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.small)
                    .clickable {
                        directoryOpen = !directoryOpen
                        if (directoryOpen && directory == null) onLoadDirectory()
                    }
                    .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp)
                    .testTag("05-activity-subagent-directory"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(text = "⌗", fontSize = 9.sp, color = BiuZText3)
                Text(
                    text = stringResource(R.string.activity_directory_subagents),
                    style = TextStyle(fontSize = 11.sp),
                    color = BiuZText2,
                )
                Text(
                    text = "${model.subagentDirectoryCount}",
                    style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                )
                Spacer(Modifier.weight(1f))
                Text(text = if (directoryOpen) "▴" else "▸", fontSize = 9.sp, color = BiuZText3)
            }
            if (directoryOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.small)
                        .background(BiuZBgCard)
                        .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs)
                        .testTag("05-activity-subagent-directory-content"),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (directory == null && directoryFailed) {
                        // 失败如实上屏 + 重试（不得停在「正在加载…」，AGENTS §5-12）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.activity_directory_failed),
                                style = TextStyle(fontSize = 10.5.sp),
                                color = BiuZOrange,
                            )
                            Text(
                                text = stringResource(R.string.activity_directory_retry),
                                style = TextStyle(fontSize = 10.5.sp),
                                color = BiuZAccent,
                                modifier = Modifier
                                    .clickable { onLoadDirectory?.invoke() }
                                    .testTag("05-activity-subagent-directory-retry"),
                            )
                        }
                    } else if (directory == null) {
                        Text(
                            text = stringResource(R.string.activity_directory_loading),
                            style = TextStyle(fontSize = 10.5.sp),
                            color = BiuZText3,
                        )
                    } else {
                        directory.items.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = "⌗", fontSize = 9.sp, color = BiuZText3)
                                Text(
                                    text = item.title.ifEmpty { item.subagentType },
                                    style = TextStyle(fontSize = 11.sp),
                                    color = BiuZText2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = directoryStatusLabel(item.status),
                                    style = TextStyle(fontSize = 9.5.sp),
                                    color = when (item.status) {
                                        "success" -> BiuZGreen
                                        "failed" -> BiuZRed
                                        "lost" -> BiuZOrange
                                        else -> BiuZText3
                                    },
                                )
                            }
                        }
                        val next = directory.nextCursor
                        if (next != null && onLoadMore != null) {
                            Text(
                                text = stringResource(R.string.activity_directory_more),
                                style = TextStyle(fontSize = 10.5.sp),
                                color = BiuZAccent,
                                modifier = Modifier
                                    .clickable { onLoadMore() }
                                    .padding(vertical = 3.dp)
                                    .testTag("05-activity-subagent-directory-more"),
                            )
                        }
                        if (directory.items.isEmpty()) {
                            Text(
                                text = stringResource(R.string.activity_directory_empty),
                                style = TextStyle(fontSize = 10.5.sp),
                                color = BiuZText3,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 目录条目状态词（success|failed|cancelled|lost）。 */
@Composable
private fun directoryStatusLabel(status: String): String = when (status) {
    "success" -> stringResource(R.string.activity_directory_status_success)
    "failed" -> stringResource(R.string.activity_directory_status_failed)
    "cancelled" -> stringResource(R.string.activity_directory_status_cancelled)
    "lost" -> stringResource(R.string.activity_directory_status_lost)
    else -> status
}

/** 明细行：种类字形 + 标题 + 结果点 + 摘要（单行截断）。 */
@Composable
private fun ActivityDetailLine(
    kind: EndedActivity.Kind,
    title: String,
    result: ActivityResult?,
    summary: String?,
) {
    val glyph = when (kind) {
        EndedActivity.Kind.Bash -> "❯"
        EndedActivity.Kind.Workflow -> "⑂"
        EndedActivity.Kind.Subagent -> "⌗"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text = glyph, fontSize = 9.sp, color = BiuZText3)
        Text(
            text = title,
            style = TextStyle(fontSize = 11.sp),
            color = BiuZText2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (summary != null && summary.isNotEmpty()) {
            Text(
                text = summary,
                style = TextStyle(fontSize = 10.sp),
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (result != null) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(
                        when (result) {
                            ActivityResult.Completed -> BiuZGreen
                            ActivityResult.Failed -> BiuZRed
                            ActivityResult.Stopped -> BiuZText3
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

// MARK: chips 行

@Composable
private fun PanelChip(
    kind: SessionPanelKind,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) BiuZAccentDim.copy(alpha = 0.55f) else BiuZBgCard)
            .border(
                1.dp,
                if (selected) BiuZAccent.copy(alpha = 0.6f) else BiuZBorder,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp)
            .testTag("05-panel-chip-${kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = kind.glyph, fontSize = 10.sp, color = if (selected) BiuZAccent else BiuZViolet)
        Text(
            text = stringResource(kind.labelRes),
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = if (selected) BiuZAccent else BiuZText2,
        )
        badge?.let {
            Text(
                text = it,
                style = TextStyle(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = if (selected) BiuZAccent else BiuZText3,
            )
        }
    }
}

/** chip 徽标：工作流进度 n/m（多 run「N活/M」）；后台运行中计数；子代理条目数；目标暂停标。 */
private fun panelBadgeText(
    kind: SessionPanelKind,
    goal: RemoteGoalSummary?,
    runs: List<WorkflowRunSummary>,
    works: List<BackgroundWorkSummary>,
    subagents: List<SubagentSessionSummary>,
): String? = when (kind) {
    SessionPanelKind.Goal -> if (goal?.isPaused == true) "已暂停" else null

    SessionPanelKind.Plan -> null

    SessionPanelKind.Workflow ->
        if (runs.size > 1) {
            "${runs.count { it.isLive }}活/${runs.size}"
        } else {
            runs.firstOrNull()?.let { "${it.doneCount}/${it.nodes.size}" }
        }

    // 运行中优先（用户裁决「运行中的参考价值更大」；上游 hasBackgroundWork 派生口径同源）
    SessionPanelKind.Works -> works.count { it.isRunning }.takeIf { it > 0 }?.let { "$it 运行中" }
        ?: "${works.size}"

    SessionPanelKind.Subagents -> "${subagents.size}"
}

@Composable
private fun PanelCollapseHeader(kind: SessionPanelKind, onCollapse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCollapse)
            .padding(vertical = BiuZSpacing.xs)
            .testTag("05-panel-act-collapse-${kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        Text(text = kind.glyph, fontSize = 10.sp, color = BiuZViolet)
        Text(
            text = stringResource(kind.labelRes),
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = BiuZViolet,
        )
        Spacer(Modifier.weight(1f))
        Text(text = "▴", fontSize = 10.sp, color = BiuZText3)
    }
}

@Composable
private fun HistoryCaption() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiuZSpacing.xs)
            .testTag("05-wf-history-caption"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.wf_history_caption),
            style = TextStyle(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
            color = BiuZText3,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(BiuZBorder),
        )
    }
}

// MARK: 目标面板（goal 文本 + 暂停/继续；命令面 setGoalPaused → pauseGoal/resumeGoal）

@Composable
fun GoalPanelView(goal: RemoteGoalSummary, onTogglePause: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("05-panel-goal"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Text(text = "◎", fontSize = 12.sp, color = BiuZViolet)
            Text(
                text = stringResource(R.string.panel_goal_title),
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText,
            )
            Spacer(Modifier.weight(1f))
            PanelCapsuleButton(
                label = stringResource(if (goal.isPaused) R.string.goal_resume else R.string.goal_pause),
                onClick = onTogglePause,
                modifier = Modifier.testTag("05-goal-act-pause"),
            )
        }
        Text(
            text = goal.text,
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            color = BiuZText2,
        )
    }
}

// MARK: 计划面板（plan 正文只读卡；审批仍走会话流审批卡，不在此重复）

@Composable
fun PlanPanelView(plan: PlanPanelSummary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("05-panel-plan"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Text(text = "☰", fontSize = 12.sp, color = BiuZViolet)
            Text(
                text = plan.title ?: stringResource(R.string.panel_plan_title),
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            plan.rawStatus?.takeIf { it.isNotEmpty() }?.let {
                StatusPill(text = planStatusText(it), color = BiuZText3)
            }
        }
        Text(
            text = plan.content,
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            color = BiuZText2,
        )
    }
}

/** 计划状态原词 → 本地化文案（宽容映射；未知原词回退原文）（iOS planStatusText :512-521）。 */
fun planStatusText(raw: String): String = when (raw) {
    "running", "active" -> "运行中"
    "completed", "done" -> "已完成"
    "failed", "errored" -> "失败"
    "stopped" -> "已停止"
    "pending", "draft" -> "待执行"
    else -> raw
}

// MARK: 工作流面板卡（单 run：头部 + 阶段链 + 控制条；iOS WorkflowPanelContent :551-925 直译）

@Composable
fun WorkflowPanelCard(
    viewModel: ChatViewModel,
    run: WorkflowRunSummary,
    goal: RemoteGoalSummary?,
    modifier: Modifier = Modifier,
) {
    var expandedPhases by remember(run.id) { mutableStateOf(setOf<String>()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("05-panel-workflow"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        // 头部：工作流名 + 五态胶囊 + 进度计数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Text(text = "⑂", fontSize = 13.sp, color = BiuZViolet)
            Text(
                text = run.name,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusPill(text = runStatusText(run.rawStatus), color = runStatusColor(run.rawStatus))
            if (run.truncated) {
                Text(
                    text = stringResource(R.string.wf_truncated),
                    style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZOrange,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${run.doneCount}/${run.nodes.size}",
                style = TextStyle(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = if (run.rawStatus == "completed") BiuZAccent else BiuZText3,
            )
        }
        WorkflowPhaseChain(
            run = run,
            expandedPhases = expandedPhases,
            onTogglePhase = { nodeId ->
                expandedPhases = if (nodeId in expandedPhases) {
                    expandedPhases - nodeId
                } else {
                    expandedPhases + nodeId
                }
            },
            onOpenActor = { actor ->
                viewModel.openActorTranscript(title = actor.name, sessionId = actor.sessionId)
            },
        )
        WorkflowControlBar(
            viewModel = viewModel,
            run = run,
            goal = goal,
        )
    }
}

/** run 五态原词 → 文案（桌面五态闭集；未知回退「待执行」）（iOS runStatusText :906-914）。 */
fun runStatusText(rawStatus: String): String = when (rawStatus) {
    "running" -> "运行中"
    "completed" -> "已完成"
    "errored" -> "失败"
    "stopped" -> "已停止"
    else -> "待执行"
}

fun runStatusColor(rawStatus: String): Color = when (rawStatus) {
    "running" -> BiuZBlue
    "completed" -> BiuZAccent
    "errored" -> BiuZRed
    else -> BiuZText3
}

/** 状态胶囊（compact tag 同构）。 */
@Composable
fun StatusPill(text: String, color: Color) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        ),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// MARK: 阶段链（桌面同构：阶段行 n/m + chevron；展开为子代理实例行）

@Composable
fun WorkflowPhaseChain(
    run: WorkflowRunSummary,
    expandedPhases: Set<String>,
    onTogglePhase: (String) -> Unit,
    onOpenActor: (WorkflowActorSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        run.nodes.forEachIndexed { index, node ->
            PhaseRowScope(
                run = run,
                node = node,
                index = index,
                isLast = index == run.nodes.size - 1,
                expanded = node.id in expandedPhases,
                onTogglePhase = onTogglePhase,
                onOpenActor = onOpenActor,
            )
        }
    }
}

/** 单阶段行（节点圆点 + 连接线 + 阶段头 + 展开体；remember(run, node) 缓存 actors 归属）。 */
@Composable
private fun PhaseRowScope(
    run: WorkflowRunSummary,
    node: WorkflowNodeSummary,
    index: Int,
    isLast: Boolean,
    expanded: Boolean,
    onTogglePhase: (String) -> Unit,
    onOpenActor: (WorkflowActorSummary) -> Unit,
) {
    val actors = remember(run, node) { run.actors(boundTo = node) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
    ) {
        // 左列：节点状态圆点 + 连接线
        Column(modifier = Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NodeDot(node.status)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(18.dp)
                        .background(BiuZBorder),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (!isLast) BiuZSpacing.md else 0.dp),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
        ) {
            // 阶段行（**无条件可展开**——桌面同构；无实例行翻面为空也不给死按钮）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTogglePhase(node.id) }
                    .padding(vertical = 2.dp)
                    .testTag("05-wf-phase-$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(
                    text = node.label,
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = if (node.status == WorkflowStepStatus.running) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    ),
                    color = if (node.status == WorkflowStepStatus.done) BiuZText3 else BiuZText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (actors.isNotEmpty()) {
                    Text(
                        text = "${actors.count { it.status == WorkflowStepStatus.done }}/${actors.size}",
                        style = TextStyle(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = BiuZText3,
                    )
                }
                Text(text = if (expanded) "▾" else "▸", fontSize = 9.sp, color = BiuZText3)
            }
            if (expanded) {
                // 子代理实例行（桌面「N 个任务」同构；sessionId 在场可下钻转录）
                actors.forEach { actor -> ActorRow(actor, onOpenActor) }
            } else {
                node.summary?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontSize = 11.sp),
                        color = BiuZText3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActorRow(actor: WorkflowActorSummary, onOpen: (WorkflowActorSummary) -> Unit) {
    val actionable = actor.sessionId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = BiuZSpacing.md)
            .clip(BiuZRadius.small)
            .background(BiuZBlueDim.copy(alpha = 0.5f))
            .clickable(enabled = actionable) { onOpen(actor) }
            .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp)
            .testTag("05-wf-actor-${actor.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = "⌗",
            fontSize = 11.sp,
            color = if (actor.status == WorkflowStepStatus.running) BiuZBlue else BiuZText3,
        )
        Text(
            text = actor.name ?: stringResource(R.string.wf_actor_default),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = BiuZText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        actor.tasksTotal?.let {
            Text(
                text = stringResource(R.string.wf_actor_tasks, it),
                style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                color = BiuZText3,
            )
        }
        if (actionable) {
            Text(text = "▸", fontSize = 9.sp, color = BiuZText3)
        }
        when (actor.status) {
            WorkflowStepStatus.running -> PanelSpinner(size = 11.dp, color = BiuZBlue)
            WorkflowStepStatus.done -> Text(text = "✓", fontSize = 12.sp, color = BiuZAccent)
            else -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .border(1.2.dp, BiuZBorderStrong, CircleShape),
            )
        }
    }
}

/** 节点状态圆点（done 复选 / running spinner / failed 红叉 / pending 空心）（iOS nodeDot :884-904）。 */
@Composable
private fun NodeDot(status: WorkflowStepStatus) {
    when (status) {
        WorkflowStepStatus.done -> Text(text = "✓", fontSize = 14.sp, color = BiuZAccent)
        WorkflowStepStatus.running -> PanelSpinner(size = 14.dp, color = BiuZBlue)
        WorkflowStepStatus.failed -> Text(text = "✕", fontSize = 14.sp, color = BiuZRed)
        WorkflowStepStatus.pending -> Box(
            modifier = Modifier
                .size(13.dp)
                .border(1.5.dp, BiuZBorderStrong, CircleShape),
        )
    }
}

// MARK: 控制条（暂停/继续 + 子代理模型 + 并发数 + 取消/恢复）

@Composable
private fun WorkflowControlBar(
    viewModel: ChatViewModel,
    run: WorkflowRunSummary,
    goal: RemoteGoalSummary?,
) {
    val scope = rememberCoroutineScope()
    val models by viewModel.modelSelection.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        // 目标暂停/继续（桌面顶栏同语义；无 goal 不渲染——pauseGoal 对无目标会话是 noop）
        if (goal != null) {
            PanelCapsuleButton(
                label = stringResource(if (goal.isPaused) R.string.goal_resume_act else R.string.goal_pause_act),
                onClick = { scope.launch { viewModel.toggleGoalPause() } },
                modifier = Modifier.testTag("05-wf-act-pause"),
            )
        }
        // 子代理模型（amendWorkflowRunSettings.subagentModel；null = 跟随主模型；
        // 按套餐分组展示，数据缺席退化为平铺列表）
        ControlMenuCapsule(
            icon = "⌗",
            text = run.subagentModel?.let { shortModelName(it) }
                ?: stringResource(R.string.wf_follow_main_model),
            items = buildList {
                add(stringResource(R.string.wf_follow_main_model) to null)
                if (models?.planGroups.isNullOrEmpty()) {
                    models?.models?.forEach { model -> add(model to model) }
                } else {
                    models?.planGroups?.forEach { group ->
                        group.models.forEach { model -> add("${group.plan} · $model" to model) }
                    }
                }
            },
            currentKey = run.subagentModel,
            onPick = { model -> scope.launch { viewModel.setSubagentModel(model, run.workId ?: run.id) } },
            modifier = Modifier.testTag("05-wf-act-model"),
        )
        // 并发 agent 数（amendWorkflowRunSettings.maxConcurrency；1..min(ceiling,8) + 无上限）
        val choices = remember(run.concurrencyCeiling) {
            (1..(maxOf(run.concurrencyCeiling ?: 4, 4).coerceAtMost(8))).toList()
        }
        ControlMenuCapsule(
            icon = "≡",
            text = run.concurrencyCeiling?.let { stringResource(R.string.wf_concurrency_n, it) }
                ?: stringResource(R.string.wf_concurrency_default),
            items = buildList {
                choices.forEach { limit -> add("$limit" to limit.toString()) }
                add(stringResource(R.string.wf_no_limit) to null)
            },
            currentKey = run.concurrencyCeiling?.toString(),
            onPick = { raw ->
                scope.launch { viewModel.setMaxConcurrency(raw?.toIntOrNull(), run.workId ?: run.id) }
            },
            modifier = Modifier.testTag("05-wf-act-concurrency"),
        )
        Spacer(Modifier.weight(1f))
        // 运行取消（live 且 cancellable）/ 恢复（stopped+resumable）——workId 取该 run 卡
        // 自带值（store.workflowRuns 仲裁已把滞留活态旧 run 降级 stopped，命令不再打到
        // 死 workId——「取消按钮取活 run」的完整链路）
        if (run.isLive && run.cancellable) {
            PanelCapsuleButton(
                label = stringResource(R.string.wf_cancel_run),
                onClick = { scope.launch { viewModel.cancelWork(run.workId ?: run.id) } },
                modifier = Modifier.testTag("05-wf-act-cancel"),
            )
        } else if (!run.isLive && run.resumable) {
            PanelCapsuleButton(
                label = stringResource(R.string.wf_resume_run),
                onClick = { scope.launch { viewModel.resumeWork(run.workId ?: run.id, run.name) } },
                modifier = Modifier.testTag("05-wf-act-resume"),
            )
        }
    }
}

/** 长模型名缩短显示（iOS shortModelName :824-831 逐字直译）。 */
fun shortModelName(raw: String): String {
    val afterSlash = if (raw.contains('/')) raw.substringAfterLast('/') else raw
    val dollar = afterSlash.indexOf('$')
    return if (dollar >= 0) {
        "${afterSlash.substring(0, dollar)}·${afterSlash.substring(dollar + 1)}"
    } else {
        afterSlash
    }
}

/** 下拉菜单胶囊（iOS Menu 控制钮对位：icon + 文案 + 候选；当前值打勾）。 */
@Composable
private fun ControlMenuCapsule(
    icon: String,
    text: String,
    items: List<Pair<String, String?>>,
    currentKey: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(BiuZBgInput)
                .clickable { open = true }
                .padding(horizontal = BiuZSpacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = icon, fontSize = 9.sp, color = BiuZText3)
            Text(
                text = text,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, key) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (key == currentKey) "✓ $label" else label,
                            fontSize = 13.sp,
                            color = BiuZText,
                        )
                    },
                    onClick = {
                        open = false
                        onPick(key)
                    },
                )
            }
        }
    }
}

/** 面板小钮（Capsule 语言：弱底 + 文案）。 */
@Composable
private fun PanelCapsuleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = BiuZText2,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BiuZBgInput)
            .clickable(onClick = onClick)
            .padding(horizontal = BiuZSpacing.sm, vertical = 4.dp),
    )
}

@Composable
private fun PanelSpinner(size: Dp, color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        strokeWidth = 1.5.dp,
        color = color,
    )
}

// MARK: btw 后台工作面板
//
// **口径（2026-11-24 真机取证修复）**：运行中置顶 → 已结束（按 endedAt 降序）。
//
// 原实现「只渲染运行中条目」制造了一个自相矛盾的界面：chip 徽标统计**全部**条目
// （`panelBadgeText` 的 Works 分支运行中优先、否则显总数），而面板体只画 running——
// 徽标写着「后台 4」、展开却是**空白面板**（真机实测，4 条 bash 全为 resultPending）。
// 用户无法判断是「没有内容」还是「渲染坏了」。
//
// 与 web 的对位：web 没有独立的后台胶囊，backgroundWorks 的 running 进 activity 胶囊的
// 运行组、已结束进 `endedActivities`（ConversationStatusPanel.tsx:1064-1072）——**两者都
// 可见**。移动端保留独立 chip（下钻入口更直接），因此面板必须同样把两类都列出来。
// 运行中判定 = rawStatus == "running"（上游 backgroundWorkSummarySchema 官方派生口径）。

@Composable
fun WorksPanelView(
    works: List<BackgroundWorkSummary>,
    onCancel: (String) -> Unit,
    onResume: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(works) { orderWorksForPanel(works) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("05-panel-works"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        ordered.forEach { work -> WorkRow(work, onCancel, onResume) }
    }
}

/**
 * 后台面板行序（纯函数，可单测）：运行中在前；已结束按 `endedAt`（缺席退 `startedAt`）
 * 降序、再缺席沉底。与活动摘要的 `startedAt` 升序**不同**——面板看的是「最近发生了什么」。
 */
internal fun orderWorksForPanel(works: List<BackgroundWorkSummary>): List<BackgroundWorkSummary> =
    works.sortedWith(
        compareByDescending<BackgroundWorkSummary> { it.isRunning }
            .thenByDescending { it.endedAt ?: it.startedAt ?: Long.MIN_VALUE },
    )

@Composable
private fun WorkRow(
    work: BackgroundWorkSummary,
    onCancel: (String) -> Unit,
    onResume: (String, String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.small)
            .background(BiuZBlueDim.copy(alpha = 0.5f))
            .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(text = "◷", fontSize = 11.sp, color = if (work.isRunning) BiuZBlue else BiuZText3)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = work.title ?: work.workId,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // blocked = 桌面忙等（上游 backgroundWorkSummarySchema.blocked）
                if (work.blocked) {
                    Text(
                        text = stringResource(R.string.works_blocked),
                        style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                        color = BiuZOrange,
                    )
                }
                Text(
                    text = work.statusText,
                    style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                )
                worksDurationText(work)?.let {
                    Text(
                        text = "· $it",
                        style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                        color = BiuZText3,
                    )
                }
            }
        }
        if (work.resumable) {
            WorkRowButton(label = stringResource(R.string.works_act_resume)) { onResume(work.workId, work.title) }
        }
        if (work.cancellable) {
            WorkRowButton(label = stringResource(R.string.works_act_cancel)) { onCancel(work.workId) }
        }
    }
}

/** 行状态时长：已运行/耗时（startedAt/endedAt；运行中=至今，已结束=跨度）（iOS :1021-1030）。 */
fun worksDurationText(work: BackgroundWorkSummary, nowMs: Long = System.currentTimeMillis()): String? {
    val startedAt = work.startedAt ?: return null
    val seconds = ((nowMs - startedAt) / 1000.0).toInt()
    if (seconds < 0) return null
    if (seconds < 60) return "$seconds 秒"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes 分"
    return "${minutes / 60} 小时 ${minutes % 60} 分"
}

@Composable
private fun WorkRowButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = BiuZText2,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BiuZBgInput)
            .clickable(onClick = onClick)
            .padding(horizontal = BiuZSpacing.sm, vertical = 3.dp),
    )
}

// MARK: side 子代理面板（运行中子代理实例；行点击 → 只读转录下钻）

@Composable
fun SubagentsPanelView(
    subagents: List<SubagentSessionSummary>,
    onOpen: (SubagentSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("05-panel-subagents"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        subagents.forEach { sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.small)
                    .background(BiuZBlueDim.copy(alpha = 0.5f))
                    .clickable { onOpen(sub) }
                    .padding(horizontal = BiuZSpacing.sm, vertical = 5.dp)
                    .testTag("05-subagents-row-${sub.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(
                    text = "⌗",
                    fontSize = 11.sp,
                    color = if (sub.status == WorkflowStepStatus.running) BiuZBlue else BiuZText3,
                )
                Text(
                    text = sub.name ?: sub.agentType ?: stringResource(R.string.wf_actor_default),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = BiuZText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                sub.agentType?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                        color = BiuZText3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(text = "▸", fontSize = 9.sp, color = BiuZText3)
                when (sub.status) {
                    WorkflowStepStatus.running -> PanelSpinner(size = 11.dp, color = BiuZBlue)
                    WorkflowStepStatus.failed -> Text(text = "✕", fontSize = 12.sp, color = BiuZRed)
                    else -> Text(text = "✓", fontSize = 12.sp, color = BiuZAccent)
                }
            }
        }
    }
}

// MARK: 子代理只读转录 sheet（actor 行下钻；G-021 actorTranscript 只读拉一页）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActorTranscriptSheet(
    title: String?,
    isLoading: Boolean,
    messages: List<ChatMessage>,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = BiuZBgElevated) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg)
                .padding(bottom = BiuZSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title ?: stringResource(R.string.wf_actor_default),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
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
                        .padding(BiuZSpacing.sm),
                )
            }
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = BiuZSpacing.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PanelSpinner(size = 18.dp, color = BiuZAccent)
                    Spacer(Modifier.width(BiuZSpacing.sm))
                    Text(
                        text = stringResource(R.string.transcript_loading),
                        style = TextStyle(fontSize = 13.sp),
                        color = BiuZText3,
                    )
                }
            } else if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.transcript_empty),
                    style = TextStyle(fontSize = 13.sp),
                    color = BiuZText3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = BiuZSpacing.xl),
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    items(messages, key = { it.id }) { message ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.small)
                                .background(BiuZBgInput)
                                .padding(BiuZSpacing.md),
                        ) {
                            Text(
                                text = if (message.role == MessageRole.user) {
                                    stringResource(R.string.transcript_role_user)
                                } else {
                                    stringResource(R.string.transcript_role_agent)
                                },
                                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = if (message.role == MessageRole.user) BiuZAccent else BiuZText3,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = message.text,
                                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                                color = BiuZText,
                            )
                        }
                    }
                }
            }
        }
    }
}

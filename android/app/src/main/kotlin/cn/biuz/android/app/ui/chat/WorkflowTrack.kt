package cn.biuz.android.app.ui.chat

// G-007 会话工作流迷你轨道（桌面侧栏 workflowRunLine 同构；iOS ConversationListView
// :1003-1131 直译 + 本任务「聊天页顶」挂点）。形态：Workflow 图标 + 站点灯 ●●●○○
// （四态词汇）+ 当前阶段名 + 「N agents working」+ 并行双线段 + 「+n」溢出。
// 截断口径：每会话最多画 2 条 run 行（桌面 WORKFLOW_RUN_LINE_MAX_LINES）；站点 ≤6 全画，
// 更多以运行站为中心保留 ±2 共 5 站 +「+n」尾（桌面 foldWorkflowRunRail 固定窗口）。
// 双线段简化口径：站 i 的 alongside 含 i-1 即视为与前一站并行（桌面按折带连通分量算
// 轨道号，移动端迷你轨道只画一层并行关系）。
// run 级过滤：只画运行中（live-only，桌面 workflowRunLine.ts:128-133 为 live||未确认，
// 移动端无确认 UI——用户裁决 2026-10-08「只展示运行中的」；WorkflowActivitySummary.liveRuns
// 已承担该过滤，本文件不再重复过滤）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZGreen
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.BiuZViolet
import cn.biuz.android.protocol.SessionWorkflowPhase
import cn.biuz.android.protocol.SessionWorkflowRunSummary
import cn.biuz.android.protocol.WorkflowActivitySummary
import cn.biuz.android.protocol.WorkflowStepStatus

/** 每会话最多画的 run 行数（桌面 WORKFLOW_RUN_LINE_MAX_LINES）。 */
const val WORKFLOW_TRACK_MAX_RUN_LINES = 2

/** 站点折叠窗口全画上界（≤6 全画；桌面 foldWorkflowRunRail 同值）。 */
private const val RAIL_MAX_STATIONS = 6

/** 迷你轨道站点（折叠窗口计算后的渲染模型）。 */
data class WorkflowRailStation(
    val name: String,
    val status: WorkflowStepStatus,
    /** 控制流是否到过这一站（running/done/failed 都算；桌面 isWorkflowRunStationReached）。 */
    val reached: Boolean,
    /** 本站与前一站并行（双线段）。 */
    val twin: Boolean,
)

/**
 * 站点折叠（≤6 全画；更多以运行站为中心保留 ±2 共 5 站，其余合成「+n」尾）。
 * 锚点选择：运行站优先；无运行站取最后一个已到达站；再无则首站（iOS foldWorkflowRail
 * ConversationListView.swift:1010-1031 逐字移植；纯函数——JVM 单测直测）。
 */
fun foldWorkflowRail(phases: List<SessionWorkflowPhase>): Pair<List<WorkflowRailStation>, Int> {
    if (phases.isEmpty()) return emptyList<WorkflowRailStation>() to 0
    val all = phases.mapIndexed { index, phase ->
        WorkflowRailStation(
            name = phase.name,
            status = phase.status,
            reached = phase.status != WorkflowStepStatus.pending,
            twin = index > 0 && phase.alongside.contains(index - 1),
        )
    }
    if (all.size <= RAIL_MAX_STATIONS) return all to 0
    var anchor = all.indexOfFirst { it.status == WorkflowStepStatus.running }
    if (anchor < 0) {
        for (index in all.indices.reversed()) {
            if (all[index].reached) {
                anchor = index
                break
            }
        }
    }
    if (anchor < 0) anchor = 0
    val lower = maxOf(0, anchor - 2)
    val upper = minOf(all.size - 1, anchor + 2)
    val window = all.subList(lower, upper + 1)
    return window to (all.size - window.size)
}

/**
 * 迷你轨道容器（聊天页顶挂点；最多 2 条 run 行 + 溢出计数；随 sessions-index 帧
 * 实时更新。无活 run 不渲染——调用方以 liveRuns.isEmpty 预判）。
 */
@Composable
fun SessionWorkflowTrackView(activity: WorkflowActivitySummary, modifier: Modifier = Modifier) {
    val visibleRuns = activity.liveRuns.take(WORKFLOW_TRACK_MAX_RUN_LINES)
    val overflow = (activity.liveRuns.size - visibleRuns.size).coerceAtLeast(0)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("04-workflow-track"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleRuns.forEach { run -> WorkflowRunRailRow(run = run) }
        if (overflow > 0) {
            Text(
                text = stringResource(R.string.track_overflow, overflow),
                style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                color = BiuZText3,
            )
        }
    }
}

/** 单条 run 迷你轨道行。 */
@Composable
private fun WorkflowRunRailRow(run: SessionWorkflowRunSummary) {
    val (stations, hidden) = remember(run) { foldWorkflowRail(run.phases) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text = "⑂", fontSize = 10.sp, color = BiuZViolet)
        // 站点灯（●●●○○：running 蓝 / done 绿 / failed 红 / pending 灰空心；并行双线段）
        Row(verticalAlignment = Alignment.CenterVertically) {
            stations.forEachIndexed { index, station ->
                if (index > 0) RailConnector(twin = station.twin)
                StationDot(station.status)
            }
            if (hidden > 0) {
                Text(
                    text = "+$hidden",
                    style = TextStyle(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = BiuZText3,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
        // 当前阶段名（run 名缺席时桌面画隐含站「Workflow」，此处以 run 状态胶囊词替代，
        // 阶段名优先）
        Text(
            text = run.currentPhase ?: run.name ?: stringResource(R.string.track_default_name),
            style = TextStyle(fontSize = 10.5.sp),
            color = BiuZText3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (run.agentsWorking > 0) {
            Text(
                text = if (run.agentsWorking == 1) {
                    stringResource(R.string.track_agents_one)
                } else {
                    stringResource(R.string.track_agents_n, run.agentsWorking)
                },
                style = TextStyle(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                ),
                color = BiuZGreen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 站点灯圆点（四态）。 */
@Composable
private fun StationDot(status: WorkflowStepStatus) {
    val color = when (status) {
        WorkflowStepStatus.running -> BiuZGreen
        WorkflowStepStatus.done -> BiuZBlue
        WorkflowStepStatus.failed -> BiuZRed
        WorkflowStepStatus.pending -> BiuZBorderStrong
    }
    if (status == WorkflowStepStatus.pending) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .border(1.2.dp, BiuZBorderStrong, CircleShape),
        )
    } else {
        Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
    }
}

/** 站间连线（并行站画双细线段）。 */
@Composable
private fun RailConnector(twin: Boolean) {
    if (twin) {
        Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
            Box(modifier = Modifier.width(7.dp).height(1.dp).background(BiuZBorderStrong))
            Box(modifier = Modifier.width(7.dp).height(1.dp).background(BiuZBorderStrong))
        }
    } else {
        Box(modifier = Modifier.width(7.dp).height(1.2.dp).background(BiuZBorder))
    }
}

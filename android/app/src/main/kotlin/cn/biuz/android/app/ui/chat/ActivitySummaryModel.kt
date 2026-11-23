package cn.biuz.android.app.ui.chat

// v4.0.0 活动摘要行（web `ConversationStatusPanel` 的 Activity 胶囊）派生模型。
//
// 上游权威：`packages/ui/src/v4/ConversationStatusPanel.tsx:1035-1300`
// （endedAgents/endedRuns/endedWorks 三源过滤 + activities 排序 + endedActivities 结果归类
// + completedCount/activityResult/activityLabel 推导）。本文件只做**纯计算**，UI 零文案零配色
// 判断——渲染层 ([ActivitySummaryRow]) 只按 [ActivitySummaryModel] 的结构出视图，
// 便于单测锁定口径（web 一处算错就是「已完成数不对/该折叠的没折叠」）。
//
// 三个易错点（web 源码逐字警示，全部在此固化）：
// 1. **忽略集只对「已结束」生效**（`:1050-1070`）：`activitySummaryDismissed` 的 id 集
//    不隐藏仍在跑的项；子代理还有一条时间例外——`endedAt > dismissed.at` 的重新出现
//    （`:1056-1057`，即忽略后又跑了一轮的 child）。
// 2. **completedActivityCount 为 null 不等于 0**（`:1282-1287`）：null = 完整历史读取失败，
//    此时才回退 previewCompletedCount；undefined（旧桌面）= 也走 preview。两者都不能
//    把已观察到的成功抹掉。
// 3. **activityCount 取 `activities.length || failedCount || completedCount`**（`:1301`）：
//    运行中优先报运行数；无运行时有失败报失败数；否则报完成数（web 用 `||` 不是 `??`，
//    所以 0 会穿透到下一项——但 `completedCount === null` 时 secondary 显示省略）。

/** 活动结果四态（web `ActivityResult`：completed | failed | stopped）。 */
internal enum class ActivityResult { Completed, Failed, Stopped }

/** 单条已结束活动（web `endedActivities` 元素的结构化投影，node 由渲染层按 [kind] 出）。 */
internal data class EndedActivity(
    val id: String,
    val kind: Kind,
    val title: String,
    val summary: String?,
    val result: ActivityResult,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** 可下钻目标（workflow run 的 toolCallId / 子代理的 childSessionId） */
    val openTargetId: String? = null,
) {
    enum class Kind { Bash, Workflow, Subagent }
}

/** 单条运行中活动。 */
internal data class RunningActivity(
    val id: String,
    val kind: EndedActivity.Kind,
    val title: String,
    val startedAt: Long? = null,
)

/**
 * 活动摘要行模型。[visible] = false 时整行不渲染（web：三类都空且无历史目录）。
 */
internal data class ActivitySummaryModel(
    val running: List<RunningActivity> = emptyList(),
    val ended: List<EndedActivity> = emptyList(),
    /** 活动行主标签状态（运行中时 = Running，由 [isRunningAny] 表达）。 */
    val result: ActivityResult = ActivityResult.Completed,
    /** 计数：运行数 > 0 报运行数；否则失败数 > 0 报失败数；否则完成数（null 表示未知）。 */
    val count: Int? = null,
    /** 已完成总数是否确定（false = completedActivityCount 为 null，UI 显示省略而非 0）。 */
    val countKnown: Boolean = true,
    /** 是否有可展开的历史目录入口（web hasSubagentDirectory / hasWorkflowDirectory）。 */
    val hasSubagentDirectory: Boolean = false,
    val hasWorkflowDirectory: Boolean = false,
    /** 历史目录条目数（子代理 endedTotal / 工作流已结束 run 数）。 */
    val subagentDirectoryCount: Int = 0,
    val workflowDirectoryCount: Int = 0,
    /** 运行中子代理/工作流（渲染头像组用；已结束的 success 项也保留在头像组里）。 */
    val avatarSubagents: List<ActivityAvatarItem> = emptyList(),
) {
    val isRunningAny: Boolean get() = running.isNotEmpty()
    val visible: Boolean get() = running.isNotEmpty() || ended.isNotEmpty() ||
        hasSubagentDirectory || hasWorkflowDirectory

    /** 主标签 key（渲染层映射资源；不在此处出文案）。 */
    val labelKey: String
        get() = if (isRunningAny) {
            "chat.capsule.activity.running"
        } else {
            "chat.capsule.activity.${result.name.lowercase()}"
        }
}

/** 头像组条目（web activityAvatars 的结构化投影）。 */
internal data class ActivityAvatarItem(
    val id: String,
    val name: String,
    val status: AvatarStatus,
    val result: ActivityResult?,
) {
    enum class AvatarStatus { Running, Done, Failed }

    /** 停止/失败不能回退 Running（否则头像显示等待三点，web :1218-1224 注释）。 */
    val badge: AvatarStatus
        get() = if (result == ActivityResult.Completed) AvatarStatus.Done else status
}

/**
 * 活动摘要派生（纯函数）。输入与 web `ConversationStatusPanelImpl` 同名同义：
 * - [workflowRuns]：会话全部 run（活 + 已结束）；
 * - [backgroundWorks]：`state.backgroundWorks` 全量；
 * - [subagentProjection]：v4 `state.subagents`（running/ended/endedTotal）；
 * - [dismissed]：`state.activitySummaryDismissed`；
 * - [completedActivityCount]：v4 `state.completedActivityCount`；
 * - [endedSubagentCount] / [endedWorkflowRunCount]：历史目录条目数（平台各自派生）。
 *
 * 与 web 的**唯一有意差异**：web 用 `workflowRuns.flatMap(actors).sessionId` 排除
 * 「工作流里的子代理」（避免同一 child 双列），Android 的 run.actors 已由 store 侧
 * 归一成 [WorkflowActorSummary]，此处同口径排除。
 */
internal fun buildActivitySummaryModel(
    workflowRuns: List<cn.biuz.android.protocol.WorkflowRunSummary>,
    backgroundWorks: List<cn.biuz.android.protocol.BackgroundWorkSummary>,
    subagentProjection: cn.biuz.android.protocol.SubagentProjectionState?,
    dismissed: cn.biuz.android.protocol.ActivitySummaryDismissed?,
    completedActivityCount: Int?,
    endedSubagentCount: Int = 0,
    endedWorkflowRunCount: Int = 0,
    hasDirectoryTargets: Boolean = false,
): ActivitySummaryModel {
    // ① 工作流 actor 的 child 不重复计入独立子代理（web :1044-1049 workflowChildren）
    val workflowChildren = workflowRuns
        .flatMap { run -> run.actors.mapNotNull { it.sessionId } }
        .toSet()

    val runningSubagents = subagentProjection?.running.orEmpty()
        .filter { it.childSessionId !in workflowChildren }
    val endedAgents = subagentProjection?.ended.orEmpty().filter { agent ->
        agent.childSessionId !in workflowChildren &&
            // 忽略集带时间例外：忽略之后又有新结束时间 → 重新出现（web :1056-1057）
            (
                dismissed?.subagentIds?.contains(agent.childSessionId) != true ||
                    (agent.endedAt ?: 0) > (dismissed.at ?: 0)
                )
    }
    val endedRuns = workflowRuns.filter { run ->
        !run.isLive && dismissed?.workflowRunIds?.contains(run.id) != true
    }
    val endedWorks = backgroundWorks.filter { work ->
        work.kind == "bash" && !work.isRunning &&
            dismissed?.workIds?.contains(work.workId) != true
    }
    val runningBash = backgroundWorks.filter { it.isRunning && it.kind == "bash" }
    val runningRuns = workflowRuns.filter { it.isLive }

    // ② 运行中活动：bash / workflow / subagent 三源合并，按 startedAt 升序（缺席沉底）
    val running = buildList {
        runningBash.forEach {
            add(
                RunningActivity(
                    id = "bash:${it.workId}",
                    kind = EndedActivity.Kind.Bash,
                    title = it.title ?: it.kind ?: "",
                    startedAt = it.startedAt,
                ),
            )
        }
        runningRuns.forEach {
            add(
                RunningActivity(
                    id = "workflow:${it.id}",
                    kind = EndedActivity.Kind.Workflow,
                    title = it.name,
                    startedAt = it.startedAt,
                ),
            )
        }
        runningSubagents.forEach {
            add(
                RunningActivity(
                    id = "agent:${it.childSessionId}",
                    kind = EndedActivity.Kind.Subagent,
                    title = it.title.ifEmpty { it.subagentType },
                    startedAt = it.startedAt,
                ),
            )
        }
    }.sortedWith(
        compareBy<RunningActivity> { it.startedAt ?: Long.MAX_VALUE }.thenBy { it.id },
    )

    // ③ 已结束活动（三源，各自结果词表映射）
    val ended = buildList {
        endedWorks.forEach { work ->
            val result = when (work.rawStatus) {
                "failed" -> ActivityResult.Failed
                "cancelled" -> ActivityResult.Stopped
                else -> ActivityResult.Completed
            }
            add(
                EndedActivity(
                    id = "bash:${work.workId}",
                    kind = EndedActivity.Kind.Bash,
                    title = work.title ?: work.kind ?: "",
                    summary = work.resultSummary,
                    result = result,
                    startedAt = work.startedAt,
                    endedAt = work.endedAt,
                ),
            )
        }
        endedRuns.forEach { run ->
            val work = backgroundWorks.firstOrNull { it.workId == run.id }
            val result = when (run.rawStatus) {
                "errored" -> ActivityResult.Failed
                "stopped" -> ActivityResult.Stopped
                else -> ActivityResult.Completed
            }
            add(
                EndedActivity(
                    id = "workflow:${run.id}",
                    kind = EndedActivity.Kind.Workflow,
                    // 标题退化：work 无标题或标题 == runId 时用「Workflow」兜底（web :1174-1177）
                    title = work?.title?.takeIf { it != run.id } ?: run.name,
                    summary = run.stopReason ?: work?.resultSummary,
                    result = result,
                    startedAt = work?.startedAt ?: run.startedAt,
                    endedAt = work?.endedAt,
                    openTargetId = run.id,
                ),
            )
        }
        endedAgents.forEach { agent ->
            val result = when (agent.status) {
                "failed" -> ActivityResult.Failed
                "cancelled" -> ActivityResult.Stopped
                else -> ActivityResult.Completed
            }
            add(
                EndedActivity(
                    id = "agent:${agent.childSessionId}",
                    kind = EndedActivity.Kind.Subagent,
                    title = agent.title.ifEmpty { agent.subagentType },
                    summary = agent.summary,
                    result = result,
                    startedAt = agent.startedAt,
                    endedAt = agent.endedAt,
                    openTargetId = agent.childSessionId,
                ),
            )
        }
    }

    // ④ 计数与总结果（web :1274-1301）
    val failedCount = ended.count { it.result == ActivityResult.Failed }
    // 只统计「待投递」的 bash 为已完成（resultPending = 完成待投递；web :1276）
    val completedBashCount = endedWorks.count { it.rawStatus == "resultPending" }
    val previewCompletedCount =
        workflowRuns.count { it.rawStatus == "completed" } +
            subagentProjection?.ended.orEmpty()
                .count { it.status == "success" && it.childSessionId !in workflowChildren }
    // null（读失败）才回退 preview；undefined（旧桌面）也走 preview（web :1282-1287）
    val completedCount = completedBashCount + (completedActivityCount ?: previewCompletedCount)
    val countKnown = completedActivityCount != null
    val hasCompletedResult = completedCount > 0
    val result = when {
        failedCount > 0 -> ActivityResult.Failed
        !hasCompletedResult && ended.any { it.result == ActivityResult.Stopped } ->
            ActivityResult.Stopped
        else -> ActivityResult.Completed
    }
    val count: Int? = when {
        running.isNotEmpty() -> running.size
        failedCount > 0 -> failedCount
        else -> completedCount
    }

    // ⑤ 历史目录（web :1252-1273）：需忽略集非空（有历史身份）、目录目标可开、计数 > 0
    val hasSubagentDirectory = hasDirectoryTargets &&
        (dismissed?.subagentIds?.isNotEmpty() == true) && endedSubagentCount > 0
    val hasWorkflowDirectory = hasDirectoryTargets &&
        (dismissed?.workflowRunIds?.isNotEmpty() == true) && endedWorkflowRunCount > 0

    // ⑥ 头像组（web :1304-1400 简化：运行中的子代理 + 成功结束的子代理）
    val avatarAgents = buildList {
        runningSubagents.forEach {
            add(
                ActivityAvatarItem(
                    id = it.childSessionId,
                    name = it.subagentType,
                    status = ActivityAvatarItem.AvatarStatus.Running,
                    result = null,
                ),
            )
        }
        if (running.isNotEmpty() || result == ActivityResult.Completed) {
            subagentProjection?.ended.orEmpty()
                .filter { it.status == "success" && it.childSessionId !in workflowChildren }
                .forEach {
                    add(
                        ActivityAvatarItem(
                            id = it.childSessionId,
                            name = it.subagentType,
                            status = ActivityAvatarItem.AvatarStatus.Done,
                            result = ActivityResult.Completed,
                        ),
                    )
                }
        } else {
            endedAgents
                .filter {
                    if (result == ActivityResult.Failed) it.status == "failed"
                    else it.status == "cancelled"
                }
                .forEach {
                    add(
                        ActivityAvatarItem(
                            id = it.childSessionId,
                            name = it.subagentType,
                            status = if (result == ActivityResult.Failed) {
                                ActivityAvatarItem.AvatarStatus.Failed
                            } else {
                                ActivityAvatarItem.AvatarStatus.Done
                            },
                            result = if (result == ActivityResult.Failed) {
                                ActivityResult.Failed
                            } else {
                                ActivityResult.Stopped
                            },
                        ),
                    )
                }
        }
    }

    return ActivitySummaryModel(
        running = running,
        ended = ended,
        result = result,
        count = count,
        countKnown = countKnown,
        hasSubagentDirectory = hasSubagentDirectory,
        hasWorkflowDirectory = hasWorkflowDirectory,
        subagentDirectoryCount = endedSubagentCount,
        workflowDirectoryCount = endedWorkflowRunCount,
        avatarSubagents = avatarAgents,
    )
}

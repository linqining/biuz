package cn.biuz.android.protocol

// 直译自 ios/ZCodeMobileApp/Sources/Models/Models.swift（631 行）：行模型 / state 投影数据结构。
// 机械映射约定（全文件统一）：
// - Swift `Int` → Kotlin Int（JSON 侧 32 位值域口径见 RPCSerialization.kt JSONValue KDoc）；
// - Swift `Date` → Long（epoch 毫秒）；`TimeInterval` → Double（秒）；
// - Swift struct `var` 字段 → Kotlin `val`（可变语义经 data class copy() 承接）；
// - Swift `Codable`：protocol 模块无序列化运行时（依赖骨架禁改），持久化面
//   （DeviceOption/AppSettings 的 UserDefaults JSON）由装配层需要时另行实现；
//   Models.swift:622-630 的 decodeIfPresent 逐键兼容升级在 Kotlin 由默认值天然等价；
// - `String(localized:)` → 同串字面量（iOS 键 = 串本身；Android 本地化由 UI 层资源承接，
//   协议层保持原词映射，词表语义不变）。
// 上游 file:line 注释一律保留 iOS 原文指位。

enum class MessageRole { user, agent }
enum class MessageStatus { done, streaming }
enum class ToolCallStatus { running, done, failed }
enum class ToolKind { bash, edit, read, ask, browser }

/** 工具调用卡（会话流内嵌）（Models.swift:9-23） */
data class ToolCall(
    val id: String,
    val kind: ToolKind,
    val target: String,
    val status: ToolCallStatus,
    val duration: String? = null,
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    val output: String? = null,
    val diff: List<DiffLine>? = null,
    /** G-015：retryTurn 精确游标（行元数据 entityId，宽容解析；null = 无游标不渲染重试） */
    val entityId: String? = null,
    /** G-020：workflow 启动类工具调用关联的 runId（宽容解析；null = 非启动轮） */
    val workflowRunId: String? = null,
    /**
     * 原始工具名（行 `toolName`；web `lib/toolIdentity.ts` 家族判定源）。
     * null = 行未携带——渲染回退通用卡（工具名/参数兜底展示）。
     */
    val toolName: String? = null,
    /** 工具输入摘要（行 `input.description`/`summary` 宽容读取；bash 等副标题）。 */
    val description: String? = null,
    /** 工具调用标题（行 `title`；web primary 文案优先级最高）。 */
    val title: String? = null,
)

/**
 * todo/plan 步骤状态：done/now/todo 为既有三态；failed 为 BiuZ 扩展
 * （映射 Qoder Error 态；桌面 v4 state.todos 的 status 仅 pending|in_progress|completed，
 * failed 供宽容解析 error|failed 等扩展值使用）（Models.swift:28）
 */
enum class TodoState { done, now, todo, failed }

data class TodoItem(
    val id: String,
    val title: String,
    val state: TodoState,
)

// MARK: - 思考/推理内容（折叠块；项 4）

/**
 * reasoning 行 → 折叠块数据。state：流式中/完成/中断（中断为移动端呈现的显式态）。
 * startedAt 仅在该行被观察到流式时记录（历史快照一次性到达的 done 行无时长，
 * duration 为 streaming→done 的实测用时，null = 未知，UI 退化为仅字数摘要）。
 * （Models.swift:41-48）
 */
enum class ThinkingState { streaming, done, interrupted }

data class ThinkingContent(
    val text: String,
    val state: ThinkingState,
    /** epoch 毫秒（Swift Date 对应物）。 */
    val startedAt: Long? = null,
    /** 秒（Swift TimeInterval 对应物）。 */
    val duration: Double? = null,
)

data class AgentQuestion(
    val text: String,
    val quickReplies: List<String>,
)

// MARK: - 桌面 workflow 运行进度（要求 5 / G-007 / G-008 · 只读展示）
// 权威 schema（桌面开源 v3.14.3 只读克隆）（Models.swift:55-66）：
// - 通路 A：sessions-index.ts:44 workflowActivity（sessionWorkflowActivitySchema，
//   sessions-index-workflow-activity.ts:68）——{runs[≤4]}，run 摘要 {runId, name?, status 五态,
//   phases[{name, status 四态, alongside?}], currentPhase?, agentsWorking}；
// - 通路 B：workflow-runs.ts:508 workflowRunsStateSchema——{revision, runs[workflowRunSchema]}，
//   冷快照必带（snapshot.ts:497-499）、state.updated 键级替换（delta.ts:50）、专属增量 op 仅
//   workflowRun.updated / workflowRun.removed 两个（delta.ts:122/140，header/条目整替换）；
// - run 五态 pending|running|completed|errored|stopped（workflow-runs.ts:365）；
//   站点灯四态 pending|running|done|failed（STATUS_DOT 词汇，sessions-index-workflow-activity.ts:30）；
//   节点七相位 queued|dispatched|executing|waiting|repairing|nudged|settled（workflow-runs.ts:226）；
//   子代理实例 actors[] {siteId, ordinal, name?, status: waiting|running|completed, phaseName?}。

/**
 * 站点灯四态（桌面 STATUS_DOT 同词汇；节点/阶段通用）（Models.swift:69-95）
 */
enum class WorkflowStepStatus {
    pending, running, done, failed,
    ;

    companion object {
        /** 宽容映射桌面端 status/state/phase 词表（未知值归 pending）（:73-83） */
        fun map(raw: String?): WorkflowStepStatus {
            return when ((raw ?: "").lowercase()) {
                "running", "in_progress", "active", "executing", "dispatched",
                "waiting", "repairing", "nudged",
                ->
                    // 七相位中 dispatched/executing/waiting/repairing/nudged 均视为进行中烧站点
                    running
                "done", "completed", "success", "succeeded", "finished" -> done
                "failed", "error", "errored", "cancelled", "aborted" -> failed
                else -> pending
            }
        }

        /** run 五态原词 → 站点灯四态（stopped 桌面语义为「当前站回退 pending」）（:86-94） */
        fun mapRunStatus(raw: String?): WorkflowStepStatus {
            return when ((raw ?: "").lowercase()) {
                "running" -> running
                "completed" -> done
                "errored" -> failed
                "stopped" -> pending
                else -> pending
            }
        }
    }
}

/** 通路 A：sessions-index workflowActivity 的 run 摘要（侧栏迷你轨道数据；有界 ≤4 run）（:98-104） */
data class SessionWorkflowPhase(
    val name: String,
    val status: WorkflowStepStatus,
    /** 进入本站时仍在跑的其他站下标（并行双线段依据；声明表才有） */
    val alongside: List<Int> = emptyList(),
) {
    val id: String get() = name
}

data class SessionWorkflowRunSummary(
    val id: String, // runId
    val name: String?,
    /** 五态原词（pending|running|completed|errored|stopped） */
    val rawStatus: String,
    val phases: List<SessionWorkflowPhase>,
    val currentPhase: String?,
    val agentsWorking: Int,
) {
    /** 是否存活（桌面 isSessionWorkflowRunLive：pending|running）（:116） */
    val isLive: Boolean
        get() = rawStatus == "pending" || rawStatus == "running"
}

/** 通路 A 投影（无 run 时 null，行不渲染占位）（:120-128） */
data class WorkflowActivitySummary(
    val runs: List<SessionWorkflowRunSummary>,
) {
    /**
     * 运行中 run（pending|running）。行迷你轨道只画活 run——桌面侧栏同位过滤
     * 【实证·上游仓 workflowRunLine.ts:128-133】：visible = live || 未确认(ack)，
     * 移动端无「确认收起」UI、未确认已完成 run 无清除手段 → 取 live-only 口径
     * （用户裁决 2026-10-08「工作流只展示运行中的」）；溢出计数同源（ts:133）
     */
    val liveRuns: List<SessionWorkflowRunSummary>
        get() = runs.filter { it.isLive }
}

/** 阶段节点（详情面板节点链；含子代理节点：isSubagent=true 时渲染为子代理卡片）（:131-137） */
data class WorkflowNodeSummary(
    val id: String,
    val label: String,
    val status: WorkflowStepStatus,
    val isSubagent: Boolean = false,
    val summary: String? = null,
)

/** 子代理实例（通路 B actors[] 权威投影：waiting|running|completed）（:140-160） */
data class WorkflowActorSummary(
    val id: String, // siteId#ordinal
    val name: String?,
    /** 原词 waiting|running|completed */
    val rawStatus: String,
    val phaseName: String?,
    /** G-021：子代理会话 id（桌面 workflowRunActorSchema.sessionId；null = 无下钻入口） */
    val sessionId: String? = null,
    /** 任务计数（桌面 actor 行「N 个任务」；字段宽容解析，null = 不展示计数） */
    val tasksTotal: Int? = null,
    val tasksSettled: Int? = null,
) {
    val status: WorkflowStepStatus
        get() = when (rawStatus) {
            "running", "active", "executing", "dispatched" -> WorkflowStepStatus.running
            "completed", "done", "settled", "finished", "succeeded" -> WorkflowStepStatus.done
            "errored", "failed", "cancelled", "aborted" -> WorkflowStepStatus.failed
            else -> WorkflowStepStatus.pending
        }
}

/**
 * 会话 workflow run 只读投影（UI 不新增发送命令）（:162-252）。
 * resumable/truncated 为 CLI 算好透传的字段（桌面基准：UI 绝不自推导），只读展示。
 * run 产物 brief（仅完成卡 join 所需三键；workflowRunArtifactSummarySchema strict
 * 的子集宽容读，缺席键 null）
 */
data class WorkflowArtifactBrief(
    val id: String,
    val bytes: Int? = null,
    val itemCount: Int? = null,
)

data class WorkflowRunSummary(
    val id: String,
    val name: String,
    /** 桌面五态原词（pending|running|completed|errored|stopped） */
    val rawStatus: String,
    val stopReason: String? = null,
    val resumable: Boolean = false,
    val truncated: Boolean = false,
    val nodes: List<WorkflowNodeSummary> = emptyList(),
    val actors: List<WorkflowActorSummary> = emptyList(),
    val artifactsCount: Int = 0,
    /** run 级用量（usage.spentTokens【实证·上游仓 workflow-runs.ts:147-153 引擎
     * usage-updated 已花总量】；完成卡 tokens 格数据源，null = 格写「—」） */
    val spentTokens: Int? = null,
    /** 进过的阶段数（phases 表 → phaseNames → nodes 去重 phaseName 三级取一；
     * 完成卡阶段格数据源，null = 格写「—」——无 phase 标记的脚本拿不到） */
    val phaseCount: Int? = null,
    /** 产物字节/条目 brief（workflowRunArtifactSummarySchema 的 id+bytes+itemCount
     * 三键；完成卡按 id join 补「文档 · 11.8 KB」「6 条」展示，缺席 = 只显种类词） */
    val artifactBriefs: List<WorkflowArtifactBrief> = emptyList(),
    val pendingQuestionsCount: Int = 0,
    val concurrency: Int? = null,
    val concurrencyCeiling: Int? = null,
    /** 子代理模型（桌面 amendWorkflowRunSettings 的 subagentModel 同名透传；null = 跟随主模型） */
    val subagentModel: String? = null,
    /** 运行取消（桌面 workflow 卡 cancellable → cancelBackgroundWork） */
    val cancellable: Boolean = false,
    /** 控制/设置命令的定位键（run 对象自带 workId；缺席时以 runId 充当，web 同构） */
    val workId: String? = null,
    /** 阶段词汇在场（任一节点/实例带 phaseName 出生戳；上游 runHasPhaseVocabulary
     * 同口径）——决定无戳实例的归属：有词汇只归「未分组」站，无词汇归全部站 */
    val hasPhaseVocabulary: Boolean = false,
    /** 起止时间（宽容解析：毫秒数/ISO 双形态，缺席 = null——多 run 排序回退表序；
     * 字段未在桌面 §10 词表取证，仅移动端显示排序用）。epoch 毫秒。 */
    val startedAt: Long? = null,
    val updatedAt: Long? = null,
    // MARK: G25 run 详情深化（2026-11-25 与 iOS 同步；字段权威 = 上游 workflowRunSchema
    // workflow-runs.ts:419-583【实证·上游仓】；旧桌面缺键全 null，UI 分支不渲染承载）
    /** 错误正文（:435；errored 终态区正文） */
    val error: String? = null,
    /** 完成结果预览（:441；completed 终态区预览块） */
    val resultPreview: String? = null,
    /** 血缘「改自」（:429-431，来自 run-started 载荷；恢复型 run 的父 runId） */
    val resumedFrom: String? = null,
    /** 血缘「已被替代」（:432-434，只随 stopReason=superseded 的 run-settled；后继 runId） */
    val supersededBy: String? = null,
    /** 用量节点数（usage.nodesUsed，:174-189） */
    val usageNodesUsed: Int? = null,
    /** 本 run 自己的并发界（concurrency.limit :322-329；null = 跟随默认 ceiling） */
    val concurrencyLimit: Int? = null,
    /** 并发上限（concurrency.cap；v4.0.0 对象形态 {key?,cap,ceiling,limit?,cooldownMs?}） */
    val concurrencyCap: Int? = null,
    /** 并发冷却时长 ms（concurrency.cooldownMs；>0 = 该 key 曾限流） */
    val concurrencyCooldownMs: Long? = null,
    /** 枚举面失败归因（conversationWorkflowRunsV4 summary failureCode） */
    val failureCode: String? = null,
) {
    /** 站点灯四态映射（stopped→pending，UI 另以文案区分「已停止」）（:210） */
    val status: WorkflowStepStatus
        get() = WorkflowStepStatus.mapRunStatus(rawStatus)

    val isLive: Boolean
        get() = rawStatus == "pending" || rawStatus == "running"

    /** 进度点计数（done 节点 / 总节点）（:214） */
    val doneCount: Int
        get() = nodes.count { it.status == WorkflowStepStatus.done }

    companion object {
        /** display 阶段名截断上界（上游 DISPLAY_PHASE_NAME_BOUND，phase-name.ts:39）（:217） */
        const val phaseNameDisplayBound = 128

        /** 无戳实例承接站的保留 id（store 侧合成「未分组」站时使用）（:219） */
        const val unphasedStationID = "station-unphased"

        /**
         * display 阶段名 ↔ 运行时出生戳的唯一关联规则【实证·上游仓 phase-name.ts
         * phaseNameMatches:44-49 逐字移植】：精确匹配优先；display 名顶到截断上界才按
         * 前缀兜底——前缀只在截断真的发生过时才开，否则「计划」会误认「计划修复」（:224-227）。
         */
        fun phaseNameMatches(displayName: String, runtimeName: String): Boolean {
            if (displayName == runtimeName) return true
            return displayName.length >= phaseNameDisplayBound && runtimeName.startsWith(displayName)
        }
    }

    /**
     * 子代理实例 → 阶段行归属【实证·上游仓 instance-phases.ts phasesOf 三分支移植】：
     * ① 有戳按名字匹配；② 无戳：有词汇只归「未分组」站、无词汇归全部站（无戳 ↔ 无名
     * 是同一事实的两面）；③ 匹配为空的实例归全部站——「宁可重复显示，也不把一个
     * 在跑的子代理藏起来」。此前精确相等匹配下，截断名/无戳实例永远无站可归，
     * 阶段行 actors 恒空、永远不可展开（用户报障「点击工作流某项不展开」根因之一）。（:234-251）
     */
    fun actors(boundTo: WorkflowNodeSummary): List<WorkflowActorSummary> {
        val node = boundTo
        val bound = ArrayList<WorkflowActorSummary>()
        val orphans = ArrayList<WorkflowActorSummary>()
        for (actor in actors) {
            val stamp = actor.phaseName
            if (stamp == null) {
                if (node.id == unphasedStationID || !hasPhaseVocabulary) {
                    bound.add(actor)
                }
                continue
            }
            if (phaseNameMatches(node.label, stamp)) {
                bound.add(actor)
            } else if (nodes.none { phaseNameMatches(it.label, stamp) }) {
                orphans.add(actor)
            }
        }
        return if (bound.isEmpty()) orphans else bound
    }
}

// MARK: - 会话面板投影（goal / plan / btw 后台工作 / side 子代理；桌面 state.* 同名宽容解析）
// 数据边界：桌面 state schema 字段未完整取证（压缩混淆），按多形态宽容解析；缺数据不渲染。

/** state.goal 投影：目标文本 + 暂停态（pauseGoal/resumeGoal 命令面的只读回显）（:258-262） */
data class RemoteGoalSummary(
    val text: String,
    val rawStatus: String? = null,
    val isPaused: Boolean = false,
)

/** state.plan 投影：计划正文只读卡（计划审批仍走 pendingInteractions 审批卡，不在此重复）（:265-269） */
/**
 * 计划条目（v4 `planStateSchema.items[]` 单条【实证·上游仓 snapshot.ts:504-507】：
 * `{id, content, status: pending|inProgress|completed}`）。
 *
 * 为什么要有结构化条目：此前 store 把 items 拼成一行行字符串（`✓ / ▸ / ·` + 标题）塞进
 * `content`，UI 只能整段渲染——换行行首顶到圆点左边，且丢掉「哪一条完成了」的结构，
 * 无法做条级勾选/删除线与悬挂缩进（web todo 列表是逐条行）。
 */
data class PlanItem(
    val title: String,
    val statusWord: String? = null,
) {
    val isDone: Boolean
        get() = statusWord == "completed" || statusWord == "done"

    val isRunning: Boolean
        get() = statusWord == "inProgress" || statusWord == "in_progress" || statusWord == "running"

    /** 三态字形（与 store 旧拼串同字表；UI 用它做前置槽，标题独立成文本 → 悬挂缩进） */
    val glyph: String
        get() = if (isDone) "✓" else if (isRunning) "▸" else "·"
}

data class PlanPanelSummary(
    val title: String? = null,
    val content: String,
    val rawStatus: String? = null,
    /** 结构化条目（空 = 只有正文形态，UI 回退整段渲染） */
    val items: List<PlanItem> = emptyList(),
    /** 完成项数 / 总项数（chip 徽标「N/M」；0/0 = 无 items，UI 不显徽标） */
    val completedCount: Int = 0,
    val totalCount: Int = 0,
) {
    /** 全部完成（web todoStatus === "completed" 判据之一）。 */
    val allCompleted: Boolean get() = totalCount > 0 && completedCount == totalCount
}

/** state.backgroundWorks 投影（btw 面板）：后台工作条目，cancel/resume 命令面（:272-308） */
data class BackgroundWorkSummary(
    val workId: String,
    val title: String? = null,
    val kind: String? = null,
    val rawStatus: String? = null,
    val cancellable: Boolean = false,
    val resumable: Boolean = false,
    /** 关联 run / 子会话（下钻入口；null = 无） */
    val runId: String? = null,
    val sessionId: String? = null,
    /** 起止时间（上游 backgroundWorkSummarySchema.startedAt/endedAt【实证·上游仓
     * snapshot.ts:352-367】；宽容毫秒/ISO 双形态解析，缺席 null 不显示时长）。epoch 毫秒。 */
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** 桌面忙等标记（上游 blocked；缺席 = false） */
    val blocked: Boolean = false,
    /** 完成摘要（v4.0.0 `backgroundWorkSummarySchema.resultSummary`，max 4096；
     * 活动摘要折叠行展示，缺席 = 不展示）【实证·上游仓 snapshot.ts:401】 */
    val resultSummary: String? = null,
) {
    val id: String get() = workId

    /** 运行中判定（上游官方派生口径：backgroundWorks.some(w => w.status === "running")，
     * snapshot.ts:136 注释逐字——「运行中的参考价值更大」的协议层依据）（:292） */
    val isRunning: Boolean
        get() = rawStatus == "running"

    /** 后台状态原词 → 本地化文案：权威四态闭集 running|resultPending|failed|cancelled
     * 【实证·上游仓 backgroundWorkSummarySchema】+ 旧桌面宽容词；未知原词回退原文（:296-307）
     * （iOS String(localized:) 键 = 同串，见文件头映射约定） */
    val statusText: String
        get() = when (rawStatus) {
            "running" -> "运行中"
            "resultPending" -> "待投递"
            "failed" -> "失败"
            "cancelled" -> "已取消"
            "completed", "done" -> "已完成"
            "stopped" -> "已停止"
            "pending", "queued" -> "待执行"
            else -> rawStatus ?: ""
        }
}

/** state.subagents 投影（side 面板）：子代理会话实例，只读转录下钻（:311-327） */
data class SubagentSessionSummary(
    val childSessionId: String,
    val agentId: String? = null,
    val agentType: String? = null,
    val name: String? = null,
    val rawStatus: String? = null,
) {
    val id: String get() = childSessionId

    val status: WorkflowStepStatus
        get() = when (rawStatus) {
            "running", "active" -> WorkflowStepStatus.running
            "completed", "stopped" -> WorkflowStepStatus.done
            "errored", "failed" -> WorkflowStepStatus.failed
            else -> WorkflowStepStatus.running
        }
}

/** 待处理交互的服务端选项（A-3：应答=回传 optionId，label 仅作展示）。
 * 元素形状宽容（对象 id/optionId + label/title；裸字符串 id=label=该串）。（:331-334） */
/** G22 多题向导单题（userInputRequestPayloadSchema.questions[] 元素） */
data class RemoteInteractionQuestion(
    val question: String,
    val options: List<RemoteInteractionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class RemoteInteractionOption(
    val id: String,
    val label: String,
)

/**
 * workspaceHookReview 审核项（web 对齐 2026-10-06）。应答键 `reviewItemId` 为
 * web 实证（bundle respondWorkspaceHookReview 调用点 `reviewItemIds:[t.reviewItemId]`）；
 * 其余展示字段（title/detail/trustState）宽容解析【未取证——元素 schema 在共享 chunk，
 * webshell2.js 未含】。trustState 已实证值域：pending_trust / revoked / stale_digest /
 * trusted_persistent（bundle w8e 集合与 trusted_persistent 判定逐字取证）。（:341-356）
 */
data class WorkspaceHookReviewItem(
    /** reviewItemId（应答时逐字回传） */
    val id: String,
    /** 展示名（title|name|hookName 宽容链） */
    val title: String? = null,
    /** 说明（summary|description|command 宽容链） */
    val detail: String? = null,
    /** 信任态（见结构注释；null = 服务端未携） */
    val trustState: String? = null,
) {
    /** 是否已信任（trusted* 前缀；撤销信任入口仅对已信任项呈现）（:352-355） */
    val isTrusted: Boolean
        get() = trustState?.startsWith("trusted") == true
}

/**
 * 连接态待处理交互投影（conversation state.pendingInteractions 的移动端映射）：
 * permission = 权限审批卡（命令/路径/影响结构化排版）；userInput / elicitation =
 * Agent 提问（快捷回复/输入框应答）。字段宽容解析（id/kind 必有，其余可缺）。（:361-382）
 */
data class RemotePendingInteraction(
    val id: String,
    val kind: String,
    val title: String? = null,
    val command: String? = null,
    val path: String? = null,
    val impact: String? = null,
    /** 服务端选项（A-3：(id,label) 对——应答回传 optionId，label 展示） */
    val options: List<RemoteInteractionOption> = emptyList(),
    /** 计划审批（G-017）：renderContext.kind == "plan_approval" 时的计划文本 */
    val planText: String? = null,
    /** workspaceHookReview 审核项（payload 宽容解析；非该 kind 恒空） */
    val hookReviewItems: List<WorkspaceHookReviewItem> = emptyList(),
    /** G21 权限卡拒绝反馈文本框开关（payload.freeText=true；snapshot.ts:277【实证·上游仓】，
     * 缺席 = 不显示） */
    val permissionFreeText: Boolean = false,
    /** G23 插件安装卡字段族（kind=="pluginInstall"；schema=pluginInstall.ts【实证·上游仓】） */
    val pluginName: String? = null,
    val pluginDescription: String? = null,
    /** 请求原因（agent 为何要装/启用；缺席 = 只显示名称与描述） */
    val pluginReason: String? = null,
    /** "user" | "workspace"（安装落点档位） */
    val pluginScope: String? = null,
    /** "install" | "enable"（确认按钮文案分叉） */
    val pluginAction: String? = null,
    /** G22 多题向导（userInputRequestPayloadSchema.questions——{question, options[{value,
     * label}], multiSelect?}【实证·上游仓 snapshot.ts userInputRequestPayloadSchema】；
     * 缺席/空 = 单题卡） */
    val questions: List<RemoteInteractionQuestion> = emptyList(),
    /** G22 提问自由输入开关（payload.freeText / 存在无选项题 / 纯自由输入——iOS
     * RemoteConversationStore.swift:3358-3366 同构【实证·上游仓 snapshot.ts freeText】） */
    val freeTextEnabled: Boolean = false,
    /** G33 行内自动决议倒计时（interactionAutoResolutionSchema snapshot.ts:329-341 两态） */
    val autoResolutionState: String? = null,
    val autoResolutionDeadline: Long? = null,
    val autoResolutionVisibleAt: Long? = null,
    val autoResolutionSnoozedAt: Long? = null,
) {
    /** 是否权限审批类（批准/拒绝下发 resolveInteraction） */
    val isPermission: Boolean
        get() = kind == "permission"

    /** 是否计划审批类（G-017：桌面 ElicitationDialog renderContext.kind="plan_approval"） */
    val isPlanApproval: Boolean
        get() = kind == "plan_approval" || kind == "plan" || kind == "plan-approval"

    /** 是否 workspace hook 信任审核类（web payload.kind='workspaceHookReview'；
     * 应答走 respondWorkspaceHookReview，与 resolveInteraction 是姊妹 kind） */
    val isWorkspaceHookReview: Boolean
        get() = kind == "workspaceHookReview"

    /** G23：是否插件安装卡（上游 pendingInteractionSchema.kind 第四枚举值 snapshot.ts:347） */
    val isPluginInstall: Boolean
        get() = kind == "pluginInstall"

    /** G33：倒计时是否在场且可暂停（web TaskInteractionBadge.tsx:21-31——只有
     * userInput+autoResolution 才给进度与 snooze；permission 恒 false） */
    val canSnoozeCountdown: Boolean
        get() = kind == "userInput" && autoResolutionState != null

    /** G33：进度 0.0…1.0（snoozed/静默期 = null 不画环；web :44-58 同式） */
    fun countdownProgress(nowMs: Long): Double? {
        if (!canSnoozeCountdown || autoResolutionState == "snoozed") return null
        val deadline = autoResolutionDeadline ?: return null
        val visible = autoResolutionVisibleAt ?: return null
        if (nowMs < visible) return null
        val span = deadline - visible
        if (span <= 0) return null
        val remaining = deadline - nowMs
        return (remaining.toDouble() / span).coerceIn(0.0, 1.0)
    }
}

/**
 * 会话消息：用户气泡 / Agent 正文 / 内嵌思考折叠块、工具卡、todo 卡、提问卡
 * 工作流完成卡·产物行（turnHeader 行 workflowNotification.artifacts 元素 +
 * 活投影 run.artifacts 按 id join 补 bytes/itemCount【实证·上游仓 workflow-row-meta.ts:57-71
 * + workflow-artifacts.ts:125-137 workflowRunArtifactSummarySchema】）（:388-401）
 */
data class WorkflowCompletionArtifact(
    val id: String,
    /** 桌面六态闭集：file|markdown|chart|table|metrics|board */
    val kind: String,
    val title: String? = null,
    /** 版本（workflowRunArtifactReadV4 取字节必带；通知载荷恒带，缺省 1） */
    val version: Int = 1,
    /** 交付物旗（至多一件；完成卡交付物行/产物索引行的排位依据） */
    val primary: Boolean = false,
    val description: String? = null,
    /** 通知载荷不带 bytes/itemCount——join 活投影 run.artifacts 补（缺席 = 不显示大小/条目） */
    val bytes: Int? = null,
    val itemCount: Int? = null,
)

/**
 * 工作流完成卡数据（桌面 WorkflowCompletionCard 同款，2026-10-09 用户看桌面截图点单
 * 「手机端也做个类似的卡片」）。数据源=turnHeader 行（origin=="backgroundResult" 且
 * originMeta.backgroundSource=="workflow" 且 workflowNotification{kind:"terminal",
 * status:"completed"}）【实证·上游仓 workflowTurnCompletion.ts:35-49 + rows.ts:58-97】；
 * 四格数字 join 活投影 workflowRuns 按 runId：usage.spentTokens / actors.count / 阶段数；
 * 拿不到的格写「—」不写 0（上游 WorkflowCompletionCard.tsx 数字的诚实口径）（:409-419）
 */
data class WorkflowCompletionCard(
    /** originMeta.workId ≡ runId */
    val runId: String,
    /** originMeta.title（CLI workflowTaskSubject 权威给出，不本地化） */
    val name: String,
    /** 通知里的墙钟时间；缺席 = 时间格「—」 */
    val durationMs: Int? = null,
    val artifacts: List<WorkflowCompletionArtifact> = emptyList(),
    /** 发射侧砍过（超 8 或被过滤） */
    val truncated: Boolean = false,
)

/**
 * 会话消息（:421-471）。
 * 工作流完成卡（turnHeader 行合成；null = 非完成通知行。桌面轮尾落卡同款，
 * 四格数字由 ChatView 以 workflowRuns 活投影 join 后经 MessageView 注入）
 */
/**
 * 单轮文件改动摘要（上游 `TurnHeaderRow.fileChanges`，
 * packages/shared/src/zcode-protocol-v4/rows.ts:105-112）：
 * `{additions, deletions, files, state?: "active"|"reverted"}`。
 * web 会话页的「N 个文件已更改 +A -D」按轮渲染在助手消息下方（`MessageChangeSummaryPanel.tsx`）。
 */
data class TurnFileChanges(
    val files: Int,
    val additions: Int,
    val deletions: Int,
    val reverted: Boolean = false,
)

/**
 * 时间轴分隔标记（v4 `timelineMarker` 行，`timelineMarkerRowSchema`
 * 【实证·上游仓 rows.ts:441-449 + payload union :355-424】）。
 *
 * web 侧渲染为一行分隔线（`ConversationShareReadonlyTimeline.tsx:255-390`
 * `ConversationTimelineMarkerDivider`），五类可见：
 * - `compact`（上下文压缩：手动/自动，running|success|failed|noop|cancelled）
 * - `modelChange`（模型切换，含 source-less 的「首次使用」形态——此时 fromProvider/fromModel
 *   双双缺席，web 用 `z.never().optional()` 保证不会只出现一半）
 * - `goalVerify`（目标校验，running|pass|notSatisfied|failed）
 * - `forkNotice`（child 会话首部「派生自…」，可跳父会话）
 * 其余 `forkCreated`/`worktreeCreated`/`goalSet`/`retryNotice`/`checkpointRestored` 在 web
 * 的 switch 里落 `default: return null`（不渲染），本端同口径——**解析但不渲染**，
 * 以便将来取用时不必再动解析层。
 *
 * ⚠ 移动端此前的 `when (kind)` 里没有 `timelineMarker` 分支，marker 行被**静默丢弃**
 * （模型切换/压缩/目标校验/派生提示全部不可见）。
 */
data class TimelineMarkerInfo(
    /** compact | forkNotice | forkCreated | modelChange | goalVerify | worktreeCreated | 其他 */
    val type: String,
    /** compact: manual|auto */
    val origin: String? = null,
    /** compact: running|success|failed|noop|cancelled；goalVerify: running|pass|notSatisfied|failed */
    val status: String? = null,
    /** modelChange 来源（source-less「首次使用」形态两者皆 null） */
    val fromProvider: String? = null,
    val fromModel: String? = null,
    /** modelChange 目标 */
    val toProvider: String? = null,
    val toModel: String? = null,
    /** goalVerify 迭代序（1 起） */
    val iteration: Int? = null,
    /** forkNotice 父会话跳转目标 */
    val parentSessionId: String? = null,
    val parentRowId: Int? = null,
) {
    /** 是否在 web 有可见渲染（其余类型 web 走 default null，本端同口径不渲染）。 */
    val isVisible: Boolean
        get() = when (type) {
            "compact", "modelChange", "goalVerify", "forkNotice" -> true
            else -> false
        }
}

/**
 * 会话子代理**历史目录**（`session/subagents` 只读查询
 * 【实证·上游仓 zcode-protocol/index.ts:2013-2027 + :4436 wire 名「session/subagents」】）。
 *
 * 数据源与 `state.subagents` 投影**不同**：投影只带本批（running + ended 摘要 + 总数），
 * 历史目录是**可翻页的完整清单**（`endedCursor`/`endedLimit`，上限 100/页）。
 * web 侧 `useSessionSubagents`（hooks/useSessionSubagents.ts:50-80）→
 * `zcodeAgentService.listSessionSubagents` 即此查询，供侧栏「子代理目录」页使用。
 *
 * 移动端此前无此面——活动摘要的历史目录入口因此只能恒关（无数据源）。
 */
data class SessionSubagentDirectory(
    val revision: Int = 0,
    val childSessionIds: List<String> = emptyList(),
    val total: Int = 0,
    val items: List<SubagentDirectoryItem> = emptyList(),
    /** 下一页游标（缺席 = 已到末页） */
    val nextCursor: String? = null,
)

/** 历史目录条目（base schema `childSessionId/agentId?/toolCallId?/subagentType/title/summary?/startedAt?/endedAt?`
 *  + ended 词表 `success|failed|cancelled|lost`）。 */
data class SubagentDirectoryItem(
    val childSessionId: String,
    val agentId: String? = null,
    val toolCallId: String? = null,
    val subagentType: String = "",
    val title: String = "",
    val summary: String? = null,
    /** success | failed | cancelled | lost */
    val status: String = "",
    val startedAt: Long? = null,
    val endedAt: Long? = null,
)

/**
 * Hook 单次执行投影（v4 `hookExecutionProjectionSchema`
 * 【实证·上游仓 rows.ts:309-327】）。挂在 `hookInvocation` 行的 `executions[]` 上。
 */
data class HookExecutionInfo(
    val hookRunId: String,
    val hookIndex: Int = 0,
    /** 同一 runId 已观察到 HookRunStarted 才为 true；admission-only blocked 为 false */
    val didExecute: Boolean = false,
    /** running | completed | failed */
    val state: String = "",
    /** success | blocked | failed | cancelled | timed_out */
    val outcome: String? = null,
    val blockReason: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val displayName: String = "",
    /** user | plugin | project */
    val sourceKind: String = "",
    val pluginName: String? = null,
    val toolName: String? = null,
) {
    /** 展示态五态（web hookDetailStatus 逐条同构：running 优先、outcome 次之、failed 兜底）。 */
    val displayStatus: String?
        get() = when {
            state == "running" -> "running"
            outcome == "timed_out" -> "timedOut"
            outcome == "cancelled" -> "cancelled"
            outcome == "blocked" -> "blocked"
            state == "failed" || outcome == "failed" -> "failed"
            else -> null
        }
}

/**
 * `hookInvocation` 行投影（v4 `hookInvocationRowSchema`
 * 【实证·上游仓 rows.ts:329-351】）。
 *
 * **web 侧行渲染同样 `return null`**（ConversationShareReadonlyTimeline.tsx:826-828）——
 * 它不进消息流，只按 turn 汇总成「本轮 Hook 详情」入口
 * （`ConversationHookDetailsAction`，ConversationRowView.tsx:1514-1516）。
 * 移动端此前连解析都没有（`when (kind)` 的 else 分支静默跳过），
 * 因此本轮不知道发生过任何 Hook。
 */
data class HookInvocationInfo(
    val hookInvocationId: String,
    val hookEventName: String = "",
    val hookCount: Int = 1,
    val state: String = "",
    val lane: String? = null,
    val anchorToolCallId: String? = null,
    val executions: List<HookExecutionInfo> = emptyList(),
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val status: MessageStatus = MessageStatus.done,
    val toolCall: ToolCall? = null,
    val todos: List<TodoItem>? = null,
    val question: AgentQuestion? = null,
    val thinking: ThinkingContent? = null,
    /** 附件引用（G-014：会话流图片/文件缩略图数据源；桌面行 attachments/ref 宽容解析，
     * 空数组 = 无附件 → 不渲染任何占位块） */
    val attachments: List<String> = emptyList(),
    /** P1-3 行元数据游标（服务端行 entityId 提取——C-1 修正后无 turnId 回退，
     * 仅行合成消息携带；setAssistantFeedback / editUserQuery 与 retryTurn 同款
     * 精确游标之一，null = 无游标不渲染入口、store 拒发） */
    val entityId: String? = null,
    /** P1-3 行来源标记（rebuildMessages 合成时打标："userInput"/"assistantText"）。
     * 助手反馈行仅对 assistantText 渲染——reasoning/subagent/artifact/state-todos
     * 等合成消息（rowKind 为 null）天然排除，不做字符串前缀判断的脆弱门槛 */
    val rowKind: String? = null,
    /** 本轮文件改动摘要（turnHeader 行 fileChanges 投影；null = 该轮无改动） */
    val turnChanges: TurnFileChanges? = null,
    /** 时间轴分隔标记（`timelineMarker` 行投影；null = 非 marker 行） */
    val timelineMarker: TimelineMarkerInfo? = null,
    /** Hook 调用（`hookInvocation` 行投影；不进消息流，汇总为本轮 Hook 详情入口） */
    val hookInvocation: HookInvocationInfo? = null,
    /** P1-3 反馈态服务端回读（用户实测 2026-10-06「点赞后重载丢失」：此前仅本地
     * write-through 字典，重进会话即空白。web 实证 bundle bX(row) 读行上
     * `feedback: "like"|"dislike"` 渲染——本字段即该回读落点；null = 无反馈态） */
    val feedback: Boolean? = null,
    val workflowCompletion: WorkflowCompletionCard? = null,
    /**
     * 回合序号（store 按 turnHeader 边界递增赋值；同轮消息同值）。
     * -1 = 未知（在途回显/会话级卡）；UI 回合分组按此聚合（web conversationTurnRenderUnits 同构）。
     */
    val turnIndex: Int = -1,
    /** 服务端 turnId（行携带时；null = 无）。 */
    val turnId: String? = null,
    /** 子代理行结构化投影（web SubagentRowView：`type · status — summary`）。 */
    val subagent: SubagentSummary? = null,
    /** 产物行结构化投影（web ArtifactRowView：名称 + `TYPE · N bytes`）。 */
    val artifact: ArtifactRowInfo? = null,
    /** epoch 毫秒（Swift Date 对应物）。 */
    val timestamp: Long,
)

/**
 * 会话级子代理投影（v4.0.0 `subagentProjectionStateSchema`【实证·上游仓 snapshot.ts:443-459】）。
 *
 * 与行级 [SubagentSummary] 是两层：本类型是 `state.subagents` 的整块投影，携带
 * **本批结束摘要**（`ended`）与**历史目录总数**（`endedTotal`）——活动摘要行据此计算
 * 「已完成 N 项」；完整历史详情仍走游标查询（`childSessionIds` 只给 id 目录）。
 * 旧桌面（< v4.0.0）不发该键：整体缺席按「无该投影」处理，不伪造 ended/总数。
 */
data class SubagentProjectionState(
    val revision: Int = 0,
    /** 子会话 id 目录（完整历史详情仍走游标查询） */
    val childSessionIds: List<String> = emptyList(),
    val running: List<SubagentRuntimeSummary> = emptyList(),
    /** 历史目录里的子代理总数（活动摘要「已完成 N 项」的分母来源） */
    val endedTotal: Int = 0,
    /** 本批结束摘要（v4.0.0 新增；缺席 = 本批无结束项，不是没有历史） */
    val ended: List<SubagentRuntimeSummary> = emptyList(),
)

/** 子代理运行/结束摘要（running 与 ended 共用主体；ended 的 status 词表为
 * success|failed|cancelled 且携 endedAt，running 为 running|waiting|blocked）。 */
data class SubagentRuntimeSummary(
    val childSessionId: String,
    val subagentType: String = "",
    val title: String = "",
    val summary: String? = null,
    /** running 族：running|waiting|blocked；ended 族：success|failed|cancelled */
    val status: String = "",
    val agentId: String? = null,
    val toolCallId: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) {
    val isRunning: Boolean get() = status == "running" || status == "waiting"
}

// MARK: G21/G23/G24/G25/G27/G33 镜像模型（2026-11-25 与 iOS 同步；wire 取证见各字段注释）

/** G24 工作区 MCP 准入待审核快照（state.workspaceMcpAdmission；schema =
 * workspaceMcpAdmissionStateSchema snapshot.ts:531-536【实证·上游仓】——无状态枚举，
 * 非 null 且 pendingCount>0 即待审核） */
data class WorkspaceMcpAdmissionState(
    val pendingCount: Int,
    val bundleDigest: String? = null,
    val workspaceIdentity: String? = null,
)

/** G27 后台 bash 输出快照（v4/conversation/backgroundBashOutput 回执；
 * schema = background-bash-output.ts【实证·上游仓】——成功 kind="output"
 * {status: running|completed|failed|timed_out|cancelled|spawn_error, output ≤8192,
 * truncated, outputPath}；失败 kind = unavailable|unsupported|read_failed） */
data class BackgroundBashOutputInfo(
    val kind: String,
    val status: String? = null,
    val output: String? = null,
    val truncated: Boolean = false,
    val outputPath: String? = null,
    val code: String? = null,
)

/** G25 run ↔ 已保存工作流判定（workflows/forRun 回执 entry 子集；仅 name+scope 供「再次运行」） */
data class WorkflowRunSavedRef(
    val name: String,
    /** "project" | "global" */
    val scope: String,
)

/** G25 保存结果（workflows/save 回执 union ok:true/false 的 UI 承载；targetExists 仅在
 * 用户已见过覆盖警告后带 overwrite=true 重发——覆盖是用户决定，不是保存副作用） */
sealed interface WorkflowRunSaveOutcome {
    data class Saved(val name: String, val scope: String, val overwritten: Boolean) : WorkflowRunSaveOutcome
    data object TargetExists : WorkflowRunSaveOutcome
    data class Failed(val message: String) : WorkflowRunSaveOutcome
}

/** G43 批量删除已归档结果（zcode-task deleteArchivedTasks 回执；【实证·上游仓
 * zcodeTaskServiceAdapter.ts:3059】deletedTaskIds/skippedTaskIds/failedTaskIds 三表） */
data class ArchivedTaskDeletionResult(
    val deletedTaskIds: List<String>,
    val skippedTaskIds: List<String>,
    val failedTaskIds: List<String>,
)

/** G43 自动归档设置（setting 频道 taskAutoArchiveEnabled/taskAutoArchiveOlderThanDays；
 * web 档位枚举 TASK_AUTO_ARCHIVE_DAY_OPTIONS=[3,7,14,30] 默认 30——
 * validationAppSettings.ts:555-557 + settingsPageHelpers.tsx:69【实证·上游仓】） */
data class AutoArchiveSettings(
    val enabled: Boolean,
    val olderThanDays: Int,
)

/** G21/G23/G33 交互投影扩展字段（RemotePendingInteraction 等价物挂 app 层——
 * 见 ChatMessageViews/AppModels 解析） */

/** 活动摘要忽略集（v4.0.0 `activitySummaryDismissedSchema`【实证·上游仓 snapshot.ts:439-444】）：
 * 用户忽略过的活动摘要条目（按三类 id 分别记录 + 忽略时刻），用于折叠行不再重复提示。 */
data class ActivitySummaryDismissed(
    val at: Long? = null,
    val workIds: List<String> = emptyList(),
    val subagentIds: List<String> = emptyList(),
    val workflowRunIds: List<String> = emptyList(),
)

/** 子代理行投影（web SubagentRowView 同口径；null 字段按行缺省宽容）。 */
data class SubagentSummary(
    val type: String,
    val status: String? = null,
    val summary: String? = null,
)

/** 产物行投影（web ArtifactRowView 同口径）。 */
data class ArtifactRowInfo(
    val name: String,
    val kind: String,
    val bytes: Long? = null,
)

data class Conversation(
    val id: String,
    val title: String,
    val summary: String,
    val directory: String,
    /** epoch 毫秒（Swift Date 对应物）。 */
    val updatedAt: Long,
    val unreadCount: Int = 0,
    /** 服务端挂起审批数（pendingInteractionSummary permission+userInput 合计；
     * 桌面侧栏「等待确认」同源口径，2026-10-08 用户报障「待审批的有显示移动端也需要」） */
    val pendingApprovalCount: Int = 0,
    // MARK: G33 行内待批徽标（sessions-index pendingInteraction 轻量投影
    // ZCodeTaskPendingInteraction【实证·上游仓 zcode-task-types-core.ts:262-268】）
    /** 挂起交互 id（snooze 定位键；null = 行无轻量投影不渲染倒计时徽标） */
    val pendingInteractionId: String? = null,
    /** "permission" | "userInput"（徽标 kind 区分；词表外不渲染徽标） */
    val pendingInteractionKind: String? = null,
    /** 自动决议倒计时截止 epoch 毫秒（hiddenGrace/visibleCountdown 态） */
    val pendingInteractionDeadline: Long? = null,
    /** 倒计时可见起点 epoch 毫秒（进度分母 = deadline - visible，web :44-58 同式） */
    val pendingInteractionVisibleAt: Long? = null,
    /** snoozed 态时间戳（非 null = 已暂停不画进度） */
    val pendingInteractionSnoozedAt: Long? = null,
    // G40 行元数据身份（task-index cronAutomationId/offPeakTaskId 透传）
    val isCronTask: Boolean = false,
    val isOffPeakTask: Boolean = false,
    /** G40 残面：worktree 根 + 远端设备显示名 */
    val worktreeRoot: String? = null,
    val serverDisplayName: String? = null,
    val isPinned: Boolean = false,
    /** 行转圈（web `isTaskListRowRunning`：只认 sessions-index 实时 phase running/prewarming） */
    val isRunning: Boolean = false,
    /**
     * 列表**排序**运行层成员（web `isTaskListRowActive`，taskListRowActivity.ts:56-66）：
     * 回合在跑 **或** 挂着后台工作（`hasBackgroundWork`——workflow run / 后台 bash /
     * 分离子代理仍在跑，而父 phase 已收口）。与 [isRunning] 分开两个字段：排序层宽、
     * 转圈窄，web 刻意不让「后台工作」点亮转圈图标。
     */
    val isActive: Boolean = false,
    val isArchived: Boolean = false,
    /** 失败任务（phase=completedError 族；桌面侧栏失败任务带「清理」动作） */
    val isFailed: Boolean = false,
    val taskProgress: Double? = null,
    val todoSummary: String? = null,
    /** 会话来源（项 5 来源过滤 chips 数据口径）："mac" = 已配对桌面端（局域网/云中继均属
     * 我的 Mac），"cloud" = 云端沙盒（BiuZ 尚无云端会话数据源，预留），null = 未知/演示态。 */
    val source: String? = null,
    /** 通路 A：sessions-index 行自带 workflowActivity（G-007；随既有订阅到达零新增订阅）。
     * 无 run 时 null → 行不渲染占位。 */
    val workflowActivity: WorkflowActivitySummary? = null,
) {
    /** 项目名（列表分组键）：directory 末段；directory 为空视为未绑定项目（走日期分组）（:498-502） */
    val projectName: String?
        get() {
            val trimmed = directory.trim()
            if (trimmed.isEmpty()) return null
            // Swift split(separator:)" omittingEmptySubsequences 默认开：空段剔除后取末段，全空回退原文
            return trimmed.split('/').lastOrNull { it.isNotEmpty() } ?: trimmed
        }
}

/**
 * 设备列表项（项 2 机器层 / 项 6 执行目标选择器共用数据源）：
 * 云端沙盒固定一项 + 已配对 Mac（ServerRegistry 中继服务器）逐台。
 * Codable：执行目标选择器 per-conversation 持久化（UserDefaults JSON）——见文件头映射约定。（:508-514）
 */
data class DeviceOption(
    val id: String,
    val name: String,
    val kind: ExecutorKind,
) {
    companion object {
        val cloudSandbox = DeviceOption(id = "cloud", name = "云端沙盒", kind = ExecutorKind.cloudSandbox)
    }
}

enum class TaskStatus { waiting, running, done, failed }

data class TaskRecord(
    val id: String,
    val title: String,
    val summary: String,
    val directory: String,
    val status: TaskStatus,
    val todoDone: Int,
    val todoTotal: Int,
    val progress: Double,
    val tools: List<String>,
    /** epoch 毫秒（Swift Date 对应物）。 */
    val updatedAt: Long,
    val lastLog: String? = null,
    val pendingCommand: String? = null,
    val pendingImpact: String? = null,
)

data class FileNode(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Int? = null,
    val children: List<FileNode>? = null,
)

enum class DiffLineKind { hunk, add, del, ctx }

data class DiffLine(
    val id: String,
    val kind: DiffLineKind,
    val oldNumber: Int? = null,
    val newNumber: Int? = null,
    val text: String,
)

data class DiffFile(
    val id: String,
    val path: String,
    val language: String,
    val added: Int,
    val removed: Int,
    val lines: List<DiffLine>,
    val isApproved: Boolean = false,
    val isRejected: Boolean = false,
    /** git 写面（stagePaths/unstagePaths）携带的原始路径：web 同参——变更条目
     * `stagePath: n.path`（bundle 取证 TW=wW(e,t).map(e=>e.stagePath)），而非展示用
     * repoRelativePath/短名。null = 演示/会话源未提供（回退 path）。（:562-565） */
    val stagePath: String? = null,
)

data class TerminalLine(
    val id: Int,
    val label: String? = null,
    val text: String,
    val isCommand: Boolean = false,
    val isSuccess: Boolean = false,
)

enum class ExecutorKind {
    cloudSandbox,
    pairedMac,
    ;

    val id: String
        get() = name

    /** （iOS String(localized:) 键 = 同串，见文件头映射约定） */
    val label: String
        get() = when (this) {
            cloudSandbox -> "云端沙盒"
            pairedMac -> "我的 Mac"
        }

    val subtitle: String
        get() = when (this) {
            cloudSandbox -> "隔离环境 · 自动配置"
            pairedMac -> "通过本机 Host 执行"
        }
}

enum class ThoughtLevel {
    off, low, medium, high,
    ;

    val id: String
        get() = name

    val label: String
        get() = when (this) {
            off -> "关闭"
            low -> "低"
            medium -> "中"
            high -> "高"
        }
}

/**
 * iOS 原文位于 Theme.swift:123-131（SwiftUI ColorScheme 派生部分不移植）；
 * AppSettings.appearance 结构需要而预置同词表枚举（system|light|dark）——
 * 后续 Theme 层移植直接复用本枚举，勿重复定义。
 */
enum class AppearanceMode {
    system, light, dark,
    ;

    val id: String
        get() = name

    val label: String
        get() = when (this) {
            system -> "跟随系统"
            light -> "朱批 · 浅色"
            dark -> "朱批 · 深色"
        }
}

/**
 * 应用设置（Models.swift:607-631）。
 * 旧存档无 developerMode 键：iOS 合成解码遇缺键整档失败 → load() 回退默认值
 * （外观/语言/模型偏好全部丢失），故原文逐键 decodeIfPresent 兼容升级——Kotlin
 * 构造默认值天然等价（缺键即默认，无整档失败面）。
 */
data class AppSettings(
    val appearance: AppearanceMode = AppearanceMode.system,
    val notificationsEnabled: Boolean = true,
    val model: String = "GLM-5.3",
    val thoughtLevel: ThoughtLevel = ThoughtLevel.medium,
    val language: String = "跟随系统",
    /**
     * 开发者模式（默认关）：连接页默认只留扫码主路径；开启后显示手动输入地址 /
     * 桌面端开启指引 / 访问令牌说明等开发向入口。设置页品牌行连点 7 次切换；
     * `-ZCodeDevMode` 启动参数可强制开启（E2E 手动链路用例依赖）
     */
    val developerMode: Boolean = false,
)

/** G42 残面 代码预览偏好（web codePreviewSettings 同构：fontSizePx 12–20 默认 12
 * （store/index.ts:112-115 clamp 同值）、showLineNumbers 默认 true、wrapLongLines
 * 默认 false【实证·上游仓 codePreviewSettings.ts:18-24】；主题双键 shiki 移动端
 * TinySyntax 无主题面不投影，登记差异）。 */
data class CodePreviewSettings(
    val fontSizePx: Int = 12,
    val showLineNumbers: Boolean = true,
    val wrapLongLines: Boolean = false,
)

package cn.biuz.android.store

// 会话索引合并基底：sessions-index 行解析（多形态时间回退）+ 跨工作区任务索引 merge
// （权威行纠正）+ 运行态双源合成。直译自 ios RemoteConversationStore.swift 的三块：
// - SessionSummary 结构与 parse 扩展（RemoteConversationStore.swift:241-267/:5581-5706）
// - mergeBootstrapTaskIndex / mergeGlobalTaskIndex 行合并（:402-432/:492-599）
// - sortedConversations 的运行态双源合成（:616-677）
// - applySessionsSnapshot / handleSessionsIndexFrame 增量（:814-858）
// iOS 原文这些逻辑内嵌在 RemoteConversationStore（会话 Store 全量移植在后续批次）；
// 本文件先以独立纯逻辑组件落位，后续移植整体收编（勿在此重复生长行级/消息面逻辑）。
//
// 【上游裁决 2026-10-09 /tmp/zcode-upstream】（与 iOS 注释逐字对齐）：
// - zcodeTaskMetaSchema（validation.ts:1205）：taskId/title/workspacePath 必填、
//   workspaceIdentity?、createdAt/updatedAt = z.number().int()（epoch 毫秒）、
//   status 可选（zcodeTaskPersistStatusSchema = running|completed|error，validation.ts:1037）、
//   lastError{message,…}?.detail、changeSummary{fileCount,added,removed,…}；
// - sessionSummarySchema（sessions-index.ts:29-53）：sessionId/workspaceId 必填、title、
//   phase（sessionPhaseSchema = draft|prewarming|running|completedSuccess|
//   completedInterrupted|error，snapshot.ts:28-37）、lastActivityAt（timestampSchema =
//   z.number()，core.ts:19）、lastAssistantPreview?、pendingInteractionSummary?{
//   permissionCount,userInputCount}、workflowActivity?——**不含 pinned/archived 字段**
//   （组织态 join 另源，上游 taskListMembershipSets.ts 口径）；
// - 双源合成权威：buildTaskListResultFromSessions.ts:68 `status: sessionTask.status ??
//   taskIndexTask.status`；运行判定 taskListRowActivity.ts:42-44 phase prewarming|running。
//
// 【交接红线（docs/Android开发交接.md §4-乙-6）】跨工作区幽灵行（上游
// seedTaskMetaIfMissing 按订阅工作区给同一会话写多行 task-index，桌面至今存留）：
// ① 合并器必须允许权威行纠正——「只补缺不纠正」让错误归属永久冻结；
// ② 行 workspacePath 缺失时禁止拿 workspaceId 当分组键（iOS parse 的 workspaceId
//   兜底分支 :5700-5704 不复刻——relay workspaceId 非恒路径型，误用会把幽灵归属坐实）；
// ③ 时间字段多形态多键回退，解析失败不渲染日期（null → 列表排序沉底，UI 不画日期）。

import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.JSONValue
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** JSONValue 的 Long 宽容读取（与 RemoteTaskStore.kt 顶层同名同义——合并基底独立复用）。 */
internal fun jsonLongValue(value: JSONValue?): Long? = value.longValue()

/**
 * sessions-index / task-index 行解析出的会话摘要（iOS SessionSummary 直译，
 * RemoteConversationStore.swift:241-267）。
 */
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val phase: String,
    /** epoch 毫秒；null = 行无可解析时间（解析失败不渲染日期——乙-9 红线）。 */
    val lastActivityAt: Long?,
    val lastAssistantPreview: String? = null,
    val pendingPermissionCount: Int = 0,
    val pendingUserInputCount: Int = 0,
    /** sessions-index 投影（setTaskPinned/archiveTask 写后的服务端回推；null = 未投影） */
    val pinned: Boolean? = null,
    /** 同上（宽容键 archived/isArchived） */
    val archived: Boolean? = null,
    /**
     * task-index 行状态（ZCodeTaskMeta.status【实证·上游仓 zcode-task-types-core.ts:173,331，
     * 词表 running|completed|error】；上游 validation.ts:1037 zcodeTaskPersistStatusSchema 同词表）。
     * sessions-index 行无此键（phase 才是它的活性词表）——null = 行未携带。
     */
    val taskStatus: String? = null,
    /**
     * 行内是否真实携带 phase 键（缺省回填 "completedSuccess" 与真实终态不可辨——
     * 合成 isRunning 时缺 phase 行才能采纳 taskStatus，实携终态 phase 的行以
     * sessions-index 实时值为准，防 task-index 迟到 running 压过实时已收口）（:257-260）
     */
    val phaseExplicit: Boolean = false,
    /**
     * 会话自带归属工作区（行内 workspacePath/workspace 字段；要求 4 分组键真源——
     * 桌面侧栏多项目并存，不得以当前连接的 workspace 兜底；null = 无法判定归属 →
     * 列表归「其它」组）。【红线】行 workspacePath 缺失禁用 workspaceId 当分组键（乙-6）。
     */
    val workspacePath: String? = null,
    /** 通路 A（G-007）：行自带 workflowActivity 原文（投影解析在 UI/后续批次）。 */
    val workflowActivity: JSONValue? = null,
)

/** 会话摘要行解析（iOS `extension RemoteConversationStore.SessionSummary` 直译，:5581-5706）。 */
object SessionSummaryParser {

    /** task-index 状态词表（validation.ts:1037 zcodeTaskPersistStatusSchema；词表外值不采）。 */
    private val TASK_STATUS_VOCABULARY = setOf("running", "completed", "error")

    /**
     * 时间字段宽容解析：sessions-index 行 lastActivityAt（毫秒数优先，ISO 字符串
     * 兜底；中继快照实测 1791040344222 / 替身 ISO 双形态）；bootstrap.tasks /
     * listTaskList 行上游 zcodeTaskMetaSchema **无 lastActivityAt**，携带
     * updatedAt/createdAt（int 毫秒，validation.ts:1205 实证）——缺此回退曾落
     * distantPast，列表渲染「2025年前」（真机报障 2026-10-07）。
     * 毫秒数走 Long 路径（JSONValue.Int 32 位值域装不下毫秒数——越界值以整值 double
     * 到达）；ISO 解析失败继续试下一键，全失败返回 null（不渲染日期）。
     */
    fun parseLastActivityAt(dict: Map<String, JSONValue>): Long? {
        for (key in listOf("lastActivityAt", "updatedAt", "createdAt")) {
            val value = dict[key] ?: continue
            val ms = jsonLongValue(value)
            if (ms != null && ms > 0) return ms
            val iso = value.stringValue
            if (iso != null) {
                parseIsoMillis(iso)?.let { return it }
            }
        }
        return null
    }

    /** ISO-8601 → epoch 毫秒（带/不带小数秒、Z/偏移量皆容；解析失败 null）。 */
    fun parseIsoMillis(iso: String): Long? {
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * 行解析：sessionId 必有否则整行弃（调用方语义：解析失败行不进合并表）。
     * 【红线差异（乙-6）】workspacePath 解析链 workspacePath / workspace(串) /
     * workspace.path(嵌套)——**无 workspaceId 兜底**（iOS :5704 的
     * `?? dict["workspaceId"]?.stringValue` 分支不复刻：workspaceId 非恒为路径型 id，
     * relay 行只带 workspaceId 时归属未知 → null → 「其它」组，禁坐实错误归属）。
     */
    fun parse(json: JSONValue?): SessionSummary? {
        val dict = json?.objectValue ?: return null
        val sessionId = dict["sessionId"]?.stringValue ?: return null
        val phaseRaw = dict["phase"]?.stringValue
        val taskStatus = (dict["status"]?.stringValue
            ?: dict["taskStatus"]?.stringValue
            ?: dict["task_status"]?.stringValue)
            ?.takeIf { it in TASK_STATUS_VOCABULARY }
        val pending = dict["pendingInteractionSummary"]?.objectValue
        val workspaceObj = dict["workspace"]?.objectValue
        return SessionSummary(
            sessionId = sessionId,
            title = dict["title"]?.stringValue ?: "未命名会话",
            phase = phaseRaw ?: "completedSuccess",
            lastActivityAt = parseLastActivityAt(dict),
            lastAssistantPreview = dict["lastAssistantPreview"]?.stringValue,
            pendingPermissionCount = pending?.get("permissionCount").longValue()?.toInt() ?: 0,
            pendingUserInputCount = pending?.get("userInputCount").longValue()?.toInt() ?: 0,
            pinned = dict["pinned"]?.boolValue,
            archived = dict["archived"]?.boolValue ?: dict["isArchived"]?.boolValue,
            taskStatus = taskStatus,
            phaseExplicit = phaseRaw != null,
            workspacePath = dict["workspacePath"]?.stringValue
                ?: dict["workspace"]?.stringValue
                ?: workspaceObj?.get("path")?.stringValue,
            workflowActivity = dict["workflowActivity"],
        )
    }
}

/** mergeBootstrapTasks 记账（diag.tasks.bootstrap 同口径；diagSink 在场时上送）。 */
data class BootstrapMergeStats(
    val rows: Int,
    val merged: Int,
    val totalSessions: Int,
    val distinctWorkspaces: Int,
)

/** 会话合并表（iOS RemoteConversationStore.sessions: [String: SessionSummary] 的独立承载）。 */
class SessionMergeTable {

    /** Swift actor 串行化的 Kotlin 等价物；纯内存操作，锁内无挂起。 */
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, SessionSummary>()

    /** 诊断钩子（键名与 iOS diag.* 同名；装配层 diag 模式接线，默认 null 静默）。 */
    var diagListener: ((key: String, value: String) -> Unit)? = null

    suspend fun snapshot(): Map<String, SessionSummary> = mutex.withLock { sessions.toMap() }

    suspend fun count(): Int = mutex.withLock { sessions.size }

    /**
     * 最近一次 bootstrap.tasks 行（跨工作区全量索引）。**留存并在快照/新行后重放**：
     * sessions-index 快照整表替换不含 workspacePath（上行 schema 只有 workspaceId），
     * 若不留存，bootstrap 回填的项目归属会被下一个快照冲掉——列表随即退化为单一
     * 「任务」分区（2026-11-23 真机「没有分组」根因）。重放只补 workspacePath/taskStatus，
     * 不动标题与其它富字段。
     */
    private var retainedBootstrapItems: List<JSONValue> = emptyList()

    /** 锁内 bootstrap 行合并（调用方持锁；语义与 [mergeBootstrapTasks] 行循环一致）。 */
    private fun mergeBootstrapRowsLocked(items: List<JSONValue>): Int {
        var merged = 0
        for (item in items) {
            var dict = item.objectValue ?: continue
            // 任务行主键是 taskId；统一成 sessions-index 的 sessionId 口径（iOS :408-410）
            if (dict["sessionId"] == null) {
                val taskId = dict["taskId"]?.stringValue
                if (taskId != null) {
                    val patched = LinkedHashMap(dict)
                    patched["sessionId"] = JSONValue.Str(taskId)
                    dict = patched
                }
            }
            val summary = SessionSummaryParser.parse(JSONValue.Obj(dict)) ?: continue
            val existing = sessions[summary.sessionId]
            if (existing == null) {
                sessions[summary.sessionId] = summary
                merged += 1
            } else {
                var updated = existing
                // workspacePath **只补缺**（iOS mergeBootstrapTaskIndex :419-421 同构）：
                // bootstrap 行在本表可随快照/增量无限重放，是最陈旧的一路（2026-11-23
                // 取证：桌面 3.15.1+ bootstrap-response 已无 tasks 字段，本路仅老桌面
                // 生效；且行可能携历史幽灵归属）——无条件覆盖会让陈旧路径随每次快照
                // 把归属拖回旧值，反盖 task-index（行自带归属权威）/sessions-index 的
                // 更新判定。行级「权威行纠正」（乙-6①：真实区行后到也必须赢）仍由
                // [mergeTaskIndexRows] 承担——后到的权威行照常覆盖。
                if (updated.workspacePath.isNullOrEmpty() && !summary.workspacePath.isNullOrEmpty()) {
                    updated = updated.copy(workspacePath = summary.workspacePath)
                }
                if (summary.taskStatus != null) {
                    updated = updated.copy(taskStatus = summary.taskStatus)
                }
                sessions[summary.sessionId] = updated
            }
        }
        return merged
    }

    // MARK: bootstrap.tasks 跨工作区全量索引 merge（iOS mergeBootstrapTaskIndex 直译）

    /**
     * bootstrap-response 的 tasks 清单（跨工作区全量任务索引，iOS 实测 250 行/26 个
     * distinct workspacePath——web「所有项目目录」的真实数据源）。行形状与 listTaskList
     * items 同源：taskId/title/workspacePath/updatedAt——web Nxe 去重逻辑同款字段。
     * 跨工作区无需 scope RPC。
     * **时效注记（2026-11-23 上游仓取证）**：桌面 3.15.1+ 的 bootstrap-response 已无
     * `tasks` 字段（buildBootstrapResult webRemoteControlManager.ts:1040 全字段无 tasks；
     * git -S 全历史无此字段）——本路仅 3.14- 时代桌面生效；现行桌面的跨区全量源是
     * listTaskList `workspaceScopes:"host"`（见 RemoteConversationStore
     * maybeRefreshProjectMetadata）。本表仍保留留存+重放以兼容旧桌面。
     *
     * 与 iOS 的差异（交接乙-6 红线，2026-10-09；2026-11-23 修订）：
     * ① workspacePath **只补缺不覆盖**（iOS :417-419 同构，撤销 2026-10-09 的
     *    「无条件纠正」——bootstrap 行随快照/增量**无限重放**且可能是幽灵归属/旧迁移
     *    路径，无条件覆盖会让陈旧路径每次快照把归属拖回旧值，反盖 task-index/
     *    sessions-index 的更新判定。「真实区行后到也必须赢」的行级纠正由
     *    [mergeTaskIndexRows] 承担（后到的权威行照常覆盖）。
     *    task-index 是 status 字段的权威源（行携带即回填——运行/失败态跨刷新保留，iOS :420-423 同）。
     * ② workspaceId 不作归属来源（见 SessionSummaryParser.parse）。
     */
    suspend fun mergeBootstrapTasks(items: List<JSONValue>): BootstrapMergeStats {
        if (items.isEmpty()) {
            return BootstrapMergeStats(0, 0, mutex.withLock { sessions.size }, 0)
        }
        // 留存量（快照/新行后重放，防归属被 sessions-index 快照冲掉）
        retainedBootstrapItems = items
        val wsSeen = HashSet<String>()
        for (item in items) {
            item.objectValue?.get("workspacePath")?.stringValue?.let { wsSeen.add(it) }
        }
        val merged = mutex.withLock { mergeBootstrapRowsLocked(items) }
        val stats = BootstrapMergeStats(
            rows = items.size,
            merged = merged,
            totalSessions = mutex.withLock { sessions.size },
            distinctWorkspaces = wsSeen.size,
        )
        diagListener?.invoke(
            "diag.tasks.bootstrap",
            "rows=${stats.rows} merged=${stats.merged} totalSessions=${stats.totalSessions} distinctWs=${stats.distinctWorkspaces}",
        )
        return stats
    }

    // MARK: listTaskList 逐区聚合行 merge（iOS mergeGlobalTaskIndex 行循环直译，:533-581）

    /**
     * 对清单内每个工作区逐个 listTaskList 聚合后的行合并（host-scope 单调用/逐区聚合
     * 两路的行统一在此合并）。已有行**字段级回填**
     * （2026-10-08 报障「新建会话列表恒显占位标题 新会话」）：本地种行（createConversation
     * 占位标题）之后，桌面自动命名只随 task-index 与连接 workspace 的 sessions-index
     * 通道到达——非连接区行此处是唯一刷新点。只回填服务端权威的字段，不整行替换
     * （task-index 行无 preview/workflowActivity 等富字段，整行替换会剥掉 sessions-index
     * 已到达的投影）。标题让位本地改名 override（[titleOverriddenIds]）；
     * 活跃时间仅缺值时补（sessions-index 实时值不回退 task-index，防排序抖动）。
     * workspacePath 纠正同 bootstrap merge（乙-6①）。
     *
     * **批内去重（2026-11-23）**：host-scope / 跨区聚合会把历史幽灵行一并带回——上游
     * tasks 表主键 (workspace_key, task_id)（taskIndexRepo writeRecord ON CONFLICT），
     * 同一会话可在多 workspace_key 下各有一行（乙-6 上游 seedTaskMetaIfMissing 旧伤，
     * 桌面至今存留）。同批同任务多行时：updatedAt **新者赢**；平手**保首个**——
     * scope 序当前工作区垫底（projectScopes 先清单后 current），平手保首个让先合并的
     * 归属区行压过尾部当前工作区的幽灵行，不坐实错误归属（web Nxe 去重同款字段
     * taskId/title/workspacePath/updatedAt 口径）。
     *
     * **归属覆盖「更新者赢」（2026-11-23 修订）**：跨批的 workspacePath 覆盖要求
     * 「既有归属缺失 或 本行 updatedAt 严格更新」——幽灵行与权威行同源同 updatedAt
     * （mirrorSummary 种行拷会话时间戳，无法按内容分辨），保现状防 120s 节流回填把
     * 已定归属反复翻面；fork/移动等真实迁移必然伴随更新的 updatedAt，照常覆盖。
     * 残留局限（如实声明）：老桌面（≤3.16 无 host-scope）+ workspace-list 只含当前
     * tabs 时，幽灵行仍可能先种——需等会话再有活动（updatedAt 推进）才被权威行覆盖。
     *
     * @return merged=新插入行数；backfilled=标题实际回填次数（diag.tasks.merge 同口径）
     */
    suspend fun mergeTaskIndexRows(
        items: List<JSONValue>,
        titleOverriddenIds: Set<String> = emptySet(),
    ): Pair<Int, Int> {
        var merged = 0
        var backfilled = 0
        val workspacesSeen = HashSet<String>()
        // 批内去重（键级插入序 = 首见序；严格新 updatedAt 才顶替，平手保首个）
        val deduped = LinkedHashMap<String, SessionSummary>()
        for (item in items) {
            var dict = item.objectValue ?: continue
            // 任务行主键是 taskId；统一成 sessions-index 的 sessionId 口径（iOS :536-539）
            if (dict["sessionId"] == null) {
                val taskId = dict["taskId"]?.stringValue
                if (taskId != null) {
                    val patched = LinkedHashMap(dict)
                    patched["sessionId"] = JSONValue.Str(taskId)
                    dict = patched
                }
            }
            dict["workspacePath"]?.stringValue?.let { workspacesSeen.add(it) }
            val summary = SessionSummaryParser.parse(JSONValue.Obj(dict)) ?: continue
            val previous = deduped[summary.sessionId]
            if (previous == null) {
                deduped[summary.sessionId] = summary
            } else {
                val incomingAt = summary.lastActivityAt ?: Long.MIN_VALUE
                val previousAt = previous.lastActivityAt ?: Long.MIN_VALUE
                if (incomingAt > previousAt) {
                    deduped[summary.sessionId] = summary
                }
            }
        }
        mutex.withLock {
            for ((_, summary) in deduped) {
                val existing = sessions[summary.sessionId]
                if (existing == null) {
                    sessions[summary.sessionId] = summary
                    merged += 1
                    continue
                }
                var updated = existing
                // 标题回填：让位本地改名 override；空标题/同值不写（iOS :563-567）
                if (summary.sessionId !in titleOverriddenIds &&
                    summary.title.isNotEmpty() &&
                    summary.title != existing.title
                ) {
                    updated = updated.copy(title = summary.title)
                    backfilled += 1
                }
                // 归属纠正（乙-6① 2026-11-23 修订「更新者赢」；iOS :568-570 同向）：
                // 只在既有归属缺失或本行严格更新时覆盖（KDoc 详述幽灵行防翻面）
                val ws = summary.workspacePath
                if (!ws.isNullOrEmpty()) {
                    val incomingAt = summary.lastActivityAt ?: Long.MIN_VALUE
                    val existingAt = existing.lastActivityAt ?: Long.MIN_VALUE
                    if (updated.workspacePath.isNullOrEmpty() || incomingAt > existingAt) {
                        updated = updated.copy(workspacePath = ws)
                    }
                }
                // task-index 是 status 权威源：行携带即覆盖（含 running→completed
                // 收口推进；缺席不动——不剥掉既有值）（iOS :571-575）
                if (summary.taskStatus != null) {
                    updated = updated.copy(taskStatus = summary.taskStatus)
                }
                // 活跃时间仅缺值时补（iOS :576-578）
                if (updated.lastActivityAt == null) {
                    updated = updated.copy(lastActivityAt = summary.lastActivityAt)
                }
                sessions[summary.sessionId] = updated
            }
        }
        if (workspacesSeen.isNotEmpty()) {
            diagListener?.invoke(
                "diag.tasks.merge.workspaces",
                workspacesSeen.sorted().joinToString(","),
            )
        }
        return merged to backfilled
    }

    // MARK: sessions-index 快照/增量（iOS :814-858 直译）

    /**
     * 快照整表替换（键级整体替换，A10）。sessions-index 行不携 status：整行替换前
     * 保住 task-index 已回填的状态（丢掉则运行行在两源交替推送间闪断）（iOS :845-858）。
     */
    suspend fun applySessionsSnapshot(snapshot: JSONValue) {
        val dict = snapshot.objectValue ?: return
        val previous = mutex.withLock { sessions.toMap() }
        val next = LinkedHashMap<String, SessionSummary>()
        for (item in dict["sessions"]?.arrayValue ?: emptyList()) {
            var summary = SessionSummaryParser.parse(item) ?: continue
            if (summary.taskStatus == null) {
                summary = summary.copy(taskStatus = previous[summary.sessionId]?.taskStatus)
            }
            // 归属跨快照保全：行 schema 无 workspacePath（只有 workspaceId），整表替换时
            // 沿用既有归属（bootstrap/listTaskList 已回填），否则列表分组随每次快照丢失。
            if (summary.workspacePath.isNullOrEmpty()) {
                summary = summary.copy(workspacePath = previous[summary.sessionId]?.workspacePath)
            }
            next[summary.sessionId] = summary
        }
        mutex.withLock {
            sessions.clear()
            sessions.putAll(next)
            // bootstrap 留存行重放（补齐本快照未覆盖/新到行的项目归属与状态）
            if (retainedBootstrapItems.isNotEmpty()) {
                mergeBootstrapRowsLocked(retainedBootstrapItems)
            }
        }
    }

    /**
     * 单条增量应用（session.upserted / session.removed；键级整体替换，A10）。
     * 返回是否命中已知 op（未知 op 静默忽略——宽容解析纪律）。
     */
    suspend fun applySessionDelta(delta: JSONValue): Boolean {
        val dict = delta.objectValue ?: return false
        return when (dict["op"]?.stringValue) {
            "session.upserted" -> {
                val sessionJson = dict["session"]
                var summary = SessionSummaryParser.parse(sessionJson) ?: return true
                mutex.withLock {
                    val existing = sessions[summary.sessionId]
                    // sessions-index 行不携 status：整行替换前保住 task-index 已回填的状态
                    if (summary.taskStatus == null) {
                        summary = summary.copy(taskStatus = existing?.taskStatus)
                    }
                    // 归属保全（同快照口径）：行无 workspacePath 时沿用既有归属
                    if (summary.workspacePath.isNullOrEmpty()) {
                        summary = summary.copy(workspacePath = existing?.workspacePath)
                    }
                    sessions[summary.sessionId] = summary
                    // 新行/归属仍缺 → 用 bootstrap 留存行补齐（不覆盖标题等富字段）
                    if (summary.workspacePath.isNullOrEmpty() && retainedBootstrapItems.isNotEmpty()) {
                        mergeBootstrapRowsLocked(retainedBootstrapItems)
                    }
                }
                true
            }
            "session.removed" -> {
                val sessionId = dict["sessionId"]?.stringValue ?: return true
                mutex.withLock { sessions.remove(sessionId) }
                true
            }
            else -> false
        }
    }

    /**
     * listSessions 兜底读的行合并（iOS fallbackListSessions :759-780）：宽容解析，
     * 全部解析失败/空清单不动既有表（`guard !next.isEmpty` 同口径）；非空则整表替换。
     */
    suspend fun replaceFromListSessions(items: List<JSONValue>): Boolean {
        val next = LinkedHashMap<String, SessionSummary>()
        for (item in items) {
            val summary = SessionSummaryParser.parse(item) ?: continue
            next[summary.sessionId] = summary
        }
        if (next.isEmpty()) return false
        mutex.withLock {
            sessions.clear()
            sessions.putAll(next)
        }
        return true
    }

    /**
     * 本地新建会话即刻入表（iOS performCreateConversation :2548-2558 直译）：标题/
     * 归属/时间与创建参数同源，否则列表一重载（conversations() 走本表投影）新建行
     * 就消失，直到桌面 sessions-index 推送才回来（真机报障「新建会话后找不到」）。
     * 行值语义 = iOS 整行覆写（sessions[sessionId] = created）。
     */
    suspend fun upsertLocalSession(summary: SessionSummary) {
        mutex.withLock { sessions[summary.sessionId] = summary }
    }

    /**
     * 字段级回填（iOS refreshDueSessionMeta :2364-2391 的表侧承载）：只改既有行、
     * **不新增行**（新增行归 sessions-index/bootstrap 通道）——标题仅当该行无本地
     * override、非空且不同；preview 非空且不同；lastActivityAt 取更新者；taskStatus
     * 行携带即回填（task-index 是 status 字段的权威源，iOS :420-424 同口径）。
     * 返回标题发生变化的 sessionId 集（调用方据此注销兜底刷新窗口）。
     */
    suspend fun backfillSessionFields(
        items: List<JSONValue>,
        titleOverriddenIds: Set<String>,
    ): Set<String> {
        val candidates = ArrayList<Pair<SessionSummary, SessionSummary>>() // (existing, parsed)
        for (item in items) {
            val summary = SessionSummaryParser.parse(item) ?: continue
            mutex.withLock { sessions[summary.sessionId] }?.let { candidates.add(it to summary) }
        }
        if (candidates.isEmpty()) return emptySet()
        val titleChanged = HashSet<String>()
        mutex.withLock {
            for ((existing, parsed) in candidates) {
                var updated = existing
                if (!titleOverriddenIds.contains(parsed.sessionId) &&
                    parsed.title.isNotEmpty() && parsed.title != existing.title
                ) {
                    updated = updated.copy(title = parsed.title)
                    titleChanged.add(parsed.sessionId)
                }
                if (!parsed.lastAssistantPreview.isNullOrEmpty() &&
                    parsed.lastAssistantPreview != existing.lastAssistantPreview
                ) {
                    updated = updated.copy(lastAssistantPreview = parsed.lastAssistantPreview)
                }
                val existingAt = existing.lastActivityAt
                if (parsed.lastActivityAt != null &&
                    (existingAt == null || parsed.lastActivityAt > existingAt)
                ) {
                    updated = updated.copy(lastActivityAt = parsed.lastActivityAt)
                }
                if (parsed.taskStatus != null) {
                    updated = updated.copy(taskStatus = parsed.taskStatus)
                }
                if (updated != existing) {
                    sessions[existing.sessionId] = updated
                }
            }
        }
        return titleChanged
    }

    // MARK: 投影（iOS sortedConversations :616-677 直译）

    /**
     * 合并表 → 会话列表投影。
     *
     * 运行态**双源合成**（2026-10-09 用户报障「进行中的列表没有转圈」根因：
     * 跨工作区行只来自 task-index，其行携带 status=running 而无 phase——
     * 只认 phase 时全库运行行恒不转）。上游口径 buildTaskListResultFromSessions
     * .ts:68 `status: sessionTask.status ?? taskIndexTask.status`、侧栏转圈
     * taskListRowActivity.ts:43 phase running/prewarming——两源 OR 合成；
     * 实携终态 phase 的行以 sessions-index 实时值为准（task-index 迟到的
     * running 不压过实时收口），phase 缺席行才采纳 taskStatus。
     *
     * 组织态三源 join【实证·上游仓 taskListMembershipSets】：本地 override（操作
     * 即时反馈）→ tasks-index membership（listPinnedTasks/listArchivedTasks 拉取，
     * sessions-index 行不含 pinned/archived 字段——schema 冻结）→ 行字段（宽容
     * 兼容未来服务端投影，缺席不碍事）。
     *
     * 分组键用每个会话自带的工作区字段，不以当前连接的 workspace 兜底（兜底曾使
     * 全部会话挤进同一项目组）；无法判定归属 → 空串 → 「其它」组【乙-6③ 展示口径】。
     */
    suspend fun toConversations(
        titleOverrides: Map<String, String> = emptyMap(),
        pinnedOverrides: Map<String, Boolean> = emptyMap(),
        archivedOverrides: Map<String, Boolean> = emptyMap(),
        pinnedIds: Set<String> = emptySet(),
        archivedIds: Set<String> = emptySet(),
        unreadFlags: Map<String, Boolean> = emptyMap(),
        /** 归档区读面（019 归档管理）需要连归档行一起投影；主列表恒为 false。（§A15） */
        includeArchived: Boolean = false,
    ): List<Conversation> {
        val current = mutex.withLock { sessions.values.toList() }
        val list = ArrayList<Conversation>(current.size)
        for (summary in current) {
            val id = summary.sessionId
            var conversation = Conversation(
                id = id,
                title = titleOverrides[id] ?: summary.title,
                summary = summary.lastAssistantPreview ?: "暂无输出",
                directory = summary.workspacePath ?: "",
                // 解析失败不渲染日期（乙-9）：null → distantPast 对应物，排序沉底
                updatedAt = summary.lastActivityAt ?: Long.MIN_VALUE,
            )
            // —— 运行态双源合成（iOS :635-640 逐条直译）
            val phaseActive = summary.phase == "running" || summary.phase == "prewarming"
            val phaseTerminal = summary.phaseExplicit &&
                summary.phase in PHASE_TERMINAL_VOCABULARY
            conversation = conversation.copy(
                isRunning = phaseActive ||
                    (summary.taskStatus == "running" && !phaseTerminal),
                // 失败任务（桌面侧栏失败任务带「清理」；phase 词表 completedError 族宽容）
                isFailed = summary.phase.lowercase().contains("error") ||
                    summary.phase.lowercase().contains("failed") ||
                    summary.taskStatus == "error",
                // 来源过滤（G-006）数据口径：连接态会话均来自当前桌面端（局域网/云中继
                // 均属"我的 Mac"）；云端沙盒执行端尚无会话数据源
                source = "mac",
                // G-007 通路 A：行迷你轨道（workflowActivity 投影解析已随 M7 工作流面板轮
                // 接入——parseWorkflowActivity 宽容解析，无效 run 行不渲染占位；live-only
                // 过滤口径见 WorkflowActivitySummary.liveRuns KDoc）
                workflowActivity = RemoteConversationStore.parseWorkflowActivity(summary.workflowActivity),
            )
            // 组织态三源 join（iOS :654-659：本地 override → membership → 行字段）
            conversation = conversation.copy(
                isPinned = pinnedOverrides[id]
                    ?: (if (pinnedIds.contains(id)) true else null)
                    ?: summary.pinned
                    ?: false,
                isArchived = archivedOverrides[id]
                    ?: (if (archivedIds.contains(id)) true else null)
                    ?: summary.archived
                    ?: false,
            )
            val pending = summary.pendingPermissionCount + summary.pendingUserInputCount
            conversation = conversation.copy(
                // 服务端挂起审批数（行「等待确认」绿标数据源；桌面侧栏同口径）
                pendingApprovalCount = pending,
                // 未读 = 待交互数；「标记未读」本地置位（服务端无未读投影时仍可呈现）
                unreadCount = if (unreadFlags[id] == true) maxOf(pending, 1) else pending,
                taskProgress = if (summary.pendingPermissionCount > 0) null else conversation.taskProgress,
                todoSummary = if (summary.pendingPermissionCount > 0) "${summary.pendingPermissionCount} 项待审批" else conversation.todoSummary,
            )
            list.add(conversation)
        }
        return list
            .filter { includeArchived || !it.isArchived }
            .sortedWith(
                compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.updatedAt },
            )
    }

    companion object {
        /** 实携终态 phase 词表（iOS :636-638；上游 sessionPhaseSchema 终态族 + draft）。 */
        private val PHASE_TERMINAL_VOCABULARY = setOf(
            "completedSuccess",
            "completedInterrupted",
            "error",
            "draft",
        )
    }
}

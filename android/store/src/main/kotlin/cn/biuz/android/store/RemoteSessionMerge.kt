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

    // MARK: bootstrap.tasks 跨工作区全量索引 merge（iOS mergeBootstrapTaskIndex 直译 + 乙-6 纠正）

    /**
     * bootstrap-response 的 tasks 清单（跨工作区全量任务索引，iOS 实测 250 行/26 个
     * distinct workspacePath——web「所有项目目录」的真实数据源）。行形状与 listTaskList
     * items 同源：taskId/title/workspacePath/updatedAt——web Nxe 去重逻辑同款字段。
     * 跨工作区无需 scope RPC。
     *
     * 与 iOS 的两处差异（均出自交接乙-6 红线，2026-10-09）：
     * ① **权威行纠正**：iOS 对已存在行只补缺 workspacePath（`:417-419` if nil）；
     *    本实现改为「行携带非空 workspacePath 即纠正既有归属」——上游幽灵行 bug 下
     *    「只补缺」会让先到的错误归属永久冻结（同会话双区行，真实区行后到也必须赢）。
     *    task-index 是 status 字段的权威源（行携带即回填——运行/失败态跨刷新保留，iOS :420-423 同）。
     * ② workspaceId 不作归属来源（见 SessionSummaryParser.parse）。
     */
    suspend fun mergeBootstrapTasks(items: List<JSONValue>): BootstrapMergeStats {
        if (items.isEmpty()) {
            return BootstrapMergeStats(0, 0, mutex.withLock { sessions.size }, 0)
        }
        var merged = 0
        val wsSeen = HashSet<String>()
        mutex.withLock {
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
                dict["workspacePath"]?.stringValue?.let { wsSeen.add(it) }
                val summary = SessionSummaryParser.parse(JSONValue.Obj(dict)) ?: continue
                val existing = sessions[summary.sessionId]
                if (existing == null) {
                    sessions[summary.sessionId] = summary
                    merged += 1
                } else {
                    // 已存在行：允许权威行纠正（乙-6①）+ taskStatus 权威回填
                    var updated = existing
                    val ws = summary.workspacePath
                    if (!ws.isNullOrEmpty() && updated.workspacePath != ws) {
                        updated = updated.copy(workspacePath = ws)
                    }
                    // task-index 是 status 字段的权威源（行携带即回填——运行/失败态跨刷新保留）
                    if (summary.taskStatus != null) {
                        updated = updated.copy(taskStatus = summary.taskStatus)
                    }
                    sessions[summary.sessionId] = updated
                }
            }
        }
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
     * 对清单内每个工作区逐个 listTaskList 聚合后的行合并（RPC 逐区调用在
     * RemoteSessionsIndexStore/后续全量 Store；本方法只做行级合并）。已有行**字段级回填**
     * （2026-10-08 报障「新建会话列表恒显占位标题 新会话」）：本地种行（createConversation
     * 占位标题）之后，桌面自动命名只随 task-index 与连接 workspace 的 sessions-index
     * 通道到达——非连接区行此处是唯一刷新点。只回填服务端权威的字段，不整行替换
     * （task-index 行无 preview/workflowActivity 等富字段，整行替换会剥掉 sessions-index
     * 已到达的投影）。标题让位本地改名 override（[titleOverriddenIds]）；
     * 活跃时间仅缺值时补（sessions-index 实时值不回退 task-index，防排序抖动）。
     * workspacePath 纠正同 bootstrap merge（乙-6①）。
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
        mutex.withLock {
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
                // 归属纠正（乙-6①；iOS :568-570 同向）
                val ws = summary.workspacePath
                if (!ws.isNullOrEmpty() && updated.workspacePath != ws) {
                    updated = updated.copy(workspacePath = ws)
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
            next[summary.sessionId] = summary
        }
        mutex.withLock {
            sessions.clear()
            sessions.putAll(next)
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
                    // sessions-index 行不携 status：整行替换前保住 task-index 已回填的状态
                    if (summary.taskStatus == null) {
                        summary = summary.copy(taskStatus = sessions[summary.sessionId]?.taskStatus)
                    }
                    sessions[summary.sessionId] = summary
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
            .filter { !it.isArchived }
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

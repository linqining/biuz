package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Stores/Remote/RemoteTaskStore.swift（549 行）。
//
// 并发模型适配（docs/Android开发交接.md §2.2）：Swift actor → Mutex 临界区
// （挂起点一律在锁外，与 protocol/ChannelClient.kt 同口径）；AsyncStream → Flow；
// notification 挂钩 → [RemoteTaskStore.onTaskStatusChanged] 可空回调（iOS
// NotificationService 属 UI 附属层，未在本批移植面）。
//
// 连接面适配（本批 :connection 未实装）：iOS 持有 ZCodeServerConnection 强类型引用；
// Kotlin 以 protocol 预置的 RPCChannelTransport 门面承接（call/listen 同签名，
// 勿重复定义——M1 残留 deviations 摘要）；ZCodeServerConnection.call 唯一出口 +
// ReadOnlyGate 出口拦截在 :connection 装配层补齐后，注入的 transport 即唯一出口实现。
// ServerWorkspaceInfo 未有 Kotlin 定义，本文件给 [StoreWorkspaceInfo] 承载（:connection
// 移植时采用同一形状，勿再定义第二份）。

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.TaskRecord
import cn.biuz.android.protocol.TaskStatus
import cn.biuz.android.protocol.TerminalLine
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 连接工作区条目（iOS ServerWorkspaceInfo 的 :store 侧承载；workspaceIdentity 为远端工作区判别键）。 */
data class StoreWorkspaceInfo(
    val path: String,
    val label: String? = null,
    val workspaceIdentity: String? = null,
)

/**
 * RemoteTaskStore 对会话 Store 的依赖面（iOS 原文直引 RemoteConversationStore 同型方法；
 * RemoteConversationStore 全量移植前的桥接口——未来实现类直接实现本接口注入）。
 * 四方法语义与 iOS 调用点一比一（RemoteTaskStore.swift:244-251/:270-274/:330-333）。
 */
interface RemoteConversationBridge {
    /** 该会话（任务）当前某类挂起交互（kinds 例 ["permission"]；null = 无挂起交互）。 */
    suspend fun currentPendingInteraction(
        conversationId: String,
        kinds: List<String>,
    ): RemotePendingInteraction?

    /** 交互应答信封（v4 resolveInteraction；返回命令回执，null=未送达）。 */
    suspend fun resolveInteractionRaw(conversationId: String, interactionId: String, answer: JSONValue): JSONValue?

    /** 停止 turn（统一 sendCommand 信封——嵌套形态、握手 clientId、workspace 信封齐全）；null=未送达。 */
    suspend fun stopTurn(sessionId: String): JSONValue?

    /** 会话行投影（trajectoryLines 数据源）。 */
    suspend fun messages(conversationId: String): List<ChatMessage>
}

/** JSONValue 的 Long 宽容读取（毫秒时间戳/revisionAtDecision 专用——JSONValue.Int 为 32 位
 * 值域，毫秒数越界落整值 double，须走 double/Long 路径；M1 残留 deviations 摘要口径）。 */
internal fun JSONValue?.longValue(): Long? {
    return when (this) {
        null -> null
        is JSONValue.Int -> value.toLong()
        is JSONValue.Double ->
            // 整值 double 直取（线缆整型字面量 1.79…E12 即毫秒时间戳主形态）；带小数不采
            if (value == kotlin.math.floor(value) && !value.isInfinite()) value.toLong() else null
        is JSONValue.Str -> value.toLongOrNull()
        else -> null
    }
}

// MARK: - 远端任务存储：zcode-task 频道读面 + 会话行投影

/**
 * 真实实现：TaskStore 协议按 mappingToApp 第 4 条落地。
 * - tasks() ← zcodeTaskService.listTaskList（真相源 tasks-index.sqlite）
 * - terminalStream/trajectoryLines ← conversation 行投影（toolCall/reasoning）
 * - 命令面（v3 纠偏口径）：approve/reject/stop 真实下发——审批经会话挂起交互解析
 *   真实 interactionId 后走 v4 resolveInteraction；stop 走 v4 stop 信封（sessionId=taskId）。
 *   均属「客户端发命令、桌面代执行」；文件直写/配置写仍由 ReadOnlyGate 出口拦截。
 */
class RemoteTaskStore(
    transport: RPCChannelTransport,
    workspace: StoreWorkspaceInfo,
    conversationStore: RemoteConversationBridge?,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TaskStore {

    private val transport: RPCChannelTransport = transport
    private val workspace: StoreWorkspaceInfo = workspace
    private val conversationStore: RemoteConversationBridge? = conversationStore

    override val isReadOnly: Boolean
        get() = true

    /** Swift actor 串行化的 Kotlin 等价物；锁内禁挂起（RPC 调用一律在锁外）。 */
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private val tasksState = MutableStateFlow<List<TaskRecord>>(emptyList())
    /** per-task 终端流桶（terminalStream 无挂起读取 → ConcurrentHashMap）。 */
    private val terminalFlows = ConcurrentHashMap<String, MutableSharedFlow<TerminalLine>>()

    /** onDynamicTaskEvent 订阅（任务活性服务端推送；named gap「状态变化只能手动下拉刷新」修复）。 */
    private var taskEventsSubscription: EventSubscription? = null
    /**
     * onDynamicWorkspaceEvent 订阅（任务列表结构变化推送：task_created 等触发
     * 列表全量重拉——web 同款即时刷新；只订当前活跃工作区，多工作区靠
     * bootstrap.tasks 合并口径不变）。
     */
    private var workspaceEventsSubscription: EventSubscription? = null

    /**
     * 任务状态迁移回调（iOS NotificationService.handleTaskStatusChange 的 :store 侧挂钩；
     * record.status 已是新态，previous 为迁移前态。UI/通知层装配时接入，默认 null 不挂）。
     */
    var onTaskStatusChanged: ((record: TaskRecord, previous: TaskStatus?) -> Unit)? = null

    /** 诊断钩子（iOS UserDefaults diag.* 的 Kotlin 承载；装配层在 diag 模式接线，键名与 iOS 同名）。 */
    var diagListener: ((key: String, value: String) -> Unit)? = null

    // MARK: 读面

    override suspend fun tasks(): List<TaskRecord> {
        ensureTaskEventsSubscribed()
        ensureWorkspaceEventsSubscribed()
        refresh()
        return mutex.withLock { tasksState.value }
    }

    override fun observeTasks(): Flow<List<TaskRecord>> = flow {
        // 订阅即建立服务端推送（断线随 Store 重建重订阅：Store 与连接同生命周期）；
        // iOS 在 AsyncStream 工厂内 yield(cache) 后台触发订阅——此处同序：先推当前缓存，
        // 再挂事件流（StateFlow 订阅即发射当前值）
        emit(mutex.withLock { tasksState.value })
        if (mutex.withLock { taskEventsSubscription == null }) {
            ensureTaskEventsSubscribed()
        }
        ensureWorkspaceEventsSubscribed()
        emitAll(tasksState.asStateFlow())
    }

    /** onDynamicTaskEvent 订阅（workspace 维度）：事件更新 cache 并发布；事件不可完整映射时回退 refresh()。 */
    private suspend fun ensureTaskEventsSubscribed() {
        if (mutex.withLock { taskEventsSubscription != null }) return
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        val subscription = transport.listen(
            "zcode-task",
            "onDynamicTaskEvent",
            RPCValue.json(JSONValue.Obj(fields)),
        ) { payload ->
            scope.launch { handleTaskEvent(payload.jsonValue) }
        }
        mutex.withLock {
            // 双重检查（并发订阅竞态：后来者让位并立即释放重复句柄）
            if (taskEventsSubscription != null) {
                subscription.cancel()
            } else {
                taskEventsSubscription = subscription
            }
        }
    }

    /**
     * onDynamicWorkspaceEvent 订阅（web 对齐 2026-10-06，bundle 消费点逐字取证）：
     * 入参 `{workspacePath, workspaceIdentity?}`（identity 在场才携）；事件载荷
     * `{type:'workspace_task_list_changed', workspacePath, workspaceIdentity,
     * reason:'task_created'|…}`——列表结构变化事件不携完整 task 行，web 处理即
     * 全量拉新，移动端同构回退 refresh()。只订当前活跃工作区。handler 注册先于
     * eventListen 帧（ChannelClient.listen 先落表后发帧——transport.listen 返回即已发出）。
     */
    private suspend fun ensureWorkspaceEventsSubscribed() {
        if (mutex.withLock { workspaceEventsSubscription != null }) return
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        val identity = workspace.workspaceIdentity
        if (!identity.isNullOrEmpty()) {
            fields["workspaceIdentity"] = JSONValue.Str(identity)
        }
        val subscription = transport.listen(
            "zcode-task",
            "onDynamicWorkspaceEvent",
            RPCValue.json(JSONValue.Obj(fields)),
        ) { payload ->
            scope.launch { handleWorkspaceEvent(payload.jsonValue) }
        }
        mutex.withLock {
            if (workspaceEventsSubscription != null) {
                subscription.cancel()
            } else {
                workspaceEventsSubscription = subscription
            }
        }
    }

    /** workspace_task_list_changed → 全量重拉（reason 宽容透传 diag 供取证）。 */
    private suspend fun handleWorkspaceEvent(payload: JSONValue?) {
        val dict = payload?.objectValue ?: return
        if (dict["type"]?.stringValue != "workspace_task_list_changed") return
        diagListener?.invoke(
            "diag.task.wsEvent",
            "workspace_task_list_changed reason=${dict["reason"]?.stringValue ?: "?"}",
        )
        refresh()
    }

    /**
     * 事件 → cache 更新。ZCodeStreamEvent 家族宽容解析：携带完整 task 形态
     * （taskId + workspacePath）就地 upsert；仅携带 taskId 则标记并回退 refresh。
     */
    private suspend fun handleTaskEvent(payload: JSONValue?) {
        val dict = payload?.objectValue ?: return
        // 事件体外层可能直接是 task，也可能包在 task/event 字段里
        val taskJSON: Map<String, JSONValue>? = when {
            dict["task"]?.objectValue != null -> dict["task"]?.objectValue
            dict["taskId"]?.stringValue != null && dict["workspacePath"]?.stringValue != null -> dict
            else -> null
        }
        val record = taskJSON?.let { mapTask(JSONValue.Obj(it)) }
        if (record != null) {
            var previousStatus: TaskStatus? = null
            mutex.withLock {
                val current = tasksState.value.toMutableList()
                val index = current.indexOfFirst { it.id == record.id }
                if (index >= 0) {
                    // 保留本地未变字段（todo 投影等事件可能缺省）
                    val existing = current[index]
                    val merged = record.copy(
                        todoDone = if (record.todoDone == 0) existing.todoDone else record.todoDone,
                        todoTotal = if (record.todoTotal == 0) existing.todoTotal else record.todoTotal,
                    )
                    previousStatus = existing.status
                    current[index] = merged
                } else {
                    current.add(record)
                }
                tasksState.value = current
            }
            // 任务状态/待审批本地通知（iOS NotificationService 内部去重；Kotlin 侧回调直投）
            val previous = previousStatus
            if (previous != null && previous != record.status) {
                onTaskStatusChanged?.invoke(record, previous)
            }
        } else if (dict["taskId"]?.stringValue != null || dict["sessionId"]?.stringValue != null) {
            refresh()
        }
    }

    /** zcodeTaskService.listTaskList（上游 zcodeTaskListTypes.ts:12-28 ZCodeTaskListQuery）。 */
    suspend fun refresh() {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["kind"] = JSONValue.Str("timeline")
        fields["workspaceScopes"] = JSONValue.Arr(
            listOf(
                JSONValue.Obj(mapOf("workspacePath" to JSONValue.Str(workspace.path))),
            ),
        )
        fields["sortBy"] = JSONValue.Str("updated")
        fields["limit"] = JSONValue.Int(50)
        val items = try {
            val result = transport.call("zcode-task", "listTaskList", RPCValue.json(JSONValue.Obj(fields)))
            result.jsonValue?.get("items")?.arrayValue ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // 保持上一次快照（离线兜底由装配层处理）
        }
        val next = items.mapNotNull { mapTask(it) }
        mutex.withLock { tasksState.value = next }
    }

    // MARK: 行映射（RemoteTaskStore.swift:185-221 直译）

    fun mapTask(json: JSONValue?): TaskRecord? {
        val dict = json?.objectValue ?: return null
        val taskId = dict["taskId"]?.stringValue ?: return null
        val workspacePath = dict["workspacePath"]?.stringValue ?: return null
        val status: TaskStatus = when (dict["status"]?.stringValue) {
            "running" -> TaskStatus.running
            "completed" -> TaskStatus.done
            "error" -> TaskStatus.failed
            else -> TaskStatus.waiting
        }
        // updatedAt：毫秒时间戳越 Int32 值域——恒走 double/Long 路径（longValue）
        val updatedAtMs = dict["updatedAt"].longValue() ?: 0L
        val lastErrorMessage = dict["lastError"]?.objectValue?.get("message")?.stringValue
        val record = TaskRecord(
            id = taskId,
            title = dict["title"]?.stringValue ?: "未命名任务",
            summary = lastErrorMessage
                ?: (if (dict["changeSummary"] != null) "有新的文件变更待审查" else null)
                ?: "",
            directory = workspacePath,
            status = status,
            todoDone = 0,
            todoTotal = 0,
            progress = if (status == TaskStatus.done) 1.0 else 0.0,
            tools = emptyList(),
            updatedAt = updatedAtMs,
            lastLog = dict["lastError"]?.objectValue?.get("detail")?.stringValue,
            pendingCommand = null,
            pendingImpact = null,
        )
        val change = dict["changeSummary"]?.objectValue
        val pendingImpact = change?.let {
            val fileCount = it["fileCount"].longValue()?.toInt() ?: 0
            val added = it["added"].longValue()?.toInt() ?: 0
            val removed = it["removed"].longValue()?.toInt() ?: 0
            "文件 $fileCount · +$added/-$removed"
        }
        // 结构化摘要（会话页变更条 P-019 数据源）：files 逐条宽容解析，缺键的行跳过
        val summary = change?.let { obj ->
            val files = (obj["files"]?.arrayValue ?: emptyList()).mapNotNull { item ->
                val filePath = item["path"]?.stringValue ?: return@mapNotNull null
                cn.biuz.android.protocol.TaskChangedFile(
                    path = filePath,
                    added = item["added"].longValue()?.toInt() ?: 0,
                    removed = item["removed"].longValue()?.toInt() ?: 0,
                )
            }
            cn.biuz.android.protocol.TaskChangeSummary(
                fileCount = obj["fileCount"].longValue()?.toInt() ?: files.size,
                added = obj["added"].longValue()?.toInt() ?: files.sumOf { it.added },
                removed = obj["removed"].longValue()?.toInt() ?: files.sumOf { it.removed },
                files = files,
            )
        }
        return record.copy(
            pendingImpact = pendingImpact,
            changeSummary = summary,
        )
    }

    // MARK: 动作面（v3 纠偏口径：客户端发命令、桌面代执行）

    /** 批准：解析该任务（会话）最新 permission 类挂起交互 → v4 resolveInteraction {interactionId, answer:{optionId}}。 */
    override suspend fun approve(taskId: String, optionId: String): TaskDecisionOutcome {
        return resolvePermission(taskId, optionId)
    }

    /** 拒绝：同 approve（rejectOnce/rejectAlways 由 optionId 承载方向）。 */
    override suspend fun reject(taskId: String, optionId: String): TaskDecisionOutcome {
        return resolvePermission(taskId, optionId)
    }

    /**
     * 权限应答共用支路：interactionId 来自会话挂起交互投影；answer 恒为对象 {optionId}
     * （A-3：approved/scope 是 wire 不存在的键，被 strip 后 answer 退化空对象 = 假成功）。
     * interaction 不在场 → InteractionMissing（U-5：不再静默 return，调用方如实提示）。
     */
    suspend fun resolvePermission(taskId: String, optionId: String): TaskDecisionOutcome {
        val store = conversationStore ?: return TaskDecisionOutcome.Undelivered
        val interaction = store.currentPendingInteraction(taskId, listOf("permission"))
            ?: return TaskDecisionOutcome.InteractionMissing
        val ack = store.resolveInteractionRaw(
            taskId,
            interaction.id,
            JSONValue.Obj(mapOf("optionId" to JSONValue.Str(optionId))),
        ) ?: return TaskDecisionOutcome.Undelivered
        val status = ack["status"]?.stringValue
            ?: ack.objectValue?.get("ack")?.objectValue?.get("status")?.stringValue
        return when (status) {
            null, "accepted", "noop", "applied", "ok" -> TaskDecisionOutcome.Accepted
            else -> {
                val reason = ack["reasonCode"]?.stringValue ?: status ?: "?"
                TaskDecisionOutcome.Rejected("桌面端拒绝（$reason）")
            }
        }
    }

    /**
     * 停止任务：委托会话 Store stopTurn（统一 sendCommand 信封——嵌套形态、
     * 握手 clientId、epoch 毫秒 issuedAt、workspace 信封齐全）。状态回流由
     * onDynamicTaskEvent 驱动卡片/状态条翻转。桌面代执行命令，边界内允许。
     */
    override suspend fun stop(taskId: String): String? {
        val store = conversationStore ?: return "未连接桌面端，停止指令未送达"
        // M2 写面如实回传：stopTurn 回执 null = 命令未送达（连接异常/被拒），此前静默
        val ack = store.stopTurn(taskId)
        return if (ack == null) "停止指令未送达 · 连接恢复后重试" else null
    }

    /**
     * 失败重试：retryTurn 需 target{rowId, entityId}（rewind 后重喂 agent 的精确
     * 游标），任务详情语境拿不到该游标——本轮不接（UI 无重试入口，保持协议位）。
     */
    override suspend fun retry(taskId: String) {
        // retryTurn 游标缺失，暂不下发（诚实不实现，不虚构「已重试」）
    }

    /** 服务端全文搜索（P2）：listTaskList 透传 search（服务端 searchable_text/snippets 检索，纯只读）。空 query 退化为普通刷新。 */
    override suspend fun searchTasks(query: String): List<TaskRecord> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            refresh()
            return mutex.withLock { tasksState.value }
        }
        val fields = LinkedHashMap<String, JSONValue>()
        fields["kind"] = JSONValue.Str("timeline")
        fields["workspaceScopes"] = JSONValue.Arr(
            listOf(
                JSONValue.Obj(mapOf("workspacePath" to JSONValue.Str(workspace.path))),
            ),
        )
        fields["sortBy"] = JSONValue.Str("updated")
        fields["limit"] = JSONValue.Int(50)
        // 键名 = search（ZCodeTaskListQuery.search?，上游 zcodeTaskListTypes.ts:13——
        // 2026-10-08 回归审核轮 §11.1#7：此前发 searchQuery 被服务端忽略，
        // 搜索静默返回未过滤的最近 50 条假结果）
        fields["search"] = JSONValue.Str(trimmed)
        return try {
            val result = transport.call("zcode-task", "listTaskList", RPCValue.json(JSONValue.Obj(fields)))
            val items = result.jsonValue?.get("items")?.arrayValue ?: return emptyList()
            items.mapNotNull { mapTask(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // MARK: 终端输出 / 轨迹投影

    override fun terminalStream(taskId: String): Flow<TerminalLine> {
        // MutableSharedFlow 惰性建桶（读面无挂起）。iOS 原文同理：AsyncStream 工厂只登记
        // continuation、本文件内无发布点（后台 Bash 输出注入面未接，保持协议位）
        return terminalFlows.computeIfAbsent(taskId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }.asSharedFlow()
    }

    override suspend fun trajectoryLines(taskId: String): List<TerminalLine> {
        // reasoning + toolCall 行投影（mappingToApp：trajectoryLines ← reasoning+toolCall）
        val store = conversationStore ?: return emptyList()
        val rows = store.messages(taskId)
        val lines = ArrayList<TerminalLine>()
        var id = 0
        for (message in rows) {
            if (message.role == MessageRole.agent && message.text.isNotEmpty() && message.toolCall == null) {
                lines.add(TerminalLine(id = id, label = "reasoning", text = message.text))
                id += 1
            }
            val tool = message.toolCall
            if (tool != null) {
                val outputSuffix = tool.output?.let { " → $it" } ?: ""
                lines.add(TerminalLine(id = id, label = tool.kind.name.uppercase(), text = "${tool.target}$outputSuffix"))
                id += 1
            }
        }
        return lines
    }

    // MARK: 只读元数据（连接态展示：配置/模型/Token 用量；不调任何 set*）

    /** getTaskConfigOptions：option 名 → 当前值（currentValue 兼容 string/boolean） */
    override suspend fun taskConfigOptions(taskId: String): Map<String, String>? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(taskId)
        return try {
            val result = transport.call("zcode-task", "getTaskConfigOptions", RPCValue.json(JSONValue.Obj(fields)))
            val items = result.jsonValue?.arrayValue
                ?: result.jsonValue?.get("options")?.arrayValue
                ?: result.jsonValue?.get("configOptions")?.arrayValue
                ?: emptyList()
            val options = LinkedHashMap<String, String>()
            for (item in items) {
                val dict = item.objectValue ?: continue
                val name = dict["name"]?.stringValue ?: dict["id"]?.stringValue ?: continue
                val value = when (val current = dict["currentValue"]) {
                    is JSONValue.Str -> current.value
                    is JSONValue.Bool -> if (current.value) "开" else "关"
                    else -> "--"
                }
                options[name] = value
            }
            if (options.isEmpty()) null else options
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** getTaskModelSelection：当前绑定模型（只读展示；不调 setModel） */
    override suspend fun taskModelSelection(taskId: String): String? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(taskId)
        return try {
            val result = transport.call("zcode-task", "getTaskModelSelection", RPCValue.json(JSONValue.Obj(fields)))
            val dict = result.jsonValue?.objectValue ?: return null
            // ModelSelection {providerId, modelId, options:{reasoningLevel}}
            val modelId = dict["modelId"]?.stringValue
            if (modelId != null) {
                val reasoning = dict["options"]?.objectValue?.get("reasoningLevel")?.stringValue
                return if (reasoning != null) "$modelId · $reasoning" else modelId
            }
            dict["model"]?.stringValue ?: dict["name"]?.stringValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** getTaskTokenUsage：累计用量（ZCodeTaskTokenUsageResult：input/output/totalTokens） */
    override suspend fun taskTokenUsage(taskId: String): TaskTokenUsage? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(taskId)
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        return try {
            val result = transport.call("zcode-task", "getTaskTokenUsage", RPCValue.json(JSONValue.Obj(fields)))
            val dict = result.jsonValue?.objectValue ?: return null
            // 计数可能超 Int32（长会话累计）——宽容走 Long 路径后再收窄
            val input = (dict["inputTokens"].longValue() ?: dict["input"].longValue() ?: 0L)
            val output = (dict["outputTokens"].longValue() ?: dict["output"].longValue() ?: 0L)
            val total = (dict["totalTokens"].longValue() ?: dict["total"].longValue() ?: (input + output))
            if (input <= 0 && output <= 0 && total <= 0) return null
            TaskTokenUsage(
                input = input.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                output = output.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                total = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // MARK: 桌面分组视图结构（P2-6：listGroupedTaskViewStructure，任务看板对齐桌面分组）

    /**
     * 桌面分组结构只读拉取。B-2 修正（web 实证 bundle，2026-10-06）：入参恒为
     * `{workspaceScopes:[{workspacePath, workspaceIdentity?}]}`（web 调用点
     * `listGroupedTaskViewStructure({workspaceScopes:i})`，条目含 workspaceIdentity）
     * ——原「先 workspaceScopes 后扁平 {workspacePath} 双形态探针」系零取证时期的
     * 猜测序，扁平形态不在 web 调用面，撤除。失败/解析不出 → null（消费侧回退本地
     * 状态分组并轻提示）。
     */
    suspend fun groupedTaskViewStructure(): DesktopTaskGrouping? {
        val arg = RPCValue.json(
            JSONValue.Obj(
                mapOf(
                    "workspaceScopes" to JSONValue.Arr(
                        listOf(JSONValue.Obj(taskWorkspaceScopeJson(workspace))),
                    ),
                ),
            ),
        )
        return try {
            val result = transport.call("zcode-task", "listGroupedTaskViewStructure", arg)
            parseGroupedTaskView(result.jsonValue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保持 null → TaskBoardView 回退本地状态分组（groupingUnavailable 轻提示）
            null
        }
    }

    companion object {
        /**
         * workspaceScopes 条目（B-2/B-3 共用；web K3 形状 `{workspacePath, workspaceIdentity?}`，
         * identity 缺席省键 = web JSON.stringify 丢 undefined 的同行为）。
         */
        fun taskWorkspaceScopeJson(workspace: StoreWorkspaceInfo): Map<String, JSONValue> {
            val scope = LinkedHashMap<String, JSONValue>()
            scope["workspacePath"] = JSONValue.Str(workspace.path)
            val identity = workspace.workspaceIdentity
            if (!identity.isNullOrEmpty()) {
                scope["workspaceIdentity"] = JSONValue.Str(identity)
            }
            return scope
        }

        /**
         * 回执解析（B-2 重写，web 实证【移植·bundle 逆向】2026-10-06）：形状
         * `{groups:[{id,title,color,…}], members:[{groupId,workspacePath,
         * workspaceIdentity,taskId}], topLevelOrders:[…]}`
         * ——成员在**顶层 members 数组**、按自带 groupId 归组（组对象内无任何成员键；
         * 旧解析在组内找 tasks/taskIds/items/order 恒空 → 每组 taskIDs 恒空 → 桌面
         * 分组恒回退本地四组，即审查报告 B-2「RPC 成功的静默假成功」）。
         * topLevelOrders 携带组排序（`{type:'group',groupId,sortOrder}`；任务条目
         * `{type:'task',workspaceKey,taskId,sortOrder}` 仅排序用，成员归属仍以 members
         * 为准）。回执无 groups 键/非对象 → null（解析失败与「桌面无分组」区分：空
         * groups 数组是合法态，返回空 groups 的 grouping）。
         */
        fun parseGroupedTaskView(value: JSONValue?): DesktopTaskGrouping? {
            val dict = value?.objectValue ?: return null
            val rawGroups = dict["groups"]?.arrayValue ?: return null
            // 顶层 members 按 groupId 归组（保持 members 数组序 = web 组内任务序）
            val membersByGroup = LinkedHashMap<String, MutableList<DesktopTaskGrouping.TaskRef>>()
            for (member in dict["members"]?.arrayValue ?: emptyList()) {
                val entry = member.objectValue ?: continue
                val groupId = entry["groupId"]?.stringValue ?: continue
                val taskId = entry["taskId"]?.stringValue ?: continue
                val workspacePath = entry["workspacePath"]?.stringValue ?: continue
                membersByGroup.getOrPut(groupId) { ArrayList() }.add(
                    DesktopTaskGrouping.TaskRef(
                        workspacePath = workspacePath,
                        workspaceIdentity = entry["workspaceIdentity"]?.stringValue,
                        taskId = taskId,
                    ),
                )
            }
            // 组排序：topLevelOrders 组条目 sortOrder 升序；无排序条目的组按回执序殿后
            val groupOrder = HashMap<String, Int>()
            for (order in dict["topLevelOrders"]?.arrayValue ?: emptyList()) {
                val entry = order.objectValue ?: continue
                if (entry["type"]?.stringValue != "group") continue
                val groupId = entry["groupId"]?.stringValue ?: continue
                groupOrder[groupId] = entry["sortOrder"].longValue()?.toInt() ?: 0
            }
            data class Entry(val group: DesktopTaskGrouping.Group, val sort: Int, val fallback: Int)

            val entries = ArrayList<Entry>()
            rawGroups.forEachIndexed { index, groupJson ->
                val group = parseGroupedTaskViewGroup(groupJson) ?: return@forEachIndexed
                val sortOrder = groupOrder[group.id]
                entries.add(
                    if (sortOrder != null) Entry(group, sortOrder, index) else Entry(group, Int.MAX_VALUE, index),
                )
            }
            return DesktopTaskGrouping(
                groups = entries
                    .sortedWith(compareBy({ it.sort }, { it.fallback }))
                    .map { entry ->
                        // 成员归组：顶层 members 按自带 groupId 领取（组对象内无成员键——B-2）
                        val group = entry.group
                        val refs = membersByGroup[group.id] ?: emptyList()
                        group.copy(taskRefs = refs, taskIds = refs.map { it.taskId })
                    },
            )
        }

        /**
         * 组条目：web 组对象 {id, title, color, …}（bundle 消费点 e.group.id/
         * group.title/group.color）。collapsed 不在 web 形状内，宽容读
         * collapsed/isCollapsed（未取证，仅作本地折叠初值）。
         */
        private fun parseGroupedTaskViewGroup(json: JSONValue): DesktopTaskGrouping.Group? {
            val dict = json.objectValue ?: return null
            val id = (dict["id"]?.stringValue ?: dict["groupId"]?.stringValue) ?: return null
            if (id.isEmpty()) return null
            val name = dict["title"]?.stringValue ?: id
            val collapsed = dict["collapsed"]?.boolValue ?: dict["isCollapsed"]?.boolValue ?: false
            return DesktopTaskGrouping.Group(
                id = id,
                name = name,
                collapsed = collapsed,
                taskRefs = emptyList(),
                taskIds = emptyList(),
            )
        }
    }
}

// MARK: - 桌面分组视图结构投影（RemoteTaskStore.swift:524-549 直译）

/**
 * P2-6：桌面分组视图结构投影（zcode-task.listGroupedTaskViewStructure 回执）。
 * B-2 重写（web 实证【移植·bundle 逆向】2026-10-06）：回执
 * `{groups:[{id,title,color,…}], members:[{groupId,workspacePath,workspaceIdentity,
 * taskId}], topLevelOrders:[…]}`——成员按自带 groupId 归组、组排序取 topLevelOrders
 * 组条目 sortOrder。taskRefs 保留完整工作区引用（B-3 全量视图写回所需）。
 */
data class DesktopTaskGrouping(
    val groups: List<Group>,
) {
    /** 组成员/写回条目（web 形状 {workspacePath, workspaceIdentity?, taskId}） */
    data class TaskRef(
        val workspacePath: String,
        val workspaceIdentity: String?,
        val taskId: String,
    )

    data class Group(
        /** groupId（web 组对象 id；真实组标识——写面禁止以组名充当，B-3） */
        val id: String,
        /** 组名（web title；title 缺席时以 id 兜底显示） */
        val name: String,
        /** 桌面折叠标记（宽容读 collapsed/isCollapsed；未取证——仅作本地折叠初值） */
        val collapsed: Boolean,
        /** 组成员完整引用（回执 members 序；B-3 全量视图写回的数据基线） */
        val taskRefs: List<TaskRef>,
        /** 成员 taskId（taskRefs 投影；消费侧 TaskBoardView 按 id 对齐缓存） */
        val taskIds: List<String>,
    )
}

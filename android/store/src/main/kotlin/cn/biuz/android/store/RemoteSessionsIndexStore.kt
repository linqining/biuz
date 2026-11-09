package cn.biuz.android.store

// sessions-index 订阅 + 失败兜底链 + 帧应用：iOS RemoteConversationStore.swift:686-858 的
// 独立承载（会话 Store 全量移植在后续批次，本类先落订阅/兜底/帧面，勿重复生长消息面逻辑）。
//
// 纪律对照（AGENTS §5）：
// - §5-3 帧 handler 先于 subscribe 注册——transport.listen 返回即 eventListen 帧已按序
//   写出（ChannelClient.listen 同步发送后才返回），随后才发 subscribe 调用；
// - §5-6 订阅失败必须有兜底链——sessions-index → listSessions（本文件）；
// - §5-7 v4 订阅回执取不到 subscriptionId = 自愈链路失效，视为错误处理（走兜底，
//   不得静默落 null 仍走成功路径）；
// - §5-12 读面失败三态呈现——listLoadFailure 与 last-good 数据共存，不折叠成空态。
//
// 【上游裁决 2026-10-09 /tmp/zcode-upstream】
// - 订阅参数：topic + workspacePath（服务端签名 zcodeAgentPluginParams.ts:8-11）+
//   workspaceIdentity?（identity 在场才携）+ runtimePolicy:"existing-only"
//   （被动订阅禁拉起 Agent——zcodeAgentService.ts:5494-5497 注释口径）；
// - topic 键 = identity?.trim() || path（agentSessionsIndexTransport.ts:70-82
//   `sessionsIndexTopic(target.workspaceIdentity?.trim() || target.workspacePath)`；
//   identity 型工作区帧 topic 是 identity 而非 path，恒用 path 注册 handler 会静默丢帧）；
// - 回执形态 {ack:{subscriptionId,mode,logEpoch}}（中继桥实测），顶层兼容局域网；
// - 增量 op 词表：session.upserted{session} / session.removed{sessionId}
//   （sessions-index.ts:66-70 sessionsIndexDeltaSchema）。

import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.TopicWireFrame
import cn.biuz.android.protocol.V4TopicFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * sessions-index 订阅面：订阅 → 失败兜底（listSessions）→ 帧应用（快照/增量）→
 * 会话列表投影。读面入口 [conversations] 与 [observeConversations]。
 */
class RemoteSessionsIndexStore(
    private val transport: RPCChannelTransport,
    private val workspace: StoreWorkspaceInfo,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val table = SessionMergeTable()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)

    private var subscription: EventSubscription? = null
    private var subscriptionId: String? = null
    private var watermarkLogEpoch: String? = null
    private var watermarkSeq: Int = 0
    @Volatile private var listLoadFailure: String? = null

    /** 本地改名 override（renameTask 写失败回滚；投影时让位服务端权威标题）。 */
    @Volatile
    var titleOverrides: Map<String, String> = emptyMap()

    /** 本地置顶/归档 override（操作即时反馈；投影三源 join 第一源）。 */
    @Volatile
    var pinnedOverrides: Map<String, Boolean> = emptyMap()

    @Volatile
    var archivedOverrides: Map<String, Boolean> = emptyMap()

    /** tasks-index membership（listPinnedTasks/listArchivedTasks 逐 scope 拉取的组织态权威源）。 */
    @Volatile
    var pinnedTaskIds: Set<String> = emptySet()

    @Volatile
    var archivedTaskIds: Set<String> = emptySet()

    /** 未读本地置位（「标记未读」；投影未读 = 本地置位 ? max(pending,1) : pending）。 */
    @Volatile
    var unreadFlags: Map<String, Boolean> = emptyMap()

    /** 诊断钩子（iOS diag.* 同名键；装配层 diag 模式接线）。 */
    var diagListener: ((key: String, value: String) -> Unit)?
        get() = table.diagListener
        set(value) {
            table.diagListener = value
        }

    /**
     * sessions-index 帧 topic 键（2026-10-08 回归审核轮 §11.2）：服务端按
     * resolveWorkspaceKey = identity || path 派发——identity 型工作区（remote/SSH/WSL）
     * 帧 topic 是 identity 而非 path，此前恒用 path 注册 handler，该类工作区快照/增量
     * 全部静默丢弃（列表退化为 30s 聚合）。
     */
    val sessionsIndexTopicKey: String
        get() = workspace.workspaceIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: workspace.path

    /** 会话列表级失败文本（读面如实呈现：null = 正常；非 null = UI 出错误页/横幅）。 */
    val conversationsLoadFailure: String?
        get() = listLoadFailure

    // MARK: 订阅（iOS ensureSessionsIndexSubscribed :701-755 直译）

    /**
     * 订阅 sessions-index topic；失败/回执缺 subscriptionId → listSessions 兜底。
     * 快照覆盖语义：订阅成功后帧仍以快照为准。
     */
    suspend fun ensureSubscribed() {
        if (mutex.withLock { subscription != null }) return
        val topic = "sessions-index/$sessionsIndexTopicKey"
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        val identity = workspace.workspaceIdentity
        if (!identity.isNullOrEmpty()) {
            fields["workspaceIdentity"] = JSONValue.Str(identity)
        }
        // 帧 handler 先于订阅注册：中继桥快照帧在 subscribe 回执前即推，
        // 对未注册 topic 的帧会丢弃（workspace-config 的 replay 机制同一动因）
        val listenArg = RPCValue.json(JSONValue.Obj(LinkedHashMap(fields)))
        val handle = transport.listen("zcode-agent", EVENT_SESSIONS_INDEX_FRAME, listenArg) { payload ->
            scope.launch { handleSessionsIndexPayload(payload.jsonValue) }
        }
        mutex.withLock {
            if (subscription != null) {
                handle.cancel()
                return
            }
        }
        // 订阅成功才落 subscription 句柄（iOS :739-745 同口径：失败路径保持 nil，
        // 下次 ensureSubscribed 可重入重订；本帧槽随失败分支显式回收防泄漏）
        // 服务端签名要求 topic + workspacePath；runtimePolicy=existing-only
        // （被动订阅禁拉起 Agent——此前缺省落入 start-if-needed，打开列表即可能为该区拉起 CLI）
        val subscribeArg = LinkedHashMap(fields)
        subscribeArg["topic"] = JSONValue.Str(topic)
        subscribeArg["runtimePolicy"] = JSONValue.Str("existing-only")
        try {
            val reply = transport.call("zcode-agent", "subscribeSessionsIndexV4", RPCValue.json(JSONValue.Obj(subscribeArg)))
            // 回执形态：{ack:{subscriptionId,mode,logEpoch}}（中继桥实测）；顶层兼容局域网。
            // 取不到 subId = 丢帧自愈（resync）全链路失效——视为订阅失败走 listSessions
            // 兜底，不得静默落 null 仍走成功路径（AGENTS §5-7）
            val json = reply.jsonValue
            val ack = json?.get("ack")?.objectValue ?: json?.objectValue
            val newSubscriptionId = ack?.get("subscriptionId")?.stringValue
                ?: json?.get("subscriptionId")?.stringValue
            if (newSubscriptionId == null) {
                handle.cancel()
                listLoadFailure = "会话实时同步订阅失败 · 回执缺 subscriptionId（自愈链不可用）"
                fallbackListSessions()
                return
            }
            mutex.withLock {
                subscriptionId = newSubscriptionId
                subscription = handle
            }
            listLoadFailure = null
        } catch (e: CancellationException) {
            handle.cancel()
            throw e
        } catch (e: Exception) {
            // 订阅失败兜底（named gap「订阅失败列表恒空」）：listSessions 只读拉一次列表。
            // 拒因走 listLoadFailure（RPCError.name+message——不能丢真实拒因，§5-12）
            handle.cancel()
            val failureText = loadFailureText("订阅会话列表", e)
            listLoadFailure = failureText
            fallbackListSessions()
        }
    }

    /**
     * listSessions 兜底读（zcode-agent.listSessions，workspacePath 界定范围）。
     * 回执宽容解析：顶层数组或 {sessions:[…]} 两种形态。兜底亦失败：列表保持既有态
     * （离线可用的兜底由装配层负责）。
     */
    suspend fun fallbackListSessions() {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        val items: List<JSONValue> = try {
            val result = transport.call("zcode-agent", "listSessions", RPCValue.json(JSONValue.Obj(fields)))
            val json = result.jsonValue
            json?.arrayValue ?: json?.get("sessions")?.arrayValue ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val replaced = table.replaceFromListSessions(items)
        // 空清单/全部解析失败不动既有表（iOS `guard !next.isEmpty else { return }` 同口径）
        if (replaced) yieldConversationsReplaced()
    }

    /**
     * assembler dropped → resyncSessionsIndexV4（subscriptionId + 水位 base；无 logEpoch 传 null 全量）。
     * iOS 由 connection.setFrameDropHandler 触发（:721-724）；:connection 移植接同一钩子。
     */
    suspend fun resyncSessionsIndex() {
        val subId = mutex.withLock { subscriptionId } ?: return
        val fields = LinkedHashMap<String, JSONValue>()
        fields["subscriptionId"] = JSONValue.Str(subId)
        val logEpoch = watermarkLogEpoch
        fields["base"] = if (logEpoch != null) {
            JSONValue.Obj(
                mapOf(
                    "logEpoch" to JSONValue.Str(logEpoch),
                    "seq" to JSONValue.Int(watermarkSeq),
                ),
            )
        } else {
            JSONValue.Null
        }
        fields["runtimePolicy"] = JSONValue.Str("existing-only")
        try {
            transport.call("zcode-agent", "resyncSessionsIndexV4", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径：resync 失败静默（丢帧自愈链的下一次触发兜底）
        }
    }

    /** 会话关闭/Store 释放：handler 反注册 + 按 subscriptionId 精确退订（§11.1#2）。 */
    suspend fun dispose() {
        val (handle, subId) = mutex.withLock {
            val h = subscription ?: return
            subscription = null
            val s = subscriptionId
            subscriptionId = null
            h to s
        }
        handle.cancel()
        val fields = LinkedHashMap<String, JSONValue>()
        fields["topic"] = JSONValue.Str("sessions-index/$sessionsIndexTopicKey")
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        // 退订按 subscriptionId 精确匹配，必带（§11.1#2）
        subId?.let { fields["subscriptionId"] = JSONValue.Str(it) }
        try {
            transport.call("zcode-agent", "unsubscribeSessionsIndexV4", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径
        }
    }

    // MARK: 帧应用（iOS handleSessionsIndexFrame :814-858 直译）

    private suspend fun handleSessionsIndexPayload(json: JSONValue?) {
        val envelope = json?.let { TopicWireFrame.parse(it) } ?: return
        val complete = envelope.completeFrame ?: return
        val frame = V4TopicFrame.parse(complete) ?: return
        watermarkSeq = frame.toSeq
        val snapshot = frame.snapshot
        if (snapshot != null) {
            val epoch = snapshot.objectValue?.get("logEpoch")?.stringValue
            if (epoch != null) watermarkLogEpoch = epoch
            table.applySessionsSnapshot(snapshot)
        }
        for (delta in frame.deltas) {
            table.applySessionDelta(delta)
        }
        yieldConversationsReplaced()
    }

    // MARK: 读面投影

    /** 合并表投影（订阅先行；投影参数 = 本类持有的 override/membership 态）。 */
    suspend fun conversations(): List<Conversation> {
        ensureSubscribed()
        return table.toConversations(
            titleOverrides = titleOverrides,
            pinnedOverrides = pinnedOverrides,
            archivedOverrides = archivedOverrides,
            pinnedIds = pinnedTaskIds,
            archivedIds = archivedTaskIds,
            unreadFlags = unreadFlags,
        )
    }

    /** 合并表当前投影（无订阅副作用——纯内存读，P0 缓存先行口径）。 */
    suspend fun currentConversations(): List<Conversation> =
        table.toConversations(
            titleOverrides = titleOverrides,
            pinnedOverrides = pinnedOverrides,
            archivedOverrides = archivedOverrides,
            pinnedIds = pinnedTaskIds,
            archivedIds = archivedTaskIds,
            unreadFlags = unreadFlags,
        )

    /**
     * 归档区读面（019 归档管理）：与 [conversations] 同参数，但**保留**归档行
     * （主列表 filter 掉归档；此处 includeArchived）。调用方自行按 isArchived 过滤。
     */
    suspend fun archivedConversations(): List<Conversation> {
        ensureSubscribed()
        return table.toConversations(
            titleOverrides = titleOverrides,
            pinnedOverrides = pinnedOverrides,
            archivedOverrides = archivedOverrides,
            pinnedIds = pinnedTaskIds,
            archivedIds = archivedTaskIds,
            unreadFlags = unreadFlags,
            includeArchived = true,
        ).filter { it.isArchived }
    }

    /** 会话事件流：订阅者先收到一次全量替换事件（iOS AsyncStream 工厂 yield 同口径）。 */
    fun observeConversations(): Flow<ConversationEvent> = flow {
        emit(ConversationEvent.ConversationsReplaced(currentConversations()))
        emitAll(events.asSharedFlow())
    }

    /** bootstrap.tasks 直接入会话表（AppSession 装配回写口；注入后立即合并——列表首跑早于装配的竞态在此收口）。 */
    suspend fun setBootstrapTasks(tasks: JSONValue?) {
        val items = tasks?.arrayValue ?: return
        table.mergeBootstrapTasks(items)
        yieldConversationsReplaced()
    }

    /** listTaskList 聚合行的合并入口（逐区 RPC 在调用方；iOS mergeGlobalTaskIndex 行段同构）。 */
    suspend fun mergeTaskIndexRows(items: List<JSONValue>): Pair<Int, Int> =
        table.mergeTaskIndexRows(items, titleOverriddenIds = titleOverrides.keys)

    /** 本地新建会话即刻入表（RemoteConversationStore.performCreateConversation 回写口；iOS :2548-2558）。 */
    suspend fun upsertLocalSession(summary: SessionSummary) = table.upsertLocalSession(summary)

    /**
     * 字段级回填（RemoteConversationStore.refreshDueSessionMeta 的表侧；只改既有行
     * 不新增行，标题让位本地 override）。返回标题发生变化的 sessionId 集。
     */
    suspend fun backfillSessionFields(items: List<JSONValue>): Set<String> =
        table.backfillSessionFields(items, titleOverriddenIds = titleOverrides.keys)

    /** 合并表直读（测试/诊断面）。 */
    suspend fun sessionSummaries(): Map<String, SessionSummary> = table.snapshot()

    private suspend fun yieldConversationsReplaced() {
        events.emit(ConversationEvent.ConversationsReplaced(currentConversations()))
    }

    companion object {
        const val EVENT_SESSIONS_INDEX_FRAME = "onDynamicSessionsIndexFrame"

        /**
         * 读面失败文案组装（iOS loadFailureText(verb:error:) :1195-1206 直译）：
         * RPCError 取 name+message 原文（不能经 toString 之外的加工丢真实拒因）；
         * 超时场景注明桌面端可能繁忙（繁忙桌面/大会话初始拉取易超时）。
         */
        fun loadFailureText(verb: String, error: Throwable): String {
            if (error is RPCError) {
                val base = "${verb}失败 · ${error.name}：${error.message}"
                if (error.name == "TimeoutError") {
                    return "$base · 桌面端可能繁忙，可稍后重试"
                }
                return base
            }
            return "${verb}失败 · $error"
        }
    }
}

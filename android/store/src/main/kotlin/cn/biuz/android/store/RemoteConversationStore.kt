package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Stores/Remote/RemoteConversationStore.swift（5646 行）的
// **订阅/快照/行投影面 + 发送面**（:860-1908 消息 + 帧路由 + 行模型映射、:2023-2034
// workspaceTarget、:2889-3016 交互读投影、:3286-3450 resync 族 + workflowRuns 表应用；
// 发送面 :2036-2592 sendCommand/sendText/乐观回显/watchdog/标题兜底回填/createSession 族、
// :2744-2782 markUnread/markRead）。会话列表读面（iOS :274-858，:store 已由
// RemoteSessionsIndexStore 承载）与 v4 命令扩容族（switchModelConfig/队列五件等 CAS
// 命令，iOS :3848-4238）不在本批——挂步骤 3。
//
// 并发模型适配（docs/Android开发交接.md §2.2，与 RemoteTaskStore.kt 同口径）：
// - Swift actor → Mutex 临界区；挂起点（RPC/屏障操作）一律在锁外，锁内只做纯内存读写；
// - iOS 帧 handler 内 `Task { }` 派发（无序）→ 单消费者 Channel 串行派发（帧序保序——
//   fromSeq 连续性判定（A10/A9 前置）依赖帧按到达序处理，并发 apply 会误判断档）；
// - UserDefaults diag.* → [diagListener] 回调（装配层 diag 模式接线；键名与 iOS 同名）。
//
// 连接面适配：iOS 持有 ZCodeServerConnection 强类型引用并直接调 setFrameHandler/
// ensureConversationFrameStream 等帧面四件；:store 不依赖 :connection（:connection → :store
// 单向），故帧面收敛为 [ConversationFrameSurface] 接口（方法名/签名与 :connection 已移植的
// ZCodeServerConnection 四件逐字一致——ZCodeServerConnection.kt:781/:966/:982/:991，装配轮
// 声明 implements 或包一层适配器即可接线，先例：RemoteTaskStore.RemoteConversationBridge）。
//
// 【30s keep-warm 会话租约池（用户裁决：镜像 web 缓存，别每次点击都重拉）】iOS
// closeConversation 立即真退订（RemoteConversationStore.swift:1068-1070）；上游 web 的
// sessionDataLayer 是引用计数 + 引用归零延迟退订【实证·上游仓 sessionDataLayer.ts:130-164，
// SESSION_DATA_LAYER_KEEP_WARM_MS=30_000 / E2E 1_000（:30-31）】——关页 ≠ 停订阅，30s 内
// 重开 pane 命中热缓存零重拉。Android 对位：closeConversation 起 30s 到期任务，到期才
// disposeConversation（携 subscriptionId 真退订）；租约期内 messages() 直接命中缓存
// （ensureConversationSubscribed 的已订阅守卫短路，rows 非空不再 rowsRange）。
//
// 红线对照（AGENTS §5 / 架构总纲 §7，逐条落点见各方法 KDoc）：
// - A7 订阅/读面按会话归属 workspacePath 寻址（applySessionTarget/workspaceTarget）；
// - A8+陷阱④ 帧 handler 与帧面流先于 subscribe 注册（ensureConversationSubscribed 序）；
// - A9 atLogEpoch 不符整批作废（loadHistory / handleConversationFrame epoch 换代整表替换）；
// - A10 快照/delta 键级整替不深合并（applyDelta / 快照 rows 合并语义）；
// - §5-7 回执取不到 subscriptionId = 自愈链失效，按错误处理（拒因上屏 + readSession 对账）；
// - §5-12 读面失败三态（loadFailures 与 last-good 共存）。

import cn.biuz.android.protocol.AckActivationBarrier
import cn.biuz.android.protocol.CasRevision
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.ConversationCommandFactory
import cn.biuz.android.protocol.ConversationCommandRequest
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.MessageStatus
import cn.biuz.android.protocol.RemoteInteractionOption
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.ThinkingContent
import cn.biuz.android.protocol.ThinkingState
import cn.biuz.android.protocol.TodoItem
import cn.biuz.android.protocol.TodoState
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.protocol.ToolCallStatus
import cn.biuz.android.protocol.ToolKind
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.protocol.WorkflowCompletionCard
import cn.biuz.android.protocol.WorkflowCompletionArtifact
import cn.biuz.android.protocol.WorkspaceHookReviewItem
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// MARK: - 帧面（:connection ZCodeServerConnection 帧四件的 :store 侧接口化）

/**
 * conversation 帧面契约（iOS ZCodeServerConnection.swift:959/:1120/:1133/:1140 四件的接口化；
 * 方法签名与 :connection 已移植的 ZCodeServerConnection 逐字一致）。:store 不依赖 :connection
 * ——真实实现由装配层注入；单例共享注册表语义（同一 workspace 恒一路 eventListen，防双路
 * 帧复制把 row.delta 逐字追加翻倍）由实现方保证。
 */
interface ConversationFrameSurface {
    /**
     * 确保指定 workspace 的 conversation 帧流已注册（幂等）。会话订阅前调用——订阅按
     * 归属 workspace 寻址（A7）而帧 emitter 按 workspaceKey 分发，帧面不同 workspace 时
     * 快照/增量静默全丢（2026-10-08 真机实据 sess_e8677b05）。identity 型工作区必须携
     * identity（emitter 键 = identity || path）。
     */
    suspend fun ensureConversationFrameStream(workspacePath: String, workspaceIdentity: String? = null)

    /** 注册 topic 逻辑帧处理器（topic：conversation/&lt;sessionId&gt;）。必须先于 subscribe 调用。 */
    fun setFrameHandler(topic: String, handler: (V4TopicFrame) -> Unit)

    /** 反注册 topic 处理器（会话真退订时）。 */
    fun removeFrameHandler(topic: String)

    /** 注册 assembler 丢帧回调（key：conversation；多路分键回退基键由实现方负责）。 */
    fun setFrameDropHandler(key: String, handler: () -> Unit)
}

// MARK: - 远端会话存储：conversation v4 订阅 + 行模型 → ChatMessage

/**
 * 订阅/快照/行投影面（ConversationStore 的连接态真实实现第一批：读面 messages 族 +
 * 面板投影 + keep-warm 租约池；写命令面挂步骤 2，接入前写面走协议默认 = 如实失败）。
 */
class RemoteConversationStore(
    private val transport: RPCChannelTransport,
    private val frameSurface: ConversationFrameSurface,
    /** 连接 workspace（归属未知会话的寻址回退 = 旧口径，iOS :26-27 注释口径） */
    private val workspace: StoreWorkspaceInfo,
    /**
     * sessions-index 订阅面（同模块已移植）：A7 寻址的「会话归属 workspacePath」查询源
     * （iOS 原文直读内部 sessions 表 :2024——Android 该表在 RemoteSessionsIndexStore，
     * 注入直读；null = 归属未知恒回退连接 workspace）。
     */
    private val sessionsIndex: RemoteSessionsIndexStore? = null,
    /**
     * 握手注册 clientId（A2 全生命周期单一来源）：信封 clientId 必须逐字等于连接握手
     * 注册值（否则 fault.command.clientMismatch）。装配层从连接实例注入
     * （connection.registeredClientId）；Store 与连接同生命周期重建（adoptCaches 口径），
     * 构造注入即单一来源。
     */
    private val clientId: String,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * keep-warm 租约窗（毫秒）。上游 sessionDataLayer.ts:30-31：生产 30_000 / E2E 1_000；
     * 测试注入小值驱动到期。
     */
    private val keepWarmMs: Long = DEFAULT_KEEP_WARM_MS,
    /**
     * 时钟源（仅标题兜底刷新窗等本地窗口计时用；信封 issuedAt 恒走系统墙钟——
     * 线缆事实不走注入面）。缺省系统墙钟；测试注入虚拟时钟驱动 15s 首试/10min 窗口。
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ConversationStore, RemoteConversationBridge {

    /** 会话行记录（iOS RowRecord :236-239）。 */
    class RowRecord(val rowId: Int, var json: JSONValue)

    /** 订阅水位（快照 logEpoch + toSeq；resync base，缺 logEpoch 时传 null 全量）（iOS :178-179）。 */
    data class Watermark(val logEpoch: String?, val seq: Int)

    /** state.workflowRuns 表（G-008 通路 B；键级整替）（iOS :162）。 */
    data class WorkflowRunTable(val revision: Long, val runs: List<JSONValue>)

    /** 乐观回显在途用户消息（iOS :188-192 元组 → data class）。生产面（send）挂步骤 2。 */
    class PendingSend(val text: String, val anchor: Int, val commandId: String)

    // Swift actor 串行化的 Kotlin 等价物；锁内禁挂起（RPC/屏障/Flow emit 均在锁外或非挂起）。
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    // DROP_OLDEST：缺省 SUSPEND 会让慢收集器把 emit 反压进锁内（锁内禁挂起红线）；
    // UI 事件允许丢弃（订阅方以 messages() 全量重读兜底），iOS continuation.yield 满载丢弃同口径
    private val events = MutableSharedFlow<ConversationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // MARK: 状态容器（iOS :41-233 订阅面子集；rowId 键控行模型 + 派生消息）

    private val rows = HashMap<String, HashMap<Int, RowRecord>>()
    private val messages = HashMap<String, List<ChatMessage>>()
    /** state.updated patch 合并目标（合并视图 = 嵌套 state ∪ 快照顶层扁平键，iOS :1298-1309） */
    private val snapshotState = HashMap<String, JSONValue>()
    /** 会话 state revision（快照/state.updated 各自携带；CAS 类信封必须，步骤 2 消费） */
    private val conversationStateRevisions = HashMap<String, Long>()
    /** 订阅水位（resync base） */
    private val conversationWatermarks = HashMap<String, Watermark>()
    /** 会话加载失败文本（键级覆盖：任一环节成功即清除；null = 正常空会话）（iOS :53-57） */
    private val loadFailures = HashMap<String, String?>()
    private val conversationSubscriptions = HashMap<String, EventSubscription>()
    /** v4 订阅回执 subscriptionId（resync/丢帧自愈/退订的凭据；屏障激活判据） */
    private val conversationSubscriptionIds = HashMap<String, String>()
    private val pendingInteractions = HashMap<String, JSONValue>()
    /** 会话级模型选择（state.modelSelection 键级替换；桌面 composer 变更随 delta 到达） */
    private val sessionModelSelections = HashMap<String, JSONValue>()
    /** 会话协作模式投影（state.config.mode 键级替换；上游 product-projection.ts:3785） */
    private val sessionCollaborationModes = HashMap<String, String>()
    /** workflowRuns 状态表（state.workflowRuns 整键替换 + 专属增量 op）（iOS :162） */
    private val workflowRunTables = HashMap<String, WorkflowRunTable>()
    /** 快照/增量 workflowRun 单数键（旧实现兼容；缺席保持既有缓存）（iOS :62-63） */
    private val workflowRunStates = HashMap<String, JSONValue>()
    /** reasoning 行流式计时（消息ID → 流式开始时刻；streaming→done 换算 duration）（iOS :165-166） */
    private val reasoningStartedAt = HashMap<String, Long>()
    private val reasoningDuration = HashMap<String, Double>()
    /** 乐观回显在途用户消息（生产面挂步骤 2；消费面随 rebuildMessages 本批落位） */
    private val pendingLocalSends = HashMap<String, LinkedHashMap<String, PendingSend>>()
    private val lastYieldedMessages = HashMap<String, List<ChatMessage>>()
    /** forceFullResync（base=null）已发出、下一帧快照按全量语义整表替换的会话集（iOS :75） */
    private val fullResyncSnapshotsPending = HashSet<String>()
    /** 重连 pending 兜底去重（每会话每次连接期最多一次全量补拉）（iOS :3288） */
    private val pendingRecoveryRequested = HashSet<String>()
    /** workspace hook 审核补拉去重（同口径）（iOS :3291） */
    private val hookReviewRecoveryRequested = HashSet<String>()
    /** 多 workspace 清单（identity 反查；AppSession 装配后回写，iOS setAllWorkspaces :441-443） */
    @Volatile
    var allWorkspaces: List<StoreWorkspaceInfo> = emptyList()

    /** 订阅回执竞态屏障（官方 ackActivationBarrier 镜像；M1 已有组件接线）（iOS :72）。 */
    private val frameBarrier = AckActivationBarrier()

    /** 诊断钩子（iOS UserDefaults diag.* 承载；装配层 diag 模式接线，键名与 iOS 同名）。 */
    @Volatile
    var diagListener: ((key: String, value: String) -> Unit)? = null

    /**
     * workspace hook 审核补拉回调（iOS 快照 workspaceHookAdmission 检出后调
     * requestWorkspaceHookReview——写命令挂步骤 2，本批以回调承载；null = 不补拉）。
     */
    @Volatile
    var onHookReviewRecoveryNeeded: (suspend (conversationId: String, admission: JSONValue) -> Unit)? = null

    /** keep-warm 租约（conversationId → 到期退订任务；ConcurrentHashMap 免锁读写） */
    private val keepWarmJobs = ConcurrentHashMap<String, Job>()

    // MARK: 发送面状态（iOS :125-128/:175-176/:203-204/:2128-2132/:2470/:2575-2581/:219/:222）

    /** 最近一次 sendText 桌面拒因（sendRejectionText 协议读面；成功发送即清除）（iOS :2128）。 */
    private val sendRejections = HashMap<String, String>()

    /** held 态待确认标记（拒因 heldQueueDispositionRequired/guard.heldQueueConfirmationStale 置位）（iOS :2130-2132）。 */
    private val heldQueuePending = HashMap<String, Boolean>()

    /** 发送后元数据兜底刷新窗（标题占位回填；epoch ms）（iOS :203-204）。 */
    private val metaRefreshNextTry = HashMap<String, Long>()
    private val metaRefreshDeadline = HashMap<String, Long>()

    /** 在途创建去重账本（A12 防双写；键见 createConversation）（iOS :2470）。 */
    private val inFlightCreates = HashMap<String, CompletableDeferred<Conversation>>()

    /** 最近一次 createConversation 失败原文（空串 = 无）（iOS :2575-2576）。 */
    @Volatile
    private var lastCreateFailure = ""

    /** 最近一次 sendCommand 传输层失败原文（RPCError.message/本地拒发描述；成功即清）（iOS :2580-2581）。 */
    @Volatile
    private var lastCommandError = ""

    /** 任务元数据写（标记未读）最近一次失败原文（空串 = 无；§5-12 写面禁静默）（iOS :221-222）。 */
    @Volatile
    private var lastTaskWriteFailure = ""

    /** 帧路由单消费者队列（handler 非 suspend lambda → 经此串行化，帧序保序）。 */
    private data class FrameRoute(val conversationId: String, val frame: V4TopicFrame)

    private val frameRoutes = Channel<FrameRoute>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (route in frameRoutes) {
                routeConversationFrame(route.conversationId, route.frame)
            }
        }
    }

    override val isReadOnly: Boolean
        get() = true

    private fun diag(key: String, value: String) {
        diagListener?.invoke(key, value)
    }

    // MARK: A7 寻址（iOS applySessionTarget :28-37 / workspaceTarget :2023-2034 直译）

    /**
     * 会话域 RPC 参数的 workspace 信封（对齐 Web 客户端 wire 形状：扁平 workspacePath +
     * workspaceIdentity?；嵌套 workspace:{...} 会被参数规范化丢弃）。**按会话归属 workspace
     * 寻址**【实证·上游仓 zcodeAgentService.subscribeConversationV4：getReadOnlyClient(params)
     * 以 params.workspacePath 选 CLI 进程承接订阅】——恒用连接 workspace 时运行中 turn 的
     * 行增量永不抵达（真机报障「手机端没有回复」根因）。归属未知回退连接 workspace = 旧口径。
     */
    private suspend fun applySessionTarget(fields: LinkedHashMap<String, JSONValue>, sessionId: String?) {
        if (sessionId != null) {
            fields["sessionId"] = JSONValue.Str(sessionId)
        }
        val target = workspaceTarget(sessionId, null)
        fields["workspacePath"] = JSONValue.Str(target.first)
        val identity = target.second
        if (!identity.isNullOrEmpty()) {
            fields["workspaceIdentity"] = JSONValue.Str(identity)
        }
    }

    /**
     * 会话归属 workspace 解析：目标会话自带 workspacePath（sessions-index 行）优先；
     * override 次之（本批无调用方，保留 iOS 参数位）；归属未知回退连接 workspace。
     * identity：归属=连接 workspace 用连接身份，否则查 allWorkspaces 清单；解析不出不携键。
     */
    suspend fun workspaceTarget(sessionId: String?, overridePath: String?): Pair<String, String?> {
        val rowPath = sessionId
            ?.let { sessionsIndex?.sessionSummaries()?.get(it)?.workspacePath }
            ?.takeIf { it.isNotEmpty() }
        val path = overridePath ?: rowPath ?: workspace.path
        val identity: String? = if (path == workspace.path) {
            workspace.workspaceIdentity
        } else {
            allWorkspaces.firstOrNull { it.path == path }?.workspaceIdentity
        }
        return path to identity
    }

    /** 会话归属 workspace 只读解析（跨 Store 复用面；iOS conversationWorkspaceTarget :1992-1994）。 */
    suspend fun conversationWorkspaceTarget(sessionId: String): Pair<String, String?> =
        workspaceTarget(sessionId, null)

    /** 会话当前 revision/logEpoch 水位（步骤 2 CAS base 数据源；iOS :1998-2001）。 */
    suspend fun conversationBaseWatermark(sessionId: String): Pair<Long, String> {
        val (revision, epoch) = mutex.withLock {
            (conversationStateRevisions[sessionId] ?: 0L) to (conversationWatermarks[sessionId]?.logEpoch)
        }
        return revision to (epoch ?: "0")
    }

    // MARK: 消息（iOS :862-878 直译）

    /**
     * 当前消息投影（纯内存读，无订阅副作用——RemoteSessionsIndexStore.currentConversations
     * 同口径；诊断/断言面）。
     */
    suspend fun currentMessages(conversationId: String): List<ChatMessage> =
        mutex.withLock { messages[conversationId] ?: emptyList() }

    override suspend fun messages(conversationId: String): List<ChatMessage> {
        // keep-warm 租约期内重入 = 热获取：撤销到期退订，订阅与行缓存原样续用
        //（上游 sessionDataLayer.acquire 的 keepWarmTimer 清除同构）
        keepWarmJobs.remove(conversationId)?.cancel()
        ensureConversationSubscribed(conversationId)
        // 拉历史分页（rowsRange 向前，limit ≤200）
        if (mutex.withLock { rows[conversationId].isNullOrEmpty() }) {
            // P0：订阅快照通常自带尾部行窗口（web 同构——首屏=快照，rowsRange 只管向旧翻页）。
            // 快照帧与 ack 有竞态，短暂等快照落表再决定回退拉取，避免「快照 + rowsRange(200)」双份行载荷
            var waits = 0
            while (waits < 6 && mutex.withLock { rows[conversationId].isNullOrEmpty() }) {
                delay(SNAPSHOT_WAIT_MS)
                waits += 1
            }
            if (mutex.withLock { rows[conversationId].isNullOrEmpty() }) {
                loadHistory(conversationId = conversationId, beforeRowId = null)
            }
        }
        return mutex.withLock { messages[conversationId] ?: emptyList() }
    }

    /**
     * 向上分页（iOS loadOlder :884-900 直译）：取 rows 最小 rowId 为游标拉更早一页，
     * rowId 字典天然拼接去重；返回是否还有更早数据。中继瞬断会让单页拉取静默失败
     * （表未增长且报无更多）：1.2s 退避重试一次。
     */
    override suspend fun loadOlder(conversationId: String): Boolean {
        val oldest = mutex.withLock { rows[conversationId]?.keys?.minOrNull() } ?: return false
        val beforeCount = mutex.withLock { rows[conversationId]?.size ?: 0 }
        var hasMore = loadHistory(
            conversationId = conversationId,
            beforeRowId = oldest,
            timeout = PAGE_FETCH_TIMEOUT,
        )
        if (!hasMore && mutex.withLock { rows[conversationId]?.size ?: 0 } == beforeCount) {
            delay(PAGE_RETRY_BACKOFF_MS)
            hasMore = loadHistory(
                conversationId = conversationId,
                beforeRowId = oldest,
                timeout = PAGE_FETCH_TIMEOUT,
            )
        }
        mutex.withLock { rebuildMessagesLocked(conversationId) }
        yieldReplacedSyncingBaseline(conversationId)
        return hasMore
    }

    // MARK: 订阅（iOS ensureConversationSubscribed :902-1014 直译）

    /**
     * 订阅序（陷阱④+工程纪律 3+A8，逐条不可换位）：
     * ① barrier.begin（订阅发起前标记 in-flight）；
     * ② 帧 handler 注册（本地路由表，先注册无副作用；订阅失败残留无害——幂等落表）；
     * ③ 丢帧自愈 handler；
     * ④ ensureConversationFrameStream（帧面与订阅同 workspace，幂等多路挂载）；
     * ⑤ subscribeConversationV4（topic + sessionId + workspace 信封）。
     */
    private suspend fun ensureConversationSubscribed(conversationId: String) {
        if (mutex.withLock { conversationSubscriptions[conversationId] != null }) return
        val topic = "conversation/$conversationId"
        try {
            frameBarrier.begin(conversationId)
            frameSurface.setFrameHandler(topic) { frame ->
                frameRoutes.trySend(FrameRoute(conversationId, frame))
            }
            // 丢帧自愈：conversation assembler 是单键（多会话共用），dropped 时对全部
            // 已保存 subscriptionId 的订阅逐个 resync（幂等；无水位传 null 走全量快照）
            frameSurface.setFrameDropHandler(key = "conversation") {
                scope.launch { resyncAllConversations() }
            }
            // 帧面与订阅同 workspace【P0-1，2026-10-08 真机实据 sess_e8677b05】：上游
            // ownsFrame 按 workspaceKey 硬匹配，帧面挂错区则快照/revision/增量全丢。
            val target = workspaceTarget(conversationId, null)
            frameSurface.ensureConversationFrameStream(
                workspacePath = target.first,
                workspaceIdentity = target.second,
            )
            val fields = LinkedHashMap<String, JSONValue>()
            fields["topic"] = JSONValue.Str(topic)
            applySessionTarget(fields, conversationId)
            diag("diag.wire.sub.${conversationId.take(14)}", JSONValue.Obj(fields).toString())
            val reply = transport.call(
                "zcode-agent", "subscribeConversationV4", RPCValue.json(JSONValue.Obj(fields)),
            )
            // 回执形态：{ack:{subscriptionId,mode,logEpoch}}（中继桥实测）；顶层兼容局域网直连。
            // 取不到 = 自愈链路失效（AGENTS §5-7：resync/丢帧自愈全依赖 subId），视为订阅失败
            // 走兜底链——静默落空仍走成功路径会让 CAS 全族被本地拒发门永久拒发（iOS P1② 同案）
            val json = reply.jsonValue
            val ack = json?.get("ack")?.objectValue
            val subscriptionId = ack?.get("subscriptionId")?.stringValue
                ?: json?.get("subscriptionId")?.stringValue
            if (subscriptionId == null) {
                frameBarrier.abort(conversationId)
                val detail = "订阅会话失败 · 回执缺 subscriptionId（丢帧自愈链不可用）"
                mutex.withLock { loadFailures[conversationId] = detail }
                diag(
                    "diag.conv.${conversationId.take(14)}",
                    "sub ERR $detail reply=${json?.toString()?.take(200)}",
                )
                reconcileViaReadSession(conversationId)
                return
            }
            mutex.withLock { conversationSubscriptionIds[conversationId] = subscriptionId }
            // 屏障放行：subId 已落库，暂存帧按原序回放；overflow=暂存超限（增量连续性
            // 不可保证）→ 全量 resync 重建（上游 initialFrameStagingOverflow 同语义）
            when (val outcome = frameBarrier.finish(conversationId)) {
                is AckActivationBarrier.FinishOutcome.Frames ->
                    for (frame in outcome.frames) {
                        mutex.withLock { handleConversationFrameLocked(conversationId, frame) }
                    }
                AckActivationBarrier.FinishOutcome.Overflow ->
                    forceFullResync(conversationId, subscriptionId)
                AckActivationBarrier.FinishOutcome.Empty -> Unit
            }
            mutex.withLock {
                conversationSubscriptions[conversationId] = EventSubscription {
                    scope.launch { disposeConversation(conversationId) }
                }
                loadFailures[conversationId] = null
            }
            diag(
                "diag.conv.${conversationId.take(14)}",
                "sub ok rows=${mutex.withLock { rows[conversationId]?.size ?: -1 }}",
            )
        } catch (e: CancellationException) {
            // 订阅未成立：暂存帧整体丢弃（未取得订阅的帧不可信）
            frameBarrier.abort(conversationId)
            throw e
        } catch (e: Exception) {
            frameBarrier.abort(conversationId)
            val detail = if (e is RPCError) {
                "name=${e.name} msg=${e.message} detail=${e.detail?.toString() ?: "nil"}"
            } else {
                e.toString()
            }
            diag("diag.conv.${conversationId.take(14)}", "sub ERR $detail")
            // 失败透出（read 面协议 conversationLoadFailure）：UI 错误态+重试的数据源
            mutex.withLock { loadFailures[conversationId] = loadFailureText("订阅会话", e) }
            // 订阅失败兜底第二步：readSession（只读恢复）对账展示态，修正挂起交互角标；
            // 消息仍以可用流/分页为准。
            reconcileViaReadSession(conversationId)
        }
    }

    /**
     * readSession 对账（iOS :1017-1043 直译；runtimePolicy=existing-only：只读恢复，
     * 不拉起 Agent）。回执宽容：pendingInteractionSummary 顶层或 session 包裹内。
     */
    private suspend fun reconcileViaReadSession(conversationId: String) {
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, conversationId)
        fields["runtimePolicy"] = JSONValue.Str("existing-only")
        val result = try {
            transport.call(
                "zcode-agent", "readSession", RPCValue.json(JSONValue.Obj(fields)),
                timeout = HISTORY_FETCH_TIMEOUT,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // 对账失败：维持现有展示态
        }
        val dict = result.jsonValue?.objectValue ?: return
        // 回执宽容：pendingInteractionSummary 可能在顶层或 session 包裹内（iOS 原序回退）
        val summary = dict["pendingInteractionSummary"]
            ?: dict["session"]?.objectValue?.get("pendingInteractionSummary")
            ?: return
        mutex.withLock {
            pendingInteractions[conversationId] = JSONValue.Arr(summary.arrayValue ?: emptyList())
        }
        // iOS 原文还会把 summary 计数回写 sessions 表并 yieldConversationsReplaced——
        // 该表在 :store 由 RemoteSessionsIndexStore 承载（summary 计数由其帧面自行投影），
        // 此处不再双写（读面投影归属单一，防两处漂移）。
    }

    /** 丢帧自愈全量对账（iOS resyncAllConversations :1045-1061 直译）。 */
    suspend fun resyncAllConversations() {
        val entries = mutex.withLock { conversationSubscriptionIds.toList() }
        for ((conversationId, subscriptionId) in entries) {
            val fields = LinkedHashMap<String, JSONValue>()
            fields["subscriptionId"] = JSONValue.Str(subscriptionId)
            val watermark = mutex.withLock { conversationWatermarks[conversationId] }
            val epoch = watermark?.logEpoch
            fields["base"] = if (epoch != null) {
                JSONValue.Obj(
                    mapOf(
                        "logEpoch" to JSONValue.Str(epoch),
                        "seq" to JSONValue.Int(watermark.seq),
                    ),
                )
            } else {
                JSONValue.Null
            }
            // workspace 信封随订阅同源（ownership 匹配，forceFullResync 同注）
            applySessionTarget(fields, conversationId)
            try {
                transport.call("zcode-agent", "resyncConversationV4", RPCValue.json(JSONValue.Obj(fields)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // iOS `_ = try?` 同口径
            }
        }
    }

    /**
     * 会话关闭（ConversationStore 协议面）。iOS 立即真退订（:1068-1070）；Android 按
     * keep-warm 租约池延迟退订（用户裁决镜像 web sessionDataLayer：引用归零后 30s 内
     * 重开命中热缓存零重拉）。租约期内重入（messages）撤销到期任务；到期真退订由
     * [disposeConversation] 承担（携 subscriptionId 精确退订 + 水位/revision/投影回收）。
     */
    override suspend fun closeConversation(conversationId: String) {
        keepWarmJobs[conversationId]?.cancel()
        keepWarmJobs[conversationId] = scope.launch {
            delay(keepWarmMs)
            keepWarmJobs.remove(conversationId)
            disposeConversation(conversationId)
        }
    }

    /** Store 级释放（连接拆卸时装配层调用）：跳过租约立即全部真退订。 */
    suspend fun dispose() {
        val ids = mutex.withLock { conversationSubscriptions.keys.toList() }
        for (id in ids) {
            keepWarmJobs.remove(id)?.cancel()
            disposeConversation(id)
        }
        frameRoutes.close()
    }

    /**
     * 真退订（iOS disposeConversation :1072-1096 直译）：订阅/帧 handler/水位/revision/
     * 快照投影一并回收 + 携 subscriptionId 精确退订（上游 unsubscribeV4Route 按
     * subscriptionId 精确匹配路由，缺省 = 空操作、桌面侧订阅单调增长）。
     */
    private suspend fun disposeConversation(conversationId: String) {
        val (handle, subscriptionId) = mutex.withLock {
            val h = conversationSubscriptions.remove(conversationId) ?: return
            val s = conversationSubscriptionIds.remove(conversationId)
            h to s
        }
        handle.cancel()
        frameBarrier.abort(conversationId)
        frameSurface.removeFrameHandler(topic = "conversation/$conversationId")
        mutex.withLock {
            conversationWatermarks.remove(conversationId)
            // 水位与 revision 同清（iOS §11.3：只清水位不清 revision 会让重进会话的
            // ensureStateRevision 被残留旧值立即满足，而 logEpoch 已清，CAS 命令被误拦）
            conversationStateRevisions.remove(conversationId)
            snapshotState.remove(conversationId)
            pendingInteractions.remove(conversationId)
        }
        val fields = LinkedHashMap<String, JSONValue>()
        fields["topic"] = JSONValue.Str("conversation/$conversationId")
        subscriptionId?.let { fields["subscriptionId"] = JSONValue.Str(it) }
        applySessionTarget(fields, conversationId)
        try {
            transport.call("zcode-agent", "unsubscribeConversationV4", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径
        }
    }

    /**
     * 历史拉取/readSession 对账的 RPC 超时预算（iOS :1098-1102）：繁忙桌面单次调用可能
     * 超 30s（大型 workflow 执行实证），放大到 60s（仍有限，不为绕超时无限挂起 UI）。
     */
    private val HISTORY_FETCH_TIMEOUT = 60.seconds

    /** 向上翻页单页预算（iOS :1104-1107）：翻页是滚顶触发的轻交互，快速失败交给「再点一次」。 */
    private val PAGE_FETCH_TIMEOUT = 20.seconds

    /**
     * 拉一页历史行；返回是否还有更早数据（回执 hasMore 缺席时以「非空页」近似）。
     * A9 读面校验：在途回执 atLogEpoch 与订阅水位 epoch 不符（CLI 重启/换代窗口）→
     * 整批作废不并入——rowId 只在一个 epoch 内有意义，旧纪元行复活进新表 = 跨纪元混行。
     */
    private suspend fun loadHistory(
        conversationId: String,
        beforeRowId: Int?,
        retryOnTimeout: Boolean = true,
        timeout: kotlin.time.Duration = HISTORY_FETCH_TIMEOUT,
    ): Boolean {
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, conversationId)
        fields["limit"] = JSONValue.Int(200)
        if (beforeRowId != null) {
            fields["beforeRowId"] = JSONValue.Int(beforeRowId)
        }
        val result = try {
            transport.call(
                "zcode-agent", "conversationRowsRangeV4", RPCValue.json(JSONValue.Obj(fields)),
                timeout = timeout,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 超时首败：1.2s 退避自动重试一次再落错误态（仅初始拉取重试——loadOlder 已有
            // 「表未增长」重试，叠加会三连发）。重试也超时才记录失败
            if (retryOnTimeout && beforeRowId == null && isTimeoutError(e)) {
                diag("diag.rowsrange.${conversationId.take(14)}", "rowsRange TIMEOUT 首败，1.2s 后重试一次")
                delay(PAGE_RETRY_BACKOFF_MS)
                return loadHistory(
                    conversationId = conversationId,
                    beforeRowId = null,
                    retryOnTimeout = false,
                )
            }
            val detail = if (e is RPCError) {
                "name=${e.name} msg=${e.message} detail=${e.detail?.toString() ?: "nil"}"
            } else {
                e.toString()
            }
            diag("diag.rowsrange.${conversationId.take(14)}", "rowsRange ERR $detail")
            mutex.withLock { loadFailures[conversationId] = loadFailureText("拉取历史", e) }
            return false
        }
        val dict = result.jsonValue?.objectValue ?: return false
        val currentEpoch = mutex.withLock { conversationWatermarks[conversationId]?.logEpoch }
        val atEpoch = dict["atLogEpoch"]?.stringValue
        if (!atEpoch.isNullOrEmpty() && !currentEpoch.isNullOrEmpty() && atEpoch != currentEpoch) {
            diag(
                "diag.rowsrange.${conversationId.take(14)}",
                "rowsRange epoch 失配作废（at=$atEpoch current=$currentEpoch）",
            )
            return false
        }
        val pageRows = ArrayList<RowRecord>()
        for (row in dict["rows"]?.arrayValue ?: emptyList()) {
            val rowId = row.objectValue?.get("rowId")?.intValue ?: continue
            pageRows.add(RowRecord(rowId = rowId, json = row))
        }
        var tableSize = 0
        mutex.withLock {
            val table = rows[conversationId] ?: HashMap()
            for (record in pageRows) {
                table[record.rowId] = record
            }
            rows[conversationId] = table
            tableSize = table.size
            rebuildMessagesLocked(conversationId)
            loadFailures[conversationId] = null
        }
        // 诊断：向上分页取证（游标/页行数/页行号范围/表总量/hasMore）
        val pageIds = pageRows.map { it.rowId }.sorted()
        val rangeText = if (pageIds.isEmpty()) "" else " range=[${pageIds.first()}..${pageIds.last()}]"
        diag(
            "diag.rowsrange.ok",
            "cursor=${beforeRowId?.toString() ?: "nil"} page=${pageRows.size}$rangeText" +
                " table=$tableSize" +
                " hasMoreField=${dict["hasMore"]?.boolValue?.toString() ?: "absent"}" +
                " respKeys=${dict.keys.sorted().take(8)}",
        )
        // hasMore 显式字段优先；缺席时非空页视为可能还有更早数据（loadOlder 再探一页）
        return dict["hasMore"]?.boolValue ?: pageRows.isNotEmpty()
    }

    /**
     * 加载失败 → 用户可读文本（iOS :1195-1206；复用 RemoteSessionsIndexStore 同款组装——
     * RPCError 取 name+message 原文，超时注明桌面端可能繁忙）。
     */
    private fun loadFailureText(verb: String, error: Throwable): String =
        RemoteSessionsIndexStore.loadFailureText(verb, error)

    /** 超时错误判定（ChannelClient/Relay 传输超时统一 name="TimeoutError"）（iOS :1110-1112）。 */
    private fun isTimeoutError(error: Throwable): Boolean =
        (error as? RPCError)?.name == "TimeoutError"

    /** 会话最近一次加载失败文本（null = 无失败——含「订阅/拉取都成功但行为空」的正常空会话）。 */
    override suspend fun conversationLoadFailure(conversationId: String): String? =
        mutex.withLock { loadFailures[conversationId] }

    // MARK: 帧路由（iOS routeConversationFrame :1223-1230 / handleConversationFrame :1232-1382）

    /** 屏障口：subId 未落库且屏障在飞 → 暂存；否则直通处理。 */
    private suspend fun routeConversationFrame(conversationId: String, frame: V4TopicFrame) {
        val inFlight = mutex.withLock { conversationSubscriptionIds[conversationId] == null } &&
            frameBarrier.isInFlight(conversationId)
        if (inFlight) {
            frameBarrier.stage(conversationId, frame)
            return
        }
        mutex.withLock { handleConversationFrameLocked(conversationId, frame) }
    }

    /** 快照顶层扁平 state 键集（上游 conversationSnapshotSchema 扁平化；iOS :1298-1303 同集）。 */
    private val snapshotStateKeys = setOf(
        "control", "availability", "inputRouting", "meta", "sharedContextImport",
        "config", "modelTransition", "usage", "queue", "pendingInteractions",
        "pendingCommands", "backgroundWorks", "subagents", "workflowRuns",
        "goal", "plan", "workspaceHookAdmission",
    )

    /** 须在 [mutex] 临界区内调用（iOS handleConversationFrame 主体直译）。 */
    private fun handleConversationFrameLocked(conversationId: String, frame: V4TopicFrame) {
        // 帧过滤（官方 conversationProjectionStore.ts:606-621 同构）：
        // ① subscriptionId 过滤——旧订阅迟到帧直接丢；帧缺 subId（旧桌面）不过滤。
        // ② fromSeq 连续性——已知水位且 delta 帧断档时不猜、不写投影、不推进水位，
        //    转全量 resync 恢复；迟到帧（toSeq ≤ 水位）静默丢。
        val knownSubId = conversationSubscriptionIds[conversationId]
        if (knownSubId != null && frame.subscriptionId.isNotEmpty() && frame.subscriptionId != knownSubId) {
            return
        }
        val watermark = conversationWatermarks[conversationId]
        if (watermark != null && frame.snapshot == null) {
            if (frame.toSeq <= watermark.seq) return
            if (frame.fromSeq > watermark.seq + 1) {
                conversationSubscriptionIds[conversationId]?.let { subId ->
                    diag(
                        "diag.watchdog.${conversationId.take(14)}",
                        "帧断档（last=${watermark.seq} from=${frame.fromSeq}）→ 全量 resync",
                    )
                    scope.launch { forceFullResync(conversationId, subId) }
                }
                return
            }
        }
        // 水位记录（resync base）：logEpoch 仅快照携带，缺席沿用旧值
        val previousEpoch = watermark?.logEpoch
        conversationWatermarks[conversationId] = Watermark(
            logEpoch = frame.snapshot?.objectValue?.get("logEpoch")?.stringValue ?: previousEpoch,
            seq = frame.toSeq,
        )
        val snapshot = frame.snapshot
        if (snapshot != null) {
            val dict = snapshot.objectValue
            if (dict != null) {
                // 快照顶层 revision（会话快照可能无 state 键但带顶层 revision——CAS 用）
                dict["revision"]?.longValue()?.let { conversationStateRevisions[conversationId] = it }
                // 全量 resync 语义标记回收：forceFullResync（base=null）后的第一帧快照按
                // 权威全集整表替换（桌面已移除的行不残留）；常规订阅快照仍按 rowId 键级
                // 合并（尾部窗口覆盖，旧行保留——web 冷/热判定同构）。
                // A9 补齐：logEpoch 换代（CLI 重启/重建/fork 会重新编号 rowId）时同样整表
                // 替换——rowId 只在一个 epoch 内有意义，跨纪元键级合并会把两个纪元的行拼
                // 进同表（官方 conversationProjectionStore.ts:660-671「snapshot → 整体替换」）
                val snapshotEpoch = dict["logEpoch"]?.stringValue
                val epochChanged = previousEpoch != null && snapshotEpoch != null &&
                    snapshotEpoch != previousEpoch
                val replaceWholeTable =
                    fullResyncSnapshotsPending.remove(conversationId) || epochChanged
                val rowsArray = dict["rows"]?.arrayValue
                if (rowsArray != null) {
                    val table: HashMap<Int, RowRecord> =
                        if (replaceWholeTable) HashMap() else (rows[conversationId] ?: HashMap())
                    for (row in rowsArray) {
                        val rowId = row.objectValue?.get("rowId")?.intValue ?: continue
                        table[rowId] = RowRecord(rowId = rowId, json = row)
                    }
                    rows[conversationId] = table
                } else if (replaceWholeTable) {
                    rows[conversationId] = HashMap()
                }
                // 快照 state 双形态（iOS :1291-1319）：上游 main 扁平化（queue/
                // pendingInteractions/workflowRuns/goal/plan 全在快照顶层），旧桌面为嵌套
                // state 键。合并视图 = 嵌套 state ∪ 顶层扁平键——两种桌面形态下投影都成立。
                val merged = HashMap<String, JSONValue>(dict["state"]?.objectValue ?: emptyMap())
                var hasState = merged.isNotEmpty()
                for ((key, value) in dict) {
                    if (key in snapshotStateKeys) {
                        merged[key] = value
                        hasState = true
                    }
                }
                // 会话级模型选择的扁平位（上游 session-config.ts:7 为 config.modelSelection）：
                // 兼容投影到既有 state.modelSelection 读法
                val existingSelection = merged["modelSelection"]
                if (existingSelection == null || existingSelection.isNull) {
                    val config = dict["config"]?.objectValue
                        ?: dict["state"]?.objectValue?.get("config")?.objectValue
                    val selection = config?.get("modelSelection")
                    if (selection != null && !selection.isNull) {
                        merged["modelSelection"] = selection
                        hasState = true
                    }
                }
                if (hasState) {
                    val state = JSONValue.Obj(merged)
                    snapshotState[conversationId] = state
                    merged["revision"]?.longValue()?.let {
                        conversationStateRevisions[conversationId] = it
                    }
                    refreshPendingInteractionsLocked(conversationId, state)
                    // 重连兜底（iOS :1338-1348）：store 重连重建后 pendingInteractions 缓存
                    // 为空；若快照带 state 但该键缺席（挂起交互在断线期间无变化→增量恢复
                    // 不补发），全量 resync 一次补齐
                    if (merged["pendingInteractions"] == null &&
                        pendingInteractions[conversationId] == null &&
                        conversationId !in pendingRecoveryRequested
                    ) {
                        pendingRecoveryRequested.add(conversationId)
                        scope.launch { forceFullResyncForPending(conversationId) }
                    }
                    // workspace hook 审核补拉（web 对齐）：state 声明有 hook 准入而挂起
                    // 交互缺席时主动补拉（web pending 补拉同路径）；写命令挂步骤 2，
                    // 本批经 [onHookReviewRecoveryNeeded] 回调承载（null = 不补拉）。
                    val admission = merged["workspaceHookAdmission"]?.objectValue
                    if (admission != null &&
                        !admission["bundleDigest"]?.stringValue.isNullOrEmpty() &&
                        currentPendingInteractionLocked(conversationId, listOf("workspaceHookReview")) == null &&
                        conversationId !in hookReviewRecoveryRequested
                    ) {
                        hookReviewRecoveryRequested.add(conversationId)
                        val callback = onHookReviewRecoveryNeeded
                        if (callback != null) {
                            val admissionValue = JSONValue.Obj(admission)
                            scope.launch { callback(conversationId, admissionValue) }
                        }
                    }
                    // G-008：冷快照必带 workflowRuns（snapshot.ts:497-499——漏这一处，
                    // 刷新/重连后正在跑的 run 会静默消失）；键级整体替换
                    merged["workflowRuns"]?.let { applyWorkflowRunsStateLocked(conversationId, it) }
                    // 面板态（goal/plan/backgroundWorks/subagents）随快照到达
                    yieldPanelStateLocked(conversationId)
                }
                // 要求 5：快照内 workflowRun 单数键（旧实现兼容；缺席保持既有缓存）
                extractWorkflowRun(dict)?.let { workflowRunStates[conversationId] = it }
            }
        }
        for (delta in frame.deltas) {
            applyDeltaLocked(conversationId, delta)
        }
        rebuildMessagesLocked(conversationId)
    }

    /** delta 应用（iOS applyDelta :1384-1452 直译；A10：键级整替，绝无字段级深合并）。 */
    private fun applyDeltaLocked(conversationId: String, delta: JSONValue) {
        val dict = delta.objectValue ?: return
        when (dict["op"]?.stringValue) {
            "row.appended", "row.upserted" -> {
                val row = dict["row"] ?: return
                val rowId = row.objectValue?.get("rowId")?.intValue ?: return
                rows.getOrPut(conversationId) { HashMap() }[rowId] = RowRecord(rowId, row)
            }
            "row.removed" -> {
                val fromRowId = dict["fromRowId"]?.intValue ?: return
                val table = rows[conversationId] ?: return
                table.keys.filter { it >= fromRowId }.forEach { table.remove(it) }
            }
            "row.delta" -> {
                // 流式逐字追加：仅允许作用于流式态行（服务端保证）；
                // path ∈ text|inputText|output.text|summaryText
                val rowId = dict["rowId"]?.intValue ?: return
                val append = dict["append"]?.stringValue ?: return
                val record = rows[conversationId]?.get(rowId) ?: return
                // Swift `case .object(var rowDict)` 值拷贝语义 → toMutableMap 拷贝后回写
                val rowDict = record.json.objectValue?.toMutableMap() ?: return
                val path = dict["path"]?.stringValue ?: "text"
                appendText(rowDict, path, append)
                record.json = JSONValue.Obj(rowDict)
            }
            "state.updated" -> {
                val patch = dict["patch"]?.objectValue ?: return
                val current = snapshotState[conversationId]?.objectValue ?: emptyMap()
                val mergedState = HashMap(current)
                for ((key, value) in patch) {
                    mergedState[key] = value // 键级整体替换，不深合并（A10）
                }
                val merged = JSONValue.Obj(mergedState)
                snapshotState[conversationId] = merged
                patch["revision"]?.longValue()?.let {
                    conversationStateRevisions[conversationId] = it
                }
                refreshPendingInteractionsLocked(conversationId, merged)
                // G-008：state.updated 的 workflowRuns 键（delta.ts:50；键级整体替换）
                patch["workflowRuns"]?.let { applyWorkflowRunsStateLocked(conversationId, it) }
                // 面板键（goal/plan/backgroundWorks/subagents/queue）任一到达即广播面板刷新
                if (patch.containsKey("goal") || patch.containsKey("plan") ||
                    patch.containsKey("backgroundWorks") || patch.containsKey("subagents") ||
                    patch.containsKey("queue")
                ) {
                    yieldPanelStateLocked(conversationId)
                }
                // state.patch 内也可能携带 workflowRun 单数键（旧实现兼容）
                patch["workflowRun"]?.let { workflowRunStates[conversationId] = it }
            }
            "workflowRun.updated" -> {
                // G-008 专属增量 op（delta.ts:122）——header 键整键替换（run patch +
                // cleared 清除）+ actors/nodes 条目按 (siteId, ordinal) 整条替换/移除
                applyWorkflowRunUpdatedLocked(conversationId, dict)
            }
            "workflowRun.removed" -> {
                // run 被生产者淘汰（delta.ts:140；只有生产者淘汰且必须说出来）
                val runId = dict["runId"]?.stringValue
                if (runId != null) {
                    val table = workflowRunTables[conversationId]
                    if (table != null) {
                        val remaining = table.runs.filter {
                            it.objectValue?.get("runId")?.stringValue != runId
                        }
                        val revision = dict["revision"]?.longValue() ?: table.revision
                        workflowRunTables[conversationId] = WorkflowRunTable(revision, remaining)
                    }
                }
                workflowRunStates.remove(conversationId)
            }
            else -> Unit
        }
    }

    /** streamablePath：text | inputText | output.text | summaryText（core.ts 口径；iOS :1455-1468）。 */
    private fun appendText(row: MutableMap<String, JSONValue>, path: String, append: String) {
        when (path) {
            "text", "inputText", "summaryText" -> {
                val existing = row[path]?.stringValue ?: ""
                row[path] = JSONValue.Str(existing + append)
            }
            "output.text" -> {
                val output = HashMap(row["output"]?.objectValue ?: emptyMap())
                val existing = output["text"]?.stringValue ?: ""
                output["text"] = JSONValue.Str(existing + append)
                row["output"] = JSONValue.Obj(output)
            }
            else -> Unit
        }
    }

    /** 挂起交互/模型选择/协作模式投影（iOS refreshPendingInteractions :1470-1489 直译）。 */
    private fun refreshPendingInteractionsLocked(conversationId: String, state: JSONValue) {
        val array = state.objectValue?.get("pendingInteractions")?.arrayValue
        if (array != null) {
            pendingInteractions[conversationId] = JSONValue.Arr(array)
        }
        // 会话级模型选择（桌面 composer 改的是 state.modelSelection——chips 同步权威源）
        val ms = state.objectValue?.get("modelSelection")
        if (ms != null && !ms.isNull) {
            sessionModelSelections[conversationId] = ms
        }
        // 会话协作模式（state.config.mode 权威源；nil/空 = 桌面尚未投影，不落）
        val mode = state.objectValue?.get("config")?.objectValue?.get("mode")?.stringValue
        if (!mode.isNullOrEmpty()) {
            sessionCollaborationModes[conversationId] = mode
        }
    }

    /** workflowRuns 状态键 → 表（整键替换；无 runs 数组形态忽略）（iOS :3332-3358 直译）。 */
    private fun applyWorkflowRunsStateLocked(conversationId: String, value: JSONValue) {
        val dict = value.objectValue ?: return
        val runs = dict["runs"]?.arrayValue ?: return
        workflowRunTables[conversationId] = WorkflowRunTable(
            revision = dict["revision"]?.longValue() ?: 0L,
            runs = runs,
        )
        // iOS persistWorkflowMirror（wf.runs.mirror.* UserDefaults 落盘）未移植：:store 无
        // UserDefaults 承载（挂装配轮持久化钩子）；表本体在内存，冷启动水合缺失仅影响
        // 「快照间歇缺席」场景的首屏时序（deviation 已登记）。
    }

    /**
     * workflowRun.updated（delta.ts:122；iOS :3362-3420 直译）：header 键整键替换（run patch +
     * cleared 清除）+ actors/nodes 条目按 (siteId, ordinal) 整条 upsert/移除。runId 不在表中
     * 且带 run 键 → 新建条目。绝无字段级深合并。
     */
    private fun applyWorkflowRunUpdatedLocked(conversationId: String, dict: Map<String, JSONValue>) {
        val runId = dict["runId"]?.stringValue ?: return
        val existing = workflowRunTables[conversationId]
        var revision = existing?.revision ?: 0L
        val runs = ArrayList(existing?.runs ?: emptyList())
        dict["revision"]?.longValue()?.let { revision = it }
        var index = runs.indexOfFirst { it.objectValue?.get("runId")?.stringValue == runId }
        // ① 新建条目（run 完整对象在场且表中无此 run）
        if (index < 0) {
            val fullRun = dict["run"]?.objectValue
            if (fullRun != null) {
                runs.add(JSONValue.Obj(fullRun))
                index = runs.size - 1
            } else {
                // iOS `guard var entry = index.flatMap {...} else { return }` 同口径：
                // 无既有条目且无完整 run 载荷 → 不动表（revision 也不推进）
                return
            }
        }
        val entry = HashMap(runs[index].objectValue ?: return)
        // ② header 键整键替换：run patch 逐键覆盖 + cleared 键删除（无深合并）
        dict["run"]?.objectValue?.let { patch -> entry.putAll(patch) }
        dict["cleared"]?.arrayValue?.forEach { cleared ->
            cleared.stringValue?.let { entry.remove(it) }
        }
        // ③ actors/nodes 条目按 (siteId, ordinal) 整条替换/移除（workflow-runs-delta.ts 规则）
        fun upsertEntries(key: String, incoming: List<JSONValue>?, removed: List<JSONValue>?) {
            val list = ArrayList(entry[key]?.arrayValue ?: emptyList())
            for (ref in removed ?: emptyList()) {
                val d = ref.objectValue ?: continue
                val siteId = d["siteId"]?.stringValue ?: continue
                val ordinal = d["ordinal"]?.intValue ?: continue
                list.removeAll {
                    it.objectValue?.get("siteId")?.stringValue == siteId &&
                        it.objectValue?.get("ordinal")?.intValue == ordinal
                }
            }
            for (item in incoming ?: emptyList()) {
                val d = item.objectValue ?: continue
                val siteId = d["siteId"]?.stringValue ?: continue
                val ordinal = d["ordinal"]?.intValue ?: continue
                val at = list.indexOfFirst {
                    it.objectValue?.get("siteId")?.stringValue == siteId &&
                        it.objectValue?.get("ordinal")?.intValue == ordinal
                }
                if (at >= 0) {
                    list[at] = item // 条目整条替换
                } else {
                    list.add(item)
                }
            }
            entry[key] = JSONValue.Arr(list)
        }
        upsertEntries("actors", incoming = dict["actors"]?.arrayValue, removed = dict["removedActors"]?.arrayValue)
        upsertEntries("nodes", incoming = dict["nodes"]?.arrayValue, removed = dict["removedNodes"]?.arrayValue)

        runs[index] = JSONValue.Obj(entry)
        workflowRunTables[conversationId] = WorkflowRunTable(revision, runs)
    }

    /** 从快照/op 信封提取 run 负载（多键宽容；无 run 形态返回 nil）（iOS :3439-3449 直译）。 */
    fun extractWorkflowRun(dict: Map<String, JSONValue>): JSONValue? {
        val candidate = dict["workflowRun"] ?: dict["run"] ?: dict["payload"] ?: dict["delta"]
        if (candidate != null) return candidate
        // 信封自身即 run 对象形态（含 runId/status 等特征键）
        val hasRunShape = dict.containsKey("runId") || dict.containsKey("workflowRunId") ||
            (dict.containsKey("status") && dict.containsKey("nodes"))
        return if (hasRunShape) JSONValue.Obj(dict) else null
    }

    // MARK: resync 族（iOS :3286-3329 直译）

    /** pending 兜底：订阅缺失时先走既有自愈链，再 base null 全量 resync。 */
    private suspend fun forceFullResyncForPending(conversationId: String) {
        if (mutex.withLock { conversationSubscriptionIds[conversationId] == null }) {
            ensureConversationSubscribed(conversationId)
        }
        val subId = mutex.withLock { conversationSubscriptionIds[conversationId] } ?: return
        forceFullResync(conversationId, subId)
    }

    /**
     * 无水位全量 resync（服务端重发全量快照，含 state.workflowRuns）。workspace 信封必须与
     * 订阅一致【实证·上游仓 zcodeAgentConnectionScope.resyncOwned：ownership 按
     * workspaceKey(params) 匹配订阅登记——缺 workspace/带错 workspace 都会
     * fault.subscription.notOwned】。
     */
    suspend fun forceFullResync(conversationId: String, subscriptionId: String) {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["subscriptionId"] = JSONValue.Str(subscriptionId)
        fields["base"] = JSONValue.Null
        applySessionTarget(fields, conversationId)
        // 全量语义标记：下一帧快照 rows 整表替换（handleConversationFrameLocked 回收）
        mutex.withLock { fullResyncSnapshotsPending.add(conversationId) }
        try {
            transport.call("zcode-agent", "resyncConversationV4", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径
        }
    }

    /** 主动全量 resync（ConversationStore 协议面：amend 停旧换新后拉新 run 用）。 */
    override suspend fun triggerResync(conversationId: String) {
        forceFullResyncForPending(conversationId)
    }

    /** 绕过差分的直发全量替换：lastYieldedMessages 必须与订阅方实收保持一致（iOS :3325-3329）。 */
    private suspend fun yieldReplacedSyncingBaseline(conversationId: String) {
        val latest = mutex.withLock {
            val latest = messages[conversationId] ?: emptyList()
            lastYieldedMessages[conversationId] = latest
            latest
        }
        yieldToAll(ConversationEvent.MessagesReplaced(conversationId, latest))
    }

    /** 面板态变更广播（iOS yieldPanelState :3703 的投影面：观察方重读面板投影）。 */
    private fun yieldPanelStateLocked(conversationId: String) {
        yieldToAll(ConversationEvent.PanelStateUpdated(conversationId))
    }

    // MARK: 行模型 → ChatMessage 映射（iOS rebuildMessages :1588-1787 直译）

    /**
     * 行集合 → ChatMessage 序列。乐观回显去重：服务端同文本 userInput 行抵达后移除在途
     * 回显（防快照/重连双气泡）。须在 [mutex] 临界区内调用。
     */
    private fun rebuildMessagesLocked(conversationId: String) {
        val table = rows[conversationId] ?: emptyMap()
        val result = ArrayList<ChatMessage>()
        val serverUserTexts = HashSet<String>()
        val serverSourceCommandIds = HashSet<String>()
        // 工作流完成卡轮尾落卡（桌面 WorkflowCompletionCard 同位形）：通知行是轮的
        // turnHeader（轮首），卡在轮尾——缓存到下一个轮边界（任一 turnHeader 行）或行
        // 序列末尾再落，digest 文本在前、卡在后
        var pendingWorkflowCompletion: Pair<Int, WorkflowCompletionCard>? = null
        for (rowId in table.keys.sorted()) {
            val record = table[rowId] ?: continue
            val row = record.json.objectValue ?: continue
            val kind = row["kind"]?.stringValue ?: continue
            if (kind == "turnHeader") {
                pendingWorkflowCompletion?.let { pending ->
                    result.add(
                        ChatMessage(
                            id = "row-${pending.first}-wfdone", role = MessageRole.agent, text = "",
                            rowKind = "workflowCompletion",
                            workflowCompletion = pending.second,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
                pendingWorkflowCompletion = null
            }
            when (kind) {
                "userInput" -> {
                    val text = row["text"]?.stringValue ?: ""
                    serverUserTexts.add(text)
                    val sourceCommandId = row["sourceCommandId"]?.stringValue
                    if (!sourceCommandId.isNullOrEmpty()) {
                        serverSourceCommandIds.add(sourceCommandId)
                    }
                    result.add(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.user,
                            text = text,
                            timestamp = System.currentTimeMillis(),
                            // G-014：桌面随行下发的图片/文件附件（截图类用户消息常见）
                            attachments = extractAttachmentRefs(row),
                            // P1-3 编辑重发行游标（C-1 修正：entityId 缺失 → UI 不渲染入口、store 拒发）
                            entityId = rowEntityId(row),
                            rowKind = "userInput",
                        ),
                    )
                }
                "assistantText" -> {
                    val state = row["state"]?.stringValue ?: "complete"
                    // P1-3 反馈态服务端回读（行上 feedback "like"|"dislike"|缺席三态）
                    val feedback = when (row["feedback"]?.stringValue) {
                        "like" -> true
                        "dislike" -> false
                        else -> null
                    }
                    result.add(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.agent,
                            text = row["text"]?.stringValue ?: "",
                            status = if (state == "streaming") MessageStatus.streaming else MessageStatus.done,
                            timestamp = System.currentTimeMillis(),
                            entityId = rowEntityId(row),
                            rowKind = "assistantText",
                            feedback = feedback,
                        ),
                    )
                }
                "reasoning" -> {
                    // 项 4：reasoning → ThinkingContent 折叠块（不再 💭 前缀平铺进正文流）
                    val state = row["state"]?.stringValue ?: "complete"
                    val text = row["text"]?.stringValue ?: ""
                    if (text.isEmpty()) continue
                    val messageId = "row-$rowId"
                    val thinkingState: ThinkingState
                    val now = System.currentTimeMillis()
                    when (state) {
                        "streaming" -> {
                            thinkingState = ThinkingState.streaming
                            if (reasoningStartedAt[messageId] == null) {
                                reasoningStartedAt[messageId] = now
                            }
                        }
                        "error", "cancelled", "interrupted", "aborted" ->
                            thinkingState = ThinkingState.interrupted
                        else -> {
                            thinkingState = ThinkingState.done
                            reasoningStartedAt[messageId]?.let { startedAt ->
                                reasoningDuration[messageId] = (now - startedAt) / 1000.0
                            }
                        }
                    }
                    result.add(
                        ChatMessage(
                            id = messageId, role = MessageRole.agent,
                            text = "",
                            status = if (thinkingState == ThinkingState.streaming) {
                                MessageStatus.streaming
                            } else {
                                MessageStatus.done
                            },
                            timestamp = now,
                            thinking = ThinkingContent(
                                text = text,
                                state = thinkingState,
                                startedAt = reasoningStartedAt[messageId],
                                duration = reasoningDuration[messageId],
                            ),
                        ),
                    )
                }
                "todo", "todos", "plan", "taskPlan", "todoList" -> {
                    // 行级 plan/todo 兜底（当前桌面 v4 的 todos 挂在会话 state 而非行）
                    val todos = parseTodoRow(row, rowId)
                    if (!todos.isNullOrEmpty()) {
                        result.add(
                            ChatMessage(
                                id = "row-$rowId", role = MessageRole.agent,
                                text = "",
                                status = MessageStatus.done,
                                timestamp = System.currentTimeMillis(),
                                todos = todos,
                            ),
                        )
                    }
                }
                "toolCall" -> {
                    result.add(toolCallRowMessage(row, rowId, "row-$rowId", includeRetryCursor = true))
                }
                "subagent" -> {
                    result.add(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.agent,
                            text = "🤖 子智能体 · ${row["subagentType"]?.stringValue ?: ""}：" +
                                (row["summaryText"]?.stringValue ?: ""),
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
                "artifact" -> {
                    val name = row["displayName"]?.stringValue
                        ?: row["name"]?.stringValue ?: "产物"
                    val type = row["artifactType"]?.stringValue
                        ?: row["type"]?.stringValue ?: "file"
                    result.add(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.agent,
                            text = "📦 产物 · $name（$type）",
                            timestamp = System.currentTimeMillis(),
                            attachments = extractAttachmentRefs(row),
                        ),
                    )
                }
                "turnHeader" -> {
                    // 工作流完成通知轮 → 轮尾落完成卡；其余 turnHeader 维持不进消息流
                    parseWorkflowCompletionRow(row)?.let { card ->
                        pendingWorkflowCompletion = rowId to card
                    }
                }
                else -> {
                    // hookInvocation / timelineMarker 不进消息流
                }
            }
        }
        pendingWorkflowCompletion?.let { pending ->
            result.add(
                ChatMessage(
                    id = "row-${pending.first}-wfdone", role = MessageRole.agent, text = "",
                    rowKind = "workflowCompletion",
                    workflowCompletion = pending.second,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
        // 在途回显：服务端行 sourceCommandId 与发送 commandId 相等（精确）或同文本
        // （旧行兜底）任一命中即消费掉；未抵达的按锚点插入保持时间序
        // （服务端不回推用户行时，回显应位于其发送后的助手回复之前）
        val outstanding = pendingLocalSends[conversationId]
        if (!outstanding.isNullOrEmpty()) {
            val consumedIDs = outstanding.entries
                .filter { entry ->
                    serverSourceCommandIds.contains(entry.value.commandId) ||
                        serverUserTexts.contains(entry.value.text)
                }
                .map { it.key }
            for (echoId in consumedIDs) {
                outstanding.remove(echoId)
            }
            var insertedCount = 0
            for ((echoId, entry) in outstanding.entries.sortedBy { it.value.anchor }) {
                val index = (entry.anchor + insertedCount).coerceIn(0, result.size)
                result.add(
                    index,
                    ChatMessage(
                        id = echoId, role = MessageRole.user, text = entry.text,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
                insertedCount += 1
            }
        }
        // 项 3：会话 state.todos（桌面 v4 口径）→ 流末尾常驻流程面板消息（随 state.updated 增量刷新）
        val stateTodos = parseStateTodos(snapshotState[conversationId])
        if (stateTodos != null) {
            result.removeAll { it.id == "state-todos" }
            result.add(
                ChatMessage(
                    id = "state-todos", role = MessageRole.agent,
                    text = "",
                    status = MessageStatus.done,
                    timestamp = System.currentTimeMillis(),
                    todos = stateTodos,
                ),
            )
        }
        // 回显被服务端行消费 / row.removed 等导致列表收缩时，append/update 差分事件
        // 无法表达删除——补发 messagesReplaced 全量事件（订阅方幂等替换）
        val didShrink = result.size < (lastYieldedMessages[conversationId]?.size ?: 0)
        messages[conversationId] = result
        yieldMessageEventsLocked(conversationId, result)
        if (didShrink) {
            yieldToAll(ConversationEvent.MessagesReplaced(conversationId, result))
        }
    }

    /**
     * 差分事件（iOS yieldMessageEvents :1812-1832 直译）：回显消费即中段变更（尾部
     * 「删回显+加行」总数不变，append 语义表达不了）；共享前缀任一 id 错位即发全量
     * replaced，驱动订阅方整表重建。须在 [mutex] 临界区内调用。
     */
    private fun yieldMessageEventsLocked(conversationId: String, latest: List<ChatMessage>) {
        val previous = lastYieldedMessages[conversationId] ?: emptyList()
        val headDrifted = previous.size > 0 &&
            previous.zip(latest).any { (p, l) -> p.id != l.id }
        // 首帧 / 头部插入（loadOlder 前置历史）/ 收缩：尾部 append 语义表达不了，发全量 replaced
        if (previous.isEmpty() || previous.firstOrNull()?.id != latest.firstOrNull()?.id ||
            latest.size < previous.size || headDrifted
        ) {
            yieldToAll(ConversationEvent.MessagesReplaced(conversationId, latest))
        } else {
            for (index in previous.size until latest.size) {
                yieldToAll(ConversationEvent.MessageAppended(conversationId, latest[index]))
            }
            for (message in latest) {
                val old = previous.firstOrNull { it.id == message.id }
                if (old != null && old != message) {
                    yieldToAll(ConversationEvent.MessageUpdated(conversationId, message))
                }
            }
        }
        lastYieldedMessages[conversationId] = latest
    }

    private fun yieldToAll(event: ConversationEvent) {
        // tryEmit + DROP_OLDEST 缺省 SUSPEND 会把慢收集器反压进锁内（锁内禁挂起红线）；
        // UI 事件允许丢弃（订阅方以 messages() 全量重读兜底），iOS continuation.yield
        // 满载丢弃同口径
        events.tryEmit(event)
    }

    // MARK: 行映射纯函数（iOS static 族直译；单测锁定）

    /** 行 → 附件引用列表（G-014 宽容解析：attachments 数组（字符串或 {ref} 对象）、顶层 ref）（iOS :1791-1801）。 */
    fun extractAttachmentRefs(row: Map<String, JSONValue>): List<String> {
        val refs = ArrayList<String>()
        fun push(value: JSONValue?) {
            when {
                value == null -> Unit
                !value.stringValue.isNullOrEmpty() -> value.stringValue?.let { refs.add(it) }
                !value.objectValue?.get("ref")?.stringValue.isNullOrEmpty() ->
                    value.objectValue?.get("ref")?.stringValue?.let { refs.add(it) }
            }
        }
        row["attachments"]?.arrayValue?.forEach { push(it) }
        if (refs.isEmpty()) push(row["ref"])
        return refs
    }

    /**
     * 行元数据游标提取（P1-3；C-1 修正：web 语义是 entityId 缺失即不出入口，
     * 无 turnId 回退——turnId 冒充 entityId 被服务端拒）。缺失/空串返回 null。（iOS :1807-1810）
     */
    fun rowEntityId(row: Map<String, JSONValue>): String? {
        val id = row["entityId"]?.stringValue ?: return null
        return id.ifEmpty { null }
    }

    /** toolName → ToolKind（bash/edit/read/ask/browser 五类；未知归 bash）（iOS :1843-1858）。 */
    private fun mapToolKind(toolName: String?): ToolKind {
        val name = (toolName ?: "").lowercase()
        return when {
            name.contains("bash") || name.contains("shell") || name.contains("terminal") -> ToolKind.bash
            name.contains("edit") || name.contains("write") || name.contains("patch") ||
                name.contains("apply") -> ToolKind.edit
            name.contains("read") || name.contains("grep") || name.contains("glob") ||
                name.contains("search") || name.contains("list") -> ToolKind.read
            name.contains("ask") || name.contains("question") -> ToolKind.ask
            name.contains("browser") || name.contains("navigate") -> ToolKind.browser
            else -> ToolKind.bash
        }
    }

    /** inputStreaming|pendingApproval|running → .running；success → .done；error|cancelled → .failed（iOS :1861-1867）。 */
    private fun mapToolStatus(status: String): ToolCallStatus = when (status) {
        "success" -> ToolCallStatus.done
        "error", "cancelled" -> ToolCallStatus.failed
        else -> ToolCallStatus.running
    }

    /**
     * toolCall 行 → 工具卡消息（主会话 rebuildMessages 与子代理转录 actorTranscript
     * 共用同一份行映射，防两处漂移；iOS :1874-1907）。includeRetryCursor=false
     * （子代理只读转录）不带 entityId。
     */
    fun toolCallRowMessage(
        row: Map<String, JSONValue>,
        rowId: Int,
        id: String,
        includeRetryCursor: Boolean,
    ): ChatMessage {
        val status = row["status"]?.stringValue ?: "running"
        val outputText = row["output"]?.objectValue?.get("text")?.stringValue
            ?: row["outputPreview"]?.objectValue?.get("text")?.stringValue
            ?: row["progress"]?.objectValue?.get("text")?.stringValue
        // G-020：workflow 启动类工具调用宽容取关联 runId（行 result/回执内 runId 键）
        val toolName = (row["toolName"]?.stringValue ?: "").lowercase()
        val workflowRunId = if (toolName.contains("workflow")) {
            row["result"]?.objectValue?.get("runId")?.stringValue
                ?: row["runId"]?.stringValue
                ?: row["output"]?.objectValue?.get("runId")?.stringValue
        } else {
            null
        }
        val toolCall = ToolCall(
            id = row["toolCallId"]?.stringValue ?: "tool-$rowId",
            kind = mapToolKind(row["toolName"]?.stringValue),
            target = row["inputText"]?.stringValue ?: row["toolName"]?.stringValue ?: "",
            status = mapToolStatus(status),
            duration = null,
            addedLines = row["display"]?.objectValue?.get("addedLines")?.intValue,
            removedLines = row["display"]?.objectValue?.get("removedLines")?.intValue,
            output = outputText,
            diff = null,
            entityId = if (includeRetryCursor) row["entityId"]?.stringValue else null,
            workflowRunId = workflowRunId,
        )
        return ChatMessage(
            id = id, role = MessageRole.agent,
            text = "",
            status = if (status == "running" || status == "inputStreaming" || status == "pendingApproval") {
                MessageStatus.streaming
            } else {
                MessageStatus.done
            },
            timestamp = System.currentTimeMillis(),
            toolCall = toolCall,
        )
    }

    /**
     * turnHeader 行 → 工作流完成卡（纯函数；只认四件齐备【实证·上游仓
     * workflowTurnCompletion.ts:35-49 同缝】：origin=="backgroundResult"、
     * originMeta.backgroundSource=="workflow"、载荷 kind=="terminal" 且 status=="completed"
     * （errored/stopped 不画卡——通知行已说错误））。name 取 originMeta.title（CLI
     * workflowTaskSubject 权威，不本地化）；artifacts 上界 8 由服务端保证，客户端不再裁。
     * （iOS :1518-1547）
     */
    fun parseWorkflowCompletionRow(row: Map<String, JSONValue>): WorkflowCompletionCard? {
        if (row["kind"]?.stringValue != "turnHeader" || row["origin"]?.stringValue != "backgroundResult") {
            return null
        }
        val meta = row["originMeta"]?.objectValue
        if (meta?.get("backgroundSource")?.stringValue != "workflow") return null
        val notification = meta?.get("workflowNotification")?.objectValue
        if (notification?.get("kind")?.stringValue != "terminal" ||
            notification?.get("status")?.stringValue != "completed"
        ) {
            return null
        }
        val name = (meta?.get("title")?.stringValue ?: "").trim()
        if (name.isEmpty()) return null
        val artifacts = (notification?.get("artifacts")?.arrayValue ?: emptyList()).mapNotNull { item ->
            val d = item.objectValue ?: return@mapNotNull null
            val id = d["id"]?.stringValue
            if (id.isNullOrEmpty()) return@mapNotNull null
            WorkflowCompletionArtifact(
                id = id,
                kind = d["kind"]?.stringValue ?: "file",
                title = d["title"]?.stringValue,
                version = d["version"]?.intValue ?: 1,
                primary = d["primary"]?.boolValue == true,
                description = d["description"]?.stringValue,
            )
        }
        return WorkflowCompletionCard(
            runId = meta?.get("workId")?.stringValue ?: "",
            name = name,
            durationMs = notification?.get("durationMs")?.intValue,
            artifacts = artifacts,
            truncated = notification?.get("artifactsTruncated")?.boolValue == true,
        )
    }

    /** 会话 state.todos → [TodoItem]（桌面 v4 schema；宽容兼容 title/label 与 error|failed）（iOS :1914-1920）。 */
    fun parseStateTodos(state: JSONValue?): List<TodoItem>? {
        val dict = state?.objectValue ?: return null
        val items = dict["todos"]?.arrayValue
            ?: dict["todoGroups"]?.objectValue?.get("todos")?.arrayValue
            ?: emptyList()
        return parseTodoItems(items, "state-todo")
    }

    /** 行级 todo/plan 兜底解析：行内 todos/items/steps/plan 数组，或行本身即单条 todo（iOS :1923-1941）。 */
    fun parseTodoRow(row: Map<String, JSONValue>, rowId: Int): List<TodoItem>? {
        val items = row["todos"]?.arrayValue
            ?: row["items"]?.arrayValue
            ?: row["steps"]?.arrayValue
            ?: row["plan"]?.arrayValue
        if (!items.isNullOrEmpty()) {
            return parseTodoItems(items, "row-todo-$rowId")
        }
        // 单条形态：行自身携带 content/title + status
        val title = row["content"]?.stringValue
            ?: row["title"]?.stringValue
            ?: row["label"]?.stringValue
        if (!title.isNullOrEmpty()) {
            val status = row["status"]?.stringValue ?: row["state"]?.stringValue ?: "pending"
            return listOf(
                TodoItem(
                    id = row["id"]?.stringValue ?: "row-todo-$rowId",
                    title = title,
                    state = mapTodoStatus(status),
                ),
            )
        }
        return null
    }

    private fun parseTodoItems(items: List<JSONValue>, idPrefix: String): List<TodoItem>? {
        val todos = ArrayList<TodoItem>()
        for ((index, item) in items.withIndex()) {
            val dict = item.objectValue ?: continue
            // 步骤标题：content（桌面 v4）为主，title/label/text 兼容
            val title = dict["content"]?.stringValue
                ?: dict["title"]?.stringValue
                ?: dict["label"]?.stringValue
                ?: dict["text"]?.stringValue
            if (title.isNullOrEmpty()) continue
            todos.add(
                TodoItem(
                    id = dict["id"]?.stringValue ?: "$idPrefix-$index",
                    title = title,
                    state = mapTodoStatus(dict["status"]?.stringValue ?: dict["state"]?.stringValue ?: "pending"),
                ),
            )
        }
        return if (todos.isEmpty()) null else todos
    }

    /** 步骤状态宽容映射：completed→done；in_progress/running→now；failed/error→failed（BiuZ 扩展）（iOS :1963-1970）。 */
    fun mapTodoStatus(raw: String): TodoState = when (raw.lowercase()) {
        "completed", "done", "success" -> TodoState.done
        "in_progress", "inprogress", "running", "active", "now" -> TodoState.now
        "failed", "error", "cancelled", "aborted" -> TodoState.failed
        else -> TodoState.todo
    }

    // MARK: 交互读投影（iOS :2889-3016 直译）

    /**
     * 该会话当前某类挂起交互的**解析投影**（kinds 例 ["permission"]；null = 无）。
     * RemoteConversationBridge 面（RemoteTaskStore 审批链以 interaction.id 应答）。
     */
    override suspend fun currentPendingInteraction(
        conversationId: String,
        kinds: List<String>,
    ): RemotePendingInteraction? {
        val array = mutex.withLock { pendingInteractions[conversationId] }?.arrayValue ?: return null
        val raw = array.firstOrNull { item ->
            val kind = item.objectValue?.get("kind")?.stringValue ?: return@firstOrNull false
            kinds.contains(kind)
        } ?: return null
        return parseInteraction(raw)
    }

    /** 锁内版（帧处理路径用；同 currentPendingInteraction 语义）。 */
    private fun currentPendingInteractionLocked(conversationId: String, kinds: List<String>): JSONValue? {
        val array = pendingInteractions[conversationId]?.arrayValue ?: return null
        return array.firstOrNull { item ->
            val kind = item.objectValue?.get("kind")?.stringValue ?: return@firstOrNull false
            kinds.contains(kind)
        }
    }

    /** pendingInteractions 数组 → 移动端投影（审批卡数据源）（iOS :2898-2901）。 */
    override suspend fun pendingInteractionList(conversationId: String): List<RemotePendingInteraction> {
        val array = mutex.withLock { pendingInteractions[conversationId] }?.arrayValue ?: return emptyList()
        return array.mapNotNull { parseInteraction(it) }
    }

    /**
     * 交互投影解析（宽容：id/interactionId 二选一；命令/路径/影响/title/options 就近取）
     * （iOS :2904-2975 直译，含 2026-10-08 plan_approval schema 归位与 G-017 planText 宽容链）。
     */
    fun parseInteraction(json: JSONValue): RemotePendingInteraction? {
        val dict = json.objectValue ?: return null
        val payload = dict["payload"]?.objectValue ?: emptyMap()
        val id = interactionIdOf(json) ?: return null
        val kind = dict["kind"]?.stringValue ?: payload["kind"]?.stringValue ?: return null
        var resolvedKind = kind
        var title = dict["title"]?.stringValue
            ?: dict["toolName"]?.stringValue
            ?: payload["title"]?.stringValue
            ?: payload["toolName"]?.stringValue
        // 2026-10-08 报障「plan 模式审核没出来」根因：上游 ExitPlanMode 挂起交互
        // kind="userInput"，plan_approval 只在 payload.schema.interaction
        // 【实证·上游仓 product-projection.ts:3117-3134】——按 schema 归位 kind，审批卡才出得来。
        val schemaInteraction = payload["schema"]?.objectValue?.get("interaction")?.stringValue
            ?: dict["schema"]?.objectValue?.get("interaction")?.stringValue
        if (resolvedKind == "userInput" && schemaInteraction == "plan_approval") {
            resolvedKind = "plan_approval"
            // 审批卡标题用审核原因（payload.prompt），工具名退居兜底
            val prompt = payload["prompt"]?.stringValue
            if (!prompt.isNullOrEmpty()) title = prompt
        }
        val command = dict["command"]?.stringValue
            ?: dict["inputText"]?.stringValue
            ?: payload["command"]?.stringValue
            ?: payload["inputText"]?.stringValue
            ?: payload["text"]?.stringValue
        val path = dict["path"]?.stringValue
            ?: dict["workspacePath"]?.stringValue
            ?: payload["path"]?.stringValue
            ?: payload["workspacePath"]?.stringValue
        val impact = dict["impact"]?.stringValue
            ?: dict["description"]?.stringValue
            ?: payload["impact"]?.stringValue
            ?: payload["description"]?.stringValue
        val options = (dict["options"]?.arrayValue ?: payload["options"]?.arrayValue ?: emptyList())
            .mapNotNull { element ->
                // A-3：options 保留 (id,label) 对——应答时原样回传 optionId，label 仅作展示。
                // 服务端 options 元素形状【未取证】，宽容链：对象取 id/optionId + label/title，
                // 裸字符串 id=label=该串。
                val text = element.stringValue
                if (!text.isNullOrEmpty()) {
                    return@mapNotNull RemoteInteractionOption(id = text, label = text)
                }
                val obj = element.objectValue ?: return@mapNotNull null
                val optionId = obj["id"]?.stringValue ?: obj["optionId"]?.stringValue
                if (optionId.isNullOrEmpty()) return@mapNotNull null
                RemoteInteractionOption(id = optionId, label = obj["label"]?.stringValue ?: optionId)
            }
        // G-017：plan_approval 计划文本。宽容链：renderContext.plan / 顶层 plan /
        // payload.input.plan（上游实证：直接取 schema ?? input 会丢 input.plan）
        val renderContext = payload["renderContext"]?.objectValue
            ?: dict["renderContext"]?.objectValue
        var planText: String? = null
        if (renderContext?.get("kind")?.stringValue == "plan_approval") {
            val plan = renderContext?.get("plan")?.stringValue
            if (!plan.isNullOrEmpty()) planText = plan
        }
        if (planText == null) {
            val plan = payload["plan"]?.stringValue ?: dict["plan"]?.stringValue
            if (!plan.isNullOrEmpty()) planText = plan
        }
        if (planText == null) {
            val plan = payload["input"]?.objectValue?.get("plan")?.stringValue
            if (!plan.isNullOrEmpty()) planText = plan
        }
        // workspace hook 信任审核：payload 宽容解析审核项（列表键/元素键均未取证）
        var hookReviewItems: List<WorkspaceHookReviewItem> = emptyList()
        if (kind == "workspaceHookReview") {
            hookReviewItems = parseHookReviewItems(payload, dict)
        }
        return RemotePendingInteraction(
            id = id,
            kind = resolvedKind,
            title = title,
            command = command,
            path = path,
            impact = impact,
            options = options,
            planText = planText,
            hookReviewItems = hookReviewItems,
        )
    }

    /**
     * workspaceHookReview 审核项宽容解析（iOS :2982-3008）：列表键链
     * reviewItems|items|hooks|reviewFlowItems（payload 优先、顶层次之）；元素 id 链
     * reviewItemId|id|hookId（reviewItemId 为 web 应答键实证，排首位）。
     * 解析不出任何项 → 空数组（卡片呈「审核项未同步」+ 重发请求入口，不虚构条目）。
     */
    fun parseHookReviewItems(
        payload: Map<String, JSONValue>,
        dict: Map<String, JSONValue>,
    ): List<WorkspaceHookReviewItem> {
        val rawItems = payload["reviewItems"]?.arrayValue
            ?: payload["items"]?.arrayValue
            ?: payload["hooks"]?.arrayValue
            ?: payload["reviewFlowItems"]?.arrayValue
            ?: dict["reviewItems"]?.arrayValue
            ?: dict["items"]?.arrayValue
            ?: return emptyList()
        return rawItems.mapNotNull { element ->
            val obj = element.objectValue ?: return@mapNotNull null
            val id = obj["reviewItemId"]?.stringValue
                ?: obj["id"]?.stringValue
                ?: obj["hookId"]?.stringValue
            if (id.isNullOrEmpty()) return@mapNotNull null
            WorkspaceHookReviewItem(
                id = id,
                title = obj["title"]?.stringValue
                    ?: obj["name"]?.stringValue
                    ?: obj["hookName"]?.stringValue,
                detail = obj["summary"]?.stringValue
                    ?: obj["description"]?.stringValue
                    ?: obj["command"]?.stringValue,
                trustState = obj["trustState"]?.stringValue,
            )
        }
    }

    /** 交互 id 宽容解析（id / interactionId / payload.interactionId）（iOS :3011-3016）。 */
    fun interactionIdOf(json: JSONValue): String? {
        val dict = json.objectValue ?: return null
        return dict["id"]?.stringValue
            ?: dict["interactionId"]?.stringValue
            ?: dict["payload"]?.objectValue?.get("interactionId")?.stringValue
    }

    // MARK: 会话级 state 读投影

    /** 会话协作模式投影（state.config.mode；null = 桌面尚未投影——订阅快照未到或旧桌面无该键）。 */
    override suspend fun sessionCollaborationMode(conversationId: String): String? =
        mutex.withLock { sessionCollaborationModes[conversationId] }

    /**
     * 会话级模型选择（state.modelSelection：{providerId, modelId, options:{reasoningLevel}}）。
     * providerId 是 getView 失败时同模型/思考档切换的唯一权威 provider 源
     * （provider 必填【实证·上游仓 command.ts:211】）。（iOS :1502-1510）
     */
    override suspend fun sessionModelSelection(conversationId: String): SessionModelSelection? {
        val ms = mutex.withLock { sessionModelSelections[conversationId] }?.objectValue ?: return null
        val model = ms["modelId"]?.stringValue ?: ms["model"]?.stringValue ?: return null
        val thought = ms["options"]?.objectValue?.get("reasoningLevel")?.stringValue
            ?: ms["thought"]?.stringValue
        val provider = (ms["providerId"]?.stringValue ?: ms["provider"]?.stringValue)
            ?.takeIf { it.isNotEmpty() }
        return SessionModelSelection(model = model, thought = thought, provider = provider)
    }

    /** state.workflowRuns 表直读（G-008 投影源；面板读面挂后续轮）。 */
    suspend fun workflowRunsTable(conversationId: String): WorkflowRunTable? =
        mutex.withLock { workflowRunTables[conversationId] }

    /** workflowRun 单数键缓存直读（旧实现兼容投影）。 */
    suspend fun workflowRunState(conversationId: String): JSONValue? =
        mutex.withLock { workflowRunStates[conversationId] }

    // MARK: 会话列表读面（iOS sessions 表在 :store 由 RemoteSessionsIndexStore 承载——委托直读）

    override suspend fun conversations(): List<Conversation> {
        // 发送后元数据兜底刷新前置（iOS refreshDueSessionMeta :2339-2341 同挂点）
        refreshDueSessionMeta()
        return sessionsIndex?.conversations() ?: emptyList()
    }

    override fun observeConversations(): Flow<ConversationEvent> = events.asSharedFlow()

    /** P0 缓存先行：纯内存会话摘要（无 RPC——首屏标题等元数据即时投影）。 */
    override suspend fun cachedConversation(conversationId: String): Conversation? =
        sessionsIndex?.currentConversations()?.firstOrNull { it.id == conversationId }

    // MARK: 命令面（iOS sendCommand :2036-2115 直译；写命令唯一出口，AGENTS §5-2 红线）

    /**
     * sendConversationCommandV4 信封构造：**全仓唯一合法构造点**——委托
     * ConversationCommandFactory（官方 commandFactory.ts:51-70 镜像，M1 组件）：
     * 构造即校验（payload strip 规范化 + 可信字段黑名单 + CAS 词表门 + 信封 schema
     * 复核）。workspace 信封按目标会话归属寻址（v1.16 实证：恒带连接 workspace 时
     * 跨工作区写全被桌面拒）；identity 缺席不携键（web scope 转换器同构）。
     * 本地拒发（client.casRevisionUnavailable / client.schemaViolation）返回合成拒收
     * 回执走调用方既有失败呈现（写面禁假成功 §5-12），不空耗注定被拒的写。
     * 「平铺形态兜底」不存在（A12：兜底用新 commandId 重发违反幂等纪律）。
     */
    suspend fun sendCommand(
        type: String,
        sessionId: String?,
        payload: JSONValue,
        casRevision: Boolean = false,
        workspacePathOverride: String? = null,
        commandId: String? = null,
        timeout: kotlin.time.Duration = 30.seconds,
    ): JSONValue? {
        // CAS 类命令携当前 state 定位：baseRevision（state.revision）+ baseLogEpoch
        // （订阅水位——桌面校验两者必须同时在场，缺一必拒）。任一缺席 = cas null →
        // Factory 构造期拒发（client.casRevisionUnavailable 合成拒收回执，不空耗注定被拒的写）
        var cas: CasRevision? = null
        if (casRevision) {
            cas = mutex.withLock {
                val revision = conversationStateRevisions[sessionId ?: ""]
                val epoch = conversationWatermarks[sessionId ?: ""]?.logEpoch
                if (revision != null && epoch != null) CasRevision(revision, epoch) else null
            }
        }
        val target = workspaceTarget(sessionId, workspacePathOverride)
        val request = ConversationCommandRequest(
            type = type,
            sessionId = sessionId,
            payload = payload,
            casRevision = cas,
            workspacePath = target.first,
            workspaceIdentity = target.second,
            commandId = commandId ?: java.util.UUID.randomUUID().toString(),
        )
        val built: ConversationCommandFactory.Built = ConversationCommandFactory.build(
            request,
            clientId = clientId,
            issuedAt = System.currentTimeMillis(),
        ).fold(
            onSuccess = { it },
            onFailure = { error ->
                // 本地拒发：合成拒收回执走调用方既有失败呈现；schemaViolation 落 diag 供取证
                val rejection = error as? ConversationCommandFactory.Rejection
                if (rejection != null) {
                    if (rejection.reasonCode == "client.schemaViolation") {
                        diag("diag.cmd.shape", "$type: ${rejection.message}")
                    }
                    return rejection.syntheticAck
                }
                return null
            },
        )
        return try {
            val ack = transport.call(
                "zcode-agent", "sendConversationCommandV4", RPCValue.json(built.outer),
                timeout = timeout,
            )
            ack.jsonValue?.let { json ->
                // 回执 revisionAtDecision 回填为最新 state revision（CAS 后续命令用）
                val sessionId0 = sessionId
                if (sessionId0 != null) {
                    json["revisionAtDecision"]?.longValue()?.takeIf { it > 0 }?.let {
                        mutex.withLock { conversationStateRevisions[sessionId0] = it }
                    }
                }
            }
            lastCommandError = ""
            ack.jsonValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 写失败错误原文落地（§5-12：RPCError.message 服务端 fault 原文，禁静默吞成 nil）
            lastCommandError = (e as? RPCError)?.message ?: e.toString().take(300)
            diag("diag.cmd.err", "type=$type envErr=${e.toString().take(300)}")
            null
        }
    }

    /**
     * 发送消息：sendText 信封真实下发（桌面端 agent 开跑）+ 本地乐观回显。
     * 服务端 userInput 行抵达（sourceCommandId 精确匹配）后经 rebuildMessages 去重；
     * 下发失败撤销回显 + sendRejectionText 在案，如实反馈未送达。（iOS send :2121-2125）
     */
    override suspend fun send(text: String, conversationId: String): Boolean =
        sendWithAttachments(
            text = text,
            attachments = emptyList(),
            requestedDelivery = null,
            modelSelection = null,
            conversationId = conversationId,
        )

    /** sendText 回执判定（iOS :2143-2151 直译）：nil = 送达；非 nil = 拒因文案。 */
    fun sendTextRejectionText(ack: JSONValue?): String? {
        if (ack?.objectValue == null) {
            // §11.2：回执缺席 = 连接断开**或超时**——超时时桌面可能已受理（移动端无
            // pendingCommandRegistry 对账机制，如实提示歧义，防止假阴性下重发双开 turn）
            return "消息未送达 · 连接中断或超时（超时时桌面可能仍在处理，重发前建议先确认会话列表，避免重复发送）"
        }
        return CommandAck(ack).failureText
    }

    /**
     * 携附件发送（P1-1）：attachments 非空时 sendText payload 附加 `attachments` 对象
     * 数组，元素键 `{ref, fileName, mime, bytes}`（B-1 web wire 同形）。requestedDelivery
     * 非空随 payload 下发（web delivery 枚举 startNow|queue|guide【实证】），null/空串
     * 不携键。modelSelection（P0 修复 2026-10-07）：draft 路径的会话前选择随首条
     * sendText 下发【实证·上游仓 command.ts sendText schema】。（iOS :2165-2174）
     */
    override suspend fun sendWithAttachments(
        text: String,
        attachments: List<OutgoingAttachment>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        conversationId: String,
    ): Boolean = performSend(
        text = text,
        attachments = attachments,
        requestedDelivery = requestedDelivery,
        modelSelection = modelSelection,
        heldQueueDisposition = null,
        expectedHeldQueueItemIds = null,
        conversationId = conversationId,
    )

    /**
     * held 态确认重发（§11.2）：disposition ∈ clearQueueAndSend|keepQueueAndSend +
     * expectedHeldQueueItemIds（CLI 执行 clear/keep 前校验集合一致——
     * command.ts:78-95；stale 拒绝 guard.heldQueueConfirmationStale 同链再确认）。
     * （iOS :2180-2191）
     */
    override suspend fun sendHeldConfirmed(
        text: String,
        attachments: List<OutgoingAttachment>,
        disposition: String,
        expectedHeldQueueItemIds: List<String>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        conversationId: String,
    ): Boolean = performSend(
        text = text,
        attachments = attachments,
        requestedDelivery = requestedDelivery,
        modelSelection = modelSelection,
        heldQueueDisposition = disposition,
        expectedHeldQueueItemIds = expectedHeldQueueItemIds,
        conversationId = conversationId,
    )

    /** 最近一次发送是否卡在 held 确认（确认弹层数据源；成功重发/新发送即清）（iOS :2194-2196）。 */
    override suspend fun heldQueueConfirmationPending(conversationId: String): Boolean =
        mutex.withLock { heldQueuePending[conversationId] == true }

    /** 当前挂起队列条目 id 集（快照 state.queue.items[].queueItemId 宽容链）（iOS :2199-2206）。 */
    override suspend fun queueHeldItemIds(conversationId: String): List<String> {
        val items = mutex.withLock {
            snapshotState[conversationId]?.objectValue?.get("queue")
                ?.objectValue?.get("items")?.arrayValue
        } ?: return emptyList()
        return items.mapNotNull {
            it.objectValue?.get("queueItemId")?.stringValue
                ?: it.objectValue?.get("itemId")?.stringValue
                ?: it.objectValue?.get("id")?.stringValue
        }
    }

    /** 最近一次 sendText 的桌面拒因（null = 无在案拒因；写失败时写入、成功时清除）。 */
    override suspend fun sendRejectionText(conversationId: String): String? =
        mutex.withLock { sendRejections[conversationId] }

    /**
     * 乐观回显 + sendText 下发主体（iOS performSend :2208-2297 直译）。
     * 回显链：信封 commandId 前置生成并登记在途表（A12 幂等：重发不变——服务端
     * userInput 行 sourceCommandId 即它，精确消费依据）→ 本地回显 append →
     * **lastYieldedMessages 基线同步**（v1.32 教训：直发 append 不更新基线，后续
     * rebuild 差分以不含回显的陈旧 previous 比对成「纯尾部新增」只发 append，
     * ViewModel 里的回显永不撤除 → 双气泡；凡绕过 yieldMessageEvents 直发事件，
     * 基线必须同步）→ MessageAppended 事件。
     */
    private suspend fun performSend(
        text: String,
        attachments: List<OutgoingAttachment>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        heldQueueDisposition: String?,
        expectedHeldQueueItemIds: List<String>?,
        conversationId: String,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val echoId = "local-send-${java.util.UUID.randomUUID()}"
        // 信封 commandId 前置生成并登记到回显条目（A12 幂等：重发不变）
        val commandId = java.util.UUID.randomUUID().toString()
        val anchor = mutex.withLock { rows[conversationId]?.size ?: 0 }
        val echo = ChatMessage(
            id = echoId, role = MessageRole.user, text = trimmed,
            timestamp = System.currentTimeMillis(),
        )
        mutex.withLock {
            pendingLocalSends.getOrPut(conversationId) { LinkedHashMap() }[echoId] =
                PendingSend(text = trimmed, anchor = anchor, commandId = commandId)
            messages[conversationId] = (messages[conversationId] ?: emptyList()) + echo
            // 订阅方实收基线同步记账（2026-10-09 报障「刚发消息两条、重进一条」根因）
            lastYieldedMessages[conversationId] =
                (lastYieldedMessages[conversationId] ?: emptyList()) + echo
        }
        yieldToAll(ConversationEvent.MessageAppended(conversationId, echo))
        val payload = LinkedHashMap<String, JSONValue>()
        payload["text"] = JSONValue.Str(trimmed)
        if (!requestedDelivery.isNullOrEmpty()) {
            payload["requestedDelivery"] = JSONValue.Str(requestedDelivery)
        }
        if (modelSelection != null) {
            // 形同 firstInput.modelSelection（web U7e）：档位缺席时整个 options 略去
            val selectionPayload = LinkedHashMap<String, JSONValue>()
            selectionPayload["providerId"] = JSONValue.Str(modelSelection.providerId)
            selectionPayload["modelId"] = JSONValue.Str(modelSelection.modelId)
            if (modelSelection.reasoningLevel.isNotEmpty()) {
                selectionPayload["options"] = JSONValue.Obj(
                    mapOf("reasoningLevel" to JSONValue.Str(modelSelection.reasoningLevel)),
                )
            }
            payload["modelSelection"] = JSONValue.Obj(selectionPayload)
        }
        if (attachments.isNotEmpty()) {
            // B-1：元素键 {ref, fileName, mime, bytes}（web wire 同形）
            payload["attachments"] = JSONValue.Arr(
                attachments.map { att ->
                    JSONValue.Obj(
                        mapOf(
                            "ref" to JSONValue.Str(att.ref),
                            "fileName" to JSONValue.Str(att.fileName),
                            "mime" to JSONValue.Str(att.mime),
                            "bytes" to JSONValue.Int(att.bytes),
                        ),
                    )
                },
            )
        }
        // held 确认重发载荷（§11.2：仅确认路径携带——常规发送不携）
        if (!heldQueueDisposition.isNullOrEmpty()) {
            payload["heldQueueDisposition"] = JSONValue.Str(heldQueueDisposition)
        }
        if (!expectedHeldQueueItemIds.isNullOrEmpty()) {
            payload["expectedHeldQueueItemIds"] = JSONValue.Arr(
                expectedHeldQueueItemIds.map { JSONValue.Str(it) },
            )
        }
        val ack = sendCommand(
            type = "sendText",
            sessionId = conversationId,
            payload = JSONValue.Obj(payload),
            commandId = commandId,
        )
        val rejection = sendTextRejectionText(ack)
        if (rejection != null) {
            // 未送达（连接断开/桌面拒收）：撤销乐观回显（在途表同步清除），下次重试
            // 不产生重影；拒因在案供 UI 错误行据实呈现（写面禁假成功 §5-12）
            mutex.withLock {
                pendingLocalSends[conversationId]?.remove(echoId)
                messages[conversationId] =
                    (messages[conversationId] ?: emptyList()).filter { it.id != echoId }
                sendRejections[conversationId] = rejection
                // held 态识别（§11.2，session-flow.ts:41-45 + web SessionPane 同构）：
                // 缺 disposition / 确认过期 → 标记待确认，UI 弹「清空/保留并发送」
                val reason = CommandAck(ack).reasonCode
                heldQueuePending[conversationId] =
                    reason == "heldQueueDispositionRequired" ||
                    reason == "guard.heldQueueConfirmationStale"
            }
            yieldReplacedSyncingBaseline(conversationId)
            return false
        }
        mutex.withLock {
            sendRejections.remove(conversationId)
            heldQueuePending.remove(conversationId)
        }
        // 首条消息发出后桌面端会生成标题——登记兜底刷新窗口
        registerMetaRefreshIfNeeded(conversationId)
        // 投影 watchdog（P0-2，官方 expectAcceptedInputProjection 同构：
        // conversationProjectionStore.ts:1247-1274 sendText ACK 后 2s 投影静默 →
        // requestRecovery）：主动 same-sub 全量 resync 拉一次。幂等：行已增长即放弃。
        scheduleProjectionWatchdog(
            conversationId = conversationId,
            anchorRows = anchor + 1,
            commandId = commandId,
        )
        return true
    }

    /**
     * sendText 投影 watchdog（iOS :2305-2324 直译）：2s 后行数未越过锚点（服务端
     * userInput 行未达/快照未重放）→ 强制全量 resync 一次。队列感知（官方
     * conversationProjectionStore.ts:78-88 同构）：命中锚定 commandId 的 queue 条目
     * （sourceCommandId）也算收口——queue/guide 投递不产生行，不误触发 resync。
     */
    private fun scheduleProjectionWatchdog(conversationId: String, anchorRows: Int, commandId: String) {
        scope.launch {
            delay(WATCHDOG_DELAY_MS)
            val rowCount = mutex.withLock { rows[conversationId]?.size ?: 0 }
            if (rowCount >= anchorRows) return@launch
            val queued = mutex.withLock {
                snapshotState[conversationId]?.objectValue?.get("queue")
                    ?.objectValue?.get("items")?.arrayValue
            } ?: emptyList()
            if (queued.any { it.objectValue?.get("sourceCommandId")?.stringValue == commandId }) {
                return@launch
            }
            val subId = mutex.withLock { conversationSubscriptionIds[conversationId] } ?: return@launch
            diag(
                "diag.watchdog.${conversationId.take(14)}",
                "sendText accepted 后 2s 投影静默（anchor=$anchorRows rows=$rowCount）→ resync",
            )
            forceFullResync(conversationId, subId)
        }
    }

    // MARK: 发送后元数据兜底刷新（iOS :2326-2396；「新会话标题不回填」）

    /**
     * 仅占位标题（"新会话"/空串）登记——桌面自动命名只发生在这类会话上；
     * 用户命名/已回填标题的会话不值得周期拉取。窗口 10min，15s 首试。
     */
    private suspend fun registerMetaRefreshIfNeeded(conversationId: String) {
        val currentTitle = sessionsIndex?.sessionSummaries()?.get(conversationId)?.title ?: ""
        if (currentTitle.isNotEmpty() && currentTitle != PLACEHOLDER_TITLE) return
        val now = nowMs()
        mutex.withLock {
            metaRefreshDeadline[conversationId] = now + META_REFRESH_WINDOW_MS
            metaRefreshNextTry[conversationId] = now + META_REFRESH_FIRST_TRY_MS
        }
    }

    /**
     * conversations() 前置调用：到期会话单区 listSessions 拉一次，字段级回填
     * 标题/预览/活跃时间（只改既有行不新增行——新增行归 sessions-index/bootstrap
     * 通道）。目标会话标题落定即注销；30s 重试至窗口（10min）过期。
     */
    private suspend fun refreshDueSessionMeta() {
        val index = sessionsIndex ?: return
        val due: List<Pair<String, Long>> = mutex.withLock {
            if (metaRefreshNextTry.isEmpty()) return
            metaRefreshNextTry.map { it.key to it.value }
        }
        val now = nowMs()
        for ((conversationId, nextTry) in due) {
            if (now < nextTry) continue
            val deadline = mutex.withLock { metaRefreshDeadline[conversationId] }
            if (deadline == null || now > deadline) {
                mutex.withLock {
                    metaRefreshNextTry.remove(conversationId)
                    metaRefreshDeadline.remove(conversationId)
                }
                continue
            }
            mutex.withLock { metaRefreshNextTry[conversationId] = now + META_REFRESH_RETRY_MS }
            val path = index.sessionSummaries()[conversationId]?.workspacePath ?: workspace.path
            val items: List<JSONValue> = try {
                val fields = LinkedHashMap<String, JSONValue>()
                fields["workspacePath"] = JSONValue.Str(path)
                val result = transport.call(
                    "zcode-agent", "listSessions", RPCValue.json(JSONValue.Obj(fields)),
                )
                result.jsonValue?.arrayValue
                    ?: result.jsonValue?.get("sessions")?.arrayValue
                    ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // 到期未拉到：30s 后本方法再试；窗口过期静默放弃
            }
            val titleChanged = index.backfillSessionFields(items)
            if (conversationId in titleChanged) {
                mutex.withLock {
                    metaRefreshNextTry.remove(conversationId)
                    metaRefreshDeadline.remove(conversationId)
                }
            }
        }
    }

    /** 最近一次 createConversation 失败原文（空串 = 无）。 */
    override suspend fun lastCreateFailureText(): String = lastCreateFailure

    /** 最近一次 sendCommand 传输层失败原文（把「未送达」换成真实拒因；§5-12）。 */
    suspend fun lastCommandErrorText(): String = lastCommandError

    /** 任务元数据写（标记未读）最近一次失败原文（空串 = 无；§5-12 写面禁静默）。 */
    override suspend fun lastTaskWriteFailureText(): String = lastTaskWriteFailure

    // MARK: 停止与应答（iOS :2398-2433；RemoteConversationBridge 面）

    /**
     * 停止当前 turn（任务页「停止」入口；RemoteTaskStore 委托至此，统一信封纪律）。
     * expectedForegroundExecutionId 竞态保护（2026-10-08 §11.3，
     * command.ts:144-147【实证·上游仓】）：来自 state.control.activeWorks——CLI 用它
     * 拒绝「会误杀后续无关执行的迟到 Stop」。best-effort：投影缺席则不带。
     */
    override suspend fun stopTurn(sessionId: String): JSONValue? {
        val payload = LinkedHashMap<String, JSONValue>()
        val activeWorks = mutex.withLock {
            snapshotState[sessionId]?.objectValue?.get("control")
                ?.objectValue?.get("activeWorks")?.arrayValue
        } ?: emptyList()
        val execId = activeWorks.firstNotNullOfOrNull { work ->
            work.objectValue?.get("foregroundExecutionId")?.stringValue?.takeIf { it.isNotEmpty() }
        }
        if (execId != null) {
            payload["expectedForegroundExecutionId"] = JSONValue.Str(execId)
        }
        return sendCommand("stop", sessionId, JSONValue.Obj(payload))
    }

    /**
     * 提问应答：解析最新 userInput/elicitation 类挂起交互 → resolveInteraction
     * （interactionId + answer 对象）。A-3 修正：answer 恒为对象——提问族 `{freeText}`
     * （web 实证 bundle：answer schema，裸字符串被 zod expected object 拒）；
     * 无挂起交互则静默（无对象可应答）。（iOS :2424-2433）
     */
    override suspend fun answerQuestion(reply: String, conversationId: String, questionId: String) {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return
        val interaction = currentPendingInteraction(
            conversationId, listOf("userInput", "elicitation", "question"),
        ) ?: return
        resolveInteractionRaw(
            conversationId,
            interaction.id,
            JSONValue.Obj(mapOf("freeText" to JSONValue.Str(trimmed))),
        )
    }

    /**
     * 交互应答信封下发：resolveInteraction（interactionId + answer）。
     * A-3 修正：answer 恒为对象按交互族分形（权限 {optionId} / 计划 {action,…} /
     * 提问 {freeText}|{optionId}）由调用方构造；payload 恒 {interactionId, answer}
     * 两键（无顶层平铺——approved/scope/text 是 wire 不存在的键）。（iOS :3026-3033）
     */
    override suspend fun resolveInteractionRaw(
        conversationId: String,
        interactionId: String,
        answer: JSONValue,
    ): JSONValue? = sendCommand(
        type = "resolveInteraction",
        sessionId = conversationId,
        payload = JSONValue.Obj(
            linkedMapOf(
                "interactionId" to JSONValue.Str(interactionId),
                "answer" to answer,
            ),
        ),
    )

    // MARK: 新建会话（iOS :2435-2592 直译；§7.6：draft 首发协议主路径 = createSession+firstInput）

    /**
     * 新建会话（去重入口）：同 (标题, 目录, 执行端, 模型选择) 的并发创建合并为一次
     * createSession 下发、共享同一结果（A12 防双写——超时等待窗内重复点按曾产生
     * 多份桌面会话）。真实流程在 [performCreateConversation]；请求完成后即出去重窗
     * （完成后再次提交 = 用户有意的重试，不拦）。
     */
    override suspend fun createConversation(
        title: String,
        directory: String,
        executor: ExecutorKind,
        modelSelection: NewSessionModelSelection?,
    ): Conversation {
        val trimmed = title.trim()
        val trimmedDirectory = directory.trim()
        val dedupKey = listOf(
            trimmed,
            trimmedDirectory,
            executor.name,
            modelSelection?.providerId ?: "",
            modelSelection?.modelId ?: "",
            modelSelection?.reasoningLevel ?: "",
        ).joinToString("\u001F")
        mutex.withLock { inFlightCreates[dedupKey] }?.let { existing -> return existing.await() }
        val deferred = CompletableDeferred<Conversation>()
        val registered = mutex.withLock {
            val existing = inFlightCreates[dedupKey]
            if (existing != null) {
                existing
            } else {
                inFlightCreates[dedupKey] = deferred
                null
            }
        }
        if (registered != null) return registered.await()
        try {
            val conversation = performCreateConversation(
                title = title,
                directory = directory,
                executor = executor,
                modelSelection = modelSelection,
            )
            deferred.complete(conversation)
            return conversation
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock { inFlightCreates.remove(dedupKey) }
        }
    }

    /**
     * 新建会话实体流程：标题/首条指令非空时以 createSession+firstInput 一次下发
     * （桌面端立即开跑首条 turn，边界内允许）——**§7.6 否定性结论：本地凭空 sessionId
     * 的 draft 后发 sendText 收 proto.sessionNotFound，首发必须走本主路径**；
     * 标题为空时保持 draft 空会话 + promoteDeferredDraftSession 转正写。
     * modelSelection 非空随 firstInput.modelSelection 下发（形同 web U7e：
     * {providerId, modelId, options:{reasoningLevel}}，档位缺席略去 options）。
     * 失败如实上屏（空 id Conversation + lastCreateFailureText 原文——凭空 UUID()
     * 假成功已废除）。**不做超时自动重发**（写命令，回执丢失场景重发有双创建风险）。
     */
    private suspend fun performCreateConversation(
        title: String,
        directory: String,
        executor: ExecutorKind,
        modelSelection: NewSessionModelSelection?,
    ): Conversation {
        val trimmed = title.trim()
        val trimmedDirectory = directory.trim()
        val displayTitle = trimmed.ifEmpty { PLACEHOLDER_TITLE }
        val payload = LinkedHashMap<String, JSONValue>()
        // 项 2：directory 参数承载项目层选择 → createSession workspaceId；空回退连接 workspace
        payload["workspaceId"] = JSONValue.Str(trimmedDirectory.ifEmpty { workspace.path })
        if (trimmed.isNotEmpty()) {
            val firstInput = LinkedHashMap<String, JSONValue>()
            firstInput["text"] = JSONValue.Str(trimmed)
            if (modelSelection != null) {
                val selectionPayload = LinkedHashMap<String, JSONValue>()
                selectionPayload["providerId"] = JSONValue.Str(modelSelection.providerId)
                selectionPayload["modelId"] = JSONValue.Str(modelSelection.modelId)
                if (modelSelection.reasoningLevel.isNotEmpty()) {
                    selectionPayload["options"] = JSONValue.Obj(
                        mapOf("reasoningLevel" to JSONValue.Str(modelSelection.reasoningLevel)),
                    )
                }
                firstInput["modelSelection"] = JSONValue.Obj(selectionPayload)
            }
            payload["firstInput"] = JSONValue.Obj(firstInput)
        }
        val ack = sendCommand(
            type = "createSession",
            sessionId = null, // A1：createSession 信封 sessionId=null，键恒在场
            payload = JSONValue.Obj(payload),
            // 信封 workspacePath 与 workspaceId 同源（web 同构：create 在目标 workspace 里建）
            workspacePathOverride = trimmedDirectory.ifEmpty { null },
            // 创建为重命令（桌面携 firstInput 立即开跑首条 turn）：60s 专项预算
            timeout = CREATE_SESSION_TIMEOUT,
        )
        val sessionId = ack?.get("result")?.objectValue?.get("sessionId")?.stringValue
            ?: ack?.get("sessionId")?.stringValue
        if (sessionId.isNullOrEmpty()) {
            // createSession 被拒/未送达：如实失败（上游 schema strict——firstInput 载荷
            // 任一键不合形即整条拒收）。拒因原文上屏（§5-12）
            lastCreateFailure = if (ack != null) {
                CommandAck(ack).failureText
                    ?: "桌面端回执缺少 sessionId（创建被拒，请检查模型选择后重试）"
            } else {
                val transportError = lastCommandError.trim()
                if (transportError.contains("超时")) {
                    // 超时专属口径：请求可能已在桌面端落地（回执未归）——指引查列表不纵容重试
                    "创建请求超时 · 桌面端可能仍在处理，可稍后在会话列表查看，请勿连续重试"
                } else if (transportError.isEmpty()) {
                    "创建请求未送达 · 连接中断或桌面端拒收"
                } else {
                    "创建请求未送达 · $transportError"
                }
            }
            return Conversation(
                id = "",
                title = displayTitle,
                summary = "",
                directory = directory,
                updatedAt = System.currentTimeMillis(),
            )
        }
        lastCreateFailure = ""
        lastCommandError = ""
        // 新会话即刻入 sessions 表——否则列表一重载新建行就消失，直到桌面
        // sessions-index 推送才回来（真机报障「新建会话后找不到」）
        sessionsIndex?.upsertLocalSession(
            SessionSummary(
                sessionId = sessionId,
                title = displayTitle,
                phase = if (trimmed.isEmpty()) "draft" else "running",
                lastActivityAt = System.currentTimeMillis(),
                workspacePath = trimmedDirectory.ifEmpty { workspace.path },
            ),
        )
        if (trimmed.isEmpty()) {
            // draft 空会话：promote 转正写 task index，重启后不丢（失败静默——draft 语义保留可重建）
            val fields = LinkedHashMap<String, JSONValue>()
            fields["sessionId"] = JSONValue.Str(sessionId)
            promoteDraftSession(fields)
        }
        val conversation = Conversation(
            id = sessionId,
            title = displayTitle,
            summary = if (trimmed.isEmpty()) "空会话 · 可在输入框发起首条任务" else "已发送首条指令 · 桌面端执行中",
            directory = directory,
            updatedAt = System.currentTimeMillis(),
            isRunning = trimmed.isNotEmpty(),
        )
        yieldToAll(ConversationEvent.ConversationUpdated(conversation))
        yieldConversationsReplaced()
        return conversation
    }

    private suspend fun promoteDraftSession(fields: LinkedHashMap<String, JSONValue>) {
        try {
            transport.call("zcode-session", "promoteDeferredDraftSession", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径
        }
    }

    /** 列表全量替换事件（本 store 侧投影源 = sessionsIndex；iOS yieldConversationsReplaced 同名）。 */
    private suspend fun yieldConversationsReplaced() {
        val list = sessionsIndex?.currentConversations() ?: emptyList()
        yieldToAll(ConversationEvent.ConversationsReplaced(list))
    }

    // MARK: 列表态变更（iOS :2741-2782 直译；本地置位 + zcode-task 双写）

    /**
     * 标记未读（P1-5）：本地置位 + setTaskUnread unread:true。写失败**回滚本地置位**
     * + 拒因上屏（写面禁静默 §5-12；回滚=可见失败，下次快照真置位会再同步）。
     * 寻址：zcode-task 行级写按行归属 workspace（workspaceTarget；iOS taskWriteTarget
     * 的 archivedTaskWorkspaces/bootstrapWorkspaces 反查段挂归档批次——非归档行同语义）。
     */
    override suspend fun markUnread(conversationId: String) {
        sessionsIndex?.let { index ->
            index.unreadFlags = index.unreadFlags + (conversationId to true)
            yieldConversationsReplaced()
        }
        val target = workspaceTarget(conversationId, null)
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(conversationId)
        fields["workspacePath"] = JSONValue.Str(target.first)
        val identity = target.second
        if (!identity.isNullOrEmpty()) {
            fields["workspaceIdentity"] = JSONValue.Str(identity)
        }
        fields["unread"] = JSONValue.Bool(true)
        try {
            transport.call("zcode-task", "setTaskUnread", RPCValue.json(JSONValue.Obj(fields)))
            lastTaskWriteFailure = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessionsIndex?.let { index ->
                index.unreadFlags = index.unreadFlags - conversationId
                yieldConversationsReplaced()
            }
            lastTaskWriteFailure = RemoteSessionsIndexStore.loadFailureText("标记未读", e)
        }
    }

    /**
     * 已读清零：本地清零 + setTaskUnread（compare-and-clear 防并发覆盖；失败不回滚
     * ——未读态会在下一次快照/增量恢复，本地清零无破坏性）。（iOS :2768-2782）
     * 【偏差】iOS 同时清零 summary 的 pending 计数（badge 数据源）——计数在
     * RemoteSessionsIndexStore 合并表内由帧面投影，本批不动表（挂列表批次）。
     */
    override suspend fun markRead(conversationId: String) {
        sessionsIndex?.let { index ->
            index.unreadFlags = index.unreadFlags - conversationId
            yieldConversationsReplaced()
        }
        val target = workspaceTarget(conversationId, null)
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(conversationId)
        fields["workspacePath"] = JSONValue.Str(target.first)
        fields["unread"] = JSONValue.Bool(false)
        try {
            transport.call("zcode-task", "setTaskUnread", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径（compare-and-clear 无破坏性）
        }
    }

    companion object {
        /** keep-warm 租约窗缺省（上游 sessionDataLayer.ts:30 SESSION_DATA_LAYER_KEEP_WARM_MS）。 */
        const val DEFAULT_KEEP_WARM_MS = 30_000L

        /** 上游 E2E 档（sessionDataLayer.ts:31 E2E_SESSION_DATA_LAYER_KEEP_WARM_MS）。 */
        const val E2E_KEEP_WARM_MS = 1_000L

        /** 快照落表等待：单次 100ms × 6（iOS messages(in:) :869-872）。 */
        private const val SNAPSHOT_WAIT_MS = 100L

        /** 翻页/超时退避重试间隔（AGENTS §5-9 中继瞬断 1.2s 退避同口径）。 */
        private const val PAGE_RETRY_BACKOFF_MS = 1_200L

        /** 投影 watchdog 窗（官方 conversationProjectionStore.ts:1247-1274 同值 2s）。 */
        private const val WATCHDOG_DELAY_MS = 2_000L

        /** 占位标题（iOS String(localized: "新会话")；键 = 串本身）。 */
        private const val PLACEHOLDER_TITLE = "新会话"

        /** 标题兜底刷新窗（10min，iOS :2335）。 */
        private const val META_REFRESH_WINDOW_MS = 600_000L

        /** 标题兜底首试延时（15s，iOS :2336——桌面自动命名在首条 turn 后很快落定）。 */
        private const val META_REFRESH_FIRST_TRY_MS = 15_000L

        /** 标题兜底重试间隔（30s，iOS :2354）。 */
        private const val META_REFRESH_RETRY_MS = 30_000L

        /** createSession 专项预算（iOS :2516-2520：创建为重命令，30s 缺省在繁忙桌面易超时 → 60s）。 */
        private val CREATE_SESSION_TIMEOUT = 60.seconds
    }
}

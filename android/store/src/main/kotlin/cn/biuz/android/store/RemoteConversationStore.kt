package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Stores/Remote/RemoteConversationStore.swift（5646 行）的
// **订阅/快照/行投影面 + 发送面 + 写命令族**（:860-1908 消息 + 帧路由 + 行模型映射、
// :2023-2034 workspaceTarget、:2889-3016 交互读投影、:3286-3450 resync 族 + workflowRuns
// 表应用、发送面 :2036-2592、:2744-2782 markUnread/markRead、组织态 :2596-2739、
// v4 命令扩容族 :3848-4238/:4453-4501（队列五件/pauseGoal/resumeGoal/retryTurn/
// setAssistantFeedback/editUserQuery/switchModelConfig/switchCollaborationMode/
// setFollowupMode——sendCASWithRetry 三段自愈 + ensureStateRevision 内建）+ 模型面板
// getView 解析 :5261-5578）。会话列表读面（iOS :274-858，:store 已由
// RemoteSessionsIndexStore 承载）不在本文件。
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
import cn.biuz.android.protocol.SubagentProjectionState
import cn.biuz.android.protocol.SubagentRuntimeSummary
import cn.biuz.android.protocol.ActivitySummaryDismissed
import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.ArtifactRowInfo
import cn.biuz.android.protocol.CasRevision
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.CommandSchemas
import cn.biuz.android.protocol.ConversationCommandFactory
import cn.biuz.android.protocol.ConversationCommandRequest
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.MessageStatus
import cn.biuz.android.protocol.PlanPanelSummary
import cn.biuz.android.protocol.RemoteGoalSummary
import cn.biuz.android.protocol.SubagentSessionSummary
import cn.biuz.android.protocol.PSchema
import cn.biuz.android.protocol.PValidator
import cn.biuz.android.protocol.RemoteInteractionOption
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.SubagentSummary
import cn.biuz.android.protocol.TimelineMarkerInfo
import cn.biuz.android.protocol.ThinkingContent
import cn.biuz.android.protocol.ThinkingState
import cn.biuz.android.protocol.TodoItem
import cn.biuz.android.protocol.TodoState
import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.protocol.ToolCallStatus
import cn.biuz.android.protocol.ToolKind
import cn.biuz.android.protocol.SessionWorkflowRunSummary
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.protocol.WorkflowActivitySummary
import cn.biuz.android.protocol.WorkflowActorSummary
import cn.biuz.android.protocol.WorkflowArtifactBrief
import cn.biuz.android.protocol.WorkflowCompletionCard
import cn.biuz.android.protocol.WorkflowCompletionArtifact
import cn.biuz.android.protocol.WorkflowNodeSummary
import cn.biuz.android.protocol.WorkflowRunSummary
import cn.biuz.android.protocol.WorkflowStepStatus
import cn.biuz.android.protocol.WorkspaceHookReviewItem
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * 工作流状态镜像持久化钩子（iOS persistWorkflowMirror/loadWorkflowMirror :3424-3437 的
 * 持久化抽象）：快照/增量一到即落盘（截尾 4 条），冷启动表空时水合，面板不再依赖
 * 投递时序。iOS 以 UserDefaults `wf.runs.mirror.<id>` 承载；Android :store 纯 JVM，
 * 实现由装配层提供（DataStore/Preferences + JsonCodec 序列化）。
 */
interface WorkflowMirrorStore {
    fun save(conversationId: String, runs: List<JSONValue>)

    fun load(conversationId: String): List<JSONValue>?
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
    /**
     * 3.17.2 输入预测能力门：hello.capabilities.inputPrediction === true 才发请求
     * （spec「手机端建议①」）。装配层在握手完成后从连接实例注入
     * （connection.inputPredictionAvailable()——iOS :250-253 同口径）；Store 与连接
     * 同生命周期重建，构造注入即单一来源。false = 恒 null（旧 Host / 未宣告）。
     */
    private val inputPredictionEnabled: Boolean = false,
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
) : ConversationStore, RemoteConversationBridge, FileConversationAnchor {

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
    /** 触发表缺失全量 resync 去重（快照 state.workflowRuns 间歇缺席的一次性自愈；iOS :3222） */
    private val workflowResyncTriggered = HashSet<String>()
    /** conversationWorkflowRunsV4 只读兜底去重（一次，失败不重复；iOS :3250 workflowRunFetched） */
    private val workflowRunFetched = HashSet<String>()
    /** membership（pin/archive 组织态权威源）上次刷新时刻（30s 节流；iOS membershipRefreshedAt :302-308） */
    @Volatile
    private var membershipRefreshedAt: Long? = null
    /**
     * 项目归属元数据（host-scope/逐工作区 listTaskList）上次刷新时刻。
     * sessions-index 行只有 workspaceId（上行 schema 冻结），workspacePath 只能由
     * task-index 行补齐——列表「按项目分组」的数据源（iOS mergeGlobalTaskIndex :501-599）。
     * **失败短退避（2026-11-23 真机实证）**：首次 `conversations()` 常撞连接建立/stale
     * 重连期——host 与逐区两路全空，若同样吃满 120s 长窗，用户在抖动连接下长期看不到
     * 分组。现按 [projectMetadataLastAttemptFailed] 区分：失败 15s 后即重试，成功才进
     * 120s 长窗（重连重建 Store 时本字段随实例复位，天然立即重试）。
     */
    @Volatile
    private var projectMetadataRefreshedAt: Long? = null

    @Volatile
    private var projectMetadataLastAttemptFailed: Boolean = false
    /** bootstrap 派生工作区清单（membership scope 枚举第二源；AppSession 装配后回写，iOS bootstrapWorkspaces） */
    @Volatile
    var bootstrapWorkspaces: List<StoreWorkspaceInfo> = emptyList()
    /**
     * 工作流状态镜像持久化钩子（iOS persistWorkflowMirror/loadWorkflowMirror 的
     * `wf.runs.mirror.<id>` UserDefaults 落盘；wf.runs.mirror.* 是 diag.* 键族中唯一承载
     * 业务逻辑的例外，AGENTS §4.6 已注明）。:store 为纯 JVM 模块——装配层接
     * DataStore/Preferences 承接；null = 仅内存（冷启动水合缺失仅影响「快照间歇缺席」
     * 场景的首屏时序）。
     */
    @Volatile
    var workflowMirrorStore: WorkflowMirrorStore? = null
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

    /** 任务元数据写（标记未读/置顶/改名）最近一次失败原文（空串 = 无；§5-12 写面禁静默）（iOS :221-222/:2626）。 */
    @Volatile
    private var lastTaskWriteFailure = ""

    // MARK: v4 命令扩容族状态（iOS :3848-4238/:4453-4501 + ChatViewModel v1.33 护栏的 :store 承载）

    /**
     * 协作模式切换失败/未知态常驻错误行（nil = 无在案；区别于 3s 闪过的提示——
     * 「选了没有变化/点了没反应」报障直接根因。清除 = 下次权威成功切换 /
     * state.config.mode 投影对未知态定案）（iOS ChatViewModel.collaborationModeFailureText）。
     */
    private val collaborationModeFailures = HashMap<String, String>()

    /**
     * 协作模式本地切换收敛护栏（value + 时刻）：ack 权威成功后、state.config.mode
     * 增量到达的间隙内旧投影不回闪（真机实证忙 turn 期间增量可滞后分钟级）
     * （iOS ChatViewModel.lastLocalModeSwitch 同款口径）。
     */
    private val lastLocalModeSwitches = HashMap<String, Pair<String, Long>>()

    /**
     * 协作模式未知态回执（status 缺席/词表外）的切换目标值：state.config.mode 投影
     * 命中即定案并清失败行——落盘即与桌面真态脱钩且无读面时永不自愈
     * （2026-10-09「计划模式丢失」根因之一）（iOS ChatViewModel.pendingUnknownModeValue）。
     */
    private val pendingUnknownModeValues = HashMap<String, String>()

    /**
     * model-selection.getView 缓存（workspace 级；**空清单不入缓存**——缓存命中即
     * 短路重拉，空清单锁死会让重试面永远命中空缓存）。onDidChange 到达即失效重拉。
     */
    @Volatile
    private var modelSelectionCache: ModelSelectionInfo? = null

    /** 模型选择观察流（getView 成功非空才发布；onDidChange 失效后重拉发布）。 */
    private val modelSelectionState = MutableStateFlow<ModelSelectionInfo?>(null)

    /** model-selection.onDidChange 订阅句柄（事件常驻；Store 与连接同生命周期重建）。 */
    private var modelSelectionSubscription: EventSubscription? = null

    /** 归档/取消归档最近一次写失败原文（空串 = 无；失败完整回滚 + 原文上屏 §5-12）（iOS _lastArchiveFailureText）。 */
    @Volatile
    private var lastArchiveFailure = ""

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
    override suspend fun conversationWorkspaceTarget(sessionId: String): Pair<String, String?> =
        workspaceTarget(sessionId, null)

    /** 会话当前 revision/logEpoch 水位（步骤 2 CAS base 数据源；iOS :1998-2001）。 */
    override suspend fun conversationBaseWatermark(sessionId: String): Pair<Long, String> {
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

    /** 快照顶层扁平 state 键集（上游 conversationSnapshotSchema 扁平化；iOS :1298-1303 同集）。
     * v4.0.0 增补 `activitySummaryDismissed` / `completedActivityCount`（活动摘要）与
     * `workspaceMcpAdmission`（MCP 待审核提示条）——本集合是快照拷贝的白名单（见下方
     * hasState 循环），漏登记 = 全量快照静默丢键，表象「重连后活动摘要/提示条消失」
     * 【实证·上游仓 snapshot.ts:575-592】。 */
    private val snapshotStateKeys = setOf(
        "control", "availability", "inputRouting", "meta", "sharedContextImport",
        "config", "modelTransition", "usage", "queue", "pendingInteractions",
        "pendingCommands", "backgroundWorks", "subagents", "workflowRuns",
        "activitySummaryDismissed", "completedActivityCount", "workspaceMcpAdmission",
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
                // 面板键（goal/plan/backgroundWorks/subagents/queue/活动摘要）任一到达即广播面板刷新
                if (patch.containsKey("goal") || patch.containsKey("plan") ||
                    patch.containsKey("backgroundWorks") || patch.containsKey("subagents") ||
                    patch.containsKey("queue") ||
                    patch.containsKey("activitySummaryDismissed") ||
                    patch.containsKey("completedActivityCount")
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
        // 会话协作模式（state.config.mode 权威源；nil/空 = 桌面尚未投影，不落）。
        // v1.33 三态（上游 product-projection.ts:3785 投影 + ChatViewModel 护栏的 :store 承载）：
        // ① 本地切换护栏窗（3s）内旧投影不回闪（ack 已权威成功、config.mode 增量未达——
        //    忙 turn 期间增量可滞后分钟级，真机 14:08 实证）；
        // ② 未知态待定期（switchCollaborationMode 收到 status 缺席/词表外回执）首个投影
        //    即定案（桌面真态说话）并清失败行；
        // ③ 常规覆盖：投影值落表（桌面改回/工具路径 enterPlanMode/exitPlanMode 双路共用投影）。
        val mode = state.objectValue?.get("config")?.objectValue?.get("mode")?.stringValue
        if (!mode.isNullOrEmpty()) {
            val guard = lastLocalModeSwitches[conversationId]
            val inGuardWindow = guard != null && (nowMs() - guard.second) < MODE_SWITCH_GUARD_MS
            when {
                pendingUnknownModeValues.containsKey(conversationId) -> {
                    // 未知态定案：不管与请求值是否一致——桌面实际已受理与否由投影说话
                    pendingUnknownModeValues.remove(conversationId)
                    sessionCollaborationModes[conversationId] = mode
                    collaborationModeFailures.remove(conversationId)
                }
                inGuardWindow && guard!!.first != mode -> Unit // 护栏窗内旧投影：不回闪乐观值
                else -> sessionCollaborationModes[conversationId] = mode
            }
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
        // 快照/增量一到即落盘镜像（iOS persistWorkflowMirror :3424 同挂点；截尾 4 条）
        workflowMirrorStore?.save(conversationId, runs.takeLast(WORKFLOW_MIRROR_CAP))
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
        // 增量应用后同落镜像（iOS applyWorkflowRunUpdated :3414 同挂点）
        workflowMirrorStore?.save(conversationId, runs.takeLast(WORKFLOW_MIRROR_CAP))
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
        // 当前轮的 fileChanges（turnHeader 行投影，随该轮助手消息落到 UI）
        var currentTurnChanges: cn.biuz.android.protocol.TurnFileChanges? = null
        // 回合分组（web conversationTurnRenderUnits 口径）：turnHeader = 轮边界；
        // 同轮消息 shared turnIndex（UI 按此聚合渲染 / 折叠工作摘要）。
        var turnIndex = 0
        var turnId: String? = null
        fun emit(message: ChatMessage) {
            result.add(message.copy(turnIndex = turnIndex, turnId = turnId))
        }
        for (rowId in table.keys.sorted()) {
            val record = table[rowId] ?: continue
            val row = record.json.objectValue ?: continue
            val kind = row["kind"]?.stringValue ?: continue
            val rowTime = rowTimestamp(row)
            if (kind == "turnHeader") {
                currentTurnChanges = row["fileChanges"]?.objectValue?.let { ch ->
                    cn.biuz.android.protocol.TurnFileChanges(
                        files = ch["files"].longValue()?.toInt() ?: 0,
                        additions = ch["additions"].longValue()?.toInt() ?: 0,
                        deletions = ch["deletions"].longValue()?.toInt() ?: 0,
                        reverted = ch["state"]?.stringValue == "reverted",
                    )
                }
                pendingWorkflowCompletion?.let { pending ->
                    emit(
                        ChatMessage(
                            id = "row-${pending.first}-wfdone", role = MessageRole.agent, text = "",
                            rowKind = "workflowCompletion",
                            workflowCompletion = pending.second,
                            timestamp = rowTime,
                        ),
                    )
                }
                pendingWorkflowCompletion = null
                // 新回合开始（turnHeader 即轮首；行携带 turnId 时记下）
                turnIndex += 1
                turnId = row["turnId"]?.stringValue ?: turnId
            }
            when (kind) {
                "userInput" -> {
                    val text = row["text"]?.stringValue ?: ""
                    serverUserTexts.add(text)
                    val sourceCommandId = row["sourceCommandId"]?.stringValue
                    if (!sourceCommandId.isNullOrEmpty()) {
                        serverSourceCommandIds.add(sourceCommandId)
                    }
                    emit(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.user,
                            text = text,
                            timestamp = rowTime,
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
                    emit(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.agent,
                            text = row["text"]?.stringValue ?: "",
                            status = if (state == "streaming") MessageStatus.streaming else MessageStatus.done,
                            timestamp = rowTime,
                            entityId = rowEntityId(row),
                            rowKind = "assistantText",
                            turnChanges = currentTurnChanges,
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
                    emit(
                        ChatMessage(
                            id = messageId, role = MessageRole.agent,
                            text = "",
                            status = if (thinkingState == ThinkingState.streaming) {
                                MessageStatus.streaming
                            } else {
                                MessageStatus.done
                            },
                            timestamp = rowTime,
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
                        emit(
                            ChatMessage(
                                id = "row-$rowId", role = MessageRole.agent,
                                text = "",
                                status = MessageStatus.done,
                                timestamp = rowTime,
                                todos = todos,
                            ),
                        )
                    }
                }
                "toolCall" -> {
                    emit(toolCallRowMessage(row, rowId, "row-$rowId", includeRetryCursor = true, timestampMs = rowTime))
                }
                // v4 timelineMarker 行（rows.ts:441-449）：时间轴分隔标记。此前无此分支，
                // marker 行被**静默丢弃**——模型切换/上下文压缩/目标校验/fork 派生提示全不可见。
                "timelineMarker" -> {
                    parseTimelineMarker(row)?.let { marker ->
                        emit(
                            ChatMessage(
                                id = "row-$rowId", role = MessageRole.agent,
                                text = "",
                                status = MessageStatus.done,
                                timestamp = rowTime,
                                rowKind = "timelineMarker",
                                timelineMarker = marker,
                            ),
                        )
                    }
                }
                "subagent" -> {
                    val subagentType = row["subagentType"]?.stringValue ?: ""
                    val subagentStatus = row["status"]?.stringValue
                    val subagentSummary = row["summaryText"]?.stringValue
                    emit(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.agent,
                            text = "🤖 子智能体 · $subagentType：" + (subagentSummary ?: ""),
                            timestamp = rowTime,
                            subagent = SubagentSummary(
                                type = subagentType,
                                status = subagentStatus,
                                summary = subagentSummary,
                            ),
                        ),
                    )
                }
                "artifact" -> {
                    val name = row["displayName"]?.stringValue
                        ?: row["name"]?.stringValue ?: "产物"
                    val type = row["artifactType"]?.stringValue
                        ?: row["type"]?.stringValue ?: "file"
                    val bytes = jsonLongValue(row["byteSize"])
                        ?: jsonLongValue(row["size"])
                        ?: jsonLongValue(row["bytes"])
                    emit(
                        ChatMessage(
                            id = "row-$rowId", role = MessageRole.agent,
                            text = "📦 产物 · $name（$type）",
                            timestamp = rowTime,
                            attachments = extractAttachmentRefs(row),
                            artifact = ArtifactRowInfo(name = name, kind = type, bytes = bytes),
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
                    // hookInvocation 不进消息流（web 行渲染同样 return null，
                    // ConversationShareReadonlyTimeline.tsx:826-828；它只汇总进「本轮 Hook
                    // 详情」入口 ConversationHookDetailsAction——移动端无该面，遗留项）。
                    // 其余未知 kind 一并静默跳过：v4 新增行类型必须显式加分支，否则不渲染
                    // （timelineMarker 曾因此整类丢失，见本文件 timelineMarker 分支注释）。
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

    /**
     * 行时间戳宽容解析（timestamp/createdAt/at/ts；毫秒数或 ISO 双形态）。
     * 缺失/不可解析回落到达时刻（历史快照一次性到达时到达时刻≠真实时间，仅作兜底）。
     */
    fun rowTimestamp(row: Map<String, JSONValue>): Long {
        for (key in listOf("timestamp", "createdAt", "at", "ts")) {
            val value = row[key] ?: continue
            jsonLongValue(value)?.takeIf { it > 0 }?.let { return it }
            value.stringValue?.let { iso ->
                SessionSummaryParser.parseIsoMillis(iso)?.let { return it }
            }
        }
        return System.currentTimeMillis()
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
     * `timelineMarker` 行 → 时间轴分隔（`timelineMarkerPayloadSchema` union）
     * 【实证·上游仓 rows.ts:355-424】。marker 在 `row.marker` 子对象内。
     *
     * 未识别的 type 也返回非 null 对象（[TimelineMarkerInfo.isVisible] 为 false）——
     * 让解析层如实**看见**新类型（长度/计数不错位），渲染层按 web 同口径 `default: null` 跳过。
     */
    private fun parseTimelineMarker(row: Map<String, JSONValue>): TimelineMarkerInfo? {
        val marker = row["marker"]?.objectValue ?: return null
        val type = marker["type"]?.stringValue ?: return null
        return TimelineMarkerInfo(
            type = type,
            origin = marker["origin"]?.stringValue,
            status = marker["status"]?.stringValue ?: marker["outcome"]?.stringValue,
            fromProvider = marker["fromProvider"]?.stringValue,
            fromModel = marker["fromModel"]?.stringValue,
            toProvider = marker["toProvider"]?.stringValue,
            toModel = marker["toModel"]?.stringValue,
            iteration = marker["iteration"]?.intValue,
            parentSessionId = marker["parentSessionId"]?.stringValue,
            parentRowId = marker["parentRowId"]?.intValue,
        )
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
        timestampMs: Long = System.currentTimeMillis(),
    ): ChatMessage {
        val status = row["status"]?.stringValue ?: "running"
        val outputText = row["output"]?.objectValue?.get("text")?.stringValue
            ?: row["outputPreview"]?.objectValue?.get("text")?.stringValue
            ?: row["progress"]?.objectValue?.get("text")?.stringValue
        // G-020：workflow 启动类工具调用宽容取关联 runId（行 result/回执内 runId 键）
        val rawToolName = row["toolName"]?.stringValue ?: row["name"]?.stringValue
        val toolName = (rawToolName ?: "").lowercase()
        // 工具输入摘要/标题（web buildToolDisplayModel primary/secondary 数据源；
        // input 为对象时取 description/summary，工具卡副标题用）
        val inputObj = row["input"]?.objectValue
        val description = inputObj?.get("description")?.stringValue
            ?: inputObj?.get("summary")?.stringValue
        val title = row["title"]?.stringValue
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
            toolName = rawToolName,
            description = description,
            title = title,
        )
        return ChatMessage(
            id = id, role = MessageRole.agent,
            text = "",
            status = if (status == "running" || status == "inputStreaming" || status == "pendingApproval") {
                MessageStatus.streaming
            } else {
                MessageStatus.done
            },
            timestamp = timestampMs,
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
     * 3.17.2 输入预测（ghost hint）真实实现（iOS RemoteConversationStore.inputPrediction
     * :2924-2964 直译）。只读、无状态查询：请求 = 会话 target（A7 寻址）+ revision
     * （当前 state 缓存，参与服务端缓存键）+ draft（≤4000 截断；空省略 = 预测下一条
     * 完整输入）+ requestId（≤128，原样回声供 VM 丢过期）【实证·上游仓
     * docs/web-remote-control/mobile-input-prediction.md 线格式 +
     * zcode-protocol-v4/input-prediction/contract.ts strict schema】。
     * 手机侧超时 25s 略长于 CLI 20s（spec 手机端建议④——让服务端降级结果先到达）。
     * service 层失败统一回 unavailable 不抛错；RPC 错误（断连/超时）按 spec 失败语义
     * 「任何异常 = 无 hint」吞掉返回 null，不提示用户、不重试。
     */
    override suspend fun inputPrediction(conversationId: String, draft: String, requestId: String): InputPredictionResult? {
        if (!inputPredictionEnabled) return null
        val revision = mutex.withLock { conversationStateRevisions[conversationId] ?: 0L }
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, conversationId)
        // revision 32 位值域口径（RPCSerialization.kt:30-32：域外整数落 integral double）
        fields["revision"] = if (revision in Int.MIN_VALUE..Int.MAX_VALUE) {
            JSONValue.Int(revision.toInt())
        } else {
            JSONValue.Double(revision.toDouble())
        }
        val clampedDraft = draft.take(INPUT_PREDICTION_MAX_DRAFT_CHARS)
        if (clampedDraft.isNotEmpty()) {
            fields["draft"] = JSONValue.Str(clampedDraft)
        }
        fields["requestId"] = JSONValue.Str(requestId.take(INPUT_PREDICTION_MAX_REQUEST_ID_CHARS))
        return try {
            val result = transport.call(
                "zcode-agent", "conversationInputPredictionV4",
                RPCValue.json(JSONValue.Obj(fields)),
                timeout = 25.seconds,
            )
            parseInputPrediction(result.jsonValue, revision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 输入预测回执解析（contract.ts 回执宽容读面）：decision 必填（缺席/形状不识别
     * → null 无 hint）；candidates {text, confidence} 逐元素宽容；requestId 原样回声
     * ——过期丢弃（requestId 非最新 / 草稿已变）由 VM 承担（spec「手机端建议③」）。
     */
    fun parseInputPrediction(json: JSONValue?, revision: Long): InputPredictionResult? {
        val dict = json?.objectValue ?: return null
        val decision = dict["decision"]?.stringValue ?: return null
        val candidates = (dict["candidates"]?.arrayValue ?: emptyList()).mapNotNull { item ->
            val obj = item.objectValue ?: return@mapNotNull null
            val text = obj["text"]?.stringValue ?: return@mapNotNull null
            val confidence = obj["confidence"]?.doubleValue ?: return@mapNotNull null
            InputPredictionCandidate(text = text, confidence = confidence)
        }
        return InputPredictionResult(
            decision = decision,
            candidates = candidates,
            requestId = dict["requestId"]?.stringValue,
            revision = revision,
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

    // MARK: 桌面 workflow 运行进度（要求 5 / G-008 · 只读；不新增任何发送命令）
    //
    // 数据面（桌面开源 v3.14.3 权威 schema）：
    // 通路 B 主源——conversation state.workflowRuns（workflowRunsStateSchema {revision, runs[]}）：
    //   冷快照必带、state.updated 键级替换、专属增量 op 仅 workflowRun.updated/removed 两个
    //   （header 键整键替换 + actors/nodes 条目按 (siteId, ordinal) 整条替换，绝无字段级深合并）。
    // 旧兼容——workflowRun 单数键（上轮要求 5 实现）与 conversationWorkflowRunsV4 只读兜底。
    // resumable/truncated 等 CLI 算好字段只透传展示，绝不自推导（桌面基准纪律）。

    /**
     * 会话全部 workflow run（多 run 面板数据源；活 run 优先、同名取最新活 run、stale 仲裁降级）。
     * 数据源合并：通路 B 表（主）+ sessions-index activity 补表外 run（A' 摘要投影/事件重建）。
     * stale 仲裁（实测案例）：表内 run 可能滞留「运行中」而 sessions-index 已遗忘（被取代/
     * 清理）——activity 非空且不含该 id → 降级为 stopped，**取消不再打到死 workId**
     * （background_task_not_found；甲类活 run 仲裁红线）。多 run 场景手机端此前只显示第一个。
     * 排序（2026-10-06 用户反馈口径）：活优先；分区内 startedAt/updatedAt 降序（缺席回退
     * 表序倒序）；同名多活仅最新保持活态（supersede 滞留旧 run 不再主导显示）。
     * （iOS workflowRuns(in:) :3106-3206 直译）
     */
    // MARK: v4.0.0 会话面板读面（此前只有协议默认空实现——子代理 / 后台工作 / 目标
    // 三条链恒空，是「会话详情渲染与 web 不一致」的协议层根因；数据源 = 已缓存的
    // snapshotState 全量 state 投影，零新增订阅）

    /**
     * `state.subagents` → 会话级子代理投影（v4.0.0 `subagentProjectionStateSchema`）。
     * 携带 running（运行态）、ended（本批结束摘要）、endedTotal（历史目录总数）——
     * 活动摘要行据此算「已完成 N 项」。旧桌面（< v4.0.0）不发该键：返回 null（调用方
     * 按「无该投影」处理，不伪造 ended/总数）。
     */
    override suspend fun subagentProjection(conversationId: String): SubagentProjectionState? {
        val raw = mutex.withLock { snapshotState[conversationId]?.objectValue?.get("subagents") }
        val dict = raw?.objectValue ?: return null
        fun parseList(key: String) = dict[key]?.arrayValue.orEmpty().mapNotNull { item ->
            val d = item.objectValue ?: return@mapNotNull null
            val childId = d["childSessionId"]?.stringValue ?: return@mapNotNull null
            SubagentRuntimeSummary(
                childSessionId = childId,
                subagentType = d["subagentType"]?.stringValue ?: "",
                title = d["title"]?.stringValue ?: "",
                summary = d["summary"]?.stringValue,
                status = d["status"]?.stringValue ?: "",
                agentId = d["agentId"]?.stringValue,
                toolCallId = d["toolCallId"]?.stringValue,
                startedAt = d["startedAt"]?.let { SessionSummaryParser.parseIsoMillis(it.stringValue ?: "") }
                    ?: d["startedAt"]?.longValue(),
                endedAt = d["endedAt"]?.let { SessionSummaryParser.parseIsoMillis(it.stringValue ?: "") }
                    ?: d["endedAt"]?.longValue(),
            )
        }
        return SubagentProjectionState(
            revision = dict["revision"]?.longValue()?.toInt() ?: 0,
            childSessionIds = dict["childSessionIds"]?.arrayValue.orEmpty()
                .mapNotNull { it.stringValue },
            running = parseList("running"),
            endedTotal = dict["endedTotal"]?.longValue()?.toInt() ?: 0,
            ended = parseList("ended"),
        )
    }

    /** `state.backgroundWorks` → 后台工作清单（含 v4.0.0 `resultSummary`）。 */
    override suspend fun backgroundWorks(conversationId: String): List<BackgroundWorkSummary> {
        val raw = mutex.withLock { snapshotState[conversationId]?.objectValue?.get("backgroundWorks") }
        return raw?.arrayValue.orEmpty().mapNotNull { item ->
            val d = item.objectValue ?: return@mapNotNull null
            val workId = d["workId"]?.stringValue ?: return@mapNotNull null
            BackgroundWorkSummary(
                workId = workId,
                title = d["title"]?.stringValue,
                kind = d["kind"]?.stringValue,
                rawStatus = d["status"]?.stringValue,
                cancellable = d["cancellable"]?.boolValue ?: false,
                runId = d["workflowRunId"]?.stringValue,
                sessionId = d["childSessionId"]?.stringValue,
                startedAt = d["startedAt"]?.stringValue?.let(SessionSummaryParser::parseIsoMillis)
                    ?: d["startedAt"]?.longValue(),
                endedAt = d["endedAt"]?.stringValue?.let(SessionSummaryParser::parseIsoMillis)
                    ?: d["endedAt"]?.longValue(),
                blocked = d["blocked"]?.boolValue ?: false,
                resultSummary = d["resultSummary"]?.stringValue,
            )
        }
    }

    /** `state.activitySummaryDismissed` → 活动摘要忽略集（v4.0.0 新增四键之一）。 */
    override suspend fun activitySummaryDismissed(conversationId: String): ActivitySummaryDismissed? {
        val raw = mutex.withLock {
            snapshotState[conversationId]?.objectValue?.get("activitySummaryDismissed")
        }
        val d = raw?.objectValue ?: return null
        fun ids(key: String) = d[key]?.arrayValue.orEmpty().mapNotNull { it.stringValue }
        return ActivitySummaryDismissed(
            at = d["at"]?.stringValue?.let(SessionSummaryParser::parseIsoMillis) ?: d["at"]?.longValue(),
            workIds = ids("workIds"),
            subagentIds = ids("subagentIds"),
            workflowRunIds = ids("workflowRunIds"),
        )
    }

    /**
     * `state.completedActivityCount` → 完整历史里成功的独立 child 与 Workflow 数
     * （不含 Bash）。null = 字段缺席（旧桌面）或完整历史读取失败——两种都不显示计数。
     */
    override suspend fun completedActivityCount(conversationId: String): Int? =
        mutex.withLock {
            snapshotState[conversationId]?.objectValue?.get("completedActivityCount")?.longValue()?.toInt()
        }

    /**
     * `state.goal` → 目标卡投影（iOS :3890-3906 直译 + v4.0.0 schema 校正）。
     *
     * 权威形状 = `goalStateSchema`【实证·上游仓 snapshot.ts:471-502】：
     * `{objective, status(active|paused|verifying|verified|notSatisfied|failed),
     *   summaryTitle, timeUsedSeconds, activeRunStartedAtMs, iteration,
     *   summaryHidden?, verifications[], iterations[]}`。
     * 旧桌面宽容键族（text/description/content/prompt/goal）保留——v4 前的移动端已按此读。
     */
    override suspend fun goalSummary(conversationId: String): RemoteGoalSummary? {
        val raw = mutex.withLock { snapshotState[conversationId]?.objectValue?.get("goal") }
        if (raw == null || raw.isNull) return null
        val d = raw.objectValue ?: return null
        // objective 是 v4 权威键；其余为旧桌面宽容形态（顺序同 iOS :3898-3904）
        val text = d["objective"]?.stringValue
            ?: d["text"]?.stringValue
            ?: d["description"]?.stringValue
            ?: d["content"]?.stringValue
            ?: d["prompt"]?.stringValue
            ?: d["goal"]?.stringValue
        if (text.isNullOrEmpty()) return null
        val status = d["status"]?.stringValue
        // paused 双形态：显式 paused 键（旧桌面）或 status 词表 paused/pausing（v4 权威）
        val paused = d["paused"]?.boolValue ?: (status == "paused" || status == "pausing")
        return RemoteGoalSummary(text = text, rawStatus = status, isPaused = paused)
    }

    /**
     * `state.plan` → 计划卡投影（iOS :3908-3941 直译 + v4.0.0 schema 校正）。
     *
     * 权威形状 = `planStateSchema`【实证·上游仓 snapshot.ts:504-507】：
     * `{items:[{id, content, status: pending|inProgress|completed}], updatedAt}`。
     * 正文直取键（content/text/markdown/body）为旧桌面宽容形态；items 数组按 v4 三态
     * 逐行拼（✓ 完成 / ▸ 进行中 / · 待办）。
     */
    override suspend fun planPanel(conversationId: String): PlanPanelSummary? {
        val raw = mutex.withLock { snapshotState[conversationId]?.objectValue?.get("plan") }
        if (raw == null || raw.isNull) return null
        val d = raw.objectValue ?: return null
        var content = d["content"]?.stringValue
            ?: d["text"]?.stringValue
            ?: d["markdown"]?.stringValue
            ?: d["body"]?.stringValue
        if (content.isNullOrEmpty()) {
            val entries = d["items"]?.arrayValue
                ?: d["entries"]?.arrayValue
                ?: d["steps"]?.arrayValue
                ?: emptyList()
            val lines = entries.mapNotNull { entry ->
                val o = entry.objectValue
                if (o == null) {
                    entry.stringValue?.let { "· $it" }
                } else {
                    val title = o["content"]?.stringValue
                        ?: o["title"]?.stringValue
                        ?: o["text"]?.stringValue
                        ?: ""
                    // v4 三态闭集 pending|inProgress|completed + 旧桌面 done/in_progress
                    val statusWord = o["status"]?.stringValue
                    val done = statusWord == "completed" || statusWord == "done" ||
                        o["completed"]?.boolValue == true
                    val running = statusWord == "inProgress" || statusWord == "in_progress"
                    (if (done) "✓ " else if (running) "▸ " else "· ") + title
                }
            }
            content = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
        if (content.isNullOrEmpty()) return null
        return PlanPanelSummary(
            title = d["title"]?.stringValue ?: d["name"]?.stringValue,
            content = content,
            rawStatus = d["status"]?.stringValue,
        )
    }

    /**
     * `state.subagents.running` → 子代理清单（side 面板数据源；iOS :3962-3979 直译）。
     * v4.0.0 权威形状见 [subagentProjection]（`{running:[...], ended, endedTotal}`）；
     * 本方法只取运行态，裸数组/`all` 键为旧桌面宽容形态。
     */
    override suspend fun subagentSessions(conversationId: String): List<SubagentSessionSummary> {
        val raw = mutex.withLock { snapshotState[conversationId]?.objectValue?.get("subagents") }
        val array = raw?.objectValue?.get("running")?.arrayValue
            ?: raw?.objectValue?.get("all")?.arrayValue
            ?: raw?.arrayValue
            ?: emptyList()
        return array.mapNotNull { item ->
            val d = item.objectValue ?: return@mapNotNull null
            val childSessionId = d["childSessionId"]?.stringValue
                ?: d["sessionId"]?.stringValue
                ?: return@mapNotNull null
            SubagentSessionSummary(
                childSessionId = childSessionId,
                agentId = d["agentId"]?.stringValue,
                agentType = d["subagentType"]?.stringValue ?: d["agentType"]?.stringValue
                    ?: d["type"]?.stringValue,
                name = d["title"]?.stringValue ?: d["name"]?.stringValue
                    ?: d["agentName"]?.stringValue,
                rawStatus = d["status"]?.stringValue ?: "running",
            )
        }
    }

    override suspend fun workflowRuns(conversationId: String): List<WorkflowRunSummary> {
        // ⓪ 镜像水合：快照尚未到/本次缺席时，用上次落盘的 run 状态先渲染（有真数据即被覆盖）
        mutex.withLock {
            if (workflowRunTables[conversationId]?.runs.isNullOrEmpty()) {
                workflowMirrorStore?.load(conversationId)?.takeIf { it.isNotEmpty() }?.let { mirror ->
                    workflowRunTables[conversationId] = WorkflowRunTable(revision = 0L, runs = mirror)
                }
            }
        }
        val runs = mutex.withLock { workflowRunTables[conversationId]?.runs ?: emptyList() }
        val parsed = runs.mapNotNull { parseWorkflowRun(it) }
        var results: List<WorkflowRunSummary> = parsed
        val tableIds: Set<String> = parsed.map { it.id }.toSet()
        val tableSize: Int = runs.size
        val activity = parseWorkflowActivity(
            sessionsIndex?.sessionSummaries()?.get(conversationId)?.workflowActivity,
        )
        val activityIds = activity?.runs?.map { it.id }?.toSet() ?: emptySet()
        // 表外 run 补充：activity 有而表没有（新 run 尚未随 state 快照/增量到达）
        if (activity != null) {
            val firstLiveIndex = activity.runs.indexOfFirst { it.isLive }
            for ((index, run) in activity.runs.withIndex()) {
                if (tableIds.contains(run.id)) continue
                // 首个活 run 走事件重建（通路 C，完整节点/actors）；其余降级摘要投影
                if (index == firstLiveIndex && run.isLive && results.all { !it.isLive }) {
                    val rebuilt = rebuildWorkflowRunFromEvents(conversationId, runId = run.id)
                    if (rebuilt != null) {
                        results = results + rebuilt
                        continue
                    }
                }
                results = results + projectActivityRun(run)
            }
        }
        // stale 仲裁（函数头注）：activity 在场且非空时，表内滞留「运行中」但 activity
        // 已不含该 id 的 run 降级 stopped（取消命令不再打到死 workId）
        if (activity != null && activityIds.isNotEmpty()) {
            results = results.map { run ->
                if (run.isLive && !activityIds.contains(run.id)) run.copy(rawStatus = "stopped") else run
            }
        }
        // 同一 workflow 取最新活 run（用户真机反馈 2026-10-06：桌面 supersede 换 run 后
        // 旧 run 的 running 状态可能滞留，移动端面板被旧 run 的计划/进度主导）——
        // 同名多活时仅最新活 run 保持活态，其余降级 stopped（不再主导显示，取消命令
        // 也不再打到滞留活态的旧 workId）。「同一 workflow」以 name 相同为宽容近似
        // （runId 不同、同名；改名 supersede 不识别，仅回落到下方排序兜底）。
        var demotedIds: Set<String> = emptySet()
        val liveRuns = results.filter { it.isLive }
        if (liveRuns.size > 1) {
            val demoted = HashSet<String>()
            for ((_, group) in liveRuns.groupBy { it.name }) {
                if (group.size < 2) continue
                // 最新活 run：时间戳新者优先；时间戳同/缺席按表序靠后者（桌面追加新 run 在尾，
                // workflowRun(in:) runs?.last 同先例）
                val newest = group.maxWithOrNull(
                    compareBy({ recencyMs(it) }, { results.indexOf(it) }),
                ) ?: continue
                for (run in group) {
                    if (run.id != newest.id) demoted.add(run.id)
                }
            }
            if (demoted.isNotEmpty()) {
                demotedIds = demoted
                results = results.map { run ->
                    if (demotedIds.contains(run.id)) run.copy(rawStatus = "stopped") else run
                }
            }
        }
        // 活优先；分区内新 run 在前（startedAt/updatedAt 降序，缺席同分按表序倒序）——
        // 面板首卡即最新活 run，被替换旧 run 沉底为历史（UI 另有「历史」分界标注）
        return results
            .sortedWith(
                compareByDescending<WorkflowRunSummary> { it.isLive }
                    .thenByDescending { recencyMs(it) }
                    .thenByDescending { results.indexOf(it) },
            )
            .also { sorted ->
                diag(
                    "diag.wf.multi",
                    "multi 表=${tableIds.size}(raw$tableSize) activity=${activityIds.size} → " +
                        sorted.joinToString(", ") { "${it.id.take(14)}:${it.rawStatus}" },
                )
            }
    }

    /** run 排序新近度（updatedAt ?? startedAt ?? Long.MIN_VALUE；iOS recency :3157）。 */
    private fun recencyMs(run: WorkflowRunSummary): Long =
        run.updatedAt ?: run.startedAt ?: Long.MIN_VALUE

    /**
     * 会话主 workflow run（单 run 面板数据源；iOS workflowRun(in:) :3208-3280 直译）：
     * ① 多源列表命中 → 首个（活优先）即主 run；
     * ② 单数键旧兼容；
     * ②.5 触发表缺失全量 resync（一次性；快照 state.workflowRuns 间歇缺席自愈）+
     *     2.5s 后 nudge 一次面板事件让 observe 重查——置于 ③ 之前：RPC 抛错/空表/
     *     解析失败等一切后续路径都能被兜住；
     * ③ conversationWorkflowRunsV4 只读 RPC 兜底（一次，失败不重复）；回执宽容：
     *    顶层 {runs:[…]} / {result:{runs|workflowRun}} / 单 run 对象；G-019 历史 run
     *    （无实时 nodes/actors）→ 走 RunEvents 分页重建（通路 C）。
     */
    override suspend fun workflowRun(conversationId: String): WorkflowRunSummary? {
        // ① 多源列表命中 → 首个（活优先）即主 run
        workflowRuns(conversationId).firstOrNull()?.let { return it }
        // ② 单数键旧兼容
        mutex.withLock { workflowRunStates[conversationId] }?.let { cached ->
            return parseWorkflowRun(cached)
        }
        // ②.5 触发表缺失全量 resync（一次性；订阅在场才可 resync）
        val subId = mutex.withLock {
            val shouldTrigger = !workflowResyncTriggered.contains(conversationId) &&
                conversationSubscriptionIds[conversationId] != null
            if (shouldTrigger) workflowResyncTriggered.add(conversationId)
            conversationSubscriptionIds[conversationId]
        }
        if (subId != null) {
            diag("diag.wf.path", "触发表缺失全量 resync")
            scope.launch {
                forceFullResync(conversationId, subId)
                delay(NUDGE_DELAY_MS)
                // nudge：面板事件驱动 observe 侧重查（iOS nudgeWorkflowRefresh 的
                // AsyncStream yield 对应物——:store 无 UI 指针，以事件流承载）
                yieldToAll(ConversationEvent.PanelStateUpdated(conversationId))
            }
        }
        // ③ 只读 RPC 兜底（一次，失败不重复）
        val alreadyFetched = mutex.withLock {
            val hit = workflowRunFetched.contains(conversationId)
            if (!hit) workflowRunFetched.add(conversationId)
            hit
        }
        if (alreadyFetched) return null
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, conversationId)
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        val json: JSONValue? = try {
            transport.call(
                "zcode-agent", "conversationWorkflowRunsV4", RPCValue.json(JSONValue.Obj(fields)),
                timeout = HISTORY_FETCH_TIMEOUT,
            ).jsonValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag("diag.wf.rpc", "RPC 兜底调用失败")
            null
        }
        // 回执宽容：顶层 {runs:[…]} / {result:{runs|workflowRun}} / 单 run 对象
        val runs = json?.get("runs")?.arrayValue
            ?: json?.get("result")?.objectValue?.get("runs")?.arrayValue
            ?: json?.get("result")?.objectValue?.get("workflowRuns")?.objectValue?.get("runs")?.arrayValue
        val run = runs?.lastOrNull()
            ?: json?.get("result")?.objectValue?.get("workflowRun")
            ?: json?.get("workflowRun")
        // G-019：历史 run（无实时 nodes/actors）→ 走 RunEvents 分页重建（通路 C）
        if (run != null) {
            parseWorkflowRun(run)?.let { parsed ->
                mutex.withLock { workflowRunStates[conversationId] = run }
                return parsed
            }
            val runId = run.objectValue?.get("runId")?.stringValue
                ?: run.objectValue?.get("id")?.stringValue
            if (runId != null) {
                return rebuildWorkflowRunFromEvents(conversationId, runId)
            }
        }
        return null
    }

    /**
     * 通路 C：RunEvents 分页重建（G-019 历史 run；iOS rebuildWorkflowRunFromEvents
     * :4772-4862 直译）。conversationWorkflowRunEventsV4 分页拉取（≤2 轮、每页 500；
     * 下一页游标 = 末条事件 sequence——上游无 nextCursor 键），事件流重建阶段链/子代理
     * 实例/终态；truncated=true（重建投影非全量）、cancellable=false（死 run 不再可取消）。
     */
    private suspend fun rebuildWorkflowRunFromEvents(
        conversationId: String,
        runId: String,
    ): WorkflowRunSummary? {
        val events = ArrayList<JSONValue>()
        var afterSequence: Int? = null
        for (page_ in 0 until 2) {
            val fields = LinkedHashMap<String, JSONValue>()
            applySessionTarget(fields, conversationId)
            fields["runId"] = JSONValue.Str(runId)
            fields["limit"] = JSONValue.Int(500)
            afterSequence?.let { fields["afterSequence"] = JSONValue.Int(it) }
            val json: JSONValue? = try {
                transport.call(
                    "zcode-agent", "conversationWorkflowRunEventsV4",
                    RPCValue.json(JSONValue.Obj(fields)),
                ).jsonValue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: break
            val page = json?.get("events")?.arrayValue
                ?: json?.get("result")?.objectValue?.get("events")?.arrayValue
                ?: emptyList()
            events.addAll(page)
            // 下一页游标 = 末条事件 sequence（上游无 nextCursor 键）
            val hasMore = json?.get("hasMore")?.boolValue
                ?: json?.get("result")?.objectValue?.get("hasMore")?.boolValue
                ?: false
            if (!hasMore || page.isEmpty()) break
            val next = page.lastOrNull()?.objectValue?.get("sequence")?.intValue
            if (next == null) break
            afterSequence = next
        }
        if (events.isEmpty()) return null

        val stationNames = ArrayList<String>()
        val actors = ArrayList<WorkflowActorSummary>()
        var rawStatus: String? = null
        var stopReason: String? = null
        for (event in events) {
            val d = event.objectValue ?: continue
            val type = d["type"]?.stringValue ?: d["kind"]?.stringValue ?: continue
            val payload = d["payload"]?.objectValue ?: d["data"]?.objectValue ?: emptyMap()
            when {
                type.contains("phase") -> {
                    val name = payload["phase"]?.stringValue ?: payload["phaseName"]?.stringValue
                    if (!name.isNullOrEmpty() && !stationNames.contains(name)) {
                        stationNames.add(name)
                    }
                }
                type.contains("actor") -> {
                    val siteId = payload["siteId"]?.stringValue ?: continue
                    val ordinal = payload["ordinal"]?.intValue ?: continue
                    val actorId = "$siteId#$ordinal"
                    if (actors.none { it.id == actorId }) {
                        actors.add(
                            WorkflowActorSummary(
                                id = actorId,
                                name = payload["name"]?.stringValue,
                                rawStatus = payload["status"]?.stringValue ?: "waiting",
                                phaseName = payload["phaseName"]?.stringValue
                                    ?: payload["phase"]?.stringValue,
                                sessionId = payload["sessionId"]?.stringValue
                                    ?: payload["childSessionId"]?.stringValue,
                                tasksTotal = null,
                                tasksSettled = null,
                            ),
                        )
                    }
                }
                type.contains("run-settled") || type.contains("settled") -> {
                    payload["status"]?.stringValue?.let { rawStatus = it }
                    payload["stopReason"]?.stringValue?.let { stopReason = it }
                }
            }
        }
        if (stationNames.isEmpty() && actors.isEmpty()) return null
        val nodes = stationNames.mapIndexed { index, name ->
            WorkflowNodeSummary(id = "event-station-$index-$name", label = name, status = WorkflowStepStatus.done)
        }
        return WorkflowRunSummary(
            id = runId,
            name = "工作流",
            rawStatus = rawStatus ?: "completed",
            stopReason = stopReason,
            resumable = false,
            truncated = true,
            nodes = nodes,
            actors = actors,
            artifactsCount = 0,
            pendingQuestionsCount = 0,
            concurrency = null,
            concurrencyCeiling = null,
            subagentModel = null,
            cancellable = false,
        )
    }

    /**
     * 工作流产物内容分块读（conversationWorkflowRunArtifactReadV4【实证·上游仓
     * workflow-artifacts.ts:221-268 + zcodeAgentService.ts:5369-5389】：≤512KiB 一块、
     * 授权在 CLI 侧 journal 行、sessionId 必须是 run 的 parentSessionId——寻址沿用
     * applySessionTarget 会话归属 workspace 同族）。拼装至 nextOffset==null，总量随
     * 上游 ARTIFACT_CAPS.maxFileBytes=20MB 钳制（40 × 512KiB）；任一块失败回 null
     * （UI 层如实上屏）。（iOS workflowArtifactContent :1550-1592 直译）
     */
    override suspend fun workflowArtifactContent(
        sessionId: String,
        runId: String,
        artifactId: String,
        version: Int,
    ): ArtifactContent? {
        var offset = 0
        val data = ByteArrayOutputStream()
        var mediaType = "application/octet-stream"
        for (chunk_ in 0 until ARTIFACT_MAX_CHUNKS) {
            val fields = LinkedHashMap<String, JSONValue>()
            applySessionTarget(fields, sessionId)
            fields["runId"] = JSONValue.Str(runId)
            fields["artifactId"] = JSONValue.Str(artifactId)
            fields["version"] = JSONValue.Int(version)
            fields["offset"] = JSONValue.Int(offset)
            fields["limit"] = JSONValue.Int(ARTIFACT_CHUNK_BYTES)
            val json: JSONValue? = try {
                transport.call(
                    "zcode-agent", "conversationWorkflowRunArtifactReadV4",
                    RPCValue.json(JSONValue.Obj(fields)),
                ).jsonValue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            // 回执宽容：顶层 / {result:{…}} 双形态（store 既有读面同口径）
            val dict = if (json?.objectValue?.containsKey("dataBase64") == true) {
                json.objectValue
            } else {
                json?.get("result")?.objectValue
            }
            val b64 = dict?.get("dataBase64")?.stringValue ?: return null
            val chunk: ByteArray = try {
                java.util.Base64.getMimeDecoder().decode(b64)
            } catch (e: Exception) {
                return null
            }
            dict["mediaType"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { mediaType = it }
            data.write(chunk)
            val next = dict["nextOffset"]?.intValue ?: break
            offset = next
        }
        return if (data.size() == 0) null else ArtifactContent(data.toByteArray(), mediaType)
    }

    /**
     * tasks-index membership（pin/archive 组织态权威源）逐 scope **并发**拉取
     * 【实证·上游仓 taskListMembershipSets.ts：`e.scopes.map(...)` 并发 + 单区失败跳过】；
     * 完成后写 sessionsIndex.pinnedTaskIds/archivedTaskIds 供三级合成 join（A15：
     * sessions-index 行不含 pinned/archived 字段——schema 冻结，组织态只此一源）。
     * scope 清单与归档拉取同源（连接层采集 ∪ bootstrap 派生 ∪ 当前工作区保底）。
     * （iOS refreshTaskMembership :322-360 直译）
     */
    suspend fun refreshTaskMembership() {
        val index = sessionsIndex ?: return
        val scopes = membershipScopes(allWorkspaces, bootstrapWorkspaces, workspace)
        val pinned = HashSet<String>()
        val archived = HashSet<String>()
        coroutineScope {
            val deferred = scopes.map { scope ->
                async {
                    val method = if (scope.third) "listPinnedTasks" else "listArchivedTasks"
                    val fields = LinkedHashMap<String, JSONValue>()
                    fields["workspacePath"] = JSONValue.Str(scope.first)
                    val identity = scope.second
                    if (!identity.isNullOrEmpty()) {
                        fields["workspaceIdentity"] = JSONValue.Str(identity)
                    }
                    try {
                        val result = transport.call(
                            "zcode-task", method, RPCValue.json(JSONValue.Obj(fields)),
                        )
                        val json = result.jsonValue
                        val items = json?.get("items")?.arrayValue
                            ?: json?.get("tasks")?.arrayValue
                            ?: json?.arrayValue
                            ?: emptyList()
                        val ids = items.mapNotNull {
                            it.objectValue?.get("taskId")?.stringValue
                                ?: it.objectValue?.get("sessionId")?.stringValue
                        }
                        scope.third to ids
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        scope.third to emptyList<String>() // 单区失败跳过（web 同构）
                    }
                }
            }
            for ((isPinned, ids) in deferred.awaitAll()) {
                if (isPinned) pinned.addAll(ids) else archived.addAll(ids)
            }
        }
        index.pinnedTaskIds = pinned
        index.archivedTaskIds = archived
    }

    /**
     * membership 30s 节流后台刷新（iOS sortedConversations :302-308 同挂点）：列表读面
     * 触发、不阻塞首屏（Task 派发同构——scope.launch），完成后回推 ConversationsReplaced。
     */
    private suspend fun maybeRefreshMembership() {
        if (sessionsIndex == null) return
        val now = System.currentTimeMillis()
        val last = membershipRefreshedAt
        if (last != null && now - last < MEMBERSHIP_REFRESH_INTERVAL_MS) return
        membershipRefreshedAt = now
        scope.launch {
            try {
                refreshTaskMembership()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch // 单区失败/瞬断：membership 保持上次值，下个节流窗再对账
            }
            yieldConversationsReplaced()
        }
    }

    /**
     * 项目归属元数据刷新（120s 节流后台 / 失败 15s 短退避）：`zcode-task.listTaskList`
     * 合并 task-index 行，回填 sessions-index 行缺失的 workspacePath 与标题
     * （iOS mergeGlobalTaskIndex :501-599 同构；列表「项目」分组的真实数据源——
     * sessions-index 行 schema 只有 workspaceId）。后台执行不阻塞首屏；合并后回推
     * 列表替换事件。
     *
     * **范围两路（2026-11-23 真机「项目只有一个命名空间」根修，对照上游仓取证）**：
     * ① **host-scope 单调用优先**（web 现行全局清单源）：`workspaceScopes: "host"`
     *    （`zcodeTaskListTypes.ts:15` "host 由存储 owner 确定全部范围"；
     *    `useGlobalTaskList.ts:88-90` / `windowControllerTaskListRegistry.ts:268` 同值直通；
     *    `taskIndexRepo.queryTaskList:1337` 按 host_id 一把查全库）。桌面 3.17.1 起支持
     *    （git show a60ba6a7 已含 "host"；3.16.2 同位代码无 →
     *    `normalizeWorkspaceKeys(string)` 对字符串 .map 抛错 → 调用失败走回退）。
     *    **kind 三查（2026-11-24「置顶没有处理」根修）**：主清单 kind="timeline"
     *    （SQL 排除 pinned/archived——web ProjectTaskRows kind 同源）；置顶/归档各有
     *    host 专查 kind="pinned"/"archived"（SQL 列过滤，web ProjectTaskRows kind=pinned
     *    同构）→ id 集落 [RemoteSessionsIndexStore.hostPinnedTaskIds/hostArchivedTaskIds]
     *    （投影与 membership 并集）。**为什么必须专查**：上游 zcodeTaskMetaSchema
     *    无 pinned/archived 字段（validation.ts:2056；sqlite 列也不投影进 wire item），
     *    task-index 行无法自带组织态，membership（listPinnedTasks）枚举源又只有当前
     *    tabs——跨项目置顶只有 host 专查能到。
     * ② **逐工作区聚合回退**（旧桌面）：为什么不能只靠 ①？因为回退的枚举源各自塌缩——
     *    workspace-list-response 只含桌面窗口当前打开的 tabs
     *    （`rootPlatformWorkspaceSync.ts:75-95` → `syncAvailableWorkspaces`，
     *    desktop main index.ts:2584 直通；**不是全部项目目录**），bootstrap-response 的
     *    `tasks` 字段在 3.15.1+ 已不存在（`buildBootstrapResult` webRemoteControlManager.ts:1040
     *    全字段无 tasks；git -S 全历史无此字段）——枚举源只剩当前工作区 = 真机只见
     *    单一项目节点的根因。老桌面（≤3.16，无 host-scope）依赖此路 + bootstrap.tasks
     *    （3.14- 时代实证存在）双源兜底；置顶/归档仍走既有 membership 逐区拉取
     *    （窄枚举，与本轮前一致，不倒退）。单区失败跳过（web taskListMembershipSets 同口径）。
     *
     * 两路的行合并为**同一批**（批内去重见 [SessionMergeTable.mergeTaskIndexRows]）。
     */
    private suspend fun maybeRefreshProjectMetadata() {
        val index = sessionsIndex ?: return
        val now = nowMs()
        val last = projectMetadataRefreshedAt
        val interval =
            if (projectMetadataLastAttemptFailed) PROJECT_METADATA_RETRY_INTERVAL_MS
            else PROJECT_METADATA_REFRESH_INTERVAL_MS
        if (last != null && now - last < interval) return
        projectMetadataRefreshedAt = now
        scope.launch {
            val items = ArrayList<JSONValue>()
            val hostPinnedIds = ArrayList<String>()
            val hostArchivedIds = ArrayList<String>()
            var hostScopeUsable = false
            // ① host-scope 主清单（kind="timeline"：排除 pinned/archived，归专查——
            //    归档行若混进主批且行无组织态字段，主列表会漏过滤）
            if (fetchHostScopeRows("timeline", items)) {
                hostScopeUsable = true
                // ② 置顶专查：行合并（归属/状态）+ id 集落 hostPinnedTaskIds
                val pinnedRows = ArrayList<JSONValue>()
                if (fetchHostScopeRows("pinned", pinnedRows)) {
                    items.addAll(pinnedRows)
                    for (row in pinnedRows) {
                        row.objectValue?.get("taskId")?.stringValue?.let { hostPinnedIds.add(it) }
                    }
                }
                // ③ 归档专查：id 集落 hostArchivedTaskIds（主列表过滤 + 归档区读面）
                val archivedRows = ArrayList<JSONValue>()
                if (fetchHostScopeRows("archived", archivedRows)) {
                    items.addAll(archivedRows)
                    for (row in archivedRows) {
                        row.objectValue?.get("taskId")?.stringValue?.let { hostArchivedIds.add(it) }
                    }
                }
            }
            if (!hostScopeUsable) {
                // ② 逐工作区聚合回退（每区一次单 scope 查询；老桌面无 host-scope）
                val scopes = projectScopes(allWorkspaces, bootstrapWorkspaces, workspace)
                for ((path, identity) in scopes) {
                    val scopeObj = LinkedHashMap<String, JSONValue>()
                    scopeObj["workspacePath"] = JSONValue.Str(path)
                    if (!identity.isNullOrEmpty()) {
                        scopeObj["workspaceIdentity"] = JSONValue.Str(identity)
                    }
                    val fields = LinkedHashMap<String, JSONValue>()
                    fields["kind"] = JSONValue.Str("timeline")
                    fields["workspaceScopes"] = JSONValue.Arr(listOf(JSONValue.Obj(scopeObj)))
                    fields["sortBy"] = JSONValue.Str("updated")
                    fields["limit"] = JSONValue.Int(200)
                    try {
                        val result = transport.call(
                            "zcode-task", "listTaskList", RPCValue.json(JSONValue.Obj(fields)),
                        )
                        result.jsonValue?.get("items")?.arrayValue?.let { items.addAll(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单区失败跳过（web taskListMembershipSets 同口径）
                    }
                }
            }
            // host 组织态专查结果落位（并集源；membership 各自替换互不清除）
            if (hostScopeUsable) {
                index.hostPinnedTaskIds = hostPinnedIds.toSet()
                index.hostArchivedTaskIds = hostArchivedIds.toSet()
            }
            // 失败短退避记账（连接建立期/stale 重连期首战全空 → 15s 后重试，
            // 成功合批或拿到任意行才算成功进 120s 长窗；KDoc 详述）
            projectMetadataLastAttemptFailed = items.isEmpty()
            if (items.isEmpty()) return@launch
            try {
                val (merged, backfilled) = index.mergeTaskIndexRows(items)
                diag(
                    "diag.tasks.merge",
                    "host=${if (hostScopeUsable) "ok" else "fallback"} " +
                        "rows=${items.size} merged=$merged backfilled=$backfilled " +
                        "pinned=${hostPinnedIds.size} archived=${hostArchivedIds.size}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            yieldConversationsReplaced()
        }
    }

    /**
     * host-scope 单查（workspaceScopes:"host" 直传字符串；无 limit=全量，
     * web useProjectTree.ts:364 limit:0 同款——normalizeLimit(0)=null=无 LIMIT）。
     * @return 是否拿到非空行（主清单判定 host 链路可用性；专查空=无置顶/归档，也属可用）
     */
    private suspend fun fetchHostScopeRows(kind: String, out: ArrayList<JSONValue>): Boolean {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["kind"] = JSONValue.Str(kind)
        fields["workspaceScopes"] = JSONValue.Str("host")
        fields["sortBy"] = JSONValue.Str("updated")
        return try {
            val result = transport.call(
                "zcode-task", "listTaskList", RPCValue.json(JSONValue.Obj(fields)),
            )
            val rows = result.jsonValue?.get("items")?.arrayValue
            if (!rows.isNullOrEmpty()) {
                out.addAll(rows)
                true
            } else {
                // 空回执 = 桌面接受 host 语义但无该 kind 行（pinned/archived 专查常态）
                kind != "timeline"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }


    // MARK: 会话列表读面（iOS sessions 表在 :store 由 RemoteSessionsIndexStore 承载——委托直读）

    override suspend fun conversations(): List<Conversation> {
        // 发送后元数据兜底刷新前置（iOS refreshDueSessionMeta :2339-2341 同挂点）
        refreshDueSessionMeta()
        // membership 30s 节流后台刷新（iOS sortedConversations :302-308 同挂点：
        // RPC 突发与刚打开会话的订阅/帧处理抢同一 actor，进详情审批卡晚到——后台化不阻塞首屏）
        maybeRefreshMembership()
        // 项目归属元数据 120s 节流后台刷新（列表按项目分组的数据源；iOS mergeGlobalTaskIndex 同挂点）
        maybeRefreshProjectMetadata()
        return sessionsIndex?.conversations() ?: emptyList()
    }

    /**
     * 归档区读面（019）：先刷新 membership 权威组织态（listArchivedTasks 逐 scope 并发），
     * 再从合并表取归档行。此前接口只有默认空实现 → 归档页恒空（2026-11-09 实测发现）。
     */
    override suspend fun archivedConversations(): List<Conversation> {
        val index = sessionsIndex ?: return emptyList()
        runCatching { refreshTaskMembership() }
        return index.archivedConversations()
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
            var selectionPayload: LinkedHashMap<String, JSONValue>? = null
            if (modelSelection != null) {
                // providerId/modelId trim 非空（上游 providerId min(1)【实证·上游仓 command.ts:211
                // + createSession strict schema】——空串/空白必被桌面拒，本地拦截禁裸发 §5-12）
                val providerId = modelSelection.providerId.trim()
                val modelId = modelSelection.modelId.trim()
                if (providerId.isEmpty() || modelId.isEmpty()) {
                    lastCreateFailure = "模型选择不完整（provider/model 为空）· 请重新选择模型后再创建"
                    return Conversation(
                        id = "",
                        title = displayTitle,
                        summary = "",
                        directory = directory,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                selectionPayload = LinkedHashMap<String, JSONValue>()
                selectionPayload["providerId"] = JSONValue.Str(providerId)
                selectionPayload["modelId"] = JSONValue.Str(modelId)
                val reasoningLevel = modelSelection.reasoningLevel.trim()
                if (reasoningLevel.isNotEmpty()) {
                    selectionPayload["options"] = JSONValue.Obj(
                        mapOf("reasoningLevel" to JSONValue.Str(reasoningLevel)),
                    )
                }
            }
            val firstInput = LinkedHashMap<String, JSONValue>()
            firstInput["text"] = JSONValue.Str(trimmed)
            if (selectionPayload != null) {
                // 形同 web U7e：{providerId, modelId, options:{reasoningLevel}}，档位缺席略去 options
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

    // MARK: v4 命令扩容族（iOS :3848-4238/:4453-4501 直译；写命令唯一出口 = sendCommand）
    //
    // 边界口径：全部「客户端发命令、桌面代执行」。CAS 词表成员（CommandSchemas.
    // commandsRequiringBaseRevision【实证·上游仓 command.ts:296-311】）统一走
    // [sendCASWithRetry]（三段 stale 自愈内建，调用点漏接成为不可能）；非 CAS 成员
    // 普通信封直发。payload 形状除注明【实证】外沿用 iOS 同键构造（宽容面）。

    /**
     * CAS 命令发送 + stale 三段收敛自愈（官方 sendHostCasCommandV4 探测-收敛循环镜像；
     * iOS :3879-3901 直译）。**前置公共步已内建**（2026-10-07 重构下沉）：revision 缺失
     * 先 resync+轮询——pauseGoal/retryTurn 曾漏接导致冷启动首击必撞本地拒发门（P1③）。
     * commandId 全程不变（A12 幂等：上游同 (sessionId, commandId) 重试回放 duplicate，
     * 不构成双写）。
     *
     * 三段（探针实证 + 用户报障「胶囊切换都不行」活跃会话兜底）：
     * ① 首发必撞 stale（活跃会话 revision 持续前进，diag.wf.control.ui 实证
     *    revisionAtDecision=12119）→ stale 回执自带最新 revisionAtDecision（sendCommand
     *    已回填）→ ② 原样重发一次即命中 → ③ 重发仍 stale（重发窗口内 revision 又前进）
     *    → 清 revision 缓存 + 全量 resync 取权威 revision + 轮询等待后末次重发。
     */
    private suspend fun sendCASWithRetry(
        type: String,
        sessionId: String,
        payload: JSONValue,
    ): JSONValue? {
        ensureStateRevision(sessionId)
        val commandId = java.util.UUID.randomUUID().toString()
        var ack = sendCommand(type, sessionId, payload, casRevision = true, commandId = commandId)
        if (CommandAck(ack).status != "stale") return ack
        // ② 原样重发（同 commandId，A12；第一段 stale 的 revisionAtDecision 已回填 baseRevision）
        ack = sendCommand(type, sessionId, payload, casRevision = true, commandId = commandId)
        if (CommandAck(ack).status != "stale") return ack
        // ③ 末段自愈：清缓存 → resync 取权威 revision → 轮询就绪 → 末次重发（同 commandId）。
        // 直发 forceFullResyncForPending（本 store triggerResync 无 iOS 5s 节流——节流会把
        // 连续 CAS 场景的 ensureStateRevision 吞成纯等待，revision 永远等不到；iOS 原文
        // ensureStateRevision 注释同口径，Android 版无节流、行为强于 iOS）
        mutex.withLock { conversationStateRevisions.remove(sessionId) }
        triggerResync(sessionId)
        ensureStateRevision(sessionId)
        return sendCommand(type, sessionId, payload, casRevision = true, commandId = commandId)
    }

    /**
     * CAS 前置公共步（iOS :3910-3923 直译）：revision 缺失（冷启动刚进会话/快照未带
     * state）先 resync 拉带 state 的快照并轮询等待（帧异步，固定 0.9s 曾不够）。
     * 探针实证：队列命令缺此步时冷启动首击必被拒（"CAS commands require
     * baseRevision and baseLogEpoch"）。等待中段（第 5 轮）再补发一次 resync
     * （首包快照与 resync 帧竞态的双保险）。
     */
    private suspend fun ensureStateRevision(conversationId: String) {
        if (mutex.withLock { conversationStateRevisions[conversationId] != null }) return
        mutex.withLock { conversationSubscriptionIds[conversationId] }?.let { subId ->
            forceFullResync(conversationId, subId)
        }
        for (round in 0 until 10) {
            if (mutex.withLock { conversationStateRevisions[conversationId] != null }) break
            delay(STATE_REVISION_POLL_MS)
            if (round == 4 && mutex.withLock { conversationStateRevisions[conversationId] == null }) {
                mutex.withLock { conversationSubscriptionIds[conversationId] }?.let { subId ->
                    forceFullResync(conversationId, subId)
                }
            }
        }
    }

    // MARK: 排队消息（桌面 composer pending 队列；state.queue 投影 + 队列命令面）

    /**
     * 排队队列只读投影（state.queue：{items:[{queueItemId, text, delivery{admitted}}],
     * autoDrain}；nil = 无排队）（iOS :3851-3869 直译；条目 id/text 宽容键组同序）。
     */
    override suspend fun queueInfo(conversationId: String): ConversationQueueInfo? {
        val queue = mutex.withLock { snapshotState[conversationId]?.objectValue?.get("queue") }
            ?: return null
        if (queue.isNull) return null
        val d = queue.objectValue ?: return null
        val items = (d["items"]?.arrayValue ?: emptyList()).mapNotNull { item ->
            val o = item.objectValue ?: return@mapNotNull null
            val id = o["queueItemId"]?.stringValue
                ?: o["itemId"]?.stringValue
                ?: o["id"]?.stringValue
                ?: return@mapNotNull null
            val text = o["text"]?.stringValue
                ?: o["inputText"]?.stringValue
                ?: o["newText"]?.stringValue
                ?: ""
            val admitted = o["delivery"]?.objectValue?.get("admitted")?.stringValue
            RemoteQueueItem(id = id, text = text, admitted = admitted)
        }
        if (items.isEmpty()) return null
        return ConversationQueueInfo(items = items, autoDrain = d["autoDrain"]?.boolValue ?: true)
    }

    /** 队列条目立即发送（CAS 类，携 state revision）（iOS :3927-3932 直译）。 */
    override suspend fun sendQueuedNow(conversationId: String, queueItemId: String): JSONValue? =
        sendCASWithRetry(
            "sendQueuedNow",
            sessionId = conversationId,
            payload = JSONValue.Obj(mapOf("queueItemId" to JSONValue.Str(queueItemId))),
        )

    /** 队列条目文本编辑（CAS 类——桌面四件队列命令全部携 revision，缺一被拒）（iOS :3936-3941）。 */
    override suspend fun editQueueItem(
        conversationId: String,
        queueItemId: String,
        newText: String,
    ): JSONValue? = sendCASWithRetry(
        "editQueueItem",
        sessionId = conversationId,
        payload = JSONValue.Obj(
            mapOf(
                "queueItemId" to JSONValue.Str(queueItemId),
                "newText" to JSONValue.Str(newText),
            ),
        ),
    )

    /** 队列条目删除（CAS 类）（iOS :3945-3950）。 */
    override suspend fun deleteQueueItem(conversationId: String, queueItemId: String): JSONValue? =
        sendCASWithRetry(
            "deleteQueueItem",
            sessionId = conversationId,
            payload = JSONValue.Obj(mapOf("queueItemId" to JSONValue.Str(queueItemId))),
        )

    /**
     * 队列条目重排（beforeQueueItemId=null = 移到队尾；CAS 类——置顶报
     * "CAS commands require baseRevision" 的根因就是漏携）（iOS :3955-3963；
     * null 恒以 JSON null 键在场——上游 strip 语义下两形态等价，与 iOS wire 同形）。
     */
    override suspend fun reorderQueueItem(
        conversationId: String,
        queueItemId: String,
        beforeQueueItemId: String?,
    ): JSONValue? = sendCASWithRetry(
        "reorderQueueItem",
        sessionId = conversationId,
        payload = JSONValue.Obj(
            mapOf(
                "queueItemId" to JSONValue.Str(queueItemId),
                "beforeQueueItemId" to (beforeQueueItemId?.let { JSONValue.Str(it) } ?: JSONValue.Null),
            ),
        ),
    )

    /** 自动排空开关（CAS 类）（iOS :3967-3972）。 */
    override suspend fun setAutoDrain(conversationId: String, enabled: Boolean): JSONValue? =
        sendCASWithRetry(
            "setAutoDrain",
            sessionId = conversationId,
            payload = JSONValue.Obj(mapOf("autoDrain" to JSONValue.Bool(enabled))),
        )

    /** 目标暂停/继续（pauseGoal/resumeGoal；CAS 类，payload {}）（iOS :3811-3815 直译）。 */
    override suspend fun setGoalPaused(paused: Boolean, conversationId: String): JSONValue? =
        sendCASWithRetry(
            if (paused) "pauseGoal" else "resumeGoal",
            sessionId = conversationId,
            payload = JSONValue.Obj(emptyMap()),
        )

    /**
     * G-015：失败 turn 重试——携行元数据精确游标 {rowId, entityId} 下发 retryTurn
     * （CAS + row-target 双类成员）。entityId 缺失不下发：合成 rejected 回执走调用方
     * 失败呈现链（iOS 原文返回 nil 静默——Android 按 §5-12 写面禁静默改为合成拒收，
     * 不虚构「已重试」也不无声丢弃）。
     */
    override suspend fun retryTurn(conversationId: String, rowId: Int, entityId: String?): JSONValue? {
        val cursor = entityId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ConversationCommandFactory.Rejection(
                reasonCode = "client.rowCursorUnavailable",
                message = "该消息缺少重试游标（entityId），无法精确重试",
            ).syntheticAck
        return sendCASWithRetry(
            "retryTurn",
            sessionId = conversationId,
            payload = JSONValue.Obj(
                mapOf(
                    "target" to JSONValue.Obj(
                        mapOf(
                            "rowId" to JSONValue.Int(rowId),
                            "entityId" to JSONValue.Str(cursor),
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * 助手消息轻反馈（点赞/点踩反馈行）。row-target + CAS 双类成员（§7.2:414）。
     * 游标照 retryTurn 先例 {target:{rowId, entityId}}；value 三态：true=赞 / false=踩 /
     * null=取消（置 null）。A-1 修正（web 实证 bundle）：feedback 值域 `like|dislike`
     * （可 null）——原 positive/negative 是「未取证」候选被当事实实现，zod
     * invalid_enum_value 全拒。entityId 缺失合成拒收（retryTurn 同纪律）。
     */
    override suspend fun setAssistantFeedback(
        conversationId: String,
        rowId: Int,
        entityId: String?,
        value: Boolean?,
    ): JSONValue? {
        val cursor = entityId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ConversationCommandFactory.Rejection(
                reasonCode = "client.rowCursorUnavailable",
                message = "该消息缺少反馈游标（entityId），无法下发反馈",
            ).syntheticAck
        val payload = LinkedHashMap<String, JSONValue>()
        payload["target"] = JSONValue.Obj(
            mapOf(
                "rowId" to JSONValue.Int(rowId),
                "entityId" to JSONValue.Str(cursor),
            ),
        )
        payload["feedback"] = value?.let { JSONValue.Str(if (it) "like" else "dislike") } ?: JSONValue.Null
        return sendCASWithRetry("setAssistantFeedback", sessionId = conversationId, payload = JSONValue.Obj(payload))
    }

    /**
     * 编辑用户消息并重发（「编辑重发」sheet；§7.2:404「rewind 后以新文本
     * 重发，与 retryTurn 同族」——CAS + row-target 双类）。新文本键名取同族实证先例
     * editQueueItem 的 newText。C-2 修正：显式携 workspaceMode:"preserve"（web 实证
     * bundle——preserve|rewind 枚举、默认 preserve，web 调用点恒带该键）。
     * entityId 缺失合成拒收（retryTurn 同纪律）。
     */
    override suspend fun editUserQuery(
        conversationId: String,
        rowId: Int,
        entityId: String?,
        newText: String,
    ): JSONValue? {
        val cursor = entityId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ConversationCommandFactory.Rejection(
                reasonCode = "client.rowCursorUnavailable",
                message = "该消息缺少编辑游标（entityId），无法编辑重发",
            ).syntheticAck
        return sendCASWithRetry(
            "editUserQuery",
            sessionId = conversationId,
            payload = JSONValue.Obj(
                mapOf(
                    "target" to JSONValue.Obj(
                        mapOf(
                            "rowId" to JSONValue.Int(rowId),
                            "entityId" to JSONValue.Str(cursor),
                        ),
                    ),
                    "newText" to JSONValue.Str(newText),
                    "workspaceMode" to JSONValue.Str("preserve"),
                ),
            ),
        )
    }

    // MARK: 主模型/思考强度切换（iOS :4453-4468 + :5396-5578 直译；v1.27/36 复合键纪律）

    /**
     * 主模型/思考强度切换（switchModelConfig {provider, model, thought}；web 端模型
     * 选择器同构造，CAS 类命令携当前 state revision）。thought 缺席传空串（schema 必填
     * 字符串）。**provider 空/空白本地拦截**（上游 provider 必填【实证·上游仓
     * command.ts:211】——空串裸发必被桌面拒，换回 3s 闪过的拒因、用户观感「选了没反应」，
     * 真机报障 2026-10-09）：合成 rejected 回执走调用方失败呈现链，不空耗注定被拒的写。
     * model 恒携纯模型名（复合键解析由 [switchModelForRowKey] 承担后传入）。
     */
    override suspend fun switchModelConfig(
        conversationId: String,
        provider: String,
        model: String,
        thought: String?,
    ): JSONValue? {
        val trimmedProvider = provider.trim()
        if (trimmedProvider.isEmpty()) {
            return ConversationCommandFactory.Rejection(
                reasonCode = "client.providerUnavailable",
                message = "模型提供商信息未就绪 · 重新获取模型列表后再切换",
            ).syntheticAck
        }
        return sendCASWithRetry(
            "switchModelConfig",
            sessionId = conversationId,
            payload = JSONValue.Obj(
                mapOf(
                    "provider" to JSONValue.Str(trimmedProvider),
                    "model" to JSONValue.Str(model),
                    "thought" to JSONValue.Str(thought ?: ""),
                ),
            ),
        )
    }

    /**
     * 模型面板行键切换门（v1.36 全纪律组合；iOS ChatViewModel.switchModel 的 :store 承载）：
     * ① 行键「套餐|模型」复合解析（[ModelSelectionInfo.providerForRowKey]——复合表权威、
     *    裸名回退 activeProviderId/modelProviders）；
     * ② getView 失败/空表时同模型切换以会话级 state.modelSelection.providerId 兜底
     *    （v1.36①：provider 解析空串裸发必被桌面拒；state.providerId 是权威源）；
     * ③ 仍解析不出 → 本地拦截（合成 rejected client.providerUnavailable，禁空串裸发）；
     * ④ 下发 model 恒纯名（[ModelSelectionInfo.modelLabelForRowKey]，套餐前缀只用于本地解析）。
     */
    suspend fun switchModelForRowKey(
        conversationId: String,
        rowKey: String,
        thoughtLevel: String? = null,
    ): JSONValue? {
        val selection = modelSelectionView()
        val label = selection?.modelLabelForRowKey(rowKey)?.takeIf { it.isNotEmpty() } ?: rowKey
        var provider = selection?.providerForRowKey(rowKey, label)
        if (provider.isNullOrEmpty()) {
            // getView 失败/空表兜底：会话级 state.modelSelection.providerId（同模型场景）
            val sessionSelection = sessionModelSelection(conversationId)
            if (sessionSelection?.model == label && !sessionSelection.provider.isNullOrEmpty()) {
                provider = sessionSelection.provider
            }
        }
        if (provider.isNullOrEmpty()) {
            return ConversationCommandFactory.Rejection(
                reasonCode = "client.providerUnavailable",
                message = "模型提供商信息未就绪 · 点模型胶囊重新获取列表后再切换",
            ).syntheticAck
        }
        val thought = thoughtLevel ?: selection?.activeThoughtLevel ?: ""
        return switchModelConfig(
            conversationId = conversationId,
            provider = provider,
            model = label,
            thought = thought,
        )
    }

    /**
     * 桌面端模型选择只读视图（model-selection.getView {selection:null}；iOS :5396-5437
     * 直译）。内存缓存命中即短路；**空清单不入缓存**（缓存命中即短路重拉，空清单锁死
     * 会让重试面永远命中空缓存——模型/思考可选性修复的兜底面）；失败返回 null。
     */
    override suspend fun modelSelectionView(): ModelSelectionInfo? {
        modelSelectionCache?.let { return it }
        // getView 入参 {selection: null}（不带当前选择 → 返回缺省视图 + preferredSelection）
        val fields = LinkedHashMap<String, JSONValue>()
        fields["selection"] = JSONValue.Null
        val result = try {
            transport.call("model-selection", "getView", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val view = result.jsonValue?.objectValue ?: return null
        val info = parseModelSelectionView(view)
        if (info.hasSelectableModels) {
            modelSelectionCache = info
            modelSelectionState.value = info
        }
        ensureModelSelectionSubscribed()
        return info
    }

    /** 模型选择变化流（getView 成功非空发布；onDidChange 失效重拉后发布新值）。 */
    override fun observeModelSelection(): Flow<ModelSelectionInfo?> = modelSelectionState.asStateFlow()

    /**
     * onDidChange 订阅（固定事件无参；收到即失效缓存重拉——桌面套餐/模型清单变更回流）。
     * handler 注册先于事件帧（ChannelClient.listen 先落表后发帧，§5-3 同源）。
     * 并发去重：subscription 落表前并发进入只发一次 eventListen 帧（ RemoteTaskStore
     * ensureTaskEventsSubscribed 同款双重检查）。
     */
    private suspend fun ensureModelSelectionSubscribed() {
        if (modelSelectionSubscription != null) return
        val subscription = transport.listen(
            "model-selection", "onDidChange", RPCValue.Undefined,
            handler = { _ ->
                scope.launch {
                    modelSelectionCache = null
                    modelSelectionView()
                }
            },
        )
        // 双重检查（并发订阅竞态：后来者让位并立即释放重复句柄）
        if (modelSelectionSubscription != null) {
            subscription.cancel()
        } else {
            modelSelectionSubscription = subscription
        }
    }

    /** 模型可用思考档：getView per-model 词表优先（thoughtByModel），未就绪时现拉一次（iOS :5267-5273 同构）。 */
    override suspend fun thoughtLevels(model: String): List<String> {
        modelSelectionCache?.thoughtByModel?.get(model)?.takeIf { it.isNotEmpty() }?.let { return it }
        modelSelectionView()
        return modelSelectionCache?.thoughtByModel?.get(model) ?: emptyList()
    }

    /**
     * getView 回执 → ModelSelectionInfo（iOS :5480-5578 纯函数直译；单测锁定）。
     * - 思考档词表权威 = per-model `config.optionSpecs.reasoningLevel.values`
     *   【实证·上游仓 provider/facades.ts ModelSelectionModelView + web v4 工具栏 yB】；
     *   `modelThoughtLevels` 键兜底；provider 级 optionSpecs 是旧猜测形态，仅并入并集兜底；
     * - 套餐分组（providerId 含 individual-coding-plan→个人/start-plan→体验/
     *   team-coding-plan→团队）；同名模型跨套餐各组各计；
     * - modelProviders 裸名表（后到覆盖）+ modelProvidersByPlan「套餐|模型」复合表
     *   （同名跨套餐消歧权威，v1.27）；
     * - 当前绑定 preferredSelection → effectiveSelection → effective.selection 宽容链。
     */
    fun parseModelSelectionView(view: Map<String, JSONValue>): ModelSelectionInfo {
        val models = ArrayList<String>()
        val thoughtLevels = ArrayList<String>()
        val thoughtByModel = HashMap<String, List<String>>()
        val modelProviders = LinkedHashMap<String, String>()
        val modelProvidersByPlan = LinkedHashMap<String, String>()
        val groupsByPlan = LinkedHashMap<String, MutableList<String>>()

        // 套餐名判定。**非套餐 provider（自定义供应商 / ccgithub / API key 等）必须回退成
        // 自己的分组名**，不能返 null——返 null 时该 provider 的模型只进裸表 `models` 而不进
        // 任何 planGroups，而 UI 在 planGroups 非空时只渲染分组行 → **模型在面板里彻底消失**
        // （用户报障「新建会话选不到 ccgithub 的 deepseek」根因；2026-11-24 本轮修复）。
        // web 权威：`composerPlanGroups.ts:74-120` 把「非 individual/team 套餐」的一切都推进
        // `others`（照样渲染），只有套餐才进 `plans`；分组标签取 `providerName || providerId`
        // （`modelSelectionGroups.ts:59`）。
        fun planNameFor(providerId: String, providerName: String?): String = when {
            providerId.contains("individual-coding-plan") -> "个人套餐"
            providerId.contains("start-plan") -> "体验套餐"
            providerId.contains("team-coding-plan") -> "团队套餐"
            else -> providerName?.trim()?.takeIf { it.isNotEmpty() } ?: providerId
        }

        for (provider in view["providers"]?.arrayValue ?: emptyList()) {
            val providerDict = provider.objectValue ?: continue
            val providerId = providerDict["providerId"]?.stringValue ?: ""
            val providerName = providerDict["providerName"]?.stringValue
                ?: providerDict["name"]?.stringValue
                ?: providerDict["label"]?.stringValue
            val plan = planNameFor(providerId, providerName)
            // 思考档兜底源：provider config.optionSpecs.reasoningLevel.values
            val config = providerDict["config"]?.objectValue
                ?: providerDict["effectiveConfig"]?.objectValue
            for (level in config?.get("optionSpecs")?.objectValue?.get("reasoningLevel")
                ?.objectValue?.get("values")?.arrayValue ?: emptyList()) {
                val levelName = level.stringValue
                if (!levelName.isNullOrEmpty() && !thoughtLevels.contains(levelName)) {
                    thoughtLevels.add(levelName)
                }
            }
            for (model in providerDict["models"]?.arrayValue ?: emptyList()) {
                var label: String? = null
                val stringModel = model.stringValue
                if (!stringModel.isNullOrEmpty()) {
                    label = stringModel
                } else {
                    val modelDict = model.objectValue
                    if (modelDict != null) {
                        label = modelDict["label"]?.stringValue
                            ?: modelDict["name"]?.stringValue
                            ?: modelDict["modelId"]?.stringValue
                            ?: modelDict["id"]?.stringValue
                        // per-model 思考档词表（权威 modelId 键；label 相异时双键）
                        val modelId = modelDict["modelId"]?.stringValue
                        if (!modelId.isNullOrEmpty()) {
                            var perModel = (modelDict["config"]?.objectValue?.get("optionSpecs")
                                ?.objectValue?.get("reasoningLevel")?.objectValue?.get("values")
                                ?.arrayValue ?: emptyList()).mapNotNull { it.stringValue }
                            if (perModel.isEmpty()) {
                                perModel = (modelDict["modelThoughtLevels"]?.arrayValue ?: emptyList())
                                    .mapNotNull { it.stringValue }
                            }
                            if (perModel.isNotEmpty()) {
                                thoughtByModel[modelId] = perModel
                                if (!label.isNullOrEmpty() && label != modelId) {
                                    thoughtByModel[label] = perModel
                                }
                            }
                        }
                        for (level in modelDict["modelThoughtLevels"]?.arrayValue ?: emptyList()) {
                            val levelName = level.stringValue
                            if (!levelName.isNullOrEmpty() && !thoughtLevels.contains(levelName)) {
                                thoughtLevels.add(levelName)
                            }
                        }
                    }
                }
                if (label.isNullOrEmpty()) continue
                if (!models.contains(label)) models.add(label)
                if (providerId.isNotEmpty()) modelProviders[label] = providerId
                // 每个 provider 恒有一个分组（套餐或它自己的名字）——无分组 = UI 里消失
                val groupName = plan.takeIf { it.isNotEmpty() } ?: providerId
                if (groupName.isNotEmpty()) {
                    val group = groupsByPlan.getOrPut(groupName) { ArrayList() }
                    if (!group.contains(label)) group.add(label)
                    // 复合键表：同名模型跨套餐各组各计（裸名表后到覆盖先到——
                    // 点个人套餐行发出体验套餐 providerId，2026-10-08 报障根因）
                    if (providerId.isNotEmpty()) {
                        modelProvidersByPlan["$groupName|$label"] = providerId
                    }
                }
            }
        }
        // 当前绑定优先 preferredSelection；退化 effectiveSelection → effective.selection
        val selection = view["preferredSelection"]?.objectValue
            ?: view["effectiveSelection"]?.objectValue
            ?: view["effective"]?.objectValue?.get("selection")?.objectValue
        return ModelSelectionInfo(
            models = models,
            activeModel = selection?.get("modelId")?.stringValue,
            thoughtLevels = thoughtLevels,
            activeThoughtLevel = selection?.get("options")?.objectValue?.get("reasoningLevel")?.stringValue,
            thoughtByModel = thoughtByModel,
            planGroups = groupsByPlan.map { (plan, list) -> ModelPlanGroup(plan = plan, models = list) },
            modelProviders = modelProviders,
            modelProvidersByPlan = modelProvidersByPlan,
            activeProviderId = selection?.get("providerId")?.stringValue,
        )
    }

    // MARK: 协作模式与投递模式（iOS :3989-4009 + ChatViewModel v1.33 直译）

    /**
     * 协作模式切换（Plan/Build 胶囊；switchCollaborationMode CAS【实证 web bundle：
     * mode 枚举 build|edit|plan|yolo】）。ACK 三态（v1.33）：
     * - 失败态（rejected/stale 终局/failed）：chip 不动，failureText 原文落常驻错误行
     *   （[collaborationModeFailureText]）；
     * - 未知态（status 缺席/词表外，含未送达 null）：**不落盘**——落盘即与桌面真态脱钩
     *   且无读面时永不自愈（2026-10-09「计划模式丢失」根因之一）；登记待定值交
     *   state.config.mode 投影首帧定案（refreshPendingInteractionsLocked）；
     * - 权威成功（accepted/duplicate/noop）：乐观落投影 + 3s 收敛护栏（旧投影不回闪）。
     */
    override suspend fun switchCollaborationMode(conversationId: String, mode: String): JSONValue? {
        val ack = sendCASWithRetry(
            "switchCollaborationMode",
            sessionId = conversationId,
            payload = JSONValue.Obj(mapOf("mode" to JSONValue.Str(mode))),
        )
        val commandAck = CommandAck(ack)
        val failure = commandAck.failureText
        mutex.withLock {
            when {
                failure != null -> collaborationModeFailures[conversationId] = failure
                !commandAck.isAuthoritativeSuccess -> {
                    pendingUnknownModeValues[conversationId] = mode
                    collaborationModeFailures[conversationId] =
                        "桌面回执状态未知（status 缺席）· 本机不落盘，以桌面实际模式为准"
                }
                else -> {
                    // 权威成功：乐观落投影 + 护栏（不等桌面投影回流——忙 turn 期间增量可滞后分钟级）；
                    // 更新的权威 ack 同时作废此前登记的未知态待定标记
                    sessionCollaborationModes[conversationId] = mode
                    lastLocalModeSwitches[conversationId] = mode to nowMs()
                    pendingUnknownModeValues.remove(conversationId)
                    collaborationModeFailures.remove(conversationId)
                }
            }
        }
        return ack
    }

    /** 协作模式切换失败/未知态常驻错误行（null = 无在案；权威成功/投影定案即清）。 */
    suspend fun collaborationModeFailureText(conversationId: String): String? =
        mutex.withLock { collaborationModeFailures[conversationId] }

    /**
     * 投递/跟随模式（CAS 类）。A-2 词表门：mode 枚举仅 `queue|guide`——「立即」不是
     * followupMode 值（web 默认 queue，立即语义由 sendText 逐消息
     * requestedDelivery:"startNow" 承载）：now 档调用方拦截不下发，本方法再兜一层
     * 词表外值本地拒发（合成 rejected，不上线缆）。
     */
    override suspend fun setFollowupMode(conversationId: String, mode: String): JSONValue? {
        if (mode != "queue" && mode != "guide") {
            return ConversationCommandFactory.Rejection(
                reasonCode = "client.invalidFollowupMode",
                message = "投递模式 $mode 不在词表（queue|guide）；「立即」为本机档，发送时以 requestedDelivery:startNow 承载",
            ).syntheticAck
        }
        return sendCASWithRetry(
            "setFollowupMode",
            sessionId = conversationId,
            payload = JSONValue.Obj(mapOf("mode" to JSONValue.Str(mode))),
        )
    }

    // MARK: resolveInteraction 三族形状门（A-3/甲-3：answer 恒对象，禁平铺裸串）

    /**
     * 交互应答三族（answer 对象形状的唯一合法构造点；web 实证 bundle answer schema：
     * 裸字符串被 zod expected object 拒——2026-10-06 甲-3「审批卡点击确认全部没反应」根因）。
     */
    sealed class InteractionAnswer {
        /** 权限族 `{optionId}`（allowOnce/allowAlways/rejectOnce/rejectAlways 由 UI 按服务端 options 传入）。 */
        data class Permission(val optionId: String) : InteractionAnswer()

        /** 计划族 `{action}`——词表 accept|decline|cancel。 */
        data class Plan(val action: String) : InteractionAnswer()

        /** 提问族 `{freeText}`。 */
        data class Question(val freeText: String) : InteractionAnswer()
    }

    /**
     * 交互应答（三族形状门）：payload 恒 `{interactionId, answer}` 两键、answer 恒对象
     * （无顶层平铺——approved/scope/text 是 wire 不存在的键）。词表外/空值本地拒发
     * （合成 rejected，不上线缆）。（iOS :3026-3033 resolveInteractionRaw + VM decide/
     * decidePlan/answerQuestion 三调用点形状的 :store 收口）
     */
    suspend fun resolveInteraction(
        conversationId: String,
        interactionId: String,
        answer: InteractionAnswer,
    ): JSONValue? {
        val answerObj: JSONValue = when (answer) {
            is InteractionAnswer.Permission -> {
                val optionId = answer.optionId.trim()
                if (optionId.isEmpty()) {
                    return ConversationCommandFactory.Rejection(
                        reasonCode = "client.invalidInteractionAnswer",
                        message = "权限应答缺少 optionId，未下发",
                    ).syntheticAck
                }
                JSONValue.Obj(mapOf("optionId" to JSONValue.Str(optionId)))
            }
            is InteractionAnswer.Plan -> {
                if (answer.action !in PLAN_ACTIONS) {
                    return ConversationCommandFactory.Rejection(
                        reasonCode = "client.invalidInteractionAnswer",
                        message = "计划决议 action=${answer.action} 不在词表（accept|decline|cancel），未下发",
                    ).syntheticAck
                }
                JSONValue.Obj(mapOf("action" to JSONValue.Str(answer.action)))
            }
            is InteractionAnswer.Question -> {
                val freeText = answer.freeText.trim()
                if (freeText.isEmpty()) {
                    return ConversationCommandFactory.Rejection(
                        reasonCode = "client.invalidInteractionAnswer",
                        message = "提问应答内容为空，未下发",
                    ).syntheticAck
                }
                JSONValue.Obj(mapOf("freeText" to JSONValue.Str(freeText)))
            }
        }
        return resolveInteractionRaw(conversationId, interactionId, answerObj)
    }

    // MARK: 列表组织态写（iOS :2596-2739 直译；本地 override 即时反馈 + zcode-task 双写，
    // 失败完整回滚 + fault 原文上屏 §5-12；跨区行按归属 workspace 寻址 workspaceTarget）

    /**
     * 置顶：本地 override 即时反馈 + setTaskPinned 远端写（修复重启后置顶丢失）。
     * 远端失败回滚本地态；membership 集（pinnedTaskIds/archivedTaskIds）写成功即同步
     * 维护（sessions-index 行不含 pinned 字段——schema 冻结【实证·上游仓
     * sessionSummarySchema】，写后不等全量刷新；桌面 tasks-index.sqlite 为权威，
     * 下次 membership 刷新对账）。
     */
    override suspend fun setPinned(pinned: Boolean, conversationId: String) {
        val index = sessionsIndex
        val previous = index?.pinnedOverrides?.get(conversationId) ?: false
        if (index != null) {
            index.pinnedOverrides = index.pinnedOverrides + (conversationId to pinned)
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
        fields["pinned"] = JSONValue.Bool(pinned)
        try {
            transport.call("zcode-task", "setTaskPinned", RPCValue.json(JSONValue.Obj(fields)))
            if (index != null) {
                index.pinnedTaskIds =
                    if (pinned) index.pinnedTaskIds + conversationId else index.pinnedTaskIds - conversationId
                if (pinned) {
                    index.archivedTaskIds = index.archivedTaskIds - conversationId
                }
            }
            lastTaskWriteFailure = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (index != null) {
                index.pinnedOverrides = index.pinnedOverrides + (conversationId to previous)
                yieldConversationsReplaced()
            }
            lastTaskWriteFailure = loadFailureText("置顶", e)
        }
    }

    /**
     * 归档/取消归档：archiveTask / unarchiveTask（取消归档此前误发 archiveTask，
     * named gap P1-6 修复）。本地 override 即时反馈 + membership 维护，失败**完整回滚**
     * （override + membership——此前缓存不回滚：归档区本会话仍显示该行、重启后随缓存
     * 蒸发，用户报障「归档的会话又丢了」第三次）。写目标修正（⑥）：workspacePath/
     * workspaceIdentity 取**任务自带归属工作区**（web 同款——跨区任务此前误发当前连接
     * workspace，归档落错工作区）。归属反查：sessions-index 行 workspacePath → 连接
     * workspace 兜底（【偏差】iOS 另有 listArchivedTasks 行缓存反查环，该缓存随归档区
     * 读面挂后续轮，见 deviations）。
     */
    override suspend fun setArchived(archived: Boolean, conversationId: String) {
        val index = sessionsIndex
        val ownPath = index?.sessionSummaries()?.get(conversationId)?.workspacePath
        val resolvedPath = ownPath?.takeIf { it.isNotEmpty() } ?: workspace.path
        val targetIdentity = if (resolvedPath == workspace.path) {
            workspace.workspaceIdentity
        } else {
            allWorkspaces.firstOrNull { it.path == resolvedPath }?.workspaceIdentity
        }
        val previous = index?.archivedOverrides?.get(conversationId) ?: false
        if (index != null) {
            index.archivedOverrides = index.archivedOverrides + (conversationId to archived)
            yieldConversationsReplaced()
        }
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(conversationId)
        fields["workspacePath"] = JSONValue.Str(resolvedPath)
        if (!targetIdentity.isNullOrEmpty()) {
            fields["workspaceIdentity"] = JSONValue.Str(targetIdentity)
        }
        try {
            transport.call(
                "zcode-task",
                if (archived) "archiveTask" else "unarchiveTask",
                RPCValue.json(JSONValue.Obj(fields)),
            )
            if (index != null) {
                if (archived) {
                    index.archivedTaskIds = index.archivedTaskIds + conversationId
                    index.pinnedTaskIds = index.pinnedTaskIds - conversationId
                } else {
                    index.archivedTaskIds = index.archivedTaskIds - conversationId
                }
            }
            lastArchiveFailure = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 写失败完整回滚（override + membership）+ 失败原因透出（写面禁止静默 §5-12）
            if (index != null) {
                index.archivedOverrides = index.archivedOverrides + (conversationId to previous)
            }
            lastArchiveFailure = if (e is RPCError) {
                "${e.name}: ${e.message}"
            } else {
                e.toString().take(240)
            }
            yieldConversationsReplaced()
        }
    }

    /** 归档/取消归档最近一次写失败原文（空串 = 无；列表页归档动作如实反馈）。 */
    override suspend fun lastArchiveFailureText(): String = lastArchiveFailure

    /**
     * 重命名（P1-5）：renameTask 双写 + 本地标题 override 即时反馈，失败回滚 + 拒因
     * 上屏（§5-12 写面禁静默——iOS 曾只回滚不提示，「点了没反应」无从分诊）。
     */
    override suspend fun renameConversation(title: String, conversationId: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val index = sessionsIndex
        val previous = index?.titleOverrides?.get(conversationId)
        if (index != null) {
            index.titleOverrides = index.titleOverrides + (conversationId to trimmed)
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
        fields["title"] = JSONValue.Str(trimmed)
        try {
            transport.call("zcode-task", "renameTask", RPCValue.json(JSONValue.Obj(fields)))
            lastTaskWriteFailure = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (index != null) {
                index.titleOverrides = if (previous != null) {
                    index.titleOverrides + (conversationId to previous)
                } else {
                    index.titleOverrides - conversationId
                }
                yieldConversationsReplaced()
            }
            lastTaskWriteFailure = loadFailureText("改名", e)
        }
    }

    // MARK: 附件上传事务（A-4 对齐 web：Begin/Chunk/Commit/Abort 四条 channel RPC）
    //
    // 直译自 ios/ZCodeMobileApp/Sources/Stores/Remote/RemoteConversationStore.swift
    // :5010-5250。web 事务【实证·上游仓 attachmentUploadTransaction.ts】：客户端参数里
    // **没有 connectionId**——common = {...workspace, sessionId, uploadId}，connectionId
    // 由桌面 host facade 注入（zcodeAgentConnectionScope.ts:91-110 withTrustedConnection
    // 先 delete 客户端伪造的 connectionId/clientMode 再写 host 真值）；多发即被真机桌面
    // strict 校验以 Invalid params 拒（2026-10-06「附件不能上传」根因之一，甲-2/A11）。
    // 键面逐键对齐 web 客户端：
    // ① Begin `{workspacePath, workspaceIdentity?, sessionId, uploadId, fileName, mime,
    //    totalBytes, totalChunks, checksum:"sha256:"+64hex}` → 回执 state 判别联合
    //    staging{nextChunkIndex} | committed{nextChunkIndex, ref}；
    // ② Chunk `{...common, chunkIndex, dataBase64}` → `{uploadId, nextChunkIndex}`——
    //    chunkIndex 是**块序号非字节 offset**，进度判定 = nextChunkIndex === chunkIndex+1；
    // ③ Commit `{...common}` → `{ref}`；④ Abort 同 Commit 形（Begin 成功后的失败路径收口）。
    // 384KiB/块（上游 attachmentUploadTransaction.ts ATTACHMENT_UPLOAD_CHUNK_BYTES=
    // 384*1024，可被 3 整除——除末片外 base64 无 padding）；限额【实证·上游仓 core.ts:84-96
    // PROTOCOL_V4_LIMITS】：attachmentMaxBytes=20MB、attachmentUploadMaxChunks=64。
    // ReadOnlyGate：附件事务为桌面存储写非手机直写，zcode-agent 默认面放行。
    // 中继瞬断首败 1.2s 退避重试一次（AGENTS §5-9；Abort 不重试——iOS 同口径，
    // 尽力收口语义，失败不掩盖原始错误）；失败以 Result.failure 搪错误文本回传
    // 调用方（禁静默吞错 §5-12）。

    /** channel 错误 → UI 可读文本（name+message 保留服务端原词，供失败行如实展示）（iOS :5021-5025）。 */
    private fun attachmentWireError(error: Throwable): String =
        (error as? RPCError)?.let { "${it.name}: ${it.message}" } ?: error.toString()

    /**
     * diag.attach.last：附件事务最近一次往返取证（方法 + 回执/错误原文前缀；iOS :5027-5032
     * UserDefaults diag.attach.last 的 diagListener 承载；验收后随 diag 清理波次移除）。
     */
    private fun attachmentDiag(method: String, detail: String) {
        diag("diag.attach.last", "$method ${detail.take(400)}")
    }

    /**
     * 开启（或幂等续接）上传事务（iOS :5034-5106 直译）。同 uploadId 重发 Begin 时桌面按
     * 已收块数回报 staging.nextChunkIndex——调用方以此为续传起点（web PCe 循环同款起点语义）。
     */
    /**
     * 会话 userInput 附件预览读（`attachmentReadV4` 分块聚合）。
     *
     * **缺陷背景（2026-11-24 任务 2 走查发现）**：本方法此前**只有接口默认实现返回 null**，
     * `RemoteConversationStore` 从未 override → 连接态下每个 `userInput` 附件都落到
     * `AttachmentViews` 的 `failed` 分支，真机上**所有历史图片/视频一律显示「⚠ 附件加载失败」**。
     * 这是「会话详情页渲染与 web 不一致」最直观的一处。iOS 早有完整实现
     * （`RemoteConversationStore.swift:5163-5242`），Android 未移植。
     *
     * 桌面 schema（`zcode-protocol-v4/transport.ts` v4/attachment/read）：
     * params `{sessionId, workspacePath?, workspaceIdentity?, ref, target?, attachmentIndex?,
     * offset, limit≤512KiB}`；result `{dataBase64, mediaType, totalBytes, nextOffset?}`。
     *
     * 纪律（逐条对齐 iOS）：
     * - 上限 **60 块 ≈ 30MB**（`attachmentPreviewMaxChunks`，core.ts:92-93）——旧 8 块口径会把
     *   20MB 通道放宽后的大图静默截成花图；
     * - 循环耗尽仍有 `nextOffset` = **截断** → 按 `fault.attachment.previewTruncated` 同语义
     *   **作失败处理**，禁止拿前缀冒充完整预览；
     * - 分块 base64 **各自带 padding**：整串解码失败时按块独立解码拼接（AGENTS §5-8）；
     * - 中继瞬断（分块读首败）1.2s 退避重试一次（AGENTS §5-9）。
     */
    override suspend fun attachmentPreview(sessionId: String, ref: String): AttachmentPreview? =
        attachmentPreviewOnce(sessionId, ref)
            ?: run {
                delay(PAGE_RETRY_BACKOFF_MS)
                attachmentPreviewOnce(sessionId, ref)
            }

    private suspend fun attachmentPreviewOnce(sessionId: String, ref: String): AttachmentPreview? {
        val chunks = ArrayList<String>()
        var mediaType: String? = null
        var totalBytes = 0
        var offset = 0
        var expectMore = true
        for (chunk in 0 until ATTACHMENT_PREVIEW_MAX_CHUNKS) {
            val fields = LinkedHashMap<String, JSONValue>()
            applySessionTarget(fields, sessionId)
            fields["ref"] = JSONValue.Str(ref)
            fields["offset"] = JSONValue.Int(offset)
            fields["limit"] = JSONValue.Int(ATTACHMENT_CHUNK_MAX_BYTES)
            val ack = try {
                transport.call("zcode-agent", "attachmentReadV4", RPCValue.json(JSONValue.Obj(fields)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attachmentDiag("preview-err", attachmentWireError(e))
                return null
            }
            val dict = ack.jsonValue?.objectValue
            val base64 = dict?.get("dataBase64")?.stringValue
            if (base64 == null) {
                attachmentDiag("preview-nodata", ack.jsonValue?.toString()?.take(300) ?: "null")
                return null
            }
            chunks.add(base64)
            if (mediaType == null) mediaType = dict["mediaType"]?.stringValue
            dict["totalBytes"]?.longValue()?.let { totalBytes = it.toInt() }
            val next = dict["nextOffset"]?.longValue()?.toInt()
            if (next == null) {
                expectMore = false
                break
            }
            offset = next
        }
        // 块数耗尽仍要下一页 = 截断（禁止部分预览冒充完整）
        if (expectMore) {
            attachmentDiag("preview-truncated", "bytes=$totalBytes chunks=${chunks.size}")
            return null
        }
        val resolvedMediaType = mediaType ?: return null
        val data = decodeChunks(chunks) ?: run {
            attachmentDiag("preview-decode-fail", "chunks=${chunks.size}")
            return null
        }
        return AttachmentPreview(
            ref = ref,
            data = data,
            mediaType = resolvedMediaType,
            totalBytes = totalBytes,
        )
    }

    /** 分块 base64 解码：先整串，失败按块独立解码拼接（§5-8 各块自带 padding）。 */
    private fun decodeChunks(chunks: List<String>): ByteArray? {
        val joined = chunks.joinToString("")
        val decoder = java.util.Base64.getMimeDecoder()
        runCatching { decoder.decode(joined) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val out = java.io.ByteArrayOutputStream()
        for (chunk in chunks) {
            val part = runCatching { java.util.Base64.getMimeDecoder().decode(chunk) }.getOrNull()
                ?: return null
            out.write(part)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    override suspend fun attachmentBeginV4(
        sessionId: String,
        uploadId: String,
        fileName: String,
        mime: String,
        totalBytes: Int,
        totalChunks: Int,
        checksum: String,
    ): Result<AttachmentBeginOutcome> {
        val fields = LinkedHashMap<String, JSONValue>()
        // 会话域 workspace 信封（实机实证 2026-10-06：缺 workspacePath 被 "Invalid params"
        // 拒——web 由 channel 层注入同款信封；上游 facade getClient(params) 按
        // params.workspacePath 选 CLI 进程，键被 scope 层消费，不入 CLI wire）
        applySessionTarget(fields, sessionId)
        fields["uploadId"] = JSONValue.Str(uploadId)
        fields["sessionId"] = JSONValue.Str(sessionId)
        fields["fileName"] = JSONValue.Str(fileName)
        fields["mime"] = JSONValue.Str(mime)
        fields["totalBytes"] = JSONValue.Int(totalBytes)
        fields["totalChunks"] = JSONValue.Int(totalChunks)
        fields["checksum"] = JSONValue.Str(checksum)
        // 参数层 strict 校验（构造与校验同源：transport.ts:832-952 附件四方法全 .strict()
        // ——正则/限额在本地构造期即爆，替代「发出后服务端 zod 拒」）
        val paramCheck = PValidator.validate(
            JSONValue.Obj(fields),
            schema = PSchema.Obj(CommandSchemas.attachmentBeginParamsShape()),
            path = "attachmentBeginV4",
        )
        if (!paramCheck.isValid) {
            return Result.failure(AttachmentRPCError("参数校验失败：" + paramCheck.errors.joinToString("; ")))
        }
        try {
            val ack = transport.call(
                "zcode-agent", "attachmentBeginV4", RPCValue.json(JSONValue.Obj(fields)),
            )
            attachmentDiag("begin", ack.jsonValue.toString())
            return parseBeginReply(ack.jsonValue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attachmentDiag("begin-err", attachmentWireError(e))
            delay(PAGE_RETRY_BACKOFF_MS)
            return try {
                val ack = transport.call(
                    "zcode-agent", "attachmentBeginV4", RPCValue.json(JSONValue.Obj(fields)),
                )
                parseBeginReply(ack.jsonValue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(AttachmentRPCError(attachmentWireError(e)))
            }
        }
    }

    /**
     * Begin 回执解析（iOS :5108-5137 直译）：state 判别联合——committed 短路给 ref；
     * staging 给续传起点；形状不识别如实报错（本方法形状【移植·bundle 逆向】+【实证·上游仓
     * transport.ts:855-883 v4AttachmentBeginResultSchema】）。
     */
    private fun parseBeginReply(json: JSONValue?): Result<AttachmentBeginOutcome> {
        val dict = json?.objectValue
            ?: return Result.failure(AttachmentRPCError("回执形状未识别：${json.toString().take(160)}"))
        return when (dict["state"]?.stringValue) {
            "committed" -> {
                val ref = dict["ref"]?.stringValue
                if (ref.isNullOrEmpty()) {
                    Result.failure(AttachmentRPCError("committed 回执缺 ref：${dict.toString().take(160)}"))
                } else {
                    Result.success(AttachmentBeginOutcome.Committed(ref = ref))
                }
            }
            "staging" -> {
                val next = dict["nextChunkIndex"]?.intValue
                if (next == null) {
                    Result.failure(AttachmentRPCError("staging 回执缺 nextChunkIndex：${dict.toString().take(160)}"))
                } else {
                    Result.success(AttachmentBeginOutcome.Staging(nextChunkIndex = next))
                }
            }
            else -> Result.failure(AttachmentRPCError("回执 state 未识别：${dict.toString().take(160)}"))
        }
    }

    /** 分块下发（iOS :5139-5182 直译）。成功值 = 回执 nextChunkIndex（调用方校验 == chunkIndex+1）。 */
    override suspend fun attachmentChunkV4(
        sessionId: String,
        uploadId: String,
        chunkIndex: Int,
        dataBase64: String,
    ): Result<Int> {
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, sessionId)
        fields["uploadId"] = JSONValue.Str(uploadId)
        fields["sessionId"] = JSONValue.Str(sessionId)
        fields["chunkIndex"] = JSONValue.Int(chunkIndex)
        fields["dataBase64"] = JSONValue.Str(dataBase64)
        // 参数层 strict 校验（含 chunk base64 自带 padding + ≤512KiB 解码上限——
        // A11 不变量本地前移，CommandSchemas.attachmentChunkParamsShape StrictBase64）
        val paramCheck = PValidator.validate(
            JSONValue.Obj(fields),
            schema = PSchema.Obj(CommandSchemas.attachmentChunkParamsShape()),
            path = "attachmentChunkV4",
        )
        if (!paramCheck.isValid) {
            return Result.failure(AttachmentRPCError("参数校验失败：" + paramCheck.errors.joinToString("; ")))
        }
        try {
            val ack = transport.call(
                "zcode-agent", "attachmentChunkV4", RPCValue.json(JSONValue.Obj(fields)),
            )
            return parseNextChunkIndex(ack.jsonValue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(PAGE_RETRY_BACKOFF_MS)
            return try {
                val ack = transport.call(
                    "zcode-agent", "attachmentChunkV4", RPCValue.json(JSONValue.Obj(fields)),
                )
                parseNextChunkIndex(ack.jsonValue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(AttachmentRPCError(attachmentWireError(e)))
            }
        }
    }

    /** Chunk 回执解析（iOS :5169-5176 直译）：`{uploadId, nextChunkIndex}`（strict）——仅取进度游标。 */
    private fun parseNextChunkIndex(json: JSONValue?): Result<Int> {
        val next = json?.objectValue?.get("nextChunkIndex")?.intValue
            ?: return Result.failure(AttachmentRPCError("回执缺 nextChunkIndex：${json.toString().take(160)}"))
        return Result.success(next)
    }

    /** 事务收口（iOS :5184-5236 直译）。成功值 = 回执 ref（strict `{ref}`——sendText attachments 携带值）。 */
    override suspend fun attachmentCommitV4(sessionId: String, uploadId: String): Result<String> {
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, sessionId)
        fields["uploadId"] = JSONValue.Str(uploadId)
        fields["sessionId"] = JSONValue.Str(sessionId)
        val paramCheck = PValidator.validate(
            JSONValue.Obj(fields),
            schema = PSchema.Obj(CommandSchemas.attachmentFinishParamsShape()),
            path = "attachmentCommitV4",
        )
        if (!paramCheck.isValid) {
            return Result.failure(AttachmentRPCError("参数校验失败：" + paramCheck.errors.joinToString("; ")))
        }
        fun refOf(json: JSONValue?): Result<String> {
            val ref = json?.objectValue?.get("ref")?.stringValue
            if (ref.isNullOrEmpty()) {
                return Result.failure(AttachmentRPCError("回执缺 ref：${json.toString().take(160)}"))
            }
            return Result.success(ref)
        }
        try {
            val ack = transport.call(
                "zcode-agent", "attachmentCommitV4", RPCValue.json(JSONValue.Obj(fields)),
            )
            attachmentDiag("commit", ack.jsonValue.toString())
            return refOf(ack.jsonValue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(PAGE_RETRY_BACKOFF_MS)
            return try {
                val ack = transport.call(
                    "zcode-agent", "attachmentCommitV4", RPCValue.json(JSONValue.Obj(fields)),
                )
                refOf(ack.jsonValue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(AttachmentRPCError(attachmentWireError(e)))
            }
        }
    }

    /**
     * 失败路径中止（iOS :5238-5262 直译；web PCe catch 分支同款：Begin 成功后的事务失败
     * 尽力收口）。Abort 失败不掩盖原始错误——调用方将「中止未送达」并入失败提示，孤儿
     * 事务由桌面端超时回收（attachmentUploadTtlMs=5min，core.ts:98）。**不重试**（iOS
     * 同口径：abort 是尽力收口非关键写，重试会给繁忙桌面再压一次 30s 超时）。
     */
    override suspend fun attachmentAbortV4(sessionId: String, uploadId: String): Result<Unit> {
        val fields = LinkedHashMap<String, JSONValue>()
        applySessionTarget(fields, sessionId)
        fields["uploadId"] = JSONValue.Str(uploadId)
        fields["sessionId"] = JSONValue.Str(sessionId)
        val paramCheck = PValidator.validate(
            JSONValue.Obj(fields),
            schema = PSchema.Obj(CommandSchemas.attachmentFinishParamsShape()),
            path = "attachmentAbortV4",
        )
        if (!paramCheck.isValid) {
            return Result.failure(AttachmentRPCError("参数校验失败：" + paramCheck.errors.joinToString("; ")))
        }
        return try {
            transport.call("zcode-agent", "attachmentAbortV4", RPCValue.json(JSONValue.Obj(fields)))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(AttachmentRPCError(attachmentWireError(e)))
        }
    }

    companion object {
        /** keep-warm 租约窗缺省（上游 sessionDataLayer.ts:30 SESSION_DATA_LAYER_KEEP_WARM_MS）。 */
        const val DEFAULT_KEEP_WARM_MS = 30_000L

        /** 输入预测草稿上限（contract.ts INPUT_PREDICTION_MAX_DRAFT_CHARS；超长手机侧截断）。 */
        private const val INPUT_PREDICTION_MAX_DRAFT_CHARS = 4000

        /** 输入预测请求号上限（contract.ts INPUT_PREDICTION_MAX_REQUEST_ID_CHARS）。 */
        private const val INPUT_PREDICTION_MAX_REQUEST_ID_CHARS = 128

        /** 上游 E2E 档（sessionDataLayer.ts:31 E2E_SESSION_DATA_LAYER_KEEP_WARM_MS）。 */
        const val E2E_KEEP_WARM_MS = 1_000L

        /** 快照落表等待：单次 100ms × 6（iOS messages(in:) :869-872）。 */
        private const val SNAPSHOT_WAIT_MS = 100L

        /** 翻页/超时退避重试间隔（AGENTS §5-9 中继瞬断 1.2s 退避同口径）。 */
        private const val PAGE_RETRY_BACKOFF_MS = 1_200L
        /** 附件预览分块上限（core.ts:92-93 attachmentPreviewMaxChunks = 30MB/512KiB） */
        private const val ATTACHMENT_PREVIEW_MAX_CHUNKS = 60
        private const val ATTACHMENT_CHUNK_MAX_BYTES = 512 * 1024

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

        /** 协作模式本地切换收敛护栏窗（v1.33：ack 与 state.config.mode 增量到达间隙内旧投影不回闪）。 */
        const val MODE_SWITCH_GUARD_MS = 3_000L

        /** CAS 前置 revision 轮询间隔（iOS ensureStateRevision 300ms × 10 直译）。 */
        private const val STATE_REVISION_POLL_MS = 300L

        /** 计划决议 action 词表（iOS ChatViewModel.decidePlan accept|decline + v1.35 cancel 语义）。 */
        val PLAN_ACTIONS = setOf("accept", "decline", "cancel")

        /** 工作流镜像截尾上界（iOS persistWorkflowMirror `runs.suffix(4)` 同值）。 */
        private const val WORKFLOW_MIRROR_CAP = 4

        /** 触发表缺失 resync 后的 UI nudge 延时（iOS :3232 `2_500_000_000` ns 同值）。 */
        private const val NUDGE_DELAY_MS = 2_500L

        /** membership 节流刷新窗（iOS :302 `>= 30` 秒同值）。 */
        private const val MEMBERSHIP_REFRESH_INTERVAL_MS = 30_000L

        /** 项目归属元数据（逐工作区 listTaskList）节流刷新窗——列表分组数据源，较 membership 低频。 */
        private const val PROJECT_METADATA_REFRESH_INTERVAL_MS = 120_000L

        /** 归属回填失败短退避（2026-11-23 真机实证：首战撞连接期全空时 15s 重试，不烧满长窗）。 */
        private const val PROJECT_METADATA_RETRY_INTERVAL_MS = 15_000L

        /** 产物分块读单块上限（上游 workflow-artifacts.ts ≤512KiB 一块）。 */
        private const val ARTIFACT_CHUNK_BYTES = 512 * 1024

        /** 产物分块读轮次上界（40 × 512KiB = 上游 ARTIFACT_CAPS.maxFileBytes=20MB）。 */
        private const val ARTIFACT_MAX_CHUNKS = 40

        /**
         * G-007 通路 A：workflowActivity → 会话行迷你轨道投影（sessionWorkflowActivitySchema：
         * {runs[≤4]}，run 摘要 {runId, name?, status 五态, phases[{name,status 四态,alongside?}],
         * currentPhase?, agentsWorking}；宽容解析，无有效 run 返回 nil）。
         * （iOS parseWorkflowActivity :3453-3478 直译）
         */
        fun parseWorkflowActivity(json: JSONValue?): WorkflowActivitySummary? {
            val runs = json?.objectValue?.get("runs")?.arrayValue ?: return null
            if (runs.isEmpty()) return null
            val parsed = runs.mapNotNull { run ->
                val dict = run.objectValue ?: return@mapNotNull null
                val runId = dict["runId"]?.stringValue ?: dict["id"]?.stringValue ?: return@mapNotNull null
                val phases = (dict["phases"]?.arrayValue ?: emptyList()).mapNotNull { phase ->
                    val d = phase.objectValue ?: return@mapNotNull null
                    val name = d["name"]?.stringValue?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    cn.biuz.android.protocol.SessionWorkflowPhase(
                        name = name,
                        status = WorkflowStepStatus.map(d["status"]?.stringValue),
                        alongside = d["alongside"]?.arrayValue?.mapNotNull { it.intValue } ?: emptyList(),
                    )
                }
                SessionWorkflowRunSummary(
                    id = runId,
                    name = dict["name"]?.stringValue,
                    rawStatus = dict["status"]?.stringValue ?: "pending",
                    phases = phases,
                    currentPhase = dict["currentPhase"]?.stringValue,
                    agentsWorking = dict["agentsWorking"]?.intValue ?: 0,
                )
            }
            return if (parsed.isEmpty()) null else WorkflowActivitySummary(runs = parsed)
        }

        /**
         * 通路 A 摘要投影（阶段链 only；列表内 activity-only run 用）。
         * （iOS projectActivityRun :3194-3206 直译）
         */
        fun projectActivityRun(run: SessionWorkflowRunSummary): WorkflowRunSummary = WorkflowRunSummary(
            id = run.id,
            name = run.name ?: "工作流",
            rawStatus = run.rawStatus,
            stopReason = null,
            resumable = false,
            truncated = false,
            nodes = run.phases.map { phase ->
                WorkflowNodeSummary(id = phase.id, label = phase.name, status = phase.status)
            },
            actors = emptyList(),
        )

        /**
         * run 时间字段双形态宽容解析（lastActivityAt 同口径：毫秒数优先，ISO 字符串兜底；
         * startedAt/updatedAt 未在桌面 §10 词表取证——缺席返回 null，排序回退表序）。
         * epoch 毫秒。（iOS workflowRunTimestamp :3480-3495 直译）
         */
        fun workflowRunTimestamp(dict: Map<String, JSONValue>, key: String): Long? {
            // 数值时间戳：>1e11 视为毫秒（含带小数的毫秒），>1e8 视为秒；更小值不认（防脏数据）。
            // 返回恒 epoch 毫秒（Swift Date → Long 毫秒映射约定）
            val value = dict[key] ?: return null
            val numeric = value.doubleValue
            if (numeric != null && numeric > 0) {
                if (numeric > 100_000_000_000) return numeric.toLong()
                if (numeric > 100_000_000) return (numeric * 1000).toLong()
            }
            value.stringValue?.let { iso ->
                try {
                    return java.time.Instant.parse(iso).toEpochMilli()
                } catch (e: Exception) {
                    // fallthrough
                }
                try {
                    return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    // fallthrough
                }
            }
            return null
        }

        /**
         * run 负载 → 只读投影（G-008 增强：五态原词/actors 子代理实例/阶段链按声明表推导/
         * 容量计数/concurrency/truncated/resumable 透传；宽容解析，无阶段且无节点返回 nil）。
         * （iOS parseWorkflowRun :3497-3700 直译；阶段行展开 = 上游 instance-phases.ts
         * phasesOf 同构——「未分组」承接站与隐式「工作流」单站兜底 2026-10-08 取证）
         */
        fun parseWorkflowRun(json: JSONValue): WorkflowRunSummary? {
            val dict = json.objectValue ?: return null
            val id = dict["runId"]?.stringValue
                ?: dict["workflowRunId"]?.stringValue
                ?: dict["id"]?.stringValue
                ?: "workflow-run"
            val name = dict["name"]?.stringValue
                ?: dict["workflowName"]?.stringValue
                ?: dict["title"]?.stringValue
                ?: dict["displayName"]?.stringValue
                ?: "工作流"
            val rawStatus = dict["status"]?.stringValue ?: "pending"
            val currentPhase = dict["currentPhase"]?.stringValue

            // 阶段链（阶段三表取一）：① 声明表 phases[{name,rounds}] → phaseNames[]；
            // ② 退化 = 已进入站（节点 phaseName 首现序）+ 当前站
            val stationNames = ArrayList(
                (dict["phases"]?.arrayValue ?: emptyList()).mapNotNull { it.objectValue?.get("name")?.stringValue },
            )
            if (stationNames.isEmpty()) {
                stationNames.addAll(
                    (dict["phaseNames"]?.arrayValue ?: emptyList()).mapNotNull { it.stringValue },
                )
            }
            val rawNodes = dict["nodes"]?.arrayValue ?: emptyList()
            if (stationNames.isEmpty()) {
                val seen = ArrayList<String>()
                for (node in rawNodes) {
                    val phaseName = node.objectValue?.get("phaseName")?.stringValue
                    if (!phaseName.isNullOrEmpty() && !seen.contains(phaseName)) {
                        seen.add(phaseName)
                    }
                }
                if (currentPhase != null && !seen.contains(currentPhase)) {
                    seen.add(currentPhase)
                }
                stationNames.addAll(seen)
            }

            // 每站状态：节点证据优先（outcome failed/cancelled → failed；七相位中
            // dispatched/executing/waiting/repairing/nudged → running；全部 settled ok → done），
            // 无节点时控制流已进入（currentPhase）→ running，否则 pending
            val nodes = ArrayList<WorkflowNodeSummary>()
            for ((index, station) in stationNames.withIndex()) {
                val stationNodes = rawNodes.filter { it.objectValue?.get("phaseName")?.stringValue == station }
                var status = WorkflowStepStatus.pending
                if (stationNodes.isNotEmpty()) {
                    val failed = stationNodes.any {
                        val outcome = it.objectValue?.get("outcome")?.stringValue
                        outcome == "failed" || outcome == "cancelled"
                    }
                    when {
                        failed -> status = WorkflowStepStatus.failed
                        stationNodes.any {
                            val phase = it.objectValue?.get("phase")?.stringValue
                            phase == "dispatched" || phase == "executing" || phase == "waiting" ||
                                phase == "repairing" || phase == "nudged"
                        } -> status = WorkflowStepStatus.running
                        stationNodes.all {
                            it.objectValue?.get("phase")?.stringValue == "settled" &&
                                it.objectValue?.get("outcome")?.stringValue == "ok"
                        } -> status = WorkflowStepStatus.done
                    }
                } else if (currentPhase == station) {
                    status = WorkflowStepStatus.running
                }
                nodes.add(
                    WorkflowNodeSummary(
                        id = "station-$index-$station",
                        label = station,
                        status = status,
                        isSubagent = false,
                        summary = null,
                    ),
                )
            }

            // 子代理实例（actors[]：waiting|running|completed + phaseName）
            val actors = (dict["actors"]?.arrayValue ?: emptyList()).mapNotNull { actor ->
                val d = actor.objectValue ?: return@mapNotNull null
                val siteId = d["siteId"]?.stringValue ?: return@mapNotNull null
                val ordinal = d["ordinal"]?.intValue ?: return@mapNotNull null
                val tasksTotal = d["tasksTotal"]?.intValue
                    ?: d["nodesTotal"]?.intValue
                    ?: d["stations"]?.intValue
                val tasksSettled = d["tasksSettled"]?.intValue
                    ?: d["nodesSettled"]?.intValue
                WorkflowActorSummary(
                    id = "$siteId#$ordinal",
                    name = d["name"]?.stringValue,
                    rawStatus = d["status"]?.stringValue ?: "waiting",
                    phaseName = d["phaseName"]?.stringValue,
                    sessionId = d["sessionId"]?.stringValue ?: d["childSessionId"]?.stringValue,
                    tasksTotal = tasksTotal,
                    tasksSettled = tasksSettled,
                )
            }

            if (nodes.isEmpty() && actors.isEmpty()) return null

            // 无站/无戳兜底【实证·上游仓 instance-phases.ts phasesOf + workflow-graph
            // withImplicitPhase 同构，2026-10-08 取证】：
            // ① 全程无站（旧 CLI 无 phase() 标记）但有实例 → 合成隐式「工作流」单站承接，
            //    否则实例无处渲染、面板只剩头部；
            // ② 有站且有词汇（任一节点/实例带 phaseName 出生戳）、存在无戳实例（首个标记
            //    前出生，或旧 CLI 不带戳）→ 表头合成「未分组」站承接——无戳 ↔ 无名站是
            //    同一事实的两面。
            val hasVocabulary = actors.any { it.phaseName != null } ||
                rawNodes.any { it.objectValue?.get("phaseName")?.stringValue != null }
            val finalNodes = ArrayList(nodes)
            if (finalNodes.isEmpty() && actors.isNotEmpty()) {
                finalNodes.add(
                    WorkflowNodeSummary(
                        id = "station-implicit",
                        label = "工作流",
                        status = WorkflowStepStatus.mapRunStatus(rawStatus),
                    ),
                )
            } else if (hasVocabulary && finalNodes.isNotEmpty() && actors.any { it.phaseName == null }) {
                val unphased = actors.filter { it.phaseName == null }
                val unphasedStatus = when {
                    unphased.any { it.status == WorkflowStepStatus.running } -> WorkflowStepStatus.running
                    unphased.any { it.status == WorkflowStepStatus.failed } -> WorkflowStepStatus.failed
                    unphased.all { it.status == WorkflowStepStatus.done } -> WorkflowStepStatus.done
                    else -> WorkflowStepStatus.pending
                }
                finalNodes.add(
                    0,
                    WorkflowNodeSummary(
                        id = WorkflowRunSummary.unphasedStationID,
                        label = "未分组",
                        status = unphasedStatus,
                    ),
                )
            }

            // 子代理模型宽容解析（字符串直传；对象形态取 modelId|name|id）
            val subagentModel: String? = when (val raw = dict["subagentModel"]) {
                is JSONValue.Str -> raw.value
                is JSONValue.Obj ->
                    raw.fields["modelId"]?.stringValue
                        ?: raw.fields["name"]?.stringValue
                        ?: raw.fields["id"]?.stringValue
                else -> null
            }
            // workId：取消/恢复/设置命令的定位键（web 端 resumeWorkflowRun 以 runId 充当
            // workId；run 对象自带 workId 字段时优先）
            val workId = dict["workId"]?.stringValue ?: id
            // 完成卡 join 面三源（2026-10-09 工作流完成卡轮）：
            // tokens = usage.spentTokens；阶段数 = phases 表 → phaseNames → nodes 去重
            // phaseName（与 finalNodes 同序口径，取首源计数）；产物 brief = artifacts 元素的
            // id+bytes+itemCount（workflowRunArtifactSummarySchema 子集宽容读）
            val spentTokens = dict["usage"]?.objectValue?.get("spentTokens")?.intValue
            val phasesArray = dict["phases"]?.arrayValue
            val phaseNamesArray = dict["phaseNames"]?.arrayValue
            val phaseCount: Int? = if (!phasesArray.isNullOrEmpty()) {
                phasesArray.size
            } else if (!phaseNamesArray.isNullOrEmpty()) {
                // 首现序去重（与 nodes 退化同口径；正常发射不重复）
                val seen = HashSet<String>()
                var count = 0
                for (nameValue in phaseNamesArray) {
                    val n = nameValue.stringValue
                    if (!n.isNullOrEmpty() && seen.add(n)) count += 1
                }
                if (count == 0) null else count
            } else {
                // 节点即阶段站（label=display 阶段名）——去重计数
                val distinct = finalNodes.map { it.label }.toSet()
                if (distinct.isEmpty()) null else distinct.size
            }
            val artifactBriefs = (dict["artifacts"]?.arrayValue ?: emptyList()).mapNotNull { item ->
                val d = item.objectValue ?: return@mapNotNull null
                val aid = d["id"]?.stringValue?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                WorkflowArtifactBrief(
                    id = aid,
                    bytes = d["bytes"]?.intValue,
                    itemCount = d["itemCount"]?.intValue,
                )
            }
            return WorkflowRunSummary(
                id = id,
                name = name,
                rawStatus = rawStatus,
                stopReason = dict["stopReason"]?.stringValue,
                resumable = dict["resumable"]?.boolValue ?: false,
                truncated = dict["truncated"]?.boolValue ?: false,
                nodes = finalNodes,
                actors = actors,
                artifactsCount = dict["artifacts"]?.arrayValue?.size ?: 0,
                spentTokens = spentTokens,
                phaseCount = phaseCount,
                artifactBriefs = artifactBriefs,
                pendingQuestionsCount = dict["pendingQuestions"]?.arrayValue?.size ?: 0,
                concurrency = dict["concurrency"]?.intValue
                    ?: dict["concurrency"]?.objectValue?.get("active")?.intValue,
                concurrencyCeiling = dict["concurrencyCeiling"]?.intValue
                    ?: dict["concurrency"]?.objectValue?.get("ceiling")?.intValue
                    ?: dict["maxConcurrency"]?.intValue,
                subagentModel = subagentModel,
                cancellable = dict["cancellable"]?.boolValue ?: true,
                workId = workId,
                hasPhaseVocabulary = hasVocabulary,
                startedAt = workflowRunTimestamp(dict, "startedAt"),
                updatedAt = workflowRunTimestamp(dict, "updatedAt"),
            )
        }

        /**
         * membership/归档拉取的 scope 清单（去重；pin 与 archive 各半——web 两集合同 scope 面）。
         * 返回 (path, identity?, pinned) 三元组列表。（iOS membershipScopes :362-386 直译）
         */
        fun membershipScopes(
            all: List<StoreWorkspaceInfo>,
            bootstrap: List<StoreWorkspaceInfo>,
            current: StoreWorkspaceInfo,
        ): List<Triple<String, String?, Boolean>> {
            val paths = ArrayList<String>()
            val seen = HashSet<String>()
            for (ws in all) {
                if (seen.add(ws.path)) paths.add(ws.path)
            }
            for (ws in bootstrap) {
                if (seen.add(ws.path)) paths.add(ws.path)
            }
            if (!seen.contains(current.path)) paths.add(current.path)
            // 每个路径两问：listPinnedTasks + listArchivedTasks（pin 在前）
            return paths.flatMap { path ->
                var identity = all.firstOrNull { it.path == path }?.workspaceIdentity
                    ?: bootstrap.firstOrNull { it.path == path }?.workspaceIdentity
                if (identity == null && path == current.path) {
                    identity = current.workspaceIdentity
                }
                listOf(Triple(path, identity, true), Triple(path, identity, false))
            }
        }

        /**
         * 项目归属元数据拉取的 scope 清单（去重；与 membershipScopes 同一路径源，
         * 只取 (path, identity?) 二元组——每个工作区一次 listTaskList）。
         */
        fun projectScopes(
            all: List<StoreWorkspaceInfo>,
            bootstrap: List<StoreWorkspaceInfo>,
            current: StoreWorkspaceInfo,
        ): List<Pair<String, String?>> {
            val paths = ArrayList<String>()
            val seen = HashSet<String>()
            for (ws in all) {
                if (seen.add(ws.path)) paths.add(ws.path)
            }
            for (ws in bootstrap) {
                if (seen.add(ws.path)) paths.add(ws.path)
            }
            if (!seen.contains(current.path)) paths.add(current.path)
            return paths.map { path ->
                var identity = all.firstOrNull { it.path == path }?.workspaceIdentity
                    ?: bootstrap.firstOrNull { it.path == path }?.workspaceIdentity
                if (identity == null && path == current.path) {
                    identity = current.workspaceIdentity
                }
                path to identity
            }
        }
    }
}

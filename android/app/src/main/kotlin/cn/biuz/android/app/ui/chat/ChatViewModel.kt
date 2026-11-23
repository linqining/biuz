package cn.biuz.android.app.ui.chat

// ChatViewModel（iOS ChatViewModel.swift 的连接态投影/发送/面板面直译，绑定 :store 会话流）。
//
// 换源重绑纪律（AppSession.StoreSurface 契约）：Store 装配面以 epoch 换源（装配成功/teardown
// 各 +1），ViewModel 以 store **实例**为重绑判据——attach 同实例幂等短路，异实例先取消旧
// 收集 Job 再整体重置投影（防旧 Store 残影混入新源；iOS .task(id: storeEpoch) 环境值换绑
// 同语义）。会话事件流（ConversationEvent）→ reduceMessages 纯函数差分（StateFlow 投影唯一
// 写入口），渲染层 LazyColumn 以 message.id 为 key 增量复用。
//
// 发送面（v1.33④ + U-6 + §5-12）：
// - 斜杠意图客户端拦截子集本批仅 /plan（任务点名 v1.33④）；/goal /compact 未拦——原文直发
//   由桌面 agent 解释（iOS parseSlashIntent「未命中返回 nil = 原文直发」同口径）。
// - /plan 分支：先切 plan 模式（switchCollaborationMode CAS），**失败/未知态阻断发送**——
//   任务以旧模式进桌面 = 真机报障「/plan 发出没触发」的根因（iOS dispatchSlashIntent
//   :1176-1187 逐字语义）；拒因落常驻错误行（collaborationModeFailure）+ 草稿回填。
// - 普通发送失败：草稿回填（仅当用户尚未重新输入）+ 持久错误行（sendFailure，服务层
//   拒因原文透传，禁假成功）——不在 VM 内 3s 自动消失（U-6 数据安全提示必须显式关闭）。
//
// 面板呈现（openComposerPanel，iOS ChatView.swift:2172-2190 直译）：**先撤 composer 焦点
// 再呈现**——焦点在场时面板与键盘收起转场同事务互撞会被吞（「点了没反应」根因）；
// 撤焦先行 + 120ms 延迟呈现（撤焦回调由 UI 层注入 LocalFocusManager，VM 零 Android 依赖，
// JVM 单测可驱动）。关闭单路径 closeComposerPanel（§5-15 呈现期捕获口径的 VM 侧对位）。
//
// VM 内不落任何用户可见文案（stringResource 由 UI 层承担；服务层拒因原文原样透传）——
// 本类零 Android 依赖，:app 的 testDebugUnitTest 直测。

import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.PlanPanelSummary
import cn.biuz.android.protocol.RemoteGoalSummary
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.protocol.SubagentSessionSummary
import cn.biuz.android.protocol.WorkflowRunSummary
import cn.biuz.android.store.AttachmentUploadService
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.ConversationQueueInfo
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.ModelSelectionInfo
import cn.biuz.android.store.NewSessionModelSelection
import cn.biuz.android.store.OptionalArg
import cn.biuz.android.store.PendingAttachment
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    /** 路由会话 id（桌面端规范 sessionId）。 */
    val conversationId: String,
    /** VM 生命周期宿主作用域（UI 层 rememberCoroutineScope / 测试 TestScope 注入）。 */
    private val scope: CoroutineScope,
    /** 撤焦→面板呈现延迟（毫秒；iOS 120ms 用户不可感知窗，测试注入 0 直驱）。 */
    private val panelDelayMs: Long = PANEL_DELAY_MS,
    /** 斜杠提示自动清除窗（毫秒；iOS showSlashHint 3s，测试注入虚拟时距）。 */
    private val hintClearMs: Long = SLASH_HINT_CLEAR_MS,
    /** 时钟源（协作模式 3s 本地切换护栏计时；测试注入虚拟时钟）。 */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** 上传 hint 镜像同步延迟（毫秒；service 自身 4s 自动清除后再读回真值——测试注入 0 直驱）。 */
    private val uploadHintSyncMs: Long = UPLOAD_HINT_SYNC_MS,
) {

    companion object {
        /** 撤焦→呈现延迟（iOS ChatView.swift:2187：120_000_000 ns）。 */
        const val PANEL_DELAY_MS = 120L

        /** 输入预测防抖窗（spec 手机端建议② 600–800ms 中值；iOS ChatViewModel :1355 700ms 同值）。 */
        const val INPUT_PREDICTION_DEBOUNCE_MS = 700L

        /** 斜杠提示 3s 自动清除（iOS ChatViewModel.swift:1112）。 */
        const val SLASH_HINT_CLEAR_MS = 3_000L

        /** 协作模式本地切换护栏：ack 权威成功后 3s 内旧投影不回闪（iOS :289-293）。 */
        const val MODE_SWITCH_GUARD_MS = 3_000L

        /**
         * 协作模式词表（switchCollaborationMode mode 值域【实证·web bundle v1.12】：
         * `La(["build","edit","plan","yolo"])` 4 档全集；初值兜底 build（web 同构）。
         */
        val COLLABORATION_MODES = listOf("build", "edit", "plan", "yolo", "guarded")
        const val DEFAULT_COLLABORATION_MODE = "build"

        /** 模型切换本地收敛护栏：与协作模式同窗 3s（iOS ChatViewModel lastLocalModelSwitch 同值）。 */
        const val MODEL_SWITCH_GUARD_MS = 3_000L

        /**
         * 上传 hint 镜像同步延迟（service.HINT_CLEAR_MS=4s 自动清除窗 + 200ms 余量——
         * 同步时读 service.hint 真值，新提示/已清空都如实镜像）。
         */
        const val UPLOAD_HINT_SYNC_MS = 4_200L

        /** 控制动作后首次回读 run 延迟（iOS scheduleRunRefresh 1.2s 同值）。 */
        const val RUN_REFRESH_FIRST_MS = 1_200L

        /** 首次回读到二次回读的间隔（iOS 2.5s 同值；总窗 3.7s）。 */
        const val RUN_REFRESH_SECOND_GAP_MS = 2_500L

        /**
         * 思考档静态梯（iOS ChatView.fallbackThoughtLevels :2217 同值直译）：getView 不携带
         * 词表、workspace-config 为空的远端环境兜底——不支持的档位由桌面端校验拒绝并提示。
         */
        val FALLBACK_THOUGHT_LEVELS = listOf("off", "minimal", "low", "medium", "high", "max")

        /**
         * 新建会话提交门判定（iOS NewConversationSheet.submitGateBlocked :279-282 直译；
         * 可测静态）：「明确做过选择但解析不出完整选择」= 拦截弹「用默认模型继续」确认
         * （禁静默裸发——此前「选了等于没选」：桌面按默认开跑、用户侧零反馈）；
         * 纯未选择（无行键无思考档）= 直通桌面默认，不拦。
         */
        fun submitGateBlocked(
            rowKey: String?,
            thought: String?,
            resolved: NewSessionModelSelection?,
        ): Boolean = resolved == null && (rowKey != null || thought != null)

        /**
         * 会话前选择组装（iOS pendingModelSelection :1063-1072 直译）：上游
         * modelSelectionSchema strict 且 providerId/modelId trim min(1)——provider 映射缺失
         * 时整条 createSession 被拒。未选模型或 provider 缺失一律返回 null；返回 null 不等于
         * 静默裸发：[submitGateBlocked] 对「明确做过选择但解析不出」弹确认。
         */
        fun resolveNewSessionSelection(
            info: ModelSelectionInfo?,
            selection: NewSessionDraftSelection,
        ): NewSessionModelSelection? {
            val model = selection.selectedModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val provider = info?.providerForRowKey(selection.selectedModelRowKey, model)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val thought = selection.selectedThought?.trim()?.takeIf { it.isNotEmpty() }
                ?: info?.activeThoughtLevel?.trim()?.takeIf { it.isNotEmpty() }
                ?: ""
            return NewSessionModelSelection(providerId = provider, modelId = model, reasoningLevel = thought)
        }
    }

    // MARK: 斜杠意图（iOS parseSlashIntent :1127-1159 直译；web sX 解析器同构的客户端拦截子集）

    sealed class SlashIntent {
        data class Goal(val objective: String) : SlashIntent()

        data class Plan(val task: String) : SlashIntent()

        data object Compact : SlashIntent()
    }

    /** 斜杠意图解析（纯函数；未命中返回 null = 原文直发由桌面解释）。 */
    fun parseSlashIntent(raw: String): SlashIntent? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("/")) return null
        val body = trimmed.drop(1)
        val parts = body.split(" ", limit = 2)
        val name = (parts.firstOrNull() ?: "").lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""
        return when (name) {
            "plan" -> SlashIntent.Plan(task = arg) // 空任务 = 仅切模式（web planShortcut !task → sent）
            "compact", "compress" -> SlashIntent.Compact
            "goal", "target" -> {
                // 空目标与 pause/clear/show/resume 子命令不拦截（原文直发由桌面解释）
                if (arg.isEmpty()) return null
                val goalWords = arg.split(" ", limit = 2)
                val firstWord = (goalWords.firstOrNull() ?: "").lowercase()
                if (firstWord in setOf("pause", "clear", "show", "resume")) return null
                // /goal replace <文本> → 替换 = 直接下发新目标（首词整词匹配，
                // "replaces" 等前缀撞词不得误剥；裸 replace 无正文不拦截）
                if (firstWord == "replace") {
                    val replaced = goalWords.getOrNull(1)?.trim() ?: return null
                    if (replaced.isEmpty()) return null
                    return SlashIntent.Goal(objective = replaced)
                }
                SlashIntent.Goal(objective = arg)
            }
            else -> null
        }
    }

    // MARK: 投影 StateFlow（会话流绑定 :store 的唯一 UI 面）

    /** 消息流投影（store 事件经 reduceMessages 差分应用；换源重绑时整体重置）。 */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> get() = _messages

    /** 会话加载失败文本（读面失败与 last-good 共存 §5-12；null = 正常空会话）。 */
    private val _loadFailure = MutableStateFlow<String?>(null)
    val loadFailure: StateFlow<String?> get() = _loadFailure

    /** 待处理交互投影（审批/提问/计划三卡 + hook 审核数据源；composer 上方常驻）。 */
    private val _pendingInteractions = MutableStateFlow<List<RemotePendingInteraction>>(emptyList())
    val pendingInteractions: StateFlow<List<RemotePendingInteraction>> get() = _pendingInteractions

    /** 协作模式（初值本机兜底 build；state.config.mode 投影到达即被桌面真值覆盖）。 */
    private val _collaborationMode = MutableStateFlow(DEFAULT_COLLABORATION_MODE)
    val collaborationMode: StateFlow<String> get() = _collaborationMode

    private val _collaborationModeFailure = MutableStateFlow<CollabModeFailure?>(null)
    val collaborationModeFailure: StateFlow<CollabModeFailure?> get() = _collaborationModeFailure

    /** 发送失败持久错误行数据源（服务层拒因原文；UI 组装资源文案，重试/编辑/成功即清）。 */
    private val _sendFailureReason = MutableStateFlow<String?>(null)
    val sendFailureReason: StateFlow<String?> get() = _sendFailureReason

    /** 斜杠/命令反馈行（3s 自动清除；PlanBlocked 等枚举由 UI 映射资源文案）。 */
    private val _slashHint = MutableStateFlow<SlashHint?>(null)
    val slashHint: StateFlow<SlashHint?> get() = _slashHint

    /** composer 草稿（发送先清稿、失败回填 U-6）。 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> get() = _draft

    // MARK: 3.17.2 输入预测 ghost hint（iOS ChatViewModel :1345-1390 直译）

    /**
     * 灰字续写 hint（decision=="show" 首候选；点按采纳拼到草稿尾部，不 trim）。
     * 任何异常 / 非 show 决策 = null，不提示用户【实证·上游仓
     * docs/web-remote-control/mobile-input-prediction.md】。
     */
    private val _inputHint = MutableStateFlow<String?>(null)
    val inputHint: StateFlow<String?> get() = _inputHint

    /** 在途防抖 Job（新输入即取消——不再匹配当前草稿）。 */
    private var predictionJob: Job? = null

    /** 请求序号（requestId "r-N"；迟到回包按非最新丢弃，spec 手机端建议③）。 */
    private var predictionSeq = 0

    val isSending: StateFlow<Boolean> get() = _sending
    private val _sending = MutableStateFlow(false)

    /** 在呈现的 composer 面板（null = 无；单路径关闭）。 */
    private val _composerPanel = MutableStateFlow<ComposerPanelKind?>(null)
    val composerPanel: StateFlow<ComposerPanelKind?> get() = _composerPanel

    /**
     * 权威成功决议后本地遮罩的交互 id 集（独立 StateFlow：桌面投影移除前 UI 据此撤卡
     * 防卡死；投影移除后随之出账——「已受理」行的兜底半边）。
     */
    private val _decidedInteractionIds = MutableStateFlow<Set<String>>(emptySet())
    val decidedInteractionIds: StateFlow<Set<String>> get() = _decidedInteractionIds

    /**
     * 盖章停驻（motion-spec，朱批品牌升级 2026-11-21）：ACK 权威成功后卡面落印
     * 「照办/不准」并停驻 700ms 再入账 [decidedInteractionIds] 收卡——替代即时撤卡。
     * duplicate ack 去重：同一交互首次权威成功定案，重试不重置停驻。
     */
    data class StampHold(val interaction: RemotePendingInteraction, val approved: Boolean)
    private val _stampHolds = MutableStateFlow<Map<String, StampHold>>(emptyMap())
    val stampHolds: StateFlow<Map<String, StampHold>> get() = _stampHolds

    /**
     * Store 能力标志（远端 Store 为 true）。**不再用于 UI 门禁**：2026-11-23 对齐
     * iOS `canSend`/web composer——远端会话可正常发送（只读边界由桌面端 ReadOnlyGate
     * 在 RPC 出口强制，不在 UI 侧禁发）；历史实现曾把它当「只读模式」禁用发送/附件，
     * 与 iOS/web 不一致（真机「桌面端只读 · 发送不可用」误报）。
     */
    var isReadOnly: Boolean = false
        private set

    /** 当前绑定的 Store 实例（换源重绑判据；null = 未 attach）。 */
    var attachedStore: ConversationStore? = null
        private set

    private var collectJob: Job? = null

    /** 协作模式失败面（VM 零文案：枚举由 UI 映射 stringResource；服务层拒因原样透传）。 */
    sealed class CollabModeFailure {
        /** status 缺席/词表外——本机不落盘，以桌面实际模式为准（投影到达即定案）。 */
        data object UnknownAckState : CollabModeFailure()

        /** 六态词表内失败：CommandAck.failureText（reasonCode + message 原文）。 */
        data class Rejected(val text: String) : CollabModeFailure()
    }

    /** 斜杠/控制命令反馈面（VM 零文案；UI 映射 stringResource / Raw 直出）。 */
    sealed class SlashHint {
        /** /plan 切换失败 → 任务未发送（阻断 + 草稿回填）。 */
        data object PlanBlocked : SlashHint()

        /** /plan（空任务）仅切模式成功。 */
        data object PlanSwitched : SlashHint()

        /** 服务层原文直出（goal 等真实拒因/回执文案）。 */
        data class Raw(val text: String) : SlashHint()
    }

    /** 交互决议失败面（审批/提问/计划卡错误行；不 dismiss、控件恢复）。 */
    sealed class InteractionFailure {
        /** ack null：未送达（断线/未连接）→「审批未送达 · 连接恢复后再试」口径。 */
        data object Undelivered : InteractionFailure()

        /** 六态词表内失败：拒因原文（reasonCode + message）。 */
        data class Rejected(val text: String) : InteractionFailure()

        /** status 缺席/词表外：未知态（不撤卡，交投影定案）。 */
        data object UnknownState : InteractionFailure()
    }

    /** composer 面板种类（协作模式 / 模型 / 思考档；呈现统一走撤焦先行 + 延迟）。 */
    enum class ComposerPanelKind { Collaboration, Model, Thought }

    // MARK: 模型面板（chips + getView 词表 + 乐观落 chip 护栏；iOS ChatViewModel v1.36/37 直译）

    /**
     * 桌面端模型选择视图（getView 投影 + 会话级 overlay 合成后的单显示源；null = 尚未
     * 拉到。chips/面板同读此流：activeModel/activeThoughtLevel/activeProviderId 由
     * 权威成功回执乐观改写，桌面投影在护栏窗后接管）。
     */
    private val _modelSelection = MutableStateFlow<ModelSelectionInfo?>(null)
    val modelSelection: StateFlow<ModelSelectionInfo?> get() = _modelSelection

    /** 当前 chip 模型的思考档词表（store.thoughtLevels；空 = 退化静态梯，UI 层拼接）。 */
    private val _thoughtLevels = MutableStateFlow<List<String>>(emptyList())
    val thoughtLevels: StateFlow<List<String>> get() = _thoughtLevels

    /** 模型切换失败/未知态常驻错误行（nil = 无在案；权威成功即清）。 */
    sealed class ModelSwitchFailure {
        /** provider 解析不出（getView 缺席且非当前模型）——本地拦截禁空串裸发。 */
        data object ProviderUnavailable : ModelSwitchFailure()

        /** ack null：未送达（断线/未连接）。 */
        data object Undelivered : ModelSwitchFailure()

        /** status 缺席/词表外：未知态不乐观落 chip，登记待定交投影定案。 */
        data object UnknownAckState : ModelSwitchFailure()

        /** 六态词表内失败：CommandAck.failureText（reasonCode + message 原文）。 */
        data class Rejected(val text: String) : ModelSwitchFailure()
    }

    private val _modelSwitchFailure = MutableStateFlow<ModelSwitchFailure?>(null)
    val modelSwitchFailure: StateFlow<ModelSwitchFailure?> get() = _modelSwitchFailure

    /** 模型切换护栏记录（乐观值 + 时刻；3s 内旧投影不回闪，iOS :966-977 同口径）。 */
    private data class ModelSwitchRecord(val model: String, val thought: String?, val at: Long)

    private var lastLocalModelSwitchRecord: ModelSwitchRecord? = null

    /** 未知态回执待定标记：会话级 state.modelSelection 投影首个到达即定案并清失败行。 */
    private var pendingUnknownModelSelection = false

    // MARK: 排队队列（state.queue 投影 + 五件命令面；iOS ChatViewModel queueAction 同构）

    /** 排队队列只读投影（null = 无排队）。 */
    private val _queueInfo = MutableStateFlow<ConversationQueueInfo?>(null)
    val queueInfo: StateFlow<ConversationQueueInfo?> get() = _queueInfo

    /** 队列操作失败面（常驻错误行；成功即清——失败 3s 气泡一律改常驻的任务口径）。 */
    sealed class QueueActionFailure {
        data object Undelivered : QueueActionFailure()

        data object UnknownState : QueueActionFailure()

        data class Rejected(val text: String) : QueueActionFailure()
    }

    private val _queueActionFailure = MutableStateFlow<QueueActionFailure?>(null)
    val queueActionFailure: StateFlow<QueueActionFailure?> get() = _queueActionFailure

    // MARK: 会话面板投影（goal/plan/工作流/btw 后台/side 子代理；iOS ChatViewModel :73-97 直译）

    /**
     * 桌面 workflow run 多 run 列表（store.workflowRuns 仲裁后投影：活 run 优先、
     * stale 降级、同名取最新活——「取消按钮取活 run」的 store 侧保证：面板每张 run 卡
     * 的取消打到各自 workId，且滞留活态旧 run 已被 store 降级 stopped，命令不再打到
     * 死 workId【实证·上游仓 command.ts:221 cancelBackgroundWork {workId}】）。
     */
    private val _workflowRuns = MutableStateFlow<List<WorkflowRunSummary>>(emptyList())
    val workflowRuns: StateFlow<List<WorkflowRunSummary>> get() = _workflowRuns

    /** 主 run（活优先首个；chips 徽标与完成卡四格 join 兜底用）（iOS :75）。 */
    val workflowRun: WorkflowRunSummary? get() = _workflowRuns.value.firstOrNull()

    private val _goalSummary = MutableStateFlow<RemoteGoalSummary?>(null)
    val goalSummary: StateFlow<RemoteGoalSummary?> get() = _goalSummary

    private val _planPanel = MutableStateFlow<PlanPanelSummary?>(null)
    val planPanel: StateFlow<PlanPanelSummary?> get() = _planPanel

    private val _backgroundWorks = MutableStateFlow<List<BackgroundWorkSummary>>(emptyList())
    val backgroundWorks: StateFlow<List<BackgroundWorkSummary>> get() = _backgroundWorks

    private val _subagentSessions = MutableStateFlow<List<SubagentSessionSummary>>(emptyList())
    val subagentSessions: StateFlow<List<SubagentSessionSummary>> get() = _subagentSessions

    /**
     * 当前会话行投影（聊天页顶迷你轨道数据源 = 行自带 workflowActivity，G-007；
     * 初值 cachedConversation 内存摘要，随后列表事件逐条覆盖）。
     */
    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> get() = _currentConversation

    // MARK: 面板控制命令（pauseGoal / cancelBackgroundWork / resumeWorkflowRun / amendWorkflowRunSettings）

    /** 面板控制命令失败面（常驻错误行数据源；成功提示走 [ControlFeedback.Success] 3s 自清）。 */
    sealed class ControlFailure {
        /** ack null：未送达（断线/未连接）。 */
        data object Undelivered : ControlFailure()

        /** status 缺席/词表外：未知态（交投影定案）。 */
        data object UnknownState : ControlFailure()

        /** 六态词表内失败：CommandAck.failureText（reasonCode + message 原文）。 */
        data class Rejected(val text: String) : ControlFailure()
    }

    /** 面板控制成功反馈（UI 显资源文案 3s 自清；VM 零文案纪律）。 */
    data object ControlSuccessHint

    private val _controlFailure = MutableStateFlow<ControlFailure?>(null)
    val controlFailure: StateFlow<ControlFailure?> get() = _controlFailure

    private val _controlSuccess = MutableStateFlow(false)
    val controlSuccess: StateFlow<Boolean> get() = _controlSuccess

    /**
     * 面板控制命令统一收口（iOS controlFeedback :549-557 + recordControlDiag 直译）：
     * ack 三态映射；权威成功静默落 [ControlSuccessHint]（UI 3s 自清），失败落常驻
     * 错误行（下次动作或 detach 清——「失败 3s 气泡一律改常驻」任务口径）。
     * 成功后回读 run 投影（amend 类追加全量 resync——桌面停旧换新，新 run 可能只在
     * 重发快照的 state.workflowRuns 里）（iOS scheduleRunRefresh :570-585 直译）。
     */
    private suspend fun controlAction(
        resync: Boolean = false,
        action: suspend (ConversationStore) -> JSONValue?,
    ): ControlFailure? {
        val store = attachedStore
        val ack = store?.let { action(it) }
        val commandAck = CommandAck(ack)
        val failure = when {
            ack == null -> ControlFailure.Undelivered
            commandAck.isFailure -> ControlFailure.Rejected(commandAck.failureText ?: "桌面端拒绝")
            commandAck.isAuthoritativeSuccess -> null
            else -> ControlFailure.UnknownState
        }
        _controlFailure.value = failure
        if (failure == null) {
            _controlSuccess.value = true
            scope.launch {
                delay(hintClearMs)
                _controlSuccess.value = false
            }
        }
        scheduleRunRefresh(resync = resync)
        return failure
    }

    /** 目标暂停/继续（state.goal 只读回显取反；无 goal = 本地拒绝不下发）（iOS toggleGoalPause :102-107）。 */
    suspend fun toggleGoalPause(): ControlFailure? {
        val goal = _goalSummary.value
            ?: return ControlFailure.Rejected("当前会话没有可暂停的目标").also {
                _controlFailure.value = it
            }
        return controlAction { store ->
            store.setGoalPaused(paused = !goal.isPaused, conversationId = conversationId)
        }
    }

    /** 取消后台工作 / run（btw 面板与 workflow 卡控制面；workId 由调用方取活 run 卡自带值）。 */
    suspend fun cancelWork(workId: String): ControlFailure? =
        controlAction { store -> store.cancelWork(conversationId, workId) }

    /** 恢复工作流运行（stopped+resumable 卡；name 喂恢复后完成通知主题）。 */
    suspend fun resumeWork(workId: String, name: String?): ControlFailure? =
        controlAction { store -> store.resumeWorkflowRun(conversationId, workId, name) }

    /**
     * 子代理模型修改（amendWorkflowRunSettings.subagentModel；null = 跟随主模型）。
     * 桌面语义：对运行中 run 改设置 = 停旧 run → 以新设置重启（新 runId）——成功也提示
     * 「已下发」，回执后 run 由 resync 换新（iOS setSubagentModel :129-140 直译）。
     * workId = run 卡自带定位键（store.workflowRuns 仲裁后的活 run；UI 层以
     * `run.workId ?: run.id` 传入）。
     */
    suspend fun setSubagentModel(model: String?, workId: String): ControlFailure? =
        controlAction(resync = true) { store ->
            store.amendWorkflowRunSettings(
                conversationId = conversationId,
                workId = workId,
                subagentModel = OptionalArg(model),
                maxConcurrency = null,
            )
        }

    /** 并发 agent 数修改（amendWorkflowRunSettings.maxConcurrency；null = 解除上限）。 */
    suspend fun setMaxConcurrency(limit: Int?, workId: String): ControlFailure? =
        controlAction(resync = true) { store ->
            store.amendWorkflowRunSettings(
                conversationId = conversationId,
                workId = workId,
                subagentModel = null,
                maxConcurrency = OptionalArg(limit),
            )
        }

    /**
     * 控制动作后延迟回读 run（state 事件未及时到达时按钮态也能跟上）；
     * 三段式——1.2s / 3.7s 双次回读 + amend 类动作追加全量 resync（iOS :570-585 同值）。
     */
    private fun scheduleRunRefresh(resync: Boolean) {
        val store = attachedStore ?: return
        scope.launch {
            delay(RUN_REFRESH_FIRST_MS)
            _workflowRuns.value = store.workflowRuns(conversationId)
            if (resync) {
                runCatching { store.triggerResync(conversationId) }
            }
            delay(RUN_REFRESH_SECOND_GAP_MS)
            _workflowRuns.value = store.workflowRuns(conversationId)
        }
    }

    /** 子代理只读转录（actor.sessionId → store 只读拉一页；页大小 200 = iOS 同值 :1306-1309）。 */
    suspend fun actorTranscript(sessionId: String): List<ChatMessage> =
        attachedStore?.actorTranscript(sessionId = sessionId, limit = 200) ?: emptyList()

    /**
     * 子代理转录下钻 sheet 态（工作流 actor 行 / 子代理面板行共用；null = 收起）。
     * 打开即清空旧内容 + loading，拉取完成后原位呈现（iOS openTranscript :329-335 同构）。
     */
    data class ActorTranscriptSheetState(
        val title: String?,
        val sessionId: String,
        val loading: Boolean,
        val messages: List<ChatMessage>,
    )

    private val _actorTranscriptSheet = MutableStateFlow<ActorTranscriptSheetState?>(null)
    val actorTranscriptSheet: StateFlow<ActorTranscriptSheetState?> get() = _actorTranscriptSheet

    /** 打开子代理转录（sessionId 为 null 的实例行无下钻入口——渲染层已禁用，此处防御性忽略）。 */
    fun openActorTranscript(title: String?, sessionId: String?) {
        val target = sessionId ?: return
        _actorTranscriptSheet.value =
            ActorTranscriptSheetState(title = title, sessionId = target, loading = true, messages = emptyList())
        scope.launch {
            val messages = actorTranscript(target)
            // 关闭竞态守卫：sheet 已被用户关掉则丢弃结果
            if (_actorTranscriptSheet.value?.sessionId == target) {
                _actorTranscriptSheet.value =
                    ActorTranscriptSheetState(title = title, sessionId = target, loading = false, messages = messages)
            }
        }
    }

    /** 转录 sheet 关闭唯一路径（§5-15 单路径口径）。 */
    fun closeActorTranscript() {
        _actorTranscriptSheet.value = null
    }

    // MARK: 待发附件（P1-1：AttachmentUploadService 随 VM 装配；iOS ChatViewModel init 同构）

    /**
     * 上传服务（attach 时按绑定 store + 会话 id 建；iOS init 中
     * `AttachmentUploadService(store:sessionID:)` 同构——附件事务须真实 sessionId）。
     * 进度投影：service 是命令式可变对象，UI 经 [uploadsVersion] 计数 + 快照读面驱动
     * 重组（Compose 无 @Observable 自动追踪——每次状态迁移点显式 bump）。
     */
    private var uploadsService: AttachmentUploadService? = null

    /** 上传状态版本计数（bump 驱动 UI 快照重读；入队/移除/进度回调/事务收尾各 +1）。 */
    private val _uploadsVersion = MutableStateFlow(0)
    val uploadsVersion: StateFlow<Int> get() = _uploadsVersion

    /** service.hint 镜像（add 拒绝提示同步镜像；4s 后回读 service 真值自清）。 */
    private val _uploadHint = MutableStateFlow<String?>(null)
    val uploadHint: StateFlow<String?> get() = _uploadHint

    private var uploadHintSyncJob: Job? = null

    private fun bumpUploads() {
        _uploadsVersion.value = _uploadsVersion.value + 1
    }

    /** service.hint 镜像到 StateFlow（4s 后回读 service 真值——清空/新提示都如实落）。 */
    private fun syncUploadHint() {
        _uploadHint.value = uploadsService?.hint
        val text = _uploadHint.value ?: return
        uploadHintSyncJob?.cancel()
        uploadHintSyncJob = scope.launch {
            delay(uploadHintSyncMs)
            _uploadHint.value = uploadsService?.hint
        }
    }

    /** 待发条快照（UI remember(uploadsVersion) 直读；service 无项返回空表）。 */
    fun uploadsSnapshot(): List<PendingAttachment> =
        uploadsService?.itemsSnapshot() ?: emptyList()

    /** 上传失败原文行（首个 failed 项 errorText 原文直出；null = 无在案失败）。 */
    fun uploadFailureText(): String? = uploadsService?.firstFailureText

    /** 发送闸（有待发且存在非 committed 项 = 发送钮禁用；iOS canSend `!uploads.blocksSend` 同口径）。 */
    fun uploadsBlocksSend(): Boolean = uploadsService?.blocksSend ?: false

    /**
     * 入队（UI 从选择器读出字节后调；iOS addPhotoPickerItems 直译）：
     * mime 缺席（contentResolver.getType 空的少数来源）按文件名后缀走 service 内置表兜底；
     * add 成功即建上传事务（选定即上传，不等发送——iOS startUploadIfNeeded 同口径）。
     */
    fun enqueueAttachment(name: String, mediaType: String, data: ByteArray): Boolean {
        val uploads = uploadsService ?: return false
        val resolvedMime = mediaType.ifEmpty {
            val ext = name.substringAfterLast('.', "")
            if (ext.isEmpty()) "application/octet-stream" else uploads.mediaTypeForFileExtension(ext)
        }
        val added = uploads.add(name = name, mediaType = resolvedMime, data = data)
        bumpUploads()
        syncUploadHint()
        if (added) {
            scope.launch {
                uploads.ensureAllCommitted()
                bumpUploads()
                syncUploadHint()
            }
        }
        return added
    }

    /** UI 侧读取失败写提示（iOS「照片读取失败，已跳过」通道；service 4s 自动清除）。 */
    fun setUploadHint(text: String) {
        uploadsService?.setHint(text)
        syncUploadHint()
    }

    /** 移除（未 committed 项尽力 AbortV4 收口由 service 承担；Abort 未送达 hint 异步落 service）。 */
    fun removeAttachment(id: UUID) {
        uploadsService?.remove(id)
        bumpUploads()
        syncUploadHint()
    }

    /**
     * 失败重试（同 uploadId 续传由 service 承担；此处派发收敛等待以便失败终态可呈现）。
     * service.retry 内部自派发与本收敛并行的幂等竞态无害（upload 对 uploading 短路）。
     */
    fun retryAttachment(id: UUID) {
        val uploads = uploadsService ?: return
        uploads.retry(id)
        bumpUploads()
        scope.launch {
            uploads.ensureAllCommitted()
            bumpUploads()
            syncUploadHint()
        }
    }

    // MARK: 换源重绑（store 按实例重绑；epoch 换源即新实例）

    /**
     * 绑定/换源重绑：同实例幂等短路；异实例（StoreSurface.epoch 换源后 conversation 即新
     * 实例）先取消旧收集 Job → 投影整体重置 → 重载初值 + 重挂事件流。
     */
    fun attach(store: ConversationStore) {
        if (store === attachedStore) return
        collectJob?.cancel()
        collectJob = null
        attachedStore = store
        isReadOnly = store.isReadOnly
        // 换源即重置投影（防旧 Store 残影混入新源）
        _messages.value = emptyList()
        // 切会话：作废在途预测并清 hint（iOS send/attach 同口径——requestId 自增失配）
        invalidateInputHint()
        _loadFailure.value = null
        _pendingInteractions.value = emptyList()
        _decidedInteractionIds.value = emptySet()
        _modelSelection.value = null
        _thoughtLevels.value = emptyList()
        _modelSwitchFailure.value = null
        _queueInfo.value = null
        _queueActionFailure.value = null
        _workflowRuns.value = emptyList()
        _goalSummary.value = null
        _planPanel.value = null
        _backgroundWorks.value = emptyList()
        _subagentSessions.value = emptyList()
        _currentConversation.value = null
        _controlFailure.value = null
        _controlSuccess.value = false
        _actorTranscriptSheet.value = null
        pendingUnknownModelSelection = false
        lastLocalModelSwitchRecord = null
        // 待发附件服务随源重建（上传事务绑 store 的连接面——旧源事务不可跨源续传）
        uploadsService = AttachmentUploadService(store = store, sessionID = conversationId).also {
            // 进度回调挂 bump（逐块进度/committed 短路即达 UI；失败终态经收尾 bump 兜底）
            it.onProgress = { bumpUploads() }
        }
        bumpUploads()
        syncUploadHint()
        collectJob = scope.launch { observe(store) }
    }

    /** 离开会话（路由退栈）：真退订交 Store closeConversation（keep-warm 租约由 store 自管）。 */
    fun detach() {
        collectJob?.cancel()
        collectJob = null
        attachedStore = null
        _composerPanel.value = null
        _slashHint.value = null
        _controlFailure.value = null
        _controlSuccess.value = false
        _actorTranscriptSheet.value = null
        // 面板投影一并出账（离开会话即旧数据不可见；重进走 attach 全量重读）
        _workflowRuns.value = emptyList()
        _goalSummary.value = null
        _planPanel.value = null
        _backgroundWorks.value = emptyList()
        _subagentSessions.value = emptyList()
        _currentConversation.value = null
        uploadHintSyncJob?.cancel()
        uploadHintSyncJob = null
    }

    private suspend fun observe(store: ConversationStore) = coroutineScope {
        // 桌面 getView 变化流（model-selection.onDidChange 驱动）：清单/词表换新，护栏窗内
        // 乐观选择三元组保留（iOS observeModelSelection :960-982 直译）
        launch {
            store.observeModelSelection().collect { info ->
                if (info != null) applyModelSelectionInfo(info)
            }
        }
        // 初载：订阅/历史拉取的帧序（handler 先注册）由 Store 内部保证；这里只吃投影
        _messages.value = store.messages(conversationId)
        _loadFailure.value = store.conversationLoadFailure(conversationId)
        refreshInteractionProjection(store)
        refreshCollaborationModeProjection(store)
        refreshSessionModelOverlay(store)
        refreshQueueProjection(store)
        refreshPanelProjections(store)
        _currentConversation.value = store.cachedConversation(conversationId)
            ?: store.conversations().firstOrNull { it.id == conversationId }
        store.observeConversations().collect { event ->
            val current = _messages.value
            val reduced = reduceMessages(current, event, conversationId)
            if (reduced !== current) _messages.value = reduced
            if (event is ConversationEvent.MessageAppended && event.conversationId == conversationId) {
                // 新行 = revision 前进：仅清已显示 hint，不取消在途预测/防抖——取消会让
                // 有活动期间永远不出 hint（iOS :831-838 v1.39 E2E 实测同口径）；在途结果
                // 仍受 requestId + 草稿快照双重校验（spec「不是最新的就丢弃」由这两道守卫承担）
                clearInputHintDisplay()
            }
            applyConversationRowProjection(event)
            // 面板/模式/模型 chip/队列投影逐事件覆盖（iOS refreshPendingInteractions /
            // refreshCollaborationModeProjection / refreshSessionModelOverlay 同挂点——
            // 快照与每条增量都重读，多为内存读；面板投影随事件轻量刷新 iOS :834-836 同口径）
            refreshInteractionProjection(store)
            refreshCollaborationModeProjection(store)
            refreshSessionModelOverlay(store)
            refreshQueueProjection(store)
            refreshPanelProjections(store)
        }
    }

    /**
     * 当前会话行投影维护（迷你轨道数据源 workflowActivity 随列表事件到达）：
     * ConversationsReplaced / ConversationUpdated 事件按 id 认领，其余事件不携行变更。
     */
    private fun applyConversationRowProjection(event: ConversationEvent) {
        when (event) {
            is ConversationEvent.ConversationsReplaced ->
                _currentConversation.value =
                    event.conversations.firstOrNull { it.id == conversationId }
                        ?: _currentConversation.value

            is ConversationEvent.ConversationUpdated ->
                if (event.conversation.id == conversationId) {
                    _currentConversation.value = event.conversation
                }

            else -> Unit
        }
    }

    /**
     * 面板投影刷新（iOS refreshPanels :93-100 直译）：goal/plan/works/subagents 只读投影
     * + workflowRuns 多 run 仲裁列表。全部内存读（state 表直读），随每个会话事件轻量重读。
     */
    private suspend fun refreshPanelProjections(store: ConversationStore) {
        _workflowRuns.value = store.workflowRuns(conversationId)
        _goalSummary.value = store.goalSummary(conversationId)
        _planPanel.value = store.planPanel(conversationId)
        _backgroundWorks.value = store.backgroundWorks(conversationId)
        _subagentSessions.value = store.subagentSessions(conversationId)
    }

    /**
     * 消息差分 reducer（纯函数）：事件只应用到本会话；重复 id 幂等替换不重复追加
     * （乐观回显被服务端行 sourceCommandId 精确消费的 VM 侧对位——store 层已按
     * lastYieldedMessages 基线去重，本层再防双投）。
     */
    fun reduceMessages(
        current: List<ChatMessage>,
        event: ConversationEvent,
        conversationId: String,
    ): List<ChatMessage> {
        return when (event) {
            is ConversationEvent.MessagesReplaced ->
                if (event.conversationId == conversationId) event.messages else current

            is ConversationEvent.MessageAppended -> {
                if (event.conversationId != conversationId) return current
                val incoming = event.message
                val index = current.indexOfFirst { it.id == incoming.id }
                if (index >= 0) {
                    current.toMutableList().apply { this[index] = incoming }
                } else {
                    current + incoming
                }
            }

            is ConversationEvent.MessageUpdated -> {
                if (event.conversationId != conversationId) return current
                val incoming = event.message
                val index = current.indexOfFirst { it.id == incoming.id }
                if (index >= 0) {
                    current.toMutableList().apply { this[index] = incoming }
                } else {
                    current // 未曾见过的更新按 append 语义交给 store 排序；本层不乱插
                }
            }

            // 列表级/面板级事件不携行变更
            is ConversationEvent.ConversationsReplaced, is ConversationEvent.ConversationUpdated,
            is ConversationEvent.PanelStateUpdated,
            -> current
        }
    }

    // MARK: 草稿与发送（U-6 + v1.33④）

    fun updateDraft(text: String) {
        _draft.value = text
        // 清除时机：发送成功 / 用户手动编辑 draft / 离开会话——不随 3s 消失
        if (text.isNotEmpty()) {
            if (_sendFailureReason.value != null) {
                _sendFailureReason.value = null
            }
            // 常驻失败反馈行（/plan 阻断等）：用户重新输入 = 重试意图，反馈行让位
            val hint = _slashHint.value
            if (hint != null && hint !is SlashHint.PlanSwitched) {
                _slashHint.value = null
            }
        }
    }

    /**
     * 会话派生（web fork / iOS fork 入口同构，2026-11-23）：从当前会话派生新会话，
     * 返回新会话 id（null = 失败/未连接，失败原因落常驻错误行）。
     * 行级入口门：assistantText + entityId 在场 + 非流式（web 同口径）。
     */
    suspend fun forkConversation(): String? {
        val store = attachedStore ?: return null
        return try {
            store.forkConversation(conversationId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _sendFailureReason.value = "Fork 失败：${e.message ?: e::class.simpleName}"
            null
        }
    }

    // MARK: 输入预测（3.17.2 ghost hint；iOS ChatViewModel :1349-1390 直译）

    /**
     * 草稿变化触发（Composer onChange）：防抖 ~700ms 后请求续写预测。每次变化
     * 先清旧 hint（不再匹配当前草稿）；演示态/无能力门由 store 侧返 null 兜底。
     * 触发口径（spec 手机端建议②）：非空草稿停顿即预测续写；空草稿仅在失焦时
     * 预测下一条输入——聚焦清空〔发送后〕不立即触发（焦点条件由 UI 承担）。
     */
    fun scheduleInputPrediction() {
        predictionJob?.cancel()
        _inputHint.value = null
        val snapshot = _draft.value
        predictionJob = scope.launch {
            delay(INPUT_PREDICTION_DEBOUNCE_MS)
            requestInputPrediction(snapshot)
        }
    }

    private suspend fun requestInputPrediction(snapshot: String) {
        predictionSeq += 1
        val seq = predictionSeq
        val requestId = "r-$seq"
        val store = attachedStore ?: return
        val result = store.inputPrediction(conversationId, draft = snapshot, requestId = requestId)
        // 过期丢弃（spec 手机端建议③⑤）：requestId 非最新 / 草稿已变 / 决策非 show
        if (seq != predictionSeq || _draft.value != snapshot) return
        val text = result?.topText ?: return
        _inputHint.value = text
    }

    /** 点按采纳：续写拼到草稿尾部（spec：draft + text，不 trim——英文续写可空格起头）；清 hint。 */
    fun acceptInputHint() {
        val text = _inputHint.value ?: return
        _draft.value += text
        _inputHint.value = null
    }

    /** 发送/新 revision/切会话：作废在途预测并清 hint（requestId 自增使迟到回包失配）。 */
    fun invalidateInputHint() {
        predictionJob?.cancel()
        predictionSeq += 1
        _inputHint.value = null
    }

    /**
     * 仅清显示（不取消在途/防抖）：新行到达时 revision 前进，旧 hint 不该继续显示，
     * 但用户草稿未变——在途预测仍可落地（spec 丢弃语义由 requestId/草稿校验承担）。
     */
    fun clearInputHintDisplay() {
        _inputHint.value = null
    }

    /**
     * 发送入口（UI 在自身作用域 launch）。返回是否进入送达链（仅用于测试断言；
     * 失败反馈全部落 StateFlow 由 UI 呈现）。
     */
    suspend fun send(): Boolean {
        if (_sending.value) return false
        val text = _draft.value.trim()
        if (text.isEmpty()) return false
        invalidateInputHint()
        val intent = parseSlashIntent(text)
        if (intent is SlashIntent.Plan) {
            return sendPlanBranch(intent, originalText = text)
        }
        // 其余斜杠（/goal /compact）本批不拦截：原文直发由桌面解释（头部注释口径）
        return performSend(text)
    }

    /** /plan 分支（iOS dispatchSlashIntent .plan :1176-1216 直译）：切换失败即阻断。 */
    private suspend fun sendPlanBranch(intent: SlashIntent.Plan, originalText: String): Boolean {
        _draft.value = ""
        _sending.value = true
        val modeFailure = switchCollaborationMode("plan")
        _sending.value = false
        if (modeFailure != null) {
            // v1.33④：fire-and-forget = 任务以旧模式进桌面（「/plan 没触发」报障根因）。
            // 阻断发送 + 草稿回填 + 常驻错误行（switchCollaborationMode 已落）。
            showSlashHint(SlashHint.PlanBlocked)
            if (_draft.value.isEmpty()) _draft.value = originalText
            return false
        }
        val task = intent.task
        if (task.isEmpty()) {
            showSlashHint(SlashHint.PlanSwitched)
            return true
        }
        // 待发附件随任务（iOS :1196-1208 直译——「/plan + 附件发送后附件静默丢失」修复）：
        // 补传失败 = 附件未就绪，阻断发送 + 草稿回填 + 失败原文上屏（禁静默丢附件）
        val uploads = uploadsService
        if (uploads != null && !uploads.isEmpty) {
            _sending.value = true
            try {
                uploads.ensureAllCommitted()
            } finally {
                _sending.value = false
            }
            bumpUploads()
            if (uploads.blocksSend) {
                showSlashHint(SlashHint.Raw(uploadFailureText() ?: "附件未就绪，未发送"))
                if (_draft.value.isEmpty()) _draft.value = originalText
                return false
            }
        }
        return performSend(task)
    }

    /**
     * 普通发送主体：附件补传门（iOS send :1246-1257 直译）→ 清稿 → 发送；
     * 失败回填 + 持久错误行（服务层拒因原文透传，禁假成功）。
     * 附件 peek 不移除（§11.1#6）：committed 引用快照随消息下发，**成功才 clear**——
     * 失败保留待发条（committed 引用可复用，重试只发文本+引用不重传）。
     */
    private suspend fun performSend(text: String): Boolean {
        val store = attachedStore
        val uploads = uploadsService
        // 附件补传门：仍有失败项 = 直发文本会丢附件 → 本次不发送（待发条保留失败态，
        // 失败原文行常驻；此为发送钮 blocksSend 主闸的旁路兜底，iOS 静默 return false 同口径）
        if (uploads != null && !uploads.isEmpty) {
            _sending.value = true
            try {
                uploads.ensureAllCommitted()
            } finally {
                _sending.value = false
            }
            bumpUploads()
            if (uploads.blocksSend) return false
        }
        val attachments = uploads?.committedAttachments() ?: emptyList()
        _draft.value = ""
        _sending.value = true
        val delivered = try {
            store?.sendWithAttachments(
                text = text,
                attachments = attachments,
                requestedDelivery = null,
                modelSelection = null,
                conversationId = conversationId,
            ) ?: false
        } finally {
            _sending.value = false
        }
        if (delivered) {
            // 成功才清已随消息下发的 committed 附件（失败保留可重发）
            uploads?.clearCommitted(attachments)
            bumpUploads()
            _sendFailureReason.value = null
            // 发送成功：常驻失败反馈行一并出账（阻断已解除）
            if (_slashHint.value is SlashHint.Raw || _slashHint.value is SlashHint.PlanBlocked) {
                _slashHint.value = null
            }
            return true
        }
        // U-6：回填仅当用户尚未重新输入（防覆盖新输入）；错误行持久不自动消失
        if (_draft.value.isEmpty()) _draft.value = text
        _sendFailureReason.value =
            store?.sendRejectionText(conversationId) // null = 无在案拒因（未送达泛因），UI 用通用文案
        return false
    }

    /** 失败重试（错误行「重试」钮）：直接重发当前草稿。 */
    suspend fun retrySend() {
        send()
    }

    // MARK: 协作模式切换（chip 面板 + /plan 前置共用；CAS 权威成功才落态）

    /**
     * 切换协作模式（iOS switchCollaborationMode :245-263 直译）。权威成功：落态 + 清失败行 +
     * 3s 护栏；失败/未知态：chip 不动、不落盘，失败面落常驻错误行。
     */
    suspend fun switchCollaborationMode(mode: String): CollabModeFailure? {
        val store = attachedStore
        val ack = store?.switchCollaborationMode(conversationId, mode)
        val commandAck = CommandAck(ack)
        if (commandAck.isFailure) {
            val failure = CollabModeFailure.Rejected(commandAck.failureText ?: "桌面端拒绝")
            _collaborationModeFailure.value = failure
            return failure
        }
        if (ack == null || !commandAck.isAuthoritativeSuccess) {
            // 未知态不落盘（2026-10-09「计划模式丢失」根因）；投影到达即定案
            pendingUnknownModeValue = mode
            val failure = CollabModeFailure.UnknownAckState
            _collaborationModeFailure.value = failure
            return failure
        }
        _collaborationMode.value = mode
        lastLocalModeSwitch = ModeSwitch(mode, nowMs())
        _collaborationModeFailure.value = null
        return null
    }

    private data class ModeSwitch(val value: String, val at: Long)

    private var lastLocalModeSwitch: ModeSwitch? = null
    private var pendingUnknownModeValue: String? = null

    /** 协作模式桌面投影（state.config.mode；逐事件覆盖，桌面改回自愈）。 */
    private suspend fun refreshCollaborationModeProjection(store: ConversationStore) {
        val projected = store.sessionCollaborationMode(conversationId) ?: return
        if (projected !in COLLABORATION_MODES) return
        // 未知态等待期：首个投影即定案（桌面真态说话），清失败行
        if (pendingUnknownModeValue != null) {
            pendingUnknownModeValue = null
            _collaborationModeFailure.value = null
        }
        // ack 已权威成功但 config.mode 增量未达：3s 内旧投影不回闪
        val guard = lastLocalModeSwitch
        if (guard != null && nowMs() - guard.at < MODE_SWITCH_GUARD_MS && projected != guard.value) {
            return
        }
        if (projected != _collaborationMode.value) {
            _collaborationMode.value = projected
        }
    }

    // MARK: 模型/思考档切换（chips 面板；iOS ChatViewModel.switchModel :158-202 直译）

    /**
     * 主模型/思考档切换（switchModelConfig 三元组）。`rowKey` 接受面板行键「套餐|模型」
     * （复合表权威解析 providerId）或裸模型名（当前绑定走 activeProviderId 消歧）。
     * 权威成功回执：乐观落 chip + 3s 护栏（忙 turn 期间桌面增量可滞后分钟级——
     * 真机 14:08 实证 accepted 后 chip 恒旧值「选了没有变化」）；失败/未知态：chip 不动，
     * 失败面落常驻错误行。
     */
    suspend fun switchModel(rowKey: String, thoughtLevel: String? = null): ModelSwitchFailure? {
        val store = attachedStore
        val info = _modelSelection.value
        val label = info?.modelLabelForRowKey(rowKey)?.takeIf { it.isNotEmpty() } ?: rowKey
        var provider = info?.providerForRowKey(rowKey, label)
        if (provider.isNullOrEmpty()) {
            // getView 失败/空表兜底：会话级 state.modelSelection.providerId（同模型场景，
            // v1.36①：provider 必填【实证·上游仓 command.ts:211】，空串裸发必被桌面拒）
            val session = store?.sessionModelSelection(conversationId)
            if (session?.model == label && !session.provider.isNullOrEmpty()) {
                provider = session.provider
            }
        }
        if (provider.isNullOrEmpty()) {
            val failure = ModelSwitchFailure.ProviderUnavailable
            _modelSwitchFailure.value = failure
            return failure
        }
        val thought = thoughtLevel ?: info?.activeThoughtLevel ?: ""
        val ack = store?.switchModelConfig(
            conversationId = conversationId,
            provider = provider,
            model = label,
            thought = thought,
        )
        val commandAck = CommandAck(ack)
        val failure = when {
            ack == null -> ModelSwitchFailure.Undelivered
            commandAck.isFailure -> ModelSwitchFailure.Rejected(commandAck.failureText ?: "桌面端拒绝")
            // 未知态不乐观落 chip：登记待定标记交 state.modelSelection 投影首帧定案
            !commandAck.isAuthoritativeSuccess -> ModelSwitchFailure.UnknownAckState.also {
                pendingUnknownModelSelection = true
            }
            else -> null
        }
        if (failure != null) {
            _modelSwitchFailure.value = failure
            return failure
        }
        // 权威成功（accepted/duplicate/noop）即乐观落 chip，不等桌面投影回流
        _modelSelection.value = (info ?: ModelSelectionInfo()).copy(
            activeModel = label,
            activeThoughtLevel = thought.ifEmpty { null },
            activeProviderId = provider,
        )
        lastLocalModelSwitchRecord = ModelSwitchRecord(
            model = label,
            thought = thought.ifEmpty { null },
            at = nowMs(),
        )
        _modelSwitchFailure.value = null
        refreshThoughtLevelsInternal(store)
        return null
    }

    /** 思考档切换（iOS switchThoughtLevel :347-356 直译：model 沿用当前绑定裸名——provider 经 activeModel==activeProviderId 消歧）。 */
    suspend fun switchThought(level: String): ModelSwitchFailure? {
        val model = _modelSelection.value?.activeModel
            ?: return ModelSwitchFailure.ProviderUnavailable.also {
                _modelSwitchFailure.value = it
            }
        return switchModel(model, thoughtLevel = level)
    }

    /**
     * getView 手动重试（面板「重新获取」行/chips 兜底）。判据是 hasSelectableModels 而非
     * 非 null：getView 失败会种出「非 nil 但空」的实例，按非 null 短路会死锁到重连
     * （iOS retryModelSelection :990-997 同教训）。
     */
    suspend fun refetchModelSelection(): Boolean {
        val store = attachedStore ?: return false
        if (_modelSelection.value?.hasSelectableModels == true) return true
        val info = store.modelSelectionView() ?: return false
        if (!info.hasSelectableModels) return false
        applyModelSelectionInfo(info)
        return true
    }

    /** 桌面 getView 变化流应用：清单/词表换新，护栏窗内乐观三元组保留（iOS :963-977）。 */
    private suspend fun applyModelSelectionInfo(info: ModelSelectionInfo) {
        var merged = info
        val guard = lastLocalModelSwitchRecord
        if (guard != null && nowMs() - guard.at < MODEL_SWITCH_GUARD_MS &&
            !(info.activeModel == guard.model && info.activeThoughtLevel == guard.thought)
        ) {
            val current = _modelSelection.value
            if (current != null) {
                merged = info.copy(
                    activeModel = current.activeModel,
                    activeThoughtLevel = current.activeThoughtLevel,
                    activeProviderId = current.activeProviderId,
                )
            }
        }
        _modelSelection.value = merged
        refreshThoughtLevelsInternal(attachedStore)
    }

    /**
     * 会话级模型选择覆盖（state.modelSelection 权威源；iOS refreshSessionModelOverlay
     * :1002-1034 直译）：provider 权威灌入 + 未知态定案 + 3s 乐观护栏 + 显示值覆盖。
     */
    private suspend fun refreshSessionModelOverlay(store: ConversationStore?) {
        if (store == null) return
        val session = store.sessionModelSelection(conversationId) ?: return
        var info = _modelSelection.value
        if (info == null) {
            info = store.modelSelectionView() ?: ModelSelectionInfo()
        }
        // 会话级权威 provider 灌入（getView 失败时同模型/思考档切换免 getView 的唯一权威源）
        var merged = if (!session.provider.isNullOrEmpty() && session.provider != info.activeProviderId) {
            info.copy(activeProviderId = session.provider)
        } else {
            info
        }
        if (pendingUnknownModelSelection) {
            // 未知态待定期：首个投影即定案（桌面真态说话），清失败行
            pendingUnknownModelSelection = false
            _modelSwitchFailure.value = null
        }
        val guard = lastLocalModelSwitchRecord
        val inGuardWindow = guard != null && nowMs() - guard.at < MODEL_SWITCH_GUARD_MS
        if (inGuardWindow && guard != null &&
            !(session.model == guard.model && session.thought == guard.thought)
        ) {
            // 护栏窗内旧投影：不回闪乐观值（provider 灌入仍落表）
            if (merged != _modelSelection.value) _modelSelection.value = merged
            return
        }
        if (merged.activeModel != session.model || merged.activeThoughtLevel != session.thought) {
            merged = merged.copy(activeModel = session.model, activeThoughtLevel = session.thought)
            _modelSelection.value = merged
            refreshThoughtLevelsInternal(store)
        } else if (merged != info) {
            _modelSelection.value = merged
        }
    }

    /** 思考档词表重查（当前 chip 模型；getView per-model 优先，空 = UI 层退化静态梯）。 */
    private suspend fun refreshThoughtLevelsInternal(store: ConversationStore?) {
        val model = _modelSelection.value?.activeModel
        _thoughtLevels.value = if (model == null) {
            emptyList()
        } else {
            store?.thoughtLevels(model) ?: emptyList()
        }
    }

    // MARK: 排队队列操作（五件；iOS queueAction/sendQueuedNow 族 :497-534 直译）

    private suspend fun refreshQueueProjection(store: ConversationStore) {
        _queueInfo.value = store.queueInfo(conversationId)
    }

    /**
     * 队列命令统一收口：ack 三态映射（null=未送达 / isFailure=拒因原文 / 未知态），
     * 失败落常驻错误行（不 3s 闪过）；权威成功刷新队列投影（含 queue 键的增量亦会
     * 自动回流，此处先读一步免等帧）。
     */
    private suspend fun queueAction(
        action: suspend (ConversationStore) -> JSONValue?,
    ): QueueActionFailure? {
        val store = attachedStore
        val ack = store?.let { action(it) }
        val commandAck = CommandAck(ack)
        val failure = when {
            ack == null -> QueueActionFailure.Undelivered
            commandAck.isFailure -> QueueActionFailure.Rejected(commandAck.failureText ?: "桌面端拒绝")
            commandAck.isAuthoritativeSuccess -> null
            else -> QueueActionFailure.UnknownState
        }
        _queueActionFailure.value = failure
        if (failure == null && store != null) {
            refreshQueueProjection(store)
        }
        return failure
    }

    /** 队列条目立即发送（CAS）。 */
    suspend fun sendQueuedNow(queueItemId: String): QueueActionFailure? =
        queueAction { it.sendQueuedNow(conversationId, queueItemId) }

    /** 队列条目删除（CAS）。 */
    suspend fun deleteQueueItem(queueItemId: String): QueueActionFailure? =
        queueAction { it.deleteQueueItem(conversationId, queueItemId) }

    /** 队列条目文本编辑（CAS；空文本由 UI 禁用保存钮拦截，不下发）。 */
    suspend fun editQueueItem(queueItemId: String, newText: String): QueueActionFailure? =
        queueAction { it.editQueueItem(conversationId, queueItemId, newText) }

    /**
     * 队列条目上移（与前一位置交换 = reorder 到前一项之前；iOS moveQueueItemUp :522-530
     * 直译。首行无上移语义：静默不下发）。beforeQueueItemId=null = 移到队尾。
     */
    suspend fun moveQueueItemUp(queueItemId: String): QueueActionFailure? {
        val items = _queueInfo.value?.items ?: return null
        val index = items.indexOfFirst { it.id == queueItemId }
        if (index <= 0) return null
        val before = items[index - 1].id
        return queueAction { it.reorderQueueItem(conversationId, queueItemId, before) }
    }

    /** 自动排空开关（CAS）。 */
    suspend fun setAutoDrain(enabled: Boolean): QueueActionFailure? =
        queueAction { it.setAutoDrain(conversationId, enabled) }

    // MARK: 新建会话提交门（iOS NewConversationSheet :263-282 直译；纯函数供 sheet 直用）

    /**
     * 新建会话草稿选择（sheet 侧状态快照；行键「套餐|模型」+ 纯模型名 + 思考档）。
     */
    data class NewSessionDraftSelection(
        val selectedModelRowKey: String? = null,
        val selectedModel: String? = null,
        val selectedThought: String? = null,
    )

    // MARK: 编辑重发（P1-3：CAS+row-target；rewind 语义——确认走 UI 双重确认，本方法只下发）

    /**
     * 编辑用户消息并重发（store.editUserQuery；rowId+entityId 行游标精确寻址）。
     * 返回 null = 权威成功（消息流由桌面 rows 键级重建自动刷新，本地乐观不做）；
     * 游标缺失 = Undelivered（调用方门槛：游标缺失不渲染入口，本实现亦不下发——防御位）。
     */
    /** 行级反馈失败原文（P-020；常驻错误行，失败不上屏即假成功）。 */
    private val _feedbackFailure = MutableStateFlow<String?>(null)
    val feedbackFailure: StateFlow<String?> = _feedbackFailure

    /**
     * 助手消息行级反馈（store.setAssistantFeedback；rowId+entityId 双游标精确寻址）。
     * value：true=赞 / false=踩 / null=取消。写面失败/未知态把原文落常驻错误行。
     */
    suspend fun setAssistantFeedback(message: ChatMessage, value: Boolean?) {
        val rowId = message.id.removePrefix("row-").toIntOrNull()
        val entityId = message.entityId
        val store = attachedStore
        if (rowId == null || entityId == null || store == null) {
            _feedbackFailure.value = "该消息不可反馈（缺少行游标）"
            return
        }
        val ack = store.setAssistantFeedback(
            conversationId = conversationId,
            rowId = rowId,
            entityId = entityId,
            value = value,
        )
        val commandAck = CommandAck(ack)
        _feedbackFailure.value = when {
            ack == null -> "消息未送达（无回执）"
            commandAck.isFailure -> commandAck.failureText ?: "桌面端拒绝"
            commandAck.isAuthoritativeSuccess -> null
            else -> "桌面回执状态未知"
        }
    }

    suspend fun editUserQuery(message: ChatMessage, newText: String): InteractionFailure? {
        val rowId = message.id.removePrefix("row-").toIntOrNull()
            ?: return InteractionFailure.Undelivered
        val entityId = message.entityId ?: return InteractionFailure.Undelivered
        val ack = attachedStore?.editUserQuery(
            conversationId = conversationId,
            rowId = rowId,
            entityId = entityId,
            newText = newText,
        )
        val commandAck = CommandAck(ack)
        return when {
            ack == null -> InteractionFailure.Undelivered
            commandAck.isFailure -> InteractionFailure.Rejected(
                commandAck.failureText ?: "桌面端拒绝",
            )
            commandAck.isAuthoritativeSuccess -> null
            else -> InteractionFailure.UnknownState
        }
    }

    // MARK: 待处理交互投影 + 决议（审批/提问/计划三卡 + hook 审核）

    private suspend fun refreshInteractionProjection(store: ConversationStore) {
        val list = store.pendingInteractionList(conversationId)
        _pendingInteractions.value = list
        // 本地遮罩回收：桌面投影已移除的决议 id 随之出账
        val pruned = _decidedInteractionIds.value.filterTo(HashSet()) { id ->
            list.any { it.id == id }
        }
        if (pruned.size != _decidedInteractionIds.value.size) {
            _decidedInteractionIds.value = pruned
        }
    }

    /**
     * 交互应答（A-3：answer 恒为对象按交互族分形——权限 {optionId} / 计划 {action} /
     * 提问 {freeText}|{optionId}，由调用方构造）。返回 null = 权威成功（本地遮罩撤卡）。
     */
    suspend fun resolveInteraction(
        interaction: RemotePendingInteraction,
        answer: JSONValue,
    ): InteractionFailure? {
        val ack = attachedStore?.resolveInteractionRaw(
            conversationId = conversationId,
            interactionId = interaction.id,
            answer = answer,
        )
        val commandAck = CommandAck(ack)
        return when {
            ack == null -> InteractionFailure.Undelivered
            commandAck.isFailure -> InteractionFailure.Rejected(
                commandAck.failureText ?: "桌面端拒绝",
            )
            commandAck.isAuthoritativeSuccess -> {
                beginStampHold(interaction, approved = stampApprovedFor(answer))
                null
            }
            else -> InteractionFailure.UnknownState
        }
    }

    /** workspace hook 信任审核应答（reviewItemId 列表逐字回传）。 */
    suspend fun respondHookReview(
        interaction: RemotePendingInteraction,
        reviewItemIds: List<String>,
    ): InteractionFailure? {
        val ack = attachedStore?.respondWorkspaceHookReview(conversationId, reviewItemIds)
        val commandAck = CommandAck(ack)
        return when {
            ack == null -> InteractionFailure.Undelivered
            commandAck.isFailure -> InteractionFailure.Rejected(
                commandAck.failureText ?: "桌面端拒绝",
            )
            commandAck.isAuthoritativeSuccess -> {
                beginStampHold(interaction, approved = true) // hook 信任 = 朱印「照办」
                null
            }
            else -> InteractionFailure.UnknownState
        }
    }

    /**
     * 印向推导（motion-spec：批准=朱印「照办」，拒绝=墨印「不准」，提交型一律照办）：
     * reject 前缀 optionId（权限四族）/ action=decline（计划驳回）→ 不准；其余（提交
     * 型回答、原生 optionId 单选即决、hook 信任）→ 照办。
     */
    private fun stampApprovedFor(answer: JSONValue): Boolean {
        val fields = (answer as? JSONValue.Obj)?.fields ?: return true
        val optionId = fields["optionId"]?.stringValue
        if (optionId?.startsWith("reject") == true) return false
        if (fields["action"]?.stringValue == "decline") return false
        return true
    }

    /** 落印 + 停驻 700ms → 入账 decidedIds 收卡（去重：首印定案，duplicate 不重置）。 */
    private fun beginStampHold(interaction: RemotePendingInteraction, approved: Boolean) {
        if (_stampHolds.value.containsKey(interaction.id)) return
        _stampHolds.value = _stampHolds.value + (interaction.id to StampHold(interaction, approved))
        scope.launch {
            kotlinx.coroutines.delay(700)
            _stampHolds.value = _stampHolds.value - interaction.id
            _decidedInteractionIds.value = _decidedInteractionIds.value + interaction.id
        }
    }

    // MARK: 面板呈现（撤焦先行 + 延迟呈现；关闭单路径）

    /**
     * 打开 composer 面板：**先撤焦点再呈现**（撤焦回调由 UI 注入 LocalFocusManager），
     * 延迟 [panelDelayMs] 待键盘让出呈现位——同事务发出时面板会与键盘收起转场相撞被吞
     * （iOS openComposerPanel :2180-2190 逐字语义；「点了没反应」根因）。
     */
    fun openComposerPanel(kind: ComposerPanelKind, clearFocus: () -> Unit) {
        clearFocus()
        scope.launch {
            delay(panelDelayMs)
            _composerPanel.value = kind
        }
    }

    /** 面板关闭唯一路径（§5-15：关闭由 handler 置 null 单路径）。 */
    fun closeComposerPanel() {
        _composerPanel.value = null
    }

    // MARK: 反馈行

    /**
     * 反馈行呈现：**失败反馈一律常驻**（任务口径「失败 3s 气泡一律改常驻」；自动消失的
     * 失败用户根本看不到——「点了没反应」报障同根因），仅成功提示保留 3s 自动清除。
     * 常驻失败清除路径：发送成功 / 用户重新输入草稿 / 离开会话（detach）。
     */
    private fun showSlashHint(hint: SlashHint) {
        slashHintClearJob?.cancel()
        slashHintClearJob = null
        _slashHint.value = hint
        if (hint !is SlashHint.PlanSwitched) return
        slashHintClearJob = scope.launch {
            delay(hintClearMs)
            if (!_sending.value) _slashHint.value = null
        }
    }

    private var slashHintClearJob: Job? = null
}

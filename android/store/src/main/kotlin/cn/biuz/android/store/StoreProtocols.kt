package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Stores/StoreProtocols.swift（831 行：三大 Store 协议面
// + 默认空实现）与 Sources/Stores/StoreEnvironment.swift（未连接态 Empty 三件）。
//
// 映射约定（与 protocol/Models.kt 文件头一致）：
// - Swift `Date` → Long（epoch 毫秒）；`Data` → ByteArray；`[String: JSONValue]` → Map；
// - `String(localized:)` → 同串字面量（iOS 键 = 串本身；Android 本地化由 UI 层资源承接）；
// - Swift `AsyncStream<T>` → kotlinx.coroutines.flow.Flow<T>；空流 = emptyFlow；
// - Swift protocol extension 默认实现 → Kotlin interface 默认方法体。Swift 因 `any 存在类型`
//   静态分派问题要求「本体 requirement 声明」（原文 :34-39 注释）；Kotlin interface 方法
//   恒动态分派，该约束天然消解——远端实现覆写默认方法即可生效，无需额外声明面。
// - 红线（交接 §0-4 / AGENTS §5-13）：Mock 演示数据仅测试用途；正式路径一律 Empty*Store
//   空实现——读面空、写面如实失败（本文件默认实现即该口径，Empty 三件只覆写核心读写面）。

import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.DiffFile
import cn.biuz.android.protocol.FileNode
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.protocol.RemoteGoalSummary
import cn.biuz.android.protocol.PlanPanelSummary
import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.SubagentProjectionState
import cn.biuz.android.protocol.SubagentRuntimeSummary
import cn.biuz.android.protocol.ActivitySummaryDismissed
import cn.biuz.android.protocol.SubagentSessionSummary
import cn.biuz.android.protocol.SessionSubagentDirectory
import cn.biuz.android.protocol.TaskRecord
import cn.biuz.android.protocol.TerminalLine
import cn.biuz.android.protocol.WorkflowRunSummary
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// MARK: - 会话事件流（数据层 → UI）（StoreProtocols.swift:4-13）

/** 会话事件流（数据层 → UI）。 */
sealed class ConversationEvent {
    data class ConversationsReplaced(val conversations: List<Conversation>) : ConversationEvent()

    data class ConversationUpdated(val conversation: Conversation) : ConversationEvent()

    data class MessagesReplaced(val conversationId: String, val messages: List<ChatMessage>) :
        ConversationEvent()

    data class MessageAppended(val conversationId: String, val message: ChatMessage) :
        ConversationEvent()

    data class MessageUpdated(val conversationId: String, val message: ChatMessage) :
        ConversationEvent()

    /**
     * 面板态变更（goal/plan/backgroundWorks/subagents 任一键的快照/state.updated 应用）；
     * 观察方重读面板投影（无行级语义）（:10-12）。
     */
    data class PanelStateUpdated(val conversationId: String) : ConversationEvent()
}

// MARK: - 协议面值类型（StoreProtocols.swift:294-324 / :509-623 / :677-825；AppSession.swift:139-159）

/** 附件预览结果（G-014）（:295-301）。 */
data class AttachmentPreview(
    val ref: String,
    val data: ByteArray,
    /** image·video·application/pdf 前缀族（原文词表 image/…|video/…|application/pdf——斜杠星号在 Kotlin 注释内开嵌套层，此处改写） */
    val mediaType: String,
    val totalBytes: Int,
) {
    /** Swift `var id: String { ref }`（SwiftUI Identifiable）对应物。 */
    val id: String get() = ref

    // ByteArray 参与数据类：按内容比较（Equatable 口径）
    override fun equals(other: Any?): Boolean =
        other is AttachmentPreview &&
            ref == other.ref &&
            data.contentEquals(other.data) &&
            mediaType == other.mediaType && totalBytes == other.totalBytes

    override fun hashCode(): Int = ref.hashCode() * 31 + data.contentHashCode()
}

/**
 * 随 sendText 下发的待发附件引用（P1-1 上传链路产物；attachmentCommitV4 收口后的
 * 最终附件引用 + 展示元数据）。B-1：字段名与 web wire 元素键一比一
 * `{ref, fileName, mime, bytes}`（bundle 三处独立构造同形【移植·bundle 逆向】）（:303-311）。
 */
data class OutgoingAttachment(
    val ref: String,
    val fileName: String,
    val mime: String,
    val bytes: Int,
)

/**
 * attachmentBeginV4 回执（web state 判别联合【移植·bundle 逆向】）：
 * staging 携 nextChunkIndex（已收块数，续传起点）；committed 直接给 ref
 * （checksum 命中已上传附件的短路路径，免分块直取引用）（:313-319）。
 */
sealed class AttachmentBeginOutcome {
    data class Staging(val nextChunkIndex: Int) : AttachmentBeginOutcome()

    data class Committed(val ref: String) : AttachmentBeginOutcome()
}

/** 附件事务 RPC 失败（错误文本直出 UI 失败行；channel 异常/回执形状不识别/未连接）（:322-324）。 */
class AttachmentRPCError(val text: String) : Exception(text)

/** workspace-config topic 投影（workspace-config/&lt;workspacePath&gt; 快照/delta 的只读映射）（:510-524）。 */
data class WorkspaceConfigInfo(
    val options: List<Option> = emptyList(),
    val slashCommands: List<SlashCommand> = emptyList(),
) {
    data class Option(
        val id: String,
        val name: String,
        val currentValue: String,
        val values: List<String>,
    )

    data class SlashCommand(
        val name: String,
        val description: String,
    ) {
        val id: String get() = name
    }
}

/**
 * model-selection.getView 只读投影（桌面端可选模型/思考档 + 当前绑定）（:527-578）。
 */
data class ModelSelectionInfo(
    val models: List<String> = emptyList(),
    val activeModel: String? = null,
    val thoughtLevels: List<String> = emptyList(),
    val activeThoughtLevel: String? = null,
    /**
     * 模型 → 可用思考档（getView per-model 词表【实证·上游仓 provider/facades.ts
     * ModelSelectionModelView `{modelId, config}` + web bundle yB 同构读取
     * `config.optionSpecs.reasoningLevel.values`】；`modelThoughtLevels` 键同义兜底）。
     * web v4 工具栏思考菜单的唯一数据源；键 = modelId（label 不同时双键兼容）（:532-536）
     */
    val thoughtByModel: Map<String, List<String>> = emptyMap(),
    /**
     * 套餐分组（个人套餐/体验套餐/团队套餐…；providerId 含 start-plan → 体验、
     * individual-coding-plan → 个人、team-coding-plan → 团队；缺席 = 未分组）（:537-539）
     */
    val planGroups: List<ModelPlanGroup> = emptyList(),
    /** 模型 → 所属 providerId（switchModelConfig 需要三元组 provider/model/thought）（:541） */
    val modelProviders: Map<String, String> = emptyMap(),
    /**
     * 「套餐|模型」→ providerId（同名模型跨套餐各组各计——modelProviders 按裸名做键
     * 后到覆盖先到，点个人套餐行发出体验套餐 providerId，2026-10-08 报障根因）（:542-544）
     */
    val modelProvidersByPlan: Map<String, String> = emptyMap(),
    /**
     * 当前绑定的 providerId（getView preferredSelection/effective.selection；
     * 裸名查询时跨套餐消歧的权威源）（:545-547）
     */
    val activeProviderId: String? = null,
) {
    /**
     * 是否携带可选模型清单（2026-10-08 报障「对话框模型选不了」根修）：getView
     * 失败/空回执时 overlay 会种出「非 nil 但空」的实例——chips 靠 state 叠加仍可
     * 显示 activeModel，面板却是零行。以此区分「可开门」与「需重拉」，替代
     * `!= null` 判据（retryModelSelection 短路死锁根因）（:549-555）
     */
    val hasSelectableModels: Boolean
        get() = models.isNotEmpty() || planGroups.isNotEmpty()

    /**
     * 行键中的纯模型名（「套餐|模型」→ 模型；裸名原样）——switchModelConfig /
     * firstInput.modelSelection 的 modelId 恒携纯模型名，套餐前缀只用于本地解析（:557-562）
     */
    fun modelLabelForRowKey(key: String?): String {
        if (key == null) return ""
        if (!key.contains("|")) return key
        // Swift split(separator:maxSplits:1) 取末段
        return key.split("|", limit = 2).lastOrNull() ?: key
    }

    /**
     * 选择行 providerId 解析。`rowKey` = 面板行键「套餐|模型」（复合表权威）；
     * 裸模型名回退序：当前绑定模型取 activeProviderId（桌面绑定即真相）→
     * modelProviders[裸名]（同名跨套餐时歧义遗留，仅旧缓存兜底）（:564-577）
     */
    fun providerForRowKey(key: String?, model: String): String? {
        val label: String
        if (key != null && key.contains("|")) {
            modelProvidersByPlan[key]?.let { return it }
            label = modelLabelForRowKey(key)
        } else {
            label = model
        }
        if (label == activeModel && !activeProviderId.isNullOrEmpty()) return activeProviderId
        return modelProviders[label]
    }
}

/** 模型套餐分组（选择器按组分节展示；同一模型可同时出现在个人与体验两组——配额不同）（:581-585）。 */
data class ModelPlanGroup(
    val plan: String,
    val models: List<String>,
) {
    val id: String get() = plan
}

/**
 * 新建会话的会话前模型选择（createSession firstInput.modelSelection）。
 * 字段名与 web 端 sendText/createSession 载荷一致：
 * {providerId, modelId, options:{reasoningLevel}}（provider/model/thought 三元组同源）（:588-594）。
 */
data class NewSessionModelSelection(
    val providerId: String,
    val modelId: String,
    val reasoningLevel: String,
)

/**
 * 会话上下文用量（G-021：会话流工具行真实数据源）。
 * 连接态来自会话 state.runtime.contextUsage（zcodeSessionContextUsageSchema 的 used/size）；
 * 演示态由 Mock 按消息量动态生成（非硬编码常量）。null = 无数据 → UI 不渲染进度条（:596-611）。
 */
data class ContextUsageInfo(
    val used: Int,
    val size: Int,
) {
    val fraction: Double
        get() = if (size <= 0) 0.0 else minOf(1.0, used.toDouble() / size.toDouble())

    val percentText: String
        get() = "${(fraction * 100).roundToInt()}%"
}

/** 任务审批决议结果（U-5：approve/reject 如实回传，禁止无条件假成功）（:613-623）。 */
sealed class TaskDecisionOutcome {
    /** 回执 accepted/noop/applied/ok */
    data object Accepted : TaskDecisionOutcome()

    /** 会话无待审批交互（可能已在桌面端处理） */
    data object InteractionMissing : TaskDecisionOutcome()

    /** 回执拒绝（附 reasonCode/status 详情） */
    data class Rejected(val reason: String) : TaskDecisionOutcome()

    /** 未送达（ack null：连接断开/不在连接态） */
    data object Undelivered : TaskDecisionOutcome()
}

/** getTaskTokenUsage 只读投影（ZCodeTaskTokenUsageResult 子集）（:677-681）。 */
data class TaskTokenUsage(
    val input: Int,
    val output: Int,
    val total: Int,
)

/**
 * 会话排队消息（桌面 composer 上方的 pending 队列；state.queue 同源投影）。
 * iOS 原文位于 AppSession.swift:139-159（协议面 queueInfo 依赖，随本批落位 :store）。
 */
data class RemoteQueueItem(
    /** queueItemId（sendQueuedNow/editQueueItem/deleteQueueItem/reorderQueueItem 的定位键） */
    val id: String,
    val text: String,
    /** delivery.admitted（"queue"=排队等待 / "guide"=引导模式待发） */
    val admitted: String? = null,
) {
    val isGuide: Boolean get() = admitted == "guide"
}

data class ConversationQueueInfo(
    val items: List<RemoteQueueItem>,
    /** 自动排空（桌面 queue.autoDrain；setAutoDrain 命令切换） */
    val autoDrain: Boolean,
)

/**
 * Swift `T??`（双重可选）的 Kotlin 承载：参数 null = 不修改该键；非 null 包装 = 覆写
 * （[value] 可为 null = 置空，如 subagentModel 置 null=跟随主模型）。
 * 仅用于 amendWorkflowRunSettings 的两个参数位（StoreProtocols.swift:148-154 语义）。
 */
class OptionalArg<T>(val value: T?) {
    override fun equals(other: Any?): Boolean = other is OptionalArg<*> && value == other.value

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String = "OptionalArg($value)"
}

/** git.commit 回执：hash 非 null 且非空=提交生效（回执 hash 为成功唯一凭据）；
 * 失败 hash=null，errorMessage 必带可读原因（服务端 fault message 优先——写面禁止静默）（:783-788）。 */
data class GitCommitOutcome(
    val hash: String?,
    val errorMessage: String?,
)

/**
 * 检查点行（P3-11：git-checkpoint.diffCheckpoints 宽容投影——id/时间/说明字段名
 * 均未取证，多形态兼容见 RemoteFileStore.parseCheckpoint；时间支持毫秒/ISO 双形态）（:790-796）。
 * date 为 epoch 毫秒（Swift Date? 对应物）。
 */
data class CheckpointInfo(
    val id: String,
    val date: Long? = null,
    val label: String? = null,
)

/**
 * git.getIdentity 只读投影（提交面板预检）。回执字段未在 web 消费点出现
 * 【未取证】——name/email 宽容解析（name|userName / email|userEmail）；
 * 两字段可同时为 null（身份对象在场但配置缺失，web 端此态禁用提交按钮）（:798-818）。
 */
data class GitIdentityInfo(
    val name: String? = null,
    val email: String? = null,
) {
    /** 是否完整（web identityMissing 口径：缺任一字段视为未配置） */
    val isComplete: Boolean
        get() = !name.isNullOrEmpty() && !email.isNullOrEmpty()

    val displayText: String
        get() = when {
            name != null && email != null -> "$name <$email>"
            name != null -> name
            email != null -> email
            else -> ""
        }
}

/** readTextFile 分页读结果（FileTextSlice 口径：content + totalBytes）（:820-825）。 */
data class FileContentPage(
    val content: String,
    val totalBytes: Int,
    val isTruncated: Boolean,
)

// MARK: - 输入预测 ghost hint（3.17.2；iOS Models.swift:373-390 对位）

/**
 * 输入预测候选（mobile-input-prediction.md 线格式：0..3 个，confidence 降序；
 * text 长度 1..40、单行、非纯空白、不 trim——接在草稿后面）。
 */
data class InputPredictionCandidate(
    val text: String,
    val confidence: Double,
)

/**
 * 输入预测回执投影（spec「手机端建议⑤」）：仅 decision=="show" 展示首候选；
 * `topText` 供 VM 直接拼接（draft + text，不 trim）。suppress 族（置信度不足/上下文
 * 不足）/unavailable/cancelled 一律不展示（VM 侧任何异常/非 show = 无 hint，
 * 不提示用户、不重试）。
 */
data class InputPredictionResult(
    val decision: String,
    val candidates: List<InputPredictionCandidate>,
    val requestId: String?,
    val revision: Long,
) {
    val topText: String?
        get() {
            if (decision != "show") return null
            val first = candidates.firstOrNull() ?: return null
            if (first.text.isBlank()) return null
            return first.text
        }
}

// MARK: - 会话存储协议（StoreProtocols.swift:15-292 + extension 默认 :326-507）

/**
 * 会话存储协议。接入真实 ZCode 后端时按此边界替换实现。
 * Kotlin interface 默认方法恒动态分派（Swift「本期接入面」的本体 requirement 声明约束
 * 在 Kotlin 不存在，见文件头映射约定）；默认实现 = 未连接/演示态空实现口径。
 */
interface ConversationStore {
    /**
     * 连接态只读边界：true 时执行类入口（发送/回复提问/审批）由 UI 禁用并提示。
     * mock 演示实现取默认值 false，演示态交互完全不变。（:17-19）
     */
    val isReadOnly: Boolean
        get() = false

    suspend fun conversations(): List<Conversation>

    fun observeConversations(): Flow<ConversationEvent> = emptyFlow()

    suspend fun messages(conversationId: String): List<ChatMessage>

    /**
     * 发送用户消息；回复经事件流逐字推送（模拟流式输出）。
     * 返回是否受理（连接态=信封下发成功；false=未送达，调用方可如实反馈）（:23-26）。
     */
    suspend fun send(text: String, conversationId: String): Boolean = false

    suspend fun answerQuestion(reply: String, conversationId: String, questionId: String) {}

    suspend fun createConversation(
        title: String,
        directory: String,
        executor: ExecutorKind,
        modelSelection: NewSessionModelSelection?,
    ): Conversation

    suspend fun setPinned(pinned: Boolean, conversationId: String) {}

    suspend fun setArchived(archived: Boolean, conversationId: String) {}

    suspend fun markRead(conversationId: String) {}

    // MARK: 本期接入面（Swift :34-55 的分派约束说明见接口 KDoc；此处默认实现即协议扩展默认）

    /** 向上分页：取更早历史行（拼接去重由实现负责）；返回是否还有更早数据。（:40-41） */
    suspend fun loadOlder(conversationId: String): Boolean = false

    /**
     * 最近一次 createConversation 失败原文（连接态新建失败行数据源；空串 = 无——
     * 真实拒收原因上屏，禁止假成功）。（:42-45）
     */
    suspend fun lastCreateFailureText(): String = ""

    /**
     * 最近一次归档/取消归档写失败原文（空串 = 无；列表页归档动作如实反馈——
     * 写面禁止静默：曾因失败只回滚 override 不回滚缓存也不提示，归档行「本会话在、
     * 重启蒸发」，用户报障 2026-10-07「归档的会话又丢了」）。（:46-48/:335-338）
     */
    suspend fun lastArchiveFailureText(): String = ""

    /** 最近一次任务元数据写（置顶/改名/未读）失败原文（空串 = 无；§11.2 写面禁静默）。（:49-51/:340-341） */
    suspend fun lastTaskWriteFailureText(): String = ""

    /** 会话关闭释放订阅（2026-10-08 回归审核轮 §11.1#2；远端实现携 subscriptionId 真退订 + 回收水位/revision）。（:52-55/:343-344） */
    suspend fun closeConversation(conversationId: String) {}

    /**
     * 会话最近一次加载失败文本（连接态订阅/历史行拉取失败的 UI 透出面；null = 无失败
     * ——空会话与失败由此区分。真机报障「消息区空白且无提示」修复的 read 面）。（:56-59/:349-350）
     */
    suspend fun conversationLoadFailure(conversationId: String): String? = null

    /**
     * 纯内存会话摘要（P0 缓存先行：无 RPC——首屏标题等元数据即时投影；默认 null，
     * 远程实现=bootstrap/sessions-index/task 索引合并表的即时读取）。（:60-63/:351）
     */
    suspend fun cachedConversation(conversationId: String): Conversation? = null

    /** workspace 级配置目录只读投影（ChatView chips 数据源；null = 无数据）。（:64-65/:353-354） */
    suspend fun workspaceConfig(): WorkspaceConfigInfo? = null

    /**
     * 会话级模型选择（state.modelSelection——桌面 composer 变更随会话 state delta
     * 到达；覆盖 workspace 级 getView 作为 chips 权威显示源；provider 是 getView
     * 失败时同模型/思考档切换的权威兜底源；null = 无数据）。（:66-69/:358）
     */
    suspend fun sessionModelSelection(conversationId: String): SessionModelSelection? = null

    /**
     * 会话协作模式投影（state.config.mode——上游 onSessionModeChanged 投影，命令面
     * 与 plan 工具路径共用；chip 权威显示源；null = 桌面尚未投影）。（:70-72/:359）
     */
    suspend fun sessionCollaborationMode(conversationId: String): String? = null

    /** 工作流产物内容分块读（conversationWorkflowRunArtifactReadV4；null = 失败/未连接）。（:73-76/:360-362） */
    suspend fun workflowArtifactContent(
        sessionId: String,
        runId: String,
        artifactId: String,
        version: Int,
    ): ArtifactContent? = null

    /** 桌面端模型选择只读视图（model-selection.getView；null = 无数据）。（:77-78/:356-357） */
    suspend fun modelSelectionView(): ModelSelectionInfo? = null

    /** 模型选择变化流（连接态 model-selection.onDidChange 驱动；演示态不产生事件）。（:79-80/:364-367） */
    fun observeModelSelection(): Flow<ModelSelectionInfo?> = emptyFlow()

    /** 待处理交互投影列表（连接态 conversation state.pendingInteractions；演示态空）。（:81-82/:369-370） */
    suspend fun pendingInteractionList(conversationId: String): List<RemotePendingInteraction> = emptyList()

    /**
     * 3.17.2 输入预测（ghost hint）：只读、无状态查询，不改会话、不做 CAS。
     * 能力门 `hello.capabilities.inputPrediction === true` 才发（连接装配注入；
     * iOS inputPredictionAvailable :250-253 同口径）；任何异常 / 非 show 决策一律
     * null（无 hint），不提示用户、不重试【实证·上游仓
     * docs/web-remote-control/mobile-input-prediction.md +
     * zcode-protocol-v4/input-prediction/contract.ts strict schema + spec 失败语义】。
     * 请求 = 会话 target（workspace 寻址，A7）+ revision（当前 state 缓存，缓存键的一部分）
     * + draft（超 4000 截断；空 = 预测下一条完整输入）+ requestId（原样回声供调用方丢过期）。
     * （iOS RemoteConversationStore.inputPrediction :2924-2964 对位）
     */
    suspend fun inputPrediction(conversationId: String, draft: String, requestId: String): InputPredictionResult? =
        null

    /** 重命名会话（连接态 zcode-task.renameTask 双写；演示态本地改）。（:83-84/:372-373） */
    suspend fun renameConversation(title: String, conversationId: String) {}

    /** 标记未读（连接态 zcode-task.setTaskUnread unread:true；compare-and-clear 的反向）。（:85-86/:375-376） */
    suspend fun markUnread(conversationId: String) {}

    /** 已归档会话（连接态 listArchivedTasks 只读拉取 + 本地归档动作合并；演示态空）。（:87-88/:378-379） */
    suspend fun archivedConversations(): List<Conversation> = emptyList()

    /** 删除任务（桌面失败任务「清理」/归档区删除同款：zcode-task.deleteTask）。（:89-91/:380） */
    suspend fun deleteTask(conversationId: String): Boolean = false

    /**
     * 交互应答信封（连接态 v4 resolveInteraction；演示态无交互面）。
     * A-3：answer 恒为对象按交互族分形（权限 {optionId} / 计划 {action} / 提问
     * {freeText}|{optionId}），由调用方构造；返回命令回执供如实反馈（null=未送达）。（:92-96/:382-384）
     */
    suspend fun resolveInteractionRaw(
        conversationId: String,
        interactionId: String,
        answer: JSONValue,
    ): JSONValue? = null

    /**
     * 失败 turn 重试（G-015）：携行元数据精确游标 {rowId, entityId} 下发 retryTurn；
     * 游标缺失（entityId=null）时调用方不渲染入口，本实现亦不下发。
     * 返回命令回执供如实反馈（null=未送达——此前 `_ =` 丢弃，被拒纯静默）。（:97-101/:386-388）
     */
    suspend fun retryTurn(conversationId: String, rowId: Int, entityId: String?): JSONValue? = null

    /** 会话派生（G-018）：forkAssistant（session 类放行分支）；返回新会话 id（失败 null）。（:102-103/:390-391） */
    suspend fun forkConversation(conversationId: String): String? = null

    /**
     * 会话分组管理写面（G-017，均为索引元数据写、桌面代执行合法）。B-3/B-4/C-3/C-4
     * 按 web bundle 实证形状重写（2026-10-06）：createTaskGroup 零参 + 回执 groupId、
     * 落名走 renameTaskGroup{groupId,title,workspaceScopes}；updateTaskGroupColor/
     * deleteTaskGroup 均补 workspaceScopes；applyGroupedTaskViewOrder 为全量视图写
     * {workspaceScopes, topLevelNodes, groups[{groupId, taskRefs}]}——由
     * moveConversationToGroup 全链组合（拉结构→定位/建组→整视图重建提交），UI 不
     * 直接拼视图。返回 null=成功、非 null=失败原因（写面禁止静默，UI 必须如实提示）。（:104-114/:393-405）
     */
    suspend fun moveConversationToGroup(conversationId: String, groupName: String): String? =
        "未连接桌面端，分组未同步"

    suspend fun renameTaskGroup(groupId: String, title: String): String? =
        "未连接桌面端，分组未同步"

    suspend fun updateTaskGroupColor(groupId: String, color: String): String? =
        "未连接桌面端，分组未同步"

    suspend fun deleteTaskGroup(groupId: String): String? = "未连接桌面端，分组未同步"

    /** 子代理只读转录（G-021）：按 actor.sessionId 拉一页 rowsRange 渲染（无新协议）。（:115-116/:407-408） */
    suspend fun actorTranscript(sessionId: String, limit: Int): List<ChatMessage> = emptyList()

    /** 会话上下文用量（G-021：连接态 state.runtime.contextUsage；nil=无数据不渲染）。（:117-118/:410-411） */
    suspend fun sessionContextUsage(conversationId: String): ContextUsageInfo? = null

    /** 附件预览读（G-014：连接态 attachmentReadV4 分块聚合 image/video/pdf；演示态恒 null）。（:119-121/:413-414） */
    suspend fun attachmentPreview(sessionId: String, ref: String): AttachmentPreview? = null

    /** 会话全文检索（G-018：连接态 zcode-task listTaskList searchQuery 透传；演示态空 → 列表回退本地过滤）。（:122-124/:416-417） */
    suspend fun searchSessions(query: String): List<Conversation> = emptyList()

    /** 桌面 workflow 运行进度（要求 5 · 只读；nil = 无数据不渲染；不新增任何发送命令）。（:125-127/:419-420） */
    suspend fun workflowRun(conversationId: String): WorkflowRunSummary? = null

    /** 会话全部 workflow run（多 run 面板；活 run 优先。缺省退化为单 run 包装）。（:128-129/:422-425） */
    suspend fun workflowRuns(conversationId: String): List<WorkflowRunSummary> =
        listOfNotNull(workflowRun(conversationId))

    // MARK: 会话面板（goal/plan/btw/side；数据源 state.* 只读投影 + 控制命令面）（:130-179/:427-446）

    /** state.goal 只读投影（null = 无目标不渲染） */
    suspend fun goalSummary(conversationId: String): RemoteGoalSummary? = null

    /** state.plan 只读投影（null = 无计划不渲染） */
    suspend fun planPanel(conversationId: String): PlanPanelSummary? = null

    /** state.backgroundWorks 只读投影（btw 面板数据源） */
    suspend fun backgroundWorks(conversationId: String): List<BackgroundWorkSummary> = emptyList()

    /** state.subagents 只读投影（side 面板数据源） */
    suspend fun subagentSessions(conversationId: String): List<SubagentSessionSummary> = emptyList()

    /**
     * `state.subagents` 会话级投影（v4.0.0 `subagentProjectionStateSchema`）：
     * running / ended / endedTotal / revision 四块。null = 旧桌面未发该键。
     * 与 [subagentSessions]（旧行级读面）并存，活动摘要行用本方法。
     */
    suspend fun subagentProjection(conversationId: String): SubagentProjectionState? = null

    /**
     * 会话子代理历史目录（`session/subagents` 只读查询，可分页）。
     * 与 [subagentProjection] 分工：投影=本批（含 running）；本方法=完整历史清单。
     * null = 查询失败/旧桌面无该方法（UI 不显示目录入口）。
     */
    suspend fun subagentDirectory(
        conversationId: String,
        cursor: String? = null,
        limit: Int = 20,
    ): SessionSubagentDirectory? = null

    /** `state.activitySummaryDismissed`（v4.0.0）：用户忽略过的活动摘要条目。null = 未忽略/旧桌面。 */
    suspend fun activitySummaryDismissed(conversationId: String): ActivitySummaryDismissed? = null

    /** `state.completedActivityCount`（v4.0.0）：完整历史里成功的独立 child + Workflow 数；null = 缺席或读取失败。 */
    suspend fun completedActivityCount(conversationId: String): Int? = null

    /** 目标暂停/继续（pauseGoal / resumeGoal；桌面代执行；返回命令回执供失败反馈） */
    suspend fun setGoalPaused(paused: Boolean, conversationId: String): JSONValue? = null

    /** 取消后台工作（cancelBackgroundWork {workId}；返回命令回执供失败反馈） */
    suspend fun cancelWork(conversationId: String, workId: String): JSONValue? = null

    /** 恢复工作流运行（resumeWorkflowRun {workId, name?}；返回命令回执供失败反馈） */
    suspend fun resumeWorkflowRun(conversationId: String, workId: String, name: String?): JSONValue? = null

    /**
     * 工作流运行设置（amendWorkflowRunSettings {workId, subagentModel?, maxConcurrency?}）：
     * 双重可选语义——参数 null = 不修改该键；[OptionalArg](null) = 置 null（跟随主模型/解除上限）。
     * 返回命令回执供失败反馈
     */
    suspend fun amendWorkflowRunSettings(
        conversationId: String,
        workId: String,
        subagentModel: OptionalArg<String>?,
        maxConcurrency: OptionalArg<Int>?,
    ): JSONValue? = null

    /** 主模型/思考强度切换（switchModelConfig {provider, model, thought}；桌面代执行，onDidChange 回流驱动 chips 同步） */
    suspend fun switchModelConfig(
        conversationId: String,
        provider: String,
        model: String,
        thought: String?,
    ): JSONValue? = null

    /** 主动全量 resync（服务端重发快照；amend 停旧换新后拉新 run 用，实现内节流） */
    suspend fun triggerResync(conversationId: String) {}

    /** 模型可用思考档（workspace-config configOptions 词表；getView 不携带） */
    suspend fun thoughtLevels(model: String): List<String> = emptyList()

    /** 排队队列只读投影（state.queue；null = 无排队） */
    suspend fun queueInfo(conversationId: String): ConversationQueueInfo? = null

    /** 队列条目立即发送（CAS） */
    suspend fun sendQueuedNow(conversationId: String, queueItemId: String): JSONValue? = null

    /** 队列条目文本编辑 */
    suspend fun editQueueItem(conversationId: String, queueItemId: String, newText: String): JSONValue? = null

    /** 队列条目删除 */
    suspend fun deleteQueueItem(conversationId: String, queueItemId: String): JSONValue? = null

    /** 队列条目重排（beforeQueueItemId=null = 移到队尾） */
    suspend fun reorderQueueItem(
        conversationId: String,
        queueItemId: String,
        beforeQueueItemId: String?,
    ): JSONValue? = null

    /** 自动排空开关（CAS） */
    suspend fun setAutoDrain(conversationId: String, enabled: Boolean): JSONValue? = null

    // MARK: v4 命令扩容（发送层；:181-232/:448-469）

    /** 协作模式切换（Plan/Build；CAS 类，mode: "plan"|"build"） */
    suspend fun switchCollaborationMode(conversationId: String, mode: String): JSONValue? = null

    /**
     * 投递/跟随模式（CAS 类；A-2：mode 值域 queue|guide——now 档为本地语义，
     * 调用方拦截不下发命令，见 ChatViewModel.setDeliveryMode）
     */
    suspend fun setFollowupMode(conversationId: String, mode: String): JSONValue? = null

    /**
     * 助手消息轻反馈（CAS+row-target；value true=赞 false=踩 null=取消；
     * entityId 缺失时远端实现拒发——调用方须先保证游标在场再渲染入口）
     */
    suspend fun setAssistantFeedback(
        conversationId: String,
        rowId: Int,
        entityId: String?,
        value: Boolean?,
    ): JSONValue? = null

    /** 编辑用户消息并重发（CAS+row-target，与 retryTurn 同族；rowId+entityId 为行游标） */
    suspend fun editUserQuery(
        conversationId: String,
        rowId: Int,
        entityId: String?,
        newText: String,
    ): JSONValue? = null

    /** 压缩上下文（非 CAS；完成判定另看 contextUsage 回落） */
    suspend fun compact(conversationId: String): JSONValue? = null

    /** 挂起交互「稍后处理」（{interactionId}；命令本体未取证） */
    suspend fun snoozeInteractionAutoResolution(conversationId: String, interactionId: String): JSONValue? = null

    /** 目标下发（改 state.goal；按 CAS 预期处理，探针裁决） */
    suspend fun sendGoalCommand(conversationId: String, text: String): JSONValue? = null

    /**
     * 启动已保存工作流（conversationId 传 null = 无会话上下文，会话由桌面创建；
     * 回执 result.sessionId 宽容提取交调用方跳转；args 为动态参数表单键值）
     */
    suspend fun startSavedWorkflow(
        conversationId: String?,
        workflowId: String,
        args: Map<String, JSONValue>?,
    ): JSONValue? = null

    // MARK: workspace hook 信任审核（:213-232/:462-469）

    /** 应答信任审核（decision:{action:'trust_selected', reviewItemIds}，web 实证唯一 action）；返回命令回执供如实反馈（null=未送达/无挂起交互） */
    suspend fun respondWorkspaceHookReview(conversationId: String, reviewItemIds: List<String>): JSONValue? = null

    /** 主动请求重发信任审核（{sessionId, remoteSessionId?, workspaceIdentity, bundleDigest}，web 两调用点取证；无 digest 可携返回 null） */
    suspend fun requestWorkspaceHookReview(conversationId: String): JSONValue? = null

    /** 单条审核项信任开关（{reviewItemId, enabled}；web 仅 schema 无调用点，enabled 语义未取证——暂无 UI 入口） */
    suspend fun toggleWorkspaceHookReviewItem(conversationId: String, reviewItemId: String, enabled: Boolean): JSONValue? = null

    /** 撤销已信任 hook 项（{reviewItemIds}；写桌面信任账本，UI 必须带确认弹层） */
    suspend fun revokeWorkspaceHookTrust(conversationId: String, reviewItemIds: List<String>): JSONValue? = null

    // MARK: 附件事务四方法 + 携附件发送（:233-291/:470-506）

    /**
     * A-4 附件上传事务（web 对齐）：四条 **channel RPC**（zcode-agent 直发，非 v4 会话
     * 命令、无信封）。键面（2026-10-10 附件链路轮对齐上游实装修正）：会话域 workspace
     * 信封（workspacePath + workspaceIdentity?）**恒在场**——2026-10-06 真机实证缺
     * workspacePath 被 Invalid params 拒、上游 facade getClient(params) 按
     * params.workspacePath 选 CLI（zcodeAgentService.ts:3037）；事务键 Begin 7 键 /
     * Chunk 4 键 / Commit·Abort 2 键全 strict。**客户端参数绝无 connectionId**——
     * 由桌面 host facade 注入（withTrustedConnection 先删后写真值，
     * zcodeAgentConnectionScope.ts:91-110），多发即 Invalid params（甲-2/A11）。
     * 中继瞬断首败 1.2s 退避重试一次（Abort 例外：尽力收口不重试）；失败以
     * Result.failure 携错误文本回传 UI（禁静默吞错 §5-12）。
     * 演示态默认实现返回 failure（无上传面）。（:233-241/:470-486）
     */
    suspend fun attachmentBeginV4(
        sessionId: String,
        uploadId: String,
        fileName: String,
        mime: String,
        totalBytes: Int,
        totalChunks: Int,
        checksum: String,
    ): Result<AttachmentBeginOutcome> =
        Result.failure(AttachmentRPCError("演示态无附件上传面"))

    /** 分块下发；成功值 = 回执 nextChunkIndex（进度判定=其恒等于 chunkIndex+1，web PCe 同款） */
    suspend fun attachmentChunkV4(
        sessionId: String,
        uploadId: String,
        chunkIndex: Int,
        dataBase64: String,
    ): Result<Int> = Result.failure(AttachmentRPCError("演示态无附件上传面"))

    /** 事务收口；成功值 = 回执 ref（随 sendText attachments 携带） */
    suspend fun attachmentCommitV4(sessionId: String, uploadId: String): Result<String> =
        Result.failure(AttachmentRPCError("演示态无附件上传面"))

    /** 失败路径中止（尽力收口；Abort 送达与否影响重试是否重开新事务） */
    suspend fun attachmentAbortV4(sessionId: String, uploadId: String): Result<Unit> =
        Result.failure(AttachmentRPCError("演示态无附件上传面"))

    /**
     * 携附件发送（P1-1）：attachments 为 committed 引用列表，随 sendText payload
     * `attachments` 字段下发。B-1（web 对齐）：元素键 `{ref, fileName, mime, bytes}`。
     * refs 为空时行为与 send 一致。requestedDelivery 投递模式（P1-2）：null/空串不携带键；
     * "startNow" 为 now 档恒携值（A-2 修正，web sendText delivery 枚举 startNow|queue|guide【实证】）。
     * modelSelection（P0 修复 2026-10-07）：新建会话 draft 路径的会话前选择随首条 sendText
     * `modelSelection` 下发【实证·上游仓 command.ts sendText schema】；null 不携带键。
     * 回执判定【实证·上游仓 command.ts commandAckSchema】：
     * status ∈ accepted|rejected|stale|duplicate|noop|failed——非 accepted 视为
     * 未送达（拒因经 sendRejectionText 读出，撤销乐观回显，禁假成功）。（:246-270/:487-492）
     */
    suspend fun sendWithAttachments(
        text: String,
        attachments: List<OutgoingAttachment>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        conversationId: String,
    ): Boolean = send(text, conversationId)

    /**
     * held 态确认重发（2026-10-08 回归审核轮 §11.2，上游 command.ts:78-95：
     * 暂停队列〔setAutoDrain(false)〕下 sendText 被拒 heldQueueDispositionRequired
     * / guard.heldQueueConfirmationStale 后，携 disposition
     * （clearQueueAndSend|keepQueueAndSend）+ expectedHeldQueueItemIds 重发）（:272-281/:494-499）。
     */
    suspend fun sendHeldConfirmed(
        text: String,
        attachments: List<OutgoingAttachment>,
        disposition: String,
        expectedHeldQueueItemIds: List<String>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        conversationId: String,
    ): Boolean = false

    /** 最近一次发送是否卡在 held 确认（确认弹层数据源；成功重发/新发送即清）（:283-284/:501） */
    suspend fun heldQueueConfirmationPending(conversationId: String): Boolean = false

    /** 当前挂起队列条目 id 集（确认重发 expectedHeldQueueItemIds 数据源）（:286-287/:503） */
    suspend fun queueHeldItemIds(conversationId: String): List<String> = emptyList()

    /** 最近一次 sendText 的桌面拒因（null = 无在案拒因；写失败时写入、成功时清除——UI 错误行据实呈现）（:289-291/:505-506） */
    suspend fun sendRejectionText(conversationId: String): String? = null
}

/**
 * 会话级模型选择三元组（StoreProtocols.swift:69 的 Swift 元组 `(model, thought?, provider?)`
 * 的 Kotlin 承载——具名承载便于消费侧与 v1.36 扩展字段对齐）。
 */
data class SessionModelSelection(
    val model: String,
    val thought: String?,
    val provider: String?,
)

/** 工作流产物内容分块读成功值（StoreProtocols.swift:74-76 元组的具名承载）。 */
data class ArtifactContent(
    val data: ByteArray,
    val mediaType: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ArtifactContent && data.contentEquals(other.data) && mediaType == other.mediaType

    override fun hashCode(): Int = data.contentHashCode() * 31 + mediaType.hashCode()
}

// MARK: - 任务存储协议（StoreProtocols.swift:625-674）

/** 任务存储协议。 */
interface TaskStore {
    /** 连接态只读边界（同 ConversationStore.isReadOnly） */
    val isReadOnly: Boolean
        get() = false

    suspend fun tasks(): List<TaskRecord>

    fun observeTasks(): Flow<List<TaskRecord>> = emptyFlow()

    /**
     * 审批决议（U-4：optionId 透传——A-3 后 answer={optionId}，四族
     * allowOnce/allowAlways/rejectOnce/rejectAlways 由 UI 拼好传入）
     */
    suspend fun approve(taskId: String, optionId: String): TaskDecisionOutcome = TaskDecisionOutcome.Undelivered

    suspend fun reject(taskId: String, optionId: String): TaskDecisionOutcome = TaskDecisionOutcome.Undelivered

    /**
     * 停止任务（连接态 v4 stop 桌面代执行）。返回失败文案（null = 已送达/演示态）——
     * M2 写面如实回传：停止失败此前静默，用户无从得知任务是否真的停了
     */
    suspend fun stop(taskId: String): String? = null

    suspend fun retry(taskId: String) {}

    /** 模拟后台终端输出流（后台 Bash） */
    fun terminalStream(taskId: String): Flow<TerminalLine> = emptyFlow()

    /** 模型轨迹行 */
    suspend fun trajectoryLines(taskId: String): List<TerminalLine> = emptyList()

    // MARK: 本期接入面（分派约束说明同 ConversationStore）

    /** getTaskConfigOptions 只读投影（option 名 → 当前值；null = 无数据） */
    suspend fun taskConfigOptions(taskId: String): Map<String, String>? = null

    /** getTaskModelSelection 只读投影（当前绑定模型显示名；null = 无数据） */
    suspend fun taskModelSelection(taskId: String): String? = null

    /** getTaskTokenUsage 只读投影；null = 无数据 */
    suspend fun taskTokenUsage(taskId: String): TaskTokenUsage? = null

    /** 服务端全文搜索（连接态 listTaskList search 透传；演示态返回空走本地过滤） */
    suspend fun searchTasks(query: String): List<TaskRecord> = emptyList()
}

// MARK: - 文件与 Diff 存储协议（StoreProtocols.swift:683-781）

/** 文件与 Diff 存储协议。 */
interface FileStore {
    suspend fun fileTree(): List<FileNode>

    suspend fun diffFiles(): List<DiffFile>

    suspend fun content(path: String): String

    /**
     * 逐文件决定（连接态=git.stagePaths/unstagePaths 桌面代执行；path=DiffFile.stagePath
     * ?? path）。**返回 null=成功；非 null=可直接上屏的错误文案（含服务端 reason）**——
     * 写面禁止静默吞错（2026-10-06 回归纪律）。
     */
    suspend fun setFileDecision(path: String, approved: Boolean?): String? =
        "未连接桌面端，批准/拒绝需要在连接后操作"

    /** 全部批准（未决文件一次性 stagePaths）。返回值语义同 setFileDecision。 */
    suspend fun approveAll(): String? = "未连接桌面端，批准/拒绝需要在连接后操作"

    // MARK: 本期接入面

    /** 远端实现标记（连接态）：搜索/分页读/会话维度变更等远端能力仅连接态提供 */
    val isRemote: Boolean
        get() = false

    /** 服务端搜索（file.searchWorkspaceFiles）；演示态返回空走本地过滤 */
    suspend fun searchFiles(query: String, limit: Int): List<FileNode> = emptyList()

    /** git.getChanges 是否有超出有界展示（20 条）的更多变更（「更多」提示判定） */
    suspend fun hasMoreChanges(sourceId: String): Boolean = false

    /** 按 sourceId（unstaged/staged）拉取工作区变更；默认实现忽略维度（mock 单维度） */
    suspend fun diffFiles(sourceId: String): List<DiffFile> = diffFiles()

    /** 有界读一页文本（readTextFile offset/length + totalBytes）；默认实现整读后包装 */
    suspend fun contentPage(path: String, offset: Int, length: Int): FileContentPage {
        if (offset != 0) return FileContentPage(content = "", totalBytes = 0, isTruncated = false)
        val full = content(path)
        return FileContentPage(content = full, totalBytes = full.toByteArray(Charsets.UTF_8).size, isTruncated = false)
    }

    /** 二进制预览首块（file.readBinaryPreview，图片/PDF 只读预览；演示态/失败返回 null） */
    suspend fun binaryPreview(path: String, maxBytes: Int): ByteArray? = null

    /** 会话维度文件变更（conversationFileChangesV4）；演示态返回空 */
    suspend fun sessionDiffFiles(sessionId: String): List<DiffFile> = emptyList()

    /** 文件树变更通知（file-watcher.onDynamicChange 驱动；演示态立即结束） */
    fun observeFileTreeChanges(): Flow<Unit> = emptyFlow()

    // MARK: 一站式提交 + 检查点（P3-8/P3-11：git / git-checkpoint 写族 UI 化，连接态 only）（:713-733/:771-780）

    /** AI 生成本次提交信息（git.generateCommitMessage，paths=已暂存文件）；失败返回 null（UI 降级为手写）。演示态不可达。 */
    suspend fun generateCommitMessage(paths: List<String>): String? = null

    /** 提交身份只读预检（git.getIdentity {workspacePath}）；null = 调用失败（区别于「已读取但字段空」） */
    suspend fun gitIdentity(): GitIdentityInfo? = null

    /** 提交已暂存变更（git.commit）；成功判定以回执为准：hash 非 null 且非空=提交生效 */
    suspend fun commit(message: String): GitCommitOutcome = GitCommitOutcome(hash = null, errorMessage = null)

    /** 检查点清单（git-checkpoint.diffCheckpoints，按时间倒序）；null = 拉取失败（区别于空清单）。 */
    suspend fun checkpoints(): List<CheckpointInfo>? = null

    /** 创建检查点（git-checkpoint.createCheckpoint，note 可选说明）。 */
    suspend fun createCheckpoint(note: String?): Boolean = false

    /** 恢复工作区到检查点（git-checkpoint.restoreBetweenCheckpoints，破坏性——UI 侧硬要求 destructive 确认弹层）。 */
    suspend fun restoreCheckpoint(checkpoint: CheckpointInfo): Boolean = false
}

// MARK: - 设置存储协议（StoreProtocols.swift:827-831）

/** 设置存储协议（iOS UserDefaults 持久化；Android 由装配层以 DataStore/Preferences 承接）。 */
interface SettingsStore {
    fun load(): AppSettingsType

    fun save(settings: AppSettingsType)
}

/** AppSettings 别名（类型本体在 :protocol Models.kt——此处只消 iOS `AppSettings` 的同名引用面）。 */
typealias AppSettingsType = cn.biuz.android.protocol.AppSettings

// MARK: - 未连接态空实现 Store（StoreEnvironment.swift 直译；移除 Mock 后的编译基座）
//
// 全部读面返回空、观察流立即结束；写面如实失败——`send()` 返回 false（协议默认
// `sendWithAttachments` 转发 send 的返回值，覆写 send 即令整条转发链为 false，
// U-6 composer 发送失败错误行由此兜底）；`approve/reject` 返回 .undelivered
// （U-5 审批「未送达」口径）；`createConversation` 不返回成功占位（空 id——
// 未连接态新建入口不可达，防御性兜底防 onCreated → openChat 假成功导航）。
// 仅远端能力吃接口默认实现（null/[]/空流），不逐一覆写；isReadOnly/isRemote 亦取默认 false。

/** 未连接态空会话 Store（StoreEnvironment.swift EmptyConversationStore 直译）。 */
class EmptyConversationStore : ConversationStore {
    override suspend fun conversations(): List<Conversation> = emptyList()

    override fun observeConversations(): Flow<ConversationEvent> = emptyFlow()

    override suspend fun messages(conversationId: String): List<ChatMessage> = emptyList()

    override suspend fun send(text: String, conversationId: String): Boolean = false

    override suspend fun answerQuestion(reply: String, conversationId: String, questionId: String) {}

    override suspend fun createConversation(
        title: String,
        directory: String,
        executor: ExecutorKind,
        modelSelection: NewSessionModelSelection?,
    ): Conversation = Conversation(
        id = "",
        title = title,
        summary = "",
        directory = directory,
        updatedAt = System.currentTimeMillis(),
    )

    override suspend fun setPinned(pinned: Boolean, conversationId: String) {}

    override suspend fun setArchived(archived: Boolean, conversationId: String) {}

    override suspend fun markRead(conversationId: String) {}
}

/**
 * 未连接态空任务 Store（StoreEnvironment.swift EmptyTaskStore 直译）。
 * 【偏差】iOS 原文 `stop` 返回 nil（= 已送达）；Android 按红线「写如实失败」改为
 * 拒因文本——未连接态停止指令不可能送达，返回 nil 即假成功（AGENTS §5-12 同根因）。
 */
class EmptyTaskStore : TaskStore {
    override suspend fun tasks(): List<TaskRecord> = emptyList()

    override fun observeTasks(): Flow<List<TaskRecord>> = emptyFlow()

    override suspend fun approve(taskId: String, optionId: String): TaskDecisionOutcome =
        TaskDecisionOutcome.Undelivered

    override suspend fun reject(taskId: String, optionId: String): TaskDecisionOutcome =
        TaskDecisionOutcome.Undelivered

    override suspend fun stop(taskId: String): String? = "未连接桌面端，停止指令未送达"

    override suspend fun retry(taskId: String) {}

    override fun terminalStream(taskId: String): Flow<TerminalLine> = emptyFlow()

    override suspend fun trajectoryLines(taskId: String): List<TerminalLine> = emptyList()
}

/** 未连接态空文件 Store（StoreEnvironment.swift EmptyFileStore 直译；写面失败文案取接口默认）。 */
class EmptyFileStore : FileStore {
    override suspend fun fileTree(): List<FileNode> = emptyList()

    override suspend fun diffFiles(): List<DiffFile> = emptyList()

    override suspend fun content(path: String): String = ""
}

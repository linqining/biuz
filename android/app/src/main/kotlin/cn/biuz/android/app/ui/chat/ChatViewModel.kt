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

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.ConversationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) {

    companion object {
        /** 撤焦→呈现延迟（iOS ChatView.swift:2187：120_000_000 ns）。 */
        const val PANEL_DELAY_MS = 120L

        /** 斜杠提示 3s 自动清除（iOS ChatViewModel.swift:1112）。 */
        const val SLASH_HINT_CLEAR_MS = 3_000L

        /** 协作模式本地切换护栏：ack 权威成功后 3s 内旧投影不回闪（iOS :289-293）。 */
        const val MODE_SWITCH_GUARD_MS = 3_000L

        /**
         * 协作模式词表（switchCollaborationMode mode 值域【实证·web bundle v1.12】：
         * `La(["build","edit","plan","yolo"])` 4 档全集；初值兜底 build（web 同构）。
         */
        val COLLABORATION_MODES = listOf("build", "edit", "plan", "yolo")
        const val DEFAULT_COLLABORATION_MODE = "build"
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

    val isSending: StateFlow<Boolean> get() = _sending
    private val _sending = MutableStateFlow(false)

    /** 在呈现的 composer 面板（null = 无；单路径关闭）。 */
    private val _composerPanel = MutableStateFlow<ComposerPanelKind?>(null)
    val composerPanel: StateFlow<ComposerPanelKind?> get() = _composerPanel

    /**
     * 权威成功决议后本地遮罩的交互 id 集（独立 StateFlow：桌面投影移除前 UI 据此撤卡
     * 防卡死；投影移除后随之出账——§3.2 状态矩阵「已受理」行的兜底半边）。
     */
    private val _decidedInteractionIds = MutableStateFlow<Set<String>>(emptySet())
    val decidedInteractionIds: StateFlow<Set<String>> get() = _decidedInteractionIds

    /** 只读边界（连接态远端 Store 可能为 true；UI 据此禁用发送/审批并提示）。 */
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

    /** 交互决议失败面（审批/提问/计划卡错误行；§3.2-3.4 状态矩阵「不 dismiss、控件恢复」）。 */
    sealed class InteractionFailure {
        /** ack null：未送达（断线/未连接）→「审批未送达 · 连接恢复后再试」口径。 */
        data object Undelivered : InteractionFailure()

        /** 六态词表内失败：拒因原文（reasonCode + message）。 */
        data class Rejected(val text: String) : InteractionFailure()

        /** status 缺席/词表外：未知态（不撤卡，交投影定案）。 */
        data object UnknownState : InteractionFailure()
    }

    /** composer 面板种类（模型/思考选择器随 M3+ 接线扩枚举；本批仅协作模式面板）。 */
    enum class ComposerPanelKind { Collaboration }

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
        _loadFailure.value = null
        _pendingInteractions.value = emptyList()
        _decidedInteractionIds.value = emptySet()
        collectJob = scope.launch { observe(store) }
    }

    /** 离开会话（路由退栈）：真退订交 Store closeConversation（keep-warm 租约由 store 自管）。 */
    fun detach() {
        collectJob?.cancel()
        collectJob = null
        attachedStore = null
        _composerPanel.value = null
        _slashHint.value = null
    }

    private suspend fun observe(store: ConversationStore) {
        // 初载：订阅/历史拉取的帧序（handler 先注册）由 Store 内部保证；这里只吃投影
        _messages.value = store.messages(conversationId)
        _loadFailure.value = store.conversationLoadFailure(conversationId)
        refreshInteractionProjection(store)
        refreshCollaborationModeProjection(store)
        store.observeConversations().collect { event ->
            val current = _messages.value
            val reduced = reduceMessages(current, event, conversationId)
            if (reduced !== current) _messages.value = reduced
            // 面板/模式投影逐事件覆盖（iOS refreshPendingInteractions /
            // refreshCollaborationModeProjection 同挂点——快照与每条增量都重读）
            refreshInteractionProjection(store)
            refreshCollaborationModeProjection(store)
        }
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
        // 清除时机（§5.1）：发送成功 / 用户手动编辑 draft / 离开会话——不随 3s 消失
        if (text.isNotEmpty() && _sendFailureReason.value != null) {
            _sendFailureReason.value = null
        }
    }

    /**
     * 发送入口（UI 在自身作用域 launch）。返回是否进入送达链（仅用于测试断言；
     * 失败反馈全部落 StateFlow 由 UI 呈现）。
     */
    suspend fun send(): Boolean {
        if (_sending.value) return false
        val text = _draft.value.trim()
        if (text.isEmpty()) return false
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
        return performSend(task)
    }

    /** 普通发送主体：先清稿，失败回填 + 持久错误行（服务层拒因原文透传，禁假成功）。 */
    private suspend fun performSend(text: String): Boolean {
        val store = attachedStore
        _draft.value = ""
        _sending.value = true
        val delivered = try {
            store?.sendWithAttachments(
                text = text,
                attachments = emptyList(),
                requestedDelivery = null,
                modelSelection = null,
                conversationId = conversationId,
            ) ?: false
        } finally {
            _sending.value = false
        }
        if (delivered) {
            _sendFailureReason.value = null
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

    // MARK: 编辑重发（P1-3：CAS+row-target；rewind 语义——确认走 UI 双重确认，本方法只下发）

    /**
     * 编辑用户消息并重发（store.editUserQuery；rowId+entityId 行游标精确寻址）。
     * 返回 null = 权威成功（消息流由桌面 rows 键级重建自动刷新，本地乐观不做）；
     * 游标缺失 = Undelivered（调用方门槛：游标缺失不渲染入口，本实现亦不下发——防御位）。
     */
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
                _decidedInteractionIds.value = _decidedInteractionIds.value + interaction.id
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
                _decidedInteractionIds.value = _decidedInteractionIds.value + interaction.id
                null
            }
            else -> InteractionFailure.UnknownState
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

    private fun showSlashHint(hint: SlashHint) {
        slashHintClearJob?.cancel()
        _slashHint.value = hint
        slashHintClearJob = scope.launch {
            delay(hintClearMs)
            if (!_sending.value) _slashHint.value = null
        }
    }

    private var slashHintClearJob: Job? = null
}

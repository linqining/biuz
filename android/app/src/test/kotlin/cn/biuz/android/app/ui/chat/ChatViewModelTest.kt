package cn.biuz.android.app.ui.chat

// ChatViewModel JVM 单测（testDebugUnitTest 纯逻辑；Mock 仅测试装配——红线见 AGENTS §5-13）。
// 覆盖面（任务点名 ≥3 例：投影/差分/重绑）+ /plan 阻断（v1.33④）+ U-6 草稿回填
// + 撤焦延迟面板 + 协作模式未知态不落盘。虚拟时钟驱动（kotlinx-coroutines-test）。

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.store.AttachmentUploadService
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.ConversationQueueInfo
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.InputPredictionCandidate
import cn.biuz.android.store.InputPredictionResult
import cn.biuz.android.store.ModelPlanGroup
import cn.biuz.android.store.ModelSelectionInfo
import cn.biuz.android.store.NewSessionModelSelection
import cn.biuz.android.store.OutgoingAttachment
import cn.biuz.android.store.RemoteQueueItem
import cn.biuz.android.store.SessionModelSelection
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** 测试专用会话 Store（实现 ConversationStore 协议面；可注入初值/回执/事件）。 */
private class FakeConversationStore(
    var initialMessages: List<ChatMessage> = emptyList(),
    var sendOutcome: Boolean = true,
    var sendRejection: String? = null,
    var collabModeAck: JSONValue? = null,
    var collaborationProjection: String? = null,
    var interactions: List<RemotePendingInteraction> = emptyList(),
    var editAck: JSONValue? = null,
    // P-020 行级反馈（赞/踩）回执
    var feedbackAck: JSONValue? = null,
    // 模型面板面（v1.36/37）
    var modelSelectionInfo: ModelSelectionInfo? = null,
    var modelAck: JSONValue? = null,
    var sessionSelection: SessionModelSelection? = null,
    var thoughtLevelsForModel: List<String> = emptyList(),
    // 队列五件面
    var queue: ConversationQueueInfo? = null,
    var queueAck: JSONValue? = null,
    // 附件事务面（默认 false = 演示态口径：四方法全 failure——上传失败阻断发送路径）
    var attachmentUploadSucceeds: Boolean = false,
) : ConversationStore {

    val events = MutableSharedFlow<ConversationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val sendCalls = CopyOnWriteArrayList<String>()
    val sendAttachmentCalls = CopyOnWriteArrayList<List<OutgoingAttachment>>()
    val collabModeCalls = CopyOnWriteArrayList<String>()
    val editCalls = CopyOnWriteArrayList<Triple<Int, String?, String>>()
    val feedbackCalls = CopyOnWriteArrayList<Triple<Int, String?, Boolean?>>()
    val switchModelCalls = CopyOnWriteArrayList<Triple<String, String, String>>() // provider|model|thought
    val queueCalls = CopyOnWriteArrayList<Pair<String, String>>() // verb|argument
    val attachmentCalls = CopyOnWriteArrayList<String>() // begin|chunk:i|commit|abort

    override suspend fun conversations(): List<cn.biuz.android.protocol.Conversation> = emptyList()

    override suspend fun createConversation(
        title: String,
        directory: String,
        executor: cn.biuz.android.protocol.ExecutorKind,
        modelSelection: NewSessionModelSelection?,
    ): cn.biuz.android.protocol.Conversation =
        cn.biuz.android.protocol.Conversation(
            id = "",
            title = title,
            summary = "",
            directory = directory,
            updatedAt = 0,
        )

    override suspend fun messages(conversationId: String): List<ChatMessage> = initialMessages

    override fun observeConversations(): Flow<ConversationEvent> = events.asSharedFlow()

    override suspend fun sendWithAttachments(
        text: String,
        attachments: List<OutgoingAttachment>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        conversationId: String,
    ): Boolean {
        sendCalls.add(text)
        sendAttachmentCalls.add(attachments)
        return sendOutcome
    }

    // MARK: 附件事务面（默认 failure=演示态；attachmentUploadSucceeds=true 走
    // Staging(0) → nextChunkIndex=index+1 → "ref-<uploadId>" 的最小可续传脚本）

    override suspend fun attachmentBeginV4(
        sessionId: String,
        uploadId: String,
        fileName: String,
        mime: String,
        totalBytes: Int,
        totalChunks: Int,
        checksum: String,
    ): Result<cn.biuz.android.store.AttachmentBeginOutcome> {
        attachmentCalls.add("begin:$uploadId")
        return if (attachmentUploadSucceeds) {
            Result.success(cn.biuz.android.store.AttachmentBeginOutcome.Staging(nextChunkIndex = 0))
        } else {
            Result.failure(cn.biuz.android.store.AttachmentRPCError("演示态无附件上传面"))
        }
    }

    override suspend fun attachmentChunkV4(
        sessionId: String,
        uploadId: String,
        chunkIndex: Int,
        dataBase64: String,
    ): Result<Int> {
        attachmentCalls.add("chunk:$chunkIndex")
        return if (attachmentUploadSucceeds) Result.success(chunkIndex + 1) else failureInt
    }

    override suspend fun attachmentCommitV4(sessionId: String, uploadId: String): Result<String> {
        attachmentCalls.add("commit")
        return if (attachmentUploadSucceeds) Result.success("ref-$uploadId") else failureString
    }

    override suspend fun attachmentAbortV4(sessionId: String, uploadId: String): Result<Unit> {
        attachmentCalls.add("abort")
        return if (attachmentUploadSucceeds) Result.success(Unit) else failureUnit
    }

    private val failureInt: Result<Int> =
        Result.failure(cn.biuz.android.store.AttachmentRPCError("演示态无附件上传面"))
    private val failureString: Result<String> =
        Result.failure(cn.biuz.android.store.AttachmentRPCError("演示态无附件上传面"))
    private val failureUnit: Result<Unit> =
        Result.failure(cn.biuz.android.store.AttachmentRPCError("演示态无附件上传面"))

    override suspend fun sendRejectionText(conversationId: String): String? = sendRejection

    override suspend fun switchCollaborationMode(
        conversationId: String,
        mode: String,
    ): JSONValue? {
        collabModeCalls.add(mode)
        return collabModeAck
    }

    override suspend fun sessionCollaborationMode(conversationId: String): String? =
        collaborationProjection

    override suspend fun editUserQuery(
        conversationId: String,
        rowId: Int,
        entityId: String?,
        newText: String,
    ): JSONValue? {
        editCalls.add(Triple(rowId, entityId, newText))
        return editAck
    }

    override suspend fun setAssistantFeedback(
        conversationId: String,
        rowId: Int,
        entityId: String?,
        value: Boolean?,
    ): JSONValue? {
        feedbackCalls.add(Triple(rowId, entityId, value))
        return feedbackAck
    }

    override suspend fun pendingInteractionList(
        conversationId: String,
    ): List<RemotePendingInteraction> = interactions

    // MARK: 模型面板面

    override suspend fun modelSelectionView(): ModelSelectionInfo? = modelSelectionInfo

    override suspend fun switchModelConfig(
        conversationId: String,
        provider: String,
        model: String,
        thought: String?,
    ): JSONValue? {
        switchModelCalls.add(Triple(provider, model, thought ?: ""))
        return modelAck
    }

    override suspend fun sessionModelSelection(conversationId: String): SessionModelSelection? =
        sessionSelection

    override suspend fun thoughtLevels(model: String): List<String> = thoughtLevelsForModel

    // MARK: 队列五件面

    override suspend fun queueInfo(conversationId: String): ConversationQueueInfo? = queue

    override suspend fun sendQueuedNow(conversationId: String, queueItemId: String): JSONValue? {
        queueCalls.add("sendNow" to queueItemId)
        return queueAck
    }

    override suspend fun editQueueItem(
        conversationId: String,
        queueItemId: String,
        newText: String,
    ): JSONValue? {
        queueCalls.add("edit" to "$queueItemId:$newText")
        return queueAck
    }

    override suspend fun deleteQueueItem(conversationId: String, queueItemId: String): JSONValue? {
        queueCalls.add("delete" to queueItemId)
        return queueAck
    }

    override suspend fun reorderQueueItem(
        conversationId: String,
        queueItemId: String,
        beforeQueueItemId: String?,
    ): JSONValue? {
        queueCalls.add("reorder" to "$queueItemId>$beforeQueueItemId")
        return queueAck
    }

    override suspend fun setAutoDrain(conversationId: String, enabled: Boolean): JSONValue? {
        queueCalls.add("autoDrain" to enabled.toString())
        return queueAck
    }

    // MARK: 3.17.2 输入预测（ghost hint）

    /** 连接态只读边界（VM scheduleInputPrediction 门 = isReadOnly）。 */
    override val isReadOnly: Boolean get() = readOnly

    var readOnly: Boolean = false

    @Volatile
    var predictionResult: InputPredictionResult? = null

    /** 非空 = 预测请求抛错（spec 失败语义注水）。 */
    @Volatile
    var predictionError: Throwable? = null

    /**
     * 在途时延（ms；模拟 CLI 20s 慢回包）。VM 侧 Job 取消时吞掉 CancellationException
     * 模拟「RPC 不可立即取消」的迟到回包——过期丢弃守卫（requestId/草稿快照）由此可测。
     */
    var predictionDelayMs: Long = 0

    val predictionCalls = CopyOnWriteArrayList<Triple<String, String, String>>() // conv|draft|requestId

    override suspend fun inputPrediction(
        conversationId: String,
        draft: String,
        requestId: String,
    ): InputPredictionResult? {
        predictionCalls.add(Triple(conversationId, draft, requestId))
        try {
            if (predictionDelayMs > 0) delay(predictionDelayMs)
        } catch (e: CancellationException) {
            // 模拟桌面同会话替代的迟到回包：取消不中断回执（守卫面由此可测）
        }
        predictionError?.let { throw it }
        return predictionResult
    }
}

private fun userMessage(id: String, text: String): ChatMessage = ChatMessage(
    id = id,
    role = MessageRole.user,
    text = text,
    timestamp = 1_700_000_000_000,
)

/** status=accepted 权威成功回执。 */
private fun acceptedAck(): JSONValue = JSONValue.Obj(mapOf("status" to JSONValue.Str("accepted")))

/** status=rejected 拒收回执（带 reasonCode）。 */
private fun rejectedAck(): JSONValue = JSONValue.Obj(
    mapOf(
        "status" to JSONValue.Str("rejected"),
        "reasonCode" to JSONValue.Str("test.rejected"),
    ),
)

/** 作用域占位（纯函数用例不启动任何协程；Unconfined 即建即弃）。 */
private fun plainVm(conversationId: String = "sess-1"): ChatViewModel =
    ChatViewModel(conversationId = conversationId, scope = CoroutineScope(Dispatchers.Unconfined))

class ChatViewModelTest {

    // MARK: 投影（绑定 :store 会话流 → StateFlow）

    @Test
    fun projectionAttachLoadsMessagesAndEventsApplyToStateFlow() = runTest {
        val store = FakeConversationStore(initialMessages = listOf(userMessage("row-1", "历史行")))
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()
        assertEquals(listOf("row-1"), vm.messages.value.map { it.id })

        // 事件流增量投影：MessageAppended 进入 StateFlow
        store.events.emit(ConversationEvent.MessageAppended("sess-1", userMessage("row-2", "新行")))
        advanceUntilIdle()
        assertEquals(listOf("row-1", "row-2"), vm.messages.value.map { it.id })

        // 他会话事件不应用（conversationId 过滤）
        store.events.emit(
            ConversationEvent.MessageAppended("sess-other", userMessage("row-3", "别的会话")),
        )
        advanceUntilIdle()
        assertEquals(listOf("row-1", "row-2"), vm.messages.value.map { it.id })
    }

    // MARK: 差分（reduceMessages 纯函数）

    @Test
    fun reducerDeduplicatesAppendedIdsAndAppliesReplacements() {
        val vm = plainVm()
        val base = listOf(userMessage("row-1", "A"))
        // 重复 id 幂等替换（乐观回显被服务端行 sourceCommandId 精确消费的双保险）
        val afterEcho = vm.reduceMessages(
            base,
            ConversationEvent.MessageAppended("sess-1", userMessage("row-1", "服务端行")),
            "sess-1",
        )
        assertEquals(1, afterEcho.size)
        assertEquals("服务端行", afterEcho.single().text)
        // 新 id 追加
        val afterAppend = vm.reduceMessages(
            afterEcho,
            ConversationEvent.MessageAppended("sess-1", userMessage("row-2", "B")),
            "sess-1",
        )
        assertEquals(listOf("row-1", "row-2"), afterAppend.map { it.id })
        // MessageUpdated 整行替换
        val afterUpdate = vm.reduceMessages(
            afterAppend,
            ConversationEvent.MessageUpdated("sess-1", userMessage("row-2", "B 更新")),
            "sess-1",
        )
        assertEquals(2, afterUpdate.size)
        assertEquals("B 更新", afterUpdate.last().text)
        // MessagesReplaced 整表替换
        val afterReplaced = vm.reduceMessages(
            afterUpdate,
            ConversationEvent.MessagesReplaced("sess-1", listOf(userMessage("row-9", "快照"))),
            "sess-1",
        )
        assertEquals(listOf("row-9"), afterReplaced.map { it.id })
        // 他会话事件原样返回（同一实例短路，StateFlow 值相等去重不触发重组）
        assertTrue(
            afterReplaced === vm.reduceMessages(
                afterReplaced,
                ConversationEvent.MessageAppended("sess-x", userMessage("row-99", "x")),
                "sess-1",
            ),
        )
    }

    // MARK: 换源重绑（store 按实例重绑）

    @Test
    fun rebindOnNewStoreInstanceReloadsAndDropsOldStream() = runTest {
        val store1 = FakeConversationStore(initialMessages = listOf(userMessage("row-1", "旧源")))
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store1)
        advanceUntilIdle()
        assertEquals(listOf("row-1"), vm.messages.value.map { it.id })

        val store2 = FakeConversationStore(initialMessages = listOf(userMessage("row-2", "新源")))
        vm.attach(store2)
        advanceUntilIdle()
        assertEquals(store2, vm.attachedStore)
        // 投影整体重置后重载新源（防旧 Store 残影混入）
        assertEquals(listOf("row-2"), vm.messages.value.map { it.id })

        // 旧源事件不再生效（收集 Job 已取消，SharedFlow 无订阅即丢）
        store1.events.emit(
            ConversationEvent.MessageAppended("sess-1", userMessage("row-3", "旧源迟到行")),
        )
        advanceUntilIdle()
        assertEquals(listOf("row-2"), vm.messages.value.map { it.id })

        // 新源事件照常生效
        store2.events.emit(
            ConversationEvent.MessageAppended("sess-1", userMessage("row-4", "新源增量")),
        )
        advanceUntilIdle()
        assertEquals(listOf("row-2", "row-4"), vm.messages.value.map { it.id })

        // 同实例幂等：重挂不重置投影
        vm.attach(store2)
        advanceUntilIdle()
        assertEquals(listOf("row-2", "row-4"), vm.messages.value.map { it.id })
    }

    // MARK: /plan 分支切换失败阻断发送（v1.33④）

    @Test
    fun planSlashBlockedWhenModeSwitchFailsAndDraftRestored() = runTest {
        val store = FakeConversationStore(collabModeAck = rejectedAck())
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("/plan 修复登录页崩溃")
        val entered = vm.send()
        // 只跑当前时点任务——advanceUntilIdle 会把虚拟时钟推过 3s 提示窗、误清反馈行
        runCurrent()

        // 阻断：任务一条都不发（fire-and-forget = 「/plan 没触发」报障根因）
        assertTrue(store.collabModeCalls.contains("plan"))
        assertTrue(store.sendCalls.isEmpty())
        // 草稿回填（仅当用户尚未重新输入）
        assertEquals("/plan 修复登录页崩溃", vm.draft.value)
        // 常驻错误行 + 3s 反馈行（枚举由 UI 映射文案）
        assertTrue(vm.collaborationModeFailure.value is ChatViewModel.CollabModeFailure.Rejected)
        assertEquals(ChatViewModel.SlashHint.PlanBlocked, vm.slashHint.value)
        assertTrue(!entered)
    }

    @Test
    fun planSlashSendsTaskAfterAuthoritativeModeSwitch() = runTest {
        val store = FakeConversationStore(collabModeAck = acceptedAck(), sendOutcome = true)
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("/plan 重构存储层")
        val entered = vm.send()
        advanceUntilIdle()

        // 权威成功才落态：chip 切 plan；随后任务正文（不带斜杠）单独下发
        assertEquals("plan", vm.collaborationMode.value)
        assertNull(vm.collaborationModeFailure.value)
        assertEquals(listOf("重构存储层"), store.sendCalls)
        assertTrue(vm.draft.value.isEmpty())
        assertTrue(entered)
    }

    // MARK: 普通发送失败（U-6：草稿回填 + 服务层拒因原文透传）

    @Test
    fun normalSendFailureRestoresDraftAndSurfacesRejection() = runTest {
        val store = FakeConversationStore(
            sendOutcome = false,
            sendRejection = "桌面端拒绝（test.rejected：负载不合规）",
        )
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("帮我跑一下测试")
        val entered = vm.send()
        advanceUntilIdle()

        assertEquals(listOf("帮我跑一下测试"), store.sendCalls)
        assertEquals("帮我跑一下测试", vm.draft.value) // 回填
        assertEquals("桌面端拒绝（test.rejected：负载不合规）", vm.sendFailureReason.value)
        assertTrue(!entered)

        // 用户重新输入即清错误行（清除时机）；成功发送也清
        vm.updateDraft("改一下")
        assertNull(vm.sendFailureReason.value)
        store.sendOutcome = true
        val ok = vm.send()
        advanceUntilIdle()
        assertTrue(ok)
        assertTrue(vm.draft.value.isEmpty())
        assertNull(vm.sendFailureReason.value)
    }

    // MARK: 撤焦先行 + 延迟呈现（openComposerPanel）与关闭单路径

    @Test
    fun panelPresentationClearsFocusFirstThenDelaysThenSinglePathClose() = runTest {
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            panelDelayMs = 500,
        )
        var focusCleared = false
        vm.openComposerPanel(ChatViewModel.ComposerPanelKind.Collaboration) { focusCleared = true }
        // 撤焦同事务先执行；面板在延迟窗内尚未呈现（与键盘收起转场不互撞）
        assertTrue(focusCleared)
        assertNull(vm.composerPanel.value)
        advanceTimeBy(499)
        runCurrent()
        assertNull(vm.composerPanel.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ChatViewModel.ComposerPanelKind.Collaboration, vm.composerPanel.value)
        // 关闭单路径（§5-15）
        vm.closeComposerPanel()
        assertNull(vm.composerPanel.value)
    }

    // MARK: 协作模式未知态不落盘 + 投影定案（v1.33②）

    @Test
    fun collabModeUnknownAckNotPersistedThenProjectionDecides() = runTest {
        val store = FakeConversationStore(collabModeAck = JSONValue.Obj(emptyMap()))
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        val failure = vm.switchCollaborationMode("plan")
        assertTrue(failure is ChatViewModel.CollabModeFailure.UnknownAckState)
        assertEquals("build", vm.collaborationMode.value) // 不落盘
        assertNotNull(vm.collaborationModeFailure.value)

        // 桌面投影（state.config.mode）到达即定案：未知态清账、chip 跟桌面真值
        store.collaborationProjection = "plan"
        store.events.emit(ConversationEvent.PanelStateUpdated("sess-1"))
        advanceUntilIdle()
        assertEquals("plan", vm.collaborationMode.value)
        assertNull(vm.collaborationModeFailure.value)
    }

    // MARK: P-020 行级反馈（赞/踩；rowId+entityId 双游标 + 回执三态）

    @Test
    fun assistantFeedbackRequiresCursorAndMapsAckOutcome() = runTest {
        val store = FakeConversationStore(feedbackAck = acceptedAck())
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        // 游标缺失：不下发
        val noCursor = ChatMessage(
            id = "row-7", role = MessageRole.agent, text = "回答",
            rowKind = "assistantText", timestamp = 0,
        )
        vm.setAssistantFeedback(noCursor, true)
        assertTrue(store.feedbackCalls.isEmpty(), "缺 entityId 不得下发")
        assertNotNull(vm.feedbackFailure.value, "缺游标要如实上屏失败")

        // 前人成功：rowId + entityId + 值逐字
        val msg = ChatMessage(
            id = "row-8", role = MessageRole.agent, text = "回答",
            entityId = "ent-8", rowKind = "assistantText", timestamp = 0,
        )
        vm.setAssistantFeedback(msg, true)
        assertEquals(listOf(Triple(8, "ent-8", true)), store.feedbackCalls.toList())
        assertNull(vm.feedbackFailure.value, "权威成功清错误行")

        // 取消（value=null）同样下发
        vm.setAssistantFeedback(msg, null)
        assertEquals(Triple(8, "ent-8", null), store.feedbackCalls.last())

        // 拒收：原文上屏
        store.feedbackAck = rejectedAck()
        vm.setAssistantFeedback(msg, false)
        assertNotNull(vm.feedbackFailure.value, "拒收必须常驻错误行")
    }

    // MARK: 编辑重发（P1-3：行游标精确寻址 + 回执三态如实映射；预填/下发恒用原文）

    @Test
    fun editResendRequiresCursorAndMapsAckOutcome() = runTest {
        val store = FakeConversationStore(editAck = acceptedAck())
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        // 无游标（entityId 缺失）：不下发（调用方门槛的防御位）
        val noCursor = ChatMessage(
            id = "row-4", role = MessageRole.user, text = "原文",
            timestamp = 0,
        )
        val cursorFailure = vm.editUserQuery(noCursor, "改后文本")
        assertTrue(cursorFailure is ChatViewModel.InteractionFailure.Undelivered)
        assertTrue(store.editCalls.isEmpty())

        // 游标在场：rowId/entityId 精确寻址 + 原文透传；权威成功 → null
        val withCursor = ChatMessage(
            id = "row-3", role = MessageRole.user, text = "原文",
            entityId = "ent-9", rowKind = "userInput", timestamp = 0,
        )
        assertNull(vm.editUserQuery(withCursor, "改后文本"))
        assertEquals(listOf(Triple(3, "ent-9", "改后文本")), store.editCalls.toList())

        // 拒收回执 → Rejected（拒因原文）
        store.editAck = rejectedAck()
        val failure = vm.editUserQuery(withCursor, "再改")
        assertTrue(failure is ChatViewModel.InteractionFailure.Rejected)
        assertEquals(2, store.editCalls.size)
    }

    // MARK: 斜杠意图解析（web sX 解析器同构子集）

    @Test
    fun slashIntentParsingSubset() {
        val vm = plainVm()
        assertEquals(ChatViewModel.SlashIntent.Plan(task = "做 A"), vm.parseSlashIntent("/plan 做 A"))
        assertEquals(ChatViewModel.SlashIntent.Plan(task = ""), vm.parseSlashIntent("/plan"))
        assertNull(vm.parseSlashIntent("普通消息 /plan 不在首位"))
        // /goal 子命令守卫：pause/clear/show/resume 与空目标不拦截（原文直发由桌面解释）
        assertNull(vm.parseSlashIntent("/goal pause"))
        assertNull(vm.parseSlashIntent("/goal"))
        // replace 首词整词匹配："replaces" 不误剥
        assertEquals(
            ChatViewModel.SlashIntent.Goal(objective = "replaces 旧目标"),
            vm.parseSlashIntent("/goal replaces 旧目标"),
        )
        assertEquals(
            ChatViewModel.SlashIntent.Goal(objective = "新目标文本"),
            vm.parseSlashIntent("/goal replace 新目标文本"),
        )
    }

    // MARK: 审批卡三族应答形状（A-3 对象分形回归门——answer 恒对象禁平铺裸串，
    // 2026-10-06 甲-3「审批卡点击确认全部没反应」根因）

    @Test
    fun interactionAnswersAreObjectShapedPerFamily() {
        // 权限族 {optionId}（四族 allowOnce/allowAlways/rejectOnce/rejectAlways 由 UI 拼好传入）
        assertEquals(
            JSONValue.Obj(mapOf("optionId" to JSONValue.Str("allowAlways"))),
            permissionAnswer("allowAlways"),
        )
        // 计划族 {action}（词表 accept|decline——store PLAN_ACTIONS 含 cancel 由「稍后处理」承载）
        assertEquals(
            JSONValue.Obj(mapOf("action" to JSONValue.Str("accept"))),
            planAnswer("accept"),
        )
        // 提问族：自由文本 {freeText} / 服务端选项点选 {optionId}
        assertEquals(
            JSONValue.Obj(mapOf("freeText" to JSONValue.Str("回答文本"))),
            freeTextAnswer("回答文本"),
        )
        assertEquals(permissionAnswer("opt-1"), optionAnswer("opt-1"))
    }

    // MARK: 新建会话提交门（iOS NewConversationSheet :263-282 直译；纯函数）

    @Test
    fun newSessionSubmitGateBlocksOnlyExplicitSelectionThatFailsResolution() {
        val vm = plainVm()
        val info = modelInfo()

        // 复合键解析：个人套餐行 → individual-provider；thought 缺席回填桌面当前档；
        // 载荷 model 恒纯模型名（套餐前缀只用于本地解析）
        val picked = ChatViewModel.NewSessionDraftSelection(
            selectedModelRowKey = "个人套餐|GLM-5.3-Flash",
            selectedModel = "GLM-5.3-Flash",
            selectedThought = null,
        )
        val resolved = ChatViewModel.resolveNewSessionSelection(info, picked)
        assertEquals(
            NewSessionModelSelection(providerId = "individual-provider", modelId = "GLM-5.3-Flash", reasoningLevel = "high"),
            resolved,
        )

        // 同名跨套餐消歧：体验套餐行 → start-provider（复合表权威；裸名表后到覆盖不串套餐）
        assertEquals(
            "start-provider",
            ChatViewModel.resolveNewSessionSelection(
                info,
                picked.copy(selectedModelRowKey = "体验套餐|GLM-5.3-Flash"),
            )?.providerId,
        )

        // getView 失败（info=null）：解析不出 → null（provider 缺失不发空串——strict schema）
        assertNull(ChatViewModel.resolveNewSessionSelection(null, picked))

        // 提交门：明确做过选择（行键/思考档任一在场）但解析不出 = 拦截弹「用默认模型继续」
        assertTrue(
            ChatViewModel.submitGateBlocked(rowKey = "个人套餐|GLM-5.3-Flash", thought = null, resolved = null),
        )
        assertTrue(ChatViewModel.submitGateBlocked(rowKey = null, thought = "high", resolved = null))
        // 纯未选择 = 直通桌面默认，不拦；解析成功 = 不拦
        assertFalse(ChatViewModel.submitGateBlocked(rowKey = null, thought = null, resolved = null))
        assertFalse(
            ChatViewModel.submitGateBlocked(rowKey = "个人套餐|GLM-5.3-Flash", thought = null, resolved = resolved),
        )
    }

    // MARK: 模型/思考切换（乐观落 chip + 3s 护栏 + ack 三态常驻错误行；v1.36/37）

    @Test
    fun modelSwitchMapsAckThreeStatesWithOptimisticGuardWindow() = runTest {
        var clockMs = 1_000_000L
        val store = FakeConversationStore(
            // getView 失败（modelSelectionInfo=null）场景：会话级 provider 兜底是唯一权威源
            // （v1.36①：state.modelSelection.providerId 灌入后同模型/思考档切换免 getView）
            sessionSelection = SessionModelSelection(
                model = "GLM-5.3-Flash",
                thought = "high",
                provider = "individual-provider",
            ),
            modelAck = rejectedAck(),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            nowMs = { clockMs },
        )
        vm.attach(store)
        advanceUntilIdle()
        // 初载 overlay：getView 失败种「非 nil 但空」实例 + 会话级三元组灌入
        assertEquals("GLM-5.3-Flash", vm.modelSelection.value?.activeModel)
        assertEquals("individual-provider", vm.modelSelection.value?.activeProviderId)

        // ① 拒收回执：chip 不动 + 常驻失败行（拒因原文，不 3s 闪过）
        val rejected = vm.switchModel("GLM-5.3-Flash", thoughtLevel = "max")
        assertTrue(rejected is ChatViewModel.ModelSwitchFailure.Rejected)
        val rejectedFailure = rejected as ChatViewModel.ModelSwitchFailure.Rejected
        assertNotNull(rejectedFailure.text)
        assertEquals("high", vm.modelSelection.value?.activeThoughtLevel)
        assertNotNull(vm.modelSwitchFailure.value)

        // ② 权威成功（复合行键→provider 解析）：乐观落 chip，不等桌面投影回流
        store.modelAck = acceptedAck()
        assertNull(vm.switchModel("个人套餐|GLM-5.3-Flash", thoughtLevel = "max"))
        assertNull(vm.modelSwitchFailure.value)
        assertEquals("max", vm.modelSelection.value?.activeThoughtLevel)
        assertEquals(
            Triple("individual-provider", "GLM-5.3-Flash", "max"),
            store.switchModelCalls.last(),
        )

        // ③ 护栏窗（3s）内旧投影不回闪（忙 turn 增量可滞后分钟级——真机 14:08 实证）
        store.sessionSelection = SessionModelSelection(
            model = "GLM-5.3-Flash",
            thought = "low",
            provider = "individual-provider",
        )
        clockMs += 1_000
        store.events.emit(ConversationEvent.PanelStateUpdated("sess-1"))
        advanceUntilIdle()
        assertEquals("max", vm.modelSelection.value?.activeThoughtLevel)

        // ④ 窗口过后桌面权威源接管
        clockMs += 5_000
        store.events.emit(ConversationEvent.PanelStateUpdated("sess-1"))
        advanceUntilIdle()
        assertEquals("low", vm.modelSelection.value?.activeThoughtLevel)
        assertNull(vm.modelSwitchFailure.value)
    }

    // MARK: 队列五件（ack 三态映射 + 权威成功刷新投影；上移 = reorder 到前一项之前）

    @Test
    fun queueActionsMapAckStatesAndRefreshProjection() = runTest {
        val store = FakeConversationStore(
            queue = ConversationQueueInfo(
                items = listOf(RemoteQueueItem("q-1", "第一条"), RemoteQueueItem("q-2", "第二条")),
                autoDrain = true,
            ),
            queueAck = acceptedAck(),
        )
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()
        assertEquals(listOf("q-1", "q-2"), vm.queueInfo.value?.items?.map { it.id })

        // 上移 = 与前一位置交换；首行无上移语义不下发
        assertNull(vm.moveQueueItemUp("q-2"))
        assertTrue(store.queueCalls.contains("reorder" to "q-2>q-1"))
        val callsBefore = store.queueCalls.size
        assertNull(vm.moveQueueItemUp("q-1"))
        assertEquals(callsBefore, store.queueCalls.size)

        // 拒绝：常驻失败行（拒因原文），队列投影不动
        store.queueAck = rejectedAck()
        val rejected = vm.deleteQueueItem("q-1")
        assertTrue(rejected is ChatViewModel.QueueActionFailure.Rejected)
        assertEquals(listOf("q-1", "q-2"), vm.queueInfo.value?.items?.map { it.id })

        // 权威成功：投影刷新（store 侧队列已同步移除）
        store.queueAck = acceptedAck()
        store.queue = ConversationQueueInfo(
            items = listOf(RemoteQueueItem("q-2", "第二条")),
            autoDrain = true,
        )
        assertNull(vm.deleteQueueItem("q-1"))
        assertNull(vm.queueActionFailure.value)
        assertEquals(listOf("q-2"), vm.queueInfo.value?.items?.map { it.id })

        // 未送达（ack null）：Undelivered 如实上屏，禁假成功
        store.queueAck = null
        assertTrue(vm.setAutoDrain(false) is ChatViewModel.QueueActionFailure.Undelivered)
    }

    // MARK: 失败反馈常驻（「失败 3s 气泡一律改常驻」；清除 = 重新输入/发送成功/离开）

    @Test
    fun planBlockedFailureHintPersistsBeyondAutoClearWindow() = runTest {
        val store = FakeConversationStore(collabModeAck = rejectedAck())
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("/plan 修复登录页崩溃")
        vm.send()
        runCurrent()
        assertEquals(ChatViewModel.SlashHint.PlanBlocked, vm.slashHint.value)

        // 推过 3s 自动清除窗：失败反馈不消失（原 3s 气泡 = 用户根本看不到的假反馈）
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(ChatViewModel.SlashHint.PlanBlocked, vm.slashHint.value)

        // 用户重新输入 = 重试意图：常驻失败行出账（草稿回填仍在）
        vm.updateDraft("换一种说法")
        assertNull(vm.slashHint.value)
    }

    // MARK: 待发附件面（P1-1 发送联动：补传门 / peek 不移除成功才清 / 超限提示镜像）

    @Test
    fun failedUploadBlocksSendAndSurfacesFailureText() = runTest {
        // fake 默认 attachmentUploadSucceeds=false（演示态口径：begin failure）
        val store = FakeConversationStore()
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        assertTrue(vm.enqueueAttachment(name = "clip.mp4", mediaType = "video/mp4", data = ByteArray(1024)))
        advanceUntilIdle()

        // 上传失败：blocksSend 发送闸落下 + 失败原文行可读（errorText 直出，禁笼统文案）
        assertTrue(vm.uploadsBlocksSend())
        assertEquals("clip.mp4", vm.uploadsSnapshot().single().name)
        val failure = vm.uploadFailureText()
        assertTrue(failure != null && failure.isNotEmpty())

        // 发送门兜底：blocksSend 在场时 send 不下发（直发文本会丢附件）
        vm.updateDraft("带附件的消息")
        val delivered = vm.send()
        advanceUntilIdle()
        assertFalse(delivered)
        assertTrue(store.sendCalls.isEmpty(), "上传未就绪时禁止裸发文本（丢附件）")
    }

    @Test
    fun committedAttachmentFlowsIntoSendAndClearsOnlyOnSuccess() = runTest {
        val store = FakeConversationStore(attachmentUploadSucceeds = true)
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        assertTrue(vm.enqueueAttachment(name = "shot.png", mediaType = "image/png", data = ByteArray(512)))
        advanceUntilIdle()
        // 事务收口：begin→chunk→commit 全走，committed 态 + 发送闸抬起
        assertTrue(store.attachmentCalls.any { it.startsWith("begin:") })
        assertTrue(store.attachmentCalls.contains("commit"))
        assertFalse(vm.uploadsBlocksSend())

        // 发送成功 → committed 引用随 sendText 下发（B-1 元素面）→ 待发条清空（成功才清）
        vm.updateDraft("带图消息")
        assertTrue(vm.send())
        advanceUntilIdle()
        val sent = store.sendAttachmentCalls.single()
        assertEquals(1, sent.size)
        assertEquals("shot.png", sent.single().fileName)
        assertEquals("image/png", sent.single().mime)
        assertTrue(sent.single().ref.startsWith("ref-upload-"))
        assertTrue(vm.uploadsSnapshot().isEmpty(), "发送成功后 committed 附件出账（成功才清语义）")

        // 发送失败 → 附件保留待发条（committed 引用可复用，重试不重传——§11.1#6）
        val store2 = FakeConversationStore(attachmentUploadSucceeds = true, sendOutcome = false)
        val vm2 = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm2.attach(store2)
        advanceUntilIdle()
        vm2.enqueueAttachment(name = "keep.mov", mediaType = "video/quicktime", data = ByteArray(512))
        advanceUntilIdle()
        assertFalse(vm2.send())
        advanceUntilIdle()
        assertEquals(1, vm2.uploadsSnapshot().size)
        assertEquals("keep.mov", vm2.uploadsSnapshot().single().name)
    }

    @Test
    fun oversizeEnqueueIsRefusedWithMirroredHint() {
        val vm = plainVm()
        vm.attach(FakeConversationStore())
        // 20MB 上限（AttachmentUploadService.MAX_BYTES）+1 拒收：add false + hint 镜像可读
        val oversize = ByteArray(AttachmentUploadService.MAX_BYTES + 1)
        assertFalse(vm.enqueueAttachment(name = "big.mp4", mediaType = "video/mp4", data = oversize))
        assertTrue(vm.uploadsSnapshot().isEmpty())
        assertTrue(vm.uploadHint.value?.contains("20MB") == true)
    }

    // MARK: 3.17.2 输入预测 ghost hint（spec 手机端建议②③⑤；iOS :1349-1390 直译）

    private fun predictionStore(): FakeConversationStore =
        FakeConversationStore().apply {
            readOnly = true // VM 门 = isReadOnly（连接态只读边界）
            predictionResult = InputPredictionResult(
                decision = "show",
                candidates = listOf(InputPredictionCandidate(text = " 再跑一遍测试", confidence = 0.9)),
                requestId = "r-1",
                revision = 0,
            )
        }

    @Test
    fun inputHintArrivesAfterDebounceAndAcceptAppendsUntrimmed() = runTest {
        val store = predictionStore()
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("帮我")
        vm.scheduleInputPrediction()
        // 防抖窗内无请求、无 hint（每次变化先清旧 hint）
        assertNull(vm.inputHint.value)
        assertTrue(store.predictionCalls.isEmpty())

        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, store.predictionCalls.size)
        assertEquals("sess-1", store.predictionCalls[0].first)
        assertEquals("帮我", store.predictionCalls[0].second)
        // attach 重绑已自增一次序号（切会话作废在途语义），首个请求 = r-2
        assertEquals("r-2", store.predictionCalls[0].third)
        assertEquals(" 再跑一遍测试", vm.inputHint.value)

        // 点按采纳：draft + text 不 trim（英文续写可空格起头）+ hint 出账
        vm.acceptInputHint()
        assertEquals("帮我 再跑一遍测试", vm.draft.value)
        assertNull(vm.inputHint.value)
    }

    @Test
    fun predictionRequestsRegardlessOfReadOnlyFlag_storeCapabilityDecides() = runTest {
        // 2026-11-23：UI 不再用 isReadOnly 门禁（对齐 iOS canSend / web composer）；
        // 预测是否可用由 store 能力决定（无能力 store 返回 null → 无 hint）
        val store = predictionStore().apply { readOnly = false }
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()
        vm.updateDraft("帮我")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        advanceUntilIdle()
        assertEquals(1, store.predictionCalls.size, "只读标志不再阻断预测请求（能力由 store 决定）")
        assertEquals(" 再跑一遍测试", vm.inputHint.value)

        // store 返 null（无候选/无能力）→ 无 hint
        val noCapability = predictionStore().apply {
            readOnly = false
            predictionResult = null
        }
        val vm2 = ChatViewModel(conversationId = "sess-2", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm2.attach(noCapability)
        advanceUntilIdle()
        vm2.updateDraft("帮我")
        vm2.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        advanceUntilIdle()
        assertTrue(noCapability.predictionCalls.isNotEmpty(), "请求照发，能力门在 store 侧")
        assertNull(vm2.inputHint.value)
    }

    @Test
    fun staleReplyDroppedAfterNewerRequest() = runTest {
        val store = predictionStore().apply { predictionDelayMs = 5_000 } // call1 慢回包
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("aaa")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        runCurrent() // call1 在途
        assertEquals(1, store.predictionCalls.size)

        // 新请求（同会话替代语义）：call2 快回包换新结果；call1 迟到回包 = requestId 非最新 → 丢弃
        store.predictionResult = InputPredictionResult(
            decision = "show",
            candidates = listOf(InputPredictionCandidate(text = " fresh", confidence = 0.9)),
            requestId = "r-2",
            revision = 0,
        )
        vm.updateDraft("aaa bbb")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        advanceUntilIdle() // call1 迟到回包落放
        assertEquals(2, store.predictionCalls.size)
        assertEquals(" fresh", vm.inputHint.value, "迟到回包按非最新 requestId 丢弃（spec 手机端建议③）")
    }

    @Test
    fun draftMismatchDropsHint() = runTest {
        val store = predictionStore().apply { predictionDelayMs = 5_000 }
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()

        vm.updateDraft("aaa")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        runCurrent() // 在途
        // 用户继续输入（草稿快照失配）→ 迟到回包不得上屏
        vm.updateDraft("aaa2")
        advanceUntilIdle()
        assertNull(vm.inputHint.value, "草稿已变 = 回包过期（spec 手机端建议③）")
    }

    @Test
    fun nonShowDecisionNeverDisplaysHint() = runTest {
        val store = predictionStore().apply {
            predictionResult = InputPredictionResult(
                decision = "suppress_below_threshold",
                candidates = listOf(InputPredictionCandidate(text = " 不展示", confidence = 0.5)),
                requestId = "r-1",
                revision = 0,
            )
        }
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()
        vm.updateDraft("帮我")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        advanceUntilIdle()
        assertEquals(1, store.predictionCalls.size)
        assertNull(vm.inputHint.value, "仅 decision==show 展示首候选（spec 手机端建议⑤）")
    }

    @Test
    fun sendAndRebindInvalidateHint() = runTest {
        val store = predictionStore()
        val vm = ChatViewModel(conversationId = "sess-1", scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.attach(store)
        advanceUntilIdle()
        vm.updateDraft("待发送")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        runCurrent()
        assertEquals(" 再跑一遍测试", vm.inputHint.value)

        // 发送入口作废在途 + 清 hint（iOS send :1273 同口径）
        vm.send()
        advanceUntilIdle()
        assertNull(vm.inputHint.value)

        // 换源重绑（attach 新实例）同样出账
        vm.updateDraft("重绑前草稿")
        vm.scheduleInputPrediction()
        advanceTimeBy(ChatViewModel.INPUT_PREDICTION_DEBOUNCE_MS)
        runCurrent()
        assertEquals(" 再跑一遍测试", vm.inputHint.value)
        vm.attach(FakeConversationStore().apply { readOnly = true })
        advanceUntilIdle()
        assertNull(vm.inputHint.value, "切会话/换源：作废在途预测并清 hint")
    }
}

/** getView 投影夹具：同名模型「GLM-5.3-Flash」跨两套餐（复合表消歧）+ activeProviderId 绑定个人组。 */
private fun modelInfo(
    activeModel: String? = "GLM-5.3-Flash",
    activeThought: String? = "high",
    activeProvider: String? = "individual-provider",
): ModelSelectionInfo = ModelSelectionInfo(
    models = listOf("GLM-5.3-Flash", "GLM-5"),
    activeModel = activeModel,
    thoughtLevels = listOf("off", "low", "high", "max"),
    activeThoughtLevel = activeThought,
    planGroups = listOf(
        ModelPlanGroup(plan = "个人套餐", models = listOf("GLM-5.3-Flash", "GLM-5")),
        ModelPlanGroup(plan = "体验套餐", models = listOf("GLM-5.3-Flash")),
    ),
    modelProviders = mapOf(
        "GLM-5.3-Flash" to "start-provider", // 裸名表后到覆盖先到（跨套餐歧义遗留）
        "GLM-5" to "individual-provider",
    ),
    modelProvidersByPlan = mapOf(
        "个人套餐|GLM-5.3-Flash" to "individual-provider",
        "个人套餐|GLM-5" to "individual-provider",
        "体验套餐|GLM-5.3-Flash" to "start-provider",
    ),
    activeProviderId = activeProvider,
)

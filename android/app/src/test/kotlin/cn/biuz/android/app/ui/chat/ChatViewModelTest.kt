package cn.biuz.android.app.ui.chat

// ChatViewModel JVM 单测（testDebugUnitTest 纯逻辑；Mock 仅测试装配——红线 §0-4）。
// 覆盖面（任务点名 ≥3 例：投影/差分/重绑）+ /plan 阻断（v1.33④）+ U-6 草稿回填
// + 撤焦延迟面板 + 协作模式未知态不落盘。虚拟时钟驱动（kotlinx-coroutines-test）。

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.RemotePendingInteraction
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.NewSessionModelSelection
import cn.biuz.android.store.OutgoingAttachment
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
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
) : ConversationStore {

    val events = MutableSharedFlow<ConversationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val sendCalls = CopyOnWriteArrayList<String>()
    val collabModeCalls = CopyOnWriteArrayList<String>()

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
        return sendOutcome
    }

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

    override suspend fun pendingInteractionList(
        conversationId: String,
    ): List<RemotePendingInteraction> = interactions
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

        // 用户重新输入即清错误行（§5.1 清除时机）；成功发送也清
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
}

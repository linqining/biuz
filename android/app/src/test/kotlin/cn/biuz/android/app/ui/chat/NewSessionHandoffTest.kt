package cn.biuz.android.app.ui.chat

// 新建会话 → 会话页交接（iOS NewConversationHandoffBox 同构）。
//
// 缺陷背景（2026-11-24 任务 4 验收受阻）：新建 sheet 留空描述只选模型时，
// createSession 的 firstInput 为空 → modelSelection 无通道 → **选择被整段丢弃**，
// 会话回落桌面默认模型（实测选 ccgithub/deepseek-v4.1-flash → 会话显示 GLM-5.4）。
// 修法 = draft 创建 + 交接箱暂存，随首条 sendText.modelSelection 下发。

import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.NewSessionModelSelection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** 只覆盖发送面 + 交接消费的极简 store。 */
private class HandoffFakeStore(
    var sendResult: Boolean = true,
) : cn.biuz.android.store.ConversationStore {
    val sendSelections = ArrayList<NewSessionModelSelection?>()
    var sendCount = 0

    override suspend fun conversations(): List<Conversation> = emptyList()
    override suspend fun createConversation(
        title: String,
        directory: String,
        executor: ExecutorKind,
        modelSelection: NewSessionModelSelection?,
    ): Conversation = Conversation(id = "", title = title, summary = "", directory = directory, updatedAt = 0)

    override fun observeConversations(): Flow<ConversationEvent> = MutableSharedFlow()
    override suspend fun messages(conversationId: String): List<cn.biuz.android.protocol.ChatMessage> = emptyList()
    override suspend fun cachedConversation(conversationId: String): Conversation? = null

    override suspend fun send(text: String, conversationId: String): Boolean = sendResult

    override suspend fun sendWithAttachments(
        text: String,
        attachments: List<cn.biuz.android.store.OutgoingAttachment>,
        requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        conversationId: String,
    ): Boolean {
        sendCount += 1
        sendSelections.add(modelSelection)
        return sendResult
    }

    override suspend fun sendRejectionText(conversationId: String): String? = null
}

class NewSessionHandoffTest {

    private val selection = NewSessionModelSelection(
        providerId = "ccgithub",
        modelId = "deepseek/deepseek-v4.1-flash",
        reasoningLevel = "max",
    )

    @AfterTest
    fun tearDown() {
        NewSessionHandoffBox.clear()
    }

    @Test
    fun handoffSelectionRidesFirstSendTextThenClears() = runTest {
        NewSessionHandoffBox.deposit(
            NewSessionHandoff(draftText = "", modelSelection = selection),
            forSessionId = "sess-new",
        )
        val store = HandoffFakeStore(sendResult = true)
        val vm = ChatViewModel(
            conversationId = "sess-new",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        assertEquals(selection, vm.pendingNewSessionSelection, "attach 时从交接箱取出")
        assertNull(NewSessionHandoffBox.take("sess-new"), "take 即清（一次性）")

        vm.updateDraft("hello")
        vm.send()
        advanceUntilIdle()

        assertEquals(
            listOf<NewSessionModelSelection?>(selection),
            store.sendSelections,
            "首条 sendText 必须携交接的 modelSelection（原缺陷：恒传 null → 选择丢失）",
        )
        assertNull(vm.pendingNewSessionSelection, "发送成功即消耗")

        // 第二条消息不再重复携带（一次性语义）
        vm.updateDraft("again")
        vm.send()
        advanceUntilIdle()
        assertEquals(
            listOf<NewSessionModelSelection?>(selection, null),
            store.sendSelections,
            "第二条起不带选择（已在首条下发）",
        )
    }

    @Test
    fun handoffSelectionKeptWhenSendFails() = runTest {
        NewSessionHandoffBox.deposit(
            NewSessionHandoff(draftText = "", modelSelection = selection),
            forSessionId = "sess-fail",
        )
        val store = HandoffFakeStore(sendResult = false)
        val vm = ChatViewModel(
            conversationId = "sess-fail",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        vm.updateDraft("hello")
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf<NewSessionModelSelection?>(selection), store.sendSelections)
        assertEquals(
            selection,
            vm.pendingNewSessionSelection,
            "发送失败保留选择供重试（iOS 同语义）",
        )
    }

    @Test
    fun draftTextFromHandoffPrefillsComposer() = runTest {
        NewSessionHandoffBox.deposit(
            NewSessionHandoff(draftText = "预填的任务描述", modelSelection = null),
            forSessionId = "sess-draft",
        )
        val vm = ChatViewModel(
            conversationId = "sess-draft",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(HandoffFakeStore())
        advanceUntilIdle()
        assertEquals("预填的任务描述", vm.draft.value)
    }
}

package cn.biuz.android.app.ui.chat

// 会话面板 VM 面单测（面板投影/控制命令 ack 三态/迷你轨道会话行投影；iOS ChatViewModel
// 面板面直译轮的 JVM 直测。虚拟时钟驱动；Mock 仅测试装配——红线见 AGENTS §5-13）。

import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.RemoteGoalSummary
import cn.biuz.android.protocol.SubagentSessionSummary
import cn.biuz.android.protocol.WorkflowActorSummary
import cn.biuz.android.protocol.WorkflowNodeSummary
import cn.biuz.android.protocol.WorkflowRunSummary
import cn.biuz.android.protocol.WorkflowStepStatus
import cn.biuz.android.store.ConversationEvent
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.NewSessionModelSelection
import cn.biuz.android.store.OptionalArg
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 面板面专用 Store（实现 ConversationStore 协议面；注入面板投影初值 + 命令回执）。 */
private class PanelsFakeStore(
    var runs: List<WorkflowRunSummary> = emptyList(),
    var goal: RemoteGoalSummary? = null,
    var works: List<BackgroundWorkSummary> = emptyList(),
    var subagents: List<SubagentSessionSummary> = emptyList(),
    var planPanel: cn.biuz.android.protocol.PlanPanelSummary? = null,
    var subagentProjection: cn.biuz.android.protocol.SubagentProjectionState? = null,
    var activityDismissed: cn.biuz.android.protocol.ActivitySummaryDismissed? = null,
    var completedActivityTotal: Int? = null,
    var goalAck: JSONValue? = null,
    var controlAck: JSONValue? = null,
    var cachedRow: Conversation? = null,
    var transcriptResult: List<ChatMessage> = emptyList(),
) : ConversationStore {

    val events = MutableSharedFlow<ConversationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val goalPauseCalls = CopyOnWriteArrayList<Boolean>()
    val cancelCalls = CopyOnWriteArrayList<String>()
    val resumeCalls = CopyOnWriteArrayList<Pair<String, String?>>()
    val amendCalls = CopyOnWriteArrayList<Triple<String, OptionalArg<String>?, OptionalArg<Int>?>>()
    val resyncCalls = CopyOnWriteArrayList<Int>()
    val transcriptCalls = CopyOnWriteArrayList<String>()

    override suspend fun conversations(): List<Conversation> = emptyList()

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
        updatedAt = 0,
    )

    override fun observeConversations(): Flow<ConversationEvent> = events.asSharedFlow()

    override suspend fun messages(conversationId: String): List<ChatMessage> = emptyList()

    override suspend fun cachedConversation(conversationId: String): Conversation? = cachedRow

    // 面板投影读面（面板轮覆盖面）
    override suspend fun workflowRuns(conversationId: String): List<WorkflowRunSummary> = runs

    override suspend fun goalSummary(conversationId: String): RemoteGoalSummary? = goal

    override suspend fun planPanel(conversationId: String): cn.biuz.android.protocol.PlanPanelSummary? = planPanel

    override suspend fun backgroundWorks(conversationId: String): List<BackgroundWorkSummary> = works

    override suspend fun subagentSessions(conversationId: String): List<SubagentSessionSummary> = subagents

    override suspend fun subagentProjection(
        conversationId: String,
    ): cn.biuz.android.protocol.SubagentProjectionState? = subagentProjection

    override suspend fun activitySummaryDismissed(
        conversationId: String,
    ): cn.biuz.android.protocol.ActivitySummaryDismissed? = activityDismissed

    override suspend fun completedActivityCount(conversationId: String): Int? = completedActivityTotal

    // 控制命令面（回执注入 + 调用记账）
    override suspend fun setGoalPaused(paused: Boolean, conversationId: String): JSONValue? {
        goalPauseCalls.add(paused)
        return goalAck
    }

    override suspend fun cancelWork(conversationId: String, workId: String): JSONValue? {
        cancelCalls.add(workId)
        return controlAck
    }

    override suspend fun resumeWorkflowRun(
        conversationId: String,
        workId: String,
        name: String?,
    ): JSONValue? {
        resumeCalls.add(workId to name)
        return controlAck
    }

    override suspend fun amendWorkflowRunSettings(
        conversationId: String,
        workId: String,
        subagentModel: OptionalArg<String>?,
        maxConcurrency: OptionalArg<Int>?,
    ): JSONValue? {
        amendCalls.add(Triple(workId, subagentModel, maxConcurrency))
        return controlAck
    }

    override suspend fun triggerResync(conversationId: String) {
        resyncCalls.add(resyncCalls.size + 1)
    }

    override suspend fun actorTranscript(sessionId: String, limit: Int): List<ChatMessage> {
        transcriptCalls.add(sessionId)
        return transcriptResult
    }
}

class ChatViewModelPanelsTest {

    private fun ack(status: String, message: String? = null, reasonCode: String? = null): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["commandId"] = JSONValue.Str("cmd-1")
        fields["status"] = JSONValue.Str(status)
        if (message != null) fields["message"] = JSONValue.Str(message)
        if (reasonCode != null) fields["reasonCode"] = JSONValue.Str(reasonCode)
        return JSONValue.Obj(fields)
    }

    private fun run(
        id: String,
        rawStatus: String = "running",
        workId: String? = null,
        cancellable: Boolean = true,
        resumable: Boolean = false,
    ) = WorkflowRunSummary(
        id = id,
        name = "移植工作流",
        rawStatus = rawStatus,
        nodes = listOf(WorkflowNodeSummary(id = "n1", label = "规划", status = WorkflowStepStatus.done)),
        actors = listOf(
            WorkflowActorSummary(
                id = "site-1#0",
                name = "子代理A",
                rawStatus = "running",
                phaseName = "规划",
                sessionId = "child-1",
            ),
        ),
        cancellable = cancellable,
        resumable = resumable,
        workId = workId,
    )

    // MARK: 面板投影（attach 初载 + 事件轻量刷新）

    @Test
    fun attachPopulatesPanelProjections() = runTest {
        val store = PanelsFakeStore(
            runs = listOf(run("run-1")),
            goal = RemoteGoalSummary(text = "完成移植", isPaused = true),
            works = listOf(BackgroundWorkSummary(workId = "w-1", rawStatus = "running")),
            subagents = listOf(SubagentSessionSummary(childSessionId = "child-1", name = "子代理A")),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        assertEquals(1, vm.workflowRuns.value.size)
        assertEquals("完成移植", vm.goalSummary.value?.text)
        assertEquals(1, vm.backgroundWorks.value.size)
        assertEquals("child-1", vm.subagentSessions.value.first().childSessionId)
    }

    @Test
    fun attachPopulatesV4ActivitySummaryReadSurfaces() = runTest {
        // v4.0.0 三读面（subagentProjection / activitySummaryDismissed /
        // completedActivityCount）此前只有协议默认空实现——attach 后必须从 store 取到
        val store = PanelsFakeStore(
            subagentProjection = cn.biuz.android.protocol.SubagentProjectionState(
                revision = 3,
                running = listOf(
                    cn.biuz.android.protocol.SubagentRuntimeSummary(
                        childSessionId = "child-run",
                        subagentType = "explore",
                        title = "搜索",
                        status = "running",
                    ),
                ),
                endedTotal = 7,
            ),
            activityDismissed = cn.biuz.android.protocol.ActivitySummaryDismissed(
                at = 1_700_000_000_000L,
                workIds = listOf("w-1"),
            ),
            completedActivityTotal = 5,
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        assertEquals(7, vm.subagentProjection.value?.endedTotal)
        assertEquals("child-run", vm.subagentProjection.value?.running?.first()?.childSessionId)
        assertEquals(listOf("w-1"), vm.activitySummaryDismissed.value?.workIds)
        assertEquals(5, vm.completedActivityCount.value)
    }

    @Test
    fun panelStateUpdatedRefreshesProjections() = runTest {
        val store = PanelsFakeStore(runs = emptyList())
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        assertTrue(vm.workflowRuns.value.isEmpty())
        // 桌面 state.workflowRuns 快照/增量到达 → store 投影更新 → PanelStateUpdated 事件
        store.runs = listOf(run("run-2"))
        store.events.tryEmit(ConversationEvent.PanelStateUpdated("sess-1"))
        advanceUntilIdle()
        assertEquals("run-2", vm.workflowRuns.value.first().id)
    }

    // MARK: 取消按钮取活 run（store 仲裁列表 → 每卡自带 workId 直传 cancelWork）

    @Test
    fun cancelWorkAcceptedTargetsLiveWorkIdAndShowsSuccessHint() = runTest {
        val store = PanelsFakeStore(
            runs = listOf(run("run-live", rawStatus = "running", workId = "work-live")),
            controlAck = ack("accepted"),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            hintClearMs = 1_000,
        )
        vm.attach(store)
        runCurrent()
        val failure = vm.cancelWork("work-live")
        runCurrent()
        assertNull(failure)
        assertEquals(listOf("work-live"), store.cancelCalls) // 打到活 run 的 workId
        assertTrue(vm.controlSuccess.value) // 成功提示（UI 3s 自清；窗内仍在场）
        assertNull(vm.controlFailure.value)
        // 提示窗过后自清（advanceTimeBy 只推进到目标时刻，hint 任务先于 run 二次回读）
        advanceTimeBy(2_000)
        assertTrue(!vm.controlSuccess.value)
    }

    @Test
    fun cancelWorkNullAckReportsUndelivered() = runTest {
        val store = PanelsFakeStore(controlAck = null)
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        val failure = vm.cancelWork("work-x")
        advanceUntilIdle()
        assertNotNull(failure)
        assertTrue(failure is ChatViewModel.ControlFailure.Undelivered)
        assertTrue(vm.controlFailure.value is ChatViewModel.ControlFailure.Undelivered)
    }

    @Test
    fun cancelWorkRejectedSurfacesReasonText() = runTest {
        val store = PanelsFakeStore(
            controlAck = ack(
                "rejected",
                message = "no cancellable background task",
                reasonCode = "background_task_not_found",
            ),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        val failure = vm.cancelWork("work-stale")
        advanceUntilIdle()
        assertTrue(failure is ChatViewModel.ControlFailure.Rejected)
        val text = (failure as ChatViewModel.ControlFailure.Rejected).text
        assertTrue(text.contains("background_task_not_found"))
        assertTrue(text.contains("no cancellable background task"))
    }

    @Test
    fun cancelWorkUnknownAckStateDoesNotFakeSuccess() = runTest {
        val store = PanelsFakeStore(controlAck = ack("maybe")) // 词表外
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        val failure = vm.cancelWork("work-x")
        advanceUntilIdle()
        assertTrue(failure is ChatViewModel.ControlFailure.UnknownState)
    }

    // MARK: 目标暂停/继续（state.goal 回显取反；无 goal 本地拒绝不下发）

    @Test
    fun toggleGoalPauseSendsInvertedPause() = runTest {
        val store = PanelsFakeStore(
            goal = RemoteGoalSummary(text = "目标", isPaused = true),
            goalAck = ack("accepted"),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        val failure = vm.toggleGoalPause()
        advanceUntilIdle()
        assertNull(failure)
        // paused=true → 下发 resume（false）
        assertEquals(listOf(false), store.goalPauseCalls)
    }

    @Test
    fun toggleGoalPauseWithoutGoalLocalRejects() = runTest {
        val store = PanelsFakeStore(goal = null)
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        val failure = vm.toggleGoalPause()
        advanceUntilIdle()
        assertTrue(failure is ChatViewModel.ControlFailure.Rejected)
        assertTrue(store.goalPauseCalls.isEmpty()) // 不下发
    }

    // MARK: amendWorkflowRunSettings（子代理模型/并发数；成功后 resync 换新 run）

    @Test
    fun amendModelResyncsAfterControlWindow() = runTest {
        val store = PanelsFakeStore(
            runs = listOf(run("run-1")),
            controlAck = ack("accepted"),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        runCurrent()
        val failure = vm.setSubagentModel("GLM-5.3-Flash", workId = "work-1")
        runCurrent()
        assertNull(failure)
        assertEquals(1, store.amendCalls.size)
        assertEquals("work-1", store.amendCalls.first().first)
        assertEquals(OptionalArg("GLM-5.3-Flash"), store.amendCalls.first().second)
        assertEquals(0, store.resyncCalls.size) // resync 在 1.2s 窗后
        advanceTimeBy(ChatViewModel.RUN_REFRESH_FIRST_MS + 100)
        assertEquals(1, store.resyncCalls.size) // amend 类动作追加全量 resync
    }

    // MARK: 子代理转录下钻（打开 loading → 拉取回填；关闭竞态丢弃）

    @Test
    fun actorTranscriptSheetLoadsAndFills() = runTest {
        val store = PanelsFakeStore(
            transcriptResult = listOf(
                ChatMessage(
                    id = "t-1",
                    role = MessageRole.agent,
                    text = "转录内容",
                    timestamp = 0,
                ),
            ),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        vm.openActorTranscript(title = "子代理A", sessionId = "child-1")
        advanceUntilIdle()
        val sheet = vm.actorTranscriptSheet.value
        assertNotNull(sheet)
        assertEquals("子代理A", sheet.title)
        assertTrue(!sheet.loading)
        assertEquals(1, sheet.messages.size)
        assertEquals(listOf("child-1"), store.transcriptCalls)
        vm.closeActorTranscript()
        assertNull(vm.actorTranscriptSheet.value)
    }

    @Test
    fun openActorTranscriptWithoutSessionIdIsIgnored() = runTest {
        val store = PanelsFakeStore()
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        vm.openActorTranscript(title = null, sessionId = null)
        advanceUntilIdle()
        assertNull(vm.actorTranscriptSheet.value)
    }

    // MARK: 会话行投影（迷你轨道数据源 = 行自带 workflowActivity 随列表事件到达）

    @Test
    fun conversationRowProjectionCarriesWorkflowActivity() = runTest {
        val store = PanelsFakeStore()
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        assertNull(vm.currentConversation.value)
        val row = Conversation(
            id = "sess-1",
            title = "T",
            summary = "",
            directory = "/ws",
            updatedAt = 0,
            workflowActivity = cn.biuz.android.protocol.WorkflowActivitySummary(
                runs = listOf(
                    cn.biuz.android.protocol.SessionWorkflowRunSummary(
                        id = "run-a",
                        name = null,
                        rawStatus = "running",
                        phases = listOf(
                            cn.biuz.android.protocol.SessionWorkflowPhase(
                                name = "实现",
                                status = WorkflowStepStatus.running,
                            ),
                        ),
                        currentPhase = "实现",
                        agentsWorking = 2,
                    ),
                ),
            ),
        )
        store.events.tryEmit(ConversationEvent.ConversationUpdated(row))
        advanceUntilIdle()
        val projected = vm.currentConversation.value
        assertNotNull(projected)
        assertEquals(1, projected.workflowActivity?.liveRuns?.size)
        assertEquals("实现", projected.workflowActivity?.liveRuns?.first()?.currentPhase)
    }

    // MARK: detach 清面板投影与反馈面

    @Test
    fun detachClearsPanelProjectionsAndFeedback() = runTest {
        val store = PanelsFakeStore(
            runs = listOf(run("run-1")),
            controlAck = ack("rejected", message = "x"),
        )
        val vm = ChatViewModel(
            conversationId = "sess-1",
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.attach(store)
        advanceUntilIdle()
        vm.cancelWork("w")
        advanceUntilIdle()
        assertTrue(vm.controlFailure.value != null)
        vm.detach()
        assertTrue(vm.workflowRuns.value.isEmpty())
        assertNull(vm.controlFailure.value)
        assertNull(vm.actorTranscriptSheet.value)
    }
}

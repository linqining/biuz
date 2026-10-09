package cn.biuz.android.store

// 工作流面板数据面单测（M7；对应 iOS parseWorkflowRun/workflowRuns(in:) 仲裁面 + G-007 迷你轨道）：
// - parseWorkflowRun：阶段三表取一（phases → phaseNames → nodes 去重 phaseName）+ 每站状态
//   节点证据推导（failed/cancelled → failed；七相位进行族 → running；settled ok → done）；
// - 无站/无戳兜底（上游 instance-phases.ts phasesOf 同构）：隐式「工作流」单站 + 「未分组」承接站；
// - workflowRuns() 多 run 仲裁：stale 活 run 降级（取消不打死 workId）、同名 supersede 取最新活、
//   活优先排序、表外 activity run 摘要投影（A' 通路）；
// - workflowRun() 通路 C：RunEvents 分页重建（G-019 历史 run）；
// - G-007 迷你轨道：workflowActivity 解析入 Conversation 行 + live-only 过滤。
// Mock 仅测试装配（红线 §0-4）。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class WorkflowRunsArbitrationTest {

    // MARK: parseWorkflowRun（阶段行展开）

    private fun runJson(vararg pairs: Pair<String, JSONValue?>): JSONValue = testObj(*pairs)

    @Test
    fun parseWorkflowRun_phaseTableDerivesStationStatuses() {
        val run = RemoteConversationStore.parseWorkflowRun(
            runJson(
                "runId" to JSONValue.Str("run-1"),
                "name" to JSONValue.Str("移植工作流"),
                "status" to JSONValue.Str("running"),
                "currentPhase" to JSONValue.Str("实现"),
                "phases" to JSONValue.Arr(
                    listOf(
                        testObj("name" to JSONValue.Str("规划")),
                        testObj("name" to JSONValue.Str("实现")),
                        testObj("name" to JSONValue.Str("验收")),
                    ),
                ),
                "nodes" to JSONValue.Arr(
                    listOf(
                        // 规划站：全部 settled ok → done
                        testObj(
                            "phaseName" to JSONValue.Str("规划"),
                            "phase" to JSONValue.Str("settled"),
                            "outcome" to JSONValue.Str("ok"),
                        ),
                        // 实现站：有 executing → running
                        testObj(
                            "phaseName" to JSONValue.Str("实现"),
                            "phase" to JSONValue.Str("executing"),
                            "outcome" to JSONValue.Str(""),
                        ),
                        // 验收站：未来站（无节点证据、非 currentPhase）→ pending
                    ),
                ),
                "actors" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "siteId" to JSONValue.Str("site-1"),
                            "ordinal" to JSONValue.Int(0),
                            "status" to JSONValue.Str("running"),
                            "phaseName" to JSONValue.Str("实现"),
                            "sessionId" to JSONValue.Str("child-1"),
                            "tasksTotal" to JSONValue.Int(3),
                            "tasksSettled" to JSONValue.Int(1),
                        ),
                    ),
                ),
            ),
        )
        assertNotNull(run)
        assertEquals("run-1", run.id)
        assertEquals("移植工作流", run.name)
        assertEquals(3, run.nodes.size)
        assertEquals("station-0-规划", run.nodes[0].id)
        assertEquals(cn.biuz.android.protocol.WorkflowStepStatus.done, run.nodes[0].status)
        assertEquals(cn.biuz.android.protocol.WorkflowStepStatus.running, run.nodes[1].status)
        assertEquals(cn.biuz.android.protocol.WorkflowStepStatus.pending, run.nodes[2].status)
        // 子代理实例投影
        assertEquals(1, run.actors.size)
        assertEquals("site-1#0", run.actors[0].id)
        assertEquals("child-1", run.actors[0].sessionId)
        assertEquals(3, run.actors[0].tasksTotal)
        assertEquals(1, run.actors[0].tasksSettled)
        // 阶段数（phases 表首源）
        assertEquals(3, run.phaseCount)
        assertTrue(run.hasPhaseVocabulary)
        // workId 兜底 = runId；活态
        assertEquals("run-1", run.workId)
        assertTrue(run.isLive)
    }

    @Test
    fun parseWorkflowRun_failedOutcomeStationMarksFailed() {
        val run = RemoteConversationStore.parseWorkflowRun(
            runJson(
                "runId" to JSONValue.Str("run-2"),
                "status" to JSONValue.Str("errored"),
                "phaseNames" to JSONValue.Arr(listOf(JSONValue.Str("构建"))),
                "nodes" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "phaseName" to JSONValue.Str("构建"),
                            "phase" to JSONValue.Str("settled"),
                            "outcome" to JSONValue.Str("failed"),
                        ),
                    ),
                ),
            ),
        )
        assertNotNull(run)
        assertEquals(cn.biuz.android.protocol.WorkflowStepStatus.failed, run.nodes.single().status)
        assertEquals("构建", run.nodes.single().label)
        assertFalse(run.isLive)
    }

    @Test
    fun parseWorkflowRun_unphasedActors_synthesizeUnphasedStation() {
        // ② 有站且有词汇、存在无戳实例 → 表头合成「未分组」站承接
        val run = RemoteConversationStore.parseWorkflowRun(
            runJson(
                "runId" to JSONValue.Str("run-3"),
                "status" to JSONValue.Str("running"),
                "phases" to JSONValue.Arr(listOf(testObj("name" to JSONValue.Str("唯一站")))),
                "nodes" to JSONValue.Arr(
                    listOf(testObj("phaseName" to JSONValue.Str("唯一站"), "phase" to JSONValue.Str("executing"))),
                ),
                "actors" to JSONValue.Arr(
                    listOf(
                        // 无 phaseName 出生戳（首个标记前出生/旧 CLI）
                        testObj("siteId" to JSONValue.Str("s"), "ordinal" to JSONValue.Int(1), "status" to JSONValue.Str("running")),
                    ),
                ),
            ),
        )
        assertNotNull(run)
        assertEquals(cn.biuz.android.protocol.WorkflowRunSummary.unphasedStationID, run.nodes.first().id)
        assertEquals("未分组", run.nodes.first().label)
        assertEquals(cn.biuz.android.protocol.WorkflowStepStatus.running, run.nodes.first().status)
    }

    @Test
    fun parseWorkflowRun_noStationsButActors_implicitWorkflowStation() {
        // ① 全程无站（旧 CLI 无 phase() 标记）但有实例 → 合成隐式「工作流」单站
        val run = RemoteConversationStore.parseWorkflowRun(
            runJson(
                "runId" to JSONValue.Str("run-4"),
                "status" to JSONValue.Str("completed"),
                "actors" to JSONValue.Arr(
                    listOf(testObj("siteId" to JSONValue.Str("s"), "ordinal" to JSONValue.Int(2), "status" to JSONValue.Str("completed"))),
                ),
            ),
        )
        assertNotNull(run)
        assertEquals("station-implicit", run.nodes.single().id)
        assertEquals("工作流", run.nodes.single().label)
        assertEquals(cn.biuz.android.protocol.WorkflowStepStatus.done, run.nodes.single().status)
    }

    @Test
    fun parseWorkflowRun_joinFace_workIdAndBriefsAndTimestamps() {
        val run = RemoteConversationStore.parseWorkflowRun(
            runJson(
                "runId" to JSONValue.Str("run-5"),
                "workId" to JSONValue.Str("work-9"),
                "status" to JSONValue.Str("completed"),
                "usage" to testObj("spentTokens" to JSONValue.Int(1234)),
                "phaseNames" to JSONValue.Arr(
                    listOf(JSONValue.Str("甲"), JSONValue.Str("乙"), JSONValue.Str("甲")),
                ),
                "artifacts" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "id" to JSONValue.Str("art-1"),
                            "bytes" to JSONValue.Int(11 * 1024),
                            "itemCount" to JSONValue.Int(6),
                        ),
                    ),
                ),
                "pendingQuestions" to JSONValue.Arr(emptyList<JSONValue>()),
                "concurrency" to testObj("active" to JSONValue.Int(2), "ceiling" to JSONValue.Int(5)),
                "cancellable" to JSONValue.Bool(true),
                "startedAt" to testNum(1_767_000_000_000L),
                "updatedAt" to testNum(1_767_000_600_000L),
            ),
        )
        assertNotNull(run)
        assertEquals("work-9", run.workId)
        assertEquals(1234, run.spentTokens)
        // phaseNames 首现序去重：甲/乙 → 2
        assertEquals(2, run.phaseCount)
        assertEquals(1, run.artifactBriefs.size)
        assertEquals("art-1", run.artifactBriefs[0].id)
        assertEquals(11 * 1024, run.artifactBriefs[0].bytes)
        assertEquals(6, run.artifactBriefs[0].itemCount)
        assertEquals(2, run.concurrency)
        assertEquals(5, run.concurrencyCeiling)
        assertEquals(1_767_000_000_000L, run.startedAt)
        assertEquals(1_767_000_600_000L, run.updatedAt)
        // 无 nodes/actors 也不为 null（status-only 历史行解析后交 rebuild 精化——
        // iOS 同口径：本行无站无实例时 parse 返回 nil，此行带 status-only 亦无 nodes → nil）
    }

    @Test
    fun parseWorkflowRun_statusOnlyRowWithoutNodesOrActors_returnsNull() {
        assertNull(
            RemoteConversationStore.parseWorkflowRun(runJson("runId" to JSONValue.Str("run-6"), "status" to JSONValue.Str("completed"))),
            "无阶段且无节点返回 nil（iOS :3557 guard 同口径）",
        )
    }

    @Test
    fun workflowRunTimestamp_milliSecondAndIsoForms() {
        val dict = testObj(
            "a" to testNum(1_791_040_344_222L),
            "b" to JSONValue.Str("2026-10-10T00:00:00.000Z"),
            "c" to JSONValue.Int(5), // <1e8 脏值不认
        ).objectValue!!
        assertEquals(1_791_040_344_222L, RemoteConversationStore.workflowRunTimestamp(dict, "a"))
        // 2026-10-10T00:00:00Z = epoch 毫秒 1_791_590_400_000
        assertEquals(1_791_590_400_000L, RemoteConversationStore.workflowRunTimestamp(dict, "b"))
        assertNull(RemoteConversationStore.workflowRunTimestamp(dict, "c"))
    }

    // MARK: workflowRuns() 多 run 仲裁（stale 降级 / 同名 supersede / 活优先）

    /** 注水 workflowRuns 表 + sessions-index activity，跑仲裁面（TestScope 扩展——advanceUntilIdle 接收者）。 */
    private suspend fun kotlinx.coroutines.test.TestScope.arbiterFixture(
        tableRuns: List<JSONValue>,
        activity: JSONValue?,
    ): cn.biuz.android.store.RemoteConversationStore {
        val f = Fixture(this, withIndex = true)
        if (activity != null) {
            // sessions-index 订阅 + 快照帧注水（活动摘要挂会话行）
            f.index?.ensureSubscribed()
            advanceUntilIdle()
            val snapshotFrame = testObj(
                "wireVersion" to JSONValue.Int(3),
                "kind" to JSONValue.Str("complete"),
                "deliveryKind" to JSONValue.Str("initial"),
                "logicalFrameId" to JSONValue.Str("lf-wf-1"),
                "logicalFrameOrdinal" to JSONValue.Int(0),
                "topic" to JSONValue.Str("sessions-index//conn/ws"),
                "subscriptionId" to JSONValue.Str("sub-sess-test"),
                "frame" to testObj(
                    "topic" to JSONValue.Str("sessions-index//conn/ws"),
                    "subscriptionId" to JSONValue.Str("sub-sess-test"),
                    "fromSeq" to JSONValue.Int(0),
                    "toSeq" to JSONValue.Int(1),
                    "payload" to testObj(
                        "kind" to JSONValue.Str("snapshot"),
                        "snapshot" to testObj(
                            "logEpoch" to JSONValue.Str("epoch-wf-1"),
                            "sessions" to JSONValue.Arr(
                                listOf(
                                    testObj(
                                        "sessionId" to JSONValue.Str("sess-wf"),
                                        "title" to JSONValue.Str("工作流会话"),
                                        "phase" to JSONValue.Str("running"),
                                        "lastActivityAt" to testNum(1_767_000_000_000L),
                                        "workspacePath" to JSONValue.Str("/conn/ws"),
                                        "workflowActivity" to activity,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            f.transport.sessionsIndexFrameHandler!!.invoke(RPCValue.json(snapshotFrame))
            awaitUntilSuspend("activity 行落表") {
                f.index?.sessionSummaries()?.containsKey("sess-wf") == true
            }
        }
        if (tableRuns.isNotEmpty()) {
            // 订阅 + 快照帧注水 state.workflowRuns（Fixture/RemoteConversationStoreTest 同法）
            f.store.messages("sess-wf")
            advanceUntilIdle()
            val frameSnapshot = testObj(
                "logEpoch" to JSONValue.Str("epoch-1"),
                "revision" to testNum(7),
                "rows" to JSONValue.Arr(emptyList<JSONValue>()),
                "state" to testObj(
                    "workflowRuns" to testObj(
                        "revision" to testNum(1),
                        "runs" to JSONValue.Arr(tableRuns),
                    ),
                    "pendingInteractions" to JSONValue.Arr(emptyList<JSONValue>()),
                ),
            )
            f.frameSurface.emit(
                "conversation/sess-wf",
                frame(
                    topic = "conversation/sess-wf", subscriptionId = "sub-c-1",
                    fromSeq = 1, toSeq = 1,
                    snapshot = frameSnapshot,
                ),
            )
            advanceUntilIdle()
        }
        return f.store
    }

    private fun tableRun(
        runId: String,
        status: String,
        name: String = "流水线",
        startedAt: Long? = null,
    ): JSONValue = runJson(
        "runId" to JSONValue.Str(runId),
        "name" to JSONValue.Str(name),
        "status" to JSONValue.Str(status),
        "startedAt" to (startedAt?.let { testNum(it) }),
        // 最小活体形态：单站 phases + 一实例——无站无实例的 run 解析为 nil，不进仲裁面
        "phases" to JSONValue.Arr(listOf(testObj("name" to JSONValue.Str("唯一站")))),
        "actors" to JSONValue.Arr(
            listOf(
                testObj(
                    "siteId" to JSONValue.Str("s-$runId"),
                    "ordinal" to JSONValue.Int(0),
                    "status" to JSONValue.Str("running"),
                ),
            ),
        ),
    )

    @Test
    fun workflowRuns_staleLiveRun_demotedWhenActivityForgotsIt() = runTest {
        val store = arbiterFixture(
            tableRuns = listOf(tableRun("run-old", "running"), tableRun("run-new", "running")),
            // activity 只记得 run-new：run-old 滞留活态 → 降级 stopped（取消不打死 workId）
            activity = testObj(
                "runs" to JSONValue.Arr(
                    listOf(
                        testObj("runId" to JSONValue.Str("run-new"), "status" to JSONValue.Str("running")),
                    ),
                ),
            ),
        )
        val runs = store.workflowRuns("sess-wf")
        val byId = runs.associateBy { it.id }
        assertEquals("stopped", byId.getValue("run-old").rawStatus, "activity 遗忘的滞留活 run 降级 stopped")
        assertEquals("running", byId.getValue("run-new").rawStatus)
    }

    @Test
    fun workflowRuns_sameNameSupersede_onlyNewestStaysLive() = runTest {
        val store = arbiterFixture(
            // 表内两个同名活 run：桌面 supersede 换 run 后旧 run 滞留 running
            tableRuns = listOf(
                tableRun("run-old", "running", startedAt = 1_767_000_000_000L),
                tableRun("run-new", "running", startedAt = 1_767_000_600_000L),
            ),
            // activity 为空（两 run 都在表内，无外部证据）→ 同名仲裁兜底
            activity = null,
        )
        val runs = store.workflowRuns("sess-wf")
        val byId = runs.associateBy { it.id }
        assertEquals("running", byId.getValue("run-new").rawStatus, "最新活 run 保持活态")
        assertEquals("stopped", byId.getValue("run-old").rawStatus, "同名滞留旧 run 降级 stopped，不再主导显示")
        // 活优先排序：活 run 在前
        assertEquals("run-new", runs.first().id)
    }

    @Test
    fun workflowRuns_activityOnlyRun_projectedAsSummary() = runTest {
        val store = arbiterFixture(
            tableRuns = emptyList(),
            activity = testObj(
                "runs" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "runId" to JSONValue.Str("run-act"),
                            "name" to JSONValue.Str("活动摘要"),
                            "status" to JSONValue.Str("running"),
                            "phases" to JSONValue.Arr(
                                listOf(
                                    testObj(
                                        "name" to JSONValue.Str("阶段A"),
                                        "status" to JSONValue.Str("running"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val runs = store.workflowRuns("sess-wf")
        assertEquals(1, runs.size)
        assertEquals("run-act", runs[0].id)
        assertEquals("活动摘要", runs[0].name)
        // 摘要投影：阶段链 only（A' 通路）
        assertEquals(listOf("阶段A"), runs[0].nodes.map { it.label })
    }

    // MARK: workflowRun() 通路 C（RunEvents 分页重建）

    /** 独立替身：conversationWorkflowRunsV4 回裸 run、events 回三事件（phase/actor/settled）。 */
    private class RebuildTransport : RPCChannelTransport {
        val calls = ArrayList<String>()

        override suspend fun call(
            channel: String,
            command: String,
            arg: RPCValue,
            timeout: kotlin.time.Duration,
        ): RPCValue {
            calls.add(command)
            return when (command) {
                "conversationWorkflowRunsV4" -> RPCValue.json(
                    // 历史 run：status-only（无实时 nodes/actors）→ parse 返回 null → 通路 C
                    testObj("runs" to JSONValue.Arr(listOf(testObj("runId" to JSONValue.Str("run-hist"), "status" to JSONValue.Str("completed"))))),
                )
                "conversationWorkflowRunEventsV4" -> RPCValue.json(
                    testObj(
                        "events" to JSONValue.Arr(
                            listOf(
                                testObj("type" to JSONValue.Str("workflow.phase.entered"), "payload" to testObj("phase" to JSONValue.Str("调研"))),
                                testObj("type" to JSONValue.Str("workflow.phase.entered"), "payload" to testObj("phase" to JSONValue.Str("收尾"))),
                                testObj(
                                    "type" to JSONValue.Str("workflow.actor.spawned"),
                                    "payload" to testObj(
                                        "siteId" to JSONValue.Str("s9"),
                                        "ordinal" to JSONValue.Int(3),
                                        "status" to JSONValue.Str("completed"),
                                        "phaseName" to JSONValue.Str("调研"),
                                    ),
                                ),
                                testObj(
                                    "type" to JSONValue.Str("run.settled"),
                                    "payload" to testObj(
                                        "status" to JSONValue.Str("completed"),
                                        "stopReason" to JSONValue.Str("done"),
                                    ),
                                ),
                            ),
                        ),
                        "hasMore" to JSONValue.Bool(false),
                    ),
                )
                else -> throw RPCError(message = "fake: unhandled $command", name = "ErrorObj")
            }
        }

        override suspend fun listen(
            channel: String,
            event: String,
            arg: RPCValue,
            handler: (RPCValue) -> Unit,
        ): cn.biuz.android.protocol.EventSubscription = cn.biuz.android.protocol.EventSubscription { }

        override suspend fun disconnect() {}
    }

    private class NoopFrameSurface : cn.biuz.android.store.ConversationFrameSurface {
        override suspend fun ensureConversationFrameStream(workspacePath: String, workspaceIdentity: String?) {}
        override fun setFrameHandler(topic: String, handler: (cn.biuz.android.protocol.V4TopicFrame) -> Unit) {}
        override fun removeFrameHandler(topic: String) {}
        override fun setFrameDropHandler(key: String, handler: () -> Unit) {}
    }

    @Test
    fun workflowRun_historyRun_rebuiltFromRunEvents() = runTest {
        val transport = RebuildTransport()
        val store = RemoteConversationStore(
            transport = transport,
            frameSurface = NoopFrameSurface(),
            workspace = StoreWorkspaceInfo("/conn/ws"),
            clientId = "test-client-1",
            backgroundDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        val run = store.workflowRun("sess-hist")
        assertNotNull(run, "历史 run 走通路 C 重建")
        assertEquals("run-hist", run.id)
        // 事件流重建阶段链（done 节点）+ 子代理实例 + 终态
        assertEquals(listOf("调研", "收尾"), run.nodes.map { it.label })
        assertTrue(run.nodes.all { it.status == cn.biuz.android.protocol.WorkflowStepStatus.done })
        assertEquals(1, run.actors.size)
        assertEquals("s9#3", run.actors[0].id)
        assertEquals("completed", run.rawStatus)
        assertEquals("done", run.stopReason)
        assertTrue(run.truncated, "重建投影非全量（iOS :4856 同口径）")
        assertFalse(run.cancellable, "死 run 不再可取消")
        assertEquals(listOf("conversationWorkflowRunsV4", "conversationWorkflowRunEventsV4"), transport.calls)
        // 二次调用：fetched 去重（一次，失败不重复）
        assertNull(store.workflowRun("sess-hist"))
        assertEquals(2, transport.calls.size)
    }

    // MARK: 镜像水合（wf.runs.mirror 钩子；快照间歇缺席的首屏兜底）

    @Test
    fun workflowRuns_emptyTable_hydratesFromMirrorHook() = runTest {
        val transport = RebuildTransport()
        val store = RemoteConversationStore(
            transport = transport,
            frameSurface = NoopFrameSurface(),
            workspace = StoreWorkspaceInfo("/conn/ws"),
            clientId = "test-client-1",
            backgroundDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        val backing = HashMap<String, List<JSONValue>>()
        store.workflowMirrorStore = object : cn.biuz.android.store.WorkflowMirrorStore {
            override fun save(conversationId: String, runs: List<JSONValue>) {
                backing[conversationId] = runs
            }

            override fun load(conversationId: String): List<JSONValue>? = backing[conversationId]
        }
        // 预置镜像（装配层持久化在测试里以内存 Map 承接）
        backing["sess-mirror"] = listOf(tableRun("run-mirror", "running"))

        val runs = store.workflowRuns("sess-mirror")
        assertEquals(listOf("run-mirror"), runs.map { it.id }, "表空时镜像水合先行（iOS :3110 ⓪ 同口径）")
        assertTrue(transport.calls.isEmpty(), "workflowRuns 无 RPC 面：镜像命中不触网")
    }

    // MARK: G-007 迷你轨道（workflowActivity → Conversation 行 + live-only）

    @Test
    fun workflowActivity_parseAndLiveOnlyFilter() {
        val activity = RemoteConversationStore.parseWorkflowActivity(
            testObj(
                "runs" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "runId" to JSONValue.Str("r-live"),
                            "name" to JSONValue.Str("在跑"),
                            "status" to JSONValue.Str("running"),
                            "currentPhase" to JSONValue.Str("实现"),
                            "agentsWorking" to JSONValue.Int(2),
                            "phases" to JSONValue.Arr(
                                listOf(testObj("name" to JSONValue.Str("实现"), "status" to JSONValue.Str("running"))),
                            ),
                        ),
                        testObj("runId" to JSONValue.Str("r-done"), "status" to JSONValue.Str("completed")),
                        testObj("status" to JSONValue.Str("running")), // 无 runId → 丢弃
                    ),
                ),
            ),
        )
        assertNotNull(activity)
        assertEquals(2, activity.runs.size)
        // live-only（上游 workflowRunLine.ts:128 移动端口径：无 ack UI → 只画活 run）
        assertEquals(listOf("r-live"), activity.liveRuns.map { it.id })
        val live = activity.liveRuns.single()
        assertEquals("实现", live.currentPhase)
        assertEquals(2, live.agentsWorking)
    }

    @Test
    fun conversationRow_carriesWorkflowActivity_miniTrackWired() {
        val table = SessionMergeTable()
        kotlinx.coroutines.runBlocking {
            table.applySessionsSnapshot(
                testObj(
                    "sessions" to JSONValue.Arr(
                        listOf(
                            testObj(
                                "sessionId" to JSONValue.Str("sess-track"),
                                "title" to JSONValue.Str("迷你轨道会话"),
                                "phase" to JSONValue.Str("running"),
                                "lastActivityAt" to testNum(1_767_000_000_000L),
                                "workspacePath" to JSONValue.Str("/conn/ws"),
                                "workflowActivity" to testObj(
                                    "runs" to JSONValue.Arr(
                                        listOf(testObj("runId" to JSONValue.Str("r-1"), "status" to JSONValue.Str("running"))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val row = table.toConversations().single()
            assertNotNull(row.workflowActivity, "G-007 通路 A：行迷你轨道数据随投影携带")
            assertEquals("r-1", row.workflowActivity!!.liveRuns.single().id)
        }
    }
}

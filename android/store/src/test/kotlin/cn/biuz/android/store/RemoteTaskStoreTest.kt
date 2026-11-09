package cn.biuz.android.store

import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RemotePendingInteraction

import cn.biuz.android.protocol.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * RemoteTaskStore 基面（直译自 RemoteTaskStore.swift：读面/动作面/分组解析）。
 * 命令面（resolvePermission/stop）经 RemoteConversationBridge 假实现注水验证三态
 * （Mock 仅测试装配——红线 §0-4）；分组解析为纯函数对拍（B-2 members 顶层归组形状）。
 */
class RemoteTaskStoreTest {

    private val ws = "/Users/mac/zcode_mobile"

    private fun taskRow(taskId: String, status: String, updatedAt: Long = 1_767_000_000_000L): JSONValue =
        testObj(
            "taskId" to JSONValue.Str(taskId),
            "title" to JSONValue.Str("任务 $taskId"),
            "workspacePath" to JSONValue.Str(ws),
            "status" to JSONValue.Str(status),
            "updatedAt" to testNum(updatedAt),
        )

    /** 假会话桥：挂起交互/回执可注水（记录面供断言）。 */
    private class FakeBridge(
        var interaction: RemotePendingInteraction? = RemotePendingInteraction(id = "ia-1", kind = "permission"),
        var ackQueue: ArrayDeque<JSONValue> = ArrayDeque(),
        var stopAck: JSONValue? = null,
    ) : RemoteConversationBridge {
        var lastAnswer: JSONValue? = null
        var lastInteractionId: String? = null
        var stopCalls = 0

        override suspend fun currentPendingInteraction(conversationId: String, kinds: List<String>): RemotePendingInteraction? {
            assertEquals(listOf("permission"), kinds, "权限应答只解析 permission 类挂起交互")
            return interaction
        }

        override suspend fun resolveInteractionRaw(conversationId: String, interactionId: String, answer: JSONValue): JSONValue? {
            lastInteractionId = interactionId
            lastAnswer = answer
            return ackQueue.removeFirstOrNull()
        }

        override suspend fun stopTurn(sessionId: String): JSONValue? {
            stopCalls += 1
            return stopAck
        }

        override suspend fun messages(conversationId: String): List<ChatMessage> = emptyList()
    }

    @Test
    fun refresh_mapsListTaskListRows_withLongTimestampAndErrorSummary() = runBlocking {
        val fake = FakeRemoteTransport()
        fake.listTaskListItems = listOf(
            testObj(
                "taskId" to JSONValue.Str("sess-1"),
                "title" to JSONValue.Str("正常行"),
                "workspacePath" to JSONValue.Str(ws),
                "status" to JSONValue.Str("running"),
                // 毫秒时间戳越 Int32：JSONValue.Int 装不下，必须走 double/Long 路径
                "updatedAt" to testNum(1_791_040_344_222L),
            ),
            testObj(
                "taskId" to JSONValue.Str("sess-2"),
                "title" to JSONValue.Str("失败行"),
                "workspacePath" to JSONValue.Str(ws),
                "status" to JSONValue.Str("error"),
                "updatedAt" to testNum(1_767_000_000_000L),
                "lastError" to testObj(
                    "message" to JSONValue.Str("模型侧 WorkflowError"),
                    "detail" to JSONValue.Str("stack line"),
                ),
                "changeSummary" to testObj(
                    "fileCount" to JSONValue.Int(3),
                    "added" to JSONValue.Int(10),
                    "removed" to JSONValue.Int(2),
                ),
            ),
            // 行缺 workspacePath → 整行弃（mapTask 守卫，iOS :186-188 同口径）
            testObj(
                "taskId" to JSONValue.Str("sess-3"),
                "title" to JSONValue.Str("缺归属行"),
                "status" to JSONValue.Str("running"),
            ),
        )
        val store = RemoteTaskStore(fake, StoreWorkspaceInfo(path = ws), conversationStore = null)
        val tasks = store.tasks()

        assertEquals(2, tasks.size, "缺 workspacePath 的行不入表")
        val running = tasks.single { it.id == "sess-1" }
        assertEquals(TaskStatus.running, running.status)
        assertEquals(1_791_040_344_222L, running.updatedAt, "毫秒时间戳 Long 路径（32 位 Int 值域装不下）")
        assertEquals(ws, running.directory)
        val failed = tasks.single { it.id == "sess-2" }
        assertEquals(TaskStatus.failed, failed.status)
        assertEquals("模型侧 WorkflowError", failed.summary, "lastError.message 优先")
        assertEquals("stack line", failed.lastLog)
        assertEquals("文件 3 · +10/-2", failed.pendingImpact)

        // listTaskList 走 zcode-task 频道（读面唯一出口经 transport，ReadOnlyGate 在 :connection 出口）
        val listCall = fake.callLog.single { it.second == "listTaskList" }
        assertEquals("zcode-task", listCall.first)
    }

    @Test
    fun searchTasks_emptyQueryRefreshes_nonEmptyUsesSearchKey() = runBlocking {
        val fake = FakeRemoteTransport()
        fake.listTaskListItems = listOf(taskRow("sess-9", "running"))
        val store = RemoteTaskStore(fake, StoreWorkspaceInfo(path = ws), conversationStore = null)

        val refreshed = store.searchTasks("   ")
        assertEquals(1, refreshed.size, "空 query 退化为普通刷新")
        assertEquals(1, fake.callLog.count { it.second == "listTaskList" })

        fake.listTaskListItems = listOf(taskRow("sess-hit", "completed"))
        val hits = store.searchTasks("指纹")
        assertEquals("sess-hit", hits.single().id)
        // search 键名（上游 zcodeTaskListTypes.ts:13）——此前发 searchQuery 被服务端忽略（假结果）
        assertEquals(2, fake.callLog.count { it.second == "listTaskList" })
    }

    @Test
    fun stopAndApprove_withoutBridge_reportUndeliveredFaithfully() = runBlocking {
        val store = RemoteTaskStore(FakeRemoteTransport(), StoreWorkspaceInfo(path = ws), conversationStore = null)
        assertEquals("未连接桌面端，停止指令未送达", store.stop("sess-1"), "写面禁静默：无会话 Store 时如实失败")
        assertEquals(TaskDecisionOutcome.Undelivered, store.approve("sess-1", "allowOnce"))
        assertEquals(TaskDecisionOutcome.Undelivered, store.reject("sess-1", "rejectOnce"))
    }

    @Test
    fun approve_viaBridge_threeStateAckMapping() = runBlocking {
        val bridge = FakeBridge(
            ackQueue = ArrayDeque(
                listOf(
                    // 权威成功：status=accepted
                    testObj("status" to JSONValue.Str("accepted"), "revisionAtDecision" to JSONValue.Int(7)),
                    // 权威成功宽容：ack 包裹形态 + noop
                    testObj(
                        "ack" to testObj(
                            "status" to JSONValue.Str("noop"),
                            "revisionAtDecision" to JSONValue.Int(7),
                        ),
                    ),
                    // 拒绝：reasonCode 原文回传
                    testObj(
                        "status" to JSONValue.Str("rejected"),
                        "reasonCode" to JSONValue.Str("guard.notPermitted"),
                        "message" to JSONValue.Str("桌面拒绝"),
                        "revisionAtDecision" to JSONValue.Int(7),
                    ),
                ),
            ),
        )
        val store = RemoteTaskStore(FakeRemoteTransport(), StoreWorkspaceInfo(path = ws), conversationStore = bridge)

        assertEquals(TaskDecisionOutcome.Accepted, store.approve("sess-1", "allowOnce"))
        assertEquals("ia-1", bridge.lastInteractionId)
        // A-3：answer 恒为对象 {optionId}（approved/scope 是 wire 不存在的键）
        assertEquals("allowOnce", bridge.lastAnswer?.objectValue?.get("optionId")?.stringValue)
        assertEquals(TaskDecisionOutcome.Accepted, store.approve("sess-1", "allowOnce"), "ack 包裹形态宽容读取")
        val rejected = store.approve("sess-1", "allowOnce")
        assertTrue(rejected is TaskDecisionOutcome.Rejected)
        assertTrue(rejected.reason.contains("guard.notPermitted"), "拒因原文（reasonCode 优先）")

        // interaction 不在场 → interactionMissing（U-5：不再静默）
        val absentBridge = FakeBridge(interaction = null)
        val store2 = RemoteTaskStore(FakeRemoteTransport(), StoreWorkspaceInfo(path = ws), conversationStore = absentBridge)
        assertEquals(TaskDecisionOutcome.InteractionMissing, store2.approve("sess-1", "allowOnce"))
    }

    @Test
    fun stop_viaBridge_ackMissingMeansUndelivered() = runBlocking {
        val bridge = FakeBridge(stopAck = null)
        val store = RemoteTaskStore(FakeRemoteTransport(), StoreWorkspaceInfo(path = ws), conversationStore = bridge)
        assertEquals("停止指令未送达 · 连接恢复后重试", store.stop("sess-1"), "stopTurn 回执 null = 命令未送达（写面如实回传）")
        assertEquals(1, bridge.stopCalls)
        bridge.stopAck = testObj("status" to JSONValue.Str("accepted"))
        assertNull(store.stop("sess-1"), "已送达 → 无失败文案")
    }

    @Test
    fun groupedTaskView_membersAtTopLevel_b2Shape() {
        val reply = testObj(
            "groups" to JSONValue.Arr(
                listOf(
                    testObj(
                        "id" to JSONValue.Str("g-2"),
                        "title" to JSONValue.Str("重构组"),
                        "color" to JSONValue.Str("blue"),
                    ),
                    testObj(
                        "id" to JSONValue.Str("g-1"),
                        "title" to JSONValue.Str("主线组"),
                    ),
                ),
            ),
            "members" to JSONValue.Arr(
                listOf(
                    testObj(
                        "groupId" to JSONValue.Str("g-1"),
                        "workspacePath" to JSONValue.Str(ws),
                        "workspaceIdentity" to JSONValue.Str("ident-a"),
                        "taskId" to JSONValue.Str("sess-a"),
                    ),
                    testObj(
                        "groupId" to JSONValue.Str("g-1"),
                        "workspacePath" to JSONValue.Str(ws),
                        "taskId" to JSONValue.Str("sess-b"),
                    ),
                ),
            ),
            "topLevelOrders" to JSONValue.Arr(
                listOf(
                    testObj(
                        "type" to JSONValue.Str("group"),
                        "groupId" to JSONValue.Str("g-2"),
                        "sortOrder" to JSONValue.Int(0),
                    ),
                    testObj(
                        "type" to JSONValue.Str("group"),
                        "groupId" to JSONValue.Str("g-1"),
                        "sortOrder" to JSONValue.Int(1),
                    ),
                ),
            ),
        )
        val grouping = RemoteTaskStore.parseGroupedTaskView(reply)
        assertNotNull(grouping)
        assertEquals(2, grouping.groups.size)
        // 组排序取 topLevelOrders 组条目 sortOrder：g-2(sort 0) 先于 g-1(sort 1)
        assertEquals("g-2", grouping.groups[0].id)
        assertEquals("重构组", grouping.groups[0].name)
        assertEquals(0, grouping.groups[0].taskRefs.size, "g-2 无成员（组对象内无成员键——B-2）")
        assertEquals("g-1", grouping.groups[1].id)
        assertEquals(listOf("sess-a", "sess-b"), grouping.groups[1].taskIds, "顶层 members 按自带 groupId 归组")
        assertEquals("ident-a", grouping.groups[1].taskRefs[0].workspaceIdentity)

        // 解析失败与「桌面无分组」区分：无 groups 键 → null；空 groups 数组 → 合法空
        assertNull(RemoteTaskStore.parseGroupedTaskView(testObj("items" to JSONValue.Arr(emptyList()))))
        val empty = RemoteTaskStore.parseGroupedTaskView(testObj("groups" to JSONValue.Arr(emptyList())))
        assertNotNull(empty)
        assertEquals(0, empty.groups.size)
    }

    @Test
    fun taskWorkspaceScope_identityAbsentOmitsKey() {
        val withIdentity = RemoteTaskStore.taskWorkspaceScopeJson(StoreWorkspaceInfo(path = ws, workspaceIdentity = "ident-1"))
        assertEquals(2, withIdentity.size)
        val without = RemoteTaskStore.taskWorkspaceScopeJson(StoreWorkspaceInfo(path = ws))
        assertEquals(setOf("workspacePath"), without.keys, "identity 缺席省键 = web JSON.stringify 丢 undefined 的同行为")
    }
}

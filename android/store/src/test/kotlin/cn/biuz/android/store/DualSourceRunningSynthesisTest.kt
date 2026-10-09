package cn.biuz.android.store

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 场景二：运行态双源合成——task-index status × sessions-index phase，实时 phase 优先
 * （2026-10-09 用户报障「进行中的列表没有转圈」根因面；上游裁决
 * buildTaskListResultFromSessions.ts:68 `status: sessionTask.status ?? taskIndexTask.status`、
 * taskListRowActivity.ts:43 phase running/prewarming）。
 */
class DualSourceRunningSynthesisTest {

    private val ws = "/Users/mac/zcode_mobile"

    private fun taskRow(taskId: String, status: String): JSONValue = testObj(
        "taskId" to JSONValue.Str(taskId),
        "title" to JSONValue.Str("任务 $taskId"),
        "workspacePath" to JSONValue.Str(ws),
        "status" to JSONValue.Str(status),
        "updatedAt" to testNum(1_767_000_000_000L),
    )

    private fun sessionRow(sessionId: String, phase: String?): JSONValue {
        val row = LinkedHashMap<String, JSONValue>()
        row["sessionId"] = JSONValue.Str(sessionId)
        row["title"] = JSONValue.Str("会话 $sessionId")
        // phase 键真实携带与否是 phaseExplicit 的判定面（缺省回填 completedSuccess 不可辨）
        if (phase != null) row["phase"] = JSONValue.Str(phase)
        row["lastActivityAt"] = testNum(1_767_000_000_000L)
        row["workspacePath"] = JSONValue.Str(ws)
        return JSONValue.Obj(row)
    }

    @Test
    fun taskIndexRunningWithoutPhase_isRunning_viaDualSource() = runBlocking {
        val table = SessionMergeTable()
        // 跨工作区行只来自 task-index：行携带 status=running 而无 phase——只认 phase 时恒不转
        table.mergeTaskIndexRows(
            listOf(
                taskRow("sess-run", "running"),
                taskRow("sess-done", "completed"),
                taskRow("sess-error", "error"),
            ),
        )
        val byId = table.toConversations().associateBy { it.id }
        assertTrue(byId.getValue("sess-run").isRunning, "task-index running 行（无 phase）必须转圈（双源 OR）")
        assertFalse(byId.getValue("sess-done").isRunning)
        assertFalse(byId.getValue("sess-error").isRunning)
        assertTrue(byId.getValue("sess-error").isFailed, "task-index error → 失败任务（桌面侧栏「清理」族）")
    }

    @Test
    fun explicitTerminalPhase_notOverriddenByLateTaskIndexRunning() = runBlocking {
        val table = SessionMergeTable()
        // 先到 task-index running（收口前的旧态）
        table.mergeTaskIndexRows(listOf(taskRow("sess-closed", "running")))
        // 后到 sessions-index 快照：实时 phase 已收口（completedSuccess），行不携 status
        table.applySessionsSnapshot(
            testObj(
                "logEpoch" to JSONValue.Str("epoch-1"),
                "sessions" to JSONValue.Arr(listOf(sessionRow("sess-closed", "completedSuccess"))),
            ),
        )
        val row = table.snapshot().getValue("sess-closed")
        assertEquals("running", row.taskStatus, "快照整行替换前保住 task-index 已回填状态（闪断修复）")
        assertTrue(row.phaseExplicit)
        val conversation = table.toConversations().single()
        assertFalse(
            conversation.isRunning,
            "实携终态 phase 的行以 sessions-index 实时值为准：task-index 迟到的 running 不压过实时收口",
        )
    }

    @Test
    fun sessionPhaseRunning_prewarming_andFailureVocabulary() = runBlocking {
        val table = SessionMergeTable()
        table.applySessionsSnapshot(
            testObj(
                "sessions" to JSONValue.Arr(
                    listOf(
                        sessionRow("sess-phase-running", "running"),
                        sessionRow("sess-phase-prewarming", "prewarming"),
                        sessionRow("sess-phase-error", "completedError"),
                        sessionRow("sess-phase-none", null),
                    ),
                ),
            ),
        )
        val byId = table.toConversations().associateBy { it.id }
        assertTrue(byId.getValue("sess-phase-running").isRunning)
        assertTrue(byId.getValue("sess-phase-prewarming").isRunning, "prewarming 与 running 同权（上游 taskListRowActivity.ts:43）")
        assertTrue(byId.getValue("sess-phase-error").isFailed, "phase 词表 completedError 族宽容 contains")
        assertFalse(byId.getValue("sess-phase-none").isRunning, "phase 缺席（回填 completedSuccess）且无 taskStatus → 非运行")
    }

    @Test
    fun pendingApprovalCountAndUnread_projection() = runBlocking {
        val table = SessionMergeTable()
        table.applySessionsSnapshot(
            testObj(
                "sessions" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "sessionId" to JSONValue.Str("sess-pending"),
                            "title" to JSONValue.Str("待审批会话"),
                            "phase" to JSONValue.Str("running"),
                            "lastActivityAt" to testNum(1_767_000_000_000L),
                            "workspacePath" to JSONValue.Str(ws),
                            "pendingInteractionSummary" to testObj(
                                "permissionCount" to JSONValue.Int(2),
                                "userInputCount" to JSONValue.Int(1),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val plain = table.toConversations().single()
        assertEquals(3, plain.pendingApprovalCount, "行「等待确认」绿标数据源（桌面侧栏同口径）")
        assertEquals(3, plain.unreadCount)
        assertEquals("2 项待审批", plain.todoSummary)
        val marked = table.toConversations(unreadFlags = mapOf("sess-pending" to true)).single()
        assertEquals(3, marked.unreadCount, "本地标记未读 = max(pending, 1)")
        val emptyPending = table
            .applySessionsSnapshot(
                testObj(
                    "sessions" to JSONValue.Arr(
                        listOf(
                            testObj(
                                "sessionId" to JSONValue.Str("sess-quiet"),
                                "phase" to JSONValue.Str("completedSuccess"),
                                "lastActivityAt" to testNum(1_767_000_000_000L),
                            ),
                        ),
                    ),
                ),
            ).let { table.toConversations().single { it.id == "sess-quiet" } }
        assertEquals(0, emptyPending.unreadCount, "无待交互且未标记 → 未读 0")
    }
}

package cn.biuz.android.store

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * v4.0.0 会话面板投影（`state.subagents` / `backgroundWorks` / `activitySummaryDismissed` /
 * `completedActivityCount`）。此前这些读面只有协议默认空实现——子代理与后台工作在
 * Android 侧恒空，是「会话详情与 web 不一致」的协议层根因。
 *
 * 字段形状来源：上游 v4.0.0 `packages/shared/src/zcode-protocol-v4/snapshot.ts`
 * （backgroundWorkSummarySchema:400-419 / subagentProjectionStateSchema:443-459 /
 * activitySummaryDismissedSchema:439-444 / completedActivityCount:577-579）。
 *
 * 注入方式：真实 `RemoteConversationStore` + 一帧 `state.updated`（走生产解析路径，
 * 不直接改内部字段——否则测的是替身不是实现）。
 */
class SessionPanelProjectionTest {

    private suspend fun TestScope.storeWith(vararg pairs: Pair<String, JSONValue>): RemoteConversationStore {
        val f = Fixture(this)
        // 先订阅：帧 handler 由 messages() 注册（§帧序 A8——handler 先于帧到达）
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1",
                subscriptionId = "sub-c-1",
                fromSeq = 1,
                toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("e1"),
                    "rows" to JSONValue.Arr(emptyList()),
                ),
            ),
        )
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1",
                subscriptionId = "sub-c-1",
                fromSeq = 2,
                toSeq = 2,
                deltas = listOf(
                    testObj("op" to JSONValue.Str("state.updated"), "patch" to testObj(*pairs)),
                ),
            ),
        )
        advanceUntilIdle()
        return f.store
    }

    @Test
    fun subagentProjectionParsesRunningEndedAndTotal() = runTest {
        val store = storeWith(
            "subagents" to testObj(
                "revision" to testNum(7),
                "childSessionIds" to JSONValue.Arr(
                    listOf(JSONValue.Str("child-1"), JSONValue.Str("child-2")),
                ),
                "endedTotal" to testNum(12),
                "running" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "childSessionId" to JSONValue.Str("child-1"),
                            "subagentType" to JSONValue.Str("explore"),
                            "title" to JSONValue.Str("Search"),
                            "status" to JSONValue.Str("running"),
                            "startedAt" to testNum(1_700_000_000_000L),
                        ),
                    ),
                ),
                "ended" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "childSessionId" to JSONValue.Str("child-2"),
                            "subagentType" to JSONValue.Str("general"),
                            "title" to JSONValue.Str("Done"),
                            "status" to JSONValue.Str("success"),
                            "endedAt" to testNum(1_700_000_100_000L),
                        ),
                    ),
                ),
            ),
        )
        val p = store.subagentProjection("sess-1")!!
        assertEquals(7, p.revision)
        assertEquals(listOf("child-1", "child-2"), p.childSessionIds)
        assertEquals(12, p.endedTotal, "endedTotal 是历史目录总数，用于「已完成 N 项」分母")
        assertEquals(1, p.running.size)
        assertEquals("running", p.running[0].status)
        assertTrue(p.running[0].isRunning)
        assertEquals(1, p.ended.size, "ended 是本批结束摘要（v4 新增），语义不同于 endedTotal")
        assertEquals("success", p.ended[0].status)
        assertEquals(1_700_000_100_000L, p.ended[0].endedAt)
    }

    @Test
    fun subagentProjectionAbsentOnOldDesktop() = runTest {
        // 旧桌面（< v4.0.0）不发 subagents 键：返回 null，不伪造 ended / 总数
        val store = storeWith(
            "plan" to testObj("items" to JSONValue.Arr(emptyList())),
        )
        assertNull(store.subagentProjection("sess-1"))
    }

    @Test
    fun backgroundWorksParsesResultSummaryAndTimes() = runTest {
        val store = storeWith(
            "backgroundWorks" to JSONValue.Arr(
                listOf(
                    testObj(
                        "workId" to JSONValue.Str("w1"),
                        "kind" to JSONValue.Str("workflow"),
                        "title" to JSONValue.Str("Refactor"),
                        "status" to JSONValue.Str("resultPending"),
                        "startedAt" to testNum(1_700_000_000_000L),
                        "endedAt" to testNum(1_700_000_200_000L),
                        "resultSummary" to JSONValue.Str("改了 3 个文件"),
                    ),
                ),
            ),
        )
        val works = store.backgroundWorks("sess-1")
        assertEquals(1, works.size)
        val w = works.single()
        assertEquals("w1", w.workId)
        assertEquals("workflow", w.kind)
        assertEquals("resultPending", w.rawStatus)
        assertEquals("改了 3 个文件", w.resultSummary, "v4.0.0 新增 resultSummary 必须解析（活动摘要展示用）")
        assertEquals(1_700_000_200_000L, w.endedAt)
        assertFalse(w.isRunning, "resultPending 不是运行中")
    }

    @Test
    fun activitySummaryDismissedAndCompletedCount() = runTest {
        val store = storeWith(
            "activitySummaryDismissed" to testObj(
                "at" to testNum(1_700_000_300_000L),
                "workIds" to JSONValue.Arr(listOf(JSONValue.Str("w1"))),
                "subagentIds" to JSONValue.Arr(listOf(JSONValue.Str("s1"))),
                "workflowRunIds" to JSONValue.Arr(listOf(JSONValue.Str("r1"))),
            ),
            "completedActivityCount" to testNum(9),
        )
        val d = store.activitySummaryDismissed("sess-1")!!
        assertEquals(1_700_000_300_000L, d.at)
        assertEquals(listOf("w1"), d.workIds)
        assertEquals(listOf("s1"), d.subagentIds)
        assertEquals(listOf("r1"), d.workflowRunIds)
        assertEquals(9, store.completedActivityCount("sess-1"))
    }

    // MARK: goal / plan / subagents 读面（v4.0.0 权威形状 + 旧桌面宽容键）

    @Test
    fun goalSummaryReadsV4ObjectiveAndStatusVocab() = runTest {
        // v4 goalStateSchema 权威键 objective + status 闭集（snapshot.ts:471-502）
        val active = storeWith(
            "goal" to testObj(
                "targetId" to JSONValue.Str("t1"),
                "objective" to JSONValue.Str("完成 Android 复刻"),
                "status" to JSONValue.Str("active"),
                "timeUsedSeconds" to testNum(120),
                "iteration" to testNum(2),
            ),
        )
        val g = active.goalSummary("sess-1")!!
        assertEquals("完成 Android 复刻", g.text)
        assertEquals("active", g.rawStatus)
        assertFalse(g.isPaused, "active 不暂停")

        // paused 由 status 决定（v4 无 paused 布尔键）
        val paused = storeWith(
            "goal" to testObj(
                "objective" to JSONValue.Str("x"),
                "status" to JSONValue.Str("paused"),
            ),
        )
        assertTrue(paused.goalSummary("sess-1")!!.isPaused, "status=paused → isPaused")

        // 旧桌面宽容键
        val legacy = storeWith("goal" to testObj("text" to JSONValue.Str("旧目标")))
        assertEquals("旧目标", legacy.goalSummary("sess-1")!!.text)

        // goal 键为 null（无目标）→ null
        val none = storeWith("goal" to JSONValue.Null)
        assertNull(none.goalSummary("sess-1"))
    }

    @Test
    fun planPanelReadsV4ItemsWithThreeStates() = runTest {
        // v4 planStateSchema: {items:[{id,content,status:pending|inProgress|completed}], updatedAt}
        val store = storeWith(
            "plan" to testObj(
                "items" to JSONValue.Arr(
                    listOf(
                        testObj("id" to JSONValue.Str("1"), "content" to JSONValue.Str("读 schema"),
                            "status" to JSONValue.Str("completed")),
                        testObj("id" to JSONValue.Str("2"), "content" to JSONValue.Str("写实现"),
                            "status" to JSONValue.Str("inProgress")),
                        testObj("id" to JSONValue.Str("3"), "content" to JSONValue.Str("跑真机"),
                            "status" to JSONValue.Str("pending")),
                    ),
                ),
                "updatedAt" to JSONValue.Str("2026-11-24T00:00:00Z"),
            ),
        )
        val p = store.planPanel("sess-1")!!
        assertEquals("✓ 读 schema\n▸ 写实现\n· 跑真机", p.content, "v4 三态标记逐行")
        assertEquals(1, p.completedCount, "chip 徽标完成数（web todo secondary 同义）")
        assertEquals(3, p.totalCount)
        assertFalse(p.allCompleted)
        // 结构化条目（UI 逐条渲染：字形独立成前置槽 → 换行悬挂缩进）
        assertEquals(listOf("读 schema", "写实现", "跑真机"), p.items.map { it.title })
        assertEquals(listOf(true, false, false), p.items.map { it.isDone })
        assertEquals(listOf(false, true, false), p.items.map { it.isRunning })
        assertEquals(listOf("✓", "▸", "·"), p.items.map { it.glyph })
        // 旧桌面宽容：正文直取键优先于 items
        val legacy = storeWith("plan" to testObj("content" to JSONValue.Str("- a\n- b")))
        assertEquals("- a\n- b", legacy.planPanel("sess-1")!!.content)
        val none = storeWith("plan" to JSONValue.Null)
        assertNull(none.planPanel("sess-1"))
    }

    @Test
    fun subagentSessionsTakesOnlyRunningFromV4Projection() = runTest {
        val store = storeWith(
            "subagents" to testObj(
                "revision" to testNum(3),
                "running" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "childSessionId" to JSONValue.Str("child-1"),
                            "subagentType" to JSONValue.Str("explore"),
                            "title" to JSONValue.Str("搜索协议"),
                            "status" to JSONValue.Str("running"),
                        ),
                    ),
                ),
                "endedTotal" to testNum(5),
                "ended" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "childSessionId" to JSONValue.Str("child-0"),
                            "subagentType" to JSONValue.Str("general"),
                            "title" to JSONValue.Str("已完成"),
                            "status" to JSONValue.Str("success"),
                        ),
                    ),
                ),
            ),
        )
        val sessions = store.subagentSessions("sess-1")
        assertEquals(1, sessions.size, "旧行级读面只取 running（ended 走 subagentProjection）")
        assertEquals("child-1", sessions[0].childSessionId)
        assertEquals("explore", sessions[0].agentType)
        assertEquals("搜索协议", sessions[0].name)
    }

    @Test
    fun completedCountNullWhenAbsentOrUnreadable() = runTest {
        // 字段缺席（旧桌面）→ null（不显示计数）
        val absent = storeWith("goal" to testObj("objective" to JSONValue.Str("x")))
        assertNull(absent.completedActivityCount("sess-1"))
        // 完整历史读取失败 → 上游给 null，等价于「不显示」
        val nulled = storeWith("completedActivityCount" to JSONValue.Null)
        assertNull(nulled.completedActivityCount("sess-1"))
    }
}

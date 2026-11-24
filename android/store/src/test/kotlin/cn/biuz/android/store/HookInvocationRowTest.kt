package cn.biuz.android.store

// v4 `hookInvocation` 行解析单测（rows.ts:329-351 行 schema + :309-327 execution schema）。
//
// 背景：本端 `when (kind)` 此前没有该分支（else 静默跳过）→ 完全不知道发生过 Hook。
// web 侧行渲染同样 return null（ConversationShareReadonlyTimeline.tsx:826-828），
// 只汇总进「本轮 Hook 详情」入口（ConversationHookDetailsAction）。
//
// 锁定：event 名/计数/executions 投影 + 五态展示映射 + admission-only 不算执行。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class HookInvocationRowTest {

    private fun snapshotWith(vararg rows: JSONValue): JSONValue = testObj(
        "logEpoch" to JSONValue.Str("e1"),
        "revision" to testNum(1),
        "rows" to JSONValue.Arr(rows.toList()),
    )

    @Test
    fun hookRowProjectsEventCountAndExecutions() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    row(
                        1, "hookInvocation",
                        "hookInvocationId" to JSONValue.Str("hi-1"),
                        "hookEventName" to JSONValue.Str("PreToolUse"),
                        "hookCount" to testNum(2),
                        "state" to JSONValue.Str("completed"),
                        "lane" to JSONValue.Str("toolBefore"),
                        "executions" to JSONValue.Arr(
                            listOf(
                                testObj(
                                    "hookRunId" to JSONValue.Str("run-a"),
                                    "hookIndex" to testNum(1),
                                    "didExecute" to JSONValue.Bool(true),
                                    "state" to JSONValue.Str("completed"),
                                    "outcome" to JSONValue.Str("success"),
                                    "durationMs" to testNum(120),
                                    "displayName" to JSONValue.Str("lint-check"),
                                    "sourceKind" to JSONValue.Str("project"),
                                ),
                                testObj(
                                    "hookRunId" to JSONValue.Str("run-b"),
                                    "hookIndex" to testNum(0),
                                    // admission-only blocked：未真正执行
                                    "didExecute" to JSONValue.Bool(false),
                                    "state" to JSONValue.Str("failed"),
                                    "outcome" to JSONValue.Str("blocked"),
                                    "blockReason" to JSONValue.Str("denied"),
                                    "sourceKind" to JSONValue.Str("user"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val msgs = f.store.messages("sess-1")
        val hook = msgs.mapNotNull { it.hookInvocation }.singleOrNull()
        assertNotNull(hook, "hookInvocation 行必须被解析（此前 else 静默跳过）")
        assertEquals("hi-1", hook.hookInvocationId)
        assertEquals("PreToolUse", hook.hookEventName)
        assertEquals(2, hook.hookCount)
        assertEquals("toolBefore", hook.lane)
        assertEquals(2, hook.executions.size)
        assertEquals("run-a", hook.executions[0].hookRunId)
        assertEquals(120L, hook.executions[0].durationMs)
        assertEquals("project", hook.executions[0].sourceKind)
        assertEquals("denied", hook.executions[1].blockReason)
        assertEquals(false, hook.executions[1].didExecute)
    }

    @Test
    fun displayStatusFollowsWebHookDetailStatus() {
        fun exec(state: String, outcome: String?) = cn.biuz.android.protocol.HookExecutionInfo(
            hookRunId = "r", state = state, outcome = outcome,
        )
        assertEquals("running", exec("running", null).displayStatus, "running 优先于 outcome")
        assertEquals("timedOut", exec("completed", "timed_out").displayStatus)
        assertEquals("cancelled", exec("completed", "cancelled").displayStatus)
        assertEquals("blocked", exec("completed", "blocked").displayStatus)
        assertEquals("failed", exec("failed", null).displayStatus)
        assertEquals("failed", exec("completed", "failed").displayStatus, "outcome=failed 也算失败")
        assertNull(exec("completed", "success").displayStatus, "成功无状态标")
    }

    @Test
    fun rowWithoutExecutionsStillParses() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-2")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-2",
            frame(
                topic = "conversation/sess-2", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    row(
                        1, "hookInvocation",
                        "hookEventName" to JSONValue.Str("Stop"),
                        "state" to JSONValue.Str("running"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val hook = f.store.messages("sess-2").mapNotNull { it.hookInvocation }.single()
        assertEquals("Stop", hook.hookEventName)
        assertEquals(0, hook.executions.size)
        assertEquals(1, hook.hookCount, "hookCount 缺席回退 1")
    }
}

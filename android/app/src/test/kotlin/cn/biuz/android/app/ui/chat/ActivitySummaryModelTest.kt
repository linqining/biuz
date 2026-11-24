package cn.biuz.android.app.ui.chat

// 活动摘要派生模型单测（web ConversationStatusPanel.tsx:1035-1300 逐条口径）。
// 三条红线各一组：
// - 忽略集只对已结束生效，且子代理带「endedAt > dismissed.at 复活」时间例外；
// - completedActivityCount = null（读失败）才回退 preview；缺席（旧桌面）也走 preview；
// - 计数优先级：运行数 > 失败数 > 完成数。

import cn.biuz.android.protocol.ActivitySummaryDismissed
import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.SubagentProjectionState
import cn.biuz.android.protocol.SubagentRuntimeSummary
import cn.biuz.android.protocol.WorkflowActorSummary
import cn.biuz.android.protocol.WorkflowRunSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivitySummaryModelTest {

    private fun run(
        id: String,
        status: String,
        startedAt: Long? = null,
        actors: List<WorkflowActorSummary> = emptyList(),
    ) = WorkflowRunSummary(
        id = id,
        name = "run-$id",
        rawStatus = status,
        actors = actors,
        startedAt = startedAt,
    )

    private fun work(
        id: String,
        status: String,
        kind: String = "bash",
        startedAt: Long? = null,
    ) = BackgroundWorkSummary(
        workId = id,
        title = "work-$id",
        kind = kind,
        rawStatus = status,
        startedAt = startedAt,
    )

    private fun agent(
        id: String,
        status: String,
        endedAt: Long? = null,
    ) = SubagentRuntimeSummary(
        childSessionId = id,
        subagentType = "explore",
        title = "agent-$id",
        status = status,
        endedAt = endedAt,
    )

    private fun projection(
        running: List<SubagentRuntimeSummary> = emptyList(),
        ended: List<SubagentRuntimeSummary> = emptyList(),
        endedTotal: Int = 0,
    ) = SubagentProjectionState(running = running, ended = ended, endedTotal = endedTotal)

    @Test
    fun runningActivitiesSortedByStartedAtAcrossThreeSources() {
        val model = buildActivitySummaryModel(
            workflowRuns = listOf(run("r1", "running", startedAt = 200)),
            backgroundWorks = listOf(work("b1", "running", startedAt = 100)),
            subagentProjection = projection(running = listOf(agent("c1", "running").copy(startedAt = 300))),
            dismissed = null,
            completedActivityCount = null,
        )
        assertEquals(listOf("bash:b1", "workflow:r1", "agent:c1"), model.running.map { it.id })
        assertTrue(model.isRunningAny)
        assertEquals("chat.capsule.activity.running", model.labelKey)
        assertEquals(3, model.count, "运行中优先报运行数")
    }

    @Test
    fun dismissedHidesEndedButNeverRunning() {
        val model = buildActivitySummaryModel(
            workflowRuns = listOf(
                run("r-live", "running", startedAt = 200), run("r-done", "completed"),
            ),
            backgroundWorks = listOf(
                work("b-live", "running", startedAt = 100),
                work("b-done", "resultPending", startedAt = 150),
            ),
            subagentProjection = projection(
                running = listOf(agent("c-live", "running").copy(startedAt = 300)),
                ended = listOf(agent("c-done", "success", endedAt = 50)),
            ),
            dismissed = ActivitySummaryDismissed(
                at = 100,
                workIds = listOf("b-live", "b-done"),
                subagentIds = listOf("c-live", "c-done"),
                workflowRunIds = listOf("r-live", "r-done"),
            ),
            completedActivityCount = 0,
        )
        assertEquals(
            listOf("bash:b-live", "workflow:r-live", "agent:c-live"),
            model.running.map { it.id },
            "忽略集不隐藏仍在跑的项（web :1050-1070）",
        )
        assertTrue(
            model.ended.isEmpty(),
            "已结束项被忽略集隐藏（b-done / r-done / c-done 全在集内且 endedAt ≤ at）",
        )
    }

    @Test
    fun subagentRevivesWhenEndedAfterDismiss() {
        // 忽略之后又跑完一轮：endedAt > dismissed.at → 重新出现（web :1056-1057）
        val model = buildActivitySummaryModel(
            workflowRuns = emptyList(),
            backgroundWorks = emptyList(),
            subagentProjection = projection(
                ended = listOf(
                    agent("old", "success", endedAt = 50),
                    agent("revived", "success", endedAt = 200),
                ),
            ),
            dismissed = ActivitySummaryDismissed(
                at = 100,
                subagentIds = listOf("old", "revived"),
            ),
            completedActivityCount = null,
        )
        assertEquals(
            listOf("agent:revived"),
            model.ended.map { it.id },
            "endedAt 晚于忽略时刻的子代理复活；其余仍隐藏",
        )
    }

    @Test
    fun workflowChildSubagentsNotDoubleCounted() {
        // run.actors 携 sessionId 的 child 不再作为独立子代理列出（web workflowChildren）
        val model = buildActivitySummaryModel(
            workflowRuns = listOf(
                run(
                    "r1", "running", startedAt = 100,
                    actors = listOf(
                        WorkflowActorSummary(
                            id = "s#1", name = "a", rawStatus = "running", phaseName = null,
                            sessionId = "c-in-run",
                        ),
                    ),
                ),
            ),
            backgroundWorks = emptyList(),
            subagentProjection = projection(
                running = listOf(
                    agent("c-in-run", "running"),
                    agent("c-free", "running").copy(startedAt = 200),
                ),
            ),
            dismissed = null,
            completedActivityCount = null,
        )
        assertEquals(
            listOf("workflow:r1", "agent:c-free"),
            model.running.map { it.id },
            "工作流内的子代理只经 run 呈现一次",
        )
    }

    @Test
    fun completedCountNullFallsBackToPreviewButMarksUnknown() {
        // null（完整历史读失败）→ 回退 preview（1 run completed + 1 success child = 2）
        val nullCase = buildActivitySummaryModel(
            workflowRuns = listOf(run("r1", "completed")),
            backgroundWorks = emptyList(),
            subagentProjection = projection(
                ended = listOf(agent("c1", "success", endedAt = 10)),
            ),
            dismissed = null,
            completedActivityCount = null,
        )
        assertEquals(2, nullCase.count, "null 回退 preview（web :1284-1287）")
        assertFalse(nullCase.countKnown, "null 是「未知」不是 0，UI 显示省略")

        // 权威计数在场（9）→ 直接采信，不被 preview 影响
        val knownCase = buildActivitySummaryModel(
            workflowRuns = listOf(run("r1", "completed")),
            backgroundWorks = emptyList(),
            subagentProjection = projection(ended = listOf(agent("c1", "success", endedAt = 10))),
            dismissed = null,
            completedActivityCount = 9,
        )
        assertEquals(9, knownCase.count, "权威计数优先")
        assertTrue(knownCase.countKnown)
    }

    @Test
    fun failedCountWinsOverCompletedWhenNoRunning() {
        val model = buildActivitySummaryModel(
            workflowRuns = listOf(run("r-fail", "errored"), run("r-ok", "completed")),
            backgroundWorks = emptyList(),
            subagentProjection = null,
            dismissed = null,
            completedActivityCount = 5,
        )
        assertEquals(ActivityResult.Failed, model.result, "有失败项 → 总结果失败")
        assertEquals(1, model.count, "无运行 → 报失败数（web :1301）")
        assertEquals("chat.capsule.activity.failed", model.labelKey)
    }

    @Test
    fun stoppedOnlyWhenNothingCompleted() {
        val model = buildActivitySummaryModel(
            workflowRuns = listOf(run("r-stop", "stopped")),
            backgroundWorks = emptyList(),
            subagentProjection = null,
            dismissed = null,
            completedActivityCount = 0,
        )
        assertEquals(ActivityResult.Stopped, model.result, "无完成项且有停止项 → 已停止")
        assertEquals("chat.capsule.activity.stopped", model.labelKey)

        // 有完成项时不再报停止（web hasCompletedResult 优先）
        val withCompleted = buildActivitySummaryModel(
            workflowRuns = listOf(run("r-stop", "stopped"), run("r-ok", "completed")),
            backgroundWorks = emptyList(),
            subagentProjection = null,
            dismissed = null,
            completedActivityCount = 1,
        )
        assertEquals(ActivityResult.Completed, withCompleted.result)
    }

    @Test
    fun directoriesRequireDismissedIdentityAndCount() {
        val noIdentity = buildActivitySummaryModel(
            workflowRuns = emptyList(),
            backgroundWorks = emptyList(),
            subagentProjection = projection(endedTotal = 3),
            dismissed = null,
            completedActivityCount = 0,
            endedSubagentCount = 3,
            endedWorkflowRunCount = 2,
            hasDirectoryTargets = true,
        )
        assertFalse(noIdentity.hasSubagentDirectory, "无忽略集 = 无历史身份，不出目录入口")
        assertFalse(noIdentity.visible, "三类全空且无目录 → 整行不渲染")

        val withIdentity = buildActivitySummaryModel(
            workflowRuns = emptyList(),
            backgroundWorks = emptyList(),
            subagentProjection = projection(endedTotal = 3),
            dismissed = ActivitySummaryDismissed(
                at = 1, subagentIds = listOf("c1"), workflowRunIds = listOf("r1"),
            ),
            completedActivityCount = 0,
            endedSubagentCount = 3,
            endedWorkflowRunCount = 2,
            hasDirectoryTargets = true,
        )
        assertTrue(withIdentity.hasSubagentDirectory)
        assertTrue(withIdentity.hasWorkflowDirectory)
        assertEquals(3, withIdentity.subagentDirectoryCount)
        assertTrue(withIdentity.visible)

        // 无宿主目标（无 parentSessionId/回调）时目录不出（web hasDirectoryTargets 同义）
        val noTargets = buildActivitySummaryModel(
            workflowRuns = emptyList(),
            backgroundWorks = emptyList(),
            subagentProjection = projection(endedTotal = 3),
            dismissed = ActivitySummaryDismissed(
                at = 1, subagentIds = listOf("c1"), workflowRunIds = listOf("r1"),
            ),
            completedActivityCount = 0,
            endedSubagentCount = 3,
            endedWorkflowRunCount = 2,
            hasDirectoryTargets = false,
        )
        assertFalse(noTargets.hasSubagentDirectory)
        assertFalse(noTargets.visible)
    }

    @Test
    fun avatarBadgeDerivesFromResultNotRawStatus() {
        // web :1218-1224 注释：停止/失败不能回退 undefined，否则头像显示等待三点
        val completed = ActivityAvatarItem(
            id = "a", name = "explore",
            status = ActivityAvatarItem.AvatarStatus.Running,
            result = ActivityResult.Completed,
        )
        assertEquals(ActivityAvatarItem.AvatarStatus.Done, completed.badge, "有 final 结果时以 result 定徽标")

        val stopped = ActivityAvatarItem(
            id = "b", name = "general",
            status = ActivityAvatarItem.AvatarStatus.Running,
            result = ActivityResult.Stopped,
        )
        assertEquals(ActivityAvatarItem.AvatarStatus.Running, stopped.badge, "停止不算 Done/Failed，保留原状态")

        val live = ActivityAvatarItem(
            id = "c", name = "general",
            status = ActivityAvatarItem.AvatarStatus.Running,
            result = null,
        )
        assertEquals(ActivityAvatarItem.AvatarStatus.Running, live.badge)
    }

    @Test
    fun avatarGroupKeepsRunningAndSuccessNotDismissedFiltered() {
        val model = buildActivitySummaryModel(
            workflowRuns = emptyList(),
            backgroundWorks = emptyList(),
            subagentProjection = projection(
                running = listOf(agent("c-run", "running")),
                ended = listOf(agent("c-ok", "success", endedAt = 10)),
            ),
            dismissed = ActivitySummaryDismissed(at = 100, subagentIds = listOf("c-ok")),
            completedActivityCount = 1,
        )
        assertEquals(
            listOf("c-run", "c-ok"),
            model.avatarSubagents.map { it.id },
            "头像组保留运行与成功项，不复用摘要过滤（web :1304-1309 注释）",
        )
        assertTrue(model.ended.isEmpty(), "但摘要明细里 c-ok 已被忽略")
    }
}

package cn.biuz.android.store

// 工作流阶段行归属单测（v1.24「点击工作流某项不展开」根修回归门）。
//
// 上游证据【实证·上游仓】：phase-name.ts phaseNameMatches（精确优先、display 顶到
// 128 截断上界才前缀兜底）+ instance-phases.ts phasesOf（无戳实例有词汇归「未分组」
// 站、无词汇归全部站；匹配为空归全部站）+ WorkflowRunPhaseList.tsx（阶段行无条件
// 可展开）。此前的精确相等匹配 + 有实例才可展开，是无戳/截断戳实例永无归站、
// 部分阶段行点击无反应的根因。
//
// 平移自 ios/UnitTests/WorkflowActorBindingTests.swift（逐用例断言语义一一对应，
// 用例名逐字保留）；被测 API：
// - WorkflowRunSummary.phaseNameMatches / actors(boundTo:) / unphasedStationID
//   （:protocol 模块 Models.kt:263/:276/:256）；
// - RemoteConversationStore.parseWorkflowRun（本模块 main，
//   RemoteConversationStore.kt:4204；iOS RemoteConversationStore.swift:3497-3700 直译）。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.WorkflowActorSummary
import cn.biuz.android.protocol.WorkflowNodeSummary
import cn.biuz.android.protocol.WorkflowRunSummary
import cn.biuz.android.protocol.WorkflowStepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** iOS 原文 node(_:_:status:) 助手（WorkflowActorBindingTests.swift:14-17）逐字直译。 */
private fun node(id: String, label: String, status: WorkflowStepStatus = WorkflowStepStatus.pending) =
    WorkflowNodeSummary(id = id, label = label, status = status)

/** iOS 原文 actor(_:phaseName:rawStatus:sessionId:) 助手（WorkflowActorBindingTests.swift:19-24）逐字直译。 */
private fun actor(
    id: String,
    phaseName: String?,
    rawStatus: String = "running",
    sessionId: String? = null,
) = WorkflowActorSummary(
    id = id,
    name = "actor-$id",
    rawStatus = rawStatus,
    phaseName = phaseName,
    sessionId = sessionId,
    tasksTotal = null,
    tasksSettled = null,
)

class WorkflowActorBindingTest {

    // MARK: phaseNameMatches（上游 phase-name.ts 逐字移植；Models.kt:263）

    @Test
    fun testPhaseNameMatchesExactPriority() {
        assertTrue(WorkflowRunSummary.phaseNameMatches("计划", "计划"))
        // 128 上界内不做前缀兜底：「计划」不得误认「计划修复」（上游注释原话）
        assertFalse(WorkflowRunSummary.phaseNameMatches("计划", "计划修复"))
    }

    @Test
    fun testPhaseNameMatchesPrefixOnlyAtTruncationBound() {
        // 截断方向：display 名被截到 128 上界，运行时出生戳带全名（phase-name.ts 注释）
        val runtime = "长".repeat(200)
        val display = runtime.take(128)
        // display 顶到 128 上界（截断真的发生过）→ 前缀兜底放行
        assertTrue(WorkflowRunSummary.phaseNameMatches(display, runtime))
        assertTrue(WorkflowRunSummary.phaseNameMatches(runtime, runtime))
        // 未顶到上界 → 前缀一律不放行（「计划」不得误认「计划修复」同族）
        val shortDisplay = runtime.take(127)
        assertFalse(WorkflowRunSummary.phaseNameMatches(shortDisplay, runtime))
    }

    // MARK: actors(boundTo:)（上游 phasesOf 三分支移植；Models.kt:276）

    @Test
    fun testBoundByExactStamp() {
        val run = WorkflowRunSummary(
            id = "r1", name = "run", rawStatus = "running", stopReason = null, resumable = false,
            truncated = false,
            nodes = listOf(node("s0", "开发"), node("s1", "验收")),
            actors = listOf(
                actor("a1", phaseName = "开发"),
                actor("a2", phaseName = "验收", rawStatus = "completed"),
            ),
            hasPhaseVocabulary = true,
        )
        assertEquals(listOf("a1"), run.actors(boundTo = run.nodes[0]).map { it.id })
        assertEquals(listOf("a2"), run.actors(boundTo = run.nodes[1]).map { it.id })
    }

    @Test
    fun testUnphasedActorsGoToUnphasedStationWhenVocabularyExists() {
        val run = WorkflowRunSummary(
            id = "r1", name = "run", rawStatus = "running", stopReason = null, resumable = false,
            truncated = false,
            nodes = listOf(node(WorkflowRunSummary.unphasedStationID, "未分组"), node("s1", "开发")),
            actors = listOf(
                actor("a1", phaseName = null),
                actor("a2", phaseName = "开发"),
            ),
            hasPhaseVocabulary = true,
        )
        // 有词汇：无戳实例只归「未分组」站，不得广播到具名站（上游 phasesOf 同构）
        assertEquals(listOf("a1"), run.actors(boundTo = run.nodes[0]).map { it.id })
        assertEquals(listOf("a2"), run.actors(boundTo = run.nodes[1]).map { it.id })
    }

    @Test
    fun testUnphasedActorsBroadcastWhenNoVocabulary() {
        val run = WorkflowRunSummary(
            id = "r1", name = "run", rawStatus = "running", stopReason = null, resumable = false,
            truncated = false,
            nodes = listOf(node("s0", "开发"), node("s1", "验收")),
            actors = listOf(actor("a1", phaseName = null)),
            hasPhaseVocabulary = false,
        )
        // 无词汇：无戳 ↔ 全部站是同一事实的两面（上游「任一分支结果为空 → 全部阶段」）
        assertEquals(listOf("a1"), run.actors(boundTo = run.nodes[0]).map { it.id })
        assertEquals(listOf("a1"), run.actors(boundTo = run.nodes[1]).map { it.id })
    }

    @Test
    fun testOrphanActorsFallBackToAllStations() {
        val run = WorkflowRunSummary(
            id = "r1", name = "run", rawStatus = "running", stopReason = null, resumable = false,
            truncated = false,
            nodes = listOf(node("s0", "站A"), node("s1", "站B")),
            actors = listOf(actor("a1", phaseName = "不存在的站")),
            hasPhaseVocabulary = true,
        )
        // 戳不匹配任何站 → 归全部站（宁重复不藏）
        assertEquals(listOf("a1"), run.actors(boundTo = run.nodes[0]).map { it.id })
        assertEquals(listOf("a1"), run.actors(boundTo = run.nodes[1]).map { it.id })
    }

    // MARK: parseWorkflowRun 合成站（无站/无戳兜底；RemoteConversationStore.kt:4204）

    @Test
    fun testParseSynthesizesImplicitStationWhenNoStationsButActors() {
        val json = JSONValue.Obj(
            mapOf(
                "runId" to JSONValue.Str("dwfrun-1"),
                "name" to JSONValue.Str("wf"),
                "status" to JSONValue.Str("running"),
                "actors" to JSONValue.Arr(
                    listOf(
                        JSONValue.Obj(
                            mapOf(
                                "siteId" to JSONValue.Str("site"),
                                "ordinal" to JSONValue.Int(1),
                                "status" to JSONValue.Str("running"),
                                // 旧 CLI：无 phaseName 戳、无 nodes/phases
                            ),
                        ),
                    ),
                ),
            ),
        )
        val run = RemoteConversationStore.parseWorkflowRun(json)
        assertNotNull(run)
        assertEquals(1, run.nodes.size, "无站有实例应合成隐式单站承接")
        assertEquals(WorkflowStepStatus.running, run.nodes.first().status)
        assertEquals(1, run.actors(boundTo = run.nodes[0]).size)
    }

    @Test
    fun testParseSynthesizesUnphasedStationForUnstampedActors() {
        val json = JSONValue.Obj(
            mapOf(
                "runId" to JSONValue.Str("dwfrun-2"),
                "name" to JSONValue.Str("wf"),
                "status" to JSONValue.Str("running"),
                "phaseNames" to JSONValue.Arr(listOf(JSONValue.Str("开发"), JSONValue.Str("验收"))),
                "nodes" to JSONValue.Arr(
                    listOf(
                        JSONValue.Obj(
                            mapOf(
                                "phaseName" to JSONValue.Str("开发"),
                                "phase" to JSONValue.Str("executing"),
                            ),
                        ),
                    ),
                ),
                "actors" to JSONValue.Arr(
                    listOf(
                        JSONValue.Obj(
                            mapOf(
                                "siteId" to JSONValue.Str("site"),
                                "ordinal" to JSONValue.Int(1),
                                "status" to JSONValue.Str("running"),
                                "phaseName" to JSONValue.Str("开发"),
                            ),
                        ),
                        JSONValue.Obj(
                            mapOf(
                                "siteId" to JSONValue.Str("site"),
                                "ordinal" to JSONValue.Int(2),
                                "status" to JSONValue.Str("running"), // 无戳：首个标记前出生/旧 CLI
                            ),
                        ),
                    ),
                ),
            ),
        )
        val run = RemoteConversationStore.parseWorkflowRun(json)
        assertNotNull(run)
        assertTrue(run.hasPhaseVocabulary)
        assertEquals(
            WorkflowRunSummary.unphasedStationID,
            run.nodes.first().id,
            "有词汇且有无戳实例应在表头合成「未分组」站",
        )
        assertEquals(3, run.nodes.size, "未分组站 + 两个声明站")
        val unphased = run.actors(boundTo = run.nodes[0])
        val dev = run.actors(boundTo = run.nodes[1])
        assertEquals(1, unphased.size, "无戳实例只归未分组站")
        assertEquals(1, dev.size, "有戳实例归具名站")
    }
}

package cn.biuz.android.app.ui.chat

// 工作流面板/迷你轨道/完成卡纯函数单测（testDebugUnitTest 纯逻辑；Mock 仅测试装配）。
// 覆盖面：foldWorkflowRail 折叠窗口（G-007 桌面 foldWorkflowRunRail 同构）、站点 twin
// 双线段判定、完成卡四格格式化（tokens 紧凑/字节/时长）、run/plan 状态词映射、
// works 行时长、模型短名。纯函数零 Android 依赖。

import cn.biuz.android.protocol.BackgroundWorkSummary
import cn.biuz.android.protocol.SessionWorkflowPhase
import cn.biuz.android.protocol.WorkflowStepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowPanelsPureTest {

    private fun phase(name: String, status: WorkflowStepStatus, alongside: List<Int> = emptyList()) =
        SessionWorkflowPhase(name = name, status = status, alongside = alongside)

    // MARK: foldWorkflowRail（≤6 全画）

    @Test
    fun foldWorkflowRail_emptyPhasesYieldsEmpty() {
        val (stations, hidden) = foldWorkflowRail(emptyList())
        assertTrue(stations.isEmpty())
        assertEquals(0, hidden)
    }

    @Test
    fun foldWorkflowRail_sixStationsDrawAll() {
        val phases = (1..6).map { phase("站$it", WorkflowStepStatus.pending) }
        val (stations, hidden) = foldWorkflowRail(phases)
        assertEquals(6, stations.size)
        assertEquals(0, hidden)
    }

    @Test
    fun foldWorkflowRail_sevenStationsFoldAroundRunning() {
        val phases = (1..7).map { i ->
            phase("站$i", if (i == 5) WorkflowStepStatus.running else WorkflowStepStatus.pending)
        }
        val (stations, hidden) = foldWorkflowRail(phases)
        // 运行站（index 4）为中心保留 ±2 共 5 站 → 站3..站7；隐藏 2
        assertEquals(5, stations.size)
        assertEquals("站3", stations.first().name)
        assertEquals("站7", stations.last().name)
        assertEquals(2, hidden)
    }

    @Test
    fun foldWorkflowRail_noRunningAnchorFallsToLastReached() {
        val phases = (1..7).map { i ->
            phase("站$i", if (i <= 4) WorkflowStepStatus.done else WorkflowStepStatus.pending)
        }
        val (stations, hidden) = foldWorkflowRail(phases)
        // 无运行站：锚点取最后一个已到达站（index 3，站4）→ 窗口 站2..站6
        assertEquals(5, stations.size)
        assertEquals("站2", stations.first().name)
        assertEquals(2, hidden)
    }

    @Test
    fun foldWorkflowRail_twinMarkedWhenAlongsidePrevious() {
        val phases = listOf(
            phase("规划", WorkflowStepStatus.done),
            phase("实现", WorkflowStepStatus.running, alongside = listOf(0)),
            phase("验收", WorkflowStepStatus.pending),
        )
        val (stations, _) = foldWorkflowRail(phases)
        assertFalse(stations[0].twin)
        assertTrue(stations[1].twin) // alongside 含前一站 → 双线段
        assertFalse(stations[2].twin)
        // reached：控制流到过（running/done 都算；pending 不算）
        assertTrue(stations[0].reached)
        assertTrue(stations[1].reached)
        assertFalse(stations[2].reached)
    }

    // MARK: run/plan 状态词映射

    @Test
    fun runStatusText_coversFiveStateVocabulary() {
        assertEquals("运行中", runStatusText("running"))
        assertEquals("已完成", runStatusText("completed"))
        assertEquals("失败", runStatusText("errored"))
        assertEquals("已停止", runStatusText("stopped"))
        assertEquals("待执行", runStatusText("pending"))
        assertEquals("待执行", runStatusText("unknown-word")) // 未知回退待执行
    }

    @Test
    fun planStatusText_tolerantMapping() {
        assertEquals("运行中", planStatusText("active"))
        assertEquals("已完成", planStatusText("done"))
        assertEquals("失败", planStatusText("errored"))
        assertEquals("待执行", planStatusText("draft"))
        assertEquals("weird", planStatusText("weird")) // 未知原词回退原文
    }

    // MARK: 完成卡四格格式化（上游 WorkflowCompletionCard 数字诚实口径）

    @Test
    fun compactCount_fixedWidthTiers() {
        assertEquals("990", compactCount(990))
        assertEquals("1.0k", compactCount(1_000))
        assertEquals("12.3k", compactCount(12_340))
        assertEquals("1.23M", compactCount(1_234_000))
    }

    @Test
    fun byteText_tiers() {
        assertEquals("990 B", byteText(990))
        assertEquals("1.0 KB", byteText(1_000))
        assertEquals("1.1 MB", byteText(1_100_000))
    }

    @Test
    fun completionDurationText_honestNullForMissing() {
        assertNull(completionDurationText(null))
        assertNull(completionDurationText(0)) // 拿不到写「—」不写 0（上游口径）
        assertEquals("5 分钟", completionDurationText(300_000))
        assertEquals("20 小时 22 分钟", completionDurationText(20 * 3_600_000 + 22 * 60_000))
    }

    @Test
    fun kindLabel_sixStateClosedSet() {
        assertEquals("文档", kindLabel("markdown"))
        assertEquals("图表", kindLabel("chart"))
        assertEquals("表格", kindLabel("table"))
        assertEquals("指标", kindLabel("metrics"))
        assertEquals("看板", kindLabel("board"))
        assertEquals("文件", kindLabel("file"))
        assertEquals("文件", kindLabel("anything-else"))
    }

    // MARK: works 行时长（startedAt/endedAt；运行中=至今）

    @Test
    fun worksDurationText_runningCountsFromStart() {
        val now = 1_000_000L
        val work = BackgroundWorkSummary(workId = "w1", rawStatus = "running", startedAt = now - 65_000)
        assertEquals("1 分", worksDurationText(work, nowMs = now))
    }

    @Test
    fun worksDurationText_hoursAndMissing() {
        val now = 1_000_000L
        val long = BackgroundWorkSummary(workId = "w2", rawStatus = "running", startedAt = now - 3_600_000 * 2)
        assertEquals("2 小时 0 分", worksDurationText(long, nowMs = now))
        val noStart = BackgroundWorkSummary(workId = "w3", rawStatus = "running")
        assertNull(worksDurationText(noStart, nowMs = now))
    }

    // MARK: 模型短名（iOS shortModelName 逐字直译）

    @Test
    fun shortModelName_stripsAccountPrefixAndKeepsVariant() {
        assertEquals(
            "GLM-5.3-Flash·max",
            shortModelName("account:zai-individual-coding-plan/GLM-5.3-Flash\$max"),
        )
        assertEquals("GLM-5.3-Flash", shortModelName("GLM-5.3-Flash"))
    }
}

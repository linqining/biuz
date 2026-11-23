package cn.biuz.android.app.ui.chat

// 后台面板行序 + 「面板不得空白」回归门。
//
// 缺陷背景（2026-11-24 真机取证）：WorksPanelView 原实现**只渲染 isRunning 条目**，
// 而 chip 徽标统计全部条目 → 徽标「后台 4」、展开面板空白（4 条 bash 全为 resultPending）。
// web 对位：backgroundWorks 的 running 与已结束**都可见**（进 activity 胶囊的两个组）。

import cn.biuz.android.protocol.BackgroundWorkSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorksPanelOrderTest {

    private fun work(id: String, status: String, endedAt: Long? = null, startedAt: Long? = null) =
        BackgroundWorkSummary(
            workId = id,
            title = "work-$id",
            kind = "bash",
            rawStatus = status,
            startedAt = startedAt,
            endedAt = endedAt,
        )

    @Test
    fun finishedWorksAreStillListedNotDropped() {
        // 「徽标有数、面板空白」的直接回归门：非 running 也必须出现在结果里
        val ordered = orderWorksForPanel(
            listOf(
                work("a", "resultPending", endedAt = 100),
                work("b", "failed", endedAt = 200),
                work("c", "cancelled", endedAt = 50),
            ),
        )
        assertEquals(3, ordered.size, "已结束条目不得被丢弃（原实现只留 running → 空面板）")
        assertEquals(listOf("b", "a", "c"), ordered.map { it.workId }, "已结束按 endedAt 降序")
    }

    @Test
    fun runningWorksFloatAboveFinished() {
        val ordered = orderWorksForPanel(
            listOf(
                work("done-recent", "resultPending", endedAt = 9_000),
                work("running-old", "running", startedAt = 1_000),
                work("done-old", "failed", endedAt = 100),
            ),
        )
        assertEquals(
            listOf("running-old", "done-recent", "done-old"),
            ordered.map { it.workId },
            "运行中恒在已结束之上（即便 startedAt 更早）",
        )
        assertTrue(ordered.first().isRunning)
    }

    @Test
    fun missingTimestampsSinkToBottom() {
        val ordered = orderWorksForPanel(
            listOf(
                work("no-time", "resultPending"),
                work("has-time", "resultPending", endedAt = 500),
            ),
        )
        assertEquals(listOf("has-time", "no-time"), ordered.map { it.workId }, "时间缺席沉底")
    }
}

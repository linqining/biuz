package cn.biuz.android.protocol

// 平移自 ios/UnitTests/BackgroundWorkPanelTests.swift：后台工作面板投影单测
// （v1.25「运行中的参考价值更大」回归门）。
//
// 上游证据【实证·上游仓 snapshot.ts backgroundWorkSummarySchema】：status 四态闭集
// running|resultPending|failed|cancelled、startedAt/endedAt/blocked；运行中派生口径
// = status === "running"（snapshot.ts:136 注释逐字）。UI 口径：运行中置顶、历史折叠。
//
// 落位说明：被测 API BackgroundWorkSummary 在本模块 Models.kt:316（iOS Models.swift:272-308
// 直译），isRunning/statusText/blocked 三个派生面为本文件断言对象。
//
// 【未平移挂账】iOS 第 4 例 testMockDemoProjectionGroupsRunningFirst（MockConversationStore
// 演示投影 c1 有数据/c2 空）被测 API 缺位：Android 按 AGENTS §5-13 移除 Mock 演示数据
// （store StoreProtocols.kt「移除 Mock 后的编译基座」，backgroundWorks 空实现恒 emptyList()），
// 演示数据面契约无对应实现可断言。回补条件：若后续在测试源码集引入演示数据构建器，
// 按 iOS 原文断言补齐（运行中在场且置顶、覆盖 resultPending、startedAt 在场、空会话不渲染）。

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundWorkPanelTests {

    /** iOS work(status:startedAt:endedAt:blocked:) 辅助构造直译（Date? → Long? epoch 毫秒） */
    private fun work(
        status: String?,
        startedAt: Long? = null,
        endedAt: Long? = null,
        blocked: Boolean = false,
    ): BackgroundWorkSummary =
        BackgroundWorkSummary(
            workId = "w-$status-${UUID.randomUUID()}",
            title = "t",
            kind = "bash",
            rawStatus = status,
            cancellable = false,
            resumable = false,
            runId = null,
            sessionId = null,
            startedAt = startedAt,
            endedAt = endedAt,
            blocked = blocked,
        )

    @Test
    fun testRunningDerivedFromAuthoritativeStatus() {
        assertTrue(work("running").isRunning)
        assertFalse(work("resultPending").isRunning)
        assertFalse(work("failed").isRunning)
        assertFalse(work("cancelled").isRunning)
        // 旧桌面宽容词不误判为运行中
        assertFalse(work("completed").isRunning)
        assertFalse(work(null).isRunning)
    }

    @Test
    fun testStatusTextCoversAuthoritativeVocabulary() {
        // 权威四态闭集【实证·上游仓】（String(localized:) 键 = 同串，见 Models.kt 文件头映射约定）
        assertEquals("运行中", work("running").statusText)
        assertEquals("待投递", work("resultPending").statusText)
        assertEquals("失败", work("failed").statusText)
        assertEquals("已取消", work("cancelled").statusText)
        // 宽容词 + 未知原词回退原文（不猜测）
        assertEquals("已停止", work("stopped").statusText)
        assertEquals("weird-state", work("weird-state").statusText)
        assertEquals("", work(null).statusText)
    }

    @Test
    fun testBlockedDefaultsFalseAndParsExplicit() {
        assertFalse(work("running").blocked)
        assertTrue(work("running", blocked = true).blocked)
    }
}

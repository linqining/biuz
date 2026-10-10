package cn.biuz.android.store

// 直译自 ios/UnitTests/TaskNotificationContentTests.swift（6 用例 → 6 个 @Test）。
// 通知内容构造纯函数单测（P1-7）：三类映射 / 去重键 / 摘要截断 / running 不投递。
//
// 与既有 TaskNotificationContentTest.kt（M7 实装批随 NotificationService.swift 锁面
// 平移，方法名 make_*）互为补充：本文件按 iOS 独立测试文件逐用例对位平移，
// 保留 iOS 方法名与逐条断言语义（含 G-016② 去重键三元组编号）。
// 机械适配（逐处注明）：
// - XCTest 断言 → kotlin.test；Optional 链 `content?.x == true` → assertNotNull
//   契约收窄后直读（断言对象与次序与 iOS 一致）；
// - String(repeating:count:) → String.repeat(n)（CJK 均为 BMP 单码元，
//   Swift Character.count 与 Kotlin length 在本文件向量上等值）；
// - Swift dropFirst(prefix.count) → String.drop(prefix.length)。

import cn.biuz.android.protocol.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskNotificationContentTests {

    @Test
    fun testDoneMappingWithChangeSummary() {
        val content = TaskNotificationContent.make(
            taskID = "task-1", taskTitle = "重构会话持久层",
            status = TaskStatus.done, changeSummary = "文件 3 · +120/-40",
        )
        assertNotNull(content, "done 状态应产生通知")
        assertEquals("任务完成", content.title)
        assertTrue(content.body.contains("重构会话持久层"), "正文应带任务标题")
        assertTrue(content.body.contains("文件 3 · +120/-40"), "正文应带变更规模摘要")
        // G-016②：去重键升级为三元组 (status,taskId,requestId)；requestID 缺省段 "-"
        assertEquals("(done,task-1,-)", content.dedupKey, "去重键应为 (status,taskId,requestId缺省-)")
        assertEquals("task-1", content.taskID)
    }

    @Test
    fun testWaitingMappingPendingApproval() {
        val content = TaskNotificationContent.make(
            taskID = "task-2", taskTitle = "执行数据库迁移",
            status = TaskStatus.waiting, changeSummary = null,
        )
        assertNotNull(content, "waiting（待审批）状态应产生通知")
        assertEquals("等待你的批准", content.title)
        assertTrue(content.body.contains("待审批"))
        assertEquals("(waiting,task-2,-)", content.dedupKey)
    }

    @Test
    fun testFailedMapping() {
        val content = TaskNotificationContent.make(
            taskID = "task-3", taskTitle = "同步镜像仓库",
            status = TaskStatus.failed, changeSummary = "fatal: unable to access …",
        )
        assertNotNull(content)
        assertEquals("任务失败", content.title)
        assertTrue(content.body.contains("执行失败"))
        assertEquals("(failed,task-3,-)", content.dedupKey)
    }

    @Test
    fun testRunningProducesNoNotification() {
        val content = TaskNotificationContent.make(
            taskID = "task-4", taskTitle = "进行中任务", status = TaskStatus.running, changeSummary = "…",
        )
        assertNull(content, "running 状态不产生通知（避免噪声）")
    }

    @Test
    fun testSummaryTruncatedAt80Chars() {
        val long = "长".repeat(200)
        val content = assertNotNull(
            TaskNotificationContent.make(
                taskID = "task-5", taskTitle = "长摘要任务", status = TaskStatus.done, changeSummary = long,
            ),
        )
        val body = content.body
        assertTrue(body.endsWith("…"), "截断应以省略号收尾")
        // 前缀「长摘要任务 已完成：」共 10 字 + 摘要恰为 80 字 + 省略号 = 91 字
        val prefix = "长摘要任务 已完成："
        val detail = body.drop(prefix.length)
        assertEquals(81, detail.length, "摘要应截断为 80 字 + 省略号")
    }

    @Test
    fun testEmptySummaryFallsBackToPrefixBody() {
        val content = TaskNotificationContent.make(
            taskID = "task-6", taskTitle = "无摘要任务", status = TaskStatus.done, changeSummary = "   ",
        )
        // iOS 原文 content?.body 可空链等值断言：null 与期望不等即失败（不断言 notNil）
        assertEquals("无摘要任务 已完成", content?.body, "空白摘要应退化为纯前缀正文")
    }
}

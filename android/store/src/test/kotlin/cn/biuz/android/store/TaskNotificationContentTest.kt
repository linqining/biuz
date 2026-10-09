package cn.biuz.android.store

// TaskNotificationContent 纯函数单测（G-016；iOS NotificationService.swift TaskNotificationContent
// UnitTests 锁面平移）：三类状态映射、running 不产生通知、摘要 80 字符截断、
// 去重键 "(status,taskId,requestId)" 与路由键 identifier、waiting 类时效性。

import cn.biuz.android.protocol.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskNotificationContentTest {

    @Test
    fun make_threeStatusMappings() {
        val done = TaskNotificationContent.make(
            taskID = "sess-a",
            taskTitle = "移植工作流",
            status = TaskStatus.done,
            changeSummary = "文件 3 · +120/-40",
        )
        assertNotNull(done)
        assertEquals("任务完成", done.title)
        assertEquals("移植工作流 已完成：文件 3 · +120/-40", done.body)
        assertFalse(done.timeSensitive, "完成类非常驻提醒（G-016 时效性仅 waiting）")
        assertNull(done.requestID)

        val failed = TaskNotificationContent.make("sess-b", "发布任务", TaskStatus.failed, "命令退出码 1")
        assertNotNull(failed)
        assertEquals("任务失败", failed.title)
        assertEquals("发布任务 执行失败：命令退出码 1", failed.body)

        val waiting = TaskNotificationContent.make(
            taskID = "sess-c",
            taskTitle = "审批流",
            status = TaskStatus.waiting,
            changeSummary = null,
            requestID = "req-9",
        )
        assertNotNull(waiting)
        assertEquals("等待你的批准", waiting.title)
        assertEquals("审批流 有操作待审批", waiting.body, "无摘要时正文仅前缀")
        assertTrue(waiting.timeSensitive, "G-016：审批/提问类时效性通知（waiting）")
        assertEquals("req-9", waiting.requestID)
    }

    @Test
    fun make_runningYieldsNull() {
        assertNull(
            TaskNotificationContent.make("sess-d", "跑着", TaskStatus.running, "x"),
            "running 不产生通知（iOS :46 同口径）",
        )
    }

    @Test
    fun make_summaryTruncatedAt80() {
        val long = "长".repeat(120)
        val content = TaskNotificationContent.make("sess-e", "任务", TaskStatus.done, long)
        assertNotNull(content)
        // 80 字符截断 + 省略号收尾；正文 = 前缀：摘要
        assertTrue(content.body.endsWith("…"))
        // 前缀（任务 已完成）不含省略号 → 摘要段恰 81 字符（80+…）
        val detail = content.body.removePrefix("任务 已完成：")
        assertEquals(81, detail.length)
    }

    @Test
    fun make_dedupKeyAndIdentifier() {
        val content = TaskNotificationContent.make(
            taskID = "sess-f",
            taskTitle = "任务",
            status = TaskStatus.waiting,
            changeSummary = " ",
            requestID = "req-7",
        )
        assertNotNull(content)
        // changeSummary 空白 trim 后为空 → 正文退前缀
        assertEquals("任务 有操作待审批", content.body)
        assertEquals("(waiting,sess-f,req-7)", content.dedupKey, "(status,taskId,requestId) 去重键")
        assertEquals("task-sess-f-waiting-req-7", content.identifier, "同 id 系统级替换去重的路由键")
        val noRequest = TaskNotificationContent.make("sess-f", "任务", TaskStatus.done, null, null)
        assertEquals("(done,sess-f,-)", noRequest!!.dedupKey, "requestId 缺席以 '-' 占位")
        assertEquals("task-sess-f-done-na", noRequest.identifier)
    }
}

import XCTest
@testable import ZCodeMobile

/// 通知内容构造纯函数单测（P1-7）：三类映射 / 去重键 / 摘要截断 / running 不投递。
final class TaskNotificationContentTests: XCTestCase {

    func testDoneMappingWithChangeSummary() {
        let content = TaskNotificationContent.make(
            taskID: "task-1", taskTitle: "重构会话持久层",
            status: .done, changeSummary: "文件 3 · +120/-40")
        XCTAssertNotNil(content, "done 状态应产生通知")
        XCTAssertEqual(content?.title, "任务完成")
        XCTAssertTrue(content?.body.contains("重构会话持久层") == true, "正文应带任务标题")
        XCTAssertTrue(content?.body.contains("文件 3 · +120/-40") == true, "正文应带变更规模摘要")
        // G-016②：去重键升级为三元组 (status,taskId,requestId)；requestID 缺省段 "-"
        XCTAssertEqual(content?.dedupKey, "(done,task-1,-)", "去重键应为 (status,taskId,requestId缺省-)")
        XCTAssertEqual(content?.taskID, "task-1")
    }

    func testWaitingMappingPendingApproval() {
        let content = TaskNotificationContent.make(
            taskID: "task-2", taskTitle: "执行数据库迁移",
            status: .waiting, changeSummary: nil)
        XCTAssertNotNil(content, "waiting（待审批）状态应产生通知")
        XCTAssertEqual(content?.title, "等待你的批准")
        XCTAssertTrue(content?.body.contains("待审批") == true)
        XCTAssertEqual(content?.dedupKey, "(waiting,task-2,-)")
    }

    func testFailedMapping() {
        let content = TaskNotificationContent.make(
            taskID: "task-3", taskTitle: "同步镜像仓库",
            status: .failed, changeSummary: "fatal: unable to access …")
        XCTAssertNotNil(content)
        XCTAssertEqual(content?.title, "任务失败")
        XCTAssertTrue(content?.body.contains("执行失败") == true)
        XCTAssertEqual(content?.dedupKey, "(failed,task-3,-)")
    }

    func testRunningProducesNoNotification() {
        let content = TaskNotificationContent.make(
            taskID: "task-4", taskTitle: "进行中任务", status: .running, changeSummary: "…")
        XCTAssertNil(content, "running 状态不产生通知（避免噪声）")
    }

    func testSummaryTruncatedAt80Chars() {
        let long = String(repeating: "长", count: 200)
        let content = TaskNotificationContent.make(
            taskID: "task-5", taskTitle: "长摘要任务", status: .done, changeSummary: long)
        XCTAssertNotNil(content)
        let body = content?.body ?? ""
        XCTAssertTrue(body.hasSuffix("…"), "截断应以省略号收尾")
        // 前缀「长摘要任务 已完成：」共 10 字 + 摘要恰为 80 字 + 省略号 = 91 字
        let prefix = "长摘要任务 已完成："
        let detail = body.dropFirst(prefix.count)
        XCTAssertEqual(detail.count, 81, "摘要应截断为 80 字 + 省略号")
    }

    func testEmptySummaryFallsBackToPrefixBody() {
        let content = TaskNotificationContent.make(
            taskID: "task-6", taskTitle: "无摘要任务", status: .done, changeSummary: "   ")
        XCTAssertEqual(content?.body, "无摘要任务 已完成", "空白摘要应退化为纯前缀正文")
    }
}

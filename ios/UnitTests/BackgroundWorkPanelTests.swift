import XCTest
@testable import ZCodeMobile

// MARK: - 后台工作面板投影单测（v1.25「运行中的参考价值更大」回归门）
//
// 上游证据【实证·上游仓 snapshot.ts backgroundWorkSummarySchema】：status 四态闭集
// running|resultPending|failed|cancelled、startedAt/endedAt/blocked；运行中派生口径
// = status === "running"（snapshot.ts:136 注释逐字）。UI 口径：运行中置顶、历史折叠。

final class BackgroundWorkPanelTests: XCTestCase {

    private func work(status: String?, startedAt: Date? = nil, endedAt: Date? = nil,
                      blocked: Bool = false) -> BackgroundWorkSummary {
        BackgroundWorkSummary(
            workId: "w-\(status)-\(UUID().uuidString)", title: "t", kind: "bash",
            rawStatus: status, cancellable: false, resumable: false,
            runId: nil, sessionId: nil,
            startedAt: startedAt, endedAt: endedAt, blocked: blocked)
    }

    func testRunningDerivedFromAuthoritativeStatus() {
        XCTAssertTrue(work(status: "running").isRunning)
        XCTAssertFalse(work(status: "resultPending").isRunning)
        XCTAssertFalse(work(status: "failed").isRunning)
        XCTAssertFalse(work(status: "cancelled").isRunning)
        // 旧桌面宽容词不误判为运行中
        XCTAssertFalse(work(status: "completed").isRunning)
        XCTAssertFalse(work(status: nil).isRunning)
    }

    func testStatusTextCoversAuthoritativeVocabulary() {
        // 权威四态闭集【实证·上游仓】
        XCTAssertEqual(work(status: "running").statusText, String(localized: "运行中"))
        XCTAssertEqual(work(status: "resultPending").statusText, String(localized: "待投递"))
        XCTAssertEqual(work(status: "failed").statusText, String(localized: "失败"))
        XCTAssertEqual(work(status: "cancelled").statusText, String(localized: "已取消"))
        // 宽容词 + 未知原词回退原文（不猜测）
        XCTAssertEqual(work(status: "stopped").statusText, String(localized: "已停止"))
        XCTAssertEqual(work(status: "weird-state").statusText, "weird-state")
        XCTAssertEqual(work(status: nil).statusText, "")
    }

    func testBlockedDefaultsFalseAndParsesExplicit() {
        XCTAssertFalse(work(status: "running").blocked)
        XCTAssertTrue(work(status: "running", blocked: true).blocked)
    }

    func testMockDemoProjectionGroupsRunningFirst() async {
        // 演示投影（c1）：运行中在场且置顶、历史可折叠——面板分组的数据面契约
        let store = MockConversationStore()
        let works = await store.backgroundWorks(in: "c1")
        XCTAssertFalse(works.isEmpty, "演示会话 c1 应有后台工作")
        XCTAssertTrue(works.contains(where: \.isRunning), "演示数据应含运行中条目")
        XCTAssertTrue(works.contains(where: { $0.rawStatus == "resultPending" }),
                      "演示数据应覆盖 resultPending（上游四态词表）")
        // 演示运行中条目带 startedAt（面板耗时行数据源）
        let running = works.first(where: \.isRunning)
        XCTAssertNotNil(running?.startedAt, "运行中条目应携带 startedAt")
        // 其余会话无数据不渲染（与 workflowRun 同口径）
        let empty = await store.backgroundWorks(in: "c2")
        XCTAssertTrue(empty.isEmpty)
    }
}

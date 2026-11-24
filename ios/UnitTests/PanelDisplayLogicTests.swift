import XCTest
@testable import ZCodeMobile

// MARK: - 面板显示逻辑（2026-11-24 与 Android 同步的两处判定）
//
// 1) 工作流卡标题：桌面 run.name 实测恒为「工作流」（或缺席）→ 多 run 时一排同名卡；
//    名字为空或等于通用名时按序号显示「运行 N」。
// 2) 死 run 不渲染控制条（amend/并发对已结束 run 桌面必拒）——由 `run.isLive` 门控，
//    这里只锁标题判定，控制条门控是同一条件的直接使用。

final class PanelDisplayLogicTests: XCTestCase {

    private func run(name: String, status: String = "completed") -> WorkflowRunSummary {
        WorkflowRunSummary(id: "run-1", name: name, rawStatus: status, stopReason: nil, resumable: false, truncated: false, nodes: [], actors: [])
    }

    func testGenericNameFallsBackToOrdinal() {
        XCTAssertEqual(
            WorkflowPanelContent.displayRunName(run(name: "工作流"), ordinal: 2), "运行 2",
            "名字等于通用名 → 按序号显示")
        XCTAssertEqual(
            WorkflowPanelContent.displayRunName(run(name: ""), ordinal: 3), "运行 3",
            "名字缺席 → 按序号显示")
        XCTAssertEqual(
            WorkflowPanelContent.displayRunName(run(name: "   "), ordinal: 1), "运行 1",
            "纯空白也算缺席")
    }

    func testRealNameIsKept() {
        XCTAssertEqual(
            WorkflowPanelContent.displayRunName(run(name: "竞品分析"), ordinal: 4), "竞品分析",
            "真名字照常显示（不被序号覆盖）")
    }

    func testNoOrdinalFallsBackToGeneric() {
        XCTAssertEqual(
            WorkflowPanelContent.displayRunName(run(name: ""), ordinal: nil), "工作流",
            "无序号时的兜底")
    }
}

import XCTest
@testable import ZCodeMobile

// MARK: - 工作流阶段行归属单测（v1.24「点击工作流某项不展开」根修回归门）
//
// 上游证据【实证·上游仓】：phase-name.ts phaseNameMatches（精确优先、display 顶到
// 128 截断上界才前缀兜底）+ instance-phases.ts phasesOf（无戳实例有词汇归「未分组」
// 站、无词汇归全部站；匹配为空归全部站）+ WorkflowRunPhaseList.tsx（阶段行无条件
// 可展开）。此前的精确相等匹配 + 有实例才可展开，是无戳/截断戳实例永无归站、
// 部分阶段行点击无反应的根因。

final class WorkflowActorBindingTests: XCTestCase {

    private func node(_ id: String, _ label: String,
                      status: WorkflowStepStatus = .pending) -> WorkflowNodeSummary {
        WorkflowNodeSummary(id: id, label: label, status: status)
    }

    private func actor(_ id: String, phaseName: String?, rawStatus: String = "running",
                       sessionId: String? = nil) -> WorkflowActorSummary {
        WorkflowActorSummary(
            id: id, name: "actor-\(id)", rawStatus: rawStatus,
            phaseName: phaseName, sessionId: sessionId, tasksTotal: nil, tasksSettled: nil)
    }

    // MARK: phaseNameMatches（上游 phase-name.ts 逐字移植）

    func testPhaseNameMatchesExactPriority() {
        XCTAssertTrue(WorkflowRunSummary.phaseNameMatches("计划", "计划"))
        // 128 上界内不做前缀兜底：「计划」不得误认「计划修复」（上游注释原话）
        XCTAssertFalse(WorkflowRunSummary.phaseNameMatches("计划", "计划修复"))
    }

    func testPhaseNameMatchesPrefixOnlyAtTruncationBound() {
        // 截断方向：display 名被截到 128 上界，运行时出生戳带全名（phase-name.ts 注释）
        let runtime = String(repeating: "长", count: 200)
        let display = String(runtime.prefix(128))
        // display 顶到 128 上界（截断真的发生过）→ 前缀兜底放行
        XCTAssertTrue(WorkflowRunSummary.phaseNameMatches(display, runtime))
        XCTAssertTrue(WorkflowRunSummary.phaseNameMatches(runtime, runtime))
        // 未顶到上界 → 前缀一律不放行（「计划」不得误认「计划修复」同族）
        let shortDisplay = String(runtime.prefix(127))
        XCTAssertFalse(WorkflowRunSummary.phaseNameMatches(shortDisplay, runtime))
    }

    // MARK: actors(boundTo:)（上游 phasesOf 三分支移植）

    func testBoundByExactStamp() {
        let run = WorkflowRunSummary(
            id: "r1", name: "run", rawStatus: "running", stopReason: nil, resumable: false,
            truncated: false,
            nodes: [node("s0", "开发"), node("s1", "验收")],
            actors: [
                actor("a1", phaseName: "开发"),
                actor("a2", phaseName: "验收", rawStatus: "completed"),
            ],
            hasPhaseVocabulary: true)
        XCTAssertEqual(run.actors(boundTo: run.nodes[0]).map(\.id), ["a1"])
        XCTAssertEqual(run.actors(boundTo: run.nodes[1]).map(\.id), ["a2"])
    }

    func testUnphasedActorsGoToUnphasedStationWhenVocabularyExists() {
        let run = WorkflowRunSummary(
            id: "r1", name: "run", rawStatus: "running", stopReason: nil, resumable: false,
            truncated: false,
            nodes: [node(WorkflowRunSummary.unphasedStationID, "未分组"), node("s1", "开发")],
            actors: [
                actor("a1", phaseName: nil),
                actor("a2", phaseName: "开发"),
            ],
            hasPhaseVocabulary: true)
        // 有词汇：无戳实例只归「未分组」站，不得广播到具名站（上游 phasesOf 同构）
        XCTAssertEqual(run.actors(boundTo: run.nodes[0]).map(\.id), ["a1"])
        XCTAssertEqual(run.actors(boundTo: run.nodes[1]).map(\.id), ["a2"])
    }

    func testUnphasedActorsBroadcastWhenNoVocabulary() {
        let run = WorkflowRunSummary(
            id: "r1", name: "run", rawStatus: "running", stopReason: nil, resumable: false,
            truncated: false,
            nodes: [node("s0", "开发"), node("s1", "验收")],
            actors: [actor("a1", phaseName: nil)],
            hasPhaseVocabulary: false)
        // 无词汇：无戳 ↔ 全部站是同一事实的两面（上游「任一分支结果为空 → 全部阶段」）
        XCTAssertEqual(run.actors(boundTo: run.nodes[0]).map(\.id), ["a1"])
        XCTAssertEqual(run.actors(boundTo: run.nodes[1]).map(\.id), ["a1"])
    }

    func testOrphanActorsFallBackToAllStations() {
        let run = WorkflowRunSummary(
            id: "r1", name: "run", rawStatus: "running", stopReason: nil, resumable: false,
            truncated: false,
            nodes: [node("s0", "站A"), node("s1", "站B")],
            actors: [actor("a1", phaseName: "不存在的站")],
            hasPhaseVocabulary: true)
        // 戳不匹配任何站 → 归全部站（宁重复不藏）
        XCTAssertEqual(run.actors(boundTo: run.nodes[0]).map(\.id), ["a1"])
        XCTAssertEqual(run.actors(boundTo: run.nodes[1]).map(\.id), ["a1"])
    }

    // MARK: parseWorkflowRun 合成站（无站/无戳兜底）

    func testParseSynthesizesImplicitStationWhenNoStationsButActors() {
        let json = JSONValue.object([
            "runId": .string("dwfrun-1"),
            "name": .string("wf"),
            "status": .string("running"),
            "actors": .array([.object([
                "siteId": .string("site"), "ordinal": .int(1),
                "status": .string("running"),
                // 旧 CLI：无 phaseName 戳、无 nodes/phases
            ])]),
        ])
        let run = RemoteConversationStore.parseWorkflowRun(json)
        XCTAssertNotNil(run)
        XCTAssertEqual(run?.nodes.count, 1, "无站有实例应合成隐式单站承接")
        XCTAssertEqual(run?.nodes.first?.status, .running)
        XCTAssertEqual(run?.actors(boundTo: run!.nodes[0]).count, 1)
    }

    func testParseSynthesizesUnphasedStationForUnstampedActors() {
        let json = JSONValue.object([
            "runId": .string("dwfrun-2"),
            "name": .string("wf"),
            "status": .string("running"),
            "phaseNames": .array([.string("开发"), .string("验收")]),
            "nodes": .array([.object([
                "phaseName": .string("开发"), "phase": .string("executing"),
            ])]),
            "actors": .array([
                .object([
                    "siteId": .string("site"), "ordinal": .int(1),
                    "status": .string("running"), "phaseName": .string("开发"),
                ]),
                .object([
                    "siteId": .string("site"), "ordinal": .int(2),
                    "status": .string("running"),  // 无戳：首个标记前出生/旧 CLI
                ]),
            ]),
        ])
        let run = RemoteConversationStore.parseWorkflowRun(json)
        XCTAssertNotNil(run)
        XCTAssertTrue(run?.hasPhaseVocabulary == true)
        XCTAssertEqual(run?.nodes.first?.id, WorkflowRunSummary.unphasedStationID,
                       "有词汇且有无戳实例应在表头合成「未分组」站")
        XCTAssertEqual(run?.nodes.count, 3, "未分组站 + 两个声明站")
        let unphased = run?.actors(boundTo: run!.nodes[0]) ?? []
        let dev = run?.actors(boundTo: run!.nodes[1]) ?? []
        XCTAssertEqual(unphased.count, 1, "无戳实例只归未分组站")
        XCTAssertEqual(dev.count, 1, "有戳实例归具名站")
    }
}

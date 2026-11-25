import XCTest
@testable import ZCodeMobile

// MARK: - 缺口补齐轮单测（2026-11-25，design/web-gap-list.md G25/G21/G23/G33/G49/G28/G50）
//
// 覆盖本轮七块新逻辑的解析/派生纯函数面：
// ① parseWorkflowRun G25 新字段（血缘/终态/用量/并发对象形态）
// ② parseInteraction G21 freeText / G23 pluginInstall / G33 autoResolution
// ③ Conversation.pendingCountdownProgress（G33 静默期/snoozed 不画环）
// ④ ChatScrollMemory LRU 200（G49 容量/touch）
// ⑤ SubagentAvatarItem.badge（G28 停止/失败不回退 Running）
// ⑥ stopReasonText 词表（G25）
// ⑦ 草稿持久化 roundtrip（G50）

final class GapParityRoundTests: XCTestCase {

    // MARK: ① parseWorkflowRun G25 新字段【实证·上游仓 workflow-runs.ts:419-583】

    func testParseWorkflowRunG25Fields() {
        let json = JSONValue.object([
            "runId": .string("run-g25"),
            "name": .string("对齐审查"),
            "status": .string("errored"),
            "stopReason": .string("provider"),
            "resumedFrom": .string("run-parent"),
            "supersededBy": .string("run-child"),
            "error": .string("boom: provider unavailable"),
            "resultPreview": .string("预览文本"),
            "usage": .object(["spentTokens": .int(1200), "nodesUsed": .int(4)]),
            "concurrency": .object([
                "cap": .int(8), "ceiling": .int(16),
                "limit": .int(4), "cooldownMs": .int(30000),
            ]),
            "failureCode": .string("script_missing"),
            "nodes": .array([]),
            "actors": .array([.object([
                "siteId": .string("s1"), "ordinal": .int(1),
                "status": .string("running"), "subagentType": .string("explore"),
            ])]),
        ])
        let run = RemoteConversationStore.parseWorkflowRun(json)
        XCTAssertNotNil(run)
        XCTAssertEqual(run?.resumedFrom, "run-parent")
        XCTAssertEqual(run?.supersededBy, "run-child")
        XCTAssertEqual(run?.error, "boom: provider unavailable")
        XCTAssertEqual(run?.resultPreview, "预览文本")
        XCTAssertEqual(run?.usageNodesUsed, 4)
        XCTAssertEqual(run?.concurrencyCap, 8)
        XCTAssertEqual(run?.concurrencyCooldownMs, 30000)
        XCTAssertNotNil(run?.concurrencyCooldownUntil)
        XCTAssertEqual(run?.concurrencyLimit, 4)
        XCTAssertEqual(run?.failureCode, "script_missing")
    }

    func testParseWorkflowRunG25FieldsAbsentOnLegacyDesktop() {
        // 旧桌面缺键 → 全 nil，冷却不渲染（UI 分支不渲染承载）
        let json = JSONValue.object([
            "runId": .string("run-old"),
            "status": .string("completed"),
            "nodes": .array([]),
            "actors": .array([.object([
                "siteId": .string("s1"), "ordinal": .int(1), "status": .string("completed"),
            ])]),
        ])
        let run = RemoteConversationStore.parseWorkflowRun(json)
        XCTAssertNotNil(run)
        XCTAssertNil(run?.error)
        XCTAssertNil(run?.resultPreview)
        XCTAssertNil(run?.resumedFrom)
        XCTAssertNil(run?.supersededBy)
        XCTAssertNil(run?.usageNodesUsed)
        XCTAssertNil(run?.concurrencyCap)
        XCTAssertNil(run?.concurrencyCooldownMs)
        XCTAssertNil(run?.concurrencyCooldownUntil)
    }

    // MARK: ⑥ stopReason 文案词表（web WorkflowRunSidePaneSections.tsx:432-440 同表）

    func testStopReasonTextVocabulary() {
        XCTAssertEqual(WorkflowRunSummary.stopReasonText("user"), String(localized: "手动停止"))
        XCTAssertEqual(WorkflowRunSummary.stopReasonText("model"), String(localized: "模型停止"))
        XCTAssertEqual(WorkflowRunSummary.stopReasonText("provider"), String(localized: "供应商中断"))
        XCTAssertEqual(WorkflowRunSummary.stopReasonText("interrupted"), String(localized: "已中断"))
        XCTAssertEqual(WorkflowRunSummary.stopReasonText("superseded"), String(localized: "已被新运行替代"))
        XCTAssertNil(WorkflowRunSummary.stopReasonText(nil))
        XCTAssertNil(WorkflowRunSummary.stopReasonText(""))
        // 词表外原词透传（不猜）
        XCTAssertEqual(WorkflowRunSummary.stopReasonText("weird"), "weird")
    }

    // MARK: ② parseInteraction G21/G23/G33

    func testParseInteractionPermissionFreeTextFlag() {
        let json = JSONValue.object([
            "interactionId": .string("i1"),
            "kind": .string("permission"),
            "payload": .object(["freeText": .bool(true), "command": .string("git push")]),
        ])
        let interaction = RemoteConversationStore.parseInteraction(json)
        XCTAssertEqual(interaction?.permissionFreeText, true)
        XCTAssertFalse(interaction?.isPluginInstall ?? true)
    }

    func testParseInteractionPermissionFreeTextAbsentDefaultsFalse() {
        let json = JSONValue.object([
            "interactionId": .string("i2"),
            "kind": .string("permission"),
            "payload": .object([:]),
        ])
        XCTAssertEqual(RemoteConversationStore.parseInteraction(json)?.permissionFreeText, false)
    }

    func testParseInteractionPluginInstallFields() {
        // schema=pluginInstall.ts pluginInstallRequestPayloadSchema【实证·上游仓】
        let json = JSONValue.object([
            "interactionId": .string("i3"),
            "kind": .string("pluginInstall"),
            "payload": .object([
                "kind": .string("pluginInstall"),
                "pluginId": .string("ccgithub"),
                "name": .string("ccgithub"),
                "displayName": .string("CC GitHub"),
                "description": .string("GitHub 集成"),
                "reason": .string("模型请求安装"),
                "action": .string("install"),
                "scope": .string("workspace"),
                "toolCallId": .string("t1"),
            ]),
        ])
        let interaction = RemoteConversationStore.parseInteraction(json)
        XCTAssertNotNil(interaction)
        XCTAssertTrue(interaction?.isPluginInstall ?? false)
        XCTAssertEqual(interaction?.pluginName, "CC GitHub")
        XCTAssertEqual(interaction?.pluginDescription, "GitHub 集成")
        XCTAssertEqual(interaction?.pluginReason, "模型请求安装")
        XCTAssertEqual(interaction?.pluginAction, "install")
        XCTAssertEqual(interaction?.pluginScope, "workspace")
    }

    func testParseInteractionAutoResolutionBothStates() {
        // 两态【实证·上游仓 snapshot.ts:329-341】：hiddenGrace/visibleCountdown 带
        // deadlineAt/visibleAt；snoozed 只带 snoozedAt
        let json = JSONValue.object([
            "interactionId": .string("i4"),
            "kind": .string("userInput"),
            "payload": .object([
                "prompt": .string("继续吗？"),
                "autoResolution": .object([
                    "state": .string("visibleCountdown"),
                    "startedAt": .int(1_700_000_000_000),
                    "visibleAt": .int(1_700_000_000_000),
                    "deadlineAt": .int(1_700_000_030_000),
                ]),
            ]),
        ])
        let interaction = RemoteConversationStore.parseInteraction(json)
        XCTAssertEqual(interaction?.autoResolutionState, "visibleCountdown")
        XCTAssertNotNil(interaction?.autoResolutionDeadline)
        XCTAssertNotNil(interaction?.autoResolutionVisibleAt)
        XCTAssertEqual(interaction?.canSnoozeCountdown, true)

        let snoozed = JSONValue.object([
            "interactionId": .string("i5"),
            "kind": .string("userInput"),
            "payload": .object([
                "prompt": .string("继续吗？"),
                "autoResolution": .object([
                    "state": .string("snoozed"),
                    "startedAt": .int(1_700_000_000_000),
                    "snoozedAt": .int(1_700_000_010_000),
                ]),
            ]),
        ])
        let snoozedInteraction = RemoteConversationStore.parseInteraction(snoozed)
        XCTAssertEqual(snoozedInteraction?.autoResolutionState, "snoozed")
        XCTAssertNil(snoozedInteraction?.autoResolutionDeadline)
        XCTAssertNil(snoozedInteraction?.countdownProgress)
    }

    // MARK: ③ Conversation.pendingCountdownProgress（G33）

    func testPendingCountdownProgressStates() {
        func conversation(deadline: Date?, visible: Date?, snoozed: Date?,
                          kind: String = "userInput") -> Conversation {
            var c = Conversation(
                id: "c1", title: "t", summary: "", directory: "",
                updatedAt: Date())
            c.pendingInteractionKind = kind
            c.pendingInteractionDeadline = deadline
            c.pendingInteractionVisibleAt = visible
            c.pendingInteractionSnoozedAt = snoozed
            return c
        }
        let now = Date()
        let visible = now.addingTimeInterval(-60)
        let deadline = now.addingTimeInterval(60)
        // 正常倒计时：remaining/span = 60/120 = 0.5
        let progress = conversation(deadline: deadline, visible: visible, snoozed: nil)
            .pendingCountdownProgress
        XCTAssertNotNil(progress)
        XCTAssertEqual(progress!, 0.5, accuracy: 0.01)
        // 静默期（visibleAt 在未来）→ nil（web :44-58 静默期不画环）
        XCTAssertNil(
            conversation(deadline: deadline, visible: now.addingTimeInterval(60), snoozed: nil)
                .pendingCountdownProgress)
        // snoozed → nil
        XCTAssertNil(
            conversation(deadline: deadline, visible: visible, snoozed: now).pendingCountdownProgress)
        // permission → nil（web :21-31 恒 false）
        XCTAssertNil(
            conversation(deadline: deadline, visible: visible, snoozed: nil, kind: "permission")
                .pendingCountdownProgress)
    }

    // MARK: ④ ChatScrollMemory LRU（G49）

    @MainActor
    func testScrollMemoryLRUEvictionAndTouch() {
        let memory = ChatScrollMemory()
        // 填满 + 溢出 5
        for i in 0..<(ChatScrollMemory.capacity + 5) {
            memory.save(key: "k\(i)", messageId: "m\(i)")
        }
        // 最旧 5 个被驱逐
        XCTAssertNil(memory.load(key: "k0"))
        XCTAssertNil(memory.load(key: "k4"))
        // 新的还在
        XCTAssertEqual(memory.load(key: "k\(ChatScrollMemory.capacity + 4)"), "m\(ChatScrollMemory.capacity + 4)")
        // 读即 touch：k5 读过后再灌 1 条，k5 不被驱逐（k6 才是）
        _ = memory.load(key: "k5")
        memory.save(key: "fresh", messageId: "mf")
        XCTAssertEqual(memory.load(key: "k5"), "m5")
        XCTAssertNil(memory.load(key: "k6"))
    }

    @MainActor
    func testScrollMemoryKeyUsesIdentityOverPath() {
        func workspace(identity: String?, path: String) -> ServerWorkspaceInfo {
            ServerWorkspaceInfo(path: path, label: nil, workspaceIdentity: identity)
        }
        XCTAssertEqual(
            ChatScrollMemory.key(workspace: workspace(identity: "ident-1", path: "/a"), conversationID: "s1"),
            "ident-1::session:s1")
        XCTAssertEqual(
            ChatScrollMemory.key(workspace: workspace(identity: nil, path: "/a"), conversationID: "s1"),
            "/a::session:s1")
    }

    // MARK: ⑤ SubagentAvatarItem.badge（G28）

    func testAvatarBadgeStoppedDoesNotFallBackToRunning() {
        // web :1218-1224 注释同规：停止/失败不能回退 Running（否则画等待三点）
        XCTAssertEqual(
            SubagentAvatarItem(
                childSessionId: "a", name: "explore", status: .done, result: .completed
            ).badge, .done)
        XCTAssertEqual(
            SubagentAvatarItem(
                childSessionId: "b", name: "explore", status: .done, result: .stopped
            ).badge, .done)
        XCTAssertEqual(
            SubagentAvatarItem(
                childSessionId: "c", name: "explore", status: .failed, result: .failed
            ).badge, .failed)
        XCTAssertEqual(
            SubagentAvatarItem(
                childSessionId: "d", name: "explore", status: .running, result: nil
            ).badge, .running)
    }

    // MARK: ⑦ 草稿持久化（G50）

    func testDraftPersistenceRoundTrip() {
        let id = "draft-test-\(UUID().uuidString)"
        defer { UserDefaults.standard.removeObject(forKey: "zcode.composer.draft.v1.\(id)") }
        XCTAssertTrue(ChatViewModel.loadPersistedDraft(conversationID: id).isEmpty)
        ChatViewModel.persistDraft("草稿草稿", conversationID: id)
        XCTAssertEqual(ChatViewModel.loadPersistedDraft(conversationID: id), "草稿草稿")
        // 空白即删键（web composerDraftStore.ts:389-393 同口径）
        ChatViewModel.persistDraft("   ", conversationID: id)
        XCTAssertTrue(ChatViewModel.loadPersistedDraft(conversationID: id).isEmpty)
    }

@MainActor
func testCommandCenterHistoryRoundTripAndDedup() {
    let workspace = ServerWorkspaceInfo(path: "/ws/g38", label: nil, workspaceIdentity: "ident-g38")
    let key = CommandCenterHistory.key(workspace: workspace)
    // 键词面：prefix + identity（ChatScrollMemory.key 同推导，不带 conversation 段）
    XCTAssertTrue(key.hasPrefix("zcode-command-center-search-history:"))
    XCTAssertTrue(key.contains("ident-g38"))
    defer { UserDefaults.standard.removeObject(forKey: key) }

    XCTAssertTrue(CommandCenterHistory.read(workspace: workspace).isEmpty)
    // 记录三条 → 最新在前
    CommandCenterHistory.record(.init(scope: "all", query: "登录", updatedAt: 1), workspace: workspace)
    CommandCenterHistory.record(.init(scope: "files", query: "main.swift", updatedAt: 2), workspace: workspace)
    CommandCenterHistory.record(.init(scope: "all", query: "重连", updatedAt: 3), workspace: workspace)
    let entries = CommandCenterHistory.read(workspace: workspace)
    XCTAssertEqual(entries.count, 3)
    XCTAssertEqual(entries.first?.query, "重连")
    XCTAssertEqual(entries.last?.query, "登录")
    // 同 scope+query 去重置顶（不产生重复行）
    CommandCenterHistory.record(.init(scope: "all", query: "登录", updatedAt: 4), workspace: workspace)
    let deduped = CommandCenterHistory.read(workspace: workspace)
    XCTAssertEqual(deduped.count, 3)
    XCTAssertEqual(deduped.first?.query, "登录")
    XCTAssertEqual(deduped.first?.updatedAt, 4)
}

@MainActor
func testCommandCenterHistoryLimit20() {
    let workspace = ServerWorkspaceInfo(path: "/ws/g38-limit", label: nil, workspaceIdentity: nil)
    let key = CommandCenterHistory.key(workspace: workspace)
    defer { UserDefaults.standard.removeObject(forKey: key) }
    for i in 0..<25 {
        CommandCenterHistory.record(
            .init(scope: "all", query: "query-\(i)", updatedAt: Double(i)),
            workspace: workspace)
    }
    let entries = CommandCenterHistory.read(workspace: workspace)
    XCTAssertEqual(entries.count, CommandCenterHistory.limit)
    XCTAssertEqual(entries.first?.query, "query-24") // 最新在前
    XCTAssertEqual(entries.last?.query, "query-5")   // 最旧 5 条被挤出
}
}


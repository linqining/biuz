import XCTest
@testable import ZCodeMobile

/// 新建会话 draft 交接链路单测（真机报障 2026-10-07「新建会话的模型和思考强度没有
/// 带到会话页面」回归面，v1.21）：sheet 交接箱（NewConversationHandoffBox）→
/// ChatViewModel 消费 → 首条 sendText.modelSelection 下发。XCUITest 驱动 sheet
/// 输入法时序脆弱（键盘落空/typeText 丢字），协议链路在此单测层锁死。
@MainActor
final class DraftHandoffModelSelectionTests: XCTestCase {

    /// 记账型 store：连接态语义（isReadOnly=true 驱动 slash 拦截），sendText 记账。
    private actor RecordingStore {
        nonisolated var isReadOnly: Bool { true }
        struct SendRecord {
            let text: String
            let requestedDelivery: String?
            let modelSelection: NewSessionModelSelection?
        }
        private(set) var sendRecords: [SendRecord] = []
        /// 非 nil 时 sendText 回执按此 status 返回（拒收路径用）
        var forcedStatus: String?
        private(set) var lastRejection: String?

        func record(text: String, requestedDelivery: String?, modelSelection: NewSessionModelSelection?) {
            sendRecords.append(SendRecord(
                text: text, requestedDelivery: requestedDelivery, modelSelection: modelSelection))
        }
        func setForcedStatus(_ status: String?) { forcedStatus = status }
        func setRejection(_ text: String?) { lastRejection = text }
        func rejectionText() -> String? { lastRejection }
    }

    private final class RecordingStoreHandle: ConversationStore, @unchecked Sendable {
        let store = RecordingStore()
        var isReadOnly: Bool { true }
        func conversations() async -> [Conversation] { [] }
        func observeConversations() -> AsyncStream<ConversationEvent> { AsyncStream { $0.finish() } }
        func messages(in conversationID: String) async -> [ChatMessage] { [] }
        func send(_ text: String, in conversationID: String) async -> Bool { false }
        func answerQuestion(_ reply: String, in conversationID: String, questionID: String) async {}
        func createConversation(title: String, directory: String, executor: ExecutorKind,
                                modelSelection: NewSessionModelSelection?) async -> Conversation {
            Conversation(id: "", title: title, summary: "", directory: directory, updatedAt: Date())
        }
        func setPinned(_ pinned: Bool, conversationID: String) async {}
        func setArchived(_ archived: Bool, conversationID: String) async {}
        func markRead(conversationID: String) async {}

        @discardableResult
        func sendWithAttachments(
            _ text: String, attachments: [OutgoingAttachment], requestedDelivery: String?,
            modelSelection: NewSessionModelSelection?,
            in conversationID: String) async -> Bool {
            await store.record(text: text, requestedDelivery: requestedDelivery,
                               modelSelection: modelSelection)
            if let status = await store.forcedStatus, status != "accepted" {
                let reason = "桌面端拒绝（client.\(status)）"
                await store.setRejection(reason)
                return false
            }
            await store.setRejection(nil)
            return true
        }

        func sendRejectionText(in conversationID: String) async -> String? {
            await store.rejectionText()
        }
    }

    override func tearDown() {
        NewConversationHandoffBox.take(for: "sess-draft-test")
        super.tearDown()
    }

    /// 交接箱 → VM 消费 → 首条 sendText 携 modelSelection（slash /plan 拦截分支）
    func testHandoffModelSelectionRidesFirstSendTextViaPlanSlash() async {
        let handle = RecordingStoreHandle()
        let selection = NewSessionModelSelection(
            providerId: "account:zai-individual-coding-plan", modelId: "GLM-5.3",
            reasoningLevel: "max")
        NewConversationHandoffBox.deposit(
            NewConversationHandoff(draftText: "/plan 替身回归", attachments: [],
                                   modelSelection: selection),
            for: "sess-draft-test")
        let vm = ChatViewModel(store: handle, conversationID: "sess-draft-test")
        guard case .some(.plan(let task)) = ChatViewModel.parseSlashIntent(vm.draft) else {
            return XCTFail("交接草稿应可解析出 plan 意图")
        }
        XCTAssertEqual(task, "替身回归")
        XCTAssertNotNil(vm.pendingNewSessionSelection, "交接的会话前选择应在 VM 消费后在案")
        let delivered = await vm.send()
        XCTAssertTrue(delivered, "发送应送达（记账 store 回 accepted）")
        let records = await handle.store.sendRecords
        XCTAssertEqual(records.count, 1, "应恰好一条 sendText")
        XCTAssertEqual(records.first?.text, "替身回归", "plan 任务文本应剥离 /plan 前缀")
        XCTAssertEqual(records.first?.modelSelection, selection,
                       "会话前选择应随首条 sendText 下发")
        let pendingAfterSend = vm.pendingNewSessionSelection
        XCTAssertNil(pendingAfterSend, "发送成功后交接选择应清除（一次性）")
    }

    /// 附件交接（同 handoff 通道）走普通 send 分支同样携带；发送失败保留待重试
    func testHandoffSelectionRetainedWhenSendRejected() async {
        let handle = RecordingStoreHandle()
        let selection = NewSessionModelSelection(
            providerId: "account:zai-individual-coding-plan", modelId: "GLM-5.3",
            reasoningLevel: "low")
        NewConversationHandoffBox.deposit(
            NewConversationHandoff(draftText: "普通文本", attachments: [],
                                   modelSelection: selection),
            for: "sess-draft-test")
        let vm = ChatViewModel(store: handle, conversationID: "sess-draft-test")
        await handle.store.setForcedStatus("rejected")
        let delivered = await vm.send()
        XCTAssertFalse(delivered, "rejected 回执应如实失败（禁假成功）")
        let pendingAfterFail = vm.pendingNewSessionSelection
        XCTAssertNotNil(pendingAfterFail, "发送失败应保留交接选择供重试")
        let rejection = await vm.lastSendRejectionText()
        XCTAssertNotNil(rejection, "拒因应在案供错误行上屏")
        let failedRecords = await handle.store.sendRecords
        XCTAssertEqual(failedRecords.first?.modelSelection, selection,
                       "失败一次的发送同样应已携带 modelSelection")
        // 重试（解除强制拒收）→ 送达 + 选择清除
        await handle.store.setForcedStatus(nil)
        let retried = await vm.send()
        XCTAssertTrue(retried)
        let records = await handle.store.sendRecords
        XCTAssertEqual(records.last?.modelSelection, selection, "重试仍携带会话前选择")
        let pendingAfterRetry = vm.pendingNewSessionSelection
        XCTAssertNil(pendingAfterRetry, "重试成功后清除")
    }
}

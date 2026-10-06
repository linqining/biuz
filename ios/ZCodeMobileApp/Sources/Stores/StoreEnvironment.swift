import SwiftUI

/// Store 环境键：`@Environment(\.conversationStore)` 读取。
/// SwiftUI 的 `@Environment(T.self)` 只接受具体 Observable 类型，
/// 协议存在类型（any Store）经由 @Entry 环境值注入（SDK 27 的
/// EnvironmentKey 协议要求 _valuesEqual，手写 conformance 不再可行）。
/// 默认值给空实现实例（未注入时视图不崩、无假数据——设计稿 §0.4）。
extension EnvironmentValues {
    @Entry var conversationStore: any ConversationStore = EmptyConversationStore()
    @Entry var taskStore: any TaskStore = EmptyTaskStore()
    @Entry var fileStore: any FileStore = EmptyFileStore()
}

// MARK: - 未连接态空实现 Store（移除 Mock 后的编译基座，设计稿 §0.4）
//
// 全部读面返回空、观察流立即结束；写面如实失败——`send()` 返回 false（协议默认
// `sendWithAttachments` 转发 send 的返回值，覆写 send 即令整条转发链为 false，
// U-6 composer 发送失败错误行由此兜底）；`approve/reject` 返回 .undelivered
// （U-5 审批「未送达」口径）；`createConversation` 不返回成功占位（空 id——
// 未连接态新建入口不可达，防御性兜底防 onCreated → openChat 假成功导航，
// NewConversationSheet 侧对空 id 如实提示）。
// 仅远端能力（modelSelectionView/queueInfo/generateCommitMessage 等）吃协议
// extension 默认实现（nil/[]/空流），不逐一覆写——与 StoreProtocols 的 mock
// 兜底默认同一机制；isReadOnly/isRemote 亦取默认 false（ChatView 以 isReadOnly
// 区分连接态 chips，未连接态走本地 toolsRow 分支，行为正确）。

actor EmptyConversationStore: @preconcurrency ConversationStore {
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
}

actor EmptyTaskStore: @preconcurrency TaskStore {
    func tasks() async -> [TaskRecord] { [] }
    func observeTasks() -> AsyncStream<[TaskRecord]> { AsyncStream { $0.finish() } }
    func approve(taskID: String, optionId: String) async -> TaskDecisionOutcome { .undelivered }
    func reject(taskID: String, optionId: String) async -> TaskDecisionOutcome { .undelivered }
    func stop(taskID: String) async {}
    func retry(taskID: String) async {}
    func terminalStream(taskID: String) -> AsyncStream<TerminalLine> { AsyncStream { $0.finish() } }
    func trajectoryLines(taskID: String) async -> [TerminalLine] { [] }
}

actor EmptyFileStore: @preconcurrency FileStore {
    func fileTree() async -> [FileNode] { [] }
    func diffFiles() async -> [DiffFile] { [] }
    func content(of path: String) async -> String { "" }
    func setFileDecision(path: String, approved: Bool?) async {}
    func approveAll() async {}
}

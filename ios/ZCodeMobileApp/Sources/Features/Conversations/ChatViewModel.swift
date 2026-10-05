import SwiftUI

@MainActor
@Observable
final class ChatViewModel {
    let conversationID: String
    private let store: ConversationStore

    var conversation: Conversation?
    var messages: [ChatMessage] = []
    var draft: String = ""
    var isLoading = true

    /// 连接态数据源（chips / 向上分页 / 待审批交互）。演示态全部保持 nil/false，
    /// 工具行沿用本机设置，演示交互完全不变。
    var modelSelection: ModelSelectionInfo?
    var workspaceConfig: WorkspaceConfigInfo?
    var canLoadOlder = false
    private var isLoadingOlder = false
    /// 会话上下文用量（G-021：连接态真实值；演示态 Mock 动态值；nil=不渲染）
    var contextUsage: ContextUsageInfo?

    /// 连接态数据源标记（mock 演示恒为 false）：chips / 向上分页 / 待审批卡数据仅连接态提供
    var isReadOnly: Bool { store.isReadOnly }

    /// 待处理交互投影（连接态 conversation state.pendingInteractions；
    /// permission 类渲染审批卡，其余类型暂以折叠卡呈现）
    var pendingInteractions: [RemotePendingInteraction] = []

    /// 桌面 workflow 运行进度（要求 5 · 只读投影：nil = 无数据不渲染；
    /// 演示态运行中会话提供演示 run）
    var workflowRun: WorkflowRunSummary?

    /// 会话内消息搜索（P2-3）：对已加载 messages 本地检索 + 命中计数
    var searchQuery: String = ""
    var isSearchActive = false
    var filteredMessages: [ChatMessage] {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return messages }
        return messages.filter {
            $0.text.localizedCaseInsensitiveContains(query)
                || ($0.toolCall?.target.localizedCaseInsensitiveContains(query) ?? false)
                || ($0.toolCall?.output?.localizedCaseInsensitiveContains(query) ?? false)
        }
    }
    var searchHitCount: Int { filteredMessages.count }

    init(store: ConversationStore, conversationID: String) {
        self.store = store
        self.conversationID = conversationID
    }

    func load() async {
        let all = await store.conversations()
        conversation = all.first { $0.id == conversationID }
        messages = await store.messages(in: conversationID)
        await store.markRead(conversationID: conversationID)
        contextUsage = await store.sessionContextUsage(in: conversationID)
        // 要求 5：workflow 只读投影（带内缓存命中为纯内存读；无数据不渲染）
        workflowRun = await store.workflowRun(in: conversationID)
        if isReadOnly, !messages.isEmpty {
            canLoadOlder = true // 首屏尾部 200 行之外可能有更早数据（loadOlder 探测回收）
            modelSelection = await store.modelSelectionView()
            workspaceConfig = await store.workspaceConfig()
        }
        await refreshPendingInteractions()
        isLoading = false
    }

    /// 订阅数据层事件（流式输出逐字经此刷新）
    func observe() async {
        for await event in store.observeConversations() {
            switch event {
            case .conversationsReplaced(let list):
                if let match = list.first(where: { $0.id == conversationID }) {
                    conversation = match
                }
            case .conversationUpdated(let updated) where updated.id == conversationID:
                conversation = updated
            case .messagesReplaced(let id, let replaced) where id == conversationID:
                messages = replaced
                contextUsage = await store.sessionContextUsage(in: conversationID)
            case .messageAppended(let id, let message) where id == conversationID:
                if !messages.contains(where: { $0.id == message.id }) {
                    messages.append(message)
                }
            case .messageUpdated(let id, let message) where id == conversationID:
                if let index = messages.firstIndex(where: { $0.id == message.id }) {
                    messages[index] = message
                }
            default:
                break
            }
            // 任意事件后轻量刷新待审批投影（state.updated 无独立事件语义）
            await refreshPendingInteractions()
            // workflowRun.updated 带内事件无独立语义，随事件轻量刷新（缓存命中为内存读）
            workflowRun = await store.workflowRun(in: conversationID)
        }
    }

    /// 待处理交互投影刷新（连接态；演示态空）
    func refreshPendingInteractions() async {
        guard isReadOnly else { return }
        pendingInteractions = await store.pendingInteractionList(in: conversationID)
    }

    /// 审批卡决议：批准/拒绝携带真实 interactionId + 三档 scope 注入 answer 下发
    func decide(_ interaction: RemotePendingInteraction, approved: Bool, scope: String) async {
        await store.resolveInteraction(
            interaction.id, approved: approved, scope: scope, conversationID: conversationID)
        await refreshPendingInteractions()
    }

    /// 桌面端模型选择变化流（model-selection.onDidChange 驱动 chips 只读刷新）
    func observeModelSelection() async {
        for await info in store.observeModelSelection() {
            if let info {
                modelSelection = info
            }
        }
    }

    /// 向上分页：滚动触顶（顶部「加载更早消息」）拉更早历史，拼接去重在 Store 内完成
    func loadOlder() async {
        guard isReadOnly, !isLoadingOlder else { return }
        isLoadingOlder = true
        defer { isLoadingOlder = false }
        canLoadOlder = await store.loadOlder(conversationID: conversationID)
    }

    func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        await store.send(text, in: conversationID)
    }

    /// G-021：子代理只读转录加载（actor.sessionId → store 只读拉一页）
    func storeActorTranscript(sessionId: String) async -> [ChatMessage] {
        await store.actorTranscript(sessionId: sessionId, limit: 100)
    }

    /// G-015：失败工具卡「重试」→ retryTurn 下发（行合成 id "row-<n>" 携 rowId；
    /// entityId 由工具卡渲染门槛保证在场，此处从 messages 反查补齐）
    func retryTurn(rowId: Int) async {
        guard rowId > 0 else { return }
        guard let message = messages.first(where: { $0.id == "row-\(rowId)" }),
              let entityId = message.toolCall?.entityId else { return }
        await store.retryTurn(conversationID, rowId: rowId, entityId: entityId)
    }

    func answerQuestion(_ reply: String) async {
        await store.answerQuestion(reply, in: conversationID, questionID: "pending")
    }
}

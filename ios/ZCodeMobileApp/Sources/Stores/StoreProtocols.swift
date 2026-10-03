import Foundation

/// 会话事件流（数据层 → UI）
enum ConversationEvent {
    case conversationsReplaced([Conversation])
    case conversationUpdated(Conversation)
    case messagesReplaced(conversationID: String, messages: [ChatMessage])
    case messageAppended(conversationID: String, message: ChatMessage)
    case messageUpdated(conversationID: String, message: ChatMessage)
}

/// 会话存储协议。接入真实 ZCode 后端时按此边界替换实现。
protocol ConversationStore: AnyObject, Sendable {
    /// 连接态只读边界：true 时执行类入口（发送/回复提问/审批）由 UI 禁用并提示。
    /// mock 演示实现取协议扩展默认值 false，演示态交互完全不变。
    var isReadOnly: Bool { get }
    func conversations() async -> [Conversation]
    func observeConversations() -> AsyncStream<ConversationEvent>
    func messages(in conversationID: String) async -> [ChatMessage]
    /// 发送用户消息；回复经事件流逐字推送（模拟流式输出）
    func send(_ text: String, in conversationID: String) async
    func answerQuestion(_ reply: String, in conversationID: String, questionID: String) async
    func createConversation(title: String, directory: String, executor: ExecutorKind) async -> Conversation
    func setPinned(_ pinned: Bool, conversationID: String) async
    func setArchived(_ archived: Bool, conversationID: String) async
    func markRead(conversationID: String) async

    // MARK: 本期接入面（必须声明为本体 requirement）
    //
    // Swift 存在类型（any ConversationStore）对协议扩展中「非 requirement」方法的调用
    // 一律静态分派到默认实现——仅由远端实现覆写不会生效（门禁第 1 轮实证：
    // chips 永不渲染、getView 从未发出）。以下方法在 extension 提供默认实现供
    // mock 演示回退（行为不变），但必须在此声明才能经 any 动态分派到远端实现。
    /// 向上分页：取更早历史行（拼接去重由实现负责）；返回是否还有更早数据。
    func loadOlder(conversationID: String) async -> Bool
    /// workspace 级配置目录只读投影（ChatView chips 数据源；nil = 无数据）
    func workspaceConfig() async -> WorkspaceConfigInfo?
    /// 桌面端模型选择只读视图（model-selection.getView；nil = 无数据）
    func modelSelectionView() async -> ModelSelectionInfo?
    /// 模型选择变化流（连接态 model-selection.onDidChange 驱动；演示态不产生事件）
    func observeModelSelection() -> AsyncStream<ModelSelectionInfo?>
    /// 待处理交互投影列表（连接态 conversation state.pendingInteractions；演示态空）
    func pendingInteractionList(in conversationID: String) async -> [RemotePendingInteraction]
    /// 重命名会话（连接态 zcode-task.renameTask 双写；演示态本地改）
    func renameConversation(_ title: String, conversationID: String) async
    /// 标记未读（连接态 zcode-task.setTaskUnread unread:true；compare-and-clear 的反向）
    func markUnread(conversationID: String) async
    /// 已归档会话（连接态 listArchivedTasks 只读拉取 + 本地归档动作合并；演示态空）
    func archivedConversations() async -> [Conversation]
    /// 交互应答信封（连接态 v4 resolveInteraction；演示态无交互面）
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async
}

extension ConversationStore {
    var isReadOnly: Bool { false }

    // MARK: 以下能力仅远端实现覆写；默认实现让 mock 演示无需感知（演示行为不变）

    /// 向上分页：取更早历史行（拼接去重由实现负责）；返回是否还有更早数据。
    func loadOlder(conversationID: String) async -> Bool { false }

    /// workspace 级配置目录只读投影（ChatView chips 数据源；nil = 无数据）
    func workspaceConfig() async -> WorkspaceConfigInfo? { nil }

    /// 桌面端模型选择只读视图（model-selection.getView；nil = 无数据）
    func modelSelectionView() async -> ModelSelectionInfo? { nil }

    /// 模型选择变化流（连接态 model-selection.onDidChange 驱动；演示态不产生事件）
    func observeModelSelection() -> AsyncStream<ModelSelectionInfo?> {
        AsyncStream { $0.finish() }
    }

    /// 待处理交互投影列表（演示态无交互面，返回空）
    func pendingInteractionList(in conversationID: String) async -> [RemotePendingInteraction] { [] }

    /// 重命名会话（mock 默认本地改，连接态由远端实现双写覆写）
    func renameConversation(_ title: String, conversationID: String) async {}

    /// 标记未读（mock 默认本地置 1，连接态由远端实现双写覆写）
    func markUnread(conversationID: String) async {}

    /// 已归档会话（演示态空：归档行即刻从列表消失，与既有行为一致）
    func archivedConversations() async -> [Conversation] { [] }

    /// 交互应答信封（演示态无挂起交互，默认空实现）
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async {}

    /// 审批卡便捷应答：{approved, scope} 注入 answer（scope 三档：once/task/always）
    func resolveInteraction(_ interactionId: String, approved: Bool, scope: String,
                            conversationID: String) async {
        await resolveInteractionRaw(
            conversationID,
            interactionId: interactionId,
            answer: .object([
                "approved": .bool(approved),
                "scope": .string(scope),
            ]))
    }
}

/// workspace-config topic 投影（workspace-config/<workspacePath> 快照/delta 的只读映射）
struct WorkspaceConfigInfo: Equatable {
    struct Option: Equatable, Identifiable {
        var id: String
        var name: String
        var currentValue: String
        var values: [String]
    }
    struct SlashCommand: Equatable, Identifiable {
        var id: String { name }
        var name: String
        var description: String
    }
    var options: [Option] = []
    var slashCommands: [SlashCommand] = []
}

/// model-selection.getView 只读投影（桌面端可选模型/思考档 + 当前绑定）
struct ModelSelectionInfo: Equatable {
    var models: [String] = []
    var activeModel: String?
    var thoughtLevels: [String] = []
    var activeThoughtLevel: String?
}

/// 任务存储协议
protocol TaskStore: AnyObject, Sendable {
    /// 连接态只读边界（同 ConversationStore.isReadOnly）
    var isReadOnly: Bool { get }
    func tasks() async -> [TaskRecord]
    func observeTasks() -> AsyncStream<[TaskRecord]>
    func approve(taskID: String) async
    func reject(taskID: String) async
    func stop(taskID: String) async
    func retry(taskID: String) async
    /// 模拟后台终端输出流（后台 Bash）
    func terminalStream(taskID: String) -> AsyncStream<TerminalLine>
    /// 模型轨迹行
    func trajectoryLines(taskID: String) async -> [TerminalLine]

    // MARK: 本期接入面（必须声明为本体 requirement，同 ConversationStore 的分派约束）
    /// getTaskConfigOptions 只读投影（option 名 → 当前值；nil = 无数据）
    func taskConfigOptions(taskID: String) async -> [String: String]?
    /// getTaskModelSelection 只读投影（当前绑定模型显示名；nil = 无数据）
    func taskModelSelection(taskID: String) async -> String?
    /// getTaskTokenUsage 只读投影；nil = 无数据
    func taskTokenUsage(taskID: String) async -> TaskTokenUsage?
    /// 服务端全文搜索（连接态 listTaskList searchQuery 透传；演示态返回空走本地过滤）
    func searchTasks(_ query: String) async -> [TaskRecord]
}

extension TaskStore {
    var isReadOnly: Bool { false }

    // MARK: 以下只读能力仅远端实现覆写；默认实现让 mock 演示无需感知

    /// getTaskConfigOptions 只读投影（option 名 → 当前值；nil = 无数据）
    func taskConfigOptions(taskID: String) async -> [String: String]? { nil }

    /// getTaskModelSelection 只读投影（当前绑定模型显示名；nil = 无数据）
    func taskModelSelection(taskID: String) async -> String? { nil }

    /// getTaskTokenUsage 只读投影；nil = 无数据
    func taskTokenUsage(taskID: String) async -> TaskTokenUsage? { nil }

    /// 服务端全文搜索（演示态空：看板保持本地标题过滤）
    func searchTasks(_ query: String) async -> [TaskRecord] { [] }
}

/// getTaskTokenUsage 只读投影（ZCodeTaskTokenUsageResult 子集）
struct TaskTokenUsage: Equatable {
    var input: Int
    var output: Int
    var total: Int
}

/// 文件与 Diff 存储协议
protocol FileStore: AnyObject, Sendable {
    func fileTree() async -> [FileNode]
    func diffFiles() async -> [DiffFile]
    func content(of path: String) async -> String
    func setFileDecision(path: String, approved: Bool?) async
    func approveAll() async

    // MARK: 本期接入面（必须声明为本体 requirement，同 ConversationStore 的分派约束）
    /// 远端实现标记（连接态）：搜索/分页读/会话维度变更等远端能力仅连接态提供
    var isRemote: Bool { get }
    /// 服务端搜索（file.searchWorkspaceFiles）；演示态返回空走本地过滤
    func searchFiles(_ query: String, limit: Int) async -> [FileNode]
    /// git.getChanges 是否有超出有界展示（20 条）的更多变更（「更多」提示判定）
    func hasMoreChanges(sourceId: String) async -> Bool
    /// 按 sourceId（unstaged/staged）拉取工作区变更；默认实现忽略维度（mock 单维度）
    func diffFiles(sourceId: String) async -> [DiffFile]
    /// 有界读一页文本（readTextFile offset/length + totalBytes）；默认实现整读后包装
    func contentPage(of path: String, offset: Int, length: Int) async -> FileContentPage
    /// 二进制预览首块（file.readBinaryPreview，图片/PDF 只读预览；演示态/失败返回 nil）
    func binaryPreview(of path: String, maxBytes: Int) async -> Data?
    /// 会话维度文件变更（conversationFileChangesV4）；演示态返回空
    func sessionDiffFiles(sessionId: String) async -> [DiffFile]
    /// 文件树变更通知（file-watcher.onDynamicChange 驱动；演示态立即结束）
    func observeFileTreeChanges() -> AsyncStream<Void>
}

extension FileStore {
    /// 远端实现标记（连接态）：搜索/分页读/会话维度变更等远端能力仅连接态提供，
    /// mock 演示恒为 false（演示态交互完全不变）。
    var isRemote: Bool { false }

    /// 服务端搜索（file.searchWorkspaceFiles）；仅远端实现，演示态返回空走本地过滤。
    func searchFiles(_ query: String, limit: Int) async -> [FileNode] { [] }

    /// git.getChanges 是否有超出有界展示（20 条）的更多变更（「更多」提示判定）
    func hasMoreChanges(sourceId: String) async -> Bool { false }

    /// 按 sourceId（unstaged/staged）拉取工作区变更；默认实现忽略维度（mock 单维度）
    func diffFiles(sourceId: String) async -> [DiffFile] {
        await diffFiles()
    }

    /// 有界读一页文本（readTextFile offset/length + totalBytes）；
    /// 默认实现整读后包装（mock 全量返回、不截断），演示行为不变。
    func contentPage(of path: String, offset: Int, length: Int) async -> FileContentPage {
        guard offset == 0 else { return FileContentPage(content: "", totalBytes: 0, isTruncated: false) }
        let full = await content(of: path)
        return FileContentPage(content: full, totalBytes: full.utf8.count, isTruncated: false)
    }

    /// 二进制预览（演示态无远端读面，返回 nil → UI 降级说明文案）
    func binaryPreview(of path: String, maxBytes: Int) async -> Data? { nil }

    /// 会话维度文件变更（conversationFileChangesV4）；演示态返回空。
    func sessionDiffFiles(sessionId: String) async -> [DiffFile] { [] }

    /// 文件树变更通知（file-watcher.onDynamicChange 驱动；演示态立即结束）。
    func observeFileTreeChanges() -> AsyncStream<Void> {
        AsyncStream { $0.finish() }
    }
}

/// readTextFile 分页读结果（FileTextSlice 口径：content + totalBytes）
struct FileContentPage {
    var content: String
    var totalBytes: Int
    var isTruncated: Bool
}

/// 设置存储协议（UserDefaults 持久化）
protocol SettingsStore: Sendable {
    func load() -> AppSettings
    func save(_ settings: AppSettings)
}

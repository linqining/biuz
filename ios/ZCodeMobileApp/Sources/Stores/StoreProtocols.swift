import Foundation

/// 会话事件流（数据层 → UI）
enum ConversationEvent {
    case conversationsReplaced([Conversation])
    case conversationUpdated(Conversation)
    case messagesReplaced(conversationID: String, messages: [ChatMessage])
    case messageAppended(conversationID: String, message: ChatMessage)
    case messageUpdated(conversationID: String, message: ChatMessage)
    /// 面板态变更（goal/plan/backgroundWorks/subagents 任一键的快照/state.updated 应用）；
    /// 观察方重读面板投影（无行级语义）
    case panelStateUpdated(conversationID: String)
}

/// 会话存储协议。接入真实 ZCode 后端时按此边界替换实现。
protocol ConversationStore: AnyObject, Sendable {
    /// 连接态只读边界：true 时执行类入口（发送/回复提问/审批）由 UI 禁用并提示。
    /// mock 演示实现取协议扩展默认值 false，演示态交互完全不变。
    var isReadOnly: Bool { get }
    func conversations() async -> [Conversation]
    func observeConversations() -> AsyncStream<ConversationEvent>
    func messages(in conversationID: String) async -> [ChatMessage]
    /// 发送用户消息；回复经事件流逐字推送（模拟流式输出）。
    /// 返回是否受理（连接态=信封下发成功；false=未送达，调用方可如实反馈）
    @discardableResult
    func send(_ text: String, in conversationID: String) async -> Bool
    func answerQuestion(_ reply: String, in conversationID: String, questionID: String) async
    func createConversation(title: String, directory: String, executor: ExecutorKind,
                            modelSelection: NewSessionModelSelection?) async -> Conversation
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
    /// 删除任务（桌面失败任务「清理」/归档区删除同款：zcode-task.deleteTask）
    @discardableResult
    func deleteTask(_ conversationID: String) async -> Bool
    /// 交互应答信封（连接态 v4 resolveInteraction；演示态无交互面）
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async
    /// 失败 turn 重试（G-015）：携行元数据精确游标 {rowId, entityId} 下发 retryTurn；
    /// 游标缺失（entityId=nil）时调用方不渲染入口，本实现亦不下发
    func retryTurn(_ conversationID: String, rowId: Int, entityId: String?) async
    /// 会话派生（G-018）：forkAssistant（session 类放行分支）；返回新会话 id（失败 nil）
    func forkConversation(_ conversationID: String) async -> String?
    /// 会话分组管理写面（G-017，均为索引元数据写、桌面代执行合法）：
    /// createTaskGroup / renameTaskGroup / updateTaskGroupColor / deleteTaskGroup /
    /// applyGroupedTaskViewOrder（组内顺序 + 会话入组）
    func createTaskGroup(named name: String, color: String?) async -> String?
    func renameTaskGroup(_ groupID: String, to name: String) async
    func updateTaskGroupColor(_ groupID: String, color: String) async
    func deleteTaskGroup(_ groupID: String) async
    func applyGroupedTaskViewOrder(groupID: String?, order: [(taskID: String, groupID: String?)]) async
    /// 子代理只读转录（G-021）：按 actor.sessionId 拉一页 rowsRange 渲染（无新协议）
    func actorTranscript(sessionId: String, limit: Int) async -> [ChatMessage]
    /// 会话上下文用量（G-021：连接态 state.runtime.contextUsage；演示态 Mock 动态值；nil=无数据不渲染）
    func sessionContextUsage(in conversationID: String) async -> ContextUsageInfo?
    /// 附件预览读（G-014：连接态 attachmentReadV4 分块聚合 image/video/pdf；
    /// 演示态无附件数据恒 nil——无数据不渲染占位死块）
    func attachmentPreview(sessionID: String, ref: String) async -> AttachmentPreview?
    /// 会话全文检索（G-018：连接态 zcode-task listTaskList searchQuery 透传，
    /// 命中含未加载进内存的历史会话；演示态空 → 列表回退本地过滤）
    func searchSessions(_ query: String) async -> [Conversation]
    /// 桌面 workflow 运行进度（要求 5 · 只读：v4 workflowRun.updated 带内事件 +
    /// conversationWorkflowRunsV4 只读族；nil = 无数据不渲染；不新增任何发送命令）
    func workflowRun(in conversationID: String) async -> WorkflowRunSummary?
    /// 会话全部 workflow run（多 run 面板；活 run 优先。缺省退化为单 run 包装）
    func workflowRuns(in conversationID: String) async -> [WorkflowRunSummary]
    // MARK: 会话面板（goal/plan/btw/side；数据源 state.* 只读投影 + 控制命令面）
    /// state.goal 只读投影（nil = 无目标不渲染）
    func goalSummary(in conversationID: String) async -> RemoteGoalSummary?
    /// state.plan 只读投影（nil = 无计划不渲染）
    func planPanel(in conversationID: String) async -> PlanPanelSummary?
    /// state.backgroundWorks 只读投影（btw 面板数据源）
    func backgroundWorks(in conversationID: String) async -> [BackgroundWorkSummary]
    /// state.subagents 只读投影（side 面板数据源）
    func subagentSessions(in conversationID: String) async -> [SubagentSessionSummary]
    /// 目标暂停/继续（pauseGoal / resumeGoal；桌面代执行；返回命令回执供失败反馈）
    @discardableResult
    func setGoalPaused(_ paused: Bool, conversationID: String) async -> JSONValue?
    /// 取消后台工作（cancelBackgroundWork {workId}；返回命令回执供失败反馈）
    @discardableResult
    func cancelWork(_ conversationID: String, workId: String) async -> JSONValue?
    /// 恢复工作流运行（resumeWorkflowRun {workId, name?}；返回命令回执供失败反馈）
    @discardableResult
    func resumeWorkflowRun(_ conversationID: String, workId: String, name: String?) async -> JSONValue?
    /// 工作流运行设置（amendWorkflowRunSettings {workId, subagentModel?, maxConcurrency?}）：
    /// 双重可选语义——nil 参数 = 不修改该键；.some(nil) = 置 null（跟随主模型/解除上限）。
    /// 返回命令回执供失败反馈
    @discardableResult
    func amendWorkflowRunSettings(
        _ conversationID: String, workId: String,
        subagentModel: String??, maxConcurrency: Int??) async -> JSONValue?
    /// 主模型/思考强度切换（switchModelConfig {provider, model, thought}；桌面代执行，
    /// onDidChange 回流驱动 chips 同步）
    @discardableResult
    func switchModelConfig(_ conversationID: String, provider: String, model: String, thought: String?) async -> JSONValue?
    /// 主动全量 resync（服务端重发快照；amend 停旧换新后拉新 run 用，实现内节流）
    func triggerResync(_ conversationID: String) async
    /// 模型可用思考档（workspace-config configOptions 词表；getView 不携带）
    func thoughtLevels(for model: String) async -> [String]
    /// 排队队列只读投影（state.queue；nil = 无排队）
    func queueInfo(in conversationID: String) async -> ConversationQueueInfo?
    /// 队列条目立即发送（CAS）
    @discardableResult
    func sendQueuedNow(_ conversationID: String, queueItemId: String) async -> JSONValue?
    /// 队列条目文本编辑
    @discardableResult
    func editQueueItem(_ conversationID: String, queueItemId: String, newText: String) async -> JSONValue?
    /// 队列条目删除
    @discardableResult
    func deleteQueueItem(_ conversationID: String, queueItemId: String) async -> JSONValue?
    /// 队列条目重排（beforeQueueItemId=nil = 移到队尾）
    @discardableResult
    func reorderQueueItem(_ conversationID: String, queueItemId: String, beforeQueueItemId: String?) async -> JSONValue?
    /// 自动排空开关（CAS）
    @discardableResult
    func setAutoDrain(_ conversationID: String, enabled: Bool) async -> JSONValue?
}

/// 附件预览结果（G-014）
struct AttachmentPreview: Equatable, Identifiable {
    var id: String { ref }
    var ref: String = ""
    var data: Data
    var mediaType: String   // image/* | video/* | application/pdf
    var totalBytes: Int
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
    func deleteTask(_ conversationID: String) async -> Bool { false }

    /// 交互应答信封（演示态无挂起交互，默认空实现）
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async {}

    /// 失败 turn 重试（演示态无游标面，默认空实现）
    func retryTurn(_ conversationID: String, rowId: Int, entityId: String?) async {}

    /// 会话派生（演示态本地无桌面 fork 面，默认 nil）
    func forkConversation(_ conversationID: String) async -> String? { nil }

    /// 会话分组写面（演示态本地无桌面组面，默认空实现）
    func createTaskGroup(named name: String, color: String?) async -> String? { nil }
    func renameTaskGroup(_ groupID: String, to name: String) async {}
    func updateTaskGroupColor(_ groupID: String, color: String) async {}
    func deleteTaskGroup(_ groupID: String) async {}
    func applyGroupedTaskViewOrder(groupID: String?, order: [(taskID: String, groupID: String?)]) async {}

    /// 子代理只读转录（演示态无远端行面，默认空）
    func actorTranscript(sessionId: String, limit: Int) async -> [ChatMessage] { [] }

    /// 会话上下文用量（演示态默认由 Mock 覆写；协议默认无数据）
    func sessionContextUsage(in conversationID: String) async -> ContextUsageInfo? { nil }

    /// 附件预览读（演示态无附件读面，默认 nil）
    func attachmentPreview(sessionID: String, ref: String) async -> AttachmentPreview? { nil }

    /// 会话全文检索（演示态空；列表回退本地过滤）
    func searchSessions(_ query: String) async -> [Conversation] { [] }

    /// 桌面 workflow 运行进度（协议默认无数据不渲染；Mock 对运行中演示会话覆写提供演示 run）
    func workflowRun(in conversationID: String) async -> WorkflowRunSummary? { nil }

    /// 会话全部 workflow run（缺省退化为单 run 包装；远端实现覆写多源合并）
    func workflowRuns(in conversationID: String) async -> [WorkflowRunSummary] {
        [await workflowRun(in: conversationID)].compactMap { $0 }
    }

    // MARK: 会话面板（演示态无桌面 state 面，全部默认空/无数据；控制命令默认空实现）
    func goalSummary(in conversationID: String) async -> RemoteGoalSummary? { nil }
    func planPanel(in conversationID: String) async -> PlanPanelSummary? { nil }
    func backgroundWorks(in conversationID: String) async -> [BackgroundWorkSummary] { [] }
    func subagentSessions(in conversationID: String) async -> [SubagentSessionSummary] { [] }
    func setGoalPaused(_ paused: Bool, conversationID: String) async -> JSONValue? { nil }
    func cancelWork(_ conversationID: String, workId: String) async -> JSONValue? { nil }
    func resumeWorkflowRun(_ conversationID: String, workId: String, name: String?) async -> JSONValue? { nil }
    func amendWorkflowRunSettings(
        _ conversationID: String, workId: String,
        subagentModel: String??, maxConcurrency: Int??) async -> JSONValue? { nil }
    func switchModelConfig(_ conversationID: String, provider: String, model: String, thought: String?) async -> JSONValue? { nil }
    func triggerResync(_ conversationID: String) async {}
    func thoughtLevels(for model: String) async -> [String] { [] }
    func queueInfo(in conversationID: String) async -> ConversationQueueInfo? { nil }
    func sendQueuedNow(_ conversationID: String, queueItemId: String) async -> JSONValue? { nil }
    func editQueueItem(_ conversationID: String, queueItemId: String, newText: String) async -> JSONValue? { nil }
    func deleteQueueItem(_ conversationID: String, queueItemId: String) async -> JSONValue? { nil }
    func reorderQueueItem(_ conversationID: String, queueItemId: String, beforeQueueItemId: String?) async -> JSONValue? { nil }
    func setAutoDrain(_ conversationID: String, enabled: Bool) async -> JSONValue? { nil }

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
    /// 套餐分组（个人套餐/体验套餐/团队套餐…；providerId 含 start-plan → 体验、
    /// individual-coding-plan → 个人、team-coding-plan → 团队；缺席 = 未分组）
    var planGroups: [ModelPlanGroup] = []
    /// 模型 → 所属 providerId（switchModelConfig 需要三元组 provider/model/thought）
    var modelProviders: [String: String] = [:]
}

/// 模型套餐分组（选择器按组分节展示；同一模型可同时出现在个人与体验两组——配额不同）
struct ModelPlanGroup: Equatable, Identifiable {
    var plan: String
    var models: [String]
    var id: String { plan }
}

/// 新建会话的会话前模型选择（createSession firstInput.modelSelection）。
/// 字段名与 web 端 sendText/createSession 载荷一致：
/// {providerId, modelId, options:{reasoningLevel}}（provider/model/thought 三元组同源）。
struct NewSessionModelSelection: Equatable {
    var providerId: String
    var modelId: String
    var reasoningLevel: String
}

/// 会话上下文用量（G-021：会话流工具行真实数据源）。
/// 连接态来自会话 state.runtime.contextUsage（zcodeSessionContextUsageSchema 的 used/size）；
/// 演示态由 Mock 按消息量动态生成（非硬编码常量）。nil = 无数据 → UI 不渲染进度条。
struct ContextUsageInfo: Equatable {
    var used: Int
    var size: Int

    var fraction: Double {
        guard size > 0 else { return 0 }
        return min(1, Double(used) / Double(size))
    }

    var percentText: String {
        "\(Int((fraction * 100).rounded()))%"
    }
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

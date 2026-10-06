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
    /// 会话级模型选择（state.modelSelection——桌面 composer 变更随会话 state delta
    /// 到达；覆盖 workspace 级 getView 作为 chips 权威显示源；nil = 无数据）
    func sessionModelSelection(in conversationID: String) async -> (model: String, thought: String?)?
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
    /// 交互应答信封（连接态 v4 resolveInteraction；演示态无交互面）。
    /// A-3：answer 恒为对象按交互族分形（权限 {optionId} / 计划 {action} / 提问
    /// {freeText}|{optionId}），由调用方构造；返回命令回执供如实反馈（nil=未送达）
    @discardableResult
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async -> JSONValue?
    /// 失败 turn 重试（G-015）：携行元数据精确游标 {rowId, entityId} 下发 retryTurn；
    /// 游标缺失（entityId=nil）时调用方不渲染入口，本实现亦不下发
    func retryTurn(_ conversationID: String, rowId: Int, entityId: String?) async
    /// 会话派生（G-018）：forkAssistant（session 类放行分支）；返回新会话 id（失败 nil）
    func forkConversation(_ conversationID: String) async -> String?
    /// 会话分组管理写面（G-017，均为索引元数据写、桌面代执行合法）。B-3/B-4/C-3/C-4
    /// 按 web bundle 实证形状重写（2026-10-06）：createTaskGroup 零参 + 回执 groupId、
    /// 落名走 renameTaskGroup{groupId,title,workspaceScopes}；updateTaskGroupColor/
    /// deleteTaskGroup 均补 workspaceScopes；applyGroupedTaskViewOrder 为全量视图写
    /// {workspaceScopes, topLevelNodes, groups[{groupId, taskRefs}]}——由
    /// moveConversationToGroup 全链组合（拉结构→定位/建组→整视图重建提交），UI 不
    /// 直接拼视图。返回 nil=成功、非 nil=失败原因（写面禁止静默，UI 必须如实提示）。
    func moveConversationToGroup(_ conversationID: String, groupName: String) async -> String?
    func renameTaskGroup(groupId: String, title: String) async -> String?
    func updateTaskGroupColor(groupId: String, color: String) async -> String?
    func deleteTaskGroup(groupId: String) async -> String?
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

    // MARK: v4 命令扩容（发送层；payload 未取证项以远端实现注释为准，UI 接入前探针定形）
    /// 协作模式切换（Plan/Build；CAS 类，mode: "plan"|"build"）
    @discardableResult
    func switchCollaborationMode(_ conversationID: String, mode: String) async -> JSONValue?
    /// 投递/跟随模式（CAS 类；A-2：mode 值域 queue|guide——now 档为本地语义，
    /// 调用方拦截不下发命令，见 ChatViewModel.setDeliveryMode）
    @discardableResult
    func setFollowupMode(_ conversationID: String, mode: String) async -> JSONValue?
    /// 助手消息轻反馈（CAS+row-target；value true=赞 false=踩 nil=取消；
    /// entityId 缺失时远端实现拒发——调用方须先保证游标在场再渲染入口）
    @discardableResult
    func setAssistantFeedback(
        _ conversationID: String, rowId: Int, entityId: String?, value: Bool?) async -> JSONValue?
    /// 编辑用户消息并重发（CAS+row-target，与 retryTurn 同族；rowId+entityId 为行游标）
    @discardableResult
    func editUserQuery(
        _ conversationID: String, rowId: Int, entityId: String?, newText: String) async -> JSONValue?
    /// 压缩上下文（非 CAS；完成判定另看 contextUsage 回落）
    @discardableResult
    func compact(_ conversationID: String) async -> JSONValue?
    /// 挂起交互「稍后处理」（{interactionId}；命令本体未取证）
    @discardableResult
    func snoozeInteractionAutoResolution(
        _ conversationID: String, interactionId: String) async -> JSONValue?
    /// 目标下发（改 state.goal；按 CAS 预期处理，探针裁决）
    @discardableResult
    func sendGoalCommand(_ conversationID: String, text: String) async -> JSONValue?
    /// 启动已保存工作流（conversationID 传 nil = 无会话上下文，会话由桌面创建；
    /// 回执 result.sessionId 宽容提取交调用方跳转；args 为动态参数表单键值）
    @discardableResult
    func startSavedWorkflow(
        _ conversationID: String?, workflowId: String, args: [String: JSONValue]?) async -> JSONValue?
    // MARK: workspace hook 信任审核（web 对齐：respond/request/toggle/revoke 四命令，
    // 均非 CAS 普通信封；payload 基座 M8e 字段族由远端实现从挂起交互透传）
    /// 应答信任审核（decision:{action:'trust_selected', reviewItemIds}，web 实证唯一
    /// action）；返回命令回执供如实反馈（nil=未送达/无挂起交互）
    @discardableResult
    func respondWorkspaceHookReview(
        _ conversationID: String, reviewItemIds: [String]) async -> JSONValue?
    /// 主动请求重发信任审核（{sessionId, remoteSessionId?, workspaceIdentity,
    /// bundleDigest}，web 两调用点取证；无 digest 可携返回 nil）
    @discardableResult
    func requestWorkspaceHookReview(_ conversationID: String) async -> JSONValue?
    /// 单条审核项信任开关（{reviewItemId, enabled}；web 仅 schema 无调用点，
    /// enabled 语义未取证——暂无 UI 入口）
    @discardableResult
    func toggleWorkspaceHookReviewItem(
        _ conversationID: String, reviewItemId: String, enabled: Bool) async -> JSONValue?
    /// 撤销已信任 hook 项（{reviewItemIds}；写桌面信任账本，UI 必须带确认弹层）
    @discardableResult
    func revokeWorkspaceHookTrust(
        _ conversationID: String, reviewItemIds: [String]) async -> JSONValue?
    /// A-4 附件上传事务（web 对齐）：四条 **channel RPC**（zcode-agent 直发，非 v4 会话
    /// 命令、无信封）。全部 strict 键集——Begin 8 键 / Chunk 5 键 / Commit·Abort 3 键，
    /// 多一个键都会被 zod 拒收（不带 workspacePath/workspaceIdentity）。远端实现注入
    /// connectionId（握手注册 clientId）；失败以 Result.failure 携错误文本回传 UI。
    /// 演示态默认实现返回 failure（无上传面）。
    func attachmentBeginV4(
        sessionID: String, uploadId: String, fileName: String, mime: String,
        totalBytes: Int, totalChunks: Int, checksum: String
    ) async -> Result<AttachmentBeginOutcome, AttachmentRPCError>
    /// 分块下发；成功值 = 回执 nextChunkIndex（进度判定=其恒等于 chunkIndex+1，web PCe 同款）
    func attachmentChunkV4(
        sessionID: String, uploadId: String, chunkIndex: Int, dataBase64: String
    ) async -> Result<Int, AttachmentRPCError>
    /// 事务收口；成功值 = 回执 ref（随 sendText attachments 携带）
    func attachmentCommitV4(sessionID: String, uploadId: String) async -> Result<String, AttachmentRPCError>
    /// 失败路径中止（尽力收口；Abort 送达与否影响重试是否重开新事务）
    func attachmentAbortV4(sessionID: String, uploadId: String) async -> Result<Void, AttachmentRPCError>
    /// 携附件发送（P1-1）：attachments 为 committed 引用列表，随 sendText payload
    /// `attachments` 字段下发。B-1（web 对齐）：元素键 `{ref, fileName, mime, bytes}`
    /// （bundle 三处独立构造同形、sendText schema `attachments: ta(Do).optional()`
    /// 【移植·bundle 逆向】；首击回执待真机探针）。refs 为空时行为与 send 一致。
    /// 演示态默认实现忽略附件直发文本——演示态无上传面，不会携带非空 refs）。
    /// requestedDelivery 投递模式（P1-2）：nil/空串不携带键；"startNow" 为 now 档
    /// 恒携值（A-2 修正，web sendText delivery 枚举 startNow|queue|guide【实证】）；
    /// "queue" 形态已探针活体验证（diag 探针在案），"guide" 属 sendText 三路
    /// admission 词表【移植】。经 any ConversationStore 调用无默认实参，须显式传。
    @discardableResult
    func sendWithAttachments(
        _ text: String, attachments: [OutgoingAttachment], requestedDelivery: String?,
        in conversationID: String) async -> Bool
}

/// 附件预览结果（G-014）
struct AttachmentPreview: Equatable, Identifiable {
    var id: String { ref }
    var ref: String = ""
    var data: Data
    var mediaType: String   // image/* | video/* | application/pdf
    var totalBytes: Int
}

/// 随 sendText 下发的待发附件引用（P1-1 上传链路产物；attachmentCommitV4 收口后的
/// 最终附件引用 + 展示元数据）。B-1：字段名与 web wire 元素键一比一
/// `{ref, fileName, mime, bytes}`（bundle 三处独立构造同形【移植·bundle 逆向】）。
struct OutgoingAttachment: Equatable {
    var ref: String
    var fileName: String
    var mime: String
    var bytes: Int
}

/// attachmentBeginV4 回执（web state 判别联合【移植·bundle 逆向】）：
/// staging 携 nextChunkIndex（已收块数，续传起点）；committed 直接给 ref
/// （checksum 命中已上传附件的短路路径，免分块直取引用）。
enum AttachmentBeginOutcome {
    case staging(nextChunkIndex: Int)
    case committed(ref: String)
}

/// 附件事务 RPC 失败（错误文本直出 UI 失败行；channel 异常/回执形状不识别/未连接）
struct AttachmentRPCError: Error {
    let text: String
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
    func sessionModelSelection(in conversationID: String) async -> (model: String, thought: String?)? { nil }

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
    @discardableResult
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async -> JSONValue? { nil }

    /// 失败 turn 重试（演示态无游标面，默认空实现）
    func retryTurn(_ conversationID: String, rowId: Int, entityId: String?) async {}

    /// 会话派生（演示态本地无桌面 fork 面，默认 nil）
    func forkConversation(_ conversationID: String) async -> String? { nil }

    /// 会话分组写面（演示/未连接态本地无桌面组面，默认如实失败——写面禁止静默假成功）
    func moveConversationToGroup(_ conversationID: String, groupName: String) async -> String? {
        String(localized: "未连接桌面端，分组未同步")
    }
    func renameTaskGroup(groupId: String, title: String) async -> String? {
        String(localized: "未连接桌面端，分组未同步")
    }
    func updateTaskGroupColor(groupId: String, color: String) async -> String? {
        String(localized: "未连接桌面端，分组未同步")
    }
    func deleteTaskGroup(groupId: String) async -> String? {
        String(localized: "未连接桌面端，分组未同步")
    }

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

    // MARK: v4 命令扩容（演示态无桌面命令面，全部默认空实现；远端实现覆写）
    func switchCollaborationMode(_ conversationID: String, mode: String) async -> JSONValue? { nil }
    func setFollowupMode(_ conversationID: String, mode: String) async -> JSONValue? { nil }
    func setAssistantFeedback(
        _ conversationID: String, rowId: Int, entityId: String?, value: Bool?) async -> JSONValue? { nil }
    @discardableResult
    func editUserQuery(
        _ conversationID: String, rowId: Int, entityId: String?, newText: String) async -> JSONValue? { nil }
    func compact(_ conversationID: String) async -> JSONValue? { nil }
    func snoozeInteractionAutoResolution(
        _ conversationID: String, interactionId: String) async -> JSONValue? { nil }
    func sendGoalCommand(_ conversationID: String, text: String) async -> JSONValue? { nil }
    func startSavedWorkflow(
        _ conversationID: String?, workflowId: String, args: [String: JSONValue]?) async -> JSONValue? { nil }
    // MARK: workspace hook 信任审核（演示态无 hook 审核面，默认空实现；远端实现覆写）
    func respondWorkspaceHookReview(
        _ conversationID: String, reviewItemIds: [String]) async -> JSONValue? { nil }
    func requestWorkspaceHookReview(_ conversationID: String) async -> JSONValue? { nil }
    func toggleWorkspaceHookReviewItem(
        _ conversationID: String, reviewItemId: String, enabled: Bool) async -> JSONValue? { nil }
    func revokeWorkspaceHookTrust(
        _ conversationID: String, reviewItemIds: [String]) async -> JSONValue? { nil }
    func attachmentBeginV4(
        sessionID: String, uploadId: String, fileName: String, mime: String,
        totalBytes: Int, totalChunks: Int, checksum: String
    ) async -> Result<AttachmentBeginOutcome, AttachmentRPCError> {
        .failure(AttachmentRPCError(text: "演示态无附件上传面"))
    }
    func attachmentChunkV4(
        sessionID: String, uploadId: String, chunkIndex: Int, dataBase64: String
    ) async -> Result<Int, AttachmentRPCError> {
        .failure(AttachmentRPCError(text: "演示态无附件上传面"))
    }
    func attachmentCommitV4(sessionID: String, uploadId: String) async -> Result<String, AttachmentRPCError> {
        .failure(AttachmentRPCError(text: "演示态无附件上传面"))
    }
    func attachmentAbortV4(sessionID: String, uploadId: String) async -> Result<Void, AttachmentRPCError> {
        .failure(AttachmentRPCError(text: "演示态无附件上传面"))
    }
    func sendWithAttachments(
        _ text: String, attachments: [OutgoingAttachment], requestedDelivery: String?,
        in conversationID: String) async -> Bool {
        await send(text, in: conversationID)
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

/// 任务审批决议结果（U-5：approve/reject 如实回传，禁止无条件假成功）
enum TaskDecisionOutcome: Equatable {
    /// 回执 accepted/noop/applied/ok
    case accepted
    /// 会话无待审批交互（可能已在桌面端处理）
    case interactionMissing
    /// 回执拒绝（附 reasonCode/status 详情）
    case rejected(String)
    /// 未送达（ack nil：连接断开/不在连接态）
    case undelivered
}

/// 任务存储协议
protocol TaskStore: AnyObject, Sendable {
    /// 连接态只读边界（同 ConversationStore.isReadOnly）
    var isReadOnly: Bool { get }
    func tasks() async -> [TaskRecord]
    func observeTasks() -> AsyncStream<[TaskRecord]>
    /// 审批决议（U-4：optionId 透传——A-3 后 answer={optionId}，四族
    /// allowOnce/allowAlways/rejectOnce/rejectAlways 由 UI 拼好传入）
    @discardableResult
    func approve(taskID: String, optionId: String) async -> TaskDecisionOutcome
    @discardableResult
    func reject(taskID: String, optionId: String) async -> TaskDecisionOutcome
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

    // MARK: 一站式提交 + 检查点（P3-8/P3-11：git / git-checkpoint 写族 UI 化，连接态 only）
    /// AI 生成本次提交信息（git.generateCommitMessage，paths=已暂存文件）；
    /// 失败返回 nil（UI 降级为手写）。演示态不可达。
    func generateCommitMessage(paths: [String]) async -> String?
    /// 提交身份只读预检（git.getIdentity {workspacePath}，web GitBranchSwitcher 与
    /// getChanges('staged') 并发调用同面）；nil = 调用失败（区别于「已读取但字段空」）。
    /// 回执字段未在 web 消费点出现——name/email 宽容解析【未取证】。
    func gitIdentity() async -> GitIdentityInfo?
    /// 提交已暂存变更（git.commit，提交集由桌面端 git index 定义）；
    /// 成功返回短 hash（桌面端未回 hash 时为空串），失败返回 nil。
    func commit(message: String) async -> String?
    /// 检查点清单（git-checkpoint.diffCheckpoints，按时间倒序）；
    /// nil = 拉取失败（区别于空清单）。
    func checkpoints() async -> [CheckpointInfo]?
    /// 创建检查点（git-checkpoint.createCheckpoint，note 可选说明）。
    func createCheckpoint(note: String?) async -> Bool
    /// 恢复工作区到检查点（git-checkpoint.restoreBetweenCheckpoints，破坏性——
    /// UI 侧硬要求 destructive 确认弹层）。
    func restoreCheckpoint(_ checkpoint: CheckpointInfo) async -> Bool
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

    // MARK: 一站式提交 + 检查点（演示态默认实现：入口不渲染，以下永不触达）
    func generateCommitMessage(paths: [String]) async -> String? { nil }
    /// git.getIdentity（演示态不可达，默认 nil）
    func gitIdentity() async -> GitIdentityInfo? { nil }
    func commit(message: String) async -> String? { nil }
    func checkpoints() async -> [CheckpointInfo]? { nil }
    func createCheckpoint(note: String?) async -> Bool { false }
    func restoreCheckpoint(_ checkpoint: CheckpointInfo) async -> Bool { false }
}

/// 检查点行（P3-11：git-checkpoint.diffCheckpoints 宽容投影——id/时间/说明字段名
/// 均未取证，多形态兼容见 RemoteFileStore.parseCheckpoint；时间支持毫秒/ISO 双形态）
struct CheckpointInfo: Identifiable, Equatable {
    var id: String
    var date: Date?
    var label: String?
}

/// git.getIdentity 只读投影（提交面板预检）。回执字段未在 web 消费点出现
/// 【未取证】——name/email 宽容解析（name|userName / email|userEmail）；
/// 两字段可同时为 nil（身份对象在场但配置缺失，web 端此态禁用提交按钮）。
struct GitIdentityInfo: Equatable {
    var name: String?
    var email: String?

    /// 是否完整（web identityMissing 口径：缺任一字段视为未配置）
    var isComplete: Bool {
        !(name ?? "").isEmpty && !(email ?? "").isEmpty
    }

    var displayText: String {
        switch (name, email) {
        case let (name?, email?): return "\(name) <\(email)>"
        case let (name?, nil): return name
        case let (nil, email?): return email
        default: return ""
        }
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

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
    var isLoadingOlder = false
    /// 会话上下文用量（G-021：连接态真实值；演示态 Mock 动态值；nil=不渲染）
    var contextUsage: ContextUsageInfo?

    /// 连接态数据源标记（mock 演示恒为 false）：chips / 向上分页 / 待审批卡数据仅连接态提供
    var isReadOnly: Bool { store.isReadOnly }

    /// 待处理交互投影（连接态 conversation state.pendingInteractions；
    /// permission 类渲染审批卡，其余类型暂以折叠卡呈现）
    var pendingInteractions: [RemotePendingInteraction] = []

    /// 桌面 workflow 运行进度（要求 5 · 多 run：活 run 优先、stale 仲裁降级；
    /// 演示态运行中会话提供演示 run）
    var workflowRuns: [WorkflowRunSummary] = []
    /// 主 run（活优先首个；chips 徽标与 diag 用）
    var workflowRun: WorkflowRunSummary? { workflowRuns.first }

    // MARK: 会话面板投影（goal/plan/btw/side；连接态 state.* 只读投影，空 = 不渲染）
    var goalSummary: RemoteGoalSummary?
    var planPanel: PlanPanelSummary?
    var backgroundWorks: [BackgroundWorkSummary] = []
    var subagentSessions: [SubagentSessionSummary] = []

    /// 是否存在任一面板数据（chips 条渲染门槛）
    var hasAnyPanel: Bool {
        goalSummary != nil || planPanel != nil || !workflowRuns.isEmpty
            || !backgroundWorks.isEmpty || !subagentSessions.isEmpty
    }

    /// 面板投影刷新（panelStateUpdated 事件与 load/任意事件后的轻量全量重读；
    /// 全部为内存读，成本可忽略）
    func refreshPanels() async {
        guard isReadOnly else { return }
        goalSummary = await store.goalSummary(in: conversationID)
        planPanel = await store.planPanel(in: conversationID)
        backgroundWorks = await store.backgroundWorks(in: conversationID)
        subagentSessions = await store.subagentSessions(in: conversationID)
        queueInfo = await store.queueInfo(in: conversationID)
    }

    /// 目标暂停/继续（下发后随 state.updated 回流刷新；回执转人类可读反馈，nil = 成功静默）
    func toggleGoalPause() async -> String? {
        guard goalSummary != nil else { return String(localized: "当前会话没有可暂停的目标") }
        let ack = await store.setGoalPaused(!(goalSummary?.isPaused ?? false), conversationID: conversationID)
        recordControlDiag(goalSummary?.isPaused == true ? "resume-goal" : "pause-goal", workId: nil, ack: ack)
        return Self.controlFeedback(ack, verb: goalSummary?.isPaused == true ? "resume" : "pause")
    }

    /// 子代理模型修改（amendWorkflowRunSettings；nil = 跟随主模型；workId 缺省 = 主 run）。
    /// 桌面端语义：对运行中 run 修改设置 = 停旧 run → 以新设置重启（新 runId），
    /// 故成功时也给出可见提示，避免「看起来没作用」
    func setSubagentModel(_ model: String?, workId: String? = nil) async -> String? {
        guard let run = workId.map({ id in workflowRuns.first { $0.workId == id || $0.id == id } })
            ?? workflowRun else { return nil }
        let ack = await store.amendWorkflowRunSettings(
            conversationID, workId: run.workId ?? run.id, subagentModel: .some(model), maxConcurrency: nil)
        recordControlDiag("model=\(model ?? "null")", workId: run.workId ?? run.id, ack: ack)
        scheduleRunRefresh(resync: true)
        return Self.controlFeedback(ack, verb: "settings")
            ?? String(localized: "已下发 · 桌面端将切换运行设置")
    }

    /// 并发 agent 数修改（amendWorkflowRunSettings；nil = 解除上限跟随默认；workId 缺省 = 主 run）
    func setMaxConcurrency(_ limit: Int?, workId: String? = nil) async -> String? {
        guard let run = workId.map({ id in workflowRuns.first { $0.workId == id || $0.id == id } })
            ?? workflowRun else { return nil }
        let ack = await store.amendWorkflowRunSettings(
            conversationID, workId: run.workId ?? run.id, subagentModel: nil, maxConcurrency: .some(limit))
        recordControlDiag("concurrency=\(limit.map(String.init) ?? "null")", workId: run.workId ?? run.id, ack: ack)
        scheduleRunRefresh(resync: true)
        return Self.controlFeedback(ack, verb: "settings")
            ?? String(localized: "已下发 · 桌面端将切换运行设置")
    }

    /// 主模型切换（switchModelConfig 三元组；thought 沿用当前档位）
    func switchModel(_ model: String, thoughtLevel: String? = nil) async -> String? {
        guard let selection = modelSelection else {
            return String(localized: "不在连接态，无法切换桌面端模型")
        }
        let provider = selection.modelProviders[model] ?? ""
        let thought = thoughtLevel ?? selection.activeThoughtLevel ?? ""
        let ack = await store.switchModelConfig(
            conversationID, provider: provider, model: model, thought: thought)
        recordControlDiag("switchModel=\(model) thought=\(thought)", workId: nil, ack: ack)
        return Self.controlFeedback(ack, verb: "model")
    }

    /// 思考强度切换（同三元组；model 沿用当前绑定）
    func switchThoughtLevel(_ level: String) async -> String? {
        guard let model = modelSelection?.activeModel else {
            return String(localized: "不在连接态，无法切换思考强度")
        }
        return await switchModel(model, thoughtLevel: level)
    }

    /// 当前模型的可用思考档（workspace-config 词表；懒加载一次并随模型切换重查）
    var thoughtLevels: [String]?

    func refreshThoughtLevels() async {
        guard isReadOnly, let model = modelSelection?.activeModel else { return }
        let levels = await store.thoughtLevels(for: model)
        thoughtLevels = levels.isEmpty ? nil : levels
    }

    /// 排队队列（桌面 composer pending 队列；state.queue 投影，nil = 无排队）
    var queueInfo: ConversationQueueInfo?

    /// 队列动作统一反馈（成功静默；含 queue 键的 state.updated 会自动回流刷新）
    private func queueAction(_ verb: String, _ action: () async -> JSONValue?) async -> String? {
        let ack = await action()
        recordControlDiag("queue-\(verb)", workId: nil, ack: ack)
        return Self.controlFeedback(ack, verb: verb)
    }

    func sendQueuedNow(_ queueItemId: String) async -> String? {
        await queueAction("sendNow") { await store.sendQueuedNow(conversationID, queueItemId: queueItemId) }
    }

    func deleteQueueItem(_ queueItemId: String) async -> String? {
        await queueAction("delete") { await store.deleteQueueItem(conversationID, queueItemId: queueItemId) }
    }

    /// 编辑排队文本（保存后回流刷新；返回反馈）
    func editQueueItem(_ queueItemId: String, newText: String) async -> String? {
        guard !newText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return String(localized: "内容不能为空")
        }
        return await queueAction("edit") {
            await store.editQueueItem(conversationID, queueItemId: queueItemId, newText: newText)
        }
    }

    /// 队列条目上移（与前一位置交换 = reorder 到前一项之前）
    func moveQueueItemUp(_ queueItemId: String) async -> String? {
        guard let items = queueInfo?.items,
              let index = items.firstIndex(where: { $0.id == queueItemId }),
              index > 0 else { return nil }
        let before = items[index - 1].id
        return await queueAction("reorder") {
            await store.reorderQueueItem(conversationID, queueItemId: queueItemId, beforeQueueItemId: before)
        }
    }

    func setAutoDrain(_ enabled: Bool) async -> String? {
        await queueAction("autoDrain") { await store.setAutoDrain(conversationID, enabled: enabled) }
    }

    /// 取消后台工作 / 恢复工作流运行（btw 面板与 workflow 卡控制面）
    func cancelWork(_ workId: String) async -> String? {
        let ack = await store.cancelWork(conversationID, workId: workId)
        recordControlDiag("cancel", workId: workId, ack: ack)
        scheduleRunRefresh()
        return Self.controlFeedback(ack, verb: "cancel")
    }

    func resumeWork(_ workId: String, name: String?) async -> String? {
        let ack = await store.resumeWorkflowRun(conversationID, workId: workId, name: name)
        recordControlDiag("resume", workId: workId, ack: ack)
        scheduleRunRefresh()
        return Self.controlFeedback(ack, verb: "resume")
    }

    /// 命令回执 → 人类可读反馈（nil ack = 未送达；rejected 时带 reasonCode + 首条详情）
    private static func controlFeedback(_ ack: JSONValue?, verb: String) -> String? {
        guard let ack else {
            return String(localized: "命令未送达（连接中断或不在连接态）")
        }
        let status = ack["status"]?.stringValue
            ?? ack.objectValue?["ack"]?.objectValue?["status"]?.stringValue
        switch status {
        case nil, "accepted", "noop", "applied", "ok":
            return nil
        default:
            var detail = ack["reasonCode"]?.stringValue ?? status ?? "?"
            // zod 校验类拒绝：message 为 issue 数组 JSON，取首条 message 字段
            if let message = ack["message"]?.stringValue,
               let range = message.range(of: "\"message\": \"") {
                let tail = message[range.upperBound...]
                if let end = tail.firstIndex(of: "\"") {
                    detail += "：" + tail[..<end]
                }
            }
            return String(localized: "桌面端拒绝（\(detail)）")
        }
    }

    /// UI 控制动作回执取证（diag.wf.control.ui；不限 diag 模式——用户手工测试的
    /// 拒绝原因也要能回查，键很小）
    private func recordControlDiag(_ verb: String, workId: String?, ack: JSONValue?) {
        UserDefaults.standard.set(
            "ui \(verb) workId=\(workId ?? "-") ack=\(String(describing: ack).prefix(600))",
            forKey: "diag.wf.control.ui")
        UserDefaults.standard.synchronize()
    }

    /// 控制动作后延迟回读 run（state 事件未及时到达时按钮态也能跟上）；
    /// 三段式——1.2s/3.7s 双次回读 + amend 类动作追加全量 resync（桌面端停旧换新，
    /// 新 run 可能只在重发快照的 state.workflowRuns 里）
    private func scheduleRunRefresh(resync: Bool = false) {
        Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            workflowRuns = await store.workflowRuns(in: conversationID)
            if resync {
                await store.triggerResync(conversationID)
            }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            workflowRuns = await store.workflowRuns(in: conversationID)
        }
    }

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
        // 诊断钩子：-ZCodeDiagPanelControl <pause|resume|cancel>（首条 workflowRun 到达后
        // 自动发一次控制命令；PTY 受限期间无点按路径的端到端验证入口）
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-ZCodeDiagPanelControl"),
           i + 1 < args.count {
            pendingPanelControl = args[i + 1]
        }
    }

    private var pendingPanelControl: String?

    func load() async {
        // 诊断旗标最先置位：订阅发生在 load 中段，晚了会漏掉订阅回执取证
        if ProcessInfo.processInfo.arguments.contains("-ZCodeDiagWorkflow") {
            UserDefaults.standard.set("1", forKey: "diag.wf.mode")
            // 清空上次启动残留（跨启动 stale 值污染取证），保留 mode 本身
            for (key, _) in UserDefaults.standard.dictionaryRepresentation() where key.hasPrefix("diag.") {
                UserDefaults.standard.removeObject(forKey: key)
            }
            UserDefaults.standard.set("1", forKey: "diag.wf.mode")
        }
        let all = await store.conversations()
        conversation = all.first { $0.id == conversationID }
        messages = await store.messages(in: conversationID)
        await store.markRead(conversationID: conversationID)
        contextUsage = await store.sessionContextUsage(in: conversationID)
        // 要求 5：workflow 只读投影（带内缓存命中为纯内存读；无数据不渲染）
        workflowRuns = await store.workflowRuns(in: conversationID)
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set(
                workflowRun.map { "name=\($0.name) status=\($0.rawStatus) nodes=\($0.nodes.count) actors=\($0.actors.count)" } ?? "nil",
                forKey: "diag.wf.result")
        }
        if isReadOnly, !messages.isEmpty {
            canLoadOlder = true // 首屏尾部 200 行之外可能有更早数据（loadOlder 探测回收）
            modelSelection = await store.modelSelectionView()
            workspaceConfig = await store.workspaceConfig()
            await refreshThoughtLevels()
        }
        await refreshPendingInteractions()
        await refreshPanels()
        isLoading = false
        // 诊断钩子：-ZCodeDiagLoadOlder 自动探测一次向上分页（「加载更早消息不生效」取证）
        if ProcessInfo.processInfo.arguments.contains("-ZCodeDiagLoadOlder"), isReadOnly, !messages.isEmpty {
            let before = messages.count
            await loadOlder()
            try? await Task.sleep(nanoseconds: 500_000_000) // messagesReplaced 经 observe 异步回灌
            UserDefaults.standard.set(
                "before=\(before) after=\(messages.count) canLoadOlder=\(canLoadOlder) isLoadingOlder=\(isLoadingOlder)",
                forKey: "diag.loadolder")
        }
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
                if ProcessInfo.processInfo.arguments.contains("-ZCodeDiagLoadOlder") {
                    UserDefaults.standard.set("delivered \(replaced.count)", forKey: "diag.loadolder.delivered")
                }
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
            workflowRuns = await store.workflowRuns(in: conversationID)
            // 面板投影随事件轻量刷新（panelStateUpdated/快照/任意行事件统一口径）
            await refreshPanels()
            // diag：首条 workflowRun 到达后自动发一次面板控制命令（一发即止）
            if let action = pendingPanelControl, workflowRun != nil, isReadOnly {
                pendingPanelControl = nil
                await firePanelControl(action)
            }
        }
    }

    /// diag 控制命令实发（pause/resume/cancel/text）——回执落 diag.wf.control 供取证
    private func firePanelControl(_ action: String) async {
        guard let run = workflowRun else { return }
        var ack: JSONValue?
        var note = ""
        switch action {
        case "pause": ack = await store.setGoalPaused(true, conversationID: conversationID)
        case "resume": ack = await store.setGoalPaused(false, conversationID: conversationID)
        case "cancel": ack = await store.cancelWork(conversationID, workId: run.workId ?? run.id)
        case "text":
            let delivered = await store.send("[面板验证] envelope 链路回归 \(Int(Date().timeIntervalSince1970))", in: conversationID)
            note = "delivered=\(delivered)"
        case "switchmodel":
            // 零干扰 CAS 验证：默认切到当前模型+当前思考档（桌面无变化）；
            // -ZCodeDiagSwitchModelTo <model> / -ZCodeDiagSwitchThoughtTo <level> 可指定
            // 目标以复现真实切换的拒绝原因
            var current = modelSelection
            if current == nil {
                current = await store.modelSelectionView()
            }
            if let selection = current {
                let args = ProcessInfo.processInfo.arguments
                func argValue(_ flag: String) -> String? {
                    guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
                    return args[i + 1]
                }
                let targetModel = argValue("-ZCodeDiagSwitchModelTo") ?? selection.activeModel ?? ""
                let provider = selection.modelProviders[targetModel]
                    ?? (targetModel == selection.activeModel
                        ? selection.modelProviders[selection.activeModel ?? ""] ?? ""
                        : "")
                let thought = argValue("-ZCodeDiagSwitchThoughtTo") ?? selection.activeThoughtLevel
                ack = await store.switchModelConfig(
                    conversationID,
                    provider: provider,
                    model: targetModel,
                    thought: thought)
                note = "target=\(targetModel) provider=\(provider) thought=\(thought ?? "nil") thoughts=\(selection.thoughtLevels)"
            }
        default: break
        }
        UserDefaults.standard.set(
            "\(action) workId=\(run.workId ?? run.id) \(note) ack=\(String(describing: ack).prefix(500))",
            forKey: "diag.wf.control")
        UserDefaults.standard.synchronize()
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

    /// 桌面端模型选择变化流（model-selection.onDidChange 驱动 chips 刷新 + 思考档词表重查）
    func observeModelSelection() async {
        for await info in store.observeModelSelection() {
            if let info {
                modelSelection = info
                await refreshThoughtLevels()
            }
        }
    }

    /// 向上分页：滚动触顶（顶部「加载更早消息」）拉更早历史，拼接去重在 Store 内完成
    func loadOlder() async {
        guard isReadOnly, !isLoadingOlder else { return }
        isLoadingOlder = true
        defer { isLoadingOlder = false }
        canLoadOlder = await store.loadOlder(conversationID: conversationID)
        // 顶部插入无法用尾部 append 事件语义表达：直读 Store 全量对齐（事件通道仅作兜底）
        messages = await store.messages(in: conversationID)
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

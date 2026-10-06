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

    /// 会话加载失败文本（连接态订阅/历史行拉取失败的 UI 透出面；nil = 无失败——
    /// 含「订阅/拉取都成功但行为空」的正常空会话，空/载/失败三态据此区分。
    /// 真机报障「二级页面消息区空白且无提示」2026-10-06 修复面）
    var loadFailure: String?

    /// 待发附件（P1-1：相册/拍照/文件三来源；会话维度实例。演示态 UI 不出附件
    /// 入口——设计稿 1.4「非连接态 📎 不渲染」，本服务仅连接态被触达）
    let uploads: AttachmentUploadService

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

    /// P2-5 编辑目标 sheet 显隐（GoalEditSheet 挂 SessionPanelsView 根部；
    /// ✏️ 钮置位，sheet 内下发成功/取消经 dismiss 复位）
    var editingGoalActive = false

    /// 目标下发（sendGoalCommand；store 内 ensureStateRevision + sendCASWithRetry——
    /// 非 §7.2 CAS 权威全集成员但写 state.goal，按 CAS 预期处理，探针裁决）。
    /// 返回错误文案（nil = 成功；成功提示由面板反馈行承担，controlFeedback 口径）
    func sendGoal(_ text: String) async -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return String(localized: "内容不能为空") }
        guard goalSummary != nil else { return String(localized: "当前会话没有可编辑的目标") }
        let ack = await store.sendGoalCommand(conversationID, text: trimmed)
        recordControlDiag("send-goal", workId: nil, ack: ack)
        return Self.controlFeedback(ack, verb: "goal")
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

    // MARK: P1-2 会话模式（plan/build）与投递模式（now/queue/guide）
    //
    // 状态单源在 ViewModel：胶囊 UI 态与 send() 的 requestedDelivery 实参同读此二值，
    // 不存在「UI 一张皮、发送另一张皮」。本地持久化走 ComposerModeStore（设计稿 §2.1
    // 照抄执行目标先例）；协作模式无桌面读面（全 Sources 无投影，设计稿 §2.7③），
    // 首屏显示本机偏好，点按切换即真实 CAS 下发并按回执落态。

    /// 协作模式（"plan"|"build"；switchCollaborationMode CAS 成功后才落态+持久化）
    var collaborationMode: String = ComposerModeStore.collaborationDefault

    /// 投递模式（移动端三档 "now"|"queue"|"guide"。A-2：now 为纯本机档——不下发
    /// setFollowupMode，send 恒携 requestedDelivery:"startNow"；queue/guide CAS
    /// 成功后落态+持久化，并即时驱动后续 send() 的 requestedDelivery 实参）
    var deliveryMode: String = ComposerModeStore.deliveryDefault

    /// 发送时随 sendText 携带的投递参数。A-2 修正（设计稿 §4.1 唯一方案）：
    /// now 档恒携 requestedDelivery:"startNow"（web 立即语义的实证形态——
    /// sendText delivery 枚举 startNow|queue|guide，bundle 实证；web 默认
    /// followupMode=queue，now 档不下发命令后若不带键则桌面仍按 queue 处理）；
    /// queue 形态已探针活体验证；guide 属三路 admission 词表【移植】
    var requestedDeliveryKey: String? {
        deliveryMode == "now" ? "startNow" : deliveryMode
    }

    /// 协作模式切换（Plan/Build 胶囊；switchCollaborationMode CAS）。成功：落态 +
    /// 持久化 + 返回 nil（UI 给选中震动）；失败：态不变，返回拒绝文案供 hint。
    func switchCollaborationMode(_ mode: String) async -> String? {
        let ack = await store.switchCollaborationMode(conversationID, mode: mode)
        recordControlDiag("collabMode=\(mode)", workId: nil, ack: ack)
        if let failure = Self.controlFeedback(ack, verb: "collaborationMode") {
            return failure
        }
        collaborationMode = mode
        ComposerModeStore.setCollaborationMode(mode, conversationID: conversationID)
        return nil
    }

    /// 投递模式切换（立即/排队/引导）。A-2 修正（设计稿 §4.1）：now 档不下发
    /// setFollowupMode（web 枚举仅 queue|guide，「立即」不是 followupMode 值——
    /// 下发必被拒），仅本地落态 + 持久化，立即语义由 send() 携
    /// requestedDelivery:"startNow" 承载；queue/guide 照常 CAS 下发，成功才落态。
    /// 返回 nil = 已生效（UI 给提示）；失败态不变并返回拒绝文案。
    func setDeliveryMode(_ mode: String) async -> String? {
        guard mode != "now" else {
            deliveryMode = mode
            ComposerModeStore.setDeliveryMode(mode, conversationID: conversationID)
            return nil
        }
        let ack = await store.setFollowupMode(conversationID, mode: mode)
        recordControlDiag("followupMode=\(mode)", workId: nil, ack: ack)
        if let failure = Self.controlFeedback(ack, verb: "followupMode") {
            return failure
        }
        deliveryMode = mode
        ComposerModeStore.setDeliveryMode(mode, conversationID: conversationID)
        return nil
    }

    // MARK: P2-7 上下文压缩（compact）

    /// 压缩进行中（确认后置位，完成/失败清除；composer 提示行据此显示「压缩中…」
    /// 且不自动消失——覆盖 switchHint 3s 清除口径）
    var isCompacting = false

    /// 压缩上下文（compact 非 CAS，普通信封直发）。回执 accepted 后等 contextUsage
    /// 回落（state.runtime 回流，1s 轮询最多 5s）再报完成；5s 内无回落也报完成
    /// （回执已 accepted，诚实口径）。返回提示文案（完成或失败）供 3s hint。
    func compactContext() async -> String {
        guard !isCompacting else { return String(localized: "压缩中…") }
        isCompacting = true
        defer { isCompacting = false }
        let ack = await store.compact(conversationID)
        recordControlDiag("compact", workId: nil, ack: ack)
        if let failure = Self.controlFeedback(ack, verb: "compact") {
            return failure
        }
        if let before = contextUsage?.used {
            for _ in 0..<5 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                contextUsage = await store.sessionContextUsage(in: conversationID)
                if let now = contextUsage?.used, now < before { break }
            }
        }
        return String(localized: "压缩完成 · 上下文已释放")
    }

    /// 思考强度切换（同三元组；model 沿用当前绑定）
    func switchThoughtLevel(_ level: String) async -> String? {
        guard let model = modelSelection?.activeModel else {
            return String(localized: "不在连接态，无法切换思考强度")
        }
        return await switchModel(model, thoughtLevel: level)
    }

    // MARK: P1-3 消息反馈与编辑重发（setAssistantFeedback / editUserQuery）

    /// 助手消息反馈回显（会话级内存：message.id → true=赞 / false=踩；命令 accepted
    /// 才写，再点同项 = 取消即移除，赞/踩天然互斥）。服务端 assistant 行的反馈读回
    /// 字段【未取证】（设计稿 §3.7②），重启后清零——若桌面无读回字段则显示未反馈，
    /// 与桌面实态可能不一致，如实降级不做假持久化。
    var assistantFeedback: [String: Bool] = [:]

    /// 点赞/点踩（setAssistantFeedback：CAS+row-target，store 侧 entityId 缺失拒发
    /// 返回 nil——UI 入口已按游标在场门槛渲染，此处兜底再查一次）。返回失败文案
    /// （nil = 成功静默，UI 自行高亮 + 震动）。
    func setAssistantFeedback(_ message: ChatMessage, value: Bool?) async -> String? {
        guard let rowId = Self.messageRowId(message), let entityId = message.entityId, !entityId.isEmpty else {
            return String(localized: "该消息缺少行游标，无法反馈")
        }
        let ack = await store.setAssistantFeedback(
            conversationID, rowId: rowId, entityId: entityId, value: value)
        recordControlDiag("feedback=\(value.map(String.init) ?? "clear") row=\(rowId)", workId: nil, ack: ack)
        if let failure = Self.controlFeedback(ack, verb: "feedback") { return failure }
        if let value {
            assistantFeedback[message.id] = value
        } else {
            assistantFeedback.removeValue(forKey: message.id)
        }
        return nil
    }

    /// 编辑并重发（editUserQuery：rewind 后以新文本重发，与 retryTurn 同族
    /// CAS+row-target）。成功后不做乐观改动——消息流以桌面 rows 键级重建为准
    /// （设计稿 §3.4「乐观不做」）。返回失败文案（nil = 成功，UI dismiss + 震动）。
    func editAndResend(_ message: ChatMessage, newText: String) async -> String? {
        let trimmed = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return String(localized: "内容不能为空") }
        guard let rowId = Self.messageRowId(message), let entityId = message.entityId, !entityId.isEmpty else {
            return String(localized: "该消息缺少行游标，无法重发")
        }
        let ack = await store.editUserQuery(
            conversationID, rowId: rowId, entityId: entityId, newText: trimmed)
        recordControlDiag("editResend row=\(rowId)", workId: nil, ack: ack)
        return Self.controlFeedback(ack, verb: "editResend")
    }

    /// 消息行 rowId（id 形如 "row-<n>"；本地回显 "local-send-*"/"state-todos" 等
    /// 非行合成 id 返回 nil）
    private static func messageRowId(_ message: ChatMessage) -> Int? {
        guard message.id.hasPrefix("row-") else { return nil }
        return Int(message.id.dropFirst(4))
    }

    // MARK: P2-7B 审批卡「稍后处理」（snoozeInteractionAutoResolution）

    /// 本地遮罩的交互 id（「稍后处理」accepted 后桌面 pendingInteractions 回流 >1s
    /// 未至的 UI 兜底——仅过滤投影，不改 store state 合并纪律，设计稿 §7B；
    /// 降级路径「关闭」纯本地收起同用此遮罩）
    var snoozedInteractionIds: Set<String> = []

    /// 「稍后处理」反馈行（composer 上方卡片区间一行 hint，3s 自动清除——卡片可能
    /// 随即被回流撤下，提示需在卡片之外存活；switchHint 同款一行橙字口径）
    var snoozeFeedback: String?
    private var snoozeFeedbackTask: Task<Void, Never>?

    /// 旧桌面端无 snooze 命令（rejected 且 reasonCode/message 宽容匹配 unknown
    /// 【未取证降级判定】）→ 审批卡按钮降级为纯关闭
    var snoozeUnsupported = false

    /// 「稍后处理」下发（snoozeInteractionAutoResolution {interactionId}；命令本体/
    /// 回执/桌面重提醒行为全部未取证，payload 键名照 resolveInteraction 实证先例）。
    /// 成功：优先依赖桌面回流撤卡，>1s 未至本地遮罩兜底。返回失败文案（nil = 成功，
    /// 成功/失败 hint 均由本方法经 snoozeFeedback 通道给出）。
    @discardableResult
    func snoozeInteraction(_ interaction: RemotePendingInteraction) async -> String? {
        let ack = await store.snoozeInteractionAutoResolution(
            conversationID, interactionId: interaction.id)
        recordControlDiag("snooze", workId: interaction.id, ack: ack)
        if let failure = Self.controlFeedback(ack, verb: "snooze") {
            let reason = (ack?["reasonCode"]?.stringValue ?? "")
                + " " + (ack?["message"]?.stringValue ?? "")
            snoozeUnsupported = reason.lowercased().contains("unknown")
            showSnoozeFeedback(String(localized: "稍后失败 · \(failure)"))
            return failure
        }
        showSnoozeFeedback(String(localized: "已稍后 · 桌面端稍后会再次提醒"))
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        // 回流撤卡优先：立即刷新仍在场 → 1s 后再刷一次 → 仍未至本地遮罩兜底
        await refreshPendingInteractions()
        if pendingInteractions.contains(where: { $0.id == interaction.id }) {
            Task {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                await refreshPendingInteractions()
                if pendingInteractions.contains(where: { $0.id == interaction.id }) {
                    snoozedInteractionIds.insert(interaction.id)
                    await refreshPendingInteractions()
                }
            }
        }
        return nil
    }

    /// 纯本地收起（降级路径「关闭」：仅 UI 遮罩，不下发命令、不改桌面挂起态）
    func dismissInteractionLocally(_ interaction: RemotePendingInteraction) async {
        snoozedInteractionIds.insert(interaction.id)
        await refreshPendingInteractions()
    }

    private func showSnoozeFeedback(_ text: String) {
        snoozeFeedbackTask?.cancel()
        snoozeFeedback = text
        snoozeFeedbackTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            snoozeFeedback = nil
        }
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
        self.uploads = AttachmentUploadService(store: store, sessionID: conversationID)
        // P1-2 模式偏好恢复（per-conversation → 全局默认；协作模式无桌面读面，
        // 首屏即本机偏好，点按切换才真实下发）
        self.collaborationMode = ComposerModeStore.collaborationMode(for: conversationID)
        self.deliveryMode = ComposerModeStore.deliveryMode(for: conversationID)
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
        // —— P0 首屏段：消息先行（本连接缓存命中=纯内存，立即渲染），不等跨区
        // 任务索引聚合（26 区 listTaskList 串行）与已读上报 ——
        messages = await store.messages(in: conversationID)
        // 失败透出（「订阅/拉取失败 UI 不可见」修复）：有消息=成功；空列表时读 store
        // 最近一次失败——nil = 正常空会话（错误态与空态可区分）
        loadFailure = messages.isEmpty
            ? await store.conversationLoadFailure(in: conversationID) : nil
        // 标题等元数据先行取纯内存投影（零 RPC；后台段全量对账后回填）
        conversation = await store.cachedConversation(conversationID)
        isLoading = false
        // 消息区自动恢复（切换失败回滚/中继瞬断兜底）：load 以失败收尾时退避自动重试，
        // 不必等用户点「重试」；成功收尾则复位预算并撤销挂起的重试
        if loadFailure != nil {
            scheduleAutoRetry()
        } else {
            autoRetryCount = 0
            autoRetryTask?.cancel()
        }
        // —— P0 伴生段：chips/审批/面板投影（多为内存快照读，不再阻塞消息区）——
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
        // —— P0 后台段：重数据与写面不阻塞首屏——跨区任务索引聚合（取会话元数据
        // 全量对账）+ 已读上报；完成后回填 conversation ----
        backgroundLoadTask?.cancel()
        backgroundLoadTask = Task { [weak self] in
            guard let self else { return }
            let all = await self.store.conversations()
            if let found = all.first(where: { $0.id == self.conversationID }) {
                self.conversation = found
            }
            await self.store.markRead(conversationID: self.conversationID)
        }
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

    // MARK: 消息区自动恢复（切换失败/瞬断兜底链）

    /// load 失败后的退避自动重试预算与挂起任务。场景：工作区切换在途会中断全部在途
    /// RPC（RelayChannelClient.switchBridgeWorkspace 按重连同口径失败）——切换失败
    /// 回滚虽会重发 eventListen（订阅存活则快照自动回流、loadFailure 随
    /// messagesReplaced 清除），但订阅本身在切换窗口被打断时无人重新触发订阅/rows
    /// 拉取，消息区只能停在错误态等手动重试；此处 1.2s/3s 两轮退避自动重入，仍失败
    /// 保持错误态供手动重试（AGENTS §5.8 中继瞬断 1.2s 退避重试同口径）。
    private var autoRetryCount = 0
    private var autoRetryTask: Task<Void, Never>?
    private let autoRetryLimit = 2

    /// 后台补齐任务（P0 首屏提速：跨区任务索引聚合 + 已读上报移出首屏链路；
    /// 重入 load 时取消旧任务避免重复聚合）
    private var backgroundLoadTask: Task<Void, Never>?

    private func scheduleAutoRetry() {
        guard isReadOnly, autoRetryCount < autoRetryLimit else { return }
        autoRetryCount += 1
        let delayNanos: UInt64 = autoRetryCount == 1 ? 1_200_000_000 : 3_000_000_000
        autoRetryTask?.cancel()
        autoRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delayNanos)
            guard let self, !Task.isCancelled else { return }
            // 已恢复（订阅回流清 loadFailure）或正在加载（新一轮 load 在途）则放弃
            guard self.loadFailure != nil, !self.isLoading else { return }
            await self.reloadAfterFailure()
        }
    }

    /// 错误态重试（消息区错误卡「重试」钮）：直重入 load()——store 侧订阅守卫对失败
    /// 态放行（仅成功订阅去重）、rows 空表重新分页拉取；期间 isLoading 置位呈加载态。
    /// 手动重试重置自动重试预算（用户显式要求 = 新一轮兜底）。
    /// 连接恢复换源（store 身份变化）由 ChatView .task(id:) 重建 VM 自动兜底。
    func retryLoad() async {
        guard loadFailure != nil else { return }
        autoRetryCount = 0
        await reloadAfterFailure()
    }

    /// 重入 load（手动/自动共用；自动路径不重置预算——重试仍失败继续按退避计次）
    private func reloadAfterFailure() async {
        loadFailure = nil
        isLoading = true
        await load()
    }

    /// 订阅数据层事件（流式输出逐字经此刷新）
    func observe() async {
        for await event in store.observeConversations() {
            // 会话级模型选择随 state delta 到达（便宜：内存字典读），逐事件覆盖
            await refreshSessionModelOverlay()
            switch event {
            case .conversationsReplaced(let list):
                if let match = list.first(where: { $0.id == conversationID }) {
                    conversation = match
                }
            case .conversationUpdated(let updated) where updated.id == conversationID:
                conversation = updated
            case .messagesReplaced(let id, let replaced) where id == conversationID:
                messages = replaced
                // 帧已抵达 = 订阅存活：清除失败态（重试成功/自动恢复共用此口）
                if !replaced.isEmpty { loadFailure = nil }
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

    /// 待处理交互投影刷新（连接态；演示态空。「稍后处理」本地遮罩在此过滤——
    /// 仅 UI 层，store 原始投影不动，§7B 口径）
    func refreshPendingInteractions() async {
        guard isReadOnly else { return }
        let all = await store.pendingInteractionList(in: conversationID)
        pendingInteractions = all.filter { !snoozedInteractionIds.contains($0.id) }
    }

    /// 审批卡决议（A-3：权限族 answer={optionId}——四族 allowOnce/allowAlways/
    /// rejectOnce/rejectAlways 由 UI 按服务端 options 组合拼好传入）。
    /// 返回失败文案（nil = 成功/已受理；未送达与拒绝均如实透出，U-5 同口径）
    @discardableResult
    func decide(_ interaction: RemotePendingInteraction, optionId: String) async -> String? {
        let ack = await store.resolveInteractionRaw(
            conversationID,
            interactionId: interaction.id,
            answer: .object(["optionId": .string(optionId)]))
        recordControlDiag("resolve optionId=\(optionId)", workId: interaction.id, ack: ack)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "审批")
    }

    /// 计划决议（A-3：计划族 answer={action:"accept"|"decline"}；cancel 语义由
    /// 「稍后处理」snooze 独立命令承担，不设按钮——设计稿 §3.3）。返回失败文案。
    @discardableResult
    func decidePlan(_ interaction: RemotePendingInteraction, action: String) async -> String? {
        let ack = await store.resolveInteractionRaw(
            conversationID,
            interactionId: interaction.id,
            answer: .object(["action": .string(action)]))
        recordControlDiag("planResolve action=\(action)", workId: interaction.id, ack: ack)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "计划决议")
    }

    // MARK: workspace hook 信任审核（web 对齐：respond/request/revoke；返回失败文案，
    // nil = 成功/已受理——与 decide/decidePlan 同口径）

    /// 信任所选审核项（respondWorkspaceHookReview decision:{action:'trust_selected',
    /// reviewItemIds}，web 实证唯一 action）。成功后卡片由桌面 pendingInteractions
    /// 回流撤下（refresh 兜底）。
    @discardableResult
    func trustWorkspaceHooks(
        _ interaction: RemotePendingInteraction, reviewItemIds: [String]
    ) async -> String? {
        let ack = await store.respondWorkspaceHookReview(conversationID, reviewItemIds: reviewItemIds)
        recordControlDiag("hookTrust ids=\(reviewItemIds.count)", workId: interaction.id, ack: ack)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "信任")
    }

    /// 重新请求审核（requestWorkspaceHookReview；payload 缓存不在场/无 digest 时
    /// 远端实现返回 nil → 如实提示，不虚构「已请求」）
    @discardableResult
    func requestWorkspaceHookReview() async -> String? {
        let ack = await store.requestWorkspaceHookReview(conversationID)
        recordControlDiag("hookReviewRequest", workId: nil, ack: ack)
        return Self.controlFeedback(ack, verb: "请求审核")
    }

    /// 撤销已信任 hook 项（revokeWorkspaceHookTrust；写桌面信任账本——UI 侧确认
    /// 弹层后才可调用本方法）
    @discardableResult
    func revokeWorkspaceHookTrust(reviewItemIds: [String]) async -> String? {
        let ack = await store.revokeWorkspaceHookTrust(conversationID, reviewItemIds: reviewItemIds)
        recordControlDiag("hookRevoke ids=\(reviewItemIds.count)", workId: nil, ack: ack)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "撤销信任")
    }

    /// 桌面端模型选择变化流（model-selection.onDidChange 驱动 chips 刷新 + 思考档词表重查）
    func observeModelSelection() async {
        for await info in store.observeModelSelection() {
            if let info {
                modelSelection = info
                await refreshThoughtLevels()
                await refreshSessionModelOverlay()
            }
        }
    }

    /// 会话级模型选择覆盖（用户报障「桌面改了手机不同步」：桌面 composer 改的是
    /// state.modelSelection——会话级，不走 workspace 级 onDidChange；以 state 为
    /// 权威源覆盖 chips 显示，词表/套餐分组仍取 getView）
    func refreshSessionModelOverlay() async {
        guard isReadOnly,
              let session = await store.sessionModelSelection(in: conversationID) else { return }
        if modelSelection == nil {
            modelSelection = await store.modelSelectionView() ?? ModelSelectionInfo()
        }
        guard modelSelection?.activeModel != session.model
            || modelSelection?.activeThoughtLevel != session.thought else { return }
        modelSelection?.activeModel = session.model
        modelSelection?.activeThoughtLevel = session.thought
        await refreshThoughtLevels()
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

    /// 发送可用：无待发附件沿用 draft 非空口径；有待发附件时须全部 committed
    /// （P1-1 设计稿 1.3③④——上传中禁发、纯附件无文本不允许发送）
    var canSend: Bool {
        !draft.isEmpty && !uploads.blocksSend
    }

    /// U-6：最近一次 send 未送达时回填的文本（nil = 最近一次发送成功，或未真正
    /// 发出——空稿/附件未就绪等本地 guard 路径不算「未送达」，附件失败走
    /// uploads.firstFailureText 通道）。composer 据此渲染持久错误行 + 重试钮。
    var lastSendUndeliveredText: String?

    /// 发送（P1-2 投递联动）：按当前投递模式携带 requestedDelivery——now 档恒携
    /// "startNow"（A-2 修正，见 requestedDeliveryKey），queue/guide 随 sendText
    /// 走对应 admission。返回是否送达（false=未送达，连接中断或桌面拒收）。
    /// U-6：失败时草稿回填（仅当用户尚未重新输入——失败时刻 draft 为空才写回，
    /// 避免覆盖新输入）并记 lastSendUndeliveredText 供 composer 错误行。
    @discardableResult
    func send() async -> Bool {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        // P1-1 发送联动：未完成附件先顺序补传（按钮禁用为主闸，此处为 onSubmit
        // 等旁路兜底）；仍有失败项则本次不发送（直发文本会丢附件），保留失败态
        guard await uploads.ensureAllCommitted() else { return false }
        let attachments = uploads.takeCommitted()
        draft = ""
        let delivered = await store.sendWithAttachments(
            text, attachments: attachments, requestedDelivery: requestedDeliveryKey,
            in: conversationID)
        if delivered {
            lastSendUndeliveredText = nil
        } else {
            // 消息未送达：文本回填保稿（附件已随事务消费，不回填——重试为文本路径，
            // 设计稿 §5.1 连带说明）
            if draft.isEmpty {
                draft = text
            }
            lastSendUndeliveredText = text
        }
        return delivered
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

// MARK: - P1-2 composer 模式偏好持久化（协作模式 + 投递模式）
//
// ExecutionTargetStore 同构（ContextPicker.swift：per-conversation 键 + 全局默认，
// 写时双写）；值域白名单校验防手改 plist 的脏值逃过枚举。协作模式无桌面读面，
// 持久值即首屏显示值（设计稿 §2.7③ 如实口径）；投递模式兼作 send()
// requestedDelivery 的实参源——成功切换才写入，保证「UI 态 = 发送值」。
enum ComposerModeStore {
    /// 协作模式词表（switchCollaborationMode mode 值域；设计稿 §2 标题词）
    static let collaborationModes = ["plan", "build"]
    /// 投递模式词表（移动端三档。A-2：queue/guide 下发 setFollowupMode【实证 bundle
    /// 枚举】并随 sendText 走对应 admission；now 为纯本机档——不下发命令，
    /// send 恒携 requestedDelivery:"startNow"（sendText delivery 枚举【实证】））
    static let deliveryModes = ["now", "queue", "guide"]

    static let collaborationDefault = "plan"
    static let deliveryDefault = "now"

    private static func key(_ prefix: String, conversationID: String) -> String {
        "chat.\(prefix).\(conversationID)"
    }
    private static let collaborationDefaultKey = "chat.collabMode.default.v1"
    private static let deliveryDefaultKey = "chat.deliveryMode.default.v1"

    /// 该会话的协作模式；未单独选择过回退全局默认；再无则 Plan
    static func collaborationMode(for conversationID: String) -> String {
        valid(UserDefaults.standard.string(forKey: key("collabMode", conversationID: conversationID)),
              collaborationModes)
            ?? valid(UserDefaults.standard.string(forKey: collaborationDefaultKey), collaborationModes)
            ?? collaborationDefault
    }

    static func setCollaborationMode(_ mode: String, conversationID: String) {
        UserDefaults.standard.set(mode, forKey: key("collabMode", conversationID: conversationID))
        UserDefaults.standard.set(mode, forKey: collaborationDefaultKey)
    }

    /// 该会话的投递模式；未单独选择过回退全局默认；再无则立即发送
    static func deliveryMode(for conversationID: String) -> String {
        valid(UserDefaults.standard.string(forKey: key("deliveryMode", conversationID: conversationID)),
              deliveryModes)
            ?? valid(UserDefaults.standard.string(forKey: deliveryDefaultKey), deliveryModes)
            ?? deliveryDefault
    }

    static func setDeliveryMode(_ mode: String, conversationID: String) {
        UserDefaults.standard.set(mode, forKey: key("deliveryMode", conversationID: conversationID))
        UserDefaults.standard.set(mode, forKey: deliveryDefaultKey)
    }

    private static func valid(_ value: String?, _ allowed: [String]) -> String? {
        guard let value, allowed.contains(value) else { return nil }
        return value
    }
}

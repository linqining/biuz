import SwiftUI

// MARK: - 待批 tab（2026-11-25 重设计裁决 R2，design/design-spec.md §3.1）
//
// 跨会话审批收件箱，数据两层：
//  1. 摘要层（廉价常驻）：sessions-index 行的 pendingApprovalCount 投影
//     （RemoteConversationStore.sortedConversations 逐行投影，pendingInteractionSummary
//     【实证·上游仓 sessions-index.ts:23-27】——只有 kind/count，无敏感载荷）。
//  2. 卡片层（按需）：摘要行点开才订阅该会话（messages(in:) → subscribeConversationV4
//     → pendingInteractions 内联卡）。**挂载零订阅**——首版曾「前 8 个待批会话随 tab
//     挂载全部预订阅」，冷启即挂 8 路会话帧流，且订阅先于任何「全新订阅=权威快照」
//     语义（E2E test15 的快照抑制因此失效、CAS 本地门被旧 revision 放行——2026-11-25
//     test15 门禁红 + 基线对照实验实证）。决议卡与会话页同一套组件（协议
//     InteractionDeciding 复用）、同一决议链路（store 唯一出口）与落印停驻。
// 已知边界（真机取证项，登记于 design-spec §3.1）：sessions-index 是否跨工作区
// 携带待批摘要未证——跨区会话待批可能缺席；缺席行不给卡（不做假可供性）。

/// 单个待批会话的轻量模型：与 ChatViewModel 的决议族逐字同构（store 调用是唯一
/// 出口；U-5 失败口径/落印停驻一致）。不含消息流/面板/composer 面——待批页只
/// 消费 pendingInteractions 投影。
@MainActor
@Observable
final class ApprovalSessionModel: InteractionDeciding {
    let conversationID: String
    var title: String
    private let store: ConversationStore

    var isLoading = true
    /// 慢加载标记：订阅超时/失败的兜底（2026-11-25 用户报「点击载入详情一直转」——
    /// messages(in:) 订阅在桌面忙/超时时会长时间不返回，此前只有一个无出口的转圈）。
    /// 超时后 UI 给「在会话中查看」明确出口，不再让用户干等。
    var loadSlow = false
    var pendingInteractions: [RemotePendingInteraction] = []
    var stampHolds: [String: StampHold] = [:]
    var snoozeFeedback: String?
    var snoozeUnsupported = false
    /// 本地遮罩（「稍后处理」/降级「关闭」；仅过滤投影，store 原始投影不动）
    var snoozedInteractionIds: Set<String> = []

    private var observeTask: Task<Void, Never>?
    private var snoozeFeedbackTask: Task<Void, Never>?

    init(store: ConversationStore, conversation: Conversation) {
        self.store = store
        self.conversationID = conversation.id
        self.title = conversation.title
    }

    /// 展开该会话（唯一订阅入口）：messages(in:) 幂等触发 subscribeConversationV4
    /// 并保持 pendingInteractions 投影刷新；事件循环与 ChatViewModel.observe 同源
    /// （observeConversations 全局流，按 id 过滤）。
    func start() async {
        guard observeTask == nil else { return }
        // 慢加载看门狗：6s 未取到投影就置 loadSlow（订阅本身不取消——到货仍会刷新）
        let slowTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard !Task.isCancelled else { return }
            guard let self, self.isLoading else { return }
            self.loadSlow = true
        }
        _ = await store.messages(in: conversationID)
        slowTask.cancel()
        await refreshPendingInteractions()
        isLoading = false
        loadSlow = false
        observeTask = Task { [weak self] in
            await self?.observeLoop()
        }
    }

    /// 收起该会话：取消观察 + 关闭订阅（keep-warm 30s 窗口内重开展开 = 缓存命中）。
    func stop() {
        observeTask?.cancel()
        observeTask = nil
        snoozeFeedbackTask?.cancel()
        let id = conversationID
        let closingStore = store
        Task { await closingStore.closeConversation(id) }
    }

    private func observeLoop() async {
        for await event in store.observeConversations() {
            switch event {
            case .conversationsReplaced(let list):
                if let match = list.first(where: { $0.id == conversationID }) {
                    title = match.title
                }
            case .conversationUpdated(let updated) where updated.id == conversationID:
                title = updated.title
            default:
                break
            }
            // 任意事件后轻量刷新待审批投影（state.updated 无独立事件语义，同 ChatViewModel 口径）
            await refreshPendingInteractions()
        }
    }

    /// 待处理交互投影刷新（停驻顶住 + snooze 遮罩过滤，ChatViewModel 同构）
    func refreshPendingInteractions() async {
        let all = await store.pendingInteractionList(in: conversationID)
        var merged = all.filter { !snoozedInteractionIds.contains($0.id) }
        for hold in stampHolds.values
        where !merged.contains(where: { $0.id == hold.interaction.id }) {
            merged.append(hold.interaction)
        }
        pendingInteractions = merged
    }

    /// ACK 权威成功 → 置盖章停驻并排期满摘除（失败/未知态不落印，U-5）。
    /// 停驻窗复用 ChatViewModel.stampHoldNanos（-ZCodeE2EStampHold 同钩子）。
    private func beginStampIfAuthoritative(
        _ interaction: RemotePendingInteraction, ack: JSONValue?, approved: Bool
    ) {
        guard let ack, CommandAck(ack).isAuthoritativeSuccess else { return }
        guard stampHolds[interaction.id] == nil else { return }
        stampHolds[interaction.id] = StampHold(interaction: interaction, approved: approved)
        let id = interaction.id
        Task {
            try? await Task.sleep(nanoseconds: ChatViewModel.stampHoldNanos)
            stampHolds[id] = nil
            await refreshPendingInteractions()
        }
    }

    /// 命令回执 → 人类可读反馈（nil = 成功；ChatViewModel.controlFeedback 同构，
    /// CommandAck 统一解析六态词表 + reasonCode/message 原文）
    private static func controlFeedback(_ ack: JSONValue?, verb: String) -> String? {
        guard ack != nil else {
            return String(localized: "命令未送达（连接中断或不在连接态）")
        }
        return CommandAck(ack).failureText
    }

    // MARK: 决议族（InteractionDeciding；口径与 ChatViewModel 逐字同构）

    @discardableResult
    func decide(
        _ interaction: RemotePendingInteraction, optionId: String, approved: Bool,
        feedback: String? = nil
    ) async -> String? {
        var answer: [String: JSONValue] = ["optionId": .string(optionId)]
        if let feedback = feedback?.trimmingCharacters(in: .whitespacesAndNewlines),
           !feedback.isEmpty {
            answer["freeText"] = .string(feedback)
        }
        let ack = await store.resolveInteractionRaw(
            conversationID,
            interactionId: interaction.id,
            answer: .object(answer))
        beginStampIfAuthoritative(interaction, ack: ack, approved: approved)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "审批")
    }

    @discardableResult
    func decidePlan(_ interaction: RemotePendingInteraction, action: String) async -> String? {
        let ack = await store.resolveInteractionRaw(
            conversationID,
            interactionId: interaction.id,
            answer: .object(["action": .string(action)]))
        beginStampIfAuthoritative(interaction, ack: ack, approved: action == "accept")
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "计划决议")
    }

    @discardableResult
    func resolveUserInput(_ interaction: RemotePendingInteraction, answer: JSONValue) async -> String? {
        let ack = await store.resolveInteractionRaw(
            conversationID,
            interactionId: interaction.id,
            answer: answer)
        if answer["action"]?.stringValue != "cancel" {
            beginStampIfAuthoritative(interaction, ack: ack, approved: true)
        }
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "回答")
    }

    @discardableResult
    func snoozeInteraction(_ interaction: RemotePendingInteraction) async -> String? {
        let ack = await store.snoozeInteractionAutoResolution(
            conversationID, interactionId: interaction.id)
        if let failure = Self.controlFeedback(ack, verb: "snooze") {
            let reason = (ack?["reasonCode"]?.stringValue ?? "")
                + " " + (ack?["message"]?.stringValue ?? "")
            snoozeUnsupported = reason.lowercased().contains("unknown")
            showSnoozeFeedback(String(localized: "稍后失败 · \(failure)"))
            return failure
        }
        showSnoozeFeedback(String(localized: "已稍后 · 桌面端稍后会再次提醒"))
        UINotificationFeedbackGenerator().notificationOccurred(.success)
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

    func dismissInteractionLocally(_ interaction: RemotePendingInteraction) async {
        snoozedInteractionIds.insert(interaction.id)
        await refreshPendingInteractions()
    }

    // MARK: hook 审核卡闭包面（WorkspaceHookReviewCard 三闭包的落点）

    @discardableResult
    func trustWorkspaceHooks(
        _ interaction: RemotePendingInteraction, reviewItemIds: [String]
    ) async -> String? {
        let ack = await store.respondWorkspaceHookReview(conversationID, reviewItemIds: reviewItemIds)
        beginStampIfAuthoritative(interaction, ack: ack, approved: true)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "信任")
    }

    @discardableResult
    func requestWorkspaceHookReview() async -> String? {
        let ack = await store.requestWorkspaceHookReview(conversationID)
        return Self.controlFeedback(ack, verb: "请求审核")
    }

    @discardableResult
    func revokeWorkspaceHookTrust(reviewItemIds: [String]) async -> String? {
        let ack = await store.revokeWorkspaceHookTrust(conversationID, reviewItemIds: reviewItemIds)
        await refreshPendingInteractions()
        return Self.controlFeedback(ack, verb: "撤销信任")
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
}

// MARK: - 视图

/// 待批收件箱：摘要行列表，行点开 = 手风琴展开该会话的决议卡（按需订阅）。
/// 订阅生命周期 = 展开态；收起或离页 stop()（keep-warm 30s 窗口内再展开 = 缓存命中）。
struct PendingApprovalsView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    @Environment(AppSession.self) private var session

    /// 摘要层（sessions-index 投影；updatedAt 降序）
    @State private var pendingConversations: [Conversation] = []
    /// 卡片层模型（按展开差量启停；id 键控）
    @State private var models: [String: ApprovalSessionModel] = [:]
    @State private var modelsTask: [String: Task<Void, Never>] = [:]
    /// 展开态（手风琴；单开——展开新的收起旧的，屏面与订阅成本都有界）
    @State private var expandedID: String?
    @State private var isLoading = true

    /// 展开上限（超出者仅摘要行 +「在会话中处理」；订阅成本有界）
    static let inlineLimit = 8

    var body: some View {
        Group {
            if isLoading {
                CenterLoadingView(text: "正在同步待批操作…")
                    .accessibilityIdentifier("09-loading-center")
            } else if pendingConversations.isEmpty {
                EmptyStateView(
                    icon: "checkmark.seal",
                    title: "没有待你批准的操作",
                    detail: "桌面端在执行中需要你确认的命令或问题，会出现在这里")
                    .accessibilityIdentifier("09-approvals-empty")
            } else {
                list
            }
        }
        // 铺满 + 忽略安全区（2026-11-25 用户报「审批页是白底，设计不符」）：此前
        // `.background(T.bg)` 挂在 Group 上，Group 只占内容高度——空态/加载态居中时
        // 上下露出窗口默认白底，屏上就是「中间一条纸、上下白」。
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(T.bg.ignoresSafeArea())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: T.sp2) {
                    Text("待批").font(T.font(17, .bold)).foregroundColor(T.text)
                    if !pendingConversations.isEmpty {
                        Text("\(pendingConversations.count)")
                            .font(T.mono(12, .semibold))
                            .foregroundColor(T.onSeal)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 1)
                            .background(T.seal)
                            .clipShape(Capsule())
                    }
                }
            }
        }
        // store 换源重绑（mock→remote）：与 ConversationListView 同口径
        .task(id: ObjectIdentifier(store)) {
            await reload()
            for await event in store.observeConversations() {
                if case .conversationsReplaced(let list) = event {
                    apply(list)
                }
            }
        }
        .onDisappear { teardownModels() }
    }

    private var list: some View {
        List {
            ForEach(Array(pendingConversations.enumerated()), id: \.element.id) { index, conversation in
                Section {
                    if index < Self.inlineLimit {
                        sessionSection(conversation)
                    } else {
                        summaryRow(conversation)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollIndicators(.hidden)
    }

    /// 会话段：头行（手风琴开关）+ 展开时的决议卡（与会话页同一套组件与落印）。
    /// 展开即 start()（唯一订阅入口），收起即 stop()——挂载零订阅。
    @ViewBuilder
    private func sessionSection(_ conversation: Conversation) -> some View {
        let isExpanded = expandedID == conversation.id
        Button {
            toggleExpansion(conversation)
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: isExpanded ? "checkmark.seal.fill" : "checkmark.seal")
                    .font(.system(size: 14))
                    .foregroundColor(T.seal)
                VStack(alignment: .leading, spacing: 1) {
                    Text(conversation.title)
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    Text(expandSubtitle(conversation))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(T.text3)
                    .rotationEffect(.degrees(isExpanded ? 90 : 0))
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("09-approvals-row-\(conversation.id)")
        if isExpanded, let model = models[conversation.id] {
            expandedCards(conversation, model: model)
        }
    }

    private func expandSubtitle(_ conversation: Conversation) -> String {
        if expandedID == conversation.id {
            return String(localized: "收起明细")
        }
        let kinds = conversation.pendingApprovalCount > 0
            ? String(localized: "\(conversation.pendingApprovalCount) 项待确认 · 点开处理")
            : String(localized: "点开处理")
        return kinds
    }

    /// 展开态：载入行 / 决议卡 / snooze 反馈行
    @ViewBuilder
    private func expandedCards(_ conversation: Conversation, model: ApprovalSessionModel) -> some View {
        if model.isLoading {
            HStack(spacing: T.sp2) {
                SpinnerView(size: 13)
                Text(model.loadSlow
                    ? String(localized: "详情加载较慢（桌面忙）…")
                    : String(localized: "正在载入待批详情…"))
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
                Spacer(minLength: 0)
                // 出口常显：这是从会话来的条目，随时可跳过去看完整上下文
                Button {
                    router.openChatFromApprovals(conversationID: conversation.id)
                } label: {
                    Text(String(localized: "在会话中查看"))
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(T.seal)
                        .frame(minHeight: 32)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("09-approvals-open-chat-\(conversation.id)")
            }
            .padding(.vertical, 6)
            .accessibilityIdentifier("09-approvals-loading-\(conversation.id)")
        }
        ForEach(model.pendingInteractions) { interaction in
            Group {
                if interaction.isPlanApproval {
                    PlanApprovalCard(viewModel: model, interaction: interaction)
                } else if interaction.isWorkspaceHookReview {
                    WorkspaceHookReviewCard(
                        interaction: interaction,
                        onTrust: { ids in
                            await model.trustWorkspaceHooks(interaction, reviewItemIds: ids)
                        },
                        onRequest: {
                            await model.requestWorkspaceHookReview()
                        },
                        onRevoke: { ids in
                            await model.revokeWorkspaceHookTrust(reviewItemIds: ids)
                        })
                } else if interaction.isUserInput {
                    UserInputInteractionCard(viewModel: model, interaction: interaction)
                } else if interaction.isPermission
                            || interaction.kind.lowercased().contains("escalation") {
                    ApprovalInteractionCard(viewModel: model, interaction: interaction)
                } else {
                    // 未知 kind：与会话页同口径（不渲染，不猜测应答命令）
                    EmptyView()
                }
            }
            .modifier(ApprovalCardKeyboardBound(
                maxHeight: interaction.isUserInput ? 360 : 240))
            .modifier(ApprovalStampOverlay(viewModel: model, interactionId: interaction.id))
        }
        if let snoozeFeedback = model.snoozeFeedback {
            Text(snoozeFeedback)
                .font(T.font(10.5))
                .foregroundColor(T.orange)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("09-approval-snooze-hint-\(conversation.id)")
        }
    }

    /// 摘要行（>8 名次或不可展开时；无卡不做假按钮——直接跳会话处理）
    private func summaryRow(_ conversation: Conversation) -> some View {
        Button {
            router.openChatFromApprovals(conversationID: conversation.id)
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "checkmark.seal")
                    .font(.system(size: 14))
                    .foregroundColor(T.seal)
                VStack(alignment: .leading, spacing: 1) {
                    Text(conversation.title)
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    Text(String(localized: "等待确认 · 在会话中处理"))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(T.text3)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("09-approvals-open-\(conversation.id)")
    }

    // MARK: 数据

    private func apply(_ list: [Conversation]) {
        pendingConversations = list
            .filter { $0.pendingApprovalCount > 0 && !$0.isArchived }
            .sorted { $0.updatedAt > $1.updatedAt }
        isLoading = false
        syncModels()
    }

    private func reload() async {
        apply(await store.conversations())
    }

    /// 手风琴开关：展开新的（单开）收起旧的；展开 → start()（订阅），收起 → stop()
    private func toggleExpansion(_ conversation: Conversation) {
        let id = conversation.id
        if expandedID == id {
            collapse(id)
            UISelectionFeedbackGenerator().selectionChanged()
            return
        }
        if let previous = expandedID {
            collapse(previous)
        }
        expandedID = id
        if models[id] == nil {
            let model = ApprovalSessionModel(store: store, conversation: conversation)
            models[id] = model
            modelsTask[id] = Task { await model.start() }
        }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func collapse(_ id: String) {
        if expandedID == id { expandedID = nil }
        modelsTask[id]?.cancel()
        modelsTask[id] = nil
        models[id]?.stop()
        models.removeValue(forKey: id)
    }

    /// 摘要刷新后差量对齐：掉出列表/超上限的展开态收起（订阅不悬挂）
    private func syncModels() {
        let wanted = Set(pendingConversations.prefix(Self.inlineLimit).map(\.id))
        for id in models.keys where !wanted.contains(id) {
            collapse(id)
        }
        if let expanded = expandedID, !wanted.contains(expanded) {
            collapse(expanded)
        }
    }

    private func teardownModels() {
        for (id, task) in modelsTask {
            task.cancel()
            modelsTask[id] = nil
            models[id]?.stop()
        }
        models.removeAll()
        expandedID = nil
    }
}

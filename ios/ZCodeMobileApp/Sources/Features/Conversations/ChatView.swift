import SwiftUI

/// 屏 05 · Agent 对话（Push L2）
/// 连接态与演示态共用发送/应答链路（v3 纠偏：客户端发命令、桌面代执行）；
/// 连接态增量面：桌面端模型只读 chips、待审批交互卡、向上分页。
struct ChatView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    let conversationID: String

    @State private var viewModel: ChatViewModel?
    @State private var observeTask: Task<Void, Never>?
    /// loadOlder 顶部插入识别：插入后的首次 count 变化不滚底（保持阅读位置）
    @State private var prependGuardFirstID: String?

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel)
            } else {
                CenterLoadingView(text: "正在载入会话…")
                    .accessibilityIdentifier("05-loading-center")
            }
        }
        .background(T.bg)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(false)
        .toolbarBackground(T.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    withAnimation(.easeOut(duration: 0.15)) {
                        let turningOn = viewModel?.isSearchActive != true
                        viewModel?.isSearchActive.toggle()
                        if !turningOn {
                            viewModel?.searchQuery = ""
                        } else if viewModel?.canLoadOlder == true {
                            // 检索前自动向上扩页一次（P2-3：扩大已加载检索范围）
                            Task { await viewModel?.loadOlder() }
                        }
                    }
                } label: {
                    Image(systemName: viewModel?.isSearchActive == true ? "xmark.circle" : "magnifyingglass")
                        .font(.system(size: 15))
                        .foregroundColor(T.text2)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("05-act-search")
            }
        }
        // store 换源重绑（mock→remote 装配完成）：冷启自动重连架构下，连接完成前进
        // 会话页会绑到 mock store——空列表+演示 chips 且永不自愈（同 ConversationListView
        // 的 .task(id:) 修复口径）。旧观察流经 Task.cancel 终结（AsyncStream 取消语义）。
        .task(id: ObjectIdentifier(store)) {
            observeTask?.cancel()
            let vm = ChatViewModel(store: store, conversationID: conversationID)
            viewModel = vm
            observeTask = Task {
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { await vm.observe() }
                    // 桌面端模型选择变化流（连接态 onDidChange 驱动 chips 只读刷新；演示态流立即结束）
                    group.addTask { await vm.observeModelSelection() }
                }
            }
            await vm.load()
        }
    }

    private func content(_ viewModel: ChatViewModel) -> some View {
        VStack(spacing: 0) {
            header(viewModel)
            // 会话面板区（goal/plan/工作流/btw/子代理 chips + 单开面板；默认收起为一行
            // chips 不占消息流空间；面板体内部滚动、有界高度，数据缺席不渲染）
            if viewModel.hasAnyPanel {
                SessionPanelsView(viewModel: viewModel)
                    .padding(.horizontal, T.sp4)
                    .padding(.bottom, T.sp2)
            }
            if viewModel.isSearchActive {
                messageSearchBar(viewModel)
            }
            messageList(viewModel)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                // 桌面同构：排队消息紧贴 composer 上方（pending 队列；无排队不渲染）
                if viewModel.isReadOnly, let queue = viewModel.queueInfo {
                    QueueBarView(
                        queue: queue,
                        onSendNow: { queueItemId in await viewModel.sendQueuedNow(queueItemId) },
                        onDelete: { queueItemId in await viewModel.deleteQueueItem(queueItemId) },
                        onEdit: { queueItemId, newText in
                            await viewModel.editQueueItem(queueItemId, newText: newText)
                        },
                        onMoveUp: { queueItemId in await viewModel.moveQueueItemUp(queueItemId) },
                        onToggleAutoDrain: { enabled in await viewModel.setAutoDrain(enabled) })
                }
                ComposerBar(viewModel: viewModel)
            }
        }
    }

    /// 头部（标题 + 运行中胶囊 + todo 摘要 + 3px 进度条）
    private func header(_ viewModel: ChatViewModel) -> some View {
        VStack(spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Text(viewModel.conversation?.title ?? "会话")
                    .font(T.font(17, .bold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if viewModel.conversation?.isRunning == true {
                    StatusPill(text: "运行中", kind: .run, compact: true)
                }
                Spacer(minLength: 0)
                if let summary = viewModel.conversation?.todoSummary {
                    Text(summary)
                        .font(T.mono(11))
                        .foregroundColor(T.text3)
                }
            }
            if let progress = viewModel.conversation?.taskProgress {
                ThinProgressBar(progress: progress)
            }
        }
        .padding(.horizontal, T.sp4)
        .padding(.vertical, T.sp2)
        .background(T.bg)
        .accessibilityIdentifier("05-header")
    }

    /// 会话内消息搜索（P2-3）：已加载范围本地检索 + 命中计数
    private func messageSearchBar(_ viewModel: ChatViewModel) -> some View {
        @Bindable var bindableViewModel = viewModel
        return HStack(spacing: T.sp2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12))
                .foregroundColor(T.text3)
            TextField("搜索已加载消息…", text: $bindableViewModel.searchQuery)
                .font(T.font(14))
                .foregroundColor(T.text)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityIdentifier("05-search-messages")
            Text("\(viewModel.searchHitCount) 处命中")
                .font(T.font(11))
                .foregroundColor(viewModel.searchHitCount > 0 ? T.accentText : T.text3)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 40)
        .background(T.bgInput)
        .clipShape(Capsule())
        .padding(.horizontal, T.sp4)
        .padding(.bottom, T.sp2)
    }

    private func messageList(_ viewModel: ChatViewModel) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: T.sp4) {
                    // 待审批交互卡（连接态：permission 类挂起交互，命令/路径/影响结构化排版）
                    ForEach(viewModel.pendingInteractions) { interaction in
                        if interaction.isPlanApproval {
                            // G-017：计划审批结构化卡（计划内容 + 放行/驳回，resolveInteraction 下发）
                            PlanApprovalCard(viewModel: viewModel, interaction: interaction)
                        } else if interaction.isPermission {
                            ApprovalInteractionCard(viewModel: viewModel, interaction: interaction)
                        } else if interaction.kind.lowercased().contains("escalation") {
                            // G-020：工作流 escalation 升级问题 → 复用审批卡作为移动审批入口
                            // （answer 经 resolveInteraction 桌面代执行，边界不变）
                            ApprovalInteractionCard(viewModel: viewModel, interaction: interaction)
                        }
                    }
                    // 向上分页入口：点击加载 + 滚顶自动加载（下拉到顶即拉更早历史）
                    if viewModel.canLoadOlder {
                        Button {
                            loadOlderAnchored(viewModel, proxy: proxy)
                        } label: {
                            HStack(spacing: T.sp1) {
                                if viewModel.isLoadingOlder {
                                    SpinnerView(size: 12)
                                } else {
                                    Image(systemName: "chevron.up")
                                        .font(.system(size: 10, weight: .semibold))
                                }
                                Text("加载更早消息")
                                    .font(T.font(12, .medium))
                            }
                            .foregroundColor(T.accentText)
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("05-act-load-older")
                        // 滚顶自动加载：按钮进入可见区（用户翻到顶）即拉下一页——
                        // 「下拉/点击」双触发；无更多数据时按钮隐藏不形成循环
                        .onAppear {
                            guard viewModel.canLoadOlder, !viewModel.isLoadingOlder else { return }
                            loadOlderAnchored(viewModel, proxy: proxy)
                        }
                    }
                    let visible = viewModel.searchQuery.isEmpty
                        ? viewModel.messages : viewModel.filteredMessages
                    ForEach(visible) { message in
                        MessageView(message: message, sessionID: conversationID) { reply in
                            Task { await viewModel.answerQuestion(reply) }
                        } onRetryToolCall: { _, rowId in
                            Task { await viewModel.retryTurn(rowId: rowId) }
                        }
                        .id(message.id)
                        .transition(.opacity.animation(.easeIn(duration: 0.12)))
                    }
                    Color.clear.frame(height: T.sp2).id("bottom-anchor")
                }
                .padding(.horizontal, T.sp4)
                .padding(.top, T.sp2)
            }
            .scrollIndicators(.visible)
            .defaultScrollAnchor(.bottom)
            .modifier(ChatScrollToTopLoadModifier {
                // 滚动到顶（内容顶距视口 ≤ 24pt）：「下拉到顶自动加载更早」触发区
                guard viewModel.canLoadOlder, !viewModel.isLoadingOlder else { return }
                loadOlderAnchored(viewModel, proxy: proxy)
            })
            .onChange(of: viewModel.messages.count) { _, _ in
                // loadOlder 顶部插入：保持阅读位置（button 已锚定原顶部消息），不滚底
                if viewModel.messages.first?.id != nil,
                   viewModel.messages.first?.id == prependGuardFirstID {
                    prependGuardFirstID = nil
                    return
                }
                scrollToBottom(proxy)
            }
            .onChange(of: viewModel.messages.last?.text) { _, _ in
                scrollToBottom(proxy)
            }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.2)) {
            proxy.scrollTo("bottom-anchor", anchor: .bottom)
        }
    }

    /// 加载更早 + 锚定原顶部消息（保持阅读位置）；点击按钮与下拉到顶共用
    private func loadOlderAnchored(_ viewModel: ChatViewModel, proxy: ScrollViewProxy) {
        let anchor = viewModel.messages.first?.id
        prependGuardFirstID = anchor
        Task {
            await viewModel.loadOlder()
            if let anchor {
                withAnimation(.easeOut(duration: 0.18)) {
                    proxy.scrollTo(anchor, anchor: .top)
                }
            }
        }
    }
}

/// 「下拉到顶自动加载更早」手势（onScrollGeometryChange 为 iOS 18+ API）：
/// 低版本系统降级为无自动触发（05-act-load-older 手动入口仍在），不阻断编译。
struct ChatScrollToTopLoadModifier: ViewModifier {
    let onAtTop: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: Bool.self) { geo in
                let offset = geo.contentOffset.y + geo.contentInsets.top
                return offset <= 24
            } action: { _, atTop in
                guard atTop else { return }
                onAtTop()
            }
        } else {
            content
        }
    }
}

// MARK: - G-021 子代理只读转录下钻（actor.sessionId → rowsRange 一页；无新协议）

struct ActorTranscriptSheet: View {
    let actor: WorkflowActorSummary
    let messages: [ChatMessage]
    let isLoading: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    CenterLoadingView(text: "正在读取子代理转录…")
                } else if messages.isEmpty {
                    EmptyStateView(
                        icon: "text.bubble",
                        title: String(localized: "暂无转录"),
                        detail: String(localized: "该子代理会话暂无可读的行记录"))
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: T.sp2) {
                            ForEach(messages) { message in
                                Text(message.text)
                                    .font(T.font(12.5, message.role == .user ? .semibold : .regular))
                                    .foregroundColor(message.role == .user ? T.text : T.text2)
                                    .lineSpacing(3)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(T.sp2)
                                    .background(message.role == .user ? T.accentDim.opacity(0.5) : T.bgCard)
                                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                            }
                        }
                        .padding(T.sp4)
                    }
                }
            }
            .background(T.bg)
            .navigationTitle(actor.name ?? String(localized: "子代理转录"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(T.text)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityIdentifier("05-transcript-act-close")
                }
            }
        }
    }
}

// MARK: - 连接态审批卡（pendingInteractions.permission 投影：命令/路径/影响结构化排版）

struct ApprovalInteractionCard: View {
    @Bindable var viewModel: ChatViewModel
    let interaction: RemotePendingInteraction

    enum AuthScope: String, CaseIterable, Identifiable {
        case once, task, always
        var id: String { rawValue }
        var label: String {
            switch self {
            case .once: return "仅本次"
            case .task: return "本任务内"
            case .always: return "始终允许"
            }
        }
        var identifier: String {
            switch self {
            case .once: return "05-choice-once"
            case .task: return "05-choice-task"
            case .always: return "05-choice-always"
            }
        }
    }

    @State private var scope: AuthScope = .once
    @State private var deciding = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 13))
                    .foregroundColor(T.orange)
                Text("关键操作待批准")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                StatusPill(text: "待审批", kind: .wait, compact: true)
            }
            if let title = interaction.title, !title.isEmpty {
                structureRow(icon: "wrench.and.screwdriver", label: "类型", value: title)
            }
            if let command = interaction.command, !command.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(command)
                        .font(T.mono(12))
                        .foregroundColor(T.text)
                        .padding(T.sp2)
                        .frame(minWidth: 0, alignment: .leading)
                        .background(T.bgCode)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .accessibilityIdentifier("05-approval-cmd")
            }
            if let path = interaction.path, !path.isEmpty {
                structureRow(icon: "folder", label: "路径", value: path)
            }
            if let impact = interaction.impact, !impact.isEmpty {
                HStack(spacing: T.sp1) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 11))
                        .foregroundColor(T.orange)
                    Text(impact)
                        .font(T.font(11.5))
                        .foregroundColor(T.text2)
                }
            }
            // 授权范围三档（answer.scope 注入；热区 ≥44pt）
            HStack(spacing: T.sp2) {
                ForEach(AuthScope.allCases) { item in
                    Button {
                        scope = item
                    } label: {
                        Text(item.label)
                            .font(T.font(12, .medium))
                            .foregroundColor(scope == item ? T.onAccent : T.text2)
                            .padding(.horizontal, T.sp2)
                            .frame(minHeight: 44)
                            .background(scope == item ? T.accent : T.bgInput)
                            .clipShape(Capsule())
                    }
                    .accessibilityIdentifier(item.identifier)
                }
                Spacer()
            }
            HStack(spacing: 10) {
                Button {
                    decide(approved: false)
                } label: {
                    Text("拒绝")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.red)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-reject")

                Button {
                    decide(approved: true)
                } label: {
                    Text("批准执行")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-approve")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.55), lineWidth: 1))
        // 透明容器：容器可定位（05-approval-card），子元素保留各自 identifier
        // （05-choice-*/05-act-*；否则容器 identifier 会覆盖全部后代，门禁实证）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-approval-card")
    }

    private func structureRow(icon: String, label: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 11))
                .foregroundColor(T.text3)
                .frame(width: 20)
            Text(label)
                .font(T.font(11.5))
                .foregroundColor(T.text3)
            Text(value)
                .font(T.mono(11))
                .foregroundColor(T.text2)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 0)
        }
    }

    private func decide(approved: Bool) {
        deciding = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        Task {
            await viewModel.decide(interaction, approved: approved, scope: scope.rawValue)
            deciding = false
        }
    }
}

// MARK: - 计划审批卡（G-017：plan_approval 结构化渲染——计划文本 + 放行/驳回；
// 与普通命令审批卡互不影响；决议复用 resolveInteraction 桌面代执行）

struct PlanApprovalCard: View {
    @Bindable var viewModel: ChatViewModel
    let interaction: RemotePendingInteraction
    @State private var deciding = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "list.clipboard")
                    .font(.system(size: 13))
                    .foregroundColor(T.blue)
                Text("计划待确认")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                StatusPill(text: "待审批", kind: .wait, compact: true)
            }
            if let title = interaction.title, !title.isEmpty {
                Text(title)
                    .font(T.font(12.5))
                    .foregroundColor(T.text2)
                    .lineLimit(2)
            }
            if let plan = interaction.planText, !plan.isEmpty {
                ScrollView {
                    Text(plan)
                        .font(T.font(12))
                        .foregroundColor(T.text2)
                        .lineSpacing(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 220)
                .padding(T.sp2)
                .background(T.bgCode)
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
                .accessibilityIdentifier("05-plan-body")
            }
            HStack(spacing: 10) {
                Button {
                    decide(approved: false)
                } label: {
                    Text("驳回计划")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.red)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-plan-reject")

                Button {
                    decide(approved: true)
                } label: {
                    Text("放行计划")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-plan-approve")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.blue.opacity(0.55), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-plan-card")
    }

    private func decide(approved: Bool) {
        deciding = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        Task {
            // 计划决议不带 scope（桌面 plan_approval 语义为通过/驳回二值）
            await viewModel.decide(interaction, approved: approved, scope: "once")
            deciding = false
        }
    }
}

// MARK: - 排队消息条（桌面 composer pending 队列同构：立即 / 编辑 / 删除 / 上移 / 自动排空）

struct QueueBarView: View {
    let queue: ConversationQueueInfo
    var onSendNow: (String) async -> String?
    var onDelete: (String) async -> String?
    var onEdit: (String, String) async -> String?
    var onMoveUp: (String) async -> String?
    var onToggleAutoDrain: (Bool) async -> String?

    @State private var editingItemId: String?
    @State private var editingText = ""
    @State private var feedback: String?
    @State private var feedbackClear: Task<Void, Never>?

    private func showFeedback(_ message: String?) {
        guard let message else { return }
        feedbackClear?.cancel()
        feedback = message
        feedbackClear = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { feedback = nil }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp1) {
            HStack(spacing: T.sp2) {
                Image(systemName: "square.stack.3d.up.fill")
                    .font(.system(size: 11))
                    .foregroundColor(T.violet)
                Text(String(localized: "排队中 \(queue.items.count)"))
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
                Button {
                    Task {
                        if let message = await onToggleAutoDrain(!queue.autoDrain) {
                            showFeedback(message)
                        }
                    }
                } label: {
                    Text(queue.autoDrain
                        ? String(localized: "自动排空 · 开")
                        : String(localized: "自动排空 · 关"))
                        .font(T.font(10.5, .semibold))
                        .foregroundColor(queue.autoDrain ? T.accentText : T.text3)
                        .padding(.horizontal, T.sp2)
                        .padding(.vertical, 3)
                        .background(queue.autoDrain ? T.accentDim.opacity(0.5) : T.bgInput)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-queue-act-autodrain")
            }
            ForEach(queue.items) { item in
                queueRow(item)
            }
            if let message = feedback {
                Text(message)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
            }
        }
        .padding(.horizontal, T.sp4)
        .padding(.vertical, T.sp2)
        .background(T.tabbarBg)
        .overlay(alignment: .top) { Divider().overlay(T.border) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-queue-bar")
    }

    /// 单条排队消息（立即 / 编辑 / 删除 / 上移；guide 模式条目标注）
    private func queueRow(_ item: RemoteQueueItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if editingItemId == item.id {
                HStack(spacing: T.sp2) {
                    TextField("编辑排队内容", text: $editingText, axis: .vertical)
                        .font(T.font(12.5))
                        .foregroundColor(T.text)
                        .lineLimit(1...4)
                        .padding(.horizontal, T.sp2)
                        .padding(.vertical, 6)
                        .background(T.bgInput)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    Button {
                        Task {
                            let message = await onEdit(item.id, editingText)
                            if message == nil {
                                editingItemId = nil
                            } else {
                                showFeedback(message)
                            }
                        }
                    } label: {
                        Text(String(localized: "保存"))
                            .font(T.font(11.5, .semibold))
                            .foregroundColor(T.onAccent)
                            .padding(.horizontal, T.sp2)
                            .frame(minHeight: 30)
                            .background(T.accent)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    Button {
                        editingItemId = nil
                    } label: {
                        Text(String(localized: "取消"))
                            .font(T.font(11.5))
                            .foregroundColor(T.text2)
                    }
                    .buttonStyle(.plain)
                }
            } else {
                HStack(alignment: .top, spacing: T.sp2) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.text)
                            .font(T.font(12))
                            .foregroundColor(T.text)
                            .lineLimit(2)
                        if item.isGuide {
                            Text(String(localized: "引导模式"))
                                .font(T.mono(9.5))
                                .foregroundColor(T.violet)
                        }
                    }
                    Spacer(minLength: 0)
                    // 立即发送（桌面「立即」同语义：sendQueuedNow，CAS）
                    rowAction(icon: "arrow.up.circle.fill", label: String(localized: "立即")) {
                        let message = await onSendNow(item.id)
                        showFeedback(message)
                    }
                    .accessibilityIdentifier("05-queue-act-send-\(item.id)")
                    rowAction(icon: "pencil", label: String(localized: "编辑")) {
                        editingText = item.text
                        editingItemId = item.id
                    }
                    if queue.items.first?.id != item.id {
                        rowAction(icon: "arrow.up.to.line", label: "") {
                            let message = await onMoveUp(item.id)
                            showFeedback(message)
                        }
                    }
                    rowAction(icon: "trash", label: "") {
                        let message = await onDelete(item.id)
                        showFeedback(message)
                    }
                    .accessibilityIdentifier("05-queue-act-delete-\(item.id)")
                }
                .padding(.horizontal, T.sp2)
                .padding(.vertical, 6)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
                .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.border, lineWidth: 1))
            }
        }
    }

    private func rowAction(
        icon: String, label: String,
        action: @escaping () async -> Void
    ) -> some View {
        Button {
            Task { await action() }
        } label: {
            HStack(spacing: 2) {
                Image(systemName: icon)
                    .font(.system(size: 11))
                if !label.isEmpty {
                    Text(label).font(T.font(10.5, .semibold))
                }
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, label.isEmpty ? 6 : T.sp2)
            .frame(minHeight: 28)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Composer（工具行 + 44px 胶囊输入行；输入框 16px 防聚焦缩放）
// v3 纠偏：连接态输入区可用（sendText 桌面代执行）；工具行连接态展示桌面端模型
// 只读 chips（model-selection.getView 真实数据，缺数据不渲染），演示态沿用本机设置。

struct ComposerBar: View {
    @Environment(AppSettingsModel.self) private var settings
    @Bindable var viewModel: ChatViewModel
    @FocusState private var inputFocused: Bool
    /// 执行目标偏好（G-012 如实口径）：☁️ 云端沙盒 / 💻 我的 Mac——当前仅记录发送偏好
    /// 并持久化；sendText 信封无目标路由字段，消息仍经当前连接的会话链路下发
    /// （客户端发命令、桌面代执行边界不变）。UI 已按验收 B 以「偏好」如实标注。
    @State private var executionTarget = DeviceOption.cloudSandbox

    var body: some View {
        VStack(spacing: T.sp2) {
            targetRow
            if let message = switchHint {
                Text(message)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-composer-switch-hint")
            }
            if let selection = viewModel.modelSelection {
                remoteChips(selection)
            } else {
                toolsRow
            }
            inputRow
        }
        .padding(.horizontal, T.sp4)
        .padding(.top, T.sp2)
        .padding(.bottom, T.sp2)
        .background(T.tabbarBg)
        .overlay(alignment: .top) { Divider().overlay(T.border) }
        .task(id: viewModel.conversationID) {
            // 恢复该会话执行目标（per-conversation → 全局默认 → 云端沙盒）
            executionTarget = ExecutionTargetStore.target(for: viewModel.conversationID)
        }
    }

    // MARK: 执行目标选择器（Qoder 对照屏 2/3：目标内联常驻、单击弹出设备清单、✓ 标当前）

    private var targetRow: some View {
        HStack(spacing: T.sp2) {
            executionTargetMenu
            Text(String(localized: "偏好"))
                .font(T.mono(10))
                .foregroundColor(T.text3)
                .accessibilityIdentifier("05-target-note")
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-target-menu")
    }

    private var executionTargetMenu: some View {
        Menu {
            // G-012：如实标注——当前为发送偏好记录，无信封级目标路由
            Section {
                Text(String(localized: "仅记录发送偏好 · 消息经当前连接的桌面端执行"))
            }
            ForEach(ExecutionTargetStore.machines()) { option in
                Button {
                    selectTarget(option)
                } label: {
                    HStack {
                        Image(systemName: option.kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer")
                        Text(option.name)
                        if option.id == executionTarget.id {
                            Image(systemName: "checkmark")
                        }
                    }
                }
                .accessibilityIdentifier(option.kind == .cloudSandbox
                    ? "05-target-cloud" : "05-target-mac")
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: executionTarget.kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer")
                    .font(.system(size: 11))
                Text(executionTarget.name)
                    .font(T.font(11.5, .medium))
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8, weight: .semibold))
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, T.sp2)
            .frame(minHeight: 44)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        // identifier 挂 Menu 本体而非 label：挂在 label 上时 SwiftUI Menu 运行时会把它
        // 逐层拼接成「05-act-target-05-act-target-…」（门禁诊断实据），XCUI 精确匹配
        // 05-act-target 命中的是惰性子元素，tap 落空、菜单永不弹出
        .accessibilityIdentifier("05-act-target")
    }

    private func selectTarget(_ option: DeviceOption) {
        executionTarget = option
        ExecutionTargetStore.setTarget(option, conversationID: viewModel.conversationID)
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// 桌面端模型/思考档切换（switchModelConfig 桌面代执行；onDidChange 回流同步 chips。
    /// 原只读口径随命令链路打通升级：模型按套餐分组，思考档独立菜单）
    @State private var switchHint: String?
    @State private var switchHintClear: Task<Void, Never>?

    private func remoteChips(_ selection: ModelSelectionInfo) -> some View {
        HStack(spacing: T.sp2) {
            modelMenu(selection)
            thoughtMenu(selection)
            Spacer(minLength: 0)
            if let usage = viewModel.contextUsage {
                contextMeter(usage, demo: false)
            }
        }
        .accessibilityIdentifier("05-composer-remote-chips")
    }

    /// 模型菜单（套餐分节：个人套餐/体验套餐；回执失败在 chips 行上方提示）
    private func modelMenu(_ selection: ModelSelectionInfo) -> some View {
        Menu {
            if selection.planGroups.isEmpty {
                ForEach(selection.models, id: \.self) { model in
                    modelRow(model, selection)
                }
            } else {
                ForEach(selection.planGroups) { group in
                    Section(group.plan) {
                        ForEach(group.models, id: \.self) { model in
                            modelRow(model, selection)
                        }
                    }
                }
            }
        } label: {
            pill(icon: nil, text: selection.activeModel ?? "--", chevron: true)
        }
        .accessibilityIdentifier("05-chip-model")
    }

    private func modelRow(_ model: String, _ selection: ModelSelectionInfo) -> some View {
        Button {
            Task {
                if let message = await viewModel.switchModel(model) {
                    showSwitchHint(message)
                }
            }
        } label: {
            if model == selection.activeModel {
                Label(model, systemImage: "checkmark")
            } else {
                Text(model)
            }
        }
    }

    /// 思考档菜单（workspace-config 词表按当前模型查询；缺席时退化为静态档位梯——
    /// web 端别名表归纳词表，不支持的档位由桌面端校验拒绝并提示）
    private func thoughtMenu(_ selection: ModelSelectionInfo) -> some View {
        Menu {
            if let levels = viewModel.thoughtLevels, !levels.isEmpty {
                ForEach(levels, id: \.self) { level in
                    thoughtRow(level, selection)
                }
            } else if !selection.thoughtLevels.isEmpty {
                ForEach(selection.thoughtLevels, id: \.self) { level in
                    thoughtRow(level, selection)
                }
            } else {
                ForEach(Self.fallbackThoughtLevels, id: \.self) { level in
                    thoughtRow(level, selection)
                }
            }
        } label: {
            pill(icon: "brain", text: "思考·\(selection.activeThoughtLevel ?? "--")", chevron: true)
        }
        .accessibilityIdentifier("05-chip-thought")
    }

    /// 思考档静态梯（getView 不携带词表、workspace-config 为空的远端环境兜底）
    private static let fallbackThoughtLevels = ["off", "minimal", "low", "medium", "high", "max"]

    private func thoughtRow(_ level: String, _ selection: ModelSelectionInfo) -> some View {
        Button {
            Task {
                if let message = await viewModel.switchThoughtLevel(level) {
                    showSwitchHint(message)
                }
            }
        } label: {
            if level == selection.activeThoughtLevel {
                Label(level, systemImage: "checkmark")
            } else {
                Text(level)
            }
        }
    }

    private func showSwitchHint(_ message: String) {
        switchHintClear?.cancel()
        switchHint = message
        switchHintClear = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { switchHint = nil }
        }
    }

    private var toolsRow: some View {
        HStack(spacing: T.sp2) {
            pill(icon: nil, text: settings.value.model)
            pill(icon: "brain", text: "思考·\(settings.value.thoughtLevel.label)")
            Spacer(minLength: 0)
            // G-021：演示态用量为 Mock 动态值，如实标注「演示」；nil 不渲染
            if let usage = viewModel.contextUsage {
                contextMeter(usage, demo: !viewModel.isReadOnly)
            }
        }
        .accessibilityIdentifier("05-composer-tools")
    }

    /// 上下文用量条（连接态=state.runtime.contextUsage；演示态=Mock 动态值）
    private func contextMeter(_ usage: ContextUsageInfo, demo: Bool) -> some View {
        HStack(spacing: T.sp1) {
            Text(demo ? String(localized: "上下文·演示") : String(localized: "上下文"))
                .font(T.font(11))
                .foregroundColor(T.text3)
            ThinProgressBar(progress: usage.fraction, height: 4, tint: T.accent)
                .frame(width: 56)
            Text(usage.percentText)
                .font(T.mono(10.5))
                .foregroundColor(T.text3)
        }
        .accessibilityIdentifier("05-composer-context")
    }

    private var inputRow: some View {
        HStack(spacing: T.sp2) {
            TextField("发送消息…", text: $viewModel.draft, axis: .vertical)
                .font(T.font(16))
                .foregroundColor(T.text)
                .lineLimit(1...4)
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
                .focused($inputFocused)
                .submitLabel(.send)
                .onSubmit { Task { await viewModel.send() } }
                .accessibilityIdentifier("05-composer-input")

            // G-023：麦克风死按钮移除（原仅图标动画无录音/听写行为；语音输入走 iOS
            // 键盘自带听写，长按语音待 G-055 真实现）

            sendButton
        }
    }

    private var sendButton: some View {
        Button {
            Task { await viewModel.send() }
            inputFocused = false
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(T.onAccent)
                .frame(width: 44, height: 44)
                .background(viewModel.draft.isEmpty ? T.bgInput : T.accent)
                .clipShape(Circle())
        }
        .disabled(viewModel.draft.isEmpty)
        .accessibilityIdentifier("05-composer-send")
    }

    private func pill(icon: String?, text: String, chevron: Bool = false) -> some View {
        HStack(spacing: 4) {
            if let icon {
                Image(systemName: icon).font(.system(size: 10))
            }
            Text(text).font(T.font(11, .medium))
            if chevron {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 7.5, weight: .semibold))
                    .foregroundColor(T.text3)
            }
        }
        .foregroundColor(T.text2)
        .padding(.horizontal, T.sp2)
        .frame(height: 26)
        .background(T.bgInput)
        .clipShape(Capsule())
    }
}

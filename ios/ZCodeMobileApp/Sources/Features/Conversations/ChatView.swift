import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

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
                // 待审批交互卡（常驻 composer 上方：不随消息滚动，新消息不再顶走；
                // 连接态 permission/plan/workspaceHookReview/escalation 类挂起交互，按
                // kind 分派应答命令——permission/plan 走 resolveInteraction，
                // workspaceHookReview 走 respondWorkspaceHookReview）
                ForEach(viewModel.pendingInteractions) { interaction in
                    if interaction.isPlanApproval {
                        PlanApprovalCard(viewModel: viewModel, interaction: interaction)
                            .padding(.horizontal, T.sp4)
                            .padding(.top, T.sp2)
                    } else if interaction.isWorkspaceHookReview {
                        WorkspaceHookReviewCard(
                            interaction: interaction,
                            onTrust: { ids in
                                await viewModel.trustWorkspaceHooks(interaction, reviewItemIds: ids)
                            },
                            onRequest: {
                                await viewModel.requestWorkspaceHookReview()
                            },
                            onRevoke: { ids in
                                await viewModel.revokeWorkspaceHookTrust(reviewItemIds: ids)
                            })
                            .padding(.horizontal, T.sp4)
                            .padding(.top, T.sp2)
                    } else if interaction.isPermission
                                || interaction.kind.lowercased().contains("escalation") {
                        ApprovalInteractionCard(viewModel: viewModel, interaction: interaction)
                            .padding(.horizontal, T.sp4)
                            .padding(.top, T.sp2)
                    }
                }
                // P2-7B「稍后处理」反馈行（3s 自动清除）：卡片可能随即被回流撤下，
                // 提示需在卡片之外存活（switchHint 同款一行橙字口径）
                if let snoozeFeedback = viewModel.snoozeFeedback {
                    Text(snoozeFeedback)
                        .font(T.font(10.5))
                        .foregroundColor(T.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, T.sp4)
                        .accessibilityIdentifier("05-approval-snooze-hint")
                }
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
                    // 待审批交互卡已移出滚动列表（用户报障：新消息把卡片顶出屏幕，
                    // 需上滚找批准按钮）——改固定在 composer 上方常驻，见 approvalBar(_:)
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
                    ForEach(Array(visible.enumerated()), id: \.element.id) { index, message in
                        MessageView(
                            message: message, sessionID: conversationID,
                            feedbackState: viewModel.assistantFeedback[message.id],
                            ordinal: index + 1,
                            onQuickReply: { reply in
                                Task { await viewModel.answerQuestion(reply) }
                            },
                            onRetryToolCall: { _, rowId in
                                Task { await viewModel.retryTurn(rowId: rowId) }
                            },
                            // P1-3：回调仅连接态接线——演示态传 nil，反馈行/长按编辑项
                            // 不渲染（命令不可达不渲染死入口；游标门槛在 MessageView 内）
                            onFeedback: viewModel.isReadOnly ? { value in
                                await viewModel.setAssistantFeedback(message, value: value)
                            } : nil,
                            onEditResend: viewModel.isReadOnly ? { newText in
                                await viewModel.editAndResend(message, newText: newText)
                            } : nil)
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

// MARK: - 权限审批 optionId 推导（A-3 / 设计稿 §3.2 规则 2/3；两处权限 UI 共用：
// 会话内 ApprovalInteractionCard 与任务页 ApprovalSheetView）
//
// web 四族词表 allowOnce/allowAlways/rejectOnce/rejectAlways（bundle 归一化函数
// dQ 实证：小写包含 allow|approve → allow 族、reject|deny → reject 族、含 always
// 升 Always 档，否则 custom）。应答 answer={optionId}——优先回传服务端原生
// optionId（web 调用点 {optionId: t.optionId}，按钮即选项）；options 空时按
// 设计稿拼规范 id：(approved ? "allow" : "reject") + (always ? "Always" : "Once")。

enum PermissionFamily {
    case allowOnce, allowAlways, rejectOnce, rejectAlways, custom
}

struct PermissionOptionMatrix: Equatable {
    let allowOnce: RemoteInteractionOption?
    let allowAlways: RemoteInteractionOption?
    let rejectOnce: RemoteInteractionOption?
    let rejectAlways: RemoteInteractionOption?
    /// 全非四族 options（单选即决形态：chips 即选项，点选即发原生 id）
    let customOptions: [RemoteInteractionOption]

    init(options: [RemoteInteractionOption]) {
        var once = RemoteInteractionOption?.none
        var always = RemoteInteractionOption?.none
        var rOnce = RemoteInteractionOption?.none
        var rAlways = RemoteInteractionOption?.none
        var custom: [RemoteInteractionOption] = []
        for option in options {
            switch Self.family(of: option.id) {
            case .allowOnce where once == nil: once = option
            case .allowAlways where always == nil: always = option
            case .rejectOnce where rOnce == nil: rOnce = option
            case .rejectAlways where rAlways == nil: rAlways = option
            case .allowOnce, .allowAlways, .rejectOnce, .rejectAlways: break // 重复项忽略
            case .custom: custom.append(option)
            }
        }
        allowOnce = once
        allowAlways = always
        rejectOnce = rOnce
        rejectAlways = rAlways
        customOptions = custom
    }

    /// web dQ 同款归一（子串判定，兼容 always allow 等变体写法）
    static func family(of id: String) -> PermissionFamily {
        let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let isAllow = normalized.contains("allow") || normalized.contains("approve")
        let isReject = normalized.contains("reject") || normalized.contains("deny")
        let isAlways = normalized.contains("always")
        if isAllow && isAlways { return .allowAlways }
        if isAllow { return .allowOnce }
        if isReject && isAlways { return .rejectAlways }
        if isReject { return .rejectOnce }
        return .custom
    }

    /// 无任一四族命中（服务端 options 全为自定义档）
    var isAllCustom: Bool {
        allowOnce == nil && allowAlways == nil && rejectOnce == nil && rejectAlways == nil
    }

    /// 档位可见性（设计稿规则 3）：仅本次 ⇔ allowOnce/rejectOnce 任一在场；始终允许
    /// ⇔ allowAlways/rejectAlways 任一在场。无四族时两档恒可见（规则 2 默认形态，
    /// 该形态下方向钮恒可用、optionId 走拼接）。
    var showsOnceChip: Bool { isAllCustom || allowOnce != nil || rejectOnce != nil }
    var showsAlwaysChip: Bool { isAllCustom || allowAlways != nil || rejectAlways != nil }

    /// 方向钮可用性（混合态核心）：选中档对应方向的四族项不在场则禁用
    /// （拼出即词表外 optionId，必被服务端拒）
    func canDecide(approved: Bool, always: Bool) -> Bool {
        guard !isAllCustom else { return true }
        if approved { return always ? allowAlways != nil : allowOnce != nil }
        return always ? rejectAlways != nil : rejectOnce != nil
    }

    /// 应答 optionId：优先服务端原生 id；无四族（options 空）按设计稿拼规范 id；
    /// 组合无效（该方向四族缺席）返回 nil——调用方不出手
    func optionId(approved: Bool, always: Bool) -> String? {
        let matched = approved
            ? (always ? allowAlways : allowOnce)
            : (always ? rejectAlways : rejectOnce)
        if let matched { return matched.id }
        guard isAllCustom else { return nil }
        return (approved ? "allow" : "reject") + (always ? "Always" : "Once")
    }

    /// 默认档（设计稿规则 3）：首个两方向齐全的档；无齐全档选首个可见档
    func defaultScopeAlways() -> Bool {
        if canDecide(approved: true, always: false), canDecide(approved: false, always: false) {
            return false
        }
        if canDecide(approved: true, always: true), canDecide(approved: false, always: true) {
            return true
        }
        return !showsOnceChip && showsAlwaysChip
    }
}

// MARK: - 连接态审批卡（pendingInteractions.permission 投影：命令/路径/影响结构化排版）

struct ApprovalInteractionCard: View {
    @Bindable var viewModel: ChatViewModel
    let interaction: RemotePendingInteraction

    /// 授权范围两档（A-3/设计稿 §3.2 规则 4：原 task 档在 wire 四族值域无对应，删除；
    /// 服务端携带等效自定义 id 时经 customOptions 直选呈现，不进两步矩阵）
    enum AuthScope: String, CaseIterable, Identifiable {
        case once, always
        var id: String { rawValue }
        var label: String {
            switch self {
            case .once: return "仅本次"
            case .always: return "始终允许"
            }
        }
        var identifier: String {
            switch self {
            case .once: return "05-choice-once"
            case .always: return "05-choice-always"
            }
        }
    }

    private var matrix: PermissionOptionMatrix {
        PermissionOptionMatrix(options: interaction.options)
    }

    @State private var scope: AuthScope = .once
    @State private var deciding = false
    // P2-7B「稍后处理」进行中（批准/拒绝同步 disabled，deciding 机制同款）
    @State private var snoozing = false
    // 旧桌面端无 snooze 命令（viewModel 依回执宽容判定）→ 按钮降级纯关闭（§7B）
    @State private var snoozeUnsupported = false
    // A-3/U-5：决议失败如实回显（回执拒绝/未送达），按钮恢复可点、卡片不撤
    @State private var decisionError: String?

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
            if matrix.isAllCustom, !matrix.customOptions.isEmpty {
                // 设计稿 §3.2 规则 3 末条：options 全为非四族 id → chips 即选项，
                // 单选即决（自带方向语义，点选即发原生 optionId），批准/拒绝两钮整组隐藏
                FlowChips(items: matrix.customOptions.map(\.label), identifierPrefix: "05-choice") { label in
                    if let option = matrix.customOptions.first(where: { $0.label == label }) {
                        decideWith(optionId: option.id)
                    }
                }
            } else {
                // 授权范围两档 chips（answer={optionId} 拼接依据；热区 ≥44pt）。
                // 可见性按四族命中推导（规则 3）；服务端自定义档追加直选 chip
                HStack(spacing: T.sp2) {
                    if matrix.showsOnceChip {
                        scopeChip(.once)
                    }
                    if matrix.showsAlwaysChip {
                        scopeChip(.always)
                    }
                    ForEach(matrix.customOptions) { option in
                        directChip(option)
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
                    .disabled(deciding || snoozing
                              || !matrix.canDecide(approved: false, always: scope == .always))
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
                    .disabled(deciding || snoozing
                              || !matrix.canDecide(approved: true, always: scope == .always))
                    .accessibilityIdentifier("05-act-approve")
                }
            }
            if let decisionError {
                // U-5：失败态错误行（controlFeedback 口径，reasonCode 透出）
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-approval-decision-error")
            }
            // P2-7B「稍后处理」（设计稿 §7B：批准/拒绝行下方第三动作，text3 居中行，
            // 样式照抄 ApprovalSheetView 同名动作）。成功后卡片由桌面 pendingInteractions
            // 回流撤下（>1s 未至 viewModel 本地遮罩兜底）；hint 走 viewModel.snoozeFeedback
            // 通道（卡片可能随即收起，提示不能挂在卡片内）。命令不存在（旧桌面端）时
            // 降级为纯关闭——仅本地收起，不改桌面挂起态
            TextActionButton(
                title: snoozeUnsupported
                    ? "关闭"
                    : (snoozing ? "稍后中…" : "稍后处理"),
                tint: T.text3,
                action: {
                    if snoozeUnsupported {
                        Task { await viewModel.dismissInteractionLocally(interaction) }
                    } else {
                        snooze()
                    }
                },
                identifier: "05-approval-act-later")
                .disabled(snoozing)
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.55), lineWidth: 1))
        // 首帧按服务端 options 组合定默认档（首个两方向齐全的档）
        .onAppear {
            scope = matrix.defaultScopeAlways() ? .always : .once
        }
        // 透明容器：容器可定位（05-approval-card），子元素保留各自 identifier
        // （05-choice-*/05-act-*；否则容器 identifier 会覆盖全部后代，门禁实证）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-approval-card")
    }

    /// 范围 chip（44pt 胶囊，ApprovalSheetView chips 化后两处同构）
    private func scopeChip(_ item: AuthScope) -> some View {
        Button {
            scope = item
        } label: {
            HStack(spacing: T.sp1) {
                Text(item.label)
                if item == .always {
                    Text("需谨慎")
                        .font(T.font(10.5, .semibold))
                        .foregroundColor(scope == item ? T.onAccent : T.red)
                }
            }
            .font(T.font(12, .medium))
            .foregroundColor(scope == item ? T.onAccent : T.text2)
            .padding(.horizontal, T.sp2)
            .frame(minHeight: 44)
            .background(scope == item ? T.accent : T.bgInput)
            .clipShape(Capsule())
        }
        .accessibilityIdentifier(item.identifier)
    }

    /// 服务端自定义选项 chip（单选即决：点选即发该原生 optionId）
    private func directChip(_ option: RemoteInteractionOption) -> some View {
        Button {
            decideWith(optionId: option.id)
        } label: {
            Text(option.label)
                .font(T.font(12, .medium))
                .foregroundColor(T.text2)
                .padding(.horizontal, T.sp2)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
        }
        .disabled(deciding || snoozing)
        .accessibilityIdentifier("05-choice-custom-\(option.id)")
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

    /// 两步矩阵决议：按 scope 拼/选 optionId（A-3：answer={optionId}）
    private func decide(approved: Bool) {
        guard let optionId = matrix.optionId(approved: approved, always: scope == .always) else {
            decisionError = String(localized: "该授权范围无对应选项，请切换范围")
            return
        }
        decideWith(optionId: optionId)
    }

    /// 决议下发 + 如实回执（U-5：失败不撤卡、错误行透出 reasonCode；成功卡片由
    /// 桌面 pendingInteractions 回流撤下）
    private func decideWith(optionId: String) {
        deciding = true
        Task {
            if let failure = await viewModel.decide(interaction, optionId: optionId) {
                decisionError = failure
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } else {
                decisionError = nil
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            deciding = false
        }
    }

    /// 「稍后处理」（snoozeInteractionAutoResolution；hint 由 viewModel.snoozeFeedback
    /// 通道给出）。旧桌面端无该命令（回执宽容判定）→ 按钮降级纯关闭（§7B 状态矩阵）
    private func snooze() {
        snoozing = true
        Task {
            let failure = await viewModel.snoozeInteraction(interaction)
            snoozing = false
            if failure != nil, viewModel.snoozeUnsupported {
                snoozeUnsupported = true
            }
        }
    }
}

// MARK: - 计划审批卡（G-017：plan_approval 结构化渲染——计划文本 + 放行/驳回；
// 与普通命令审批卡互不影响；决议复用 resolveInteraction 桌面代执行）

struct PlanApprovalCard: View {
    @Bindable var viewModel: ChatViewModel
    let interaction: RemotePendingInteraction
    @State private var deciding = false
    // A-3/U-5：计划决议失败如实回显（回执拒绝/未送达），卡片不撤、按钮恢复
    @State private var decisionError: String?

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
            if let decisionError {
                // U-5：失败态错误行（「计划决议」语境，controlFeedback 口径）
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-plan-decision-error")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.blue.opacity(0.55), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-plan-card")
    }

    /// A-3：计划族 answer={action:"accept"|"decline"}（原 approved/scope 平铺形态是
    /// wire 不存在的键）。cancel 不设按钮——「稍后处理」snooze 独立命令承担挂起语义
    /// （设计稿 §3.3）；content（可选理由）首版不做输入，键缺省不发。
    private func decide(approved: Bool) {
        deciding = true
        Task {
            if let failure = await viewModel.decidePlan(interaction, action: approved ? "accept" : "decline") {
                decisionError = failure
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } else {
                decisionError = nil
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            deciding = false
        }
    }
}

// MARK: - workspace hook 信任审核卡（web 对齐 2026-10-06：pendingInteractions
// payload.kind='workspaceHookReview' 的专属卡——应答走 respondWorkspaceHookReview
//（trust_selected+reviewItemIds，web 实证唯一 action），与权限审批卡是姊妹 kind。
// 闭包驱动（ChatView 经 ChatViewModel / ApprovalSheetView 直连 conversationStore
// 两路复用同一 UI）；写面（信任/撤销）均带确认弹层，失败经 error 行如实透出）

struct WorkspaceHookReviewCard: View {
    let interaction: RemotePendingInteraction
    /// 信任所选（入参=reviewItemIds；返回失败文案，nil=成功/已受理）
    let onTrust: ([String]) async -> String?
    /// 重新请求审核（返回失败文案，nil=成功/已受理）
    let onRequest: () async -> String?
    /// 撤销已信任项（入参=reviewItemIds；UI 侧确认弹层后由本卡调用）
    let onRevoke: (([String]) async -> String?)?

    @State private var selectedIds: Set<String> = []
    @State private var deciding = false
    @State private var requesting = false
    @State private var decisionError: String?
    @State private var showTrustConfirm = false
    @State private var pendingRevokeItem: WorkspaceHookReviewItem?

    private var items: [WorkspaceHookReviewItem] { interaction.hookReviewItems }
    private var selectedItems: [WorkspaceHookReviewItem] {
        items.filter { selectedIds.contains($0.id) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            header
            summaryRow
            if items.isEmpty {
                emptyHint
            } else {
                ForEach(items) { item in
                    itemRow(item)
                }
                trustActionButton
            }
            if let decisionError {
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-hook-decision-error")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.55), lineWidth: 1))
        .onAppear {
            preselectPendingItems()
        }
        // 审核项随快照/补拉晚于首帧到达时补一次默认全选（onAppear 已过）
        .onChange(of: interaction.hookReviewItems) {
            preselectPendingItems()
        }
        // 信任确认（写桌面信任账本——放行 hook 在桌面执行）
        .confirmationDialog(
            "信任所选 \(selectedItems.count) 个 hook 项？",
            isPresented: $showTrustConfirm,
            titleVisibility: .visible) {
            Button("信任") {
                Task { await trustSelected() }
            }
            .accessibilityIdentifier("05-hook-confirm-trust")
            Button("取消", role: .cancel) {}
        } message: {
            Text("桌面端将按信任账本放行所选 hook 的执行。")
        }
        // 撤销确认（可逆性未知——从严 destructive）
        .confirmationDialog(
            pendingRevokeItem.map { item in
                item.title.map { "撤销「\($0)」的信任？" } ?? "撤销该 hook 项的信任？"
            } ?? "",
            isPresented: Binding(
                get: { pendingRevokeItem != nil },
                set: { if !$0 { pendingRevokeItem = nil } }),
            titleVisibility: .visible) {
            Button("撤销信任", role: .destructive) {
                if let item = pendingRevokeItem {
                    Task { await revoke(item) }
                }
                pendingRevokeItem = nil
            }
            .accessibilityIdentifier("05-hook-confirm-revoke")
            Button("取消", role: .cancel) { pendingRevokeItem = nil }
        } message: {
            Text("桌面端将不再放行该 hook，下次触发会重新进入审核。")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-hook-card")
    }

    private var header: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "curlybraces.square")
                .font(.system(size: 13))
                .foregroundColor(T.orange)
            Text("工作区 Hook 待信任")
                .font(T.font(13.5, .semibold))
                .foregroundColor(T.text)
            Spacer()
            StatusPill(text: "待信任", kind: .wait, compact: true)
        }
    }

    /// 默认全选待信任项（审核项本就是待信任集——web 逐项信任按钮的批量等价）；
    /// 已信任项不预选（撤销语义独立）。仅在无手选时执行，不覆盖用户取消。
    private func preselectPendingItems() {
        guard selectedIds.isEmpty else { return }
        let pending = items.filter { !$0.isTrusted }.map(\.id)
        guard !pending.isEmpty else { return }
        selectedIds = Set(pending)
    }

    @ViewBuilder
    private var summaryRow: some View {
        if let summary = interaction.impact ?? interaction.title, !summary.isEmpty {
            Text(summary)
                .font(T.font(12))
                .foregroundColor(T.text2)
                .lineLimit(3)
                .accessibilityIdentifier("05-hook-summary")
        }
    }

    /// 审核项未随交互到达（元素 schema 未取证/桌面未携）——诚实空态 + 重发请求入口，
    /// 不虚构条目（U-5 同口径：无数据不假成功）
    private var emptyHint: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("审核项尚未同步到移动端")
                .font(T.font(12))
                .foregroundColor(T.text3)
            requestButton
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(T.sp2)
        .background(T.bgCode)
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
    }

    private var requestButton: some View {
        Button {
            Task { await requestReview() }
        } label: {
            HStack(spacing: 5) {
                if requesting {
                    SpinnerView(size: 11)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11))
                }
                Text(requesting ? "请求中…" : "重新请求审核")
                    .font(T.font(11.5, .medium))
            }
            .foregroundColor(T.accentText)
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 30)
            .background(T.accentDim)
            .clipShape(Capsule())
        }
        .disabled(requesting || deciding)
        .accessibilityIdentifier("05-hook-act-request")
    }

    /// 单项行：选择圈（待信任项）+ 标题/说明 + 状态胶囊；已信任项呈撤销入口
    private func itemRow(_ item: WorkspaceHookReviewItem) -> some View {
        HStack(alignment: .top, spacing: T.sp2) {
            if item.isTrusted {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 14))
                    .foregroundColor(T.accentText)
                    .frame(width: 24)
            } else {
                Button {
                    if selectedIds.contains(item.id) {
                        selectedIds.remove(item.id)
                    } else {
                        selectedIds.insert(item.id)
                    }
                } label: {
                    Image(systemName: selectedIds.contains(item.id)
                          ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 16))
                        .foregroundColor(selectedIds.contains(item.id) ? T.accentText : T.text3)
                        .frame(width: 24, minHeight: 30)
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-hook-select-\(item.id)")
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title ?? item.id)
                    .font(T.font(12.5, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if let detail = item.detail, !detail.isEmpty {
                    Text(detail)
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            if let trustState = item.trustState, !trustState.isEmpty {
                Text(trustState == "trusted_persistent" ? "已信任" : trustState)
                    .font(T.font(10))
                    .foregroundColor(item.isTrusted ? T.accentText : T.text3)
            }
            if item.isTrusted, onRevoke != nil {
                Button {
                    pendingRevokeItem = item
                } label: {
                    Text("撤销")
                        .font(T.font(11, .medium))
                        .foregroundColor(T.red)
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-hook-revoke-\(item.id)")
            }
        }
        .padding(.horizontal, T.sp2)
        .padding(.vertical, 4)
        .background(T.bgCode.opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-hook-item-\(item.id)")
    }

    /// 信任所选（主按钮；无待信任项时整组不渲染——空态由 emptyHint 承载）
    @ViewBuilder
    private var trustActionButton: some View {
        let pendingItems = items.filter { !$0.isTrusted }
        if !pendingItems.isEmpty {
            HStack(spacing: T.sp2) {
                Button {
                    showTrustConfirm = true
                } label: {
                    Text(deciding ? "信任中…" : "信任所选（\(selectedItems.count)）")
                        .font(T.font(14, .semibold))
                        .foregroundColor(selectedItems.isEmpty ? T.text3 : T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(selectedItems.isEmpty ? T.bgInput : T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(deciding || selectedItems.isEmpty)
                .accessibilityIdentifier("05-hook-act-trust")
                requestButton
            }
        }
    }

    private func trustSelected() async {
        guard !selectedItems.isEmpty else { return }
        deciding = true
        let ids = selectedItems.map(\.id)
        if let failure = await onTrust(ids) {
            decisionError = failure
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        } else {
            decisionError = nil
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            // 卡片撤下依赖桌面 pendingInteractions 回流；失败态卡片保留可重试
        }
        deciding = false
    }

    private func requestReview() async {
        requesting = true
        if let failure = await onRequest() {
            decisionError = failure
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        } else {
            decisionError = nil
        }
        requesting = false
    }

    private func revoke(_ item: WorkspaceHookReviewItem) async {
        guard let onRevoke else { return }
        deciding = true
        if let failure = await onRevoke([item.id]) {
            decisionError = failure
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        } else {
            decisionError = nil
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        deciding = false
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
    @Environment(AppSession.self) private var session
    @Bindable var viewModel: ChatViewModel
    @FocusState private var inputFocused: Bool
    /// U-6 发送失败持久错误行（非 3s 自动消失）：失败时置位 + 草稿已由 viewModel
    /// 回填；清除时机 = 发送成功 / 用户手动编辑 draft / 离开会话（数据安全提示
    /// 必须显式关闭，设计稿 §5.1）
    @State private var sendFailure: String?
    @State private var retryingSend = false
    /// U-6：未送达时回填的草稿快照——用户手动编辑（draft 与快照不一致）即清错误行
    @State private var restoredDraftText: String?
    /// 执行目标偏好（G-012 如实口径）：☁️ 云端沙盒 / 💻 我的 Mac——当前仅记录发送偏好
    /// 并持久化；sendText 信封无目标路由字段，消息仍经当前连接的会话链路下发
    /// （客户端发命令、桌面代执行边界不变）。UI 已按验收 B 以「偏好」如实标注。
    @State private var executionTarget = DeviceOption.cloudSandbox

    // P1-1 附件三来源（设计稿 1.3①：confirmationDialog 拍照/照片图库/文件）
    @State private var showAttachmentSource = false
    @State private var showPhotoPicker = false
    @State private var showCamera = false
    @State private var showFileImporter = false
    @State private var photoPickerItems: [PhotosPickerItem] = []

    // P2-7 压缩入口（设计稿 §7A：contextMeter 即入口，点击弹确认；进行中不可再点）
    @State private var showCompactConfirm = false
    // P1-2 模式行一次性标注（设计稿 §2.7③：协作模式无桌面读面，首屏显示本机偏好）
    @State private var modeDisclaimerShown = false

    var body: some View {
        VStack(spacing: T.sp2) {
            // HIDDEN(对齐修复): composer 执行目标菜单隐藏（sendText 信封无目标路由字段，
            // 「仅记录偏好」的本地菜单构成假选择——设计稿 H5）· 恢复条件：sendText 有
            // 目标路由字段。E2E 兼容：-ZCodeDemoData 演示开关下保留（FeatureCompletion
            // test07 目标选择器用例依赖该行）；ExecutionTargetStore 本体保留（持久化面）
            if AppSession.isDemoDataEnabled {
                targetRow
            }
            // P1-2 模式行（设计稿 §2.2：独立 modeRow 插在 targetRow 与 switchHint 之间
            // ——不挤 targetRow 一行，窄屏溢出；仅连接态渲染，同 remoteChips 口径）
            if viewModel.isReadOnly {
                modeRow
            }
            // U-6 发送失败持久错误行（优先级高于 3s 轻提示——数据安全提示不自动消失）
            if let sendFailure {
                HStack(spacing: T.sp2) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundColor(T.red)
                    Text(sendFailure)
                        .font(T.font(12))
                        .foregroundColor(T.red)
                    Spacer(minLength: 0)
                    Button {
                        Task { await retrySend() }
                    } label: {
                        Text(retryingSend ? String(localized: "重试中…") : String(localized: "重试"))
                            .font(T.font(12, .semibold))
                            .foregroundColor(T.red)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .disabled(retryingSend)
                    .accessibilityIdentifier("05-composer-send-retry")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("05-composer-send-fail")
            } else if viewModel.isCompacting {
                // P2-7 压缩进行中（提示不自动消失，覆盖 3s 清除口径；switchHint 同通道）
                Text(String(localized: "压缩中…"))
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-composer-compact-hint")
            } else if let message = switchHint {
                Text(message)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-composer-switch-hint")
            }
            // P1-1 待发附件条 + 失败/提示行（无待发不渲染整行——「无排队不渲染」同口径）
            if !viewModel.uploads.isEmpty {
                attachmentStrip
            }
            if let hint = viewModel.uploads.hint {
                Text(hint)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-attach-hint")
            } else if let failure = viewModel.uploads.firstFailureText {
                Text(failure)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-attach-fail-hint")
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
        // U-6：用户手动编辑草稿（与回填快照不一致）即清发送失败行；回填写回本身
        // 不触发（写回后 draft == 快照）。离开会话视图销毁，行随之消失
        .onChange(of: viewModel.draft) { _, newDraft in
            if let restored = restoredDraftText, newDraft != restored {
                sendFailure = nil
                restoredDraftText = nil
            }
        }
        .confirmationDialog(String(localized: "添加附件"), isPresented: $showAttachmentSource, titleVisibility: .visible) {
            Button(String(localized: "拍照")) { showCamera = true }
            Button(String(localized: "照片图库")) { showPhotoPicker = true }
            Button(String(localized: "文件")) { showFileImporter = true }
            Button(String(localized: "取消"), role: .cancel) {}
        }
        // P2-7 压缩确认（设计稿 §7A：主键普通按钮非 destructive——压缩不丢数据、
        // 回执失败即无副作用；文案照设计稿）
        .confirmationDialog(
            String(localized: "压缩上下文？"), isPresented: $showCompactConfirm,
            titleVisibility: .visible) {
            Button(String(localized: "压缩")) {
                Task { await runCompact() }
            }
            .accessibilityIdentifier("05-compact-confirm")
            Button(String(localized: "取消"), role: .cancel) {}
        } message: {
            Text(String(localized: "让桌面端把历史对话压缩为摘要，释放上下文空间。压缩可能持续数十秒，期间请勿下发新指令。"))
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoPickerItems, matching: .images)
        .onChange(of: photoPickerItems) { _, newItems in
            guard !newItems.isEmpty else { return }
            photoPickerItems = []
            Task { await addPhotoPickerItems(newItems) }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true) { result in
            importFiles(result)
        }
        .sheet(isPresented: $showCamera) {
            CameraPicker { image in
                addCameraImage(image)
            }
            .ignoresSafeArea()
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

    // MARK: P1-2 模式行（协作 Plan/Build + 投递 立即/排队/引导；设计稿 §2.2）
    //
    // 胶囊样式照抄 executionTargetMenu（11.5pt medium、bgInput、Capsule、minHeight 44）；
    // identifier 挂 Menu 本体（同 05-act-target 教训：挂 label 会被运行时逐层拼接）。
    // 状态单源在 viewModel——切换成功才落态并持久化，send() 同读该值携
    // requestedDelivery，UI 态与发送参数不两张皮。

    private var modeRow: some View {
        HStack(spacing: T.sp2) {
            collaborationModeMenu
            deliveryModeMenu
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-composer-mode-row")
        .onAppear {
            // §2.7③：协作模式无桌面读面（全 Sources 无投影），首屏只能显示本机偏好
            // ——一次性如实标注；点按切换即真实 CAS 下发并按回执落态。
            // §4.3：now 档投递同为纯本机态（A-2 后不下发命令），改显投递侧同句式
            guard !modeDisclaimerShown else { return }
            modeDisclaimerShown = true
            showSwitchHint(viewModel.deliveryMode == "now"
                ? String(localized: "投递模式显示为本机偏好 · 桌面端会话模式以执行行为为准")
                : String(localized: "显示为本机偏好 · 桌面端实际模式以执行行为为准"))
        }
    }

    /// 协作模式胶囊（Plan=先出计划 / Build=直接执行；switchCollaborationMode CAS）
    private var collaborationModeMenu: some View {
        Menu {
            ForEach(ComposerModeStore.collaborationModes, id: \.self) { mode in
                Button {
                    selectCollaborationMode(mode)
                } label: {
                    modeMenuItem(
                        title: mode == "plan" ? "Plan" : "Build",
                        detail: mode == "plan" ? "先出计划，改动需经你确认" : "直接执行改动",
                        icon: mode == "plan" ? "list.clipboard" : "hammer",
                        selected: viewModel.collaborationMode == mode)
                }
            }
        } label: {
            modePill(
                icon: viewModel.collaborationMode == "plan" ? "list.clipboard" : "hammer",
                text: viewModel.collaborationMode == "plan" ? "Plan" : "Build")
        }
        .accessibilityIdentifier("05-chip-mode")
    }

    /// 投递模式胶囊（立即/排队/引导）。A-2：now 档不下发 setFollowupMode（web 枚举
    /// 仅 queue|guide），仅本地态 + send 恒携 requestedDelivery:"startNow"；
    /// queue/guide 照常 CAS 下发 + requestedDelivery 联动）
    private var deliveryModeMenu: some View {
        Menu {
            ForEach(ComposerModeStore.deliveryModes, id: \.self) { mode in
                Button {
                    selectDeliveryMode(mode)
                } label: {
                    modeMenuItem(
                        title: Self.deliveryLabel(mode),
                        detail: mode == "now"
                            ? "本机默认 · 不下发模式命令，消息逐条直接投递"
                            : mode == "queue"
                                ? "回合进行中发送将排队，回合结束后自动投递（桌面默认）"
                                : "作为引导补充注入当前回合",
                        icon: Self.deliveryIcon(mode),
                        selected: viewModel.deliveryMode == mode)
                }
            }
            // guide 桌面语义未取证（仅盘点报告词表口述；设计稿 §2.2 要求菜单项标注）
            Section {
                Text(String(localized: "引导模式桌面语义以实际执行行为为准"))
            }
        } label: {
            modePill(
                icon: Self.deliveryIcon(viewModel.deliveryMode),
                text: Self.deliveryLabel(viewModel.deliveryMode))
        }
        .accessibilityIdentifier("05-chip-delivery")
    }

    /// 模式胶囊 label（executionTargetMenu :833-847 同款：11.5pt medium、bgInput、
    /// Capsule、minHeight 44——与工具行 26pt 小 chip 区分，模式是常驻一级控件）
    private func modePill(icon: String, text: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 11))
            Text(text)
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

    static func deliveryLabel(_ mode: String) -> String {
        switch mode {
        case "queue": return String(localized: "排队")
        case "guide": return String(localized: "引导")
        default: return String(localized: "立即发送")
        }
    }

    static func deliveryIcon(_ mode: String) -> String {
        switch mode {
        case "queue": return "list.number"
        case "guide": return "arrow.triangle.merge"
        default: return "bolt.fill"
        }
    }

    /// 菜单项（✓ 标当前 = modelRow 先例；副文案设计稿 §2.2 关键文案）
    private func modeMenuItem(title: String, detail: String, icon: String, selected: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                Text(detail)
                    .font(T.font(10))
                    .foregroundColor(T.text3)
            }
            if selected {
                Spacer(minLength: 12)
                Image(systemName: "checkmark")
            }
        }
    }

    private func selectCollaborationMode(_ mode: String) {
        Task {
            if let message = await viewModel.switchCollaborationMode(mode) {
                showSwitchHint(message)
            } else {
                UISelectionFeedbackGenerator().selectionChanged()
            }
        }
    }

    private func selectDeliveryMode(_ mode: String) {
        Task {
            if let message = await viewModel.setDeliveryMode(mode) {
                showSwitchHint(message)
            } else {
                UISelectionFeedbackGenerator().selectionChanged()
                // 设计稿 §4.4 三段式：now 档成功=本地默认（桌面会话模式不变）；
                // queue/guide 成功=「本条消息起按「X」投递」
                showSwitchHint(mode == "now"
                    ? String(localized: "已设为本机默认 · 桌面端会话模式不变")
                    : String(localized: "本条消息起按「\(Self.deliveryLabel(mode))」投递"))
            }
        }
    }

    // MARK: P2-7 压缩入口（contextMeter 即入口：用量条是压缩动机，就地可发现）

    /// 压缩确认后的下发与完成判定（设计稿 §7A：完成 hint / 失败 hint 各 3s；
    /// 「压缩中…」由 viewModel.isCompacting 驱动、不自动消失）
    private func runCompact() async {
        let message = await viewModel.compactContext()
        showSwitchHint(message)
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
                // P2-7 压缩入口（设计稿 §7A：用量条即压缩动机，就地可发现）。
                // 显式连接态 gating——不以 contextMeter 条件渲染作隐式门槛（演示态
                // Mock 恒返回非 nil usage，toolsRow 照样渲染「上下文·演示」，无 gating
                // 则入口会出现在命令不可达的演示态）；进行中禁点防重复下发
                if viewModel.isReadOnly {
                    Button {
                        showCompactConfirm = true
                    } label: {
                        contextMeter(usage, demo: false)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isCompacting)
                    .accessibilityIdentifier("05-compact-entry")
                } else {
                    contextMeter(usage, demo: false)
                }
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
            // P1-1：附件入口（设计稿 1.2——inputRow 首位 📎 44pt 热区；连接态渲染，
            // 演示态无上传面不出入口）
            if viewModel.isReadOnly {
                Button {
                    showAttachmentSource = true
                } label: {
                    Image(systemName: "plus.circle")
                        .font(T.font(20))
                        .foregroundColor(T.text2)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("05-attach-button")
            }

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
                .onSubmit { Task { await sendAndHintDelivery() } }
                .accessibilityIdentifier("05-composer-input")

            // G-023：麦克风死按钮移除（原仅图标动画无录音/听写行为；语音输入走 iOS
            // 键盘自带听写，长按语音待 G-055 真实现）

            sendButton
        }
    }

    private var sendButton: some View {
        Button {
            Task { await sendAndHintDelivery() }
            inputFocused = false
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(T.onAccent)
                .frame(width: 44, height: 44)
                .background(viewModel.canSend ? T.accent : T.bgInput)
                .clipShape(Circle())
        }
        .disabled(!viewModel.canSend)
        .accessibilityIdentifier("05-composer-send")
    }

    /// 发送 + 投递提示（设计稿 §2.3：queue 档送达后 hint「已加入排队」，消息随后
    /// 经 QueueBarView 回流呈现；now/guide 档无额外提示）。
    /// U-6：失败不再静默——持久错误行（断线子文案区分）+ 草稿已由 viewModel 回填
    /// + 焦点回输入框 + error 触觉；重试钮复用本方法。
    private func sendAndHintDelivery() async {
        let delivered = await viewModel.send()
        if delivered {
            sendFailure = nil
            restoredDraftText = nil
            if viewModel.deliveryMode == "queue" {
                showSwitchHint(String(localized: "已加入排队"))
            }
        } else if let failedText = viewModel.lastSendUndeliveredText, failedText == viewModel.draft {
            // 真正的未送达（guard 路径不算）：草稿已回填，错误行持久在场直至
            // 成功/手动编辑/离开展示（设计稿 §5.1 状态矩阵）
            let disconnected: Bool
            if case .connected = session.mode { disconnected = false } else { disconnected = true }
            sendFailure = disconnected
                ? String(localized: "消息未送达 · 连接已断开，草稿已保留")
                : String(localized: "消息未送达 · 已恢复草稿")
            restoredDraftText = failedText
            inputFocused = true
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }

    /// U-6 重试：携回填后的 draft 重发；成功清错误行，再失败行保留
    private func retrySend() async {
        retryingSend = true
        defer { retryingSend = false }
        await sendAndHintDelivery()
    }

    // MARK: P1-1 待发附件条（设计稿 1.2：横向滚动 72×72 缩略卡 + 进度 + 角标）

    private var attachmentStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: T.sp2) {
                ForEach(viewModel.uploads.items) { item in
                    PendingAttachmentThumb(
                        item: item,
                        onRemove: { viewModel.uploads.remove(item.id) },
                        onRetry: { viewModel.uploads.retry(item.id) })
                }
            }
            .padding(.horizontal, T.sp1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-attach-strip")
    }

    // MARK: 附件三来源入队（设计稿 1.3②：选定后立即建上传事务，不等发送）

    private func addPhotoPickerItems(_ items: [PhotosPickerItem]) {
        Task {
            for item in items {
                let contentType = item.supportedContentTypes.first
                let ext = contentType?.preferredFilenameExtension ?? "jpg"
                let mediaType = contentType?.preferredMIMEType ?? "image/jpeg"
                guard let data = try? await item.loadTransferable(type: Data.self) else {
                    viewModel.uploads.setHint(String(localized: "照片读取失败，已跳过"))
                    continue
                }
                if viewModel.uploads.add(
                    name: "IMG_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)",
                    mediaType: mediaType, data: data) {
                    await startUploadIfNeeded()
                }
            }
        }
    }

    private func addCameraImage(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.9) else {
            viewModel.uploads.setHint(String(localized: "照片编码失败，已跳过"))
            return
        }
        if viewModel.uploads.add(
            name: "IMG_\(Int(Date().timeIntervalSince1970 * 1000)).jpg",
            mediaType: "image/jpeg", data: data) {
            Task { await startUploadIfNeeded() }
        }
    }

    private func importFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                viewModel.uploads.setHint(String(localized: "《\(url.lastPathComponent)》读取失败，已跳过"))
                continue
            }
            if viewModel.uploads.add(
                name: url.lastPathComponent,
                mediaType: AttachmentUploadService.mediaType(forFileExtension: url.pathExtension),
                data: data) {
                Task { await startUploadIfNeeded() }
            }
        }
    }

    /// 设计稿 1.3②：选定后立即建上传事务（不等发送）；已在上传中的项自动跳过
    private func startUploadIfNeeded() async {
        _ = await viewModel.uploads.ensureAllCommitted()
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

// MARK: - 待发附件缩略卡（P1-1 设计稿 1.2/1.4）
//
// 72×72 缩略规格 = AttachmentThumbView（132×96+T.rM+T.border 描边）等比缩；
// 底部 4px ThinProgressBar + mono 百分比；成功勾角标 / 失败橙角标（点按重试）；
// 右上角 ✕ 移除（44pt 热区、T.bgCard 半透明圆底）。非图片 doc.icon 占位
// （mediaType 判定同 AttachmentThumbView）。
struct PendingAttachmentThumb: View {
    let item: PendingAttachment
    var onRemove: () -> Void
    var onRetry: () -> Void

    private var previewImage: UIImage? {
        guard item.isImage, let image = UIImage(data: item.data) else { return nil }
        return image
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            thumbnail
            Text(item.name)
                .font(T.mono(10))
                .foregroundColor(T.text3)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(width: 72, alignment: .leading)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-attach-item")
    }

    private var thumbnail: some View {
        ZStack(alignment: .topTrailing) {
            ZStack(alignment: .bottom) {
                base
                if item.state == .uploading {
                    VStack(spacing: 1) {
                        ThinProgressBar(progress: item.progress, height: 4, tint: T.accent)
                        Text(item.percentText)
                            .font(T.mono(9.5))
                            .foregroundColor(T.text2)
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 3)
                }
                if item.state == .pending {
                    SpinnerView(size: 14).padding(4)
                }
            }
            .frame(width: 72, height: 72)
            .overlay(alignment: .topLeading) { statusBadge }

            // ✕ 移除（44pt 热区包住 18pt 圆底——同 targetRow 热区口径）
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(T.text2)
                    .frame(width: 18, height: 18)
                    .background(T.bgCard.opacity(0.9))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .frame(width: 44, height: 44, alignment: .topTrailing)
            .accessibilityIdentifier("05-attach-remove")
        }
    }

    private var base: some View {
        Group {
            if let image = previewImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                T.bgInput
                    .overlay(
                        Image(systemName: item.isImage ? "photo" : "doc.fill")
                            .font(.system(size: 18))
                            .foregroundColor(T.text3))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
        .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.border, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: T.rS))
        .onTapGesture {
            // 失败点条目重试（设计稿 1.2 失败角标「点条目重试」）
            if item.state == .failed { onRetry() }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch item.state {
        case .committed:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 15))
                .foregroundColor(T.accentText)
                .padding(3)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 15))
                .foregroundColor(T.orange)
                .padding(3)
        default:
            EmptyView()
        }
    }
}

// MARK: - 相机拍摄（P1-1 附件来源①：UIImagePickerController camera 封装）
//
// 权限键 NSCameraUsageDescription（project.yml：扫码 + 附件拍摄双用途文案）。
struct CameraPicker: UIViewControllerRepresentable {
    var onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController()
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            controller.sourceType = .camera
        }
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                parent.onImage(image)
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

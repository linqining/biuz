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
            // 桌面 workflow 运行进度（要求 5 · 只读：阶段节点链 + 子代理卡片 + 进度点；
            // nil = 无数据不渲染。置于头部下固定区：不随消息流滚动，吸底锚点不影响可达性）
            if let run = viewModel.workflowRun {
                WorkflowPanelView(run: run) { sessionId in
                    await viewModel.storeActorTranscript(sessionId: sessionId)
                }
                .padding(.horizontal, T.sp4)
                .padding(.bottom, T.sp2)
            }
            if viewModel.isSearchActive {
                messageSearchBar(viewModel)
            }
            messageList(viewModel)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ComposerBar(viewModel: viewModel)
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
                    // 向上分页入口（named gap 补全：beforeRowId 此前无调用入口）
                    if viewModel.canLoadOlder {
                        Button {
                            Task { await viewModel.loadOlder() }
                        } label: {
                            HStack(spacing: T.sp1) {
                                Image(systemName: "chevron.up")
                                    .font(.system(size: 10, weight: .semibold))
                                Text("加载更早消息")
                                    .font(T.font(12, .medium))
                            }
                            .foregroundColor(T.accentText)
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("05-act-load-older")
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
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.bottom)
            .onChange(of: viewModel.messages.count) { _, _ in
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
}

// MARK: - 桌面 workflow 运行进度面板（要求 5 · 只读展示）
// 形态对齐桌面基准：阶段节点链（状态圆点 + 竖向连线）+ 子代理卡片（isSubagent 节点）+
// 头部进度点（已完成 n/m）。数据边界：桌面 workflowRun schema 未完整取证（调研 gaps），
// 数据粒度不足（无 nodes）时整块不渲染，会话列表行保留 isRunning 进度点降级面；
// 本面板纯只读，不新增任何发送命令。

struct WorkflowPanelView: View {
    let run: WorkflowRunSummary
    var onLoadActorTranscript: (String) async -> [ChatMessage] = { _ in [] }
    @State private var transcriptActor: WorkflowActorSummary?
    @State private var transcript: [ChatMessage] = []
    @State private var transcriptLoading = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            header
            nodeChain
            if !run.actors.isEmpty {
                actorCards
            }
            capacityRow
        }
        .padding(T.sp3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.violet.opacity(0.35), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-workflow-panel")
    }

    /// 头部：工作流名 + 五态状态胶囊（completed/errored/stopped 精确词）+ 进度点计数
    private var header: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "flowchart.fill")
                .font(.system(size: 13))
                .foregroundColor(T.violet)
            Text(run.name)
                .font(T.font(13, .semibold))
                .foregroundColor(T.text)
                .lineLimit(1)
            StatusPill(text: Self.runStatusText(run), kind: Self.runPillKind(run), compact: true)
            if run.truncated {
                Text(String(localized: "已截断"))
                    .font(T.mono(9.5))
                    .foregroundColor(T.orange)
            }
            Spacer(minLength: 0)
            // 进度点计数（done / total）
            Text("\(run.doneCount)/\(run.nodes.count)")
                .font(T.mono(10.5, .semibold))
                .foregroundColor(run.status == .done ? T.accentText : T.text3)
        }
    }

    /// 子代理实例卡片（G-008 通路 B actors[] 权威投影：waiting|running|completed + 出生阶段）
    private var actorCards: some View {
        VStack(alignment: .leading, spacing: T.sp1) {
            ForEach(run.actors) { actor in
                // G-021：sessionId 在场整卡可点 → 只读转录下钻；无 sessionId 不渲染入口
                let actionable = actor.sessionId != nil
                Button {
                    guard let sessionId = actor.sessionId else { return }
                    transcriptActor = actor
                    transcript = []
                    transcriptLoading = true
                    Task {
                        transcript = await onLoadActorTranscript(sessionId)
                        transcriptLoading = false
                    }
                } label: {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "cpu")
                            .font(.system(size: 11))
                            .foregroundColor(actor.status == .running ? T.blue : T.text3)
                        Text(actor.name ?? String(localized: "子代理"))
                            .font(T.font(12, .medium))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        if let phaseName = actor.phaseName, !phaseName.isEmpty {
                            Text(phaseName)
                                .font(T.mono(9.5))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        if actionable {
                            Image(systemName: "chevron.right")
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundColor(T.text3)
                        }
                        StatusPill(
                            text: Self.actorStatusText(actor),
                            kind: actor.rawStatus == "completed" ? .done : (actor.rawStatus == "running" ? .run : .wait),
                            compact: true)
                    }
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 5)
                    .background(T.blueDim.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .buttonStyle(.plain)
                .disabled(!actionable)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("05-workflow-actor-\(actor.id)")
                .accessibilityHint(actionable ? String(localized: "查看子代理只读转录") : "")
            }
        }
        .sheet(item: $transcriptActor) { actor in
            ActorTranscriptSheet(
                actor: actor,
                messages: transcript,
                isLoading: transcriptLoading)
        }
    }

    /// 容量与产物只读行（reports≤64/pendingQuestions≤32/artifacts≤32 由服务端有界下发，
    /// 这里只展示计数；concurrency/ceiling/truncated/resumable 均为 CLI 算好透传，不自推导）
    @ViewBuilder
    private var capacityRow: some View {
        if !capacityFragments.isEmpty {
            HStack(spacing: T.sp2) {
                ForEach(capacityFragments, id: \.self) { fragment in
                    Text(fragment)
                        .font(T.mono(9.5))
                        .foregroundColor(T.text3)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private var capacityFragments: [String] {
        var fragments: [String] = []
        if run.artifactsCount > 0 {
            fragments.append(String(format: String(localized: "产物 %lld"), run.artifactsCount))
        }
        if run.pendingQuestionsCount > 0 {
            fragments.append(String(format: String(localized: "待答问题 %lld"), run.pendingQuestionsCount))
        }
        if let concurrency = run.concurrency {
            if let ceiling = run.concurrencyCeiling {
                fragments.append(String(format: String(localized: "并发 %lld/%lld"), concurrency, ceiling))
            } else {
                fragments.append(String(format: String(localized: "并发 %lld"), concurrency))
            }
        }
        if run.resumable {
            fragments.append(String(localized: "可恢复"))
        }
        return fragments
    }

    /// 阶段节点链：左侧状态圆点 + 竖向连线；子代理节点渲染为卡片
    private var nodeChain: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(run.nodes.enumerated()), id: \.element.id) { index, node in
                HStack(alignment: .top, spacing: T.sp3) {
                    // 状态圆点 + 竖向连线（末节点不画）
                    VStack(spacing: 0) {
                        nodeDot(node)
                        if index < run.nodes.count - 1 {
                            Rectangle()
                                .fill(T.border)
                                .frame(width: 1.5)
                                .frame(maxHeight: .infinity)
                        }
                    }
                    .frame(width: 16)

                    if node.isSubagent {
                        subagentCard(node, index: index)
                            .padding(.bottom, T.sp2)
                    } else {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(node.label)
                                .font(T.font(12.5, node.status == .running ? .semibold : .regular))
                                .foregroundColor(node.status == .done ? T.text3 : T.text)
                                .lineLimit(1)
                            if let summary = node.summary, !summary.isEmpty {
                                Text(summary)
                                    .font(T.font(11))
                                    .foregroundColor(T.text3)
                                    .lineLimit(1)
                            }
                        }
                        .padding(.bottom, index < run.nodes.count - 1 ? T.sp3 : 0)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// 节点状态圆点（done 复选 / running spinner / failed 红叉 / pending 空心）
    @ViewBuilder
    private func nodeDot(_ node: WorkflowNodeSummary) -> some View {
        switch node.status {
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.accentText)
                .frame(width: 16)
        case .running:
            SpinnerView(color: T.blue, size: 14)
                .frame(width: 16)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.red)
                .frame(width: 16)
        case .pending:
            Circle().strokeBorder(T.borderStrong, lineWidth: 1.5)
                .frame(width: 13, height: 13)
                .frame(width: 16)
        }
    }

    /// 子代理卡片（🤖 名称 + 状态胶囊 + 简述；浅底弱化）
    private func subagentCard(_ node: WorkflowNodeSummary, index: Int) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "cpu")
                .font(.system(size: 12))
                .foregroundColor(T.blue)
            VStack(alignment: .leading, spacing: 1) {
                Text(node.label)
                    .font(T.font(12, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if let summary = node.summary, !summary.isEmpty {
                    Text(summary)
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            StatusPill(text: Self.statusText(node.status), kind: Self.pillKind(node.status), compact: true)
        }
        .padding(.horizontal, T.sp2)
        .padding(.vertical, 6)
        .background(T.blueDim.opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-workflow-node-\(index)")
    }

    private static func statusText(_ status: WorkflowStepStatus) -> String {
        switch status {
        case .running: return String(localized: "运行中")
        case .done: return String(localized: "已完成")
        case .failed: return String(localized: "失败")
        case .pending: return String(localized: "待执行")
        }
    }

    private static func pillKind(_ status: WorkflowStepStatus) -> PillKind {
        switch status {
        case .running: return .run
        case .done: return .done
        case .failed: return .err
        case .pending: return .wait
        }
    }

    /// run 五态文案（workflow-runs.ts:365 词表原词映射；stopped/errored 精确呈现）
    private static func runStatusText(_ run: WorkflowRunSummary) -> String {
        switch run.rawStatus {
        case "running": return String(localized: "运行中")
        case "completed": return String(localized: "已完成")
        case "errored": return String(localized: "失败")
        case "stopped": return String(localized: "已停止")
        default: return String(localized: "待执行")
        }
    }

    private static func runPillKind(_ run: WorkflowRunSummary) -> PillKind {
        switch run.rawStatus {
        case "running": return .run
        case "completed": return .done
        case "errored": return .err
        case "stopped": return .tag
        default: return .wait
        }
    }

    /// 子代理实例三态文案（waiting|running|completed）
    private static func actorStatusText(_ actor: WorkflowActorSummary) -> String {
        switch actor.rawStatus {
        case "running": return String(localized: "运行中")
        case "completed": return String(localized: "已完成")
        default: return String(localized: "等待中")
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
            .accessibilityIdentifier("05-act-target")
        }
    }

    private func selectTarget(_ option: DeviceOption) {
        executionTarget = option
        ExecutionTargetStore.setTarget(option, conversationID: viewModel.conversationID)
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// 桌面端模型/思考档只读展示（不调 set*；切换属边界外，须在桌面端完成）
    private func remoteChips(_ selection: ModelSelectionInfo) -> some View {
        HStack(spacing: T.sp2) {
            pill(icon: nil, text: selection.activeModel ?? "--")
            pill(icon: "brain", text: "思考·\(selection.activeThoughtLevel ?? "--")")
            Spacer(minLength: 0)
            if let usage = viewModel.contextUsage {
                contextMeter(usage, demo: false)
            }
        }
        .accessibilityIdentifier("05-composer-remote-chips")
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

    private func pill(icon: String?, text: String) -> some View {
        HStack(spacing: 4) {
            if let icon {
                Image(systemName: icon).font(.system(size: 10))
            }
            Text(text).font(T.font(11, .medium))
        }
        .foregroundColor(T.text2)
        .padding(.horizontal, T.sp2)
        .frame(height: 26)
        .background(T.bgInput)
        .clipShape(Capsule())
    }
}

import SwiftUI

/// 屏 05 · Agent 对话（Push L2）
/// 连接态与演示态共用发送/应答链路（v3 纠偏：客户端发命令、桌面代执行）；
/// 连接态增量面：桌面端模型只读 chips、待审批交互卡、向上分页。
struct ChatView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    let conversationID: String

    @State private var viewModel: ChatViewModel?

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
        .task {
            if viewModel == nil {
                viewModel = ChatViewModel(store: store, conversationID: conversationID)
                await viewModel?.load()
                await viewModel?.observe()
            }
        }
        .task {
            // 桌面端模型选择变化流（连接态 onDidChange 驱动 chips 只读刷新；演示态流立即结束）
            await viewModel?.observeModelSelection()
        }
    }

    private func content(_ viewModel: ChatViewModel) -> some View {
        VStack(spacing: 0) {
            header(viewModel)
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
                        if interaction.isPermission {
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
                        MessageView(message: message) { reply in
                            Task { await viewModel.answerQuestion(reply) }
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

// MARK: - Composer（工具行 + 44px 胶囊输入行；输入框 16px 防聚焦缩放）
// v3 纠偏：连接态输入区可用（sendText 桌面代执行）；工具行连接态展示桌面端模型
// 只读 chips（model-selection.getView 真实数据，缺数据不渲染），演示态沿用本机设置。

struct ComposerBar: View {
    @Environment(AppSettingsModel.self) private var settings
    @Bindable var viewModel: ChatViewModel
    @FocusState private var inputFocused: Bool
    @State private var micTapped = false

    var body: some View {
        VStack(spacing: T.sp2) {
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
    }

    /// 桌面端模型/思考档只读展示（不调 set*；切换属边界外，须在桌面端完成）
    private func remoteChips(_ selection: ModelSelectionInfo) -> some View {
        HStack(spacing: T.sp2) {
            pill(icon: nil, text: selection.activeModel ?? "--")
            pill(icon: "brain", text: "思考·\(selection.activeThoughtLevel ?? "--")")
            Spacer(minLength: 0)
        }
        .accessibilityIdentifier("05-composer-remote-chips")
    }

    private var toolsRow: some View {
        HStack(spacing: T.sp2) {
            pill(icon: nil, text: settings.value.model)
            pill(icon: "brain", text: "思考·\(settings.value.thoughtLevel.label)")
            Spacer(minLength: 0)
            contextMeter
        }
        .accessibilityIdentifier("05-composer-tools")
    }

    private var contextMeter: some View {
        HStack(spacing: T.sp1) {
            Text("上下文")
                .font(T.font(11))
                .foregroundColor(T.text3)
            ThinProgressBar(progress: 0.64, height: 4, tint: T.accent)
                .frame(width: 56)
            Text("64%")
                .font(T.mono(10.5))
                .foregroundColor(T.text3)
        }
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

            Button {
                micTapped = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { micTapped = false }
            } label: {
                Image(systemName: micTapped ? "waveform" : "mic")
                    .font(.system(size: 17))
                    .foregroundColor(T.text2)
                    .frame(width: 44, height: 44)
            }
            .accessibilityIdentifier("05-composer-mic")

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

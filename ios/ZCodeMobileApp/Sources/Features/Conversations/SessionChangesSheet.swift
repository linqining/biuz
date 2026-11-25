import SwiftUI

// MARK: - 会话「更改」sheet（2026-11-25 三 tab 重构，design/design-spec.md §1）
//
// 原全局「文件」tab 的会话化承载：DiffReviewView 的「本次会话」分段此前取
// 「最近活跃会话」兜底（DiffReviewView.swift「文件 Tab 无会话上下文」注释）——
// 三 tab 后从会话页打开，sessionId 显式传入，作用域终于正确；默认落在「本次会话」，
// 未暂存/已暂存两档保留（web GitPane 同源分段）。
// 数据 = conversationFileChangesV4 / git.getChanges；写面 = git 通道
// stagePaths/unstagePaths 桌面代执行（web GitPane 同参同面）；「全部批准」仅在
// 会话/未暂存源出现（会话源 = 逐文件 stagePaths，未暂存源 = store.approveAll 工作区
// 未决集——与原全局动作栏同语义）。U-5：写面失败原文上屏，卡片不撤。
struct SessionChangesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.fileStore) private var store
    @Environment(\.conversationStore) private var conversationStore
    let sessionId: String
    let sessionTitle: String

    @State private var viewModel = DiffViewModel()
    /// 全部批准进行中（spinner + disabled：桌面 stagePaths 往返期间无指示即「点了没反应」）
    @State private var isApprovingAll = false

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    CenterLoadingView(text: "正在读取变更…")
                        .accessibilityIdentifier("05-changes-loading")
                } else if viewModel.files.isEmpty, let loadError = viewModel.loadError {
                    EmptyStateView(
                        icon: "exclamationmark.triangle",
                        title: String(localized: "文件变更读取失败"),
                        detail: loadError,
                        cta: String(localized: "重试"),
                        ctaAction: { Task { await reload() } },
                        ctaIdentifier: "05-changes-retry")
                        .accessibilityIdentifier("05-changes-load-error")
                } else if viewModel.files.isEmpty {
                    EmptyStateView(
                        icon: "checkmark.seal",
                        title: emptyTitle,
                        detail: emptyDetail)
                        .accessibilityIdentifier("05-changes-empty")
                } else {
                    fileList
                }
            }
            .background(T.bg)
            .navigationTitle(String(localized: "更改"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(String(localized: "完成")) { dismiss() }
                        .accessibilityIdentifier("05-changes-close")
                }
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text(sessionTitle)
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        if viewModel.pendingCount > 0 {
                            Text(String(localized: "\(viewModel.pendingCount) 个文件待批准"))
                                .font(T.font(10.5))
                                .foregroundColor(T.text3)
                        }
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !viewModel.files.isEmpty && showsApproveAll {
                    approveAllBar
                }
            }
            .task {
                // sessionId 显式预设（sheet 由会话页打开，不再走「最近活跃会话」兜底）；
                // 默认源 = 本次会话（三 tab 后这里是会话语境，工作区两档由分段切换）
                viewModel.sessionId = sessionId
                await reload()
            }
        }
    }

    private var emptyTitle: String {
        viewModel.source == .session ? String(localized: "本会话暂无文件变更") : String(localized: "工作区是干净的")
    }

    private var emptyDetail: String {
        viewModel.source == .session
            ? String(localized: "该会话尚未产生文件写入，或变更已回退")
            : String(localized: "没有待审查的变更，或已全部处理完毕")
    }

    /// 「全部批准」可见性：会话源 + 未暂存源（已暂存集已入 index，无「批准」语义）
    private var showsApproveAll: Bool {
        viewModel.source != .workspaceStaged
    }

    private var fileList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: T.sp3) {
                if viewModel.isRemote {
                    ZSegmentedPicker(
                        items: DiffViewModel.DiffSource.allCases,
                        label: \.label,
                        selection: Binding(
                            get: { viewModel.source },
                            set: { newValue in
                                Task { await viewModel.select(newValue, store: store) }
                            }),
                        identifierPrefix: "05-changes-seg")
                }
                if let error = viewModel.actionError {
                    // 写面失败如实上屏（服务端 reason；点按清除，下次动作亦自动清除）
                    HStack(spacing: T.sp2) {
                        Image(systemName: "exclamationmark.circle")
                            .font(.system(size: 12))
                        Text(error)
                            .font(T.font(11.5, .semibold))
                            .lineLimit(3)
                        Spacer(minLength: 0)
                    }
                    .foregroundColor(T.red)
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 36)
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.redLine, lineWidth: 1))
                    .onTapGesture { viewModel.actionError = nil }
                    .accessibilityIdentifier("05-changes-action-error")
                }
                if viewModel.hasMoreChanges {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "ellipsis.circle")
                            .font(.system(size: 12))
                            .foregroundColor(T.text3)
                        Text(String(localized: "仅显示前 20 个文件，更多变更请在桌面端查看"))
                            .font(T.font(11.5))
                            .foregroundColor(T.text3)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 36)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                ForEach(viewModel.files) { file in
                    DiffFileCardView(file: file, viewModel: viewModel)
                }
                Color.clear.frame(height: 24)
            }
            .padding(.horizontal, T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    /// 底部「全部批准」：会话源 = 逐文件 stagePaths（作用域不出本会话）；
    /// 未暂存源 = store.approveAll（工作区未决集，与原全局动作栏同语义）
    private var approveAllBar: some View {
        VStack(spacing: T.sp2) {
            if let error = viewModel.actionError {
                Text(error)
                    .font(T.font(10.5, .semibold))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .onTapGesture { viewModel.actionError = nil }
            }
            Button {
                approveAll()
            } label: {
                HStack(spacing: T.sp1) {
                    if isApprovingAll { SpinnerView(size: 14) }
                    Text(isApprovingAll
                         ? String(localized: "批准中…")
                         : String(localized: "全部批准（\(viewModel.pendingCount)）"))
                        .font(T.font(15, .semibold))
                        .foregroundColor(isApprovingAll ? T.text2 : T.onAccent)
                }
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(isApprovingAll ? T.bgInput : T.seal)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .disabled(isApprovingAll || viewModel.pendingCount == 0)
            .accessibilityIdentifier("05-changes-approve-all")
        }
        .padding(.horizontal, T.sp4)
        .padding(.top, T.sp2)
        .padding(.bottom, 6)
        .background(
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(T.tabbarBg))
                .overlay(alignment: .top) { Divider().overlay(T.border) }
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private func approveAll() {
        guard !isApprovingAll else { return }
        let pendingPaths = viewModel.files
            .filter { !$0.isApproved && !$0.isRejected }
            .map { $0.stagePath ?? $0.path }
        guard !pendingPaths.isEmpty else { return }
        let source = viewModel.source
        isApprovingAll = true
        viewModel.actionError = nil
        Task {
            var firstError: String?
            if source == .session {
                // 会话作用域逐文件批准（不用全局 approveAll——它作用于整个工作区未暂存集）
                for path in pendingPaths where firstError == nil {
                    if let error = await store.setFileDecision(path: path, approved: true) {
                        firstError = error
                    }
                }
            } else {
                firstError = await store.approveAll()
            }
            isApprovingAll = false
            if let firstError {
                viewModel.actionError = firstError
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } else {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                await reload()
            }
        }
    }

    private func reload() async {
        viewModel.isLoading = true
        await viewModel.load(store: store, conversationStore: conversationStore)
        viewModel.isLoading = false
    }
}

import SwiftUI

@MainActor
@Observable
final class DiffViewModel {
    /// 数据源分段（named gap 补全：staged 维度 + 会话维度；演示态不显示分段选择器）
    enum DiffSource: String, CaseIterable, Identifiable {
        case workspaceUnstaged
        case workspaceStaged
        case session
        var id: String { rawValue }
        var label: String {
            switch self {
            case .workspaceUnstaged: return "未暂存"
            case .workspaceStaged: return "已暂存"
            case .session: return "本次会话"
            }
        }
    }

    var files: [DiffFile] = []
    var expanded: Set<String> = []
    var isLoading = true
    var source: DiffSource = .workspaceUnstaged
    var isRemote = false
    /// 本次会话 id（文件 Tab 无会话上下文，取最近活动会话承载）
    var sessionId: String?
    /// 写面进行中的文件 id（批准/拒绝按钮 Spinner + 双钮 disabled 防重复提交）
    var deciding: Set<String> = []
    /// 写面失败文案（批准/拒绝被桌面端拒绝或连接异常；下次动作或换源时清除）
    var actionError: String?
    /// 读面失败文案（getChanges/conversationFileChangesV4 瞬时失败；nil=最近一次拉取成功。
    /// 三态支撑：空列表 + 本信号在场 = 读取失败而非「工作区干净」，UI 须如实区分）
    var loadError: String?
    /// 会话存储弱引用：load 时注入；「本次会话」分段切换/刷新时惰性解析 sessionId
    /// （load 触发于 store 切换瞬间，会话列表可能尚未加载——一次性解析会永久落空）
    private var conversationStore: ConversationStore?
    /// 超出有界展示（20 条）的「更多」提示
    var hasMoreChanges = false

    private func fetch(store: FileStore, source: DiffSource) async -> [DiffFile] {
        switch source {
        case .workspaceUnstaged:
            let files = await store.diffFiles()
            hasMoreChanges = await store.hasMoreChanges(sourceId: "unstaged")
            return files
        case .workspaceStaged:
            guard store.isRemote else {
                hasMoreChanges = false
                return []
            }
            let files = await store.diffFiles(sourceId: "staged")
            hasMoreChanges = await store.hasMoreChanges(sourceId: "staged")
            return files
        case .session:
            guard store.isRemote else {
                hasMoreChanges = false
                return []
            }
            if sessionId == nil, let conversationStore {
                sessionId = await conversationStore.conversations().first?.id
            }
            guard let sessionId else {
                hasMoreChanges = false
                return []
            }
            hasMoreChanges = false
            return await store.sessionDiffFiles(sessionId: sessionId)
        }
    }

    /// 数据源分段 → RemoteFileStore 失败信号键（loadFailure(sourceId:)）
    private var failureKey: String {
        switch source {
        case .workspaceUnstaged: return "unstaged"
        case .workspaceStaged: return "staged"
        case .session: return "session"
        }
    }

    /// 拉取并应用。空结果 + Store 端失败信号 = 瞬时读取失败：保留旧列表（replacesExisting
    /// = false，如刷新/批准后 reload）或转错误态（= true，换源不留跨段旧数据），
    /// 并记录 loadError——列表任何时刻不得无提示变空（加载/错误/空三态可区分）。
    private func applyFetch(store: FileStore, replacesExisting: Bool) async {
        let fetched = await fetch(store: store, source: source)
        if fetched.isEmpty, let remote = store as? RemoteFileStore,
           let failure = await remote.loadFailure(sourceId: failureKey) {
            loadError = failure
            if replacesExisting {
                files = []
                expanded = []
            }
            return
        }
        loadError = nil
        files = fetched
    }

    func load(store: FileStore, conversationStore: ConversationStore?) async {
        isRemote = store.isRemote
        self.conversationStore = conversationStore
        if isRemote, sessionId == nil, let conversationStore {
            // 最近活动会话（conversations() 已按 updatedAt 排序）
            sessionId = await conversationStore.conversations().first?.id
        }
        await applyFetch(store: store, replacesExisting: false)
        isLoading = false
    }

    func select(_ newSource: DiffSource, store: FileStore) async {
        source = newSource
        expanded = []
        actionError = nil
        await applyFetch(store: store, replacesExisting: true)
    }

    func toggle(_ file: DiffFile) {
        if expanded.contains(file.id) {
            expanded.remove(file.id)
        } else {
            expanded.insert(file.id)
        }
    }

    /// 逐文件批准/拒绝：stagePath（web 同参原始路径）下发；失败文案回传 UI，
    /// 成功 reload 让卡片翻「已批准/已回退」（成功反馈即列表实态变化）
    func decide(_ file: DiffFile, approved: Bool, store: FileStore) async {
        guard !deciding.contains(file.id) else { return }
        deciding.insert(file.id)
        actionError = nil
        let error = await store.setFileDecision(path: file.stagePath ?? file.path, approved: approved)
        deciding.remove(file.id)
        if let error {
            actionError = error
            return
        }
        await reload(store: store)
    }

    // 全部批准的动作态在 RootView.DiffActionBar（store.approveAll + 自有 spinner/
    // 错误行）；成功后经 router.diffReloadToken 驱动本视图 .task(id:) reload。
    // 原 DiffViewModel.approveAll 已删（零调用死代码，rg 验证）。

    func reload(store: FileStore) async {
        await applyFetch(store: store, replacesExisting: false)
    }

    var addedTotal: Int { files.reduce(0) { $0 + $1.added } }
    var removedTotal: Int { files.reduce(0) { $0 + $1.removed } }
    var pendingCount: Int { files.filter { !$0.isApproved && !$0.isRejected }.count }
}

/// 屏 08 · Diff 审查（Tab「文件」根）：文件卡列表 + 增删行着色 + 底部动作栏
/// 连接态增数据源分段（未暂存/已暂存/本次会话）；演示态不渲染分段，行为完全不变
struct DiffReviewView: View {
    @Environment(AppRouter.self) private var router
    @Environment(AppSession.self) private var session
    @Environment(\.fileStore) private var store
    @Environment(\.conversationStore) private var conversationStore
    @State private var viewModel = DiffViewModel()

    var body: some View {
        Group {
            if viewModel.isLoading {
                CenterLoadingView(text: "正在读取变更…").accessibilityIdentifier("08-loading-center")
            } else if viewModel.files.isEmpty, let loadError = viewModel.loadError {
                // 读面失败且无旧数据可保：错误态（区别于「工作区是干净的」空态），可重试
                EmptyStateView(
                    icon: "exclamationmark.triangle",
                    title: String(localized: "文件变更读取失败"),
                    detail: loadError,
                    cta: String(localized: "重试"),
                    ctaAction: {
                        Task {
                            viewModel.isLoading = true
                            await viewModel.load(store: store, conversationStore: conversationStore)
                        }
                    },
                    ctaIdentifier: "08-act-retry-load")
                .accessibilityIdentifier("08-load-error-state")
            } else if viewModel.files.isEmpty {
                EmptyStateView(
                    icon: "checkmark.seal",
                    title: emptyTitle,
                    detail: emptyDetail,
                    cta: "浏览工作区文件",
                    ctaAction: { router.pushFileTree() },
                    ctaIdentifier: "08-act-browse-empty")
                .accessibilityIdentifier("08-empty")
            } else {
                fileList
            }
        }
        .background(T.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("文件").font(T.font(17, .bold)).foregroundColor(T.text)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    router.pushFileTree()
                } label: {
                    Image(systemName: "folder")
                        .font(.system(size: 16))
                        .foregroundColor(T.text)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("08-act-browse")
            }
        }
        // id 绑定 store 实例：连接成功后数据源 mock→远端 切换时重新拉取
        // （无 id 的 .task 仅在首次挂载（当时还是 mock）执行，连接态 diff 永不加载——门禁第 1 轮实证）
        .task(id: ObjectIdentifier(store)) {
            await viewModel.load(store: store, conversationStore: conversationStore)
            await loadGitSummary()
        }
        // 底部动作栏「全部批准」成功后的列表刷新（diffReloadToken 由 RootView bump；
        // 挂载首触 token=0 跳过——.task(id: store) 已负责首载）
        .task(id: router.diffReloadToken) {
            guard router.diffReloadToken > 0 else { return }
            await viewModel.reload(store: store)
        }
        // G-038：下拉刷新与会话/任务列表一致（桌面产生新改动后下拉可见）
        .refreshable {
            await viewModel.load(store: store, conversationStore: conversationStore)
            await loadGitSummary()
        }
    }

    // MARK: G-040 Git 只读信息（getRepositorySummary：真实分支/领先落后；失败保持中性标注）

    struct GitSummaryInfo: Equatable {
        var branchName: String?
        var ahead: Int
        var behind: Int
        var isDirty: Bool
    }
    @State private var gitSummary: GitSummaryInfo?

    private func loadGitSummary() async {
        guard case .connected = session.mode else { gitSummary = nil; return }
        var builder = JSONObjectBuilder()
        if let ws = session.connection.workspace {
            builder.set("workspacePath", ws.path)
        }
        guard let result = try? await session.connection.call(
            "git", "getRepositorySummary", .json(.object(builder.fields))),
            let dict = result.jsonValue?.objectValue else {
            gitSummary = nil
            return
        }
        gitSummary = GitSummaryInfo(
            branchName: dict["branchName"]?.stringValue,
            ahead: dict["ahead"]?.intValue ?? 0,
            behind: dict["behind"]?.intValue ?? 0,
            isDirty: dict["isDirty"]?.boolValue ?? false)
    }

    private var emptyTitle: String {
        viewModel.source == .session ? "本会话暂无文件变更" : "工作区是干净的"
    }

    private var emptyDetail: String {
        viewModel.source == .session
            ? "该会话尚未产生文件写入，或变更已回退"
            : "没有待审查的变更，或已全部处理完毕"
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
                        identifierPrefix: "08-seg-source")
                }
                branchRow
                statsRow
                if let loadError = viewModel.loadError {
                    // 读面失败但旧列表仍在展示（last-good 兜底）：横幅如实声明数据可能过期
                    HStack(spacing: T.sp2) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 12))
                        Text(loadError)
                            .font(T.font(11.5, .semibold))
                            .lineLimit(3)
                        Spacer(minLength: 0)
                        Button {
                            Task { await viewModel.reload(store: store) }
                        } label: {
                            Text(String(localized: "重试"))
                                .font(T.font(11.5, .semibold))
                                .foregroundColor(T.accentText)
                                .padding(.horizontal, T.sp2)
                                .frame(minHeight: 32)
                                .contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("08-act-retry-load-banner")
                    }
                    .foregroundColor(T.orangeBright)
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 36)
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.orangeBright.opacity(0.4), lineWidth: 1))
                    .accessibilityIdentifier("08-load-error-banner")
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
                    .accessibilityIdentifier("08-action-error")
                }
                if viewModel.hasMoreChanges {
                    moreNotice
                }
                ForEach(viewModel.files) { file in
                    DiffFileCardView(file: file, viewModel: viewModel)
                }
                Color.clear.frame(height: 150)
            }
            .padding(.horizontal, T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    /// 有界展示提示：getChanges prefix(20) 之外的变更请在桌面端查看
    private var moreNotice: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "ellipsis.circle")
                .font(.system(size: 12))
                .foregroundColor(T.text3)
            Text("仅显示前 20 个文件，更多变更请在桌面端查看")
                .font(T.font(11.5))
                .foregroundColor(T.text3)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 36)
        .background(T.bgInput)
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
        .accessibilityIdentifier("08-more-notice")
    }

    /// 分支胶囊（G-030/G-040）：连接态接 git.getRepositorySummary 真实分支与 ahead/behind
    /// （只读零风险，git 频道读放行）；演示态/无数据呈「桌面端管理」中性标注，不再硬编码假分支名
    private var branchRow: some View {
        HStack(spacing: T.sp2) {
            HStack(spacing: 5) {
                Image(systemName: "arrow.triangle.branch")
                    .font(.system(size: 11))
                Text(gitBranchText)
                    .font(T.mono(11.5, .medium))
                    .lineLimit(1)
                if let ahead = gitSummary?.ahead, let behind = gitSummary?.behind, ahead > 0 || behind > 0 {
                    Text("↑\(ahead) ↓\(behind)")
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                }
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 32)
            .background(T.bgInput)
            .clipShape(Capsule())
            .accessibilityIdentifier("08-branch-switcher")
            // G-023：提交图谱 / 分支对比只读页入口
            Button {
                router.pushFileRoute(.commitGraph)
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                        .font(.system(size: 11))
                    Text(String(localized: "提交图谱"))
                        .font(T.font(11.5, .medium))
                }
                .foregroundColor(T.text2)
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 32)
                .background(T.bgInput)
                .clipShape(Capsule())
            }
            .accessibilityIdentifier("08-act-commit-graph")
            Spacer()
        }
        .padding(.top, T.sp1)
    }

    private var gitBranchText: String {
        if let branch = gitSummary?.branchName, !branch.isEmpty {
            return branch
        }
        return String(localized: "分支由桌面端管理")
    }

    private var statsRow: some View {
        HStack(spacing: T.sp2) {
            Text("+\(viewModel.addedTotal)")
                .font(T.mono(12, .semibold))
                .foregroundColor(T.add)
            Text("-\(viewModel.removedTotal)")
                .font(T.mono(12, .semibold))
                .foregroundColor(T.del)
            Text("\(viewModel.files.count) 个文件")
                .font(T.font(11.5))
                .foregroundColor(T.text3)
            if viewModel.pendingCount > 0 {
                TabBadge(count: viewModel.pendingCount, color: T.orangeBright)
            }
            Spacer()
        }
    }
}

// MARK: - 文件卡（展开态：44px 头 + unified diff + 44px 批准/拒绝）

struct DiffFileCardView: View {
    @Environment(\.fileStore) private var store
    let file: DiffFile
    @Bindable var viewModel: DiffViewModel

    private var isExpanded: Bool { viewModel.expanded.contains(file.id) }

    var body: some View {
        VStack(spacing: 0) {
            head
            if isExpanded {
                DiffLineListView(lines: file.lines, screenPrefix: "08")
                    .accessibilityIdentifier("08-filecard-body")
                Divider().overlay(T.border)
                decisionRow
            }
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay {
            RoundedRectangle(cornerRadius: T.rM).stroke(
                file.isApproved ? T.accent.opacity(0.4) : file.isRejected ? T.redLine : T.border,
                lineWidth: 1)
        }
    }

    /// 文件头 44px：图标 + mono 路径 + ±统计 + ⋯ 菜单 + 折叠箭头
    private var head: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "doc.text")
                .font(.system(size: 12))
                .foregroundColor(T.codeLab)
            Text(file.path)
                .font(T.mono(11.5))
                .foregroundColor(T.text)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("+\(file.added)")
                .font(T.mono(10.5))
                .foregroundColor(T.add)
            Text("-\(file.removed)")
                .font(T.mono(10.5))
                .foregroundColor(T.del)
            Menu {
                Button(role: .destructive) {
                    Task { await viewModel.decide(file, approved: false, store: store) }
                } label: {
                    Label("回退此文件", systemImage: "arrow.uturn.backward")
                }
                Button {
                    UIPasteboard.general.string = file.path
                } label: {
                    Label("复制路径", systemImage: "doc.on.doc")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(T.text2)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("08-filecard-menu-\(file.id)")

            Button {
                withAnimation(.easeOut(duration: 0.2)) { viewModel.toggle(file) }
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(T.text3)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
                    .rotationEffect(.degrees(isExpanded ? 0 : -90))
            }
            .accessibilityIdentifier("08-filecard-toggle-\(file.id)")
        }
        .padding(.leading, T.sp3)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeOut(duration: 0.2)) { viewModel.toggle(file) }
        }
    }

    @ViewBuilder
    private var decisionRow: some View {
        if file.isApproved {
            HStack(spacing: T.sp2) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(T.accentText)
                Text("已批准")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.accentText)
                Spacer()
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 44)
        } else if file.isRejected {
            HStack(spacing: T.sp2) {
                Image(systemName: "arrow.uturn.backward.circle.fill")
                    .foregroundColor(T.red)
                Text("已回退")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.red)
                Spacer()
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 44)
        } else {
            HStack(spacing: 10) {
                Button {
                    Task { await viewModel.decide(file, approved: false, store: store) }
                } label: {
                    HStack(spacing: T.sp1) {
                        // 进行中指示（Spinner+disabled）：桌面 stage/unstage 往返秒级，
                        // 无反馈即「点了没反应」（2026-10-06 用户回归反馈）
                        if viewModel.deciding.contains(file.id) { SpinnerView(size: 12) }
                        Text(viewModel.deciding.contains(file.id) ? "处理中…" : "拒绝")
                            .font(T.font(13, .semibold))
                    }
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.redLine, lineWidth: 1))
                }
                .disabled(!viewModel.deciding.isEmpty)
                .accessibilityIdentifier("08-filecard-reject")
                Button {
                    Task { await viewModel.decide(file, approved: true, store: store) }
                } label: {
                    HStack(spacing: T.sp1) {
                        if viewModel.deciding.contains(file.id) { SpinnerView(size: 12) }
                        Text(viewModel.deciding.contains(file.id) ? "处理中…" : "批准")
                            .font(T.font(13, .semibold))
                    }
                    .foregroundColor(T.onAccent)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .disabled(!viewModel.deciding.isEmpty)
                .accessibilityIdentifier("08-filecard-approve")
            }
            .padding(T.sp3)
        }
    }
}

// MARK: - diff 行列表（spec 5.4：正文 12px、行号 10.5px/32px、横滚禁折行）

struct IndexedDiffLine: Identifiable {
    let line: DiffLine
    let indexInKind: Int
    var id: String { line.id }
}

struct DiffLineListView: View {
    let lines: [DiffLine]
    var screenPrefix: String = "08"

    private var indexed: [IndexedDiffLine] {
        var counters: [DiffLineKind: Int] = [:]
        return lines.map { line in
            let next = counters[line.kind, default: 0] + 1
            counters[line.kind] = next
            return IndexedDiffLine(line: line, indexInKind: next)
        }
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(indexed) { item in
                    DiffLineRowView(line: item.line, screenPrefix: screenPrefix, indexInKind: item.indexInKind)
                }
            }
        }
        .background(T.bgCode)
    }
}

/// 单条 diff 行：hunk / add / del / ctx 四类着色
struct DiffLineRowView: View {
    let line: DiffLine
    var screenPrefix: String
    var indexInKind: Int

    private var rowColors: (fg: Color, bg: Color) {
        switch line.kind {
        case .hunk: return (T.codeLab, T.codeLabDim)
        case .add: return (T.add, T.addBg)
        case .del: return (T.del, T.delBg)
        case .ctx: return (T.text2, Color.clear)
        }
    }

    var body: some View {
        let colors = rowColors
        HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
            Text(line.oldNumber.map(String.init) ?? "")
                .font(T.mono(10.5))
                .foregroundColor(T.text3)
                .frame(width: 32, alignment: .trailing)
            Text(line.newNumber.map(String.init) ?? "")
                .font(T.mono(10.5))
                .foregroundColor(T.text3)
                .frame(width: 32, alignment: .trailing)
            Text(line.text)
                .font(T.mono(12))
                .foregroundColor(colors.fg)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, T.sp2)
        .frame(height: line.kind == .hunk ? 24 : 21)
        .background(colors.bg)
        .accessibilityIdentifier("\(screenPrefix)-diffrow-\(line.kind.rawValue)-\(indexInKind)")
    }
}

// MARK: - G-023 提交图谱 / 分支对比只读页（git.getCommitGraph + getBranchComparison；
// switchBranch 等仓库写维持 ReadOnlyGate 拦截；回执宽容解析，失败呈中性空态）

struct CommitGraphPage: View {
    struct CommitRow: Identifiable {
        let id: String
        let subject: String
        let author: String
        let when: String
    }

    @Environment(AppSession.self) private var session
    @State private var commits: [CommitRow] = []
    @State private var comparisonText: String?
    @State private var isLoading = true
    @State private var failed = false
    /// getCommitGraph hasMore：超出 maxCount(50) 的更早提交在桌面端查看
    @State private var hasMore = false

    var body: some View {
        Group {
            if isLoading {
                CenterLoadingView(text: "正在读取提交图谱…")
            } else if failed || commits.isEmpty {
                EmptyStateView(
                    icon: "point.topleft.down.curvedto.point.bottomright.up",
                    title: String(localized: "暂无提交图谱"),
                    detail: String(localized: "连接桌面端后同步最近提交链（只读）"))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: T.sp2) {
                        if let comparisonText {
                            HStack(spacing: T.sp2) {
                                Image(systemName: "arrow.triangle.branch")
                                    .font(.system(size: 12))
                                    .foregroundColor(T.blue)
                                Text(comparisonText)
                                    .font(T.font(12, .medium))
                                    .foregroundColor(T.text2)
                                Spacer(minLength: 0)
                            }
                            .card(padding: T.sp2)
                            .accessibilityIdentifier("08-branch-comparison")
                        }
                        ForEach(commits) { commit in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: T.sp2) {
                                    Text(commit.subject)
                                        .font(T.font(13, .medium))
                                        .foregroundColor(T.text)
                                        .lineLimit(1)
                                    Spacer(minLength: 0)
                                    Text(commit.when)
                                        .font(T.font(10.5))
                                        .foregroundColor(T.text3)
                                }
                                HStack(spacing: T.sp2) {
                                    Text(commit.id)
                                        .font(T.mono(10))
                                        .foregroundColor(T.codeLab)
                                    Text(commit.author)
                                        .font(T.font(10.5))
                                        .foregroundColor(T.text3)
                                        .lineLimit(1)
                                    Spacer(minLength: 0)
                                }
                            }
                            .card(padding: T.sp2)
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("08-commit-\(commit.id)")
                        }
                        if hasMore {
                            Text(String(localized: "仅显示最近 50 条提交，更早的请在桌面端查看"))
                                .font(T.font(11))
                                .foregroundColor(T.text3)
                                .frame(maxWidth: .infinity, alignment: .center)
                                .padding(.vertical, T.sp2)
                                .accessibilityIdentifier("08-commit-more-notice")
                        }
                    }
                    .padding(T.sp4)
                }
                .background(T.bg)
                .refreshable { await load() }
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "提交图谱"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        isLoading = true
        failed = false
        let connection = session.connection
        guard connection.isActive else {
            failed = true
            isLoading = false
            return
        }
        var builder = JSONObjectBuilder()
        if let workspacePath = connection.workspace?.path {
            builder.set("workspacePath", workspacePath)
        }
        // 提交链（getCommitGraph）：web 恒带 {maxCount:50, skip:0}（bundle 取证 hW=50，
        // 加载更多按 skip=已加载数翻页——移动端单页 50 条 + hasMore 提示）；
        // 条目宽容取 commits[]/graph[]/[]，时间键 authoredAtMs（web 读法，兼容保留
        // 旧 timestamp|authorDate 键）
        var graphBuilder = builder
        graphBuilder.set("maxCount", 50)
        graphBuilder.set("skip", 0)
        if let result = try? await connection.call(
            "git", "getCommitGraph", .json(.object(graphBuilder.fields))) {
            let items = result.jsonValue?["commits"]?.arrayValue
                ?? result.jsonValue?["graph"]?.arrayValue
                ?? result.jsonValue?.arrayValue
                ?? []
            hasMore = result.jsonValue?["hasMore"]?.boolValue ?? false
            commits = items.compactMap { item in
                guard let d = item.objectValue,
                      let hash = d["hash"]?.stringValue ?? d["id"]?.stringValue
                          ?? d["oid"]?.stringValue else { return nil }
                let ms = d["authoredAtMs"]?.intValue ?? d["timestamp"]?.intValue
                    ?? d["authorDate"]?.intValue
                let when = ms.map {
                    Self.relative.localizedString(
                        for: Date(timeIntervalSince1970: Double($0) / 1000), relativeTo: Date())
                } ?? ""
                return CommitRow(
                    id: String(hash.prefix(8)),
                    subject: d["subject"]?.stringValue
                        ?? d["message"]?.stringValue
                        ?? String(localized: "（无提交说明）"),
                    author: d["author"]?.stringValue
                        ?? d["authorName"]?.stringValue
                        ?? "",
                    when: when)
            }
        }
        // 分支对比（getBranchComparison）：web mock 形态 {baseRef, headRef,
        // comparisonLabel, changes}（bundle 取证；changes 为结构化节，移动端不展开）。
        // 宽容保留 ahead/behind/base/summary 旧键
        if let result = try? await connection.call(
            "git", "getBranchComparison", .json(.object(builder.fields))) {
            if let dict = result.jsonValue?.objectValue {
                let ahead = dict["ahead"]?.intValue ?? dict["aheadCount"]?.intValue
                let behind = dict["behind"]?.intValue ?? dict["behindCount"]?.intValue
                if ahead != nil || behind != nil {
                    let base = dict["baseRef"]?.stringValue ?? dict["base"]?.stringValue
                        ?? dict["baseBranch"]?.stringValue ?? ""
                    comparisonText = String(
                        format: String(localized: "对比 %@ · 领先 %lld / 落后 %lld"),
                        base, ahead ?? 0, behind ?? 0)
                } else if let label = dict["comparisonLabel"]?.stringValue, !label.isEmpty {
                    comparisonText = label
                } else if let summary = dict["summary"]?.stringValue {
                    comparisonText = summary
                }
            }
        }
        failed = commits.isEmpty && comparisonText == nil
        isLoading = false
    }

    static let relative: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()
}

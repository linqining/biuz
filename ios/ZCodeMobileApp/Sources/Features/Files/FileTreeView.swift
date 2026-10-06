import SwiftUI

/// 工作区文件树（Push）：文件夹展开/收起、文件行 mono、点击进预览
/// 连接态搜索走 file.searchWorkspaceFiles（服务端有界候选）；演示态保持本地过滤不变
struct FileTreeView: View {
    @Environment(\.fileStore) private var store
    @Environment(AppSession.self) private var session
    @State private var tree: [FileNode] = []
    @State private var expanded: Set<String> = ["src", "src/core"]
    @State private var query = ""
    @State private var isLoading = true
    @State private var searchResults: [FileNode]?
    @State private var searchTask: Task<Void, Never>?
    // P3-8：一站式提交（git 写族 UI 化，连接态 only）；P3-11 检查点入口已隐藏（H3）
    @State private var stagedCount: Int?
    @State private var showCommitSheet = false
    /// 提交成功待对账标记（onCommitted 置位、sheet onDismiss 消费）：区分「提交后
    /// 对账重拉」与普通开关 sheet 的计数刷新，差异提示只在提交路径挂
    @State private var pendingCommitReconcile = false
    // HIDDEN(对齐修复 H3)：git-checkpoint 三方法 web bundle 0 命中且协议文档零实证条目
    // （审查报告 §五），真机取证立条后随入口一并恢复
    // @State private var showCheckpointSheet = false
    @State private var notice: String?
    @State private var noticeIsError = false
    /// 读面三态（2026-10-06 用户回归「点击后列表无提示清空」）：文件树拉取失败文案
    /// （nil=最近一次成功；存 old 树时仅挂横幅，首载失败呈错误空态），空态单独可辨
    @State private var loadError: String?

    /// 拉取文件树并对账失败信号。Store 端 last-good 兜底：重拉失败返回旧树（不清空），
    /// 视图旧行保留（滚动位置不动），失败横幅如实声明「暂显示上次结果」。
    private func loadTree() async {
        let fresh = await store.fileTree()
        tree = fresh
        if let remote = store as? RemoteFileStore {
            loadError = await remote.treeLoadFailure()
        } else {
            loadError = nil
        }
    }

    /// G-031：连接态组头显示当前连接 workspace 真实路径；演示态保留演示路径
    private var headerPath: String {
        if case .connected = session.mode, let ws = session.connection.workspace {
            return "工作区 \(ws.path)"
        }
        return "工作区 ~/work/zcode"
    }

    /// 命中行：搜索回执优先（连接态），否则树内过滤（演示态 + 未命中时回退）
    private var visibleRows: [FileNode] {
        if let searchResults {
            return searchResults
        }
        var rows: [FileNode] = []
        func walk(_ nodes: [FileNode]) {
            for node in nodes {
                if matches(node) {
                    rows.append(node)
                }
                if node.isDirectory, expanded.contains(node.id) {
                    walk(node.children ?? [])
                }
            }
        }
        walk(tree)
        return rows
    }

    private func matches(_ node: FileNode) -> Bool {
        query.isEmpty || node.name.localizedCaseInsensitiveContains(query)
    }

    var body: some View {
        Group {
            if isLoading {
                CenterLoadingView(text: "正在读取工作区…").accessibilityIdentifier("09-loading-center")
            } else {
                List {
                    Section {
                        SearchField(text: $query, placeholder: String(localized: "搜索文件"), identifier: "09-search")
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: T.sp4, bottom: 4, trailing: T.sp4))
                            .listRowSeparator(.hidden)
                    }
                    if store.isRemote {
                        commitSection
                    }
                    if let loadError {
                        // 读面失败横幅（旧树仍展示时声明数据可能过期；可重试）
                        HStack(spacing: T.sp2) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 12))
                            Text(loadError)
                                .font(T.font(11.5, .semibold))
                                .lineLimit(2)
                            Spacer(minLength: 0)
                            Button {
                                Task { await loadTree() }
                            } label: {
                                Text(String(localized: "重试"))
                                    .font(T.font(11.5, .semibold))
                                    .foregroundColor(T.accentText)
                                    .padding(.horizontal, T.sp2)
                                    .frame(minHeight: 32)
                                    .contentShape(Rectangle())
                            }
                            .accessibilityIdentifier("09-act-retry-tree")
                        }
                        .foregroundColor(T.orangeBright)
                        .listRowBackground(T.bgCard)
                        .listRowSeparator(.hidden)
                        .accessibilityIdentifier("09-load-error-banner")
                    }
                    if tree.isEmpty, loadError == nil {
                        // 真空态（工作区无文件/目录为空）：与加载态、失败态三者可区分
                        HStack(spacing: T.sp2) {
                            Image(systemName: "folder")
                                .font(.system(size: 13))
                            Text("工作区没有可显示的文件")
                                .font(T.font(12.5))
                            Spacer(minLength: 0)
                        }
                        .foregroundColor(T.text3)
                        .listRowBackground(T.bgCard)
                        .listRowSeparator(.hidden)
                        .accessibilityIdentifier("09-empty-tree")
                    }
                    Section {
                        ForEach(visibleRows) { node in
                            row(node)
                        }
                    } header: {
                        // G-031：连接态显示真实 workspace 路径，演示态保留演示路径
                        Text(headerPath)
                            .font(T.font(11, .semibold))
                            .foregroundColor(T.text3)
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
        }
        .background(T.bg)
        .navigationTitle("工作区文件")
        .navigationBarTitleDisplayMode(.inline)
        // P3-8：一站式提交 sheet。提交成功走「乐观同帧 + 后台对账」：onCommitted
        // 与 loading 停止同一渲染帧清零 staged 计数（文件消失不再滞后于 loading）；
        // sheet 关闭后重拉桌面实态对账（差异如实提示，以桌面为准）
        .sheet(isPresented: $showCommitSheet, onDismiss: {
            if pendingCommitReconcile {
                pendingCommitReconcile = false
                Task { await reconcileStagedAfterCommit() }
            } else {
                Task { await refreshStagedCount() }
            }
        }) {
            CommitSheet(onCommitted: { hash in
                // 提交成功（回执 hash 已验证）：同帧乐观更新——成功 notice + staged
                // 计数清零（桌面实态由 sheet 关闭后的对账重拉校正）
                noticeIsError = false
                let shortHash = hash ?? ""
                notice = shortHash.isEmpty ? String(localized: "已提交") : String(localized: "已提交 · \(shortHash)")
                stagedCount = 0
                pendingCommitReconcile = true
            })
            .accessibilityIdentifier("09-commit-sheet")
        }
        // HIDDEN(对齐修复 H3)：检查点 sheet 挂载随入口一起注释（CheckpointSheet 本体
        // 与 RemoteFileStore 发送方法保留编译；恢复条件：git-checkpoint 真机取证 + 协议文档立条）
        // .sheet(isPresented: $showCheckpointSheet, onDismiss: {
        //     Task { await refreshStagedCount() }
        // }) {
        //     CheckpointSheet(onRestored: {
        //         noticeIsError = false
        //         notice = String(localized: "已恢复")
        //         Task { tree = await store.fileTree() } // 恢复后工作区文件已回退，重拉树
        //     })
        //     .accessibilityIdentifier("09-checkpoint-sheet")
        // }
        // id 绑定 store 实例（DiffReviewView/TaskBoardView 同款先例）：连接成功或 P3-10
        // 工作区切换产生新 Store 时重拉文件树 + 重挂观察流（无 id 时已挂载页面永不刷新）
        .task(id: ObjectIdentifier(store)) {
            stagedCount = nil
            // 首load才给整页 loading；Store 换绑保留旧树（返回本页时滚动位置不跳）
            isLoading = tree.isEmpty
            await loadTree()
            isLoading = false
            await refreshStagedCount()
        }
        .task(id: ObjectIdentifier(store)) {
            // 文件树活性（file-watcher.onDynamicChange）：变更即重拉；演示态流立即结束。
            // 重拉失败由 loadTree 保旧树 + 挂横幅（原实现失败即空列表无提示）
            for await _ in store.observeFileTreeChanges() {
                await loadTree()
            }
        }
        .onChange(of: query) { _, newValue in
            // 连接态防抖走服务端搜索；演示态 searchResults 恒 nil 保持本地过滤
            searchTask?.cancel()
            guard store.isRemote else {
                searchResults = nil
                return
            }
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else {
                searchResults = nil
                return
            }
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 250_000_000)
                guard !Task.isCancelled else { return }
                let results = await store.searchFiles(trimmed, limit: 50)
                guard !Task.isCancelled else { return }
                searchResults = results
                // 搜索命中文件自动展开其父目录，保证返回树视图时上下文完整
                for result in results where !result.isDirectory {
                    expanded.insert((result.path as NSString).deletingLastPathComponent)
                }
            }
        }
    }

    // MARK: 一站式提交入口（P3-8：git 写族 UI 化，桌面代执行；检查点入口 HIDDEN(H3)）
    // staged 计数独立取数（design §8.1：diffFiles(sourceId:"staged") 仅计数不装树）；
    // stagedCount==nil/0 时提交入口灰置（design §8.4 状态矩阵）。

    private func refreshStagedCount() async {
        guard store.isRemote else {
            stagedCount = nil
            return
        }
        let staged = await store.diffFiles(sourceId: "staged")
        stagedCount = staged.count
    }

    /// 提交后对账（2026-10-06 假提交回归）：重拉桌面 staged 实态（git.getChanges）。
    /// 桌面实态与乐观态（提交即清零）不符时以桌面为准并如实提示差异；对账拉取
    /// 本身失败也如实声明（乐观态暂留，重进提交页会再次拉桌面实态）——不静默。
    private func reconcileStagedAfterCommit() async {
        await refreshStagedCount()
        if let remote = store as? RemoteFileStore,
           let failure = await remote.loadFailure(sourceId: "staged") {
            noticeIsError = true
            notice = String(localized: "已提交 · 提交后对账失败 · \(failure)")
            return
        }
        if let count = stagedCount, count > 0 {
            noticeIsError = false
            notice = String(localized: "已提交 · 桌面端仍有 \(count) 个已暂存文件 · 已按桌面刷新")
        }
    }

    private var commitSection: some View {
        Section {
            if let notice {
                HStack(spacing: T.sp2) {
                    Image(systemName: noticeIsError ? "exclamationmark.circle" : "checkmark.circle")
                        .font(.system(size: 12))
                    Text(notice)
                        .font(T.font(11.5, .semibold))
                        .lineLimit(2)
                    Spacer(minLength: 0)
                }
                .foregroundColor(noticeIsError ? T.red : T.accentText)
                .listRowBackground(T.bgCard)
                .listRowSeparator(.hidden)
                .accessibilityIdentifier("09-notice")
            }
            Button {
                self.notice = nil
                showCommitSheet = true
            } label: {
                HStack(spacing: T.sp2) {
                    Image(systemName: "arrow.up.doc")
                        .font(.system(size: 13))
                        .foregroundColor(stagedCount == nil || stagedCount == 0 ? T.text3 : T.accentText)
                    Text("提交")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text)
                    Spacer()
                    if let count = stagedCount, count > 0 {
                        TabBadge(count: count, color: T.accent)
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(T.text3)
                }
                .padding(.vertical, 6)
            }
            .disabled(stagedCount == nil || stagedCount == 0)
            .listRowBackground(T.bgCard)
            .listRowSeparator(.hidden)
            .accessibilityIdentifier("09-row-commit-entry")
            // HIDDEN(对齐修复 H3)：检查点入口行整块注释（git-checkpoint 三方法 web bundle
            // 0 命中 + 协议文档零条目，审查报告 §五「全审计中最不可靠一环」——形状全靠猜，
            // 真机取证立条后恢复；CheckpointSheet :932 起保留编译）
            // Button {
            //     notice = nil
            //     showCheckpointSheet = true
            // } label: {
            //     HStack(spacing: T.sp2) {
            //         Image(systemName: "clock.arrow.circlepath")
            //             .font(.system(size: 13))
            //             .foregroundColor(T.codeLab)
            //         Text("检查点")
            //             .font(T.font(14, .medium))
            //             .foregroundColor(T.text)
            //         Spacer()
            //         Image(systemName: "chevron.right")
            //             .font(.system(size: 11, weight: .semibold))
            //             .foregroundColor(T.text3)
            //     }
            //     .padding(.vertical, 6)
            // }
            // .listRowBackground(T.bgCard)
            // .listRowSeparator(.hidden)
            // .accessibilityIdentifier("09-row-checkpoint-entry")
        } header: {
            // HIDDEN(H3)：检查点入口隐藏后组头不再提及（原「提交与检查点」）
            Text("提交")
                .font(T.font(11, .semibold))
                .foregroundColor(T.text3)
        }
    }

    private func row(_ node: FileNode) -> some View {
        Group {
            if node.isDirectory {
                Button {
                    withAnimation(.easeOut(duration: 0.18)) {
                        if expanded.contains(node.id) {
                            expanded.remove(node.id)
                        } else {
                            expanded.insert(node.id)
                        }
                    }
                } label: {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "folder.fill")
                            .font(.system(size: 13))
                            .foregroundColor(T.codeLab)
                        Text(node.name)
                            .font(T.font(14, .medium))
                            .foregroundColor(T.text)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(T.text3)
                            .rotationEffect(.degrees(expanded.contains(node.id) ? 90 : 0))
                    }
                    .padding(.vertical, 6)
                }
                .accessibilityIdentifier("09-row-dir-\(node.id)")
            } else {
                NavigationLink(value: FileRoute.preview(node)) {
                    HStack(spacing: T.sp2) {
                        Image(systemName: iconName(for: node.name))
                            .font(.system(size: 12))
                            .foregroundColor(T.text3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(node.name)
                                .font(T.mono(13))
                                .foregroundColor(T.text)
                            // G-039：搜索命中态显示相对路径，补回层级上下文
                            if searchResults != nil, node.path.count > node.name.count {
                                Text(node.path)
                                    .font(T.mono(10))
                                    .foregroundColor(T.text3)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                        }
                        Spacer()
                        if let size = node.size {
                            Text(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file))
                                .font(T.mono(10.5))
                                .foregroundColor(T.text3)
                        }
                    }
                    .padding(.vertical, 6)
                }
                .accessibilityIdentifier("09-row-file-\(node.id)")
            }
        }
        .listRowBackground(T.bgCard)
        .listRowSeparator(.hidden)
    }

    private func iconName(for name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "swift": return "swift"
        case "md": return "doc.richtext"
        case "json", "yml": return "curlybraces.square"
        default: return "doc"
        }
    }
}

/// 屏 09 · 产物预览（Push L3）：文件名 + 大小 + 分享、分段 预览/源码
/// P2 增量：图片/PDF 按扩展名分发只读二进制预览（file.readBinaryPreview）；
/// 失败/演示态降级为说明文案（不虚构预览）。
struct FilePreviewView: View {
    @Environment(\.fileStore) private var store
    let node: FileNode

    enum Segment: String, CaseIterable {
        case preview, source
        var label: String { self == .preview ? "预览" : "源码" }
    }

    /// 二进制预览类别（按扩展名分发；nil = 文本文件走原有两段视图）
    enum BinaryKind: String {
        case image, pdf
        static func of(_ ext: String) -> BinaryKind? {
            if ["png", "jpg", "jpeg", "gif", "webp", "heic", "bmp"].contains(ext) { return .image }
            if ext == "pdf" { return .pdf }
            return nil
        }
    }

    @State private var segment: Segment = .preview
    @State private var content = ""
    @State private var isLoading = true
    /// 有界读状态（readTextFile offset/length）：已读字节 / 全量字节 / 是否截断
    @State private var loadedBytes = 0
    @State private var totalBytes = 0
    @State private var isTruncated = false
    @State private var isLoadingMore = false
    /// 二进制预览状态（readBinaryPreview 首块）
    @State private var binaryData: Data?
    @State private var binaryFailed = false
    /// 读面三态（2026-10-06 用户回归「点击后结果清空」）：文本读失败不再渲染成空白
    /// 内容区——空内容 + Store 失败信号 = 错误块 + 重试（区别于真空文件）
    @State private var readFailed = false
    @State private var readFailureMessage: String?
    /// G-027 分享态：内容临时文件 URL / 不可分享提示
    @State private var shareURL: URL?
    @State private var shareNotice: String?

    /// G-027：分享文件本体——文本内容写临时文件（文件名取 node.name，路径仅作 fallback
    /// 语义）；已截断（超分页读上限）不分享半份内容，给明确提示
    private func shareFile() async {
        if binaryKind != nil {
            // 二进制（图片/PDF）已下载的首块预览不可代表本体：如实提示
            shareNotice = String(localized: "二进制文件暂不支持移动端分享，请在桌面端查看或分享")
            return
        }
        if isTruncated {
            shareNotice = String(localized: "文件超出移动端可读上限（已在桌面端截断加载），请在桌面端分享完整文件")
            return
        }
        let body = content.isEmpty ? await store.content(of: node.path) : content
        guard !body.isEmpty else {
            shareNotice = String(localized: "文件内容为空或读取失败，无法分享")
            return
        }
        let sanitized = node.name.replacingOccurrences(of: "/", with: "_")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("biuz-share-\(UUID().uuidString.prefix(6))")
            .appendingPathComponent(sanitized)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        do {
            try body.write(to: url, atomically: true, encoding: .utf8)
            shareURL = url
        } catch {
            shareNotice = String(localized: "分享失败 · \(error.localizedDescription)")
        }
    }

    private var markdownBlocks: [TinyMarkdown.Block] {
        TinyMarkdown.parse(content)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                if binaryKind == nil {
                    ZSegmentedPicker(
                        items: Segment.allCases,
                        label: \.label,
                        selection: $segment,
                        identifierPrefix: "09-seg")
                }
                if isLoading {
                    CenterLoadingView(text: "正在载入文件…").accessibilityIdentifier("09-loading-file")
                } else if let kind = binaryKind {
                    binaryPreviewBody(kind)
                } else if readFailed {
                    readFailedBlock
                } else {
                    switch segment {
                    case .preview:
                        if isMarkdown {
                            VStack(alignment: .leading, spacing: T.sp2) {
                                ForEach(markdownBlocks) { block in
                                    MarkdownBlockView(block: block)
                                }
                            }
                            .card()
                        } else {
                            CodeBlockView(language: extensionName, code: content)
                        }
                    case .source:
                        sourceView
                    }
                    if isTruncated {
                        loadMoreButton
                    }
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
        .background(T.bg)
        .navigationTitle(node.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(node.name).font(T.font(13, .semibold)).foregroundColor(T.text).lineLimit(1)
                    Text(sizeText)
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                // G-027：分享文件内容（文本经已读内容写临时文件；路径仅作 fallback 文件名）。
                // 截断（超分页读上限）时给明确不可分享提示，不分享半份内容。
                Button {
                    Task { await shareFile() }
                } label: {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 15))
                        .foregroundColor(T.text)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("09-act-share")
            }
        }
        .sheet(item: Binding(
            get: { shareURL.map(ShareFilePayload.init) },
            set: { if $0 == nil { shareURL = nil } })) { payload in
            ActivityView(payload: payload)
        }
        .alert("无法分享", isPresented: Binding(
            get: { shareNotice != nil },
            set: { if !$0 { shareNotice = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(shareNotice ?? "")
        }
        .task {
            await loadContent()
        }
    }

    /// 首屏装载（文本/二进制分派）。文本空内容 + Store 端 readTextFile 失败信号 =
    /// 读取失败态（readFailed），不再渲染空白内容区冒充空文件。
    private func loadContent() async {
        // 二进制类别（图片/PDF）走 readBinaryPreview 单独通道
        if binaryKind != nil {
            binaryData = await store.binaryPreview(of: node.path, maxBytes: 2_000_000)
            binaryFailed = binaryData == nil
            isLoading = false
            return
        }
        // 有界读首屏（256KiB；readTextFile offset/length + totalBytes 截断判定）。
        // mock 默认实现整读返回不截断，演示行为不变。
        let page = await store.contentPage(
            of: node.path, offset: 0, length: Int.max)
        content = page.content
        totalBytes = page.totalBytes
        loadedBytes = page.content.utf8.count
        isTruncated = page.isTruncated
        readFailed = false
        readFailureMessage = nil
        if page.content.isEmpty, let remote = store as? RemoteFileStore,
           let failure = await remote.textReadFailureMessage() {
            readFailed = true
            readFailureMessage = failure
        }
        isLoading = false
    }

    /// 文本读取失败块（三态之错误态：图标 + 服务端 reason + 重试）
    private var readFailedBlock: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 18))
                    .foregroundColor(T.orangeBright)
                Text("文件内容读取失败")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
            }
            Text(readFailureMessage ?? String(localized: "桌面端未返回文件内容，可稍后重试。"))
                .font(T.font(11.5))
                .foregroundColor(T.text3)
                .lineSpacing(4)
            TextActionButton(
                title: "重试",
                action: { Task {
                    isLoading = true
                    await loadContent()
                } },
                identifier: "09-act-retry-content")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityIdentifier("09-read-failed")
    }

    /// 图片 / PDF 只读预览体（失败降级说明，不虚构预览）
    @ViewBuilder
    private func binaryPreviewBody(_ kind: FilePreviewView.BinaryKind) -> some View {
        if kind == .image, let data = binaryData,
           let image = UIImage(data: data) {
            VStack(spacing: T.sp2) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .accessibilityIdentifier("09-binary-image")
                Text("图片预览 · \(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))")
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            }
            .card()
        } else if kind == .pdf, let data = binaryData {
            PDFKitView(data: data)
                .frame(minHeight: 480)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
                .accessibilityIdentifier("09-binary-pdf")
        } else {
            VStack(alignment: .leading, spacing: T.sp2) {
                HStack(spacing: T.sp2) {
                    Image(systemName: kind == .image ? "photo" : "doc.richtext")
                        .font(.system(size: 18))
                        .foregroundColor(T.text3)
                    Text(kind == .image ? "图片" : "PDF")
                        .font(T.font(13.5, .semibold))
                        .foregroundColor(T.text)
                }
                Text(binaryFailed
                     ? "当前桌面端未返回二进制预览数据（readBinaryPreview），请在桌面端查看该文件。"
                     : "正在读取二进制预览…")
                    .font(T.font(12))
                    .foregroundColor(T.text2)
                    .lineSpacing(4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
            .accessibilityIdentifier("09-binary-fallback")
        }
    }

    private var binaryKind: FilePreviewView.BinaryKind? {
        BinaryKind.of(extensionName)
    }

    /// 「已截断 · 加载更多」：追加调用 offset=已读字节数，拼接去重
    private var loadMoreButton: some View {
        Button {
            guard !isLoadingMore else { return }
            isLoadingMore = true
            Task {
                let page = await store.contentPage(
                    of: node.path, offset: loadedBytes, length: Int.max)
                content += page.content
                loadedBytes += page.content.utf8.count
                totalBytes = max(totalBytes, page.totalBytes)
                isTruncated = loadedBytes < totalBytes
                isLoadingMore = false
            }
        } label: {
            HStack(spacing: T.sp1) {
                if isLoadingMore {
                    SpinnerView(size: 12)
                } else {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                Text(isLoadingMore ? "加载中…" : "已截断 · 加载更多（\(loadedBytes.formatted()) / \(totalBytes.formatted()) 字节）")
                    .font(T.font(12, .medium))
            }
            .foregroundColor(T.accentText)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(T.bgInput)
            .clipShape(RoundedRectangle(cornerRadius: T.rS))
        }
        .accessibilityIdentifier("09-act-load-more")
    }

    private var isMarkdown: Bool {
        ["md", "markdown"].contains(extensionName)
    }

    private var extensionName: String {
        (node.name as NSString).pathExtension.lowercased()
    }

    private var sizeText: String {
        // 树节点自带大小优先；缺失时用 readTextFile/stat 回执的 totalBytes（文件详情页大小显示）
        if let size = node.size {
            return ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)
        }
        if totalBytes > 0 {
            return ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file)
        }
        return "—"
    }

    /// 源码视图：等宽 + 行号
    private var sourceView: some View {
        let lines = content.components(separatedBy: "\n")
        return ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                    HStack(alignment: .firstTextBaseline, spacing: T.sp3) {
                        Text("\(index + 1)")
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
                            .frame(width: 32, alignment: .trailing)
                        Text(line.isEmpty ? " " : line)
                            .font(T.mono(12))
                            .foregroundColor(T.text2)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                        Spacer(minLength: 0)
                    }
                    .frame(height: 21)
                }
            }
            .padding(T.sp3)
            .frame(minWidth: UIScreen.main.bounds.width - 64, alignment: .leading)
        }
        .background(T.bgCode)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .accessibilityIdentifier("09-source")
    }
}

// MARK: - PDF 只读预览容器（PDFKit 包装；P2 图片/PDF 预览）

import PDFKit

struct PDFKitView: UIViewRepresentable {
    let data: Data

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.backgroundColor = UIColor(T.bgCode)
        view.document = PDFDocument(data: data)
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {
        if uiView.document == nil {
            uiView.document = PDFDocument(data: data)
        }
    }
}


// MARK: - 文件分享（G-027：UIActivityViewController 的 SwiftUI 包装，分享内容临时文件）

struct ShareFilePayload: Identifiable {
    let id = UUID()
    let url: URL
}

/// iOS 14+ 无原生 ActivityView SwiftUI 封装；最小 UIViewControllerRepresentable
struct ActivityView: UIViewControllerRepresentable {
    let payload: ShareFilePayload

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [payload.url], applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - 一站式提交 Sheet（P3-8：git.generateCommitMessage + git.commit）
// 桌面代执行（gate 2026-10-06 与 web 对齐放行）；提交集由桌面端 git index（stagePaths
// 结果）定义——本 sheet 只传 message。参数/回执协议文档零记录【宽容】，见协议文档条目。

struct CommitSheet: View {
    @Environment(\.fileStore) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(AppSession.self) private var session
    var onCommitted: (String?) -> Void

    @State private var stagedFiles: [DiffFile] = []
    @State private var isLoading = true
    @State private var message = ""
    @State private var isGenerating = false
    @State private var isCommitting = false
    @State private var errorMessage: String?
    @State private var branchName: String?
    @State private var showCommitConfirm = false
    @State private var showOverwriteConfirm = false
    /// 提交身份预检（git.getIdentity，web 对齐 2026-10-06）：web 端身份缺失时
    /// 禁用提交（identityMissing）；移动端形态未取证——警示不阻断，读失败中性标注
    @State private var identity: GitIdentityInfo?
    @State private var identityFailed = false

    private var branchDisplayName: String {
        if let branchName, !branchName.isEmpty { return branchName }
        return String(localized: "当前分支")
    }

    private var canCommit: Bool {
        !isCommitting && !stagedFiles.isEmpty
            && !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("提交到 \(branchDisplayName)")
                    .font(T.font(15, .bold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Text("取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("09-commit-cancel")
            }
            .padding(.horizontal, T.sp4)

            if isLoading {
                CenterLoadingView(text: "正在读取已暂存变更…")
            } else if stagedFiles.isEmpty {
                EmptyStateView(
                    icon: "tray",
                    title: String(localized: "没有已暂存的变更"),
                    detail: String(localized: "先在文件列表批准文件，或使用「全部批准」。"),
                    cta: String(localized: "返回"),
                    ctaAction: { dismiss() },
                    ctaIdentifier: "09-commit-empty-back")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: T.sp4) {
                        stagedList
                        identityRow
                        messageSection
                        if let errorMessage {
                            HStack(spacing: T.sp2) {
                                Image(systemName: "exclamationmark.circle")
                                    .font(.system(size: 12))
                                Text(errorMessage)
                                    .font(T.font(11.5, .semibold))
                                Spacer(minLength: 0)
                            }
                            .foregroundColor(T.orangeBright)
                            .accessibilityIdentifier("09-commit-error")
                        }
                    }
                    .padding(T.sp4)
                }
                .scrollDismissesKeyboard(.interactively)
                submitButton
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .task {
            stagedFiles = await store.diffFiles(sourceId: "staged")
            isLoading = false
            await loadBranch()
            await loadIdentity()
        }
        // 提交确认（design §8.5 从严 destructive；弹层写明将提交哪些文件——用户硬要求）
        .confirmationDialog(
            "确认提交 \(stagedFiles.count) 个文件到 \(branchDisplayName)？",
            isPresented: $showCommitConfirm,
            titleVisibility: .visible) {
            Button("提交", role: .destructive) {
                Task { await performCommit() }
            }
            .accessibilityIdentifier("09-commit-confirm-submit")
            Button("取消", role: .cancel) {}
        } message: {
            Text(commitConfirmMessage)
        }
        // 非空时重新生成 → 先确认覆盖（design §8.3.2 简化规则）
        .alert("覆盖已编辑的提交信息？", isPresented: $showOverwriteConfirm) {
            Button("覆盖", role: .destructive) {
                Task { await generateAndFill() }
            }
            .accessibilityIdentifier("09-commit-overwrite-confirm")
            Button("取消", role: .cancel) {}
        } message: {
            Text("当前内容将被 AI 生成结果替换。")
        }
    }

    // MARK: 已暂存清单

    private var stagedList: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("已暂存（\(stagedFiles.count)）")
                .font(T.font(12, .semibold))
                .foregroundColor(T.text3)
            VStack(spacing: 0) {
                ForEach(Array(stagedFiles.enumerated()), id: \.element.id) { index, file in
                    HStack(spacing: T.sp2) {
                        Text(file.path)
                            .font(T.mono(11.5))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        Spacer(minLength: T.sp2)
                        Text("+\(file.added)")
                            .font(T.mono(10.5))
                            .foregroundColor(T.add)
                        Text("-\(file.removed)")
                            .font(T.mono(10.5))
                            .foregroundColor(T.del)
                    }
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 36)
                    if index < stagedFiles.count - 1 {
                        Divider().overlay(T.border)
                    }
                }
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
    }

    // MARK: 提交信息（AI 生成 + 可编辑）

    private var messageSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Button {
                    requestGenerate()
                } label: {
                    HStack(spacing: 5) {
                        if isGenerating {
                            SpinnerView(size: 11)
                        } else {
                            Image(systemName: "sparkles")
                                .font(.system(size: 11))
                        }
                        Text(isGenerating
                             ? "AI 生成中…"
                             : (message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? "AI 生成提交信息" : "重新生成"))
                            .font(T.font(11.5, .medium))
                    }
                    .foregroundColor(T.accentText)
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 30)
                    .background(T.accentDim)
                    .clipShape(Capsule())
                }
                .disabled(isGenerating || isCommitting)
                .accessibilityIdentifier("09-commit-ai")
                Spacer()
            }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $message)
                    .font(T.mono(13))
                    .foregroundColor(T.text)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 96)
                    .padding(T.sp2)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .accessibilityIdentifier("09-commit-field")
                if message.isEmpty {
                    Text("写一句提交信息，或让 AI 生成")
                        .font(T.mono(13))
                        .foregroundColor(T.text3)
                        .padding(.leading, 14)
                        .padding(.top, 13)
                        .allowsHitTesting(false)
                }
            }
            .disabled(isCommitting)
        }
    }

    private var submitButton: some View {
        Button {
            showCommitConfirm = true
        } label: {
            HStack(spacing: T.sp1) {
                // 提交进行中指示（confirmationDialog 确认后）：桌面 commit 往返秒级
                if isCommitting { SpinnerView(size: 14) }
                Text(isCommitting ? "提交中…" : "提交")
                    .font(T.font(15, .semibold))
                    .foregroundColor(canCommit ? T.onAccent : T.text3)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(canCommit ? T.accent : T.bgInput)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .disabled(!canCommit)
        .accessibilityIdentifier("09-commit-submit")
        .padding(.horizontal, T.sp4)
        .padding(.vertical, T.sp2)
        .background(T.bgElevated)
    }

    /// 确认弹层文案：逐个列出不超 5 条路径，更多以「等共 N 个文件」收口
    private var commitConfirmMessage: String {
        var lines = stagedFiles.prefix(5).map { "• \($0.path)" }
        if stagedFiles.count > 5 {
            lines.append(String(localized: "等共 \(stagedFiles.count) 个文件"))
        }
        return String(localized: "将提交以下文件（写入桌面端仓库历史）：\n\(lines.joined(separator: "\n"))")
    }

    // MARK: 动作

    private func requestGenerate() {
        guard !isGenerating, !isCommitting else { return }
        if !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            showOverwriteConfirm = true
            return
        }
        Task { await generateAndFill() }
    }

    private func generateAndFill() async {
        isGenerating = true
        errorMessage = nil
        // currentSessionFilePaths 携带原始路径（stagePath，web 同参；展示 path 可能是短名）
        let text = await store.generateCommitMessage(paths: stagedFiles.map { $0.stagePath ?? $0.path })
        isGenerating = false
        if let text {
            message = text
        } else {
            errorMessage = String(localized: "生成失败 · 可手写提交信息")
        }
    }

    // MARK: 提交身份预检（git.getIdentity）

    /// 身份行（读面三态：完整=中性展示 / 已读但 name·email 缺失=橙色警示（web
    /// identityMissing 同口径——email 配置缺失是桌面常见提交失败根因，预检省一次
    /// 来回）/ 读取失败=中性标注不阻断。字段宽容解析未取证，警示不硬禁提交，
    /// 提交被拒时既有失败行如实兜底）
    @ViewBuilder
    private var identityRow: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "person.crop.circle")
                .font(.system(size: 11))
                .foregroundColor(identityWarning ? T.orangeBright : T.text3)
            Text(identityText)
                .font(T.font(11.5))
                .foregroundColor(identityWarning ? T.orangeBright : T.text3)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 32)
        .background(T.bgInput)
        .clipShape(Capsule())
        .accessibilityIdentifier("09-commit-identity")
    }

    private var identityWarning: Bool {
        guard let identity, !identityFailed else { return false }
        return !identity.isComplete
    }

    private var identityText: String {
        if identityFailed {
            return String(localized: "提交身份读取失败 · 提交仍可尝试")
        }
        guard let identity else { return String(localized: "提交身份…") }
        if identity.isComplete {
            return String(localized: "提交身份 \(identity.displayText)")
        }
        return String(localized: "桌面端未配置提交身份（name/email 缺失），提交可能被拒绝")
    }

    /// git.getIdentity 只读拉取（RemoteFileStore.gitIdentity；nil=调用失败）
    private func loadIdentity() async {
        guard store.isRemote else { return }
        let result = await store.gitIdentity()
        identity = result
        identityFailed = result == nil
    }

    private func performCommit() async {
        let body = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        isCommitting = true
        errorMessage = nil
        let result = await store.commit(message: body)
        // loading 与结果同帧收口：isCommitting 复位后立即同帧落 onCommitted（乐观
        // 更新）或错误上屏——回执到齐前 loading 不消失
        isCommitting = false
        // 成功判定以回执为准：hash 非 nil 且非空才算提交生效（Store 层已保证，
        // 此处再防空串兜底——绝不把未生效的提交渲染成「已提交」）
        if let hash = result.hash, !hash.isEmpty {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onCommitted(hash)
            dismiss()
        } else {
            // 信息保留可重试（design §8.4：提交失败态）；失败原因如实上屏
            //（服务端 fault reason 优先——写面禁止静默吞错）
            let reason = result.errorMessage ?? String(localized: "桌面端拒绝或连接异常")
            errorMessage = String(localized: "提交失败 · \(reason) · 信息已保留可重试")
        }
    }

    /// 分支名只读拉取（git.getRepositorySummary，DiffReviewView.loadGitSummary 同款；
    /// 失败保持「当前分支」兜底，不阻塞提交流程）
    private func loadBranch() async {
        guard case .connected = session.mode, session.connection.isActive else { return }
        var builder = JSONObjectBuilder()
        if let ws = session.connection.workspace {
            builder.set("workspacePath", ws.path)
        }
        guard let result = try? await session.connection.call(
            "git", "getRepositorySummary", .json(.object(builder.fields))) else { return }
        branchName = result.jsonValue?["branchName"]?.stringValue
    }
}

// MARK: - 检查点 Sheet（P3-11：git-checkpoint.diffCheckpoints / createCheckpoint /
// restoreBetweenCheckpoints）。恢复为破坏性操作（工作区回退）——destructive 确认弹层
// 硬要求（design §11A）；命令词表见 ReadOnlyGate git-checkpoint 注。

struct CheckpointSheet: View {
    @Environment(\.fileStore) private var store
    @Environment(\.dismiss) private var dismiss
    var onRestored: () -> Void

    @State private var checkpoints: [CheckpointInfo] = []
    @State private var isLoading = true
    @State private var loadFailed = false
    @State private var isCreating = false
    @State private var showCreateAlert = false
    @State private var noteText = ""
    @State private var pendingRestore: CheckpointInfo?
    @State private var restoringId: String?
    @State private var errorMessage: String?
    @State private var notice: String?

    static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter
    }()

    private func timeText(_ checkpoint: CheckpointInfo) -> String {
        guard let date = checkpoint.date else { return String(localized: "时间未知") }
        return Self.timeFormatter.string(from: date)
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("检查点")
                    .font(T.font(15, .bold))
                    .foregroundColor(T.text)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Text("取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("09-checkpoint-cancel")
            }
            .padding(.horizontal, T.sp4)

            if isLoading {
                CenterLoadingView(text: "正在读取检查点…")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: T.sp3) {
                        createButton
                        if let notice {
                            HStack(spacing: T.sp2) {
                                Image(systemName: "checkmark.circle")
                                    .font(.system(size: 12))
                                Text(notice)
                                    .font(T.font(11.5, .semibold))
                                Spacer(minLength: 0)
                            }
                            .foregroundColor(T.accentText)
                            .accessibilityIdentifier("09-checkpoint-notice")
                        }
                        if let errorMessage {
                            HStack(spacing: T.sp2) {
                                Image(systemName: "exclamationmark.circle")
                                    .font(.system(size: 12))
                                Text(errorMessage)
                                    .font(T.font(11.5, .semibold))
                                Spacer(minLength: 0)
                            }
                            .foregroundColor(T.orangeBright)
                            .accessibilityIdentifier("09-checkpoint-error")
                        }
                        if loadFailed {
                            failedBlock
                        } else if checkpoints.isEmpty {
                            EmptyStateView(
                                icon: "camera.on.rectangle",
                                title: String(localized: "还没有检查点"),
                                detail: String(localized: "创建检查点后可随时把工作区回退到该时刻。"))
                        } else {
                            listCard
                        }
                    }
                    .padding(T.sp4)
                }
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .task { await reload() }
        // 创建：可选说明 alert（design §11A；带 TextField 先例 ApprovalSheetView）
        .alert("创建检查点", isPresented: $showCreateAlert) {
            TextField("说明（可选）", text: $noteText)
                .accessibilityIdentifier("09-checkpoint-note")
            Button("取消", role: .cancel) { noteText = "" }
            Button("创建") {
                Task { await performCreate() }
            }
            .accessibilityIdentifier("09-checkpoint-create-send")
        } message: {
            Text("为当前工作区状态创建快照，之后可随时恢复到该时刻。")
        }
        // 恢复确认（破坏性 · 硬要求 destructive，文案按 design §11A 逐字）
        .confirmationDialog(
            restoreTitle,
            isPresented: Binding(
                get: { pendingRestore != nil },
                set: { if !$0 { pendingRestore = nil } }),
            titleVisibility: .visible) {
            Button("恢复", role: .destructive) {
                if let target = pendingRestore {
                    Task { await performRestore(target) }
                }
                pendingRestore = nil
            }
            .accessibilityIdentifier("09-checkpoint-confirm-restore")
            Button("取消", role: .cancel) { pendingRestore = nil }
        } message: {
            Text("工作区文件将回退到该时刻，之后的改动会丢失（可通过再次恢复撤销）。")
        }
    }

    private var restoreTitle: String {
        if let target = pendingRestore {
            return String(localized: "恢复到 \(timeText(target)) 的检查点？")
        }
        return String(localized: "恢复检查点？")
    }

    private var createButton: some View {
        Button {
            noteText = ""
            showCreateAlert = true
        } label: {
            HStack(spacing: 5) {
                Image(systemName: "plus")
                    .font(.system(size: 12, weight: .semibold))
                Text(isCreating ? "创建中…" : "创建检查点")
                    .font(T.font(13, .semibold))
            }
            .foregroundColor(T.onAccent)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(isCreating ? T.bgInput : T.accent)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .disabled(isCreating)
        .accessibilityIdentifier("09-checkpoint-create")
    }

    /// 失败诚实占位（design §11A：RemoteCapabilityPlaceholderPage 口径——sheet 内
    /// 以诚实错误块 + 重试承载，不虚构清单）
    private var failedBlock: some View {
        VStack(spacing: T.sp2) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 20))
                .foregroundColor(T.orangeBright)
            Text("检查点信息不可用")
                .font(T.font(13, .semibold))
                .foregroundColor(T.text)
            Text("当前桌面端未返回检查点清单，可稍后重试。")
                .font(T.font(11.5))
                .foregroundColor(T.text3)
                .multilineTextAlignment(.center)
            TextActionButton(
                title: "重试", action: { Task { await reload() } },
                identifier: "09-checkpoint-retry")
        }
        .frame(maxWidth: .infinity)
        .card()
    }

    private var listCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(checkpoints.enumerated()), id: \.offset) { index, checkpoint in
                HStack(spacing: T.sp2) {
                    Image(systemName: "camera.on.rectangle")
                        .font(.system(size: 12))
                        .foregroundColor(T.codeLab)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(timeText(checkpoint))
                            .font(T.mono(12))
                            .foregroundColor(T.text)
                        if let label = checkpoint.label, !label.isEmpty {
                            Text(label)
                                .font(T.font(11))
                                .foregroundColor(T.text3)
                                .lineLimit(2)
                        }
                    }
                    Spacer()
                    if restoringId == checkpoint.id {
                        SpinnerView(size: 14)
                            .frame(width: 44, height: 44)
                    } else {
                        Button {
                            pendingRestore = checkpoint
                        } label: {
                            Text("恢复")
                                .font(T.font(13, .semibold))
                                .foregroundColor(T.red)
                                .padding(.horizontal, T.sp3)
                                .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("09-checkpoint-restore-\(index)")
                    }
                }
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 52)
                if index < checkpoints.count - 1 {
                    Divider().overlay(T.border)
                }
            }
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
    }

    // MARK: 动作

    private func reload() async {
        isLoading = checkpoints.isEmpty
        loadFailed = false
        if let list = await store.checkpoints() {
            checkpoints = list // 键级整体替换，绝不深合并
        } else {
            loadFailed = true
        }
        isLoading = false
    }

    private func performCreate() async {
        isCreating = true
        errorMessage = nil
        let trimmed = noteText.trimmingCharacters(in: .whitespacesAndNewlines)
        let ok = await store.createCheckpoint(note: trimmed.isEmpty ? nil : trimmed)
        isCreating = false
        noteText = ""
        if ok {
            notice = String(localized: "检查点已创建")
            await reload()
        } else {
            errorMessage = String(localized: "创建失败 · 桌面端拒绝或连接异常")
        }
    }

    private func performRestore(_ checkpoint: CheckpointInfo) async {
        restoringId = checkpoint.id
        errorMessage = nil
        let ok = await store.restoreCheckpoint(checkpoint)
        restoringId = nil
        if ok {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onRestored()
            dismiss()
        } else {
            errorMessage = String(localized: "恢复失败 · 桌面端拒绝或连接异常")
        }
    }
}

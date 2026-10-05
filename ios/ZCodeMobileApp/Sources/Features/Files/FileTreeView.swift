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
        .task {
            tree = await store.fileTree()
            isLoading = false
        }
        .task {
            // 文件树活性（file-watcher.onDynamicChange）：变更即重拉；演示态流立即结束
            for await _ in store.observeFileTreeChanges() {
                tree = await store.fileTree()
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
            isLoading = false
        }
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

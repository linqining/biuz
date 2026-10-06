import Foundation

// MARK: - 远端文件存储：file / git / file-watcher 频道 → FileStore 协议

/// 真实实现：FileStore 协议按 mappingToApp 第 5 条落地。
/// - fileTree() ← file.readdir 递归（深度限制，跳过隐藏目录）+ file-watcher 失效
/// - content(of:) ← file.readTextFile（offset/length 有界读，首屏 256KiB）
/// - diffFiles() ← git.refresh 前置 + git.getChanges（unstaged/staged）+ git.getDiff
///   逐文件 patch（恒带 sourceId，web 同参；回执结构化 {availability, patch?, before/after}）
/// - sessionDiffFiles() ← conversationFileChangesV4（会话维度变更，hunk 结构自解析）
/// 已知边界（gaps）：服务端无「逐文件批准」接口——setFileDecision/approveAll 仅本地 UI 态。
actor RemoteFileStore: @preconcurrency FileStore {

    private weak var connection: ZCodeServerConnection?
    private let workspace: ServerWorkspaceInfo

    private var localApprovals: Set<String> = []
    private var localRejections: Set<String> = []
    private var cachedTree: [FileNode] = []

    /// 首屏有界读（file.readTextFile length 上限）：256KiB，超出走「加载更多」分页
    static let firstPageSizeBytes = 256 * 1024

    // MARK: file-watcher（文件树活性：修复 cachedTree 永不失效）

    private var watcherId: String?
    private var watcherStarted = false
    private var changeSubscription: EventSubscription?
    private var treeChangeContinuations: [UUID: AsyncStream<Void>.Continuation] = [:]

    nonisolated var isRemote: Bool { true }

    init(connection: ZCodeServerConnection, workspace: ServerWorkspaceInfo) {
        self.connection = connection
        self.workspace = workspace
    }

    /// Store 释放/断线 teardown：unwatch 当前 watcher + disposeAll 兜底，
    /// 随后取消事件订阅（EventSubscription cancel 链发 EventDispose）。
    deinit {
        // deinit 内不能回调 actor 方法：直接捕获 connection 弱引用发起清理调用
        let identifier = watcherId
        let client = connection
        if client != nil {
            Task {
                guard let client else { return }
                if let identifier {
                    var builder = JSONObjectBuilder()
                    builder.set("id", identifier)
                    _ = try? await client.call("file-watcher", "unwatch", .json(.object(builder.fields)))
                }
                _ = try? await client.call("file-watcher", "disposeAll", .undefined)
            }
        }
    }

    // MARK: 文件树

    func fileTree() async -> [FileNode] {
        if cachedTree.isEmpty {
            cachedTree = await readDirectory(path: workspace.path, depth: 0)
        }
        if !cachedTree.isEmpty {
            await startWatching()
        }
        return cachedTree
    }

    /// file-watcher.watch（recursive）：首载成功后启动；onDynamicChange 失效缓存并通知视图重拉。
    /// watch 失败静默（旧服务端无该 additive 面时文件树退回手动刷新）。
    private func startWatching() async {
        guard !watcherStarted, let connection else { return }
        watcherStarted = true
        var builder = JSONObjectBuilder()
        builder.set("path", workspace.path)
        builder.set("recursive", true)
        do {
            let reply = try await connection.call("file-watcher", "watch", .json(.object(builder.fields)))
            watcherId = reply.jsonValue?["id"]?.stringValue
            // onDynamicChange 按 watcherId 订阅（onDynamicChange(id) 签名）
            var eventBuilder = JSONObjectBuilder()
            if let watcherId {
                eventBuilder.set("id", watcherId)
            }
            changeSubscription = await connection.listen(
                "file-watcher", "onDynamicChange", .json(.object(eventBuilder.fields))) { [weak self] _ in
                guard let self else { return }
                Task { await self.invalidateTree() }
            }
        } catch {
            watcherStarted = false
        }
    }

    /// 变更即失效：cachedTree 置空，下一次 fileTree() 重拉；同时通知 FileTreeView 即时刷新
    private func invalidateTree() async {
        cachedTree = []
        for continuation in treeChangeContinuations.values {
            continuation.yield(())
        }
    }

    func observeFileTreeChanges() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let key = UUID()
            treeChangeContinuations[key] = continuation
            continuation.onTermination = { _ in
                Task { await self.removeTreeContinuation(key) }
            }
        }
    }

    private func removeTreeContinuation(_ key: UUID) {
        treeChangeContinuations.removeValue(forKey: key)
    }

    private func readDirectory(path: String, depth: Int) async -> [FileNode] {
        guard let connection, depth < 3 else { return [] } // 3 层展示深度，够浏览主结构
        let arg = RPCValue.jsonObject { builder in
            builder.set("path", path)
            builder.set("includeHidden", false)
        }
        do {
            let result = try await connection.call("file", "readdir", arg)
            guard let entries = result.jsonValue?.arrayValue else { return [] }
            var nodes: [FileNode] = []
            for entry in entries {
                guard let dict = entry.objectValue,
                      let name = dict["name"]?.stringValue,
                      let entryPath = dict["path"]?.stringValue else { continue }
                let isDirectory = dict["type"]?.stringValue == "directory"
                var node = FileNode(
                    id: entryPath, name: name, path: entryPath,
                    isDirectory: isDirectory, size: dict["size"]?.intValue, children: nil)
                if isDirectory {
                    let children = await readDirectory(path: entryPath, depth: depth + 1)
                    node.children = children.isEmpty ? [] : children
                }
                nodes.append(node)
            }
            return nodes.sorted { lhs, rhs in
                if lhs.isDirectory != rhs.isDirectory { return lhs.isDirectory }
                return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
            }
        } catch {
            return []
        }
    }

    // MARK: 服务端搜索（file.searchWorkspaceFiles；host 有界候选，named gap「本地过滤」升级）

    func searchFiles(_ query: String, limit: Int = 50) async -> [FileNode] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let connection else { return [] }
        let arg = RPCValue.jsonObject { builder in
            builder.set("rootPath", workspace.path)
            builder.set("query", trimmed)
            builder.set("limit", limit)
        }
        do {
            let result = try await connection.call("file", "searchWorkspaceFiles", arg)
            guard let entries = result.jsonValue?.arrayValue else { return [] }
            return entries.compactMap { entry -> FileNode? in
                guard let dict = entry.objectValue,
                      let name = dict["name"]?.stringValue,
                      let path = dict["path"]?.stringValue else { return nil }
                return FileNode(
                    id: path, name: name, path: path,
                    isDirectory: dict["type"]?.stringValue == "directory",
                    size: nil, children: nil)
            }
        } catch {
            return []
        }
    }

    // MARK: 文件元信息（file.stat）

    struct FileStat {
        var size: Int
        var isDirectory: Bool
    }

    func stat(of path: String) async -> FileStat? {
        guard let connection else { return nil }
        let arg = RPCValue.jsonObject { builder in
            builder.set("path", path)
        }
        do {
            let result = try await connection.call("file", "stat", arg)
            guard let dict = result.jsonValue?.objectValue else { return nil }
            // 回执：{path, type: "file"|"directory", size?, mtimeMs?}
            let isDirectory = dict["type"]?.stringValue == "directory"
            let size = dict["size"]?.intValue ?? 0
            return FileStat(size: size, isDirectory: isDirectory)
        } catch {
            return nil
        }
    }

    // MARK: 文件内容（readTextFile 有界读：offset/length + totalBytes 截断判定）

    func content(of path: String) async -> String {
        // 协议位：全量读语义保留给旧调用方；内部走分页实现（首屏 256KiB）
        await contentPage(of: path, offset: 0, length: Int.max).content
    }

    func contentPage(of path: String, offset: Int, length: Int) async -> FileContentPage {
        guard connection != nil else { return FileContentPage(content: "", totalBytes: 0, isTruncated: false) }
        // stat 前置守卫：超大文件先截断提示，避免静默拉全量（二进制/超大文件不静默空串）
        if offset == 0, let fileStat = await stat(of: path), !fileStat.isDirectory,
           fileStat.size > Self.firstPageSizeBytes {
            return await readTextSlice(path: path, offset: 0, length: Self.firstPageSizeBytes,
                                       knownTotal: fileStat.size)
        }
        return await readTextSlice(path: path, offset: offset, length: length)
    }

    private func readTextSlice(path: String, offset: Int, length: Int, knownTotal: Int? = nil) async -> FileContentPage {
        guard let connection else { return FileContentPage(content: "", totalBytes: knownTotal ?? 0, isTruncated: false) }
        var builder = JSONObjectBuilder()
        builder.set("path", path)
        if offset > 0 {
            builder.set("offset", offset)
        }
        if length != Int.max {
            builder.set("length", length)
        }
        do {
            let result = try await connection.call("file", "readTextFile", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else {
                return FileContentPage(content: "", totalBytes: knownTotal ?? 0, isTruncated: false)
            }
            let content = dict["content"]?.stringValue ?? ""
            // FileTextSlice：{path, content, offset, bytesRead, totalBytes}
            let totalBytes = dict["totalBytes"]?.intValue
                ?? knownTotal
                ?? (offset + content.utf8.count)
            let bytesRead = dict["bytesRead"]?.intValue ?? content.utf8.count
            let isTruncated = offset + bytesRead < totalBytes
            return FileContentPage(content: content, totalBytes: totalBytes, isTruncated: isTruncated)
        } catch {
            return FileContentPage(content: "", totalBytes: knownTotal ?? 0, isTruncated: false)
        }
    }

    // MARK: 二进制预览（P2：file.readBinaryPreview 读族，gate 读白名单内）

    /// 有界读二进制首块（图片/PDF 只读预览）。回执宽容：base64 字段（data/content
    /// base64 编码）或 bytes 数组；失败返回 nil（UI 降级为说明文案，不虚构预览）。
    func binaryPreview(of path: String, maxBytes: Int = 2_000_000) async -> Data? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("path", path)
        builder.set("offset", 0)
        builder.set("length", maxBytes)
        do {
            let result = try await connection.call(
                "file", "readBinaryPreview", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else { return nil }
            if let base64 = dict["dataBase64"]?.stringValue ?? dict["base64"]?.stringValue
                ?? dict["data"]?.stringValue {
                return Data(base64Encoded: base64)
            }
            if let bytes = dict["bytes"]?.arrayValue {
                return Data(bytes.compactMap { UInt8(exactly: $0.intValue ?? -1) })
            }
            return nil
        } catch {
            return nil
        }
    }

    // MARK: Diff（git 层：工作区变更，unstaged/staged 双维度 + refresh 前置）

    func diffFiles() async -> [DiffFile] {
        await fetchChanges(sourceId: "unstaged")
    }

    func diffFiles(sourceId: String) async -> [DiffFile] {
        await fetchChanges(sourceId: sourceId)
    }

    /// git.refresh 前置（named gap：Diff 角标/Diff 页读到过期仓库状态）：失败静默降级维持现行为。
    private func refreshGit() async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        _ = try? await connection.call("git", "refresh", .json(.object(builder.fields)))
    }

    /// getChanges 有界拉取（prefix(20)）：sourceId ∈ unstaged | staged
    private func fetchChanges(sourceId: String) async -> [DiffFile] {
        guard let connection else { return [] }
        await refreshGit()
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspace.path)
            builder.set("sourceId", sourceId)
        }
        do {
            let result = try await connection.call("git", "getChanges", arg)
            guard let changes = result.jsonValue?.arrayValue else { return [] }
            var files: [DiffFile] = []
            for change in changes.prefix(20) { // 移动端有界展示
                guard let dict = change.objectValue,
                      let path = dict["path"]?.stringValue else { continue }
                let displayPath = dict["repoRelativePath"]?.stringValue ?? path
                var lines: [DiffLine] = []
                var added = dict["added"]?.intValue ?? 0
                var removed = dict["removed"]?.intValue ?? 0
                if let patch = await fetchPatch(path: path, sourceId: sourceId) {
                    lines = Self.parsePatch(patch)
                    added = max(added, lines.filter { $0.kind == .add }.count)
                    removed = max(removed, lines.filter { $0.kind == .del }.count)
                }
                files.append(DiffFile(
                    id: displayPath,
                    path: displayPath,
                    language: Self.language(for: displayPath),
                    added: added,
                    removed: removed,
                    lines: lines,
                    isApproved: localApprovals.contains(displayPath),
                    isRejected: localRejections.contains(displayPath)))
            }
            return files
        } catch {
            return []
        }
    }

    /// 是否有超出有界展示的更多变更（fetchChanges 截断到 20 条的「更多」提示判定）
    func hasMoreChanges(sourceId: String) async -> Bool {
        guard let connection else { return false }
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspace.path)
            builder.set("sourceId", sourceId)
        }
        guard let result = try? await connection.call("git", "getChanges", arg),
              let changes = result.jsonValue?.arrayValue else { return false }
        return changes.count > 20
    }

    // MARK: 会话维度文件变更（conversationFileChangesV4）

    /// 本次会话变更：target={sessionId}，base* 取会话 state 快照（缺省 0/空 epoch）。
    /// 回执 items[].patches 为 hunk 结构（oldStart/lines），非 unified 文本，单独转换。
    /// 签名对齐协议 requirement `sessionDiffFiles(sessionId:)`（带默认参的多参实现
    /// 不满足 witness 匹配，会被存在类型分派到协议默认实现而静默失效）。
    func sessionDiffFiles(sessionId: String) async -> [DiffFile] {
        guard let connection, !sessionId.isEmpty else { return [] }
        var builder = JSONObjectBuilder()
        builder.set("sessionId", sessionId)
        builder.set("target", .object(["sessionId": .string(sessionId)]))
        builder.set("baseRevision", 0)
        builder.set("baseLogEpoch", "0")
        do {
            let result = try await connection.call(
                "zcode-agent", "conversationFileChangesV4", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else { return [] }
            var files: [DiffFile] = []
            for item in dict["items"]?.arrayValue ?? [] {
                guard let itemDict = item.objectValue,
                      let path = itemDict["path"]?.stringValue else { continue }
                var lines: [DiffLine] = []
                var id = 0
                for hunk in itemDict["patches"]?.arrayValue ?? [] {
                    guard let hunkDict = hunk.objectValue else { continue }
                    let header = "@@ -\(hunkDict["oldStart"]?.intValue ?? 0),\(hunkDict["oldLines"]?.intValue ?? 0) +\(hunkDict["newStart"]?.intValue ?? 0),\(hunkDict["newLines"]?.intValue ?? 0) @@"
                    lines.append(DiffLine(id: "sl-\(id)", kind: .hunk, oldNumber: nil, newNumber: nil, text: header))
                    id += 1
                    var oldNumber = hunkDict["oldStart"]?.intValue ?? 0
                    var newNumber = hunkDict["newStart"]?.intValue ?? 0
                    for raw in hunkDict["lines"]?.arrayValue ?? [] {
                        guard let text = raw.stringValue else { continue }
                        if text.hasPrefix("+") {
                            lines.append(DiffLine(id: "sl-\(id)", kind: .add, oldNumber: nil, newNumber: newNumber, text: String(text.dropFirst())))
                            newNumber += 1
                        } else if text.hasPrefix("-") {
                            lines.append(DiffLine(id: "sl-\(id)", kind: .del, oldNumber: oldNumber, newNumber: nil, text: String(text.dropFirst())))
                            oldNumber += 1
                        } else {
                            lines.append(DiffLine(id: "sl-\(id)", kind: .ctx, oldNumber: oldNumber, newNumber: newNumber, text: String(text.dropFirst())))
                            oldNumber += 1
                            newNumber += 1
                        }
                        id += 1
                    }
                }
                let added = itemDict["additions"]?.intValue ?? lines.filter { $0.kind == .add }.count
                let removed = itemDict["deletions"]?.intValue ?? lines.filter { $0.kind == .del }.count
                // 显示口径对齐 git.getChanges 路径（repoRelativePath/短名）：
                // 完整路径作 id 会让文件卡 identifier 带绝对路径，与其余分段不一致
                let displayPath = (path as NSString).lastPathComponent
                files.append(DiffFile(
                    id: displayPath,
                    path: displayPath,
                    language: Self.language(for: displayPath),
                    added: added,
                    removed: removed,
                    lines: lines,
                    isApproved: localApprovals.contains(displayPath),
                    isRejected: localRejections.contains(displayPath)))
            }
            return files
        } catch {
            return []
        }
    }

    /// git.getDiff：web 恒带 sourceId（bundle 取证 `getDiff({workspacePath, path, sourceId})`，
    /// GitPane 按页签传 unstaged|staged）——staged 页签漏传会被桌面按默认源解析，patch 口径错位。
    private func fetchPatch(path: String, sourceId: String) async -> String? {
        guard let connection else { return nil }
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspace.path)
            builder.set("path", path)
            builder.set("sourceId", sourceId)
        }
        do {
            let result = try await connection.call("git", "getDiff", arg)
            guard let dict = result.jsonValue?.objectValue else {
                // 宽容：旧桌面顶层字符串形态
                return result.jsonValue?.stringValue
            }
            // web 结构化回执 {availability: "patch"|"unavailable", patch?, beforeContent?, afterContent?}
            if let patch = dict["patch"]?.stringValue, !patch.isEmpty { return patch }
            guard dict["availability"]?.stringValue != "unavailable" else { return nil }
            // patch 缺席但前后全文在场（web 形态）：合成整文件替换 diff（行内容为服务端原文）
            return Self.synthesizePatch(
                beforeContent: dict["beforeContent"]?.stringValue,
                afterContent: dict["afterContent"]?.stringValue)
        } catch {
            return nil
        }
    }

    /// before/after 全文 → 伪 unified diff（整文件替换）。仅用于 getDiff 结构化回执中
    /// patch 键缺席的宽容回退：行内容均来自服务端回执，不虚构任何行；缺少最小化
    /// hunk 切分（web 端此形态走前后双栏 diff 视图，移动端无等价组件，降级整读展示）。
    static func synthesizePatch(beforeContent: String?, afterContent: String?) -> String? {
        guard beforeContent != nil || afterContent != nil else { return nil }
        let beforeLines = (beforeContent ?? "").components(separatedBy: "\n")
        let afterLines = (afterContent ?? "").components(separatedBy: "\n")
        var out = ["@@ -1,\(beforeLines.count) +1,\(afterLines.count) @@"]
        out += beforeLines.map { "-\($0)" }
        out += afterLines.map { "+\($0)" }
        return out.joined(separator: "\n")
    }

    /// unified diff 文本 → DiffLine（kind 按行首 +/-/@@ 前缀映射）
    static func parsePatch(_ patch: String) -> [DiffLine] {
        var lines: [DiffLine] = []
        var oldNumber = 0
        var newNumber = 0
        var id = 0
        for raw in patch.components(separatedBy: "\n") {
            if raw.hasPrefix("@@") {
                // @@ -a,b +c,d @@
                let numbers = raw.split(separator: " ").compactMap { part -> Int? in
                    if part.hasPrefix("-") { return Int(part.dropFirst().split(separator: ",").first ?? "") }
                    if part.hasPrefix("+") { return Int(part.dropFirst().split(separator: ",").first ?? "") }
                    return nil
                }
                oldNumber = numbers.count > 0 ? numbers[0] : 0
                newNumber = numbers.count > 1 ? numbers[1] : 0
                lines.append(DiffLine(id: "dl-\(id)", kind: .hunk, oldNumber: nil, newNumber: nil, text: raw))
                id += 1
            } else if raw.hasPrefix("+") {
                lines.append(DiffLine(id: "dl-\(id)", kind: .add, oldNumber: nil, newNumber: newNumber, text: String(raw.dropFirst())))
                newNumber += 1
                id += 1
            } else if raw.hasPrefix("-") {
                lines.append(DiffLine(id: "dl-\(id)", kind: .del, oldNumber: oldNumber, newNumber: nil, text: String(raw.dropFirst())))
                oldNumber += 1
                id += 1
            } else if raw.hasPrefix("+++") || raw.hasPrefix("---") || raw.hasPrefix("diff ") || raw.hasPrefix("index ") {
                continue
            } else {
                lines.append(DiffLine(id: "dl-\(id)", kind: .ctx, oldNumber: oldNumber, newNumber: newNumber, text: raw))
                oldNumber += 1
                newNumber += 1
                id += 1
            }
        }
        return lines
    }

    static func language(for path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "swift": return "swift"
        case "ts", "tsx", "js", "jsx", "mjs": return "typescript"
        case "py": return "python"
        case "md": return "markdown"
        case "json", "yml", "yaml": return "json"
        case "html": return "html"
        case "css", "scss": return "css"
        case "sh", "zsh": return "bash"
        default: return ext.isEmpty ? "text" : ext
        }
    }

    // MARK: 逐文件批准（2026-10-06 边界放开：git 写族与 web bundle 对齐，桌面代执行
    // ——`git.stagePaths {workspacePath, paths}` / `git.unstagePaths {…}`，web 端同参同面）

    func setFileDecision(path: String, approved: Bool?) async {
        defer { gitDecisionDiag(path: path, approved: approved) }
        switch approved {
        case .some(true):
            // 批准 → stage：文件移入已暂存，「批准」落地为桌面 git 实态
            guard await callGitWrite("stagePaths", paths: [path]) else { return }
            localApprovals.insert(path)
            localRejections.remove(path)
        case .some(false):
            // 拒绝 → unstage：仅退索引（安全可逆、幂等）；工作区改动不动
            //（破坏性 discardPaths 不接）
            guard await callGitWrite("unstagePaths", paths: [path]) else { return }
            localRejections.insert(path)
            localApprovals.remove(path)
        case .none:
            localApprovals.remove(path)
            localRejections.remove(path)
        }
    }

    /// 全部批准 = 未决文件一次性 stagePaths（web 提交前批量 stage 同款；一次 RPC）
    func approveAll() async {
        let files = await diffFiles()
        let undecided = files.filter { !$0.isApproved && !$0.isRejected }.map(\.path)
        guard !undecided.isEmpty else { return }
        defer { gitDecisionDiag(path: "approveAll(\(undecided.count))", approved: true) }
        guard await callGitWrite("stagePaths", paths: undecided) else { return }
        for path in undecided {
            localApprovals.insert(path)
            localRejections.remove(path)
        }
    }

    /// git 写命令统一出口（ReadOnlyGate 已按 web 对齐放行；失败静默 + diag 取证）
    private func callGitWrite(_ command: String, paths: [String]) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        builder.set("paths", .array(paths.map { .string($0) }))
        do {
            _ = try await connection.call("git", command, .json(.object(builder.fields)))
            return true
        } catch {
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    "git.\(command) paths=\(paths.count) err=\(String(describing: error).prefix(300))",
                    forKey: "diag.git.write")
                UserDefaults.standard.synchronize()
            }
            return false
        }
    }

    private func gitDecisionDiag(path: String, approved: Bool?) {
        guard UserDefaults.standard.string(forKey: "diag.wf.mode") != nil else { return }
        UserDefaults.standard.set(
            "path=\(path) approved=\(String(describing: approved))", forKey: "diag.git.decision")
        UserDefaults.standard.synchronize()
    }

    // MARK: 一站式提交（P3-8：git 写族 UI 化，gate 2026-10-06 与 web 对齐全放行）
    // 协议事实：git.generateCommitMessage / git.commit 命令名来自上游 gitService
    // 18 方法清单（立项报告 §8.1:578，git/git.ts:33-52）；参数与回执零记录【宽容】。

    /// git.generateCommitMessage：参数按 web 形状（bundle 取证 GitActionMenu——
    /// `generateCommitMessage({workspacePath, workspaceIdentity?, locale, includeUnstaged,
    /// currentSessionFilePaths?, conversationContext?})`，选中集=includeUnstaged? staged+
    /// unstaged : staged）。移动端从已暂存清单进入 → includeUnstaged 恒 false、所选文件
    /// 经 currentSessionFilePaths 携带（协议形参 paths 仍为已暂存文件，仅载荷键名对齐）。
    /// 回执 {providerId, model, message}（web 读法）；message|commitMessage|text 宽容兜底，
    /// 空串视为失败。幂等读级：首败 1.2s 退避重试一次（中继瞬断纪律，AGENTS §5.8）。
    func generateCommitMessage(paths: [String]) async -> String? {
        guard let connection, !paths.isEmpty else { return nil }
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspace.path)
            builder.set("workspaceIdentity", workspace.workspaceIdentity)
            builder.set("locale", Locale.current.identifier)
            builder.set("includeUnstaged", false)
            builder.set("currentSessionFilePaths", .array(paths.map { .string($0) }))
        }
        for attempt in 0..<2 {
            do {
                let result = try await connection.call("git", "generateCommitMessage", arg)
                guard let dict = result.jsonValue?.objectValue else {
                    // 顶层字符串形态兜底
                    let text = result.jsonValue?.stringValue ?? result.stringValue ?? ""
                    return text.isEmpty ? nil : text
                }
                let text = dict["message"]?.stringValue ?? dict["commitMessage"]?.stringValue
                    ?? dict["text"]?.stringValue ?? ""
                return text.isEmpty ? nil : text
            } catch {
                guard attempt == 0 else {
                    gitWriteDiag(command: "generateCommitMessage", detail: "files=\(paths.count)", error: error)
                    return nil
                }
                try? await Task.sleep(nanoseconds: 1_200_000_000)
            }
        }
        return nil
    }

    // MARK: 提交身份预检（git.getIdentity：email 配置缺失是桌面常见提交失败根因，
    // 手机端预检省一次来回）

    /// git.getIdentity {workspacePath}（web GitBranchSwitcher 与 getChanges('staged')
    /// Promise.allSettled 并发同面，bundle 取证【移植·bundle 逆向】；web 侧回执
    /// fulfilled→identity 直入提交上下文、rejected→null 禁用提交按钮并提示
    /// identityMissing）。回执字段名未在 web 消费点出现——name|userName /
    /// email|userEmail 宽容链【未取证】；对象在场但字段空 = 身份未配置（isComplete
    /// 判定），调用方如实警示。幂等读级：首败 1.2s 退避重试一次（AGENTS §5.8）。
    func gitIdentity() async -> GitIdentityInfo? {
        guard let connection else { return nil }
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspace.path)
        }
        for attempt in 0..<2 {
            do {
                let result = try await connection.call("git", "getIdentity", arg)
                guard let dict = result.jsonValue?.objectValue else { return nil }
                return GitIdentityInfo(
                    name: dict["name"]?.stringValue ?? dict["userName"]?.stringValue,
                    email: dict["email"]?.stringValue ?? dict["userEmail"]?.stringValue)
            } catch {
                guard attempt == 0 else {
                    gitWriteDiag(command: "getIdentity", detail: "read", error: error)
                    return nil
                }
                try? await Task.sleep(nanoseconds: 1_200_000_000)
            }
        }
        return nil
    }

    /// git.commit {workspacePath, message}：提交已暂存变更（提交集由桌面端 git
    /// index 定义——stagePaths 的结果；不传 paths，未取证参数不臆造）。
    /// 回执宽容：commitHash|hash|oid|id → 短 hash（prefix 8）；无 hash 字段视为
    /// 成功返回空串。写命令不自动重试（防双重提交）。
    func commit(message: String) async -> String? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        builder.set("message", message)
        do {
            let result = try await connection.call("git", "commit", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else { return "" }
            let hash = dict["commitHash"]?.stringValue ?? dict["hash"]?.stringValue
                ?? dict["oid"]?.stringValue ?? dict["id"]?.stringValue ?? ""
            return String(hash.prefix(8))
        } catch {
            gitWriteDiag(command: "commit", detail: "msg=\(message.prefix(40))", error: error)
            return nil
        }
    }

    // MARK: 检查点（P3-11：git-checkpoint 频道，gate 已按设计稿 §11A 前置放行
    // create/restore；命令名来自上游 gitCheckpointService 四方法清单——立项报告
    // §8.1:579，git/gitCheckpoint.ts:14-17。参数与回执零记录【宽容】）
    // ⚠️ HIDDEN(对齐修复 H3)：web bundle 三方法 0 命中且协议文档零实证条目（审查报告 §五），
    // UI 入口已在 FileTreeView 注释隐藏；本节发送方法保留编译，真机取证立条后恢复入口。

    /// git-checkpoint.diffCheckpoints {workspacePath}（读面）：检查点清单，按时间倒序。
    /// 回执宽容：checkpoints[]|items[]|顶层数组。幂等读级：首败 1.2s 退避重试一次。
    func checkpoints() async -> [CheckpointInfo]? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        let arg = RPCValue.json(.object(builder.fields))
        for attempt in 0..<2 {
            do {
                let result = try await connection.call("git-checkpoint", "diffCheckpoints", arg)
                let items = result.jsonValue?["checkpoints"]?.arrayValue
                    ?? result.jsonValue?["items"]?.arrayValue
                    ?? result.jsonValue?.arrayValue
                    ?? []
                let list = items.compactMap(Self.parseCheckpoint)
                    .sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
                return list
            } catch {
                guard attempt == 0 else {
                    gitWriteDiag(command: "diffCheckpoints", detail: "read", error: error)
                    return nil
                }
                try? await Task.sleep(nanoseconds: 1_200_000_000)
            }
        }
        return nil
    }

    /// git-checkpoint.createCheckpoint {workspacePath, message?}：创建检查点
    /// （桌面代执行快照写；note 缺省时键省略）。写命令不自动重试。
    func createCheckpoint(note: String?) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        builder.set("message", note)
        do {
            _ = try await connection.call(
                "git-checkpoint", "createCheckpoint", .json(.object(builder.fields)))
            return true
        } catch {
            gitWriteDiag(command: "createCheckpoint", detail: "note=\(note.map { String($0.prefix(40)) } ?? "")", error: error)
            return false
        }
    }

    /// git-checkpoint.restoreBetweenCheckpoints：恢复工作区到检查点（破坏性——
    /// UI 侧硬要求 destructive 确认弹层后才可达此处）。参数未取证，按最可能单参
    /// 形态携 checkpointId（别名形态见 docEntries【宽容】注）。成功后失效文件树
    /// 缓存（工作区文件已回退）。
    func restoreCheckpoint(_ checkpoint: CheckpointInfo) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        builder.set("checkpointId", checkpoint.id)
        do {
            _ = try await connection.call(
                "git-checkpoint", "restoreBetweenCheckpoints", .json(.object(builder.fields)))
            await invalidateTree()
            return true
        } catch {
            gitWriteDiag(command: "restoreBetweenCheckpoints", detail: "id=\(checkpoint.id)", error: error)
            return false
        }
    }

    /// 检查点行宽容解析：id ← id|checkpointId|hash|revision；时间 ← timestamp|
    /// createdAt|time|date（毫秒优先，<1e11 视为秒级兜底；ISO 字符串次之）；
    /// 说明 ← message|label|description|note。id 与时间全缺的行丢弃。
    static func parseCheckpoint(_ json: JSONValue) -> CheckpointInfo? {
        guard let dict = json.objectValue else { return nil }
        let id = dict["id"]?.stringValue ?? dict["checkpointId"]?.stringValue
            ?? dict["hash"]?.stringValue ?? dict["revision"]?.stringValue
        let date = parseCheckpointDate(dict)
        guard id != nil || date != nil else { return nil }
        let label = dict["message"]?.stringValue ?? dict["label"]?.stringValue
            ?? dict["description"]?.stringValue ?? dict["note"]?.stringValue
        return CheckpointInfo(
            id: id ?? "", date: date, label: label)
    }

    /// 时间字段双形态解析（lastActivityAt 同口径：真实桌面推毫秒，ISO 字符串兜底）
    private static func parseCheckpointDate(_ dict: [String: JSONValue]) -> Date? {
        for key in ["timestamp", "createdAt", "time", "date"] {
            let ms = dict[key]?.intValue ?? 0
            if ms > 0 {
                return Date(timeIntervalSince1970: ms > 100_000_000_000 ? Double(ms) / 1000 : Double(ms))
            }
            if let iso = dict[key]?.stringValue {
                let formatter = ISO8601DateFormatter()
                formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
                if let date = formatter.date(from: iso) { return date }
                if let date = ISO8601DateFormatter().date(from: iso) { return date }
            }
        }
        return nil
    }

    /// git/git-checkpoint 命令失败取证（diag.wf.mode 存在才写；通道沿用 diag.git.write）
    private func gitWriteDiag(command: String, detail: String, error: Error) {
        guard UserDefaults.standard.string(forKey: "diag.wf.mode") != nil else { return }
        UserDefaults.standard.set(
            "git.\(command) \(detail) err=\(String(describing: error).prefix(300))",
            forKey: "diag.git.write")
        UserDefaults.standard.synchronize()
    }
}

import Foundation

/// 文件树 / Diff 内存实现。
actor MockFileStore: @preconcurrency FileStore {

    private var tree: [FileNode] = []
    private var diffs: [DiffFile] = []
    private var contents: [String: String] = [:]

    init() {
        seed()
    }

    func fileTree() async -> [FileNode] { tree }

    func diffFiles() async -> [DiffFile] { diffs }

    func content(of path: String) async -> String {
        contents[path] ?? "（二进制或未缓存内容：\(path)）"
    }

    /// 演示路径：本地标记翻转即成功（返回 nil），忽略写面错误语义
    func setFileDecision(path: String, approved: Bool?) async -> String? {
        guard let index = diffs.firstIndex(where: { $0.path == path }) else { return nil }
        diffs[index].isApproved = approved == true
        diffs[index].isRejected = approved == false
        return nil
    }

    func approveAll() async -> String? {
        for index in diffs.indices {
            diffs[index].isApproved = true
            diffs[index].isRejected = false
        }
        return nil
    }

    // MARK: - 预置

    private func seed() {
        tree = [
            FileNode(id: "src", name: "src", path: "src", isDirectory: true, children: [
                FileNode(id: "src/core", name: "core", path: "src/core", isDirectory: true, children: [
                    file("src/core/SessionStore.swift", 4_820),
                    file("src/core/TaskRunner.swift", 3_115),
                    file("src/core/MockAgent.swift", 1_940),
                ]),
                FileNode(id: "src/ui", name: "ui", path: "src/ui", isDirectory: true, children: [
                    file("src/ui/SessionPane.swift", 7_640),
                    file("src/ui/Composer.swift", 2_308),
                ]),
                FileNode(id: "src/server", name: "server", path: "src/server", isDirectory: true, children: [
                    file("src/server/main.swift", 986),
                    file("src/server/remote.swift", 3_522),
                ]),
            ]),
            file("DESIGN.md", 12_830),
            file("README.md", 2_115),
            FileNode(id: "package.json", name: "package.json", path: "package.json", isDirectory: false, size: 560),
        ]

        diffs = [
            DiffFile(
                id: "d1", path: "src/core/SessionStore.swift", language: "swift",
                added: 24, removed: 8, lines: diffLines(prefix: "d1", hunk1: "@@ -18,7 +18,9 @@ final class SessionStore {")),
            DiffFile(
                id: "d2", path: "src/ui/Composer.swift", language: "swift",
                added: 6, removed: 2, lines: [
                    DiffLine(id: "d2-1", kind: .hunk, oldNumber: nil, newNumber: nil, text: "@@ -55,6 +55,8 @@ struct ComposerBar: View {"),
                    DiffLine(id: "d2-2", kind: .ctx, oldNumber: 55, newNumber: 55, text: "  HStack(spacing: T.sp2) {"),
                    DiffLine(id: "d2-3", kind: .del, oldNumber: 56, newNumber: nil, text: "-    TextField(\"发送消息\", text: $draft)"),
                    DiffLine(id: "d2-4", kind: .add, oldNumber: nil, newNumber: 56, text: "+    TextField(\"发送消息\", text: $draft, axis: .vertical)"),
                    DiffLine(id: "d2-5", kind: .add, oldNumber: nil, newNumber: 57, text: "+      .font(T.font(16)) // iOS 聚焦防缩放"),
                    DiffLine(id: "d2-6", kind: .ctx, oldNumber: 57, newNumber: 58, text: "      .padding(.horizontal, T.sp3)"),
                    DiffLine(id: "d2-7", kind: .ctx, oldNumber: 58, newNumber: 59, text: "      .frame(minHeight: 44)"),
                ]),
            DiffFile(
                id: "d3", path: "DESIGN.md", language: "markdown",
                added: 3, removed: 1, lines: [
                    DiffLine(id: "d3-1", kind: .hunk, oldNumber: nil, newNumber: nil, text: "@@ -4,6 +4,8 @@ # ZCode 设计规范"),
                    DiffLine(id: "d3-2", kind: .ctx, oldNumber: 4, newNumber: 4, text: "## 设计令牌"),
                    DiffLine(id: "d3-3", kind: .del, oldNumber: 5, newNumber: nil, text: "- 主色 #21c17a"),
                    DiffLine(id: "d3-4", kind: .add, oldNumber: nil, newNumber: 5, text: "- 主色 #32f08c（对比度 12.7:1）"),
                    DiffLine(id: "d3-5", kind: .add, oldNumber: nil, newNumber: 6, text: "- 深字 #04120a 用于绿底前景"),
                    DiffLine(id: "d3-6", kind: .ctx, oldNumber: 6, newNumber: 7, text: "- 状态三色：Running / Waiting / Completed"),
                ]),
        ]

        contents["DESIGN.md"] = """
        # ZCode 移动端设计规范（摘要）

        本文档为移动端设计规范摘要，完整版见桌面端仓库。

        ## 设计原则

        - 遥控优先：手机负责下发任务、盯执行、审批与验收
        - 状态即界面：Running = 蓝 / Waiting = 橙 / Completed = 绿
        - 小屏降维：底部 Tab + 三层压栈 + 底部模态

        ## 色板

        ```sql
        SELECT token, value FROM palette WHERE theme = 'dark';
        -- bg        #0a0b0d
        -- accent    #32f08c
        -- text      #f5f9fe
        ```

        状态胶囊左缀 6px 状态点；diff 新增行 `#3ddc84`、删除行 `#ff7a7a`。
        """
        contents["README.md"] = """
        # zcode

        手机远控桌面：下发任务 → 盯执行 → 审批 → 验收产物。

        ## 快速开始

        1. 克隆仓库
        2. `npm install`
        3. `npm run dev`
        """
        contents["src/core/SessionStore.swift"] = """
        import Foundation

        /// 会话持久化协议（v42 重构后）
        protocol SessionStoreProtocol: Sendable {
            func sessions() async throws -> [Session]
            func save(_ session: Session) async throws
        }

        final class SessionStore: SessionStoreProtocol {
            let fileStore: KeyValueFileStore

            init(fileStore: KeyValueFileStore = JSONFileStore.default) {
                self.fileStore = fileStore
            }

            func sessions() async throws -> [Session] {
                guard let data = try await fileStore.load() else { return [] }
                return try JSONDecoder().decode([Session].self, from: data)
            }

            func save(_ session: Session) async throws {
                var all = try await sessions()
                all.removeAll { $0.id == session.id }
                all.append(session)
                let data = try JSONEncoder().encode(all)
                try await fileStore.store(data)
            }
        }
        """
        contents["package.json"] = """
        {
          "name": "zcode-web",
          "private": true,
          "scripts": {
            "dev": "vite",
            "build": "tsc && vite build"
          }
        }
        """
    }

    private func file(_ path: String, _ size: Int) -> FileNode {
        FileNode(id: path, name: (path as NSString).lastPathComponent,
                 path: path, isDirectory: false, size: size)
    }

    private func diffLines(prefix: String, hunk1: String) -> [DiffLine] {
        [
            DiffLine(id: "\(prefix)-1", kind: .hunk, oldNumber: nil, newNumber: nil, text: hunk1),
            DiffLine(id: "\(prefix)-2", kind: .ctx, oldNumber: 18, newNumber: 18, text: "  func sessions() -> [Session] {"),
            DiffLine(id: "\(prefix)-3", kind: .del, oldNumber: 19, newNumber: nil, text: "-    return fileStore.loadSync()"),
            DiffLine(id: "\(prefix)-4", kind: .del, oldNumber: 20, newNumber: nil, text: "-    .map(decode(Session.self))"),
            DiffLine(id: "\(prefix)-5", kind: .add, oldNumber: nil, newNumber: 19, text: "+    func sessions() async throws -> [Session] {"),
            DiffLine(id: "\(prefix)-6", kind: .add, oldNumber: nil, newNumber: 20, text: "+    guard let data = try await fileStore.load() else { return [] }"),
            DiffLine(id: "\(prefix)-7", kind: .add, oldNumber: nil, newNumber: 21, text: "+    return try decoder.decode([Session].self, from: data)"),
            DiffLine(id: "\(prefix)-8", kind: .ctx, oldNumber: 21, newNumber: 22, text: "  }"),
            DiffLine(id: "\(prefix)-9", kind: .hunk, oldNumber: nil, newNumber: nil, text: "@@ -31,4 +33,9 @@ extension SessionStore {"),
            DiffLine(id: "\(prefix)-10", kind: .del, oldNumber: 31, newNumber: nil, text: "-    static let shared = SessionStore()"),
            DiffLine(id: "\(prefix)-11", kind: .add, oldNumber: nil, newNumber: 33, text: "+    let fileStore: KeyValueFileStore"),
            DiffLine(id: "\(prefix)-12", kind: .add, oldNumber: nil, newNumber: 34, text: "+    init(fileStore: KeyValueFileStore = JSONFileStore.default) {"),
            DiffLine(id: "\(prefix)-13", kind: .add, oldNumber: nil, newNumber: 35, text: "+        self.fileStore = fileStore"),
            DiffLine(id: "\(prefix)-14", kind: .add, oldNumber: nil, newNumber: 36, text: "+    }"),
            DiffLine(id: "\(prefix)-15", kind: .ctx, oldNumber: 32, newNumber: 37, text: "}"),
        ]
    }
}

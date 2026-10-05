import SwiftUI

// MARK: - 三层上下文数据源与持久化（项 2：账号 / 机器 / 项目胶囊）
//
// 设备层口径（调研 deviceListApi）：账号级设备列表云端 API 未上线，"设备列表"的
// 最贴近绑定假设 = 云端沙盒（固定一项）+ 本机已配对的中继服务器（ServerRegistry
// 内 relay 非空者，凭据仅存 Keychain）。项目层 = 连接态 server-info workspaces
// 优先 + 历史会话 directory 去重兜底，默认上次使用。

/// 设备列表（云端沙盒 + 已配对 Mac；同 deviceSid 去重，最近连接优先）
enum DeviceDirectory {
    static func machines() -> [DeviceOption] {
        var seenSids: Set<String> = []
        var macs: [DeviceOption] = []
        let relayServers = ServerRegistry.servers
            .filter { $0.relay != nil }
            .sorted { ($0.lastConnectedAt ?? .distantPast) > ($1.lastConnectedAt ?? .distantPast) }
        for server in relayServers {
            let sid = server.relay?.deviceSid ?? server.id
            guard seenSids.insert(sid).inserted else { continue }
            macs.append(DeviceOption(id: server.id, name: server.displayName, kind: .pairedMac))
        }
        return [.cloudSandbox] + macs
    }
}

/// 项目选项（新建会话项目层候选）
struct ProjectOption: Identifiable, Equatable {
    var path: String
    var label: String
    var lastUsedAt: Date?

    var id: String { path }
    /// 展示名：label 优先，回退路径末段
    var displayName: String {
        if !label.isEmpty { return label }
        return path.split(separator: "/").last.map(String.init) ?? path
    }
}

/// 项目清单：连接态 server-info workspaces 优先；历史会话 directory 去重合并；
/// 按最近使用（历史会话最新 updatedAt / workspaces 保持服务端序）排序。
enum ProjectDirectory {
    static func projects(store: any ConversationStore, session: AppSession) async -> [ProjectOption] {
        var byPath: [String: ProjectOption] = [:]
        var order: [String] = []

        func register(path: String, label: String?, lastUsedAt: Date?) {
            let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            if var existing = byPath[trimmed] {
                if existing.lastUsedAt == nil || (lastUsedAt ?? .distantPast) > (existing.lastUsedAt ?? .distantPast) {
                    existing.lastUsedAt = lastUsedAt
                    if let label, !label.isEmpty, existing.label.isEmpty { existing.label = label }
                }
                byPath[trimmed] = existing
            } else {
                byPath[trimmed] = ProjectOption(path: trimmed, label: label ?? "", lastUsedAt: lastUsedAt)
                order.append(trimmed)
            }
        }

        // ① 连接态：server-info workspaces（服务端权威序；connection 为 @MainActor，
        // serverInfo 是连接成功后的只读快照，经主线程读取）
        let workspaces = await MainActor.run { [weak session] in
            session?.connection.serverInfo?.workspaces ?? []
        }
        for workspace in workspaces {
            register(path: workspace.path, label: workspace.label, lastUsedAt: nil)
        }
        // ② 历史会话 directory 去重（最近使用时间随会话）
        let conversations = await store.conversations()
        for conversation in conversations {
            register(path: conversation.directory, label: nil, lastUsedAt: conversation.updatedAt)
        }
        // ③ 上次使用过的项目即使不在两源内也补入（冷启动恢复口径）
        for path in NewSessionContextStore.knownProjects() {
            register(path: path, label: nil, lastUsedAt: nil)
        }

        return order.compactMap { byPath[$0] }
            .sorted { ($0.lastUsedAt ?? .distantPast) > ($1.lastUsedAt ?? .distantPast) }
    }
}

// MARK: - 机器 × 项目组合持久化（UserDefaults；冷启动恢复上次上下文）

struct NewSessionContext: Codable, Equatable {
    var machineID: String = DeviceOption.cloudSandbox.id
    var projectPath: String = ""   // "" = 未绑定（纯对话）
}

enum NewSessionContextStore {
    private static let lastKey = "newsession.context.last.v1"
    private static let combosKey = "newsession.context.combos.v1"   // machineID → projectPath
    private static let projectsKey = "newsession.context.projects.v1" // 已用项目路径（冷启动兜底源）

    static func loadLast() -> NewSessionContext {
        guard let data = UserDefaults.standard.data(forKey: lastKey),
              let context = try? JSONDecoder().decode(NewSessionContext.self, from: data) else {
            return NewSessionContext()
        }
        return context
    }

    /// 记录一次选择：全量上下文 + 机器→项目组合 + 项目路径簿
    static func save(_ context: NewSessionContext) {
        if let data = try? JSONEncoder().encode(context) {
            UserDefaults.standard.set(data, forKey: lastKey)
        }
        var combos = combos()
        combos[context.machineID] = context.projectPath
        if let data = try? JSONEncoder().encode(combos) {
            UserDefaults.standard.set(data, forKey: combosKey)
        }
        if !context.projectPath.isEmpty {
            var projects = knownProjects()
            if !projects.contains(context.projectPath) {
                projects.append(context.projectPath)
                UserDefaults.standard.set(projects, forKey: projectsKey)
            }
        }
    }

    /// 该机器上次使用的项目（切机器时回填；无记录回退全局上次）
    static func lastProject(forMachine machineID: String) -> String? {
        combos()[machineID] ?? (loadLast().machineID == machineID ? loadLast().projectPath : nil)
    }

    static func knownProjects() -> [String] {
        UserDefaults.standard.stringArray(forKey: projectsKey) ?? []
    }

    private static func combos() -> [String: String] {
        guard let data = UserDefaults.standard.data(forKey: combosKey),
              let combos = try? JSONDecoder().decode([String: String].self, from: data) else {
            return [:]
        }
        return combos
    }
}

// MARK: - 执行目标持久化（项 6：per-conversation + 全局默认）

enum ExecutionTargetStore {
    private static func key(for conversationID: String) -> String {
        "chat.target.\(conversationID)"
    }
    private static let defaultKey = "chat.target.default.v1"

    /// 该会话的执行目标；未单独选择过回退全局默认；再无则云端沙盒
    static func target(for conversationID: String) -> DeviceOption {
        if let data = UserDefaults.standard.data(forKey: key(for: conversationID)),
           let option = try? JSONDecoder().decode(DeviceOption.self, from: data) {
            return option
        }
        if let data = UserDefaults.standard.data(forKey: defaultKey),
           let option = try? JSONDecoder().decode(DeviceOption.self, from: data),
           machines().contains(where: { $0.id == option.id }) {
            return option
        }
        return .cloudSandbox
    }

    static func setTarget(_ option: DeviceOption, conversationID: String) {
        if let data = try? JSONEncoder().encode(option) {
            UserDefaults.standard.set(data, forKey: key(for: conversationID))
            UserDefaults.standard.set(data, forKey: defaultKey)
        }
    }

    /// 目标候选需与设备列表对齐（已删除的配对机器不再作为可选/回退值）
    static func machines() -> [DeviceOption] { DeviceDirectory.machines() }
}

// MARK: - 上下文胶囊（账号 / 机器 / 项目；⇕ 弹出候选，44px 热区）

struct ContextCapsule: View {
    let icon: String
    let text: String
    var identifier: String
    var tint: Color = T.text2

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 11))
                .foregroundColor(tint)
            Text(text)
                .font(T.font(11.5, .medium))
                .foregroundColor(T.text)
                .lineLimit(1)
            Image(systemName: "chevron.up.chevron.down")
                .font(.system(size: 8, weight: .semibold))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp2)
        .frame(minHeight: 44)
        .background(T.bgInput)
        .clipShape(Capsule())
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - 屏 03-P 项目选择页（搜索 + 不指定 + 最近使用 + 全部项目可折叠组；spec 口径）

struct ProjectPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    /// Binding：候选清单与加载态必须读活值——`let` 值传递在呈现时点捕获的是
    /// 陈旧视图快照（门禁实证：远端数据齐备仍渲染空态/载入态，且呈现后不再重评）
    @Binding var projects: [ProjectOption]
    let selectedPath: String
    @Binding var isLoading: Bool
    let onPick: (ProjectOption?) -> Void   // nil = 不指定（纯对话）

    @State private var query = ""
    @State private var allExpanded = true

    private var matched: [ProjectOption] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return projects }
        return projects.filter {
            $0.displayName.localizedCaseInsensitiveContains(trimmed)
                || $0.path.localizedCaseInsensitiveContains(trimmed)
        }
    }

    /// 最近使用 ≤5 条（QoderWork「最近使用的目录」口径）
    private var recents: [ProjectOption] {
        Array(matched.filter { $0.lastUsedAt != nil }.prefix(5))
    }

    private var allProjects: [ProjectOption] {
        matched.filter { option in !recents.contains(where: { $0.path == option.path }) }
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("选择工作目录").font(T.font(17, .bold)).foregroundColor(T.text)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Text("取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("03-picker-cancel")
            }
            .padding(.horizontal, T.sp4)

            if isLoading {
                CenterLoadingView(text: "正在载入项目…").frame(maxHeight: .infinity)
            } else if matched.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                SearchField(text: $query, placeholder: String(localized: "搜索项目或路径…"), identifier: "03-picker-search")
                noneRow
                if !recents.isEmpty {
                    sectionTitle("最近使用")
                    ForEach(Array(recents.enumerated()), id: \.element.id) { index, option in
                        projectRow(option, index: index, identifierPrefix: "03-picker-recent")
                    }
                }
                if !allProjects.isEmpty {
                    Button {
                        withAnimation(.easeOut(duration: 0.2)) { allExpanded.toggle() }
                    } label: {
                        HStack(spacing: T.sp2) {
                            sectionTitle("全部项目")
                            Spacer()
                            Image(systemName: allExpanded ? "chevron.down" : "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(T.text3)
                                .frame(width: 24, height: 24)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("03-picker-group-all")
                    if allExpanded {
                        ForEach(Array(allProjects.enumerated()), id: \.element.id) { index, option in
                            projectRow(option, index: index, identifierPrefix: "03-picker-row")
                        }
                    }
                }
                Color.clear.frame(height: T.sp4)
            }
            .padding(T.sp4)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    /// 「不指定（纯对话）」置顶独立行（Quest「轻量任务可不绑定项目」口径）
    private var noneRow: some View {
        Button {
            onPick(nil)
        } label: {
            HStack(spacing: T.sp3) {
                Image(systemName: "circle.dashed")
                    .font(.system(size: 16))
                    .foregroundColor(selectedPath.isEmpty ? T.accentText : T.text3)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text("不指定（纯对话）")
                        .font(T.font(14.5, .semibold))
                        .foregroundColor(T.text)
                    Text("不绑定项目，产物以文件卡呈现")
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                }
                Spacer()
                if selectedPath.isEmpty {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 48)
            .background(selectedPath.isEmpty ? T.accentDim : T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(
                selectedPath.isEmpty ? T.accent.opacity(0.4) : T.border, lineWidth: 1))
        }
        .buttonStyle(PressableButtonStyle())
        .accessibilityIdentifier("03-picker-none")
    }

    private func projectRow(_ option: ProjectOption, index: Int, identifierPrefix: String) -> some View {
        let selected = option.path == selectedPath
        return Button {
            onPick(option)
        } label: {
            HStack(spacing: T.sp3) {
                Image(systemName: "folder")
                    .font(.system(size: 16))
                    .foregroundColor(selected ? T.accentText : T.text3)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.displayName)
                        .font(T.font(14.5, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    Text(option.path)
                        .font(T.mono(11))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: 0)
                if let lastUsedAt = option.lastUsedAt {
                    Text(Self.relative.localizedString(for: lastUsedAt, relativeTo: Date()))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 48)
            .background(selected ? T.accentDim : T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(
                selected ? T.accent.opacity(0.4) : T.border, lineWidth: 1))
        }
        .buttonStyle(PressableButtonStyle())
        .accessibilityIdentifier("\(identifierPrefix)-\(index)")
    }

    private var emptyState: some View {
        VStack(spacing: T.sp2) {
            Image(systemName: "folder.badge.questionmark")
                .font(.system(size: 24))
                .foregroundColor(T.text3)
                .frame(width: 56, height: 56)
                .background(T.bgInput)
                .clipShape(Circle())
            Text(query.isEmpty ? "暂无可用项目" : "没有匹配的项目")
                .font(T.font(14, .bold))
                .foregroundColor(T.text)
            Text(query.isEmpty
                 ? "连接桌面端后同步 workspaces，或检查桌面端 --workspace 后重试"
                 : "换个关键词试试（匹配项目名或路径）")
                .font(T.font(11.5))
                .foregroundColor(T.text3)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("03-picker-empty")
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title)
            .font(T.font(13, .semibold))
            .foregroundColor(T.text3)
    }

    static let relative: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()
}

// MARK: - 引用文件选择器（G-024：新建会话「引用文件 @」数据源 = FileStore）

struct ReferenceFilePickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: any FileStore
    let onPick: (FileNode) -> Void

    @State private var query = ""
    @State private var nodes: [FileNode] = []
    @State private var isLoading = true

    /// 本地过滤（服务端 searchFiles 的服务端聚合由文件 Tab 承载，此处为工作区树内选择）
    private var matched: [FileNode] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nodes }
        return nodes.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed)
                || $0.path.localizedCaseInsensitiveContains(trimmed)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("引用文件").font(T.font(17, .bold)).foregroundColor(T.text)
                Spacer()
                Button { dismiss() } label: {
                    Text("取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("03-filepicker-cancel")
            }
            .padding(.horizontal, T.sp4)

            if isLoading {
                CenterLoadingView(text: "正在读取工作区…").frame(maxHeight: .infinity)
            } else if matched.isEmpty {
                VStack(spacing: T.sp2) {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 24))
                        .foregroundColor(T.text3)
                        .frame(width: 56, height: 56)
                        .background(T.bgInput)
                        .clipShape(Circle())
                    Text("没有匹配的文件")
                        .font(T.font(14, .bold))
                        .foregroundColor(T.text)
                    Text("换个关键词试试（匹配文件名或路径）")
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("03-filepicker-empty")
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: T.sp1) {
                        ForEach(Array(matched.enumerated()), id: \.element.id) { index, node in
                            Button {
                                onPick(node)
                            } label: {
                                HStack(spacing: T.sp2) {
                                    Image(systemName: node.isDirectory ? "folder" : "doc")
                                        .font(.system(size: 13))
                                        .foregroundColor(T.text3)
                                        .frame(width: 26)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(node.name)
                                            .font(T.font(14, .medium))
                                            .foregroundColor(T.text)
                                            .lineLimit(1)
                                        Text(node.path)
                                            .font(T.mono(10.5))
                                            .foregroundColor(T.text3)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal, T.sp3)
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("03-filepicker-row-\(index)")
                        }
                    }
                    .padding(T.sp4)
                }
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .task {
            // 展平文件树（文件优先、上限 120 项）；演示态 MockFileStore / 连接态 RemoteFileStore 同构
            var flattened: [FileNode] = []
            func walk(_ items: [FileNode], depth: Int) {
                guard flattened.count < 120, depth < 6 else { return }
                for node in items {
                    if flattened.count >= 120 { return }
                    if !node.isDirectory { flattened.append(node) }
                    if let children = node.children { walk(children, depth: depth + 1) }
                }
            }
            let tree = await store.fileTree()
            walk(tree, depth: 0)
            nodes = flattened
            isLoading = false
        }
    }
}

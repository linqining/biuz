import SwiftUI

/// 屏 04 · 会话列表（Tab「会话」根）
/// v3 纠偏增量：行长按菜单（重命名 / 标记未读 / 复制会话 ID）与「已归档」分区
/// （连接态 listArchivedTasks + unarchiveTask；演示态 rename/markUnread 走本地 mock）。
/// 项 5 增量（Qoder 对照屏 1）：顶部来源过滤 chips（全部 / 我的 Mac / 云端沙盒，
/// 数据复用设备列表口径）；会话按项目/工作区分组（📁 项目名可折叠组）优先于日期分组，
/// 未绑定项目的会话保持日期分组兜底。既有 04-row-* / 04-empty 测试标识不变。
struct ConversationListView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    @State private var conversations: [Conversation] = []
    @State private var archivedConversations: [Conversation] = []
    @State private var query = ""
    @State private var isLoading = true
    @State private var showNewSheet = false
    @State private var showArchived = false
    @State private var renameTarget: Conversation?
    @State private var renameText = ""
    /// 来源过滤 chips（持久化；默认「全部」= 既有行为，e2e 不受影响）
    @State private var sourceFilter: SourceFilter = Self.loadSourceFilter()
    /// 项目分组折叠态（组名键控）
    @State private var collapsedGroups: Set<String> = []

    /// 来源过滤口径（项 5）：all=全部（默认）；mac=已配对桌面端（局域网/云中继，source=="mac"）；
    /// cloud=云端沙盒（source=="cloud"，BiuZ 尚无云端会话数据源，选中显示对应空态提示）
    enum SourceFilter: String, CaseIterable, Identifiable {
        case all, mac, cloud
        var id: String { rawValue }
        var label: String {
            switch self {
            case .all: return "全部"
            case .mac: return "我的 Mac"
            case .cloud: return "云端沙盒"
            }
        }
        var icon: String? {
            switch self {
            case .all: return nil
            case .mac: return "laptopcomputer"
            case .cloud: return "cloud.fill"
            }
        }
        var identifier: String { "04-chip-source-\(rawValue)" }
    }

    static let sourceFilterKey = "list.sourceFilter.v1"

    static func loadSourceFilter() -> SourceFilter {
        SourceFilter(rawValue: UserDefaults.standard.string(forKey: sourceFilterKey) ?? "") ?? .all
    }

    private var pinned: [Conversation] { filtered.filter(\.isPinned) }
    private var timeline: [Conversation] { filtered.filter { !$0.isPinned } }
    private var archived: [Conversation] {
        let base = showArchived ? archivedConversations : []
        guard !query.isEmpty else { return base }
        return base.filter {
            $0.title.localizedCaseInsensitiveContains(query)
        }
    }

    /// G-018 服务端全文命中（搜索防抖后拉取；命中含未加载进内存的历史会话）
    @State private var remoteHits: [Conversation] = []
    @State private var searchDebounce: Task<Void, Never>?

    private var filtered: [Conversation] {
        let bySource = conversations.filter { conversation in
            switch sourceFilter {
            case .all: return true
            case .mac: return conversation.source == "mac"
            case .cloud: return conversation.source == "cloud"
            }
        }
        guard !query.isEmpty else { return bySource }
        let local = bySource.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || $0.summary.localizedCaseInsensitiveContains(query)
        }
        // 服务端命中合并（按 id 去重；断连/失败 remoteHits 为空 → 纯本地过滤，不崩）
        var seen = Set(local.map(\.id))
        let merged = local + remoteHits.filter { !seen.contains($0.id) }
        return merged
    }

    /// 项目分组（项 5：优先于日期分组）——组内按最近活跃降序，组间按组内最新会话降序
    private var projectGroups: [(name: String, items: [Conversation])] {
        let groups = Dictionary(grouping: timeline.filter { $0.projectName != nil }) {
            $0.projectName ?? ""
        }
        return groups
            .map { (name: $0.key, items: $0.value.sorted { $0.updatedAt > $1.updatedAt }) }
            .sorted {
                ($0.items.map(\.updatedAt).max() ?? .distantPast)
                    > ($1.items.map(\.updatedAt).max() ?? .distantPast)
            }
    }

    /// 归属未知的会话（要求 4：sessions-index 行未携带工作区字段时数据无法判定归属，
    /// 归「其它」组而非丢弃/挤进某个项目组；演示态未绑定项目的会话同样归此组）
    private var ungrouped: [Conversation] { timeline.filter { $0.projectName == nil } }
    /// G-018：正在派生的会话（fork 成功后打开新会话）
    @State private var forking = false
    /// G-017：移入分组目标会话（弹组名输入）
    @State private var groupTarget: Conversation?
    @State private var newGroupName = ""

    /// 来源过滤后为空（仅非「全部」档可能；给可行动提示而非静默空白）
    private var isSourceFilteredEmpty: Bool {
        sourceFilter != .all && filtered.isEmpty && !conversations.isEmpty
    }

    /// 连接态（远端 store）才提供已归档分区入口（演示态归档行即刻消失，行为不变）
    private var supportsArchiveSection: Bool { store.isReadOnly }

    var body: some View {
        Group {
            if isLoading {
                CenterLoadingView(text: "正在同步会话…").accessibilityIdentifier("04-loading-center")
            } else if conversations.isEmpty {
                EmptyStateView(
                    icon: "bubble.left.and.text.bubble.right",
                    title: "还没有会话",
                    detail: "从任务看板下发任务，或直接新建一个会话",
                    cta: "新建会话", ctaAction: { showNewSheet = true },
                    ctaIdentifier: "04-act-new-empty")
                .accessibilityIdentifier("04-empty")
            } else {
                list
            }
        }
        .background(T.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("会话").font(T.font(17, .bold)).foregroundColor(T.text)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showNewSheet = true
                } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 17))
                        .foregroundColor(T.text)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("04-act-new")
            }
        }
        .sheet(isPresented: $showNewSheet) {
            NewConversationSheet(onCreated: { conversation in
                showNewSheet = false
                router.openChat(conversationID: conversation.id)
            })
        }
        .alert("重命名会话", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } })) {
            TextField("新名称", text: $renameText)
            Button("取消", role: .cancel) { renameTarget = nil }
            Button("保存") {
                if let target = renameTarget {
                    let newTitle = renameText
                    Task { await store.renameConversation(newTitle, conversationID: target.id) }
                }
                renameTarget = nil
            }
} message: {
            Text("新名称会同步到桌面端任务列表")
        }
        // G-017：移入分组（组名输入 → createTaskGroup 幂等建组 + applyGroupedTaskViewOrder 入组）
        .alert("移入分组", isPresented: Binding(
            get: { groupTarget != nil },
            set: { if !$0 { groupTarget = nil } })) {
            TextField("分组名称", text: $newGroupName)
            Button("取消", role: .cancel) { groupTarget = nil }
            Button("移入") {
                guard let target = groupTarget else { return }
                let name = newGroupName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty {
                    Task {
                        _ = await store.createTaskGroup(named: name, color: nil)
                        await store.applyGroupedTaskViewOrder(
                            groupID: nil, order: [(target.id, name)])
                    }
                }
                groupTarget = nil
            }
        } message: {
            Text("输入桌面端分组名称；分组结构以桌面端同步为准")
        }
        .task { await reload() }
        .refreshable { await reload(showSpinner: true) }
        // id 绑定 store 实例：连接成功后数据源 mock→远端 切换时重订阅（id 为常量会一直挂在旧流上）。
        // 切换时必须主动 reload 一次：远端订阅（subscribeSessionsIndexV4）只在 conversations() 内建立，
        // 而首个 .task { reload() } 仅在首次挂载（当时还是 mock）执行——若只 observe 不拉取，
        // 用户停留在列表页时远端会话永不加载（e2e 门禁第 1 轮 test12 实证）。
        .task(id: ObjectIdentifier(store)) {
            await reload()
            for await event in store.observeConversations() {
                if case .conversationsReplaced(let list) = event { conversations = list }
                if case .conversationUpdated(let updated) = event {
                    if let index = conversations.firstIndex(where: { $0.id == updated.id }) {
                        conversations[index] = updated
                    }
                }
            }
        }
    }

    private var list: some View {
        List {
            Section {
                SearchField(text: $query, placeholder: String(localized: "搜索会话"), identifier: "04-search")
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: T.sp1, leading: T.sp4, bottom: 0, trailing: T.sp4))
                    .listRowSeparator(.hidden)
                    .onChange(of: query) { _, newValue in
                        scheduleSearch(newValue)
                    }
                sourceChips
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: T.sp1, leading: T.sp4, bottom: 0, trailing: T.sp4))
                    .listRowSeparator(.hidden)
            }
            // 来源过滤为空的显式提示（不静默空白；默认「全部」不触发）
            if isSourceFilteredEmpty {
                Section {
                    HStack(spacing: T.sp2) {
                        Image(systemName: sourceFilter == .cloud ? "cloud" : "laptopcomputer")
                            .font(.system(size: 13))
                            .foregroundColor(T.text3)
                        Text(sourceFilter == .cloud
                             ? "暂无云端沙盒会话 · 云端任务源接入后将在此聚合"
                             : "暂无「我的 Mac」会话 · 连接桌面端后同步")
                            .font(T.font(12.5))
                            .foregroundColor(T.text3)
                        Spacer()
                    }
                    .frame(minHeight: 44)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("04-filter-empty")
                }
            }
            if !pinned.isEmpty {
                Section {
                    ForEach(pinned) { item in row(item) }
                } header: {
                    sectionHeader("置顶")
                }
            }
            // 项目/工作区分组（项 5：📁 项目名 + 可折叠 chevron，Qoder 对照屏 1 口径）。
            // 要求 4：分组键 = 每个会话自带的工作区/项目字段，与桌面侧栏项目全集对齐
            ForEach(projectGroups, id: \.name) { group in
                Section {
                    projectGroupHeader(group)
                    if !collapsedGroups.contains(group.name) {
                        ForEach(group.items) { item in row(item) }
                    }
                }
            }
            // 归属未知（数据无法判定）→「其它」可折叠组，不丢弃（要求 4）
            if !ungrouped.isEmpty {
                Section {
                    projectGroupHeader((name: String(localized: "其它"), items: ungrouped))
                    if !collapsedGroups.contains(String(localized: "其它")) {
                        ForEach(ungrouped) { item in row(item) }
                    }
                }
            }
            // 已归档分区（P1-6，连接态）：入口行展开 → listArchivedTasks 只读拉取 + 取消归档
            if supportsArchiveSection {
                Section {
                    if showArchived {
                        if archived.isEmpty {
                            Text("暂无已归档会话")
                                .font(T.font(12.5))
                                .foregroundColor(T.text3)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        } else {
                            ForEach(archived) { item in archivedRow(item) }
                        }
                    }
                    Button {
                        Task {
                            showArchived.toggle()
                            if showArchived, archivedConversations.isEmpty {
                                archivedConversations = await store.archivedConversations()
                            }
                        }
                    } label: {
                        HStack(spacing: T.sp2) {
                            Image(systemName: showArchived ? "chevron.down" : "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(T.text3)
                                .frame(width: 16)
                            Text(showArchived ? "收起已归档" : "已归档会话")
                                .font(T.font(13, .medium))
                                .foregroundColor(T.text2)
                            Spacer()
                        }
                        .frame(minHeight: 44)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("04-act-archived")
                } header: {
                    sectionHeader("归档")
                }
            }
            Color.clear.frame(height: 80)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets())
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
    }

    /// 来源过滤 chips（全部 / 我的 Mac / 云端沙盒；选中深色实底胶囊，Qoder 屏 1 口径）。
    /// G-010：「云端沙盒」档在数据源中尚无 source=="cloud" 会话时置灰禁用（BiuZ 云端
    /// 执行端尚未接入会话通道），避免呈现静默空档；云端源接入后该档自动恢复可选。
    private var sourceChips: some View {
        HStack(spacing: T.sp2) {
            ForEach(SourceFilter.allCases) { filter in
                let unavailable = filter == .cloud && !conversations.contains { $0.source == "cloud" }
                Button {
                    guard !unavailable else { return }
                    withAnimation(.easeOut(duration: 0.18)) {
                        sourceFilter = filter
                        UserDefaults.standard.set(filter.rawValue, forKey: Self.sourceFilterKey)
                    }
                } label: {
                    HStack(spacing: 4) {
                        if let icon = filter.icon {
                            Image(systemName: icon).font(.system(size: 10))
                        }
                        Text(filter.label).font(T.font(12, .medium))
                    }
                    .foregroundColor(
                        unavailable ? T.text3.opacity(0.5)
                        : (sourceFilter == filter ? T.onAccent : T.text2))
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 44)
                    .background(sourceFilter == filter && !unavailable ? T.accent : T.bgInput)
                    .clipShape(Capsule())
                }
                .disabled(unavailable)
                .accessibilityIdentifier(filter.identifier)
                .accessibilityHint(unavailable ? "云端沙盒执行端尚未接入会话" : "")
            }
            Spacer(minLength: 0)
        }
    }

    /// 项目组头（📁 项目名 + 会话计数 + 折叠 chevron；整行可点）
    private func projectGroupHeader(_ group: (name: String, items: [Conversation])) -> some View {
        let collapsed = collapsedGroups.contains(group.name)
        return Button {
            withAnimation(.easeOut(duration: 0.2)) {
                if collapsed {
                    collapsedGroups.remove(group.name)
                } else {
                    collapsedGroups.insert(group.name)
                }
            }
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "folder.fill")
                    .font(.system(size: 12))
                    .foregroundColor(T.text3)
                Text(group.name)
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Text("\(group.items.count)")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                Spacer()
                Image(systemName: collapsed ? "chevron.right" : "chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(T.text3)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 4, leading: T.sp4, bottom: 0, trailing: T.sp4))
        .listRowSeparator(.hidden)
        .accessibilityIdentifier("04-group-\(group.name)")
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(T.font(11, .semibold))
            .foregroundColor(T.text3)
    }

    private func row(_ conversation: Conversation) -> some View {
        Button {
            router.openChat(conversationID: conversation.id)
        } label: {
            ConversationRowView(conversation: conversation)
        }
        .buttonStyle(PressableButtonStyle())
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 4, leading: T.sp4, bottom: 4, trailing: T.sp4))
        .listRowSeparator(.hidden)
        .accessibilityIdentifier("04-row-\(conversation.id)")
        .contextMenu {
            Button {
                renameText = conversation.title
                renameTarget = conversation
            } label: {
                Label("重命名", systemImage: "pencil")
            }
            .accessibilityIdentifier("04-ctx-rename-\(conversation.id)")
            Button {
                Task { await store.markUnread(conversationID: conversation.id) }
            } label: {
                Label("标记为未读", systemImage: "envelope.badge")
            }
            .accessibilityIdentifier("04-ctx-unread-\(conversation.id)")
            Button {
                UIPasteboard.general.string = conversation.id
            } label: {
                Label("复制会话 ID", systemImage: "doc.on.doc")
            }
            // G-018：派生会话（forkAssistant session 类放行分支；成功后打开新会话）
            Button {
                Task {
                    forking = true
                    if let newID = await store.forkConversation(conversation.id) {
                        showNewSheet = false
                        router.openChat(conversationID: newID)
                    }
                    forking = false
                }
            } label: {
                Label("派生会话", systemImage: "arrow.triangle.branch")
            }
            .accessibilityIdentifier("04-ctx-fork-\(conversation.id)")
            // G-017：移入分组（createTaskGroup + applyGroupedTaskViewOrder 索引元数据写）
            Button {
                groupTarget = conversation
                newGroupName = ""
            } label: {
                Label("移入分组…", systemImage: "folder.badge.plus")
            }
            .accessibilityIdentifier("04-ctx-group-\(conversation.id)")
            Button(role: .destructive) {
                Task { await store.setArchived(true, conversationID: conversation.id) }
            } label: {
                Label("归档", systemImage: "archivebox")
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                Task { await store.setArchived(true, conversationID: conversation.id) }
            } label: {
                Label("归档", systemImage: "archivebox")
            }
            .tint(T.borderStrong)
            .accessibilityIdentifier("04-rowact-archive-\(conversation.id)")
            Button {
                Task { await store.setPinned(!conversation.isPinned, conversationID: conversation.id) }
            } label: {
                Label(conversation.isPinned ? "取消置顶" : "置顶",
                      systemImage: conversation.isPinned ? "pin.slash" : "pin")
            }
            .tint(T.orange)
            .accessibilityIdentifier("04-rowact-pin-\(conversation.id)")
        }
    }

    /// 已归档行：滑块「取消归档」（unarchiveTask）+ 回到主列表
    private func archivedRow(_ conversation: Conversation) -> some View {
        Button {
            Task {
                await store.setArchived(false, conversationID: conversation.id)
                archivedConversations.removeAll { $0.id == conversation.id }
            }
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "archivebox")
                    .font(.system(size: 14))
                    .foregroundColor(T.text3)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(conversation.title)
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                    Text("点击取消归档并恢复到列表")
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                }
                Spacer()
            }
            .padding(12)
            .background(T.bgCard.opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .buttonStyle(PressableButtonStyle())
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 4, leading: T.sp4, bottom: 4, trailing: T.sp4))
        .listRowSeparator(.hidden)
        .accessibilityIdentifier("04-archivedrow-\(conversation.id)")
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                Task {
                    await store.setArchived(false, conversationID: conversation.id)
                    archivedConversations.removeAll { $0.id == conversation.id }
                }
            } label: {
                Label("取消归档", systemImage: "tray.and.arrow.up")
            }
            .tint(T.accent)
            .accessibilityIdentifier("04-archact-unarchive-\(conversation.id)")
        }
    }

    /// G-018：搜索防抖 350ms → 连接态拉服务端全文命中；空查询清空
    private func scheduleSearch(_ value: String) {
        searchDebounce?.cancel()
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            remoteHits = []
            return
        }
        searchDebounce = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            let hits = await store.searchSessions(trimmed)
            if !Task.isCancelled {
                remoteHits = hits
            }
        }
    }

    private func reload(showSpinner: Bool = false) async {
        if showSpinner == false, (try? await Task.sleep(nanoseconds: 350_000_000)) != nil { }
        conversations = await store.conversations()
        isLoading = false
    }

    static func groupName(of date: Date) -> String {
        if Calendar.current.isDateInToday(date) { return "今天" }
        if Calendar.current.isDateInYesterday(date) { return "昨天" }
        return "更早"
    }
}

/// 会话行：40px 头像 + 标题 + 摘要 + 工作流迷你轨道（G-007）+ 时间 + 未读徽章 + 运行中胶囊
struct ConversationRowView: View {
    let conversation: Conversation

    private var timeText: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.locale = Locale(identifier: "zh_CN")
        let delta = conversation.updatedAt.timeIntervalSinceNow
        if abs(delta) < 60 { return "刚刚" }
        if Calendar.current.isDateInToday(conversation.updatedAt) {
            return conversation.updatedAt.formatted(date: .omitted, time: .shortened)
        }
        return formatter.localizedString(fromTimeInterval: delta)
    }

    var body: some View {
        HStack(spacing: T.sp3) {
            avatar
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: T.sp2) {
                    Text(conversation.title)
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    if conversation.isRunning {
                        StatusPill(text: "运行中", kind: .run, compact: true)
                    }
                    Spacer(minLength: 0)
                    Text(timeText)
                        .font(T.font(10.5, .medium))
                        .foregroundColor(T.text3)
                }
                HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
                    Text(conversation.summary)
                        .font(T.font(12.5))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    if conversation.unreadCount > 0 {
                        TabBadge(count: conversation.unreadCount, color: T.orangeBright)
                    }
                }
                // G-007：workflowActivity 迷你轨道（随 sessions-index 帧实时更新；
                // 无 run 时 nil → 不渲染任何占位）
                if let activity = conversation.workflowActivity {
                    SessionWorkflowTrackView(activity: activity)
                        .padding(.top, 1)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
    }

    @ViewBuilder
    private var avatar: some View {
        if conversation.taskProgress == 1.0 {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 22))
                .foregroundColor(T.accentText)
                .frame(width: 40, height: 40)
                .background(T.accentDim)
                .clipShape(RoundedRectangle(cornerRadius: T.rM - 2))
                .overlay(alignment: .bottomTrailing) { runningDot }
        } else {
            AgentAvatar(size: 40)
                .overlay(alignment: .bottomTrailing) { runningDot }
        }
    }

    /// 行级进度点（要求 5 降级面：会话摘要行粒度——workflow/会话运行中即显示蓝点；
    /// 详情页数据充足时由 WorkflowPanelView 承载完整节点链）
    private var runningDot: some View {
        Group {
            if conversation.isRunning {
                Circle()
                    .fill(T.blue)
                    .frame(width: 10, height: 10)
                    .overlay(Circle().stroke(T.bgCard, lineWidth: 2))
            }
        }
    }
}

// MARK: - G-007 会话行工作流迷你轨道（桌面侧栏 workflowRunLine 同构）
// 形态：Workflow 图标 + 站点灯 ●●●○○（STATUS_DOT 四态词汇）+ 当前阶段名 +
// 「N agents working」+ 并行双线段 + 「+N」溢出。截断口径：每会话最多画 2 条 run 行
// （桌面 WORKFLOW_RUN_LINE_MAX_LINES）；站点 ≤6 全画，更多以运行站为中心保留 ±2
// 共 5 站 +「+n」尾（桌面 foldWorkflowRunRail 固定窗口）。双线段简化口径：
// 站 i 的 alongside 含 i-1 即视为与前一站并行（桌面按折带连通分量算轨道号，移动端
// 迷你轨道只画一层并行关系）。

/// 迷你轨道站点（折叠窗口计算后的渲染模型）
struct WorkflowRailStation: Equatable {
    var name: String
    var status: WorkflowStepStatus
    /// 控制流是否到过这一站（running/done/failed 都算；桌面 isWorkflowRunStationReached）
    var reached: Bool
    /// 本站与前一站并行（双线段）
    var twin: Bool
}

/// 站点折叠（≤6 全画；更多以运行站为中心保留 ±2 共 5 站，其余合成「+n」尾）
func foldWorkflowRail(_ phases: [SessionWorkflowPhase]) -> ([WorkflowRailStation], Int) {
    guard !phases.isEmpty else { return ([], 0) }
    let all: [WorkflowRailStation] = phases.enumerated().map { index, phase in
        WorkflowRailStation(
            name: phase.name,
            status: phase.status,
            reached: phase.status != .pending,
            twin: index > 0 && phase.alongside.contains(index - 1))
    }
    let maxStations = 6
    guard all.count > maxStations else { return (all, 0) }
    var anchor = all.firstIndex { $0.status == .running } ?? -1
    if anchor < 0 {
        for index in stride(from: all.count - 1, through: 0, by: -1) where all[index].reached {
            anchor = index
            break
        }
    }
    if anchor < 0 { anchor = 0 }
    let lower = max(0, anchor - 2)
    let upper = min(all.count - 1, anchor + 2)
    let window = Array(all[lower...upper])
    return (window, all.count - window.count)
}

/// 单条 run 迷你轨道行
struct WorkflowRunRailRow: View {
    let run: SessionWorkflowRunSummary

    var body: some View {
        let (stations, hidden) = foldWorkflowRail(run.phases)
        return HStack(spacing: 5) {
            Image(systemName: "flowchart.fill")
                .font(.system(size: 10))
                .foregroundColor(T.violet)
            // 站点灯（●●●○○：running 蓝 / done 绿 / failed 红 / pending 灰空心；并行双线段）
            HStack(spacing: 0) {
                ForEach(Array(stations.enumerated()), id: \.offset) { index, station in
                    if index > 0 {
                        RailConnector(twin: station.twin)
                    }
                    stationDot(station)
                }
                if hidden > 0 {
                    Text("+\(hidden)")
                        .font(T.mono(9, .semibold))
                        .foregroundColor(T.text3)
                        .padding(.leading, 3)
                }
            }
            // 当前阶段名（run 名缺席时桌面画隐含站「Workflow」，此处以 run 状态胶囊词替代，
            // 阶段名优先）
            Text(run.currentPhase ?? run.name ?? String(localized: "工作流"))
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .lineLimit(1)
            if run.agentsWorking > 0 {
                Text("\(run.agentsWorking) agents working")
                    .font(T.mono(9.5, .medium))
                    .foregroundColor(T.blue)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 16)
    }

    /// 站点灯圆点（STATUS_DOT 四态）
    @ViewBuilder
    private func stationDot(_ station: WorkflowRailStation) -> some View {
        switch station.status {
        case .running:
            Circle().fill(T.blue).frame(width: 7, height: 7)
        case .done:
            Circle().fill(T.accent).frame(width: 7, height: 7)
        case .failed:
            Circle().fill(T.red).frame(width: 7, height: 7)
        case .pending:
            Circle().strokeBorder(T.borderStrong, lineWidth: 1.2).frame(width: 7, height: 7)
        }
    }

    /// 站间连线（并行站画双细线段）
    struct RailConnector: View {
        let twin: Bool
        var body: some View {
            if twin {
                VStack(spacing: 1.5) {
                    Rectangle().fill(T.borderStrong).frame(height: 1)
                    Rectangle().fill(T.borderStrong).frame(height: 1)
                }
                .frame(width: 7)
            } else {
                Rectangle().fill(T.border).frame(width: 7, height: 1.2)
            }
        }
    }

    nonisolated static func fold(_ phases: [SessionWorkflowPhase]) -> ([WorkflowRailStation], Int) {
        foldWorkflowRail(phases)
    }
}

/// G-007 会话行迷你轨道容器（最多 2 条 run 行 + 溢出计数；随 sessions-index 帧实时更新）
struct SessionWorkflowTrackView: View {
    let activity: WorkflowActivitySummary

    private var visibleRuns: [SessionWorkflowRunSummary] { Array(activity.runs.prefix(2)) }
    private var overflowCount: Int { max(0, activity.runs.count - visibleRuns.count) }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(visibleRuns) { run in
                WorkflowRunRailRow(run: run)
            }
            if overflowCount > 0 {
                Text(String(format: String(localized: "+%lld 条工作流"), overflowCount))
                    .font(T.mono(9.5))
                    .foregroundColor(T.text3)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("04-workflow-track")
    }
}

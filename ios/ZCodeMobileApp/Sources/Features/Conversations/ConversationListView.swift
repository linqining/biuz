import SwiftUI

/// 屏 04 · 会话列表（Tab「会话」根）
/// v3 纠偏增量：行长按菜单（重命名 / 标记未读 / 复制会话 ID）与「已归档」分区
/// （连接态 listArchivedTasks + unarchiveTask；演示态 rename/markUnread 走本地 mock）。
/// 项 5 增量（Qoder 对照屏 1）：顶部来源过滤 chips（全部 / 我的 Mac；
/// 「云端沙盒」档 H7 隐藏——cloud 会话源接入后恢复，见 sourceChips 注释），
/// 数据复用设备列表口径；会话按项目/工作区分组（📁 项目名可折叠组）优先于日期分组，
/// 未绑定项目的会话保持日期分组兜底。既有 04-row-* / 04-empty 测试标识不变。
struct ConversationListView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    @Environment(AppSession.self) private var session
    @State private var conversations: [Conversation] = []
    @State private var archivedConversations: [Conversation] = []
    @State private var query = ""
    @State private var isLoading = true
    @State private var showNewSheet = false
    @State private var showArchived = false
    /// 归档区拉取中（26 scope 并发也需要秒级往返——拉取中显示进度而非「暂无」，
    /// 用户报障 2026-10-07「归档的会话又丢了」感知成因：假空态）
    @State private var isLoadingArchived = false
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
        /// HIDDEN(对齐修复) H7/L-4：「云端沙盒」档隐藏（无 cloud 会话数据源，恒空档；
        /// Mock seed c3/c4=cloud 移除后恒不可选）· 恢复条件：cloud 会话源接入
        static var visibleCases: [SourceFilter] { allCases.filter { $0 != .cloud } }
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
        // HIDDEN(对齐修复) H7/L-4：cloud 档已隐藏，残留持久化值（演示态点过）回退「全部」
        // · 恢复条件：cloud 会话源接入
        guard let filter = SourceFilter(rawValue: UserDefaults.standard.string(forKey: sourceFilterKey) ?? ""),
              filter != .cloud else { return .all }
        return filter
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
        let seen = Set(local.map(\.id))
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
    /// 未归集项目的会话组名（桌面侧栏「任务」组同语义）
    /// G-018：正在派生的会话（fork 成功后打开新会话）
    @State private var forking = false
    /// G-017：移入分组目标会话（弹组名输入）
    @State private var groupTarget: Conversation?
    @State private var newGroupName = ""
    /// 失败任务清理目标（桌面失败任务同款删除；确认后 zcode-task.deleteTask）
    @State private var cleanupTarget: Conversation?

    // MARK: 工作区切换器（P3-10）

    /// 待确认切换的目标工作区（confirmationDialog 确认后经 AppSession.switchWorkspace）
    @State private var switchTarget: ServerWorkspaceInfo?
    /// 切换中（Menu 禁用 + 胶囊「切换中…」，重复确认被拦）
    @State private var isSwitching = false
    /// 切换结果提示（成功/失败 toast，3s 自动清除；ChatView switchHint 同款通道）
    @State private var switchHint: String?
    @State private var switchHintClear: Task<Void, Never>?

    /// G-017 移入分组结果提示（成功/失败 3s 轻提示，showSwitchHint 同款通道）；
    /// 失败态为写面如实回传（B-3 配套：写面禁止静默），红色与 T.text3 区分
    @State private var groupHint: String?
    @State private var groupHintIsError = false
    @State private var groupHintClear: Task<Void, Never>?

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
        // G-017：移入分组（B-3 修正：全链走 moveConversationToGroup——拿 createTaskGroup
        // 回执真实 groupId、applyGroupedTaskViewOrder 按全量视图形状提交；旧实现丢弃
        // 回执、以组名充当 groupId，恒静默无效）。失败必须提示（写面禁止静默）。
        .alert("移入分组", isPresented: Binding(
            get: { groupTarget != nil },
            set: { if !$0 { groupTarget = nil } })) {
            TextField("分组名称", text: $newGroupName)
            Button("取消", role: .cancel) { groupTarget = nil }
            Button("移入") {
                guard let target = groupTarget else { return }
                let name = newGroupName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty {
                    Task { await moveConversationToGroup(target, groupName: name) }
                }
                groupTarget = nil
            }
        } message: {
            Text("输入桌面端分组名称；分组结构以桌面端同步为准")
        }
        // 失败任务清理（桌面同款确认语义：删除任务不可恢复）
        .alert(
            String(localized: "清理失败任务"),
            isPresented: Binding(
                get: { cleanupTarget != nil },
                set: { if !$0 { cleanupTarget = nil } })) {
            Button("取消", role: .cancel) { cleanupTarget = nil }
            Button("清理", role: .destructive) {
                guard let target = cleanupTarget else { return }
                Task {
                    if await store.deleteTask(target.id) {
                        conversations.removeAll { $0.id == target.id }
                    }
                }
                cleanupTarget = nil
            }
        } message: {
            Text(String(localized: "将删除失败任务「\(cleanupTarget?.title ?? "")」及其本地记录，桌面端同步删除"))
        }
        // 工作区切换确认（P3-10 §10.5：影响三面板数据面，确认层保留；主键「切换」为
        // 普通按钮——切换不丢数据且桌面任务不受影响，非 destructive）
        .confirmationDialog(
            String(localized: "切换到 \(switchTarget?.label ?? switchTarget?.path ?? "")？"),
            isPresented: Binding(
                get: { switchTarget != nil },
                set: { if !$0 { switchTarget = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "切换")) { confirmSwitch() }
                .accessibilityIdentifier("04-switcher-confirm")
            Button(String(localized: "取消"), role: .cancel) { switchTarget = nil }
        } message: {
            Text("将断开当前工作区的会话与文件面板并重连；桌面端连接保持，进行中的桌面任务不受影响。")
        }
        .task { await reload() }
        .refreshable { await reload(showSpinner: true) }
        // id 绑定 store 实例：连接成功后数据源 mock→远端 切换时重订阅（id 为常量会一直挂在旧流上）。
        // 切换时必须主动 reload 一次：远端订阅（subscribeSessionsIndexV4）只在 conversations() 内建立，
        // 而首个 .task { reload() } 仅在首次挂载（当时还是 mock）执行——若只 observe 不拉取，
        // 用户停留在列表页时远端会话永不加载（e2e 门禁第 1 轮 test12 实证）。
        .task(id: ObjectIdentifier(store)) {
            // ⑥（2026-10-06）随 store 换绑复位归档行：归档行是 store 作用域（按工作区），
            // 切换器跨工作区换 Store 后旧行不得残留——isEmpty 门只挡首展开，不覆盖换源
            archivedConversations = []
            if showArchived {
                archivedConversations = await store.archivedConversations()
            }
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
        // 搜索 + 来源 chips 固定头部（List 行外提）：List 行在过滤后行数骤减时会回收重排，
        // chips 的可访问性帧随之失准（症状：tap 云端沙盒选中后，再 tap「我的 Mac」两次均
        // 落空——门禁 Matrix test01 line202 确定性复现，连拍 frame 证实 filter 停留 cloud）。
        // 提为固定 VStack 头后元素帧稳定，identifier（04-search / 04-chip-source-*）全保留。
        VStack(spacing: 0) {
            // 工作区切换器（P3-10）：连接态且桌面工作区清单非空时渲染在列表头部
            //（未连接/清单空不渲染，演示态布局零变化）
            workspaceSwitcher
            SearchField(text: $query, placeholder: String(localized: "搜索会话"), identifier: "04-search")
                .padding(.horizontal, T.sp4)
                .padding(.top, T.sp1)
                .onChange(of: query) { _, newValue in
                    scheduleSearch(newValue)
                }
            sourceChips
                .padding(.horizontal, T.sp4)
                .padding(.top, T.sp1)
            if let groupHint {
                // G-017 移入分组结果行（成功灰/失败红，3s 自动清除）
                Text(groupHint)
                    .font(T.font(11.5))
                    .foregroundColor(groupHintIsError ? T.red : T.text3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, T.sp4)
                    .padding(.top, T.sp1)
                    .accessibilityIdentifier("04-group-hint")
            }
            List {
                // 来源过滤为空的显式提示（不静默空白；默认「全部」不触发）
            // HIDDEN(对齐修复) H7/L-4：cloud 空态分支随档位隐藏 · 恢复条件：cloud 会话源接入
            if isSourceFilteredEmpty {
                Section {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "laptopcomputer")
                            .font(.system(size: 13))
                            .foregroundColor(T.text3)
                        Text("暂无「我的 Mac」会话 · 连接桌面端后同步")
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
            // 桌面语义：未归集到项目的会话单独在「任务」组（桌面侧栏同名组；用户实测
            // 「其它」命名与桌面分区不一致）
            if !ungrouped.isEmpty {
                Section {
                    projectGroupHeader((name: String(localized: "任务"), items: ungrouped))
                    if !collapsedGroups.contains(String(localized: "任务")) {
                        ForEach(ungrouped) { item in row(item) }
                    }
                }
            }
            // 已归档分区（P1-6，连接态）：入口行展开 → listArchivedTasks 只读拉取 + 取消归档
            if supportsArchiveSection {
                Section {
                    if showArchived {
                        if isLoadingArchived {
                            HStack(spacing: T.sp2) {
                                SpinnerView(size: 12)
                                Text("正在读取已归档会话…")
                                    .font(T.font(12.5))
                                    .foregroundColor(T.text3)
                                Spacer()
                            }
                            .frame(minHeight: 44)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .accessibilityIdentifier("04-archived-loading")
                        } else if archived.isEmpty {
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
                            if showArchived {
                                isLoadingArchived = true
                                archivedConversations = await store.archivedConversations()
                                isLoadingArchived = false
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
    }

    /// 来源过滤 chips（全部 / 我的 Mac；选中深色实底胶囊，Qoder 屏 1 口径）。
    /// HIDDEN(对齐修复) H7/L-4：「云端沙盒」档整档不渲染（BiuZ 无 cloud 会话数据源，
    /// 恒空档/置灰死档，审查报告 §六 L-4）· 恢复条件：cloud 会话源接入（还原
    /// visibleCases 过滤与 cloud 空态分支即可）。
    private var sourceChips: some View {
        HStack(spacing: T.sp2) {
            ForEach(SourceFilter.visibleCases) { filter in
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
                // 选中态暴露为 selected trait（视觉深色实底的可访问性等价物；
                // 屏幕阅读器可播报，XCUITest 以 isSelected 断言持久化选中态）
                .accessibilityAddTraits(sourceFilter == filter && !unavailable ? [.isSelected] : [])
                .accessibilityHint(unavailable ? "云端沙盒执行端尚未接入会话" : "")
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: 工作区切换器（P3-10：会话列表区域入口；切换中/失败态见状态矩阵）

    /// 连接态工作区清单（⑤修复 2026-10-06 真机报障「切换器只剩 mtt_mobile」）：
    /// 不再只读 workspace-list-response 采集的桌面「当前打开」工作区——该请求非枚举源
    /// （AGENTS §6 v1.5 实证，实测清单恒 1 项），AppSession.switcherWorkspaces 已并集
    /// bootstrap.tasks 派生的跨工作区全量清单（web「所有项目目录」同源），按 path
    /// 去重、active（当前）随 serverInfo 首位；可切换性门控（C-15 canBridge/中继能力）
    /// 维持原口径不变。
    private var workspaceEntries: [ServerWorkspaceInfo] {
        session.switcherWorkspaces
    }

    @ViewBuilder
    private var workspaceSwitcher: some View {
        if case .connected = session.mode, !workspaceEntries.isEmpty {
            HStack(spacing: T.sp2) {
                switcherPill
                Spacer(minLength: 0)
                if let hint = switchHint {
                    Text(hint)
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                        .lineLimit(2)
                        .multilineTextAlignment(.trailing)
                        .accessibilityIdentifier("04-switcher-hint")
                }
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp1)
        }
    }

    /// 切换器胶囊：多工作区+中继 = 可点 Menu（上下 chevron）；单工作区/局域网 = 只读
    /// 胶囊（无 chevron，诚实不可切）；切换中 = 「切换中…」+ Spinner（重复确认被拦）
    @ViewBuilder
    private var switcherPill: some View {
        let current = session.connection.workspace
        let switchable = workspaceEntries.count > 1
            && session.connection.supportsWorkspaceSwitching
            && !isSwitching
        if isSwitching {
            switcherPillLabel(
                String(localized: "切换中…"), chevron: false, spinner: true)
                .accessibilityIdentifier("04-switcher-workspace-switching")
        } else if switchable {
            Menu {
                switcherMenuItems(currentPath: current?.path)
                Section {
                    Text("切换将重建会话/文件/任务面板")
                        .font(T.font(11))
                }
            } label: {
                switcherPillLabel(
                    String(localized: "工作区 \(current?.label ?? current?.path ?? "--")"), chevron: true, spinner: false)
            }
            .accessibilityIdentifier("04-switcher-workspace")
        } else {
            switcherPillLabel(
                String(localized: "工作区 \(current?.label ?? current?.path ?? "--")"), chevron: false, spinner: false)
                .accessibilityIdentifier("04-switcher-workspace-readonly")
        }
    }

    /// 胶囊样式（executionTargetMenu ChatView 同款：bgInput 底 + Capsule）
    private func switcherPillLabel(_ text: String, chevron: Bool, spinner: Bool) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "arrow.triangle.branch")
                .font(.system(size: 11))
            Text(text)
                .font(T.font(11.5, .medium))
                .lineLimit(1)
            if spinner {
                ProgressView()
                    .scaleEffect(0.65)
                    .frame(width: 10, height: 10)
            } else if chevron {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8, weight: .semibold))
            }
        }
        .foregroundColor(T.text2)
        .padding(.horizontal, T.sp2)
        .frame(minHeight: 32)
        .background(T.bgInput)
        .clipShape(Capsule())
    }

    /// 菜单项：当前工作区 ✓ 禁选（modelMenu 选中态先例）；其余项点击弹确认
    private func switcherMenuItems(currentPath: String?) -> some View {
        ForEach(workspaceEntries, id: \.path) { entry in
            if entry.path == currentPath {
                Button {} label: {
                    Label(entry.label ?? entry.path, systemImage: "checkmark")
                }
                .disabled(true)
            } else {
                Button {
                    switchTarget = entry
                } label: {
                    Text(entry.label ?? entry.path)
                }
            }
        }
    }

    /// 确认后执行切换（AppSession.switchWorkspace：原态保持由其失败口径保证）
    private func confirmSwitch() {
        guard let target = switchTarget else { return }
        switchTarget = nil
        isSwitching = true
        UISelectionFeedbackGenerator().selectionChanged()
        Task {
            if let error = await session.switchWorkspace(to: target) {
                showSwitchHint(error)
            } else {
                showSwitchHint(String(localized: "已切换到 \(target.label ?? target.path)"))
            }
            isSwitching = false
        }
    }

    private func showSwitchHint(_ message: String) {
        switchHintClear?.cancel()
        switchHint = message
        switchHintClear = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { switchHint = nil }
        }
    }

    // MARK: G-017 移入分组（B-3：全链走 store.moveConversationToGroup——拿真实
    // groupId、全量视图形状提交；失败必须提示，禁止静默）

    private func moveConversationToGroup(_ target: Conversation, groupName: String) async {
        let failure = await store.moveConversationToGroup(target.id, groupName: groupName)
        showGroupHint(
            failure ?? String(localized: "已移入「\(groupName)」"),
            isError: failure != nil)
    }

    private func showGroupHint(_ message: String, isError: Bool) {
        groupHintClear?.cancel()
        groupHint = message
        groupHintIsError = isError
        if isError {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
        groupHintClear = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                groupHint = nil
                groupHintIsError = false
            }
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
        // List sticky 章节头：不透明底（同列表背景色）——透明头滚动时与行内容
        // 叠加互透（用户反馈「只有文字在那和其他 ui 叠加相互影响都看不到」）。
        // full-bleed：清 listRowInsets 由内容自带边距（否则两侧默认 inset 各留
        // 一条透底缝）+ listRowBackground 盖满整行（List 给 header 行分配的
        // 高度余量若露底，滚动内容会从横缝里穿出——「中间这么大的一条缝」）
        Text(title)
            .font(T.font(11, .semibold))
            .foregroundColor(T.text3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, T.sp4)
            .padding(.vertical, 6)
            .background(T.bg)
            .listRowInsets(EdgeInsets())
            .listRowBackground(T.bg)
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
            // G-017：移入分组（moveConversationToGroup 全链：createTaskGroup 零参 + 真实
            // groupId + rename 落名 + applyGroupedTaskViewOrder 全量视图写，B-3）
            Button {
                groupTarget = conversation
                newGroupName = ""
            } label: {
                Label("移入分组…", systemImage: "folder.badge.plus")
            }
            .accessibilityIdentifier("04-ctx-group-\(conversation.id)")
            // 失败任务「清理」（桌面失败任务同款：zcode-task.deleteTask 删除任务）
            if conversation.isFailed {
                Button(role: .destructive) {
                    cleanupTarget = conversation
                } label: {
                    Label("清理", systemImage: "trash")
                }
                .accessibilityIdentifier("04-ctx-cleanup-\(conversation.id)")
            }
            Button(role: .destructive) {
                archiveAction(conversation, archived: true)
            } label: {
                Label("归档", systemImage: "archivebox")
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            // 失败任务滑块「清理」（桌面失败任务同款）
            if conversation.isFailed {
                Button {
                    cleanupTarget = conversation
                } label: {
                    Label("清理", systemImage: "trash")
                }
                .tint(T.red)
                .accessibilityIdentifier("04-rowact-cleanup-\(conversation.id)")
            }
            Button {
                archiveAction(conversation, archived: true)
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

    /// 归档/取消归档动作（写失败如实提示——store.lastArchiveFailureText 原文上屏，
    /// groupHint 通道复用；失败不動本地行——store 回滚 + conversationsReplaced 自动回位）
    private func archiveAction(_ conversation: Conversation, archived: Bool) {
        Task {
            await store.setArchived(archived, conversationID: conversation.id)
            let failure = await store.lastArchiveFailureText()
            if !failure.isEmpty {
                // 写失败：归档区本地行放回（store 已回滚 override，主列表行由
                // conversationsReplaced 回位），失败原文提示
                showGroupHint(
                    String(localized: "归档指令失败 · \(failure)"), isError: true)
                if !archived, showArchived,
                   !archivedConversations.contains(where: { $0.id == conversation.id }) {
                    archivedConversations.append(conversation)
                }
                return
            }
            if archived {
                conversations.removeAll { $0.id == conversation.id }
            }
        }
    }

    /// 已归档行：滑块「取消归档」（unarchiveTask）+ 回到主列表
    private func archivedRow(_ conversation: Conversation) -> some View {
        Button {
            archiveAction(conversation, archived: false)
            archivedConversations.removeAll { $0.id == conversation.id }
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
                archiveAction(conversation, archived: false)
                archivedConversations.removeAll { $0.id == conversation.id }
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
        // 兜底防御：时间字段全缺时 updatedAt=distantPast（公元 1 年）——真机曾渲染
        // 「2025年前」（报障 2026-10-07）；解析侧已补 updatedAt/createdAt 回退，
        // 此处再挡不合理旧日期（ZCode 诞生前）不显示时间
        if conversation.updatedAt.timeIntervalSinceNow < -15 * 365 * 86_400 { return "" }
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
                // 无 run 时 nil → 不渲染任何占位）。只画运行中 run（live-only 口径
                // 见 WorkflowActivitySummary.liveRuns；全部收尾的会话行不渲染轨道）
                if let activity = conversation.workflowActivity, !activity.liveRuns.isEmpty {
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
            // 标题首字符头像（用户反馈 2026-10-07「都是 Z 完全没意义」）；CJK 取
            // 首字、无标题回退 Z。grapheme 取首字：emoji/多字节组合字符不劈半
            let initial = conversation.title.first.map(String.init) ?? "Z"
            Text(initial)
                .font(T.font(17, .semibold))
                .foregroundColor(T.text2)
                .frame(width: 40, height: 40)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rM - 2))
                .overlay(RoundedRectangle(cornerRadius: T.rM - 2).stroke(T.border, lineWidth: 1))
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
// 迷你轨道只画一层并行关系）。run 级过滤：只画运行中（live-only，桌面 ts:128-133
// 为 live||未确认，移动端无确认 UI——用户裁决 2026-10-08「只展示运行中的」）。

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
                Text(run.agentsWorking == 1
                    ? String(localized: "1 agent working")
                    : String(localized: "\(run.agentsWorking) agents working"))
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

    /// 可见 run = 运行中（live-only 口径，WorkflowActivitySummary.liveRuns——桌面
    /// workflowRunLine.ts:128-133 为 live||未确认，移动端无确认 UI 取 live-only）
    private var visibleRuns: [SessionWorkflowRunSummary] { Array(activity.liveRuns.prefix(2)) }
    private var overflowCount: Int { max(0, activity.liveRuns.count - visibleRuns.count) }

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

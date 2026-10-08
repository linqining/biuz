import SwiftUI

/// 根视图：Tab（会话/任务/文件/设置）+ 自定义 Tab 栏（徽章三色 + 毛玻璃 + 安全区）
/// + Push 时隐藏 Tab 栏 + 文件 Tab 根页底部动作栏叠于 Tab 栏之上（spec 3.1/3.2）
struct RootView: View {
    @Environment(AppRouter.self) private var router
    @Environment(TabBadges.self) private var badges
    @Environment(AppSession.self) private var session
    @Environment(\.conversationStore) private var conversationStore
    @Environment(\.taskStore) private var taskStore
    @Environment(\.fileStore) private var fileStore
    // 连接引导根页（设计稿 §1.3：L1 资产复用——自带导航栈 + 扫码 cover + 帮助 push）
    @State private var connectPath: [ConnectFlowView.ConnectRoute] = []
    @State private var showScanner = false
    @State private var scannerUpdateToken = false
    /// 未配对根页的设置入口（用户反馈「这一屏没有退出按钮」：设置 sheet 从根页可达）
    @State private var showConnectSettings = false

    var body: some View {
        ZStack(alignment: .bottom) {
            if showsConnectRoot {
                connectRoot
            } else {
                // Tab 切换无动画直达、保留各栈滚动位置（spec 3.2）
                ZStack {
                    chatTab
                        .opacity(router.selectedTab == .chat ? 1 : 0)
                        .allowsHitTesting(router.selectedTab == .chat)
                        .accessibilityHidden(router.selectedTab != .chat)
                    tasksTab
                        .opacity(router.selectedTab == .tasks ? 1 : 0)
                        .allowsHitTesting(router.selectedTab == .tasks)
                        .accessibilityHidden(router.selectedTab != .tasks)
                    filesTab
                        .opacity(router.selectedTab == .files ? 1 : 0)
                        .allowsHitTesting(router.selectedTab == .files)
                        .accessibilityHidden(router.selectedTab != .files)
                    settingsTab
                        .opacity(router.selectedTab == .settings ? 1 : 0)
                        .allowsHitTesting(router.selectedTab == .settings)
                        .accessibilityHidden(router.selectedTab != .settings)
                }
            }

            VStack(spacing: 0) {
                // 连接状态横幅（13-③ 离线/OAuth 失败：弱底 + 描边 + 图标 + 动作文）。
                // push 详情态不在底部渲染（UI/UX 验收 P1 2026-10-07：overlay 恰好
                // 压在详情 composer 输入框上，可见性与可点性俱失）——详情态横幅由
                // ChatView safeAreaInset 插入式承载（model 单源 ConnectionBanner.model(for:)）
                if let banner = connectionBanner, !router.isPushing {
                    ConnectionBanner(model: banner)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                // §1.6：未连接不渲染 DiffActionBar（空态页无 diff 可批，Empty store 的
                // approveAll 成死钮）
                if router.showsDiffActionBar && session.isConnected {
                    DiffActionBar()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if !router.isPushing && !showsConnectRoot {
                    ZCodeTabBar()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .animation(.easeOut(duration: 0.22), value: router.isPushing)
        .animation(.easeOut(duration: 0.22), value: router.showsDiffActionBar)
        .task { await observeConversationBadge() }
        .task { await observeTaskBadge() }
        .task { await refreshDiffBadge() }
        .task(id: router.diffReloadToken) { await refreshDiffBadge() }
        .task(id: router.selectedTab) {
            if router.selectedTab == .files {
                await refreshDiffBadge()
            }
        }
        // mock→remote 换源后重绑观察器（store 换实例时角标重算——Empty↔remote 换源
        // 同口径；用户实测教训：不重绑则角标停留在旧 store 残留）
        .task(id: ObjectIdentifier(conversationStore)) { await observeConversationBadge() }
        .task(id: ObjectIdentifier(taskStore)) { await observeTaskBadge() }
        .task(id: ObjectIdentifier(fileStore)) {
            await refreshDiffBadge()
            for await _ in fileStore.observeFileTreeChanges() {
                await refreshDiffBadge()
            }
        }
    }

    // MARK: - 未连接根页（连接引导，设计稿 §1.2/§1.3）

    /// 根页替换条件：未配对（demo），或连接中且从未连上（remote Store 无实例——
    /// 含冷启动自动重连与根页/cover 发起的连接，叠加整层连接中反馈）；断线重连
    /// （有快照）保持四 Tab。E2E 演示开关 -ZCodeDemoData 下保持旧四 Tab 演示 UI。
    private var showsConnectRoot: Bool {
        guard !AppSession.isDemoDataEnabled else { return false }
        switch session.mode {
        case .demo:
            return true
        case .connecting:
            return session.remoteConversationStore == nil
        default:
            return false
        }
    }

    /// 根页版连接引导（复用 L1 ConnectHomeView 资产；与 cover 场景的三处差异：
    /// 无 ✕ 关闭钮——根页无处可关；自带 NavigationStack/扫码 cover；
    /// 连接中由本层 overlay 整层 ConnectingView 承担，失败后回落四 Tab + 红横幅）
    private var connectRoot: some View {
        NavigationStack(path: $connectPath) {
            ConnectHomeView(
                onScan: { scannerUpdateToken = false; showScanner = true },
                onManual: { connectPath.append(.manual(tokenFocusOnly: false)) },
                onHelp: { connectPath.append(.help) },
                onFilled: { /* 剪贴板一键填充在 ConnectHomeView 内直接发起连接 */ })
            // 退出入口（用户 2026-10-06「这一屏没有退出按钮」）：未配对根页是终态页，
            // 无处可「关」，但设置（账号/服务器管理）必须可达——齿轮开 Settings sheet。
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showConnectSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .accessibilityIdentifier("00-connect-root-settings")
                }
            }
            .navigationDestination(for: ConnectFlowView.ConnectRoute.self) { route in
                switch route {
                case .manual(let tokenFocusOnly):
                    ManualConnectView(tokenFocusOnly: tokenFocusOnly)
                case .help:
                    ConnectHelpView()
                }
            }
            .sheet(isPresented: $showConnectSettings) {
                NavigationStack {
                    SettingsView()
                }
            }
        }
        .overlay {
            if case .connecting = session.mode {
                ConnectingView(onCancel: { session.cancelConnecting() })
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.22), value: session.mode)
        .fullScreenCover(isPresented: $showScanner) {
            ScanView(updateTokenMode: scannerUpdateToken,
                     onClose: { showScanner = false })
        }
    }

    private var chatTab: some View {
        NavigationStack(path: Binding(get: { router.chatPath }, set: { router.chatPath = $0 })) {
            ConversationListView()
                .navigationDestination(for: ChatRoute.self) { route in
                    switch route {
                    case .chat(let id):
                        ChatView(conversationID: id)
                    }
                }
        }
    }

    private var tasksTab: some View {
        NavigationStack(path: Binding(get: { router.taskPath }, set: { router.taskPath = $0 })) {
            TaskBoardView()
                .navigationDestination(for: TaskRoute.self) { route in
                    switch route {
                    case .output(let task):
                        TaskOutputView(task: task)
                    }
                }
        }
    }

    private var filesTab: some View {
        NavigationStack(path: Binding(get: { router.filePath }, set: { router.filePath = $0 })) {
            DiffReviewView()
                .navigationDestination(for: FileRoute.self) { route in
                    switch route {
                    case .tree:
                        FileTreeView()
                    case .preview(let node):
                        FilePreviewView(node: node)
                    case .commitGraph:
                        CommitGraphPage()
                    }
                }
        }
    }

    private var settingsTab: some View {
        NavigationStack(path: Binding(get: { router.settingsPath }, set: { router.settingsPath = $0 })) {
            SettingsView()
                .navigationDestination(for: SettingsRoute.self) { route in
                    SettingsView().destination(for: route)
                }
        }
    }

    // MARK: - 连接状态横幅（错误 UI：连接失败 / 断线 / 断线重连中）

    private var connectionBanner: ConnectionBanner.Model? {
        ConnectionBanner.model(for: session)
    }

    // MARK: - 角标数据源

    private func observeConversationBadge() async {
        for await event in conversationStore.observeConversations() {
            if case .conversationsReplaced(let list) = event {
                badges.unreadChats = list.reduce(0) { $0 + $1.unreadCount }
            }
        }
    }

    private func observeTaskBadge() async {
        for await tasks in taskStore.observeTasks() {
            badges.runningTasks = tasks.filter { $0.status == .running }.count
        }
    }

    private func refreshDiffBadge() async {
        let files = await fileStore.diffFiles()
        badges.pendingDiffs = files.filter { !$0.isApproved && !$0.isRejected }.count
    }
}

// MARK: - 连接状态横幅（5.9：弱底 + 描边 + 图标 + 动作文；不复用任务状态胶囊）

struct ConnectionBanner: View {
    struct Model {
        let icon: String
        let tint: Color
        let title: String
        let detail: String
        let action: String
        let onAction: () -> Void
        /// a11y identifier（设计稿 §1.4：connecting 横幅独立 id 13-banner-connecting；
        /// 默认值令既有失败/断线两态与全部 E2E 断言不变）
        var identifier: String = "13-banner-connection"
    }

    /// 三态横幅模型单源（connecting/connectFailed/disconnected）——RootView 底部
    /// （列表/tab 态）与 ChatView 详情态 composer 上方共用，避免两张皮
    static func model(for session: AppSession) -> Model? {
        switch session.mode {
        case .connecting(let server):
            // 断线重连中（快照在场，不打断浏览——设计稿 §1.4）；从未连上的
            // connecting 由根页/cover 整层 ConnectingView 承担，横幅不重复出现
            guard session.remoteConversationStore != nil else { return nil }
            return Model(
                icon: "arrow.triangle.2.circlepath", tint: T.accent,
                title: String(localized: "正在连接桌面端…"),
                detail: "\(server.displayName) · \(server.displayAddress)",
                action: String(localized: "取消"),
                identifier: "13-banner-connecting") {
                session.cancelConnecting()
            }
        case .connectFailed(_, let error):
            // 对齐修复（U-9/设计稿 §1.6）：删「已回退演示数据」——Mock 回退语义已消失，
            // 失败态为空数据 + 重试；换台入口在设置页「添加服务器」
            return Model(
                icon: "exclamationmark.triangle.fill", tint: T.red,
                title: String(localized: "桌面端连接失败"),
                detail: error.headline,
                action: String(localized: "重试")) {
                Task { await session.reconnect() }
            }
        case .disconnected(_, let detail):
            // 断线保留最后快照（设计稿 §1.5）：如实声明数据时点，写面由各页失败提示兜底
            return Model(
                icon: "wifi.exclamationmark", tint: T.orange,
                title: String(localized: "已断开 · 显示断线前的数据"),
                detail: detail,
                action: String(localized: "重连")) {
                Task { await session.reconnect() }
            }
        default:
            return nil
        }
    }

    let model: Model

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: model.icon)
                .font(.system(size: 13))
                .foregroundColor(model.tint)
            VStack(alignment: .leading, spacing: 1) {
                Text(model.title)
                    .font(T.font(12, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Text(model.detail)
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            Button(action: model.onAction) {
                Text(model.action)
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.accentText)
                    .padding(.horizontal, T.sp2)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
        }
        .padding(.leading, T.sp3)
        .padding(.trailing, T.sp1)
        .accessibilityIdentifier(model.identifier)
        .background(
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(T.tabbarBg))
                .overlay(alignment: .top) { Divider().overlay(model.tint.opacity(0.5)) }
        )
    }
}

// MARK: - 自定义 Tab 栏（顶部 7 + 热区 48 + 底部 6 + 安全区 ≈ 95px，spec 3.1）

struct ZCodeTabBar: View {
    @Environment(AppRouter.self) private var router
    @Environment(TabBadges.self) private var badges

    var body: some View {
        VStack(spacing: 0) {
            Divider().overlay(T.border)
            HStack(spacing: 0) {
                ForEach(TabItemModel.all) { item in
                    tabButton(item)
                }
            }
            .padding(.top, 7)
            .padding(.bottom, 6)
        }
        .background(
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(T.tabbarBg))
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private func badgeCount(for tab: AppRouter.Tab) -> Int {
        switch tab {
        case .chat: return badges.unreadChats
        case .tasks: return badges.runningTasks
        case .files: return badges.pendingDiffs
        case .settings: return 0
        }
    }

    private func badgeColor(for tab: AppRouter.Tab) -> Color {
        switch tab {
        case .tasks: return T.blue
        case .chat: return T.orangeBright
        case .files: return T.orangeBright
        case .settings: return .clear
        }
    }

    private func tabButton(_ item: TabItemModel) -> some View {
        let selected = router.selectedTab == item.tab
        let count = badgeCount(for: item.tab)
        return Button {
            router.selectedTab = item.tab
        } label: {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: selected ? item.selectedIcon : item.icon)
                        .font(.system(size: 22))
                        .foregroundColor(selected ? T.accent : T.text3)
                        .frame(height: 24)
                    if count > 0 {
                        TabBadge(count: count, color: badgeColor(for: item.tab))
                            .offset(x: 12, y: -6)
                    }
                }
                Text(item.title)
                    .font(T.font(10.5, selected ? .semibold : .medium))
                    .foregroundColor(selected ? T.accent : T.text3)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(item.identifier)
        .accessibilityLabel("\(item.title)\(count > 0 ? String(localized: "，\(count) 条未处理") : "")")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: - Diff 底部动作栏（全部批准 / 浏览文件，叠于 Tab 栏之上）

struct DiffActionBar: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.fileStore) private var store
    /// 全部批准进行中（Spinner+disabled：桌面 stagePaths 往返期间无指示即「点了没反应」
    /// ——2026-10-06 用户回归反馈）
    @State private var isApprovingAll = false
    /// 批准失败文案（含服务端 reason；写面禁止静默吞错）
    @State private var errorText: String?

    var body: some View {
        VStack(spacing: T.sp2) {
            if let errorText {
                HStack(spacing: T.sp2) {
                    Image(systemName: "exclamationmark.circle")
                        .font(.system(size: 12))
                    Text(errorText)
                        .font(T.font(11.5, .semibold))
                        .lineLimit(2)
                    Spacer(minLength: 0)
                }
                .foregroundColor(T.red)
                .onTapGesture { self.errorText = nil }
                .accessibilityIdentifier("08-approve-all-error")
            }
            HStack(spacing: 10) {
                Button {
                    approveAll()
                } label: {
                    HStack(spacing: T.sp1) {
                        if isApprovingAll { SpinnerView(size: 14) }
                        Text(isApprovingAll ? String(localized: "批准中…") : String(localized: "全部批准"))
                            .font(T.font(15, .semibold))
                            .foregroundColor(isApprovingAll ? T.text2 : T.onAccent)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(isApprovingAll ? T.bgInput : T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(isApprovingAll)
                .accessibilityIdentifier("08-act-approve-all")

                Button {
                    router.pushFileTree()
                } label: {
                    // 对齐修复 H6（原 G-026「桌面端继续」）：动作实为 pushFileTree（浏览文件树），
                    // 无接力到桌面端的能力——文案降级为如实描述动作；
                    // testid 保留 08-act-desktop-continue（无 E2E 文案断言依赖，已核验）
                    Text(String(localized: "浏览文件"))
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text2)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("08-act-desktop-continue")
            }
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

    /// 全部批准：失败文案上屏（按钮恢复可重试）；成功 haptic + bump 刷新令牌
    /// （DiffReviewView .task(id: diffReloadToken) 据此 reload，卡片翻「已批准」）
    private func approveAll() {
        guard !isApprovingAll else { return }
        isApprovingAll = true
        errorText = nil
        Task {
            let error = await store.approveAll()
            isApprovingAll = false
            if let error {
                errorText = error
            } else {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                router.diffReloadToken += 1
            }
        }
    }
}

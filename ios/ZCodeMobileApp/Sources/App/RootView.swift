import SwiftUI

/// 根视图：三 Tab（会话/待批/设置）+ 自定义 Tab 栏（徽章 + 毛玻璃 + 安全区）
/// + Push 时隐藏 Tab 栏。
/// 2026-11-25 重设计裁决 R2：任务看板与全局文件 tab 撤除——「更改」「文件」收进会话页，
/// 「待批」独立成 tab（跨会话审批收件箱，design/design-spec.md §3.1）。
struct RootView: View {
    @Environment(AppRouter.self) private var router
    @Environment(TabBadges.self) private var badges
    @Environment(AppSession.self) private var session
    @Environment(\.conversationStore) private var conversationStore
    // 连接引导根页（L1 资产复用——自带导航栈 + 扫码 cover + 帮助 push）
    @State private var connectPath: [ConnectFlowView.ConnectRoute] = []
    @State private var showScanner = false
    @State private var scannerUpdateToken = false
    /// 未配对根页的设置入口（用户反馈「这一屏没有退出按钮」：设置 sheet 从根页可达）
    @State private var showConnectSettings = false
    /// 连不上时越过闸门查看离线缓存（2026-11-25 用户裁决「连不上什么功能都没意义」——
    /// 默认整屏闸门，只留一个明确的「查看离线缓存」出口，连上后自动收起）
    @State private var allowOfflineBrowsing = false

    var body: some View {
        ZStack(alignment: .bottom) {
            if showsConnectRoot {
                connectRoot
            } else if needsConnectionGate {
                // 连不上时不给「三 tab 空列表」这种没意义的界面：整屏重连/加载页
                ReconnectLoadingPage(
                    session: session,
                    onBrowseOffline: { allowOfflineBrowsing = true })
            } else {
                // Tab 切换无动画直达、保留各栈滚动位置。
                // ⚠ 待批栈**选中才挂载**：List（UICollectionView 背板）以 opacity(0)
                // 常驻上层时，其 cell 手势在 UIKit 层仍会吃掉下层会话列表的 tap
                //（SwiftUI allowsHitTesting(false) 挡不住——2026-11-25 test20 门禁红：
                // 点会话行无反应、openChat 零调用；HEAD 的任务/文件两层是 ScrollView
                // 无此问题）。待批是纯摘要列表，无状态保留诉求；会话/设置栈维持常驻。
                ZStack {
                    chatTab
                        .opacity(router.selectedTab == .chat ? 1 : 0)
                        .allowsHitTesting(router.selectedTab == .chat)
                        .accessibilityHidden(router.selectedTab != .chat)
                    if router.selectedTab == .approvals {
                        approvalsTab
                            .transition(.opacity)
                    }
                    settingsTab
                        .opacity(router.selectedTab == .settings ? 1 : 0)
                        .allowsHitTesting(router.selectedTab == .settings)
                        .accessibilityHidden(router.selectedTab != .settings)
                }
            }

            // 008-版本提示：桌面版本与配对链接声明不一致 → 阻断式（web 同口径，只能重新加载）
            if let desktopVersion = session.versionMismatch {
                VersionNoticeOverlay(desktopVersion: desktopVersion) {
                    session.confirmVersionReload()
                    Task { await session.reconnect() }
                }
            }
        }
        // 底栏（连接横幅 + tab 栏）改 safeAreaInset 承载（2026-11-25 三 tab 重构）：
        // 原为 ZStack 底部**覆盖层**——列表末行可以一直停在 tab 栏底下，行的可访问性
        // 帧落在 tab 栏区域内（实测帧 y=777–830 vs tab 栏 779+，行中心正压「待批」键），
        // tap 全被 tab 栏吞掉（test20 门禁红：点行无反应）。safeAreaInset 让所有 tab
        // 内容自动避让底栏，末行永远可点可读；push 详情态不渲染底栏（既有的
        // 2026-10-07「横幅压 composer」修复语义保持——不占位即全高）。
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !router.isPushing && !showsConnectRoot && !needsConnectionGate {
                VStack(spacing: 0) {
                    // 连接状态横幅（13-③ 离线/OAuth 失败：弱底 + 描边 + 图标 + 动作文）。
                    // push 详情态由 ChatView safeAreaInset 插入式承载（model 单源
                    // ConnectionBanner.model(for:)），此处不重复渲染
                    if let banner = connectionBanner {
                        ConnectionBanner(model: banner)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    ZCodeTabBar()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .animation(.easeOut(duration: 0.22), value: router.isPushing)
        // 连上即撤「离线浏览」豁免：下次断线重新拦（否则一次放行永久放行）
        .onChange(of: session.mode) { _, newMode in
            if case .connected = newMode { allowOfflineBrowsing = false }
        }
        .task { await observeConversationBadges() }
        // mock→remote 换源后重绑观察器（store 换实例时角标重算——Empty↔remote 换源
        // 同口径；用户实测教训：不重绑则角标停留在旧 store 残留）
        .task(id: ObjectIdentifier(conversationStore)) { await observeConversationBadges() }
    }

    // MARK: - 未连接根页（连接引导）

    /// 根页替换条件：未配对（demo），或连接中且从未连上（remote Store 无实例——
    /// 含冷启动自动重连与根页/cover 发起的连接，叠加整层连接中反馈）；断线重连
    /// （有快照）保持三 Tab。E2E 演示开关 -ZCodeDemoData 下保持演示 UI。
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
    /// 连接中由本层 overlay 整层 ConnectingView 承担，失败后回落三 Tab + 红横幅）
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

    private var approvalsTab: some View {
        NavigationStack(path: Binding(get: { router.approvalsPath }, set: { router.approvalsPath = $0 })) {
            PendingApprovalsView()
                // 独立路由类型（ApprovalChatRoute）：与会话栈同时挂载，共用 ChatRoute
                // 会重复注册 navigationDestination（push 歧义——test20 门禁实证）
                .navigationDestination(for: ApprovalChatRoute.self) { route in
                    switch route {
                    case .chat(let id):
                        ChatView(conversationID: id)
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

    /// 连接闸门：已配对但当前没连上（连接中 / 失败 / 断线）→ 整屏 loading/重连页。
    /// 未配对走 connectRoot（首连引导），此处不重复；用户点「查看离线缓存」时让行。
    private var needsConnectionGate: Bool {
        guard !allowOfflineBrowsing else { return false }
        switch session.mode {
        case .connecting, .connectFailed, .disconnected: return true
        case .demo, .connected: return false
        }
    }

    // MARK: - 连接状态横幅（错误 UI：连接失败 / 断线 / 断线重连中）

    private var connectionBanner: ConnectionBanner.Model? {
        ConnectionBanner.model(for: session)
    }

    // MARK: - 角标数据源（会话未读 + 跨会话待批合计，单流一次遍历）

    private func observeConversationBadges() async {
        for await event in conversationStore.observeConversations() {
            if case .conversationsReplaced(let list) = event {
                badges.unreadChats = list.reduce(0) { $0 + $1.unreadCount }
                badges.pendingApprovals = list.reduce(0) { $0 + $1.pendingApprovalCount }
            }
        }
    }
}

// MARK: - 连不上时的整屏页（重连 / 首次连接 loading）
//
// 2026-11-25 用户裁决：「断网应该自动重连，像 web 那样有个 loading 页」+
//「连不上什么功能都没意义」——此前断网只出一条底部横幅，三 tab 照常可点，点进去
// 全是空列表。现改为整屏接管：品牌位 + 状态 + 进度 + 主 CTA（立即重连 / 取消），
// 只保留「查看离线缓存」一个明确的越权出口（连上自动复位）。
//
// 与 web 的口径对齐：连接中/重连中有进度，失败有原因原文（不折叠成一句话含糊过去），
// 不提供任何依赖桌面的功能入口（审批/会话/设置都在闸门之外）。

struct ReconnectLoadingPage: View {
    let session: AppSession
    let onBrowseOffline: () -> Void
    @State private var busy = false

    private var server: ServerConfig? {
        switch session.mode {
        case .connecting(let s), .connected(let s),
             .connectFailed(let s, _), .disconnected(let s, _):
            return s
        case .demo:
            return nil
        }
    }

    private var failureText: String? {
        if case .connectFailed(_, let error) = session.mode { return error.headline }
        if case .disconnected(_, let reason) = session.mode, !reason.isEmpty { return reason }
        return nil
    }

    private var title: String {
        switch session.mode {
        case .connecting where session.isAutoReconnecting:
            return String(localized: "连接已断开 · 正在重连…")
        case .connecting:
            return String(localized: "正在连接桌面端…")
        case .connectFailed:
            return String(localized: "连不上桌面端")
        case .disconnected:
            return String(localized: "已与桌面端断开")
        default:
            return String(localized: "正在连接桌面端…")
        }
    }

    private var detail: String {
        let name = server?.displayName ?? ""
        switch session.mode {
        case .connecting where session.isAutoReconnecting:
            return "\(session.autoReconnectAttempt)/\(session.autoReconnectMax) · \(name)"
        case .connecting:
            return name
        case .connectFailed, .disconnected:
            return failureText.map { "\(name) · \($0)" } ?? name
        default:
            return name
        }
    }

    private var isConnecting: Bool {
        if case .connecting = session.mode { return true }
        return false
    }

    var body: some View {
        VStack(spacing: T.sp4) {
            Spacer(minLength: 0)
            ZCodeBrandMark(size: 64)
            Text("朱批")
                .font(T.font(20, .semibold))
                .foregroundColor(T.seal)
            Spacer().frame(height: T.sp2)
            if isConnecting {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(T.seal)
                    .scaleEffect(1.15)
            } else {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 26, weight: .light))
                    .foregroundColor(T.text3)
            }
            Text(title)
                .font(T.font(15, .semibold))
                .foregroundColor(T.text)
                .multilineTextAlignment(.center)
            Text(detail)
                .font(T.mono(11))
                .foregroundColor(T.text3)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.horizontal, T.sp6)
                .accessibilityIdentifier("13-gate-detail")
            Spacer().frame(height: T.sp2)
            if isConnecting {
                Button {
                    session.cancelConnecting()
                } label: {
                    Text("取消")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text2)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("13-gate-cancel")
            } else {
                Button {
                    busy = true
                    Task { await session.reconnect(); busy = false }
                } label: {
                    Text("立即重连")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.onSeal)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.seal)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .buttonStyle(.plain)
                .disabled(busy)
                .opacity(busy ? 0.6 : 1)
                .accessibilityIdentifier("13-gate-reconnect")
            }
            Button {
                onBrowseOffline()
            } label: {
                Text("查看离线缓存")
                    .font(T.font(13))
                    .foregroundColor(T.text3)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("13-gate-offline")
            Spacer(minLength: 0)
        }
        .padding(.horizontal, T.sp6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(T.bg.ignoresSafeArea())
        .accessibilityIdentifier("13-connection-gate")
    }
}

// MARK: - 连接状态横幅（弱底 + 描边 + 图标 + 动作文；不复用任务状态胶囊）
// R5 去技术化（2026-11-25）：副行只留电脑名，不再拼 host:port / relay 地址——
// 普通用户只需要「哪台电脑、什么状态」，地址与传输细节在开发者模式诊断面看。

struct ConnectionBanner: View {
    struct Model {
        let icon: String
        let tint: Color
        let title: String
        let detail: String
        let action: String
        let onAction: () -> Void
        /// a11y identifier（connecting 横幅独立 id 13-banner-connecting；
        /// 默认值令既有失败/断线两态与全部 E2E 断言不变）
        var identifier: String = "13-banner-connection"
    }

    /// 三态横幅模型单源（connecting/connectFailed/disconnected）——RootView 底部
    /// （列表/tab 态）与 ChatView 详情态 composer 上方共用，避免两张皮
    static func model(for session: AppSession) -> Model? {
        switch session.mode {
        case .connecting(let server):
            // 断线重连中（快照在场，不打断浏览）；从未连上的
            // connecting 由根页/cover 整层 ConnectingView 承担，横幅不重复出现
            guard session.remoteConversationStore != nil else { return nil }
            if session.isAutoReconnecting {
                return Model(
                    icon: "arrow.triangle.2.circlepath", tint: T.orange,
                    title: String(localized: "连接已断开 · 正在自动重连…"),
                    detail: "\(session.autoReconnectAttempt)/\(session.autoReconnectMax) · \(server.displayName)",
                    action: String(localized: "取消"),
                    identifier: "13-banner-reconnecting") {
                    session.cancelConnecting()
                }
            }
            return Model(
                icon: "arrow.triangle.2.circlepath", tint: T.accent,
                title: String(localized: "正在连接桌面端…"),
                detail: server.displayName,
                action: String(localized: "取消"),
                identifier: "13-banner-connecting") {
                session.cancelConnecting()
            }
        case .connectFailed(_, let error):
            // 对齐修复（U-9）：删「已回退演示数据」——Mock 回退语义已消失，
            // 失败态为空数据 + 重试；换台入口在设置页「添加服务器」
            return Model(
                icon: "exclamationmark.triangle.fill", tint: T.red,
                title: String(localized: "桌面端连接失败"),
                detail: error.headline,
                action: String(localized: "重试")) {
                Task { await session.reconnect() }
            }
        case .disconnected(let server, _):
            // 断线保留最后快照：如实声明数据时点，写面由各页失败提示兜底；
            // 副行 = 电脑名（R5：原 detail 字串含传输细节，撤）
            return Model(
                icon: "wifi.exclamationmark", tint: T.orange,
                title: String(localized: "已断开 · 显示断线前的数据"),
                detail: server.displayName,
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

// MARK: - 自定义 Tab 栏（顶部 7 + 热区 48 + 底部 6 + 安全区 ≈ 95px）

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
        // 平涂纸底（2026-11-25 用户「底部配色不协调」）：撤销毛玻璃+近白叠层——
        // ultraThinMaterial 会把底栏提亮成白条，与纸底内容区割裂；分隔只靠顶部 Divider
        .background(
            Rectangle()
                .fill(T.tabbarBg)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private func badgeCount(for tab: AppRouter.Tab) -> Int {
        switch tab {
        case .chat: return badges.unreadChats
        case .approvals: return badges.pendingApprovals
        case .settings: return 0
        }
    }

    private func badgeColor(for tab: AppRouter.Tab) -> Color {
        switch tab {
        case .chat: return T.orangeBright
        // 印色只给「要你落笔」的位置（design-spec §2）：待批角标朱磦
        case .approvals: return T.seal
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

// MARK: - 008 版本提示（web webRemoteControlVersionNotice.tsx 同构）

/// 阻断式提示：遮罩吞掉背景点击，只有「重新加载」一个出口。
private struct VersionNoticeOverlay: View {
    let desktopVersion: String
    let onReload: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { /* 阻断式：点遮罩不关闭 */ }
            VStack(alignment: .leading, spacing: T.sp3) {
                Text("桌面版本已更新")
                    .font(T.font(17, .semibold))
                    .foregroundColor(T.text)
                Text("桌面当前版本为 \(desktopVersion)。任务操作已暂停，请重新加载以继续。未发送的附件不会保留。")
                    .font(T.font(13))
                    .foregroundColor(T.text2)
                    .fixedSize(horizontal: false, vertical: true)
                HStack {
                    Spacer()
                    Text("重新加载")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.onAccent)
                        .padding(.horizontal, T.sp4)
                        .frame(minHeight: 44)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .onTapGesture(perform: onReload)
                        .accessibilityIdentifier("08-version-reload")
                }
            }
            .padding(T.sp6)
            .frame(maxWidth: 340)
            .background(T.bgElevated)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .padding(.horizontal, T.sp4)
        }
        .accessibilityIdentifier("08-version-notice")
    }
}

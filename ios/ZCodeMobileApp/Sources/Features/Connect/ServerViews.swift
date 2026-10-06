import SwiftUI

// MARK: - L4-B 服务器与账户 · 配置区（已登录账号 / 服务地址与令牌编辑 / 连接测试）

struct ServerAccountConfigView: View {
    @Environment(AppSession.self) private var session
    @Environment(AppRouter.self) private var router
    @State private var showLogoutConfirm = false
    @State private var testResult: TestOutcome?
    /// G-028 刷新额度状态（真实重拉 + 结果反馈）
    @State private var isRefreshingQuota = false
    @State private var quotaRefreshNotice: String?
    @State private var quotaRefreshedAt: Date?

    struct TestOutcome: Equatable {
        var ok: Bool
        var line1: String
        var line2: String
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                accountSection
                serverSection
                testSection
                Spacer(minLength: 12)
                Text("退出登录仅清除账户层凭据（tokenSet），不影响已保存的服务器与令牌\n删除服务器请前往服务器详情")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, 10)
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp1)
        }
        .scrollIndicators(.hidden)
        .navigationTitle("服务器与账户")
        .navigationBarTitleDisplayMode(.inline)
        .background(T.bg)
        .sheet(isPresented: $showLogoutConfirm) {
            ConfirmSheet(
                title: "退出登录「\(session.oauthUserInfo?.displayName ?? "")」？",
                message: "仅清除本机 Keychain 中的 OAuth tokenSet（账户层）；已保存的服务器与访问令牌不受影响。重新登录需再次走授权流程。",
                confirmTitle: "退出登录", confirmIcon: "xmark.circle",
                identifierPrefix: "l4-b-sheet-logout") {
                    showLogoutConfirm = false
                } onConfirmAction: {
                    showLogoutConfirm = false
                    session.logout()
                }
        }
    }

    // MARK: 账户（OAuth 账户层 · 三形态：已登录 / 未登录 / 已过期）

    private var accountSection: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                Text("账户")
                    .font(T.font(13, .bold))
                    .foregroundColor(T.text2)
                Text("OAuth · tokenSet 仅存 Keychain")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
            if session.oauthUserInfo != nil, let tokenSet = session.oauthTokenSet {
                if OAuthCredentialStore.isExpired(tokenSet) {
                    expiredCard(tokenSet)
                } else {
                    loggedInCard(tokenSet)
                }
            } else {
                notLoggedInCard
            }
        }
    }

    private func loggedInCard(_ tokenSet: OAuthTokenSet) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                if let userInfo = session.oauthUserInfo {
                    OAuthAvatar(userInfo: userInfo, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Text(userInfo.displayName)
                                .font(T.font(15.5, .heavy))
                                .foregroundColor(T.text)
                            StatusPill(text: "ZAI", kind: .done, compact: true)
                        }
                        Text("@\(userInfo.username) · Coding Plan · 剩余额度（演示）")
                            .font(T.font(11.5))
                            .foregroundColor(T.text3)
                    }
                }
                Spacer()
            }
            if let expiresAt = tokenSet.expiresAt {
                Text("有效期至 \(OAuthSuccessView.formatter.string(from: expiresAt))")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
            }
            HStack(spacing: 10) {
                Button {
                    showLogoutConfirm = true
                } label: {
                    Label("退出登录", systemImage: "xmark")
                        .font(T.font(13.5, .semibold))
                        .foregroundColor(T.red)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                }
                .accessibilityIdentifier("l4-b-act-logout")
                Button {
                    Task { await refreshQuota() }
                } label: {
                    Label(isRefreshingQuota ? "刷新中…" : "刷新额度", systemImage: isRefreshingQuota ? "hourglass" : "arrow.clockwise")
                        .font(T.font(13.5, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("l4-b-act-refresh")
                .disabled(isRefreshingQuota)
            }
            // G-028：刷新结果反馈（更新时间 / 错误提示；验收①②）
            if let notice = quotaRefreshNotice {
                Text(notice)
                    .font(T.font(11, .semibold))
                    .foregroundColor(notice.contains("失败") ? T.red : T.accentText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("l4-b-quota-notice")
            } else if let at = quotaRefreshedAt {
                Text("已更新 · \(Self.quotaFormatter.string(from: at))")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .card(padding: 14)
    }

    /// G-028：真实刷新——refreshDesktopReadonlyInfo 重拉 getCodingPlanUsageSnapshot /
    /// getCodingPlanResetStatus（桌面 usage-stats 两读），成功/失败均给反馈
    private func refreshQuota() async {
        guard !isRefreshingQuota else { return }
        isRefreshingQuota = true
        quotaRefreshNotice = nil
        let before = session.codingPlanUsage
        await session.refreshDesktopReadonlyInfo()
        isRefreshingQuota = false
        if let after = session.codingPlanUsage {
            quotaRefreshedAt = Date()
            if after != before {
                quotaRefreshNotice = String(localized: "额度已刷新（数据有更新）")
            } else {
                quotaRefreshNotice = String(localized: "额度已刷新（与上次一致）")
            }
        } else {
            quotaRefreshNotice = String(localized: "刷新失败 · 桌面端未返回额度数据，请确认连接后重试")
        }
    }

    static let quotaFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    private func expiredCard(_ tokenSet: OAuthTokenSet) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                OAuthAvatar(userInfo: session.oauthUserInfo ?? OAuthUserInfo(id: "?", username: "?", displayName: "?"), size: 48)
                    .opacity(0.55)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(session.oauthUserInfo?.displayName ?? "")
                            .font(T.font(14, .bold))
                            .foregroundColor(T.text)
                        StatusPill(text: "登录已过期", kind: .wait, compact: true)
                    }
                    Text("expiresAt=\(tokenSet.expiresAt.map { OAuthSuccessView.formatter.string(from: $0) } ?? "—") · 后续请求 401 → 引导重登")
                        .font(T.mono(11))
                        .foregroundColor(T.text3)
                }
            }
            // 重登主按钮（由根层 fullScreenCover 唤起 O1）
            Button {
                session.requestLoginFlow()
            } label: {
                Label("重新登录", systemImage: "arrow.clockwise")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.onAccent)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            Text("仅失效账户层 tokenSet，连接功能与已保存服务器不受影响；重登走 O1 主按钮（重置一次性 state），不做静默续期")
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .lineSpacing(2)
        }
        .card(padding: 14)
        .accessibilityIdentifier("l4-b-card-expired")
    }

    private var notLoggedInCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "person")
                .font(.system(size: 17))
                .foregroundColor(T.text3)
                .frame(width: 48, height: 48)
                .background(T.bgInput)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text("未登录")
                    .font(T.font(14, .bold))
                    .foregroundColor(T.text)
                Text("连接桌面端无需登录 · 登录仅用于云端分享与额度")
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            }
            Spacer()
            Button {
                session.requestLoginFlow()
            } label: {
                Text("使用 Z.ai 账号登录")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.onAccent)
                    .padding(.horizontal, T.sp4)
                    .frame(minHeight: 44)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
        }
        .card(padding: 14)
        .accessibilityIdentifier("l4-b-card-login")
    }

    // MARK: 服务地址与令牌（连接层编辑入口）

    private var serverSection: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                Text("服务地址与令牌")
                    .font(T.font(13, .bold))
                    .foregroundColor(T.text2)
                Text("服务器密码 · Keychain")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
            if let server = session.savedServer {
                VStack(spacing: 0) {
                    Button {
                        session.requestConnectFlow(editTokenOnly: false)
                    } label: {
                        serverRow(icon: "server.rack", title: "服务地址",
                                  detail: "\(server.baseURL) · \(server.name ?? "未命名")",
                                  value: "编辑")
                    }
                    .accessibilityIdentifier("l4-b-row-host")
                    Divider().overlay(T.border)
                    Button {
                        session.requestConnectFlow(editTokenOnly: true)
                    } label: {
                        serverRow(icon: "key", title: "访问令牌",
                                  detail: server.token.isEmpty
                                      ? "免鉴权（--no-token）· 无过期，随桌面进程存活"
                                      : "\(OAuthCredentialStore.mask(server.token)) · 无过期，随桌面进程存活",
                                  value: "更新")
                    }
                    .accessibilityIdentifier("l4-b-row-token")
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
            } else {
                Button {
                    session.requestConnectFlow(editTokenOnly: false)
                } label: {
                    serverRow(icon: "plus", title: "尚未配置服务器",
                              detail: "扫码 / 剪贴板 / 手动输入", value: "添加")
                }
                .accessibilityIdentifier("l4-b-row-host")
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
            }
        }
    }

    /// 行内容（identifier 由调用处的 Button 持有，保证 e2e firstMatch 命中带完整 label 的按钮元素）
    private func serverRow(icon: String, title: String, detail: String, value: String) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(T.text2)
                .frame(width: 30, height: 30)
                .background(T.bgElevated)
                .clipShape(RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(T.font(14.5)).foregroundColor(T.text)
                Text(detail)
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            Text(value).font(T.font(12)).foregroundColor(T.text3)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    // MARK: 连接测试（GET /api/server-info · 1.5s 超时 · 仅探测不建 WS）

    private var testSection: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                Text("连接测试")
                    .font(T.font(13, .bold))
                    .foregroundColor(T.text2)
                Text("GET /api/server-info · 1.5s 超时")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
            VStack(spacing: 0) {
                HStack(spacing: T.sp2) {
                    Image(systemName: testOutcomeIcon)
                        .font(.system(size: 12))
                        .foregroundColor(testOutcomeTint)
                        .frame(width: 30, height: 30)
                        .background(T.bgElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 9))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(testResult?.line1 ?? "尚未测试")
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.text)
                        Text(testResult?.line2 ?? "测试仅探测可达性，不建立 WS 会话")
                            .font(T.mono(11))
                            .foregroundColor(T.text3)
                            .lineLimit(2)
                    }
                    Spacer()
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .accessibilityIdentifier("l4-b-test-result")
                Divider().overlay(T.border)
                Button {
                    runTest()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.clockwise")
                        Text("重新测试")
                    }
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.accentText)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .accessibilityIdentifier("l4-b-act-test")
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        }
    }

    private var testOutcomeIcon: String {
        guard let result = testResult else { return "circle.dashed" }
        return result.ok ? "checkmark" : "xmark"
    }

    private var testOutcomeTint: Color {
        guard let result = testResult else { return T.text3 }
        return result.ok ? T.accentText : T.red
    }

    private func runTest() {
        guard let server = session.savedServer else {
            testResult = TestOutcome(ok: false, line1: "未配置服务器", line2: "请先添加服务器")
            return
        }
        testResult = TestOutcome(ok: false, line1: "测试中…", line2: server.baseURL)
        Task {
            let probe = await session.testConnection(to: server)
            if let info = probe.info, probe.error == nil {
                let line1 = "可达 · \(probe.status ?? 200) OK · \(probe.latencyMs)ms"
                let line2 = "\(info.serverId) · v\(info.version) · authRequired=\(info.authRequired) · protocolVersion=\(info.protocolVersion)"
                testResult = TestOutcome(ok: true, line1: line1, line2: line2)
            } else {
                testResult = TestOutcome(
                    ok: false,
                    line1: probe.error?.headline ?? "不可达",
                    line2: probe.error?.codeLine ?? server.baseURL)
            }
        }
    }
}

// MARK: - L5 服务器详情（连接确认 · 工作区单选 · 能力 chips · 令牌与安全 · 删除）

struct ServerDetailView: View {
    @Environment(AppSession.self) private var session
    @State private var showDeleteConfirm = false
    @State private var probe: ServerInfoClient.ProbeResult?
    @State private var selectedWorkspace: String?

    private var server: ServerConfig? {
        session.savedServer
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let server {
                    infoCard(server)
                    workspaceSection(server)
                    capabilityCard
                    securitySection(server)
                    deleteRow
                    Spacer(minLength: 12)
                    PrimaryButton(title: "立即连接", identifier: "l5-btn-connect") {
                        Task { await session.connect(server: server) }
                    }
                    .padding(.bottom, 10)
                } else {
                    EmptyStateView(icon: "server.rack", title: "尚未配置服务器",
                                   detail: "从「添加服务器」扫码或手动输入添加",
                                   cta: "添加服务器", ctaAction: { session.requestConnectFlow(editTokenOnly: false) })
                }
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp1)
        }
        .scrollIndicators(.hidden)
        .navigationTitle(server?.name ?? "服务器详情")
        .navigationBarTitleDisplayMode(.inline)
        .background(T.bg)
        .task {
            selectedWorkspace = server?.preferredWorkspacePath
            probe = await session.probeSavedServer()
            refreshDesktopInfo()
        }
        .sheet(isPresented: $showDeleteConfirm) {
            ConfirmSheet(
                title: "删除「\(server?.name ?? server?.displayAddress ?? "")」？",
                message: "将清除本机 Keychain 中的访问令牌与最近连接记录；桌面端服务不受影响，可随时重新扫码添加。",
                confirmTitle: "删除服务器", confirmIcon: "trash",
                identifierPrefix: "l5-sheet-del") {
                    showDeleteConfirm = false
                } onConfirmAction: {
                    if let server {
                        session.deleteServer(server)
                    }
                    showDeleteConfirm = false
                }
        }
    }

    // MARK: 连接信息卡（server-info 只读回显 · serverId/version 做连接确认）

    private func infoCard(_ server: ServerConfig) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: "laptopcomputer.and.iphone")
                    .font(.system(size: 16))
                    .foregroundColor(T.accent)
                    .frame(width: 40, height: 40)
                    .background(T.accentDim)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 0) {
                        Text(server.name ?? "我的桌面端")
                            .font(T.font(15.5, .heavy))
                            .foregroundColor(T.text)
                        ProbeDot(reachable: probe.map { $0.error == nil })
                    }
                    Text(server.lastConnectedAt.map {
                        "上次连接 · " + ConnectHomeView.relativeFormatter.localizedString(for: $0, relativeTo: Date())
                    } ?? "尚未连接过")
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                }
                Spacer()
            }
            VStack(alignment: .leading, spacing: 5) {
                fieldRow("地址", server.baseURL)
                fieldRow("serverId", probe?.info?.serverId ?? "—")
                fieldRow("版本", probe?.info?.version ?? "—")
                fieldRow("协议 · 鉴权", "remote v1 · v4 v3 · authReq=\(probe?.info?.authRequired.description ?? "—")")
                // 桌面端登录（oauth getProviders/getActiveProvider/restoreCachedSessionState
                // 三只读投影；连接态才拉取，断开隐藏）
                if let desktop = session.desktopOAuthInfo {
                    fieldRow("桌面端登录", Self.desktopLoginText(desktop))
                }
            }
            .padding(.top, 8)
            .overlay(alignment: .top) { Divider().overlay(T.border) }
        }
        .card(padding: 12)
        .accessibilityIdentifier("l5-info-card")
    }

    /// 桌面端登录只读文案：已登录显示 provider + 用户名；过期态显示待重登；未登录显示 provider 名
    private static func desktopLoginText(_ desktop: DesktopOAuthInfo) -> String {
        let provider = desktop.activeProvider ?? desktop.providers.sorted().first
        if desktop.authenticated {
            let who = desktop.userName ?? desktop.userHandle ?? "已登录"
            return provider.map { "\(who) · \($0)" } ?? who
        }
        if desktop.requiresReauthentication {
            return "登录已过期 · 待桌面端重登"
        }
        return provider.map { "未登录 · \($0)" } ?? "未登录"
    }

    /// 连接态刷新桌面只读信息（进入详情页时补拉一次，保持卡片新鲜）。
    /// 对齐修复后以 isConnected gating（未连接 ≠ 演示；非连接态 refresh 内部
    /// guard connection.isActive 会清空只读投影，断线快照随之如实过期）
    private func refreshDesktopInfo() {
        guard session.isConnected else { return }
        Task { await session.refreshDesktopReadonlyInfo() }
    }

    private func fieldRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(T.font(11))
                .foregroundColor(T.text3)
            Spacer()
            Text(value)
                .font(T.mono(11))
                .foregroundColor(T.text2)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    // MARK: 工作区单选（默认 workspaces[0]；切换仅改本机偏好）

    private func workspaceSection(_ server: ServerConfig) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("工作区 · \(session.connection.serverInfo?.workspaces.count ?? 0)")
                    .font(T.font(13, .bold))
                    .foregroundColor(T.text2)
                Text("默认取 server-info workspaces[0]")
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            }
            let workspaces = session.connection.serverInfo?.workspaces ?? []
            if workspaces.isEmpty {
                Text("尚未从 server-info 获取工作区（连接后显示）")
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(workspaces.enumerated()), id: \.offset) { index, workspace in
                        if index > 0 { Divider().overlay(T.border) }
                        let isSelected = selectedWorkspace.map { $0 == workspace.path } ?? (index == 0)
                        workspaceRow(workspace, index: index, selected: isSelected) {
                            selectedWorkspace = workspace.path
                            var updated = server
                            updated.preferredWorkspacePath = workspace.path
                            ServerRegistry.upsert(updated)
                        }
                    }
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
            }
        }
    }

    private func workspaceRow(_ workspace: ServerWorkspaceInfo, index: Int, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: T.sp2) {
                Circle()
                    .strokeBorder(selected ? T.accent : T.borderStrong,
                                  lineWidth: selected ? 6 : 1.5)
                    .frame(width: 20, height: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(workspace.path)
                        .font(T.mono(12.5, selected ? .medium : .regular))
                        .foregroundColor(selected ? T.text : T.text2)
                        .lineLimit(1)
                    Text("\(workspace.label ?? "") · \(selected ? "当前" : "") · workspaces[\(index)]")
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .accessibilityIdentifier("l5-row-ws-\(index)")
    }

    // MARK: 能力 chips（双源口径：chips = server-info.capabilities 仅展示）

    private var capabilityCard: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 10) {
                Text("服务端能力")
                    .font(T.font(12, .bold))
                    .foregroundColor(T.text2)
                ForEach(session.connection.serverInfo?.capabilities ?? [], id: \.self) { capability in
                    StatusPill(text: capability, kind: .tag, compact: true)
                }
            }
            Text("chips = server-info.capabilities（仅展示）；clientHello 口径另源（握手 HelloMessage.capabilities 宣告键取子集）")
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .lineSpacing(2)
        }
        .card(padding: 12)
    }

    // MARK: 令牌与安全

    private func securitySection(_ server: ServerConfig) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("令牌与安全")
                .font(T.font(13, .bold))
                .foregroundColor(T.text2)
            VStack(spacing: 0) {
                Button {
                    session.requestConnectFlow(editTokenOnly: true)
                } label: {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "key")
                            .font(.system(size: 12))
                            .foregroundColor(T.text2)
                            .frame(width: 30, height: 30)
                            .background(T.bgElevated)
                            .clipShape(RoundedRectangle(cornerRadius: 9))
                        VStack(alignment: .leading, spacing: 1) {
                            Text("更新令牌").font(T.font(14.5)).foregroundColor(T.text)
                            Text(server.token.isEmpty
                                 ? "免鉴权（--no-token）"
                                 : "Keychain · \(OAuthCredentialStore.mask(server.token)) · 401 时重扫")
                                .font(T.mono(11))
                                .foregroundColor(T.text3)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(T.text3)
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
                }
                .accessibilityIdentifier("l5-row-token-update")
                Divider().overlay(T.border)
                HStack(spacing: T.sp2) {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 12))
                        .foregroundColor(T.text2)
                        .frame(width: 30, height: 30)
                        .background(T.bgElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 9))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("传输安全").font(T.font(14.5)).foregroundColor(T.text)
                        Text(server.useTLS
                             ? "wss://（反向代理 TLS）"
                             : "当前 ws:// 无加密 · 可信局域网 / 反向代理启用 wss")
                            .font(T.font(11))
                            .foregroundColor(T.text3)
                    }
                    Spacer()
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        }
    }

    private var deleteRow: some View {
        Button {
            showDeleteConfirm = true
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "trash")
                    .font(.system(size: 12))
                    .foregroundColor(T.red)
                    .frame(width: 30, height: 30)
                    .background(T.redDim)
                    .clipShape(RoundedRectangle(cornerRadius: 9))
                VStack(alignment: .leading, spacing: 1) {
                    Text("删除此服务器")
                        .font(T.font(14.5, .semibold))
                        .foregroundColor(T.red)
                    Text("二次确认后清除 Keychain 令牌")
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                }
                Spacer()
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rL))
        .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("l5-act-del")
    }
}

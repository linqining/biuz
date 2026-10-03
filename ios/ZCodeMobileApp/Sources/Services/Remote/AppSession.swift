import SwiftUI

// MARK: - 桌面端只读信息模型（oauth / usage-stats 投影）

/// 桌面端 OAuth 登录展示态：provider 名单 + 当前 provider + 登录用户/过期态。
/// 纯只读（boundaryNotes ① 安全面）；不消费桌面凭据本体。
struct DesktopOAuthInfo: Equatable {
    var providers: [String] = []
    var activeProvider: String?
    var authenticated = false
    var requiresReauthentication = false
    var userName: String?
    var userHandle: String?
}

/// Coding Plan 用量只读投影（SettingsView 用户卡连接态数据源；nil 字段回退演示值）
struct CodingPlanUsageInfo: Equatable {
    var percentRemaining: Double?
    var used: Int?
    var limit: Int?
    var unitText: String?
    var resetsAtText: String?
}

// MARK: - 应用会话装配（OAuth 账户层 + 桌面配对连接层 + Store 装配策略）

/// 装配策略（需求原文）：
/// - OAuth 已登录或桌面配对成功 → 用真实 API 实现 Store 协议；
/// - 未登录且未配置服务器、或连接失败 → 回退演示数据（mock），离线可用；
/// - 冷启动未配置 → 直接进入演示模式（既有页面与 e2e 行为不变）。
@MainActor
@Observable
final class AppSession {

    enum Mode: Equatable {
        case demo                              // mock 演示
        case connecting(ServerConfig)          // 配对连接中
        case connected(ServerConfig)           // 已连接（真实 Store）
        case connectFailed(ServerConfig, ConnectError) // 连接失败（回退 mock + 横幅）
        case disconnected(ServerConfig, String) // 曾连接后断线
    }

    // MARK: 账户层（OAuth tokenSet）

    private(set) var oauthTokenSet: OAuthTokenSet?
    private(set) var oauthUserInfo: OAuthUserInfo?
    /// 订阅视图刷新用（「刷新额度」触发重算过期判定）
    var oauthSessionVersion = 0

    var isOAuthLoggedIn: Bool {
        guard let tokenSet = oauthTokenSet else { return false }
        return !OAuthCredentialStore.isExpired(tokenSet)
    }

    var isOAuthExpired: Bool {
        guard let tokenSet = oauthTokenSet else { return false }
        return OAuthCredentialStore.isExpired(tokenSet)
    }

    // MARK: 连接层

    private(set) var mode: Mode = .demo
    let connection = ZCodeServerConnection()

    // MARK: 全局流程唤起（登录主页 / 连接流程；由设置页、失败横幅等入口触发）

    enum PresentedFlow: Equatable {
        case login
        case connect(editTokenOnly: Bool)
    }

    var presentedFlow: PresentedFlow?

    func requestLoginFlow() {
        presentedFlow = .login
    }

    func requestConnectFlow(editTokenOnly: Bool) {
        presentedFlow = .connect(editTokenOnly: editTokenOnly)
    }

    func dismissFlow() {
        presentedFlow = nil
    }

    /// 连接成功后装配的真实 Store（未连接时为 nil，UI 走 mock 环境值）
    private(set) var remoteConversationStore: RemoteConversationStore?
    private(set) var remoteTaskStore: RemoteTaskStore?
    private(set) var remoteFileStore: RemoteFileStore?

    // MARK: 桌面端只读信息（oauth / usage-stats 只读面；连接态拉取，断开清空）

    /// 桌面端 OAuth 登录展示态（getProviders/getActiveProvider/restoreCachedSessionState
    /// 三只读投影；不消费桌面凭据本体）
    private(set) var desktopOAuthInfo: DesktopOAuthInfo?
    /// Coding Plan 用量只读投影（getCodingPlanUsageSnapshot/getCodingPlanResetStatus）
    private(set) var codingPlanUsage: CodingPlanUsageInfo?

    /// 供 UI 判断是否处于演示数据
    var isDemo: Bool {
        if case .connected = mode { return false }
        return true
    }

    var connectProgress: ConnectProgress? {
        if case .connecting = mode {
            if case .connecting(let progress) = connection.state { return progress }
        }
        return nil
    }

    var connectLogs: [ConnectLogLine] {
        connection.logs
    }

    /// 已保存服务器（最近连接一台，L4 多台口径：N>1 跳最近连接）
    var savedServer: ServerConfig? {
        ServerRegistry.selectedServer ?? ServerRegistry.servers.first
    }

    init() {
        // 断线回落（13-③ 黄色横幅）：连接成功后 WS/中继通道意外中断 → mode 切
        // .disconnected，RootView 展示「与桌面端的连接已断开 · 重连」；重连动作走
        // reconnect()（横幅重试同入口）。手动断开/连接期失败不经此路径。
        connection.onConnectionDropped = { [weak self] detail in
            guard let self, case .connected(let server) = self.mode else { return }
            self.mode = .disconnected(server, detail)
        }
        // E2E 钩子：须先于凭据加载执行（Keychain 跨进程启动持久，门禁用例需「未配置/未登录」起点）
        Self.performE2EStateResetIfNeeded()
        if let credentials = OAuthCredentialStore.load() {
            oauthTokenSet = credentials.tokenSet
            oauthUserInfo = credentials.userInfo
        }
    }

    /// E2E 专用：仅在启动参数携带 `-ZCodeE2EResetState` 时清空本机凭据态
    /// （账户层 tokenSet + 服务器注册表），保证登录/配对用例可重复、无顺序依赖；
    /// 不携带该参数时为空操作，不影响任何业务路径。
    static func performE2EStateResetIfNeeded(arguments: [String] = ProcessInfo.processInfo.arguments) {
        guard arguments.contains("-ZCodeE2EResetState") else { return }
        OAuthCredentialStore.clear()
        for server in ServerRegistry.servers {
            ServerRegistry.remove(id: server.id)
        }
    }

    // MARK: 冷启动

    /// 冷启动：已配置服务器 → 后台自动重连（失败回退演示 + 横幅）；未配置 → 演示模式。
    /// QA/E2E 钩子：`-ZCodeOpenLoginFlow` 直开 O1 登录主页；`-ZCodeOpenConnectFlow` 直开 L1 连接页；
    /// `-ZCodeRelayLink <url>` 解析中继配对链接并直接发起云中继连接（无 UI 驱动的真机验证，
    /// 模式同 -ZCodeOpenConnectFlow；链接失效保持演示态）。
    func bootstrap() {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-ZCodeOpenLoginFlow") {
            presentedFlow = .login
        } else if arguments.contains("-ZCodeOpenConnectFlow") {
            presentedFlow = .connect(editTokenOnly: false)
        } else if let relayURL = Self.relayLinkArgument(from: arguments) {
            Task { await connectRelayLink(relayURL) }
            return
        }
        if let server = savedServer {
            mode = .connecting(server)
            Task { await connectToSaved(server) }
        } else {
            mode = .demo
        }
    }

    /// `-ZCodeRelayLink <url>` 取值（相邻参数；纯函数不触 UI 态）
    nonisolated static func relayLinkArgument(from arguments: [String]) -> String? {
        guard let index = arguments.firstIndex(of: "-ZCodeRelayLink"),
              index + 1 < arguments.count else { return nil }
        let value = arguments[index + 1]
        return value.hasPrefix("-") ? nil : value
    }

    /// 中继链接直连入口（调试钩子 / L1 粘贴链接共用）：解析 → 持久化 → 连接
    func connectRelayLink(_ rawLink: String) async {
        guard let link = ConnectURLParser.parseRelayLink(rawLink) else {
            mode = .demo
            return
        }
        // 中继服务器在注册表内按 mid/sid 复用（重复连接不膨胀列表）
        let existing = ServerRegistry.servers.first { $0.relay?.deviceSid == link.deviceSid }
        var server = ServerConfig(
            id: existing?.id ?? UUID().uuidString,
            name: existing?.name,
            host: link.endpointHost ?? "zcode.z.ai",
            port: 443,
            useTLS: true,
            token: "",
            lastConnectedAt: existing?.lastConnectedAt,
            preferredWorkspacePath: existing?.preferredWorkspacePath,
            relay: link)
        mode = .connecting(server)
        await connectToSaved(server)
    }

    private func connectToSaved(_ server: ServerConfig) async {
        let result: Result<ServerRemoteInfo, ConnectError>
        if server.relay != nil {
            result = await connection.connectRelay(to: server)
        } else {
            result = await connection.connect(to: server, preferredWorkspace: server.preferredWorkspacePath)
        }
        switch result {
        case .success(let info):
            // 先装配 Store 再切 mode（装配完成后 UI 才切换数据源）
            assembleRemoteStores(info: info, workspace: connection.workspace)
            mode = .connected(server)
            // 连接成功即收起连接流程（L2 五步完成的落点 = 已连接主界面；冷启动自动重连时本就无流程在展示）
            if presentedFlow != nil {
                dismissFlow()
            }
            var updated = server
            updated.lastConnectedAt = Date()
            if updated.name == nil { updated.name = info.name }
            ServerRegistry.upsert(updated)
            ServerRegistry.selectedServerID = updated.id
            // 桌面端只读信息（oauth 登录展示态 + Coding Plan 用量）后台拉取；失败各字段保持 nil
            Task { await refreshDesktopReadonlyInfo() }
        case .failure(let error):
            teardownRemoteStores()
            mode = .connectFailed(server, error)
        }
    }

    /// 连接态拉取桌面只读信息（oauth 三读 + usage-stats 两读）；断开/重连时刷新
    func refreshDesktopReadonlyInfo() async {
        guard connection.isActive else {
            desktopOAuthInfo = nil
            codingPlanUsage = nil
            return
        }
        desktopOAuthInfo = await Self.fetchDesktopOAuthInfo(connection: connection)
        codingPlanUsage = await Self.fetchCodingPlanUsage(connection: connection)
    }

    /// oauth 只读三接口 → 展示态（provider 名单/当前 provider/登录用户与过期态）。
    /// 全部失败容忍（逐一 try?），任一成功即产出。
    private static func fetchDesktopOAuthInfo(connection: ZCodeServerConnection) async -> DesktopOAuthInfo? {
        var info = DesktopOAuthInfo()
        if let providersResult = try? await connection.call("oauth", "getProviders", .undefined) {
            let json = providersResult.jsonValue
            info.providers = (json?.arrayValue ?? json?["providers"]?.arrayValue ?? [])
                .compactMap { $0.stringValue ?? $0.objectValue?["providerId"]?.stringValue ?? $0.objectValue?["name"]?.stringValue }
        }
        if let activeResult = try? await connection.call("oauth", "getActiveProvider", .undefined) {
            let json = activeResult.jsonValue
            info.activeProvider = json?.stringValue
                ?? json?["providerId"]?.stringValue
                ?? json?["name"]?.stringValue
        }
        if let stateResult = try? await connection.call("oauth", "restoreCachedSessionState", .undefined),
           let dict = stateResult.jsonValue?.objectValue {
            switch dict["status"]?.stringValue {
            case "authenticated":
                info.authenticated = true
                let userInfo = dict["userInfo"]?.objectValue ?? dict["user"]?.objectValue
                info.userName = userInfo?["displayName"]?.stringValue
                info.userHandle = userInfo?["username"]?.stringValue
            case "reauthentication-required":
                info.authenticated = false
                info.requiresReauthentication = true
            default:
                info.authenticated = false
            }
        }
        if info.providers.isEmpty, info.activeProvider == nil,
           !info.authenticated, !info.requiresReauthentication {
            return nil
        }
        return info
    }

    /// usage-stats 两读 → 用量投影。accountAccess 按个人 Coding Plan 固定形态
    /// （zcodeProviderAccountAccessSchema：zai / individual-coding-plan）。
    /// 请求被服务端拒绝时投影为 nil（UI 回退演示额度行）。
    private static func fetchCodingPlanUsage(connection: ZCodeServerConnection) async -> CodingPlanUsageInfo? {
        let accountAccess = JSONValue.object([
            "type": .string("zhipu-account"),
            "accountType": .string("zai"),
            "mode": .string("individual-coding-plan"),
            "entitled": .bool(true),
        ])
        var usage = CodingPlanUsageInfo()
        var builder = JSONObjectBuilder()
        builder.set("range", "30d")
        builder.set("preferredProviderId", "zai")
        builder.set("accountAccess", accountAccess)
        if let result = try? await connection.call(
            "usage-stats", "getCodingPlanUsageSnapshot", .json(.object(builder.fields))),
           let dict = result.jsonValue?.objectValue {
            // quota = {level, limits:[{usage, unit, number, percentage, nextResetTime, …}]}
            if let limit = dict["quota"]?.objectValue?["limits"]?.arrayValue?.first {
                usage.used = limit["usage"]?.intValue
                usage.limit = limit["number"]?.intValue
                if let percentage = limit["percentage"]?.doubleValue {
                    usage.percentRemaining = max(0, min(1, 1 - percentage / 100))
                } else if let used = usage.used, let limit = usage.limit, limit > 0 {
                    usage.percentRemaining = max(0, min(1, Double(limit - used) / Double(limit)))
                }
                usage.unitText = limit["unit"]?.stringValue
                if let nextReset = limit["nextResetTime"]?.doubleValue, nextReset > 0 {
                    usage.resetsAtText = Self.shortFormatter.string(from: Date(timeIntervalSince1970: nextReset / 1000))
                }
            }
        }
        var resetBuilder = JSONObjectBuilder()
        resetBuilder.set("preferredProviderId", "zai")
        resetBuilder.set("accountAccess", accountAccess)
        if let result = try? await connection.call(
            "usage-stats", "getCodingPlanResetStatus", .json(.object(resetBuilder.fields))),
           let dict = result.jsonValue?.objectValue {
            // 重置窗口取最近可用 five-hour 机会（毫秒时间戳）
            if let expireAt = dict["availableFiveHourResets"]?.arrayValue?.first?.objectValue?["expireAt"]?.doubleValue,
               expireAt > 0 {
                usage.resetsAtText = Self.shortFormatter.string(from: Date(timeIntervalSince1970: expireAt / 1000))
            }
        }
        return usage.used != nil || usage.limit != nil || usage.percentRemaining != nil ? usage : nil
    }

    private static let shortFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M 月 d 日 HH:mm"
        return formatter
    }()

    // MARK: 配对流程（L1 → L2 → 成功/失败）

    func connect(server: ServerConfig) async {
        mode = .connecting(server)
        await connectToSaved(server)
    }

    func cancelConnecting() {
        connection.cancelConnecting()
        mode = .demo
    }

    /// L3「重新扫码更新令牌」：以新令牌更新已存服务器并重连
    func updateToken(for server: ServerConfig, token: String) async {
        var updated = server
        updated.token = token
        ServerRegistry.upsert(updated)
        await connect(server: updated)
    }

    /// L5 删除服务器：清 Keychain 令牌与最近连接记录（二次确认后调用）
    func deleteServer(_ server: ServerConfig) {
        ServerRegistry.remove(id: server.id)
        connection.disconnect()
        teardownRemoteStores()
        mode = .demo
    }

    /// 断线重连（横幅「重试」）
    func reconnect() async {
        if let server = savedServer {
            await connect(server: server)
        }
    }

    // MARK: OAuth

    func handleOAuthSuccess(tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo) {
        oauthTokenSet = tokenSet
        oauthUserInfo = userInfo
        oauthSessionVersion += 1
        // Keychain 写入已在流程内完成（OAuthLoginFlow 内部 save 成功后才回调成功）
    }

    /// 退出登录：仅清账户层 tokenSet，不动已保存服务器与连接令牌（9.5 两层凭据模型）
    func logout() {
        OAuthCredentialStore.clear()
        oauthTokenSet = nil
        oauthUserInfo = nil
        oauthSessionVersion += 1
    }

    // MARK: Store 装配

    private func assembleRemoteStores(info: ServerRemoteInfo, workspace: ServerWorkspaceInfo?) {
        guard let workspace else { return }
        let conversationStore = RemoteConversationStore(connection: connection, workspace: workspace)
        remoteConversationStore = conversationStore
        remoteTaskStore = RemoteTaskStore(connection: connection, workspace: workspace, conversationStore: conversationStore)
        remoteFileStore = RemoteFileStore(connection: connection, workspace: workspace)
        _ = info
    }

    private func teardownRemoteStores() {
        remoteConversationStore = nil
        remoteTaskStore = nil
        remoteFileStore = nil
        desktopOAuthInfo = nil
        codingPlanUsage = nil
    }

    // MARK: 连接测试（L4-B：1.5s 超时，仅探测不建 WS）

    func testConnection(to server: ServerConfig) async -> ServerInfoClient.ProbeResult {
        await ServerInfoClient.probe(server: server, timeout: 1.5)
    }

    /// 在线探测点（L1/L4：1.5s 超时，绿=可达 / 灰=不可达 / 半透明=未探测）。
    /// 中继服务器无 /api/server-info HTTP 面（在线性由连接态体现），跳过探测。
    func probeSavedServer() async -> ServerInfoClient.ProbeResult? {
        guard let server = savedServer else { return nil }
        if server.relay != nil { return nil }
        return await testConnection(to: server)
    }
}

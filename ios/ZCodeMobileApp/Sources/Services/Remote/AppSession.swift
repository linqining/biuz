import SwiftUI
import os

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
    /// 全部额度窗口（getCodingPlanUsageSnapshot quota.limits：5 小时/每周/每月；
    /// 此前只取首个窗口导致「额度不正确」且缺 5h/周/月分窗与重置时间）
    var windows: [CodingPlanQuotaWindow] = []
    /// 重置卡（getCodingPlanResetStatus；nil = 未取到）
    var resetCards: CodingPlanResetCards?
}

/// 重置卡信息（2026-10-05 取证：availableFiveHourResets/availableWeekResets 数组 +
/// latestFiveHourResetHistory/latestWeekResetHistory.usedAt + hasUnreadHistory）
struct CodingPlanResetCards: Equatable {
    var fiveHourCount: Int = 0
    var weekCount: Int = 0
    /// 最早过期时间（任一卡）
    var earliestExpireText: String?
    /// 最近一次使用（5h 窗）
    var lastFiveHourUsedText: String?
    /// 最近一次使用（周窗）
    var lastWeekUsedText: String?
}

/// 单个额度窗口（level 词表宽容映射：five-hour/weekly/monthly）
struct CodingPlanQuotaWindow: Equatable, Identifiable {
    var level: String
    var label: String
    var used: Int?
    var limit: Int?
    var unit: String?
    var percentUsed: Double?
    var resetsAtText: String?
    var id: String { level }

    /// 剩余比例（0~1；无 percentage 时由 used/limit 推导）
    var percentRemaining: Double? {
        if let percentUsed {
            return max(0, min(1, 1 - percentUsed / 100))
        }
        guard let used, let limit, limit > 0 else { return nil }
        return max(0, min(1, Double(limit - used) / Double(limit)))
    }
}

/// 使用统计快照（usage-stats.getAppUsageSnapshot；桌面「使用统计」页同源数据）
struct AppUsageInfo: Equatable {
    var summary: AppUsageSummary
    var models: [AppUsageModelSlice]
    var daily: [AppUsageDailyPoint]
    var tools: [AppUsageToolStat]
}

struct AppUsageSummary: Equatable {
    var totalTokens: Double?
    var peakDayTokens: Double?
    var longestSessionMs: Int?
    var currentStreakDays: Int?
    var longestStreakDays: Int?
    var totalSessions: Int?
    var totalTurns: Int?
    var activeDays: Int?
    var cacheHitRate: Double?
    var favoriteModelId: String?
    var favoriteModelShare: Double?
}

/// 模型用量占比（桌面 donut 的移动端等价呈现：占比条 + 百分比）
struct AppUsageModelSlice: Equatable, Identifiable {
    var modelId: String
    var totalTokens: Double
    var share: Double?
    var id: String { modelId }
}

/// 每日各模型 token（趋势折线数据点；date=yyyy-MM-dd）
struct AppUsageDailyPoint: Equatable {
    var date: String
    var byModel: [String: Double]
}

/// 工具调用统计
struct AppUsageToolStat: Equatable, Identifiable {
    var toolName: String
    var callCount: Int
    var avgDurationMs: Double
    var errorRate: Double?
    var id: String { toolName }
}

/// 会话排队消息（桌面 composer 上方的 pending 队列；state.queue 同源投影）
struct RemoteQueueItem: Equatable, Identifiable {
    /// queueItemId（sendQueuedNow/editQueueItem/deleteQueueItem/reorderQueueItem 的定位键）
    var id: String
    var text: String
    /// delivery.admitted（"queue"=排队等待 / "guide"=引导模式待发）
    var admitted: String?

    var isGuide: Bool { admitted == "guide" }
}

struct ConversationQueueInfo: Equatable {
    var items: [RemoteQueueItem]
    /// 自动排空（桌面 queue.autoDrain；setAutoDrain 命令切换）
    var autoDrain: Bool
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
        // 语言偏好一并复位（P1 语言切换持久化后，保证用例间无顺序依赖）
        UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        // 来源过滤档位一并复位（会话列表 chips 持久化键）：门禁轮次中被强杀的用例会把
        // 「云端沙盒/我的 Mac」档残留到下次冷启——cloud 档隐藏 source=="mac" 的全部行，
        // 后续用例的「演示行/替身行在场」首断言即全军覆没（第 1 轮门禁 Matrix test01/
        // Feature test03~06 实证）。复位到「全部」= 列表用例的确定性起点。
        UserDefaults.standard.removeObject(forKey: "list.sourceFilter.v1")
        // 执行目标偏好一并复位（chat.target.*：per-conversation + 全局默认）：上一轮
        // 选过的「E2E-Relay-Mac」残留会让下一轮的胶囊开局即回显 Mac——目标选择器用例
        // 的「初始云端沙盒」前提与「菜单候选命中」判定全部失真（本轮门禁 Feature
        // test07 line754 实证）。清空后回退云端沙盒 = 选择器用例的确定性起点。
        for key in UserDefaults.standard.dictionaryRepresentation().keys
        where key.hasPrefix("chat.target.") {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    // MARK: 冷启动

    /// 冷启动：已配置服务器 → 后台自动重连（失败回退演示 + 横幅）；未配置 → 演示模式。
    /// QA/E2E 钩子：`-ZCodeOpenLoginFlow` 直开 O1 登录主页；`-ZCodeOpenConnectFlow` 直开 L1 连接页；
    /// `-ZCodeRelayLink <url>` 解析中继配对链接并直接发起云中继连接（无 UI 驱动的真机验证，
    /// 模式同 -ZCodeOpenConnectFlow；链接失效保持演示态）。
    func bootstrap() async {
        let arguments = ProcessInfo.processInfo.arguments
        UserDefaults.standard.set("args=\(arguments.count) relayArg=\(Self.relayLinkArgument(from: arguments) ?? "nil")", forKey: "diag.args")
        if arguments.contains("-ZCodeOpenLoginFlow") {
            presentedFlow = .login
        } else if arguments.contains("-ZCodeOpenConnectFlow") {
            presentedFlow = .connect(editTokenOnly: false)
        } else if let relayURL = Self.relayLinkArgument(from: arguments) {
            await connectRelayLink(relayURL)
            return
        }
        // Keychain 在冷启动瞬间偶发 errSecMissingEntitlement（模拟器已知抖动），
        // ServerRegistry 会把读取错误吞成空数组 → 误判"未配置"进演示模式；
        // 短退避重读 3 次再判定。
        var server = savedServer
        if server == nil {
            for _ in 0..<3 {
                try? await Task.sleep(nanoseconds: 600_000_000)
                server = savedServer
                if server != nil { break }
            }
        }
        if let server {
            UserDefaults.standard.set("bootstrap: relay=\(server.relay != nil) count=\(ServerRegistry.servers.count) host=\(server.host):\(server.port)", forKey: "diag.lastConnect")
            mode = .connecting(server)
            await connectToSaved(server)
        } else {
            UserDefaults.standard.set("bootstrap: savedServer=nil(含重试) servers=\(ServerRegistry.servers.count) selected=\(ServerRegistry.selectedServerID ?? "nil") → demo", forKey: "diag.lastConnect")
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
        let server = ServerConfig(
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
        // 持久化（本函数注释所诺的第二步，此前遗漏）：连接前置写入注册表——失败也保留
        // 设备，OAuth 直达自动连接与 Composer 目标菜单（ExecutionTargetStore.machines）
        // 均以注册表 relay 项为准（门禁实测：漏 upsert 导致「暂未发现已配对的桌面设备」
        // 误引导与目标菜单缺 E2E-Relay-Mac 候选）
        ServerRegistry.upsert(server)
        await connectToSaved(server)
    }

    private func connectOnce(_ server: ServerConfig) async -> Result<ServerRemoteInfo, ConnectError> {
        if server.relay != nil {
            return await connection.connectRelay(to: server)
        }
        return await connection.connect(to: server, preferredWorkspace: server.preferredWorkspacePath)
    }

    private func connectToSaved(_ server: ServerConfig) async {
        var result = await connectOnce(server)
        // 云中继链路偶发瞬断（桌面端重启/网络抖动）：短退避自动重试两次再判失败
        if case .failure = result, server.relay != nil {
            for delayNs: UInt64 in [2_000_000_000, 4_000_000_000] {
                try? await Task.sleep(nanoseconds: delayNs)
                result = await connectOnce(server)
                if case .success = result { break }
            }
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
            UserDefaults.standard.set("connected: relay=\(server.relay != nil)", forKey: "diag.lastConnect")
            // 桌面端只读信息（oauth 登录展示态 + Coding Plan 用量）后台拉取；失败各字段保持 nil
            Task { await refreshDesktopReadonlyInfo() }
            // 诊断探针（-ZCodeDiagQueueCASProbe new）：PTY 受限期间队列 CAS/模型选择
            // schema 的活体验证入口——连接就绪后一次性运行，ack 落 diag.qcas.N；
            // （-ZCodeDiagStopProbe new）stop 命令信封活体验证，ack 落 diag.stop.N
            let probeArgs = ProcessInfo.processInfo.arguments
            func probeTarget(_ flag: String) -> String? {
                guard let i = probeArgs.firstIndex(of: flag),
                      i + 1 < probeArgs.count else { return nil }
                return probeArgs[i + 1]
            }
            if let target = probeTarget("-ZCodeDiagQueueCASProbe") {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 2_500_000_000) // stores/订阅就绪
                    guard let self else { return }
                    await self.remoteConversationStore?.runQueueCASProbeDiag(target: target)
                }
            }
            if let target = probeTarget("-ZCodeDiagStopProbe") {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    guard let self else { return }
                    await self.remoteConversationStore?.runStopProbeDiag(target: target)
                }
            }
            if probeArgs.contains("-ZCodeDiagCleanupProbe") {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    guard let self else { return }
                    await self.remoteConversationStore?.runCleanupProbeDiag()
                }
            }
        case .failure(let error):
            UserDefaults.standard.set("failed: relay=\(server.relay != nil) host=\(server.host):\(server.port) error=\(String(describing: error))", forKey: "diag.lastConnect")
            teardownRemoteStores()
            mode = .connectFailed(server, error)
        }
    }

    /// 连接态拉取桌面只读信息（oauth 三读 + usage-stats 两读 + 多 workspace 枚举探测）；
    /// 断开/重连时刷新
    func refreshDesktopReadonlyInfo() async {
        guard connection.isActive else {
            desktopOAuthInfo = nil
            codingPlanUsage = nil
            appUsageSnapshot = nil
            return
        }
        desktopOAuthInfo = await Self.fetchDesktopOAuthInfo(connection: connection)
        codingPlanUsage = await Self.fetchCodingPlanUsage(connection: connection)
        appUsageSnapshot = await Self.fetchAppUsageSnapshot(connection: connection)
        await probeRemoteControlBootstrap()
    }

    /// 多 workspace 枚举探测（web 远控 REST 面，2026-10-06 bundle 取证）：
    /// `GET {relayOrigin}/api/remote-control/windows/bootstrap/{token}` →
    /// `{workspaces, tasks, mobileViewState|initialViewState}`——web 端工作区切换器
    /// 数据源（listWorkspaces 即此端点，非 relay 命令通道）。web 的 token 来自页面
    /// 参数 remoteControlToken；移动端以配对链接 sid 试探（是否同 token 族由回执定）：
    /// 2xx → diag 落 workspace 清单（多 workspace 接线依据）；401/404 → diag 落
    /// 状态码（token 不同族实据）。GET 只读，无副作用。
    private func probeRemoteControlBootstrap() async {
        guard let relay = savedServer?.relay, !relay.deviceSid.isEmpty,
              let server = savedServer else { return }
        let origin = server.useTLS ? "https://\(server.host)" : "http://\(server.host)"
        guard let url = URL(string: "\(origin)/api/remote-control/windows/bootstrap/\(relay.deviceSid)") else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let workspaces = json["workspaces"] as? [[String: Any]] ?? []
                let paths = workspaces.compactMap {
                    ($0["workspacePath"] as? String) ?? ($0["path"] as? String) ?? ($0["workspaceKey"] as? String)
                }
                let firstShape = workspaces.first.flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                    .flatMap { String(data: $0, encoding: .utf8) } ?? "-"
                UserDefaults.standard.set(
                    "status=\(status) topKeys=\(json.keys.sorted().joined(separator: "|"))"
                        + " workspaces=\(workspaces.count) [\(paths.joined(separator: " | ").prefix(300))]"
                        + " first=\(firstShape.prefix(300))",
                    forKey: "diag.remote.bootstrap")
            } else {
                UserDefaults.standard.set(
                    "status=\(status) body=\(String(data: data, encoding: .utf8)?.prefix(200) ?? "?")",
                    forKey: "diag.remote.bootstrap")
            }
        } catch {
            UserDefaults.standard.set(
                "ERR \(String(describing: error).prefix(200))", forKey: "diag.remote.bootstrap")
        }
        UserDefaults.standard.synchronize()
    }

    /// 使用统计快照（usage-stats.getAppUsageSnapshot {range, timeZone}；
    /// 桌面「使用统计」页同源：summary 指标 + models 占比 + dailyModelUsage 趋势 + tools）
    private(set) var appUsageSnapshot: AppUsageInfo?

    private static func fetchAppUsageSnapshot(connection: ZCodeServerConnection) async -> AppUsageInfo? {
        var builder = JSONObjectBuilder()
        builder.set("range", "30d")
        builder.set("timeZone", TimeZone.current.identifier)
        guard let result = try? await usageStatsCall(connection, "getAppUsageSnapshot", builder.fields),
              let dict = result.jsonValue?.objectValue else {
            return nil
        }
        var summary = AppUsageSummary()
        if let s = dict["summary"]?.objectValue {
            summary = AppUsageSummary(
                totalTokens: s["totalTokens"]?.doubleValue,
                peakDayTokens: s["peakDayTokens"]?.doubleValue,
                longestSessionMs: s["longestSessionMs"]?.intValue,
                currentStreakDays: s["currentStreakDays"]?.intValue,
                longestStreakDays: s["longestStreakDays"]?.intValue,
                totalSessions: s["totalSessions"]?.intValue,
                totalTurns: s["totalTurns"]?.intValue,
                activeDays: s["activeDays"]?.intValue,
                cacheHitRate: s["cacheHitRate"]?.doubleValue,
                favoriteModelId: s["favoriteModel"]?.objectValue?["modelId"]?.stringValue,
                favoriteModelShare: s["favoriteModel"]?.objectValue?["share"]?.doubleValue)
        }
        let models: [AppUsageModelSlice] = (dict["models"]?.arrayValue ?? []).compactMap { m in
            guard let d = m.objectValue, let modelId = d["modelId"]?.stringValue else { return nil }
            return AppUsageModelSlice(
                modelId: modelId,
                totalTokens: d["totalTokens"]?.doubleValue ?? 0,
                share: d["share"]?.doubleValue)
        }
        let daily: [AppUsageDailyPoint] = (dict["dailyModelUsage"]?.arrayValue ?? []).compactMap { point in
            guard let d = point.objectValue, let date = d["date"]?.stringValue else { return nil }
            var byModel: [String: Double] = [:]
            for entry in d["models"]?.arrayValue ?? [] {
                guard let e = entry.objectValue, let modelId = e["modelId"]?.stringValue else { continue }
                byModel[modelId] = e["totalTokens"]?.doubleValue ?? 0
            }
            return AppUsageDailyPoint(date: date, byModel: byModel)
        }
        let tools: [AppUsageToolStat] = (dict["tools"]?.arrayValue ?? []).compactMap { t in
            guard let d = t.objectValue, let toolName = d["toolName"]?.stringValue else { return nil }
            return AppUsageToolStat(
                toolName: toolName,
                callCount: d["callCount"]?.intValue ?? 0,
                avgDurationMs: d["avgDurationMs"]?.doubleValue ?? 0,
                errorRate: d["errorRate"]?.doubleValue)
        }
        guard summary.totalTokens != nil || !models.isEmpty || !daily.isEmpty else { return nil }
        return AppUsageInfo(summary: summary, models: models, daily: daily, tools: tools)
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
    /// usage-stats 读面统一入口：桌面端对并发快照请求拒绝（"Request in progress,
    /// please wait"——连接刷新与用量页 task 竞态时实测），首败 1.2s 退避重试一次
    /// （同 loadOlder 中继瞬断口径）；重试仍败则原错误上抛（调用方取证）。
    private static func usageStatsCall(
        _ connection: ZCodeServerConnection, _ command: String,
        _ args: [String: JSONValue]) async throws -> RPCValue {
        do {
            return try await connection.call("usage-stats", command, .json(.object(args)))
        } catch {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            return try await connection.call("usage-stats", command, .json(.object(args)))
        }
    }

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
        // preferredProviderId 必须用注册表完整 id（「zai」匹配不到任何 provider，
        // 桌面端落到 bigmodel API-key 面 → no_bigmodel_api_key）；web 端另带 timeZone
        builder.set("preferredProviderId", "account:zai-individual-coding-plan")
        builder.set("accountAccess", accountAccess)
        builder.set("timeZone", TimeZone.current.identifier)
        let snapshotResult: RPCValue?
        do {
            snapshotResult = try await usageStatsCall(connection, "getCodingPlanUsageSnapshot", builder.fields)
        } catch {
            // 失败不再静默（「桌面端未返回 Coding Plan 用量」的取证口）
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    "err=\(String(describing: error).prefix(500))", forKey: "diag.usage.snapshot.error")
                UserDefaults.standard.synchronize()
            }
            snapshotResult = nil
        }
        if let dict = snapshotResult?.jsonValue?.objectValue {
            // 取证（一次性）：quota.limits 全量窗口形态（level 词表定位 5h/周/月）
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
               UserDefaults.standard.string(forKey: "diag.usage.snapshot") == nil {
                UserDefaults.standard.set(
                    String(describing: dict), forKey: "diag.usage.snapshot")
            }
            // quota = {level:"max"|…, limits:[…]}；窗口真实形态（2026-10-05 取证）：
            // ① {type:"TIME_LIMIT", unit:5, usage:总量, remaining, currentValue:已用,
            //    percentage:已用%, nextResetTime, usageDetails[]} → 5 小时条数窗
            // ②③ {type:"TOKENS_LIMIT", unit, number, percentage:已用%, nextResetTime}
            //    → 每周/每月 token 窗（无绝对数值，只有百分比）
            let limits = dict["quota"]?.objectValue?["limits"]?.arrayValue ?? []
            var tokenWindowIndex = 0
            usage.windows = limits.compactMap { limit in
                guard let d = limit.objectValue else { return nil }
                let type = d["type"]?.stringValue ?? ""
                let isTimeLimit = type == "TIME_LIMIT"
                let label: String
                var used: Int?
                var limitValue: Int?
                if isTimeLimit {
                    label = String(localized: "5 小时")
                    used = d["currentValue"]?.intValue
                    limitValue = d["usage"]?.intValue
                } else {
                    tokenWindowIndex += 1
                    label = tokenWindowIndex == 1
                        ? String(localized: "每周")
                        : tokenWindowIndex == 2
                            ? String(localized: "每月")
                            : String(localized: "Token 窗口 \(tokenWindowIndex)")
                }
                var window = CodingPlanQuotaWindow(
                    level: type.isEmpty ? "window" : type,
                    label: label,
                    used: used,
                    limit: limitValue,
                    unit: isTimeLimit ? String(localized: "条") : nil,
                    percentUsed: d["percentage"]?.doubleValue,
                    resetsAtText: nil)
                if let nextReset = d["nextResetTime"]?.doubleValue, nextReset > 0 {
                    window.resetsAtText = Self.shortFormatter.string(
                        from: Date(timeIntervalSince1970: nextReset / 1000))
                }
                return window
            }
            // 套餐档位（quota.level："max" 等）记入 unitText 供卡片角标
            usage.unitText = dict["quota"]?.objectValue?["level"]?.stringValue
            // 主窗口（legacy 字段兼容 SettingsView 用户卡）：5 小时窗优先，否则首个
            let primary = usage.windows.first {
                $0.level == "TIME_LIMIT"
            } ?? usage.windows.first
            if let primary {
                usage.used = primary.used
                usage.limit = primary.limit
                usage.percentRemaining = primary.percentRemaining
                usage.resetsAtText = primary.resetsAtText
            }
        }
        var resetBuilder = JSONObjectBuilder()
        resetBuilder.set("preferredProviderId", "account:zai-individual-coding-plan")
        resetBuilder.set("accountAccess", accountAccess)
        if let dict = try? await usageStatsCall(
            connection, "getCodingPlanResetStatus", resetBuilder.fields),
           let dict = dict.jsonValue?.objectValue {
            // 取证（一次性）：重置卡全量形态
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
               UserDefaults.standard.string(forKey: "diag.usage.reset") == nil {
                UserDefaults.standard.set(
                    String(describing: dict).prefix(1200), forKey: "diag.usage.reset")
            }
            // 重置卡全量解析：5h/周两组可用卡 + 最早过期 + 最近使用历史
            var cards = CodingPlanResetCards()
            let fiveHour = dict["availableFiveHourResets"]?.arrayValue ?? []
            let week = dict["availableWeekResets"]?.arrayValue ?? []
            cards.fiveHourCount = fiveHour.count
            cards.weekCount = week.count
            let allExpiries = (fiveHour + week).compactMap {
                $0.objectValue?["expireAt"]?.doubleValue
            }.filter { $0 > 0 }
            if let earliest = allExpiries.min() {
                cards.earliestExpireText = Self.shortFormatter.string(
                    from: Date(timeIntervalSince1970: earliest / 1000))
            }
            if let usedAt = dict["latestFiveHourResetHistory"]?.objectValue?["usedAt"]?.doubleValue,
               usedAt > 0 {
                cards.lastFiveHourUsedText = Self.shortFormatter.string(
                    from: Date(timeIntervalSince1970: usedAt / 1000))
            }
            if let usedAt = dict["latestWeekResetHistory"]?.objectValue?["usedAt"]?.doubleValue,
               usedAt > 0 {
                cards.lastWeekUsedText = Self.shortFormatter.string(
                    from: Date(timeIntervalSince1970: usedAt / 1000))
            }
            usage.resetCards = cards
            // 注意：usage.resetsAtText 保留额度窗口自身 nextResetTime（自动重置时间）；
            // 重置卡的 expireAt 是「卡过期时间」，语义不同——不能覆写（曾致用户卡显示
            // 卡过期日当额度重置日，数据对不上）
        }
        // 取证（一次性）：App 用量统计快照（summary/模型趋势/工具用量形状定位）
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
           UserDefaults.standard.string(forKey: "diag.usage.app") == nil {
            var appBuilder = JSONObjectBuilder()
            appBuilder.set("range", "30d")
            appBuilder.set("timeZone", TimeZone.current.identifier)
            if let result = try? await connection.call(
                "usage-stats", "getAppUsageSnapshot", .json(.object(appBuilder.fields))),
               let json = result.jsonValue {
                // 聚焦未取证数组的首元素形状
                var report = "topKeys=\(json.objectValue?.keys.sorted().joined(separator: "|") ?? "?")"
                for key in ["dailyModelUsage", "heatmap"] {
                    if let array = json[key]?.arrayValue {
                        report += " || \(key)(\(array.count)) first=\(String(describing: array.first).prefix(600))"
                        if array.count > 1 {
                            report += " second=\(String(describing: array[1]).prefix(200))"
                        }
                    }
                }
                UserDefaults.standard.set(String(report.prefix(2400)), forKey: "diag.usage.app")
            }
        }
        return usage.used != nil || usage.limit != nil || usage.percentRemaining != nil ? usage : nil
    }

    private static let shortFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M 月 d 日 HH:mm"
        return formatter
    }()

    // MARK: 重置卡（usage-stats 三步；web 端 resetType ∈ FIVE_HOUR|WEEK）

    enum CodingPlanResetType: String {
        case fiveHour = "FIVE_HOUR"
        case week = "WEEK"
    }

    /// 使用重置卡（桌面代执行三步，web reset-cards 同构；用户要求仅接线不自动触发）：
    /// ① requestCodingPlanResetOpportunity {scope, idempotencyKey} 幂等探测
    /// ② useCodingPlanReset {scope, idempotencyKey, resetType} 使用（FIVE_HOUR|WEEK）
    /// ③ markCodingPlanResetHistoryRead {scope} 清桌面未读角标
    /// scope = {workspaceKey, remoteSessionId:"", sessionId:""}（web 端缺省空串）。
    /// 成功后刷新额度/重置状态投影；返回用户可读反馈（nil = 成功）
    func useCodingPlanResetCard(type: CodingPlanResetType) async -> String? {
        guard connection.isActive else {
            return String(localized: "未连接桌面端")
        }
        let scope: JSONValue = .object([
            "workspaceKey": .string(
                connection.workspace?.workspaceIdentity ?? connection.workspace?.path ?? ""),
            "remoteSessionId": .string(""),
            "sessionId": .string(""),
        ])
        let idempotencyKey = UUID().uuidString
        // ① 幂等探测领取机会
        var request = JSONObjectBuilder()
        request.set("scope", scope)
        request.set("idempotencyKey", idempotencyKey)
        _ = try? await connection.call(
            "usage-stats", "requestCodingPlanResetOpportunity", .json(.object(request.fields)))
        // ② 使用重置卡
        var use = JSONObjectBuilder()
        use.set("scope", scope)
        use.set("idempotencyKey", idempotencyKey)
        use.set("resetType", type.rawValue)
        do {
            _ = try await connection.call(
                "usage-stats", "useCodingPlanReset", .json(.object(use.fields)))
        } catch {
            return String(localized: "领取失败 · \(error.localizedDescription)")
        }
        // ③ 清历史未读角标（失败不影响结果）
        var mark = JSONObjectBuilder()
        mark.set("scope", scope)
        _ = try? await connection.call(
            "usage-stats", "markCodingPlanResetHistoryRead", .json(.object(mark.fields)))
        // 额度/重置状态回流刷新
        await refreshDesktopReadonlyInfo()
        return nil
    }

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

    // MARK: 登录直达会话（项 1）

    /// 登录后设备直达结果（OAuthSuccessView 呈现）
    enum AutoConnectOutcome: Equatable {
        case connected              // 已连上桌面端（cover 由 dismissFlow 收起，直达主界面）
        case noPairedDevice         // 无已配对设备、剪贴板亦无链接 → 引导粘贴链接
        case failed(String)         // 尝试了已知设备/链接但连接失败 → 引导 + 上次目标
    }

    /// 登录成功后自动发现设备并连接（P0·登录直达会话）。
    ///
    /// 绑定假设（调研 deviceListApi 结论）：**账号级设备列表云端 API 未上线**——登录态
    /// （zcodeJwtToken）下没有任何可发现桌面设备的云端接口（web bundle 的远控登录页
    /// 也停在「设备列表能力即将接入」），且中继模式无 server-info HTTP 面可探测在线态。
    /// 因此"设备列表"取本机最贴近来源：
    /// ① ServerRegistry 已配对的中继服务器（relay 凭据仅存 Keychain）——最近连接优先
    ///   （"在线优先"的本地代理口径），直接走既有连接链路（内置 2s/4s 退避重试）；
    /// ② 无已配对设备时回退剪贴板中的 remote/v4 配对链接（官方链接传递路径，
    ///   ConnectHomeView 同源识别）→ connectRelayLink。
    /// 失败/无设备均返回 outcome，由调用方给出可行动提示（引导粘贴链接，不静默）。
    func autoConnectAfterLogin() async -> AutoConnectOutcome {
        let relayServers = ServerRegistry.servers
            .filter { $0.relay != nil }
            .sorted { ($0.lastConnectedAt ?? .distantPast) > ($1.lastConnectedAt ?? .distantPast) }
        if let target = relayServers.first {
            UserDefaults.standard.set(
                "autoConnect: relay \(target.displayName) sid=\(target.relay?.deviceSid ?? "?")",
                forKey: "diag.autoConnect")
            await connect(server: target)
            if case .connected = mode { return .connected }
            // 失败：清理失败覆盖态回演示底座，交由引导 UI（不静默、不留半开连接）
            cancelConnecting()
            return .failed(target.displayName)
        }
        // 剪贴板兜底在 E2E 下禁用：测试机剪贴板残渣可能触发真实网络连接拖垮门禁用例
        // （E2E reset 已清 ServerRegistry，正常路径恒走 noPairedDevice 立即返回）
        guard !ProcessInfo.processInfo.arguments.contains("-ZCodeE2EResetState"),
              let clipboard = UIPasteboard.general.string,
              ConnectURLParser.parseRelayLink(clipboard) != nil else {
            UserDefaults.standard.set("autoConnect: no paired device", forKey: "diag.autoConnect")
            return .noPairedDevice
        }
        UserDefaults.standard.set("autoConnect: clipboard relay link", forKey: "diag.autoConnect")
        await connectRelayLink(clipboard)
        if case .connected = mode { return .connected }
        cancelConnecting()
        return .failed("剪贴板配对链接")
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
        UserDefaults.standard.set("assemble: workspace=\(workspace == nil ? "nil!" : (workspace?.path ?? "?")) info=\(String(describing: info).prefix(200))", forKey: "diag.assemble")
        guard let workspace else { return }
        let conversationStore = RemoteConversationStore(connection: connection, workspace: workspace)
        // 多 workspace：连接期 workspace-list-response 采集的桌面全部工作区回写
        // （聚合任务列表用；active 在首位，单 workspace 行为不变）。store 是 actor，
        // 经 actor 方法回写（跨 actor 隔离）
        let workspaces = info.workspaces
        Task {
            await conversationStore.setAllWorkspaces(workspaces)
            let bootstrapTasks = await connection.relayBootstrapTasks
            await conversationStore.setBootstrapTasks(bootstrapTasks)
        }
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

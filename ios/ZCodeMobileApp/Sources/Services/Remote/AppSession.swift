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

/// Coding Plan 用量只读投影（SettingsView 用户卡连接态数据源；无数据 UI 不渲染
/// 进度条与百分比——H10，不再回退演示值）
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
    /// 最早过期（5 小时卡组）——功能上 5h/周卡分别作用于对应窗口，分开展示
    var fiveHourEarliestText: String?
    /// 最早过期（周卡组）
    var weekEarliestText: String?
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
    /// id 用 label（同 type 的两个窗——5 小时/每周都叫 TOKENS_LIMIT——level 作 id
    /// 会触发 SwiftUI 重复 id 双渲染：第二行被画成第一行，用户见「两个 5 小时」）
    var id: String { label.isEmpty ? level : label }

    /// 剩余比例（0~1；无 percentage 时由 used/limit 推导）
    var percentRemaining: Double? {
        if let percentUsed {
            return max(0, min(1, 1 - percentUsed / 100))
        }
        guard let used, let limit, limit > 0 else { return nil }
        return max(0, min(1, Double(limit - used) / Double(limit)))
    }
}

/// 套餐权益条目（usage-stats.getEntitlementSnapshot 的 subscription.details[] 元素；
/// web $Fe 同构：productName 为主名，expireTime 已过期的条目过滤不展示）
struct CodingPlanEntitlement: Equatable, Identifiable {
    var name: String
    /// 数值/额度文案（web 回执无此层，恒 nil；保留渲染位）
    var value: String?
    var detail: String?
    var id: String { name }
}

/// 套餐权益快照（P3-9 额度页；2026-10-06 按 web 口径重写——审查报告 B-5）：
/// nil = 调用失败/未连接（UI 诚实降级）；noPlan = 未订阅套餐（web 空态判定）；
/// entitlements 空 = 调用成功但无在期订阅条目（UI 不渲染子块）
struct CodingPlanEntitlementInfo: Equatable {
    /// 套餐档位（web IAt 同构：quota.level 优先，回落 subscription.details[0].productName）
    var tier: String?
    var entitlements: [CodingPlanEntitlement] = []
    /// unavailableReason === "no_plan"（未订阅套餐——web 空态文案口径）
    var noPlan = false
    /// 其他不可用原因（not_configured/not_authenticated/unavailable 等；noPlan 时冗余）
    var unavailableReason: String?
    /// 顶层 remaining（web 仅做在场判定；单位语义未取证，原样字符串透出）
    var remainingText: String?
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

/// 装配策略（对齐修复后，《对齐修复与降级体验设计》§0/§1）：
/// - 连接成功 → 真实 API 实现 Store 协议（remote 三件）；
/// - 断线/连接中 → 保留现有 Store 引用（断线保留最后快照，不回落引导页）；
/// - 未配对/连接失败 → 空实现 Store（**未连接 ≠ 演示**：无假数据；未配对=连接引导
///   页为根，失败=空 Tab + 红横幅重试）。
/// Mock 演示数据仅启动参数携带 `-ZCodeDemoData`（E2E 演示开关）时装配——用户裁决
/// 「Mock 假数据全部移除，仅测试用例允许」；正式用户路径永不装配。
@MainActor
@Observable
final class AppSession {

    enum Mode: Equatable {
        case demo                              // 未配对（连接引导页为根；-ZCodeDemoData 下演示数据）
        case connecting(ServerConfig)          // 配对连接中
        case connected(ServerConfig)           // 已连接（真实 Store）
        case connectFailed(ServerConfig, ConnectError) // 连接失败（空数据 + 红横幅重试）
        case disconnected(ServerConfig, String) // 曾连接后断线（保留最后快照 + 橙横幅）
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

    /// Store 装配世代（P3-10 多工作区切换）：assembleRemoteStores 成功与
    /// teardownRemoteStores 时 +1。App 层环境值换绑挂载点（ZCodeMobileApp 以
    /// .task(id: storeEpoch) 驱动 syncStoresWithSession 重跑）——同 .connected 内换
    /// 工作区不改变 mode（Mode: Equatable 只含 ServerConfig），需 epoch 变化触发环境值
    /// 换绑，Store 新实例再经各页 .task(id: ObjectIdentifier(store)) 自动重拉
    private(set) var storeEpoch = 0

    /// 当前装配的工作区路径（P1 重连缓存迁移判定用：teardown 清空；同路径重连
    /// =export/adopt 行缓存跨 Store 重建保留，异路径=工作区切换不迁移）
    private var assembledWorkspacePath: String?

    // MARK: 桌面端只读信息（oauth / usage-stats 只读面；连接态拉取，断开清空）

    /// 桌面端 OAuth 登录展示态（getProviders/getActiveProvider/restoreCachedSessionState
    /// 三只读投影；不消费桌面凭据本体）
    private(set) var desktopOAuthInfo: DesktopOAuthInfo?
    /// Coding Plan 用量只读投影（getCodingPlanUsageSnapshot/getCodingPlanResetStatus）
    private(set) var codingPlanUsage: CodingPlanUsageInfo?
    /// 套餐权益只读投影（getEntitlementSnapshot；P3-9 额度页「当前套餐权益」子块数据源）
    private(set) var codingPlanEntitlements: CodingPlanEntitlementInfo?

    /// E2E Mock 激活开关（用户裁决「Mock 假数据全部移除，仅测试用例允许」的落地，
    /// 设计稿 §1.7.2）：仅启动参数携带 `-ZCodeDemoData` 时未连接态装配 Mock 三件
    /// （演示页脚/演示清单等演示态 UI 随之保留）；正式用户路径永不装配——未连接 =
    /// 空数据/连接引导，非「演示」。测试面零 Mock 编译引用（调研实证），改动集中在
    /// 启动参数与装配层一处 if。
    static let demoDataArgument = "-ZCodeDemoData"
    static var isDemoDataEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains(demoDataArgument)
    }

    /// Mock 演示数据是否在场（isDemo 语义清理：**未连接 ≠ 演示**——仅 E2E 开关激活
    /// 且未连接时为 true；正式路径恒 false。连接态 gating 请用 isConnected）
    var isDemo: Bool {
        guard Self.isDemoDataEnabled else { return false }
        if case .connected = mode { return false }
        return true
    }

    /// 是否已连接桌面端（便捷属性，设计稿 §1.6：横幅/DiffActionBar/入口 gating 用；
    /// P2ExtrasViews 各页私有 isConnected 先例上移统一）
    var isConnected: Bool {
        if case .connected = mode { return true }
        return false
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
        // .disconnected（不 teardown——remote Store 与缓存快照保留，RootView 展示
        // 「已断开 · 显示断线前的数据 · 重连」）；重连动作走 reconnect()（横幅重试
        // 同入口）。手动断开/连接期失败不经此路径。
        connection.onConnectionDropped = { [weak self] detail in
            guard let self, case .connected(let server) = self.mode else { return }
            self.mode = .disconnected(server, detail)
        }
        // 工作区清单推送（P3-10 workspace-list-updated）：清单本体已由 connection 回写
        // serverInfo（切换器菜单联动）；此处刷新任务聚合清单（键级整体替换口径）
        connection.onWorkspaceListUpdated = { [weak self] workspaces in
            guard let self else { return }
            Task {
                await self.remoteConversationStore?.setAllWorkspaces(workspaces)
            }
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

    /// 冷启动：已配置服务器 → 后台自动重连（失败空态 + 红横幅重试）；未配置 → 连接
    /// 引导页为根（demo 态，无假数据）。
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
            codingPlanEntitlements = nil
            appUsageSnapshot = nil
            return
        }
        desktopOAuthInfo = await Self.fetchDesktopOAuthInfo(connection: connection)
        codingPlanUsage = await Self.fetchCodingPlanUsage(connection: connection)
        appUsageSnapshot = await Self.fetchAppUsageSnapshot(connection: connection)
        // 权益快照顺读链末位（usage-stats 对并发快照请求拒绝——见 usageStatsCall 注释，
        // 与上方两读保持串行，不 async let）
        codingPlanEntitlements = await Self.fetchCodingPlanEntitlements(connection: connection)
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
    /// 请求被服务端拒绝时投影为 nil（UI 走「额度未获取」诚实文案，H10）。
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

    // MARK: usage-stats 个人 Coding Plan 统一参数（web bundle 取证 2026-10-06）

    /// preferredProviderId 必须用注册表完整 id（「zai」短 id 匹配不到任何 provider，
    /// 桌面端落到 bigmodel API-key 面 → no_bigmodel_api_key——§9.10 实证先例）
    static let codingPlanProviderID = "account:zai-individual-coding-plan"

    /// web 端 accountAccess 统一形状【实证·bundle 逆向】：所有 usage-stats 调用点恒传
    /// `{type:'zhipu-account', family, planKind}`（换算函数 ET：access.accountType→family、
    /// access.mode→planKind；family 由 provider id 推导——account:zai-* → 'zai'）。
    /// 此前发注册表 access 形态 {accountType,mode,entitled}（bundle 另一 zod schema jb
    /// 的形状）——当前桌面两种都收，按 web 对齐防未来收紧落错面（审查报告 L-2）。
    static let codingPlanAccountAccess: JSONValue = .object([
        "type": .string("zhipu-account"),
        "family": .string("zai"),
        "planKind": .string("individual-coding-plan"),
    ])

    private static func fetchCodingPlanUsage(connection: ZCodeServerConnection) async -> CodingPlanUsageInfo? {
        let accountAccess = Self.codingPlanAccountAccess
        var usage = CodingPlanUsageInfo()
        var builder = JSONObjectBuilder()
        builder.set("range", "30d")
        // preferredProviderId 完整 id 口径见 codingPlanProviderID 注释；web 端另带 timeZone
        builder.set("preferredProviderId", Self.codingPlanProviderID)
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
            // quota = {level:"max"|…, limits:[…], mcpQuota?}。
            // 窗口映射（2026-10-06 桌面 web bundle 精确取证，app.asar 内 U5/JH 选择器）：
            // 按 type+unit+number 三元组匹配（与数组顺序无关）：
            //   5 小时   = TOKENS_LIMIT, unit:3, number:5（v2/v3 百分比窗）
            //   每周     = TOKENS_LIMIT, unit:6
            //   工具调用 = TIME_LIMIT,   unit:5, number:1（v1 条数窗：usage 总量/
            //            currentValue 已用/remaining 剩余）
            //   ZCode MCP = quota.mcpQuota.aggregate（独立字段）
            // 百分比口径（web vGt/yGt 同构，2026-10-06 校正——旧注释「0–1 已用小数」
            // 是错误口径勿再引用）：percentage 为 **0–100 已用百分数** → 剩余 = 100 − pct；
            // v1 条数套餐 percentage 缺席时 remaining/number×100 兜底（分母是 number，
            // 不是 usage）。未识别条目回退 generic 行（≤3）。
            let limits = dict["quota"]?.objectValue?["limits"]?.arrayValue ?? []

            func findLimit(_ type: String, unit: Int, number: Int? = nil) -> JSONValue? {
                for limit in limits {
                    guard let d = limit.objectValue,
                          d["type"]?.stringValue?.uppercased() == type.uppercased(),
                          d["unit"]?.intValue == unit else { continue }
                    if let number, d["number"]?.intValue != number { continue }
                    return limit
                }
                return nil
            }

            // 剩余百分比（web vGt 同构）：percentage 为 **0–100 已用百分数** → 剩余 = 100 − pct；
            // v1 条数套餐 percentage 缺席时 remaining/number×100 兜底（分母 number，web
            // vGt 原式——旧 remaining/usage×100 是拍脑袋口径，已校正）
            func remainingPercent(_ d: [String: JSONValue]?) -> Double? {
                guard let d else { return nil }
                if let pct = d["percentage"]?.doubleValue {
                    return max(0, min(100, 100 - pct))
                }
                if let remaining = d["remaining"]?.doubleValue,
                   let number = d["number"]?.doubleValue, number > 0 {
                    return max(0, min(100, remaining / number * 100))
                }
                return nil
            }

            func makeWindow(_ label: String, _ d: [String: JSONValue]?) -> CodingPlanQuotaWindow? {
                guard let d else { return nil }
                var window = CodingPlanQuotaWindow(
                    level: d["type"]?.stringValue ?? "window",
                    label: label,
                    used: d["currentValue"]?.intValue,
                    limit: d["usage"]?.intValue,
                    unit: d["currentValue"] != nil ? String(localized: "条") : nil,
                    percentUsed: nil,
                    resetsAtText: nil)
                if let remaining = remainingPercent(d) {
                    window.percentUsed = 100 - remaining // struct: percentRemaining=1-percentUsed/100
                }
                if let nextReset = d["nextResetTime"]?.doubleValue, nextReset > 0 {
                    window.resetsAtText = Self.shortFormatter.string(
                        from: Date(timeIntervalSince1970: nextReset / 1000))
                }
                return window
            }

            var windows: [CodingPlanQuotaWindow] = []
            if let w = makeWindow(String(localized: "5 小时"),
                                  findLimit("TOKENS_LIMIT", unit: 3, number: 5)?.objectValue) { windows.append(w) }
            if let w = makeWindow(String(localized: "每周"),
                                  findLimit("TOKENS_LIMIT", unit: 6)?.objectValue) { windows.append(w) }
            if let w = makeWindow(String(localized: "工具调用"),
                                  findLimit("TIME_LIMIT", unit: 5, number: 1)?.objectValue) { windows.append(w) }
            // 未识别条目回退 generic 行（web：slice(0,3)，label「额度」）
            if windows.isEmpty {
                for d in limits.prefix(3).compactMap({ $0.objectValue }) {
                    let type = d["type"]?.stringValue ?? ""
                    if let w = makeWindow(type.isEmpty ? String(localized: "额度") : type, d) {
                        windows.append(w)
                    }
                }
            }
            usage.windows = windows
            // ZCode MCP（独立字段 mcpQuota.aggregate；web 同 label「ZCode MCP」）
            if let mcp = dict["quota"]?.objectValue?["mcpQuota"]?.objectValue?["aggregate"]?.objectValue,
               var w = makeWindow(String(localized: "ZCode MCP"), mcp) {
                w.level = "MCP"
                usage.windows.append(w)
            }
            // 套餐档位（quota.level："max" 等）记入 unitText 供卡片角标
            usage.unitText = dict["quota"]?.objectValue?["level"]?.stringValue
            // 主窗口（legacy 字段兼容 SettingsView 用户卡）：5 小时窗优先
            let primary = usage.windows.first { $0.label == String(localized: "5 小时") }
                ?? usage.windows.first
            if let primary {
                usage.used = primary.used
                usage.limit = primary.limit
                usage.percentRemaining = primary.percentRemaining
                usage.resetsAtText = primary.resetsAtText
            }
        }
        var resetBuilder = JSONObjectBuilder()
        resetBuilder.set("preferredProviderId", Self.codingPlanProviderID)
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
            // 过期时间按组分开展示（5h 卡只重置 5h 窗、周卡只重置周窗，语义不同
            // ——用户裁决：不能合并取最早）
            func earliestText(_ group: [JSONValue]) -> String? {
                group.compactMap { $0.objectValue?["expireAt"]?.doubleValue }
                    .filter { $0 > 0 }
                    .min()
                    .map { Self.shortFormatter.string(from: Date(timeIntervalSince1970: $0 / 1000)) }
            }
            cards.fiveHourEarliestText = earliestText(fiveHour)
            cards.weekEarliestText = earliestText(week)
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

    /// 套餐权益快照（usage-stats.getEntitlementSnapshot；P3-9 额度页）。
    /// 入参与回执均按 web 口径【实证·bundle 逆向 2026-10-06，审查报告 B-5——原记录
    /// 「仅 {preferredProviderId} + 回执 entitlements[]|benefits[]|features[]|items[]
    /// 列表宽容解析」整体推翻：回执无任何列表键，旧解析在任何路径下都落空】：
    /// 入参六键 {includeSubscription:true, preferredProviderId, accountAccess,
    /// allowDisabledPreferredProvider:true, requirePreferredProvider:true,
    /// allowEnvApiKey:false}；回执 {provider, authenticated?, unavailableReason?,
    /// quota:{level,limits}, subscription:{details:[{productName,productId,
    /// expireTime?}]}, remaining?}。
    /// 调用失败 → nil；成功但无在期订阅条目 → 空 entitlements（UI 不渲染子块）。
    /// 诊断：diag.usage.entitlement 一次性原始回执（diag.wf.mode 存在才写；§11 登记，验收后清理）。
    private static func fetchCodingPlanEntitlements(connection: ZCodeServerConnection) async -> CodingPlanEntitlementInfo? {
        var builder = JSONObjectBuilder()
        builder.set("includeSubscription", true)
        builder.set("preferredProviderId", Self.codingPlanProviderID)
        builder.set("accountAccess", Self.codingPlanAccountAccess)
        builder.set("allowDisabledPreferredProvider", true)
        builder.set("requirePreferredProvider", true)
        builder.set("allowEnvApiKey", false)
        let result: RPCValue?
        do {
            result = try await usageStatsCall(connection, "getEntitlementSnapshot", builder.fields)
        } catch {
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    String("err=\(String(describing: error).prefix(500))"), forKey: "diag.usage.entitlement")
                UserDefaults.standard.synchronize()
            }
            return nil
        }
        guard let json = result?.jsonValue else { return CodingPlanEntitlementInfo(tier: nil, entitlements: []) }
        let dict = json.objectValue
        // 一次性取证：原始回执形态（下次连真桌面验收后回写 §9.10 并清理诊断键）
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
           UserDefaults.standard.string(forKey: "diag.usage.entitlement") == nil {
            UserDefaults.standard.set(
                String(String(describing: json).prefix(2000)), forKey: "diag.usage.entitlement")
            UserDefaults.standard.synchronize()
        }
        var info = CodingPlanEntitlementInfo(tier: nil, entitlements: [])
        info.unavailableReason = dict?["unavailableReason"]?.stringValue
        info.noPlan = info.unavailableReason == "no_plan"
        // authenticated 显式为 false 时无订阅可言（web $Fe 同构过滤）；键缺席视为已认证
        let authenticated = dict?["authenticated"]?.boolValue ?? true
        let details = authenticated
            ? (dict?["subscription"]?.objectValue?["details"]?.arrayValue ?? [])
            : []
        // 权益条目 = subscription.details[]（web $Fe：过期条目按 expireTime 过滤；
        // 名称取 productName，缺席回落 productId）
        let entitlements: [CodingPlanEntitlement] = details.compactMap { item in
            guard let d = item.objectValue else { return nil }
            let name = d["productName"]?.stringValue ?? d["productId"]?.stringValue
            guard let name, !name.isEmpty, !Self.entitlementEntryExpired(d["expireTime"]) else { return nil }
            let detail = Self.entitlementEntryDate(d["expireTime"]).map {
                String(localized: "\(Self.shortFormatter.string(from: $0)) 前有效")
            }
            return CodingPlanEntitlement(name: name, value: nil, detail: detail)
        }
        // 档位（web IAt 同构）：quota.level 优先，回落首个条目名
        info.tier = dict?["quota"]?.objectValue?["level"]?.stringValue
            ?? entitlements.first?.name
        info.entitlements = entitlements
        // 顶层 remaining（web 仅做在场判定；单位语义未取证，原样透出不加解释）
        if let remaining = dict?["remaining"], !remaining.isNull {
            info.remainingText = remaining.stringValue
                ?? remaining.intValue.map { String($0) }
                ?? remaining.doubleValue.map { String(format: "%g", $0) }
        }
        return info
    }

    /// subscription.details[].expireTime → Date（web Date.parse 同构 = ISO 字符串；
    /// 数字形态宽容按毫秒时间戳）。返回 nil = 无法解析（不过滤、不展示）
    private static func entitlementEntryDate(_ value: JSONValue?) -> Date? {
        if let ms = value?.doubleValue, ms > 0 {
            return Date(timeIntervalSince1970: ms / 1000)
        }
        if let text = value?.stringValue {
            if let date = Self.isoMilliFormatter.date(from: text) {
                return date
            }
            return Self.isoFormatter.date(from: text)
        }
        return nil
    }

    private static func entitlementEntryExpired(_ value: JSONValue?) -> Bool {
        guard let date = Self.entitlementEntryDate(value) else { return false }
        return date <= Date()
    }

    private static let isoMilliFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        return formatter
    }()

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

    private static let usageStatsLogger = Logger(subsystem: "cn.biuz.mobile", category: "usage-stats")

    /// 使用重置卡（桌面代执行三步，web reset-cards 同构；用户要求仅接线不自动触发）。
    /// 参数口径【实证·bundle 逆向 2026-10-06，审查报告 B-6——原嵌套 scope 形态推翻】：
    /// web 端「scope」只是前端内存变量名/去重键（序列化键 b1e 只用于 Map 缓存），服务端
    /// 三步全部平铺：
    /// ① requestCodingPlanResetOpportunity {preferredProviderId, accountAccess, idempotencyKey}
    /// ② useCodingPlanReset {preferredProviderId, accountAccess, idempotencyKey, resetType}
    /// ③ markCodingPlanResetHistoryRead {preferredProviderId, accountAccess}
    /// 成功后刷新额度/重置状态投影；返回用户可读反馈（nil = 成功）
    func useCodingPlanResetCard(type: CodingPlanResetType) async -> String? {
        guard connection.isActive else {
            return String(localized: "未连接桌面端")
        }
        let idempotencyKey = UUID().uuidString
        // ① 幂等探测领取机会（web 手动路径同构：探测失败不阻断用卡，仅记日志——
        // 用卡资格以先前的 getCodingPlanResetStatus 读数为准，UI 按钮本就以它 gating）
        var request = JSONObjectBuilder()
        request.set("preferredProviderId", Self.codingPlanProviderID)
        request.set("accountAccess", Self.codingPlanAccountAccess)
        request.set("idempotencyKey", idempotencyKey)
        do {
            _ = try await Self.usageStatsCall(
                connection, "requestCodingPlanResetOpportunity", request.fields)
        } catch {
            Self.usageStatsLogger.warning(
                "requestCodingPlanResetOpportunity 失败（不阻断用卡）: \(error.localizedDescription)")
        }
        // ② 使用重置卡（平铺四键；失败如实回传 UI）
        var use = JSONObjectBuilder()
        use.set("preferredProviderId", Self.codingPlanProviderID)
        use.set("accountAccess", Self.codingPlanAccountAccess)
        use.set("idempotencyKey", idempotencyKey)
        use.set("resetType", type.rawValue)
        do {
            _ = try await Self.usageStatsCall(connection, "useCodingPlanReset", use.fields)
        } catch {
            return String(localized: "领取失败 · \(error.localizedDescription)")
        }
        // ③ 清历史未读角标（web fire-and-forget 同构：失败仅记日志，不影响用卡结果）
        var mark = JSONObjectBuilder()
        mark.set("preferredProviderId", Self.codingPlanProviderID)
        mark.set("accountAccess", Self.codingPlanAccountAccess)
        do {
            _ = try await Self.usageStatsCall(
                connection, "markCodingPlanResetHistoryRead", mark.fields)
        } catch {
            Self.usageStatsLogger.warning(
                "markCodingPlanResetHistoryRead 失败（不影响用卡结果）: \(error.localizedDescription)")
        }
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
        // 取消落态按场景分流（设计稿 §1.4，防快照丢失）：从未连上（remote Store 无
        // 实例）→ .demo（回连接引导页——无数据可保留）；断线重连中取消 →
        // .disconnected（快照保留 + 橙横幅 detail「已取消重连」，可随时再点重连）
        if remoteConversationStore != nil, case .connecting(let server) = mode {
            mode = .disconnected(server, String(localized: "已取消重连"))
        } else {
            mode = .demo
        }
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
            // 失败：清理失败覆盖态回引导底座（demo），交由引导 UI（不静默、不留半开连接）
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
        // P1 重连缓存迁移：同工作区重连时行/消息缓存跨 Store 重建保留（重连不再清空
        // 全部会话首屏缓存）；工作区切换不迁移——会话行属于原工作区
        let previousConversationStore = remoteConversationStore
        let reuseCaches = assembledWorkspacePath == workspace.path
        let conversationStore = RemoteConversationStore(connection: connection, workspace: workspace)
        assembledWorkspacePath = workspace.path
        if let previousConversationStore, reuseCaches {
            Task {
                let caches = await previousConversationStore.exportCaches()
                await conversationStore.adoptCaches(caches)
            }
        }
        // 多 workspace：连接期 workspace-list-response 采集的桌面全部工作区回写
        // （聚合任务列表用；active 在首位，单 workspace 行为不变）。store 是 actor，
        // 经 actor 方法回写（跨 actor 隔离）
        let workspaces = info.workspaces
        Task {
            await conversationStore.setAllWorkspaces(workspaces)
            let bootstrapTasks = await connection.relayBootstrapTasks
            // ⑤切换器枚举源（bootstrapWorkspaces 文档）：派生与注入同源同批
            bootstrapWorkspaces = Self.deriveBootstrapWorkspaces(bootstrapTasks)
            await conversationStore.setBootstrapTasks(bootstrapTasks)
        }
        remoteConversationStore = conversationStore
        remoteTaskStore = RemoteTaskStore(connection: connection, workspace: workspace, conversationStore: conversationStore)
        remoteFileStore = RemoteFileStore(connection: connection, workspace: workspace)
        _ = info
        // 世代 +1：触发 .task(id: storeEpoch) 重跑 syncStoresWithSession 完成环境值换绑
        //（P3-10 同 .connected 内换工作区时 mode 不变，靠 epoch 驱动）
        storeEpoch += 1
    }

    private func teardownRemoteStores() {
        remoteConversationStore = nil
        remoteTaskStore = nil
        remoteFileStore = nil
        desktopOAuthInfo = nil
        codingPlanUsage = nil
        codingPlanEntitlements = nil
        bootstrapWorkspaces = []
        assembledWorkspacePath = nil
        storeEpoch += 1
    }

    // MARK: 多工作区切换（P3-10）

    /// bootstrap.tasks 派生的工作区清单（⑤切换器枚举源，2026-10-06）：装配时自
    /// connection.relayBootstrapTasks 派生，断开清空。workspace-list-request 只回桌面
    /// 当前打开的工作区（AGENTS §6 v1.5 实测清单=1，非枚举源），跨工作区枚举只能靠
    /// bootstrap.tasks（实测 256 行/26 工作区，web「所有项目目录」同源）。
    private(set) var bootstrapWorkspaces: [ServerWorkspaceInfo] = []

    /// bootstrap.tasks → 工作区条目（web 同构【移植·bundle 逆向】：任务行按键
    /// `workspaceIdentity?.trim()||workspacePath` 归组进工作区，键即 workspaceKey
    /// ——bundle tc/Ia 同款公式；行内 workspaceIdentity 可选）。按 path 去重保序；
    /// label 取路径末段（Conversation.projectName 同款口径）。
    nonisolated static func deriveBootstrapWorkspaces(_ tasks: JSONValue?) -> [ServerWorkspaceInfo] {
        guard let items = tasks?.arrayValue else { return [] }
        var byPath: [String: ServerWorkspaceInfo] = [:]
        var order: [String] = []
        for item in items {
            guard let d = item.objectValue, let path = d["workspacePath"]?.stringValue,
                  !path.isEmpty else { continue }
            let identity = d["workspaceIdentity"]?.stringValue?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if byPath[path] == nil {
                byPath[path] = ServerWorkspaceInfo(
                    path: path,
                    label: path.split(separator: "/").last.map(String.init),
                    workspaceIdentity: identity?.isEmpty == false ? identity : nil)
                order.append(path)
            } else if let identity, !identity.isEmpty,
                      byPath[path]?.workspaceIdentity == nil {
                // 同工作区多行：补首个非空身份（远端工作区判别键，切换反查用）
                byPath[path]?.workspaceIdentity = identity
            }
        }
        return order.compactMap { byPath[$0] }
    }

    /// 切换器清单（⑤修复，2026-10-06 真机报障「切换器只剩 mtt_mobile」）：连接层采集
    /// 清单（workspace-list-response/updated，C-15 canBridge 已过滤、active 首位）∪
    /// bootstrap.tasks 派生全量，按 path 去重。派生条目无 kind 字段，按 C-15「kind
    /// 缺席视为非 remote」恒可桥（web 对任务归组的工作区同口径，不过滤）。
    var switcherWorkspaces: [ServerWorkspaceInfo] {
        var merged: [ServerWorkspaceInfo] = []
        var seenPaths: Set<String> = []
        for entry in connection.serverInfo?.workspaces ?? [] where !seenPaths.contains(entry.path) {
            seenPaths.insert(entry.path)
            merged.append(entry)
        }
        for entry in bootstrapWorkspaces where !seenPaths.contains(entry.path) {
            seenPaths.insert(entry.path)
            merged.append(entry)
        }
        return merged
    }

    /// 切换在途守卫（并发切换串行化，P3-10 §10.3）：上一笔切换事务未完成时新请求
    /// 拒绝并提示——切换器 UI 的 isSwitching 禁用只覆盖单视图入口，此处为会话级
    /// 纵深防御（切换器之外的未来入口/竞态复入不得把 Store/桥留中间态）
    private var isSwitchingWorkspace = false

    /// 切换活动工作区（中继连接）：connection.switchRelayWorkspace（桥重开 →
    /// 订阅重定向 → workspace-config 重订）→ 以新工作区重跑 assembleRemoteStores，
    /// epoch 触发环境值换绑（各页经新 Store 实例自动 loading → 新数据，会话/文件/
    /// 任务三面板同换视角）。返回 nil = 成功；非 nil = 用户可读失败原因（含底层
    /// detail；失败时原工作区 Store、connection.workspace 与桥均保持不动——
    /// 失败口径由 connection 层桥回滚保证，RelayChannelClient.switchBridgeWorkspace）。
    func switchWorkspace(to target: ServerWorkspaceInfo) async -> String? {
        guard case .connected = mode else {
            return String(localized: "未连接桌面端")
        }
        guard !isSwitchingWorkspace else {
            return String(localized: "已有工作区切换在进行中，请稍后再试")
        }
        isSwitchingWorkspace = true
        defer { isSwitchingWorkspace = false }
        switch await connection.switchRelayWorkspace(to: target) {
        case .success(let workspace):
            // 完成后复核连接态：切换在途期间断线/重连会重装配 Store（connectToSaved），
            // 迟到的成功事务不得覆盖重连链路的新 Store——重连自会以正确工作区装配
            guard case .connected = mode, let info = connection.serverInfo else {
                return String(localized: "切换结果未落定 · 连接已变化，请重试")
            }
            assembleRemoteStores(info: info, workspace: workspace)
            return nil
        case .failure(let error):
            // 失败带出底层原因（原 Store/工作区不动）；.transport 携桥层原文
            //（桌面拒绝 reason / 超时 / 并发拒绝），其余形态回退 headline
            let detail: String
            if case .transport(let message) = error { detail = message }
            else { detail = error.headline }
            return String(localized: "切换失败 · 已保持当前工作区（\(detail)）")
        }
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

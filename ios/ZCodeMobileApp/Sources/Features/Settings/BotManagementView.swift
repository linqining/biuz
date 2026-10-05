import SwiftUI

// MARK: - IM Bot 域（G-001~G-006）：经通道层消费桌面 botsService 只读四方法 +
// 放行后的合法远控写（绑定码/解绑/删除/移除密钥/测试/上下文重置）。
//
// 桌面基准（开源 v3.14.3 packages/services/src/bots/botsService.ts:5176-5397 与
// packages/ui/src/BotsDialog.tsx）：
// - 读：listBots→BotConfig[]、getStatus→{botsCount,enabledBotsCount,contextsCount,
//   botRuntime:[{botId,provider,status,message,…}]}、getBotStates→BotState[]（工作区上下文）
// - 写：saveBot{bot,credentialValue?}（解绑=清 providerUserId/displayName 后整对象回传，
//   BotsDialog.tsx:1027-1035）、resetBotState{botId}、deleteBot{botId}、
//   removeBotSecret{botId}、testBot{botId}→{ok,message,provider?}、
//   createBindCode{botId}→{code,expiresAt}（TTL 30s，绑定命令 `/bind <code>`，BotsDialog.tsx:1015-1017）
// 这些写均落在桌面宿主侧 Bot 配置/凭据存储，非手机直写仓库——属「发命令由桌面执行」合法远控面。

/// Bot 配置投影（BotConfig 子集 + 原始 JSON 保底，写操作回传完整对象）
struct BotInfo: Identifiable, Equatable {
    var id: String
    var name: String
    var provider: String
    var enabled: Bool
    var providerUserId: String?
    var displayName: String?
    var credentialRef: String?
    /// 原始 BotConfig JSON（saveBot 整对象回传用；解绑时摘除绑定身份键）
    var raw: JSONValue?

    var isBound: Bool { !(providerUserId ?? "").isEmpty }
    var hasCredential: Bool { !(credentialRef ?? "").isEmpty }

    var providerLabel: String {
        switch provider {
        case "telegram": return "Telegram"
        case "feishu": return "飞书"
        case "lark": return "Lark"
        case "weixin": return "微信"
        default: return provider
        }
    }

    var providerIcon: String {
        switch provider {
        case "telegram": return "paperplane.fill"
        case "feishu", "lark": return "message.fill"
        case "weixin": return "message.circle.fill"
        default: return "app.badge.fill"
        }
    }
}

/// 运行态（getStatus.botRuntime 投影）
struct BotRuntimeInfo: Equatable {
    var botId: String
    var status: String      // disabled|idle|polling|connected|error
    var message: String?
    var deliveryError: String?

    var statusLabel: String {
        switch status {
        case "connected": return "已连接"
        case "polling": return "轮询中"
        case "idle": return "空闲"
        case "disabled": return "已停用"
        case "error": return "异常"
        default: return status
        }
    }

    /// 状态点色（runtimeDot 桌面同构：绿=连通/轮询，灰=空闲/停用，红=异常）
    var tint: Color {
        switch status {
        case "connected", "polling": return T.accent
        case "error": return T.red
        default: return T.text3
        }
    }
}

/// 工作区上下文（getBotStates 投影；「N 个绑定上下文」= contextsCount）
struct BotStateInfo: Identifiable, Equatable {
    var id: String
    var botId: String
    var workspacePath: String
    var mode: String?
    var activeTaskId: String?

    var workspaceName: String {
        workspacePath.split(separator: "/").last.map(String.init) ?? workspacePath
    }
}

/// 绑定码（createBindCode 回执 {code, expiresAt}；TTL 30s 桌面同构）
struct BotBindCode: Equatable {
    var code: String
    var expiresAt: Date
    var ttlMs: Int = 30_000

    var remainingSeconds: Int {
        max(0, Int(expiresAt.timeIntervalSinceNow.rounded()))
    }
    var isExpired: Bool { remainingSeconds <= 0 }
}

/// 连通性测试结果（testBot → BotTestResult {ok, message, provider?}，桌面同构无独立错误码字段）
struct BotTestOutcome: Equatable {
    var ok: Bool
    var message: String
    var provider: String?
}

/// IM Bot 存储：连接态经通道层读/写桌面 botsService；演示态（未连接）恒空列表。
@MainActor
@Observable
final class BotStore {
    private(set) var bots: [BotInfo] = []
    private(set) var runtimes: [String: BotRuntimeInfo] = [:]
    private(set) var states: [BotStateInfo] = []
    private(set) var contextsCount = 0
    private(set) var isLoading = false
    private(set) var lastError: String?
    /// 当前绑定码（详情页展开展示；30s 过期由 UI 倒计时呈现）
    var bindCode: BotBindCode?
    /// botId → 最近一次测试结果
    var testResults: [String: BotTestOutcome] = [:]
    /// 最近一次写操作反馈（成功/失败提示行）
    var actionNotice: String?

    /// 连接态拉取（三读并行；逐一容忍失败，任一成功即产出）
    func refresh(connection: ZCodeServerConnection) async {
        isLoading = true
        lastError = nil
        defer { isLoading = false }
        async let listTask: Void = loadBots(connection: connection)
        async let statusTask: Void = loadStatus(connection: connection)
        async let statesTask: Void = loadStates(connection: connection)
        _ = await (listTask, statusTask, statesTask)
        if bots.isEmpty, runtimes.isEmpty, states.isEmpty, lastError == nil {
            lastError = "桌面端未返回 IM Bot 数据"
        }
    }

    private func loadBots(connection: ZCodeServerConnection) async {
        do {
            let result = try await connection.call("bots", "listBots", .undefined)
            let items = result.jsonValue?.arrayValue
                ?? result.jsonValue?["bots"]?.arrayValue
                ?? result.jsonValue?["result"]?.arrayValue
                ?? []
            bots = items.compactMap(Self.parseBot)
        } catch {
            lastError = "listBots 失败 · \(error.localizedDescription)"
        }
    }

    private func loadStatus(connection: ZCodeServerConnection) async {
        guard let result = try? await connection.call("bots", "getStatus", .undefined),
              let dict = result.jsonValue?.objectValue else { return }
        contextsCount = dict["contextsCount"]?.intValue ?? 0
        var next: [String: BotRuntimeInfo] = [:]
        for item in dict["botRuntime"]?.arrayValue ?? [] {
            guard let d = item.objectValue, let botId = d["botId"]?.stringValue else { continue }
            next[botId] = BotRuntimeInfo(
                botId: botId,
                status: d["status"]?.stringValue ?? "idle",
                message: d["message"]?.stringValue,
                deliveryError: d["deliveryError"]?.stringValue)
        }
        runtimes = next
    }

    private func loadStates(connection: ZCodeServerConnection) async {
        guard let result = try? await connection.call("bots", "getBotStates", .undefined) else { return }
        let items = result.jsonValue?.arrayValue
            ?? result.jsonValue?["result"]?.arrayValue
            ?? result.jsonValue?["states"]?.arrayValue
            ?? []
        states = items.enumerated().compactMap { index, item in
            Self.parseState(item, index: index)
        }
    }

    /// 生成绑定码（createBindCode{botId} → {code, expiresAt}）
    func createBindCode(connection: ZCodeServerConnection, botID: String) async {
        bindCode = nil
        let arg = RPCValue.jsonObject { $0.set("botId", botID) }
        do {
            let result = try await connection.call("bots", "createBindCode", arg)
            guard let dict = result.jsonValue?.objectValue,
                  let code = dict["code"]?.stringValue else {
                actionNotice = "绑定码生成失败 · 回执缺 code"
                return
            }
            let ttl = dict["ttlMs"]?.intValue ?? 30_000
            let expiresAt = dict["expiresAt"].flatMap { $0.doubleValue }
                .map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date().addingTimeInterval(TimeInterval(ttl / 1000))
            bindCode = BotBindCode(code: code, expiresAt: expiresAt, ttlMs: ttl)
            actionNotice = nil
        } catch {
            actionNotice = "绑定码生成失败 · \(error.localizedDescription)"
        }
    }

    /// 解绑（桌面 BotsDialog.tsx:1027-1035 同构）：saveBot 摘除 providerUserId/displayName
    /// 整对象回传 + resetBotState；仅清绑定身份，不删 Bot 配置。
    func unbind(connection: ZCodeServerConnection, bot: BotInfo) async -> Bool {
        let saved = await saveBot(connection: connection, bot: bot, droppingBindingIdentity: true)
        if saved {
            _ = await resetBotState(connection: connection, botID: bot.id, silent: true)
            actionNotice = "已解绑 · \(bot.name)"
            await refresh(connection: connection)
        }
        return saved
    }

    /// 删除 Bot（deleteBot{botId}）
    func delete(connection: ZCodeServerConnection, bot: BotInfo) async -> Bool {
        let arg = RPCValue.jsonObject { $0.set("botId", bot.id) }
        do {
            _ = try await connection.call("bots", "deleteBot", arg)
            actionNotice = "已删除 · \(bot.name)"
            bindCode = nil
            await refresh(connection: connection)
            return true
        } catch {
            actionNotice = "删除失败 · \(error.localizedDescription)"
            return false
        }
    }

    /// 移除密钥（removeBotSecret{botId}；桌面同构：同步清理绑定状态，回未配置凭据态）
    func removeSecret(connection: ZCodeServerConnection, bot: BotInfo) async -> Bool {
        let arg = RPCValue.jsonObject { $0.set("botId", bot.id) }
        do {
            _ = try await connection.call("bots", "removeBotSecret", arg)
            actionNotice = "已移除密钥 · \(bot.name)"
            await refresh(connection: connection)
            return true
        } catch {
            actionNotice = "移除密钥失败 · \(error.localizedDescription)"
            return false
        }
    }

    /// 连通性测试（testBot{botId} → {ok, message, provider?}）
    func test(connection: ZCodeServerConnection, bot: BotInfo) async {
        let arg = RPCValue.jsonObject { $0.set("botId", bot.id) }
        do {
            let result = try await connection.call("bots", "testBot", arg)
            let dict = result.jsonValue?.objectValue ?? [:]
            testResults[bot.id] = BotTestOutcome(
                ok: dict["ok"]?.boolValue ?? false,
                message: dict["message"]?.stringValue ?? "",
                provider: dict["provider"]?.stringValue)
        } catch {
            testResults[bot.id] = BotTestOutcome(ok: false, message: error.localizedDescription, provider: nil)
        }
    }

    /// 重置工作区上下文（resetBotState{botId}；UI 入口为 per-context 清退）
    func resetBotState(connection: ZCodeServerConnection, botID: String, silent: Bool = false) async -> Bool {
        let arg = RPCValue.jsonObject { $0.set("botId", botID) }
        do {
            _ = try await connection.call("bots", "resetBotState", arg)
            if !silent {
                actionNotice = "已重置该工作区上下文"
            }
            await loadStates(connection: connection)
            return true
        } catch {
            if !silent {
                actionNotice = "重置失败 · \(error.localizedDescription)"
            }
            return false
        }
    }

    /// saveBot 整对象回传（G-043/044 泛化）：droppingBindingIdentity=解绑语义；
    /// credentialValue 非空时随 saveBot 保存凭据（桌面落 Keychain/凭据存储）；
    /// mutations 为调用方对原始 dict 的就地修改（启停 enabled 翻转等）
    private func saveBot(connection: ZCodeServerConnection, bot: BotInfo,
                         droppingBindingIdentity: Bool = false,
                         credentialValue: String? = nil,
                         mutations: (inout [String: JSONValue]) -> Void = { _ in }) async -> Bool {
        guard var rawDict = bot.raw?.objectValue else {
            actionNotice = "缺少 Bot 原始配置，无法回写"
            return false
        }
        if droppingBindingIdentity {
            rawDict.removeValue(forKey: "providerUserId")
            rawDict.removeValue(forKey: "displayName")
        }
        mutations(&rawDict)
        let arg = RPCValue.jsonObject { builder in
            builder.set("bot", JSONValue.object(rawDict))
            if let credentialValue, !credentialValue.isEmpty {
                builder.set("credentialValue", credentialValue)
            }
        }
        do {
            _ = try await connection.call("bots", "saveBot", arg)
            return true
        } catch {
            actionNotice = "saveBot 失败 · \(error.localizedDescription)"
            return false
        }
    }

    /// G-044：启停开关（saveBot enabled 翻转，桌面代执行）
    func setEnabled(connection: ZCodeServerConnection, bot: BotInfo, enabled: Bool) async -> Bool {
        let ok = await saveBot(connection: connection, bot: bot) { raw in
            raw["enabled"] = .bool(enabled)
        }
        if ok {
            actionNotice = enabled ? "已启用 · \(bot.name)" : "已停用 · \(bot.name)"
            await refresh(connection: connection)
        }
        return ok
    }

    /// G-043：补配凭据（saveBot credentialValue；桌面落凭据存储）
    func saveCredential(connection: ZCodeServerConnection, bot: BotInfo, credential: String) async -> Bool {
        let ok = await saveBot(connection: connection, bot: bot, credentialValue: credential)
        if ok {
            actionNotice = "凭据已保存 · \(bot.name)（桌面端凭据存储）"
            await refresh(connection: connection)
        }
        return ok
    }

    // MARK: 宽容解析

    static func parseBot(_ json: JSONValue) -> BotInfo? {
        guard let dict = json.objectValue,
              let id = dict["id"]?.stringValue else { return nil }
        return BotInfo(
            id: id,
            name: dict["name"]?.stringValue ?? id,
            provider: dict["provider"]?.stringValue ?? "unknown",
            enabled: dict["enabled"]?.boolValue ?? false,
            providerUserId: dict["providerUserId"]?.stringValue,
            displayName: dict["displayName"]?.stringValue,
            credentialRef: dict["credentialRef"]?.stringValue,
            raw: json)
    }

    static func parseState(_ json: JSONValue, index: Int) -> BotStateInfo? {
        guard let dict = json.objectValue,
              let botId = dict["botId"]?.stringValue else { return nil }
        let workspacePath = dict["workspacePath"]?.stringValue
            ?? dict["workspaceId"]?.stringValue ?? ""
        return BotStateInfo(
            id: "\(botId)-\(index)",
            botId: botId,
            workspacePath: workspacePath,
            mode: dict["mode"]?.stringValue,
            activeTaskId: dict["activeTaskId"]?.stringValue)
    }
}

// MARK: - IM Bot 管理页（设置 .bots 路由 destination；替换静态假数据 GenericListPage）

struct BotManagementView: View {
    @Environment(AppSession.self) private var session
    @State private var store = BotStore()

    private var isConnected: Bool {
        if case .connected = session.mode { return true }
        return false
    }

    var body: some View {
        Group {
            if !isConnected {
                EmptyStateView(
                    icon: "app.badge",
                    title: "未连接桌面端",
                    detail: "IM Bot 由桌面端托管，连接后此处同步通道列表与运行状态",
                    cta: "连接桌面端", ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "12-bot-act-connect")
            } else if store.bots.isEmpty && store.isLoading {
                CenterLoadingView(text: "正在同步 IM Bot…")
                    .accessibilityIdentifier("12-bot-loading")
            } else if store.bots.isEmpty {
                EmptyStateView(
                    icon: "app.badge",
                    title: store.lastError ?? "暂无 IM Bot",
                    detail: store.lastError == nil
                        ? "在桌面端 Bots 面板创建飞书 / Telegram / 微信通道后，此处同步"
                        : "检查桌面端是否在线后下拉重试",
                    cta: "重新加载", ctaAction: { Task { await reload() } },
                    ctaIdentifier: "12-bot-act-reload")
            } else {
                list
            }
        }
        .background(T.bg)
        .navigationTitle("IM Bot")
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .refreshable { await reload() }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                if let notice = store.actionNotice {
                    Text(notice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(T.accentText)
                        .padding(.horizontal, 2)
                        .accessibilityIdentifier("12-bot-notice")
                }
                Text("共 \(store.bots.count) 个通道 · \(store.contextsCount) 个绑定上下文")
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .padding(.horizontal, 2)
                    .accessibilityIdentifier("12-bot-summary")
                // G-012：移动端不提供 bot 应用凭据注册（飞书/微信扫码注册 4 项维持桌面端闭环）——
                // 显式引导口径，纯展示不可点、无假交互
                HStack(spacing: T.sp2) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 12))
                        .foregroundColor(T.text3)
                    Text("新建 Bot 请在桌面端 Bots 面板完成，创建后自动同步到此处")
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .card(padding: T.sp2)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("12-bot-desktop-register-hint")
                ForEach(store.bots) { bot in
                    NavigationLink(value: SettingsRouteRoute.botDetail(botID: bot.id)) {
                        botRow(bot)
                    }
                    .buttonStyle(PressableButtonStyle())
                    .accessibilityIdentifier("12-bot-row-\(bot.id)")
                }
                Text("绑定、解绑与生命周期操作由桌面端代执行；仅管理 Bot 配置与凭据，不写入本机文件。")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .lineSpacing(3)
                    .padding(.top, T.sp2)
                    .padding(.horizontal, 2)
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
        .navigationDestination(for: SettingsRouteRoute.self) { route in
            switch route {
            case .botDetail(let botID):
                BotDetailView(botID: botID, store: store)
            }
        }
    }

    private func botRow(_ bot: BotInfo) -> some View {
        let runtime = store.runtimes[bot.id]
        return HStack(spacing: T.sp3) {
            Image(systemName: bot.providerIcon)
                .font(.system(size: 15))
                .foregroundColor(T.accentText)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: T.sp1) {
                    Text(bot.name)
                        .font(T.font(14.5, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    if !bot.enabled {
                        StatusPill(text: "已停用", kind: .tag, compact: true)
                    }
                }
                Text(bot.isBound
                     ? "\(bot.providerLabel) · 已绑定 \(bot.displayName ?? bot.providerUserId ?? "")"
                     : "\(bot.providerLabel) · 未绑定" + (bot.hasCredential ? " · 凭据已配置" : " · 未配置凭据"))
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            runtimeDot(runtime)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(T.text3)
        }
        .padding(T.sp3)
        .frame(minHeight: 56)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }

    @ViewBuilder
    private func runtimeDot(_ runtime: BotRuntimeInfo?) -> some View {
        if let runtime {
            VStack(alignment: .trailing, spacing: 3) {
                HStack(spacing: 4) {
                    Circle().fill(runtime.tint).frame(width: 6, height: 6)
                    Text(runtime.statusLabel)
                        .font(T.font(10.5, .medium))
                        .foregroundColor(runtime.status == "error" ? T.red : T.text3)
                }
            }
            .accessibilityIdentifier("12-bot-runtime-\(runtime.status)")
        }
    }

    private func reload() async {
        guard let connection = activeConnection else { return }
        await store.refresh(connection: connection)
    }

    /// 连接态下的通道门面（连接失败/演示态为 nil，页面呈空态引导）
    private var activeConnection: ZCodeServerConnection? {
        isConnected ? session.connection : nil
    }
}

/// 详情页路由值（Settings push 栈内二级导航）
enum SettingsRouteRoute: Hashable {
    case botDetail(botID: String)
}

// MARK: - Bot 详情（绑定码 / 解绑 / 上下文 / 测试 / 生命周期；ConfirmSheet 二次确认）

struct BotDetailView: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let botID: String
    @Bindable var store: BotStore

    enum ConfirmKind: Equatable {
        case unbind, delete, removeSecret, resetState(String)

        var icon: String {
            switch self {
            case .unbind: return "link.badge.plus"
            case .delete: return "trash"
            case .removeSecret: return "key.slash"
            case .resetState: return "arrow.counterclockwise"
            }
        }
    }
    @State private var confirmKind: ConfirmKind?
    @State private var testing = false
    /// G-043 补配凭据输入态
    @State private var showCredentialInput = false
    @State private var credentialText = ""
    @State private var savingCredential = false

    private var isConnected: Bool {
        if case .connected = session.mode { return true }
        return false
    }

    private var bot: BotInfo? { store.bots.first { $0.id == botID } }
    private var runtime: BotRuntimeInfo? { store.runtimes[botID] }
    private var botStates: [BotStateInfo] { store.states.filter { $0.botId == botID } }
    private var testOutcome: BotTestOutcome? { store.testResults[botID] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                if let bot {
                    headerCard(bot)
                    controlCard(bot)   // G-044 启停 + G-043 补配凭据
                    bindCard(bot)
                    if !botStates.isEmpty { statesCard }
                    testCard(bot)
                    lifecycleCard(bot)
                } else {
                    CenterLoadingView(text: "正在载入 Bot…")
                }
                if let notice = store.actionNotice {
                    Text(notice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(T.accentText)
                        .accessibilityIdentifier("12-bot-notice")
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
        .background(T.bg)
        .navigationTitle(bot?.name ?? "Bot")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
        .refreshable { await refresh() }
        .overlay {
            if let kind = confirmKind {
                ConfirmSheet(
                    title: Self.confirmTitle(kind),
                    message: Self.confirmMessage(kind),
                    confirmTitle: Self.confirmActionTitle(kind),
                    confirmIcon: kind.icon,
                    identifierPrefix: "12-bot-confirm",
                    onCancel: {
                        confirmKind = nil // 二次确认取消：不产生任何 RPC
                    },
                    onConfirmAction: {
                        confirmKind = nil
                        Task { await perform(kind) }
                    })
            }
        }
    }

    private func headerCard(_ bot: BotInfo) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: bot.providerIcon)
                    .font(.system(size: 18))
                    .foregroundColor(T.accentText)
                    .frame(width: 40, height: 40)
                    .background(T.accentDim)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 1) {
                    Text(bot.name).font(T.font(15.5, .bold)).foregroundColor(T.text)
                    Text("\(bot.providerLabel) · \(bot.id)")
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer()
                StatusPill(
                    text: bot.enabled ? "启用" : "停用",
                    kind: bot.enabled ? .done : .tag,
                    compact: true)
            }
            if let runtime {
                HStack(spacing: T.sp2) {
                    Circle().fill(runtime.tint).frame(width: 6, height: 6)
                    Text("运行态：\(runtime.statusLabel)")
                        .font(T.font(12))
                        .foregroundColor(T.text2)
                    if let message = runtime.message, !message.isEmpty {
                        Text(message)
                            .font(T.font(11))
                            .foregroundColor(T.text3)
                            .lineLimit(1)
                    }
                    Spacer()
                }
                .accessibilityIdentifier("12-bot-runtime-row")
            }
            if let deliveryError = runtime?.deliveryError, !deliveryError.isEmpty {
                Text("最近投递错误：\(deliveryError)")
                    .font(T.font(11))
                    .foregroundColor(T.orange)
            }
        }
        .card()
        .accessibilityIdentifier("12-bot-header")
    }

    /// 运行控制卡（G-044 启停开关 + G-043 补配凭据 + 进阶配置只读）
    private func controlCard(_ bot: BotInfo) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "switch.2")
                    .font(.system(size: 12))
                    .foregroundColor(T.text3)
                Text(bot.enabled ? String(localized: "运行中 · 已启用") : String(localized: "已停用"))
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                Toggle("", isOn: Binding(
                    get: { bot.enabled },
                    set: { newValue in
                        Task { await store.setEnabled(connection: session.connection, bot: bot, enabled: newValue) }
                    }))
                    .labelsHidden()
                    .tint(T.accent)
                    .disabled(!isConnected)
                    .accessibilityIdentifier("12-bot-toggle-enabled")
            }
            .accessibilityIdentifier("12-bot-enabled-row")

            Button {
                showCredentialInput = true
            } label: {
                Label(bot.hasCredential ? String(localized: "重新配置凭据") : String(localized: "补配凭据（token）"),
                      systemImage: "key")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
            }
            .accessibilityIdentifier("12-bot-act-credential")

            // 进阶配置只读（G-044：写面后置，桌面完成）
            advancedConfigRows(bot)
        }
        .card()
        .accessibilityIdentifier("12-bot-control")
        .alert(String(localized: "补配凭据"), isPresented: $showCredentialInput) {
            SecureField(String(localized: "粘贴 Bot token"), text: $credentialText)
                .accessibilityIdentifier("12-bot-field-credential")
            Button(String(localized: "取消"), role: .cancel) { credentialText = "" }
            Button(String(localized: "保存")) {
                Task {
                    savingCredential = true
                    let text = credentialText.trimmingCharacters(in: .whitespacesAndNewlines)
                    credentialText = ""
                    if !text.isEmpty {
                        _ = await store.saveCredential(connection: session.connection, bot: bot, credential: text)
                    }
                    savingCredential = false
                }
            }
            .accessibilityIdentifier("12-bot-act-credential-save")
        } message: {
            Text(String(localized: "凭据经桌面端代存（凭据存储），不落本机；保存后可用「测试连接」验证。"))
        }
    }

    /// 进阶配置只读展示（allowedWorkspaces / replyMode；值来自原始 BotConfig）
    @ViewBuilder
    private func advancedConfigRows(_ bot: BotInfo) -> some View {
        if let raw = bot.raw?.objectValue {
            let workspaces = raw["allowedWorkspaces"]?.arrayValue?.compactMap(\.stringValue) ?? []
            let replyMode = raw["replyMode"]?.stringValue
            if !workspaces.isEmpty || replyMode != nil {
                VStack(alignment: .leading, spacing: 2) {
                    if !workspaces.isEmpty {
                        boundRow(label: String(localized: "授权工作区"), value: workspaces.joined(separator: ", "), id: "12-bot-adv-workspaces")
                    }
                    if let replyMode {
                        boundRow(label: String(localized: "回复粒度"), value: replyMode, id: "12-bot-adv-replymode")
                    }
                    Text(String(localized: "进阶配置为只读展示 · 修改请在桌面端 Bots 面板完成"))
                        .font(T.font(10))
                        .foregroundColor(T.text3)
                }
            }
        }
    }

    /// 绑定卡：绑定身份 / 绑定码（码+倒计时+复制绑定命令+刷新）/ 解绑
    @ViewBuilder
    private func bindCard(_ bot: BotInfo) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Text("绑定").font(T.font(13, .semibold)).foregroundColor(T.text3)
                Spacer()
                if bot.isBound {
                    StatusPill(text: "已绑定", kind: .done, compact: true)
                }
            }
            if bot.isBound {
                boundRow(label: "绑定用户", value: bot.displayName ?? bot.providerUserId ?? "",
                         id: "12-bot-bind-user")
                if let userId = bot.providerUserId, userId != (bot.displayName ?? userId) {
                    boundRow(label: "用户 ID", value: userId, id: "12-bot-bind-userid")
                }
            } else {
                Text("尚未绑定聊天用户；生成绑定码后在 IM 里向 Bot 发送绑定命令完成配对。")
                    .font(T.font(11.5))
                    .foregroundColor(T.text2)
                    .lineSpacing(3)
            }
            bindCodeSection(bot)
            HStack(spacing: 10) {
                Button {
                    Task { await store.createBindCode(connection: session.connection, botID: botID) }
                } label: {
                    Label(bot.isBound ? "重新生成绑定码" : "生成绑定码", systemImage: "qrcode")
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .accessibilityIdentifier("12-bot-act-bindcode")

                if bot.isBound {
                    Button {
                        confirmKind = .unbind
                    } label: {
                        Text("解绑")
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.red)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                    }
                    .accessibilityIdentifier("12-bot-act-unbind")
                }
            }
        }
        .card()
        .accessibilityIdentifier("12-bot-bind-card")
    }

    /// 绑定码展开区（大号码 + 倒计时进度 + 复制 /bind 命令；过期态可刷新）
    @ViewBuilder
    private func bindCodeSection(_ bot: BotInfo) -> some View {
        if let code = store.bindCode {
            VStack(alignment: .leading, spacing: T.sp2) {
                if code.isExpired {
                    HStack(spacing: T.sp1) {
                        Image(systemName: "clock.badge.exclamationmark")
                            .font(.system(size: 11))
                            .foregroundColor(T.orange)
                        Text("绑定码已过期，点击「重新生成绑定码」续码")
                            .font(T.font(11.5))
                            .foregroundColor(T.orange)
                    }
                    .accessibilityIdentifier("12-bot-code-expired")
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
                        Text(code.code)
                            .font(T.mono(26, .bold))
                            .foregroundColor(T.text)
                            .accessibilityIdentifier("12-bot-code")
                        Spacer()
                        Text("\(code.remainingSeconds)s")
                            .font(T.mono(11.5))
                            .foregroundColor(code.remainingSeconds <= 10 ? T.orange : T.text3)
                    }
                    ThinProgressBar(
                        progress: Double(code.remainingSeconds) / Double(max(code.ttlMs / 1000, 1)),
                        height: 4,
                        tint: code.remainingSeconds <= 10 ? T.orange : T.accent)
                    Button {
                        UIPasteboard.general.string = "/bind \(code.code)"
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                    } label: {
                        Label("复制绑定命令  /bind \(code.code)", systemImage: "doc.on.doc")
                            .font(T.font(12, .semibold))
                            .foregroundColor(T.text2)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(T.bgInput)
                            .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                    .accessibilityIdentifier("12-bot-act-copy-bind")
                    Text("在聊天软件私聊该 Bot 发送此命令，即完成账号绑定（有效期 \(code.ttlMs / 1000) 秒）")
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
            }
            .padding(T.sp2)
            .background(T.bgInput)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .accessibilityIdentifier("12-bot-code-card")
        }
    }

    /// 工作区上下文（getBotStates per-context；清退 = resetBotState）
    @ViewBuilder
    private var statesCard: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Text("工作区上下文").font(T.font(13, .semibold)).foregroundColor(T.text3)
                Spacer()
                Text("\(botStates.count) 个")
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
            }
            ForEach(botStates) { state in
                HStack(spacing: T.sp2) {
                    Image(systemName: "folder")
                        .font(.system(size: 12))
                        .foregroundColor(T.text3)
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(state.workspaceName)
                            .font(T.font(12.5, .medium))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        Text(state.activeTaskId.map { "进行中 · \($0)" } ?? (state.mode.map { "模式 \($0)" } ?? "空闲"))
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button {
                        confirmKind = .resetState(state.id)
                    } label: {
                        Text("清退")
                            .font(T.font(11.5, .semibold))
                            .foregroundColor(T.orange)
                            .padding(.horizontal, T.sp2)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("12-bot-state-reset-\(state.id)")
                }
            }
            Text("清退后该工作区的 Bot 上下文（草稿/待审批）被重置，用户需重新绑定会话。")
                .font(T.font(10.5))
                .foregroundColor(T.text3)
        }
        .card()
        .accessibilityIdentifier("12-bot-states")
    }

    /// 连通性测试（testBot：通过/失败 + 桌面同源 message）
    private func testCard(_ bot: BotInfo) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Text("连通性").font(T.font(13, .semibold)).foregroundColor(T.text3)
                Spacer()
                if testing { SpinnerView(size: 14) }
            }
            Button {
                testing = true
                Task {
                    await store.test(connection: session.connection, bot: bot)
                    testing = false
                }
            } label: {
                Label("测试连接", systemImage: "antenna.radiowaves.left.and.right")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
            }
            .disabled(testing)
            .accessibilityIdentifier("12-bot-act-test")

            if let outcome = testOutcome {
                HStack(alignment: .top, spacing: T.sp2) {
                    Image(systemName: outcome.ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundColor(outcome.ok ? T.accentText : T.red)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(outcome.ok ? "测试通过" : "测试失败")
                            .font(T.font(12.5, .semibold))
                            .foregroundColor(outcome.ok ? T.accentText : T.red)
                        if !outcome.message.isEmpty {
                            Text(outcome.message)
                                .font(T.mono(10.5))
                                .foregroundColor(T.text3)
                                .lineSpacing(2)
                        }
                    }
                    Spacer()
                }
                .padding(T.sp2)
                .background(outcome.ok ? T.accentDim : T.redDim)
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
                .accessibilityIdentifier(outcome.ok ? "12-bot-test-ok" : "12-bot-test-fail")
            }
        }
        .card()
        .accessibilityIdentifier("12-bot-test-card")
    }

    /// 生命周期（删除 / 移除密钥；均 ConfirmSheet 二次确认）
    private func lifecycleCard(_ bot: BotInfo) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("生命周期").font(T.font(13, .semibold)).foregroundColor(T.text3)
            Button {
                confirmKind = .removeSecret
            } label: {
                Label(bot.hasCredential ? "移除密钥（回未配置凭据态）" : "移除密钥",
                      systemImage: "key.slash")
                    .font(T.font(13))
                    .foregroundColor(T.text)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
            }
            .accessibilityIdentifier("12-bot-act-remove-secret")

            Button {
                confirmKind = .delete
            } label: {
                Label("删除 Bot", systemImage: "trash")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
            }
            .accessibilityIdentifier("12-bot-act-delete")
            Text("删除后桌面端 listBots 不再返回该 Bot；移除密钥仅清凭据并回未配置态，Bot 配置保留。")
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .lineSpacing(3)
        }
        .card()
        .accessibilityIdentifier("12-bot-lifecycle")
    }

    private func boundRow(label: String, value: String, id: String) -> some View {
        HStack(spacing: T.sp2) {
            Text(label).font(T.font(12)).foregroundColor(T.text3)
            Spacer()
            Text(value)
                .font(T.mono(11))
                .foregroundColor(T.text2)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .accessibilityIdentifier(id)
    }

    // MARK: 确认文案与动作

    private static func confirmTitle(_ kind: ConfirmKind) -> String {
        switch kind {
        case .unbind: return "解绑该 Bot？"
        case .delete: return "删除该 Bot？"
        case .removeSecret: return "移除访问密钥？"
        case .resetState: return "清退该工作区上下文？"
        }
    }

    private static func confirmMessage(_ kind: ConfirmKind) -> String {
        switch kind {
        case .unbind:
            return "仅清除绑定用户身份与 Bot 会话状态，不删除 Bot 配置；该用户需重新绑定后才能使用。"
        case .delete:
            return "从桌面端删除该 Bot 配置与凭据，listBots 不再返回；不可恢复，请确认。"
        case .removeSecret:
            return "移除后 Bot 回到未配置凭据态，需重新填入 token 才能恢复连接。"
        case .resetState:
            return "清退后该工作区的 Bot 会话上下文被重置，正在进行的绑定会话将结束。"
        }
    }

    private static func confirmActionTitle(_ kind: ConfirmKind) -> String {
        switch kind {
        case .unbind: return "确认解绑"
        case .delete: return "确认删除"
        case .removeSecret: return "确认移除"
        case .resetState: return "确认清退"
        }
    }

    private func perform(_ kind: ConfirmKind) async {
        guard let bot = bot else { return }
        switch kind {
        case .unbind:
            _ = await store.unbind(connection: session.connection, bot: bot)
        case .delete:
            if await store.delete(connection: session.connection, bot: bot) {
                // 删除成功：数据源已移除，退回 IM Bot 列表
                dismiss()
            }
        case .removeSecret:
            _ = await store.removeSecret(connection: session.connection, bot: bot)
        case .resetState:
            _ = await store.resetBotState(connection: session.connection, botID: botID)
        }
    }

    private func refresh() async {
        guard case .connected = session.mode else { return }
        if store.bots.isEmpty {
            await store.refresh(connection: session.connection)
        }
    }
}

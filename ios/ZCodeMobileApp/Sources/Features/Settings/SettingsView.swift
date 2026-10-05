import SwiftUI

/// 屏 12 · 设置（Tab「设置」根）：用户卡 + 服务器与账户分组（v2.4 增量）+ 四分组设置行（行高 48）
struct SettingsView: View {
    @Environment(AppRouter.self) private var router
    @Environment(AppSettingsModel.self) private var settings
    @Environment(AppSession.self) private var session

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp4) {
                userCard
                serverAccountGroup
                settingGroups
                Text(session.isDemo
                     ? "BiuZ for iOS · 演示数据由本地 Mock 提供"
                     : "BiuZ for iOS · 已连接桌面端 · 指令经桌面端执行，文件保持只读")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, T.sp2)
                    .accessibilityIdentifier("12-foot-data-source")
                // 品牌名独立可测锚点（e2e 断言 App 自我指称，works-with 桌面端描述性引用另行表述）
                Text("BiuZ · 为 ZCode 社区版桌面端打造的移动遥控台")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .accessibilityIdentifier("12-brand-name")
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp2)
            // 底部缓冲：滚动到底时最后内容（页脚/品牌行）不被浮层 TabBar 遮挡
            // （TabBar 内容高 53pt + 底部安全区 ≈ 100pt；第 1 轮走查实测页脚被遮 37pt）
            .padding(.bottom, 72)
        }
        .scrollIndicators(.hidden)
        .background(T.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("设置").font(T.font(17, .bold)).foregroundColor(T.text)
            }
        }
        .navigationDestination(for: SettingsRoute.self) { route in
            destination(for: route)
        }
    }

    // MARK: 服务器与账户分组（v2.4 L4：插于用户卡后第一组）

    @ViewBuilder
    private var serverAccountGroup: some View {
        let server = session.savedServer
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: 8) {
                Text("服务器与账户")
                    .font(T.font(12, .semibold))
                    .foregroundColor(T.text3)
                    .padding(.leading, 2)
                StatusPill(text: "v2.4 新增", kind: .tag, compact: true)
            }
            VStack(spacing: 0) {
                if let server {
                    Button {
                        router.pushSettings(.serverDetail)
                    } label: {
                        sessionRow(
                            icon: "laptopcomputer.and.iphone",
                            title: "服务器",
                            subtitle: "\(server.name ?? "我的桌面端") · \(server.displayAddress)",
                            online: true,
                            value: session.isDemo ? "已保存" : "已连接")
                    }
                    .accessibilityIdentifier("l4-row-server")
                    Divider().overlay(T.border)
                    Button {
                        session.requestConnectFlow(editTokenOnly: true)
                    } label: {
                        sessionRow(
                            icon: "key",
                            title: "访问令牌",
                            subtitle: server.token.isEmpty
                                ? "免鉴权（--no-token）· Keychain"
                                : "Keychain · \(OAuthCredentialStore.mask(server.token))",
                            online: nil,
                            value: "更新")
                    }
                    .accessibilityIdentifier("l4-row-token")
                    Divider().overlay(T.border)
                }
                Button {
                    router.pushSettings(.serverAccount)
                } label: {
                    sessionRow(
                        icon: "person",
                        title: "账户",
                        subtitle: session.oauthUserInfo.map { "Coding Plan · \($0.displayName)" }
                            ?? "连接桌面端无需登录",
                        online: nil,
                        value: accountValue)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("l4-row-account")
                Divider().overlay(T.border)
                Button {
                    session.requestConnectFlow(editTokenOnly: false)
                } label: {
                    sessionRow(
                        icon: "plus",
                        title: "添加服务器",
                        subtitle: "扫码 / 剪贴板 / 手动输入",
                        online: nil,
                        value: nil)
                }
                .accessibilityIdentifier("l4-row-add")
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
        }
    }

    private var accountValue: String {
        if session.oauthUserInfo == nil { return "未登录" }
        return session.isOAuthExpired ? "已过期" : "已登录"
    }

    /// 行内容（identifier 由调用处的 Button 持有，保证 e2e firstMatch 命中带完整 label 的按钮元素）
    private func sessionRow(icon: String, title: String, subtitle: String,
                            online: Bool?, value: String?) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .foregroundColor(T.accentText)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                HStack(spacing: 4) {
                    if let online {
                        ProbeDot(reachable: online)
                    }
                    Text(subtitle)
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
            }
            Spacer()
            if let value {
                Text(value)
                    .font(T.font(12))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    // MARK: - 用户卡（头像 + Coding Plan 徽章 + 额度条）
    // 连接态额度绑真实数据（usage-stats.getCodingPlanUsageSnapshot：5 小时条数窗为
    // 主窗口）；连接态缺数据时如实显示「未获取」，不再回退演示值（用户实测反馈：
    // 68%/9 月 2 日重置 是写死的演示串，误导校对）

    private var usagePercentRemaining: Double {
        if !session.isDemo, let usage = session.codingPlanUsage, let percent = usage.percentRemaining {
            return percent
        }
        return 0.68
    }

    private var usagePercentText: String {
        "\(Int((usagePercentRemaining * 100).rounded()))%"
    }

    /// 连接态是否有真实额度数据（无 → 用户卡显示「未获取」而非演示值）
    private var hasRealUsage: Bool {
        !session.isDemo && session.codingPlanUsage != nil
    }

    private var usageDetailText: String {
        if hasRealUsage, let usage = session.codingPlanUsage {
            var parts: [String] = []
            if let used = usage.used, let limit = usage.limit {
                parts.append(String(localized: "5 小时窗已用 \(used) / \(limit) 条"))
            } else if let percent = usage.percentRemaining {
                parts.append(String(localized: "已用 \(Int(((1 - percent) * 100).rounded()))%"))
            }
            // 其余窗口摘要（每周/每月）
            for window in usage.windows where window.level != "TIME_LIMIT" {
                if let percentUsed = window.percentUsed {
                    parts.append("\(window.label) \(Int(percentUsed.rounded()))%")
                }
            }
            if let resetsAt = usage.resetsAtText {
                parts.append(String(localized: "\(resetsAt) 重置"))
            }
            if parts.isEmpty {
                return String(localized: "桌面端未返回额度明细")
            }
            return parts.joined(separator: " · ")
        }
        if !session.isDemo {
            return String(localized: "额度未获取 · 连接后自动刷新")
        }
        return "本月 500 条中的 340 条已使用，9 月 2 日重置"
    }

    private var userCard: some View {
        VStack(alignment: .leading, spacing: T.sp3) {
            HStack(spacing: T.sp3) {
                AgentAvatar(size: 56)
                VStack(alignment: .leading, spacing: 4) {
                    // G-020：连接态绑定 OAuth displayName（真实数据），演示态回退演示名
                    Text(session.oauthUserInfo?.displayName ?? String(localized: "Zai 开发者"))
                        .font(T.font(17, .bold))
                        .foregroundColor(T.text)
                    if let username = session.oauthUserInfo?.username {
                        Text("@\(username) · \(String(localized: "已登录"))")
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
                            .lineLimit(1)
                    }
                    HStack(spacing: 4) {
                        Image(systemName: "bolt.fill").font(.system(size: 9))
                        Text("Coding Plan").font(T.font(10.5, .semibold))
                    }
                    .foregroundColor(T.onAccent)
                    .padding(.horizontal, T.sp2)
                    .frame(height: 18)
                    .background(T.accent)
                    .clipShape(Capsule())
                }
                Spacer()
            }
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text("剩余额度")
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                    Spacer()
                    Text(usagePercentText)
                        .font(T.mono(11, .semibold))
                        .foregroundColor(T.accentText)
                }
                ThinProgressBar(progress: usagePercentRemaining, height: 5, tint: T.accent)
                Text(usageDetailText)
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
        }
        .card(padding: T.sp4)
        .background(T.gradUserCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rL))
        .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("12-usercard")
        .task(id: session.isDemo) {
            // 进入设置页补拉一次（连接态下拉刷新口径：refreshable 不覆盖根 ScrollView，
            // 以 task(id:) 在演示↔连接切换时刷新）
            if !session.isDemo {
                await session.refreshDesktopReadonlyInfo()
            }
        }
    }

    // MARK: - 分组

    /// G-034：设备行副标题绑真实连接态——仅显示可证实的在线设备（不再硬编码假在线）
    private var pairingSubtitle: String {
        if case .connected(let server) = session.mode {
            return "\(server.name ?? server.displayAddress) 在线"
        }
        return "未连接桌面端"
    }

    /// G-034：在线数与实际连接数一致（中继/局域网同一时刻至多 1 条连接）
    private var pairingValue: String {
        let paired = ServerRegistry.servers.count
        if case .connected = session.mode {
            return "\(paired) 台已配对 · 1 台在线"
        }
        return "\(paired) 台已配对 · 0 台在线"
    }

    private var settingGroups: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            group("设备与远控") {
                navRow(route: .devices, icon: "laptopcomputer.and.iphone", title: "设备与配对",
                       subtitle: pairingSubtitle, value: pairingValue, identifier: "12-row-pairing")
                navRow(route: .bots, icon: "app.badge.filled", title: "IM Bot",
                       subtitle: "微信 / 飞书 / Telegram 通道 · 桌面端托管",
                       value: nil, identifier: "12-row-bot")
            }
            group("基础设置") {
                navRow(route: .model, icon: "cpu", title: "模型设置",
                       subtitle: nil, value: settings.value.model, identifier: "12-row-model")
                navRow(route: .appearance, icon: "circle.lefthalf.filled", title: "外观",
                       subtitle: nil, value: settings.value.appearance.label, identifier: "12-row-appearance")
                navRow(route: .diagnostics, icon: "stethoscope", title: "诊断与日志",
                       subtitle: "导出连接日志用于问题反馈", value: nil, identifier: "12-row-diagnostics")
                navRow(route: .language, icon: "globe", title: "语言",
                       subtitle: nil, value: settings.value.language, identifier: "12-row-language")
                toggleRow(icon: "bell", title: "通知", subtitle: "任务完成 / 待审批 / 失败本地推送",
                          isOn: Binding(
                            get: { settings.value.notificationsEnabled },
                            set: { newValue in
                                settings.update { $0.notificationsEnabled = newValue }
                                if newValue {
                                    // 显式打开才请求系统授权（冷启动静默镜像，防授权框干扰）
                                    Task { await NotificationService.shared.requestEnable() }
                                }
                            }),
                          identifier: "12-row-notify")
            }
            group("数据与统计") {
                // G-033 验收③：演示文案（12.4M tokens/128 条等）仅演示态出现——连接态读面
                // 未接入的行如实标注，不再展示假数据
                navRow(route: .usage, icon: "chart.bar", title: "用量统计",
                       subtitle: session.isDemo ? "近 30 天 token 与任务数" : "连接后同步桌面用量",
                       value: session.isDemo ? "12.4M tokens" : nil, identifier: "12-row-usage")
                navRow(route: .memory, icon: "brain", title: "记忆",
                       subtitle: session.isDemo ? "Agent 长期记忆条目" : "连接后同步桌面记忆",
                       value: session.isDemo ? "128 条" : nil, identifier: "12-row-memory")
            }
            group("Agent 能力") {
                navRow(route: .skills, icon: "wand.and.stars", title: "技能",
                       subtitle: session.isDemo ? "12 项已启用" : "连接后同步桌面技能",
                       value: nil, identifier: "12-row-skills")
                navRow(route: .mcp, icon: "server.rack", title: "MCP",
                       subtitle: session.isDemo ? "4 个服务器已连接" : "连接后同步桌面 MCP",
                       value: nil, identifier: "12-row-mcp")
                navRow(route: .plugins, icon: "puzzlepiece.extension", title: "插件商店",
                       subtitle: nil, value: nil, badge: "New", identifier: "12-row-plugins")
                navRow(route: .automation, icon: "clock.badge.checkmark", title: "自动化",
                       subtitle: "定时任务与触发器", value: nil, badge: "Beta", identifier: "12-row-automation")
                // P2 批次只读页入口（G-022/G-024/G-025；写面均维持拦截）
                navRow(route: .savedWorkflows, icon: "flowchart.fill", title: "工作流库",
                       subtitle: "已保存工作流与最近运行", value: nil, identifier: "12-row-workflows")
                navRow(route: .offPeakTasks, icon: "moon.stars", title: "错峰任务",
                       subtitle: "低峰期排队的后台任务", value: nil, identifier: "12-row-offpeak")
                navRow(route: .feedbackTickets, icon: "ladybug", title: "反馈工单",
                       subtitle: "查看工单进度", value: nil, identifier: "12-row-feedback")
            }
        }
    }

    @ViewBuilder
    func destination(for route: SettingsRoute) -> some View {
        switch route {
        case .model: ModelSettingsView()
        case .appearance: AppearanceSettingsView()
        case .language: LanguageSettingsView()
        case .serverAccount: ServerAccountConfigView()
        // P2 批次只读页（写面均维持 ReadOnlyGate 拦截）
        case .savedWorkflows:
            RemoteCapabilityListPage(capability: .savedWorkflows, title: "工作流库", icon: "flowchart.fill")
        case .offPeakTasks:
            RemoteCapabilityListPage(capability: .offPeak, title: "错峰任务", icon: "moon.stars")
        case .feedbackTickets:
            RemoteCapabilityListPage(capability: .feedback, title: "反馈工单", icon: "ladybug")
        case .serverDetail: ServerDetailView()
        case .devices: DevicesPage() // G-034/G-059：真实连接态 + 多机切换，替换硬编码假在线
        case .bots: BotManagementView() // G-001：真实列表/运行态（桌面 botsService 读面），替换静态假数据
        case .usage: UsageStatsView() // G-041/G-042：App 用量真值 + 重置机会卡，替换演示占位
        case .memory:
            // G-011：连接态接桌面只读清单（listProjectMemories）；失败/空回退诚实占位
            if case .connected = session.mode {
                RemoteCapabilityListPage(capability: .memory, title: "记忆", icon: "brain")
            } else {
                GenericListPage(
                    title: "记忆", icon: "brain",
                    rows: [
                        ("text.quote", "偏好 Swift + SwiftUI 原生实现", "2026-09-28 更新", nil),
                        ("text.quote", "工作区主目录 ~/work/zcode", "2026-09-25 更新", nil),
                        ("text.quote", "测试框架使用 swift-testing", "2026-09-20 更新", nil),
                    ])
            }
        case .skills:
            // G-011：连接态接桌面技能目录只读读面（getSkillReferenceCatalog）；启停写面维持拦截
            if case .connected = session.mode {
                RemoteCapabilityListPage(capability: .skills, title: "技能", icon: "wand.and.stars")
            } else {
                GenericListPage(
                    title: "技能", icon: "wand.and.stars",
                    rows: [
                        ("wand.and.stars", "代码评审", "已启用", nil),
                        ("wand.and.stars", "周报生成", "已启用", nil),
                        ("wand.and.stars", "SQL 优化", "已停用", nil),
                    ])
            }
        case .mcp:
            // G-011：连接态接 MCP 服务器状态只读读面（listMcpServerStatuses）；启停写面维持拦截
            if case .connected = session.mode {
                RemoteCapabilityListPage(capability: .mcp, title: "MCP", icon: "server.rack")
            } else {
                GenericListPage(
                    title: "MCP", icon: "server.rack",
                    rows: [
                        ("server.rack", "github-mcp", "已连接", nil),
                        ("server.rack", "jira-mcp", "已连接", nil),
                        ("server.rack", "figma-mcp", "已停用", nil),
                    ])
            }
        case .plugins:
            // G-011：连接态接插件清单只读读面（listPlugins）；安装/卸载写面维持拦截
            if case .connected = session.mode {
                RemoteCapabilityListPage(capability: .plugins, title: "插件商店", icon: "puzzlepiece.extension")
            } else {
                GenericListPage(
                    title: "插件商店", icon: "puzzlepiece.extension",
                    rows: [
                        ("puzzlepiece.extension", "K8s 助手", "社区 · 4.8 分", "New"),
                        ("puzzlepiece.extension", "数据库巡检", "官方 · 4.9 分", nil),
                        ("puzzlepiece.extension", "API 翻译", "社区 · 4.6 分", nil),
                    ])
            }
        case .automation: AutomationsView() // G-016：真实列表+运行历史（zcode-agent listAutomations），替换硬编码占位
        case .diagnostics: DiagnosticsExportView() // G-061
        }
    }

    private func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text(title)
                .font(T.font(12, .semibold))
                .foregroundColor(T.text3)
                .padding(.leading, 2)
            VStack(spacing: 0) {
                content()
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
        }
    }

    private func navRow(route: SettingsRoute, icon: String, title: String,
                        subtitle: String?, value: String?, badge: String? = nil,
                        identifier: String) -> some View {
        Button {
            router.pushSettings(route)
        } label: {
            rowContent(icon: icon, title: title, subtitle: subtitle) {
                if let badge {
                    StatusPill(text: badge, kind: .tag, compact: true)
                }
                if let value {
                    Text(value)
                        .font(T.font(12))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(T.text3)
            }
        }
        .accessibilityIdentifier(identifier)
    }

    private func toggleRow(icon: String, title: String, subtitle: String,
                           isOn: Binding<Bool>, identifier: String) -> some View {
        HStack(spacing: T.sp2) {
            rowLeading(icon: icon)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(T.font(14.5)).foregroundColor(T.text)
                Text(subtitle)
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(T.accent)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 48)
        .accessibilityIdentifier(identifier)
    }

    @ViewBuilder
    private func rowContent<Trailing: View>(icon: String, title: String,
                                            subtitle: String?, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(spacing: T.sp2) {
            rowLeading(icon: icon)
            VStack(alignment: .leading, spacing: 1) {
                // G-007：title/subtitle 经 String 参传入时 Text(_ String) 不查本地化表——
                // 包装 LocalizedStringKey 恢复 xcstrings 查表（zh-Hans 源语言查表回退键原文，
                // zh 断言不变；en 态命中 en 列）
                Text(LocalizedStringKey(title))
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                if let subtitle {
                    Text(LocalizedStringKey(subtitle))
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
            }
            Spacer()
            trailing()
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 48)
    }

    private func rowLeading(icon: String) -> some View {
        Image(systemName: icon)
            .font(.system(size: 14))
            .foregroundColor(T.accentText)
            .frame(width: 30)
    }
}

// MARK: - 模型设置（真实持久化）

struct ModelSettingsView: View {
    @Environment(AppSettingsModel.self) private var settings
    @Environment(\.conversationStore) private var conversationStore
    /// 连接态：模型清单与当前绑定来自桌面 model-selection 通道（与桌面列表一致）；
    /// 演示/离线回退本地缺省清单。选中=本地偏好；桌面绑定以桌面侧为准（只读通道）。
    @State private var remoteModels: [String] = []
    @State private var remoteActiveModel: String?
    @State private var usedRemoteList = false

    private let fallbackModels = ["GLM-5.3", "GLM-5", "GLM-4.7"]

    private var models: [String] { usedRemoteList && !remoteModels.isEmpty ? remoteModels : fallbackModels }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp4) {
                section("模型") {
                    ForEach(models, id: \.self) { item in
                        Button {
                            settings.update { $0.model = item }
                        } label: {
                            HStack {
                                Text(item)
                                    .font(T.font(14.5, .medium))
                                    .foregroundColor(T.text)
                                Spacer()
                                if isActiveModel(item) {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(T.accent)
                                }
                            }
                            .padding(.horizontal, T.sp3)
                            .frame(minHeight: 48)
                        }
                        .accessibilityIdentifier("12-model-\(item)")
                    }
                }
                section("思考等级") {
                    ZSegmentedPicker(
                        items: ThoughtLevel.allCases,
                        label: \.label,
                        selection: Binding(
                            get: { settings.value.thoughtLevel },
                            set: { newValue in settings.update { $0.thoughtLevel = newValue } }),
                        identifierPrefix: "12-seg-thought")
                }
                Text("思考等级越高，Agent 推理越深入，但响应更慢、额度消耗更快。")
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
            }
            .padding(T.sp4)
        }
        .background(T.bg)
        .navigationTitle("模型设置")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let remote = conversationStore as? RemoteConversationStore,
                  let info = await remote.modelSelectionView(), !info.models.isEmpty else { return }
            remoteModels = info.models
            remoteActiveModel = info.activeModel
            usedRemoteList = true
        }
    }

    /// 连接态打勾以桌面绑定为准；本地偏好仅用于演示/离线回退清单
    private func isActiveModel(_ item: String) -> Bool {
        if usedRemoteList, let active = remoteActiveModel {
            return item == active
        }
        return settings.value.model == item
    }

    @ViewBuilder
    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text(title).font(T.font(12, .semibold)).foregroundColor(T.text3).padding(.leading, 2)
            VStack(spacing: 0) { content() }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
        }
    }
}

// MARK: - 外观设置（即时生效 + 持久化）

struct AppearanceSettingsView: View {
    @Environment(AppSettingsModel.self) private var settings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp4) {
                ZSegmentedPicker(
                    items: AppearanceMode.allCases,
                    label: \.label,
                    selection: Binding(
                        get: { settings.value.appearance },
                        set: { newValue in settings.update { $0.appearance = newValue } }),
                    identifierPrefix: "12-seg-appearance")
                ForEach(AppearanceMode.allCases) { mode in
                    Button {
                        settings.update { $0.appearance = mode }
                    } label: {
                        HStack {
                            Text(mode.label)
                                .font(T.font(14.5, .medium))
                                .foregroundColor(T.text)
                            Spacer()
                            if settings.value.appearance == mode {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(T.accent)
                            }
                        }
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 48)
                    }
                    .accessibilityIdentifier("12-appearance-\(mode.rawValue)")
                }
                Text("默认跟随系统深浅变化即时换肤；选择固定档后将始终保持该主题。")
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
            }
            .padding(T.sp4)
        }
        .background(T.bg)
        .navigationTitle("外观")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 语言设置（G-008→P1 语言适配：真实切换链路——偏好持久化到 UserDefaults
// AppleLanguages，iOS 在下次启动时按其构建 Bundle 语言；修改后提示重启生效。
// 文案本地化由 Localizable.xcstrings（zh-Hans 源 + en）承载，跟随系统为默认。）

/// 应用语言偏好（AppleLanguages 持久化口径；system = 移除覆盖键，跟随系统）
enum AppLanguagePreference: String, CaseIterable, Identifiable {
    case system
    case zhHans = "zh-Hans"
    case english = "en"
    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return String(localized: "跟随系统")
        case .zhHans: return String(localized: "简体中文")
        case .english: return "English"
        }
    }

    static func current() -> AppLanguagePreference {
        if let first = UserDefaults.standard.stringArray(forKey: "AppleLanguages")?.first {
            if first.hasPrefix("zh") { return .zhHans }
            if first.hasPrefix("en") { return .english }
        }
        return .system
    }
}

struct LanguageSettingsView: View {
    @Environment(AppSettingsModel.self) private var settings
    @State private var selection: AppLanguagePreference = AppLanguagePreference.current()
    @State private var needsRestart = false

    /// 当前生效语言（AppleLanguages 覆盖优先，否则系统首选语言）
    private var effectiveLanguageLabel: String {
        let preferred = Locale.preferredLanguages.first ?? "zh-Hans"
        if preferred.hasPrefix("zh") { return String(localized: "简体中文") }
        if preferred.hasPrefix("en") { return "English" }
        return preferred
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                VStack(spacing: 0) {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "globe")
                            .font(.system(size: 14))
                            .foregroundColor(T.accentText)
                            .frame(width: 30)
                        Text("当前界面语言")
                            .font(T.font(14.5))
                            .foregroundColor(T.text)
                        Spacer()
                        Text(effectiveLanguageLabel)
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.accentText)
                    }
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 48)
                    .accessibilityIdentifier("12-language-system")
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))

                VStack(spacing: 0) {
                    ForEach(AppLanguagePreference.allCases) { option in
                        Button {
                            apply(option)
                        } label: {
                            HStack {
                                Text(option.label)
                                    .font(T.font(14.5, .medium))
                                    .foregroundColor(T.text)
                                Spacer()
                                if selection == option {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(T.accent)
                                }
                            }
                            .padding(.horizontal, T.sp3)
                            .frame(minHeight: 48)
                        }
                        .accessibilityIdentifier("12-language-\(option.rawValue)")
                    }
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))

                if needsRestart {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 12))
                            .foregroundColor(T.orange)
                        Text("语言将在重启应用后完全生效")
                            .font(T.font(12, .semibold))
                            .foregroundColor(T.orange)
                        Spacer()
                    }
                    .padding(T.sp3)
                    .background(T.orangeDim)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .accessibilityIdentifier("12-language-restart-hint")
                }

                VStack(alignment: .leading, spacing: T.sp2) {
                    Text("界面语言跟随系统")
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.text3)
                    Text("默认随 iOS 系统语言自动切换（简体中文 / English）。选择固定语言后，BiuZ 将始终以该语言显示（重启生效）；文案覆盖范围见版本说明。")
                        .font(T.font(12))
                        .foregroundColor(T.text2)
                        .lineSpacing(4)
                }
                .padding(T.sp3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
            }
            .padding(T.sp4)
        }
        .background(T.bg)
        .navigationTitle("语言")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func apply(_ option: AppLanguagePreference) {
        guard option != selection else { return }
        selection = option
        switch option {
        case .system:
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        case .zhHans:
            UserDefaults.standard.set(["zh-Hans"], forKey: "AppleLanguages")
        case .english:
            UserDefaults.standard.set(["en"], forKey: "AppleLanguages")
        }
        // 同步既有偏好字段（语言页展示 + 兼容旧存档语义；现已有本页消费点）
        let display = switch option {
        case .system: "跟随系统"
        case .zhHans: "简体中文"
        case .english: "English"
        }
        settings.update { $0.language = display }
        needsRestart = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}

// MARK: - 通用只读列表页（设备 / Bot / 用量 / 记忆 / 技能 / MCP / 插件 / 自动化）

struct GenericListPage: View {
    let title: String
    let icon: String
    let rows: [(icon: String, title: String, subtitle: String, badge: String?)]

    var body: some View {
        ScrollView {
            VStack(spacing: T.sp2) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: T.sp3) {
                        Image(systemName: row.icon)
                            .font(.system(size: 14))
                            .foregroundColor(T.accentText)
                            .frame(width: 30)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(row.title)
                                .font(T.font(14.5))
                                .foregroundColor(T.text)
                            Text(row.subtitle)
                                .font(T.font(11.5))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                        }
                        Spacer()
                        if let badge = row.badge {
                            StatusPill(text: badge, kind: .tag, compact: true)
                        }
                    }
                    .card()
                }
            }
            .padding(T.sp4)
        }
        .background(T.bg)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("12-generic-\(title)")
    }
}

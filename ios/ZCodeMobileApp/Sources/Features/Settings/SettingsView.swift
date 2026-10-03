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

    // MARK: - 用户卡（头像 + Coding Plan 徽章 + 额度条 68%）
    // 连接态额度绑真实数据（usage-stats.getCodingPlanUsageSnapshot/getCodingPlanResetStatus
    // 只读投影）；演示态 / 缺数据回退演示值（68% · 340/500），演示行为不变

    private var usagePercentRemaining: Double {
        if !session.isDemo, let usage = session.codingPlanUsage, let percent = usage.percentRemaining {
            return percent
        }
        return 0.68
    }

    private var usagePercentText: String {
        "\(Int((usagePercentRemaining * 100).rounded()))%"
    }

    private var usageDetailText: String {
        if !session.isDemo, let usage = session.codingPlanUsage,
           let used = usage.used, let limit = usage.limit {
            let unit = usage.unitText ?? "积分"
            var detail = "本期已用 \(used) / \(limit) \(unit)"
            if let resetsAt = usage.resetsAtText {
                detail += " · \(resetsAt) 重置"
            }
            return detail
        }
        return "本月 500 条中的 340 条已使用，9 月 2 日重置"
    }

    private var userCard: some View {
        VStack(alignment: .leading, spacing: T.sp3) {
            HStack(spacing: T.sp3) {
                AgentAvatar(size: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Zai 开发者")
                        .font(T.font(17, .bold))
                        .foregroundColor(T.text)
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

    private var settingGroups: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            group("设备与远控") {
                navRow(route: .devices, icon: "laptopcomputer.and.iphone", title: "设备与配对",
                       subtitle: "云端沙盒在线 · 我的 Mac 在线", value: "2 台在线", identifier: "12-row-pairing")
                navRow(route: .bots, icon: "app.badge.filled", title: "IM Bot",
                       subtitle: "微信 / 飞书 / Telegram 通道", value: "已绑定飞书", identifier: "12-row-bot")
            }
            group("基础设置") {
                navRow(route: .model, icon: "cpu", title: "模型设置",
                       subtitle: nil, value: settings.value.model, identifier: "12-row-model")
                navRow(route: .appearance, icon: "circle.lefthalf.filled", title: "外观",
                       subtitle: nil, value: settings.value.appearance.label, identifier: "12-row-appearance")
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
                navRow(route: .usage, icon: "chart.bar", title: "用量统计",
                       subtitle: "近 30 天 token 与任务数", value: "12.4M tokens", identifier: "12-row-usage")
                navRow(route: .memory, icon: "brain", title: "记忆",
                       subtitle: "Agent 长期记忆条目", value: "128 条", identifier: "12-row-memory")
            }
            group("Agent 能力") {
                navRow(route: .skills, icon: "wand.and.stars", title: "技能",
                       subtitle: "12 项已启用", value: nil, identifier: "12-row-skills")
                navRow(route: .mcp, icon: "server.rack", title: "MCP",
                       subtitle: "4 个服务器已连接", value: nil, identifier: "12-row-mcp")
                navRow(route: .plugins, icon: "puzzlepiece.extension", title: "插件商店",
                       subtitle: nil, value: nil, badge: "New", identifier: "12-row-plugins")
                navRow(route: .automation, icon: "clock.badge.checkmark", title: "自动化",
                       subtitle: "定时任务与触发器", value: nil, badge: "Beta", identifier: "12-row-automation")
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
        case .serverDetail: ServerDetailView()
        case .devices: GenericListPage(
            title: "设备与配对", icon: "laptopcomputer.and.iphone",
            rows: [
                ("cloud.fill", "云端沙盒 · ap-east-1", "在线 · 用量 3.2 / 10 核时", nil),
                ("desktopcomputer", "我的 Mac (studio-m2)", "在线 · Host v2.3.1 · 电量 82%", nil),
                ("qrcode", "扫码配对新设备", "手机扫描桌面端二维码", nil),
            ])
        case .bots: GenericListPage(
            title: "IM Bot", icon: "app.badge.filled",
            rows: [
                ("message.fill", "飞书", "已绑定 · 活跃", nil),
                ("message.circle", "Telegram", "未绑定", nil),
            ])
        case .usage: GenericListPage(
            title: "用量统计", icon: "chart.bar",
            rows: [
                ("number", "近 30 天 tokens", "12.4M（日均 413K）", nil),
                ("checklist", "任务完成率", "94%（47 / 50）", nil),
                ("clock", "平均任务时长", "8 分 24 秒", nil),
            ])
        case .memory: GenericListPage(
            title: "记忆", icon: "brain",
            rows: [
                ("text.quote", "偏好 Swift + SwiftUI 原生实现", "2026-09-28 更新", nil),
                ("text.quote", "工作区主目录 ~/work/zcode", "2026-09-25 更新", nil),
                ("text.quote", "测试框架使用 swift-testing", "2026-09-20 更新", nil),
            ])
        case .skills: GenericListPage(
            title: "技能", icon: "wand.and.stars",
            rows: [
                ("wand.and.stars", "代码评审", "已启用", nil),
                ("wand.and.stars", "周报生成", "已启用", nil),
                ("wand.and.stars", "SQL 优化", "已停用", nil),
            ])
        case .mcp: GenericListPage(
            title: "MCP", icon: "server.rack",
            rows: [
                ("server.rack", "github-mcp", "已连接", nil),
                ("server.rack", "jira-mcp", "已连接", nil),
                ("server.rack", "figma-mcp", "已停用", nil),
            ])
        case .plugins: GenericListPage(
            title: "插件商店", icon: "puzzlepiece.extension",
            rows: [
                ("puzzlepiece.extension", "K8s 助手", "社区 · 4.8 分", "New"),
                ("puzzlepiece.extension", "数据库巡检", "官方 · 4.9 分", nil),
                ("puzzlepiece.extension", "API 翻译", "社区 · 4.6 分", nil),
            ])
        case .automation: GenericListPage(
            title: "自动化（Beta）", icon: "clock.badge.checkmark",
            rows: [
                ("clock", "每日站会摘要", "每天 09:30 · 上次成功", "Beta"),
                ("clock", "周末镜像同步", "每周六 02:00 · 上次失败", "Beta"),
            ])
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
                Text(title)
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                if let subtitle {
                    Text(subtitle)
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

    private let models = ["GLM-5.3", "GLM-5", "GLM-4.7"]

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
                                if settings.value.model == item {
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

// MARK: - 语言设置（持久化展示值）

struct LanguageSettingsView: View {
    @Environment(AppSettingsModel.self) private var settings
    private let languages = ["跟随系统", "简体中文", "English"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                ForEach(languages, id: \.self) { language in
                    Button {
                        settings.update { $0.language = language }
                    } label: {
                        HStack {
                            Text(language)
                                .font(T.font(14.5, .medium))
                                .foregroundColor(T.text)
                            Spacer()
                            if settings.value.language == language {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(T.accent)
                            }
                        }
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 48)
                    }
                    .accessibilityIdentifier("12-language-\(language)")
                }
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .padding(T.sp4)
        }
        .background(T.bg)
        .navigationTitle("语言")
        .navigationBarTitleDisplayMode(.inline)
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

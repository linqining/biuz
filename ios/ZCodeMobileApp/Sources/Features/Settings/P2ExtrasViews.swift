import SwiftUI

// MARK: - P2 批次：设置只读页接真（G-033/034/041/042）+ 多机切换（G-059）+ 诊断导出（G-061）
//
// 桌面基准（v3.14.3）：usage-stats 频道 getCodingPlanUsageSnapshot/getCodingPlanResetStatus
// （iOS 已接）+ zcode-agent.getAppUsageStats（zcodeAgentService.ts:3650，回执 appUsageSnapshotSchema，
// usage-stats.ts:241-251：summary{totalTokens,totalSessions,totalTurns,cacheHitRate,activeDays,
// favoriteModel…}）；重置机会 = getCodingPlanResetStatus.availableFiveHourResets + 
// requestCodingPlanResetOpportunity/useCodingPlanReset（桌面代执行写，合法远控）。

// MARK: - G-041/G-042 用量统计页（App 用量真值 + Coding Plan 额度 + 重置机会卡）

struct UsageStatsView: View {
    @Environment(AppSession.self) private var session
    /// 重置机会（getCodingPlanResetStatus.availableFiveHourResets 首个）
    struct ResetOpportunity: Equatable {
        var expireAt: Date
    }

    @State private var resetOpportunity: ResetOpportunity?
    @State private var isLoading = true
    @State private var errorText: String?
    @State private var claiming = false
    @State private var claimNotice: String?
    /// 待确认的重置卡使用（扣费类接口二次确认；nil = 无待确认）
    @State private var pendingResetType: AppSession.CodingPlanResetType?

    private var isConnected: Bool {
        if case .connected = session.mode { return true }
        return false
    }

    var body: some View {
        Group {
            if !isConnected {
                // G-033 验收③：演示文案仅演示态出现——连接页离线/演示呈明确态而非假数据
                EmptyStateView(
                    icon: "chart.bar",
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "用量统计来自桌面端 Agent 数据库，连接后此处展示真实数据"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "12-usage-act-connect")
            } else if isLoading {
                CenterLoadingView(text: "正在读取用量…")
                    .accessibilityIdentifier("12-usage-loading")
            } else if let error = errorText, session.appUsageSnapshot == nil, session.codingPlanUsage == nil {
                EmptyStateView(
                    icon: "chart.bar",
                    title: String(localized: "桌面端未提供用量数据"),
                    detail: error,
                    cta: String(localized: "重新加载"),
                    ctaAction: { Task { await reload() } },
                    ctaIdentifier: "12-usage-act-reload")
            } else {
                list
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "用量统计"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .refreshable { await reload() }
        // 重置卡二次确认（扣费/消耗接口：确认后核销一张卡，不可撤销）
        .confirmationDialog(
            String(localized: "确认使用重置卡？"),
            isPresented: Binding(
                get: { pendingResetType != nil },
                set: { if !$0 { pendingResetType = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "使用"), role: .destructive) {
                if let type = pendingResetType {
                    performResetUse(type)
                }
                pendingResetType = nil
            }
            .accessibilityIdentifier("12-usage-confirm-use")
            Button("取消", role: .cancel) { pendingResetType = nil }
        } message: {
            Text(String(localized: "将核销一张重置卡并重置对应窗口的额度，核销后不可撤销。剩余：5 小时卡 ×\(session.codingPlanUsage?.resetCards?.fiveHourCount ?? 0) · 周卡 ×\(session.codingPlanUsage?.resetCards?.weekCount ?? 0)"))
        }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                if let notice = claimNotice {
                    Text(notice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(T.accentText)
                        .accessibilityIdentifier("12-usage-claim-notice")
                }
                codingPlanCard
                if let opportunity = resetOpportunity {
                    resetCard(opportunity)
                }
                appUsageSection
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    /// Coding Plan 额度（重设计：全部额度窗口分行展示——5 小时/每周/每月 + 各自重置时间；
    /// 此前只取 limits 首窗导致数值不对且缺分窗）
    private var codingPlanCard: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack {
                Text(String(localized: "Coding Plan 额度"))
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text3)
                Spacer()
            }
            if let usage = session.codingPlanUsage, !usage.windows.isEmpty {
                ForEach(usage.windows) { window in
                    quotaWindowRow(window)
                }
                // 套餐档位角标（quota.level："max" 等）
                if let level = usage.unitText, !level.isEmpty {
                    Text(String(localized: "套餐档位 \(level)"))
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                }
                // 重置卡摘要（可领取明细见下方重置机会卡）
                if let cards = usage.resetCards,
                   cards.fiveHourCount > 0 || cards.weekCount > 0 {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "giftcard")
                            .font(.system(size: 11))
                            .foregroundColor(T.orange)
                        Text(String(localized: "重置卡：5 小时 ×\(cards.fiveHourCount) · 每周 ×\(cards.weekCount)"))
                            .font(T.font(11))
                            .foregroundColor(T.text2)
                        Spacer(minLength: 0)
                        if let earliest = cards.earliestExpireText {
                            Text(String(localized: "\(earliest) 前有效"))
                                .font(T.font(10.5))
                                .foregroundColor(T.text3)
                        }
                    }
                    .accessibilityIdentifier("12-usage-reset-cards-summary")
                }
            } else if let usage = session.codingPlanUsage {
                // 旧形态兜底（无 limits 数组的版本）
                if let percent = usage.percentRemaining {
                    ThinProgressBar(progress: percent, height: 5, tint: T.accent)
                }
                HStack {
                    if let used = usage.used, let limit = usage.limit {
                        Text("\(used) / \(limit) \(usage.unitText ?? "")")
                            .font(T.mono(11))
                            .foregroundColor(T.text2)
                    }
                    Spacer()
                    if let resets = usage.resetsAtText {
                        Text(String(localized: "\(resets) 重置"))
                            .font(T.font(10.5))
                            .foregroundColor(T.text3)
                    }
                }
            } else {
                Text(String(localized: "桌面端未返回 Coding Plan 用量（可能未订阅或版本不支持）"))
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
            }
        }
        .card()
        .accessibilityIdentifier("12-usage-plan-card")
    }

    /// 单个额度窗口行（进度条 + 用量/上限 + 重置时间）
    private func quotaWindowRow(_ window: CodingPlanQuotaWindow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(window.label)
                    .font(T.font(12, .semibold))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
                if let resets = window.resetsAtText {
                    Text(String(localized: "\(resets) 重置"))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
            }
            if let remaining = window.percentRemaining {
                ThinProgressBar(progress: remaining, height: 5,
                                tint: remaining < 0.15 ? T.orange : T.accent)
            }
            HStack {
                if let used = window.used, let limit = window.limit {
                    Text("\(used) / \(limit) \(window.unit ?? "")")
                        .font(T.mono(11))
                        .foregroundColor(T.text2)
                } else if let used = window.used {
                    Text(String(localized: "已用 \(used) \(window.unit ?? "")"))
                        .font(T.mono(11))
                        .foregroundColor(T.text2)
                }
                Spacer()
                if let remaining = window.percentRemaining {
                    Text(String(localized: "剩 \(Int((remaining * 100).rounded()))%"))
                        .font(T.mono(10.5, .semibold))
                        .foregroundColor(remaining < 0.15 ? T.orange : T.text3)
                }
            }
        }
        .padding(.vertical, 3)
        .accessibilityIdentifier("12-usage-window-\(window.level)")
    }

    /// 重置机会卡（G-042：一键领取，桌面代执行）
    private func resetCard(_ opportunity: ResetOpportunity) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 13))
                    .foregroundColor(T.orange)
                Text(String(localized: "有可领取的额度重置机会"))
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
            }
            Text(String(localized: "将于 \(Self.shortTime.string(from: opportunity.expireAt)) 前有效，过期作废"))
                .font(T.font(11))
                .foregroundColor(T.text3)
            if let cards = session.codingPlanUsage?.resetCards {
                Text(String(localized: "可用：5 小时卡 ×\(cards.fiveHourCount) · 周卡 ×\(cards.weekCount)"))
                    .font(T.font(11))
                    .foregroundColor(T.text2)
                if let last5h = cards.lastFiveHourUsedText {
                    Text(String(localized: "上次使用（5 小时窗）：\(last5h)"))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
                if let lastWeek = cards.lastWeekUsedText {
                    Text(String(localized: "上次使用（周窗）：\(lastWeek)"))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
            }
            // 分档使用（web 同构：resetType ∈ FIVE_HOUR|WEEK；AppSession.useCodingPlanResetCard
            // 三步桌面代执行——仅接线，本验收期不代触发）
            HStack(spacing: T.sp2) {
                resetTypeButton(
                    String(localized: "使用 5 小时卡"),
                    type: .fiveHour,
                    enabled: (session.codingPlanUsage?.resetCards?.fiveHourCount ?? 0) > 0,
                    identifier: "12-usage-act-claim-5h")
                resetTypeButton(
                    String(localized: "使用周卡"),
                    type: .week,
                    enabled: (session.codingPlanUsage?.resetCards?.weekCount ?? 0) > 0,
                    identifier: "12-usage-act-claim-week")
            }
            .accessibilityIdentifier("12-usage-act-claim")
        }
        .card()
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.45), lineWidth: 1))
        .accessibilityIdentifier("12-usage-reset-card")
    }

    /// 单档使用按钮（无卡置灰；点击先弹二次确认——使用重置卡是扣费/消耗接口，
    /// 确认后才走 AppSession.useCodingPlanResetCard 三步）
    private func resetTypeButton(
        _ title: String, type: AppSession.CodingPlanResetType,
        enabled: Bool, identifier: String
    ) -> some View {
        Button {
            pendingResetType = type
        } label: {
            HStack {
                if claiming { SpinnerView(color: T.onAccent, size: 14) }
                Text(claiming ? String(localized: "领取中…") : title)
            }
            .font(T.font(13, .semibold))
            .foregroundColor(enabled ? T.onAccent : T.text3)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(enabled ? T.accent : T.bgInput)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .disabled(claiming || !enabled)
        .accessibilityIdentifier(identifier)
    }

    /// 使用重置卡执行（二次确认后调用；扣费类接口不放自动触发）
    private func performResetUse(_ type: AppSession.CodingPlanResetType) {
        claiming = true
        claimNotice = nil
        Task {
            defer { claiming = false }
            let message = await session.useCodingPlanResetCard(type: type)
            if let message {
                claimNotice = message
            } else {
                claimNotice = String(localized: "已使用重置卡 · 额度刷新中")
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                await reload()
            }
        }
    }

    /// 使用统计（桌面基准重设计：summary 五指标 + 趋势折线 + 模型占比 + 工具用量；
    /// 数据源 usage-stats.getAppUsageSnapshot，session.appUsageSnapshot 投影）
    @ViewBuilder
    private var appUsageSection: some View {
        if let snapshot = session.appUsageSnapshot {
            VStack(alignment: .leading, spacing: T.sp3) {
                // 五指标（桌面同款：累计/峰值/最长聊天/当前连续/最长连续）
                LazyVGrid(columns: [
                    GridItem(.flexible()), GridItem(.flexible()),
                ], spacing: T.sp2) {
                    statCard(
                        Self.tokenText(snapshot.summary.totalTokens),
                        label: String(localized: "累计 Token"),
                        id: "total")
                    statCard(
                        Self.tokenText(snapshot.summary.peakDayTokens),
                        label: String(localized: "峰值 Token"),
                        id: "peak")
                    statCard(
                        Self.durationText(snapshot.summary.longestSessionMs),
                        label: String(localized: "最长聊天时长"),
                        id: "chat")
                    statCard(
                        snapshot.summary.currentStreakDays.map { "\($0) 天" },
                        label: String(localized: "当前连续天数"),
                        id: "streak")
                    statCard(
                        snapshot.summary.longestStreakDays.map { "\($0) 天" },
                        label: String(localized: "最长连续天数"),
                        id: "best-streak")
                    statCard(
                        snapshot.summary.totalSessions.map { "\($0)" },
                        label: String(localized: "会话数"),
                        id: "sessions")
                }

                // 每日 Token 趋势（按模型分线；近 30 日）
                if !snapshot.daily.isEmpty {
                    VStack(alignment: .leading, spacing: T.sp2) {
                        Text(String(localized: "每日 Token 趋势（近 30 日）"))
                            .font(T.font(12.5, .semibold))
                            .foregroundColor(T.text)
                        UsageTrendChart(daily: snapshot.daily)
                            .frame(height: 130)
                        seriesLegend(snapshot)
                    }
                    .padding(T.sp3)
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rL))
                    .accessibilityIdentifier("12-usage-trend")
                }

                // 模型用量占比（桌面 donut 的移动端等价：占比条 + 百分比）
                if !snapshot.models.isEmpty {
                    VStack(alignment: .leading, spacing: T.sp2) {
                        Text(String(localized: "模型用量"))
                            .font(T.font(12.5, .semibold))
                            .foregroundColor(T.text)
                        ForEach(snapshot.models.sorted { $0.totalTokens > $1.totalTokens }) { model in
                            modelBar(model, total: snapshot.models.map(\.totalTokens).reduce(0, +))
                        }
                    }
                    .padding(T.sp3)
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rL))
                    .accessibilityIdentifier("12-usage-models")
                }

                // 工具用量 Top（调用次数排序）
                if !snapshot.tools.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(String(localized: "工具用量"))
                            .font(T.font(12.5, .semibold))
                            .foregroundColor(T.text)
                            .padding(.bottom, T.sp1)
                        ForEach(
                            snapshot.tools.sorted { $0.callCount > $1.callCount }.prefix(6)
                        ) { tool in
                            usageRow(
                                tool.toolName,
                                value: String(localized: "\(tool.callCount) 次"),
                                id: "tool-\(tool.toolName)")
                        }
                    }
                    .padding(T.sp3)
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rL))
                    .accessibilityIdentifier("12-usage-tools")
                }

                // 次要指标行（会话/回合/缓存命中率/活跃天）
                VStack(spacing: 0) {
                    usageRow(
                        String(localized: "轮次数"),
                        value: snapshot.summary.totalTurns.map { "\($0)" }, id: "turns")
                    usageRow(
                        String(localized: "缓存命中率"),
                        value: snapshot.summary.cacheHitRate.map { String(format: "%.0f%%", $0 * 100) },
                        id: "cache")
                    usageRow(
                        String(localized: "活跃天数"),
                        value: snapshot.summary.activeDays.map { "\($0)" }, id: "days")
                    if let favorite = snapshot.summary.favoriteModelId {
                        let share = snapshot.summary.favoriteModelShare.map {
                            String(format: "%.0f%%", $0 * 100)
                        }
                        usageRow(
                            String(localized: "常用模型"),
                            value: share.map { "\(favorite) · \($0)" } ?? favorite,
                            id: "model")
                    }
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
            }
        } else {
            Text(String(localized: "App 用量分布：桌面端未提供（getAppUsageStats 不可用）"))
                .font(T.font(11))
                .foregroundColor(T.text3)
                .accessibilityIdentifier("12-usage-app-missing")
        }
    }

    private func statCard(_ value: String?, label: String, id: String) -> some View {
        VStack(spacing: 3) {
            Text(value ?? "--")
                .font(T.mono(15, .semibold))
                .foregroundColor(T.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(T.font(10.5))
                .foregroundColor(T.text3)
        }
        .frame(maxWidth: .infinity, minHeight: 62)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rL))
        .accessibilityIdentifier("12-usage-stat-\(id)")
    }

    private func seriesLegend(_ snapshot: AppUsageInfo) -> some View {
        let series = UsageTrendChart.seriesModels(daily: snapshot.daily)
        return HStack(spacing: T.sp3) {
            ForEach(Array(series.enumerated()), id: \.element) { index, model in
                HStack(spacing: 4) {
                    Circle()
                        .fill(UsageTrendChart.seriesColor(index))
                        .frame(width: 7, height: 7)
                    Text(model)
                        .font(T.font(10.5))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                }
            }
        }
    }

    private func modelBar(_ model: AppUsageModelSlice, total: Double) -> some View {
        let share = model.share ?? (total > 0 ? model.totalTokens / total : 0)
        return VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(model.modelId)
                    .font(T.font(11.5))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(String(format: "%.0f%%", share * 100))
                    .font(T.mono(10.5, .semibold))
                    .foregroundColor(T.text2)
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(T.bgInput)
                    Capsule()
                        .fill(T.accent.opacity(0.75))
                        .frame(width: max(4, proxy.size.width * share))
                }
            }
            .frame(height: 6)
        }
        .accessibilityIdentifier("12-usage-model-\(model.modelId)")
    }

    /// Token 数格式化（桌面同款「亿/万」口径）
    static func tokenText(_ tokens: Double?) -> String? {
        guard let tokens else { return nil }
        if tokens >= 100_000_000 {
            return String(format: "%.1f 亿", tokens / 100_000_000)
        }
        if tokens >= 10_000 {
            return String(format: "%.0f 万", tokens / 10_000)
        }
        return Int(tokens).description
    }

    /// 毫秒时长格式化（「20 小时 22 分钟」桌面同款）
    static func durationText(_ ms: Int?) -> String? {
        guard let ms, ms > 0 else { return nil }
        let minutes = Int((Double(ms) / 60_000).rounded())
        let hours = minutes / 60
        if hours >= 1 {
            return String(localized: "\(hours) 小时 \(minutes % 60) 分钟")
        }
        return String(localized: "\(minutes) 分钟")
    }

    private func usageRow(_ title: String, value: String?, id: String) -> some View {
        HStack {
            Text(title).font(T.font(12.5)).foregroundColor(T.text)
            Spacer()
            Text(value ?? "--")
                .font(T.mono(11.5))
                .foregroundColor(value == nil ? T.text3 : T.text2)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 40)
        .accessibilityIdentifier("12-usage-row-\(id)")
    }

    // MARK: 数据加载

    private func reload() async {
        guard isConnected else { return }
        isLoading = true
        errorText = nil
        defer { isLoading = false }
        // ① Coding Plan 额度（既有真实链路重拉）
        await session.refreshDesktopReadonlyInfo()
        // ② 重置机会（getCodingPlanResetStatus.availableFiveHourResets）

        await loadResetOpportunity()
    }

    private func loadResetOpportunity() async {
        var builder = JSONObjectBuilder()
        builder.set("preferredProviderId", "zai")
        if let result = try? await session.connection.call(
            "usage-stats", "getCodingPlanResetStatus", .json(.object(builder.fields))),
           let dict = result.jsonValue?.objectValue,
           let first = dict["availableFiveHourResets"]?.arrayValue?.first?.objectValue,
           let expireAt = first["expireAt"]?.doubleValue, expireAt > 0 {
            resetOpportunity = ResetOpportunity(expireAt: Date(timeIntervalSince1970: expireAt / 1000))
        } else {
            resetOpportunity = nil
        }
    }

    static let shortTime: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()
}

// MARK: - G-034/G-059 设备与配对页（真实状态 + 多机切换）

struct DevicesPage: View {
    @Environment(AppSession.self) private var session
    @State private var connectingID: String?

    private var connectedServerID: String? {
        switch session.mode {
        case .connected(let server), .connecting(let server):
            return server.id
        default:
            return nil
        }
    }

    private var onlineCount: Int { connectedServerID == nil ? 0 : 1 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                // 汇总行（G-034 验收②：在线数与实际连接数一致——中继链路同一时刻至多 1 条）
                Text(String(localized: "\(ServerRegistry.servers.count) 台已配对 · \(onlineCount) 台在线"))
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .accessibilityIdentifier("12-devices-summary")

                ForEach(ServerRegistry.servers) { server in
                    deviceRow(server)
                }

                // 云端沙盒（无移动端执行通道——如实标注，不显示假在线）
                HStack(spacing: T.sp3) {
                    Image(systemName: "cloud")
                        .font(.system(size: 15))
                        .foregroundColor(T.text3)
                        .frame(width: 30)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(String(localized: "云端沙盒"))
                            .font(T.font(14, .medium))
                            .foregroundColor(T.text)
                        Text(String(localized: "执行端规划中 · 暂无移动端接入通道"))
                            .font(T.font(11))
                            .foregroundColor(T.text3)
                    }
                    Spacer()
                    Text(String(localized: "未接入"))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                }
                .padding(T.sp3)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
                .accessibilityIdentifier("12-devices-cloud")

                Button {
                    session.requestConnectFlow(editTokenOnly: false)
                } label: {
                    Label(String(localized: "扫码配对新设备"), systemImage: "qrcode")
                        .font(T.font(13.5, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("12-devices-act-add")
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
        .background(T.bg)
        .navigationTitle(String(localized: "设备与配对"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func deviceRow(_ server: ServerConfig) -> some View {
        let isConnected = server.id == connectedServerID
        let isConnecting = server.id == connectingID
        return Button {
            guard !isConnected, !isConnecting else { return }
            connectingID = server.id
            Task {
                await session.connect(server: server)
                connectingID = nil
            }
        } label: {
            HStack(spacing: T.sp3) {
                Image(systemName: server.relay != nil ? "laptopcomputer.and.iphone" : "server.rack")
                    .font(.system(size: 15))
                    .foregroundColor(isConnected ? T.accentText : T.text3)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(server.displayName)
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    Text(server.displayAddress)
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
                Spacer()
                if isConnecting {
                    SpinnerView(size: 14)
                } else {
                    HStack(spacing: 4) {
                        Circle().fill(isConnected ? T.accent : T.text3.opacity(0.5))
                            .frame(width: 6, height: 6)
                        Text(isConnected ? String(localized: "在线") : String(localized: "离线"))
                            .font(T.font(10.5))
                            .foregroundColor(isConnected ? T.accentText : T.text3)
                    }
                }
                if !isConnected {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(T.text3)
                }
            }
            .padding(T.sp3)
            .frame(minHeight: 56)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(
                isConnected ? T.accent.opacity(0.4) : T.border, lineWidth: 1))
        }
        .buttonStyle(PressableButtonStyle())
        .disabled(isConnected || isConnecting)
        .accessibilityIdentifier("12-devices-row-\(server.id)")
    }
}

// MARK: - G-061 导出诊断日志

struct DiagnosticsExportView: View {
    @Environment(AppSession.self) private var session
    @State private var exportURL: URL?
    @State private var errorText: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                VStack(alignment: .leading, spacing: T.sp2) {
                    Text(String(localized: "导出诊断日志"))
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.text3)
                    Text(String(localized: "打包连接日志（connect.log）与诊断键值、应用版本，用于问题反馈。凭据与令牌不会包含（输出前掩码校验）。"))
                        .font(T.font(12))
                        .foregroundColor(T.text2)
                        .lineSpacing(4)
                }
                .card()

                Button {
                    exportDiagnostics()
                } label: {
                    Label(String(localized: "生成并分享日志文件"), systemImage: "square.and.arrow.up")
                        .font(T.font(13.5, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .accessibilityIdentifier("12-diag-act-export")

                if let error = errorText {
                    Text(error)
                        .font(T.font(11.5))
                        .foregroundColor(T.red)
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
        .background(T.bg)
        .navigationTitle(String(localized: "诊断"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: Binding(
            get: { exportURL.map(ShareFilePayload.init) },
            set: { if $0 == nil { exportURL = nil } })) { payload in
            ActivityView(payload: payload)
        }
    }

    private func exportDiagnostics() {
        var lines: [String] = []
        lines.append("BiuZ Diagnostics · \(Date())")
        let info = Bundle.main.infoDictionary
        lines.append("appVersion: \(info?["CFBundleShortVersionString"] ?? "?") (\(info?["CFBundleVersion"] ?? "?"))")
        lines.append("osVersion: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        lines.append("connectionMode: \(session.mode)")
        lines.append("oauthLoggedIn: \(session.isOAuthLoggedIn)")
        lines.append("servers: \(ServerRegistry.servers.map { "\($0.displayName)(relay=\($0.relay != nil))" }.joined(separator: ", "))")
        lines.append("")
        lines.append("--- connect.log ---")
        for log in session.connectLogs {
            // 令牌不落盘：日志行已掩码（log 面不带 token 本体），双保险再滤 token= 段
            let sanitized = log.text.replacingOccurrences(of: "token=[^\\s]*", with: "token=***", options: .regularExpression)
            lines.append("[\(log.kind)] \(sanitized)")
        }
        lines.append("")
        lines.append("--- diag ---")
        for key in ["diag.lastConnect", "diag.args", "diag.assemble", "diag.autoConnect"] {
            if let value = UserDefaults.standard.string(forKey: key) {
                lines.append("\(key): \(value)")
            }
        }
        let body = lines.joined(separator: "\n")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("biuz-diagnostics-\(Int(Date().timeIntervalSince1970)).txt")
        do {
            try body.write(to: url, atomically: true, encoding: .utf8)
            exportURL = url
            errorText = nil
        } catch {
            errorText = String(localized: "导出失败 · \(error.localizedDescription)")
        }
    }
}

// MARK: - G-033 其余只读页连接态占位（memory/skills/mcp/plugins：读面未接入不展示假数据）

struct RemoteCapabilityPlaceholderPage: View {
    let title: String
    let icon: String
    @Environment(AppSession.self) private var session
    @State private var reloadToken = 0

    var body: some View {
        EmptyStateView(
            icon: icon,
            title: String(localized: "桌面端未提供该数据"),
            detail: String(localized: "\(title)的移动端读面尚未接入；为避免误导，连接态不再展示示例数据。请在桌面端查看，或稍后版本更新后同步。"),
            cta: String(localized: "重新检查"),
            ctaAction: { reloadToken += 1 },
            ctaIdentifier: "12-capability-act-reload")
            .id(reloadToken)
            .background(T.bg)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - G-011 桌面能力只读清单页（memory / skills / MCP / plugins）
//
// 连接态调桌面只读方法渲染真实清单（channel=zcode-agent，桌面 zcodeAgent.ts 接口族）：
// memory→listProjectMemories、skills→getSkillReferenceCatalog、MCP→listMcpServerStatuses
// （{workspacePath} 界定范围）、plugins→listPlugins。回执宽容解析（回执形态未逐项取证，
// 服务端不识别/缺键时按占位降级，不臆造数据）；失败/空 → RemoteCapabilityPlaceholderPage
// 诚实占位；零写入口（安装/卸载/启停等写面维持 ReadOnlyGate 拦截）。

struct RemoteCapabilityListPage: View {
    enum Capability {
        case memory, skills, mcp, plugins
        /// G-022：已保存工作流库（listSavedWorkflows + listSavedWorkflowRuns；写面维持拦截）
        case savedWorkflows
        /// G-024：错峰任务（off-peak list/get 只读；创建/取消等写面维持拦截）
        case offPeak
        /// G-025：反馈工单（feedback list 只读；创建/附件上传写面维持拦截）
        case feedback
    }

    struct Row: Identifiable {
        let id: String
        let icon: String
        let title: String
        let subtitle: String
        let badge: String?
    }

    enum LoadPhase: Equatable {
        case loading, loaded, failed
    }

    let capability: Capability
    let title: String
    let icon: String

    @Environment(AppSession.self) private var session
    @State private var rows: [Row] = []
    @State private var phase: LoadPhase = .loading
    @State private var reloadToken = 0

    var body: some View {
        Group {
            switch phase {
            case .loading:
                CenterLoadingView(text: "正在读取桌面端数据…")
            case .loaded where !rows.isEmpty:
                ScrollView {
                    VStack(spacing: T.sp2) {
                        ForEach(rows) { row in
                            HStack(spacing: T.sp3) {
                                Image(systemName: row.icon)
                                    .font(.system(size: 14))
                                    .foregroundColor(T.accentText)
                                    .frame(width: 30)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(row.title)
                                        .font(T.font(14.5))
                                        .foregroundColor(T.text)
                                        .lineLimit(1)
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
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("12-capability-row-\(row.id)")
                        }
                    }
                    .padding(T.sp4)
                }
                .background(T.bg)
                .refreshable { await load() }
            case .loaded, .failed:
                // 空清单/读取失败：诚实占位（不给假数据）
                RemoteCapabilityPlaceholderPage(title: title, icon: icon)
            }
        }
        .background(T.bg)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: reloadToken) { await load() }
    }

    private func load() async {
        phase = .loading
        rows = []
        let connection = session.connection
        guard connection.isActive else {
            phase = .failed
            return
        }
        do {
            switch capability {
            case .memory:
                // listProjectMemories → ProjectMemoryWorkspaceSummary[]{id,label,updatedAt,files[]}
                let result = try await connection.call("zcode-agent", "listProjectMemories", .undefined)
                let items = result.jsonValue?.arrayValue
                    ?? result.jsonValue?["workspaces"]?.arrayValue
                    ?? result.jsonValue?["memories"]?.arrayValue
                    ?? []
                rows = items.compactMap { item in
                    guard let d = item.objectValue,
                          let label = d["label"]?.stringValue ?? d["id"]?.stringValue else { return nil }
                    let fileCount = d["files"]?.arrayValue?.count ?? 0
                    let updated = (d["updatedAt"]?.intValue).map {
                        Self.shortFormatter.string(from: Date(timeIntervalSince1970: Double($0) / 1000))
                    } ?? ""
                    return Row(
                        id: label,
                        icon: "brain",
                        title: label,
                        subtitle: String(format: String(localized: "%lld 个记忆文件 · 更新于 %@"), fileCount, updated),
                        badge: nil)
                }
            case .skills:
                // getSkillReferenceCatalog → 宽容取 skills[]/catalog.skills[]/entries[]
                let result = try await connection.call("zcode-agent", "getSkillReferenceCatalog", .undefined)
                let items = result.jsonValue?["skills"]?.arrayValue
                    ?? result.jsonValue?["catalog"]?.objectValue?["skills"]?.arrayValue
                    ?? result.jsonValue?["entries"]?.arrayValue
                    ?? result.jsonValue?.arrayValue
                    ?? []
                rows = items.compactMap { item in
                    guard let d = item.objectValue,
                          let name = d["name"]?.stringValue ?? d["title"]?.stringValue else { return nil }
                    let enabled = d["enabled"]?.boolValue
                    return Row(
                        id: name,
                        icon: "wand.and.stars",
                        title: name,
                        subtitle: d["description"]?.stringValue
                            ?? d["summary"]?.stringValue
                            ?? String(localized: "技能目录条目"),
                        badge: enabled == nil ? nil : (enabled! ? String(localized: "已启用") : String(localized: "已停用")))
                }
            case .mcp:
                // listMcpServerStatuses（{workspacePath} 界定范围）→ servers[]/mcpServers[]
                var builder = JSONObjectBuilder()
                if let workspacePath = connection.workspace?.path {
                    builder.set("workspacePath", workspacePath)
                }
                let result = try await connection.call(
                    "zcode-agent", "listMcpServerStatuses", .json(.object(builder.fields)))
                let items = result.jsonValue?["servers"]?.arrayValue
                    ?? result.jsonValue?["mcpServers"]?.arrayValue
                    ?? result.jsonValue?.arrayValue
                    ?? []
                rows = items.compactMap { item in
                    guard let d = item.objectValue,
                          let name = d["name"]?.stringValue ?? d["server"]?.stringValue
                              ?? d["id"]?.stringValue else { return nil }
                    let status = d["status"]?.stringValue
                        ?? d["state"]?.stringValue
                        ?? d["connectionState"]?.stringValue
                    let badge = status.map { raw in
                        switch raw.lowercased() {
                        case "connected", "ready", "ok": return String(localized: "已连接")
                        case "disabled", "stopped": return String(localized: "已停用")
                        default: return raw
                        }
                    }
                    return Row(
                        id: name,
                        icon: "server.rack",
                        title: name,
                        subtitle: d["command"]?.stringValue
                            ?? d["transport"]?.stringValue
                            ?? String(localized: "MCP 服务器"),
                        badge: badge)
                }
            case .plugins:
                // listPlugins → plugins[]/items[]
                let result = try await connection.call("zcode-agent", "listPlugins", .undefined)
                let items = result.jsonValue?["plugins"]?.arrayValue
                    ?? result.jsonValue?["items"]?.arrayValue
                    ?? result.jsonValue?.arrayValue
                    ?? []
                rows = items.compactMap { item in
                    guard let d = item.objectValue,
                          let name = d["name"]?.stringValue ?? d["pluginName"]?.stringValue
                              ?? d["id"]?.stringValue else { return nil }
                    let version = d["version"]?.stringValue
                    let enabled = d["enabled"]?.boolValue
                    return Row(
                        id: name,
                        icon: "puzzlepiece.extension",
                        title: name,
                        subtitle: version.map { String(format: String(localized: "版本 %@"), $0) }
                            ?? d["description"]?.stringValue
                            ?? String(localized: "桌面端插件"),
                        badge: enabled == nil ? nil : (enabled! ? String(localized: "已启用") : String(localized: "已停用")))
                }
            case .savedWorkflows:
                // G-022：已保存工作流库（桌面 <cwd>/.zcode/workflows）+ 最近运行；只读
                let workflowsResult = try await connection.call(
                    "zcode-agent", "listSavedWorkflows", .undefined)
                let workflows = workflowsResult.jsonValue?["workflows"]?.arrayValue
                    ?? workflowsResult.jsonValue?["savedWorkflows"]?.arrayValue
                    ?? workflowsResult.jsonValue?["items"]?.arrayValue
                    ?? workflowsResult.jsonValue?.arrayValue
                    ?? []
                var loaded: [Row] = workflows.compactMap { item in
                    guard let d = item.objectValue,
                          let name = d["name"]?.stringValue ?? d["workflowName"]?.stringValue
                              ?? d["id"]?.stringValue else { return nil }
                    return Row(
                        id: "wf-\(name)",
                        icon: "flowchart.fill",
                        title: name,
                        subtitle: d["description"]?.stringValue
                            ?? d["path"]?.stringValue
                            ?? String(localized: "已保存工作流"),
                        badge: nil)
                }
                if let runsResult = try? await connection.call(
                    "zcode-agent", "listSavedWorkflowRuns", .undefined) {
                    let runs = runsResult.jsonValue?["runs"]?.arrayValue
                        ?? runsResult.jsonValue?["items"]?.arrayValue
                        ?? []
                    loaded += runs.compactMap { item in
                        guard let d = item.objectValue,
                              let runId = d["runId"]?.stringValue ?? d["id"]?.stringValue else { return nil }
                        let started = (d["startedAt"]?.intValue).map {
                            Self.shortFormatter.string(from: Date(timeIntervalSince1970: Double($0) / 1000))
                        } ?? ""
                        return Row(
                            id: "run-\(runId)",
                            icon: "clock.arrow.circlepath",
                            title: d["workflowName"]?.stringValue ?? runId,
                            subtitle: started.isEmpty ? String(localized: "最近运行") : String(format: String(localized: "最近运行 · %@"), started),
                            badge: String(localized: "运行"))
                    }
                }
                rows = loaded
            case .offPeak:
                // G-024：错峰任务 list/get 只读（ZCodeOffPeakTask；创建/取消/暂停写面维持拦截）
                let result = try await connection.call("off-peak", "list", .undefined)
                let items = result.jsonValue?.arrayValue
                    ?? result.jsonValue?["tasks"]?.arrayValue
                    ?? result.jsonValue?["items"]?.arrayValue
                    ?? []
                rows = items.compactMap { item in
                    guard let d = item.objectValue,
                          let id = d["offPeakTaskId"]?.stringValue ?? d["id"]?.stringValue else { return nil }
                    let title = d["title"]?.stringValue
                        ?? d["name"]?.stringValue
                        ?? d["prompt"]?.stringValue
                        ?? id
                    let created = (d["createdAt"]?.intValue).map {
                        Self.shortFormatter.string(from: Date(timeIntervalSince1970: Double($0) / 1000))
                    } ?? ""
                    return Row(
                        id: id,
                        icon: "moon.stars",
                        title: String(localized: "错峰任务"),
                        subtitle: created.isEmpty ? title : String(format: String(localized: "%@ · 创建于 %@"), title, created),
                        badge: d["status"]?.stringValue)
                }
            case .feedback:
                // G-025：反馈工单列表只读（feedback.list；创建/附件上传写面维持拦截）
                let result = try await connection.call("feedback", "list", .undefined)
                let items = result.jsonValue?["items"]?.arrayValue
                    ?? result.jsonValue?.arrayValue
                    ?? []
                rows = items.compactMap { item in
                    guard let d = item.objectValue,
                          let id = d["id"]?.stringValue ?? d["ticketId"]?.stringValue else { return nil }
                    let title = d["title"]?.stringValue
                        ?? d["subject"]?.stringValue
                        ?? String(localized: "反馈工单")
                    let updated = (d["updatedAt"]?.intValue).map {
                        Self.shortFormatter.string(from: Date(timeIntervalSince1970: Double($0) / 1000))
                    } ?? ""
                    return Row(
                        id: id,
                        icon: "ladybug",
                        title: title,
                        subtitle: updated.isEmpty ? String(localized: "工单 \(id)") : String(format: String(localized: "工单 %@ · 更新于 %@"), id, updated),
                        badge: d["status"]?.stringValue)
                }
            }
            phase = .loaded
        } catch {
            // 读面失败：诚实占位（不展示假数据；不臆造回执结构）
            phase = .failed
        }
    }

    static let shortFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d HH:mm"
        return formatter
    }()
}

// MARK: - 每日 Token 趋势折线（Canvas 多序列；桌面「每日 Token 趋势图」移动端等价）

struct UsageTrendChart: View {
    let daily: [AppUsageDailyPoint]

    /// 序列 = 按总量降序的模型名（稳定色序）
    static func seriesModels(daily: [AppUsageDailyPoint]) -> [String] {
        var totals: [String: Double] = [:]
        for point in daily {
            for (model, tokens) in point.byModel {
                totals[model, default: 0] += tokens
            }
        }
        return totals.sorted { $0.value > $1.value }.map(\.key)
    }

    static func seriesColor(_ index: Int) -> Color {
        let palette: [Color] = [.blue, .green, .orange, .purple, .teal]
        return palette[index % palette.count]
    }

    var body: some View {
        let series = Self.seriesModels(daily: daily)
        let maxValue = daily.compactMap { point in
            point.byModel.values.max()
        }.max() ?? 1
        Canvas { context, size in
            guard !daily.isEmpty, maxValue > 0 else { return }
            let stepX = daily.count > 1 ? size.width / CGFloat(daily.count - 1) : size.width
            for fraction in [0.25, 0.5, 0.75] {
                let y = size.height * (1 - fraction)
                var grid = Path()
                grid.move(to: CGPoint(x: 0, y: y))
                grid.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(grid, with: .color(T.border.opacity(0.5)), lineWidth: 0.5)
            }
            for (seriesIndex, model) in series.enumerated() {
                var path = Path()
                var started = false
                for (pointIndex, point) in daily.enumerated() {
                    let tokens = point.byModel[model] ?? 0
                    let x = CGFloat(pointIndex) * stepX
                    let y = size.height * (1 - CGFloat(tokens / maxValue))
                    if started {
                        path.addLine(to: CGPoint(x: x, y: y))
                    } else {
                        path.move(to: CGPoint(x: x, y: y))
                        started = true
                    }
                }
                context.stroke(
                    path,
                    with: .color(Self.seriesColor(seriesIndex)),
                    style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
            }
        }
        .accessibilityIdentifier("12-usage-trend-canvas")
    }
}

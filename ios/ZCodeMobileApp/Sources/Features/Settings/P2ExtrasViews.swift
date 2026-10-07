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
        // 重置卡二次确认（扣费/消耗接口：确认后核销一张卡，不可撤销）。
        // 自绘底部面板（用户 2026-10-07：系统 alert 风格与 App 设计不符——
        // AttachmentSourceSheet 同款设计语言：grabber + 卡片明细 + 主行动/取消）
        .sheet(isPresented: Binding(
            get: { pendingResetType != nil },
            set: { if !$0 { pendingResetType = nil } })) {
            // type 必须在呈现期捕获为局部常量再传参：确认回调若回读
            // pendingResetType，收起 sheet 的 binding 置 nil 先于回调执行，
            // 回调读到空值即永不执行（2026-10-07 回归根因「改了什么都不能用了」）
            if let resetType = pendingResetType {
                ResetConfirmSheet(
                    type: resetType,
                    fiveHourCount: session.codingPlanUsage?.resetCards?.fiveHourCount ?? 0,
                    weekCount: session.codingPlanUsage?.resetCards?.weekCount ?? 0) {
                    performResetUse(resetType)
                    pendingResetType = nil
                }
                .presentationDetents([.height(380)])
                .presentationDragIndicator(.hidden)
                .presentationBackground(T.bgElevated)
            }
        }
    }

    /// 重置二次确认文案（用户硬要求 2026-10-06：写明将重置哪个额度窗口与影响，严禁
    /// 一点即发；web 弹层同按 resetType 区分 dialog.fiveHour/dialog.week，bundle 实证）
    static func resetConfirmMessage(isWeek: Bool, fiveHourCount: Int, weekCount: Int) -> String {
        let windowName = isWeek ? String(localized: "每周") : String(localized: "5 小时")
        return String(localized: "将核销一张\(windowName)重置卡，并重置\(windowName)窗口的额度。当前剩余：5 小时卡 ×\(fiveHourCount) · 周卡 ×\(weekCount)。核销后不可撤销。")
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
                // 重置卡摘要（可使用明细见下方重置机会卡）。5h/周卡过期时间分开展示
                // （用户裁决：两类卡作用窗口不同，不合并取最早）
                if let cards = usage.resetCards,
                   cards.fiveHourCount > 0 || cards.weekCount > 0 {
                    VStack(alignment: .leading, spacing: 2) {
                        if cards.fiveHourCount > 0 {
                            HStack(spacing: T.sp2) {
                                Image(systemName: "giftcard")
                                    .font(.system(size: 11))
                                    .foregroundColor(T.orange)
                                Text(String(localized: "重置卡：5 小时 ×\(cards.fiveHourCount)"))
                                    .font(T.font(11))
                                    .foregroundColor(T.text2)
                                Spacer(minLength: 0)
                                if let earliest = cards.fiveHourEarliestText {
                                    Text(String(localized: "\(earliest) 前有效"))
                                        .font(T.font(10.5))
                                        .foregroundColor(T.text3)
                                }
                            }
                        }
                        if cards.weekCount > 0 {
                            HStack(spacing: T.sp2) {
                                Image(systemName: "giftcard")
                                    .font(.system(size: 11))
                                    .foregroundColor(T.orange)
                                Text(String(localized: "重置卡：每周 ×\(cards.weekCount)"))
                                    .font(T.font(11))
                                    .foregroundColor(T.text2)
                                Spacer(minLength: 0)
                                if let earliest = cards.weekEarliestText {
                                    Text(String(localized: "\(earliest) 前有效"))
                                        .font(T.font(10.5))
                                        .foregroundColor(T.text3)
                                }
                            }
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
            // 当前套餐权益子块（P3-9：getEntitlementSnapshot）——插在三分支之后，
            // usage 为 nil/旧形态/无窗口三形态下都有渲染位（设计稿 §9.1）
            entitlementSection
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

    /// 当前套餐权益子块（P3-9：usage-stats.getEntitlementSnapshot，纯展示无交互）。
    /// 状态矩阵（设计稿 §9.4 + web 口径 2026-10-06）：成功=权益行列表（≤8 行，多则
    /// 「在桌面端查看全部」尾行）+ 档位头（quota.level，缺席回落首条 productName）+
    /// 顶层 remaining 透出；未订阅（unavailableReason === "no_plan"）=单行如实空态；
    /// 空（无在期订阅条目）=子块整体不渲染；失败（投影 nil 且页面已过加载态）=
    /// 单行诚实提示；演示态/断开=整页已是未连接 EmptyStateView，子块不渲染。
    /// 加载中由页面级 CenterLoadingView 覆盖（reload 期间整页 loading，无需子块级 Spinner）。
    @ViewBuilder
    private var entitlementSection: some View {
        if let info = session.codingPlanEntitlements, info.noPlan {
            // web 空态判定（sidebar.usage.plan.noPlan 同位）：未订阅套餐如实呈现
            HStack(spacing: 4) {
                Image(systemName: "info.circle")
                    .font(.system(size: 11))
                Text(String(localized: "当前账号未订阅 Coding Plan 套餐"))
            }
            .font(T.font(11))
            .foregroundColor(T.text3)
            .accessibilityIdentifier("12-usage-entitlement-no-plan")
        } else if let info = session.codingPlanEntitlements, !info.entitlements.isEmpty {
            VStack(alignment: .leading, spacing: T.sp1) {
                HStack(spacing: T.sp2) {
                    if let tier = info.tier, !tier.isEmpty {
                        Text(String(localized: "当前套餐权益 · \(tier)"))
                    } else {
                        Text(String(localized: "当前套餐权益"))
                    }
                    Spacer(minLength: 0)
                    // 顶层 remaining（web 仅做在场判定；单位语义未取证，原样透出不加解释）
                    if let remaining = info.remainingText, !remaining.isEmpty {
                        Text(String(localized: "剩 \(remaining)"))
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
                    }
                }
                .font(T.font(11.5, .semibold))
                .foregroundColor(T.text3)
                .accessibilityIdentifier("12-usage-entitlement-header")
                ForEach(info.entitlements.prefix(8)) { item in
                    entitlementRow(item)
                }
                if info.entitlements.count > 8 {
                    Text(String(localized: "在桌面端查看全部"))
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                        .accessibilityIdentifier("12-usage-entitlement-more")
                }
            }
            .padding(T.sp2)
            .background(T.bgInput)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("12-usage-entitlement-section")
        } else if session.codingPlanEntitlements == nil {
            // 读取失败/未取到：单行诚实提示（空回执在上方分支整体不渲染，不到这里）
            HStack(spacing: 4) {
                Image(systemName: "info.circle")
                    .font(.system(size: 11))
                Text(String(localized: "权益信息不可用 · 下拉刷新重试"))
            }
            .font(T.font(11))
            .foregroundColor(T.text3)
            .accessibilityIdentifier("12-usage-entitlement-unavailable")
        }
    }

    /// 权益行：✓ 图标 + 名称（+描述）+ 数值（mono）+「已含」角标（设计稿 §9.2；
    /// 「已含」为展示文案——服务端 included 类字段未取证，不做未含态区分）
    private func entitlementRow(_ item: CodingPlanEntitlement) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "checkmark")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(T.accentText)
            VStack(alignment: .leading, spacing: 1) {
                Text(item.name)
                    .font(T.font(11.5))
                    .foregroundColor(T.text2)
                    .lineLimit(1)
                if let detail = item.detail, !detail.isEmpty {
                    Text(detail)
                        .font(T.font(10))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: T.sp2)
            if let value = item.value, !value.isEmpty {
                Text(value)
                    .font(T.mono(11))
                    .foregroundColor(T.text2)
                    .lineLimit(1)
            }
            StatusPill(text: String(localized: "已含"), kind: .tag, compact: true)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("12-usage-entitlement-\(item.name)")
    }

    /// 重置机会卡（G-042：一键使用，桌面代执行）
    private func resetCard(_ opportunity: ResetOpportunity) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 13))
                    .foregroundColor(T.orange)
                Text(String(localized: "有可使用的额度重置机会"))
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
            // 三步桌面代执行——仅接线，本验收期不代触发）。
            // .contain 容器：裸 identifier 的 HStack 会把整行合并成单 a11y 元素、
            // 两档按钮 identifier 不出树（§5.14② 同坑——test13 首跑实证）
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
            .accessibilityElement(children: .contain)
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
                Text(claiming ? String(localized: "使用中…") : title)
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
        // 参数对齐 AppSession.fetchCodingPlanUsage 同读【实证·§9.10】：preferredProviderId
        // 必须用注册表完整 id（「zai」短 id 匹配不到 provider → 桌面落 no_bigmodel_api_key，
        // 旧调用恒败致重置机会卡整体不显示）+ accountAccess web 形状（L-2 同源常量）。
        // 5h/周两组任一有卡即呈现机会卡（expireAt 取在场组各自首个的最早值——
        // 此前只看 fiveHour 组，仅有周卡时机会卡漏显示）。
        var builder = JSONObjectBuilder()
        builder.set("preferredProviderId", AppSession.codingPlanProviderID)
        builder.set("accountAccess", AppSession.codingPlanAccountAccess)
        guard let result = try? await session.connection.call(
            "usage-stats", "getCodingPlanResetStatus", .json(.object(builder.fields))),
            let dict = result.jsonValue?.objectValue else {
            resetOpportunity = nil
            return
        }
        let expireAt = ["availableFiveHourResets", "availableWeekResets"]
            .compactMap { dict[$0]?.arrayValue?.first?.objectValue?["expireAt"]?.doubleValue }
            .filter { $0 > 0 }
            .min()
        resetOpportunity = expireAt.map { ResetOpportunity(expireAt: Date(timeIntervalSince1970: $0 / 1000)) }
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

                // HIDDEN(对齐修复): 设备页「云端沙盒」行隐藏（无移动端执行通道，规划性
                // 占位行——用户裁决隐藏）· 恢复条件：云端执行通道接入
                // HStack(spacing: T.sp3) {
                //     Image(systemName: "cloud")
                //         .font(.system(size: 15))
                //         .foregroundColor(T.text3)
                //         .frame(width: 30)
                //     VStack(alignment: .leading, spacing: 1) {
                //         Text(String(localized: "云端沙盒"))
                //             .font(T.font(14, .medium))
                //             .foregroundColor(T.text)
                //         Text(String(localized: "执行端规划中 · 暂无移动端接入通道"))
                //             .font(T.font(11))
                //             .foregroundColor(T.text3)
                //     }
                //     Spacer()
                //     Text(String(localized: "未接入"))
                //         .font(T.font(10.5))
                //         .foregroundColor(T.text3)
                // }
                // .padding(T.sp3)
                // .background(T.bgCard)
                // .clipShape(RoundedRectangle(cornerRadius: T.rM))
                // .accessibilityIdentifier("12-devices-cloud")

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

// MARK: - G-011 桌面能力清单页（memory / skills / MCP / plugins；余为只读）
//
// 连接态调桌面只读方法渲染真实清单（channel=zcode-agent，桌面 zcodeAgent.ts 接口族）：
// memory→listProjectMemories、skills→getSkillReferenceCatalog、MCP→listMcpServerStatuses
// （{workspacePath} 界定范围）、plugins→listPlugins。回执宽容解析（回执形态未逐项取证，
// 服务端不识别/缺键时按占位降级，不臆造数据）；失败/空 → RemoteCapabilityPlaceholderPage
// 诚实占位。写面：plugins 接卸载 + 市场安装（P3-11/P3-11B：zcode-agent.uninstallPlugin /
// installPlugin 桌面代执行，gate 已放行，UI 均 confirmationDialog 确认 + 行内结果通知；
// 市场目录 = zcode-agent.getPluginsOverview 的 availablePlugins/installedPlugins——web
// 端插件页同源双读合并），updatePlugin 等其余插件写面维持 ReadOnlyGate 拦截，
// memory/skills/MCP 启停写面仍零写入口。

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
        // P2-4 启动面（仅 savedWorkflows 工作流行携带）：startSavedWorkflow 命令 id
        // （条目 id 键缺席时回落 name——条目键组 name|workflowName|id 宽容形态未冻结）
        // + 行原始条目（启动 sheet 的参数 schema 解析源）
        var workflowID: String?
        var workflowEntry: JSONValue?
        // P3-11 卸载面（仅 plugins 行携带）：uninstallPlugin 的 marketplace（web 实证
        // 必填；listPlugins 条目缺该键时 performUninstall 经 getPluginsOverview 兜底）
        var marketplace: String?
    }

    /// P3-11B 市场目录行（仅 plugins 能力）：zcode-agent.getPluginsOverview 的
    /// availablePlugins[] 条目（web LSt 合并模型宽容投影——id/name/marketplace/
    /// listing.description；installed 由 installedPlugins[].id + listPlugins 清单判定，
    /// 同 web「installedPlugins 命中或清单已在场」口径）
    struct MarketPlugin: Identifiable {
        let id: String
        let name: String
        let marketplace: String?
        let summary: String?
        var installed: Bool
    }

    enum LoadPhase: Equatable {
        case loading, loaded, failed
        /// U-7（审查报告 §六 / 设计稿 H11）：未连接与「已连接但读取失败」两相区分——
        /// 未连接是引导连接，不是读面失败；此前未连接也渲染「移动端读面尚未接入」
        /// 占位，用户（和审核员）会误以为功能是死的
        case notConnected
    }

    let capability: Capability
    let title: String
    let icon: String

    @Environment(AppSession.self) private var session
    @Environment(AppRouter.self) private var router
    @State private var rows: [Row] = []
    @State private var phase: LoadPhase = .loading
    @State private var reloadToken = 0
    // P3-11 插件卸载：待确认行 / 卸载中行 / 结果提示（claimNotice 同款行内通知）
    @State private var pendingUninstall: Row?
    @State private var uninstallingID: String?
    @State private var notice: String?
    @State private var noticeIsError = false
    // P3-11B 插件市场（getPluginsOverview 读面）：市场目录行 + 读取态；overview 失败
    // 时页面保留 listPlugins 已装清单（web 同构 list-only 降级），市场区呈失败态
    @State private var marketPlugins: [MarketPlugin] = []
    @State private var marketPlaceCount = 0
    @State private var marketLoading = false
    @State private var marketError: String?
    // P3-11B 安装：待确认行 / 安装中行（结果走 notice 行内通知）
    @State private var pendingInstall: MarketPlugin?
    @State private var installingID: String?
    // P2-4 工作流启动：启动 sheet 呈现行 / 已启动 workflowId（badge「运行中」，重拉前）
    @State private var startTarget: Row?
    @State private var startedWorkflowIDs: Set<String> = []

    var body: some View {
        Group {
            switch phase {
            case .loading:
                CenterLoadingView(text: "正在读取桌面端数据…")
            case .loaded where !rows.isEmpty || !marketPlugins.isEmpty:
                ScrollView {
                    VStack(spacing: T.sp2) {
                        if let notice {
                            Text(notice)
                                .font(T.font(11.5, .semibold))
                                .foregroundColor(noticeIsError ? T.red : T.accentText)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .accessibilityIdentifier("12-capability-notice")
                        }
                        ForEach(rows) { row in
                            capabilityRow(row)
                        }
                        if capability == .plugins {
                            // P3-11B：市场目录 + 安装闭环（getPluginsOverview 读面 +
                            // installPlugin 写面；原「市场安装将在后续版本提供」占位移除）
                            marketSection
                        }
                    }
                    .padding(T.sp4)
                }
                .background(T.bg)
                .refreshable { await load() }
            case .notConnected:
                // U-7 两相区分：未连接 → 引导连接（EmptyStateView + 连接 CTA，
                // UsageStatsView 未连接空态同构）；「已连接但读取失败/空」仍走下方占位
                EmptyStateView(
                    icon: icon,
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "连接后可查看桌面端的\(title)"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "12-capability-act-connect")
            case .loaded, .failed:
                // 空清单/读取失败（已连接态）：诚实占位（不给假数据）
                RemoteCapabilityPlaceholderPage(title: title, icon: icon)
            }
        }
        .background(T.bg)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: reloadToken) { await load() }
        // P3-11 卸载确认（硬要求）：其工具面从桌面端 Agent 移除，可重装恢复
        .confirmationDialog(
            String(localized: "卸载插件 \(pendingUninstall?.title ?? "")？"),
            isPresented: Binding(
                get: { pendingUninstall != nil },
                set: { if !$0 { pendingUninstall = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "卸载"), role: .destructive) {
                if let row = pendingUninstall {
                    performUninstall(row)
                }
                pendingUninstall = nil
            }
            .accessibilityIdentifier("12-capability-confirm-uninstall")
            Button("取消", role: .cancel) { pendingUninstall = nil }
        } message: {
            Text(String(localized: "其提供的工具将从桌面端 Agent 移除；可重新安装恢复。"))
        }
        // HIDDEN(对齐修复): startSavedWorkflow 不在 web 枚举、payload 零取证 · 恢复条件：桌面真机探针 accepted
        // （P2-4 启动工作流 sheet 挂载整体隐藏，设计稿 H2；StartWorkflowSheet/performStart/interpretStartAck 保留编译）
        /* HIDDEN(对齐修复) 同上
        // P2-4 启动工作流 sheet（sheet 本身即确认层；下发经 store 统一信封出口）
        .sheet(item: $startTarget) { row in
            StartWorkflowSheet(
                workflowName: row.title,
                workflowDescription: row.workflowEntry?["description"]?.stringValue
                    ?? row.workflowEntry?["summary"]?.stringValue,
                entry: row.workflowEntry,
                onSend: { args in await performStart(row, args: args) })
        }
        */
    }

    /// 行卡（长按 contextMenu 仅 plugins 能力接卸载；卸载中行内 Spinner）
    @ViewBuilder
    private func capabilityRow(_ row: Row) -> some View {
        let card = HStack(spacing: T.sp3) {
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
            /* HIDDEN(对齐修复): startSavedWorkflow 不在 web 命令枚举、payload {workflowId,args} 零取证
               （审查报告 §五）· 恢复条件：桌面真机探针 accepted · 列表保留只读（设计稿 H2）
            // P2-4 启动钮（仅 savedWorkflows 工作流行渲染；最近运行 run- 行不渲染；
            // 样式照 TaskCardView「去审批」44pt 热区，accent 底替代橙底）
            if capability == .savedWorkflows, row.id.hasPrefix("wf-") {
                Button {
                    startTarget = row
                } label: {
                    Text(String(localized: "启动"))
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.onAccent)
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 44)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM - 2))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("12-capability-act-start-\(row.id)")
            }
            */
            if uninstallingID == row.id {
                ProgressView()
                    .scaleEffect(0.7)
            } else if let badge = row.badge {
                StatusPill(text: badge, kind: .tag, compact: true)
            }
            // HIDDEN(对齐修复): 「运行中」badge 回显依赖启动钮写入 startedWorkflowIDs，随启动钮
            // 一起隐藏（设计稿 H2）· 恢复条件：startSavedWorkflow 真机探针 accepted 后还原：
            // else if capability == .savedWorkflows, let workflowID = row.workflowID,
            //          startedWorkflowIDs.contains(workflowID) {
            //     StatusPill(text: String(localized: "运行中"), kind: .run, compact: true)
            // }
        }
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("12-capability-row-\(row.id)")
        if capability == .plugins {
            card.contextMenu {
                Button(role: .destructive) {
                    pendingUninstall = row
                } label: {
                    Label(String(localized: "卸载插件"), systemImage: "trash")
                }
                .accessibilityIdentifier("12-capability-act-uninstall-\(row.id)")
                // updatePlugin 等其余插件写面维持 ReadOnlyGate 拦截，不接菜单项
            }
        } else {
            card
        }
    }

    // MARK: P3-11B 市场目录（getPluginsOverview 读面 + installPlugin 安装入口）

    /// 市场区：读取态（loading/失败/空）+ 目录行；失败不拖垮已装清单（web list-only 降级同构）
    @ViewBuilder
    private var marketSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text(marketPlaceCount > 0
                 ? String(localized: "插件市场 · \(marketPlaceCount) 个市场源")
                 : String(localized: "插件市场"))
                .font(T.font(12, .semibold))
                .foregroundColor(T.text3)
                .padding(.top, T.sp2)
                .accessibilityIdentifier("12-capability-market-header")
            if marketLoading {
                HStack(spacing: T.sp2) {
                    ProgressView().scaleEffect(0.7)
                    Text(String(localized: "正在读取市场目录…"))
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("12-capability-market-loading")
            } else if let marketError {
                VStack(alignment: .leading, spacing: T.sp1) {
                    Text(String(localized: "市场目录读取失败 · \(marketError)"))
                        .font(T.font(11.5))
                        .foregroundColor(T.red)
                        .lineLimit(2)
                    // 读面失败态给重试入口（不臆造目录）
                    Button(String(localized: "重试")) {
                        Task { await reloadMarket() }
                    }
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.accentText)
                    .accessibilityIdentifier("12-capability-market-retry")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else if marketPlugins.isEmpty {
                Text(String(localized: "市场目录为空 · 暂无可安装插件"))
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
                    .accessibilityIdentifier("12-capability-market-empty")
            } else {
                ForEach(marketPlugins) { plugin in
                    marketRow(plugin)
                }
            }
        }
        // P3-11B 安装确认（挂在市场区，避免与根级卸载弹层同链冲突）：
        // 往桌面宿主安装插件工具面（scope 恒 user，与 web 同参）
        .confirmationDialog(
            String(localized: "安装插件 \(pendingInstall?.name ?? "")？"),
            isPresented: Binding(
                get: { pendingInstall != nil },
                set: { if !$0 { pendingInstall = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "安装")) {
                if let plugin = pendingInstall {
                    performInstall(plugin)
                }
                pendingInstall = nil
            }
            .accessibilityIdentifier("12-capability-confirm-install")
            Button("取消", role: .cancel) { pendingInstall = nil }
        } message: {
            Text(String(localized: "将从「\(pendingInstall?.marketplace ?? "插件市场")」市场安装到桌面端（用户级），安装后其工具对桌面端 Agent 生效。"))
        }
    }

    /// 市场行：已装 → 「已安装」胶囊；未装 → 长按菜单「安装」（与卸载同交互面：
    /// 上下文菜单而非常驻按钮，避免清单页出现高误触写入口；已装/安装中行无菜单）
    @ViewBuilder
    private func marketRow(_ plugin: MarketPlugin) -> some View {
        let card = HStack(spacing: T.sp3) {
            Image(systemName: "puzzlepiece.extension")
                .font(.system(size: 14))
                .foregroundColor(T.accentText)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(plugin.name)
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Text(plugin.marketplace.map { "\($0) · \(plugin.summary ?? "")" }
                    ?? plugin.summary ?? String(localized: "市场插件"))
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            if installingID == plugin.id {
                ProgressView()
                    .scaleEffect(0.7)
            } else if plugin.installed {
                StatusPill(text: String(localized: "已安装"), kind: .done, compact: true)
            } else {
                StatusPill(text: String(localized: "可安装"), kind: .tag, compact: true)
            }
        }
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("12-capability-market-row-\(plugin.id)")
        if plugin.installed || installingID != nil {
            card
        } else {
            // 安装入口（写面）：长按 + 确认弹层（confirmationDialog 在 body 根挂载）
            card.contextMenu {
                Button {
                    pendingInstall = plugin
                } label: {
                    Label(String(localized: "安装插件"), systemImage: "arrow.down.circle")
                }
                .accessibilityIdentifier("12-capability-act-install-\(plugin.id)")
            }
        }
    }

    /// P3-11 卸载（桌面代执行；zcode-agent.uninstallPlugin 已过 gate）。
    /// 参数【实证·bundle 逆向 2026-10-06，审查报告 B-8——原 {name: 行标题} 推翻】：
    /// `{workspacePath(必填，缺失桌面报「请先打开一个工作区」), workspaceIdentity?,
    /// pluginName, marketplace, scope:'user'}`。pluginName/marketplace 从 listPlugins
    /// 条目取；条目缺 marketplace 时先读 getPluginsOverview 兜底（web 清单即
    /// listPlugins+overview 双读合并，marketplace 本就来自 overview 侧），仍缺则
    /// 如实提示不下发（宁可不卸载也不盲发必败键）。成功后重拉清单（badge 消失或行移除）。
    private func performUninstall(_ row: Row) {
        guard let connection = tryConnection(), uninstallingID == nil else { return }
        guard let workspacePath = connection.workspace?.path else {
            notice = "卸载失败 · 请先在桌面端打开一个工作区"
            noticeIsError = true
            return
        }
        uninstallingID = row.id
        notice = nil
        Task {
            defer { uninstallingID = nil }
            do {
                let marketplace: String?
                if let direct = row.marketplace, !direct.isEmpty {
                    marketplace = direct
                } else {
                    marketplace = try await lookupPluginMarketplace(connection, pluginName: row.title)
                }
                guard let marketplace, !marketplace.isEmpty else {
                    notice = "卸载失败 · 无法确定「\(row.title)」的插件市场来源（listPlugins/getPluginsOverview 均未返回 marketplace）"
                    noticeIsError = true
                    return
                }
                var builder = JSONObjectBuilder()
                builder.set("workspacePath", workspacePath)
                if let identity = connection.workspace?.workspaceIdentity, !identity.isEmpty {
                    builder.set("workspaceIdentity", identity)
                }
                builder.set("pluginName", row.title)
                builder.set("marketplace", marketplace)
                builder.set("scope", "user")
                _ = try await connection.call(
                    "zcode-agent", "uninstallPlugin", .json(.object(builder.fields)))
                notice = String(localized: "已卸载 \(row.title)")
                noticeIsError = false
                await load()
            } catch {
                notice = "卸载失败 · \(String(String(describing: error).prefix(160)))"
                noticeIsError = true
            }
        }
    }

    /// marketplace 兜底：getPluginsOverview({workspacePath, workspaceIdentity?}) 的
    /// availablePlugins[]/plugins[] 按 name|pluginName|id 匹配插件行取其 marketplace
    /// （回执形态未逐项取证，键组宽容；两数组均无匹配或无 marketplace 键 → nil 如实上报）
    private func lookupPluginMarketplace(
        _ connection: ZCodeServerConnection, pluginName: String
    ) async throws -> String? {
        let result = try await connection.call(
            "zcode-agent", "getPluginsOverview", .json(.object(workspaceScopeFields(connection))))
        let json = result.jsonValue
        let candidates = (json?["availablePlugins"]?.arrayValue ?? [])
            + (json?["plugins"]?.arrayValue ?? [])
        return candidates.compactMap { item -> String? in
            guard let d = item.objectValue,
                  d["name"]?.stringValue ?? d["pluginName"]?.stringValue
                  ?? d["id"]?.stringValue == pluginName else { return nil }
            return d["marketplace"]?.stringValue
        }.first { !$0.isEmpty }
    }

    // MARK: P3-11B 市场目录读取 + 安装（getPluginsOverview / installPlugin）

    /// 市场目录读取：zcode-agent.getPluginsOverview({workspacePath, workspaceIdentity?})。
    /// 回执【移植·bundle 逆向】web 消费键：plugins[]/marketplaces[]/availablePlugins[]/
    /// installedPlugins[{id, marketplace, componentTypes?}][]/restorableBuiltins[]/
    /// diagnostics[{severity, message, pluginId?}][]（web 端由 zcodeAgentService +
    /// pluginService 双频道并发合并，移动端单频道 zcode-agent 宽容取键——availablePlugins
    /// 缺席 = 市场目录空，如实呈空态不臆造）。
    private func loadPluginMarket(
        _ connection: ZCodeServerConnection, installedKeys: Set<String>
    ) async {
        marketLoading = true
        marketError = nil
        do {
            let result = try await connection.call(
                "zcode-agent", "getPluginsOverview",
                .json(.object(workspaceScopeFields(connection))))
            let json = result.jsonValue
            let available = json?["availablePlugins"]?.arrayValue ?? []
            let installedIDs = Set(
                (json?["installedPlugins"]?.arrayValue ?? [])
                    .compactMap { $0.objectValue?["id"]?.stringValue })
            marketPlaceCount = json?["marketplaces"]?.arrayValue?.count ?? 0
            marketPlugins = available.compactMap { item in
                guard let d = item.objectValue,
                      let id = d["id"]?.stringValue ?? d["name"]?.stringValue else { return nil }
                let listing = d["listing"]?.objectValue
                let name = d["name"]?.stringValue ?? id
                return MarketPlugin(
                    id: id,
                    name: name,
                    marketplace: d["marketplace"]?.stringValue,
                    summary: d["description"]?.stringValue
                        ?? listing?["description"]?.stringValue,
                    // web LSt 合并口径：installedPlugins 命中或已装清单在场（installed 键宽容）
                    installed: installedIDs.contains(id)
                        || installedKeys.contains(id)
                        || installedKeys.contains(name)
                        || (d["installed"]?.boolValue ?? false))
            }
        } catch {
            // web 同构 list-only 降级：overview 失败保留已装清单，市场区如实呈失败态
            marketPlugins = []
            marketPlaceCount = 0
            marketError = String(String(describing: error).prefix(160))
        }
        marketLoading = false
    }

    /// 市场区重试（读面失败态入口）：以当前已装清单行 id 为安装态判定基线
    private func reloadMarket() async {
        guard let connection = tryConnection() else { return }
        await loadPluginMarket(connection, installedKeys: Set(rows.map(\.id)))
    }

    /// P3-11B 安装（桌面代执行；zcode-agent.installPlugin 已过 gate）。
    /// 参数【移植·bundle 逆向，与卸载同基座】：`{workspacePath(必填——web 无工作区时报
    /// 「请先打开一个工作区后再安装插件」), workspaceIdentity?, pluginName, marketplace,
    /// scope:'user'}`（operationId 进度流面移动端不接，不携）。
    /// 回执解读同 web 两消费点：diagnostics 含非 warning 项 → 失败（「pluginId: message」，
    /// Hht 口径）；installedPlugins[] 在场但缺该 id → 失败（web "remote install did not
    /// return X"）；键整缺 → 宽容按成功并重拉对账（该形态未取证，如实以清单为准）。
    private func performInstall(_ plugin: MarketPlugin) {
        guard let connection = tryConnection(), installingID == nil else { return }
        guard let marketplace = plugin.marketplace, !marketplace.isEmpty else {
            notice = "安装失败 · 无法确定「\(plugin.name)」的市场来源"
            noticeIsError = true
            return
        }
        guard let workspacePath = connection.workspace?.path else {
            notice = "安装失败 · 请先在桌面端打开一个工作区"
            noticeIsError = true
            return
        }
        installingID = plugin.id
        notice = nil
        Task {
            defer { installingID = nil }
            do {
                var builder = JSONObjectBuilder()
                builder.set("workspacePath", workspacePath)
                if let identity = connection.workspace?.workspaceIdentity, !identity.isEmpty {
                    builder.set("workspaceIdentity", identity)
                }
                builder.set("pluginName", plugin.name)
                builder.set("marketplace", marketplace)
                builder.set("scope", "user")
                let result = try await connection.call(
                    "zcode-agent", "installPlugin", .json(.object(builder.fields)))
                // diagnostics：非 warning（含缺 severity 键）即失败——web Hht/mqe 口径
                if let diagnostics = result.jsonValue?["diagnostics"]?.arrayValue,
                   let failure = diagnostics.first(where: {
                       ($0.objectValue?["severity"]?.stringValue ?? "error") != "warning"
                   }) {
                    let d = failure.objectValue
                    let who = d?["pluginId"]?.stringValue ?? plugin.id
                    notice = "安装失败 · \(who): \(d?["message"]?.stringValue ?? String(localized: "未知错误"))"
                    noticeIsError = true
                    return
                }
                // installedPlugins 对账（web 同款：回执应含该插件 id）
                if let installed = result.jsonValue?["installedPlugins"]?.arrayValue {
                    let ids = Set(installed.compactMap { $0.objectValue?["id"]?.stringValue })
                    if !ids.contains(plugin.id), !ids.contains(plugin.name) {
                        notice = "安装失败 · 桌面端回执未包含 \(plugin.name)"
                        noticeIsError = true
                        return
                    }
                }
                if let index = marketPlugins.firstIndex(where: { $0.id == plugin.id }) {
                    marketPlugins[index].installed = true
                }
                notice = String(localized: "已安装 \(plugin.name)")
                noticeIsError = false
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                await load()
            } catch {
                notice = "安装失败 · \(String(String(describing: error).prefix(160)))"
                noticeIsError = true
            }
        }
    }

    private func tryConnection() -> ZCodeServerConnection? {
        let connection = session.connection
        guard connection.isActive else { return nil }
        return connection
    }

    /// workspace 维度入参（web 实证 2026-10-06：workspacePath 恒带，workspaceIdentity
    /// 在场才带——listPlugins/getSkillReferenceCatalog/getPluginsOverview 同一口径；
    /// configScope web 亦仅在场才携，移动端无对应值不传）
    private func workspaceScopeFields(_ connection: ZCodeServerConnection) -> [String: JSONValue] {
        var builder = JSONObjectBuilder()
        if let workspacePath = connection.workspace?.path {
            builder.set("workspacePath", workspacePath)
        }
        if let identity = connection.workspace?.workspaceIdentity, !identity.isEmpty {
            builder.set("workspaceIdentity", identity)
        }
        return builder.fields
    }

    // MARK: P2-4 工作流启动（startSavedWorkflow 桌面代执行）

    /// 下发回调（StartWorkflowSheet onSend）：经装配的统一信封出口 store.startSavedWorkflow
    /// （工作流库页无会话上下文 → conversationID 传 nil，信封 sessionId=null 与 createSession
    /// 同路；args 经协议调用无默认值，恒显式传）。返回错误文案（nil = 成功）。
    /// 成功：震动 + 行卡 badge「运行中」；回执携 sessionId 时跳转会话（fork openChat 先例）。
    private func performStart(_ row: Row, args: [String: JSONValue]) async -> String? {
        guard let store = session.remoteConversationStore else {
            return String(localized: "命令未送达（连接中断或不在连接态）")
        }
        let ack = await store.startSavedWorkflow(
            nil, workflowId: row.workflowID ?? row.title, args: args)
        let (error, sessionId) = Self.interpretStartAck(ack)
        guard error == nil else { return error }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        startedWorkflowIDs.insert(row.workflowID ?? row.title)
        notice = String(localized: "已启动 · 运行面板在对应会话内")
        noticeIsError = false
        startTarget = nil
        if let sessionId {
            router.openChat(conversationID: sessionId)
        }
        return nil
    }

    /// startSavedWorkflow 回执解读（ChatViewModel.controlFeedback 同口径：nil ack = 未送达；
    /// rejected 带 reasonCode + zod issue 首条 message）。sessionId 三形态宽容提取
    /// （result.sessionId / result.session.sessionId / 顶层 sessionId——createSession
    /// 探针同链路先例 RemoteConversationStore.swift:2577-2579），缺省 = 留页看 badge。
    private static func interpretStartAck(_ ack: JSONValue?) -> (error: String?, sessionId: String?) {
        guard let ack else { return (String(localized: "命令未送达（连接中断或不在连接态）"), nil) }
        let status = ack["status"]?.stringValue
            ?? ack.objectValue?["ack"]?.objectValue?["status"]?.stringValue
        guard status == nil || ["accepted", "noop", "applied", "ok"].contains(status ?? "") else {
            var detail = ack["reasonCode"]?.stringValue ?? status ?? "?"
            // zod 校验类拒绝：message 为 issue 数组 JSON，取首条 message 字段
            if let message = ack["message"]?.stringValue,
               let range = message.range(of: "\"message\": \"") {
                let tail = message[range.upperBound...]
                if let end = tail.firstIndex(of: "\"") {
                    detail += "：" + tail[..<end]
                }
            }
            return (String(localized: "桌面端拒绝（\(detail)）"), nil)
        }
        let sessionId = ack["result"]?.objectValue?["sessionId"]?.stringValue
            ?? ack["result"]?.objectValue?["session"]?.objectValue?["sessionId"]?.stringValue
            ?? ack["sessionId"]?.stringValue
        return (nil, sessionId)
    }

    private func load() async {
        phase = .loading
        rows = []
        let connection = session.connection
        guard connection.isActive else {
            phase = .notConnected
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
                // getSkillReferenceCatalog → 宽容取 skills[]/catalog.skills[]/entries[]；
                // 入参带 workspace 维度【实证·bundle 逆向，审查报告 C-9——web 恒携
                // {workspacePath, workspaceIdentity?}（会话内另携 remoteSessionId/
                // sessionId，能力页无会话上下文不带）】
                let result = try await connection.call(
                    "zcode-agent", "getSkillReferenceCatalog",
                    .json(.object(workspaceScopeFields(connection))))
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
                // listPlugins → plugins[]/items[]；入参 {workspacePath, workspaceIdentity?,
                // configScope?}【实证·bundle 逆向，审查报告 C-8——web 恒带 workspace 维度
                // （无参调用漏 workspace 级插件）；configScope 在场才携，无对应值不传】。
                // marketplace 键在条目在场时捕获（uninstallPlugin 必填，缺席走 overview 兜底）
                let result = try await connection.call(
                    "zcode-agent", "listPlugins", .json(.object(workspaceScopeFields(connection))))
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
                        badge: enabled == nil ? nil : (enabled! ? String(localized: "已启用") : String(localized: "已停用")),
                        marketplace: d["marketplace"]?.stringValue)
                }
                // P3-11B 市场目录：getPluginsOverview 与 listPlugins 同基座双读（web 插件页
                // 同源），overview 失败不影响已装清单（市场区呈失败态，list-only 降级）
                let installedKeys = Set(
                    rows.map(\.id)
                        + items.compactMap { $0.objectValue?["id"]?.stringValue })
                await loadPluginMarket(connection, installedKeys: installedKeys)
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
                        badge: nil,
                        // P2-4：命令 id 取条目 id（键组宽容形态未冻结，缺席回落 name）；
                        // 原始条目带进行卡供启动 sheet 解析参数 schema
                        workflowID: d["id"]?.stringValue ?? d["workflowId"]?.stringValue ?? name,
                        workflowEntry: item)
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

// MARK: - P2-4 启动工作流 sheet（动态参数表单 + 启动三态）
//
// schema 来源 = listSavedWorkflows 行条目本身（不调 getSavedWorkflow——立项报告 :588
// 清单在列但协议文档无其形状记录）。参数定义候选键 inputSchema/parametersSchema/
// argsSchema/parameters/params/schema/args 全部【未取证】（调研 risks：全 Sources grep
// 零命中），按 JSON-Schema 风格 properties/required 宽容解析（另兼容平铺 {key: def}
// 与数组 [{name|key|id,…}] 两形态）；候选键在场但解析不出字段 → 「参数定义不可用」
// 诚实占位、不出直启（宁可不启动也不丢参数盲发）；无候选键 → 「无需参数」直启。
// 三态：表单校验缺项红字不发起 / 启动中主钮禁用 / 失败 sheet 内错误行可重发。

/// 参数表单字段（schema 字段定义宽容解析结果；options 仅 choice 类使用）
struct WorkflowParamField: Identifiable {
    let key: String
    let label: String
    let kind: Kind
    let required: Bool
    let defaultValue: String?
    let options: [String]
    var id: String { key }

    enum Kind { case text, number, toggle, choice }
}

struct StartWorkflowSheet: View {
    let workflowName: String
    let workflowDescription: String?
    /// listSavedWorkflows 行原始条目（参数 schema 解析源；nil = 无条目直启）
    let entry: JSONValue?
    /// 下发回调（宿主经 store.startSavedWorkflow 统一信封出口；显式传 args）。
    /// 返回错误文案（nil = 成功，宿主 dismiss + 跳转/行卡回显）
    var onSend: ([String: JSONValue]) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var textValues: [String: String] = [:]
    @State private var boolValues: [String: Bool] = [:]
    @State private var choiceValues: [String: String] = [:]
    @State private var missingKeys: Set<String> = []
    @State private var sending = false
    @State private var errorText: String?

    private var parsed: (fields: [WorkflowParamField], schemaPresent: Bool) {
        Self.parseFields(from: entry)
    }
    /// 候选键在场但解析不出可用字段：诚实占位（不按无参数盲发）
    private var schemaUnreadable: Bool { parsed.schemaPresent && parsed.fields.isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text(String(localized: "启动「\(workflowName)」"))
                    .font(T.font(17, .bold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer()
                Button(String(localized: "取消")) { dismiss() }
                    .font(T.font(14, .medium))
                    .foregroundColor(T.text2)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityIdentifier("12-wfstart-act-cancel")
            }
            .padding(.horizontal, T.sp4)
            ScrollView {
                VStack(alignment: .leading, spacing: T.sp3) {
                    if let workflowDescription, !workflowDescription.isEmpty {
                        Text(workflowDescription)
                            .font(T.font(12))
                            .foregroundColor(T.text2)
                            .lineSpacing(3)
                    }
                    if schemaUnreadable {
                        EmptyStateView(
                            icon: "exclamationmark.triangle",
                            title: String(localized: "参数定义不可用"),
                            detail: String(localized: "桌面端返回了参数定义，但移动端暂无法解析其格式；可在桌面端启动，或下拉刷新本页后重试。"))
                    } else if parsed.fields.isEmpty {
                        Text(String(localized: "该工作流无需参数"))
                            .font(T.font(12))
                            .foregroundColor(T.text3)
                    } else {
                        Text(String(localized: "参数"))
                            .font(T.font(12.5, .semibold))
                            .foregroundColor(T.text3)
                        ForEach(parsed.fields) { field in
                            fieldRow(field)
                        }
                    }
                    startButton
                    if let errorText {
                        Text(errorText)
                            .font(T.font(11.5, .semibold))
                            .foregroundColor(T.red)
                            .accessibilityIdentifier("12-wfstart-error")
                    }
                }
                .padding(T.sp4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .onAppear {
            // 每次打开重置为 schema 默认值（state 键级整体替换口径；残留编辑不跨次携带）
            textValues = Dictionary(
                uniqueKeysWithValues: parsed.fields.filter { $0.kind != .toggle && $0.kind != .choice }
                    .map { ($0.key, $0.defaultValue ?? "") })
            boolValues = Dictionary(
                uniqueKeysWithValues: parsed.fields.filter { $0.kind == .toggle }
                    .map { ($0.key, $0.defaultValue == "true") })
            choiceValues = Dictionary(
                uniqueKeysWithValues: parsed.fields.filter { $0.kind == .choice }
                    .map { ($0.key, $0.defaultValue ?? "") })
            missingKeys = []
            errorText = nil
        }
    }

    // MARK: 字段行（string→文本框；number→数字键盘；boolean→Toggle；enum→Menu）

    @ViewBuilder
    private func fieldRow(_ field: WorkflowParamField) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Text(field.label)
                    .font(T.font(12.5))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if field.required {
                    Text("*")
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.red)
                }
            }
            switch field.kind {
            case .text:
                TextField(String(localized: "请输入"), text: binding(for: field))
                    .font(T.font(13))
                    .foregroundColor(T.text)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 8)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    .accessibilityIdentifier("12-wfstart-field-\(field.key)")
            case .number:
                TextField(String(localized: "请输入数字"), text: binding(for: field))
                    .font(T.font(13))
                    .foregroundColor(T.text)
                    .keyboardType(.decimalPad)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 8)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    .accessibilityIdentifier("12-wfstart-field-\(field.key)")
            case .toggle:
                Toggle("", isOn: Binding(
                    get: { boolValues[field.key] ?? false },
                    set: { boolValues[field.key] = $0 }))
                    .labelsHidden()
                    .tint(T.accent)
                    .accessibilityIdentifier("12-wfstart-field-\(field.key)")
            case .choice:
                // identifier 挂 Menu 本体而非 label（门禁实证口径，§0.3）
                Menu {
                    ForEach(field.options, id: \.self) { option in
                        Button(option) { choiceValues[field.key] = option }
                    }
                } label: {
                    HStack {
                        let current = choiceValues[field.key] ?? ""
                        Text(current.isEmpty ? String(localized: "请选择") : current)
                            .font(T.font(13))
                            .foregroundColor(current.isEmpty ? T.text3 : T.text)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundColor(T.text3)
                    }
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 8)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .accessibilityIdentifier("12-wfstart-field-\(field.key)")
            }
            if missingKeys.contains(field.key) {
                Text(String(localized: "此项为必填"))
                    .font(T.font(11))
                    .foregroundColor(T.red)
            }
        }
    }

    private func binding(for field: WorkflowParamField) -> Binding<String> {
        Binding(
            get: { textValues[field.key] ?? "" },
            set: {
                textValues[field.key] = $0
                missingKeys.remove(field.key)
            })
    }

    private var startButton: some View {
        Button {
            start()
        } label: {
            HStack(spacing: T.sp2) {
                if sending {
                    SpinnerView(color: T.onAccent, size: 13)
                }
                Text(sending ? String(localized: "启动中…")
                    : (parsed.fields.isEmpty && !schemaUnreadable
                        ? String(localized: "直接启动") : String(localized: "启动")))
                    .font(T.font(13.5, .semibold))
            }
            .foregroundColor(T.onAccent)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(sending ? T.accent.opacity(0.5) : T.accent)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .disabled(sending || schemaUnreadable)
        .accessibilityIdentifier("12-wfstart-act-start")
    }

    private func start() {
        guard !sending, !schemaUnreadable else { return }
        guard let args = buildArgs() else { return } // 缺项红字已标，不发起命令
        sending = true
        errorText = nil
        Task {
            let error = await onSend(args)
            sending = false
            if let error {
                errorText = error // 失败留在 sheet，主钮可重发
            } else {
                dismiss()
            }
        }
    }

    /// 表单值 → args（必填缺项/数字非法标红返回 nil；空表单返回空 dict=无参直启）
    private func buildArgs() -> [String: JSONValue]? {
        var args: [String: JSONValue] = [:]
        var missing: Set<String> = []
        for field in parsed.fields {
            switch field.kind {
            case .text:
                let value = (textValues[field.key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if value.isEmpty {
                    if field.required { missing.insert(field.key) }
                } else {
                    args[field.key] = .string(value)
                }
            case .number:
                let raw = (textValues[field.key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if raw.isEmpty {
                    if field.required { missing.insert(field.key) }
                } else if let int = Int(raw) {
                    args[field.key] = .int(int)
                } else if let double = Double(raw) {
                    args[field.key] = .double(double)
                } else {
                    missing.insert(field.key) // 非法数字视同缺项
                }
            case .toggle:
                args[field.key] = .bool(boolValues[field.key] ?? false)
            case .choice:
                let value = choiceValues[field.key] ?? ""
                if value.isEmpty {
                    if field.required { missing.insert(field.key) }
                } else {
                    args[field.key] = .string(value)
                }
            }
        }
        missingKeys = missing
        return missing.isEmpty ? args : nil
    }

    // MARK: schema 宽容解析（形状零取证，三形态兼容；字段序按 key 字典序稳定呈现）

    static func parseFields(from entry: JSONValue?) -> (fields: [WorkflowParamField], schemaPresent: Bool) {
        guard let entry else { return ([], false) }
        // 候选键全部【未取证】，命中首个非空值即用
        let candidateKeys = ["inputSchema", "parametersSchema", "argsSchema",
                             "parameters", "params", "schema", "args", "inputs", "input"]
        var schema: JSONValue?
        for key in candidateKeys {
            if let value = entry[key], !value.isNull {
                schema = value
                break
            }
        }
        guard let schema else { return ([], false) }
        var defs: [(key: String, def: JSONValue)] = []
        var requiredKeys: Set<String> = []
        if let properties = schema["properties"]?.objectValue {
            // ① JSON-Schema 风格 {properties: {key: def}, required?: [key]}
            requiredKeys = Set((schema["required"]?.arrayValue ?? []).compactMap { $0.stringValue })
            defs = properties.map { ($0.key, $0.value) }.sorted { $0.key < $1.key }
        } else if let array = schema.arrayValue {
            // ② 数组形态 [{name|key|id, type?, enum?, required?…}]
            defs = array.compactMap { item in
                guard let d = item.objectValue,
                      let key = d["name"]?.stringValue ?? d["key"]?.stringValue
                          ?? d["id"]?.stringValue else { return nil }
                return (key, item)
            }
        } else if let object = schema.objectValue {
            // ③ 平铺 {key: def}：值含 type/enum 才认作字段定义（防把普通对象误拆）
            let fieldLike = object.filter {
                $0.value.objectValue?["type"] != nil || $0.value.objectValue?["enum"] != nil
            }
            if !object.isEmpty, fieldLike.count == object.count {
                defs = object.map { ($0.key, $0.value) }.sorted { $0.key < $1.key }
            }
        }
        let fields = defs.compactMap { key, def -> WorkflowParamField? in
            guard let d = def.objectValue else { return nil }
            let options = (d["enum"]?.arrayValue ?? []).compactMap { $0.stringValue }
            let type = (d["type"]?.stringValue ?? "").lowercased()
            let kind: WorkflowParamField.Kind
            if !options.isEmpty {
                kind = .choice
            } else if type.contains("bool") {
                kind = .toggle
            } else if type.contains("number") || type.contains("int")
                        || type.contains("float") || type.contains("double") {
                kind = .number
            } else {
                kind = .text
            }
            let defaultValue: String? = {
                if let v = d["default"]?.stringValue { return v }
                if let v = d["default"]?.intValue { return String(v) }
                if let v = d["default"]?.doubleValue { return String(v) }
                if let v = d["default"]?.boolValue { return v ? "true" : "false" }
                return nil
            }()
            return WorkflowParamField(
                key: key,
                label: d["title"]?.stringValue ?? d["label"]?.stringValue
                    ?? d["description"]?.stringValue ?? key,
                kind: kind,
                required: requiredKeys.contains(key) || d["required"]?.boolValue == true,
                defaultValue: defaultValue,
                options: options)
        }
        return (fields, true)
    }
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

// MARK: - P3-11C 桌面设置同步（settingService.get 读 + settingService.update 写）
//
// 频道名二义（盘点报告 :54 记 `settingService.get/update`，gate 历史词表只有 `setting`
// 频道；真实频道名未取证——本轮桌面端不在线）：get/update 按两候选频道依次尝试，
// 首个成功者胜。读回执宽容解析（数组条目 / 包裹对象 / 整对象三形态，
// 只收 bool/string/number 可渲染标量，不臆造渲染）；写参数 web 实证【实证·bundle 逆向
// 2026-10-06】：update({设置名: 新值}) 单键补丁对象（原 {key,value} 推翻，审查报告 B-7）。
// 写面属桌面配置写（gate 已放行 setting.update/settingService.update），UI 一律经
// confirmationDialog 确认后下发；update 不自动重试（防双写），get 全候选失败后
// 1.2s 退避整轮重试一次（同分页读首败退避口径）。

struct DesktopSettingsPage: View {
    private enum Phase: Equatable {
        case loading, loaded, failed
    }

    /// 一条桌面设置项（value 只承载可渲染标量）
    struct Entry: Identifiable {
        let key: String
        let value: JSONValue
        let readonly: Bool
        var id: String { key }
    }

    /// 待确认的修改（confirmationDialog 呈现中；nil = 无）
    struct PendingChange: Identifiable {
        let key: String
        let newValue: JSONValue
        var id: String { key }
    }

    /// 频道候选序（真实频道名未取证；与 ReadOnlyGate setting/settingService 词表同源）
    private static let settingChannels = ["settingService", "setting"]
    /// 读回执可能的包裹键（宽容形态；整对象兜底见 parseEntries）
    private static let wrapperKeys = ["settings", "values", "data", "config", "items", "entries"]

    /// 设置键 → 中文名【实证·上游仓 packages/ui/src/i18n/locales/zh-CN.ts + 键全集
    /// validationAppSettings.ts:420-475 appSettingsObjectSchema，2026-10-08 取证】。
    /// 未收录键（内部态/新增项）回退显示原始键名。
    private static let keyNames: [String: String] = [
        "locale": "界面语言",
        "localePreference": "界面语言偏好",
        "terminalInheritSystemProfile": "终端继承系统配置",
        "terminalFontFamily": "终端字体",
        "integratedTerminalShell": "本机终端 Shell",
        "httpProxy": "HTTP 代理",
        "httpProxyNoProxy": "不使用代理的地址",
        "httpProxyCaCertPath": "自定义代理证书",
        "embeddedBrowserAllowInsecureCertificates": "忽略证书校验",
        "embeddedBrowserViewportPreference": "浏览器视口偏好",
        "computerUseComposerEntryHidden": "输入框显示电脑操作按钮",
        "taskAutoArchiveEnabled": "自动归档旧任务",
        "taskAutoArchiveOlderThanDays": "归档保留时长（天）",
        "closeToTrayOnWindows": "关闭时隐藏到托盘",
        "keepAwakeWhileRunning": "任务运行时保持电脑唤醒",
        "desktopZoomLevel": "界面缩放",
        "desktopWindowSize": "窗口尺寸",
        "desktopChromiumHardwareAccelerationEnabled": "界面硬件加速",
        "messageStreamShowReasoning": "显示思考过程",
        "messageStreamShowTodos": "显示待办列表",
        "toolGroupingExploreEnabled": "分组显示探索工具",
        "toolGroupingTerminalEnabled": "分组显示终端命令",
        "toolGroupingChangesEnabled": "分组显示文件更改",
        "zcodeInteractionBehavior": "交互行为（审批响应方式）",
        "askUserQuestionAutoResolutionEnabled": "提问自动继续",
        "modelIoFullRetentionEnabled": "完整保留模型输入输出",
        "nativeSearchEnhancementsEnabled": "增强文件搜索（Find/Grep）",
        "memoryEnabled": "工作区记忆",
        "proactiveSuggestionsEnabled": "主动任务推荐",
        "receivePreviewUpdates": "接收预览版更新",
        "autoDownloadAndInstallUpdates": "自动下载并安装更新",
        "dataBaseDir": "数据存储路径",
        "shortcutBindings": "键盘快捷键",
        "providerFamilyDomain": "套餐区域",
        "providerFamilyConnectionSelections": "套餐连接选择",
    ]

    /// 显示值枚举对照（常见枚举的中文渲染；未命中回退原值）
    private static func localizedValue(_ value: JSONValue) -> String {
        guard let s = value.stringValue else {
            if value == .bool(true) { return "开启" }
            if value == .bool(false) { return "关闭" }
            return String(describing: value)
        }
        switch s {
        case "zh-CN": return "中文简体"
        case "en-US": return "English"
        case "system": return "跟随系统"
        case "queue": return "排队等待确认"
        case "guide": return "逐条引导确认"
        case "zai": return "Z.ai（全球）"
        case "bigmodel": return "BigModel（中国）"
        case "auto": return "自动"
        case "shell": return "指定 Shell"
        case "cmd": return "CMD"
        case "git-bash": return "Git Bash"
        default: return s
        }
    }

    /// 行标题：中文映射优先，未收录回退原键名（内部态键如 recentProjects 原样）
    private static func displayName(for key: String) -> String {
        keyNames[key] ?? key
    }

    @Environment(AppSession.self) private var session
    @State private var entries: [Entry] = []
    @State private var phase: Phase = .loading
    @State private var errorText: String?
    @State private var editingEntry: Entry?
    @State private var editText = ""
    @State private var pendingChange: PendingChange?
    @State private var busyKey: String?
    @State private var notice: String?
    @State private var noticeIsError = false
    /// get 首个命中的频道（update 同频道优先，避免读写分家；未命中前走候选序）
    @State private var resolvedChannel: String?

    private var isConnected: Bool {
        if case .connected = session.mode { return true }
        return false
    }

    var body: some View {
        Group {
            if !isConnected {
                EmptyStateView(
                    icon: "desktopcomputer",
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "桌面设置读写自桌面端（settingService）；连接后可在此查看并修改"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "12-desktop-act-connect")
            } else {
                switch phase {
                case .loading:
                    CenterLoadingView(text: "正在读取桌面设置…")
                        .accessibilityIdentifier("12-desktop-loading")
                case .loaded where !entries.isEmpty:
                    list
                case .loaded, .failed:
                    // 空/失败：诚实占位（不给假数据；错误细节随占位展示）
                    EmptyStateView(
                        icon: "desktopcomputer",
                        title: String(localized: "桌面端未返回设置数据"),
                        detail: errorText ?? String(localized: "桌面端未提供 settingService 读面或回执形态未被识别"),
                        cta: String(localized: "重新加载"),
                        ctaAction: { Task { await load() } },
                        ctaIdentifier: "12-desktop-act-reload")
                }
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "桌面设置"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("12-desktop-settings-page")
        .task { await load() }
        .refreshable { await load() }
        // 字符串/数字项编辑（带 TextField 先例 ApprovalSheetView 追问弹层）
        .alert(
            String(localized: "修改 \(editingEntry?.key ?? "")"),
            isPresented: Binding(
                get: { editingEntry != nil },
                set: { if !$0 { editingEntry = nil } }),
            presenting: editingEntry) { entry in
            TextField("新值", text: $editText)
                .keyboardType(entry.value.stringValue == nil ? .numbersAndPunctuation : .default)
                .accessibilityIdentifier("12-desktop-field-value")
            Button("取消", role: .cancel) { editingEntry = nil }
            Button(String(localized: "修改")) { commitEdit(entry) }
                .accessibilityIdentifier("12-desktop-act-save")
        } message: { _ in
            Text(String(localized: "将把「\(editingEntry?.key ?? "")」改为输入的新值（当前：\(displayValue(editingEntry?.value))）。"))
        }
        // 修改确认（硬要求）：桌面配置写即时生效，影响所有正在运行的会话
        .confirmationDialog(
            String(localized: "修改桌面设置 \(pendingChange?.key ?? "")？"),
            isPresented: Binding(
                get: { pendingChange != nil },
                set: { if !$0 { pendingChange = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "修改"), role: .destructive) {
                if let change = pendingChange {
                    performUpdate(change)
                }
                pendingChange = nil
            }
            .accessibilityIdentifier("12-desktop-confirm-update")
            Button("取消", role: .cancel) { pendingChange = nil }
        } message: {
            Text(String(localized: "将立即作用于桌面端，影响所有正在运行的会话。"))
        }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                if let notice {
                    Text(notice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(noticeIsError ? T.red : T.accentText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("12-desktop-notice")
                }
                VStack(spacing: 0) {
                    ForEach(entries) { entry in
                        settingRow(entry)
                        if entry.id != entries.last?.id {
                            Divider().overlay(T.border)
                        }
                    }
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                Text(String(localized: "设置项由桌面端 settingService 提供；修改即时生效，未知形态的值不在此展示。"))
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .padding(.top, T.sp1)
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: 行渲染（boolean→toggle；string/number→可点编辑；readonly→无箭头）

    @ViewBuilder
    private func settingRow(_ entry: Entry) -> some View {
        HStack(spacing: T.sp2) {
            VStack(alignment: .leading, spacing: 1) {
                Text(Self.displayName(for: entry.key))
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                    .lineLimit(2)
                // 原始键名副行（中文名映射在场时保留原键可辨识——编辑确认/排障对桌面侧对齐）
                if Self.keyNames[entry.key] != nil {
                    Text(entry.key)
                        .font(T.mono(10))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
            }
            Spacer()
            if busyKey == entry.key {
                ProgressView()
                    .scaleEffect(0.7)
            } else {
                trailing(entry)
            }
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 48)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("12-desktop-setting-row-\(entry.key)")
    }

    @ViewBuilder
    private func trailing(_ entry: Entry) -> some View {
        switch entry.value {
        case .bool:
            // boolean→toggleRow 样式（SettingsView 同款）；开关不即时下发，
            // 触发确认弹层后再改（取消自动回弹：值仍读 entries）
            Toggle("", isOn: Binding(
                get: { entry.value.boolValue == true },
                set: { newValue in
                    guard newValue != entry.value.boolValue else { return }
                    pendingChange = PendingChange(key: entry.key, newValue: .bool(newValue))
                }))
                .labelsHidden()
                .tint(T.accent)
                .accessibilityIdentifier("12-desktop-toggle-\(entry.key)")
        default:
            if entry.readonly {
                Text(displayValue(entry.value))
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            } else {
                Button {
                    editText = editSeed(entry.value)
                    editingEntry = entry
                } label: {
                    HStack(spacing: 4) {
                        Text(displayValue(entry.value))
                            .font(T.mono(11))
                            .foregroundColor(T.text2)
                            .lineLimit(1)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(T.text3)
                    }
                }
                .accessibilityIdentifier("12-desktop-edit-\(entry.key)")
            }
        }
    }

    // MARK: 读面（候选频道依次尝试；全失败 1.2s 退避整轮重试一次）

    private func load() async {
        phase = .loading
        errorText = nil
        do {
            let result = try await settingCall("get", [:])
            entries = Self.parseEntries(result.jsonValue)
            phase = .loaded
        } catch {
            entries = []
            errorText = String(String(describing: error).prefix(200))
            phase = .failed
        }
    }

    /// settingService/setting 双候选依次尝试（真实频道名未取证）。
    /// 已解析频道优先（get 命中后 update 走同频道）；全候选失败 → 1.2s 退避整轮
    /// 重试一次（中继瞬断同口径），allowRetry=false 供写命令关闭防双写。
    private func settingCall(_ command: String, _ fields: [String: JSONValue],
                             allowRetry: Bool = true) async throws -> RPCValue {
        let connection = session.connection
        func runOnce() async throws -> RPCValue {
            var lastError: Error?
            var candidates = Self.settingChannels
            if let resolved = resolvedChannel {
                candidates.removeAll { $0 == resolved }
                candidates.insert(resolved, at: 0)
            }
            for channel in candidates {
                do {
                    let value = try await connection.call(channel, command, .json(.object(fields)))
                    resolvedChannel = channel
                    return value
                } catch {
                    lastError = error
                }
            }
            throw lastError ?? RPCError(message: "桌面设置频道不可用", name: "NoSettingChannel")
        }
        do {
            return try await runOnce()
        } catch {
            guard allowRetry else { throw error }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            return try await runOnce()
        }
    }

    // MARK: 写面（web 实证 2026-10-06：update({设置名: 新值}) 单键补丁对象——
    // terminalFontFamily/taskAutoArchiveEnabled 等 15 处调用点同构；原 {key,value}
    // 形态的键在 wire 层不存在，写面全坏。不做时间性自动重试，防双写）

    private func performUpdate(_ change: PendingChange) {
        guard session.connection.isActive, busyKey == nil else { return }
        busyKey = change.key
        notice = nil
        Task {
            defer { busyKey = nil }
            // web 实证：update({设置名: 新值}) 单键补丁对象（如 update({terminalFontFamily:t})）
            // ——{key,value} 是 wire 层不存在的键（审查报告 B-7）
            let fields: [String: JSONValue] = [change.key: change.newValue]
            do {
                _ = try await settingCall("update", fields, allowRetry: false)
                notice = String(localized: "已修改 \(change.key)")
                noticeIsError = false
                await load()
            } catch {
                // 失败：行值回弹（本地未改，读自 entries）+ 错误提示
                notice = "修改失败 · \(String(String(describing: error).prefix(160)))"
                noticeIsError = true
            }
        }
    }

    /// 编辑提交：按原值形态规整（int/double 校验可解析；其余按字符串下发），
    /// 通过确认弹层二次确认后才实际下发
    private func commitEdit(_ entry: Entry) {
        let text = editText.trimmingCharacters(in: .whitespaces)
        editingEntry = nil
        guard !text.isEmpty else { return }
        let newValue: JSONValue
        switch entry.value {
        case .int:
            guard let parsed = Int(text) else {
                notice = "「\(entry.key)」需要整数"
                noticeIsError = true
                return
            }
            newValue = .int(parsed)
        case .double:
            guard let parsed = Double(text) else {
                notice = "「\(entry.key)」需要数字"
                noticeIsError = true
                return
            }
            newValue = .double(parsed)
        default:
            newValue = .string(text)
        }
        pendingChange = PendingChange(key: entry.key, newValue: newValue)
    }

    // MARK: 宽容解析

    /// 回执三形态（全部【宽容·未取证】）：
    /// A 顶层数组 [{key|name|id, value…}]；B 包裹对象 {settings|values|data|config|
    /// items|entries: object 或 array}；C 整对象视为 {key: value} 映射。
    /// 只收 bool/string/int/double 标量（对象/数组/空值不臆造渲染，直接跳过）。
    static func parseEntries(_ json: JSONValue?) -> [Entry] {
        var raw: [(key: String, value: JSONValue, readonly: Bool)] = []
        if let array = json?.arrayValue {
            raw = array.compactMap { item in
                guard let d = item.objectValue,
                      let key = d["key"]?.stringValue ?? d["name"]?.stringValue
                          ?? d["id"]?.stringValue else { return nil }
                let value = d["value"] ?? d["default"] ?? JSONValue.null
                let readonly = d["readonly"]?.boolValue ?? d["readOnly"]?.boolValue
                    ?? (d["editable"]?.boolValue == false)
                return (key, value, readonly)
            }
        } else if let object = json?.objectValue {
            var handled = false
            for key in wrapperKeys {
                guard let wrapper = object[key] else { continue }
                if let wrapperObject = wrapper.objectValue {
                    raw = wrapperObject.map { ($0.key, $0.value, false) }
                    handled = true
                    break
                }
                if let wrapperArray = wrapper.arrayValue {
                    raw = wrapperArray.compactMap { item in
                        guard let d = item.objectValue,
                              let entryKey = d["key"]?.stringValue ?? d["name"]?.stringValue
                                  ?? d["id"]?.stringValue else { return nil }
                        return (entryKey, d["value"] ?? JSONValue.null,
                                d["readonly"]?.boolValue ?? d["readOnly"]?.boolValue
                                    ?? (d["editable"]?.boolValue == false))
                    }
                    handled = true
                    break
                }
            }
            // 形态 C：整对象兜底（含形态 B 各包裹键都缺席时）
            if !handled {
                raw = object.map { ($0.key, $0.value, false) }
            }
        }
        return raw.compactMap { item in
            switch item.value {
            case .bool, .int, .double, .string:
                return Entry(key: item.key, value: item.value, readonly: item.readonly)
            default:
                return nil
            }
        }
        .sorted { $0.key < $1.key }
    }

    private func displayValue(_ value: JSONValue?) -> String {
        switch value {
        case .bool(let b): return b ? String(localized: "开") : String(localized: "关")
        case .int(let i): return "\(i)"
        case .double(let d): return "\(d)"
        case .string(let s):
            // 枚举值中文化（zh-CN/queue/zai 等常见档位；未命中回退原文）
            return s.isEmpty ? String(localized: "（空）") : Self.localizedValue(.string(s))
        case .object, .array: return Self.localizedValue(value ?? .null)
        default: return "-"
        }
    }

    /// 编辑弹层初值（bool/复合形态不进编辑弹层，走 toggle 或只读展示）
    private func editSeed(_ value: JSONValue) -> String {
        switch value {
        case .int(let i): return "\(i)"
        case .double(let d): return "\(d)"
        case .string(let s): return s
        default: return ""
        }
    }
}

// MARK: - 重置卡二次确认面板（用户 2026-10-07：系统 alert 风格与设计不符——
// 自绘底部面板，AttachmentSourceSheet 同款设计语言：grabber + 明细卡 + 主行动/取消）

struct ResetConfirmSheet: View {
    let type: AppSession.CodingPlanResetType
    let fiveHourCount: Int
    let weekCount: Int
    var onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss

    private var isWeek: Bool { type == .week }

    var body: some View {
        VStack(spacing: T.sp3) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack(spacing: T.sp2) {
                Image(systemName: "creditcard")
                    .font(.system(size: 15))
                    .foregroundColor(T.orange)
                    .frame(width: 38, height: 38)
                    .background(T.orange.opacity(0.14))
                    .clipShape(Circle())
                Text("确认使用重置卡？")
                    .font(T.font(16, .bold))
                    .foregroundColor(T.text)
                Spacer()
            }
            .padding(.horizontal, T.sp4)

            VStack(alignment: .leading, spacing: T.sp2) {
                Text(UsageStatsView.resetConfirmMessage(
                    isWeek: isWeek, fiveHourCount: fiveHourCount, weekCount: weekCount))
                    .font(T.font(12.5))
                    .foregroundColor(T.text2)
                    .lineSpacing(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: T.sp2) {
                    resetCardRow("clock", String(localized: "5 小时卡"), fiveHourCount)
                    resetCardRow("calendar", String(localized: "周卡"), weekCount)
                }
            }
            .padding(T.sp3)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
            .padding(.horizontal, T.sp4)

            Button {
                // 只回调不自 dismiss：关闭由 handler 置 nil 走 isPresented binding
                // （此处置 nil 会先于回调把状态清空——sheet 呈现期捕获已规避）
                onConfirm()
            } label: {
                Text("确认使用")
                    .font(T.font(14.5, .semibold))
                    .foregroundColor(T.onAccent)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(T.red)
                    .clipShape(Capsule())
            }
            .padding(.horizontal, T.sp4)
            .accessibilityIdentifier("12-usage-confirm-use")

            Button {
                dismiss()
            } label: {
                Text("取消")
                    .font(T.font(14.5, .medium))
                    .foregroundColor(T.text2)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(T.bgInput)
                    .clipShape(Capsule())
            }
            .padding(.horizontal, T.sp4)
            .accessibilityIdentifier("12-usage-cancel")
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .background(T.bgElevated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("12-usage-reset-sheet")
    }

    private func resetCardRow(_ icon: String, _ title: String, _ count: Int) -> some View {
        HStack(spacing: T.sp1) {
            Image(systemName: icon)
                .font(.system(size: 11))
                .foregroundColor(T.text3)
            Text(title).font(T.font(11.5)).foregroundColor(T.text3)
            Spacer()
            Text("×\(count)")
                .font(T.mono(12, .semibold))
                .foregroundColor(count > 0 ? T.accentText : T.text3)
        }
        .padding(.horizontal, T.sp2)
        .frame(minHeight: 34)
        .background(T.bgInput)
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
    }
}

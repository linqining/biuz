import SwiftUI

// MARK: - G-015 会话分享预览（云端 API：GET /api/v1/shares/{shareCode}/preview）
//
// 桌面基准（packages/shared/src/conversation-share.ts:349-360 conversationSharePreviewDataSchema）：
// {schema_version, share(公开元数据), rows[](逐行降级解析), artifacts[], integrity}。
// 鉴权：Bearer zcodeJwtToken（调研 evidence：云端唯一实测 Bearer 调用；未登录也可看公开分享
// → 无 token 时裸发）。入口：zcode://share/<code> 自定义 scheme（通用链接需域名关联，暂无）。

/// 分享链接解析：zcode://share/<code> 或 https://…/share/<code>
enum ShareLinkParser {
    static func shareCode(from url: URL) -> String? {
        if url.scheme?.lowercased() == "zcode" {
            // zcode://share/<code>
            let parts = url.pathComponents.filter { $0 != "/" }
            if url.host?.lowercased() == "share", let code = parts.first { return code }
            if parts.first?.lowercased() == "share", parts.count >= 2 { return parts[1] }
            return nil
        }
        if url.scheme?.lowercased() == "https" || url.scheme?.lowercased() == "http" {
            let parts = url.pathComponents.filter { $0 != "/" }
            if let index = parts.firstIndex(where: { $0.lowercased() == "share" }),
               index + 1 < parts.count {
                return parts[index + 1]
            }
        }
        return nil
    }
}

/// 云端分享预览客户端（只读）
@MainActor
@Observable
final class SharePreviewStore {
    struct ShareMeta: Equatable {
        var title: String?
        var ownerName: String?
        var createdAtText: String?
    }
    struct PreviewRow: Identifiable, Equatable {
        var id: String
        var kind: String
        var text: String
        var isUser: Bool
    }

    private(set) var meta: ShareMeta?
    private(set) var rows: [PreviewRow] = []
    private(set) var unsupportedRowCount = 0
    private(set) var isLoading = false
    private(set) var errorText: String?

    func load(shareCode: String) async {
        isLoading = true
        errorText = nil
        defer { isLoading = false }
        var request = URLRequest(url: URL(string: "https://zcode.z.ai/api/v1/shares/\(shareCode)/preview")!)
        request.timeoutInterval = 10
        // Bearer zcodeJwtToken（有登录态带上；公开分享未登录也可看——无 token 裸发）
        if let token = OAuthCredentialStore.tokenSet?.zcodeJwtToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200..<300).contains(status) else {
                errorText = status == 401 || status == 403
                    ? String(localized: "该分享需要登录后查看（请求被拒绝）")
                    : String(localized: "分享预览获取失败（HTTP \(status)）· 链接可能已失效")
                return
            }
            let json = try JSONDecoder().decode(JSONValue.self, from: data)
            guard let dict = json.objectValue else {
                errorText = String(localized: "分享预览解析失败 · 响应不是 JSON 对象")
                return
            }
            var meta = ShareMeta()
            let share = dict["share"]?.objectValue ?? [:]
            meta.title = share["title"]?.stringValue
            meta.ownerName = share["ownerName"]?.stringValue ?? share["owner"]?.objectValue?["displayName"]?.stringValue
            if let ms = share["createdAt"]?.intValue {
                meta.createdAtText = Self.formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
            }
            self.meta = meta
            // rows 逐行降级（桌面口径：传输层不理解行语义，单行认不出不整份打不开）
            var rows: [PreviewRow] = []
            var unsupported = 0
            let rawRows: [JSONValue] = dict["rows"]?.arrayValue ?? []
            for (index, row) in rawRows.enumerated() {
                guard let d = row.objectValue else { unsupported += 1; continue }
                let kind = d["kind"]?.stringValue ?? "unknown"
                let text = d["text"]?.stringValue
                    ?? d["inputText"]?.stringValue
                    ?? d["summaryText"]?.stringValue
                    ?? d["output"]?.objectValue?["text"]?.stringValue ?? ""
                switch kind {
                case "userInput":
                    rows.append(PreviewRow(id: "r\(index)", kind: kind, text: text, isUser: true))
                case "assistantText", "reasoning":
                    rows.append(PreviewRow(id: "r\(index)", kind: kind, text: text, isUser: false))
                default:
                    // 工具调用/产物等行：只读页给轻量标注（不渲染执行细节）
                    unsupported += 1
                }
            }
            self.rows = rows
            self.unsupportedRowCount = unsupported
        } catch {
            errorText = String(localized: "分享预览获取失败 · \(error.localizedDescription)")
        }
    }

    static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()
}

/// 分享只读页（标题 + 消息流只读渲染；未登录可看公开分享）
struct SharePreviewSheet: View {
    @Environment(\.dismiss) private var dismiss
    let shareCode: String
    @State private var store = SharePreviewStore()

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("会话分享").font(T.font(17, .bold)).foregroundColor(T.text)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(T.text)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("15-act-close")
            }
            .padding(.horizontal, T.sp4)

            if store.isLoading {
                CenterLoadingView(text: "正在获取分享内容…").frame(maxHeight: .infinity)
                    .accessibilityIdentifier("15-loading")
            } else if let error = store.errorText {
                // preflight/preview 失败明确错误态（验收②）
                VStack(spacing: T.sp2) {
                    Image(systemName: "link.badge.plus")
                        .font(.system(size: 24))
                        .foregroundColor(T.orange)
                        .frame(width: 56, height: 56)
                        .background(T.orangeDim)
                        .clipShape(Circle())
                    Text(error)
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.text)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, T.sp6)
                    Button {
                        Task { await store.load(shareCode: shareCode) }
                    } label: {
                        Text("重试")
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.onAccent)
                            .padding(.horizontal, T.sp4)
                            .frame(minHeight: 44)
                            .background(T.accent)
                            .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                    .accessibilityIdentifier("15-act-retry")
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("15-error")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: T.sp2) {
                        if let meta = store.meta {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(meta.title ?? String(localized: "未命名会话"))
                                    .font(T.font(16, .heavy))
                                    .foregroundColor(T.text)
                                HStack(spacing: T.sp2) {
                                    if let owner = meta.ownerName {
                                        Text(owner).font(T.font(11)).foregroundColor(T.text3)
                                    }
                                    if let created = meta.createdAtText {
                                        Text(created).font(T.mono(10.5)).foregroundColor(T.text3)
                                    }
                                    StatusPill(text: String(localized: "只读分享"), kind: .tag, compact: true)
                                }
                            }
                            .padding(.bottom, T.sp2)
                            .accessibilityIdentifier("15-header")
                        }
                        ForEach(store.rows) { row in
                            if row.isUser {
                                UserBubble(text: row.text)
                            } else {
                                HStack(alignment: .top, spacing: T.sp2) {
                                    AgentAvatar(size: 22)
                                    MarkdownMessageBody(text: row.text)
                                }
                            }
                        }
                        if store.unsupportedRowCount > 0 {
                            Text(String(localized: "部分内容需更新 ZCode 查看（\(store.unsupportedRowCount) 行未渲染）"))
                                .font(T.font(11))
                                .foregroundColor(T.text3)
                                .padding(.top, T.sp2)
                                .accessibilityIdentifier("15-unsupported-note")
                        }
                    }
                    .padding(T.sp4)
                }
                .accessibilityIdentifier("15-body")
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .task { await store.load(shareCode: shareCode) }
    }
}

// MARK: - G-016 定时任务（自动化）列表 + 运行历史
//
// 桌面基准（zcodeAgentService.ts:4252 listAutomations / 4341 listAutomationRuns，
// toProtocolAutomation:946-965）：{automationId,title,cronExpr,enabled,lifecycleStatus,
// nextRunAt,lastRunAt,runCount,recurring,maxRuns,scheduleRule}。

struct AutomationRecord: Identifiable, Equatable {
    var id: String
    var title: String
    var cronExpr: String?
    var enabled: Bool
    var nextRunAt: Date?
    var lastRunAt: Date?
    var runCount: Int
    var lastRunFailed: Bool?

    var scheduleText: String {
        cronExpr ?? "--"
    }
}

struct AutomationRunRecord: Identifiable, Equatable {
    var id: String
    var startedAt: Date?
    var status: String   // success|failed|running…
    var summary: String?

    var statusLabel: String {
        switch status {
        case "success": return String(localized: "成功")
        case "failed": return String(localized: "失败")
        case "running": return String(localized: "运行中")
        default: return status
        }
    }

    var tint: Color {
        switch status {
        case "success": return T.accentText
        case "failed": return T.red
        case "running": return T.blue
        default: return T.text3
        }
    }
}

/// 自动化存储：连接态经 zcode-agent listAutomations/listAutomationRuns；演示/断连为空。
@MainActor
@Observable
final class AutomationStore {
    private(set) var records: [AutomationRecord] = []
    private(set) var runHistory: [String: [AutomationRunRecord]] = [:]
    private(set) var isLoading = false
    private(set) var lastError: String?

    func refresh(connection: ZCodeServerConnection, workspacePath: String?) async {
        isLoading = true
        lastError = nil
        defer { isLoading = false }
        var builder = JSONObjectBuilder()
        if let workspacePath { builder.set("workspacePath", workspacePath) }
        do {
            let result = try await connection.call("zcode-agent", "listAutomations", .json(.object(builder.fields)))
            let items = result.jsonValue?["automations"]?.arrayValue
                ?? result.jsonValue?.arrayValue ?? []
            records = items.compactMap(Self.parseAutomation)
        } catch {
            lastError = String(localized: "自动化列表获取失败 · \(error.localizedDescription)")
        }
    }

    func loadRuns(connection: ZCodeServerConnection, automationID: String) async {
        var builder = JSONObjectBuilder()
        builder.set("automationId", automationID)
        guard let result = try? await connection.call(
            "zcode-agent", "listAutomationRuns", .json(.object(builder.fields))) else { return }
        let items = result.jsonValue?["runs"]?.arrayValue
            ?? result.jsonValue?.arrayValue ?? []
        runHistory[automationID] = items.enumerated().compactMap { index, item in
            guard let d = item.objectValue else { return nil }
            return AutomationRunRecord(
                id: d["runId"]?.stringValue ?? "run-\(index)",
                startedAt: (d["startedAt"]?.intValue).map { Date(timeIntervalSince1970: Double($0) / 1000) }
                    ?? (d["startedAt"]?.doubleValue).map { Date(timeIntervalSince1970: $0) },
                status: d["status"]?.stringValue ?? "unknown",
                summary: d["summary"]?.stringValue ?? d["lastError"]?.stringValue)
        }
    }

    static func parseAutomation(_ json: JSONValue) -> AutomationRecord? {
        guard let d = json.objectValue,
              let id = d["automationId"]?.stringValue else { return nil }
        func date(_ key: String) -> Date? {
            if let ms = d[key]?.intValue, ms > 0 { return Date(timeIntervalSince1970: Double(ms) / 1000) }
            if let sec = d[key]?.doubleValue, sec > 1_000_000_000 { return Date(timeIntervalSince1970: sec) }
            return nil
        }
        return AutomationRecord(
            id: id,
            title: d["title"]?.stringValue ?? id,
            cronExpr: d["cronExpr"]?.stringValue,
            enabled: d["enabled"]?.boolValue ?? false,
            nextRunAt: date("nextRunAt"),
            lastRunAt: date("lastRunAt"),
            runCount: d["runCount"]?.intValue ?? 0,
            lastRunFailed: d["lastRunFailed"]?.boolValue)
    }
}

/// 自动化页（设置 .automation destination；替换硬编码演示占位）
struct AutomationsView: View {
    @Environment(AppSession.self) private var session
    @State private var store = AutomationStore()
    @State private var expandedRunID: String?

    private var isConnected: Bool {
        if case .connected = session.mode { return true }
        return false
    }

    var body: some View {
        Group {
            if !isConnected {
                // 断连/演示呈现离线态而非假数据（验收③）
                EmptyStateView(
                    icon: "clock.badge.checkmark",
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "定时任务由桌面端调度执行，连接后此处同步列表与运行历史"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "14-act-connect")
            } else if store.records.isEmpty && store.isLoading {
                CenterLoadingView(text: "正在同步自动化…")
                    .accessibilityIdentifier("14-loading")
            } else if store.records.isEmpty {
                EmptyStateView(
                    icon: "clock.badge.checkmark",
                    title: store.lastError ?? String(localized: "暂无定时任务"),
                    detail: store.lastError == nil
                        ? String(localized: "在桌面端创建自动化任务后，此处同步显示调度与历史")
                        : String(localized: "检查桌面端是否在线后下拉重试"),
                    cta: String(localized: "重新加载"),
                    ctaAction: { Task { await reload() } },
                    ctaIdentifier: "14-act-reload")
            } else {
                list
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "自动化"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .refreshable { await reload() }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                Text(String(localized: "共 \(store.records.count) 个定时任务 · 由桌面端调度"))
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .padding(.horizontal, 2)
                    .accessibilityIdentifier("14-summary")
                ForEach(store.records) { record in
                    automationCard(record)
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    private func automationCard(_ record: AutomationRecord) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) {
                    expandedRunID = expandedRunID == record.id ? nil : record.id
                    if expandedRunID != nil, store.runHistory[record.id] == nil {
                        Task { await store.loadRuns(connection: session.connection, automationID: record.id) }
                    }
                }
            } label: {
                HStack(spacing: T.sp2) {
                    Image(systemName: record.enabled ? "clock.fill" : "clock")
                        .font(.system(size: 14))
                        .foregroundColor(record.enabled ? T.accentText : T.text3)
                        .frame(width: 26)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(record.title)
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        Text(record.scheduleText)
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
                            .lineLimit(1)
                    }
                    Spacer()
                    if let last = record.lastRunAt {
                        Text(Self.relative.localizedString(for: last, relativeTo: Date()))
                            .font(T.font(10.5))
                            .foregroundColor(T.text3)
                    }
                    StatusPill(
                        text: record.enabled ? String(localized: "已启用") : String(localized: "已停用"),
                        kind: record.enabled ? .done : .tag,
                        compact: true)
                    Image(systemName: expandedRunID == record.id ? "chevron.down" : "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(T.text3)
                }
                .padding(T.sp3)
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
            .accessibilityIdentifier("14-row-\(record.id)")

            if expandedRunID == record.id {
                runsCard(record)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// 运行历史（验收②：运行一次后下拉刷新可见）
    private func runsCard(_ record: AutomationRecord) -> some View {
        VStack(alignment: .leading, spacing: T.sp1) {
            HStack {
                Text(String(localized: "运行历史"))
                    .font(T.font(12, .semibold))
                    .foregroundColor(T.text3)
                Spacer()
                Text(String(localized: "已运行 \(record.runCount) 次"))
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
            }
            let runs = store.runHistory[record.id] ?? []
            if runs.isEmpty {
                Text(String(localized: "暂无运行记录 · 下拉刷新重试"))
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            } else {
                ForEach(runs.prefix(10)) { run in
                    HStack(spacing: T.sp2) {
                        Circle().fill(run.tint).frame(width: 6, height: 6)
                        Text(run.statusLabel)
                            .font(T.font(12, .medium))
                            .foregroundColor(run.tint)
                        if let started = run.startedAt {
                            Text(Self.formatter.string(from: started))
                                .font(T.mono(10.5))
                                .foregroundColor(T.text3)
                        }
                        Spacer()
                        if let summary = run.summary, !summary.isEmpty {
                            Text(summary)
                                .font(T.font(10.5))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, T.sp2)
                    .frame(minHeight: 36)
                    .accessibilityIdentifier("14-run-\(run.id)")
                }
            }
        }
        .padding(T.sp2)
        .background(T.bgInput)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .accessibilityIdentifier("14-runs-\(record.id)")
    }

    private func reload() async {
        guard isConnected else { return }
        await store.refresh(connection: session.connection, workspacePath: nil)
    }

    static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        f.locale = Locale(identifier: "zh_CN")
        return f
    }()

    static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()
}

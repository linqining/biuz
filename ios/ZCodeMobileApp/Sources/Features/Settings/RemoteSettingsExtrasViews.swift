import SwiftUI

// MARK: - 屏 027/024/026/018 设置四页（上游取证 2026-11-10，web 权威源 file:line 见各段头注）
//
// 四页与 Android 同轮同口径（android/connection/Remote{Hooks,Subagents,Projects}.kt +
// DesktopSettingsScreen 前轮）：
// - 027 个性化 = web 同口径「仅本地桌面端」指引页（零服务调用）
// - 024 钩子 / 026 子智能体 / 018 项目 = 只读清单第一波；写面（saveHooks/createAgent/
//   deleteProject 等）gate 拦截或暂不接，恢复条件登记 PARITY_BACKLOG。

// MARK: 屏 027 · 个性化（指引页）

/// web 权威口径：PersonalizationSection.tsx:147-150 `if (!isDesktop) return
/// <LocalOnlyPersonalizationHint />`——手机远控壳只渲染指引、不发起任何读写
/// （SettingsPage.tsx:2099-2102：编辑会写错机器）。原生同口径，不提供编辑器。
struct PersonalizationGuidePage: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp3) {
                VStack(alignment: .leading, spacing: T.sp2) {
                    Text(String(localized: "添加自定义指令来个性化 Agent 的回复，指令会应用到你本机 Agent 的后续会话；远程设备需单独配置。"))
                        .font(T.font(13))
                        .foregroundColor(T.text2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(String(localized: "个性化指令仅支持在本地桌面端设置，请前往本地桌面端的「个性化」设置。"))
                        .font(T.font(13, .medium))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(T.sp4)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .padding(T.sp4)
        }
        .background(T.bg)
        .navigationTitle(String(localized: "个性化"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("27-local-only")
    }
}

// MARK: 写面波次②（gate 已放行 2026-11-10，web 同参同面裁决）

extension ZCodeServerConnection {
    /// 钩子整表保存（web hooksStore.persistHooks 同参）：`hooks.saveHooks
    /// {workspacePath, workspaceIdentity?, hooks}`——hooks 按 raw 整条回传（仅翻转
    /// enabled/删除目标行）；服务端过滤 editable!==false 且 source==="zcode" 的
    /// user/project 钩子后原子写 config.json（hooksService.ts:309-333）。
    /// 写命令不做时间性自动重试（防双写）。返回 nil=成功；非 nil=错误文案。
    func saveHooks(
        workspacePath: String?, workspaceIdentity: String?, hooks: [HookEntry]
    ) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        if let workspacePath, !workspacePath.isEmpty { builder.set("workspacePath", workspacePath) }
        if let workspaceIdentity, !workspaceIdentity.isEmpty { builder.set("workspaceIdentity", workspaceIdentity) }
        builder.set("hooks", .array(hooks.compactMap(\.raw)))
        do {
            _ = try await call("hooks", "saveHooks", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "保存失败 · \(error.localizedDescription)")
        }
    }

    /// 子智能体启停（web subagentsStore.setEnabled 同参）：服务端写 agents-state.json
    /// 的 disabledAgentIds（subagentsService.ts:678-692）。返回 nil=成功；非 nil=错误文案。
    func setSubagentEnabled(agentId: String, enabled: Bool) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("agentId", agentId)
        builder.set("enabled", enabled)
        do {
            _ = try await call("subagents", "setEnabled", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "操作失败 · \(error.localizedDescription)")
        }
    }
}

// MARK: 屏 024 · 钩子（hooks.loadHooks 只读）

/// 协议事实【实证·上游仓】：
/// - 频道 `hooks`（channels.ts:181-182）；`loadHooks` 入参 `{workspacePath,
///   workspaceIdentity?, scope?: "all"|"user"}`（hooks.ts:10-21），回执
///   `{hooks[], hooksEnabled, workspaceHookSnapshot?, trustStoreCorrupt?}`；
/// - 条目（shared/src/hooks.ts:33-50）：id/event/matcher?/type/command/args?/enabled/
///   editable?/workspaceHook?{trustState?}/location?{source,scope}；
/// - trust 词表（workspace-hook-review.ts:9-18）；web「待信任」判定 =
///   workspaceHook 在场且 trustState != trusted_persistent（WorkspaceHookTrustNotice.tsx:6-7）。
/// 一条钩子行；`raw` = 回执原条目（写面波次②：saveHooks 整表覆盖按 raw 回传，
/// 只翻转目标行 enabled——桌面侧过滤非可编辑条目，其余原样不动）。
struct HookEntry: Equatable {
    let id: String
    let event: String
    let matcher: String?
    let type: String
    let command: String
    let enabled: Bool
    let editable: Bool
    let source: String?
    let scope: String?
    let trustState: String?
    var raw: JSONValue?

    var needsTrust: Bool { trustState != nil && trustState != "trusted_persistent" }

    /// web HooksList 三分组：installed（zcode 可编辑）/ plugin / legacy
    var group: HookGroup {
        if source == "zcode" && editable { return .installed }
        if source == "plugin" { return .plugin }
        return .legacy
    }
}

enum HookGroup {
    case installed, plugin, legacy
}

struct HooksSnapshot: Equatable {
    var hooks: [HookEntry]
    var hooksEnabled: Bool
}

enum RemoteSettingsParsers {
    /// 钩子条目解析（id/event 缺席丢弃；editable 缺省 true——上游 save 过滤口径
    /// `editable !== false`，缺席=可编辑）
    static func parseHookEntries(_ json: JSONValue?) -> [HookEntry] {
        let items = json?.arrayValue ?? []
        return items.compactMap { item in
            guard let d = item.objectValue,
                  let id = d["id"]?.stringValue,
                  let event = d["event"]?.stringValue else { return nil }
            return HookEntry(
                id: id,
                event: event,
                matcher: d["matcher"]?.stringValue,
                type: d["type"]?.stringValue ?? "command",
                command: d["command"]?.stringValue ?? "",
                enabled: d["enabled"]?.boolValue ?? false,
                editable: d["editable"]?.boolValue ?? true,
                source: d["location"]?.objectValue?["source"]?.stringValue,
                scope: d["location"]?.objectValue?["scope"]?.stringValue,
                trustState: d["workspaceHook"]?.objectValue?["trustState"]?.stringValue,
                raw: item)
        }
    }

    /// 子智能体条目解析（id/name 缺席丢弃；tools 缺席 = 全部工具）
    static func parseSubagentEntries(_ json: JSONValue?) -> [SubagentEntry] {
        let items = json?.arrayValue ?? []
        return items.compactMap { item in
            guard let d = item.objectValue,
                  let id = d["id"]?.stringValue,
                  let name = d["name"]?.stringValue else { return nil }
            return SubagentEntry(
                id: id,
                name: name,
                description: d["description"]?.stringValue ?? "",
                scope: d["scope"]?.stringValue,
                source: d["source"]?.stringValue,
                enabled: d["enabled"]?.boolValue ?? true,
                readOnly: d["readOnly"]?.boolValue ?? false,
                tools: d["tools"]?.arrayValue?.compactMap(\.stringValue),
                pluginName: d["pluginName"]?.stringValue)
        }
    }

    /// 项目条目解析（id/name 缺席丢弃；roots[].rootPath 按 position 序）
    static func parseProjectEntries(_ json: JSONValue?) -> [ProjectEntry] {
        let items = json?.arrayValue ?? []
        return items.compactMap { item in
            guard let d = item.objectValue,
                  let id = d["id"]?.stringValue,
                  let name = d["name"]?.stringValue else { return nil }
            return ProjectEntry(
                id: id,
                name: name,
                hostId: d["hostId"]?.stringValue ?? "",
                pinned: d["pinned"]?.boolValue ?? false,
                deleted: d["deleted"]?.boolValue ?? false,
                position: d["position"]?.intValue ?? 0,
                roots: (d["roots"]?.arrayValue ?? []).compactMap { $0.objectValue?["rootPath"]?.stringValue },
                primaryRootStatus: d["primaryRootStatus"]?.stringValue,
                nameOverridden: d["nameOverridden"]?.boolValue ?? false)
        }
    }

    /// 时间双形态宽容读取：>1e12 已毫秒、>1e9 秒乘 1000（Android epochMillis 同口径）
    static func epochMillis(_ value: JSONValue?) -> Int64? {
        guard let numeric = value?.doubleValue else { return nil }
        if numeric > 1_000_000_000_000 { return Int64(numeric) }
        if numeric > 1_000_000_000 { return Int64(numeric * 1000) }
        return nil
    }
}

struct HooksPage: View {
    @Environment(AppSession.self) private var session
    @State private var snapshot: HooksSnapshot?
    @State private var phase: Phase = .loading
    @State private var errorText: String?
    // 写面波次②：启停/删除（saveHooks 整表覆盖；失败 reload 回弹 + 原文上屏）
    @State private var actionNotice: String?
    @State private var actionNoticeIsError = false
    @State private var busyHookId: String?
    @State private var pendingDelete: HookEntry?

    enum Phase: Equatable { case loading, loaded, failed, notConnected }

    private func persist(mutated: [HookEntry], successText: String) {
        guard busyHookId == nil else { return }
        let connection = session.connection
        Task {
            busyHookId = successText
            let error = await connection.saveHooks(
                workspacePath: connection.workspace?.path,
                workspaceIdentity: connection.workspace?.workspaceIdentity,
                hooks: mutated)
            busyHookId = nil
            actionNotice = error ?? successText
            actionNoticeIsError = error != nil
            if error != nil { await load() }
        }
    }

    private func load() async {
        phase = .loading
        errorText = nil
        let connection = session.connection
        guard connection.isActive else {
            phase = .notConnected
            return
        }
        var fields = workspaceScopeFields(connection)
        fields["scope"] = .string("all")
        do {
            let result = try await connection.call("hooks", "loadHooks", .json(.object(fields)))
            // id 兜底去重（同 id 保留首现；重复行会撞 a11y/列表键，子代理页实测教训）
            var seen = Set<String>()
            snapshot = HooksSnapshot(
                hooks: RemoteSettingsParsers.parseHookEntries(result.jsonValue?["hooks"]).filter { seen.insert($0.id).inserted },
                hooksEnabled: result.jsonValue?["hooksEnabled"]?.boolValue ?? false)
            phase = .loaded
        } catch {
            snapshot = nil
            errorText = String(String(describing: error).prefix(200))
            phase = .failed
        }
    }

    var body: some View {
        Group {
            if phase == .notConnected {
                EmptyStateView(
                    icon: "anchor",
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "钩子由桌面端管理；连接后此处同步清单"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "24-act-connect")
            } else {
                switch phase {
                case .loading:
                    CenterLoadingView(text: "正在读取钩子…")
                        .accessibilityIdentifier("24-loading")
                case .loaded where !(snapshot?.hooks.isEmpty ?? true):
                    list
                case .loaded, .failed:
                    EmptyStateView(
                        icon: "anchor",
                        title: phase == .failed
                            ? (errorText ?? String(localized: "读取失败"))
                            : String(localized: "暂无钩子"),
                        detail: phase == .failed
                            ? String(localized: "检查桌面端是否在线后重试")
                            : String(localized: "在桌面端创建钩子后，此处同步显示事件与命令"),
                        cta: String(localized: "重新加载"),
                        ctaAction: { Task { await load() } },
                        ctaIdentifier: "24-act-reload")
                default:
                    EmptyStateView(icon: "anchor", title: String(localized: "暂无钩子"),
                                   detail: String(localized: "在桌面端创建钩子后，此处同步显示事件与命令"))
                }
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "钩子"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("24-hooks-page")
        .task { await load() }
        .refreshable { await load() }
        // 删除确认（saveHooks 整表覆盖 = 数组过滤后保存，web handleDelete 同语义）
        .confirmationDialog(
            String(localized: "删除钩子？"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "删除"), role: .destructive) {
                guard let target = pendingDelete else { return }
                pendingDelete = nil
                let remaining = (snapshot?.hooks ?? []).filter { $0.id != target.id }
                persist(
                    mutated: remaining,
                    successText: String(format: String(localized: "已删除 %@"), target.event))
            }
            .accessibilityIdentifier("24-act-delete-confirm")
            Button(String(localized: "取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(String(format: String(localized: "确定删除「%@」钩子吗？删除会写入桌面端配置（可重新创建）。"), pendingDelete?.event ?? ""))
        }
    }

    private var list: some View {
        let hooks = snapshot?.hooks ?? []
        return ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                if let actionNotice {
                    Text(actionNotice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(actionNoticeIsError ? T.red : T.accentText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("24-action-notice")
                }
                Text(String(format: snapshot?.hooksEnabled == true
                    ? String(localized: "共 %lld 个钩子 · 总开关已启用")
                    : String(localized: "共 %lld 个钩子 · 总开关已关闭"), hooks.count))
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .accessibilityIdentifier("24-summary")
                ForEach(
                    [(HookGroup.installed, String(localized: "已安装")),
                     (HookGroup.plugin, String(localized: "插件")),
                     (HookGroup.legacy, String(localized: "旧版"))],
                    id: \.0
                ) { group, title in
                    let groupHooks = hooks.filter { $0.group == group }
                    if !groupHooks.isEmpty {
                        Text("\(title)（\(groupHooks.count)）")
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.text3)
                        VStack(spacing: 0) {
                            ForEach(Array(groupHooks.enumerated()), id: \.element.id) { index, hook in
                                hookRow(
                                    hook,
                                    busy: busyHookId == hook.id,
                                    onToggle: { newValue in
                                        let mutated = hooks.map { entry in
                                            entry.id == hook.id
                                                ? HookEntry(
                                                    id: entry.id, event: entry.event,
                                                    matcher: entry.matcher, type: entry.type,
                                                    command: entry.command, enabled: newValue,
                                                    editable: entry.editable, source: entry.source,
                                                    scope: entry.scope, trustState: entry.trustState,
                                                    raw: toggleRawEnabled(entry.raw, newValue))
                                                : entry
                                        }
                                        persist(
                                            mutated: mutated,
                                            successText: String(format: String(localized: "已%@ %@"), newValue ? String(localized: "启用") : String(localized: "停用"), hook.event))
                                    },
                                    onDelete: { pendingDelete = hook })
                                if index != groupHooks.count - 1 {
                                    Divider().overlay(T.border)
                                }
                            }
                        }
                        .background(T.bgCard)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    private func hookRow(
        _ hook: HookEntry, busy: Bool,
        onToggle: @escaping (Bool) -> Void, onDelete: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: T.sp1) {
                Text(hook.event)
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if let matcher = hook.matcher, !matcher.isEmpty {
                    Text(" · \(matcher)")
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
                Spacer()
                // 已安装钩子：启停开关 + 删除（写面波次②；legacy/plugin 只读——
                // 桌面侧 saveHooks 过滤不落非 zcode 条目，UI 不给入口）
                if hook.group == .installed {
                    if busy {
                        ProgressView().scaleEffect(0.7).padding(.leading, T.sp1)
                    } else {
                        Toggle("", isOn: Binding(
                            get: { hook.enabled },
                            set: { newValue in
                                guard newValue != hook.enabled else { return }
                                onToggle(newValue)
                            }))
                            .labelsHidden()
                            .tint(T.accentText)
                            .accessibilityIdentifier("24-toggle-\(hook.id)")
                        Button(action: onDelete) {
                            Image(systemName: "trash")
                                .font(.system(size: 12))
                                .foregroundColor(T.text3)
                                .padding(.horizontal, T.sp1)
                        }
                        .accessibilityIdentifier("24-delete-\(hook.id)")
                    }
                } else {
                    Text(hook.enabled ? String(localized: "已启用") : String(localized: "已停用"))
                        .font(T.font(10))
                        .foregroundColor(hook.enabled ? T.accentText : T.text3)
                }
            }
            if !hook.command.isEmpty {
                Text(hook.command)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(2)
            }
            HStack(spacing: T.sp2) {
                Text(hook.type == "process"
                    ? String(localized: "进程")
                    : String(localized: "Shell 命令"))
                    .font(T.font(10))
                    .foregroundColor(T.text3)
                if hook.needsTrust {
                    Text(String(localized: "待信任"))
                        .font(T.font(10, .semibold))
                        .foregroundColor(T.orange)
                }
            }
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("24-row-\(hook.id)")
    }

    /// raw 条目的 enabled 原位翻转（saveHooks 整表回传用，其余字段不动）
    private func toggleRawEnabled(_ raw: JSONValue?, _ enabled: Bool) -> JSONValue? {
        guard var fields = raw?.objectValue else { return raw }
        fields["enabled"] = .bool(enabled)
        return .object(fields)
    }

    /// workspace 信封（与 workspaceScopeFields 同构；独立实现避免跨页 private 依赖）
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
}

// MARK: 屏 026 · 子智能体（subagents.list 只读）

/// 协议事实【实证·上游仓】：频道 `subagents`（channels.ts:177-178）；`list` 入参
/// `{workspacePath, workspaceIdentity?, provider:"glm"(ZCODE_AGENT_PROVIDER,
/// zcode-agent-policy.ts:5), mode:"allRuntimeScopes"}`（subagents.ts:15-41 +
/// SubagentsSection.tsx:1404-1409）；回执 `AgentsListResult`（subagents-types.ts:88-94）
/// agents/userAgents/pluginAgents/capability{userScopeAvailable,userScopeReason}。
/// **agents 数组实测已含插件代理**（桌面 3.17.2）——按 web 投影（SubagentsSection.tsx:115-119）
/// 滤 plugin 后拼 pluginAgents + id 兜底去重。
struct SubagentEntry: Equatable {
    let id: String
    let name: String
    let description: String
    let scope: String?
    let source: String?
    let enabled: Bool
    let readOnly: Bool
    /// tools 白名单（nil = 未配置 = 全部工具）
    let tools: [String]?
    let pluginName: String?

    var toolsAll: Bool { tools == nil }
}

struct SubagentsPage: View {
    @Environment(AppSession.self) private var session
    @State private var entries: [SubagentEntry] = []
    @State private var phase: Phase = .loading
    @State private var errorText: String?
    // 写面波次②：启停开关（仅 user scope——web supportsEnabledToggle 同口径）
    @State private var actionNotice: String?
    @State private var actionNoticeIsError = false
    @State private var busyAgentId: String?

    enum Phase: Equatable { case loading, loaded, failed, notConnected }

    private func load() async {
        phase = .loading
        errorText = nil
        let connection = session.connection
        guard connection.isActive else {
            phase = .notConnected
            return
        }
        var fields = workspaceScopeFields(connection)
        fields["provider"] = .string("glm")
        fields["mode"] = .string("allRuntimeScopes")
        do {
            let result = try await connection.call("subagents", "list", .json(.object(fields)))
            let combined = RemoteSettingsParsers.parseSubagentEntries(result.jsonValue?["agents"])
                .filter { $0.source != "plugin" }
                + RemoteSettingsParsers.parseSubagentEntries(result.jsonValue?["pluginAgents"])
            var seen = Set<String>()
            entries = combined.filter { seen.insert($0.id).inserted }
            phase = .loaded
        } catch {
            entries = []
            errorText = String(String(describing: error).prefix(200))
            phase = .failed
        }
    }

    var body: some View {
        Group {
            if phase == .notConnected {
                EmptyStateView(
                    icon: "bot",
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "子智能体定义存于桌面端；连接后此处同步清单"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "26-act-connect")
            } else {
                switch phase {
                case .loading:
                    CenterLoadingView(text: "正在读取子智能体…")
                        .accessibilityIdentifier("26-loading")
                case .loaded where !entries.isEmpty:
                    list
                case .loaded, .failed:
                    EmptyStateView(
                        icon: "bot",
                        title: phase == .failed
                            ? (errorText ?? String(localized: "读取失败"))
                            : String(localized: "暂无子智能体"),
                        detail: phase == .failed
                            ? String(localized: "检查桌面端是否在线后重试")
                            : String(localized: "在桌面端创建子智能体后，此处同步显示清单"),
                        cta: String(localized: "重新加载"),
                        ctaAction: { Task { await load() } },
                        ctaIdentifier: "26-act-reload")
                default:
                    EmptyStateView(icon: "bot", title: String(localized: "暂无子智能体"),
                                   detail: String(localized: "在桌面端创建子智能体后，此处同步显示清单"))
                }
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "子智能体"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("26-subagents-page")
        .task { await load() }
        .refreshable { await load() }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                if let actionNotice {
                    Text(actionNotice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(actionNoticeIsError ? T.red : T.accentText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("26-action-notice")
                }
                ForEach(
                    [("user", String(localized: "已安装")),
                     ("built-in", String(localized: "内置子智能体")),
                     ("plugin", String(localized: "插件 profile"))],
                    id: \.0
                ) { source, title in
                    let groupEntries = entries.filter { $0.source == source }
                    if !groupEntries.isEmpty {
                        Text("\(title)（\(groupEntries.count)）")
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.text3)
                        VStack(spacing: 0) {
                            ForEach(Array(groupEntries.enumerated()), id: \.element.id) { index, entry in
                                subagentRow(
                                    entry,
                                    busy: busyAgentId == entry.id,
                                    onToggle: { newValue in
                                        guard busyAgentId == nil else { return }
                                        busyAgentId = entry.id
                                        let name = entry.name
                                        let error = await session.connection.setSubagentEnabled(
                                            agentId: entry.id, enabled: newValue)
                                        busyAgentId = nil
                                        actionNotice = error
                                            ?? String(format: String(localized: "已%@ %@"),
                                                      newValue ? String(localized: "启用") : String(localized: "停用"), name)
                                        actionNoticeIsError = error != nil
                                        if error != nil { await load() }
                                    })
                                if index != groupEntries.count - 1 {
                                    Divider().overlay(T.border)
                                }
                            }
                        }
                        .background(T.bgCard)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    private func subagentRow(
        _ entry: SubagentEntry, busy: Bool, onToggle: @escaping (Bool) async -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: T.sp1) {
                Text(entry.name)
                    .font(T.font(14, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer()
                // 启停开关（仅 user scope；built-in/workspace/plugin 行不给开关——
                // web supportsEnabledToggle 同口径）。开关不即时落本地：失败走 load() 回弹。
                if entry.source == "user" {
                    if busy {
                        ProgressView()
                            .scaleEffect(0.7)
                            .padding(.leading, T.sp1)
                    } else {
                        Toggle("", isOn: Binding(
                            get: { entry.enabled },
                            set: { newValue in
                                guard newValue != entry.enabled else { return }
                                Task { await onToggle(newValue) }
                            }))
                            .labelsHidden()
                            .tint(T.accentText)
                            .accessibilityIdentifier("26-toggle-\(entry.id)")
                    }
                } else {
                    Text(entry.enabled ? String(localized: "已启用") : String(localized: "已停用"))
                        .font(T.font(10))
                        .foregroundColor(entry.enabled ? T.accentText : T.text3)
                }
            }
            if !entry.description.isEmpty {
                Text(entry.description)
                    .font(T.font(12))
                    .foregroundColor(T.text3)
                    .lineLimit(2)
            }
            Text(entry.toolsAll
                ? String(localized: "全部工具")
                : String(format: String(localized: "%lld 个工具"), entry.tools?.count ?? 0))
                .font(T.font(10))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("26-row-\(entry.id)")
    }

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
}

// MARK: 屏 018 · 项目管理（project.readTree 只读第一波）

/// 协议事实【实证·上游仓】：频道 `project`（channels.ts:123，手机 allowlist
/// windowHostAttachmentRpcPolicy.ts:39）；`readTree` 入参 `{includeDeleted:true,
/// includeDeletedTasks:true, hostId?}`（project-tree-reader.ts:19-25，无 zod）；
/// 回执 `ProjectTree`（repository-contract.ts:35-41）projects:VirtualProject[]/
/// tasks:ProjectTask[]；VirtualProject（:6-23）id/hostId/name/nameOverridden/roots/
/// position/pinned/deleted/primaryRootStatus?。写面（deleteProject/restoreProject/
/// measureProjectData/deleteProjectData）第一波不接（登记 backlog）。
struct ProjectEntry: Equatable {
    let id: String
    let name: String
    let hostId: String
    let pinned: Bool
    let deleted: Bool
    let position: Int
    let roots: [String]
    let primaryRootStatus: String?
    let nameOverridden: Bool
}

// MARK: 018 写面（P-009 写面波次①：移除/恢复；gate 默认放行 project 频道）

extension ZCodeServerConnection {
    /// 移除项目（软删；web useProjectManagement.ts:222-225 同参 `deleteProject(id, hostId)`）。
    /// 多参服务按位置数组透传（web toService `channel.call(name, args)` 同形；
    /// 中继传输对 .array arg 原样发——RelayChannelClient 2026-11-10 约定注记）。
    /// 返回 nil=成功；非 nil=可上屏错误文案（写面禁静默；CancellationError 亦归错误——
    /// 任务取消时宿主视图已退场，notice 无消费方）。
    func deleteProject(id: String, hostId: String) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        do {
            _ = try await call(
                "project", "deleteProject",
                .json(.array([.string(id), .string(hostId)])))
            return nil
        } catch {
            return String(localized: "移除失败 · \(error.localizedDescription)")
        }
    }

    /// 恢复项目（撤销移除；web useProjectManagement.ts:341-344 同参）。返回 nil=成功。
    func restoreProject(id: String, hostId: String) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        do {
            _ = try await call(
                "project", "restoreProject",
                .json(.array([.string(id), .string(hostId)])))
            return nil
        } catch {
            return String(localized: "恢复失败 · \(error.localizedDescription)")
        }
    }
}

struct ProjectsTreeData: Equatable {
    var projects: [ProjectEntry]
    var taskCountByProject: [String: Int]
}

struct ProjectsPage: View {
    @Environment(AppSession.self) private var session
    @State private var tree: ProjectsTreeData?
    @State private var phase: Phase = .loading
    @State private var errorText: String?
    // 写面（P-009 写面波次①）：移除/恢复——nil=成功；写面禁静默（成功/失败都上屏）
    @State private var actionNotice: String?
    @State private var actionNoticeIsError = false
    @State private var pendingDelete: ProjectEntry?
    @State private var actionBusy = false

    enum Phase: Equatable { case loading, loaded, failed, notConnected }

    private func load() async {
        phase = .loading
        errorText = nil
        let connection = session.connection
        guard connection.isActive else {
            phase = .notConnected
            return
        }
        var builder = JSONObjectBuilder()
        builder.set("includeDeleted", true)
        builder.set("includeDeletedTasks", true)
        do {
            let result = try await connection.call("project", "readTree", .json(.object(builder.fields)))
            var seen = Set<String>()
            let projects = RemoteSettingsParsers.parseProjectEntries(result.jsonValue?["projects"])
                .filter { seen.insert($0.id).inserted }
            var counts: [String: Int] = [:]
            for task in result.jsonValue?["tasks"]?.arrayValue ?? [] {
                guard let projectId = task.objectValue?["projectId"]?.stringValue else { continue }
                counts[projectId, default: 0] += 1
            }
            tree = ProjectsTreeData(projects: projects, taskCountByProject: counts)
            phase = .loaded
        } catch {
            tree = nil
            errorText = String(String(describing: error).prefix(200))
            phase = .failed
        }
    }

    var body: some View {
        Group {
            if phase == .notConnected {
                EmptyStateView(
                    icon: "folder",
                    title: String(localized: "未连接桌面端"),
                    detail: String(localized: "项目组织存于桌面端；连接后此处同步清单"),
                    cta: String(localized: "连接桌面端"),
                    ctaAction: { session.requestConnectFlow(editTokenOnly: false) },
                    ctaIdentifier: "18-act-connect")
            } else {
                switch phase {
                case .loading:
                    CenterLoadingView(text: "正在读取项目…")
                        .accessibilityIdentifier("18-loading")
                case .loaded:
                    list
                case .failed:
                    EmptyStateView(
                        icon: "folder",
                        title: errorText ?? String(localized: "读取失败"),
                        detail: String(localized: "检查桌面端是否在线后重试"),
                        cta: String(localized: "重新加载"),
                        ctaAction: { Task { await load() } },
                        ctaIdentifier: "18-act-reload")
                default:
                    EmptyStateView(icon: "folder", title: String(localized: "暂无项目"),
                                   detail: String(localized: "桌面端的侧栏项目会同步显示在这里"))
                }
            }
        }
        .background(T.bg)
        .navigationTitle(String(localized: "项目"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("18-projects-page")
        .task { await load() }
        .refreshable { await load() }
        // 移除确认（软删可撤销——web ProjectRemovalDialog 的「删数据」选项未接：
        // v1 只做侧栏移除，measureProjectData/deleteProjectData 登记后续波次）
        .confirmationDialog(
            String(format: String(localized: "移除项目「%@」？"), pendingDelete?.name ?? ""),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "移除"), role: .destructive) {
                guard let target = pendingDelete else { return }
                pendingDelete = nil
                Task {
                    let error = await session.connection.deleteProject(id: target.id, hostId: target.hostId)
                    actionNotice = error ?? String(format: String(localized: "已移除 %@"), target.name)
                    actionNoticeIsError = error != nil
                    await load()
                }
            }
            .accessibilityIdentifier("18-act-remove-confirm")
            Button(String(localized: "取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(String(localized: "它会从侧栏和项目选择器中移除，会话仍保留在最近和搜索中，磁盘上的文件不受影响。"))
        }
    }

    private var list: some View {
        let projects = tree?.projects ?? []
        let active = projects.filter { !$0.deleted }
        let removed = projects.filter { $0.deleted }
        let counts = tree?.taskCountByProject ?? [:]
        return ScrollView {
            VStack(alignment: .leading, spacing: T.sp2) {
                if let actionNotice {
                    Text(actionNotice)
                        .font(T.font(11.5, .semibold))
                        .foregroundColor(actionNoticeIsError ? T.red : T.accentText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("18-action-notice")
                }
                Text(String(localized: "移除项目只会把它从侧栏和项目选择器中拿掉。会话仍保留在最近和搜索中，磁盘上的文件不受影响。"))
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .accessibilityIdentifier("18-intro")
                if active.isEmpty && removed.isEmpty {
                    EmptyStateView(
                        icon: "folder",
                        title: String(localized: "暂无项目"),
                        detail: String(localized: "桌面端的侧栏项目会同步显示在这里"))
                }
                ForEach(active, id: \.id) { project in
                    projectRow(project, taskCount: counts[project.id] ?? 0)
                        .contextMenu {
                            Button(role: .destructive) {
                                pendingDelete = project
                            } label: {
                                Label(String(localized: "移除项目"), systemImage: "folder.badge.minus")
                            }
                        }
                }
                if !removed.isEmpty {
                    Text(String(format: String(localized: "已移除的项目（%lld）"), removed.count))
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.text3)
                    VStack(spacing: 0) {
                        ForEach(removed, id: \.id) { project in
                            removedRow(project) {
                                guard !actionBusy else { return }
                                actionBusy = true
                                let name = project.name
                                let error = await session.connection.restoreProject(id: project.id, hostId: project.hostId)
                                actionBusy = false
                                actionNotice = error ?? String(format: String(localized: "已恢复 %@"), name)
                                actionNoticeIsError = error != nil
                                await load()
                            }
                        }
                    }
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
    }

    private func projectRow(_ project: ProjectEntry, taskCount: Int) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: T.sp1) {
                Text(project.name)
                    .font(T.font(14, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer()
                if project.pinned {
                    Text(String(localized: "已置顶"))
                        .font(T.font(10))
                        .foregroundColor(T.accentText)
                }
                if project.primaryRootStatus == "missing" {
                    Text(String(localized: "目录缺失"))
                        .font(T.font(10, .semibold))
                        .foregroundColor(T.orange)
                }
            }
            if !project.roots.isEmpty {
                Text(project.roots.joined(separator: " · "))
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Text(String(format: String(localized: "%lld 个会话"), taskCount))
                .font(T.font(10))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("18-row-\(project.id)")
    }

    private func removedRow(_ project: ProjectEntry, onRestore: @escaping () async -> Void) -> some View {
        HStack {
            Text(project.name)
                .font(T.font(13))
                .foregroundColor(T.text3)
                .lineLimit(1)
            Spacer()
            Text(String(localized: "已移除"))
                .font(T.font(10))
                .foregroundColor(T.text3)
            Button {
                Task { await onRestore() }
            } label: {
                Text(String(localized: "恢复"))
                    .font(T.font(12, .medium))
                    .foregroundColor(T.accentText)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 4)
            }
            .disabled(actionBusy)
            .accessibilityIdentifier("18-act-restore-\(project.id)")
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, 10)
        .accessibilityIdentifier("18-removed-\(project.id)")
    }
}

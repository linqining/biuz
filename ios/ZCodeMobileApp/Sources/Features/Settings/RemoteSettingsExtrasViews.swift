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

    /// config 装配（web onSave 表单同构）：编辑器四键 + raw preserve 族
    /// （disallowedTools/skills/permissionMode/maxTurns/background/mcpServers 原样）。
    private func subagentConfig(
        _ raw: JSONValue?, name: String, description: String,
        systemPrompt: String, injectAgentsMd: Bool?
    ) -> JSONValue {
        var config = raw?.objectValue ?? [:]
        config["name"] = .string(name)
        config["description"] = .string(description)
        config["systemPrompt"] = .string(systemPrompt)
        for key in ["tools", "disallowedTools", "skills", "permissionMode",
                    "maxTurns", "background", "mcpServers"] {
            config.removeValue(forKey: key) // 保留原值——上面从 raw 展开已含
        }
        if let raw {
            for key in ["color", "tools", "disallowedTools", "skills", "permissionMode",
                        "maxTurns", "background", "mcpServers"] {
                if let v = raw.objectValue?[key] { config[key] = v }
            }
        }
        if let injectAgentsMd { config["injectAgentsMd"] = .bool(injectAgentsMd) }
        return .object(config)
    }

    /// 子智能体新建（写面波次③，gate 已放行；web SubagentsSection.tsx:1517-1525 同参）。
    /// 返回 nil=成功；非 nil=错误文案。
    func createSubagent(
        name: String, description: String, systemPrompt: String,
        workspacePath: String?, workspaceIdentity: String?
    ) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("config", subagentConfig(nil, name: name, description: description,
                                              systemPrompt: systemPrompt, injectAgentsMd: nil))
        builder.set("provider", "glm")
        builder.set("scope", "user")
        if let workspacePath, !workspacePath.isEmpty { builder.set("workspacePath", workspacePath) }
        if let workspaceIdentity, !workspaceIdentity.isEmpty { builder.set("workspaceIdentity", workspaceIdentity) }
        do {
            _ = try await call("subagents", "createAgent", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "新建失败 · \(error.localizedDescription)")
        }
    }

    /// 子智能体更新（web SubagentsSection.tsx:1507-1516 同参：config + oldFilePath +
    /// scope 按 entry.scope 二值；workspacePath 取 projectPath 优先）。
    func updateSubagent(
        _ entry: SubagentEntry, name: String, description: String, systemPrompt: String,
        workspacePath: String?, workspaceIdentity: String?
    ) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("agentId", entry.id)
        builder.set("config", subagentConfig(entry.raw, name: name, description: description,
                                             systemPrompt: systemPrompt, injectAgentsMd: entry.injectAgentsMd))
        if let path = entry.path { builder.set("oldFilePath", path) }
        builder.set("provider", "glm")
        builder.set("scope", entry.scope == "workspace" ? "workspace" : "user")
        let effectiveWorkspace = entry.projectPath ?? workspacePath
        if let effectiveWorkspace, !effectiveWorkspace.isEmpty { builder.set("workspacePath", effectiveWorkspace) }
        if let workspaceIdentity, !workspaceIdentity.isEmpty { builder.set("workspaceIdentity", workspaceIdentity) }
        do {
            _ = try await call("subagents", "updateAgent", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "保存失败 · \(error.localizedDescription)")
        }
    }

    /// 子智能体删除（web SubagentsSection.tsx:1565 同参 {agentId, filePath}）。
    func deleteSubagent(_ entry: SubagentEntry) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("agentId", entry.id)
        builder.set("filePath", entry.path ?? "")
        do {
            _ = try await call("subagents", "deleteAgent", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "删除失败 · \(error.localizedDescription)")
        }
    }

    /// 模型覆盖 JSON（model-selection.ts:4-15 strict 三键；reasoningLevel nil=继承默认）。
    private func overrideJson(_ override: ModelSelectionOverride) -> JSONValue {
        var options: [String: JSONValue] = [:]
        if let reasoningLevel = override.reasoningLevel, !reasoningLevel.isEmpty {
            options["reasoningLevel"] = .string(reasoningLevel)
        }
        var fields: [String: JSONValue] = [
            "providerId": .string(override.providerId),
            "modelId": .string(override.modelId),
        ]
        if !options.isEmpty { fields["options"] = .object(options) }
        return .object(fields)
    }

    /// 内建子代理模型覆盖（写面波次⑤，gate 已放行；web SubagentsSection.tsx:1607-1610
    /// 同参 {agentName, modelSelection?}；modelSelection nil=继承默认）。
    func setBuiltInModelOverride(
        agentName: String, modelSelection: ModelSelectionOverride?
    ) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("agentName", agentName)
        if let modelSelection { builder.set("modelSelection", overrideJson(modelSelection)) }
        do {
            _ = try await call("subagents", "setBuiltInModelOverride", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "设置失败 · \(error.localizedDescription)")
        }
    }

    /// 插件子代理模型覆盖（web SubagentsSection.tsx:1611-1613 同参 {agentId, modelSelection?}）。
    func setPluginAgentModelOverride(
        agentId: String, modelSelection: ModelSelectionOverride?
    ) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("agentId", agentId)
        if let modelSelection { builder.set("modelSelection", overrideJson(modelSelection)) }
        do {
            _ = try await call("subagents", "setPluginAgentModelOverride", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "设置失败 · \(error.localizedDescription)")
        }
    }
}

/// 模型覆盖选择（model-selection.ts:4-15 strict 三键）。
struct ModelSelectionOverride: Equatable {
    var providerId: String
    var modelId: String
    /// nil = 继承该模型默认档
    var reasoningLevel: String?

    var pickerValue: String {
        let base = "\(providerId)/\(modelId)"
        return reasoningLevel.map { "\(base)$\($0)" } ?? base
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
                pluginName: d["pluginName"]?.stringValue,
                path: d["path"]?.stringValue,
                projectPath: d["projectPath"]?.stringValue,
                systemPrompt: d["systemPrompt"]?.stringValue,
                injectAgentsMd: d["injectAgentsMd"]?.boolValue,
                raw: item)
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
/// 一条子代理行；`raw` = 回执原条目（写面波次③：updateAgent config 按 raw 回传
/// 保留 web 表单 preserve 族——tools/disallowedTools/skills/permissionMode/
/// maxTurns/background/mcpServers 原样，编辑器只改四键）；`path`/`projectPath`
/// 供 deleteAgent filePath 与 updateAgent oldFilePath。
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
    /// md 文件路径（built-in/plugin 无文件面；delete filePath / update oldFilePath）
    var path: String?
    var projectPath: String?
    var systemPrompt: String?
    var injectAgentsMd: Bool?
    var raw: JSONValue?

    var toolsAll: Bool { tools == nil }
}

struct SubagentsPage: View {
    @Environment(AppSession.self) private var session
    @Environment(\.conversationStore) private var store
    @State private var entries: [SubagentEntry] = []
    @State private var phase: Phase = .loading
    @State private var errorText: String?
    // 写面波次②：启停开关（仅 user scope——web supportsEnabledToggle 同口径）
    @State private var actionNotice: String?
    @State private var actionNoticeIsError = false
    @State private var busyAgentId: String?
    // 写面波次③：编辑器（.sheet(item:) 载荷；entry=nil=新建）
    @State private var editorRequest: SubagentEditorRequest?
    @State private var pendingDelete: SubagentEntry?
    // 写面波次⑤：模型覆盖（built-in/plugin 行；.sheet(item:) 语义）
    @State private var overrideRequest: SubagentOverrideRequest?

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
        // 写面波次③：编辑器 sheet（.sheet(item:)——每次呈现重建 content；
        // §5-15 呈现期捕获 request；name/description/systemPrompt 三键 +
        // raw preserve 族随 update 下发；web SubagentForm 同参）
        .sheet(item: $editorRequest) { request in
            SubagentEditSheet(
                seed: request.entry,
                workspacePath: session.connection.workspace?.path,
                workspaceIdentity: session.connection.workspace?.workspaceIdentity,
                onSave: { name, description, systemPrompt in
                    let isNew = request.entry == nil
                    let error: String?
                    if let entry = request.entry {
                        error = await session.connection.updateSubagent(
                            entry, name: name, description: description, systemPrompt: systemPrompt,
                            workspacePath: session.connection.workspace?.path,
                            workspaceIdentity: session.connection.workspace?.workspaceIdentity)
                    } else {
                        error = await session.connection.createSubagent(
                            name: name, description: description, systemPrompt: systemPrompt,
                            workspacePath: session.connection.workspace?.path,
                            workspaceIdentity: session.connection.workspace?.workspaceIdentity)
                    }
                    editorRequest = nil
                    actionNotice = error
                        ?? String(format: String(localized: "已%@ %@"),
                                  isNew ? String(localized: "新建") : String(localized: "保存"),
                                  name)
                    actionNoticeIsError = error != nil
                    await load()
                },
                onCancel: { editorRequest = nil })
        }
        // 写面波次⑤：模型覆盖 sheet（.sheet(item:)；模型目录 = 既有
        // model-selection.getView 投影 store.modelSelectionView()——chips 同词表）
        .sheet(item: $overrideRequest) { request in
            SubagentOverrideSheet(
                agentKey: request.agentKey,
                isBuiltIn: request.isBuiltIn,
                store: store,
                session: session,
                onDone: { error, message in
                    actionNotice = error ?? message
                    actionNoticeIsError = error != nil
                    Task { await load() }
                },
                onCancel: { overrideRequest = nil })
        }
        // 删除确认（写失败原文常驻上屏）
        .confirmationDialog(
            String(format: String(localized: "删除子智能体「%@」？"), pendingDelete?.name ?? ""),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible) {
            Button(String(localized: "删除"), role: .destructive) {
                guard let target = pendingDelete else { return }
                pendingDelete = nil
                Task {
                    let error = await session.connection.deleteSubagent(target)
                    actionNotice = error ?? String(format: String(localized: "已删除 %@"), target.name)
                    actionNoticeIsError = error != nil
                    await load()
                }
            }
            .accessibilityIdentifier("26-act-delete-confirm")
            Button(String(localized: "取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(String(localized: "删除会从桌面端移除该 md 文件，此操作无法撤销。"))
        }
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
                // 写面波次③：新建子智能体（user scope；web addNew 同位）
                Button {
                    editorRequest = SubagentEditorRequest(entry: nil)
                } label: {
                    Label(String(localized: "新建子智能体"), systemImage: "plus.circle.fill")
                        .font(T.font(13, .medium))
                        .foregroundColor(T.accentText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 2)
                }
                .accessibilityIdentifier("26-act-new")
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
                                    },
                                    onEdit: {
                                        if entry.source == "user" && !entry.readOnly {
                                            editorRequest = SubagentEditorRequest(entry: entry)
                                        }
                                    },
                                    onModelOverride: {
                                        if entry.source == "built-in" || entry.source == "plugin" {
                                            overrideRequest = SubagentOverrideRequest(
                                                agentKey: entry.source == "built-in"
                                                    ? (entry.name ?? entry.id)
                                                    : entry.id,
                                                isBuiltIn: entry.source == "built-in")
                                        }
                                    })
                                    .contextMenu {
                                        if entry.source == "user" && !entry.readOnly {
                                            Button(role: .destructive) {
                                                pendingDelete = entry
                                            } label: {
                                                Label(String(localized: "删除子智能体"), systemImage: "trash")
                                            }
                                        }
                                    }
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
        _ entry: SubagentEntry, busy: Bool, onToggle: @escaping (Bool) async -> Void,
        onEdit: @escaping () -> Void, onModelOverride: @escaping () -> Void
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
            // 写面波次⑤：built-in/plugin 行内模型覆盖入口（web SubagentModelOverrideControl
            // :608-805 同位；user 行走编辑器）
            if entry.source == "built-in" || entry.source == "plugin" {
                Button(action: onModelOverride) {
                    Text(String(localized: "模型覆盖"))
                        .font(T.font(11))
                        .foregroundColor(T.accentText)
                }
                .accessibilityIdentifier("26-model-override-\(entry.id)")
            }
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { onEdit() }
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

/// 编辑器呈现请求（.sheet(item:) 的 Identifiable 载荷——entry=nil=新建。
/// isPresented 版在 iOS 上第二次呈现会复用首次构建的 content 闭包（test33
/// 实测：编辑保存走成 create 分支），item 语义每次 identity 变化强制重建）。
/// 写面波次⑤：模型覆盖呈现请求（.sheet(item:) 载荷——agentKey 由 kind 分派）
struct SubagentOverrideRequest: Identifiable {
    let id = UUID()
    /// built-in = agentName；plugin = agentId
    let agentKey: String
    let isBuiltIn: Bool
}

struct SubagentEditorRequest: Identifiable {
    let id = UUID()
    /// nil = 新建（user scope）；非 nil = 编辑该行
    let entry: SubagentEntry?
}

// MARK: 写面波次③ · 子智能体编辑器 sheet（web SubagentForm 三键简化版）

/// name/description/systemPrompt 三键编辑；保存只回调不自 dismiss（§5-15：
/// 关闭由父级 onSave/onCancel 置 nil 单路径）。
struct SubagentEditSheet: View {
    let seed: SubagentEntry?
    let workspacePath: String?
    let workspaceIdentity: String?
    let onSave: (String, String, String) async -> Void
    let onCancel: () -> Void

    @State private var name: String
    @State private var description: String
    @State private var systemPrompt: String
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && !systemPrompt.trimmingCharacters(in: .whitespaces).isEmpty
    }

    init(
        seed: SubagentEntry?, workspacePath: String?, workspaceIdentity: String?,
        onSave: @escaping (String, String, String) async -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.seed = seed
        self.workspacePath = workspacePath
        self.workspaceIdentity = workspaceIdentity
        self.onSave = onSave
        self.onCancel = onCancel
        _name = State(initialValue: seed?.name ?? "")
        _description = State(initialValue: seed?.description ?? "")
        _systemPrompt = State(initialValue: seed?.systemPrompt ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text(String(localized: "名称"))) {
                    TextField(String(localized: "名称"), text: $name)
                        .accessibilityIdentifier("26-field-name")
                }
                Section(header: Text(String(localized: "描述"))) {
                    TextField(String(localized: "描述"), text: $description)
                        .accessibilityIdentifier("26-field-description")
                }
                Section(header: Text(String(localized: "系统提示词"))) {
                    TextField(String(localized: "系统提示词"), text: $systemPrompt, axis: .vertical)
                        .lineLimit(4...10)
                        .accessibilityIdentifier("26-field-prompt")
                }
            }
            .navigationTitle(seed == nil
                ? String(localized: "新建子智能体")
                : String(localized: "编辑子智能体"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "取消")) {
                        onCancel()
                        dismiss()
                    }
                    .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button(String(localized: "保存")) {
                            let trimmedName = name.trimmingCharacters(in: .whitespaces)
                            let trimmedDescription = description.trimmingCharacters(in: .whitespaces)
                            let trimmedPrompt = systemPrompt.trimmingCharacters(in: .whitespaces)
                            guard canSave else { return }
                            saving = true
                            Task {
                                // 只回调不自 dismiss（§5-15）：关闭由父级置 nil 单路径
                                await onSave(trimmedName, trimmedDescription, trimmedPrompt)
                                dismiss()
                            }
                        }
                        .disabled(!canSave)
                        .accessibilityIdentifier("26-act-save")
                    }
                }
            }
            .interactiveDismissDisabled(saving)
        }
    }
}

// MARK: 写面波次⑤ · 模型覆盖选择 sheet（web SubagentModelOverrideControl :608-805
// 三键简化：继承默认 / 模型清单（getView 投影）/ 模型 → 思考档二段）

struct SubagentOverrideSheet: View {
    let agentKey: String
    let isBuiltIn: Bool
    let store: any ConversationStore
    let session: AppSession
    let onDone: (String?, String) async -> Void
    let onCancel: () -> Void

    @State private var catalog: ModelSelectionInfo?
    @State private var pickedModel: String?
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        save(nil)
                    } label: {
                        HStack {
                            Text(String(localized: "继承默认"))
                            Spacer()
                            if pickedModel == nil && !saving {
                                Image(systemName: "checkmark")
                                    .foregroundColor(T.accentText)
                            }
                        }
                    }
                    .disabled(saving)
                    .accessibilityIdentifier("26-model-inherit")
                    if catalog == nil {
                        HStack {
                            ProgressView().scaleEffect(0.8)
                            Text(String(localized: "正在读取模型目录…"))
                                .font(T.font(12))
                                .foregroundColor(T.text3)
                        }
                    } else {
                        ForEach(catalog?.models ?? [], id: \.self) { model in
                            Button {
                                pickedModel = model
                            } label: {
                                HStack {
                                    Text(model)
                                        .foregroundColor(pickedModel == model ? T.accentText : T.text)
                                    Spacer()
                                    if pickedModel == model {
                                        Image(systemName: "checkmark")
                                            .foregroundColor(T.accentText)
                                    }
                                }
                            }
                            .accessibilityIdentifier("26-model-\(model)")
                            if pickedModel == model {
                                let thoughts = catalog?.thoughtByModel[model] ?? []
                                if thoughts.isEmpty {
                                    Button {
                                        save(ModelSelectionOverride(
                                            providerId: providerId(for: model),
                                            modelId: model, reasoningLevel: nil))
                                    } label: {
                                        Text(String(localized: "继承模型默认档"))
                                            .font(T.font(12))
                                            .foregroundColor(T.accentText)
                                    }
                                    .disabled(saving)
                                    .accessibilityIdentifier("26-thought-inherit-\(model)")
                                } else {
                                    ForEach(thoughts, id: \.self) { thought in
                                        Button {
                                            save(ModelSelectionOverride(
                                                providerId: providerId(for: model),
                                                modelId: model, reasoningLevel: thought))
                                        } label: {
                                            Text(thought)
                                                .font(T.font(12))
                                                .foregroundColor(T.accentText)
                                                .padding(.leading, 16)
                                        }
                                        .disabled(saving)
                                        .accessibilityIdentifier("26-thought-\(model)-\(thought)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "模型覆盖"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "取消")) { onCancel(); dismiss() }
                        .disabled(saving)
                }
            }
            .interactiveDismissDisabled(saving)
            .task {
                catalog = await store.modelSelectionView()
            }
        }
    }

    private func providerId(for model: String) -> String {
        catalog?.modelProviders[model] ?? catalog?.activeProviderId ?? "glm"
    }

    private func save(_ override: ModelSelectionOverride?) {
        saving = true
        Task {
            let error: String?
            if isBuiltIn {
                error = await session.connection.setBuiltInModelOverride(
                    agentName: agentKey, modelSelection: override)
            } else {
                error = await session.connection.setPluginAgentModelOverride(
                    agentId: agentKey, modelSelection: override)
            }
            saving = false
            let message = override == nil
                ? String(localized: "已恢复继承默认")
                : String(format: String(localized: "已设置 %@"), override?.pickerValue ?? "")
            await onDone(error, message)
            dismiss()
        }
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

    /// 数据测量（写面波次④前置；web useProjectManagement.ts:100-126 同参
    /// `measureProjectData(ids, hostId)`——双位置参数 wire=[ids, hostId]）。
    /// 服务缺失/回执缺 projects 键 → nil（UI 隐藏「同时删除数据」选项，web 同口径）。
    func measureProjectData(ids: [String], hostId: String) async -> (bytes: Int, projects: Int)? {
        guard isActive, !ids.isEmpty else { return nil }
        do {
            let result = try await call(
                "project", "measureProjectData",
                .json(.array([.array(ids.map { .string($0) }), .string(hostId)])))
            let rows = result.jsonValue?["projects"]?.arrayValue
            guard let rows, !rows.isEmpty else { return nil }
            var total = 0
            for row in rows {
                let d = row.objectValue
                let sessionBytes = d?["sessionBytes"]?.intValue ?? 0
                let checkpointBytes = d?["checkpointBytes"]?.intValue ?? 0
                let memoryBytes = d?["memoryBytes"]?.intValue ?? 0
                total += sessionBytes + checkpointBytes + memoryBytes
            }
            return (total, rows.count)
        } catch {
            return nil
        }
    }

    /// 彻底删除项目数据（写面波次④；web useProjectManagement.ts:156 同参
    /// `deleteProjectData(id, hostId)`——只接受已软删除项目）。拒绝三态映射
    /// （web :158-160）：running/referenced/failed 任一 >0 项目保持软删除可重试。
    /// 返回 nil=成功；非 nil=错误文案。
    func deleteProjectData(id: String, hostId: String) async -> String? {
        guard isActive else { return String(localized: "未连接桌面端") }
        do {
            let result = try await call(
                "project", "deleteProjectData",
                .json(.array([.string(id), .string(hostId)])))
            let d = result.jsonValue
            let running = d?["runningSessions"]?.intValue ?? 0
            let referenced = d?["referencedSessions"]?.intValue ?? 0
            let failed = d?["failed"]?.intValue ?? 0
            if running > 0 {
                return String(format: String(localized: "删除取消 · 有 %lld 个会话仍在运行（项目保持已移除，可稍后重试）"), running)
            }
            if referenced > 0 {
                return String(format: String(localized: "删除取消 · 有 %lld 个会话被其他项目引用（项目保持已移除）"), referenced)
            }
            if failed > 0 {
                return String(format: String(localized: "删除未完成 · %lld 项失败（项目保持已移除，可稍后重试）"), failed)
            }
            return nil
        } catch {
            return String(localized: "删除失败 · \(error.localizedDescription)")
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
    // 写面波次④：同时删除数据（measure nil=服务不可用隐藏选项，web 同口径）
    @State private var measuredBytes: Int?
    @State private var pendingPurge: ProjectEntry?

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
        // 移除确认（软删可撤销）。写面波次④：hostId=local 时呈现「同时删除数据」
        // 复选项（呈现时 measure 显示大小；nil=服务不可用隐藏——web 同口径）；
        // 勾选后移除成功转二段破坏性确认（pendingPurge）。
        .confirmationDialog(
            String(format: String(localized: "移除项目「%@」？"), pendingDelete?.name ?? ""),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible) {
            // 写面波次④：confirmationDialog 不支持复选控件——iOS 标准模式 =
            // 双 destructive 按钮（canDeleteData 时多一个「移除并删除数据」项，
            // 大小由呈现时 measure 提供；nil=服务不可用只给仅移除——web 同口径）
            if let bytes = measuredBytes {
                Button(String(format: String(localized: "移除并删除数据（%@）"), formatBytesLocal(bytes)),
                       role: .destructive) {
                    guard let target = pendingDelete else { return }
                    pendingDelete = nil
                    measuredBytes = bytes
                    pendingPurge = target
                }
                .accessibilityIdentifier("18-act-remove-with-data")
            }
            Button(String(localized: "仅移除"), role: .destructive) {
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
        .task(id: pendingDelete?.id) {
            measuredBytes = nil
            guard let target = pendingDelete, target.hostId == "local" else { return }
            measuredBytes = await session.connection.measureProjectData(
                ids: [target.id], hostId: target.hostId)?.bytes
        }
        // 彻底删除数据二段确认（破坏性不可恢复——web confirm.data.word「删除」
        // 强确认口径；拒绝三态由 deleteProjectData 文案上屏）
        .confirmationDialog(
            String(format: String(localized: "删除「%@」的数据？"), pendingPurge?.name ?? ""),
            isPresented: Binding(
                get: { pendingPurge != nil },
                set: { if !$0 { pendingPurge = nil } }),
            titleVisibility: .visible) {
            Button(String(format: String(localized: "删除 %@"), formatBytesLocal(measuredBytes ?? 0)),
                   role: .destructive) {
                guard let target = pendingPurge else { return }
                pendingPurge = nil
                Task {
                    let error = await session.connection.deleteProjectData(id: target.id, hostId: target.hostId)
                    actionNotice = error ?? String(format: String(localized: "已删除 %@ 及其数据"), target.name)
                    actionNoticeIsError = error != nil
                    await load()
                }
            }
            .accessibilityIdentifier("18-act-purge-confirm")
            Button(String(localized: "取消"), role: .cancel) { pendingPurge = nil }
        } message: {
            Text(String(localized: "将永久删除该项目在桌面端磁盘上的会话与数据，此操作无法撤销（项目本身保持已移除状态）。"))
        }
    }

    /// 字节人类可读（files-model sizeText 三端口径）
    private func formatBytesLocal(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024
        if kb < 1024 { return String(format: "%.0f KB", kb) }
        let mb = kb / 1024
        if mb < 1024 { return String(format: "%.1f MB", mb) }
        return String(format: "%.2f GB", mb / 1024)
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


// MARK: - P-013 task-groups 读面（iOS 同构，Android RemoteTaskGroups.kt 对位）

/// P-013 task-groups 结构（上游 task-groups/contract.ts:5-33 同参）。
struct RelayTaskGroup: Equatable {
    let id: String
    let title: String
    let color: String?
}

struct RelayTaskGroupMember: Equatable {
    let groupId: String
    let taskId: String
}

struct RelayTaskGroupStructure: Equatable {
    let groups: [RelayTaskGroup]
    let members: [RelayTaskGroupMember]
}

extension ZCodeServerConnection {
    /// task-groups.read（频道 `task-groups`【实证·上游仓 channels.ts:124】；
    /// workspace 信封）。失败原样上抛。
    func readTaskGroups(
        workspacePath: String?, workspaceIdentity: String?
    ) async throws -> RelayTaskGroupStructure {
        var builder = JSONObjectBuilder()
        if let workspacePath, !workspacePath.isEmpty { builder.set("workspacePath", workspacePath) }
        if let workspaceIdentity, !workspaceIdentity.isEmpty { builder.set("workspaceIdentity", workspaceIdentity) }
        let result = try await call("task-groups", "read", .json(.object(builder.fields)))
        let groupRows = result.jsonValue?["groups"]?.arrayValue ?? []
        let memberRows = result.jsonValue?["members"]?.arrayValue ?? []
        let groups = groupRows.compactMap { g -> RelayTaskGroup? in
            let d = g.objectValue
            guard let id = d?["id"]?.stringValue else { return nil }
            return RelayTaskGroup(
                id: id, title: d?["title"]?.stringValue ?? id,
                color: d?["color"]?.stringValue)
        }
        let members = memberRows.compactMap { m -> RelayTaskGroupMember? in
            let d = m.objectValue
            guard let groupId = d?["groupId"]?.stringValue,
                  let taskId = d?["taskId"]?.stringValue else { return nil }
            return RelayTaskGroupMember(groupId: groupId, taskId: taskId)
        }
        return RelayTaskGroupStructure(groups: groups, members: members)
    }
}

/// P-013 按分组视图纯函数（web TaskGroupedView 同口径）：taskId ↔ sessions 行 id
/// 对位；组间 groups[] 声明序；组内 updatedAt 降序；不在任何组的会话归尾区。
func taskGroupSections(
    conversations: [(id: String, title: String, updatedAt: Double)],
    groups: [RelayTaskGroup],
    members: [RelayTaskGroupMember],
    fallbackTitle: String
) -> [(title: String, items: [(id: String, title: String, updatedAt: Double)])] {
    let byId = Dictionary(conversations.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    var out: [(title: String, items: [(id: String, title: String, updatedAt: Double)])] = []
    var assigned = Set<String>()
    for group in groups {
        let groupTasks = members.filter { $0.groupId == group.id }
        let items = groupTasks.compactMap { m -> (id: String, title: String, updatedAt: Double)? in
            guard let conv = byId[m.taskId] else { return nil }
            return (id: conv.id, title: conv.title, updatedAt: conv.updatedAt)
        }.sorted { $0.updatedAt > $1.updatedAt }
        assigned.formUnion(groupTasks.map(\.taskId))
        if !items.isEmpty {
            out.append((title: group.title, items: items))
        }
    }
    let ungrouped = conversations
        .filter { !assigned.contains($0.id) }
        .sorted { $0.updatedAt > $1.updatedAt }
    if !ungrouped.isEmpty {
        out.append((title: fallbackTitle, items: ungrouped))
    }
    return out
}

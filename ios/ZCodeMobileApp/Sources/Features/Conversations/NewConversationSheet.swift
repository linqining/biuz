import SwiftUI

/// 屏 03 · 新建会话 Sheet（抓手 + sticky 头部 + 大输入区 + 执行端单选 + 建议提示词）
/// v3 纠偏：连接态恢复完整表单——标题/首条指令以 createSession+firstInput 一次下发
/// （桌面端立即开跑），空标题保持 draft 空会话 + 转正写；演示态行为不变。
/// 项 2 增量：顶部三层上下文胶囊（账号 / 机器 / 项目）——机器层复用设备列表
/// （云端沙盒 + 已配对 Mac），项目层来自 server-info workspaces + 历史会话去重，
/// 默认上次使用、带搜索（屏 03-P），机器 × 项目组合持久化、冷启动恢复。
struct NewConversationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.conversationStore) private var conversationStore
    @Environment(\.fileStore) private var fileStore
    @Environment(AppSession.self) private var session
    @Environment(AppSettingsModel.self) private var settings
    var onCreated: (Conversation) -> Void

    @State private var title = ""
    @State private var executor: ExecutorKind = .cloudSandbox
    @State private var machineID = DeviceOption.cloudSandbox.id
    @State private var machineName = DeviceOption.cloudSandbox.name
    @State private var projectPath = ""     // "" = 未绑定（纯对话）
    @State private var projectLabel = "未绑定"
    @State private var projects: [ProjectOption] = []
    @State private var isLoadingProjects = true
    @State private var showProjectPicker = false
    @State private var showFilePicker = false
    /// 新建失败提示（未连接态 Empty store 防御路径；正常路径恒 nil）
    @State private var createFailure: String?
    /// 连接态标记（G-013）：当前连接的桌面端即执行端，无信封级执行端路由——
    /// 连接态隐藏执行端单选卡、机器胶囊只读展示当前连接；演示态保留单选（Mock 语义）
    @State private var isRemote = false
    @FocusState private var inputFocused: Bool

    // 模型与思考等级（连接态可选）：getView 投影 + 会话前选择，随 firstInput.modelSelection 下发
    @State private var modelInfo: ModelSelectionInfo?
    @State private var selectedModel: String?
    @State private var selectedThought: String?
    @State private var thoughtOptions: [String] = []

    private var suggestions: [(String, String)] {
        [
            ("修一个 bug", "定位并修复登录超时问题，补回归测试"),
            ("写一段功能", "为会话列表增加搜索防抖与高亮"),
            ("重构一个模块", "把 SessionStore 拆成协议 + 内存/文件双实现"),
            ("解释这段代码", "阅读 TaskRunner.swift 并解释执行模型"),
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("新建会话").font(T.font(17, .bold)).foregroundColor(T.text)
                Spacer()
                Button {
                    if inputFocused {
                        inputFocused = false // 键盘态：取消 = 收起键盘
                    } else {
                        dismiss()
                    }
                } label: {
                    Text(inputFocused ? "收起键盘" : "取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("03-act-cancel")
            }
            .padding(.horizontal, T.sp4)

            ScrollView {
                VStack(alignment: .leading, spacing: T.sp4) {
                    contextCapsules
                    inputArea
                    contextChips
                    if !inputFocused, !isRemote {
                        executorSection
                    }
                    settingsSection
                    if !inputFocused {
                        suggestionSection
                    }
                }
                .padding(T.sp4)
            }
            .scrollDismissesKeyboard(.interactively)

            // §0.4 防御兜底：未连接态 Empty store 的 createConversation 返回空 id（写面
            // 如实失败）——如实提示，不产生 onCreated → openChat 假成功导航
            if let createFailure {
                Text(createFailure)
                    .font(T.font(11))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("03-create-fail")
            }
            PrimaryButton(title: "开始任务", identifier: "03-submit-start") {
                Task {
                    // 项目层选择随 createSession 的 workspaceId 下发（directory 参数承载）；
                    // 连接态模型/思考等级随 firstInput.modelSelection 下发（会话前选择）
                    let selection = pendingModelSelection()
                    let conversation = await conversationStore.createConversation(
                        title: title, directory: projectPath, executor: executor,
                        modelSelection: selection)
                    guard !conversation.id.isEmpty else {
                        createFailure = String(localized: "未连接桌面端 · 连接后再新建会话")
                        UINotificationFeedbackGenerator().notificationOccurred(.error)
                        return
                    }
                    createFailure = nil
                    NewSessionContextStore.save(
                        NewSessionContext(machineID: machineID, projectPath: projectPath))
                    onCreated(conversation)
                }
            }
            .padding(.horizontal, T.sp4)
            .padding(.vertical, T.sp2)
            .background(T.bgElevated)
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .task {
            await restoreContext()
        }
        .sheet(isPresented: $showProjectPicker) {
            ProjectPickerSheet(
                projects: $projects,
                selectedPath: projectPath,
                isLoading: $isLoadingProjects,
                onPick: { option in
                    showProjectPicker = false
                    applyProject(option)
                })
        }
        .sheet(isPresented: $showFilePicker) {
            ReferenceFilePickerSheet(store: fileStore) { node in
                showFilePicker = false
                // G-024 验收①：选文件 → 输入区出现 @路径，随标题（firstInput）一并下发
                let reference = " @\(node.path)"
                if !title.contains(reference) {
                    title += reference
                }
                inputFocused = true
            }
        }
    }

    // MARK: 三层上下文胶囊（账号 / 机器 / 项目）

    private var contextCapsules: some View {
        HStack(spacing: T.sp1) {
            accountCapsule
            machineCapsule
            projectCapsule
        }
    }

    /// 账号层：BiuZ 账号唯一 Z.ai（BigModel 为高级入口），胶囊常显当前账号、无可切换项
    private var accountCapsule: some View {
        Menu {
            if let userInfo = session.oauthUserInfo {
                Text("\(userInfo.displayName) · @\(userInfo.username)")
            } else if session.isDemo {
                Text("未登录 · 演示模式")
            } else {
                Text("未登录")
            }
        } label: {
            // H13 连带：兜底字样「演示」仅 -ZCodeDemoData（E2E 演示开关）保留，
            // 正式路径兜底改「未登录」（未连接 ≠ 演示）
            ContextCapsule(
                icon: "person.crop.circle",
                text: session.oauthUserInfo?.displayName
                    ?? (session.isOAuthLoggedIn ? "Z.ai 账号" : (session.isDemo ? "演示" : "未登录")),
                identifier: "03-pill-account")
        }
    }

    /// 机器层：云端沙盒 + 已配对 Mac（数据复用设备列表）；切换联动执行端与该项目上次目录。
    /// 连接态只读（当前连接的桌面端即执行端，G-013）。
    private var machineCapsule: some View {
        Group {
            if isRemote {
                ContextCapsule(
                    icon: "laptopcomputer",
                    text: machineName,
                    identifier: "03-pill-machine",
                    tint: T.accentText)
            } else {
                Menu {
                    ForEach(DeviceDirectory.machines()) { option in
                        Button {
                            selectMachine(option)
                        } label: {
                            Label {
                                Text(option.name)
                            } icon: {
                                Image(systemName: option.kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer")
                            }
                        }
                        .accessibilityIdentifier(option.kind == .cloudSandbox
                            ? "03-pill-machine-cloud" : "03-pill-machine-mac")
                    }
                } label: {
                    ContextCapsule(
                        icon: executor == .cloudSandbox ? "cloud.fill" : "laptopcomputer",
                        text: machineName,
                        identifier: "03-pill-machine")
                }
            }
        }
    }

    /// 项目层：server-info workspaces + 历史会话去重，默认上次使用；点击进屏 03-P 带搜索
    private var projectCapsule: some View {
        Button {
            showProjectPicker = true
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "folder")
                    .font(.system(size: 11))
                    .foregroundColor(T.text3)
                Text(projectLabel)
                    .font(T.font(11.5, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundColor(T.text3)
            }
            .padding(.horizontal, T.sp2)
            .frame(minHeight: 44)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        .accessibilityIdentifier("03-pill-project")
    }

    private func selectMachine(_ option: DeviceOption) {
        executor = option.kind
        machineID = option.id
        machineName = option.name
        // 机器 × 项目组合：切机器回填该项目上次使用的目录
        if let lastPath = NewSessionContextStore.lastProject(forMachine: option.id) {
            applyProject(projects.first { $0.path == lastPath }
                ?? ProjectOption(path: lastPath, label: "", lastUsedAt: nil))
        }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func applyProject(_ option: ProjectOption?) {
        projectPath = option?.path ?? ""
        projectLabel = option?.displayName ?? "未绑定"
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// 冷启动恢复上次上下文（机器 × 项目组合），并拉取项目清单
    private func restoreContext() async {
        isRemote = conversationStore.isReadOnly
        // G-013：连接态执行端 = 当前连接的桌面端，机器胶囊只读展示
        if isRemote {
            machineID = session.savedServer?.id ?? "connected"
            machineName = session.savedServer?.displayName ?? String(localized: "我的 Mac")
            executor = .pairedMac
        }
        // 连接态拉模型选择视图（套餐分组 + 当前绑定），默认跟随桌面端当前值
        if isRemote {
            modelInfo = await conversationStore.modelSelectionView()
            selectedModel = modelInfo?.activeModel ?? modelInfo?.models.first
            selectedThought = modelInfo?.activeThoughtLevel
            await reloadThoughtOptions()
        }
        let last = NewSessionContextStore.loadLast()
        let machines = DeviceDirectory.machines()
        if let match = machines.first(where: { $0.id == last.machineID }) ?? machines.first {
            machineID = match.id
            machineName = match.name
            executor = match.kind
        }
        projectPath = last.projectPath
        projectLabel = Self.projectLabel(for: last.projectPath, in: projects)
        projects = await ProjectDirectory.projects(store: conversationStore, session: session)
        isLoadingProjects = false
        // 项目清单到位后修正展示名（上次项目可能来自历史簿）
        projectLabel = Self.projectLabel(for: projectPath, in: projects)
    }

    private static func projectLabel(for path: String, in projects: [ProjectOption]) -> String {
        guard !path.isEmpty else { return "未绑定" }
        if let match = projects.first(where: { $0.path == path }) {
            return match.displayName
        }
        return path.split(separator: "/").last.map(String.init) ?? path
    }

    private var inputArea: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            TextField("描述你要做的事…", text: $title, axis: .vertical)
                .font(T.font(16))
                .foregroundColor(T.text)
                .lineLimit(2...5)
                .focused($inputFocused)
                .padding(T.sp3)
                .frame(minHeight: 88, alignment: .topLeading)
                .background(T.bgInput)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .accessibilityIdentifier("03-input-title")
        }
    }

    /// 上下文 chips（G-024）：「引用文件 @」接文件选择器（FileStore 数据源）真实可用；
    /// 「仓库/语音」无实现置视觉禁用态（不可点）——不再呈现可点无效的假交互。
    /// 「附件」保持置灰（P1-1 设计稿 1.1：新建会话尚未有目标会话、无法建上传事务，
    /// 仅 hint 文案改为「进入会话后可用」——发送侧附件入口在会话页 composer）
    private var contextChips: some View {
        FlexibleFlow(spacing: T.sp2) {
            Button {
                showFilePicker = true
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "doc.text").font(.system(size: 11))
                    Text("引用文件 @").font(T.font(12.5, .medium))
                }
                .foregroundColor(T.text2)
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
            }
            .accessibilityIdentifier("03-chip-atfile")

            disabledChip("附件", icon: "paperclip", id: "03-chip-attach",
                         hint: String(localized: "进入会话后可用"))
            // HIDDEN(对齐修复): 新建会话「仓库/语音」chip 隐藏（无对应能力实装，置灰 chip
            // 仍构成假入口——设计稿 H4）· 恢复条件：对应能力实装。
            // E2E 兼容：-ZCodeDemoData 演示开关下保留（Matrix 布局断言 03-chip-repo/voice 在场）
            if AppSession.isDemoDataEnabled {
                disabledChip("仓库", icon: "shippingbox", id: "03-chip-repo")
                disabledChip("语音", icon: "mic", id: "03-chip-voice")
            }
        }
    }

    private func disabledChip(_ text: String, icon: String, id: String,
                              hint: String = String(localized: "即将支持")) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon).font(.system(size: 11))
            Text(text).font(T.font(12.5, .medium))
        }
        .foregroundColor(T.text3.opacity(0.55))
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 44)
        .background(T.bgInput.opacity(0.6))
        .clipShape(Capsule())
        .accessibilityIdentifier(id)
        .accessibilityHint(hint)
    }

    private var executorSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("执行端").font(T.font(13, .semibold)).foregroundColor(T.text3)
            ForEach(ExecutorKind.allCases) { kind in
                Button {
                    let option = kind == .cloudSandbox
                        ? DeviceOption.cloudSandbox
                        : (DeviceDirectory.machines().first { $0.kind == .pairedMac }
                           ?? DeviceOption(id: "mac", name: "我的 Mac", kind: .pairedMac))
                    selectMachine(option)
                } label: {
                    HStack(spacing: T.sp3) {
                        Image(systemName: kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer.and.macbook")
                            .font(.system(size: 16))
                            .foregroundColor(executor == kind ? T.accentText : T.text3)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(kind.label).font(T.font(14, .semibold)).foregroundColor(T.text)
                            Text(kind.subtitle).font(T.font(11.5)).foregroundColor(T.text3)
                        }
                        Spacer()
                        Image(systemName: executor == kind ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 18))
                            .foregroundColor(executor == kind ? T.accent : T.borderStrong)
                    }
                    .padding(T.sp3)
                    .background(executor == kind ? T.accentDim : T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(executor == kind ? T.accent.opacity(0.5) : T.border, lineWidth: 1))
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("03-exec-\(kind == .cloudSandbox ? "cloud" : "mac")")
            }
        }
    }

    private var settingsSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("设置").font(T.font(13, .semibold)).foregroundColor(T.text3)
            VStack(spacing: 0) {
                Button {
                    showProjectPicker = true
                } label: {
                    row(label: "工作目录",
                        value: projectPath.isEmpty ? "未绑定" : projectPath,
                        icon: "folder")
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("03-row-directory")
                Divider().overlay(T.border).padding(.leading, 44)
                if isRemote, let info = modelInfo, !info.models.isEmpty {
                    modelSelectionRow
                    Divider().overlay(T.border).padding(.leading, 44)
                    thoughtSelectionRow
                } else {
                    row(label: "模型与思考等级",
                        value: "\(settings.value.model) · \(settings.value.thoughtLevel.label)",
                        icon: "cpu", trailing: "info.circle")
                }
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
        }
    }

    private func row(label: String, value: String, icon: String,
                     trailing: String = "chevron.up.chevron.down") -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 13))
                .foregroundColor(T.text3)
                .frame(width: 30)
            Text(label).font(T.font(14.5)).foregroundColor(T.text)
            Spacer()
            Text(value)
                .font(T.mono(11.5))
                .foregroundColor(T.text3)
                .lineLimit(1)
                .truncationMode(.middle)
            // 连接态：可写面 chevron（switchModelConfig 链路已实证，新建会话经
            // firstInput.modelSelection 会话前选择）；演示态保持 info.circle 只读口径
            Image(systemName: trailing)
                .font(.system(size: 11, weight: trailing == "info.circle" ? .regular : .semibold))
                .foregroundColor(T.text3.opacity(0.7))
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    // MARK: 模型 / 思考等级选择（连接态；数据源 model-selection.getView）

    /// 模型行：按套餐分节（个人套餐/体验套餐），选中项打勾；默认跟随桌面端当前绑定
    private var modelSelectionRow: some View {
        Menu {
            if let info = modelInfo {
                if info.planGroups.isEmpty {
                    ForEach(info.models, id: \.self) { model in
                        modelPickerRow(model)
                    }
                } else {
                    ForEach(info.planGroups) { group in
                        Section(group.plan) {
                            ForEach(group.models, id: \.self) { model in
                                modelPickerRow(model)
                            }
                        }
                    }
                }
            }
        } label: {
            row(label: "模型", value: selectedModel ?? "--", icon: "cpu")
        }
        .accessibilityIdentifier("03-row-model")
    }

    private func modelPickerRow(_ model: String) -> some View {
        Button {
            selectedModel = model
            selectedThought = nil
            Task { await reloadThoughtOptions() }
            UISelectionFeedbackGenerator().selectionChanged()
        } label: {
            if model == selectedModel {
                Label(model, systemImage: "checkmark")
            } else {
                Text(model)
            }
        }
    }

    /// 思考档行：词表按当前模型查询（workspace-config），缺席退化为 getView 词表 →
    /// 静态梯（web 端别名表归纳；不支持的档位由桌面端校验拒绝）
    private var thoughtSelectionRow: some View {
        Menu {
            ForEach(displayedThoughtLevels, id: \.self) { level in
                Button {
                    selectedThought = level
                    UISelectionFeedbackGenerator().selectionChanged()
                } label: {
                    if level == selectedThought {
                        Label(level, systemImage: "checkmark")
                    } else {
                        Text(level)
                    }
                }
            }
        } label: {
            row(label: "思考等级",
                value: selectedThought?.isEmpty == false ? selectedThought! : "默认",
                icon: "brain")
        }
        .accessibilityIdentifier("03-row-thought")
    }

    private var displayedThoughtLevels: [String] {
        if !thoughtOptions.isEmpty { return thoughtOptions }
        if let levels = modelInfo?.thoughtLevels, !levels.isEmpty { return levels }
        return ["off", "minimal", "low", "medium", "high", "max"]
    }

    private func reloadThoughtOptions() async {
        guard let model = selectedModel else {
            thoughtOptions = []
            return
        }
        thoughtOptions = await conversationStore.thoughtLevels(for: model)
        // 未显式选择时回填桌面端当前档（仅当该档位在词表内）
        if selectedThought == nil, let active = modelInfo?.activeThoughtLevel,
           thoughtOptions.contains(active) {
            selectedThought = active
        }
    }

    /// 会话前选择组装：未选模型返回 nil（桌面端以默认模型开跑，不阻断新建）
    private func pendingModelSelection() -> NewSessionModelSelection? {
        guard isRemote, let model = selectedModel else { return nil }
        let provider = modelInfo?.modelProviders[model] ?? ""
        let thought = selectedThought ?? modelInfo?.activeThoughtLevel ?? ""
        return NewSessionModelSelection(providerId: provider, modelId: model, reasoningLevel: thought)
    }

    private var suggestionSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("试试这些").font(T.font(13, .semibold)).foregroundColor(T.text3)
            FlexibleFlow(spacing: T.sp2) {
                ForEach(suggestions, id: \.0) { suggestion in
                    Button {
                        title = suggestion.1
                        inputFocused = true
                    } label: {
                        Text(suggestion.0)
                            .font(T.font(12.5, .medium))
                            .foregroundColor(T.accentText)
                            .padding(.horizontal, T.sp3)
                            .frame(minHeight: 44)
                            .background(T.accentDim)
                            .clipShape(Capsule())
                    }
                    .accessibilityIdentifier("03-chip-suggest")
                }
            }
        }
    }
}

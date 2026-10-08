import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

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
    /// 提交在途守卫（用户报障 2026-10-08「相同请求没有做去重拦截」）：createSession
    /// 等待窗内再次点按「开始任务」不再发起第二次创建（store 级在途合并之外的首道拦截）
    @State private var isSubmitting = false
    /// 连接态标记（G-013）：当前连接的桌面端即执行端，无信封级执行端路由——
    /// 连接态隐藏执行端单选卡、机器胶囊只读展示当前连接；演示态保留单选（Mock 语义）
    @State private var isRemote = false
    @FocusState private var inputFocused: Bool

    // 模型与思考等级（连接态可选）：getView 投影 + 会话前选择，随 firstInput.modelSelection 下发
    @State private var modelInfo: ModelSelectionInfo?
    @State private var selectedModel: String?
    @State private var selectedThought: String?
    @State private var thoughtOptions: [String] = []
    // 可选性修复（用户多次报障「模型和思考等级不能选是 bug」）：getView 首击失败
    // 不再退化只读兜底——行内点按重取 + 行下提示，成功即开面板
    @State private var isLoadingModelInfo = false
    @State private var modelLoadHint: String?

    // 新建会话附件（用户 2026-10-07 第五次报障「新建会话附件不能使用」根因：
    // 附件 chip 是 disabledChip 占位从未接通）。暂存待传文件——上传事务需要
    // sessionId，故开始任务时以 draft 会话创建（不携 firstInput，桌面不先跑），
    // 暂存文件+草稿文本经交接箱注入会话 composer 上传管线（与既有三通道
    // add→上传→sendText 携带完全同路）
    @State private var stagedFiles: [StagedNewAttachment] = []
    @State private var showAttachmentSource = false
    @State private var pendingAttachmentSource: AttachmentSource?
    @State private var showPhotoPicker = false
    @State private var showCamera = false
    @State private var showFileImporter = false
    @State private var photoPickerItems: [PhotosPickerItem] = []
    // slash 建议菜单（/goal /plan /workflow /compact + workspace-config 合并；
    // sheet 只做发现与预填——三客户端意图由会话页 send() 既有拦截执行，零重复语义）
    @State private var slashCommands: [WorkspaceConfigInfo.SlashCommand] = []
    // 模型/思考自绘面板（ComposerOptionSheet 统一语言，用户 2026-10-07「样式一致」）
    @State private var composerSheet: ComposerSheetKind?

    /// slash 建议匹配：输入以 "/" 起头且聚焦时呈现
    private var slashMatches: [WorkspaceConfigInfo.SlashCommand] {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/"), inputFocused else { return [] }
        let prefix = trimmed.dropFirst().split(separator: " ", maxSplits: 1).first.map(String.init)?.lowercased() ?? ""
        guard !prefix.contains("\n") else { return [] }
        return slashCommands.filter { $0.name.lowercased().hasPrefix(prefix) }
    }

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
                Task { await submit() }
            }
            .disabled(isSubmitting)
            .opacity(isSubmitting ? 0.7 : 1)
            .overlay(alignment: .trailing) {
                if isSubmitting {
                    ProgressView().tint(T.onAccent).padding(.trailing, T.sp4)
                        .accessibilityIdentifier("03-submit-progress")
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
        // 附件三来源（与会话页 composer 同一套 AttachmentSourceSheet；选定即暂存，
        // 开始任务 draft 创建后经交接箱进入上传管线）
        .sheet(isPresented: $showAttachmentSource, onDismiss: {
            switch pendingAttachmentSource {
            case .camera: showCamera = true
            case .photos: showPhotoPicker = true
            case .files: showFileImporter = true
            case nil: break
            }
            pendingAttachmentSource = nil
        }) {
            AttachmentSourceSheet { source in
                pendingAttachmentSource = source
                showAttachmentSource = false
            }
            .presentationDetents([.height(348)])
            .presentationDragIndicator(.hidden)
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoPickerItems, matching: .images)
        .onChange(of: photoPickerItems) { _, newItems in
            guard !newItems.isEmpty else { return }
            photoPickerItems = []
            Task { await addPhotoPickerItems(newItems) }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true) { result in
            importFiles(result)
        }
        .sheet(isPresented: $showCamera) {
            CameraPicker { image in
                addCameraImage(image)
            }
            .ignoresSafeArea()
        }
        // 模型/思考自绘面板（ComposerOptionSheet；medium/large——模型清单可能超一屏）
        .sheet(item: $composerSheet) { kind in
            ComposerOptionSheet(
                title: kind.title,
                footer: sheetFooter(kind),
                options: sheetOptions(kind)) { option in
                composerSheet = nil
                applySheetPick(kind, option)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.hidden)
            .presentationBackground(T.bgElevated)
        }
    }

    // MARK: 提交分流（附件 draft / slash 意图预填 / 常规 firstInput）

    private func submit() async {
        // 去重拦截：在途时忽略再次点按（等待窗内连点曾产生多份桌面会话）
        guard !isSubmitting else { return }
        isSubmitting = true
        defer { isSubmitting = false }
        // 项目层选择随 createSession 的 workspaceId 下发（directory 参数承载）；
        // 连接态模型/思考等级随 firstInput.modelSelection 下发（会话前选择）
        let selection = pendingModelSelection()

        // ① 附件随新会话：draft 创建（桌面不先跑——文件必须先于任务就位），
        //    暂存文件+文本经交接箱注入会话 composer；模型选择无 firstInput 通道，
        //    经交接箱随会话首条 sendText.modelSelection 下发（v1.18 限制修复）
        if !stagedFiles.isEmpty {
            let conversation = await conversationStore.createConversation(
                title: "", directory: projectPath, executor: executor, modelSelection: nil)
            guard guardCreated(conversation) else { return }
            NewConversationHandoffBox.deposit(
                NewConversationHandoff(
                    draftText: title.trimmingCharacters(in: .whitespacesAndNewlines),
                    attachments: stagedFiles.map { ($0.name, $0.mediaType, $0.data) },
                    modelSelection: selection),
                for: conversation.id)
            finishCreated(conversation)
            return
        }

        // ② 客户端拦截意图（/goal /plan /compact——web/会话页同构客户端语义，
        //    桌面不解析）：draft 创建 + 原文预填 composer，发送时由会话页
        //    parseSlashIntent 既有拦截执行（成功反馈/失败回填全在既有链路）
        if isRemote, ChatViewModel.parseSlashIntent(title) != nil {
            let conversation = await conversationStore.createConversation(
                title: "", directory: projectPath, executor: executor, modelSelection: nil)
            guard guardCreated(conversation) else { return }
            NewConversationHandoffBox.deposit(
                NewConversationHandoff(draftText: title.trimmingCharacters(in: .whitespacesAndNewlines),
                                       attachments: [],
                                       modelSelection: selection),
                for: conversation.id)
            finishCreated(conversation)
            return
        }

        // ③ 常规路径：firstInput 携带文本（未命中拦截意图的 /xxx 原文直发由桌面解释）
        let conversation = await conversationStore.createConversation(
            title: title, directory: projectPath, executor: executor,
            modelSelection: selection)
        guard guardCreated(conversation) else { return }
        finishCreated(conversation)
    }

    /// 创建失败如实透出（真实拒收原因优先——上游 schema strict，载荷任一键不合形
    /// 即整条拒收；禁止假成功导航）
    private func guardCreated(_ conversation: Conversation) -> Bool {
        if !conversation.id.isEmpty {
            createFailure = nil
            return true
        }
        Task { await reportCreateFailure() }
        return false
    }

    private func reportCreateFailure() async {
        let detail = await conversationStore.lastCreateFailureText()
        createFailure = detail.isEmpty
            ? String(localized: "未连接桌面端 · 连接后再新建会话")
            : String(localized: "创建失败 · \(detail)")
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    private func finishCreated(_ conversation: Conversation) {
        NewSessionContextStore.save(
            NewSessionContext(machineID: machineID, projectPath: projectPath))
        onCreated(conversation)
    }

    // MARK: 附件暂存（与会话页同源转换；上传在会话内建事务）

    private func addPhotoPickerItems(_ items: [PhotosPickerItem]) async {
        for item in items {
            let contentType = item.supportedContentTypes.first
            let ext = contentType?.preferredFilenameExtension ?? "jpg"
            let mediaType = contentType?.preferredMIMEType ?? "image/jpeg"
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            stagedFiles.append(StagedNewAttachment(
                name: "IMG_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)",
                mediaType: mediaType, data: data))
        }
    }

    private func addCameraImage(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.9) else { return }
        stagedFiles.append(StagedNewAttachment(
            name: "IMG_\(Int(Date().timeIntervalSince1970 * 1000)).jpg",
            mediaType: "image/jpeg", data: data))
    }

    private func importFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { continue }
            stagedFiles.append(StagedNewAttachment(
                name: url.lastPathComponent,
                mediaType: AttachmentUploadService.mediaType(forFileExtension: url.pathExtension),
                data: data))
        }
    }

    // MARK: 模型/思考面板装配（NewConversationSheet 侧状态源）

    private func sheetOptions(_ kind: ComposerSheetKind) -> [ComposerOptionItem] {
        switch kind {
        case .model:
            // 清单未就绪（getView 失败/为空）给「重新获取」项——面板不空白死路
            let reloadItem = ComposerOptionItem(
                id: "reload-model", title: String(localized: "重新获取模型列表"),
                detail: "", icon: "arrow.clockwise")
            guard let info = modelInfo, !info.models.isEmpty else { return [reloadItem] }
            let items: [ComposerOptionItem]
            if info.planGroups.isEmpty {
                items = info.models.map { model in
                    ComposerOptionItem(id: model, title: model, detail: "", icon: "cpu",
                                       selected: model == selectedModel)
                }
            } else {
                items = info.planGroups.flatMap { group in
                    group.models.map { model in
                        ComposerOptionItem(
                            id: "\(group.plan)|\(model)",
                            title: model,
                            detail: "",
                            icon: "cpu",
                            selected: model == selectedModel,
                            section: group.plan,
                            payload: model)
                    }
                }
            }
            return items.isEmpty ? [reloadItem] : items
        case .thought:
            return displayedThoughtLevels.map { level in
                ComposerOptionItem(id: level, title: level, detail: "", icon: "brain",
                                   selected: level == selectedThought)
            }
        default:
            return []
        }
    }

    private func applySheetPick(_ kind: ComposerSheetKind, _ option: ComposerOptionItem) {
        switch kind {
        case .model:
            // 「重新获取」项：重拉 getView，成功重开面板（pick 闭包已收起本面板）
            if option.id == "reload-model" {
                Task {
                    modelInfo = await conversationStore.modelSelectionView()
                    if modelInfo?.models.isEmpty == false {
                        modelLoadHint = nil
                        try? await Task.sleep(nanoseconds: 200_000_000)
                        composerSheet = .model
                    } else {
                        modelLoadHint = String(localized: "模型列表获取失败 · 桌面端未回执，点按重试")
                    }
                }
                return
            }
            selectedModel = option.payload ?? option.id
            selectedThought = nil
            Task { await reloadThoughtOptions() }
            UISelectionFeedbackGenerator().selectionChanged()
        case .thought:
            selectedThought = option.payload ?? option.id
            UISelectionFeedbackGenerator().selectionChanged()
        default:
            break
        }
    }

    /// 面板页脚：模型清单未就绪时思考档为静态梯——所选档位能否随会话下发取决于
    /// provider 映射，如实标注（不静默假可选）
    private func sheetFooter(_ kind: ComposerSheetKind) -> String? {
        guard kind == .thought else { return nil }
        if let providers = modelInfo?.modelProviders, !providers.isEmpty { return nil }
        return String(localized: "模型列表未就绪 · 所选档位以桌面端当前默认为准")
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

    /// 机器层候选（用户裁决 2026-10-07「新建会话要隐藏云端沙盒」）：云端沙盒档隐藏
    /// （BiuZ 无云端会话数据源，选中创建的会话落桌面看不见的沙盒区——H7 同口径）；
    /// -ZCodeDemoData 演示开关保留（E2E Matrix 断言 03-pill-machine-cloud 依赖）
    private var availableMachines: [DeviceOption] {
        DeviceDirectory.machines().filter {
            AppSession.isDemoDataEnabled || $0.kind != .cloudSandbox
        }
    }

    /// 机器层：已配对 Mac（连接态只读展示当前连接）；切换联动执行端与该项目上次目录。
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
                    ForEach(availableMachines) { option in
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
            // 首击失败静默补拉一次（sheet 停留期内桌面恢复常见——用户无需感知重试；
            // 仍失败不阻断新建，行内点按可再取）
            if modelInfo == nil {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if !Task.isCancelled, modelInfo == nil {
                    modelInfo = await conversationStore.modelSelectionView()
                    if let info = modelInfo {
                        selectedModel = selectedModel ?? info.activeModel ?? info.models.first
                        if selectedThought == nil {
                            selectedThought = info.activeThoughtLevel
                            await reloadThoughtOptions()
                        }
                    }
                }
            }
            // slash 建议数据源（内建 + workspace-config 合并，与会话页 slashMenuCommands 同构）
            let config = await conversationStore.workspaceConfig()
            slashCommands = ChatViewModel.mergedSlashCommands(config: config)
        }
        let last = NewSessionContextStore.loadLast()
        // 机器层恢复（用户报障 2026-10-07「连接态胶囊仍显示云端沙盒」根因：上次上下文
        // 恢复曾把 G-013 已修正的连接态执行端又覆盖回 cloudSandbox——连接态机器只读，
        // 跳过恢复；云端沙盒档隐藏见 availableMachines）
        if !isRemote {
            let machines = availableMachines
            // 永未配对（列表空）也回退「我的 Mac」档——云端沙盒档已隐藏，不残留旧默认
            let match = machines.first(where: { $0.id == last.machineID }) ?? machines.first
                ?? DeviceOption(id: "mac", name: String(localized: "我的 Mac"), kind: .pairedMac)
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
                // 上限 10 行（真机报障「输入文字多了看不到全部，高度固定」）
                .lineLimit(2...10)
                .focused($inputFocused)
                .padding(T.sp3)
                .frame(minHeight: 88, alignment: .topLeading)
                .background(T.bgInput)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .accessibilityIdentifier("03-input-title")
            // 暂存附件条（附件 chip 选定后；会话内上传）
            stagedFilesRow
            // slash 建议（输入 "/" 触发，与会话页 composer 同构：选中插入 "/name "，
            // 发送侧三客户端意图由会话页既有拦截执行）
            if !slashMatches.isEmpty {
                VStack(spacing: 0) {
                    ForEach(slashMatches) { command in
                        Button {
                            title = "/\(command.name) "
                            UISelectionFeedbackGenerator().selectionChanged()
                        } label: {
                            HStack(spacing: T.sp2) {
                                Text("/\(command.name)")
                                    .font(T.mono(12.5, .semibold))
                                    .foregroundColor(T.accentText)
                                Text(command.description)
                                    .font(T.font(11))
                                    .foregroundColor(T.text3)
                                    .lineLimit(1)
                                Spacer()
                            }
                            .padding(.horizontal, T.sp3)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("03-slash-\(command.name)")
                    }
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
                .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
            }
        }
    }

    /// 上下文 chips（G-024）：「引用文件 @」接文件选择器（FileStore 数据源）真实可用；
    /// 「附件」接 AttachmentSourceSheet 三来源（拍照/图库/文件）真实暂存——
    /// 上传事务需 sessionId，开始任务 draft 创建后经交接箱进入会话上传管线
    /// （用户 2026-10-07 第五次报障「新建会话附件不能使用」修复：disabledChip
    /// 占位改真通道）。「仓库/语音」无实现置视觉禁用态（不可点）。
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

            Button {
                showAttachmentSource = true
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "paperclip").font(.system(size: 11))
                    Text("附件").font(T.font(12.5, .medium))
                    if !stagedFiles.isEmpty {
                        Text("\(stagedFiles.count)").font(T.mono(10.5, .semibold))
                            .foregroundColor(T.onAccent)
                            .padding(.horizontal, 5)
                            .background(T.accent)
                            .clipShape(Capsule())
                    }
                }
                .foregroundColor(T.text2)
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
            }
            .accessibilityIdentifier("03-chip-attach")
            // HIDDEN(对齐修复): 新建会话「仓库/语音」chip 隐藏（无对应能力实装，置灰 chip
            // 仍构成假入口——设计稿 H4）· 恢复条件：对应能力实装。
            // E2E 兼容：-ZCodeDemoData 演示开关下保留（Matrix 布局断言 03-chip-repo/voice 在场）
            if AppSession.isDemoDataEnabled {
                disabledChip("仓库", icon: "shippingbox", id: "03-chip-repo")
                disabledChip("语音", icon: "mic", id: "03-chip-voice")
            }
        }
    }

    /// 暂存附件条（输入区与 chips 之间；✕ 移除，上传进度在会话页缩略卡呈现）
    @ViewBuilder
    private var stagedFilesRow: some View {
        if !stagedFiles.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: T.sp2) {
                    ForEach(stagedFiles) { file in
                        HStack(spacing: T.sp1) {
                            Image(systemName: file.mediaType.hasPrefix("image/") ? "photo" : "doc")
                                .font(.system(size: 11))
                                .foregroundColor(T.accentText)
                            Text(file.name)
                                .font(T.mono(10.5))
                                .foregroundColor(T.text2)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .frame(maxWidth: 120)
                            Button {
                                stagedFiles.removeAll { $0.id == file.id }
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 9, weight: .semibold))
                                    .foregroundColor(T.text3)
                                    .frame(width: 24, height: 24)
                                    .background(T.bgInput)
                                    .clipShape(Circle())
                            }
                        }
                        .padding(.leading, T.sp2)
                        .frame(minHeight: 36)
                        .background(T.bgCard)
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(T.border, lineWidth: 1))
                    }
                }
                .padding(.horizontal, T.sp1)
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
            // 云端沙盒档隐藏（availableMachines 同口径；演示态保留供 E2E）
            ForEach(displayedExecutorKinds) { kind in
                Button {
                    let option = kind == .cloudSandbox
                        ? DeviceOption.cloudSandbox
                        : (availableMachines.first { $0.kind == .pairedMac }
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

    /// 执行端单选档位（云端沙盒隐藏见 availableMachines；演示态保留全量供 E2E）
    private var displayedExecutorKinds: [ExecutorKind] {
        ExecutorKind.allCases.filter {
            AppSession.isDemoDataEnabled || $0 != .cloudSandbox
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
                if isRemote {
                    // 连接态恒可选（用户多次报障「模型和思考等级不能选是 bug，不是要你做成
                    // 不能选」）：getView 首击失败/为空不再是只读 info 兜底——行内点按重取
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
            // getView 重取提示（行下 inline，不弹窗——03-create-fail 同款呈现位）
            if let modelLoadHint {
                Text(modelLoadHint)
                    .font(T.font(11))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("03-model-load-hint")
            }
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

    // MARK: 模型 / 思考等级选择（连接态；数据源 model-selection.getView；自绘面板）

    /// 模型行：ComposerOptionSheet（套餐分节 + 选中勾），默认跟随桌面端当前绑定。
    /// getView 未就绪（首击超时/清单为空）时点按先重取，成功即开面板、失败落行下
    /// 提示（不静默、不再退化只读）
    private var modelSelectionRow: some View {
        Button {
            Task { await openModelPanel() }
        } label: {
            row(label: "模型",
                value: isLoadingModelInfo ? String(localized: "获取中…") : (selectedModel ?? "--"),
                icon: "cpu")
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("03-row-model")
    }

    private func openModelPanel() async {
        guard !isLoadingModelInfo else { return }
        if modelInfo?.models.isEmpty != false {
            isLoadingModelInfo = true
            modelInfo = await conversationStore.modelSelectionView()
            isLoadingModelInfo = false
            guard modelInfo?.models.isEmpty == false else {
                modelLoadHint = String(localized: "模型列表获取失败 · 桌面端未回执，点按重试")
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                return
            }
        }
        modelLoadHint = nil
        if selectedModel == nil {
            selectedModel = modelInfo?.activeModel ?? modelInfo?.models.first
        }
        if selectedThought == nil {
            selectedThought = modelInfo?.activeThoughtLevel
            await reloadThoughtOptions()
        }
        composerSheet = .model
    }

    /// 思考档行：词表按当前模型查询（workspace-config），缺席退化为 getView 词表 →
    /// 静态梯（web 端别名表归纳；不支持的档位由桌面端校验拒绝）
    private var thoughtSelectionRow: some View {
        Button {
            composerSheet = .thought
        } label: {
            row(label: "思考等级",
                value: selectedThought?.isEmpty == false ? selectedThought! : String(localized: "默认"),
                icon: "brain")
        }
        .buttonStyle(.plain)
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

    /// 会话前选择组装：上游 modelSelectionSchema strict 且 providerId/modelId
    /// `trim().min(1)`——provider 映射缺失时**整条 createSession 被拒**（用户报障
    /// 「创建之后再电脑端看不到」根因：曾发空 providerId）。未选模型或 provider
    /// 缺失一律返回 nil（桌面端以当前默认开跑，不阻断新建）
    private func pendingModelSelection() -> NewSessionModelSelection? {
        guard isRemote, let model = selectedModel else { return nil }
        guard let provider = modelInfo?.modelProviders[model], !provider.isEmpty else { return nil }
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

// MARK: - 新建会话暂存附件 + 交接箱（附件链路：sheet 暂存 → 会话 composer 上传）

/// 新建会话暂存附件（尚未有 sessionId、无法建上传事务——start 任务 draft 创建后
/// 经交接箱转交 ChatViewModel.uploads 走与用户三通道相同的 add→上传→sendText 链路）
struct StagedNewAttachment: Identifiable {
    let id = UUID()
    let name: String
    let mediaType: String
    let data: Data
}

/// 新建会话 → 会话页一次性交接（草稿文本 + 暂存附件 + 会话前模型选择；
/// 按 sessionId 键控防错投。modelSelection：draft 创建无 firstInput 通道
/// （P0 修复 2026-10-07「模型/思考强度没带到会话」）——随首条 sendText 的
/// modelSelection 下发【实证·上游仓 command.ts sendText schema】）
struct NewConversationHandoff {
    var draftText: String
    var attachments: [(name: String, mediaType: String, data: Data)]
    var modelSelection: NewSessionModelSelection?
}

@MainActor
enum NewConversationHandoffBox {
    private static var pending: [String: NewConversationHandoff] = [:]

    static func deposit(_ handoff: NewConversationHandoff, for conversationID: String) {
        pending[conversationID] = handoff
    }

    /// 仅目标会话可取（取走即清，一次性）
    static func take(for conversationID: String) -> NewConversationHandoff? {
        pending.removeValue(forKey: conversationID)
    }
}

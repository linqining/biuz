import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Agent 对话（Push L2）
/// 连接态与演示态共用发送/应答链路（v3 纠偏：客户端发命令、桌面代执行）；
/// 连接态增量面：桌面端模型只读 chips、待审批交互卡、向上分页。
struct ChatView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    @Environment(\.fileStore) private var fileStore
    // 详情态连接横幅数据源（safeAreaInset 插入式承载——push 态 RootView 底部
    // overlay 横幅会盖住 composer 输入框，UI/UX 验收 P1 2026-10-07）
    @Environment(AppSession.self) private var session
    @Environment(AppSettingsModel.self) private var appSettings
    let conversationID: String

    @State private var viewModel: ChatViewModel?
    @State private var observeTask: Task<Void, Never>?
    /// loadOlder 顶部插入识别：插入后的首次 count 变化不滚底（保持阅读位置）
    @State private var prependGuardFirstID: String?
    /// composer 输入焦点（提升到本视图：面板展开等兄弟视图要精确失焦收键盘——
    /// iOS 26 上 UIApplication.sendAction(resignFirstResponder) 对 SwiftUI TextField
    /// 不可靠，FocusState 绑定下传 ComposerBar 是唯一稳定通道）
    @FocusState private var composerFocused: Bool
    /// 会话面板展开态（SessionPanelsView 绑定）：键盘弹出即收起面板——面板+键盘
    /// +composer 总高超屏，VStack 溢出致面板与 composer 层重叠、点按全被吞
    /// （真机反馈 2026-10-07「workflow 打开时键盘收不起、面板也点不收」）
    @State private var expandedPanel: SessionPanelKind?
    /// 工作流完成卡产物详情 sheet（点交付物/产物索引行；nil = 收起）
    @State private var workflowArtifactSheet: WorkflowArtifactRequest?
    /// 036 白板浮层（web 侧栏面板同位；零 RPC 纯客户端，语义见 WhiteboardModel）
    @State private var whiteboardOpen = false
    /// G6 待办清单展开 sheet（web StatusCapsule id="todo" 可点展开的移动对位）
    @State private var todoSheetOpen = false
    /// G49 滚动位置记忆：顶层可见消息 id（scrollPosition 双向绑定）+ 待恢复游标
    @State private var scrollPositionId: String?
    @State private var restorePendingScrollId: String?
    // G9：行高缓存（messageId 键 LRU 4000，会话切换 clear）+ 活尾跟随判定
    //（「冻结史/尾轮」分区移动端等效——用户滚离底部（back-to-bottom 在场）时
    // 新行到达不抢滚底，回底部恢复跟随；web user-scroll-intent 同语义）
    let rowHeightCache = TimelineRowHeightCache()
    @State private var followingTail = true
    @State private var showBackToBottom = false
    /// fork 成功提示（新会话 id；用户从列表打开派生会话——原地切换属 UI 重构阶段）
    @State private var forkNotice: String?
    @State private var forkNoticeFailure: String?
    /// 划词「引用到输入框」待追加尾块（追加到 composer draft）
    @State private var composerDraftQuote: String?
    /// 划词「在新会话询问」待询问消息（呈现期捕获，§5-15）
    @State private var askInNewSessionMessage: ChatMessage?
    @State private var askQuestionDraft = ""
    // ⋯ 菜单面（2026-11-25 三 tab 重构：更改/文件/执行输出原全局 tab 的会话化入口）
    @State private var showTaskOutputSheet = false
    @State private var showChangesSheet = false
    /// G38 命令中心统一搜索 sheet（⋯菜单入口）
    @State private var showCommandCenter = false
    /// G15 @ 文件提及面板（composer 工具行入口；ReferenceFilePickerSheet 复用）
    @State private var showMentionPicker = false
    @State private var showFilesSheet = false
    /// 重命名 alert 呈现载荷（§5-15 呈现期捕获：确认回调不回读宿主 @State）
    @State private var renamePresentation: RenamePresentation?
    @State private var renameText = ""
    /// 归档/标记未读二段确认（归档是会话级组织态写面，确认后回列表）
    @State private var archiveRequested = false
    @State private var markUnreadRequested = false
    /// 二级模型选择器（2026-11-25 批4：替换扁平模型长列表；思考胶囊仍走 ComposerOptionSheet）
    @State private var showModelPicker = false

    var body: some View {
        chatMenuOverlays(
        Group {
            if let viewModel {
                content(viewModel)
            } else {
                CenterLoadingView(text: "正在载入会话…")
                    .accessibilityIdentifier("05-loading-center")
            }
        }
        .background(T.bg)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(false)
        // APP-DIFF[D001] platform: web 侧边抽屉（side pane，宽 min(88vw,28rem)）在 iOS 投影为
        // .sheet 原生弹层组（产物/白板/附件/composer/相机），手势与焦点管理由系统承担
        // 工作流完成卡产物详情（点交付物/产物索引行；viewModel 透传 store 私有读面）
        .sheet(item: $workflowArtifactSheet) { request in
            WorkflowArtifactSheetView(request: request, onClose: {
                workflowArtifactSheet = nil
            }, load: { req in
                await viewModel?.workflowArtifactContent(req) ?? nil
            })
            .presentationDetents([.large])
        }
        // 键盘弹出即收起面板（单屏保一个焦点：面板+键盘+composer 总高超屏，溢出
        // 后面板与 safeAreaInset 层重叠、点按全被上层吞——真机反馈 2026-10-07；
        // 面板展开→收键盘的反向联动已存在，此处补正向）
        .onChange(of: composerFocused) { _, focused in
            if focused, expandedPanel != nil {
                withAnimation(.easeInOut(duration: 0.15)) { expandedPanel = nil }
            }
        }
        .toolbarBackground(T.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    withAnimation(.easeOut(duration: 0.15)) {
                        let turningOn = viewModel?.isSearchActive != true
                        viewModel?.isSearchActive.toggle()
                        if !turningOn {
                            viewModel?.searchQuery = ""
                        } else if viewModel?.canLoadOlder == true {
                            // 检索前自动向上扩页一次（P2-3：扩大已加载检索范围）
                            Task { await viewModel?.loadOlder() }
                        }
                    }
                } label: {
                    Image(systemName: viewModel?.isSearchActive == true ? "xmark.circle" : "magnifyingglass")
                        .font(.system(size: 15))
                        .foregroundColor(T.text2)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("05-act-search")
            }
        }
        // store 换源重绑（mock→remote 装配完成）：冷启自动重连架构下，连接完成前进
        // 会话页会绑到 mock store——空列表+演示 chips 且永不自愈（同 ConversationListView
        // 的 .task(id:) 修复口径）。旧观察流经 Task.cancel 终结（AsyncStream 取消语义）。
        .task(id: ObjectIdentifier(store)) {
            observeTask?.cancel()
            let vm = ChatViewModel(store: store, conversationID: conversationID)
            viewModel = vm
            observeTask = Task {
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { await vm.observe() }
                    // 桌面端模型选择变化流（连接态 onDidChange 驱动 chips 只读刷新；演示态流立即结束）
                    group.addTask { await vm.observeModelSelection() }
                }
            }
            // 「显示思考过程」设置镜像（web 同名开关）：装配即写入 + 变更重载
            if let remote = store as? RemoteConversationStore {
                remote.showThinkingProcess = appSettings.value.showThinkingProcess
            }
            // G49：记忆位预读（首帧装载完成前置位——messages.count onChange 用它改道）
            if let saved = ChatScrollMemory.shared.load(key: ChatScrollMemory.key(
                workspace: session.connection.workspace, conversationID: conversationID)) {
                restorePendingScrollId = saved
            }
            // G9：会话切换清行高缓存（上游 clear() 同口径——turnId/messageId 跨会话
            // 不假设全局唯一）
            rowHeightCache.clear()
            followingTail = true
            await vm.load()
        }
        .onChange(of: appSettings.value.showThinkingProcess) { _, show in
            guard let remote = store as? RemoteConversationStore else { return }
            remote.showThinkingProcess = show
            Task { await viewModel?.load() }
        }
        .onChange(of: composerDraftQuote) { _, newValue in
            // 划词「引用到输入框」：userselect 尾块追加 composer 草稿 + 聚焦
            // （具名解绑：guard let 影子名 quote 与 .task 内属性赋值并用触发
            // Swift 前端断言崩溃——§5-17④ 同型，2026-11-23 编译实证）
            guard let quoteText = newValue, !quoteText.isEmpty else { return }
            let current = viewModel?.draft ?? ""
            let joined = current.isEmpty ? quoteText : current + "\n\n" + quoteText
            viewModel?.draft = joined
            composerDraftQuote = nil
            composerFocused = true
        }
        .sheet(item: $askInNewSessionMessage) { message in
            // 划词「在新会话询问」：呈现期捕获 message（§5-15）；提交走
            // createSelectionSideSession（firstInput = 引用尾块 + 用户问题）
            VStack(alignment: .leading, spacing: T.sp3) {
                Text(String(localized: "在新会话询问"))
                    .font(T.font(15, .bold))
                    .foregroundColor(T.text)
                Text(message.text)
                    .font(T.font(11.5))
                    .foregroundColor(T.text3)
                    .lineLimit(4)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(T.bgCode)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                TextField(String(localized: "要询问的问题"), text: $askQuestionDraft, axis: .vertical)
                    .font(T.font(13))
                    .foregroundColor(T.text)
                    .lineLimit(2...5)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 8)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                HStack(spacing: T.sp3) {
                    Button(String(localized: "取消")) { askInNewSessionMessage = nil }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                    Button(String(localized: "询问")) {
                        let question = askQuestionDraft
                        askInNewSessionMessage = nil
                        Task {
                            let sid = await viewModel?.askInNewSession(message, question: question)
                            if let sid {
                                forkNotice = sid // 复用提示行（child id 从列表打开）
                            } else {
                                forkNoticeFailure = String(localized: "派生会话创建失败（桌面端版本或连接状态）")
                            }
                        }
                    }
                    .disabled(askQuestionDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(askQuestionDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? T.bgInput : T.accent)
                    .foregroundStyle(askQuestionDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? T.text3 : T.onAccent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
            }
            .padding(T.sp4)
            .presentationDetents([.medium])
            .accessibilityIdentifier("l6-ask-new-sheet")
        }
        )
    }

    /// ⋯ 菜单承载 sheets/alerts（批3：执行输出/更改/文件 + 重命名/归档/标记未读）。
    /// 独立函数拆解 body 表达式复杂度（2026-11-25 编译实证：追加 sheet 簇后
    /// body 单表达式超时 unable to type-check in reasonable time）。
    @ViewBuilder
    private func chatMenuOverlays(_ base: some View) -> some View {
        base
        // ⋯「执行输出」（原任务 tab 详情页的会话化承载；TaskRecord 由当前会话构建，
        // 轨迹/元数据/停止全部以 sessionId 寻址，行为与原 push 页一致）
        .sheet(isPresented: $showTaskOutputSheet) {
            TaskOutputSheetHost(conversation: viewModel?.conversation)
        }
        // ⋯「更改」（原全局文件 tab 的会话化承载；sessionId 显式传入——
        // 旧「本次会话」分段取最近活跃会话的作用域缺陷就此消除）
        .sheet(isPresented: $showChangesSheet) {
            SessionChangesSheet(
                sessionId: conversationID,
                sessionTitle: viewModel?.conversation?.title ?? String(localized: "任务会话"))
        }
        // G15 @ 文件提及面板（ReferenceFilePickerSheet 复用——G-024 组件；点选插 @path）
        .sheet(isPresented: $showMentionPicker) {
            ReferenceFilePickerSheet(store: fileStore) { node in
                showMentionPicker = false
                let reference = "@\"\(node.path)\" "
                let current = viewModel?.draft ?? ""
                viewModel?.draft = current.isEmpty ? reference : current + reference
                composerFocused = true
            }
        }
        // G38 命令中心统一搜索（store.searchSessions listTaskList search + searchFiles）
        .sheet(isPresented: $showCommandCenter) {
            CommandCenterSheet(
                session: session,
                store: store,
                fileStore: fileStore,
                onOpenConversation: { target in
                    // 命中会话：入栈同会话（跨会话跳转——router.chatPath 追加 ChatRoute）
                    router.chatPath.append(.chat(target))
                })
        }
        // ⋯「文件」（工作区文件树 + 预览 + 提交图谱；浏览作用域 = 工作区，
        // 与 web 抽屉 file-manager 同口径，标题即工作区路径）
        .sheet(isPresented: $showFilesSheet) {
            NavigationStack {
                FileTreeView()
                    .navigationDestination(for: FileRoute.self) { route in
                        switch route {
                        case .tree:
                            FileTreeView()
                        case .preview(let node):
                            FilePreviewView(node: node)
                        case .commitGraph:
                            CommitGraphPage()
                        }
                    }
            }
        }
        // ⋯「重命名」（呈现期捕获：确认闭包捕获快照，不回读 renamePresentation）
        .alert("重命名会话", isPresented: Binding(
            get: { renamePresentation != nil },
            set: { if !$0 { renamePresentation = nil } })) {
            TextField("新名称", text: $renameText)
            Button("取消", role: .cancel) { renamePresentation = nil }
            Button("保存") {
                let newTitle = renameText
                let target = conversationID
                renamePresentation = nil
                Task { await store.renameConversation(newTitle, conversationID: target) }
            }
        } message: {
            Text("新名称会同步到桌面端任务列表")
        }
        // ⋯「归档」（组织态写面确认；成功回会话列表——归档行已不在主列表）
        .confirmationDialog(
            String(localized: "归档「\(viewModel?.conversation?.title ?? "")」？"),
            isPresented: $archiveRequested, titleVisibility: .visible) {
            Button(String(localized: "归档"), role: .destructive) {
                let target = conversationID
                Task {
                    await store.setArchived(true, conversationID: target)
                    router.chatPath = []
                }
            }
            Button(String(localized: "取消"), role: .cancel) {}
        } message: {
            Text("归档后可在会话列表「已归档」区找回")
        }
        // ⋯「标记未读」（本地置位 + 服务端写面；成功回会话列表）
        .confirmationDialog(
            String(localized: "标记为未读？"),
            isPresented: $markUnreadRequested, titleVisibility: .visible) {
            Button(String(localized: "标记未读")) {
                let target = conversationID
                Task {
                    await store.markUnread(conversationID: target)
                    router.chatPath = []
                }
            }
            Button(String(localized: "取消"), role: .cancel) {}
        } message: {
            Text("该会话会在列表中显示未读标")
        }
    }


    private func content(_ viewModel: ChatViewModel) -> some View {
        VStack(spacing: 0) {
            header(viewModel)
            // 会话面板区（goal/plan/工作流/btw/子代理 chips + 单开面板；默认收起为一行
            // chips 不占消息流空间；面板体内部滚动、有界高度，数据缺席不渲染）
            if viewModel.hasAnyPanel {
                SessionPanelsView(
                    viewModel: viewModel,
                    onExpansionChange: { expanded in
                        if expanded { composerFocused = false }
                    },
                    expanded: $expandedPanel)
                // 面板体空白区点按即收键盘（用户报障 2026-10-07：workflow 打开时键盘
                // 收不起——面板体自带 ScrollView，滚动收起对它无效，补点按通道）
                .onTapGesture { composerFocused = false }
                .padding(.horizontal, T.sp4)
                .padding(.bottom, T.sp2)
            }
            if viewModel.isSearchActive {
                messageSearchBar(viewModel)
            }
            messageList(viewModel)
        }
        // APP-DIFF[D003] platform: web 用 dvh 视口高度（含地址栏收放）；原生无地址栏，
        // 改用 safeAreaInset 承接底部 composer/横幅栈，避让 Home 指示条与安全区
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                // 详情态连接横幅（push 态 RootView 底部 overlay 恰好压在 composer
                // 输入框上——UI/UX 验收 P1 2026-10-07「断线横幅遮挡输入框」；此处
                // 插入式预留空间，横幅在场时输入框整体上移永不被盖。模型单源
                // ConnectionBanner.model(for:)，列表/tab 态仍由 RootView 底部承载）
                if let banner = ConnectionBanner.model(for: session) {
                    ConnectionBanner(model: banner)
                }
                // 3.16.2 运行时初始化态（failed/initializing 时 composer 禁发，原因上屏）
                if let runtime = viewModel.runtimeInitialization, runtime.isBlocking {
                    RuntimeInitializationBanner(runtime: runtime)
                        .padding(.horizontal, T.sp4)
                        .padding(.top, T.sp2)
                }
                // G24 工作区 MCP 待审核指示条（web WorkspaceMcpPendingIndicator 同位——
                // state.workspaceMcpAdmission 非 null 且 pendingCount>0 即显示；
                // 「忽略」纯本地 renderer 内存态，不改桌面）
                if let admission = viewModel.workspaceMcpAdmission,
                   admission.pendingCount > 0,
                   !viewModel.mcpAdmissionDismissedIds.contains(viewModel.conversationID) {
                    WorkspaceMcpPendingBanner(
                        count: admission.pendingCount,
                        onReview: {
                            // web 同款纯导航（WorkspaceHookPendingBanner.tsx:302-308
                            // 不发 RPC）：切到设置 tab 的 MCP 区
                            router.selectedTab = .settings
                        },
                        onDismiss: {
                            viewModel.mcpAdmissionDismissedIds.insert(viewModel.conversationID)
                        })
                        .padding(.horizontal, T.sp4)
                        .padding(.top, T.sp2)
                }
                // 待审批交互卡（常驻 composer 上方：不随消息滚动，新消息不再顶走；
                // 连接态 permission/plan/workspaceHookReview/escalation 类挂起交互，按
                // kind 分派应答命令——permission/plan 走 resolveInteraction，
                // workspaceHookReview 走 respondWorkspaceHookReview）
                ForEach(viewModel.pendingInteractions) { interaction in
                    Group {
                        if interaction.isPlanApproval {
                            PlanApprovalCard(viewModel: viewModel, interaction: interaction)
                        } else if interaction.isWorkspaceHookReview {
                            WorkspaceHookReviewCard(
                                interaction: interaction,
                                onTrust: { ids in
                                    await viewModel.trustWorkspaceHooks(interaction, reviewItemIds: ids)
                                },
                                onRequest: {
                                    await viewModel.requestWorkspaceHookReview()
                                },
                                onRevoke: { ids in
                                    await viewModel.revokeWorkspaceHookTrust(reviewItemIds: ids)
                                })
                        } else if interaction.isUserInput {
                            UserInputInteractionCard(viewModel: viewModel, interaction: interaction)
                        } else if interaction.isPluginInstall {
                            // G23 插件安装卡（web PluginInstallCard.tsx 移动端同构；
                            // 此前未知 kind 落 EmptyView 静默丢弃）
                            PluginInstallInteractionCard(
                                viewModel: viewModel, interaction: interaction)
                        } else if interaction.isPermission
                                    || interaction.kind.lowercased().contains("escalation") {
                            ApprovalInteractionCard(viewModel: viewModel, interaction: interaction)
                        } else {
                            EmptyView()
                        }
                    }
                    // 键盘在场布局保障（2026-10-09 真机+门禁复现根因）：审批卡无界 +
                    // 软键盘（≈40% 屏）+ composer 全行栈超过屏高时，safeAreaInset 栈
                    // 溢出到键盘底下——靠近输入框的 remoteChips（模型/思考胶囊）+
                    // 输入框整段被键盘物理盖住（hittable=false，点击从未发出=真机
                    // 「模型/思考不可选而计划/排队可选」的物理成因：后者在栈里位置
                    // 更高未被盖）。审批卡限高 240 + 超限内部滚动，底部栈总能抬到
                    // 键盘上方（fixedSize：卡片高度自适应内容、不超上限不占死块）
                    .modifier(ApprovalCardKeyboardBound(
                        maxHeight: interaction.isUserInput ? 360 : 240))
                    // 盖章动效（motion-spec）：ACK 权威成功后落印「照办/不准」，
                    // 停驻 700ms 由 VM stampHolds 驱动；失败不落印（U-5）
                    .modifier(ApprovalStampOverlay(viewModel: viewModel, interactionId: interaction.id))
                    .padding(.horizontal, T.sp4)
                    .padding(.top, T.sp2)
                }
                // P2-7B「稍后处理」反馈行（3s 自动清除）：卡片可能随即被回流撤下，
                // 提示需在卡片之外存活（switchHint 同款一行橙字口径）
                if let snoozeFeedback = viewModel.snoozeFeedback {
                    Text(snoozeFeedback)
                        .font(T.font(10.5))
                        .foregroundColor(T.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, T.sp4)
                        .accessibilityIdentifier("05-approval-snooze-hint")
                }
                // 桌面同构：排队消息紧贴 composer 上方（pending 队列；无排队不渲染）
                if viewModel.isReadOnly, let queue = viewModel.queueInfo {
                    QueueBarView(
                        queue: queue,
                        onSendNow: { queueItemId in await viewModel.sendQueuedNow(queueItemId) },
                        onDelete: { queueItemId in await viewModel.deleteQueueItem(queueItemId) },
                        onEdit: { queueItemId, newText in
                            await viewModel.finishQueueItemEdit(queueItemId, newText: newText)
                        },
                        onMoveUp: { queueItemId in await viewModel.moveQueueItemUp(queueItemId) },
                        onReorder: { queueItemId, beforeId in
                            await viewModel.reorderQueueItem(queueItemId, beforeQueueItemId: beforeId)
                        },
                        onToggleAutoDrain: { enabled in await viewModel.setAutoDrain(enabled) },
                        onBeginEdit: { queueItemId in await viewModel.beginQueueItemEdit(queueItemId) },
                        onCancelEdit: { queueItemId in await viewModel.cancelQueueItemEdit(queueItemId) },
                        onReleaseLock: { queueItemId in await viewModel.releaseExternalEditLock(queueItemId) },
                        lockOwner: { item in viewModel.editLockOwner(for: item) })
                }
                ComposerBar(
                    viewModel: viewModel,
                    inputFocused: $composerFocused,
                    onMention: { showMentionPicker = true })
            }
            // 审核卡/队列条/composer 空白区点按即收键盘（用户报障 2026-10-07：审核
            // 弹窗在场时键盘收不起——卡片区不在消息 ScrollView 内，需独立点按通道；
            // 卡内 Button/胶囊子控件占优先级，仅空白命中）
            .onTapGesture { composerFocused = false }
            // 036 白板浮层（零 RPC 纯客户端；导出 PNG 直插 composer 附件——
            // web add-to-chat 同效；workspace 键同 web buildWhiteboardWorkspaceKey 口径）
            .sheet(isPresented: $whiteboardOpen) {
                let workspace = session.connection.workspace
                let whiteboardKey: String = {
                    if let identity = workspace?.workspaceIdentity, !identity.isEmpty { return identity }
                    if let path = workspace?.path, !path.isEmpty { return path }
                    return conversationID
                }()
                WhiteboardPanelView(
                    workspaceKey: whiteboardKey,
                    onAddToChat: { data in
                        viewModel.uploads.add(name: "白板.png", mediaType: "image/png", data: data)
                    },
                    onClose: { whiteboardOpen = false })
            }
            // G6 待办清单展开（web PlanStatusSection 同构：全量条目 + 完成删除线；
            // 上游 focusCurrent 自动定位首个 inProgress——sheet 短清单直接全显，不仿滚动定位）
            .sheet(isPresented: $todoSheetOpen) {
                if let plan = viewModel.planPanel {
                    TodoListSheet(plan: plan)
                }
            }
        }
    }

    /// 头部（标题 + 运行中胶囊 + todo 摘要 + 3px 进度条）
    private func header(_ viewModel: ChatViewModel) -> some View {
        VStack(spacing: T.sp2) {
            // web 第二行：📁 项目名 + ⋯（会话标题在 web 只出现在侧栏，聊天页不重复大标题）
            HStack(spacing: T.sp2) {
                Image(systemName: "folder")
                    .font(.system(size: 13))
                    .foregroundColor(T.text3)
                Text(viewModel.conversation?.projectName
                    ?? viewModel.conversation?.title
                    ?? String(localized: "任务会话"))
                    .font(T.font(14, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if viewModel.conversation?.isRunning == true {
                    StatusPill(text: String(localized: "运行中"), kind: .run, compact: true)
                }
                // G28 头像叠组（web activityAvatars 叠图同构，Android SessionPanels
                // 同款）：最多 3 枚 agent 面孔、后一枚压前一枚、各带结果徽标点
                if !viewModel.avatarSubagents.isEmpty {
                    HStack(spacing: 0) {
                        ForEach(Array(viewModel.avatarSubagents.prefix(3).enumerated()),
                                id: \.element.childSessionId) { index, avatar in
                            SubagentAvatarFace(avatar: avatar)
                                .offset(x: -4 * CGFloat(index))
                                .zIndex(Double(index))
                        }
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("05-subagent-avatars")
                }
                Spacer(minLength: 0)
                if let summary = viewModel.conversation?.todoSummary {
                    // G6：待办摘要可点（有结构化条目时才升为按钮——纯文本摘要无展开面）
                    if let plan = viewModel.planPanel, !plan.items.isEmpty {
                        Button {
                            todoSheetOpen = true
                        } label: {
                            HStack(spacing: 4) {
                                Text(summary)
                                    .font(T.mono(11))
                                    .foregroundColor(T.text3)
                                Image(systemName: "chevron.up.chevron.down")
                                    .font(.system(size: 8, weight: .semibold))
                                    .foregroundColor(T.text3)
                            }
                        }
                        .accessibilityIdentifier("05-todo-capsule")
                    } else {
                        Text(summary)
                            .font(T.mono(11))
                            .foregroundColor(T.text3)
                    }
                }
                // 036 白板入口（web 侧栏面板 tab 的移动对位；零 RPC 纯客户端）
                Button {
                    whiteboardOpen = true
                } label: {
                    Image(systemName: "scribble.variable")
                        .font(.system(size: 14))
                        .foregroundColor(T.text3)
                        .frame(width: 26, height: 26)
                }
                // 头部容器 05-header 的 identifier 在 iOS 26 会级联覆盖直接子元素
                // 的 identifier（树探针实证：本按钮原 identifier 被覆写为 05-header）——
                // 显式 a11y label 不受级联影响，E2E 按 label 查（test31）
                .accessibilityLabel(String(localized: "白板"))
                .accessibilityIdentifier("05-act-whiteboard")
                // ⋯ 菜单（2026-11-25 三 tab 重构：原静态 Image 是死控件——真身挂这里。
                // 「更改/文件」原全局文件 tab 的会话化入口；「执行输出」原任务 tab 详情页）
                Menu {
                    Button {
                        showChangesSheet = true
                    } label: {
                        Label(String(localized: "更改"), systemImage: "arrow.left.arrow.right")
                    }
                    .accessibilityIdentifier("05-menu-changes")
                    Button {
                        showFilesSheet = true
                    } label: {
                        Label(String(localized: "文件"), systemImage: "folder")
                    }
                    .accessibilityIdentifier("05-menu-files")
                    // G38 统一搜索（会话+文件跨区检索；web ⌘K CommandCenter 移动对位）
                    Button {
                        showCommandCenter = true
                    } label: {
                        Label(String(localized: "统一搜索"), systemImage: "magnifyingglass")
                    }
                    .accessibilityIdentifier("05-menu-command-center")
                    Button {
                        showTaskOutputSheet = true
                    } label: {
                        Label(String(localized: "执行输出"), systemImage: "doc.text.magnifyingglass")
                    }
                    .accessibilityIdentifier("05-menu-task-output")
                    Divider()
                    Button {
                        renamePresentation = RenamePresentation(
                            initial: viewModel.conversation?.title ?? "")
                    } label: {
                        Label(String(localized: "重命名"), systemImage: "pencil")
                    }
                    .accessibilityIdentifier("05-menu-rename")
                    Button {
                        markUnreadRequested = true
                    } label: {
                        Label(String(localized: "标记未读"), systemImage: "envelope.badge")
                    }
                    .accessibilityIdentifier("05-menu-mark-unread")
                    Button(role: .destructive) {
                        archiveRequested = true
                    } label: {
                        Label(String(localized: "归档"), systemImage: "archivebox")
                    }
                    .accessibilityIdentifier("05-menu-archive")
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 14))
                        .foregroundColor(T.text3)
                        .frame(width: 26, height: 26)
                }
                .accessibilityIdentifier("05-act-more")
            }
            // P-022 工作流运行摘要行（web「工作流运行中 工作流脚本 / N 个阶段 · M 个子代理工作中…」）
            // 明细仍在 SessionPanelsView 的 Workflow 面板；此行点按即展开
            if let run = viewModel.workflowRuns.first(where: { $0.rawStatus == "running" }) {
                let agents = run.actors.filter { $0.rawStatus == "running" || $0.rawStatus == "active" }.count
                let meta = [
                    (run.phaseCount ?? 0) > 0 ? "\(run.phaseCount ?? 0) 个阶段" : nil,
                    agents > 0 ? "\(agents) 个子代理工作中…" : nil,
                ].compactMap { $0 }.joined(separator: " · ")
                Button {
                    withAnimation(.easeOut(duration: 0.15)) { expandedPanel = .workflow }
                } label: {
                    HStack(spacing: T.sp2) {
                        Text("⑂").font(.system(size: 13)).foregroundColor(T.seal)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("工作流运行中 · \(run.name)")
                                .font(T.font(13, .medium))
                                .foregroundColor(T.text)
                                .lineLimit(1)
                            if !meta.isEmpty {
                                Text(meta).font(.system(size: 11)).foregroundColor(T.text3)
                            }
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: 12))
                            .foregroundColor(T.text3)
                    }
                    .padding(.horizontal, T.sp3)
                    .padding(.vertical, T.sp2)
                    .background(T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-workflow-running")
            }
            if let progress = viewModel.conversation?.taskProgress {
                ThinProgressBar(progress: progress)
            }
        }
        .padding(.horizontal, T.sp4)
        .padding(.vertical, T.sp2)
        .background(T.bg)
        .accessibilityIdentifier("05-header")
    }

    /// 会话内消息搜索（P2-3）：已加载范围本地检索 + 命中计数
    private func messageSearchBar(_ viewModel: ChatViewModel) -> some View {
        @Bindable var bindableViewModel = viewModel
        return HStack(spacing: T.sp2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12))
                .foregroundColor(T.text3)
            TextField("搜索已加载消息…", text: $bindableViewModel.searchQuery)
                .font(T.font(14))
                .foregroundColor(T.text)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityIdentifier("05-search-messages")
            Text(String(localized: "\(viewModel.searchHitCount) 处命中"))
                .font(T.font(11))
                .foregroundColor(viewModel.searchHitCount > 0 ? T.accentText : T.text3)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 40)
        .background(T.bgInput)
        .clipShape(Capsule())
        .padding(.horizontal, T.sp4)
        .padding(.bottom, T.sp2)
    }

    private func messageList(_ viewModel: ChatViewModel) -> some View {
        ScrollViewReader { proxy in
            // 三态可区分（真机报障「消息区空白且无提示」修复，2026-10-06）：
            // 载入中=加载态；订阅/拉取失败=错误态+重试；订阅拉取成功但零行=空会话态
            if viewModel.isLoading {
                CenterLoadingView(text: "正在载入会话…", identifier: "05-messages-loading")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.messages.isEmpty, let failure = viewModel.loadFailure {
                // 错误态要求「消息为空」双条件：observe 流晚于 load() 送达消息时
                // 以列表为准，不误报失败
                loadFailureView(viewModel, failure: failure)
            } else if viewModel.messages.isEmpty {
                EmptyStateView(
                    icon: "text.bubble",
                    title: String(localized: "还没有消息"),
                    detail: String(localized: "发送第一条消息，开始与桌面端的对话"))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                LazyVStack(alignment: .leading, spacing: T.sp4) {
                    // 待审批交互卡已移出滚动列表（用户报障：新消息把卡片顶出屏幕，
                    // 需上滚找批准按钮）——改固定在 composer 上方常驻，见 approvalBar(_:)
                    // 内联失败横幅（读面三态补全：last-good 与失败共存；控制面拒因
                    /// 如 retryTurn——也走此通道，重进/重试成功后自动清除）
                    if let inlineFailure = viewModel.inlineFailureText {
                        Label {
                            Text(inlineFailure)
                                .font(T.font(11, .regular))
                                .multilineTextAlignment(.leading)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.system(size: 10))
                        }
                        .foregroundColor(T.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("05-inline-failure-banner")
                    }
                    // 向上分页入口：点击加载 + 滚顶自动加载（下拉到顶即拉更早历史）
                    if viewModel.canLoadOlder {
                        Button {
                            loadOlderAnchored(viewModel, proxy: proxy)
                        } label: {
                            HStack(spacing: T.sp1) {
                                if viewModel.isLoadingOlder {
                                    SpinnerView(size: 12)
                                } else {
                                    Image(systemName: "chevron.up")
                                        .font(.system(size: 10, weight: .semibold))
                                }
                                Text("加载更早消息")
                                    .font(T.font(12, .medium))
                            }
                            .foregroundColor(T.accentText)
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("05-act-load-older")
                        // 滚顶自动加载：按钮进入可见区（用户翻到顶）即拉下一页——
                        // 「下拉/点击」双触发；无更多数据时按钮隐藏不形成循环
                        .onAppear {
                            guard viewModel.canLoadOlder, !viewModel.isLoadingOlder else { return }
                            loadOlderAnchored(viewModel, proxy: proxy)
                        }
                    }
                    // 分页失败文案（失败 ≠ 没有更早：入口保留供再试，文案透出拒因）
                    if let olderFailure = viewModel.olderLoadFailureText {
                        Text(olderFailure)
                            .font(T.font(11, .regular))
                            .foregroundColor(T.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("05-older-failure-text")
                    }
                    // fork 结果提示（成功=新会话 id 从列表打开；失败=拒因原文；4s 自动清。
                    // 具名解绑：简写影子 if let forkNotice + .task 内同名属性赋值触发
                    // Swift 前端断言崩溃——§5-17④ 同型，2026-11-23 编译实证）
                    if let notice = forkNotice {
                        Text(String(localized: "已创建 fork 会话 · \(notice)"))
                            .font(T.font(11, .regular))
                            .foregroundColor(T.text2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("05-fork-notice")
                            .task {
                                try? await Task.sleep(nanoseconds: 4_000_000_000)
                                forkNotice = nil
                            }
                    }
                    if let failureNotice = forkNoticeFailure {
                        Text(String(localized: "fork 失败 · \(failureNotice)"))
                            .font(T.font(11, .regular))
                            .foregroundColor(T.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("05-fork-failure")
                            .task {
                                try? await Task.sleep(nanoseconds: 4_000_000_000)
                                forkNoticeFailure = nil
                            }
                    }
                    let visible = viewModel.searchQuery.isEmpty
                        ? viewModel.messages : viewModel.filteredMessages
                    ForEach(Array(visible.enumerated()), id: \.element.id) { index, message in
                        MessageView(
                            message: message, sessionID: conversationID,
                            feedbackState: viewModel.assistantFeedback[message.id],
                            ordinal: index + 1,
                            showTodosInStream: appSettings.value.showTodosInStream
                                || ProcessInfo.processInfo.arguments.contains("-ZCodeShowStreamTodos"),
                            onQuickReply: { reply in
                                Task { await viewModel.answerQuestion(reply) }
                            },
                            onRetryToolCall: { _, rowId in
                                Task { await viewModel.retryTurn(rowId: rowId) }
                            },
                            // P1-3：回调仅连接态接线——演示态传 nil，反馈行/长按编辑项
                            // 不渲染（命令不可达不渲染死入口；游标门槛在 MessageView 内）
                            onFeedback: viewModel.isReadOnly ? { value in
                                await viewModel.setAssistantFeedback(message, value: value)
                            } : nil,
                            onEditResend: viewModel.isReadOnly ? { newText in
                                await viewModel.editAndResend(message, newText: newText)
                            } : nil,
                            // fork 会话派生（web 完成态 assistant 行同口径；显式行游标 CAS）
                            onFork: viewModel.isReadOnly ? { [weak viewModel] in
                                guard let viewModel else { return }
                                let result = await viewModel.forkFromMessage(message)
                                if let sid = result.sessionId {
                                    forkNotice = sid
                                } else if let failure = result.failure {
                                    forkNoticeFailure = failure
                                }
                            } : nil,
                            // 划词菜单（移动适配：行级长按/上下文菜单承载 web 划词动作）
                            onQuoteToComposer: viewModel.isReadOnly ? { [weak viewModel] in
                                guard let viewModel else { return }
                                composerDraftQuote = viewModel.quoteToComposer(message)
                                composerFocused = true
                            } : nil,
                            onAskInNewSession: viewModel.isReadOnly ? {
                                askInNewSessionMessage = message
                            } : nil,
                            // 工作流完成卡四格 join（活投影按 runId 对位；冷恢复 run 被
                            // 8 条上限淘汰 = 三格「—」桌面同口径）+ ⤢ 门控（仅卡对应 run
                            // 是面板当前 run 时接线——面板只显 workflowRuns.first）
                            workflowCompletionRun: message.workflowCompletion.flatMap { card in
                                viewModel.workflowRuns.first {
                                    ($0.workId ?? $0.id) == card.runId || $0.id == card.runId
                                }
                            },
                            onOpenWorkflowRun: message.workflowCompletion.flatMap { card in
                                viewModel.workflowRun.flatMap { current in
                                    (current.workId ?? current.id) == card.runId
                                        ? { expandedPanel = .workflow } : nil
                                }
                            },
                            onOpenWorkflowArtifact: message.workflowCompletion.map { card in
                                { artifact in
                                    workflowArtifactSheet = WorkflowArtifactRequest(
                                        sessionID: conversationID, card: card, artifact: artifact)
                                }
                            })
                        .id(message.id)
                        // G9：行测高写入（GeometryReader 单行回调，仅写缓存不触发
                        // state——布局期副作用零重组；上游 set() 同仅记录）
                        .background(
                            GeometryReader { geo in
                                // body 内直写缓存（流式行高度增长持续刷新——上游
                                // ResizeObserver 同语义；仅写缓存零 state 零重组）
                                Color.clear.onAppear {
                                    rowHeightCache.set(key: message.id, heightPx: geo.size.height)
                                }
                                .onChange(of: geo.size.height) { _, newHeight in
                                    rowHeightCache.set(key: message.id, heightPx: newHeight)
                                }
                            }
                        )
                        .transition(.opacity.animation(.easeIn(duration: 0.12)))
                    }
                    Color.clear.frame(height: T.sp2).id("bottom-anchor")
                }
                .padding(.horizontal, T.sp4)
                .padding(.top, T.sp2)
            }
            .scrollIndicators(.visible)
            .defaultScrollAnchor(.bottom)
            // G49：滚动位置双向绑定（iOS17+；读取 = 顶层可见消息 id，写入 = 滚到该行）
            .scrollPosition(id: $scrollPositionId)
            // 滚动消息即收键盘（系统标准行为；面板展开失焦走 FocusState 通道）
            .scrollDismissesKeyboard(.immediately)
            // E2E 定位用：滚动测试对消息区元素本身 swipe（其 frame 被 safeAreaInset
            // 缩短到常驻审批卡上方的带内，元素中心起滑不会被卡吞手势）
            .accessibilityIdentifier("05-message-scroll")
            // 点按消息区空白即收键盘（用户报障 2026-10-07「有审核卡/workflow 面板时
            // 键盘收不起来」：滚动收起之外补点按通道——子控件 Button/TextField 占
            // 手势优先级，仅空白区命中本手势，审核卡/面板/chips 区由 safeAreaInset
            // 容器上的同款手势覆盖，见 content）
            .onTapGesture { composerFocused = false }
            .modifier(ChatScrollToTopLoadModifier {
                // 滚动到顶（内容顶距视口 ≤ 24pt）：「下拉到顶自动加载更早」触发区
                guard viewModel.canLoadOlder, !viewModel.isLoadingOlder else { return }
                loadOlderAnchored(viewModel, proxy: proxy)
            })
            // back-to-bottom（web ConversationTimeline back-to-bottom 同构）：距底
            // > 400pt 时显示悬浮回底钮（长会话向上翻阅后一键回底；iOS18 API 低版本降级）
            .modifier(BackToBottomVisibilityModifier(isVisible: $showBackToBottom))
            .overlay(alignment: .bottomTrailing) {
                if showBackToBottom {
                    Button {
                        followingTail = true
                        withAnimation(.easeOut(duration: 0.2)) { scrollToBottom(proxy) }
                    } label: {
                        Image(systemName: "arrow.down.circle.fill")
                            .font(.system(size: 26))
                            .foregroundColor(T.accentText)
                            .frame(width: 44, height: 44)
                            .background(T.bgCard.opacity(0.9))
                            .clipShape(Circle())
                            .overlay(Circle().stroke(T.borderStrong, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("05-act-back-to-bottom")
                    .padding(.trailing, T.sp3)
                    .padding(.bottom, T.sp2)
                    .transition(.opacity)
                }
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                // loadOlder 顶部插入：保持阅读位置——loadOlderAnchored 已锚定原顶部
                // 消息并复位，这里不得滚底。旧守卫比较「新首条==旧首条」，插入后首条
                // 必变，恒不成立 → 翻页必先跳底再锚回（用户 2026-10-06 反馈），改为
                // 「本变更来自在途 loadOlder」判定
                if prependGuardFirstID != nil { return }
                // G9：活尾跟随判定（冻结史/尾轮分区移动端等效）——用户滚离底部
                // （back-to-bottom 在场 = 在史区阅读）时新行到达不抢滚底；回底部
                // （followingTail 恢复 true）后恢复跟随。web user-scroll-intent 同语义。
                if !followingTail || showBackToBottom {
                    return
                }
                // G49：记忆恢复首帧——滚到记忆行而非底部（行已被翻页挤出不恢复，回底部）
                if let pending = restorePendingScrollId {
                    restorePendingScrollId = nil
                    if viewModel.messages.contains(where: { $0.id == pending }) {
                        scrollPositionId = pending
                        return
                    }
                }
                scrollToBottom(proxy)
            }
            .onChange(of: scrollPositionId) { _, newValue in
                // G49：滚动即记忆（进程内 LRU，成本可忽略；离开页面再补一次终值）
                guard let messageId = newValue else { return }
                ChatScrollMemory.shared.save(
                    key: ChatScrollMemory.key(
                        workspace: session.connection.workspace, conversationID: conversationID),
                    messageId: messageId)
            }
            .onDisappear {
                if let messageId = scrollPositionId {
                    ChatScrollMemory.shared.save(
                        key: ChatScrollMemory.key(
                            workspace: session.connection.workspace, conversationID: conversationID),
                        messageId: messageId)
                }
            }
            .onChange(of: viewModel.messages.last?.text) { _, _ in
                scrollToBottom(proxy)
            }
            }
        }
    }

    /// 消息区加载失败错误态（订阅/历史拉取失败透出 + 重试）：与空会话/加载中可区分
    /// ——失败文本来自 store 最近一次记录（「订阅会话失败 · …」「拉取历史失败 · …」）
    private func loadFailureView(_ viewModel: ChatViewModel, failure: String) -> some View {
        VStack(spacing: T.sp3) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 30))
                .foregroundColor(T.text3)
            Text("会话加载失败")
                .font(T.font(15, .semibold))
                .foregroundColor(T.text)
            Text(failure)
                .font(T.font(12))
                .foregroundColor(T.text3)
                .multilineTextAlignment(.center)
                .padding(.horizontal, T.sp6)
            Button {
                Task { await viewModel.retryLoad() }
            } label: {
                Label(String(localized: "重试"), systemImage: "arrow.clockwise")
                    .font(T.font(14, .semibold))
                    .foregroundColor(T.onAccent)
                    .padding(.horizontal, T.sp4)
                    .frame(minHeight: 44)
                    .background(T.accent)
                    .clipShape(Capsule())
            }
            .accessibilityIdentifier("05-act-chat-retry")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.2)) {
            proxy.scrollTo("bottom-anchor", anchor: .bottom)
        }
    }

    /// 加载更早 + 锚定原顶部消息（保持阅读位置）；点击按钮与下拉到顶共用。
    /// 完成后直接 scrollTo 复位（无动画）——onChange 滚底已被守卫拦下，不再有
    /// 「先跳底再锚回」的跳变
    private func loadOlderAnchored(_ viewModel: ChatViewModel, proxy: ScrollViewProxy) {
        let anchor = viewModel.messages.first?.id
        prependGuardFirstID = anchor ?? "prepend-empty"
        Task {
            await viewModel.loadOlder()
            prependGuardFirstID = nil
            if let anchor {
                proxy.scrollTo(anchor, anchor: .top)
            }
        }
    }
}

/// 「下拉到顶自动加载更早」手势（onScrollGeometryChange 为 iOS 18+ API）：
/// 低版本系统降级为无自动触发（05-act-load-older 手动入口仍在），不阻断编译。

/// back-to-bottom 显隐（距底 > 400pt；iOS18 onScrollGeometryChange，低版本不显示——
/// defaultScrollAnchor(.bottom) 已保证进入即底部，低版本手动上翻后滚回即可）
struct BackToBottomVisibilityModifier: ViewModifier {
    @Binding var isVisible: Bool

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: Bool.self) { geo in
                let distanceFromBottom = geo.contentSize.height
                    - geo.contentOffset.y - geo.containerSize.height
                return distanceFromBottom > 400
            } action: { _, isFar in
                guard isFar != isVisible else { return }
                withAnimation(.easeOut(duration: 0.15)) { isVisible = isFar }
            }
        } else {
            content
        }
    }
}

struct ChatScrollToTopLoadModifier: ViewModifier {
    let onAtTop: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: Bool.self) { geo in
                let offset = geo.contentOffset.y + geo.contentInsets.top
                return offset <= 24
            } action: { _, atTop in
                guard atTop else { return }
                onAtTop()
            }
        } else {
            content
        }
    }
}

// MARK: - G-021 子代理只读转录下钻（actor.sessionId → rowsRange 一页；无新协议）

struct ActorTranscriptSheet: View {
    let actor: WorkflowActorSummary
    let messages: [ChatMessage]
    let isLoading: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    CenterLoadingView(text: "正在读取子代理转录…")
                } else if messages.isEmpty {
                    EmptyStateView(
                        icon: "text.bubble",
                        title: String(localized: "暂无转录"),
                        detail: String(localized: "该子代理会话暂无可读的行记录"))
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: T.sp3) {
                            ForEach(messages) { message in
                                // 与主会话同构渲染（用户气泡/思考折叠块/可展开工具卡）。
                                // 只读转录：快捷回复/反馈/重试/编辑重发均不接线——question/
                                // todos 恒空、rowKind 恒 nil、toolCall 无重试游标，天然无死入口；
                                // sessionID 传 actor.sessionId（转录内用户行附件走 attachmentReadV4）
                                MessageView(
                                    message: message,
                                    sessionID: actor.sessionId ?? "",
                                    onQuickReply: { _ in })
                            }
                        }
                        .padding(T.sp4)
                    }
                }
            }
            .background(T.bg)
            .navigationTitle(actor.name ?? String(localized: "子代理转录"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(T.text)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityIdentifier("05-transcript-act-close")
                }
            }
        }
    }
}

// MARK: - 权限审批 optionId 推导（A-3；两处权限 UI 共用：
// 会话内 ApprovalInteractionCard 与任务页 ApprovalSheetView）
//
// web 四族词表 allowOnce/allowAlways/rejectOnce/rejectAlways（bundle 归一化函数
// dQ 实证：小写包含 allow|approve → allow 族、reject|deny → reject 族、含 always
// 升 Always 档，否则 custom）。应答 answer={optionId}——优先回传服务端原生
// optionId（web 调用点 {optionId: t.optionId}，按钮即选项）；options 空时按
// 拼规范 id：(approved ? "allow" : "reject") + (always ? "Always" : "Once")。

enum PermissionFamily {
    case allowOnce, allowAlways, rejectOnce, rejectAlways, custom
}

struct PermissionOptionMatrix: Equatable {
    let allowOnce: RemoteInteractionOption?
    let allowAlways: RemoteInteractionOption?
    let rejectOnce: RemoteInteractionOption?
    let rejectAlways: RemoteInteractionOption?
    /// 全非四族 options（单选即决形态：chips 即选项，点选即发原生 id）
    let customOptions: [RemoteInteractionOption]

    init(options: [RemoteInteractionOption]) {
        var once = RemoteInteractionOption?.none
        var always = RemoteInteractionOption?.none
        var rOnce = RemoteInteractionOption?.none
        var rAlways = RemoteInteractionOption?.none
        var custom: [RemoteInteractionOption] = []
        for option in options {
            switch Self.family(of: option.id) {
            case .allowOnce where once == nil: once = option
            case .allowAlways where always == nil: always = option
            case .rejectOnce where rOnce == nil: rOnce = option
            case .rejectAlways where rAlways == nil: rAlways = option
            case .allowOnce, .allowAlways, .rejectOnce, .rejectAlways: break // 重复项忽略
            case .custom: custom.append(option)
            }
        }
        allowOnce = once
        allowAlways = always
        rejectOnce = rOnce
        rejectAlways = rAlways
        customOptions = custom
    }

    /// web dQ 同款归一（子串判定，兼容 always allow 等变体写法）
    static func family(of id: String) -> PermissionFamily {
        let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let isAllow = normalized.contains("allow") || normalized.contains("approve")
        let isReject = normalized.contains("reject") || normalized.contains("deny")
        let isAlways = normalized.contains("always")
        if isAllow && isAlways { return .allowAlways }
        if isAllow { return .allowOnce }
        if isReject && isAlways { return .rejectAlways }
        if isReject { return .rejectOnce }
        return .custom
    }

    /// 无任一四族命中（服务端 options 全为自定义档）
    var isAllCustom: Bool {
        allowOnce == nil && allowAlways == nil && rejectOnce == nil && rejectAlways == nil
    }

    /// 档位可见性：仅本次 ⇔ allowOnce/rejectOnce 任一在场；始终允许
    /// ⇔ allowAlways/rejectAlways 任一在场。无四族时两档恒可见（规则 2 默认形态，
    /// 该形态下方向钮恒可用、optionId 走拼接）。
    var showsOnceChip: Bool { isAllCustom || allowOnce != nil || rejectOnce != nil }
    var showsAlwaysChip: Bool { isAllCustom || allowAlways != nil || rejectAlways != nil }

    /// 方向钮可用性（混合态核心）：选中档对应方向的四族项不在场则禁用
    /// （拼出即词表外 optionId，必被服务端拒）
    func canDecide(approved: Bool, always: Bool) -> Bool {
        guard !isAllCustom else { return true }
        if approved { return always ? allowAlways != nil : allowOnce != nil }
        return always ? rejectAlways != nil : rejectOnce != nil
    }

    /// 应答 optionId：优先服务端原生 id；无四族（options 空）拼规范 id；
    /// 组合无效（该方向四族缺席）返回 nil——调用方不出手
    func optionId(approved: Bool, always: Bool) -> String? {
        let matched = approved
            ? (always ? allowAlways : allowOnce)
            : (always ? rejectAlways : rejectOnce)
        if let matched { return matched.id }
        guard isAllCustom else { return nil }
        return (approved ? "allow" : "reject") + (always ? "Always" : "Once")
    }

    /// 默认档：首个两方向齐全的档；无齐全档选首个可见档
    func defaultScopeAlways() -> Bool {
        if canDecide(approved: true, always: false), canDecide(approved: false, always: false) {
            return false
        }
        if canDecide(approved: true, always: true), canDecide(approved: false, always: true) {
            return true
        }
        return !showsOnceChip && showsAlwaysChip
    }
}

// MARK: - 连接态审批卡（pendingInteractions.permission 投影：命令/路径/影响结构化排版）

/// 交互卡限高包装：内容自适应、超限内部滚动（键盘在场时底部栈不再被顶到
/// 键盘底下；构造背景见 safeAreaInset ForEach 注释）。提问卡题目较多，给更高上限。
/// 2026-11-25 待批 tab 批2 去私有：待批页（PendingApprovalsView）复用同一限高包装。
struct ApprovalCardKeyboardBound: ViewModifier {
    var maxHeight: CGFloat = 240

    func body(content: Content) -> some View {
        ScrollView(showsIndicators: false) {
            content.frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxHeight: maxHeight)
    }
}

struct ApprovalInteractionCard: View {
    // 2026-11-25 待批 tab 批2：协议化（InteractionDeciding）——会话页与待批页共用本卡；
    // 观察依赖经 @Observable 属性读取照常生效（卡内无 binding，@Bindable 已不需要）
    var viewModel: any InteractionDeciding
    let interaction: RemotePendingInteraction

    /// 授权范围两档（A-3：原 task 档在 wire 四族值域无对应，删除；
    /// 服务端携带等效自定义 id 时经 customOptions 直选呈现，不进两步矩阵）
    enum AuthScope: String, CaseIterable, Identifiable {
        case once, always
        var id: String { rawValue }
        var label: String {
            switch self {
            case .once: return "仅本次"
            case .always: return "始终允许"
            }
        }
        var identifier: String {
            switch self {
            case .once: return "05-choice-once"
            case .always: return "05-choice-always"
            }
        }
    }

    private var matrix: PermissionOptionMatrix {
        PermissionOptionMatrix(options: interaction.options)
    }

    @State private var scope: AuthScope = .once
    @State private var deciding = false
    // P2-7B「稍后处理」进行中（批准/拒绝同步 disabled，deciding 机制同款）
    @State private var snoozing = false
    // 旧桌面端无 snooze 命令（viewModel 依回执宽容判定）→ 按钮降级纯关闭
    @State private var snoozeUnsupported = false
    // A-3/U-5：决议失败如实回显（回执拒绝/未送达），按钮恢复可点、卡片不撤
    @State private var decisionError: String?
    // G21（2026-11-25）：拒绝反馈原文（freeText=true 才显示输入行；仅拒绝时携带）
    @State private var feedbackText = ""

    /// G21：反馈行在场判定（web PermissionDialog.tsx:631 hasFeedbackInput 同构——
    /// request.freeText === true 且存在拒绝方向选项；工作流 refine 选项桌面专属不在此）
    private var showsFeedbackInput: Bool {
        interaction.permissionFreeText && matrix.canDecide(approved: false, always: scope == .always)
            || interaction.permissionFreeText && (!interaction.options.isEmpty)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 13))
                    .foregroundColor(T.orange)
                Text("关键操作待批准")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                StatusPill(text: "待审批", kind: .wait, compact: true)
            }
            if let title = interaction.title, !title.isEmpty {
                structureRow(icon: "wrench.and.screwdriver", label: "类型", value: title)
            }
            if let command = interaction.command, !command.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(command)
                        .font(T.mono(12))
                        .foregroundColor(T.text)
                        .padding(T.sp2)
                        .frame(minWidth: 0, alignment: .leading)
                        .background(T.bgCode)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .accessibilityIdentifier("05-approval-cmd")
            }
            if let path = interaction.path, !path.isEmpty {
                structureRow(icon: "folder", label: "路径", value: path)
            }
            if let impact = interaction.impact, !impact.isEmpty {
                HStack(spacing: T.sp1) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 11))
                        .foregroundColor(T.orange)
                    Text(impact)
                        .font(T.font(11.5))
                        .foregroundColor(T.text2)
                }
            }
            if matrix.isAllCustom, !matrix.customOptions.isEmpty {
                // options 全为非四族 id → chips 即选项，
                // 单选即决（自带方向语义，点选即发原生 optionId），批准/拒绝两钮整组隐藏
                FlowChips(items: matrix.customOptions.map(\.label), identifierPrefix: "05-choice") { label in
                    if let option = matrix.customOptions.first(where: { $0.label == label }) {
                        // 原生 optionId 单选即决：提交型决议落朱印「照办」（motion-spec 口径）
                        decideWith(optionId: option.id, approved: true)
                    }
                }
            } else {
                // 授权范围两档 chips（answer={optionId} 拼接依据；热区 ≥44pt）。
                // 可见性按四族命中推导（规则 3）；服务端自定义档追加直选 chip
                HStack(spacing: T.sp2) {
                    if matrix.showsOnceChip {
                        scopeChip(.once)
                    }
                    if matrix.showsAlwaysChip {
                        scopeChip(.always)
                    }
                    ForEach(matrix.customOptions) { option in
                        directChip(option)
                    }
                    Spacer()
                }
                // G21：拒绝反馈输入行（web PermissionDialog.tsx:1193-1232 同构——独立
                // 可选项行，placeholder「告诉模型接下来应该怎么做...」；trim 非空才携带）
                if showsFeedbackInput {
                    TextField(
                        String(localized: "告诉模型接下来应该怎么做..."),
                        text: $feedbackText,
                        axis: .vertical)
                        .font(T.font(12.5))
                        .lineLimit(1...3)
                        .padding(T.sp2)
                        .background(T.bgInput)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                        .accessibilityIdentifier("05-approval-feedback")
                }
                HStack(spacing: 10) {
                    Button {
                        decide(approved: false)
                    } label: {
                        Text("拒绝")
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.red)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                    }
                    .disabled(deciding || snoozing
                              || !matrix.canDecide(approved: false, always: scope == .always))
                    .accessibilityIdentifier("05-act-reject")

                    Button {
                        decide(approved: true)
                    } label: {
                        Text("批准执行")
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.onAccent)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(T.accent)
                            .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                    .disabled(deciding || snoozing
                              || !matrix.canDecide(approved: true, always: scope == .always))
                    .accessibilityIdentifier("05-act-approve")
                }
            }
            if let decisionError {
                // U-5：失败态错误行（controlFeedback 口径，reasonCode 透出）
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-approval-decision-error")
            }
            // P2-7B「稍后处理」（批准/拒绝行下方第三动作，text3 居中行，
            // 样式照抄 ApprovalSheetView 同名动作）。成功后卡片由桌面 pendingInteractions
            // 回流撤下（>1s 未至 viewModel 本地遮罩兜底）；hint 走 viewModel.snoozeFeedback
            // 通道（卡片可能随即收起，提示不能挂在卡片内）。命令不存在（旧桌面端）时
            // 降级为纯关闭——仅本地收起，不改桌面挂起态
            TextActionButton(
                title: snoozeUnsupported
                    ? "关闭"
                    : (snoozing ? "稍后中…" : "稍后处理"),
                tint: T.text3,
                action: {
                    if snoozeUnsupported {
                        Task { await viewModel.dismissInteractionLocally(interaction) }
                    } else {
                        snooze()
                    }
                },
                identifier: "05-approval-act-later")
                .disabled(snoozing)
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.55), lineWidth: 1))
        // 首帧按服务端 options 组合定默认档（首个两方向齐全的档）
        .onAppear {
            scope = matrix.defaultScopeAlways() ? .always : .once
        }
        // 透明容器：容器可定位（05-approval-card），子元素保留各自 identifier
        // （05-choice-*/05-act-*；否则容器 identifier 会覆盖全部后代，门禁实证）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-approval-card")
    }

    /// 范围 chip（44pt 胶囊，ApprovalSheetView chips 化后两处同构）
    private func scopeChip(_ item: AuthScope) -> some View {
        Button {
            scope = item
        } label: {
            HStack(spacing: T.sp1) {
                Text(item.label)
                if item == .always {
                    Text("需谨慎")
                        .font(T.font(10.5, .semibold))
                        .foregroundColor(scope == item ? T.onAccent : T.red)
                }
            }
            .font(T.font(12, .medium))
            .foregroundColor(scope == item ? T.onAccent : T.text2)
            .padding(.horizontal, T.sp2)
            .frame(minHeight: 44)
            .background(scope == item ? T.accent : T.bgInput)
            .clipShape(Capsule())
        }
        .accessibilityIdentifier(item.identifier)
    }

    /// 服务端自定义选项 chip（单选即决：点选即发该原生 optionId）
    private func directChip(_ option: RemoteInteractionOption) -> some View {
        Button {
            // 服务端自定义直选 chip：同「单选即决」口径，落朱印「照办」
            decideWith(optionId: option.id, approved: true)
        } label: {
            Text(option.label)
                .font(T.font(12, .medium))
                .foregroundColor(T.text2)
                .padding(.horizontal, T.sp2)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
        }
        .disabled(deciding || snoozing)
        .accessibilityIdentifier("05-choice-custom-\(option.id)")
    }

    private func structureRow(icon: String, label: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 11))
                .foregroundColor(T.text3)
                .frame(width: 20)
            Text(label)
                .font(T.font(11.5))
                .foregroundColor(T.text3)
            Text(value)
                .font(T.mono(11))
                .foregroundColor(T.text2)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 0)
        }
    }

    /// 两步矩阵决议：按 scope 拼/选 optionId（A-3：answer={optionId}；
    /// G21：拒绝方向携带反馈文本框原文）
    private func decide(approved: Bool) {
        guard let optionId = matrix.optionId(approved: approved, always: scope == .always) else {
            decisionError = String(localized: "该授权范围无对应选项，请切换范围")
            return
        }
        decideWith(
            optionId: optionId, approved: approved,
            feedback: approved ? nil : feedbackText)
    }

    /// 决议下发 + 如实回执（U-5：失败不撤卡、错误行透出 reasonCode；权威成功 →
    /// VM 置盖章停驻，卡面落印 700ms 后随投影/停驻离场）
    private func decideWith(optionId: String, approved: Bool, feedback: String? = nil) {
        deciding = true
        Task {
            if let failure = await viewModel.decide(
                interaction, optionId: optionId, approved: approved, feedback: feedback) {
                decisionError = failure
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } else {
                decisionError = nil
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            deciding = false
        }
    }

    /// 「稍后处理」（snoozeInteractionAutoResolution；hint 由 viewModel.snoozeFeedback
    /// 通道给出）。旧桌面端无该命令（回执宽容判定）→ 按钮降级纯关闭
    private func snooze() {
        snoozing = true
        Task {
            let failure = await viewModel.snoozeInteraction(interaction)
            snoozing = false
            if failure != nil, viewModel.snoozeUnsupported {
                snoozeUnsupported = true
            }
        }
    }
}

// MARK: - 插件安装卡（G23：kind="pluginInstall"；web PluginInstallCard.tsx 移动端同构）
//
// 应答形状【实证·上游仓 V4InteractionDialogs.tsx:357-367】：answer 只带
// {optionId: "install"|"skip"}（choice 字面量直接作 optionId）；确认按钮文案按
// request.action("install"|"enable") × request.scope("user"|"workspace") 分叉
// （PluginInstallCard.tsx:68-98）。此卡此前因未知 kind 落 EmptyView 静默丢弃。

struct PluginInstallInteractionCard: View {
    var viewModel: any InteractionDeciding
    let interaction: RemotePendingInteraction

    @State private var deciding = false
    @State private var decisionError: String?

    /// 确认按钮文案四分叉（web PluginInstallCard.tsx:68-98 同表）
    private var confirmLabel: String {
        let enable = interaction.pluginAction == "enable"
        let workspace = interaction.pluginScope == "workspace"
        switch (enable, workspace) {
        case (true, true): return String(localized: "在工作区启用")
        case (true, false): return String(localized: "启用")
        case (false, true): return String(localized: "安装并在工作区启用")
        default: return String(localized: "安装并启用")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "puzzlepiece.extension.fill")
                    .font(.system(size: 13))
                    .foregroundColor(T.seal)
                Text("插件安装请求")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                StatusPill(text: "待审批", kind: .wait, compact: true)
            }
            if let name = interaction.pluginName, !name.isEmpty {
                Text(name)
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text)
                    .accessibilityIdentifier("05-plugin-name")
            }
            if let desc = interaction.pluginDescription, !desc.isEmpty {
                Text(desc)
                    .font(T.font(11.5))
                    .foregroundColor(T.text2)
            }
            if let reason = interaction.pluginReason, !reason.isEmpty {
                HStack(alignment: .top, spacing: T.sp1) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 11))
                        .foregroundColor(T.orange)
                    Text(reason)
                        .font(T.font(11.5))
                        .foregroundColor(T.text2)
                }
            }
            HStack(spacing: 10) {
                Button {
                    respond("skip")
                } label: {
                    Text("跳过")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.text2)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-plugin-skip")

                Button {
                    respond("install")
                } label: {
                    Text(confirmLabel)
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.onSeal)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.seal)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-plugin-install")
            }
            if let decisionError {
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-plugin-decision-error")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.seal.opacity(0.5), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-plugin-card")
    }

    /// 应答下发（optionId 字面量；U-5 失败错误行同口径）
    private func respond(_ optionId: String) {
        deciding = true
        Task {
            if let failure = await viewModel.decide(
                interaction, optionId: optionId, approved: optionId == "install",
                feedback: nil) {
                decisionError = failure
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } else {
                decisionError = nil
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            deciding = false
        }
    }
}

// MARK: - 计划审批卡（G-017：plan_approval 结构化渲染——计划文本 + 放行/驳回；
// 与普通命令审批卡互不影响；决议复用 resolveInteraction 桌面代执行）

struct PlanApprovalCard: View {
    // 2026-11-25 待批 tab 批2：协议化（InteractionDeciding）——与 ApprovalInteractionCard 同口径
    var viewModel: any InteractionDeciding
    let interaction: RemotePendingInteraction
    @State private var deciding = false
    // A-3/U-5：计划决议失败如实回显（回执拒绝/未送达），卡片不撤、按钮恢复
    @State private var decisionError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "list.clipboard")
                    .font(.system(size: 13))
                    .foregroundColor(T.blue)
                Text("计划待确认")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                StatusPill(text: "待审批", kind: .wait, compact: true)
            }
            if let title = interaction.title, !title.isEmpty {
                Text(title)
                    .font(T.font(12.5))
                    .foregroundColor(T.text2)
                    .lineLimit(2)
            }
            if let plan = interaction.planText, !plan.isEmpty {
                // 短计划直接 Text 自适应高度（ScrollView 恒撑满 maxHeight 上限，
                // 内容少时卡内出现大片空白——UI/UX 验收 P1 2026-10-07 实证：三行
                // 计划下方 ~160pt 空白）；超长文本才进滚动容器（上限 220）
                Group {
                    if plan.count > 240 {
                        ScrollView {
                            Text(plan)
                                .font(T.font(12))
                                .foregroundColor(T.text2)
                                .lineSpacing(4)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(maxHeight: 220)
                    } else {
                        Text(plan)
                            .font(T.font(12))
                            .foregroundColor(T.text2)
                            .lineSpacing(4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(T.sp2)
                .background(T.bgCode)
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
                .accessibilityIdentifier("05-plan-body")
            }
            HStack(spacing: 10) {
                Button {
                    decide(approved: false)
                } label: {
                    Text("驳回计划")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.red)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-plan-reject")

                Button {
                    decide(approved: true)
                } label: {
                    Text("放行计划")
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-act-plan-approve")
            }
            if let decisionError {
                // U-5：失败态错误行（「计划决议」语境，controlFeedback 口径）
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-plan-decision-error")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.blue.opacity(0.55), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-plan-card")
    }

    /// A-3：计划族 answer={action:"accept"|"decline"}（原 approved/scope 平铺形态是
    /// wire 不存在的键）。cancel 不设按钮——「稍后处理」snooze 独立命令承担挂起语义
    /// ；content（可选理由）首版不做输入，键缺省不发。
    private func decide(approved: Bool) {
        deciding = true
        Task {
            if let failure = await viewModel.decidePlan(interaction, action: approved ? "accept" : "decline") {
                decisionError = failure
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } else {
                decisionError = nil
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            deciding = false
        }
    }
}

// MARK: - workspace hook 信任审核卡（web 对齐 2026-10-06：pendingInteractions
// payload.kind='workspaceHookReview' 的专属卡——应答走 respondWorkspaceHookReview
//（trust_selected+reviewItemIds，web 实证唯一 action），与权限审批卡是姊妹 kind。
// 闭包驱动（ChatView 经 ChatViewModel / ApprovalSheetView 直连 conversationStore
// 两路复用同一 UI）；写面（信任/撤销）均带确认弹层，失败经 error 行如实透出）

struct WorkspaceHookReviewCard: View {
    let interaction: RemotePendingInteraction
    /// 信任所选（入参=reviewItemIds；返回失败文案，nil=成功/已受理）
    let onTrust: ([String]) async -> String?
    /// 重新请求审核（返回失败文案，nil=成功/已受理）
    let onRequest: () async -> String?
    /// 撤销已信任项（入参=reviewItemIds；UI 侧确认弹层后由本卡调用）
    let onRevoke: (([String]) async -> String?)?

    @State private var selectedIds: Set<String> = []
    @State private var deciding = false
    @State private var requesting = false
    @State private var decisionError: String?
    @State private var showTrustConfirm = false
    @State private var pendingRevokeItem: WorkspaceHookReviewItem?

    private var items: [WorkspaceHookReviewItem] { interaction.hookReviewItems }
    private var selectedItems: [WorkspaceHookReviewItem] {
        items.filter { selectedIds.contains($0.id) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            header
            summaryRow
            if items.isEmpty {
                emptyHint
            } else {
                ForEach(items) { item in
                    itemRow(item)
                }
                trustActionButton
            }
            if let decisionError {
                Text(decisionError)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-hook-decision-error")
            }
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.55), lineWidth: 1))
        .onAppear {
            preselectPendingItems()
        }
        // 审核项随快照/补拉晚于首帧到达时补一次默认全选（onAppear 已过）
        .onChange(of: interaction.hookReviewItems) {
            preselectPendingItems()
        }
        // 信任确认（写桌面信任账本——放行 hook 在桌面执行；.alert 承载，confirmationDialog
        // 本 OS 渲染成锚定 popover——P2ExtrasViews.swift:65 同款先例）
        .alert(
            String(localized: "信任所选 \(selectedItems.count) 个 hook 项？"),
            isPresented: $showTrustConfirm) {
            Button("信任") {
                Task { await trustSelected() }
            }
            .accessibilityIdentifier("05-hook-confirm-trust")
            Button("取消", role: .cancel) {}
        } message: {
            Text("桌面端将按信任账本放行所选 hook 的执行。")
        }
        // 撤销确认（可逆性未知——从严 destructive）
        .alert(
            pendingRevokeItem.map { item in
                item.title.map { "撤销「\($0)」的信任？" } ?? "撤销该 hook 项的信任？"
            } ?? "",
            isPresented: Binding(
                get: { pendingRevokeItem != nil },
                set: { if !$0 { pendingRevokeItem = nil } })) {
            Button("撤销信任", role: .destructive) {
                if let item = pendingRevokeItem {
                    Task { await revoke(item) }
                }
                pendingRevokeItem = nil
            }
            .accessibilityIdentifier("05-hook-confirm-revoke")
            Button("取消", role: .cancel) { pendingRevokeItem = nil }
        } message: {
            Text("桌面端将不再放行该 hook，下次触发会重新进入审核。")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-hook-card")
    }

    private var header: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "curlybraces.square")
                .font(.system(size: 13))
                .foregroundColor(T.orange)
            Text("工作区 Hook 待信任")
                .font(T.font(13.5, .semibold))
                .foregroundColor(T.text)
            Spacer()
            StatusPill(text: String(localized: "待信任"), kind: .wait, compact: true)
        }
    }

    /// 默认全选待信任项（审核项本就是待信任集——web 逐项信任按钮的批量等价）；
    /// 已信任项不预选（撤销语义独立）。仅在无手选时执行，不覆盖用户取消。
    private func preselectPendingItems() {
        guard selectedIds.isEmpty else { return }
        let pending = items.filter { !$0.isTrusted }.map(\.id)
        guard !pending.isEmpty else { return }
        selectedIds = Set(pending)
    }

    @ViewBuilder
    private var summaryRow: some View {
        if let summary = interaction.impact ?? interaction.title, !summary.isEmpty {
            Text(summary)
                .font(T.font(12))
                .foregroundColor(T.text2)
                .lineLimit(3)
                .accessibilityIdentifier("05-hook-summary")
        }
    }

    /// 审核项未随交互到达（元素 schema 未取证/桌面未携）——诚实空态 + 重发请求入口，
    /// 不虚构条目（U-5 同口径：无数据不假成功）
    private var emptyHint: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("审核项尚未同步到移动端")
                .font(T.font(12))
                .foregroundColor(T.text3)
            requestButton
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(T.sp2)
        .background(T.bgCode)
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
    }

    private var requestButton: some View {
        Button {
            Task { await requestReview() }
        } label: {
            HStack(spacing: 5) {
                if requesting {
                    SpinnerView(size: 11)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11))
                }
                Text(requesting ? "请求中…" : "重新请求审核")
                    .font(T.font(11.5, .medium))
            }
            .foregroundColor(T.accentText)
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 30)
            .background(T.accentDim)
            .clipShape(Capsule())
        }
        .disabled(requesting || deciding)
        .accessibilityIdentifier("05-hook-act-request")
    }

    /// 单项行：选择圈（待信任项）+ 标题/说明 + 状态胶囊；已信任项呈撤销入口
    private func itemRow(_ item: WorkspaceHookReviewItem) -> some View {
        HStack(alignment: .top, spacing: T.sp2) {
            if item.isTrusted {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 14))
                    .foregroundColor(T.accentText)
                    .frame(width: 24)
            } else {
                Button {
                    if selectedIds.contains(item.id) {
                        selectedIds.remove(item.id)
                    } else {
                        selectedIds.insert(item.id)
                    }
                } label: {
                    Image(systemName: selectedIds.contains(item.id)
                          ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 16))
                        .foregroundColor(selectedIds.contains(item.id) ? T.accentText : T.text3)
                        .frame(width: 24)
                        .frame(minHeight: 30)
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-hook-select-\(item.id)")
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title ?? item.id)
                    .font(T.font(12.5, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if let detail = item.detail, !detail.isEmpty {
                    Text(detail)
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            if let trustState = item.trustState, !trustState.isEmpty {
                Text(trustState == "trusted_persistent" ? String(localized: "已信任") : trustState)
                    .font(T.font(10))
                    .foregroundColor(item.isTrusted ? T.accentText : T.text3)
            }
            if item.isTrusted, onRevoke != nil {
                Button {
                    pendingRevokeItem = item
                } label: {
                    Text("撤销")
                        .font(T.font(11, .medium))
                        .foregroundColor(T.red)
                }
                .disabled(deciding)
                .accessibilityIdentifier("05-hook-revoke-\(item.id)")
            }
        }
        .padding(.horizontal, T.sp2)
        .padding(.vertical, 4)
        .background(T.bgCode.opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-hook-item-\(item.id)")
    }

    /// 信任所选（主按钮；无待信任项时整组不渲染——空态由 emptyHint 承载）
    @ViewBuilder
    private var trustActionButton: some View {
        let pendingItems = items.filter { !$0.isTrusted }
        if !pendingItems.isEmpty {
            HStack(spacing: T.sp2) {
                Button {
                    showTrustConfirm = true
                } label: {
                    Text(deciding ? String(localized: "信任中…") : String(localized: "信任所选（\(selectedItems.count)）"))
                        .font(T.font(14, .semibold))
                        .foregroundColor(selectedItems.isEmpty ? T.text3 : T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(selectedItems.isEmpty ? T.bgInput : T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .disabled(deciding || selectedItems.isEmpty)
                .accessibilityIdentifier("05-hook-act-trust")
                requestButton
            }
        }
    }

    private func trustSelected() async {
        guard !selectedItems.isEmpty else { return }
        deciding = true
        let ids = selectedItems.map(\.id)
        if let failure = await onTrust(ids) {
            decisionError = failure
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        } else {
            decisionError = nil
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            // 卡片撤下依赖桌面 pendingInteractions 回流；失败态卡片保留可重试
        }
        deciding = false
    }

    private func requestReview() async {
        requesting = true
        if let failure = await onRequest() {
            decisionError = failure
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        } else {
            decisionError = nil
        }
        requesting = false
    }

    private func revoke(_ item: WorkspaceHookReviewItem) async {
        guard let onRevoke else { return }
        deciding = true
        if let failure = await onRevoke([item.id]) {
            decisionError = failure
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        } else {
            decisionError = nil
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        deciding = false
    }
}

// MARK: - 排队消息条（桌面 composer pending 队列同构：立即 / 编辑 / 删除 / 上移 / 自动排空）

struct QueueBarView: View {
    let queue: ConversationQueueInfo
    var onSendNow: (String) async -> String?
    var onDelete: (String) async -> String?
    var onEdit: (String, String) async -> String?
    var onMoveUp: (String) async -> String?
    /// G13 长按拖排序（web ConversationQueuePanel 桌面 hover-drag 的触屏对位；
    /// beforeId = 落点行 id——把拖起项插到它之前，nil = 拖到队尾）
    var onReorder: ((String, String?) async -> String?)? = nil
    var onToggleAutoDrain: (Bool) async -> String?
    // 编辑锁族（web composer-edit-mode 同构）：begin=持锁进入编辑 / cancel=释放 /
    // releaseLock=解除其他端遗留锁 / lockOwner=归属三态判定
    var onBeginEdit: (String) async -> String?
    var onCancelEdit: (String) async -> Void
    var onReleaseLock: (String) async -> String?
    var lockOwner: (RemoteQueueItem) -> QueueEditLockOwner?

    @State private var editingItemId: String?
    @State private var editingText = ""
    @State private var feedback: String?
    @State private var feedbackClear: Task<Void, Never>?
    // G13 拖拽态：拖起项 id + 当前悬停落点（高亮描边反馈）
    @State private var draggingItemId: String?
    @State private var dropTargetId: String?

    private func showFeedback(_ message: String?) {
        guard let message else { return }
        feedbackClear?.cancel()
        feedback = message
        feedbackClear = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { feedback = nil }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp1) {
            HStack(spacing: T.sp2) {
                Image(systemName: "square.stack.3d.up.fill")
                    .font(.system(size: 11))
                    .foregroundColor(T.seal)
                Text(String(localized: "排队中 \(queue.items.count)"))
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
                Button {
                    Task {
                        if let message = await onToggleAutoDrain(!queue.autoDrain) {
                            showFeedback(message)
                        }
                    }
                } label: {
                    Text(queue.autoDrain
                        ? String(localized: "自动排空 · 开")
                        : String(localized: "自动排空 · 关"))
                        .font(T.font(10.5, .semibold))
                        .foregroundColor(queue.autoDrain ? T.accentText : T.text3)
                        .padding(.horizontal, T.sp2)
                        .padding(.vertical, 3)
                        .background(queue.autoDrain ? T.accentDim.opacity(0.5) : T.bgInput)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-queue-act-autodrain")
            }
            ForEach(queue.items) { item in
                queueRow(item)
            }
            if let message = feedback {
                Text(message)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
            }
        }
        .padding(.horizontal, T.sp4)
        .padding(.vertical, T.sp2)
        .background(T.tabbarBg)
        .overlay(alignment: .top) { Divider().overlay(T.border) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-queue-bar")
    }

    /// 单条排队消息（立即 / 编辑 / 删除 / 上移；guide 模式条目标注）
    private func queueRow(_ item: RemoteQueueItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if editingItemId == item.id {
                HStack(spacing: T.sp2) {
                    TextField("编辑排队内容", text: $editingText, axis: .vertical)
                        .font(T.font(12.5))
                        .foregroundColor(T.text)
                        .lineLimit(1...4)
                        .padding(.horizontal, T.sp2)
                        .padding(.vertical, 6)
                        .background(T.bgInput)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    Button {
                        Task {
                            let message = await onEdit(item.id, editingText)
                            if message == nil {
                                editingItemId = nil
                            } else {
                                showFeedback(message)
                            }
                        }
                    } label: {
                        Text(String(localized: "保存"))
                            .font(T.font(11.5, .semibold))
                            .foregroundColor(T.onAccent)
                            .padding(.horizontal, T.sp2)
                            .frame(minHeight: 30)
                            .background(T.accent)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    Button {
                        Task { await onCancelEdit(item.id) } // 释放编辑锁
                        editingItemId = nil
                    } label: {
                        Text(String(localized: "取消"))
                            .font(T.font(11.5))
                            .foregroundColor(T.text2)
                    }
                    .buttonStyle(.plain)
                }
            } else {
                let owner = lockOwner(item)
                HStack(alignment: .top, spacing: T.sp2) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.text)
                            .font(T.font(12))
                            .foregroundColor(owner == .other ? T.text2 : T.text)
                            .lineLimit(2)
                        if item.isGuide {
                            Text(String(localized: "引导模式"))
                                .font(T.mono(9.5))
                                .foregroundColor(T.seal)
                        }
                    }
                    Spacer(minLength: 0)
                    if owner == .other {
                        // 其他端持锁（CLI 编辑锁）：锁定行隐藏 立即/编辑/删除/上移，
                        // 显示「其他端编辑中」+「解除」（web ConversationQueuePanel:297-318 同构）
                        Text(String(localized: "其他端编辑中"))
                            .font(T.font(10.5))
                            .foregroundColor(T.text3)
                        rowAction(icon: "lock.open", label: String(localized: "解除")) {
                            let message = await onReleaseLock(item.id)
                            showFeedback(message)
                        }
                        .accessibilityIdentifier("05-queue-act-release-\(item.id)")
                    } else {
                        // 立即发送（桌面「立即」同语义：sendQueuedNow，CAS）
                        rowAction(icon: "arrow.up.circle.fill", label: String(localized: "立即")) {
                            let message = await onSendNow(item.id)
                            showFeedback(message)
                        }
                        .accessibilityIdentifier("05-queue-act-send-\(item.id)")
                        rowAction(icon: "pencil", label: String(localized: "编辑")) {
                            Task {
                                if let message = await onBeginEdit(item.id) {
                                    showFeedback(message)
                                } else {
                                    editingText = item.text
                                    editingItemId = item.id
                                }
                            }
                        }
                        .accessibilityIdentifier("05-queue-act-edit-\(item.id)")
                        if queue.items.first?.id != item.id {
                            rowAction(icon: "arrow.up.to.line", label: "") {
                                let message = await onMoveUp(item.id)
                                showFeedback(message)
                            }
                        }
                        rowAction(icon: "trash", label: "") {
                            let message = await onDelete(item.id)
                            showFeedback(message)
                        }
                        .accessibilityIdentifier("05-queue-act-delete-\(item.id)")
                    }
                }
                .padding(.horizontal, T.sp2)
                .padding(.vertical, 6)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
                .overlay(
                    RoundedRectangle(cornerRadius: T.rS).stroke(
                        dropTargetId == item.id && draggingItemId != nil && draggingItemId != item.id
                            ? T.seal : T.border,
                        lineWidth: dropTargetId == item.id && draggingItemId != nil ? 1.5 : 1))
                // G13 长按拖排序（onDrag 触屏长按即起；DropDelegate 管落点高亮与
                // 松手 reorder——isTargeted Binding 形态给不了逐行回调，delegate 同规）
                .onDrag {
                    draggingItemId = item.id
                    return NSItemProvider(object: item.id as NSString)
                }
                .onDrop(
                    of: [UTType.text],
                    delegate: QueueDropDelegate(
                        item: item,
                        draggingItemId: $draggingItemId,
                        dropTargetId: $dropTargetId,
                        onDrop: { draggedId, targetId in
                            Task {
                                let message = await onReorder?(draggedId, targetId)
                                showFeedback(message)
                            }
                        }))
            }
        }
    }

    private func rowAction(
        icon: String, label: String,
        action: @escaping () async -> Void
    ) -> some View {
        Button {
            Task { await action() }
        } label: {
            HStack(spacing: 2) {
                Image(systemName: icon)
                    .font(.system(size: 11))
                if !label.isEmpty {
                    Text(label).font(T.font(10.5, .semibold))
                }
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, label.isEmpty ? 6 : T.sp2)
            .frame(minHeight: 28)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Composer（工具行 + 44px 胶囊输入行；输入框 16px 防聚焦缩放）
// v3 纠偏：连接态输入区可用（sendText 桌面代执行）；工具行连接态展示桌面端模型
// 只读 chips（model-selection.getView 真实数据，缺数据不渲染），演示态沿用本机设置。

struct ComposerBar: View {
    @Environment(AppSettingsModel.self) private var settings
    @Environment(AppSession.self) private var session
    /// 管理模型页跳转（2026-11-25 批4：模型选择器页脚「管理模型」→ 设置.模型）
    @Environment(AppRouter.self) private var router
    @Bindable var viewModel: ChatViewModel
    /// 输入焦点（宿主 ChatView 所有：面板展开等场景需跨视图精确失焦——iOS 26
    /// sendAction(resignFirstResponder) 不可靠，FocusState 绑定是唯一稳定通道）
    var inputFocused: FocusState<Bool>.Binding
    /// G15 @ 提及面板唤起（宿主持有 showMentionPicker 状态——面板 sheet 挂宿主根）
    var onMention: () -> Void = {}
    /// U-6 发送失败持久错误行（非 3s 自动消失）：失败时置位 + 草稿已由 viewModel
    /// 回填；清除时机 = 发送成功 / 用户手动编辑 draft / 离开会话（数据安全提示
    /// 必须显式关闭）
    @State private var sendFailure: String?
    @State private var retryingSend = false
    /// U-6：未送达时回填的草稿快照——用户手动编辑（draft 与快照不一致）即清错误行
    @State private var restoredDraftText: String?
    /// 执行目标偏好（G-012 如实口径）：☁️ 云端沙盒 / 💻 我的 Mac——当前仅记录发送偏好
    /// 并持久化；sendText 信封无目标路由字段，消息仍经当前连接的会话链路下发
    /// （客户端发命令、桌面代执行边界不变）。UI 已按验收 B 以「偏好」如实标注。
    @State private var executionTarget = DeviceOption.cloudSandbox

    // P1-1 附件三来源（底部 sheet 拍照/照片图库/文件）
    @State private var showAttachmentSource = false
    /// 来源接力（sheet onDismiss 后再呈现相机/图库/文件选择器，避免同视图双呈现竞态）
    @State private var pendingAttachmentSource: AttachmentSource?
    @State private var showPhotoPicker = false
    @State private var showCamera = false
    @State private var showFileImporter = false
    @State private var photoPickerItems: [PhotosPickerItem] = []

    // P2-7 压缩入口（contextMeter 即入口，点击弹确认；进行中不可再点）
    @State private var showCompactConfirm = false
    // P1-2 模式行一次性标注（协作模式无桌面读面，首屏显示本机偏好）
    @State private var modeDisclaimerShown = false

    // 五选择器统一自绘底部面板（用户 2026-10-07：系统 Menu 弹层与 App 设计风格
    // 不符——AttachmentSourceSheet 同款语言、主题令牌自适配系统深浅色）。
    // ComposerSheetKind / ComposerOptionSheet 定义见 ComposerOptionSheet.swift
    @State private var composerSheet: ComposerSheetKind?
    /// 「重新获取」重开标记：面板 onDismiss（关闭完成态）再呈现——面板在场时直接
    /// 置 composerSheet 会撞 present-while-dismissing（NewConversationSheet 同款先例）
    @State private var reopenModelPanelAfterDismiss = false
    /// 二级模型选择器（2026-11-25 批4：替换扁平模型长列表）
    @State private var showModelPicker = false

    var body: some View {
        composerOverlays(
        VStack(spacing: T.sp2) {
            // HIDDEN(对齐修复): composer 执行目标菜单隐藏（sendText 信封无目标路由字段，
            // 「仅记录偏好」的本地菜单构成假选择——隐藏入口 H5）· 恢复条件：sendText 有
            // 目标路由字段。E2E 兼容：-ZCodeDemoData 演示开关下保留（FeatureCompletion
            // test07 目标选择器用例依赖该行）；ExecutionTargetStore 本体保留（持久化面）
            if AppSession.isDemoDataEnabled {
                targetRow
            }
            // P1-2 模式行（独立 modeRow 插在 targetRow 与 switchHint 之间
            // ——不挤 targetRow 一行，窄屏溢出；仅连接态渲染，同 remoteChips 口径）
            if viewModel.isReadOnly {
                modeRow
            }
            // 协作模式切换失败/未知态持久错误行（2026-10-09「计划模式丢失」修复：
            // 此前仅 3s 轻提示，错过即「点了没反应」；拒因原文常驻直至下次成功切换）
            if let modeFailure = viewModel.collaborationModeFailureText {
                HStack(spacing: T.sp2) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundColor(T.red)
                    Text(modeFailure)
                        .font(T.font(12))
                        .foregroundColor(T.red)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("05-composer-mode-fail")
            }
            // 模型/思考切换失败/未知态持久错误行（2026-10-09「选了没有变化」修复另一面：
            // 拒因 3s 闪过即「点了没反应」——拒因原文常驻直至下次权威成功切换）
            if let modelFailure = viewModel.modelSwitchFailureText {
                HStack(spacing: T.sp2) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundColor(T.red)
                    Text(modelFailure)
                        .font(T.font(12))
                        .foregroundColor(T.red)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("05-composer-model-fail")
            }
            // U-6 发送失败持久错误行（优先级高于 3s 轻提示——数据安全提示不自动消失）
            if let sendFailure {
                HStack(spacing: T.sp2) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundColor(T.red)
                    Text(sendFailure)
                        .font(T.font(12))
                        .foregroundColor(T.red)
                    Spacer(minLength: 0)
                    Button {
                        Task { await retrySend() }
                    } label: {
                        Text(retryingSend ? String(localized: "重试中…") : String(localized: "重试"))
                            .font(T.font(12, .semibold))
                            .foregroundColor(T.red)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .disabled(retryingSend)
                    .accessibilityIdentifier("05-composer-send-retry")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("05-composer-send-fail")
            } else if viewModel.isCompacting {
                // P2-7 压缩进行中（提示不自动消失，覆盖 3s 清除口径；switchHint 同通道）
                Text(String(localized: "压缩中…"))
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-composer-compact-hint")
            } else if let message = switchHint {
                Text(message)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-composer-switch-hint")
            }
            // P1-1 待发附件条 + 失败/提示行（无待发不渲染整行——「无排队不渲染」同口径）
            if !viewModel.uploads.isEmpty {
                attachmentStrip
            }
            if let hint = viewModel.uploads.hint {
                Text(hint)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-attach-hint")
            } else if let failure = viewModel.uploads.firstFailureText {
                Text(failure)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-attach-fail-hint")
            }
            // 斜杠命令反馈行（/goal /plan /compact 下发结果；3s 自动清除，05-attach-hint 同款）
            if let slashHint = viewModel.slashCommandHint {
                Text(slashHint)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("05-slash-hint")
            }
            if let selection = viewModel.modelSelection {
                remoteChips(selection)
            } else {
                toolsRow
            }
            // 斜杠命令菜单（连接态；draft 为 "/" + 纯字母前缀且未进入参数段时呈现。
            // web 同构：桌面 config slashCommands + 内建集；选中插入 "/name " 由用户
            // 补参数后发送——/goal /plan /compact 客户端拦截转命令，其余原文直发）
            if viewModel.isReadOnly, !slashMatches.isEmpty {
                slashMenu
            }
            // 3.17.2 输入预测 ghost hint（灰字续写，点按采纳拼到草稿尾部）。仅连接态 +
            // Host 能力门开启时 store 才返候选；无 hint 不渲染整行（同附件条「无排队不渲染」）
            if viewModel.isReadOnly, let hint = viewModel.inputHint {
                Button {
                    viewModel.acceptInputHint()
                    UISelectionFeedbackGenerator().selectionChanged()
                } label: {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "text.insert")
                            .font(.system(size: 11))
                        Text(hint)
                            .font(T.font(13))
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Spacer(minLength: 0)
                        Text(String(localized: "点按采纳"))
                            .font(T.font(10.5))
                    }
                    .foregroundColor(T.text2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .accessibilityIdentifier("05-input-hint")
            }
            inputRow
        }
        .padding(.horizontal, T.sp4)
        .padding(.top, T.sp2)
        .padding(.bottom, T.sp2)
        .background(T.tabbarBg)
        .overlay(alignment: .top) { Divider().overlay(T.border) }
        .task(id: viewModel.conversationID) {
            // 恢复该会话执行目标（per-conversation → 全局默认 → 云端沙盒）
            executionTarget = ExecutionTargetStore.target(for: viewModel.conversationID)
        }
        // U-6：用户手动编辑草稿（与回填快照不一致）即清发送失败行；回填写回本身
        // 不触发（写回后 draft == 快照）。离开会话视图销毁，行随之消失
        .onChange(of: viewModel.draft) { _, newDraft in
            if let restored = restoredDraftText, newDraft != restored {
                sendFailure = nil
                restoredDraftText = nil
            }
            // 3.17.2 输入预测：非空草稿停顿即预测续写；空草稿仅失焦+空闲时预测下一条
            //（「输入框为空、失焦且会话空闲」——聚焦清空〔发送后〕不立即触发）
            if !newDraft.isEmpty || !inputFocused.wrappedValue {
                viewModel.scheduleInputPrediction()
            } else {
                viewModel.invalidateInputHint()
            }
        }
        // 添加附件来源选择：自绘底部 sheet——confirmationDialog 在本
        // OS 版本渲染成锚定 popovers（用户报障 2026-10-07「弹窗都弹到什么地方去了」，
        // P2ExtrasViews.swift:65 同款先例改 .alert），拍照/图库/文件三通道改经
        // pendingAttachmentSource 于 onDismiss 接力呈现（避免 sheet 套 sheet 竞态）
        )
    }

    /// composer 弹层簇（附件来源/选择器面板/模型二级/压缩确认/图库/文件/相机）。
    /// 独立函数拆解 body 表达式复杂度（2026-11-25 批4 编译实证：追加模型选择器后
    /// ComposerBar body 单表达式超时 unable to type-check in reasonable time）。
    @ViewBuilder
    private func composerOverlays(_ base: some View) -> some View {
        base
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
        // 五选择器自绘面板（协作/投递/执行目标/思考；模型已迁二级选择器）：行按钮
        // label=项名——test07 以 app.buttons label 精确匹配「云端沙盒 / E2E-Relay-Mac」、
        // test12 以 label == 'low' 匹配思考档，行内不得混入副文案（聚合 label 失配即门禁红）；
        // identifier 保留 05-chip-mode / 05-chip-delivery / 05-act-target 于触发胶囊
        .sheet(item: $composerSheet, onDismiss: nil) { kind in
            ComposerOptionSheet(
                title: kind.title,
                footer: kind.footer,
                options: composerSheetOptions(kind)) { option in
                composerSheet = nil
                applyComposerSheetPick(kind, option)
            }
            .presentationDetents(composerSheetDetents(kind))
            .presentationDragIndicator(.hidden)
            .presentationBackground(T.bgElevated)
        }
        // 二级模型选择器（2026-11-25 批4）：一级搜索+最近+分组下钻，二级组内单选+
        // 思考分段；「重新获取」重开经 onDismiss 链（present-while-dismissing 同旧先例）
        .sheet(isPresented: $showModelPicker, onDismiss: {
            if reopenModelPanelAfterDismiss {
                reopenModelPanelAfterDismiss = false
                presentModelPicker()
            }
        }) {
            ModelPickerSheet(
                selection: viewModel.modelSelection,
                configuredThoughtLevels: viewModel.thoughtLevels,
                onPick: { rowKey, _, thought in
                    Task {
                        if let message = await viewModel.switchModel(rowKey, thoughtLevel: thought) {
                            showSwitchHint(message)
                        }
                    }
                },
                onReload: {
                    reopenModelPanelAfterDismiss = true
                    Task {
                        if !(await viewModel.retryModelSelection()) {
                            showSwitchHint(String(localized: "模型列表获取失败 · 桌面端未回执，点按重试"))
                        }
                    }
                },
                onManageModels: {
                    router.pushSettings(.model)
                })
        }
        // P2-7 压缩确认（主键普通按钮非 destructive——压缩不丢数据、
        // 回执失败即无副作用。.alert 承载——confirmationDialog 本 OS
        // 渲染成锚定 popover，P2ExtrasViews.swift:65 同款先例）
        .alert(
            String(localized: "压缩上下文？"), isPresented: $showCompactConfirm) {
            Button(String(localized: "压缩")) {
                Task { await runCompact() }
            }
            .accessibilityIdentifier("05-compact-confirm")
            Button(String(localized: "取消"), role: .cancel) {}
        } message: {
            Text(String(localized: "让桌面端把历史对话压缩为摘要，释放上下文空间。压缩可能持续数十秒，期间请勿下发新指令。"))
        }
        // 图库可选图片+视频（上游 web 同口径；视频受 20MB 通道上限约束，超限在附件上传事务拦截）
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoPickerItems, matching: .any(of: [.images, .videos]))
        // held 态确认（§11.2）：暂停队列下发送被拒 → 清空/保留队列并发送
        .confirmationDialog(
            String(localized: "发送被暂停的队列拦下"),
            isPresented: $viewModel.heldConfirmVisible,
            titleVisibility: .visible) {
            Button(String(localized: "清空队列并发送")) {
                Task { await viewModel.confirmHeldSend(clearQueue: true) }
            }
            Button(String(localized: "保留队列并发送")) {
                Task { await viewModel.confirmHeldSend(clearQueue: false) }
            }
            Button(String(localized: "取消"), role: .cancel) {}
        } message: {
            Text(String(localized: "队列已暂停。发送前需确认如何处理挂起的排队消息（确认后按当前队列快照执行）。"))
        }
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
    }

    // MARK: 执行目标选择器（Qoder 对照屏 2/3：目标内联常驻、单击弹出设备清单、✓ 标当前）

    private var targetRow: some View {
        HStack(spacing: T.sp2) {
            executionTargetMenu
            Text(String(localized: "偏好"))
                .font(T.mono(10))
                .foregroundColor(T.text3)
                .accessibilityIdentifier("05-target-note")
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-target-menu")
    }

    private var executionTargetMenu: some View {
        Button {
            openComposerPanel(.target)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: executionTarget.kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer")
                    .font(.system(size: 11))
                Text(executionTarget.name)
                    .font(T.font(11.5, .medium))
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8, weight: .semibold))
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, T.sp2)
            .frame(minHeight: 44)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        // identifier 挂触发按钮本体：E2E 以 05-act-target 定位触发、以行按钮
        // label 精确匹配候选（test07 openTargetMenu 双依赖）
        .accessibilityIdentifier("05-act-target")
    }

    private func selectTarget(_ option: DeviceOption) {
        executionTarget = option
        ExecutionTargetStore.setTarget(option, conversationID: viewModel.conversationID)
        UISelectionFeedbackGenerator().selectionChanged()
    }

    // MARK: P1-2 模式行（协作 Plan/Build + 投递 立即/排队/引导）
    //
    // 胶囊样式照抄 executionTargetMenu（11.5pt medium、bgInput、Capsule、minHeight 44）；
    // identifier 挂 Menu 本体（同 05-act-target 教训：挂 label 会被运行时逐层拼接）。
    // 状态单源在 viewModel——切换成功才落态并持久化，send() 同读该值携
    // requestedDelivery，UI 态与发送参数不两张皮。

    private var modeRow: some View {
        HStack(spacing: T.sp2) {
            collaborationModeMenu
            deliveryModeMenu
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-composer-mode-row")
        .onAppear {
            // 协作模式现接桌面投影（state.config.mode，ChatViewModel.refresh-
            // CollaborationModeProjection 逐事件覆盖）——chip 为桌面真值，不再是
            // 「本机偏好」；仅投递模式仍为纯本机档，一次性如实标注（§4.3）
            guard !modeDisclaimerShown else { return }
            modeDisclaimerShown = true
            showSwitchHint(String(localized: "投递模式为本机偏好 · 桌面端以实际执行为准"))
        }
    }

    /// 协作模式胶囊（4 档【实证 web bundle】：build=改动前询问 / edit=自动编辑 /
    /// plan=先出计划 / yolo=完全访问；switchCollaborationMode CAS）
    private var collaborationModeMenu: some View {
        Button {
            openComposerPanel(.collaboration)
        } label: {
            modePill(
                icon: Self.collaborationIcon(viewModel.collaborationMode),
                text: Self.collaborationLabel(viewModel.collaborationMode))
        }
        .accessibilityIdentifier("05-chip-mode")
    }

    /// 协作模式 5 档文案（web 全集对齐 + 中文翻译，用户裁决 2026-10-06：
    /// build=变更前确认 / edit=自动编辑 / plan=计划模式 / yolo=完全访问；
    /// v3.15.1 新增 guarded=自主模式：有风险时询问）
    static func collaborationLabel(_ mode: String) -> String {
        switch mode {
        case "edit": return String(localized: "自动编辑")
        case "plan": return String(localized: "计划模式")
        case "yolo": return String(localized: "完全访问")
        case "guarded": return String(localized: "自主模式")
        default: return String(localized: "变更前确认")
        }
    }

    static func collaborationDetail(_ mode: String) -> String {
        switch mode {
        case "edit": return String(localized: "自动编辑相关文件，不再逐项确认")
        case "plan": return String(localized: "先出计划，改动需经你确认")
        case "yolo": return String(localized: "自动执行并运行命令，最少确认")
        case "guarded": return String(localized: "在有风险时询问")
        default: return String(localized: "每次文件改动前询问确认")
        }
    }

    static func collaborationIcon(_ mode: String) -> String {
        switch mode {
        case "edit": return "pencil.and.list.clipboard"
        case "plan": return "list.clipboard"
        case "yolo": return "bolt.fill"
        case "guarded": return "shield.lefthalf.filled"
        default: return "hammer"
        }
    }

    /// 投递模式胶囊（立即/排队/引导）。A-2：now 档不下发 setFollowupMode（web 枚举
    /// 仅 queue|guide），仅本地态 + send 恒携 requestedDelivery:"startNow"；
    /// queue/guide 照常 CAS 下发 + requestedDelivery 联动）
    private var deliveryModeMenu: some View {
        Button {
            openComposerPanel(.delivery)
        } label: {
            modePill(
                icon: Self.deliveryIcon(viewModel.deliveryMode),
                text: Self.deliveryLabel(viewModel.deliveryMode))
        }
        .accessibilityIdentifier("05-chip-delivery")
    }

    /// 模式胶囊 label（executionTargetMenu :833-847 同款：11.5pt medium、bgInput、
    /// Capsule、minHeight 44——与工具行 26pt 小 chip 区分，模式是常驻一级控件）
    private func modePill(icon: String, text: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 11))
            Text(text)
                .font(T.font(11.5, .medium))
                .lineLimit(1)
            Image(systemName: "chevron.up.chevron.down")
                .font(.system(size: 8, weight: .semibold))
        }
        .foregroundColor(T.text2)
        .padding(.horizontal, T.sp2)
        .frame(minHeight: 44)
        .background(T.bgInput)
        .clipShape(Capsule())
    }

    static func deliveryLabel(_ mode: String) -> String {
        switch mode {
        case "queue": return String(localized: "排队")
        case "guide": return String(localized: "引导")
        default: return String(localized: "立即发送")
        }
    }

    static func deliveryIcon(_ mode: String) -> String {
        switch mode {
        case "queue": return "list.number"
        case "guide": return "arrow.triangle.merge"
        default: return "bolt.fill"
        }
    }

    /// 面板选项装配（呈现期取当前态计算 selected；目标行 detail 恒空——
    /// 行按钮聚合 label 必须恰为项名，test07 以 label 精确匹配候选）
    private func composerSheetOptions(_ kind: ComposerSheetKind) -> [ComposerOptionItem] {
        switch kind {
        case .collaboration:
            ComposerModeStore.collaborationModes.map { mode in
                ComposerOptionItem(
                    id: mode,
                    title: Self.collaborationLabel(mode),
                    detail: Self.collaborationDetail(mode),
                    icon: Self.collaborationIcon(mode),
                    selected: viewModel.collaborationMode == mode)
            }
        case .delivery:
            ComposerModeStore.deliveryModes.map { mode in
                ComposerOptionItem(
                    id: mode,
                    title: Self.deliveryLabel(mode),
                    detail: mode == "now"
                        ? String(localized: "本机默认 · 不下发模式命令，消息逐条直接投递")
                        : mode == "queue"
                            ? String(localized: "回合进行中发送将排队，回合结束后自动投递（桌面默认）")
                            : String(localized: "作为引导补充注入当前回合"),
                    icon: Self.deliveryIcon(mode),
                    selected: viewModel.deliveryMode == mode)
            }
        case .target:
            ExecutionTargetStore.machines().map { option in
                ComposerOptionItem(
                    id: option.id,
                    title: option.name,
                    detail: "",
                    icon: option.kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer",
                    selected: option.id == executionTarget.id,
                    a11yId: option.kind == .cloudSandbox ? "05-target-cloud" : "05-target-mac")
            }
        case .thought:
            // 行 title 恒等于档位原文（test12 label == 'low' 精确匹配），detail 恒空
            thoughtSheetOptions()
        }
    }

    /// 模型面板选项已迁二级选择器（ModelPickerSheet，批4）——本函数撤除，
    /// 保留思考档装配（thoughtSheetOptions）。

    /// 思考档面板选项：词表三级回退（workspace-config 词表 → getView 词表 → 静态梯）
    private func thoughtSheetOptions() -> [ComposerOptionItem] {
        let selection = viewModel.modelSelection ?? ModelSelectionInfo()
        let levels: [String]
        if let configured = viewModel.thoughtLevels, !configured.isEmpty {
            levels = configured
        } else if !selection.thoughtLevels.isEmpty {
            levels = selection.thoughtLevels
        } else {
            levels = Self.fallbackThoughtLevels
        }
        return levels.map { level in
            ComposerOptionItem(id: level, title: level, detail: "", icon: "brain",
                               selected: level == selection.activeThoughtLevel)
        }
    }

    private func applyComposerSheetPick(_ kind: ComposerSheetKind, _ option: ComposerOptionItem) {
        switch kind {
        case .collaboration:
            selectCollaborationMode(option.id)
        case .delivery:
            selectDeliveryMode(option.id)
        case .target:
            if let match = ExecutionTargetStore.machines().first(where: { $0.id == option.id }) {
                selectTarget(match)
            }
        case .thought:
            Task {
                if let message = await viewModel.switchThoughtLevel(option.payload ?? option.id) {
                    showSwitchHint(message)
                }
            }
        }
    }

    /// 面板档位：固定三选择器定高（AttachmentSourceSheet 3 行 348 基准反推：基底
    /// 152 + 每行 68，页脚一行 +24）；思考清单长度不定，medium/large 双档 + 面板内滚动
    private func composerSheetDetents(_ kind: ComposerSheetKind) -> Set<PresentationDetent> {
        switch kind {
        case .thought:
            return [.medium, .large]
        case .collaboration, .delivery, .target:
            let rows: Int
            switch kind {
            case .collaboration: rows = ComposerModeStore.collaborationModes.count
            case .delivery: rows = ComposerModeStore.deliveryModes.count
            default: rows = max(ExecutionTargetStore.machines().count, 1)
            }
            return [.height(CGFloat(152 + rows * 68 + (kind.footer != nil ? 24 : 0)))]
        }
    }

    private func selectCollaborationMode(_ mode: String) {
        Task {
            if let message = await viewModel.switchCollaborationMode(mode) {
                showSwitchHint(message)
            } else {
                UISelectionFeedbackGenerator().selectionChanged()
            }
        }
    }

    private func selectDeliveryMode(_ mode: String) {
        Task {
            if let message = await viewModel.setDeliveryMode(mode) {
                showSwitchHint(message)
            } else {
                UISelectionFeedbackGenerator().selectionChanged()
                // 投递三档提示：now 档成功=本地默认（桌面会话模式不变）；
                // queue/guide 成功=「本条消息起按「X」投递」
                showSwitchHint(mode == "now"
                    ? String(localized: "已设为本机默认 · 桌面端会话模式不变")
                    : String(localized: "本条消息起按「\(Self.deliveryLabel(mode))」投递"))
            }
        }
    }

    // MARK: P2-7 压缩入口（contextMeter 即入口：用量条是压缩动机，就地可发现）

    /// 压缩确认后的下发与完成判定（完成 hint / 失败 hint 各 3s；
    /// 「压缩中…」由 viewModel.isCompacting 驱动、不自动消失）
    private func runCompact() async {
        let message = await viewModel.compactContext()
        showSwitchHint(message)
    }

    /// 桌面端模型/思考档切换（switchModelConfig 桌面代执行；onDidChange 回流同步 chips。
    /// 原只读口径随命令链路打通升级：模型按套餐分组，思考档独立菜单）
    @State private var switchHint: String?
    @State private var switchHintClear: Task<Void, Never>?

    private func remoteChips(_ selection: ModelSelectionInfo) -> some View {
        HStack(spacing: T.sp2) {
            modelMenu(selection)
            thoughtMenu(selection)
            Spacer(minLength: 0)
            if let usage = viewModel.contextUsage {
                // P2-7 压缩入口（用量条即压缩动机，就地可发现）。
                // 显式连接态 gating——不以 contextMeter 条件渲染作隐式门槛（演示态
                // Mock 恒返回非 nil usage，toolsRow 照样渲染「上下文·演示」，无 gating
                // 则入口会出现在命令不可达的演示态）；进行中禁点防重复下发
                if viewModel.isReadOnly {
                    Button {
                        showCompactConfirm = true
                    } label: {
                        contextMeter(usage, demo: false)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isCompacting)
                    .accessibilityIdentifier("05-compact-entry")
                } else {
                    contextMeter(usage, demo: false)
                }
            }
        }
        // 与 targetRow/modeRow 同口径：.contain 容器让 Menu 子元素独立出 a11y 树——
        // 裸 identifier 的 HStack 会被合并成单元素，Menu identifier 不出树（XCUITest
        // 找不到 05-chip-*，test12 门禁实证）；identifier 挂 Menu 本体（:1642 教训：
        // 挂 label 会被运行时逐层拼接、tap 落空菜单永不弹出）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-composer-remote-chips")
    }

    /// 五选择器面板统一入口：**先撤 composer 焦点再呈现**（2026-10-09 真机报障
    /// 「会话页模型/思考不可选，新建会话 sheet 可选」根因：发消息后 composer 持
    /// 焦点，chips 触摸先被输入法层消费在候选/组合态——控制台每触必刷
    /// 「Received external candidate resultset / containerToPush is nil」即中文候选
    /// 机制在跑——sheet 呈现又在键盘转场中被吞。新建流程先选模型再打字（无焦点）
    /// 故可选，对比观察与该根因吻合。FocusState 下传是唯一稳定撤焦通道（本文件
    /// 头注释同源；resignFirstResponder 对 SwiftUI TextField 不可靠。FocusState 经
    /// inputFocused 绑定下传本视图——撤焦走 wrappedValue 置 false 同一通道）
    private func openComposerPanel(_ kind: ComposerSheetKind) {
        inputFocused.wrappedValue = false
        // 呈现延迟一拍：撤焦与 sheet 呈现同事务发出时，sheet 会与键盘收起转场相撞
        // 被吞/卡半程（模拟器门禁复现：焦点在场点胶囊 → 键盘上方灰色半透明残影、
        // 候选永不出现——真机「点了没反应」同机制）。撤焦先行，待键盘让出呈现位
        // 后再呈（120ms 用户不可感知；键盘不在场时同样安全，仅统一路径）
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 120_000_000)
            composerSheet = kind
        }
    }

    /// 二级模型选择器呈现（与 openComposerPanel 同一撤焦+延迟纪律；模型已从
    /// ComposerSheetKind 迁出——2026-11-25 批4）
    private func presentModelPicker() {
        inputFocused.wrappedValue = false
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 120_000_000)
            showModelPicker = true
        }
    }

    /// 模型胶囊触发（面板：套餐分节 + 选中勾；回执失败在 chips 行上方提示）。
    /// 恒开面板（2026-10-09 真机报障「模型不可选」死胡同收口）：清单缺席时面板
    /// 呈「重新获取」行承载重试——拒开+一次性重试+3s 提示的旧路在 getView 持续
    /// 失败时表现为「点了没反应」
    private func modelMenu(_ selection: ModelSelectionInfo) -> some View {
        Button {
            presentModelPicker()
        } label: {
            pill(icon: nil, text: selection.activeModel ?? "…", chevron: true)
        }
        .accessibilityIdentifier("05-chip-model")
    }

    /// 思考档胶囊触发（面板：词表按当前模型查询，缺席退化为静态档位梯——
    /// web 端别名表归纳词表，不支持的档位由桌面端校验拒绝并提示）
    private func thoughtMenu(_ selection: ModelSelectionInfo) -> some View {
        Button {
            openComposerPanel(.thought)
        } label: {
            pill(icon: "brain", text: String(localized: "思考·\(selection.activeThoughtLevel ?? "--")"), chevron: true)
        }
        .accessibilityIdentifier("05-chip-thought")
    }

    /// 思考档静态梯（getView 不携带词表、workspace-config 为空的远端环境兜底）
    private static let fallbackThoughtLevels = ["off", "minimal", "low", "medium", "high", "max"]

    private func showSwitchHint(_ message: String) {
        switchHintClear?.cancel()
        switchHint = message
        switchHintClear = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { switchHint = nil }
        }
    }

    private var toolsRow: some View {
        HStack(spacing: T.sp2) {
            // 连接态只读兜底 pills 升级可点（用户多次报障「模型/思考等级不能选是 bug，
            // 不是要你做成不能选」）：getView 首击失败时模型 pill 点按重取、成功即开
            // 面板（chips 随流回归）；思考 pill 直开档位面板（静态梯兜底）。演示态保持只读
            if viewModel.isReadOnly {
                Button {
                    Task { await retryThenOpenModelPanel() }
                } label: {
                    pill(icon: nil, text: settings.value.model)
                }
                .accessibilityIdentifier("05-chip-model")
                Button {
                    openComposerPanel(.thought)
                } label: {
                    pill(icon: "brain", text: String(localized: "思考·\(settings.value.thoughtLevel.label)"))
                }
                .accessibilityIdentifier("05-chip-thought")
            } else {
                pill(icon: nil, text: settings.value.model)
                pill(icon: "brain", text: String(localized: "思考·\(settings.value.thoughtLevel.label)"))
            }
            Spacer(minLength: 0)
            // G15 @ 文件提及（web promptInputTriggers @ 触发的触屏对位——符号自动触发
            // 需键盘游标监听，触屏以显式按钮 + 候选面板承载同一语义；点选插入 @path。
            // 面板态由宿主持有——onMention 置位宿主 showMentionPicker）
            Button {
                onMention()
            } label: {
                Text("@")
                    .font(T.mono(13, .semibold))
                    .foregroundColor(T.text2)
                    .frame(minWidth: 28, minHeight: 28)
                    .background(T.bgInput)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-chip-mention")
            .accessibilityLabel(Text(String(localized: "引用文件")))
            // G16 粘贴附件（web useComposerAttachments paste 同通道：剪贴板图片/文件
            // 直入待发队列；纯文本追加草稿；上限 8 件·20MB 由 uploads.add 同门）
            Button {
                pasteAttachment()
            } label: {
                Image(systemName: "doc.on.clipboard")
                    .font(.system(size: 12))
                    .foregroundColor(T.text2)
                    .frame(minWidth: 28, minHeight: 28)
                    .background(T.bgInput)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-chip-paste")
            .accessibilityLabel(Text(String(localized: "粘贴附件")))
            // prompt 历史（web ConversationComposer ↑/↓ 历史的触屏适配：「历史」chip
            // 单向循环上一条发送文本，走到头回空草稿重新循环；仅连接态有发送历史）
            if viewModel.isReadOnly, viewModel.hasPromptHistory {
                Button {
                    if let text = viewModel.cyclePromptHistory() {
                        viewModel.draft = text
                    }
                } label: {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 12))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 28, minHeight: 28)
                        .background(T.bgInput)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-chip-history")
                .accessibilityLabel(Text(String(localized: "上一条发送")))
            }
            Spacer(minLength: 0)
            // G-021：演示态用量为 Mock 动态值，如实标注「演示」；nil 不渲染
            if let usage = viewModel.contextUsage {
                contextMeter(usage, demo: !viewModel.isReadOnly)
            }
        }
        .accessibilityIdentifier("05-composer-tools")
    }

    /// 模型兜底 pill：重取 getView，成功开模型面板、失败提示（05-chip-* identifier
    /// 与 remoteChips 同名——两支互斥渲染，同屏不重复）
    /// G16 粘贴附件：剪贴板图片 → jpeg 入待发；文件 URL → 按扩展名猜 mime 读字节；
    /// 纯文本 → 追加草稿（web 粘贴文本即输入同语义）；无可粘内容如实提示不静默。
    private func pasteAttachment() {
        let board = UIPasteboard.general
        if let image = board.image {
            let data = image.jpegData(compressionQuality: 0.9)
                ?? image.pngData()
            guard let data else {
                viewModel.uploads.setHint(String(localized: "剪贴板图片编码失败，已跳过"))
                return
            }
            if viewModel.uploads.add(name: String(localized: "粘贴图片.jpg"), mediaType: "image/jpeg", data: data) {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            return
        }
        if let url = board.url {
            let name = url.lastPathComponent
            let ext = url.pathExtension.lowercased()
            let mime: String
            switch ext {
            case "png": mime = "image/png"
            case "jpg", "jpeg": mime = "image/jpeg"
            case "gif": mime = "image/gif"
            case "webp": mime = "image/webp"
            case "pdf": mime = "application/pdf"
            case "mp4", "mov": mime = "video/mp4"
            case "mp3", "m4a": mime = "audio/mpeg"
            default: mime = "application/octet-stream"
            }
            if let data = try? Data(contentsOf: url) {
                if viewModel.uploads.add(name: name, mediaType: mime, data: data) {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                }
                return
            }
        }
        if let text = board.string, !text.isEmpty {
            let current = viewModel.draft
            viewModel.draft = current.isEmpty ? text : current + "\n" + text
            return
        }
        viewModel.uploads.setHint(String(localized: "剪贴板无可粘贴的图片或文件"))
    }

    private func retryThenOpenModelPanel() async {
        if await viewModel.retryModelSelection() {
            presentModelPicker()
        } else {
            showSwitchHint(String(localized: "模型列表获取失败 · 桌面端未回执，点按重试"))
        }
    }

    /// 上下文用量条（连接态=state.runtime.contextUsage；演示态=Mock 动态值）
    private func contextMeter(_ usage: ContextUsageInfo, demo: Bool) -> some View {
        HStack(spacing: T.sp1) {
            Text(demo ? String(localized: "上下文·演示") : String(localized: "上下文"))
                .font(T.font(11))
                .foregroundColor(T.text3)
            ThinProgressBar(progress: usage.fraction, height: 4, tint: T.accent)
                .frame(width: 56)
            Text(usage.percentText)
                .font(T.mono(10.5))
                .foregroundColor(T.text3)
        }
        .accessibilityIdentifier("05-composer-context")
    }

    // MARK: 斜杠命令菜单（web 同构能力命令面：goal/workflow 等；"/" 触发）

    /// 呈现条件：draft 为 "/" [+ 纯字母前缀]（未进入参数段——含空格即隐去，避免
    /// 输入参数时遮挡；裸 "/" 即触发，web 同构。**不得 trim**：选行插入的 "/goal "
    /// 尾随空格正是终止菜单的信号，裁掉会让菜单复活）。按前缀过滤，无匹配不渲染。
    private var slashMatches: [WorkspaceConfigInfo.SlashCommand] {
        let query = viewModel.draft
        guard query.hasPrefix("/"),
              query.dropFirst().allSatisfy(\.isLetter) else { return [] }
        let prefix = query.dropFirst().lowercased()
        return viewModel.slashMenuCommands.filter { $0.name.lowercased().hasPrefix(prefix) }
    }

    private var slashMenu: some View {
        let matches = slashMatches
        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(matches) { command in
                    Button {
                        viewModel.draft = "/\(command.name) "
                        inputFocused.wrappedValue = true
                    } label: {
                        HStack(spacing: T.sp2) {
                            Text("/\(command.name)")
                                .font(T.mono(12, .semibold))
                                .foregroundColor(T.accentText)
                            Text(command.description)
                                .font(T.font(11))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 38)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("05-slash-row-\(command.name)")
                    if command.id != matches.last?.id {
                        Divider().overlay(T.border)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // 自适应高度滚动窗（面板三联症同款修饰符顺序：frame 出有界提案 →
        // fixedSize 取 ideal = min(内容高, 上限)，小内容收缩、大内容封顶可滚）
        .frame(maxHeight: 176)
        .fixedSize(horizontal: false, vertical: true)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-slash-menu")
    }

    private var inputRow: some View {
        HStack(spacing: T.sp2) {
            // P1-1：附件入口（inputRow 首位 📎 44pt 热区；连接态渲染，
            // 演示态无上传面不出入口）
            if viewModel.isReadOnly {
                Button {
                    // 附件来源 sheet 同族：键盘在场时先撤焦、延迟一拍呈现（openComposerPanel
                    // 同根因——同事务发 sheet 会与键盘收起转场相撞被吞）
                    inputFocused.wrappedValue = false
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 120_000_000)
                        showAttachmentSource = true
                    }
                } label: {
                    Image(systemName: "plus.circle")
                        .font(T.font(20))
                        .foregroundColor(T.text2)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("05-attach-button")
            }

            TextField("发送消息…", text: $viewModel.draft, axis: .vertical)
                .font(T.font(16))
                .foregroundColor(T.text)
                // 上限 8 行（真机报障「输入文字多了看不到全部，高度固定」：4 行上限
                // 长文本不可见）；超出后框内滚动
                .lineLimit(1...8)
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
                .focused(inputFocused)
                .submitLabel(.send)
                .onSubmit { Task { await sendAndHintDelivery() } }
                .accessibilityIdentifier("05-composer-input")

            // G-023：麦克风死按钮移除（原仅图标动画无录音/听写行为；语音输入走 iOS
            // 键盘自带听写，长按语音待 G-055 真实现）

            sendButton
        }
    }

    private var sendButton: some View {
        Button {
            Task { await sendAndHintDelivery() }
            inputFocused.wrappedValue = false
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(T.onAccent)
                .frame(width: 44, height: 44)
                .background(viewModel.canSend ? T.accent : T.bgInput)
                .clipShape(Circle())
        }
        .disabled(!viewModel.canSend)
        .accessibilityIdentifier("05-composer-send")
    }

    /// 发送 + 投递提示（queue 档送达后 hint「已加入排队」，消息随后
    /// 经 QueueBarView 回流呈现；now/guide 档无额外提示）。
    /// U-6：失败不再静默——持久错误行（断线子文案区分）+ 草稿已由 viewModel 回填
    /// + 焦点回输入框 + error 触觉；重试钮复用本方法。
    private func sendAndHintDelivery() async {
        let delivered = await viewModel.send()
        if delivered {
            sendFailure = nil
            restoredDraftText = nil
            if viewModel.deliveryMode == "queue" {
                showSwitchHint(String(localized: "已加入排队"))
            }
        } else if let failedText = viewModel.lastSendUndeliveredText, failedText == viewModel.draft {
            // 真正的未送达（guard 路径不算）：草稿已回填，错误行持久在场直至
            // 成功/手动编辑/离开展示。桌面拒收回执的
            // 真实拒因优先（rejected 此前被当「任务已发送」假成功——2026-10-07
            // 修复面：用户看到的必须是桌面端为什么拒，不是泛化「未送达」）
            let disconnected: Bool
            if case .connected = session.mode { disconnected = false } else { disconnected = true }
            let rejection = await viewModel.lastSendRejectionText()
            sendFailure = rejection ?? (disconnected
                ? String(localized: "消息未送达 · 连接已断开，草稿已保留")
                : String(localized: "消息未送达 · 已恢复草稿"))
            restoredDraftText = failedText
            inputFocused.wrappedValue = true
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }

    /// U-6 重试：携回填后的 draft 重发；成功清错误行，再失败行保留
    private func retrySend() async {
        retryingSend = true
        defer { retryingSend = false }
        await sendAndHintDelivery()
    }

    // MARK: P1-1 待发附件条（横向滚动 72×72 缩略卡 + 进度 + 角标）

    private var attachmentStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: T.sp2) {
                ForEach(viewModel.uploads.items) { item in
                    PendingAttachmentThumb(
                        item: item,
                        onRemove: { viewModel.uploads.remove(item.id) },
                        onRetry: { viewModel.uploads.retry(item.id) })
                }
            }
            .padding(.horizontal, T.sp1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-attach-strip")
    }

    // MARK: 附件三来源入队（选定后立即建上传事务，不等发送）

    private func addPhotoPickerItems(_ items: [PhotosPickerItem]) {
        Task {
            for item in items {
                let contentType = item.supportedContentTypes.first
                let ext = contentType?.preferredFilenameExtension ?? "jpg"
                let mediaType = contentType?.preferredMIMEType ?? "image/jpeg"
                guard let data = try? await item.loadTransferable(type: Data.self) else {
                    viewModel.uploads.setHint(String(localized: "照片读取失败，已跳过"))
                    continue
                }
                if viewModel.uploads.add(
                    name: "\(mediaType.hasPrefix("video/") ? "VID" : "IMG")_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)",
                    mediaType: mediaType, data: data) {
                    await startUploadIfNeeded()
                }
            }
        }
    }

    private func addCameraImage(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.9) else {
            viewModel.uploads.setHint(String(localized: "照片编码失败，已跳过"))
            return
        }
        if viewModel.uploads.add(
            name: "IMG_\(Int(Date().timeIntervalSince1970 * 1000)).jpg",
            mediaType: "image/jpeg", data: data) {
            Task { await startUploadIfNeeded() }
        }
    }

    private func importFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        var addedAny = false
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                viewModel.uploads.setHint(String(localized: "《\(url.lastPathComponent)》读取失败，已跳过"))
                continue
            }
            if viewModel.uploads.add(
                name: url.lastPathComponent,
                mediaType: AttachmentUploadService.mediaType(forFileExtension: url.pathExtension),
                data: data) {
                addedAny = true
            }
        }
        // 单路批量补传（§11.2 并发治理：此前每 URL 一路 Task，多选 N 个文件即
        // N 路并发上传事务，可击穿服务端 staged 64MB 预算）
        if addedAny {
            Task { await startUploadIfNeeded() }
        }
    }

    /// 选定后立即建上传事务（不等发送）；已在上传中的项自动跳过
    private func startUploadIfNeeded() async {
        _ = await viewModel.uploads.ensureAllCommitted()
    }

    private func pill(icon: String?, text: String, chevron: Bool = false) -> some View {
        HStack(spacing: 4) {
            if let icon {
                Image(systemName: icon).font(.system(size: 10))
            }
            Text(text).font(T.font(11, .medium))
            if chevron {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 7.5, weight: .semibold))
                    .foregroundColor(T.text3)
            }
        }
        .foregroundColor(T.text2)
        .padding(.horizontal, T.sp2)
        .frame(height: 26)
        .background(T.bgInput)
        .clipShape(Capsule())
    }
}

// MARK: - 添加附件来源 sheet（P1-1）
//
// 自绘底部面板：confirmationDialog 在本 OS 版本会渲染成锚定 popover（用户报障
// 「弹窗都弹到什么地方去了」2026-10-07；P2ExtrasViews.swift:65 同款先例），改 sheet
// 承载。三行大热区选项（拍照/照片图库/文件）+ 取消；选中经回调置 pendingAttachmentSource
// 再收起，由宿主 onDismiss 接力呈现真正的选择器（避免同视图双 sheet 竞态）。

/// 附件来源三通道（ComposerBar 接力呈现用）
enum AttachmentSource: Identifiable {
    case camera
    case photos
    case files
    var id: String {
        switch self {
        case .camera: return "camera"
        case .photos: return "photos"
        case .files: return "files"
        }
    }
}

struct AttachmentSourceSheet: View {
    /// 选中来源（宿主收起 sheet 后于 onDismiss 接力呈现对应选择器）
    var onPick: (AttachmentSource) -> Void
    @Environment(\.dismiss) private var dismiss

    private var options: [(AttachmentSource, String, String, String)] {
        [
            (.camera, "camera.fill", "拍照", String(localized: "使用相机拍摄一张图片")),
            (.photos, "photo.on.rectangle", "照片图库", String(localized: "从图库选择图片")),
            (.files, "doc", "文件", String(localized: "从文件 App 选择（PDF、文本等）")),
        ]
    }

    var body: some View {
        VStack(spacing: T.sp3) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("添加附件").font(T.font(16, .bold)).foregroundColor(T.text)
                Spacer()
            }
            .padding(.horizontal, T.sp4)
            VStack(spacing: T.sp2) {
                ForEach(options, id: \.0.id) { source, icon, title, subtitle in
                    Button {
                        onPick(source)
                    } label: {
                        HStack(spacing: T.sp3) {
                            Image(systemName: icon)
                                .font(.system(size: 15))
                                .foregroundColor(T.accentText)
                                .frame(width: 38, height: 38)
                                .background(T.accentDim)
                                .clipShape(Circle())
                            VStack(alignment: .leading, spacing: 1) {
                                Text(title).font(T.font(14.5, .medium)).foregroundColor(T.text)
                                Text(subtitle).font(T.font(11.5)).foregroundColor(T.text3)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(T.text3.opacity(0.6))
                        }
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 60)
                        .background(T.bgCard)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
                    }
                    .buttonStyle(PressableButtonStyle())
                    .accessibilityIdentifier("05-attach-sheet-\(source.id)")
                }
            }
            .padding(.horizontal, T.sp4)
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
            .accessibilityIdentifier("05-attach-sheet-cancel")
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .background(T.bgElevated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-attach-sheet")
    }
}

// MARK: - 待发附件缩略卡（P1-1）
//
// 72×72 缩略规格 = AttachmentThumbView（132×96+T.rM+T.border 描边）等比缩；
// 底部 4px ThinProgressBar + mono 百分比；成功勾角标 / 失败橙角标（点按重试）；
// 右上角 ✕ 移除（44pt 热区、T.bgCard 半透明圆底）。非图片 doc.icon 占位
// （mediaType 判定同 AttachmentThumbView）。
/// 待发图片全屏预览（点缩略图打开；纯本地数据，零 RPC）。
/// 交互与会话流图片预览一致：点任意处关闭、右上 ✕ 同效、捏合双指缩放。
struct PendingImagePreview: View {
    let image: UIImage?
    let name: String
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { scale = max(1, $0) }
                            .onEnded { _ in if scale < 1 { scale = 1 } }
                    )
                    .onTapGesture { dismiss() }
                    .accessibilityIdentifier("05-attach-preview-image")
            } else {
                Text("图片无法解码")
                    .font(T.font(13))
                    .foregroundColor(.white.opacity(0.7))
            }
            VStack {
                HStack {
                    Text(name)
                        .font(T.mono(11))
                        .foregroundColor(.white.opacity(0.8))
                        .lineLimit(1)
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.black.opacity(0.45))
                            .clipShape(Circle())
                    }
                    .accessibilityIdentifier("05-attach-preview-close")
                }
                .padding(.horizontal, T.sp4)
                .padding(.top, T.sp2)
                Spacer()
            }
        }
        .statusBarHidden(true)
    }
}

struct PendingAttachmentThumb: View {
    let item: PendingAttachment
    var onRemove: () -> Void
    var onRetry: () -> Void

    /// 解码缓存（§11.2：previewImage 此前是 computed property——每块送达更新
    /// uploadedBytes 触发重渲染，20MB/54 块即 54 次全尺寸 UIImage 解码，大图
    /// 上传期间 CPU/内存尖峰）。视图本地 @State 缓存一次解码结果
    @State private var cachedPreview: UIImage?
    /// 点图全屏预览（2026-11-25 用户报「图片没有预览——点击的时候可以预览」）
    @State private var showPreview = false

    private var previewImage: UIImage? {
        if let cachedPreview { return cachedPreview }
        guard item.isImage, let image = UIImage(data: item.data) else { return nil }
        return image
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            thumbnail
            Text(item.name)
                .font(T.mono(10))
                .foregroundColor(T.text3)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(width: 72, alignment: .leading)
        }
        .onAppear {
            if cachedPreview == nil, item.isImage {
                cachedPreview = UIImage(data: item.data)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-attach-item")
    }

    private var thumbnail: some View {
        ZStack(alignment: .topTrailing) {
            ZStack(alignment: .bottom) {
                base
                if item.state == .uploading {
                    VStack(spacing: 1) {
                        ThinProgressBar(progress: item.progress, height: 4, tint: T.accent)
                        Text(item.percentText)
                            .font(T.mono(9.5))
                            .foregroundColor(T.text2)
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 3)
                }
                if item.state == .pending {
                    SpinnerView(size: 14).padding(4)
                }
            }
            .frame(width: 72, height: 72)
            .overlay(alignment: .topLeading) { statusBadge }

            // ✕ 移除（44pt 热区包住 18pt 圆底——同 targetRow 热区口径）
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(T.text2)
                    .frame(width: 18, height: 18)
                    .background(T.bgCard.opacity(0.9))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .frame(width: 44, height: 44, alignment: .topTrailing)
            .accessibilityIdentifier("05-attach-remove")
        }
    }

    private var base: some View {
        Group {
            if let image = previewImage {
                // 缩略图必须**在外层 72×72 帧内**填满裁切（2026-11-25 用户报「图片没有
                // 预览」：此前 scaledToFill 在 72 帧之外求值，图按自身尺寸溢出/被二次裁，
                // 实际看到的是缩到很小的一团，认不出内容）
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 72, height: 72)
                    .clipped()
            } else {
                T.bgInput
                    .overlay(
                        Image(systemName: item.isImage ? "photo"
                            : (item.isVideo ? "video.fill" : "doc.fill"))
                            .font(.system(size: 18))
                            .foregroundColor(T.text3))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
        .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.border, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: T.rS))
        .onTapGesture {
            // 失败点条目重试（失败角标「点条目重试」）；其余态点图全屏预览
            if item.state == .failed {
                onRetry()
            } else if previewImage != nil {
                showPreview = true
            }
        }
        .fullScreenCover(isPresented: $showPreview) {
            PendingImagePreview(image: previewImage, name: item.name)
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch item.state {
        case .committed:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 15))
                .foregroundColor(T.accentText)
                .padding(3)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 15))
                .foregroundColor(T.orange)
                .padding(3)
        default:
            EmptyView()
        }
    }
}

// MARK: - 相机拍摄（P1-1 附件来源①：UIImagePickerController camera 封装）
//
// 权限键 NSCameraUsageDescription（project.yml：扫码 + 附件拍摄双用途文案）。
struct CameraPicker: UIViewControllerRepresentable {
    var onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController()
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            controller.sourceType = .camera
        }
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                parent.onImage(image)
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - ⋯ 菜单承载（2026-11-25 三 tab 重构）

/// 重命名 alert 呈现载荷（§5-15：确认回调只消费快照）
struct RenamePresentation: Identifiable {
    let id = UUID()
    let initial: String
}

/// 「执行输出」sheet 宿主：TaskRecord 由当前会话构建（原任务 tab push 面以 TaskRecord
/// 传参，三 tab 后任务看板撤除）。轨迹/元数据/停止以 sessionId 寻址，行为不变。
struct TaskOutputSheetHost: View {
    @Environment(\.dismiss) private var dismiss
    let conversation: Conversation?

    var body: some View {
        NavigationStack {
            TaskOutputView(task: TaskRecord(
                id: conversation?.id ?? "",
                title: conversation?.title ?? String(localized: "任务会话"),
                summary: conversation?.summary ?? "",
                directory: conversation?.directory ?? "",
                status: (conversation?.isRunning ?? false) ? .running : .done,
                todoDone: 0,
                todoTotal: 0,
                progress: conversation?.taskProgress ?? 0,
                tools: [],
                updatedAt: conversation?.updatedAt ?? Date(),
                lastLog: nil,
                pendingCommand: nil,
                pendingImpact: nil))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(String(localized: "完成")) { dismiss() }
                        .accessibilityIdentifier("07-act-done")
                }
            }
        }
    }
}

// MARK: - 连接态提问卡（userInput / AskUserQuestion / MCP elicitation）

/// 上游 v4 交互面：单题可选即决；多题（AskUserQuestion / elicitation form）逐题选择 +
/// 自由输入，提交 `{action:"accept", content:{answers, answer_N, answer}}`。
/// 移动端不落草稿（上游 answerDrafts 为 renderer 本地草稿，非协议事实）。
struct UserInputInteractionCard: View {
    // 2026-11-25 待批 tab 批2：协议化（InteractionDeciding）——与 ApprovalInteractionCard 同口径
    var viewModel: any InteractionDeciding
    let interaction: RemotePendingInteraction

    /// qid → 已选 option value（多选集合；单选只保留一个）
    @State private var selections: [String: Set<String>] = [:]
    /// qid → 自由输入
    @State private var texts: [String: String] = [:]
    /// 单题（无 questions）选项
    @State private var simpleSelection: String?
    @State private var simpleText = ""
    @State private var submitting = false
    @State private var errorText: String?
    /// G22 多题向导索引（questions.count>1 时逐题呈现；selections/texts 按 qid 键
    /// 天然保留——上游「drafts 引用不重置，回退不清已答」同语义，ElicitationDialog
    /// .ts:353-373【实证·上游仓】；hasPrevious = index>0 :686）
    @State private var questionIndex = 0

    private var prompt: String {
        interaction.title?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? interaction.title!
            : String(localized: "Agent 提问")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "questionmark.bubble.fill")
                    .font(.system(size: 13))
                    .foregroundColor(T.blue)
                Text(String(localized: "待回答"))
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
                StatusPill(text: String(localized: "提问"), kind: .wait, compact: true)
            }
            if interaction.questions.isEmpty {
                simpleQuestion
            } else if interaction.questions.count > 1 {
                // G22 多题向导（web ElicitationDialog 逐题同构——一次一题 + 上一步/
                // 下一步；已答 selections/texts 按 qid 键跨题保留）
                let questions = interaction.questions
                let index = min(questionIndex, questions.count - 1)
                questionBlock(questions[index])
                Text(String(localized: "第 \(index + 1)/\(questions.count) 题"))
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .accessibilityIdentifier("05-userinput-progress")
                HStack(spacing: T.sp2) {
                    // G22「上一题」按钮（hasPrevious = index>0——上游 :686 同判）
                    if index > 0 {
                        Button(String(localized: "上一题")) {
                            questionIndex = index - 1
                        }
                        .font(T.font(13, .medium))
                        .foregroundColor(T.text2)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("05-userinput-previous")
                    }
                    Spacer()
                    Button(String(localized: "跳过")) { skip() }
                        .font(T.font(13, .medium))
                        .foregroundColor(T.text3)
                        .frame(minHeight: 44)
                        .disabled(submitting)
                        .accessibilityIdentifier("05-userinput-skip")
                    Button {
                        if index < questions.count - 1 {
                            questionIndex = index + 1
                        } else {
                            submitQuestions()
                        }
                    } label: {
                        Text(index < questions.count - 1
                             ? String(localized: "下一题")
                             : String(localized: "提交回答"))
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.onAccent)
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(T.accent)
                            .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                    .disabled(submitting)
                    .accessibilityIdentifier(index < questions.count - 1
                                             ? "05-userinput-next" : "05-userinput-submit")
                }
            } else {
                // 单题 questions：全量平铺（原形态）
                ForEach(interaction.questions) { question in
                    questionBlock(question)
                }
                HStack(spacing: T.sp2) {
                    Button(String(localized: "跳过")) { skip() }
                        .font(T.font(13, .medium))
                        .foregroundColor(T.text3)
                        .frame(minHeight: 44)
                        .disabled(submitting)
                        .accessibilityIdentifier("05-userinput-skip")
                    Spacer()
                    Button {
                        submitQuestions()
                    } label: {
                        Text(String(localized: "提交回答"))
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.onAccent)
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(T.accent)
                            .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                    .disabled(submitting)
                    .accessibilityIdentifier("05-userinput-submit")
                }
            }
            if let errorText {
                Text(errorText)
                    .font(T.font(10.5))
                    .foregroundColor(T.red)
                    .accessibilityIdentifier("05-userinput-error")
            }
        }
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-userinput-card")
    }

    private var simpleQuestion: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text(prompt)
                .font(T.font(13.5, .medium))
                .foregroundColor(T.text)
            if !interaction.options.isEmpty {
                FlowChips(
                    items: interaction.options.map(\.label),
                    identifierPrefix: "05-userinput-option") { label in
                        guard let option = interaction.options.first(where: { $0.label == label }) else { return }
                        simpleSelection = option.id
                        submitSimple(optionId: option.id, freeText: nil)
                    }
            }
            if interaction.freeTextEnabled || interaction.options.isEmpty {
                HStack(spacing: T.sp2) {
                    TextField(
                        interaction.sensitive ? String(localized: "请输入（敏感内容）") : String(localized: "输入回答"),
                        text: $simpleText)
                        .textFieldStyle(.plain)
                        .font(T.font(13))
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 44)
                        .background(T.bgElevated)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .accessibilityIdentifier("05-userinput-text")
                    Button(String(localized: "提交")) {
                        let text = simpleText.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !text.isEmpty else { return }
                        submitSimple(optionId: nil, freeText: text)
                    }
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.accentText)
                    .frame(minWidth: 52, minHeight: 44)
                    .disabled(submitting)
                    .accessibilityIdentifier("05-userinput-submit")
                }
            }
        }
    }

    private func questionBlock(_ question: RemoteInteractionQuestion) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            if let header = question.header, !header.isEmpty {
                Text(header.uppercased())
                    .font(T.font(10, .semibold))
                    .foregroundColor(T.text3)
            }
            Text(question.question)
                .font(T.font(13, .medium))
                .foregroundColor(T.text)
            if !question.options.isEmpty {
                FlowChips(
                    items: question.options.map { option in
                        (selections[question.id]?.contains(option.id) == true ? "✓ " : "") + option.label
                    },
                    identifierPrefix: "05-userinput-option") { label in
                        let clean = label.hasPrefix("✓ ") ? String(label.dropFirst(2)) : label
                        guard let option = question.options.first(where: { $0.label == clean }) else { return }
                        toggle(question: question, value: option.id)
                    }
            }
            if question.options.isEmpty || interaction.freeTextEnabled {
                TextField(String(localized: "补充回答（可选）"), text: binding(for: question.id))
                    .textFieldStyle(.plain)
                    .font(T.font(13))
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 42)
                    .background(T.bgElevated)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .accessibilityIdentifier("05-userinput-text-\(question.id)")
            }
        }
    }

    private func binding(for questionID: String) -> Binding<String> {
        Binding(
            get: { texts[questionID] ?? "" },
            set: { texts[questionID] = $0 })
    }

    private func toggle(question: RemoteInteractionQuestion, value: String) {
        var current = selections[question.id] ?? []
        if question.multiSelect {
            if current.contains(value) { current.remove(value) } else { current.insert(value) }
        } else {
            current = current.contains(value) ? [] : [value]
        }
        selections[question.id] = current
    }

    private func submitQuestions() {
        var answers: [String: JSONValue] = [:]
        var content: [String: JSONValue] = [:]
        for (index, question) in interaction.questions.enumerated() {
            var values = Array(selections[question.id] ?? []).sorted()
            let text = (texts[question.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { values.append(text) }
            guard !values.isEmpty else { continue }
            answers[question.question] = .string(values.joined(separator: ", "))
            content["answer_\(index)"] = question.multiSelect
                ? .array(values.map { .string($0) })
                : .string(values[0])
        }
        content["answers"] = .object(answers)
        if interaction.questions.count == 1, let only = content["answer_0"] {
            content["answer"] = only
        }
        let answer = JSONValue.object([
            "action": .string("accept"),
            "content": .object(content),
        ])
        submit(answer)
    }

    private func submitSimple(optionId: String?, freeText: String?) {
        var answer: [String: JSONValue] = [:]
        if let optionId { answer["optionId"] = .string(optionId) }
        if let freeText { answer["freeText"] = .string(freeText) }
        guard !answer.isEmpty else { return }
        submit(.object(answer))
    }

    private func skip() {
        submit(.object(["action": .string("cancel")]))
    }

    private func submit(_ answer: JSONValue) {
        guard !submitting else { return }
        submitting = true
        errorText = nil
        Task {
            let failure = await viewModel.resolveUserInput(interaction, answer: answer)
            await MainActor.run {
                submitting = false
                errorText = failure
            }
        }
    }
}

// MARK: - 运行时初始化横幅（3.16.2 runtimeInitialization）

struct RuntimeInitializationBanner: View {
    let runtime: RuntimeInitializationInfo

    var body: some View {
        HStack(spacing: T.sp2) {
            if runtime.status == "initializing" {
                SpinnerView(size: 12)
            } else {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                    .foregroundColor(T.orange)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(runtime.status == "initializing"
                     ? String(localized: "运行时初始化中…")
                     : String(localized: "运行时初始化失败"))
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.text)
                if let detail = runtime.message?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !detail.isEmpty {
                    Text(detail)
                        .font(T.font(10.5))
                        .foregroundColor(T.text2)
                        .lineLimit(2)
                } else if let reason = runtime.reasonCode, !reason.isEmpty {
                    Text(reason)
                        .font(T.font(10.5))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, T.sp2)
        .background(T.bgElevated)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("05-runtime-init-banner")
    }
}

// MARK: - G24 工作区 MCP 待审核指示条（web WorkspaceMcpPendingIndicator 移动端同构）
//
// 触发 = state.workspaceMcpAdmission 非 null 且 pendingCount>0 且本地未忽略
// （WorkspaceHookPendingBanner.tsx:235-240 同条件）；「去审核」纯导航不发 RPC
// （web setPendingSettingsPluginIntent + openSettingsTab 同款，:302-308）；
// 「忽略」纯本地 renderer 内存态（:289-293 reminderStates 同口径）。

struct WorkspaceMcpPendingBanner: View {
    let count: Int
    let onReview: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "cable")
                .font(.system(size: 12))
                .foregroundColor(T.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "待信任的工作区 MCP"))
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.text)
                Text(count == 1
                     ? String(localized: "1 个服务器等待信任审核")
                     : String(localized: "\(count) 个服务器等待信任审核"))
                    .font(T.font(10.5))
                    .foregroundColor(T.text2)
            }
            Spacer(minLength: 0)
            Button(action: onReview) {
                Text("去审核")
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.seal)
            }
            .accessibilityIdentifier("05-mcp-banner-review")
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 10))
                    .foregroundColor(T.text3)
            }
            .accessibilityIdentifier("05-mcp-banner-dismiss")
        }
        .padding(.horizontal, T.sp3)
        .padding(.vertical, T.sp2)
        .background(T.bgElevated)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.4), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-mcp-pending-banner")
    }
}

// MARK: - G6 待办清单展开 sheet（web ConversationStatusPanel StatusCapsule id="todo"
// 可点展开的移动对位，2026-11-25）
//
// 上游展开态 = PlanStatusSection 全量渲染 plan.items（:552-566——completed 删除线+
// 最淡色、inProgress 箭头、pending 空心点）；v4.0.0 无「>6 条焦点窗」，全量直出。
// 头部徽标三态（:1408-1412）由会话行/头部既有 todoSummary 承载，本 sheet 只负责展开清单。

struct TodoListSheet: View {
    let plan: PlanPanelSummary

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: T.sp1) {
                    if let title = plan.title, !title.isEmpty {
                        Text(title)
                            .font(T.font(13, .semibold))
                            .foregroundColor(T.text)
                    }
                    ForEach(plan.items) { item in
                        HStack(alignment: .top, spacing: T.sp2) {
                            Text(item.glyph)
                                .font(T.font(12, .semibold))
                                .foregroundColor(
                                    item.isDone ? T.accentText : (item.isRunning ? T.seal : T.text3))
                                .frame(width: 16)
                            Text(item.title)
                                .font(T.font(12.5))
                                .foregroundColor(item.isDone ? T.text3 : T.text)
                                .strikethrough(item.isDone, color: T.text3)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("05-todo-item")
                    }
                    if plan.items.isEmpty {
                        Text(String(localized: "暂无待办条目"))
                            .font(T.font(12))
                            .foregroundColor(T.text3)
                    }
                }
                .padding(T.sp3)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(String(localized: "待办清单"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "关闭")) { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("05-todo-sheet")
    }
}

// MARK: - G28 子代理头像面孔（Android SessionPanels.kt 同构，2026-11-25）
//
// 中性底 + 墨字（朱批纪律：头像不用状态色铺底——「面孔 + 结果徽标点」两件事分开）；
// 徽标点左下角（web AgentResultBadge 同位）：完成绿 / 失败红 / 停止灰。

struct SubagentAvatarFace: View {
    let avatar: SubagentAvatarItem

    private var badgeColor: Color {
        switch avatar.badge {
        case .done: return T.green
        case .failed: return T.red
        case .running: return T.text3
        }
    }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Circle()
                .fill(T.bgInput)
                .overlay(Circle().stroke(T.bgCard, lineWidth: 1))
            Text(String((avatar.name?.first.map(String.init) ?? "A")).uppercased())
                .font(T.font(9.5, .semibold))
                .foregroundColor(T.text2)
            Circle()
                .fill(badgeColor)
                .frame(width: 5, height: 5)
                .overlay(Circle().stroke(T.bgCard, lineWidth: 0.5))
                .offset(x: -1, y: 1)
        }
        .frame(width: 16, height: 16)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(avatar.name ?? "agent")
    }
}

// MARK: - G49 滚动位置记忆（web chatSessionScrollMemory.ts 同构，2026-11-25）
//
// 进程内 LRU 200（web MAX_ENTRIES 同值；web 键 = workspace::pane::session——
// iOS 无多 pane，键 = workspace::session:{id}）；存顶层可见消息 id（web 存绝对
// scrollTop 的移动端近似：行 id 锚定翻页挤出时自动回退底部）；读即 touch。

@MainActor
final class ChatScrollMemory {
    static let shared = ChatScrollMemory()
    /// LRU 容量（web chatSessionScrollMemory.ts:9 MAX_ENTRIES 同值）
    static let capacity = 200

    private struct Entry { var messageId: String }
    private var entries: [String: Entry] = [:]
    /// 最旧在前（驱动 LRU 驱逐）
    private var order: [String] = []

    /// 键格式：{workspaceIdentity||workspacePath||"none"}::session:{conversationID}
    static func key(workspace: ServerWorkspaceInfo?, conversationID: String) -> String {
        let identity = workspace?.workspaceIdentity ?? ""
        let base: String
        if !identity.isEmpty {
            base = identity
        } else {
            let path = workspace?.path ?? ""
            base = path.isEmpty ? "none" : path
        }
        return "\(base)::session:\(conversationID)"
    }

    func save(key: String, messageId: String) {
        guard !messageId.isEmpty else { return }
        if entries[key] != nil {
            order.removeAll { $0 == key }
        }
        entries[key] = Entry(messageId: messageId)
        order.append(key)
        while order.count > Self.capacity {
            let oldest = order.removeFirst()
            entries.removeValue(forKey: oldest)
        }
    }

    /// 读即 touch（web :116-125 同口径）
    func load(key: String) -> String? {
        guard let entry = entries[key] else { return nil }
        order.removeAll { $0 == key }
        order.append(key)
        return entry.messageId
    }
}

// MARK: - G13 队列拖排序 DropDelegate（触屏长按拖对位桌面 hover-drag，2026-11-25）

/// 落点行委托：dropEntered/dropExited 驱动落点描边，performDrop 发 reorder CAS。
struct QueueDropDelegate: DropDelegate {
    let item: RemoteQueueItem
    @Binding var draggingItemId: String?
    @Binding var dropTargetId: String?
    /// (draggedId, targetId)：把拖起项插到落点行之前
    let onDrop: (String, String) -> Void

    func dropEntered(info: DropInfo) {
        if draggingItemId != nil, draggingItemId != item.id {
            dropTargetId = item.id
        }
    }

    func dropExited(info: DropInfo) {
        if dropTargetId == item.id {
            dropTargetId = nil
        }
    }

    func validateDrop(info: DropInfo) -> Bool {
        draggingItemId != nil
    }

    func performDrop(info: DropInfo) -> Bool {
        guard let draggedId = draggingItemId, draggedId != item.id else {
            draggingItemId = nil
            dropTargetId = nil
            return false
        }
        draggingItemId = nil
        dropTargetId = nil
        onDrop(draggedId, item.id)
        return true
    }
}

// MARK: - G38 命令中心统一搜索（web CommandCenterDialog.tsx:754-1084 移动端同构，
// 2026-11-25）
//
// 上游四 scope（all / commands">" / conversations":" / files"@" 前缀 + 手动 tabs，
// :167-182）；检索面：会话 = zcode-task listTaskList `search`（store.searchSessions
// 【实证·wire 已固化协议文档 §9.3】），文件 = 本地/服务端 searchFiles，命令 = 本地
// slashCommands 过滤；历史 = localStorage `zcode-command-center-search-history:<wsKey>`
// limit 20（commandCenterSearchHistory.ts:9-10 同词面落 UserDefaults）。移动端三 scope
// （全部/会话/文件——命令 scope 由 composer 斜杠菜单承载，独立页不加）。

/// G38 搜索历史存取（workspaceKey = identity||path||"none"；JSON
/// [{scope,query,updatedAt}] 数组，最新在前，limit 20——web 同词面同上限）。
enum CommandCenterHistory {
    static let prefix = "zcode-command-center-search-history:"
    static let limit = 20

    struct Entry: Codable, Equatable {
        let scope: String
        let query: String
        let updatedAt: Double
    }

    static func key(workspace: ServerWorkspaceInfo?) -> String {
        let identity = workspace?.workspaceIdentity ?? ""
        let base: String
        if !identity.isEmpty {
            base = identity
        } else {
            let path = workspace?.path ?? ""
            base = path.isEmpty ? "none" : path
        }
        return prefix + base
    }

    static func read(workspace: ServerWorkspaceInfo?) -> [Entry] {
        guard let data = UserDefaults.standard.data(forKey: key(workspace: workspace)) else {
            return []
        }
        return (try? JSONDecoder().decode([Entry].self, from: data)) ?? []
    }

    static func record(_ entry: Entry, workspace: ServerWorkspaceInfo?) {
        var entries = read(workspace: workspace).filter { $0.scope != entry.scope || $0.query != entry.query }
        entries.insert(entry, at: 0)
        if entries.count > limit {
            entries = Array(entries.prefix(limit))
        }
        if let data = try? JSONEncoder().encode(entries) {
            UserDefaults.standard.set(data, forKey: key(workspace: workspace))
        }
    }
}

struct CommandCenterSheet: View {
    let session: AppSession
    let store: ConversationStore
    /// 文件检索（FileStore.searchFiles——searchFiles 在 FileStore 协议不在 ConversationStore）
    let fileStore: FileStore
    /// 会话命中 → 宿主推入会话（关闭本 sheet 后由宿主导航）
    var onOpenConversation: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    /// "all" | "conversations" | "files"（上游 scope 词表子集；commands 由 composer 承载）
    @State private var scope = "all"
    @State private var sessions: [Conversation] = []
    @State private var files: [FileNode] = []
    @State private var searching = false
    @State private var searchTask: Task<Void, Never>?
    @State private var history: [CommandCenterHistory.Entry] = []
    @FocusState private var focused: Bool

    private var workspace: ServerWorkspaceInfo? { session.connection.workspace }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: T.sp2) {
                SearchField(text: $query, placeholder: String(localized: "统一搜索"), identifier: "05-cmd-search")
                    .padding(.horizontal, T.sp4)
                    .focused($focused)
                scopeChips
                historyChips
                results
            }
            .navigationTitle(String(localized: "统一搜索"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "关闭")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("05-command-center")
        .onAppear {
            history = CommandCenterHistory.read(workspace: workspace)
            focused = true
        }
        .onChange(of: query) { _, newValue in
            searchTask?.cancel()
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else {
                sessions = []
                files = []
                searching = false
                return
            }
            searching = true
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 400_000_000) // web 防抖同款窗
                guard !Task.isCancelled else { return }
                async let sessionHits = (scope == "all" || scope == "conversations")
                    ? store.searchSessions(trimmed) : []
                async let fileHits = (scope == "all" || scope == "files")
                    ? fileStore.searchFiles(trimmed, limit: 20) : []
                let (s, f) = await (sessionHits, fileHits)
                guard !Task.isCancelled else { return }
                sessions = s
                files = f
                searching = false
            }
        }
    }

    private var scopeChips: some View {
        HStack(spacing: T.sp2) {
            scopeChip("all", String(localized: "全部"))
            scopeChip("conversations", String(localized: "会话"))
            scopeChip("files", String(localized: "文件"))
            Spacer()
        }
        .padding(.horizontal, T.sp4)
    }

    private func scopeChip(_ id: String, _ label: String) -> some View {
        Button {
            scope = id
            Task { await refetchNow() }
        } label: {
            Text(label)
                .font(T.font(11.5, .semibold))
                .foregroundColor(scope == id ? T.onAccent : T.text2)
                .padding(.horizontal, T.sp2)
                .padding(.vertical, 5)
                .background(scope == id ? T.accent : T.bgInput)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("05-cmd-scope-\(id)")
    }

    private var historyChips: some View {
        Group {
            if query.isEmpty, !history.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: T.sp1) {
                        ForEach(history, id: \.updatedAt) { entry in
                            Button {
                                query = entry.query
                                if entry.scope != "all" { scope = entry.scope }
                            } label: {
                                HStack(spacing: 2) {
                                    if entry.scope != "all" {
                                        Text(entry.scope == "files"
                                             ? "@" : ":")
                                            .font(T.mono(9.5))
                                            .foregroundColor(T.seal)
                                    }
                                    Text(entry.query)
                                        .font(T.font(10.5))
                                }
                                .foregroundColor(T.text2)
                                .padding(.horizontal, T.sp2)
                                .padding(.vertical, 3)
                                .background(T.bgInput)
                                .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("05-cmd-history")
                        }
                    }
                    .padding(.horizontal, T.sp4)
                }
            }
        }
    }

    @ViewBuilder
    private var results: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp1) {
                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    EmptyView()
                } else if searching {
                    HStack {
                        Spacer()
                        SpinnerView(size: 14)
                        Spacer()
                    }
                    .padding(.top, T.sp4)
                } else {
                    if scope == "all" || scope == "conversations" {
                        sectionHeader(String(localized: "会话"))
                        if sessions.isEmpty {
                            resultEmpty(String(localized: "无匹配会话"))
                        } else {
                            ForEach(sessions) { item in
                                Button {
                                    CommandCenterHistory.record(
                                        .init(scope: scope, query: query.trimmingCharacters(in: .whitespacesAndNewlines),
                                              updatedAt: Date().timeIntervalSince1970),
                                        workspace: workspace)
                                    dismiss()
                                    onOpenConversation(item.id)
                                } label: {
                                    resultRow(icon: "bubble.left", title: item.title,
                                              meta: item.projectName ?? "")
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("05-cmd-hit-session-\(item.id)")
                            }
                        }
                    }
                    if scope == "all" || scope == "files" {
                        sectionHeader(String(localized: "文件"))
                        if files.isEmpty {
                            resultEmpty(String(localized: "无匹配文件"))
                        } else {
                            ForEach(files) { file in
                                Button {
                                    CommandCenterHistory.record(
                                        .init(scope: scope, query: query.trimmingCharacters(in: .whitespacesAndNewlines),
                                              updatedAt: Date().timeIntervalSince1970),
                                        workspace: workspace)
                                } label: {
                                    resultRow(icon: "doc.text", title: file.name, meta: file.path)
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("05-cmd-hit-file-\(file.id)")
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp1)
        }
    }

    /// scope 切换即重查（有 query 时）
    private func refetchNow() async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        async let s = (scope == "all" || scope == "conversations")
            ? store.searchSessions(trimmed) : []
        async let f = (scope == "all" || scope == "files")
            ? fileStore.searchFiles(trimmed, limit: 20) : []
        let (sHits, fHits) = await (s, f)
        sessions = sHits
        files = fHits
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(T.font(11, .semibold))
            .foregroundColor(T.text3)
            .padding(.top, T.sp2)
    }

    private func resultEmpty(_ text: String) -> some View {
        Text(text)
            .font(T.font(12))
            .foregroundColor(T.text3)
            .padding(.leading, T.sp2)
    }

    private func resultRow(icon: String, title: String, meta: String) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(T.text3)
            Text(title)
                .font(T.font(13))
                .foregroundColor(T.text)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(meta)
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .lineLimit(1)
        }
        .padding(.horizontal, T.sp2)
        .padding(.vertical, 6)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rS))
    }
}

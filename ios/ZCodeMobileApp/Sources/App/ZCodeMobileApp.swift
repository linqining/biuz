import SwiftUI

@main
struct ZCodeMobileApp: App {
    @State private var router = AppRouter()
    @State private var badges = TabBadges()
    @State private var appSettings: AppSettingsModel
    @State private var session = AppSession()
    // 装配策略（对齐修复，设计稿 §1.2）：未连接默认空实现（无假数据）；连接成功后由
    // AppSession 驱动切换真实 Store。-ZCodeDemoData（E2E 演示开关）下初值/回退为
    // Mock——用户裁决「Mock 假数据全部移除，仅测试用例允许」
    @State private var conversationStore: any ConversationStore =
        AppSession.isDemoDataEnabled ? MockConversationStore() : EmptyConversationStore()
    @State private var taskStore: any TaskStore =
        AppSession.isDemoDataEnabled ? MockTaskStore() : EmptyTaskStore()
    @State private var fileStore: any FileStore =
        AppSession.isDemoDataEnabled ? MockFileStore() : EmptyFileStore()
    /// G-015：分享链接入口（zcode://share/<code> 或 https://…/share/<code>）
    @State private var sharePreviewCode: String?

    init() {
        let settingsStore = UserDefaultsSettingsStore()
        _appSettings = State(initialValue: AppSettingsModel(store: settingsStore))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(router)
                .environment(badges)
                .environment(appSettings)
                .environment(session)
                .environment(\.conversationStore, conversationStore)
                .environment(\.taskStore, taskStore)
                .environment(\.fileStore, fileStore)
                .preferredColorScheme(appSettings.value.appearance.colorScheme)
                // G-015：分享链接/二维码打开 → 只读预览页（公开分享未登录可看）
                .onOpenURL { url in
                    if let code = ShareLinkParser.shareCode(from: url) {
                        sharePreviewCode = code
                    }
                }
                .sheet(item: Binding(
                    get: { sharePreviewCode.map(SharePreviewPayload.init) },
                    set: { if $0 == nil { sharePreviewCode = nil } })) { payload in
                    SharePreviewSheet(shareCode: payload.code)
                }
                .tint(T.accent)
                .task { await bootstrapIfNeeded() }
                .task(id: session.mode) { await syncStoresWithSession() }
                // P3-10 多工作区切换：同 .connected 内换工作区不改变 mode（Equatable 只含
                // ServerConfig），由 AppSession.storeEpoch（assemble/teardown 时 +1）驱动
                // syncStoresWithSession 重跑完成环境值换绑；mode 触发面保留（断线保留
                // 快照、失败换空实现等装配切换）。函数幂等（读当前 session 态赋值），
                // 双触发无冲突。
                .task(id: session.storeEpoch) { await syncStoresWithSession() }
                .task(id: appSettings.value.notificationsEnabled) {
                    // 「通知」开关真联动（P1-7）：授权请求 / 撤销待投递
                    await NotificationService.shared.syncEnabled(appSettings.value.notificationsEnabled)
                    await installNotificationRoute()
                }
                .fullScreenCover(item: Binding(
                    get: { session.presentedFlow },
                    set: { session.presentedFlow = $0 })) { flow in
                    switch flow {
                    case .login:
                        LoginFlowView()
                            .environment(session)
                            // cover 内容不自动继承根环境（session 同款教训）：连接页/
                            // 扫码页/失败页现读 AppSettingsModel（开发者模式 gating），
                            // 缺注入 = EnvironmentValues 下标 trap 闪退（test16 首跑实证）
                            .environment(appSettings)
                    case .connect(let editTokenOnly):
                        ConnectFlowView(initialEditTokenOnly: editTokenOnly)
                            .environment(session)
                            .environment(appSettings)
                    }
                }
        }
    }

    /// 通知点击路由（P1-7）：跳对应任务详情（任务不在缓存时退回任务 Tab 根）
    private func installNotificationRoute() async {
        guard NotificationService.shared.routeHandler == nil else { return }
        NotificationService.shared.routeHandler = { taskID in
            Task { @MainActor in
                router.selectedTab = .tasks
                router.taskPath = []
                let tasks = await taskStore.tasks()
                if let match = tasks.first(where: { $0.id == taskID }) {
                    router.pushTask(taskID: taskID, task: match)
                }
            }
        }
    }

    /// 冷启动装配（未配置 → 连接引导页为根；E2E 演示开关下保持旧演示四 Tab）
    private func bootstrapIfNeeded() async {
        await session.bootstrap()
        await syncStoresWithSession()
        // G-015 验收钩子：`-ZCodeShareLink <code>` 冷启直开分享只读页（无 UI 驱动路径，
        // 模式同 -ZCodeRelayLink；zcode://share/<code> 的 onOpenURL 热路径之外的冷启入口）
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "-ZCodeShareLink"),
           index + 1 < ProcessInfo.processInfo.arguments.count {
            sharePreviewCode = ProcessInfo.processInfo.arguments[index + 1]
        }
        // 诊断钩子：`-ZCodeOpenConversationId <id>` 冷启直达会话详情（复现会话内问题用）
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "-ZCodeOpenConversationId"),
           index + 1 < ProcessInfo.processInfo.arguments.count {
            let conversationID = ProcessInfo.processInfo.arguments[index + 1]
            router.selectedTab = .chat
            router.chatPath = [.chat(conversationID)]
        }
    }

    /// 装配换源（对齐修复，设计稿 §1.2）：
    /// - .connected → 真实 Store（AppSession.connectToSaved → assembleRemoteStores 装配）
    /// - .disconnected / .connecting → 保留现有引用不动（断线保留最后快照、连接中不闪空）
    /// - .demo / .connectFailed → 空实现（connectFailed 已 teardown，引用为 nil）；
    ///   E2E 演示开关 -ZCodeDemoData 下回退 Mock 三件（仅测试用例允许，正式路径不装配）
    private func syncStoresWithSession() async {
        switch session.mode {
        case .connected:
            if let remoteConversation = session.remoteConversationStore,
               let remoteTask = session.remoteTaskStore,
               let remoteFile = session.remoteFileStore {
                conversationStore = remoteConversation
                taskStore = remoteTask
                fileStore = remoteFile
            }
        case .connecting, .disconnected:
            break
        case .demo, .connectFailed:
            if AppSession.isDemoDataEnabled {
                conversationStore = MockConversationStore()
                taskStore = MockTaskStore()
                fileStore = MockFileStore()
            } else {
                conversationStore = EmptyConversationStore()
                taskStore = EmptyTaskStore()
                fileStore = EmptyFileStore()
            }
        }
    }
}

extension AppSession.PresentedFlow: Identifiable {
    var id: String {
        switch self {
        case .login: return "login"
        case .connect(let editTokenOnly): return "connect-\(editTokenOnly)"
        }
    }
}


/// G-015 分享预览 sheet 标识
struct SharePreviewPayload: Identifiable {
    let code: String
    var id: String { code }
}

import SwiftUI

@main
struct ZCodeMobileApp: App {
    @State private var router = AppRouter()
    @State private var badges = TabBadges()
    @State private var appSettings: AppSettingsModel
    @State private var session = AppSession()
    // 装配策略：默认 mock；连接成功后由 AppSession 驱动切换为真实 Store
    @State private var conversationStore: any ConversationStore = MockConversationStore()
    @State private var taskStore: any TaskStore = MockTaskStore()
    @State private var fileStore: any FileStore = MockFileStore()

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
                .tint(T.accent)
                .task { await bootstrapIfNeeded() }
                .task(id: session.mode) { await syncStoresWithSession() }
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
                    case .connect(let editTokenOnly):
                        ConnectFlowView(initialEditTokenOnly: editTokenOnly)
                            .environment(session)
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

    /// 冷启动装配（需求：未配置时直接进入演示模式，e2e 行为不变）
    private func bootstrapIfNeeded() async {
        session.bootstrap()
        await syncStoresWithSession()
    }

    /// 连接成功 → 真实 Store；失败/未配置 → mock 回退（离线可用）
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
        case .demo, .connectFailed, .disconnected, .connecting:
            // 回退演示数据（mock 常驻内存，行为与既有验收一致）
            conversationStore = MockConversationStore()
            taskStore = MockTaskStore()
            fileStore = MockFileStore()
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

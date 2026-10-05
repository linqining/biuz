import SwiftUI

/// 全局导航路由：4 Tab 各自独立 NavigationStack，Push 层隐藏 Tab 栏（spec 3.2）
enum ChatRoute: Hashable {
    case chat(String)
}

enum TaskRoute: Hashable {
    case output(TaskRecord)
}

enum FileRoute: Hashable {
    case tree
    case preview(FileNode)
    /// G-023：提交图谱 / 分支对比只读页
    case commitGraph
}

enum SettingsRoute: String, Hashable {
    case model, appearance, language, devices, bots, usage, memory, skills, mcp, plugins, automation
    // v2.4 增量：服务器与账户（L4-B 配置区 / L5 服务器详情）
    case serverAccount, serverDetail
    // P2 批次只读页（G-022/G-024/G-025）
    case savedWorkflows, offPeakTasks, feedbackTickets
    case diagnostics // G-061 导出诊断日志
}

@MainActor
@Observable
final class AppRouter {
    enum Tab: Int, CaseIterable, Hashable {
        case chat = 0, tasks, files, settings
    }

    var selectedTab: Tab = .chat
    var chatPath: [ChatRoute] = []
    var taskPath: [TaskRoute] = []
    var filePath: [FileRoute] = []
    var settingsPath: [SettingsRoute] = []

    /// Push 深度 > 0 时隐藏底部 Tab 栏
    var isPushing: Bool {
        !chatPath.isEmpty || !taskPath.isEmpty || !filePath.isEmpty || !settingsPath.isEmpty
    }

    /// Diff 动作栏显隐：文件 Tab 根页（spec 3.2 底部动作栏叠于 Tab 栏之上）
    var showsDiffActionBar: Bool {
        selectedTab == .files && filePath.isEmpty
    }

    /// 跨视图刷新令牌（底部动作栏全部批准后驱动列表与角标刷新）
    var diffReloadToken = 0

    func openChat(conversationID: String) {
        selectedTab = .chat
        chatPath = [.chat(conversationID)]
    }

    func pushTask(taskID: String, task: TaskRecord) {
        selectedTab = .tasks
        taskPath.append(.output(task))
    }

    func pushFileTree() {
        selectedTab = .files
        filePath.append(.tree)
    }

    /// G-023：文件域内 push（提交图谱等只读页）
    func pushFileRoute(_ route: FileRoute) {
        selectedTab = .files
        filePath.append(route)
    }

    /// 「查看完整 Diff」从会话内跳转到文件 Tab 根
    func openDiffFromChat() {
        selectedTab = .files
        filePath = []
        diffReloadToken += 1
    }

    func pushSettings(_ route: SettingsRoute) {
        selectedTab = .settings
        settingsPath.append(route)
    }
}

// MARK: - Tab 角标数据（三色语义：任务蓝 / 会话橙 / 文件橙）

@MainActor
@Observable
final class TabBadges {
    var runningTasks = 0
    var unreadChats = 0
    var pendingDiffs = 0
}

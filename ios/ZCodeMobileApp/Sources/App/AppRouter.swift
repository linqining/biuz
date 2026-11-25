import SwiftUI

// APP-DIFF[D002] platform: 全局导航用 NavigationStack 系统返回语义（web 为 history.pushState +
// popstate，浏览器历史无原生对应；3 Tab 各自独立 stack，Push 层隐藏 Tab 栏）
// 2026-11-25 重设计裁决 R2：底部三 tab = 会话 / 待批 / 设置；任务看板与全局文件页撤除
// （更改/文件收进会话页 sheet，执行输出挂会话 ⋯ 菜单——见 design/design-spec.md §1）。
enum ChatRoute: Hashable {
    case chat(String)
}

/// 待批 tab 内打开会话的路由值：**独立类型**——chat 与待批两个 NavigationStack
/// 常驻 ZStack 同时挂载，若共用 ChatRoute 会对同一类型重复注册 navigationDestination
/// （SwiftUI 多栈同类型注册歧义：push 被路由进未选中的栈，点行不导航——2026-11-25
/// test20 门禁红 + 基线对照实证）。
enum ApprovalChatRoute: Hashable {
    case chat(String)
}

/// 会话页「文件」sheet 内的导航值（树 → 预览 → 提交图谱；栈归 sheet 宿主私有，
/// 不再是全局 tab 路由——2026-11-25 三 tab 重构）
enum FileRoute: Hashable {
    case tree
    case preview(FileNode)
    /// G-023：提交图谱 / 分支对比只读页
    case commitGraph
}

enum SettingsRoute: String, Hashable {
    case model, appearance, language, devices, bots, usage, memory, skills, mcp, plugins, automation
    // 服务器与账户（配置区 / 服务器详情）
    case serverAccount, serverDetail
    // P2 批次只读页（G-022/G-024/G-025）
    case savedWorkflows, offPeakTasks, feedbackTickets
    case desktopSettings // P3-11C 桌面设置同步（settingService get/update）
    case autoArchive // G43 自动归档（setting 频道 taskAutoArchiveEnabled/OlderThanDays）
    case behaviorSettings // G41 行为设置（setting 频道五键策展面）
    // 027/024/026/018 四页（上游取证 2026-11-10；RemoteSettingsExtrasViews.swift）
    case personalization, hooks, subagents, projects
    case diagnostics // G-061 导出诊断日志
}

@MainActor
@Observable
final class AppRouter {
    enum Tab: Int, CaseIterable, Hashable {
        case chat = 0, approvals, settings
    }

    var selectedTab: Tab = .chat
    var chatPath: [ChatRoute] = []
    /// 待批 tab 自己的导航栈（内联打开会话，返回回待批列表；独立路由类型——
    /// 与会话栈同挂载，共用类型会对 ChatRoute 重复注册 navigationDestination）
    var approvalsPath: [ApprovalChatRoute] = []
    var settingsPath: [SettingsRoute] = []

    /// Push 深度 > 0 时隐藏底部 Tab 栏。
    ///
    /// **只按当前选中 tab 的栈深判定**（2026-11-25 用户报「从会话页跳进设置没有底部栏」）：
    /// 会话栈里 push 着聊天页时切到设置 tab，若按「任一栈非空」判定，设置 tab 根页的
    /// 底栏也会被藏掉——settings 是 tab 根页却没有 tab 栏（用户因此要求左上角返回钮，
    /// 那条返回钮本身是这个 bug 的补丁）。按选中 tab 判定后：push 详情仍隐藏底栏
    /// （2026-10-07 语义保持），切 tab 即恢复底栏。
    var isPushing: Bool {
        switch selectedTab {
        case .chat: return !chatPath.isEmpty
        case .approvals: return !approvalsPath.isEmpty
        case .settings: return !settingsPath.isEmpty
        }
    }

    func openChat(conversationID: String) {
        selectedTab = .chat
        chatPath = [.chat(conversationID)]
    }

    /// 待批列表点行：在待批栈内打开会话（返回键回待批列表，不丢上下文）
    func openChatFromApprovals(conversationID: String) {
        selectedTab = .approvals
        approvalsPath = [.chat(conversationID)]
    }

    func pushSettings(_ route: SettingsRoute) {
        selectedTab = .settings
        settingsPath.append(route)
    }

    /// 验收缝（web-to-app `bootstrap.md` §5）：冷启直达任意页面，供实拍/自动化用。
    ///
    /// 用法：`-acceptance-route <key>[:<arg>]`，key 取 `config/page-index.json` 的页面 key 或短别名。
    /// 仅测试生效、零用户影响——未知 key 返回 false 由调用方告警，不猜测落点。
    /// seam 只解决「到页即得」，交互链验收仍需补真实入口端到端（`acceptance.md`）。
    @discardableResult
    func applyAcceptanceRoute(_ raw: String) -> Bool {
        // 中文 key 经 simctl/launchd 传入时可能是 NFD（macOS 文件名规范化口径），
        // 与源码字面量的 NFC 不相等 → 统一规范化后再比较。
        let normalized = raw.precomposedStringWithCanonicalMapping
        let parts = normalized.split(separator: ":", maxSplits: 1).map(String.init)
        guard let key = parts.first else { return false }
        let arg = parts.count > 1 ? parts[1] : nil

        switch key {
        case "010-任务主页", "home":
            selectedTab = .chat
            chatPath = []
        case "011-会话对话", "chat":
            guard let id = arg, !id.isEmpty else { return false }
            openChat(conversationID: id)
        case "012-待批", "approvals":
            selectedTab = .approvals
            approvalsPath = []
        case "013-设置", "settings":
            selectedTab = .settings
            settingsPath = arg.flatMap(SettingsRoute.init(rawValue:)).map { [$0] } ?? []
        default:
            return false
        }
        return true
    }
}

// MARK: - Tab 角标数据（会话橙 / 待批朱磦——印色只给「要你落笔」的位置，design-spec §2）

@MainActor
@Observable
final class TabBadges {
    var unreadChats = 0
    /// 跨会话待批合计（sessions-index pendingInteractionSummary 投影；
    /// permission + userInput，RemoteConversationStore.sortedConversations 已逐行投影）
    var pendingApprovals = 0
}

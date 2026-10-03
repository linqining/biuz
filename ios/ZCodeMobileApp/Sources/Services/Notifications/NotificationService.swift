import Foundation
import UserNotifications

// MARK: - 任务状态/待审批本地通知（P1-7：设置页「通知」假开关真实化）
//
// 数据源：RemoteTaskStore.handleTaskEvent 的状态迁移（→done/failed/waiting 三类）。
// 口径对齐桌面 desktopNotifications：正文带上下文摘要（变更规模或待审批提示），
// (status,taskId) 3 秒去重；开关关闭撤销待投递并停止投递。
// 内容构造为纯函数（TaskNotificationContent.make），UnitTests 锁三类映射、
// 去重键与摘要截断。通知到达与点击路由为真机手动验收项（模拟器 UNUserNotification
// 行为不完整），iOS 快捷动作本轮仅「打开」单按钮（审批在应用内完成）。

/// 通知内容纯函数构造（无 UNUserNotificationCenter 依赖，可单测）
struct TaskNotificationContent: Equatable {
    var dedupKey: String      // "(status,taskId)" 去重键
    var identifier: String    // UNNotificationRequest.identifier（去重键散列）
    var title: String
    var body: String
    var taskID: String

    /// 三类状态映射；其余状态（running）不产生通知（返回 nil）。
    /// - done     → 「任务完成」+ 变更规模摘要
    /// - failed   → 「任务失败」+ 最后错误摘要
    /// - waiting  → 「等待你的批准」+ 待审批提示
    /// - summary 超过 80 字符截断（省略号收尾）
    static func make(taskID: String, taskTitle: String, status: TaskStatus,
                     changeSummary: String?) -> TaskNotificationContent? {
        let titleText: String
        let bodyPrefix: String
        switch status {
        case .done:
            titleText = "任务完成"
            bodyPrefix = "\(taskTitle) 已完成"
        case .failed:
            titleText = "任务失败"
            bodyPrefix = "\(taskTitle) 执行失败"
        case .waiting:
            titleText = "等待你的批准"
            bodyPrefix = "\(taskTitle) 有操作待审批"
        case .running:
            return nil
        }
        var detail = changeSummary?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if detail.count > 80 {
            detail = String(detail.prefix(80)) + "…"
        }
        let bodyText = detail.isEmpty ? bodyPrefix : "\(bodyPrefix)：\(detail)"
        let dedupKey = "(\(status.rawValue),\(taskID))"
        return TaskNotificationContent(
            dedupKey: dedupKey,
            identifier: "task-\(taskID)-\(status.rawValue)",
            title: titleText,
            body: bodyText,
            taskID: taskID)
    }
}

@MainActor
final class NotificationService: NSObject {

    static let shared = NotificationService()

    /// 设置页「通知」开关镜像（关闭时不投递、撤销待投递）
    private var enabled = false
    /// (status,taskId) → 最近投递时间（3 秒去重窗口）
    private var recentFires: [String: Date] = [:]
    /// 点击路由（由 App 装配：跳对应任务详情）
    var routeHandler: ((String) -> Void)?

    override private init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    /// 开关联动（App 启动/持久化恢复时调用）：仅镜像开关态；关闭 → 撤销待投递。
    /// 授权请求只在用户显式打开开关时发起（requestEnable），避免冷启动弹系统
    /// 授权框干扰自动化验收。
    func syncEnabled(_ enabled: Bool) async {
        self.enabled = enabled
        if !enabled {
            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
            recentFires.removeAll()
        }
    }

    /// 用户把开关拨到开：请求通知授权（拒绝授权时开关语义仍记录，投递静默失败）
    func requestEnable() async {
        enabled = true
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }

    /// 任务状态迁移入口（RemoteTaskStore.handleTaskEvent 调用，主线程）。
    /// 关闭态/3 秒去重窗口内静默；内容构造失败（running 等）不投递。
    func handleTaskStatusChange(taskID: String, taskTitle: String,
                                status: TaskStatus, changeSummary: String?) {
        guard enabled else { return }
        guard let content = TaskNotificationContent.make(
            taskID: taskID, taskTitle: taskTitle, status: status,
            changeSummary: changeSummary) else { return }
        let now = Date()
        if let last = recentFires[content.dedupKey], now.timeIntervalSince(last) < 3 {
            return
        }
        recentFires[content.dedupKey] = now
        // 去重表防膨胀：只保留 60 秒内的键
        recentFires = recentFires.filter { now.timeIntervalSince($0.value) < 60 }

        let notificationContent = UNMutableNotificationContent()
        notificationContent.title = content.title
        notificationContent.body = content.body
        notificationContent.sound = .default
        notificationContent.userInfo = ["taskID": content.taskID]
        let request = UNNotificationRequest(
            identifier: content.identifier, content: notificationContent, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

extension NotificationService: UNUserNotificationCenterDelegate {

    /// 前台也横幅呈现（远控场景手机常在前台）
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        return [.banner, .sound]
    }

    /// 点击路由：userInfo.taskID → routeHandler（App 装配为任务详情跳转）
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        guard let taskID = response.notification.request.content.userInfo["taskID"] as? String else {
            return
        }
        await MainActor.run {
            NotificationService.shared.routeHandler?(taskID)
        }
    }
}

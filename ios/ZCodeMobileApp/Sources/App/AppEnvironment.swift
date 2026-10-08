import SwiftUI

/// 开发者模式（连接页开发向入口 gating）：App 内开关持久化于 AppSettings.developerMode
/// （设置页品牌行连点 7 次切换）；`-ZCodeDevMode` 启动参数为开发构建 / E2E 强制开启
/// 通道（手动连接链路用例依赖该参数保持旧断言面）
enum DeveloperMode {
    static let launchArgument = "-ZCodeDevMode"

    static var forcedByLaunchArgument: Bool {
        ProcessInfo.processInfo.arguments.contains(launchArgument)
    }
}

/// 设置模型：UserDefaults 持久化（外观 / 通知 / 模型 / 思考等级 / 语言）
@MainActor
@Observable
final class AppSettingsModel {
    private let store: SettingsStore

    var value: AppSettings {
        didSet {
            guard oldValue != value else { return }
            store.save(value)
        }
    }

    init(store: SettingsStore) {
        self.store = store
        self.value = store.load()
    }

    func update(_ mutate: (inout AppSettings) -> Void) {
        mutate(&value)
    }

    /// 开发者模式生效判定（设置开关 || 启动参数强制）
    var effectiveDeveloperMode: Bool {
        value.developerMode || DeveloperMode.forcedByLaunchArgument
    }
}

// MARK: - Tab 项定义

struct TabItemModel: Identifiable {
    let tab: AppRouter.Tab
    let title: String
    let icon: String
    let selectedIcon: String
    let identifier: String

    var id: Int { tab.rawValue }
}

extension TabItemModel {
    /// spec 3.1：图标 22px + 标签 10.5px；选择器沿用设计稿 5.10 标注
    static let all: [TabItemModel] = [
        TabItemModel(tab: .chat, title: "会话",
                     icon: "bubble.left.and.text.bubble.right",
                     selectedIcon: "bubble.left.and.text.bubble.right.fill",
                     identifier: "04-tab-chat"),
        TabItemModel(tab: .tasks, title: "任务",
                     icon: "tray",
                     selectedIcon: "tray.full.fill",
                     identifier: "02-tab-tasks"),
        TabItemModel(tab: .files, title: "文件",
                     icon: "arrow.left.arrow.right",
                     selectedIcon: "arrow.left.arrow.right",
                     identifier: "08-tab-review"),
        TabItemModel(tab: .settings, title: "设置",
                     icon: "gearshape",
                     selectedIcon: "gearshape.fill",
                     identifier: "12-tab-me"),
    ]
}

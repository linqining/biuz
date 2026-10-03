import Foundation

/// UserDefaults 持久化实现（外观 / 通知 / 模型 / 思考等级 / 语言）。
struct UserDefaultsSettingsStore: SettingsStore {

    private let defaults: UserDefaults
    private enum Key {
        static let settings = "zcode.settings.v1"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> AppSettings {
        guard let data = defaults.data(forKey: Key.settings),
              let settings = try? JSONDecoder().decode(AppSettings.self, from: data)
        else { return AppSettings() }
        return settings
    }

    func save(_ settings: AppSettings) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: Key.settings)
    }
}

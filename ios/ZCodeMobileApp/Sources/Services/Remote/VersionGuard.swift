import Foundation

/// 桌面版本变化守卫（web `webRemoteControlVersion.ts:26-52` / Android `VersionGuard.kt` 同口径）。
///
/// 判据：**配对链接声明的 app_version** ≠ **bootstrap 回报的 desktopAppVersion** 时提示。
/// 任一侧为空不判（旧桌面不带版本 / 手填链接）——宁可放过也不误拦。
enum VersionGuard {
    /// 返回需要提示的「桌面当前版本」；不需要提示返回 nil。
    static func mismatch(linkVersion: String?, desktopVersion: String?) -> String? {
        let actual = (desktopVersion ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let declared = (linkVersion ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !actual.isEmpty, !declared.isEmpty else { return nil }
        return declared == actual ? nil : actual
    }
}

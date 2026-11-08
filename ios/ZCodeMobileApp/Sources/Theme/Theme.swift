import SwiftUI
import UIKit

// MARK: - Hex 支持

extension UIColor {
    convenience init(hex: String, alpha: CGFloat = 1) {
        var v = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if v.hasPrefix("#") { v.removeFirst() }
        var value: UInt64 = 0
        Scanner(string: v).scanHexInt64(&value)
        let r, g, b: CGFloat
        switch v.count {
        case 6:
            r = CGFloat((value >> 16) & 0xFF) / 255
            g = CGFloat((value >> 8) & 0xFF) / 255
            b = CGFloat(value & 0xFF) / 255
        case 3:
            r = CGFloat((value >> 8) & 0xF) / 15
            g = CGFloat((value >> 4) & 0xF) / 15
            b = CGFloat(value & 0xF) / 15
        default:
            r = 0; g = 0; b = 0
        }
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }
}

extension Color {
    init(hex: String, alpha: CGFloat = 1) {
        self.init(uiColor: UIColor(hex: hex, alpha: alpha))
    }
    /// 深浅双档令牌色（深色为设计默认档，Light 档按规范 2.2 同构映射）
    init(light: String, dark: String, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) {
        self.init(uiColor: UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(hex: dark, alpha: darkAlpha)
                : UIColor(hex: light, alpha: lightAlpha)
        })
    }
}

// MARK: - 设计令牌（qoder 参考： monochrome 主色 + 语义状态色）
//
// 设计参照 qoder app（2026-11-08 提炼）：极简近白/近黑中性色、monochrome 近黑主按钮，
// 语义状态色独立保留——running #2FBF71 / completed #3B82F6 / error #EF4444 /
// attention #F5A623。accent 收敛为 monochrome 主色（浅档近黑、深档近白），active/
// selected/connected/主按钮统一走它；运行态绿另由 green 承担。圆角靠拢 ~16dp。

enum T {
    static let bg            = Color(light: "fdfdfd", dark: "080807")
    static let bgCard        = Color(light: "ffffff", dark: "1c1c1e")
    static let bgElevated    = Color(light: "ffffff", dark: "232326")
    static let bgInput       = Color(light: "f4f4f5", dark: "2a2a2e")
    static let bgCode        = Color(light: "f6f6f7", dark: "141416")
    static let bgTerm        = Color(light: "fbfbfc", dark: "050505")
    static let border        = Color(light: "000000", dark: "ffffff", lightAlpha: 0.12, darkAlpha: 0.20)
    static let borderStrong  = Color(light: "000000", dark: "ffffff", lightAlpha: 0.20, darkAlpha: 0.30)
    static let text          = Color(light: "111113", dark: "ffffff")
    static let text2         = Color(light: "52525b", dark: "a1a1aa")
    static let text3         = Color(light: "71717a", dark: "71717a")
    // monochrome 主色（qoder 近黑主按钮）：浅档近黑/深档近白；onAccent 取反
    static let accent        = Color(light: "18181b", dark: "fafafa")
    static let accentText    = Color(light: "18181b", dark: "fafafa")
    static let accentPress   = Color(light: "000000", dark: "e4e4e7")
    static let accentDim     = Color(light: "18181b", dark: "fafafa", lightAlpha: 0.08, darkAlpha: 0.10)
    // 语义状态色（qoder）：completed 蓝 / attention 琥珀 / error 红 / running 绿
    static let blue          = Color(light: "3b82f6", dark: "60a5fa")
    static let blueDim       = Color(light: "3b82f6", dark: "60a5fa", lightAlpha: 0.10, darkAlpha: 0.14)
    static let orange        = Color(light: "b45309", dark: "f5a623")
    static let orangeBright  = Color(light: "f5a623", dark: "f5a623")
    static let orangeDim     = Color(light: "b45309", dark: "f5a623", lightAlpha: 0.10, darkAlpha: 0.14)
    static let red           = Color(light: "ef4444", dark: "f87171")
    static let redDim        = Color(light: "ef4444", dark: "f87171", lightAlpha: 0.10, darkAlpha: 0.12)
    static let redLine       = Color(light: "ef4444", dark: "f87171", lightAlpha: 0.65, darkAlpha: 0.55)
    static let green         = Color(light: "16a34a", dark: "2fbf71")
    static let greenBright   = Color(light: "2fbf71", dark: "2fbf71")
    static let greenDim      = Color(light: "16a34a", dark: "2fbf71", lightAlpha: 0.10, darkAlpha: 0.12)
    static let violet        = Color(light: "7c3aed", dark: "c792ff")
    static let violetDim     = Color(light: "7c3aed", dark: "c792ff", lightAlpha: 0.10, darkAlpha: 0.12)
    static let add           = Color(light: "0a7340", dark: "3ddc84")
    static let addBg         = Color(light: "0a7340", dark: "32f08c", lightAlpha: 0.10, darkAlpha: 0.10)
    static let del           = Color(light: "b32e20", dark: "ff7a7a")
    static let delBg         = Color(light: "b32e20", dark: "ff5d5d", lightAlpha: 0.10, darkAlpha: 0.10)
    static let codeKw        = Color(light: "7c3aed", dark: "c792ff")
    static let codeLab       = Color(light: "1a6fd4", dark: "8f9bdd")
    static let codeLabDim    = Color(light: "1a6fd4", dark: "7d8bdd", lightAlpha: 0.08, darkAlpha: 0.08)
    static let badgeFg       = Color(light: "ffffff", dark: "18181b")
    static let onAccent      = Color(light: "ffffff", dark: "18181b")
    static let onOrange      = Color(light: "ffffff", dark: "1a0f00")

    static let tabbarBg      = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(hex: "080807", alpha: 0.92)
            : UIColor(hex: "ffffff", alpha: 0.92)
    })

    static let gradAvatar = LinearGradient(
        colors: [Color(light: "52525b", dark: "a1a1aa"), Color(light: "71717a", dark: "71717a")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradBubble = LinearGradient(
        colors: [Color(light: "f4f4f5", dark: "1c1c1e"), Color(light: "e4e4e7", dark: "232326")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradUserCard = LinearGradient(
        colors: [Color(light: "f4f4f5", dark: "1c1c1e"), Color(light: "ffffff", dark: "1c1c1e")],
        startPoint: .top, endPoint: .bottom)

    static let rS: CGFloat = 8
    static let rM: CGFloat = 14
    static let rL: CGFloat = 16
    static let rPill: CGFloat = 999

    static let sp1: CGFloat = 4
    static let sp2: CGFloat = 8
    static let sp3: CGFloat = 12
    static let sp4: CGFloat = 16
    static let sp6: CGFloat = 24
    static let sp8: CGFloat = 32

    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight)
    }
    static func mono(_ size: CGFloat = 12, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    static let shadowCard = Color.black.opacity(0.45)
    static let shadowFab = Color(light: "000000", dark: "000000", lightAlpha: 0.18, darkAlpha: 0.50)
}

// MARK: - 外观偏好（跟随系统 / Zai Light / Zai Dark）

enum AppearanceMode: String, CaseIterable, Identifiable, Codable {
    case system, light, dark
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: return String(localized: "跟随系统")
        case .light: return "Zai Light"
        case .dark: return "Zai Dark"
        }
    }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

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
    // 色板 —— 完全对齐 ZCode web（用户裁决 2026-11-09「与 ZCode web 设计一致，先不用之前设计的 UI」；
    // 原 qoder 色板作废）。取值 = 线上手机壳实测 + packages/ui/src/styles.css :root/.dark：
    //   dark : bg #161616 · card/popover #171717 · surface #262626 · 正文 #d4d4d4(oklch .87)
    //          次级 60% alpha · 最弱 30% alpha · brand #e5e5e5(oklch .922) · destructive #ff6467
    //   light: bg #fafafa · card #ffffff · 正文 #404040 · 次级 60% · 最弱 40%
    static let bg            = Color(light: "fafafa", dark: "161616")
    static let bgCard        = Color(light: "ffffff", dark: "171717")
    static let bgElevated    = Color(light: "ffffff", dark: "262626")
    static let bgInput       = Color(light: "f5f5f5", dark: "393939")
    static let bgCode        = Color(light: "f5f5f5", dark: "171717")
    static let bgTerm        = Color(light: "fbfbfc", dark: "0f0f0f")
    static let border        = Color(light: "000000", dark: "ffffff", lightAlpha: 0.10, darkAlpha: 0.10)
    static let borderStrong  = Color(light: "000000", dark: "ffffff", lightAlpha: 0.15, darkAlpha: 0.15)
    static let text          = Color(light: "404040", dark: "d4d4d4")
    static let text2         = Color(light: "404040", dark: "d4d4d4", lightAlpha: 0.60, darkAlpha: 0.60)
    static let text3         = Color(light: "404040", dark: "d4d4d4", lightAlpha: 0.40, darkAlpha: 0.30)
    // monochrome 主色（web --color-brand）：浅档近黑/深档近白；onAccent 取反
    static let accent        = Color(light: "171717", dark: "e5e5e5")
    static let accentText    = Color(light: "171717", dark: "e5e5e5")
    static let accentPress   = Color(light: "000000", dark: "d4d4d4")
    static let accentDim     = Color(light: "171717", dark: "ffffff", lightAlpha: 0.10, darkAlpha: 0.10)
    // 语义状态色（web）：completed 蓝 / attention 琥珀（实测 #ff8a30）/ error 红（#ff6467）/ running 绿（#46bf72）
    static let blue          = Color(light: "3b82f6", dark: "60a5fa")
    static let blueDim       = Color(light: "3b82f6", dark: "60a5fa", lightAlpha: 0.10, darkAlpha: 0.14)
    static let orange        = Color(light: "b45309", dark: "ff8a30")
    static let orangeBright  = Color(light: "f5a623", dark: "ff8a30")
    static let orangeDim     = Color(light: "b45309", dark: "ff8a30", lightAlpha: 0.10, darkAlpha: 0.14)
    static let red           = Color(light: "dc2626", dark: "ff6467")
    static let redDim        = Color(light: "dc2626", dark: "ff6467", lightAlpha: 0.10, darkAlpha: 0.12)
    static let redLine       = Color(light: "dc2626", dark: "ff6467", lightAlpha: 0.65, darkAlpha: 0.55)
    static let green         = Color(light: "16a34a", dark: "46bf72")
    static let greenBright   = Color(light: "22c55e", dark: "46bf72")
    static let greenDim      = Color(light: "16a34a", dark: "46bf72", lightAlpha: 0.10, darkAlpha: 0.12)
    static let violet        = Color(light: "7c3aed", dark: "c792ff")
    static let violetDim     = Color(light: "7c3aed", dark: "c792ff", lightAlpha: 0.10, darkAlpha: 0.12)
    static let add           = Color(light: "16a34a", dark: "22c55e")
    static let addBg         = Color(light: "16a34a", dark: "22c55e", lightAlpha: 0.10, darkAlpha: 0.10)
    static let del           = Color(light: "dc2626", dark: "ff6467")
    static let delBg         = Color(light: "dc2626", dark: "ff6467", lightAlpha: 0.10, darkAlpha: 0.10)
    static let codeKw        = Color(light: "7c3aed", dark: "c792ff")
    static let codeLab       = Color(light: "1a6fd4", dark: "8f9bdd")
    static let codeLabDim    = Color(light: "1a6fd4", dark: "7d8bdd", lightAlpha: 0.08, darkAlpha: 0.08)
    static let badgeFg       = Color(light: "ffffff", dark: "171717")
    static let onAccent      = Color(light: "ffffff", dark: "171717")
    static let onOrange      = Color(light: "ffffff", dark: "1a0f00")

    static let tabbarBg      = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(hex: "161616", alpha: 0.92)
            : UIColor(hex: "ffffff", alpha: 0.92)
    })

    static let gradAvatar = LinearGradient(
        colors: [Color(light: "52525b", dark: "a1a1aa"), Color(light: "71717a", dark: "71717a")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradBubble = LinearGradient(
        colors: [Color(light: "f5f5f5", dark: "171717"), Color(light: "e5e5e5", dark: "262626")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradUserCard = LinearGradient(
        colors: [Color(light: "f5f5f5", dark: "171717"), Color(light: "ffffff", dark: "171717")],
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

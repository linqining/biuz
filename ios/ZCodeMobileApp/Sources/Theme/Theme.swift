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

// MARK: - 设计令牌（design/design-spec.md §2）

enum T {
    static let bg            = Color(light: "f4f5f7", dark: "0a0b0d")
    static let bgCard        = Color(light: "ffffff", dark: "121316")
    static let bgElevated    = Color(light: "ffffff", dark: "17181c")
    static let bgInput       = Color(light: "eceef2", dark: "1a1c21")
    static let bgCode        = Color(light: "f6f7f9", dark: "0d0e10")
    static let bgTerm        = Color(light: "fbfbfc", dark: "050607")
    static let border        = Color(light: "e3e5e9", dark: "26282e")
    static let borderStrong  = Color(light: "d2d5db", dark: "34373f")
    static let text          = Color(light: "17181c", dark: "f5f9fe")
    static let text2         = Color(light: "4b5563", dark: "a6aab5")
    static let text3         = Color(light: "5f6673", dark: "8a8f9b")
    static let accent        = Color(light: "17b26a", dark: "32f08c")
    static let accentText    = Color(light: "0a7f45", dark: "32f08c")
    static let accentPress   = Color(light: "128a55", dark: "28c974")
    static let accentDim     = Color(light: "17b26a", dark: "32f08c", lightAlpha: 0.10, darkAlpha: 0.12)
    static let blue          = Color(light: "1a6fd4", dark: "4da3ff")
    static let blueDim       = Color(light: "1a6fd4", dark: "4da3ff", lightAlpha: 0.10, darkAlpha: 0.14)
    static let orange        = Color(light: "9a5200", dark: "ffb224")
    static let orangeBright  = Color(light: "ffb224", dark: "ffb224")
    static let orangeDim     = Color(light: "9a5200", dark: "ffb224", lightAlpha: 0.10, darkAlpha: 0.14)
    static let red           = Color(light: "c53030", dark: "ff5d5d")
    static let redDim        = Color(light: "c53030", dark: "ff5d5d", lightAlpha: 0.10, darkAlpha: 0.12)
    static let redLine       = Color(light: "c53030", dark: "ff5d5d", lightAlpha: 0.65, darkAlpha: 0.55)
    static let violet        = Color(light: "7c3aed", dark: "c792ff")
    static let violetDim     = Color(light: "7c3aed", dark: "c792ff", lightAlpha: 0.10, darkAlpha: 0.12)
    static let add           = Color(light: "0a7340", dark: "3ddc84")
    static let addBg         = Color(light: "0a7340", dark: "32f08c", lightAlpha: 0.10, darkAlpha: 0.10)
    static let del           = Color(light: "b32e20", dark: "ff7a7a")
    static let delBg         = Color(light: "b32e20", dark: "ff5d5d", lightAlpha: 0.10, darkAlpha: 0.10)
    static let codeKw        = Color(light: "7c3aed", dark: "c792ff")
    static let codeLab       = Color(light: "1a6fd4", dark: "8f9bdd")
    static let codeLabDim    = Color(light: "1a6fd4", dark: "7d8bdd", lightAlpha: 0.08, darkAlpha: 0.08)
    static let badgeFg       = Color(light: "ffffff", dark: "0d1500")
    static let onAccent      = Color(light: "04120a", dark: "04120a")
    static let onOrange      = Color(light: "ffffff", dark: "1a0f00")

    static let tabbarBg      = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(hex: "0d0e10", alpha: 0.92)
            : UIColor(hex: "ffffff", alpha: 0.92)
    })

    static let gradAvatar = LinearGradient(
        colors: [Color(light: "1a6fd4", dark: "4da3ff"), Color(light: "5f6673", dark: "7d8bdd")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradBubble = LinearGradient(
        colors: [Color(light: "e3f6ec", dark: "1d2a23"), Color(light: "dcefe5", dark: "152019")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradUserCard = LinearGradient(
        colors: [Color(light: "eafaf1", dark: "131a16"), Color(light: "ffffff", dark: "121316")],
        startPoint: .top, endPoint: .bottom)

    static let rS: CGFloat = 8
    static let rM: CGFloat = 12
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
    static let shadowFab = Color(hex: "32f08c").opacity(0.35)
}

// MARK: - 外观偏好（跟随系统 / Zai Light / Zai Dark）

enum AppearanceMode: String, CaseIterable, Identifiable, Codable {
    case system, light, dark
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: return "跟随系统"
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

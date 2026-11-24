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

// MARK: - 设计令牌（朱批印系：纸 / 墨 / 朱磦 / 钤灰，monochrome 主色 + 语义状态色）
//
// 2026-11-21 朱批品牌升级：色板自「ZCode web 对齐灰板」重映射为 brand/seal 印系四色
// （朱磦 #D2402E / 墨 #17181A / 纸 #F7F3EC / 钤灰 #8A8F98）——浅色档 = 纸底墨字，
// 深色档 = 墨底纸字（深色为设计默认档）。主按钮仍走 monochrome accent（浅档墨、
// 深档纸，onAccent 取反），朱磦以 seal 系 token 承担品牌/审批/印面语义（盖章动效、
// 在线点、审批卡 eyebrow），与 destructive red 分工不混用。状态色族沿用 web 口径。

enum T {
    // 色板 —— 朱批印系（light 档 2026-11-24 与 Android 同步加深，原 ZCode web 对齐板作废）：
    //   light: 纸底 **#F2EADB** · 卡=纸（层次靠 12% 墨描边）· 输入 #E8DFCB · 正文墨 #17181A
    //   dark : 墨底 #17181A · 卡 #1C1D20 · 面 #232428 · 正文纸 #F7F3EC（次级 62% / 最弱 30%）
    //   印色 : 朱磦 #D2402E（加深 #B03020）——盖章/审批/品牌位专用，destructive red 独立不混用
    //
    // ⚠ 纸底加深的理由（用户三次报「不是纸色」）：旧值 #F7F3EC 离纯白只差 8/12/19 阶
    // （约 4% 暖调），手机屏上读作浅灰/白；用户自带截图与 adb 截图逐像素一致，证明渲染
    // 没错、**值**不对。现 #F2EADB 差 13/21/36 阶，肉眼即纸。卡面与纸同色（不再用
    // #FFFDF9/#FFFFFF 的「比纸白一档」——那正是满屏白的来源）。
    // 与 brand/seal/README.md:39 出现偏差，待用户裁决是否回写品牌档。
    static let bg            = Color(light: "f2eadb", dark: "17181a")
    static let bgCard        = Color(light: "f2eadb", dark: "1c1d20")
    static let bgElevated    = Color(light: "f7f1e4", dark: "232428")
    static let bgInput       = Color(light: "e8dfcb", dark: "222326")
    static let bgCode        = Color(light: "eee6d5", dark: "1b1c1f")
    static let bgTerm        = Color(light: "efe8d8", dark: "131416")
    static let border        = Color(light: "17181a", dark: "f7f3ec", lightAlpha: 0.12, darkAlpha: 0.12)
    static let borderStrong  = Color(light: "17181a", dark: "f7f3ec", lightAlpha: 0.18, darkAlpha: 0.18)
    static let text          = Color(light: "17181a", dark: "f7f3ec")
    static let text2         = Color(light: "17181a", dark: "f7f3ec", lightAlpha: 0.62, darkAlpha: 0.62)
    static let text3         = Color(light: "17181a", dark: "f7f3ec", lightAlpha: 0.40, darkAlpha: 0.30)
    // monochrome 主色（web --color-brand）：浅档近黑/深档近白；onAccent 取反
    static let accent        = Color(light: "17181a", dark: "f0ede6")
    static let accentText    = Color(light: "17181a", dark: "f0ede6")
    static let accentPress   = Color(light: "000000", dark: "d8d5ce")
    static let accentDim     = Color(light: "17181a", dark: "f7f3ec", lightAlpha: 0.10, darkAlpha: 0.10)
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
    static let badgeFg       = Color(light: "ffffff", dark: "17181a")
    static let onAccent      = Color(light: "f7f3ec", dark: "17181a")
    static let onOrange      = Color(light: "ffffff", dark: "1a0f00")
    // 朱磦印系（2026-11-21）：盖章动效 / 审批卡 eyebrow / 在线心跳点 / 品牌位专用
    static let seal           = Color(light: "d2402e", dark: "d2402e")
    static let sealPress      = Color(light: "b03020", dark: "b03020")
    static let sealDim        = Color(light: "d2402e", dark: "d2402e", lightAlpha: 0.10, darkAlpha: 0.16)
    static let onSeal         = Color(light: "f7f3ec", dark: "f7f3ec")

    static let tabbarBg      = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(hex: "17181a", alpha: 0.92)
            : UIColor(hex: "fffdf9", alpha: 0.92)
    })

    static let gradAvatar = LinearGradient(
        colors: [Color(light: "52525b", dark: "a1a1aa"), Color(light: "71717a", dark: "71717a")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradBubble = LinearGradient(
        colors: [Color(light: "f1ece3", dark: "1c1d20"), Color(light: "e9e3d8", dark: "232428")],
        startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gradUserCard = LinearGradient(
        colors: [Color(light: "f1ece3", dark: "1c1d20"), Color(light: "fffdf9", dark: "17181a")],
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

// MARK: - 外观偏好（跟随系统 / 朱批 · 浅色 / 朱批 · 深色）

enum AppearanceMode: String, CaseIterable, Identifiable, Codable {
    case system, light, dark
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: return String(localized: "跟随系统")
        case .light: return String(localized: "朱批 · 浅色")
        case .dark: return String(localized: "朱批 · 深色")
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

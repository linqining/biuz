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
    // 色板 —— 朱批印系（light 档 2026-11-25 裁决回退：纸 #F2EADB → #F7F3EC，iOS 上太深；
    // 双端同源跟随，层次仍靠描边不靠白度差。原 ZCode web 对齐板作废）：
    //   light: 纸底 **#F7F3EC** · 卡=纸（层次靠 12% 墨描边）· 输入 #EFE9DF · 正文墨 #17181A
    //   dark : 墨底 #17181A · 卡 #1C1D20 · 面 #232428 · 正文纸 #F7F3EC（次级 62% / 最弱 30%）
    //   印色 : 朱磦 #D2402E（加深 #B03020）——盖章/审批/品牌位专用，destructive red 独立不混用
    //
    // 纸值沿革（防再来回横跳）：#F7F3EC（品牌原值）→ 2026-11-24 加深 #F2EADB（Android
    // 三报「不是纸色」）→ 2026-11-25 用户看 iOS 实机报「太深」裁定回退 #F7F3EC，品牌档
    // （brand/seal/README.md）同步回写。浅灰/白感知若再报，处置方向=卡面描边/投影另案，
    // 不再单改纸基值（基值已两度横跳，品牌档与资产跟着重导两次，成本高）。
    static let bg            = Color(light: "f7f3ec", dark: "17181a")
    static let bgCard        = Color(light: "f7f3ec", dark: "1c1d20")
    static let bgElevated    = Color(light: "faf7f0", dark: "232428")
    static let bgInput       = Color(light: "efe9df", dark: "222326")
    static let bgCode        = Color(light: "f1ece3", dark: "1b1c1f")
    static let bgTerm        = Color(light: "f2eee6", dark: "131416")
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

    // tab 栏底色（2026-11-25 用户「底部配色不协调」）：与纸/墨**同色不透明**平涂——
    // 原 #FFFDF9@0.92 + ultraThinMaterial 叠在纸底上偏白，底栏与内容区出现色差割裂。
    // 品牌口径「同一张纸，层次靠分隔线不靠色差」：分隔由 ZCodeTabBar 顶部 Divider（12% 墨）承担
    static let tabbarBg      = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(hex: "17181a", alpha: 1.0)
            : UIColor(hex: "f7f3ec", alpha: 1.0)
    })

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

    // G42 界面字号缩放（web uiFontSize.ts localStorage 同构——本地偏好非桌面
    // setting；px 12–20 默认 14，multiplier 只乘字号 token 基准，不动图标/间距/圆角）
    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size * UiFontSize.multiplier, weight: weight)
    }
    static func mono(_ size: CGFloat = 12, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size * UiFontSize.multiplier, weight: weight, design: .monospaced)
    }

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

// MARK: - G42 界面字号（web uiFontSize.ts 同构，2026-11-25）
//
// localStorage `zcode-ui-font-size-px` 同词面落 UserDefaults；px clamp 12–20 默认 14
// （uiFontSize.ts:4-5 DEFAULT/MIN/MAX 同值）；multiplier = px/14 只乘 T.font/T.mono
// 的基准——不缩图标/间距/圆角（applyUiFontSizePx :24 注释同口径）。静态缓存 +
// NotificationCenter 通知（设置行写入后轻提示，下次渲染生效）。

enum UiFontSize {
    static let key = "zcode-ui-font-size-px"
    static let defaultValue: CGFloat = 14
    static let minValue: CGFloat = 12
    static let maxValue: CGFloat = 20
    static let changeNotification = Notification.Name("uiFontSizeChanged")

    static var px: CGFloat {
        get {
            let raw = UserDefaults.standard.double(forKey: key)
            guard raw > 0 else { return defaultValue }
            return min(maxValue, max(minValue, raw))
        }
        set {
            let clamped = min(maxValue, max(minValue, newValue))
            UserDefaults.standard.set(Double(clamped), forKey: key)
            UserDefaults.standard.synchronize()
            cachedMultiplier = clamped / defaultValue
            NotificationCenter.default.post(name: changeNotification, object: nil)
        }
    }

    private static var cachedMultiplier: CGFloat = UserDefaults.standard.double(forKey: key) > 0
        ? min(maxValue, max(minValue, UserDefaults.standard.double(forKey: key))) / defaultValue
        : 1

    static var multiplier: CGFloat { cachedMultiplier }
}

// MARK: - G42 残面 代码预览偏好（web codePreviewSettings 同构，2026-11-25）
//
// localStorage `zcode-code-preview-settings` 同词面落 UserDefaults；三键：
// fontSizePx 12–20 默认 12（store/index.ts:112-115 clamp 同值）、showLineNumbers
// 默认 true、wrapLongLines 默认 false（codePreviewSettings.ts:18-24 DEFAULT 逐字）。
// 主题双键（light/dTheme shiki）移动端 TinySyntax 无 shiki 主题面——不投影，登记差异。

enum CodePreviewSettings {
    static let key = "zcode-code-preview-settings"

    struct Values: Equatable, Codable {
        var fontSizePx: CGFloat = 12
        var showLineNumbers: Bool = true
        var wrapLongLines: Bool = false
    }

    static func read() -> Values {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode(Values.self, from: data) else {
            return Values()
        }
        var v = decoded
        v.fontSizePx = min(20, max(12, v.fontSizePx))
        return v
    }

    static func write(_ values: Values) {
        if let data = try? JSONEncoder().encode(values) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

// MARK: - G9 行高缓存（上游 timelineRowHeightCache.ts 逐字语义移植，2026-11-25）
//
// 为什么需要它：虚拟化容器自身卸载/重挂会丢测量，流式行持续增长时缓存重置导致
// 滚动条跳动与底部锚定抖动——按稳定键（messageId）做持久缓存保「行重挂不丢测高」。
// DEFAULT_ROW_HEIGHT_ESTIMATE_PX=72 / MAX_ROW_HEIGHT_CACHE_ENTRIES=4000 /
// Map 插入序淘汰 / 会话切换 clear——上游 :13-58 逐字同语义（消费面 = 行测高写入 +
// 滚动恢复/锚定估高数据源；活尾跟随判定见 ChatView）。

@MainActor
final class TimelineRowHeightCache {
    static let defaultRowHeightEstimatePx: CGFloat = 72
    static let maxEntries = 4000

    private var sizes: [String: CGFloat] = [:]
    /// 插入序（最新在尾）驱动淘汰
    private var order: [String] = []

    var count: Int { sizes.count }

    func set(key: String, heightPx: CGFloat) {
        guard heightPx.isFinite, heightPx > 0 else { return }
        if sizes[key] != nil {
            order.removeAll { $0 == key }
        }
        sizes[key] = heightPx
        order.append(key)
        while order.count > Self.maxEntries {
            let oldest = order.removeFirst()
            sizes.removeValue(forKey: oldest)
        }
    }

    func estimate(_ key: String?, fallbackPx: CGFloat = TimelineRowHeightCache.defaultRowHeightEstimatePx) -> CGFloat {
        guard let key else { return fallbackPx }
        return sizes[key] ?? fallbackPx
    }

    func clear() {
        sizes.removeAll()
        order.removeAll()
    }
}

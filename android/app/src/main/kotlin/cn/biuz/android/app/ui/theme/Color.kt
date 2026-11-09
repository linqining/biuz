package cn.biuz.android.app.ui.theme

import androidx.compose.ui.graphics.Color

// 色板 —— **完全对齐 ZCode web**（用户裁决 2026-11-09「整个 app 与 ZCode web 设计样式保持一致，
// 先不用之前设计的 UI，完全复刻」；原 qoder 色板作废）。
//
// 取值来源：线上手机壳实测（`zcode.z.ai/remote/v4` 390×844 计算样式）+ web `packages/ui/src/styles.css`
// 的 :root / .dark 块；light 档与 iOS `Theme.swift:68-95`（同为 web 实测）逐值对齐：
//   dark : bg #161616 · card/popover #171717 · surface #262626 · 正文 #d4d4d4(oklch .87)
//          次级 = 正文 60% alpha · 最弱 = 正文 30% alpha · brand #e5e5e5(oklch .922) · destructive #ff6467
//   分隔线 = 白 10% alpha · 输入/强边 = 白 15% alpha
//   light: bg #fafafa · card/popover/surface #ffffff · 正文 #404040 · 次级 60% · 最弱 40%
//          brand #171717 · destructive #dc2626 · 分隔线 黑 10% · 输入 黑 15%
//   任务轨道实测：running #46bf72 / attention #ff8a30（web task-item 站点灯）
//
// 【P-003 主题切换】此前所有色是**顶层常量**（650 处引用）→ 无法切浅色。现改为
// 顶层 getter 读 [BiuZColors.current]（调色板快照），[BiuZTheme] 在组合前写入对应档：
// **650 处引用点零改动**，且切换外观即全局换肤。

/** 一套完整色板（dark / light 各一份）。 */
data class BiuZPalette(
    val bg: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val bgInput: Color,
    val bgCode: Color,
    val bgTerm: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentPress: Color,
    val accentDim: Color,
    val onAccent: Color,
    val green: Color,
    val greenDim: Color,
    val blue: Color,
    val blueDim: Color,
    val orange: Color,
    val onOrange: Color,
    val orangeDim: Color,
    val red: Color,
    val redDim: Color,
    val violet: Color,
    val add: Color,
    val del: Color,
    val lightAccent: Color,
)

val BiuZDarkPalette = BiuZPalette(
    bg = Color(0xFF161616), // --color-background
    bgCard = Color(0xFF171717), // --color-card / --color-popover
    bgElevated = Color(0xFF262626), // --color-surface（浮层/Sheet）
    bgInput = Color(0x26FFFFFF), // --color-input = 白 15% alpha
    bgCode = Color(0xFF171717), // 代码块底（同 card）
    bgTerm = Color(0xFF0F0F0F), // 终端底
    border = Color(0x1AFFFFFF), // 白 10% alpha
    borderStrong = Color(0x26FFFFFF), // 白 15% alpha
    text = Color(0xFFD4D4D4),
    text2 = Color(0x99D4D4D4),
    text3 = Color(0x4DD4D4D4),
    accent = Color(0xFFE5E5E5),
    accentPress = Color(0xFFD4D4D4),
    accentDim = Color(0x1AFFFFFF),
    onAccent = Color(0xFF171717),
    green = Color(0xFF46BF72),
    greenDim = Color(0x2446BF72),
    blue = Color(0xFF60A5FA),
    blueDim = Color(0x2460A5FA),
    orange = Color(0xFFFF8A30),
    onOrange = Color(0xFF1A0F00),
    orangeDim = Color(0x24FF8A30),
    red = Color(0xFFFF6467),
    redDim = Color(0x24FF6467),
    violet = Color(0xFFC792FF),
    add = Color(0xFF22C55E),
    del = Color(0xFFFF6467),
    lightAccent = Color(0xFF171717),
)

val BiuZLightPalette = BiuZPalette(
    bg = Color(0xFFFAFAFA),
    bgCard = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFFFFFFF),
    bgInput = Color(0x26000000), // 黑 15% alpha
    bgCode = Color(0xFFF5F5F5),
    bgTerm = Color(0xFFFBFBFC),
    border = Color(0x1A000000), // 黑 10% alpha
    borderStrong = Color(0x26000000),
    text = Color(0xFF404040),
    text2 = Color(0x99404040),
    text3 = Color(0x66404040), // light 档最弱 40%
    accent = Color(0xFF171717),
    accentPress = Color(0xFF000000),
    accentDim = Color(0x1A000000),
    onAccent = Color(0xFFFFFFFF),
    green = Color(0xFF16A34A),
    greenDim = Color(0x1A16A34A),
    blue = Color(0xFF3B82F6),
    blueDim = Color(0x1A3B82F6),
    orange = Color(0xFFB45309),
    onOrange = Color(0xFFFFFFFF),
    orangeDim = Color(0x1AB45309),
    red = Color(0xFFDC2626),
    redDim = Color(0x1ADC2626),
    violet = Color(0xFF7C3AED),
    add = Color(0xFF16A34A),
    del = Color(0xFFDC2626),
    lightAccent = Color(0xFFE5E5E5),
)

/** 当前生效调色板（[BiuZTheme] 在组合前写入；默认深色）。 */
object BiuZColors {
    @Volatile
    var current: BiuZPalette = BiuZDarkPalette
}

val BiuZBg: Color get() = BiuZColors.current.bg
val BiuZBgCard: Color get() = BiuZColors.current.bgCard
val BiuZBgElevated: Color get() = BiuZColors.current.bgElevated
val BiuZBgInput: Color get() = BiuZColors.current.bgInput
val BiuZBgCode: Color get() = BiuZColors.current.bgCode
val BiuZBgTerm: Color get() = BiuZColors.current.bgTerm
val BiuZBorder: Color get() = BiuZColors.current.border
val BiuZBorderStrong: Color get() = BiuZColors.current.borderStrong
val BiuZText: Color get() = BiuZColors.current.text
val BiuZText2: Color get() = BiuZColors.current.text2
val BiuZText3: Color get() = BiuZColors.current.text3
val BiuZAccent: Color get() = BiuZColors.current.accent
val BiuZAccentPress: Color get() = BiuZColors.current.accentPress
val BiuZAccentDim: Color get() = BiuZColors.current.accentDim
val BiuZOnAccent: Color get() = BiuZColors.current.onAccent
val BiuZGreen: Color get() = BiuZColors.current.green
val BiuZGreenDim: Color get() = BiuZColors.current.greenDim
val BiuZBlue: Color get() = BiuZColors.current.blue
val BiuZBlueDim: Color get() = BiuZColors.current.blueDim
val BiuZOrange: Color get() = BiuZColors.current.orange
val BiuZOnOrange: Color get() = BiuZColors.current.onOrange
val BiuZOrangeDim: Color get() = BiuZColors.current.orangeDim
val BiuZRed: Color get() = BiuZColors.current.red
val BiuZRedDim: Color get() = BiuZColors.current.redDim
val BiuZViolet: Color get() = BiuZColors.current.violet
val BiuZAdd: Color get() = BiuZColors.current.add
val BiuZDel: Color get() = BiuZColors.current.del
val BiuZLightAccent: Color get() = BiuZColors.current.lightAccent

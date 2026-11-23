package cn.biuz.android.app.ui.theme

import androidx.compose.ui.graphics.Color

// 色板 —— **朱批印系**（2026-11-21 朱批品牌升级，原「ZCode web 对齐板」作废；出处 brand/seal/README.md，
// 与 iOS Theme.swift 同构映射）：
//   light: 纸底 #F7F3EC · 卡 #FFFDF9 · 输入 #EFE9DF · 正文墨 #17181A（次级 62% / 最弱 40%）
//   dark : 墨底 #17181A · 卡 #1C1D20 · 面 #232428 · 正文纸 #F7F3EC（次级 62% / 最弱 30%）
//   印色 : 朱磦 #D2402E（加深 #B03020）——盖章/审批/品牌位专用，destructive red 分工不变
//   状态色族沿用 web 口径：running #46bf72 / attention #ff8a30 等。
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
    val seal: Color,
    val sealPress: Color,
    val sealDim: Color,
    val onSeal: Color,
)

val BiuZDarkPalette = BiuZPalette(
    bg = Color(0xFF17181A), // 墨底（印系）
    bgCard = Color(0xFF1C1D20), // 卡
    bgElevated = Color(0xFF232428), // 浮层/Sheet
    bgInput = Color(0xFF222326), // 输入底
    bgCode = Color(0xFF1B1C1F), // 代码块底
    bgTerm = Color(0xFF131416), // 终端底
    border = Color(0x1FF7F3EC), // 纸 12% alpha
    borderStrong = Color(0x2EF7F3EC), // 纸 18% alpha
    text = Color(0xFFF7F3EC),
    text2 = Color(0x9EF7F3EC),
    text3 = Color(0x4DF7F3EC),
    accent = Color(0xFFF0EDE6),
    accentPress = Color(0xFFD8D5CE),
    accentDim = Color(0x1AF7F3EC),
    onAccent = Color(0xFF17181A),
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
    lightAccent = Color(0xFF17181A),
    seal = Color(0xFFD2402E), // 朱磦（印系主印色）
    sealPress = Color(0xFFB03020), // 朱磦加深（按压）
    sealDim = Color(0x29D2402E), // 朱磦 16% alpha（深档 dim）
    onSeal = Color(0xFFF7F3EC), // 印面纸字
)

val BiuZLightPalette = BiuZPalette(
    bg = Color(0xFFF7F3EC),
    bgCard = Color(0xFFFFFDF9),
    bgElevated = Color(0xFFFFFFFF),
    bgInput = Color(0xFFEFE9DF), // 纸·暗一档
    bgCode = Color(0xFFF1ECE3),
    bgTerm = Color(0xFFF2EEE6),
    border = Color(0x1F17181A), // 墨 12% alpha
    borderStrong = Color(0x2E17181A), // 墨 18% alpha
    text = Color(0xFF17181A),
    text2 = Color(0x9E17181A),
    text3 = Color(0x6617181A), // light 档最弱 40%
    accent = Color(0xFF17181A),
    accentPress = Color(0xFF000000),
    accentDim = Color(0x1A17181A),
    onAccent = Color(0xFFF7F3EC),
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
    lightAccent = Color(0xFFF0EDE6),
    seal = Color(0xFFD2402E),
    sealPress = Color(0xFFB03020),
    sealDim = Color(0x1AD2402E), // 朱磦 10% alpha（浅档 dim）
    onSeal = Color(0xFFF7F3EC),
)

/** 当前生效调色板（[BiuZTheme] 在组合前写入；**默认浅色**——2026-11-24 用户裁决朱批默认浅色主题）。 */
object BiuZColors {
    @Volatile
    var current: BiuZPalette = BiuZLightPalette
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
val BiuZSeal: Color get() = BiuZColors.current.seal
val BiuZSealPress: Color get() = BiuZColors.current.sealPress
val BiuZSealDim: Color get() = BiuZColors.current.sealDim
val BiuZOnSeal: Color get() = BiuZColors.current.onSeal

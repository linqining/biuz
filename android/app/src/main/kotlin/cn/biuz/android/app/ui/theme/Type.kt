package cn.biuz.android.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 字号阶梯：8px 栅格派生。
// 字体栈用系统默认（spec --font-ui 为系统栈零外部依赖；mono 场景后续代码块屏再落 FontFamily.Monospace）。
/**
 * 排版阶（**文本只允许这 8 档**）。
 *
 * 2026-11-24 UIUX 审查：全仓曾 504 处硬编码 `fontSize`、20 种字号（含 6 个 x.5 半档
 * 共 108 处——差 0.5sp 在 400+ ppi 屏肉眼不可辨，却多出 6 个样式）。本轮已把
 * 「9 / 9.5 / 10.5 / 11.5 / 12.5 / 13.5 / 14.5 / 15.5」全部并入最近整数档。
 *
 * 规则：
 * - **文本**（消息、标题、标签、时间戳）只取 [BiuZFont] 的 8 档；
 * - **字形**（emoji/箭头/图标字符：📌 ↑ ⋯ ✕ ◎ 等）取 [BiuZGlyph]，按视觉尺寸而非排版阶梯；
 * - 新增字号前先问「能不能用现有档」；确需新档须同时更新本文件与守卫测试
 *   `TypographyScaleGuardTest`。
 */
object BiuZFont {
    /** 页面标题（22/28） */
    const val display = 22
    /** 页/卡主标题（17/24） */
    const val title = 17
    /** 区块标题、正文强调（15/22） */
    const val subtitle = 15
    /** 消息正文、列表主行（14/22） */
    const val body = 14
    /** 行内次要文本（13/20） */
    const val bodyCompact = 13
    /** 次级说明、时间戳（12/18） */
    const val caption = 12
    /** 芯片文案、徽标（11/16） */
    const val micro = 11
    /** 等宽小字：命令/路径/ID/计数（10/14） */
    const val mono = 10
}

/**
 * 字形尺寸阶（图标字符用；不是排版字号）。
 * 与 [BiuZFont] 分开是刻意的：把 26sp 的空态图标并进「22 标题档」会缩小图标，
 * 那不是「统一」，是把设计改坏。
 */
object BiuZGlyph {
    const val inline = 10
    const val sm = 16
    const val md = 20
    const val lg = 26
}

val BiuZTypography = Typography(
    // Display 22/28 w800（Tab 根页大标题）
    displayLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 28.sp),
    // Title 17/24 w700（导航栏标题、卡片标题、Sheet 标题）
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 22.sp),
    // Body 15/22 与 14/22（正文、消息、列表行）
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    // Caption 12/18（摘要、标签）
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    // Footnote 11/16 w500（时间戳、元信息；对比度走 --text-3 以上，禁叠 opacity）
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)

package cn.biuz.android.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题装配（P-003 浅色档）：
 * - 深/浅两套 [BiuZPalette]（web 实测值，见 Color.kt）→ 组件层 `BiuZ*` 引用经 getter
 *   读当前档，切换外观即全局换肤（650 处引用点零改动）；
 * - Material 3 colorScheme 同步映射（Compose 内置组件用）；
 * - [darkTheme] 由外观偏好解析（跟随系统 / 固定浅 / 固定深），**缺省浅色**
 *   （2026-11-24 用户裁决：朱批默认浅色主题；MainActivity 显式传入偏好解析结果）。
 */

private fun schemeFor(p: BiuZPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDim,
        onPrimaryContainer = p.accent,
        inversePrimary = p.lightAccent,
        secondary = p.text2,
        onSecondary = p.bg,
        secondaryContainer = p.border,
        onSecondaryContainer = p.text,
        // tertiary 曾映射 violet(#7C92FF/#2A2140)——品牌只有朱磦/墨/纸/钤灰，
        // 紫色是 BiuZ 时代残留（2026-11-24 审查报告 P0-②）。改映射到朱磦系：
        // 与主印同色，任何 Material 组件取 tertiary 都不会漏紫。
        tertiary = p.sealPress,
        onTertiary = p.onSeal,
        tertiaryContainer = p.sealDim,
        onTertiaryContainer = p.seal,
        background = p.bg,
        onBackground = p.text,
        surface = p.bgCard,
        onSurface = p.text,
        surfaceVariant = p.bgInput,
        onSurfaceVariant = p.text2,
        surfaceDim = p.bg,
        surfaceBright = p.bgElevated,
        // 「背景 → 卡片 → 浮层」三级递亮分层
        surfaceContainerLowest = p.bgCode,
        surfaceContainerLow = p.bgCard,
        surfaceContainer = p.bgCard,
        surfaceContainerHigh = p.bgElevated,
        surfaceContainerHighest = p.bgInput,
        outline = p.border,
        outlineVariant = p.borderStrong,
        error = p.red,
        onError = Color(0xFF2B0808),
        errorContainer = p.redDim,
        onErrorContainer = Color(0xFFFF8A8A),
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
        scrim = Color(0xFF000000),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDim,
        onPrimaryContainer = p.accent,
        inversePrimary = p.lightAccent,
        secondary = p.text2,
        onSecondary = p.bg,
        secondaryContainer = p.border,
        onSecondaryContainer = p.text,
        // 见深档同注：tertiary 由 violet 改映射朱磦系（#EDE4FF 浅紫容器一并去掉）
        tertiary = p.seal,
        onTertiary = p.onSeal,
        tertiaryContainer = p.sealDim,
        onTertiaryContainer = p.seal,
        background = p.bg,
        onBackground = p.text,
        surface = p.bgCard,
        onSurface = p.text,
        surfaceVariant = p.bgInput,
        onSurfaceVariant = p.text2,
        surfaceDim = p.bg,
        surfaceBright = p.bgCard,
        surfaceContainerLowest = p.bgCard,
        surfaceContainerLow = p.bgCard,
        surfaceContainer = p.bgCard,
        surfaceContainerHigh = p.bgCode,
        surfaceContainerHighest = p.bgInput,
        outline = p.border,
        outlineVariant = p.borderStrong,
        error = p.red,
        onError = Color(0xFFFFFFFF),
        errorContainer = p.redDim,
        onErrorContainer = Color(0xFF7F1D1D),
        inverseSurface = p.text,
        inverseOnSurface = p.bgCard,
        scrim = Color(0xFF000000),
    )
}

@Composable
fun BiuZTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 组合前写入调色板快照：子组件本轮组合即读到新档（切换外观 → 重组即换肤）
    BiuZColors.current = if (darkTheme) BiuZDarkPalette else BiuZLightPalette
    MaterialTheme(
        colorScheme = schemeFor(BiuZColors.current, darkTheme),
        typography = BiuZTypography,
        content = content,
    )
}

package cn.biuz.android.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 深色为主默认（spec §1 原则 6）；Light 同构映射（§2.2）随外观设置里程碑补齐，
// 当前主题恒深色，不随系统切换。
private val BiuZDarkColors = darkColorScheme(
    primary = BiuZAccent,
    onPrimary = BiuZOnAccent,
    primaryContainer = BiuZAccentDim,
    onPrimaryContainer = BiuZAccent,
    inversePrimary = BiuZLightAccent,
    secondary = BiuZText2,
    onSecondary = BiuZBg,
    secondaryContainer = BiuZBorder,
    onSecondaryContainer = BiuZText,
    tertiary = BiuZViolet,
    onTertiary = Color(0xFF160F24),
    tertiaryContainer = Color(0xFF2A2140),
    onTertiaryContainer = BiuZViolet,
    background = BiuZBg,
    onBackground = BiuZText,
    surface = BiuZBgCard,
    onSurface = BiuZText,
    surfaceVariant = BiuZBgInput,
    onSurfaceVariant = BiuZText2,
    surfaceDim = BiuZBg,
    surfaceBright = BiuZBgElevated,
    // 「背景 → 卡片 → 浮层」三级递亮分层（spec §2.1）
    surfaceContainerLowest = BiuZBgCode,
    surfaceContainerLow = BiuZBgCard,
    surfaceContainer = BiuZBgCard,
    surfaceContainerHigh = BiuZBgElevated,
    surfaceContainerHighest = BiuZBgInput,
    outline = BiuZBorder,
    outlineVariant = BiuZBorderStrong,
    error = BiuZRed,
    onError = Color(0xFF2B0808),
    errorContainer = BiuZRedDim,
    onErrorContainer = Color(0xFFFF8A8A),
    inverseSurface = BiuZText,
    inverseOnSurface = BiuZBg,
    scrim = Color(0xFF000000),
)

@Composable
fun BiuZTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BiuZDarkColors,
        typography = BiuZTypography,
        content = content,
    )
}

package cn.biuz.android.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 字号阶梯：design/design-spec.md §2.3（8px 栅格派生）直映。
// 字体栈用系统默认（spec --font-ui 为系统栈零外部依赖；mono 场景后续代码块屏再落 FontFamily.Monospace）。
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

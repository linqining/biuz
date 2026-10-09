package cn.biuz.android.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 间距：design/design-spec.md §2.5（8px 增量栅格）。
object BiuZSpacing {
    val xs = 4.dp // sp-1 微间距
    val sm = 8.dp // sp-2 组件内间距
    val md = 12.dp // sp-3 卡片内边距
    val lg = 16.dp // sp-4 页面左右安全边距
    val xl = 24.dp // sp-6 分区间隔
    val xxl = 32.dp // sp-8 区块留白
}

// 圆角：design/design-spec.md §2.4。
object BiuZRadius {
    val small = RoundedCornerShape(8.dp) // --r-s 小 chips、行内标签
    val medium = RoundedCornerShape(12.dp) // --r-m 卡片、按钮、输入框
    val large = RoundedCornerShape(16.dp) // --r-l 大输入区、设置分组容器
}

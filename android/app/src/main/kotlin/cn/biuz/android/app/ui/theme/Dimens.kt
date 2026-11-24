package cn.biuz.android.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 间距：8px 增量栅格。
object BiuZSpacing {
    /** 2dp · 描边内缩/字形微调（原散落 17 处裸 2.dp） */
    val xxs = 2.dp
    /** 4dp · 行内最小间距（原 3/5.dp 已并入） */
    val xs = 4.dp
    /** 6dp · 紧凑行/徽标（原散落 24 处裸 6.dp） */
    val tiny = 6.dp
    /** 8dp · 组件内间距 */
    val sm = 8.dp
    /** 12dp · 卡片内边距（原 10/11.dp 共 40 处已并入） */
    val md = 12.dp
    /** 16dp · 页面左右安全边距（原 14/18.dp 已并入） */
    val lg = 16.dp
    /** 24dp · 分区间隔 */
    val xl = 24.dp
    /** 32dp · 区块留白 */
    val xxl = 32.dp
    /**
     * 48dp / 64dp · **布局偏移**（空态与占位块的下压距离），刻意不并入节奏间距：
     * 这类值由版式构图决定（首屏平衡），按 2 的幂收敛会把空态直接顶到屏幕中线。
     */
    val offsetLg = 48.dp
    val offsetXl = 64.dp
}

// 圆角：小 chips 8 / 卡片·按钮·输入框 12 / 大容器 16。
object BiuZRadius {
    val small = RoundedCornerShape(8.dp) // --r-s 小 chips、行内标签
    val medium = RoundedCornerShape(12.dp) // --r-m 卡片、按钮、输入框
    val large = RoundedCornerShape(16.dp) // --r-l 大输入区、设置分组容器
}

/**
 * 语义不透明度阶（**修饰色只允许这 5 档**）。
 *
 * 2026-11-24 UIUX 审查：全仓曾 35 处 `copy(alpha = …)` 共 14 种值，同类元素浓度不齐
 * （面板描边 0.35、失败底 0.5、极淡 tint 0.10/0.14/0.18 混用）。本轮按视觉语义合并：
 *
 * | 档 | 值 | 用途 |
 * |---|---|---|
 * | [faint] | 0.14 | 极淡底/水印（原 0.10/0.14/0.18） |
 * | [subtle] | 0.35 | 描边、分隔（原 0.30/0.35） |
 * | [half] | 0.50 | 半透明底/描边（原 0.40/0.45/0.50/0.55/0.60） |
 * | [dense] | 0.70 | 压暗遮罩（原 0.70） |
 * | [nearSolid] | 0.85 | 近实底覆盖（原 0.80/0.85/0.90） |
 *
 * 合并幅度均 ≤0.10，在已 dim 的语义色上肉眼不可辨；新增档位须先证明现有 5 档不够。
 */
object BiuZAlpha {
    const val faint = 0.14f
    const val subtle = 0.35f
    const val half = 0.50f
    const val dense = 0.70f
    const val nearSolid = 0.85f
}

package cn.biuz.android.app.ui.theme

import androidx.compose.ui.graphics.Color

// 深色色板：design/design-spec.md §2.1 设计令牌直映（近黑 #0a0b0d + 强调绿 #32f08c）。
// 「叠底实算」= rgba 弱底令牌按背景逐通道实算的不透明近似值。
val BiuZBg = Color(0xFF0A0B0D) // --bg 页面背景（L≈4.5%）
val BiuZBgCard = Color(0xFF121316) // --bg-card 卡片（L≈6.5%）
val BiuZBgElevated = Color(0xFF17181C) // --bg-elevated 浮层/Sheet（L≈8%）
val BiuZBgInput = Color(0xFF1A1C21) // --bg-input 输入框/分段底
val BiuZBgCode = Color(0xFF0D0E10) // --bg-code 代码块底
val BiuZBgTerm = Color(0xFF050607) // --bg-term 终端底
val BiuZBorder = Color(0xFF26282E) // --border 常规描边
val BiuZBorderStrong = Color(0xFF34373F) // --border-strong 输入框/分段描边
val BiuZText = Color(0xFFF5F9FE) // --text 主文字（15.9:1）
val BiuZText2 = Color(0xFFA6AAB5) // --text-2 次级文字（8.1:1）
val BiuZText3 = Color(0xFF8A8F9B) // --text-3 说明/占位/时间戳（5.79:1，禁再叠 opacity）
val BiuZAccent = Color(0xFF32F08C) // --accent 强调绿（深字 12.7:1）
val BiuZAccentPress = Color(0xFF28C974) // --accent-press 按压态
val BiuZAccentDim = Color(0xFF162E24) // --accent-dim 叠底实算
val BiuZOnAccent = Color(0xFF04120A) // --accent 上的深字
val BiuZBlue = Color(0xFF4DA3FF) // --blue 状态「运行中」
val BiuZBlueDim = Color(0xFF182031) // --blue-dim 叠底实算
val BiuZOrange = Color(0xFFFFB224) // --orange 状态「待操作/待批准」
val BiuZOnOrange = Color(0xFF1A0F00) // --on-orange
val BiuZRed = Color(0xFFFF5D5D) // --red 危险操作/失败
val BiuZRedDim = Color(0xFF271517) // --red-dim 叠底实算
val BiuZViolet = Color(0xFFC792FF) // --violet 能力/生态标签与工作流语义（非任务状态）
val BiuZAdd = Color(0xFF3DDC84) // --add diff 新增
val BiuZDel = Color(0xFFFF7A7A) // --del diff 删除
val BiuZLightAccent = Color(0xFF17B26A) // Light 档 --accent（spec §2.2，作 inversePrimary）

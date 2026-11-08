package cn.biuz.android.app.ui.theme

import androidx.compose.ui.graphics.Color

// qoder 参考深色色板（2026-11-08 提炼）：近黑 #080807 + monochrome 近白主色 #FAFAFA，
// 语义状态色独立——running #2FBF71 / completed #3B82F6 / error #EF4444 / attention #F5A623。
// 「叠底实算」= rgba 弱底令牌按背景逐通道实算的不透明近似值。
val BiuZBg = Color(0xFF080807) // --bg 页面背景（qoder 近黑）
val BiuZBgCard = Color(0xFF1C1C1E) // --bg-card 卡片/表面（qoder surface）
val BiuZBgElevated = Color(0xFF232326) // --bg-elevated 浮层/Sheet
val BiuZBgInput = Color(0xFF2A2A2E) // --bg-input 输入框/分段底
val BiuZBgCode = Color(0xFF141416) // --bg-code 代码块底
val BiuZBgTerm = Color(0xFF050505) // --bg-term 终端底
val BiuZBorder = Color(0xFF393939) // --border 常规描边（白 20% 叠底实算）
val BiuZBorderStrong = Color(0xFF525252) // --border-strong 输入框/分段描边（白 30%）
val BiuZText = Color(0xFFFFFFFF) // --text 主文字
val BiuZText2 = Color(0xFFA1A1AA) // --text-2 次级文字
val BiuZText3 = Color(0xFF71717A) // --text-3 说明/占位/时间戳
val BiuZAccent = Color(0xFFFAFAFA) // --accent monochrome 主色（qoder 近白主按钮）
val BiuZAccentPress = Color(0xFFE4E4E7) // --accent-press 按压态
val BiuZAccentDim = Color(0xFF212121) // --accent-dim 叠底实算（白 10%）
val BiuZOnAccent = Color(0xFF18181B) // --accent 上的深字
val BiuZGreen = Color(0xFF2FBF71) // --green 状态「运行中」（qoder running）
val BiuZGreenDim = Color(0xFF13291D) // --green-dim 叠底实算
val BiuZBlue = Color(0xFF60A5FA) // --blue 状态「已完成」（qoder completed #3B82F6 深档）
val BiuZBlueDim = Color(0xFF1E293B) // --blue-dim 叠底实算
val BiuZOrange = Color(0xFFF5A623) // --orange 状态「待操作/待批准」（qoder attention）
val BiuZOnOrange = Color(0xFF1A0F00) // --on-orange
val BiuZOrangeDim = Color(0xFF291E0B) // --orange-dim 叠底实算（f5a623 14% over 080807，对位 iOS T.orangeDim）
val BiuZRed = Color(0xFFF87171) // --red 危险操作/失败（qoder error #EF4444 深档）
val BiuZRedDim = Color(0xFF2A1818) // --red-dim 叠底实算
val BiuZViolet = Color(0xFFC792FF) // --violet 能力/生态标签与工作流语义（非任务状态）
val BiuZAdd = Color(0xFF3DDC84) // --add diff 新增
val BiuZDel = Color(0xFFFF7A7A) // --del diff 删除
val BiuZLightAccent = Color(0xFF18181B) // Light 档 --accent（monochrome 近黑，作 inversePrimary）
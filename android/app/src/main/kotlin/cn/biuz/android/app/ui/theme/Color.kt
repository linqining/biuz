package cn.biuz.android.app.ui.theme

import androidx.compose.ui.graphics.Color

// 色板 —— **完全对齐 ZCode web**（用户裁决 2026-11-09「整个 app 与 ZCode web 设计样式保持一致，
// 先不用之前设计的 UI，完全复刻」；原 qoder 色板作废）。
//
// 取值来源：线上手机壳实测（`zcode.z.ai/remote/v4` 390×844 计算样式）+ web `packages/ui/src/styles.css`
// 的 :root / .dark 块：
//   dark : bg #161616 · card/popover #171717 · surface #262626 · 正文 #d4d4d4(oklch .87)
//          次级 = 正文 60% alpha · 最弱 = 正文 30% alpha · brand #e5e5e5(oklch .922) · destructive #ff6467
//   分隔线 = 白 10% alpha · 输入/强边 = 白 15% alpha
//   任务轨道实测：running #46bf72 / attention #ff8a30（web task-item 站点灯）
// 变量名保持不变（250+ 引用点零改动），只换值。
val BiuZBg = Color(0xFF161616) // --color-background
val BiuZBgCard = Color(0xFF171717) // --color-card / --color-popover
val BiuZBgElevated = Color(0xFF262626) // --color-surface（浮层/Sheet）
val BiuZBgInput = Color(0x26FFFFFF) // --color-input = 白 15% alpha
val BiuZBgCode = Color(0xFF171717) // 代码块底（同 card）
val BiuZBgTerm = Color(0xFF0F0F0F) // 终端底（更暗一档，web --color-terminal-bg）
val BiuZBorder = Color(0x1AFFFFFF) // --color-border = 白 10% alpha
val BiuZBorderStrong = Color(0x26FFFFFF) // 输入/分段描边 = 白 15% alpha
val BiuZText = Color(0xFFD4D4D4) // --color-foreground（oklch .87）
val BiuZText2 = Color(0x99D4D4D4) // --color-foreground-subtle（60% alpha）
val BiuZText3 = Color(0x4DD4D4D4) // --color-foreground-subtlest（30% alpha）
val BiuZAccent = Color(0xFFE5E5E5) // --color-brand（oklch .922，monochrome 近白）
val BiuZAccentPress = Color(0xFFD4D4D4) // 按压态（brand 降一档）
val BiuZAccentDim = Color(0x1AFFFFFF) // --color-accent（白 10% alpha）
val BiuZOnAccent = Color(0xFF171717) // brand 上的深字
val BiuZGreen = Color(0xFF46BF72) // 任务轨道「运行中」（web 实测）
val BiuZGreenDim = Color(0x2446BF72) // 叠底实算（14% alpha）
val BiuZBlue = Color(0xFF60A5FA) // 「已完成」语义（web --color-progress 深档）
val BiuZBlueDim = Color(0x2460A5FA)
val BiuZOrange = Color(0xFFFF8A30) // 「待操作/待批准」（web 实测 attention）
val BiuZOnOrange = Color(0xFF1A0F00)
val BiuZOrangeDim = Color(0x24FF8A30)
val BiuZRed = Color(0xFFFF6467) // --color-destructive（web 实测 oklch .704 .191 22.216）
val BiuZRedDim = Color(0x24FF6467)
val BiuZViolet = Color(0xFFC792FF) // 能力/生态标签（非任务状态）
val BiuZAdd = Color(0xFF22C55E) // diff 新增 = web --color-diff-added（green-500）
val BiuZDel = Color(0xFFFF6467) // diff 删除 = web --color-diff-removed
val BiuZLightAccent = Color(0xFF171717) // Light 档 accent（作 inversePrimary）

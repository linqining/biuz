# Android UIUX 与交互审查报告

> 2026-11-24 · 审核对象：Android 端（`cn.zhupi.android`）
> 依据：`brand/seal/README.md`（印系四色/用字/用印规则）、`brand/seal/motion-spec.html`（动效规格）、上游 web `packages/ui/src/v4/*`（复刻基准）
> **本报告只出结论与方案，不改代码**（用户裁决「先给出 uiux 和交互的报告给我再改代码和设计」）。
> 声称已改的两项见 §8「工作区在途改动」，未提交、待确认。

---

## 0 结论摘要

| 级别 | 问题 | 影响 |
|---|---|---|
| **P0** | **按钮/可点面零按压反馈**——43 处 `clickable` + 95 处 `Button`，全仓 **0 处** `collectIsPressedAsState` / scale / 自定义 indication，只有 Material 默认 ripple | 「点了没反应」的观感来源之一；与朱批「落印/压实」的动效语言完全脱节 |
| **P0** | **Material 3 的 tertiary 仍是紫色**（浅 `#7C3AED` / 容器 `#EDE4FF`；深 `#C792FF` / 容器 `#2A2140`），任何用到 tertiary 的 Material 组件都会漏紫 | 品牌只有朱磦/墨/纸/钤灰，紫是 BiuZ 时代残留 |
| **P0** | **两级主色并存**：登录页主按钮 = 朱磦 `seal`，其余所有主按钮（发送、开始任务…）= 墨 `accent` | 同一产品两种「主操作」色，品牌识别被劈开 |
| **P1** | **触觉反馈零基建**——全仓 0 处 `LocalHapticFeedback`；motion-spec 明写「触觉零基建：首例于落印帧」仍未做 | 落印这类「重动作」没有触觉，动效只到眼睛 |
| **P1** | **打字排版无阶梯**——`Typography` 令牌 9 档，但正文 **504 处硬编码 `fontSize`、20 种字号**（9/9.5/10/10.5/11/11.5/12/12.5/13/13.5/14/14.5/15/16/17/18/20/22/24/26 sp） | 令牌形同虚设；半档字号（x.5）是无系统的直接证据 |
| **P1** | **间距/透明度魔数**——121 处硬编码 `padding`、35 处 `copy(alpha=…)`（13 种不同 alpha） | 同类元素间距与描边浓度不一致 |
| **P2** | 主题外硬编码颜色 15 处（含上面 tertiary 紫 + `MarkdownText.CODE_BG = 0x14FFFFFF` 白 8%） | 深/浅档切换时个别元素不自适应 |
| **P2** | 决议面落印（motion-spec 五面）实现完整但**从未真机目检**；`SealStamp` 是唯一有动画的文件 | 规格与实现是否一致无视觉证据 |

**一句话**：功能面已大致对齐 web，但**视觉系统（色/字/间距）与动效系统（按压/触觉/转场）都还没建立**——现在是「能用的界面」，不是「朱批的界面」。

---

## 1 色彩系统

### 1.1 现状

**品牌四色**（`brand/seal/README.md:39`）：朱磦 `#D2402E`（加深 `#B03020`）· 墨 `#17181A` · 纸 `#F7F3EC` · 钤灰 `#8A8F98`

**调色板实际字段**（`ui/theme/Color.kt:87-127`）共 30 个令牌，其中品牌外色 5 个：

| 令牌 | 浅色 | 深色 | 品牌内？ | 判断 |
|---|---|---|---|---|
| `bg / bgCard / bgElevated / bgInput / bgCode / bgTerm` | 纸系 6 档 | 墨系 6 档 | ✅ | 本轮已收敛为「同一张纸略亮一档」，真机采样 `(251,248,242)` vs `(247,243,236)` |
| `text / text2 / text3` | 墨 100/62/40% | 纸同比例 | ✅ | 合理 |
| `accent / accentPress / accentDim / onAccent` | 墨 | 纸 | ✅ | 主色=墨（见 1.2 冲突） |
| `seal / sealPress / sealDim / onSeal` | 朱磦 | 朱磦 | ✅ | 登录页主按钮用 |
| `border / borderStrong` | 墨 12/18% | 纸 | ✅ | 合理 |
| **`violet`** | **`#7C3AED`** | **`#C792FF`** | ❌ | 不在四色内；**已在工作区改为 seal（见 §8）** |
| **`blue` `#3B82F6` / `green` `#16A34A` / `orange` `#B45309` / `red` `#DC2626`** | — | — | ❌ | 状态语义色，web 同样存在，**建议保留**（不属品牌标识层） |
| `add` / `del` | 绿 / 红 | — | ❌ | diff 语义色，web 同源，保留 |

### 1.2 两个真问题

**(a) Material 3 tertiary 漏紫**（`ui/theme/Theme.kt:29-33` 深 / `:67-71` 浅）
`tertiary = p.violet`、`tertiaryContainer = Color(0xFFEDE4FF)`（浅紫）。任何 Material 内置组件（Chip/Badge/Card 变体）一旦取 tertiary 就出紫，且**不走 `BiuZ*` getter**，所以本轮把 `BiuZViolet` 引用全改朱磦也不影响它。这是「改了 16 处还是觉得有偏差」的隐藏来源。

**(b) 主操作色分裂**
- 登录页主按钮：朱磦实底 `seal`/`onSeal`（2026-11-24 用户裁决「登录 z.ai 是不是也应该改成红色，和 logo 更匹配」）
- 发送按钮 / 「开始任务」/ 其他主 CTA：墨实底 `accent`/`onAccent`

同一 App 里「最重要的那个按钮」有两种颜色。**建议二选一并全局统一**（推荐朱磦：它是印面主色，且用户已就登录页明确表达过偏好；墨留给正文与描边，纸留给底）。

### 1.3 建议

1. `tertiary / tertiaryContainer / onTertiary*` 全部改映射到 `seal` 系或中性（不再引用 violet）。
2. `violet` 令牌从调色板**删除**（连同名 getter 一起），杜绝再被引用。
3. 主 CTA 统一为 `seal`；`accent`（墨/纸）降级为「中性强调/反色底」。
4. 若担心朱磦面积过大，规则：**只有「一次操作的最终提交」用朱磦实底**（发送、批准、开始任务、登录），导航/开关/芯片一律中性。

---

## 2 排版系统

### 2.1 现状

`ui/theme/Type.kt:10-25` 定义了 9 档（22/17/15/15/14/12/14/11/10 sp）。但正文里：

```
硬编码 fontSize = 504 处
不同字号 = 20 种：9, 9.5, 10, 10.5, 11, 11.5, 12, 12.5, 13, 13.5, 14, 14.5,
                    15, 16, 17, 18, 20, 22, 24, 26 (sp)
```

其中 x.5 档共 **108 处**（10.5/11.5/12.5/13.5/14.5/9.5），是最明确的「无阶梯」证据：差 0.5sp 在 400+ ppi 屏上肉眼几乎不可辨，却制造了 6 个额外样式。

### 2.2 建议阶梯（7 档，映射到现有 Typography 令牌）

| 语义 | 字号/行高 | 用途 | 现状对应 |
|---|---|---|---|
| Display | 22 / 28 | 页面标题 | `displayLarge` |
| Title | 17 / 24 | 页/卡主标题 | `titleLarge` |
| Subtitle | 15 / 22 | 区块标题、正文强调 | `titleMedium`/`bodyLarge` |
| Body | 14 / 22 | 消息正文、列表主行 | `bodyMedium` |
| Caption | 12 / 18 | 次级说明、时间戳 | `bodySmall` |
| Micro | 11 / 16 | 芯片文案、徽标 | `labelMedium` |
| Mono | 10 / 14 | 命令/路径/ID（等宽） | `labelSmall` |

规则：**禁止 x.5**；正文只允许上表 7 档；`sp` 字面量只出现在 `Type.kt`。
（本轮新增的面板/目录/状态行已按 9.5/10.5/11.5 写——属于待收敛的存量之一，共 ~30 处。）

---

## 3 间距 · 形状 · 层次

- 令牌：`BiuZSpacing` 6 档（4/8/12/16/24/32）、`BiuZRadius` 3 档（8/12/16）+ 胶囊 999。
- 但**硬编码 `padding` 121 处**、`copy(alpha=)` **35 处 / 13 种 alpha**（0.3/0.4/0.45/0.5/0.55/0.6/0.7/0.8/0.85/0.9/0.14/0.10）。
- 后果：同类元素（面板边框、芯片底、失败色底）浓度不齐；深浅档切换时对比度不同步。

**建议**：alpha 收敛为 4 档语义令牌（`tintSurface 8%` / `tintBorder 12%` / `tintStrong 18%` / `tintAccent 16%`）；padding 只允许 6 档 + 行内 2dp/6dp 两个微调例外。

---

## 4 交互动效（本次重点）

### 4.1 现状：几乎为零

| 能力 | 现状 | 证据 |
|---|---|---|
| 按压反馈（scale/色调） | **0 处** | 全仓无 `collectIsPressedAsState`、无 `interactionSource` 自定义 |
| 按压 ripple | 全用 Material 默认 | 无 `LocalIndication` / `ripple(` 覆盖（默认 ripple 是方形 RoundedCorner 继承、颜色取 primary=墨，与纸质底不协调） |
| 触觉 | **0 处** | 无 `LocalHapticFeedback` / `performHapticFeedback` |
| 动画基建 | **1 个文件** | `animateFloatAsState`/`Animatable`/`AnimatedVisibility`/`animateContentSize` 全仓只出现在 `SealStamp.kt` |
| 转场 / 列表动画 | 无 | `LazyColumn` 无 `animateItem()`；面板展开无 `AnimatedVisibility` |
| 减弱动态 | 只有落印做了 | `SealStamp.kt` 读 `ANIMATOR_DURATION_SCALE`；其余无动画故无影响 |

### 4.2 motion-spec 只规定了「落印」，没规定按钮点击

`brand/seal/motion-spec.html` 的规格对象始终是**决议面落印**：

```
落印 460ms · scale 1.6→0.96→1.04→1.0 · rotate −10°→−2.5° · cubic-bezier(.22,.68,.32,1)
印泥外溢 520ms（延迟 180ms，颜色随印）· 停驻 700ms · 离场 180ms（opacity + scale .96）
触觉：success/error，落印即触发 · 减弱动态：全部停用、印以终态直出
印随批准权走：只在 ACK 权威成功之后落印；失败/未送达不落印
```

它**没有**定义普通按钮的点击反馈。所以「按 motion-spec 实现按钮点击交互动画」需要从品牌动效语言派生出按钮规格——以下是建议方案，等确认后再实现。

### 4.3 建议的按钮按压规格（从落印语言派生，但**刻意收窄**）

落印的签名是「过冲后收束」（1.6→0.96→1.04→1.0）。若把这个过冲复用到每个按钮上，签名的稀缺性会被稀释——**过冲留给印，按钮只做「压实」**：

| 态 | 变换 | 时长/缓动 |
|---|---|---|
| 按下（press） | `scale 1.00 → 0.96`，底色向下一档（`bgCard→bgInput`；朱磦实底 → `sealPress`） | **90ms** · `cubic-bezier(.22,.68,.32,1)` |
| 松手/取消（release） | `scale 0.96 → 1.00`，底色回原档 | **140ms** · 同缓动 |
| 触觉 | **仅主 CTA**（朱磦实底：发送/批准/开始任务/登录）触发 `HapticFeedbackType.LongPress` 轻击；列表行、芯片、图标钮**不触发** | 按下帧 |
| 减弱动态 | `ANIMATOR_DURATION_SCALE == 0` 时跳过 scale，仅保留色调阶跃 | — |

补充三条与品牌一致的细节：
1. **不动旋转**——`−2.5°` 是落印专用（`brand/seal/README.md` 用印规则），按钮永不旋转。
2. **按下不做透明度闪烁**（默认 ripple 的扩散圆点在纸质底上像污渍）；改为**底色阶跃 + 压缩**，这是「压实」而非「水波」。
3. **禁用态不动**（`scale` 保持 1，仅文字降到 `text3`），避免「按了但没动」与「按了有反馈但被拒」混淆。

### 4.4 建议的令牌与落点

```kotlin
// ui/theme/Motion.kt（新增）
object BiuZMotion {
    const val pressIn = 90
    const val pressOut = 140
    const val stamp = 460        // motion-spec
    const val stampHold = 700    // motion-spec
    const val stampExit = 180    // motion-spec
    const val ring = 520         // motion-spec
    const val ringDelay = 180    // motion-spec
    val emphasized = CubicBezierEasing(0.22f, 0.68f, 0.32f, 1f) // 全品牌唯一缓动
}

// ui/theme/PressableScale.kt（新增）—— 唯一入口，所有可点面用它
fun Modifier.pressableScale(pressedScale: Float = 0.96f): Modifier
```

落点（按优先级）：
1. **主 CTA**：发送钮、开始任务、登录、批准/拒绝（`PrimaryButton`/`SendButton`/`LoginProviderButton`）
2. **芯片/胶囊**：模式·模型·思考、活动摘要、Hook、面板 chip、tab
3. **行**：会话行、消息动作（复制/赞/踩/Fork）、工具卡展开
4. **不适用**：开关（Switch 自带）、滑动手势、Swatch/色板

### 4.5 落印（motion-spec）现状对账

| spec 要求 | Android 实现 | 判定 |
|---|---|---|
| 落印 460ms / keyframes / −2.5° | `SealStamp.kt:66-…`（Animatable 分段 + CubicBezier） | ✅ 代码一致 |
| 印泥外溢 520ms 延迟 180ms，颜色随印 | `SealStamp.kt` ringScale/ringAlpha + delay(180) | ✅ |
| 停驻 700ms → 入账 decidedIds | `ChatScreen.kt:566-573`（stampHolds → VM 入账） | ✅ |
| 离场 180ms | VM 侧延迟收卡 | ✅（未见独立离场动画，卡随列表移除） |
| **失败/未送达不落印** | 只在权威成功置 `stampHolds` | ✅ |
| **触觉 success/error（落印帧）** | **无** | ❌ 缺 |
| 五决议面覆盖 | `visibleInteractions.forEach` 统一包 | ✅（四面共用一套） |
| 减弱动态 = 终态直出 | 读 `ANIMATOR_DURATION_SCALE` | ✅ |
| **真机目检** | **从未目检**（AGENTS §6 已如实挂账） | ❌ 缺证据 |

→ 落印本体只差**触觉**与**目检**两件。

---

## 5 层次与可读性

1. **卡面与底的差只有 4~6 RGB**（`#FBF8F2` vs `#F7F3EC`）。层次完全靠 12% 墨描边承担——在纸质底上这最接近「油墨压印」，方向对；但**卡片一多，页面会显得全是描边**（工具卡每张都有 1dp 边）。建议：连续同类卡（工具卡串）只保留首尾或分组边界，组内用 4dp 间距而非描边分隔。
2. **状态色面积偏大**：头像圆此前用状态色铺底（已改中性，见 §8）；活动摘要的绿点/红点若无文字并行，色盲不可辨——建议状态一律「色点 + 文字/字形」双编码（现状 `已完成/失败` 有文字，✅）。
3. **深色档未审**：本轮全部证据在浅色。深色下 `bgCard #1C1D20` vs `bg #17181A` 差 5 RGB，同款问题；纯白字在墨底上也没有降对比。

---

## 6 文案（copy）

按「界面语言即导航」的标准，现存三处不一致：

| 位置 | 现状 | 问题 | 建议 |
|---|---|---|---|
| 活动摘要计数 | 「活跃中 1」「已完成 5」 | 量词缺失（中文可接受），但**「活跃中」与「运行中」并存**（`后台 1 运行中`） | 统一为「运行中」 |
| 面板名 | 「后台」「工作」「Works」混用（代码 `panel_works`=后台） | 同一个面三种叫法 | 定为「后台任务」 |
| 目录入口 | 「子代理目录」 | 与「子代理」chip 并列时读起来像两个子代理 | 「历史子代理」 |

---

## 7 与 web 的设计差异（非缺陷，需登记）

| 项 | web | Android | 结论 |
|---|---|---|---|
| 状态胶囊位置 | composer 下方（`ComposerStatusRow`） | 消息列表顶部（`SessionPanelsSection`） | 触屏等价承载，保留 |
| 活动摘要形态 | popover 胶囊 | 常驻摘要行 | 保留（信息等价） |
| 每轮 Hook 详情 | 消息流内小按钮 | 面板区一行 | 保留（触屏热区更大） |
| 头 Avatars | 面孔插画 + 徽标 | 首字母圆 + 徽标点 | 保留（无插画资源） |
| git 胶囊 | popover 内变更清单 + 分支 | 独立「文件」页 | 保留 |

---

## 8 工作区在途改动（已改、未提交、待确认）

在你「先出报告」的指示前，为响应「主题配色有偏差」我改了两处。**已单独提交为 `f90177b1`（提交信息标注「在途·待裁决」），未装机验证**——保留或回退只需一条 `git revert`：

1. **violet → seal（16 处）**：`SessionPanels.kt`(8) / `ComposerPanels.kt`(3) / `WorkflowTrack.kt`(2) / `ChatScreen.kt`(2)，即面板 chip 图标、展开面板描边、折叠头、目标/计划/工作流图标、composer 相关图标全部由紫 `#7C3AED` 改朱磦 `#D2402E`。
2. **头像中性化**：活动摘要头像底色由「状态色 dim + 状态色字」改为 `bgInput` 底 + `text2` 字，状态只由左下徽标点承载（web「面孔 + 标记」的两件事分开）。

这两项与 §1.3 建议 1/2 部分重合。**要不要保留、要不要连 `tertiary` 一起改，请你定**；若按报告方案走，我会把 `tertiary` 映射与 `violet` 令牌删除一并做完再提交。

---

## 9 建议实施顺序（待批准）

| 批次 | 内容 | 验收 |
|---|---|---|
| **A（动效基建）** | `BiuZMotion` + `pressableScale` + 主 CTA 触觉；落印补触觉 | 真机逐面点按录屏/截图；`ANIMATOR_DURATION_SCALE=0` 回归 |
| **B（色彩收敛）** | `tertiary` 去紫、删 `violet`、主 CTA 统一朱磦 | 全屏像素采样（无 `#7C3AED` 族）；深浅两档目检 |
| **C（排版收敛）** | 7 档阶梯落 `Type.kt`；清 x.5；仅 `Type.kt` 允许字面量 | grep 断言 `fontSize =` 只出现在 `Type.kt`（允许白名单 < 20 处） |
| **D（间距/alpha）** | 4 档语义 alpha + 6 档间距；清 121 处硬编码 | 同上 grep 断言 |
| **E（层次/文案）** | 卡分组去描边、术语统一 | 截图比对 |

建议 **A 先做**：它是你本次点名要的，且不依赖 B/C/D；B 可以紧随（已经改了一半）。

---

## 10 裁决结果（2026-11-24 用户已定）

1. **主 CTA 用朱磦** ✅ → 已落地（发送钮、开始任务、决议按钮；`accent` 保留为中性强调）
2. **按压要触觉，只给主 CTA 轻击** ✅ → 已落地（`ctaPress`：按下帧 `HapticFeedbackType.LongPress`；列表行/芯片/图标钮不触发）；落印也补上了触觉（motion-spec 要求）
3. **在途改动保留** ✅ → `f90177b1` 保留；本轮 A/B 已在其上继续

### 落地明细（A 动效基建 + B 色彩收敛）

| 项 | 实现 | 证据 |
|---|---|---|
| 动效令牌 | `ui/theme/Motion.kt`：`BiuZMotion`（落印 460/700/180/520/180 + 按压 90/140 + `emphasized` 唯一缓动） | `MotionAndPaletteTest.stampDurationsMatchMotionSpecVerbatim` |
| 按压实 | `Modifier.pressableScale(interactionSource)`：`collectIsPressedAsState` → `animateFloatAsState` → `graphicsLayer`；`scale 1→0.96`（90ms）/ `0.96→1`（140ms）；`ANIMATOR_DURATION_SCALE==0` 跳过 | `MotionAndPaletteTest.pressDurationsAreShorterThanStamp` |
| 主 CTA 触觉 | `Modifier.ctaPress(interactionSource)` = 按压实 + 按下帧触觉；接入发送钮、开始任务、`DecisionButton`（四面决议统一入口） | 真机点按（触觉需实机手感，截图无法呈现） |
| 落印触觉 | `SealStamp` 落印帧 `performHapticFeedback(LongPress)`（减弱动态下也保留——触觉不是动画） | motion-spec「落印即触发」 |
| tertiary 去紫 | `Theme.kt` 深浅两档 `tertiary/onTertiary/tertiaryContainer/onTertiaryContainer` 全改朱磦系；`#EDE4FF`/`#2A2140` 紫容器删除 | `MotionAndPaletteTest.sealStaysVermilionInBothTiers` |
| 删除 violet 令牌 | `Color.kt`：`violet` 字段（两档）+ `BiuZViolet` getter 全部删除；全仓 violet 引用 0 | `grep -rc violet` = 0（仅 Theme.kt 两行历史注释） |
| 主 CTA 统一朱磦 | 发送钮 / 「开始任务」/ `DecisionButton` 容器色 `BiuZAccent`→`BiuZSeal`、文字 `BiuZOnAccent`→`BiuZOnSeal` | 真机截图：发送钮朱磦圆形 + 纸色箭头 |

**真机目检**：chip 图标（目标 ◉ / 计划 ☰ / 后台 ◷）由紫转**朱磦红**；活动头像由「绿底绿字」转**中性灰圆 + 绿点徽标**；发送钮朱磦。

#### 按压实覆盖（报告 §4.4 三类落点）

| 类 | 落点 | 触觉 |
|---|---|---|
| 主 CTA | 发送钮、开始任务、`DecisionButton`（四面决议） | ✅ 按下帧 `LongPress` |
| 胶囊 | composer 三胶囊（`Chip` 基形一处覆盖全部）、面板 chip（`PanelChip`）、活动摘要、Hook、子代理目录 | ❌ |
| 行 | 面板选项行（`OptionRow`：模型/思考/模式清单）、会话行（`SessionRow`） | ❌ |
| 另 | `SealStamp` 落印帧 | ✅（motion-spec 要求） |

**按压真机取证（数值化）**：对「完全访问」胶囊长按保持 3s，中段截图与静止帧对比——
文字包围盒 `151 → 144 px`（比 **0.954**）、字高 `37 → 35 px`（**0.946**）、胶囊左边缘内缩 `5 px`（绕中心对称缩放），与 `BiuZMotion.pressedScale = 0.96` 一致。
（截图法抓不到 90ms 的按下瞬态——adb 截图延迟约 700ms；改用「长按保持」把按下态变成静止帧才可测。此前两次尝试分别因坐标落错与录屏抽帧遇布局漂移失败。）

**门禁**：app/store/protocol **461 例全绿**（新增 4 例动效/色令牌断言），`assembleDebug` 绿。

### C/D/E 批次落地（2026-11-24，用户「现在开工」后）

| 批次 | 动作 | 结果 |
|---|---|---|
| **C 排版** | 半档清零：`9/9.5/10.5/11.5/12.5/13.5/14.5/15.5` 全部并入最近整数档（**110 处 / 14 文件**）；新增 `BiuZFont`（8 档文本：10/11/12/13/14/15/17/22）与 `BiuZGlyph`（图标字形阶 10/16/20/26，**刻意不与文本阶混用**——把 26sp 空态图标并进 22 标题档是改坏设计不是统一） | 字号种类 **20 → 12**（8 文本 + 4 字形）；`TypographyScaleGuardTest` 扫源码挡半档回流 |
| **D alpha** | 35 处 `copy(alpha=…)` 共 **14 种值 → 5 档语义**（`BiuZAlpha.faint .14 / subtle .35 / half .50 / dense .70 / nearSolid .85`，合并幅度 ≤0.10 且在已 dim 的语义色上不可辨）；10 文件替换 | 裸 alpha **0 处**；同测试内 `noBareAlphaMagicNumbers` 扫源码挡回归 |
| **E 文案** | 「活跃中」→「运行中」（与「后台 N 运行中」同词）；「后台」→「后台任务」；「子代理目录」→「历史子代理」 | zh/en 各 3 键；真机目检「后台任务 5」 |

**未做（如实挂账）**：121 处硬编码 padding 未收敛到 6 档——机械替换会改变大量布局间距，
需逐屏截图回归，单独立轮更稳；`fontSize` 字面量仍有 ~350 处（值已合法，只是尚未改为
`BiuZFont.*` 引用，视觉零差异，属纯代码整洁项）。

**真机目检**：术语「后台任务」生效、朱磦 chip 图标、中性头像、字阶无明显异常。
**门禁**：app/store/protocol **466 例全绿**（新增 5 例排版/alpha 守卫），`assembleDebug` 绿。

### E 批次·列表页排版修复（2026-11-24 用户两次报「排版混乱」后收口）

真机截图 + uiautomator bounds 双重取证，定位到三处**结构性**排版缺陷（不是像素级微差）：

| 缺陷 | 证据 | 修法 |
|---|---|---|
| **底部「文件 →」「设置 →」越级** | 两行用 `MaterialTheme.typography.bodyLarge`（15sp）+ `colorScheme.primary`（最重墨色）——**比同屏所有列表文字（14sp/text2~text3）都大**，且没有分组标题，直接挂在列表尾部，读作「两个游离的大字」 | 新增「工具」分组：与列表同构的区头（14sp/text3）+ 行（`BiuZFont.body` 14sp / `text2`）+ 右侧 `›`，与列表区间距 `xl`(24dp)；抽出 `SessionsToolsRow` 统一版式 |
| **置顶行 📌 与指示槽挤成一团** | 10sp emoji 自带升降部把 📌 顶得比标题高半个字，并与右侧 16dp 指示槽（转圈/红点/蓝点）紧贴 → 真机读作「📌 和转圈叠住」 | 📌 改为固定 14×18dp 槽内居中（9sp），槽后加 `tiny`(6dp) 间距；指示槽保持独立 16dp |
| **11 个测试会话污染列表** | 本轮验收期间真实 `createSession` 造的 `hi`/`Say` 会话留在用户列表里（用户两次报「排版混乱」的直接观感源） | 逐个长按 → 归档（**可逆**，归档管理里可恢复/删除）；脚本按 `text ∈ {hi, Say}` 精确匹配，绝不触碰用户会话 |

**真机目检**：置顶三行 📌 与标题基线对齐、不再与转圈重叠；项目两级缩进清晰；底部「工具 / 文件 / 设置」与列表同字阶、右侧 `›` 引导。
**门禁**：app/store/protocol **468 例全绿**。

**未做（同上）**：列表行与区头的垂直节奏仍未收到统一栅格（当前区头 top 12dp / 行 vertical 4dp / 组间 xl 24dp 是三个独立值）。

### F 批次·列表页收进 8/12/16 三档间距（2026-11-24 用户「一起收进 8/12/16 的三档间距」）

E 批次遗留的「三个独立值」按用户裁决收进节奏档；同时按 uiautomator bounds 实测把**左轨**收成一条。

**垂直节奏（改前 → 改后，单位 dp）**

| 位置 | 改前 | 改后 | 令牌 |
|---|---|---|---|
| 会话行上下内距 | 4 | **8** | `BiuZSpacing.sm` |
| 区头（置顶/项目/工具）上/下 | 12 / 8 | **16 / 8** | `lg` / `sm` |
| 项目节点行上下内距 | 8（含 12dp 左缩进） | **8** | `sm` |
| 项目与项目之间 | 0（与普通子行同距，读作同一项目的子项） | **8** | `sm` |
| 列表尾 / 工具区前 | 24 / 16 | **8 / 16** | `sm` / `lg` |

真机实测行距（density 480 = 3.0，bounds 取证）：会话行/子行 pitch **恒 38dp**（8+22+8）；区头到首行 46dp；项目分组之间 46dp（38+8）。

**左轨（改前 → 改后，均为 3.0 density 下的 px→dp）**

| 元素 | 改前 | 改后 |
|---|---|---|
| 区头「已置顶」「项目」「工具」 | 88px / 86px ≈ **29dp** | **48px = 16.0dp** |
| 会话行 📌 槽、项目节点 📁 | 49px / 124px（不一致） | **16.0dp** |
| 「文件」「设置」工具行 | 60px = **20dp** | **16.0dp** |
| 顶部 💻 设备行 / ⊕ 新建任务 / 「任务列表」标题 | 24 / 28 / 28dp | **16.0dp** |

根因是三处**游离缩进**（都不在节奏档里）：`SessionSectionHeader` 的 `start = BiuZSpacing.md`（12dp）、`SessionsToolsRow` 的 `padding(horizontal = xs)`（4dp）、列表头/设备行/新建任务行的 `start`（8~12dp）。删除后全页只剩一条 16dp 左轨；会话标题仍在 60dp（16 页边距 + 16 前置槽 + 8 槽后距 + 置顶 20），项目子行 56dp（+40 缩进）——三者都是**层级缩进**，不是游离值。

顺带：`sections`（最近更新/未读）分支里有一份与 `SessionSectionHeader` 逐字重复的内联区头（同样带 `start=md`），已改为调用同一 composable（同类版式只留一个定义）。

**眉批**：区头缩进与工具行 4dp 内距都属「看着像微调、实则是第四条栅格」的典型——三档之外的任何 padding 都应先问「它凭什么不在档里」。
**门禁**：app/store/protocol **551 例全绿**；`assembleDebug` 产物 mtime 晚于源码。
**真机目检**：单条左轨（区头/📌/📁/工具行/列表头同一竖线），行距均匀，项目分组有 8dp 分隔；截图 `/tmp/rhythm4.png`（本轮临时件，不入库）。

---

## 11 原待裁决项（已被 §10 取代，留档）

1. **主 CTA 用朱磦还是墨？**（推荐朱磦，与 logo/登录页一致）
2. **按钮按压要不要触觉？**（推荐只给主 CTA 轻击，列表/芯片不给）
3. **§8 那两项在途改动保留吗？**（violet→朱磦、头像中性化）

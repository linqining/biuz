# 朱批 · Seal — 品牌资产包

命名与标识提案（2026-11-21）。产品：ZCode 社区版移动遥控台（原 BiuZ / `cn.biuz.mobile`）。
品牌逻辑：产品最有辨识度的手势是审批（批准权在人），故以「钤印」立牌 —— **朱批一落，代理照办**。

## 文件清单

| 文件 | 内容 |
|---|---|
| `seal-mark.svg` | 印面主标（阴文：朱底白 Z），透明底 |
| `seal-mark-yang.svg` | 阳文反转态（纸底朱 Z），残边与字口不变 |
| `app-icon-dark.svg` | iOS 1024 图标 · 墨底（深色备选） |
| `app-icon-light.svg` | iOS 1024 图标 · 纸底（**默认**——2026-11-24 用户裁决：启动图标背景默认浅色而非黑色；导出件 `png/appicon-pi-light-{1024,180}.png`） |
| `app-icon-android-adaptive.svg` | Android adaptive 分层（含 66dp 安全区标注，导出时删 `.spec` 层） |
| `brand-sheet.html` | 品牌规格表：印面构造 / 阴阳文 / 色板 / 字体 / 动效活体 demo / 图标 / Do-Don't |
| `seal-master-pi.svg` / `seal-master-pi-yang.svg` | 主印「批」阴文/阳文（宋体直刻，多平台语境的平台中立主印，候选定稿） |
| `app-icon-pi-dark.svg` / `app-icon-pi-light.svg` | 主印批 App 图标双态（Z 印的对照/接替候选） |
| `glyph-d.svg` | 字口 #002 · D（DeepSeek 借刻，刀口规则） |
| `registry.html` | 印谱：主印两变体对比 / Z vs 批图标实物对比（180-40px）/ 字口注册表 / 刻字六条 / 设备卡与会话行场景 mock |
| `motion-spec.html` | 盖章动效规格表：规格 + 时间轴 / ACK 后三路状态机 / 五决议面活体演示（审批卡/计划卡/提问卡/hook 审核/任务页 sheet，含失败分支开关）/ 双端实现注记。口径：落印仅在 ACK 权威成功后、停驻 700ms 收卡、拒绝墨印「不准」、失败不落印、印文图形不译（2026-11-21 用户裁决：只出设计稿不进工程） |

## 多平台分层（2026-11-21 印谱轮新增）

- **品牌常量不动**：印面形制、残边、阴阳文、四色、盖章动效、名字朱批/Seal。
- **平台只进「字口 + 边款」**：字口按刻字六条重刻（拉丁走刀口规则、汉字主印走直刻/元押先例、禁第三方 logo 与平台色入印）；边款 = mono 小注「平台 · 已钤」，≥32px 必带，<32px 用双字母刻法（ZC/DS）。
- **场景**：App 图标/启动页/关于页恒为主印（批）；平台字口只出现在连接页、设备卡、会话行；审批卡盖章动效永不平台化。
- **App 图标已定（2026-11-22 落地，2026-11-24 定底色）**：主印「批」，背景默认纸底（`app-icon-pi-light.svg`）；墨底版与 Z 印资产保留为备选。三端主题**默认浅色**（纸底墨字），深色为用户可选档——本资产包是视觉、主题令牌与启动图标的唯一依据（BiuZ 时代设计稿已删除）。
- 主印「批」由 fontTools 从 Songti SC Black 提取轮廓（y 翻转 + 墨迹界内居中 + 描边 1.6 加重），重提取脚本逻辑备查：TTCollection 取 face → Bounds 定界 → TransformPen(scale,0,0,-scale,tx,ty) 输出 SVGPathPen path。

## 构造事实（改动前必读）

- **印面路径**（`viewBox 0 0 100 100`）与 **Z 路径** 在 2 个母版 SVG + brand-sheet 页脚脚本三处逐字一致，改动须同步。
- **残边**：右下角整段磕缺，是品牌签名，任何尺寸不得修复。
- **Z 刀口**：右上/左下直角刀背，左上/右下斜刃口；Z 只与印面成对出现，禁止单独抠出。
- 落印场合统一旋转 **−2.5°**，上限 ±8°。

## 色 / 字 / 动

- 朱磦 `#D2402E`（加深 `#B03020`）· 墨 `#17181A` · 纸 `#F7F3EC` · 钤灰 `#8A8F98`
- Display 思源宋体 Heavy；正文 PingFang/思源黑体；协议串 JetBrains Mono
- 盖章动效：460ms，scale 1.6→0.96→1.04→1.0，rotate −10°→−2.5°，印泥外溢圈 520ms；`prefers-reduced-motion` 下终态直出
- 在线点 = 心跳 5s lub-dub 双搏脉动（呼应 Relay 心跳）

## 接入顺序建议

显示名 → App 图标（SVG 导出 PNG：iOS 1024 / Android 两层）→ 审批卡盖章动效 → bundle id 最后迁
（`cn.biuz.mobile → cn.zhupi.mobile`，涉及 E2E 套件、Keychain、深链参数，属大迁移，单独立项）。

## 待办（未做）

- 商标/重名筛查（「朱批」「Seal」均为通用词，社区项目可用，商用前需查册）
- ~~PNG 导出与 Xcode Asset Catalog 接入~~ 已完成：iOS `AppIcon-1024.png` = 纸底版导出（RGB 无 alpha）；应用内主印 `png/mark-pi-960.png` 按密度分桶入 Android `drawable/brand_seal`
- 中英双语文案核对（en 态 wordmark 用 "SEAL"）

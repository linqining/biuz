# 差异标记协作语言（diff-language）

> 解决的问题：各端与 web 默认必须一致，但平台基建与业务原因允许**刻意的不同**。
> 这套标记让"不同"变成显式、可 grep、可审计的协作语言——agent 打标记，用户也能用
> 同一套词下达指令；任何人（含未来的 agent）看到标记就知道"这是故意的，依据是什么"。
> 前缀默认 `APP-DIFF`，可在 `config/web-to-app.yaml` 的 `diff_language.prefix` 改。

## 1. 标记语法

```
APP-DIFF[D编号] kind: 一句话原因（依据/出处）
```

- 单行注释，Swift / Kotlin / Java / Dart / TS / JS 通用：`// APP-DIFF[...]`
  （XML / WXML 用 `<!-- APP-DIFF[...] -->`，WXSS/JSON 附近放不进注释时写在紧邻的
  wxml/js 注释里并注明作用对象）
- 放在差异代码块**紧邻上方**；文件级差异放文件头 import 之后
- **跨端同一差异复用同一 D 编号**（iOS 和 Android 是同一件事的两个投影），
  端内说明可各自表述
- 纯 ASCII 前缀，保证 `grep -rn "APP-DIFF" <各端目录>` 一网打尽

### kind：内置三类 + 可扩展

kind 集合定义在 `diff_language.kinds`（key = kind 名，value = 语义说明）。
**追加自定义 kind 只需在 config 加一行**，代码内即可用，台账照常登记——
扩展不走文档改动。内置三类（可改语义描述，但三类名建议保留）：

| kind | 语义 | 例 |
|---|---|---|
| `platform` | 平台/基建差异：框架能力、系统组件、性能优化导致实现形态不同，**视觉/交互规格已对齐** | 底部抽屉 web 用 fixed div，iOS 用原生 sheet、Android 用 BottomSheet，视觉规格逐项对齐 |
| `business` | 业务原因刻意不同：产品决策、合规、移动端入口策略 | web 某路由仅 URL 可达无入口，App 侧不硬造入口；某功能法务要求移动端不开放 |
| `pending` | **暂时**不同，待对齐：必须挂 PARITY_BACKLOG 编号，修完后删标记 | `// APP-DIFF[D031] pending: 动效曲线暂用系统默认，待 #017 补 web 实测参数` |

### 示例

```swift
// APP-DIFF[D012] platform: 底部抽屉用 SwiftUI sheet 原生实现（web 为 fixed div），
// 圆角/把手/背景模糊已按 web 截图逐项对齐（.capture/runs/20261009-* 量取）
.sheet(isPresented: $showDrawer) { DrawerMenuView() }
```

```kotlin
// APP-DIFF[D012] platform: 同一抽屉差异的 Android 侧，Material3 BottomSheet，规格同 D012
ModalBottomSheet(onDismissRequest = { showDrawer = false }) { DrawerMenu() }
```

## 2. 台账（路径取 `diff_language.ledger`，默认 docs/parity-diff-ledger.md）

标记只解决"代码处可见"，全局账本解决"总量、状态、撤销"。
**打标记必登记，登记必有 D 编号。**

台账头部维护同步基线行（`ledger_key`，由 `sync_state.py done` 自动镜像）。
条目字段：

```markdown
## D012 底部抽屉原生实现
- kind: platform ｜ 状态: active
- 范围: 001-首页（ios/App/Features/Home/HomeView.swift；android .../home/HomeScreen.kt）
- web 基准: fixed 定位 div + transform 动画（.capture/runs/<ts>-001-首页/dom.html）
- 各端做法: iOS sheet / Android BottomSheet，圆角 16、把手 36×4 已对齐
- 原因: 平台原生手势（下拉关闭）体验优于手搓 div；视觉规格不降级
- 引入: 2026-10-09 ｜ 依据: .capture/runs/.../01-viewport.png
```

编号规则：`D` + 三位递增（D001…），全局唯一不复用；差异被撤销（各端改回与 web 一致）
时状态改 `absorbed` 并删代码内标记，台账留痕。

## 3. 与 PARITY_BACKLOG 的边界

| | PARITY_BACKLOG（`docs/PARITY_BACKLOG.md`） | 差异台账 |
|---|---|---|
| 定性 | **缺陷**：与 web 不一致，要修 | **刻意不同**：不修，但可审计 |
| 状态流 | 待修 → 修复中 → 已修+实拍验证 | active → absorbed |
| 桥接 | `pending` kind 的标记必须挂 BACKLOG 编号；BACKLOG 修完后对应 pending 标记删除 | |

自查发现不一致 → 先判断：能排除账号态/实验/环境因素且确认 web 如此 → BACKLOG（缺陷）；
确认是平台/业务合理不同 → 差异标记（登记）。**判不了就先入 BACKLOG 待裁决，不留模糊态。**

## 4. 用户侧用法（协作语言的另一半）

用户可以用这套语言直接下达指令，agent 负责落标记和台账：

- "这段是平台优化，标 D0XX" → agent 打 `platform` 标记 + 登记/更新台账
- "这是业务差异，记一下" → agent 打 `business` 标记（编号顺延）+ 登记
- "这个差异后面要对齐的" → agent 打 `pending` 标记 + 在 BACKLOG 开条目并互相引用
- "看一下现在有哪些刻意差异" → agent 输出台账 active 条目汇总
- "审一下这些差异还成立吗" → agent 逐条对照 web 最新采证，该 absorbed 的改状态删标记

## 5. 审计

```bash
grep -rn "$(diff_language.prefix)" <各端源码目录> \
  --include="*.swift" --include="*.kt" --include="*.js" --include="*.wxml"
# 前缀默认 APP-DIFF（config/web-to-app.yaml）；改前缀需全库替换旧标记
```

规则：代码内每个 D 编号必须能在台账查到；台账每个 active 编号必须至少在一端代码中
存在；代码内出现的每个 kind 必须在 config 的 kinds 里定义（未定义 kind 视为笔误，
审计时报告）。每次验收轮（`acceptance.md`）末尾跑一遍对账。

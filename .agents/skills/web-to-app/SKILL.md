---
name: web-to-app
description: 把任意 web 项目移植并持续同步为原生 App / 微信小程序（默认 iOS SwiftUI + Android Compose + 微信小程序，平台可配置）的完整开发与验收规范。覆盖：冷启动首次移植（页面盘点/页面索引/公共件矩阵/分波次）、web 变更 9 步同步流水线（冻结 commit→采证→契约/骨架→逻辑实现→逻辑对拍→UI 静态→弹窗/动效→实拍→验收）、浏览器采证（CDP 截图/DOM/三重稳定门/登录态）、页面索引映射、逻辑对拍（web 金标向量→多端 runner→对拍账本）、UI 测量判定（适配栈换算/网格比例/容差/rpx）、交互态/弹窗态采证、动画关键帧验收、多端验收对比与裁决留痕、刻意差异标记语言（差异标记+台账）、平台差异能力封装（含小程序域名白名单/rpx/页面栈专项）、测试缝与真实路径双验证。当用户提到把网站/web/H5/网页做成 App、移植成 iOS/Android/原生应用/微信小程序/小程序、web 改了同步到 App、parity/对齐/还原度、按 web 采截图做基线、按 web 验收 App、页面索引、逻辑对拍、差异标记、首次移植、冷启动、多端一致时使用。
---

# web-to-app：web 项目 → 原生 App 移植与持续同步

## 方法论优先（最高优先级）

实现/验收中发现任何**可复现到一类元素/页面**的偏差，先按
`references/source-to-contract.md` 的十七类机制（M1–M17）归因；确认是方法论问题 →
**同一轮内更新 skill + 加机械检查 + 记 `docs/methodology-ledger.md`（ML-N）**，
再继续实现。不得推迟到下一轮，不得只写散文。

五步：归因（内容 vs 方法论）→ 定位机制（M1–M17）→ 改 skill → 上机械检查 → 记账（ML-N）。

- 判据：同类问题会不会在别的元素/页面上再发生？会 → 方法论；只影响单个元素 → 内容缺陷。
- **没有机械检查的方法论变更记 `partial`，不算关闭**。机械检查的固定落点：
  `scripts/methodology_checks.py <子命令>`（只读业务代码），清单见
  `references/source-to-contract.md` §5；每个 ML 行都必须能贴出一条该工具的命令输出。
- 同一类缺陷在更新后再次出现 → 升级为能力/门禁变更（补工具子命令、改契约结构、
  加工作流门禁），不是再加一条规则。
- 门自身也要先自证（正控/负控/落点/失败清理/可执行性/构建身份/状态 seam 七条，
  见 `source-to-contract.md` §6.5）；`methodology_checks.py selftest` 是其机械落地。

## 四条硬口径（先记住这四条，再看后面所有流程）

这四条是长期收敛里最贵的教训，违反任何一条都会让「绿」变成假绿。

1. **判官打回单先过假缺陷四型**（`references/false-positive-triage.md`）：SSO 顶号落游客态 /
   冻结 APK·旧构建 / 同帧复用 / 基线过期。采证污染制造的幻影缺陷比漏测更贵——返修会去修
   不存在的问题，真缺陷被顺延。证据字段不齐（触发命令、等待条件、设备视口、登录态、构建
   时间、截图路径）的条目先退回补证，不进入修复。
2. **改条件/样式/门控/inset 前先写类矩阵**（`references/class-matrix-and-native-surfaces.md`）：
   `class = {全部成员} × platform × state`，修复后逐成员点名验证（test/evidence/seam/blocked）；
   **禁止用父路由族代替页面类**——「所有含 X 的页面」通常是行为不同的两个类。
3. **布局结论必须绑定目标元素 bounds**（`references/layout-claim-evidence.md`）：没有目标元素
   （用户实际看到的那一层，不是它的容器）的 bounds 与 gap 数值，就不能写「满宽 / 居中 /
   无黑带 / 被裁 / 对齐」。证据报 `target / expected / measured / tolerance` 五项。
4. **证据有时效，清除标准不能自指**：截图/回稿先过「时钟 / 构建标识 / 环境」三问，旧帧只能
   证明历史缺陷；`cleared` 的 oracle 必须是**从 web 源码/资产派生的检查清单**，不是「本轮
   发现的问题都修完了」（`findings = 0` 永远不作为通过条件）。

## 背景（必读）

以 **web 源项目为唯一权威参照**，同源开发原生 App（读 web 源码移植，非黑盒猜还原）：

- web 源仓库位置、已部署站、本地 dev 地址：一律取 `config/web-to-app.yaml`（`web.*`），不写死路径
- 目标平台与各端工程目录：`config/web-to-app.yaml`（`app.*`，默认 iOS SwiftUI +
  Android Compose + 微信小程序，可增删其他平台）；小程序专项差异见
  `references/platform-differences.md` §5

**权威源规则**：web 是视觉、交互、协议、文案的唯一权威源。App 按 web 实现同步开发；
图片/图标素材与 web 同源下载（不做近似重绘）；App 内跳转一律原生路由，禁止跳出到网站。
web 源仓库**只读**（只 `fetch/log/diff/show`，不 checkout/reset/commit；切分支由用户操作）。

## 配置文件（config/web-to-app.yaml）

路径、采集参数、差异标记语言、目标平台全部可配置，**文档与脚本不写死这些值**：

- `web.repo` / `deployed_base` / `local_dev_base` —— 源码仓库与两个采证目标
- `pages.index` —— 页面索引（web 路由 ↔ key ↔ 各端验收路由）
- `capture.*` —— 调试端口、登录 profile、输出目录、视口/UA/登录判定正则
- `sync.*` —— 同步状态文件、脚手架目录、门禁集合
- `diff_language.prefix` / `ledger` / `kinds` —— 差异标记前缀、台账路径、kind 集合（可追加）
- `app.platforms` + `app.<platform>.*` —— 参与移植的平台与各端工程信息

样本：`assets/config.sample.yaml`（新项目复制到 `config/web-to-app.yaml` 改值即用）；
脚本自动读取，配置缺失时用内置默认值并报缺失项。引用配置值一律用 YAML 路径表述
（如 `web.repo`），不要把具体值复制进文档。

## 首次使用（冷启动）

项目还没接过本 skill / App 工程还是空的 → 按 `references/bootstrap.md` 执行：
**建配置 → 页面盘点建 page-index → 公共件矩阵 → 摸清 web 技术栈 → 分波次移植
（入口页优先）→ 首页走通全流水线作样板**。已有配置的增量开发直接查下表。

## 工作流索引（按场景查表）

| 场景 | 入口 | 详细步骤 |
|---|---|---|
| ① 冷启动 / 首次移植 | "把 XX 网站做成 App / 第一次移植 / 从零开始" | `references/bootstrap.md` |
| ② web 变动 → App 同步 | "web 改了 / 有变动，同步一下" | `references/sync-workflow.md` §A |
| ③ 分支对比 → 差异清单 → 实现 | "实现 XX 分支 / 和 XX 分支对比差异" | `references/sync-workflow.md` §B |
| ④ 采证 web 截图（基线） | "采集 XX 页面 / 截图 XX" 或 `/capture` 命令 | `references/capture-pipeline.md` |
| ⑤ UI 还原与测量判定 | "布局/间距/字号怎么换算 / 达不达标怎么判" | `references/ui-parity.md` |
| ⑥ 逻辑对拍（web 金标 × 各端） | "逻辑对拍 / logic vector / 补逻辑测试 / 对拍账本" | `references/logic-parity.md` |
| ⑦ 交互态/弹窗态/动画采证 | "拍弹窗 / 拍抽屉 / 点击后状态 / 动画不对 / 时长曲线" | `references/interaction-and-motion.md` |
| ⑧ 多端验收对比 | "验收 XX / 对比 XX 多端一致性" | `references/acceptance.md` |
| ⑨ 平台差异 / 能力封装 | "平台差异 / 能力矩阵 / Platform 包怎么封装" | `references/platform-differences.md` |
| ⑩ 打回单分诊 / 假缺陷排查 | "打回单 / 截图与实现矛盾 / 怀疑采证污染 / 返修量异常" | `references/false-positive-triage.md` |
| ⑪ 一类元素批量偏 / 原生壳层 | "改了一处伤了一片 / splash 状态栏 insets / 共享组件跨宿主" | `references/class-matrix-and-native-surfaces.md` |
| ⑫ 布局结论要可反驳 | "满宽 / 居中 / 黑带 / 被裁 / 对齐对不对" | `references/layout-claim-evidence.md` |
| ⑬ 双端代码审查护栏 | "代码审查 / 崩溃预防 / 负 padding / Lazy key / 验收假绿" | `references/runtime-guards.md` |
| ⑭ 缩放比例 / 设计→屏幕变换链 | "按钮大小不对 / 整体缩放不对 / 换机型就偏 / 触控点不到 / 二次乘" | `references/source-to-contract.md` §2.9（M17） |

任何工作流都遵循同一个循环：**采证（浏览器实拍/DOM/网络）→ 对照（页面索引定位各端实现）
→ 实现（各端同步改）→ 实拍验证（App 截图回比 web 基线）→ 留痕（台账登记）**。

### 验收判据分三层（只做第 ① 层就是漏验收）

| 层 | 问什么 |
|---|---|
| ① 视觉/UIUX | 同 key 同态与 web 一致，含只有触发后才出现的弹层/错误行/禁用态 |
| ② 功能闭环 | 动作真的完成了吗：写链结果、失败分支、在途锁、成功后刷新 |
| ③ 交互/产品合理性 | 可撤销、破坏性确认、状态完备、可达路径与返回栈、多端一致、防错容错 |

## 同步流水线（一句话）

推荐顺序：**冻结 commit → 采证 → 契约/骨架 → 逻辑实现 → 逻辑对拍 → UI 静态 →
交互/动效 → 实拍对比 → 验收并推进 commit**。弹窗/动画从采证和契约阶段就进入，
不要留到最后补。

```bash
SYNC=<skill>/scripts            # 本 skill 的 scripts 目录
python3 "$SYNC/sync_state.py" status
python3 "$SYNC/sync_state.py" begin --scope logic,ui,popup,animation --commit <本轮同步目标>
python3 "$SYNC/sync_scaffold.py" --key <key> --title "<标题>" \
  --scope logic,ui,popup,animation --routes <route> --code
# 按 references/sync-workflow.md 执行 9 步；门禁通过后：
python3 "$SYNC/sync_state.py" done \
  --gates build=pass,logic=pass,ui=pass,capture=pass --commit <本轮同步目标>
```

- 纯 UI 变更可 `logic=skipped --skip-reason "..."`，不要编造逻辑向量。
- 基线写 `sync.state_file`（默认 `docs/parity-sync-state.json`），并镜像台账；只有全门通过才推进。
- `--commit` 显式钉住本轮实际同步到的 web commit；缺省才用 web 源本地 HEAD（只读源仓 HEAD 可能滞后）。
- 历史基线记错时用 `sync_state.py reconcile --commit <目标>` 自校正（仅允许向前）。
- 详细示例与门禁语义见 `references/sync-workflow.md`。

## 采集命令（一句话）

```bash
python3 <skill>/scripts/capture.py shot 001-首页 --scrolls 3        # 按 key 采证（已部署站）
python3 <skill>/scripts/capture.py shot /items --base local         # 按路由采本地 dev server
```

输出落 `capture.output_dir`（默认 `.capture/runs/<时间戳>-<名称>/`）：
视口 + 滚动截图 + meta.json（稳定门/登录态/坏图审计），可选 dom.html。
细粒度交互（点击后截图、canvas 内状态、运行时态）用 capture.py 的 CDP 类驱动，
见 `references/interaction-and-motion.md`。

## 逻辑对拍测试（一句话）

按 `references/logic-parity.md` 五步闭环：**选单元 → 写契约 → web 出金标向量 →
各端跑实际输出 → comparator 对账**。首次使用在项目 `.capture/tools/` 建最小工具链
（probe / runner / compare），向量 schema 与豁免纪律见该文档。

## 差异标记协作语言

各端与 web **默认必须一致**；确需不同时，代码内打标记 + 台账登记，两级缺一不可：

```swift
// APP-DIFF[D012] platform: 底部抽屉用 SwiftUI sheet 原生实现，web 为 fixed 定位 div，视觉规格已对齐
```

```kotlin
// APP-DIFF[D012] platform: 同一差异在 Android 侧的对应说明，复用同一 D 编号
```

三类内置 kind：`platform`（平台/基建差异）、`business`（业务原因刻意不同）、
`pending`（暂时不同，必须挂 PARITY_BACKLOG 编号）；kind 集合与前缀在
`diff_language.*` 配置，可扩展自定义 kind。台账：`diff_language.ledger`
（默认 `docs/parity-diff-ledger.md`，D 编号全局唯一、跨端共享）。
完整语法与登记规则见 `references/diff-language.md`。

## 页面索引（web ↔ 各端的对照表）

`pages.index`（默认 `config/page-index.json`）是机器可读的映射：`NNN-名称 key` ↔
web 路由 ↔ 各端验收缝值 ↔ 是否 canvas 页。**新增页面时同步更新本文件与各端验收清单**；
JSON 由 capture.py 的 `pages` 子命令消费。

## 留痕规则

- 自查发现的不一致 → 登记 `docs/PARITY_BACKLOG.md`（格式见该文件头部，状态词表：
  `待修 → 修复中 → 部分已修* → 已修+实拍验证 / 已完成`，另有衍生态 `已解除*`、
  `如实不做*`；带 `*` 的状态**必须括号注记剩余部分/堵点/复验条件**，裸状态机检 FAIL）
- 刻意差异 → 差异标记 + `diff_language.ledger` 台账（**两级缺一不可**：代码内
  `APP-DIFF[D###]` 标记与台账行双向对账，机检 `methodology_checks.py diff-ledger`）
- 稳定性/环境问题 → `docs/PENDING-STABILITY.md`
- 采证事实（截图/网络/DOM）路径必须写进登记条目，禁止无证据裁决
- 方法论变更（共性偏差的规则化修复）→ `docs/methodology-ledger.md` 追加 ML-N，
  协议与判据见 `references/source-to-contract.md` §6

## 原则（所有流程通用）

1. **web 源是参照仓库**：只读 git 操作；不在其上 checkout/reset/commit。
2. **无证据不实现**：每个改动项都要有截图/DOM/diff/源码 `file:line`。
3. **无证据不裁决**：先排除账号态/AB 实验/环境因素（游客 vs 登录、旧构建、基线过期），
   多帧互证后再判缺陷或差异。
4. **未复现如实记录**：交互点不到、状态出不来，登记 `NOT_FOUND`，不硬造。
5. **一页一闭环**：采证→契约→实现→实拍→登记，页页清零；不攒批。
6. **基线只前进一次**：`begin` 记录、`done` 推进；任何门失败都不推进 commit。
7. **测试缝不是验收**：seam 通过必须补 real 路径（见 `references/acceptance.md`）。
8. **脚手架不造数据**：`--code` 只生成 `.template` + TODO，空壳不得过验收。

## Resources

- `references/source-to-contract.md` — **方法论主干**：十类偏差机制（M1–M10）、Source→Contract 流水线、coverage 收敛定义（替换 findings=0）、能力矩阵、机械检查目录、方法论变更协议五步、门自身七条验收、本仓反例库
- `references/bootstrap.md` — 冷启动：web 项目 → App 首次移植（配置/页面盘点/公共件矩阵/技术栈摸底/分波次/样板页）
- `references/sync-workflow.md` — 同步流水线与分支对比：9 步全解（冻结→采证→契约→逻辑→对拍→UI→交互动效→实拍→验收）、diff 归类、门禁语义
- `references/capture-pipeline.md` — 浏览器采证链路：调试 Chrome、capture.py CLI、稳定门三重奏、输出目录约定、已知坑
- `references/ui-parity.md` — UI 还原：web 适配栈判定（rem/设计稿/缩放变量）、网格比例测量与容差、资源/文案同源、缺陷优先级
- `references/logic-parity.md` — 逻辑对拍：五步闭环、向量 schema、必须对拍/可跳过、最小工具链搭建、豁免纪律
- `references/interaction-and-motion.md` — 交互态/弹窗态采证与动画验收：状态矩阵、触发优先级、稳定门、关键帧 ±5%
- `references/acceptance.md` — 多端验收对比：web 基线/各端实拍/逐屏比对/裁决留痕、seam vs real、多机型矩阵
- `references/diff-language.md` — 差异标记语法、kind 语义、台账登记与编号规则、与 PARITY_BACKLOG 的边界
- `references/false-positive-triage.md` — 假缺陷四型污染指纹与 60 秒排查顺序（打回单先过这张表）
- `references/class-matrix-and-native-surfaces.md` — 类矩阵、原生壳层（splash/状态栏/insets/IME）、真实字体度量与截断、动作目的地映射
- `references/layout-claim-evidence.md` — 把「满宽/居中/无黑带」变成 target/expected/measured/tolerance 的可反驳断言
- `references/runtime-guards.md` — 双端运行时护栏清单（含可执行静态门禁）
- `references/platform-differences.md` — 平台差异能力矩阵与封装：安全区/IME/返回/生命周期/深链/权限等的统一抽象；§5 微信小程序专项（域名白名单/rpx/页面栈/实拍自动化）
- `assets/config.sample.yaml` — 配置样本（复制到项目 `config/web-to-app.yaml` 改值即用）
- `assets/page-index.sample.json` — 页面索引样本（复制到 `pages.index` 路径改值即用）
- `scripts/capture.py` — 统一采证 CLI（自包含 CDP 客户端，不依赖 node；读项目配置；CDP 类可 import 做交互驱动）
- `scripts/sync_state.py` — 同步基线：`status` / `begin` / `abort` / `done` / `reconcile`；记录 commit/branch/dirty/门禁，done 时镜像台账；`--commit` 显式钉住同步目标，`reconcile` 做向前自校正
- `scripts/sync_scaffold.py` — 生成同步任务包（brief/file-map/checklist/popup-inventory/motion-spec/state-machine/acceptance）；`--code` 只生成 `.template` 骨架
- `scripts/skill_common.py` — 上述脚本共用的配置/git/路径工具（无第三方依赖）
- `scripts/methodology_checks.py` — 方法论机械检查（只读业务代码）：`baseline` / `state-ledger` / `diff-ledger` / `page-index` / `backlog` 五子命令 + `selftest` 临时树正负控；输出 `VERDICT` 行，轮次报告/PR 必须能贴出；`--strict` 时 REVIEW 也计失败

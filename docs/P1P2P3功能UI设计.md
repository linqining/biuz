# P1–P3 功能 UI 设计（11 项 · 可直接实施稿）

> 日期：2026-10-06 · 设计依据：
> ① `docs/web接口对齐盘点报告.md`（功能清单与 P1–P3 优先级，本轮全文复核）；
> ② 三路只读调研摘要（会话与消息 / 工作流与任务 / 文件与系统）；
> ③ 本轮逐文件核对真实视图结构——**全部插入点 file:line 均为设计当日实测行号**（标注「本轮核对」）；少数引自调研摘要而未复对的组件行号标注「（调研摘要）」。
> 阅读方式：每功能含【插入点】【布局与文案】【交互流】【状态矩阵】【确认层】【设计语言一致性】【协议缺口与取证前置】七节。§0 为全体功能共享的通用约定；§12 为硬边界；§13 为实施后必须回写协议文档的清单。

---

## §0 通用约定（所有功能共同遵守，正文不再重复）

### 0.1 设计令牌与组件（Theme.swift:45 enum T，调研摘要；组件行号本轮核对）

| 用 | 取 |
|---|---|
| 底色/卡片/输入底 | `T.bg` / `T.bgCard` / `T.bgInput`；卡片一律 `.card()`（Components.swift:321，调研摘要） |
| 主色/强调字/淡主底 | `T.accent` / `T.accentText` / `T.accentDim`；审批警示橙 `T.orange`；破坏红 `T.red` / `T.redLine`；面板紫 `T.violet` |
| 圆角/间距 | `T.rS`=8 / `T.rM`=12 / `T.rL`=16；`T.sp1..sp4`=4/8/12/16 |
| 字 | 正文 `T.font(size, weight)`；协议标识/数值一律 `T.mono(size)` |
| 空态 | `EmptyStateView(icon:title:detail:cta:)`（Components.swift:175-215，本轮核对）——**全 App 禁骨架屏**（Components.swift:173 注释，本轮核对） |
| 加载态 | `CenterLoadingView`（Components.swift:217-231，本轮核对）；行内小加载 `SpinnerView`（Components.swift:134，调研摘要） |
| 轻反馈 | 3s 自动消失的一行 hint（先例：switchHint `ChatView.swift:958-966`、面板控制反馈 `SessionPanelsView.swift:90-99`，本轮核对）；触感 `UINotificationFeedbackGenerator`（`ChatView.swift:479`，本轮核对） |
| 进度 | `ThinProgressBar`（Components.swift:343，调研摘要；用法先例 `ChatView.swift:987`，本轮核对） |
| 确认弹层 | `confirmationDialog`（先例：重置卡核销 `P2ExtrasViews.swift:64-81`，本轮核对）。**`role: .destructive` 仅用于不可逆/丢失数据/扣费动作**（重置卡核销、rewind 重发、恢复检查点、卸载插件、git 提交写历史）；可逆或轻影响操作（切工作区、compact）确认弹层保留但主键**不**用 destructive |
| sheet 弹层 | 底部 Capsule 把手 36×4 + `presentationDetents([.large])` + `presentationDragIndicator(.hidden)`（先例：`ContextPicker.swift:237-266`，本轮核对） |
| 命令回执反馈 | 成功静默 / 失败带 reasonCode 一行——`ChatViewModel.controlFeedback`（`ChatViewModel.swift:182-203`，本轮核对） |

### 0.2 命令下发纪律（工程红线，不遵守即回归）

1. **一切 v4 命令走 `RemoteConversationStore.sendCommand`**（`RemoteConversationStore.swift:1227-1300`，本轮核对）：嵌套 envelope + `clientId` 逐字相等 + `issuedAt` 毫秒 + **`envelope.sessionId` 键恒在场**（createSession 传 `.null`，1248-1250）+ 外层扁平 workspace 信封（1253-1257）+ 平铺形态兜底重试（1271-1291）。禁止自造信封。
2. **CAS 类命令**（需 `baseRevision`+`baseLogEpoch` 双字段）：先 `ensureStateRevision`（:2351-2358，revision 缺失先 resync+0.3s×10 轮询）再 `sendCASWithRetry`（:2338-2345，`status=="stale"` 原样重发一次）。队列五件是标准前例（:2362-2407）。
3. 全部调用必经 `ZCodeServerConnection.call` 唯一出口（ReadOnlyGate 在此拦截）。
4. 回执一律转 `controlFeedback` 口径；用户可见失败不静默。

### 0.3 a11y 纪律（E2E 门禁依赖）

- 分区前缀沿用：`02-` 任务、`03-` 新建会话、`04-` 会话列表、`05-` 会话详情、`06-` 任务审批、`08-` 文件、`12-` 设置。新页面按宿主分区取前缀；新增整页用 `12-`（设置域）或对应域。
- **identifier 必须挂 Menu 本体而非 label**（`ChatView.swift:849-852` 门禁实证注释，本轮核对）。
- 透明容器用 `.accessibilityElement(children: .contain)` 防吞后代（`ChatView.swift:453-456`、`TaskBoardView.swift:320-325`，本轮核对）。
- 全部可点元素 `minHeight: 44`。

### 0.4 证据标注规范

凡本文写「【未取证】」的 payload 字段/回执形状，实施者**不得**按猜测字段直接上线：先以诊断探针活体验证（模式同 `-ZCodeDiagQueueCASProbe`，AGENTS §6），命中后按 AGENTS §4.2/§4.4 回写 `docs/协议接口文档.md` §9 条目再接 UI。写「形状已实证」的方可直接照抄。

---

## §1 P1-1 附件上传

### 1.0 目标

聊天输入区支持「相册 / 拍照 / 文件」三类来源选附件，待发条显示分块上传进度，失败可重试、可移除；附件随消息一起发送。

### 1.1 插入点（本轮全部核对）

| 动作 | 位置 | 现状 |
|---|---|---|
| 附件入口按钮 | `ChatView.swift:996-1016` `inputRow` 的 HStack **首位**（TextField 之前）插入 | inputRow 现仅 TextField+sendButton；G-023 已删麦克风死按钮（:1011-1012 注释），新按钮必须真功能 |
| 待发附件条 | `ChatView.swift:769-785` `ComposerBar.body` VStack 内、`targetRow`(771) 与 `switchHint`(772-778) 之间插一行 | 无排队/无数据不渲染整行的既有口径（同 QueueBarView 挂载注释 `ChatView.swift:100`） |
| 发送联动 | `ChatViewModel.swift:420-425` `send()`：trim→清 draft→`store.send`；扩展为「等全部待发附件 committed → sendText 携附件引用」 | send() 现不携带任何附加参数 |
| 新建会话 sheet 同步解禁 | `NewConversationSheet.swift:310` `disabledChip("附件", …, id:"03-chip-attach")`（定义 :316-328：text3.opacity(0.55)+bgInput.opacity(0.6) 视觉禁用、accessibilityHint「即将支持」:327） | 新建会话尚未选会话、无法建附件事务——**保持置灰不动**，仅把 hint 文案改为「进入会话后可用」 |
| 下载/预览复用 | 读侧 `AttachmentThumbView`（`MessageViews.swift:768-835`：task 拉预览 814-817、失败态 798-807、fullScreenCover 818-824） | 待发条缩略图照抄其 132×96+T.rM+T.border 描边规格（:792-794），缩为 72×72 |

### 1.2 布局与关键文案

```
┌ ComposerBar（ChatView.swift:769 VStack）──────────────────┐
│ ☁️ 云端沙盒 ▾  偏好                                        │ ← targetRow(799) 不动
│ [相册] [拍照] [文件]  ←── 仅在待发条为空时显示来源快捷 chips   │ ← 新增 1A 行（可选，见交互流②）
│ ▢▒▒▒ 72×72 ▏IMG_2041.jpg   ▓▓▓▓░░ 68%  ✕ │ ✔ ▏doc.pdf  ✕ │ ← 新增 1B 待发条（横向滚动）
│ 「第 2 个附件上传失败 · 点按重试」                          │ ← 失败原因行（复用 switchHint 772-778 样式：橙 10.5pt）
│ [模型▾] [思考·high▾]              上下文 ▓▓ 43%           │ ← remoteChips(866) 不动
│ [📎] ( 发送消息… )                                    (↑) │ ← inputRow(996) 首位插 📎 44pt
└──────────────────────────────────────────────────────────┘
```

- 附件入口：`Image(systemName: "plus.circle")`（点击弹来源 dialog）——inputRow 首位，`T.text2` 图标 44pt 热区，`accessibilityIdentifier("05-attach-button")`。
- 待发条：横向 `ScrollView`，条目 = 72×72 缩略图（图片）或 doc.icon 占位（非图片，沿用 AttachmentThumbView 的 mediaType 判定 :778-781）+ 文件名（`T.mono(10)` 截中间，先例 structureRow :472 truncationMode(.middle)）+ 右上角 ✕ 移除（44pt 热区、`T.bgCard` 半透明圆底）。
- 进度：条目底部 4px `ThinProgressBar` + `T.mono(9.5)` 百分比；成功角标 `checkmark.circle.fill` `T.accentText`；失败角标 `exclamationmark.circle.fill` `T.orange` + 点条目重试。

### 1.3 交互流

1. 点 📎 → `confirmationDialog`「添加附件」三键：**拍照**（`UIImagePickerController` camera）/ **照片图库**（`PhotosPicker`）/ **文件**（`fileImporter`）+ 取消。
2. 选定后**立即建上传事务**（不等发送）：`attachmentBeginV4 → attachmentChunkV4(512KB/块) → attachmentCommitV4`，进度回填待发条。取消整个流程可在来源选择后 1s 内撤销，其余用待发条 ✕（未 begin 的直接移除；已 begin 的发 `attachmentAbortV4`——事务族仅协议文档 §12:759 记有族名【移植 transport.ts:379-381】，无参数/回执形状，【未取证】）。
3. 发送：sendButton 点击 → 若有未完成附件：按钮禁用（并入现 `disabled(viewModel.draft.isEmpty)` 条件 `ChatView.swift:1030`）→ 全部 committed 后 `send()` 携附件引用下发（sendText payload 附件字段形状【未取证】——服务端 userInput 行有 attachments 解析先例 `RemoteConversationStore.swift:929-937` + `extractAttachmentRefs` :1092-1102，可作为宽容解析依据，但发送侧字段名必须活体取证）。
4. 空文本但有附件：允许发送吗？——**不允许**（保持 `draft.isEmpty` 禁用口径不变，避免桌面端拒收纯附件消息的未知行为；如实约束）。
5. 超限：单附件 > 4MB（读侧实证上限 8 块×512KB，`RemoteConversationStore.swift:2911` 附近，调研摘要）→ 不发起事务，hint「桌面端通道单附件上限约 4MB，已跳过《xx》」。（读侧实证书写侧未必同限，接入时以探针为准。）

### 1.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 空（无待发） | 1B 行不渲染（composer 收紧，同「无排队不渲染」口径） |
| 上传中 | 缩略图上 Spinner→进度条+百分比；sendButton disabled |
| 单块失败/中继瞬断 | 沿用下载先例：1.2s 退避自动重试一次（`RemoteConversationStore.swift:2895-2900` 调研摘要）；再败转失败态 |
| 失败 | 橙角标+点按重试+失败原因行；重试从失败块续传（nextOffset 游标先例 :2929-2930） |
| 成功 | 勾角标；全部成功且 draft 非空 → sendButton 恢复可用 |
| 移除 | 已 committed 条目点 ✕ 发 abort（若取证支持）；未开始的直接移除 |
| 非连接态（演示态） | 📎 按钮不渲染（同 remoteChips 仅连接态渲染的切换口径 `ChatView.swift:779-783`） |
| 断连中途 | 未完成事务全部转失败态；hint「连接已断开 · 附件未发送」 |

### 1.5 确认层

无破坏性动作（上传/移除均不影响桌面已有数据）。abort 他人文件不存在——附件事务只作用于本次待发。

### 1.6 设计语言一致性

- 44pt 热区、T.mono 数值、ThinProgressBar 进度 = contextMeter 同款（`ChatView.swift:982-994`）。
- 缩略图规格、失败态文案风格 = AttachmentThumbView（`MessageViews.swift:798-807`「附件加载失败」）。
- hint 行 = switchHint 通道复用（`:772-778`，含 `05-composer-switch-hint` 相邻标识位）。

### 1.7 协议缺口与取证前置（本功能最大风险）

`attachmentPut` 在协议文档 **0 命中**；`attachmentBeginV4/ChunkV4/CommitV4/AbortV4` 仅 §12 族名记录（协议文档 ：759【移植】）；`ReadOnlyGate.swift:209-210` 注释附件事务在 RPC 出口放行（调研摘要）。**实施第一步**：诊断探针对真实桌面活体取证事务四命令的参数/回执（含 sendText 附件字段名），按 §4.4 模板回写 §9 两个新条目，再接 UI。

---

## §2 P1-2 会话模式（plan/build）与投递模式（queue/guide/now）

### 2.0 目标

composer 常驻两个 Menu：协作模式 Plan/Build 切换；投递模式 立即/排队/引导 切换。投递模式与发送参数联动。

### 2.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 两个 Menu 胶囊 | `ChatView.swift:769-785` `ComposerBar.body` VStack 内、`targetRow`(771) 之后新增独立 `modeRow`（不在 targetRow 行内追加——窄屏溢出，见 §2.2） |
| 发送参数联动 | `ChatViewModel.swift:420-425` `send()`：按当前投递模式携带 `requestedDelivery`（探针已活体验证 `"queue"` 形态，`RemoteConversationStore.swift:2449-2459` 诊断代码在案，调研摘要） |
| 偏好持久化 | 照抄执行目标先例：per-conversation 恢复 `.task(id: viewModel.conversationID)`（`ChatView.swift:791-794`）+ `ExecutionTargetStore` 同构本地存储 |
| 命令下发 | `RemoteConversationStore` 新增 `switchCollaborationMode` 方法：`ensureStateRevision`+`sendCASWithRetry`（:2351-2358/:2338-2345，前例 `setGoalPaused` :2274-2279） |

### 2.2 布局与关键文案

**不挤 targetRow 一行**：执行目标 Menu+「偏好」标注已占左侧（:800-807），再塞两个 Menu（各含 icon+文案+chevron、minHeight 44）在窄屏（375pt-32 边距=343pt）必然溢出。改为**新增独立 modeRow**，插在 `targetRow`(:771) 与 `switchHint`(772-778) 之间：

```
┌ ComposerBar（改造后）─────────────────────────────────────┐
│ ☁️ 云端沙盒 ▾  偏好                                   ← targetRow 不动
│ [📋 Plan ▾]  [⚡ 立即发送 ▾]                          ← 新增 modeRow（仅连接态）
│ 「桌面端拒绝（reason）」                               ← switchHint 复用
│ [模型▾] [思考·high▾]              上下文 ▓▓ 43%       ← remoteChips 不动
│ ( 输入框… )                                       (↑) │
└──────────────────────────────────────────────────────────┘
```

- 模式 Menu：label = `list.clipboard`(plan) / `hammer`(build) + 文案「Plan」/「Build」+ 上下 chevron（pill 样式照抄 executionTargetMenu :833-847：11.5pt medium、bgInput、Capsule、minHeight 44）。菜单项带 ✓（`modelRow` 先例 :908-913）。
  - plan 项副文案：「先出计划，改动需经你确认」；build 项：「直接执行改动」。
- 投递 Menu：label = 当前模式文案。三档：
  - **立即发送（now）**：icon `bolt.fill`，「消息直接进入当前回合」
  - **排队（queue）**：icon `list.number`，「回合进行中发送将排队，回合结束后自动投递」（对应 QueueBarView 体系 `ChatView.swift:101-111`）
  - **引导（guide）**：icon `arrow.triangle.merge`，「作为引导补充注入当前回合」（guide 的桌面语义【未取证】，仅盘点报告 :27 词表口述——菜单项先上线但标注说明）
- `accessibilityIdentifier`：`05-chip-mode` / `05-chip-delivery`（挂 Menu 本体！见 §0.3）。

### 2.3 交互流

1. 切模式：点选 → `switchCollaborationMode`（payload【未取证】；CAS 全集成员：协议文档 §7.2:414【2026-10-06 bundle 取证→移植级】，按 CAS 发）→ 成功：胶囊态更新 + `UISelectionFeedbackGenerator().selectionChanged()`（`selectTarget` 先例 ：855-859）+ 本地持久化；失败：`showSwitchHint`（:958-966）橙字 3s「桌面端拒绝（reason）」。
2. 切投递：纯本地偏好切换（不发命令——`setFollowupMode` 是否需要独立下发【未取证】：盘点报告 :27 口径「sendText 已支持 requestedDelivery 参数」暗示投递随消息携带；**实施时以探针裁决**：若桌面要求独立 setFollowupMode 则补发，payload【未取证】）。切换成功给 hint「本条消息起按「排队」投递」。
3. 发送：`send()` 按模式携带 `requestedDelivery`（now 档不携带键，保守）。queue 档发送成功后 hint「已加入排队」，消息随后出现在 QueueBarView（回流驱动，无新增 UI）。
4. 非连接态：两 Menu 不渲染（同 remoteChips 条件 ：779）。

### 2.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 默认 | Plan / 立即发送（本地偏好缺省值；**注意此初值不保证等于桌面实态**，见 §2.7 缺口③） |
| 切换中 | 胶囊不进 loading（单命令回执快，与模型切换 modelRow :900-914 同策略） |
| 失败 | switchHint 橙字 3s；胶囊保持原态 |
| 成功 | 胶囊更新+震动+持久化；重新进入会话恢复 |
| 队列有货时切投递 | 不影响已排队条目（QueueBarView 独立运作） |

### 2.5 确认层

无破坏性（模式切换是桌面会话配置，可逆）。plan→build 不弹确认（桌面端 plan 模式本身已有审批卡兜底：ApprovalInteractionCard/PlanApprovalCard `ChatView.swift:338-485/490-571`）。

### 2.6 设计语言一致性

Menu 内联胶囊 = executionTargetMenu（:812-853）；✓ 选中项 = modelMenu（:908-913）；per-conversation 恢复 = executionTarget 的 `.task(id:)`（:791-794）；失败反馈 = showSwitchHint（:958-966）。

### 2.7 协议缺口

1. `switchCollaborationMode` payload 无记录（CAS 集在案）；`setFollowupMode` 词表 queue/guide/now 仅盘点报告口述（协议文档 §7.2:407 仅「跟随模式」四字）。两项均【未取证】，实施前探针。
2. `requestedDelivery` 参数：用户发送路径 `send()` 现不携带（`ChatViewModel.swift:420-425`），仅诊断探针活体验证过 `"queue"` 形态（`RemoteConversationStore.swift:2449-2459`）——接入时沿用探针形态，其余取值 now/guide 同样探针。
3. **协作模式初值无桌面实态来源**：全 Sources grep `collaborationMode|collaboration` 0 命中（2026-10-06 本轮跑），无任何读投影。首屏只能显示本地偏好，**可能与桌面实际模式不一致**，且此时点按是一次真实 CAS 切换而非无操作。处理：①探针阶段优先取证是否存在读面（候选：会话 state 投影键、`model-selection.getView` 扩展字段、快照顶层键）；②若确认无读面，胶囊下方一次性 hint「显示为本机偏好 · 桌面端实际模式以执行行为为准」，点按切换即真实下发并回显回执结果。

---

## §3 P1-3 消息反馈与编辑重发

### 3.0 目标

助手消息可点赞/点踩（已反馈态回显）；用户消息长按「编辑重发」，预填原文并说明 rewind 语义。

### 3.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 助手消息反馈行 | `MessageViews.swift:31-62` agent 分支 VStack 尾部（question 卡 ：59-61 之后）插入反馈行——**带渲染门槛，见下行** |
| 反馈行渲染门槛（防误挂） | agent 分支还渲染多类无游标/非正文消息：reasoning 行（text 为空，`RemoteConversationStore.swift:966-975`）、state-todos 合成消息（id 固定 `"state-todos"` 无 `row-` 前缀，rowId 解析 `MessageViews.swift:13-16` 返回 nil，:1070-1078）、subagent 行（合成文案「🤖 子智能体 · …」，:1018-1022）、artifact 行（「📦 产物 · …」，:1023-1032）。**反馈行仅对「助手正文行」渲染**：前置改造在 rebuildMessages 合成 ChatMessage 时打行来源标记（ChatMessage 增 `rowKind` 字段，随 §下行游标扩展一并落），`rowKind == .assistantText` 才渲染 👍👎；其余四类一律不渲染（不做字符串前缀判断的脆弱门槛） |
| 用户消息长按菜单 | `MessageViews.swift:68+` `UserBubble` 外层（agent 分支 user case :23-30 的 UserBubble 处）加 `.contextMenu`——全 App 唯一先例 `ConversationListView.swift:443-497`（本轮核对） |
| 游标扩展（前置改造） | `RemoteConversationStore.swift:920-1102` `rebuildMessages`：userInput 行现只取 text+attachments（:929-937），需扩展提取服务端行游标字段；ChatMessage 模型（`Models.swift:258-287`，调研摘要）无 revision/logEpoch，游标从 store 字典 `conversationStateRevisions`(:42)/`conversationWatermarks`(:147) 取（调研摘要核对） |
| 新命令 | `RemoteConversationStore` 增 `setAssistantFeedback` / `editUserQuery`（均走 sendCommand；两者在 CAS 权威全集 ：414，row-target 类） |

### 3.2 布局与关键文案

助手消息反馈行（正文字号更小的低调行）：

```
│ ● AgentAvatar  正文正文正文…                                    │
│   👍  👎                                   ← 24pt 图标 + 44pt 热区，T.text3；已选中 T.accentText
```

用户消息长按菜单（contextMenu）：

```
┌ 长按用户气泡 ─────────┐
│ ✏️ 编辑重发            │
│ 📋 复制文本            │
└──────────────────────┘
```

编辑重发 sheet（照抄 §0.1 sheet 范式）：

```
        ───────（Capsule 把手）
  编辑并重发                        取消
  ┌────────────────────────────────────┐
  │ （预填原消息全文，TextField axis:.vertical）│
  └────────────────────────────────────┘
  ⚠️ 说明行（T.orange 11.5pt）：
  「重发将把对话回退到这条消息之前（rewind），
    它之后的所有回复会被替换，不可撤销。」
  ┌────────────────────────────────────┐
  │            编辑并重发（T.accent 主钮）      │
  └────────────────────────────────────┘
```

### 3.3 交互流

**点赞/点踩**：点 👍/👎 → `setAssistantFeedback`（payload【未取证】；row-target 游标=rowId（`MessageView` 已解析 ：12-16）；assistant 行是否还需 entityId 以 retryTurn 先例（toolCall 行 ：1010 提取）活体验证）→ 成功：图标高亮 + 震动；再点已选项 = 取消反馈（payload 置空值形态【未取证】）。失败：消息尾部 hint（反馈行旁一行 T.orange 3s，或降级为仅震动+静默——**不弹窗**）。
**编辑重发**：长按 → 「编辑重发」→ sheet 预填 → 改文本 → 点「编辑并重发」→ `confirmationDialog`（§0.1 范式）：
- 标题「确认重发并回退对话？」/ message「将回退到第 N 条消息之前，之后的回复会被替换。」/ 主键「重发」role:.destructive + 取消。
- 确认 → `editUserQuery`（CAS 类：ensureStateRevision + sendCASWithRetry；payload 形状【未取证】——可参照同族 `retryTurn {target:{rowId, entityId}}`（store :2663 调研摘要）推测但必须探针）→ 成功：dismiss + 消息流经 rows 键级重建自动刷新（rebuildMessages :920-1088）+ 震动；失败：sheet 内错误行（controlFeedback 文案）不 dismiss。

### 3.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 反馈：未反馈 | 双图标 text3 |
| 反馈：已赞/已踩（回显） | 对应图标 accentText；另一图标保持 text3。**回显数据源（两级）**：①服务端 assistant 行若携反馈状态字段（字段名【未取证】，随 §3.1 行解析扩展一并提取）；②本地内存记录（会话级 Set，命令 accepted 即写）兜底。**重启后**：本地记录清零，以①的服务端读回为准；若服务端无读回字段则显示未反馈（与桌面可能不一致，如实降级） |
| 反馈：非正文行 | reasoning（text 空）/ state-todos（id 非 row- 前缀）/ subagent / artifact 行**不渲染反馈行**（§3.1 门槛） |
| 反馈：命令失败 | 图标回弹 + hint 3s；本地记录不写 |
| 反馈：非连接态 | 反馈行不渲染（agent 消息在演示态也存在，但命令不可达） |
| 编辑：sheet 预填 | 原文全文（不截断） |
| 编辑：发送中 | 主钮「重发中…」+ Spinner + disabled |
| 编辑：成功 | dismiss；流内旧消息被服务端 rows 重建替换（乐观不做——rewind 结果以桌面为准） |
| 编辑：失败 | sheet 错误行；可改后重试或取消 |
| 编辑：非连接态 | 菜单项隐藏（无游标无命令） |

### 3.5 确认层

rewind 不可撤销 → 双重确认：sheet 内说明行 + confirmationDialog destructive 主键（文案见 3.2）。点赞/点踩可逆，不设确认。

### 3.6 设计语言一致性

contextMenu = `ConversationListView.swift:443-497`（Label+systemImage+identifier 同款）；sheet = ContextPicker 范式；带 TextField 的弹层先例 = 追问 alert（`ApprovalSheetView.swift:254-262`）；失败反馈 = controlFeedback（`ChatViewModel.swift:182-203`）。

### 3.7 协议缺口

1. `setAssistantFeedback`/`editUserQuery` 均 §7.2 一行记录（:404/:414），payload 零记录；userInput 行游标缺失是**代码前置改造**（3.1 第三行）。两项均先探针后接 UI。
2. **反馈状态回读**：服务端 assistant 行是否携带已反馈字段（供重启后回显）零取证——探针时对助手行回执全文取证；若无此字段，「已赞/已踩」跨重启不保留，UI 如实按未反馈显示（写入实现注释，不做假持久化）。
3. assistant 行游标（是否需 entityId，同 retryTurn 先例 :1010 的 toolCall 行提取）零取证——探针时验证 `rowId` 单游标是否被桌面接受。

---

## §4 P2-4 工作流启动

### 4.0 目标

「工作流库」页的已保存工作流行卡加「启动」，动态参数表单，启动中/成功/失败态完备。

### 4.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 行卡「启动」钮 | `P2ExtrasViews.swift:845-869` 行卡 HStack 的 `Spacer`(:861) 与 badge(:862-864) 之间插 44pt 胶囊钮（仅 `id` 前缀 `wf-` 的 workflows 行渲染；`run-` 最近运行行不渲染） |
| 启动 sheet | 行卡 `.card()` 上加 onTap/按钮 → `StartWorkflowSheet`（挂载在该 ScrollView 页 `.sheet(item:)`） |
| 页面宿主 | 设置页入口 `SettingsView.swift:343-344` + destination :361-362（本轮核对）——页面本身不动 |
| 新命令 | `startSavedWorkflow`：协议文档 §9 无条目、§7.2:406 一行【移植】、web bundle 0 次出现（盘点报告 §五:120） |

### 4.2 布局与关键文案

```
┌ 工作流库行卡（改造后）────────────────────────┐
│ 🗺  周报生成流水线                [启动]      │   ← 启动钮：T.accent 底 onAccent 字，
│     每周五自动汇总…                            │     12.5pt semibold、rM-2 圆角
└──────────────────────────────────────────────┘     （照抄 TaskCardView「去审批」TaskBoardView.swift:341-354）

StartWorkflowSheet：
        ───────
  启动「周报生成流水线」            取消
  描述文字（description，若有）
  ── 参数 ──
  时间范围   [ _____________ ]        ← string
  并发数     [ 4 ]                    ← number（decimal keyboard）
  启用通知   ( Toggle )               ← boolean（SettingsView.toggleRow :466-485 样式，调研摘要）
  级别       [标准 ▾]                 ← enum（Menu；选项多则 ZSegmentedPicker）
  ┌────────────────────────────────────┐
  │              启动（主钮）                │
  └────────────────────────────────────┘
```

- schema 缺席时参数区整体替换为一行说明：「该工作流无需参数」。

### 4.3 交互流

1. 点「启动」→ sheet 打开 → 并行：探测该工作流参数 schema（**来源字段全部【未取证】**：候选 `inputSchema`/`parametersSchema`/`argsSchema`/`params`，全 Sources grep 零命中——调研 risks；上游可能有 `getSavedWorkflow(R)` 读面（立项报告 ：588 清单在列，调研摘要）但形状无记录）。
2. schema 有 → 动态表单；无 → 「无需参数」直启。
3. 点「启动」→ `startSavedWorkflow` payload【未取证】（最可能形 `{workflowId|name, args:{…}}`，禁止照抄，探针定形）→ 回执处理。
4. 成功后去向：回执若携 sessionId → `router.openChat(conversationID:)`（fork 先例 `ConversationListView.swift:463-475`）；不带 → toast「已启动 · 运行面板在对应会话内」+ 该行卡 badge 置「运行中」。

### 4.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 列表空/失败 | 维持现状占位页（`RemoteCapabilityPlaceholderPage` :776-795 诚实占位，本轮核对），不新增 |
| sheet 加载 schema 中 | `CenterLoadingView("正在读取参数定义…")` |
| schema 读取失败 | EmptyStateView(icon:"exclamationmark.triangle", title:"参数定义不可用", detail:"桌面端未返回该工作流的参数定义，可在桌面端启动，或稍后重试。", cta:"重试") |
| 无参数 | 说明行 + 主钮「直接启动」 |
| 启动中 | 主钮「启动中…」+ SpinnerView + disabled |
| 启动成功 | 震动 + toast + 按回执决定跳会话或留页 |
| 启动失败 | sheet 错误行（controlFeedback：rejected 带 reasonCode；nil=未送达） |
| 校验失败（表单必填缺） | 缺项字段下红字提示，不发起命令 |

### 4.5 确认层

启动工作流会让桌面开跑（消耗额度/产生文件写入）→ **主钮前不需 dialog**（sheet 本身即确认层，表单即知情），但按钮文案保持「启动」并在 header 下保留 description 供知情；成功跳转前 toast 明示。

### 4.6 设计语言一致性

行卡 = 既有 `RemoteCapabilityListPage` 行（:845-869）；启动钮 = TaskCardView「去审批」（`TaskBoardView.swift:341-354`）；表单控件 = toggleRow（SettingsView）/ZSegmentedPicker（DiffReviewView 源切换 ：210-218 先例，本轮核对）；sheet = §0.1 范式。

### 4.7 协议缺口

三处零取证：①参数 schema 字段名与形状；②`startSavedWorkflow` payload；③回执是否携 sessionId。**实施第一步**是探针 `listSavedWorkflows` 真实回执全文（现解析只取 4 键，`P2ExtrasViews.swift:999-1015`）+ `getSavedWorkflow` 是否存在；按 §4.4 模板回写 §9 新条目。

---

## §5 P2-5 目标下发

### 5.0 目标

goal 面板从只读变可编辑下发，与既有暂停/继续并存。

### 5.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 「编辑」钮 | `SessionPanelsView.swift:269-276` `GoalPanelView` 头部 HStack 内、「暂停/继续」钮（:277-294）**左侧**插铅笔小钮（44pt 热区、text3 图标） |
| 回调链扩展（前置改造，GoalPanelView 现仅持 `goal`+`onTogglePause` 两参数，:263-265，无 conversationID/store 通路） | ① `GoalPanelView` 参数加 `var onEditGoal: () -> Void`（铅笔钮点击回调）；② `panelBody(.goal)` 挂载处（:180-186，现只承载 pause 反馈回调）扩为同时传 `onEditGoal`（置 `viewModel.editingGoalActive = true`）；③ `ChatViewModel` 新增 `@Observable` 态 `editingGoalActive: Bool` 与 `sendGoal(_ text: String) async -> String?`（内走 store 命令+recordControlDiag，模式同 `toggleGoalPause` :60-65）；④ `GoalEditSheet` 挂在 `SessionPanelsView` 根部 `.sheet(isPresented: $viewModel.editingGoalActive)`——下发与反馈都经 `showControlFeedback`（:90-99）既有通道，conversationID 由 viewModel 持有，GoalPanelView 本身不需要新数据通路 |
| 编辑 sheet | 见上——`GoalEditSheet` 挂 SessionPanelsView 层 |
| 数据源 | `viewModel.goalSummary`（`ChatViewModel.swift:52`，state.goal 投影；宽容解析 `RemoteConversationStore.swift:2193-2206`，调研摘要） |
| 新命令 | `sendGoalCommand`：§9 无条目、§7.2:406 一行【移植】；**未列** §7.2:414 CAS 权威全集——但它会改 `state.goal`，**按 CAS 预期处理并活体验证**（调研摘要明确此口径） |

### 5.2 布局与关键文案

```
┌ GoalPanelView（改造后）──────────────────────┐
│ ◎ 当前目标   [✏️] [⏸ 暂停]                    │
│ 修复登录超时问题，并保证回归测试通过…           │
└──────────────────────────────────────────────┘

GoalEditSheet：
        ───────
  编辑目标                          取消
  ┌────────────────────────────────────┐
  │ （预填 goal.text，axis:.vertical，4 行起）  │
  └────────────────────────────────────┘
  说明行（text3 11pt）：「将替换桌面端当前目标，
  Agent 会按新目标继续执行；进行中的回合不受影响。」
  ┌────────────────────────────────────┐
  │            下发目标（主钮）               │
  └────────────────────────────────────┘
```

### 5.3 交互流

1. 点 ✏️ → sheet 预填 `goal.text`。
2. 改文本 → 「下发目标」→ `ensureStateRevision` + `sendCASWithRetry("sendGoalCommand", payload:{goal|text: …})`——**payload 键名【未取证】**（候选 goal/text/description/content，与 state.goal 宽容解析键组一致 ：2193-2206），探针定形；CAS 属性探针裁决（rejected "CAS commands require…" 即补）。
3. 成功：dismiss + 面板反馈行「目标已下发 · 桌面端将按新目标继续」3s（成功也提示的先例：`setSubagentModel` `ChatViewModel.swift:78`）+ state.updated 回流自动刷新文本。
4. 失败：sheet 内错误行（controlFeedback），不 dismiss。
5. 无 goal 会话：不出 goal chip（availableKinds :112-120 门槛不变）——**创建新目标入口本期不做**（见 5.8）。

### 5.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 只读（默认） | 现状渲染 + 新增 ✏️ |
| sheet 编辑中 | 自由编辑；空文本 → 主钮 disabled（editQueueItem 同口径 `ChatViewModel.swift:143-145`） |
| 下发中 | 主钮「下发中…」disabled |
| 成功 | dismiss + 3s 反馈行 + 文本回流刷新 |
| 失败 | sheet 错误行；「重试」即再点主钮 |
| 非连接态 | ✏️ 不渲染（goalSummary 本就只在连接态投影） |
| 暂停态并存 | 暂停/继续钮不动；下发不改变暂停态（若桌面语义改变暂停态，以 state 回流为准） |

### 5.5 确认层

改目标影响 Agent 后续行为但可再次编辑纠正（可逆）→ 不设 dialog；sheet 内说明行承担知情义务。**不**做成 destructive。

### 5.6 设计语言一致性

面板内小钮 = 暂停钮（:277-294 Capsule+accentDim）；sheet = §0.1 范式；反馈 = showControlFeedback（SessionPanelsView.swift:90-99）经 `panelBody(.goal)` 回调链（:180-186 扩展，见 §5.1 回调链扩展行）。

### 5.7 （并入 5.3）

### 5.8 范围注记

「从零创建目标」依赖 sendGoalCommand 创建语义的取证 + 无 goal 时面板入口体系改造（chips 数据在场才出 chip 的既定口径），本期不做；待命令取证后另立设计。

---

## §6 P2-6 任务分组对齐桌面

### 6.0 目标

任务看板分组/排序/折叠跟随桌面结构（`listGroupedTaskViewStructure`），失败回退本地分组并提示。

### 6.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 读面调用 | `TaskBoardView.swift:110-118` `.task(id: ObjectIdentifier(store))` 块内并行加载（不阻塞 `model.tasks` 首屏）；连接态才调 |
| 分组渲染 | `TaskBoardView.swift:146-197` `board`：以「桌面分组结构优先」替换硬编码四组循环（waiting/running/failed/done 四组计算属性 ：28-31 保留为回退路径） |
| 组头 | 复用 `groupHeader`（:222-236，标题+计数胶囊）+ 叠加 chevron 折叠（折叠先例：`ConversationListView.swift:280-286` Section+collapsedGroups 结构） |
| 回退提示行 | `board` 内 statusFilterChips（:153）之后插一行（样式照抄 `moreNotice` `DiffReviewView.swift:236-251`：info icon + 11.5pt text3 + bgInput 圆角条） |
| 数据面 | `zcode-task.listGroupedTaskViewStructure`：协议文档 §9 **无条目**（仅立项报告 ：586 upstream 清单在列【移植】，调研摘要）；回执形状零取证 |

### 6.2 布局与关键文案

```
┌ 任务页 board ────────────────────────────┐
│ 下午好                                    │
│ [🔍 搜索任务]  [全部][运行中][待操作]…       │  ← 不动
│ ⓘ 已按桌面端分组展示 · 失败时显示回退提示      │  ← 新增：常态显示来源提示？否——见交互流
│ ▾ 前端重构（桌面组名）            4         │  ← 桌面组：组头+chevron+计数
│   [任务卡][任务卡]…                        │
│ ▸ 数据迁移                       2         │  ← 折叠态
│ （桌面回执无组的任务归「未分组」组，组头同款）   │
└──────────────────────────────────────────┘
回退提示行文案：「桌面分组不可用 · 已按状态分组」
```

### 6.3 交互流

1. 进页：任务列表照常先渲染（本地四组），并行请求桌面分组结构 → 回执成功且解析出 ≥1 组 → 平滑替换为桌面分组（组序按回执；组内任务序按回执序，回执只给 taskId 列表则按 cache 平表顺序对齐）。
2. 折叠：组头点击切换；折叠态**内存态**（组名键控，先例 `ConversationListView.swift` collapsedGroups :23，调研摘要核对）——桌面回执若携折叠标记（【未取证】）则以桌面为初值。
3. 状态筛选 chips（:47-79）与桌面分组**叠加**生效（先筛选后分组，filtered :32-44 链路不变）。
4. done 组「查看全部」逻辑（:174-190）仅对回退态四组保留；桌面分组下每组建照（同款 TextActionButton），不持久化。

### 6.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 加载中（任务列表） | `CenterLoadingView`（:84-85）不变 |
| 分组结构加载中 | 静默（先渲染本地四组，替换无感）——不显示 loading 行 |
| 桌面分组成功 | 桌面组渲染；**无提示行**（常态即对齐，不打扰） |
| 回执空/解析失败/命令失败 | 回退本地四组 + 提示行「桌面分组不可用 · 已按状态分组」3s 后保留为常驻淡行（T.text3，不阻塞操作） |
| 空组 | 不渲染该组（「数据消失不渲染空壳」口径，SessionPanelsView.swift:54-55 注释先例） |
| 演示态 | 不调远端，纯本地四组，无提示行 |
| 任务空 | 现状 EmptyStateView（:86-93）不变 |

### 6.5 确认层

纯读面，无确认。分组**写**族（createTaskGroup 等）已存在且不动（`ConversationListView.swift:476-483`「移入分组」链路，本轮核对）。

### 6.6 设计语言一致性

组头 = groupHeader（:222-236）；折叠 = ConversationListView Section+chevron；提示行 = moreNotice（DiffReviewView.swift:236-251）；`.task(id: ObjectIdentifier(store))` 重挂纪律 = :108-110 门禁注释。

### 6.7 协议缺口

`listGroupedTaskViewStructure` 参数/回执**零取证**（调研 risks 明确）。实施前探针：参数**先试 `workspaceScopes:[{workspacePath}]` 数组形态**（listTaskList 实参先例 `RemoteTaskStore.swift:116-123`——注意是数组包裹，不是扁平 `{workspacePath}`），失败再试扁平形态；回执宽容解析（组名/序/成员多形态）。命中后回写 §9 新条目 + §10（若涉投影键）。

---

## §7 P2-7 上下文压缩（compact）与审批「稍后处理」（snooze）

### 7A. compact 入口

#### 插入点（本轮核对）

- `ChatView.swift:982-994` `contextMeter` 外包一层 `Button`（remoteChips 行 ：866-876 与 toolsRow :968-979 两处调用点同步）——点击弹压缩确认。入口语义：用量条即压缩动机，就地可发现。**显式连接态 gating**：Button 仅在 `viewModel.isReadOnly`（连接态）时包裹——**不能以「contextMeter 条件渲染」作隐式门槛**：演示态 Mock 恒返回非 nil `sessionContextUsage`（`MockConversationStore.swift:42-48`，本轮核对；`ChatViewModel.swift:271` 装载），toolsRow 演示态照样渲染「上下文·演示」（:974-976），无 gating 则压缩入口会出现在演示态且命令不可达。演示态保持只展示不可点。
- 新命令 `compact`：§7.2:406「压缩上下文」；**非** CAS 全集成员；payload 无记录（推测 `{}`，【未取证】）；发普通 sendCommand。

#### 布局与文案

```
[模型▾] [思考·high▾]      上下文 ▓▓▓ 78% ← 可点（整段 44pt 热区）
        ↓ 点按
┌ confirmationDialog ───────────────────────┐
│ 压缩上下文？                                │
│ 让桌面端把历史对话压缩为摘要，释放上下文空间。    │
│ 压缩可能持续数十秒，期间请勿下发新指令。         │
│ [ 压缩 ]  [ 取消 ]      ← 主键普通按钮，非 destructive
└──────────────────────────────────────────┘
压缩中 hint：「压缩中…」（switchHint 通道）
完成 hint：「压缩完成 · 上下文已释放」3s
失败 hint：「桌面端拒绝（reasonCode）」3s
```

#### 状态矩阵

| 状态 | 表现 |
|---|---|
| 空闲 | 用量条常态；`usage.fraction` 低（如 <0.5）时仍可压（不拦） |
| 确认后进行中 | 本地 `isCompacting`；hint「压缩中…」不自动消失（覆盖 3s 清除） |
| 完成判定 | 命令回执 accepted 后等 `contextUsage` 回落（state.runtime 回流）→ 完成 hint；5s 内无回落也给完成 hint（回执已 accepted，诚实口径） |
| 失败/未送达 | controlFeedback 文案 hint |
| 非连接态 | 入口不可点（Button 仅连接态包裹，见插入点 gating——contextMeter 本身演示态仍渲染「上下文·演示」） |

#### 确认层

压缩属较轻操作（历史在桌面端归档、不丢数据，且回执失败即无副作用）→ confirmationDialog 保留、主键「压缩」**不用 destructive**（§0.1 语义）。

### 7B. 审批卡「稍后处理」

#### 插入点（本轮核对）

- 会话内 `ApprovalInteractionCard`（`ChatView.swift:338-485`）：批准/拒绝按钮行（:422-447）**下方**追加一行居中 `TextActionButton`「稍后处理」（tint `T.text3`，样式照抄 `ApprovalSheetView.swift:249-251` 同名动作）。
- 任务页 `ApprovalSheetView`「稍后处理」（:249-251 现为纯 dismiss）：同点替换为 snooze 下发；**前提**是任务页投影在手 interactionId（现 `RemoteTaskStore.resolvePermission` kinds:["permission"] 单条投影，`RemoteTaskStore.swift:195-207`，调研摘要）——若 interactionId 不在手，任务页维持纯 dismiss 并在代码注释注明（诚实降级）。

#### 交互流

点「稍后处理」→ `snoozeInteractionAutoResolution`（协议文档 **0 命中**，仅盘点报告 ：32/:82；payload【未取证】，最可能 `{interactionId}`）→ 成功：卡片收起 + hint「已稍后 · 桌面端稍后会再次提醒」；收起机制：优先依赖桌面 pendingInteractions 回流撤卡（投影刷新 `RemoteConversationStore.swift:912-916`，调研摘要），回流 >1s 未至则本地 `snoozedInteractionIds` Set 内存遮罩兜底（仅 UI 过滤，不改 store state 合并纪律）。

#### 状态矩阵

| 状态 | 表现 |
|---|---|
| 进行中 | 按钮「稍后中…」disabled（批准/拒绝钮同步 disabled，参照 deciding 机制 ：362/:432/:445） |
| 成功 | 卡片收起（RowAnimation） |
| 失败（rejected unknown command 等） | 卡片**保留** + hint「稍后失败 · <reason>」；按钮恢复。若 reasonCode 表明命令不存在 → 按钮降级为纯 dismiss（文案「关闭」） |
| 非连接态 | 卡片本就只在连接态投影渲染（`ChatView.swift:88-99` pendingInteractions 驱动） |

#### 确认层

「稍后」可逆（交互仍挂起、可再被提醒）→ 不设 dialog。

### 设计语言一致性（7 合并）

confirmationDialog = 重置卡先例（P2ExtrasViews.swift:64-81）；hint = switchHint；TextActionButton = ApprovalSheetView exitActions；按钮禁用联动 = deciding 态（:362-446）。

### 协议缺口

`snoozeInteractionAutoResolution` 全无记录——payload、回执、桌面重提醒行为均【未取证】，探针先行；`compact` payload 未取证但低风险（单命令、失败即回执）。

---

## §8 P3-8 一站式提交（commit + generateCommitMessage）

### 8.0 目标

文件页内完成：查看已暂存 → AI 生成提交信息 → 编辑 → 确认提交。**discardPaths / push 的 UI 本次不做**（§12）。

### 8.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 「提交」入口钮 | `DiffReviewView.swift:255-295` `branchRow` 内、「提交图谱」钮（:276-291）之前插主色胶囊钮「提交」 |
| CommitSheet | 文件页根 `.sheet(item:)` 挂载 |
| 已暂存数据源 | `DiffSource` 的 case 实名是 `workspaceStaged`（`DiffReviewView.swift:7-19`，无 `.staged`）；staged 段取数走 `store.diffFiles(sourceId: "staged")`（fetch :40-47）——**不是** `sessionDiffFiles`（那是 `session` 段数据源，:61）。sheet 内已暂存列表：`viewModel.select(.workspaceStaged, store:)` 或对 store 直接 `diffFiles(sourceId:"staged")` |
| 「提交 N」计数取数路径 | `viewModel.files` 只装当前段数据（:24 声明、:72 装载）——用户停留「未暂存」段时取不到 staged 计数。新增 `DiffViewModel.stagedCount: Int?` 独立状态：`load`(:65-74) 与每次 `decide`/`approveAll` 后（:90-98）并行 `let staged = await store.diffFiles(sourceId: "staged"); stagedCount = staged.count`（仅计数不装 files，不影响当前段渲染）；演示态恒 nil |
| 命令 | `git.generateCommitMessage` / `git.commit`：gate 已放行（`ReadOnlyGate.swift:73` 空黑名单 + :67-72 注释载明 web 对齐裁决，本轮核对）；payload 形状协议文档无记录【未取证】（参照 `callGitWrite` 的 `{workspacePath, paths}` 族形 `RemoteFileStore.swift:514-531`） |
| 完成后刷新 | `viewModel.reload(store:)`（:100-102）+ `loadGitSummary()`（:177-194）+ `stagedCount` 重取 |

### 8.2 布局与关键文案

```
┌ branchRow（改造后）──────────────────────────────────────┐
│ [⎇ main ↑2 ↓0] [提交图谱] [提交 3 ▸]                       │ ← 「提交 3」= stagedCount（§8.1 独立取数）；
└──────────────────────────────────────────────────────────┘   stagedCount==0/nil 时「提交」灰置

CommitSheet：
        ───────
  提交到 main                        取消
  ── 已暂存（3）──
  • Services/Login.swift      +42 −8
  • Views/Home.swift          +12 −31
  • README.md                 +4  −0
  ── 提交信息 ──
  [✨ AI 生成]  ← 生成中变「生成中…」+Spinner
  ┌────────────────────────────────────┐
  │ （提交信息编辑框，axis:.vertical，3 行起；       │
  │   AI 生成后预填，可改）                        │
  └────────────────────────────────────┘
  ┌────────────────────────────────────┐
  │              提交（主钮）                 │
  └────────────────────────────────────┘
        ↓ 点提交
┌ confirmationDialog ───────────────────────┐
│ 确认提交 3 个文件到 main？                   │
│ 提交将写入桌面端仓库历史。                     │
│ [ 提交 ](destructive)  [ 取消 ]             │
└──────────────────────────────────────────┘
```

### 8.3 交互流

1. 点「提交 N」→ sheet 列出 staged 文件（复用 `sessionDiffFiles`/diff 数据，路径 mono、+/- 着色 `T.add`/`T.del`，与 statsRow :304-320 同色）。
2. 点「✨ AI 生成」→ `git.generateCommitMessage {workspacePath, paths?}`【未取证】→ 预填编辑框（覆盖前若用户已手写，先弹 actionSheet 询问覆盖/追加——简化：仅在编辑框为空时自动预填，非空时按钮文案「重新生成」，点按弹确认「覆盖已编辑的信息？」）。
3. 编辑信息 → 「提交」→ confirmationDialog → `git.commit {workspacePath, message, paths?}`【未取证】。
4. 成功：震动 + toast「已提交 <短hash 或 ok>」+ dismiss + reload + gitSummary 刷新（ahead/behind 变化可见）。
5. 边界文案：`gitBranchText` 兜底「分支由桌面端管理」（:297-302）保留不变；sheet 标题在该兜底态用「提交到当前分支」。

### 8.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 入口空态 | `stagedCount == 0`（或 nil=未取到/演示态）→ 钮 disabled（T.bgInput 灰底）；sheet 不可达 |
| sheet 打开 staged 又为空 | EmptyStateView("没有已暂存的变更","先在文件列表批准文件，或使用「全部批准」。", cta:"返回") |
| 生成中 | 「AI 生成中…」+Spinner+disabled |
| 生成失败 | 错误行「生成失败 · 可手写提交信息」（橙），编辑框仍可用 |
| 提交中 | 主钮「提交中…」disabled；dialog 关闭 |
| 提交成功 | toast + dismiss + 列表与 gitSummary 刷新 |
| 提交失败 | sheet 错误行（rejected reasonCode，如身份未配置 git.identity 缺失）；信息保留可重试 |
| 演示态 | 入口不渲染（git 写面仅连接态） |

### 8.5 确认层

commit 写入仓库历史（可通过后续 commit 追正，但属重要写）→ confirmationDialog destructive 主键（盘点报告 ：53 裁决「discard/push 必须带确认」；commit 从严同款）。

### 8.6 设计语言一致性

入口钮 = branchRow 既有胶囊（:276-291）；确认层 = P2ExtrasViews.swift:64-81；sheet = §0.1 范式；刷新链 = DiffViewModel.decide 后 reload 同款（:90-98）；失败文案 = controlFeedback。

### 8.7 协议缺口

`git.commit`/`generateCommitMessage` 参数与回执零记录【未取证】；`git.getIdentity`（盘点报告 :52）可在提交前探测身份缺失给前置提示（本期可选）。探针后回写 §9。

---

## §9 P3-9 额度权益（getEntitlementSnapshot）

### 9.0 目标

额度页补套餐档位与权益列表（空态/失败态诚实）。

### 9.1 插入点（本轮核对）

| 动作 | 位置 |
|---|---|
| 权益子卡 | `P2ExtrasViews.swift:106-169` `codingPlanCard` 内、**usage 三分支全部结束之后**（:114-142 windows 分支 / :143-160 旧形态兜底分支 / :161-165 nil 分支）、`.card()`(:167) 之前插入——**不得**挂在「套餐档位」行（:119-123）之下：那落在 windows 分支内部，usage 为 nil/旧形态/无窗口时子块连同加载中/不可用两态都无处渲染。插在三分支后则三种形态下都有渲染位置 |
| 读面 | `usage-stats.getEntitlementSnapshot {preferredProviderId}`——协议文档与代码均无记录（盘点报告 :50「低——额度页补全」）；preferredProviderId 必须用注册表完整 id `"account:zai-individual-coding-plan"`（先例 `AppSession.swift:586`，调研摘要） |
| 读面出口 | 挂 `AppSession`（与 `fetchCodingPlanUsage` :574-724 同层），随 `refreshDesktopReadonlyInfo`（:411-422）刷新、断开清空（并入 `teardownRemoteStores` 清单，`AppSession.swift:905-911`——注意该函数现不清 `appUsageSnapshot` 的既有缺口，新字段务必一并清） |

### 9.2 布局与关键文案

```
│ Coding Plan 额度                    │
│ 5 小时窗口    ▓▓▓▓░ 72%  重置 09-30 │  ← quotaWindowRow :172-209 不动
│ 套餐档位 max                        │  ← :119-123 不动
│ ── 当前套餐权益 ──                   │  ← 新增子块（bgInput 圆角分隔）
│ ✓ 每 5 小时 600 次请求        已含   │  ← 权益行：icon+名称+值（mono）+StatusPill
│ ✓ 周窗口 2,000 requests      已含   │
│ ✓ 优先队列                    已含   │
│（读取失败：）
│ ⓘ 权益信息不可用 · 下拉刷新重试        │  ← 单行诚实提示，不占整页
```

### 9.3 交互流

1. 页面 `.task { await reload() }`（:62）链路内并行拉 entitlement 快照；下拉刷新（:63）同刷。
2. 回执宽容解析（多形态兜底键 `entitlements[]|benefits[]|features[]|items[]|顶层数组`——RemoteCapabilityListPage 先例 ：899-924 同法）；条目宽容取 `{name|title, value|limit, description}`。【全部未取证，仅存在性在案】
3. 无任何交互动作（纯展示）。

### 9.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 加载中 | 子块位置 `SpinnerView(size:14)` 单行 |
| 成功 | 权益行列表（≤8 行，多则「在桌面端查看全部」尾行） |
| 空（回执空数组/解析不出） | 子块整体不渲染（数据缺席不渲染空壳） |
| 失败/非连接 | 「权益信息不可用 · 下拉刷新重试」单行 text3 |
| 演示态 | 子块不渲染（连接态读面，无演示假数据——G-033 口径 SettingsView.swift:322-323） |

### 9.5 确认层

无（纯读）。

### 9.6 设计语言一致性

子块 = codingPlanCard 内既有分组行语言；StatusPill = badge 用法（RemoteCapabilityListPage :862-864）；宽容解析与诚实降级 = RemoteCapabilityListPage/PlaceholderPage 全套（:886-1089/:776-795）。

### 9.7 协议缺口

方法存在性仅盘点报告一行；参数/回执零取证。探针后按 §4.4 回写 §9 条目（usage-stats 频道）。

---

## §10 P3-10 多工作区切换器

### 10.0 目标

连接态在多个桌面工作区间切换；切换中/失败态与影响提示完备。

### 10.1 插入点（本轮核对 + 调研摘要）

| 动作 | 位置 |
|---|---|
| 入口 | `FileTreeView.swift:16-21` `headerPath`（现只读文本「工作区 <path>」）改为 Menu 胶囊：label 同款文案 + 上下 chevron；菜单列出 `info.workspaces` 全清单（active 首位——中继连接期已采集，`ZCodeServerConnection.swift:505-536`，调研摘要；经 `setAllWorkspaces` 回写 store，`AppSession.swift:893-897`，本轮核对） |
| 切换请求 | `workspace-reconnect-request/response`：协议文档仅 ：410 一行注脚（同族传输层消息、relay WS 原生 zcode_type、requestId 关联），参数形状【未取证】。**响应消费不加新 case**：`workspace-reconnect-response` 已在 `handleDataPayload` 的 resolveAppRequest case 组内（`RelayTransport.swift:385-388`，本轮核对），带 requestId 的响应经现有 waiter 配对路径（`resolveAppRequest` :391-398）即可消费——直接用 `requestAppPayload`（:427-454）发请求等响应即可；若另加 case 截帧反而破坏请求-响应配对 |
| 推送消费（前置改造，仅限无 requestId 的主动推送） | `workspace-list-updated` 是无请求推送、永远无 waiter，现经 `resolveAppRequest` 落入「app 响应无匹配 requestId（忽略）」被丢弃（`RelayTransport.swift:391-394`，本轮核对）——在 `handleDataPayload` switch 内为它**单独**拆 case 上抛回调（从 ：385-388 组里移出；现成回调样板 `onConnectionDropped`/closedHandler，调研摘要）。**注意与上行区分**：reconnect-response 留在 resolveAppRequest 组不动 |
| 切换落地（App 层换绑，两处前置改造） | 重建三 Store 只是第一步（`assembleRemoteStores` `AppSession.swift:886-903`，本轮核对）——**App 层环境值不会自动换绑**：`@State conversationStore/taskStore/fileStore` 仅在 `syncStoresWithSession()` 里重新赋值（`ZCodeMobileApp.swift:101-117`），而它仅由 `.task(id: session.mode)`（:45）触发；`Mode: Equatable` 只含 ServerConfig（`AppSession.swift:137-143`，本轮核对），同 `.connected(config)` 内换 workspace 不改变 mode → task 不重跑 → 环境值仍指旧 Store → 各页 `.task(id: ObjectIdentifier(store))`（`DiffReviewView.swift:156`、`TaskBoardView.swift:110`）不会重拉。**方案**：`AppSession` 新增 `storeEpoch: Int` 计数器（`assembleRemoteStores` 成功与 `teardownRemoteStores` 时 +1），挂载点改为 `.task(id: session.storeEpoch)`（`ZCodeMobileApp.swift:45`）——epoch 变化即重跑 `syncStoresWithSession()` 完成环境值换绑，Store 新实例再触发各页重拉 |
| connection.workspace 更新通路 | `ZCodeServerConnection.workspace` 为 `private(set)` 且连接期一次写入（声明 `ZCodeServerConnection.swift:243`、中继写入 ：550，本轮核对），运行时只读——`FileTreeView.headerPath`（:16-21）与 `loadGitSummary`（`DiffReviewView.swift:180-181`）直读该字段，不更新则切换后头部分支行仍显旧工作区。**方案**：connection 新增主线程方法 `updateWorkspace(_:)`（reconnect 响应成功、以新 workspaceKey 重开桥后调用，同步刷新 `serverInfo.workspaces` active 序），或以新 workspace 走最小重建路径重新赋值 `workspace`——实施时按 reconnect 响应实际载荷裁决，但该字段必须在切换成功路径上被显式更新 |

### 10.2 布局与关键文案

```
文件树头（改造后）：
┌──────────────────────────────────────────┐
│ [⎇ 工作区 ~/work/zcode ▾]                 │ ← 原 headerPath 变 Menu
└──────────────────────────────────────────┘
        ↓ 点开
┌ Menu ──────────────────────┐
│ ✓ ~/work/zcode   （当前）    │
│   ~/work/zcode-api          │
│   ~/work/docs               │
│ ─────────────────────────── │
│ ⓘ 切换将重建会话/文件/任务面板   │
└────────────────────────────┘
        ↓ 选中非当前项
┌ confirmationDialog ───────────────────────┐
│ 切换到 ~/work/zcode-api？                   │
│ 将断开当前工作区的会话与文件面板并重连；          │
│ 桌面端连接保持，进行中的桌面任务不受影响。        │
│ [ 切换 ]  [ 取消 ]    ← 主键普通按钮（非破坏，§0.1）
└──────────────────────────────────────────┘
切换中：Menu label 变「切换中…」+ SpinnerView；各页经 Store 重绑自然进入 CenterLoadingView
成功 toast：「已切换到 ~/work/zcode-api」
失败 toast：「切换失败 · 已保持当前工作区」
```

### 10.3 交互流

1. 点选目标工作区 → confirmationDialog 确认 → 经 `requestAppPayload`（`RelayTransport.swift:427-454`，requestId waiter 现成路径）发 `workspace-reconnect-request {workspaceKey?}`【未取证；键名候选 workspacePath/workspaceKey，探针定】→ 响应经既有 `resolveAppRequest` 配对回来（§10.1：不加 case）。
2. 响应成功 → connection 更新 workspace（§10.1 通路）→ 重跑 `assembleRemoteStores` + `storeEpoch += 1` → `.task(id: storeEpoch)` 重跑 `syncStoresWithSession()` 完成环境值换绑 → 各页经 Store 新实例自动 loading→新数据；`workspace-list-updated` 推送到达时刷新 Menu 清单（新 case 上抛后）。
3. 超时/失败 → 保持原 Store 与原 workspace 不动 + 失败 toast（1.2s 退避重试一次，中继瞬断纪律 AGENTS §5.8）。

### 10.4 状态矩阵

| 状态 | 表现 |
|---|---|
| 单工作区 | Menu 不渲染 chevron（不可点，保持只读 headerPath） |
| 清单为空/未连接 | 不渲染 Menu（演示态保持现 headerPath 文案） |
| 切换中 | Menu disabled+「切换中…」；重复确认被拦 |
| 成功 | toast + headerPath 更新 + 会话/任务/文件三面板经 Store 重绑刷新 |
| 失败 | toast + 原态保持 + Menu 恢复可点 |
| 桌面推送清单变化 | Menu 项增删（workspace-list-updated 消费后） |

### 10.5 确认层

切换影响三面板数据面（会话列表/文件/任务全部换 workspace 视角）→ confirmationDialog 保留 + 影响说明（文案见 10.2）；主键「切换」为**普通按钮**——切换本身不丢数据且文案明示「进行中的桌面任务不受影响」，不属 destructive（§0.1 语义）。

### 10.6 设计语言一致性

Menu 胶囊 = executionTargetMenu（ChatView.swift:812-853）；✓ 当前项 = modelMenu（:908-913）；dialog = §0.1；页面级 loading 沿用各页既有 CenterLoadingView（Store 重绑自动态，无需新 UI）。

### 10.7 协议缺口（11 项中最大）

`workspace-reconnect-request/response` 与 `workspace-list-updated` 的参数/回执/触发时机**全部未取证**（协议文档仅一行注脚 ：410；transport 现状丢弃推送 `RelayTransport.swift:396-403`）。实施顺序：①transport 双 case 上抛（纯客户端改造可先行）；②探针取证 reconnect 双向帧；③按 §4.4 回写 §9/§12；④接 UI。

---

## §11 P3-11 检查点 / 插件安装卸载 / 桌面设置同步（三合一）

### 11A. 检查点（git-checkpoint.*）

| 项 | 设计 |
|---|---|
| 入口 | `DiffReviewView.swift:255-295` `branchRow`「提交图谱」钮后加「检查点」胶囊钮（text2 胶囊，同款样式）→ 弹 `CheckpointSheet` |
| 前置 gate | `git-checkpoint` 在频道黑名单表（`ReadOnlyGate.swift:108-109` 注释「仓库快照写（git-checkpoint）」，:114 声明行，本轮核对），黑名单词表**逐字**为 `createCheckpoint` / `restoreBetweenCheckpoints` / `deleteCheckpoint`（:125，本轮核对）——放行裁决必须按此逐字词表（「恢复」对应的真实命令名是 `restoreBetweenCheckpoints`，盘点报告 :55 的「restore…」是缩写），**接入前先裁决放行并按 AGENTS §4.2 回写协议文档 §8**；回执形状协议文档零记录【未取证】 |
| Sheet 布局 | 头部「检查点」+「＋ 创建检查点」主钮；列表行：时间（mono）+ 说明 + 「恢复」文本钮（TextActionButton） |
| 创建 | 点创建 → 可选说明 alert（带 TextField，追问先例 ApprovalSheetView.swift:254-262）→ 命令 → 成功 toast「检查点已创建」+ 列表头部插入；失败行内错误 |
| 恢复（破坏性·硬要求带确认） | 点「恢复」→ confirmationDialog「恢复到 <时间> 的检查点？」/ message「工作区文件将回退到该时刻，之后的改动会丢失（可通过再次恢复撤销）。」/ 主键「恢复」role:.destructive → `git-checkpoint.restoreBetweenCheckpoints`（命令名逐字见插入点行；参数/回执【未取证】）→ 成功：dismiss sheet + diff reload + gitSummary 刷新 + toast「已恢复」；失败：行内错误 |
| 状态 | 加载 CenterLoadingView / 空 EmptyStateView("还没有检查点","创建检查点后可随时把工作区回退到该时刻。") / 失败诚实占位（RemoteCapabilityPlaceholderPage 口径） / 恢复中行内 Spinner |
| 一致性 | sheet=§0.1；列表行=GenericListPage 行（SettingsView.swift:806-844，调研摘要）；确认=§0.1 destructive |

### 11B. 插件安装/卸载

| 项 | 设计 |
|---|---|
| 入口 | 设置页「插件商店」（`SettingsView.swift:338-339` → `RemoteCapabilityListPage(.plugins)` destination :410-422，本轮核对）；数据面 listPlugins（`P2ExtrasViews.swift:973-994`） |
| 前置 gate | `installPlugin`/`uninstallPlugin`/`updatePlugin` 等 12 族在 `zcodeAgentDirectWriteCommands` 黑名单（`ReadOnlyGate.swift:94-98`，本轮核对）——**接 UI 前必须先裁决放行卸载族并回写 §8** |
| UI | 行卡（:845-869）加长按 `contextMenu`：「卸载插件」（role:.destructive）——已安装插件 badge「已启用/已停用」保留 |
| 卸载确认（硬要求） | confirmationDialog「卸载插件 <name>？」/ message「其提供的工具将从桌面端 Agent 移除；可重新安装恢复。」/ 主键「卸载」role:.destructive → `uninstallPlugin {name|pluginName}`【未取证】→ 成功：行卡刷新（badge 消失或行移除）+ toast；失败：错误 toast |
| 安装 | 「从市场安装」入口本期**不做**（依赖 getPluginsOverview/addPluginMarketplace 族形状零取证 + 市场浏览面，盘点报告 :45）；页尾注一行「市场安装将在后续版本提供」text3 |
| 状态 | 列表三态维持现状（:837-884）；卸载中 contextMenu 关闭+行内 Spinner；成功刷新；失败 toast |
| 一致性 | contextMenu=ConversationListView.swift:443-497；destructive=§0.1；toast=hint 通道 |

### 11C. 桌面设置读取+修改（settingService）

| 项 | 设计 |
|---|---|
| 入口 | `SettingsView.swift:291-351` `settingGroups` 的「Agent 能力」组（:331）内加 navRow「桌面设置」（icon `desktopcomputer`，subtitle「读取与修改桌面端设置」，id `12-row-desktop-settings`）+ destination 分支（:354-426 switch 内）→ `DesktopSettingsPage` |
| 协议前置（含频道名二义裁决） | ①「settingService」是盘点报告 ：54 的**服务属性名**；gate 黑名单里的频道名是 **`setting`**（`ReadOnlyGate.swift:116` `"setting": ["update", "updateDataBaseDir"]`，本轮核对）。两者不是同一字符串——实际 RPC 频道名必须探针逐字取证：若为 `setting`，写命令 `update` 落在现拦截面内，放行需改 gate + 回写 §8；若为 `settingService`，现 gate 对它完全不拦（default 分支黑名单外频道放行，`ReadOnlyGate.swift:236-245`，本轮核对）——**接 UI 前必须先把该频道写面词表补进 gate 黑名单**，否则读面顺手、写面静默绕过拦截面且无人改 gate。②`settingService` 协议文档零记录；「gate 现拦 settings 族」的盘点表述与代码事实（只拦 `setting.update/updateDataBaseDir`）有出入，以代码为准。 |
| 读面 | 频道.方法名以探针逐字为准（候选 `setting.get` / `settingService.get`），参数形状【未取证】→ 宽容解析键值清单 → 行渲染：boolean→toggleRow（SettingsView.swift:466-485 样式，调研摘要）、string/number→navRow+点击弹编辑 alert（带 TextField 先例）、只读项→navRow 无箭头 |
| 写面（破坏性·带确认） | 修改任一项 → confirmationDialog「修改桌面设置 <项名>？」/ message「将立即作用于桌面端，影响所有正在运行的会话。」/ 主键「修改」role:.destructive → 写命令以探针逐字为准（候选 `setting.update` / `settingService.update`，频道名二义裁决见插入点行①）【未取证】→ 成功 toast「已修改」；失败：行值回弹 + 错误行 |
| 状态 | 加载 CenterLoadingView / 成功分组列表 / 失败 RemoteCapabilityPlaceholderPage 口径诚实占位 / 修改中该行右侧 SpinnerView / 成功 toast / 演示态整行 navRow subtitle「连接桌面端后可读写」不可进 |
| 一致性 | 页面结构=SettingsView group/navRow/toggleRow 全套；占位=RemoteCapabilityPlaceholderPage；确认=§0.1 |

---

## §12 硬边界（本次不做，一句话存档）

1. **`discardPaths`（丢弃工作区改动）与 `push`（推远端）的 UI 本次不做**——gate 已放行（`ReadOnlyGate.swift:73` 空黑名单 + :71-72 注释载明「接入 UI 时必须带确认弹层」），未来接入时必须走 §0.1 destructive confirmationDialog，且 push 文案需含远端分支名。
2. **文件内容直写类操作永远禁止**：file 频道默认拒绝（白名单外全拦，`ReadOnlyGate.swift:78-80`）与 v4 `applyFileRewind` 拦截（:45-47）不变；本设计稿所有功能均为「客户端发命令、桌面代执行」。

---

## §13 实施后必须回写协议文档的清单（AGENTS §4.2 触发器汇总）

| 功能 | 新增/变更 | 回写动作 |
|---|---|---|
| P1-1 | attachmentBegin/Chunk/Commit/AbortV4 + sendText 附件字段 | §9 新条目×2（附件事务族、sendText 参数表扩展）；§12 移出「未使用」注记 |
| P1-2 | switchCollaborationMode / setFollowupMode | §9 新条目×2；§7.2 词表补 payload 形状 |
| P1-3 | setAssistantFeedback / editUserQuery | §9 新条目×2（row-target + CAS 双字段实证） |
| P2-4 | startSavedWorkflow（+getSavedWorkflow 若存在） | §9 新条目；listSavedWorkflows 条目补 schema 字段实证 |
| P2-5 | sendGoalCommand | §9 新条目；CAS 属性裁决写入 §7.2 |
| P2-6 | listGroupedTaskViewStructure | §9 新条目 |
| P2-7 | compact / snoozeInteractionAutoResolution | §9 新条目×2（snooze 目前全无记录） |
| P3-8 | git.commit / generateCommitMessage | §9 新条目×2（git 频道写族首两个 UI 化） |
| P3-9 | usage-stats.getEntitlementSnapshot | §9 新条目 |
| P3-10 | workspace-reconnect-request/response、workspace-list-updated 消费 | §9/§12 新条目 + 传输层消息表 |
| P3-11 | git-checkpoint.createCheckpoint/restoreBetweenCheckpoints/deleteCheckpoint、uninstallPlugin、setting 频道族（频道名与词表以探针逐字为准，见 §11C） | **先改 ReadOnlyGate 黑名单再回写 §8**；§9 新条目若干 |

另：本设计稿引用的代码行号为 2026-10-06 快照——实施移动代码时按 AGENTS §4.3 同步更新指位（已知漂移先例：协议文档 §9.1.8 attachmentReadV4 调用点写 2309-2383、实际已漂至 2888-2959，调研摘要核对）。

---

## 附：实施顺序建议

1. **零协议缺口先行**：P2-5（编辑语义复用现成 CAS 链，payload 探针成本低）、P2-6（读面失败即回退，风险最低）、P3-9（纯只读）。
2. **单命令探针批**：P1-2、P2-7（compact/snooze）、P3-8——每个一条诊断探针即可定形。
3. **UI 链路较长批**：P1-3（含 rebuildMessages 游标前置改造）、P2-4（含 schema 探测）、P3-11。
4. **transport 级批**：P1-1（附件事务族）、P3-10（reconnect + 推送消费）——放最后，探针与改造面最大。

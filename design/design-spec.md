# 朱批 App 重设计规格（2026-11-25）

> 状态：**实施依据**（iOS 批次随本轮落地；Android 对齐为下阶段批次）。
> 视觉与主题令牌的**唯一权威仍是 `brand/seal/`**（README 四色/用印规则 + motion-spec）；本文只做信息架构、交互与令牌映射，不另立色板。
> 证据基线：上游 `ZCode/`（fec9759）+ `/tmp/zcode-upstream`（a4b44451 = v4.0.0，用户桌面实跑版本）双仓取证；App 现状 = iOS 66 文件全量盘点 + Android 全量盘点（2026-11-24~25）。
> 配套：`app-design.html`（原型）、`web-gap-list.md`（功能缺口）、`cleanup-list.md`（清理清单）。

---

## 0. 裁决记录（2026-11-24 用户四答 + 2026-11-25 实施指令）

| # | 裁决 |
|---|---|
| R1 | 新建会话入口**收敛为一个**：会话列表页右上角 ✎。空态 CTA 保留（仅列表为空时出现，同一 sheet）。会话内 Fork / 在新会话询问是消息动作，不算入口 |
| R2 | 底部 **三 tab：会话 / 待批 / 设置**。任务 tab（状态看板）与文件 tab（全局 Diff）撤除 |
| R3 | 功能面以 **iOS 为基准**，Android 下一轮对齐；小程序不纳入 |
| R4 | 交付前**完整测试**；真实桌面验证一律用 **ccgithub / deepseek-v4.1-flash**，只在新建测试会话进行、验证后归档 |
| R5 | 重连等场景**不向普通用户暴露** token / 地址 / 日志 / RPC 名；技术细节全部收进开发者模式 |

---

## 1. 信息架构（两端同构）

```
App 根
├─ 未配对根页（连接引导，现状保留；按 R5 去技术化）
└─ 三 Tab（独立 NavigationStack，push 时隐藏 tab 栏——现状机制不变）
   ├─ 会话（主页）
   │  ├─ 顶栏：设备行（◉ 电脑名 ▾ → 电脑与账号）+ ✎ 新建（唯一入口）
   │  ├─ 筛选视图菜单（按项目/按分组/最近更新/按未读，web 同名同序）
   │  ├─ 搜索 + 已置顶/项目分组/行（状态槽：待批朱点 > 失败红点 > 未读蓝点 > 运行中转圈）
   │  └─ 行动作（长按）：重命名/置顶/归档/标记未读/派生会话/移入分组/清理失败任务（现状保留）
   ├─ 待批（新，跨会话审批收件箱）
   │  ├─ 列表行 = sessions-index 摘要（会话标题 + 待批类型胶囊 + 时间）
   │  └─ 有待批的会话（≤8，按最近活跃）展开内联决议卡（与 会话页 同一套卡组件/决议链路/落印）
   └─ 设置
      ├─ 账号与电脑（账号行 / 电脑行 / 添加电脑）
      ├─ 偏好（外观 / 语言 / 通知 / 显示思考过程）
      ├─ 审批行为（投递模式 · 开发者模式可见技术说明）
      ├─ 用量 / 项目 / 归档
      ├─ 桌面能力（个性化/技能/MCP/子代理/插件/自动化/钩子/桌面设置 —— 现状八项一页收拢）
      └─ 关于 +（开发者模式分组：诊断与日志 / 开发者开关 / 访问令牌 / 服务器详情）

会话页（push，全高）
├─ 顶栏：‹ 返回 · 标题+运行态 · ⋯ 菜单（重命名/归档/标记未读/执行输出/文件）
├─ 状态胶囊行（composer 上方，v4.0.0 同位）：目标 / 待办 N/M / 更改 +N−N / 活动 —— 有数据才出现
├─ 消息流（回合分组 + 工具卡 + 待办卡 + 决议卡 + 队列条）
├─ Composer：[+] 附件 · 模式 chip · 模型 chip（二级选择器）· 输入 · 发送/停止
└─ Sheets：更改（会话级 Diff + 批准/拒绝/全部批准）/ 文件（树+预览）/ 产物 / 白板 / 附件 / 划词询问 / 模型选择器 / 面板（目标/计划/工作流/后台/子代理/Hook）
```

与 web 手机壳的刻意差异（延续既有差异台账口径，实施时逐条登记 APP-DIFF）：
- web 无 tab 栏（home/chat 两页）；App 用「会话/待批/设置」三 tab 承载——**待批 tab 是产品裁决新增**（web 的待批只存在于列表行徽标）。
- web 文件/Git 是右侧抽屉 pane；iOS 延续 sheet 承载（D001 已登记），入口从全局 tab 移入会话页。
- web 新建会话 = 直接进草稿态会话页（首条发送才 createSession）；iOS 保留 `NewConversationSheet` 简化版承接（见 §4.6）。

---

## 2. 设计令牌（统一映射，iOS `T.*` 现值 → 目标）

颜色不新增值，全部对齐 `brand/seal/README.md` 四色 + 语义色（web 同源）。结构性原则：**朱只落在要你落笔的地方**——待批标记、批准/发送主 CTA、品牌位；其余纸/墨/钤灰。同屏至多一处朱磦实底（决议卡在场时发送钮降为墨描边）。

| 令牌 | 值/规则 | iOS 落点 |
|---|---|---|
| 纸 bg | `#F7f3ec` / 深 `#17181A` | `T.bg`（现值已对） |
| 卡 bgCard | = 纸（层次靠 12% 墨描边） | `T.bgCard`（已对） |
| 浮层 bgElevated | `#F7F1E4` / `#232428` | `T.bgElevated`（已对） |
| 输入 bgInput | `#E8DFCB` / `#222326` | `T.bgInput`（已对） |
| 描边 border / borderStrong | 墨 12% / 18% | 已对 |
| 正文 text / text2 / text3 | 墨 100/62/40（深 30） | 已对 |
| 朱磦 seal / sealPress / onSeal | `#D2402E` / `#B03020` / 纸 | 已对；**用途收窄**（见上） |
| 状态色 blue/orange/red/green + add/del | web 同源，保留，状态必须「色+文字/字形」双编码 | 已对 |
| **删除**：`violet/violetDim`、`gradAvatar/gradBubble/gradUserCard`、`shadowCard/shadowFab` | BiuZ 时代残留；印是平的（brand Do/Don't：不加渐变、投影） | 本轮删 |
| 字阶（文本 8 档） | 10/11/12/13/14/15/17/22；**禁半档** | iOS 现有 19 种字号 → 收敛；新增 `T.Font` 语义档（display22/title17/subtitle15/body14/caption12/micro11/mono10）逐处替换 |
| 字形阶 | 16/20/26（图标 emoji/glyph） | 同 Android `BiuZGlyph` 口径 |
| 间距 | 4/8/12/16/24/32（`sp1..sp8` 已对，`sp8` 启用） | 清理游离 padding |
| 圆角 | 8/12/16/胶囊 | `rS/rM/rL/rPill` 已对；清理字面量 6/9/10/13/18/24 |
| alpha | 5 档 .14/.35/.50/.70/.85 | 对齐 Android `BiuZAlpha`；清理裸 opacity 魔数 |
| 触控 | 可点元素 ≥44pt；主按钮 48pt | 现状基本对，逐屏核对 |
| 动效 | 按压实 90/140ms `scale 0.96`（`PressableButtonStyle` 已有）；落印 460ms（StampView 已有）；全品牌唯一缓动 `cubic-bezier(.22,.68,.32,1)`；触觉只给主 CTA 与落印 | 已有基建，补一致性 |

字体：品牌字/决议卡标题/空态标题 = 宋体（Songti SC Black / system serif）；正文 PingFang 系统栈；协议串/命令/路径 = `T.mono`。

---

## 3. 关键交互规格

### 3.1 待批 tab（新增）
- **数据两层**：
  1. 摘要层（廉价、常驻）：`subscribeSessionsIndexV4` 快照/增量的 `pendingInteraction {interactionId, kind: permission|userInput, toolName?, autoResolution?}` 与 `pendingInteractionSummary {permissionCount, userInputCount}`（上游 `sessions-index.ts:12-49`【实证·上游仓】）。行 = 标题 + 胶囊（「等待确认」permission / 「等待回答」userInput，web `taskList.permissionTag/userInputTag` 同义）+ 相对时间。投影进 `Conversation.pendingPermissionCount/pendingUserInputCount`（新字段）。
  2. 卡片层（按需）：有待批的会话按最近活跃取前 **8** 个，进入 tab 时 `store.messages(in:)` 触发订阅 + 观察 `pendingInteractions`，内联渲染与 会话页 完全相同的卡组件（权限/计划/提问/Hook 审核）。决议走同一 `resolveInteractionRaw` 链路 + ACK 权威成功落印（StampView 复用）；离开 tab 或决议完成 → `closeConversation`（keep-warm 30s，见 §3.5）。
- 订阅未就绪/超 8 个：摘要行 +「在会话中处理」；**不做无卡的假可供性**。
- tab 角标 = Σ pending counts（朱磦点）。
- 已知边界（真机取证项）：sessions-index 是否跨工作区携带待批摘要未证——跨区会话的待批可能缺席，取证结论回填本节与协议文档。

### 3.2 会话行状态槽（对齐 web `deriveTaskLeadingIndicator`）
优先级互斥：**待批（朱点+胶囊）> 失败红点 > 未读蓝点 > 运行中转圈**；无状态留空槽不画占位。排序列层用 `isActive`（活性 ∪ 后台工作），转圈只认 `isRunning`（协议文档 §10.3 既有口径，Android 已落，iOS 对齐）。

### 3.3 模型选择器（二级 sheet，替换 ComposerOptionSheet 的 model/thought 两档）
- **一级**：搜索框（本地过滤）+ 最近使用（≤3，UserDefaults 按连接存行键）+ 分组列表：套餐分组（getView planGroups 序）+ **其他**（非套餐 provider 一律归「其他」，组名取 `providerName ?? providerId`——Android 已修的同构缺陷，iOS 现状自定义 provider 模型会整组消失，必修）+ 页脚「管理模型」→ 设置.模型。
- **二级**：该组模型单选行（含徽标/视觉标记，web 同位）+ 底部「思考强度」分段控件（当前模型 thoughtLevels；单档时静态标签）。
- 选择即发：会话内走既有 `switchModel`（乐观落 chip + 3s 护栏 + 常驻错误行，v1.37 口径不变）；新建 sheet 内走既有 handoff（`NewSessionHandoff` 模型选择随首条 sendText 下发）。
- a11y：一级 `05-model-sheet` / 行 `05-model-row-<组|模型>` / 搜索 `05-model-search`；二级 `05-model-group-sheet` / 思考 `05-thought-seg-<level>`。旧 `05-chip-model/05-chip-thought` 触发 chip 保留。

### 3.4 待办（一个名字一个数据源）
- 权威源 = `state.plan.items`（`{id, content, status: pending|inProgress|completed}`，v4.0.0 `planStateSchema`【实证·上游仓 snapshot.ts:504-507】）；旧键 `state.todos / todoGroups.todos` 保留兜底。
- 呈现两处，同一个模型：
  1. **状态胶囊**「待办 N/M」（composer 上方胶囊行，点击展开面板列表；>6 条折叠为当前项附近 3 条窗口 + 前后折叠组——web `getStatusPanelTodoFocusWindow` 同构，`ConversationStatusPanel.tsx:629-670`）。
  2. **流内待办卡**：最近一次 `TodoWrite/TodoRead`（正则 `/(^|[_\s-])(todo[_\s-]*(read|write)|update[_\s-]*plan)($|[_\s-])/i`，上游 `tool-plan-adapter.ts:3-4`）工具行渲染为待办卡（完成划线/进行中▸/待办○ + N/M 头），不再裸 JSON；`state.plan` 胶囊与卡片同源不双计。
- 术语统一：「待办」（退役「任务拆解」「计划」两个旧名在待办语境的混用；ExitPlanMode 的计划预览卡不受影响）。

### 3.5 会话缓存（keep-warm，两端同构目标）
- 打开 = 订阅（幂等）+ 内存快照即现；关闭（VM deinit / 待批 tab 收起）= **不立即退订**：置 30s keep-warm 定时器；窗口内重开 = 取消定时器 + 缓存即现（零 RPC）。
- 到期退订：真 `unsubscribeConversationV4` + 清水位/revision/订阅结构，**保留 rows/messages/snapshotState**；重开 = 重订阅 + 快照权威替换（Q 批次 stale-guard 已在位）+ 水位后增量，不重拉历史（历史仅 rows 空时拉）。
- LRU 上限 12 个会话的行缓存，超出淘汰最久未访问者（防无界增长）。
- 断线重连：iOS 已有 exportCaches/adoptCaches 保留；Android 缺 adopt（缺口清单登记）。

### 3.6 新建会话（简化后的 sheet）
- 入口唯一（列表 ✎ / 空态 CTA）。sheet 保留：输入框 + 项目 chip + 模型/思考（走 §3.3 选择器）+ 开始按钮；撤除：执行器/云端沙盒区、仓库/语音 chips（demo-only 遗留，进清理清单）。
- 交互继承 web「草稿优先」的精神但保留 sheet 形态（iOS 差异登记）：空标题 + 已选模型 → draft 创建 + `NewSessionHandoff` 交接（Android 已同构，iOS 现状已修）。

### 3.7 连接/重连（R5，对齐 Android D018 已裁决结构）
- 横幅：标题人话 + 副行**只留电脑名**（删 `host:port` 拼接）。
- L2 连接中：五步改四段人话步骤；步骤 meta / connect.log 终端 / `GET /api/server-info` / `--no-token` 字样 → 开发者模式才显示。
- L3 失败：一行结论 + 一句话说明 + 目标电脑（名字，不含地址）；`l3-err-code` / 排查清单 / 令牌字样 → 开发者模式。
- 设置「访问令牌」行 → 开发者模式分组；服务器详情保留但入口 dev-gate。
- 诊断导出页修复 P0 疑似泄露（`session.mode` 插值带出 token——先复现确认再修，见 cleanup-list）。

---

## 4. 实施批次（iOS 本轮 / Android 下阶段）

| 批 | iOS 内容 | 关键落点 |
|---|---|---|
| 1 | 路由 4→3 tab；`TabItemModel` 三项；待批页骨架（摘要列表 + 角标）；单一新建入口（删列表「新建任务」行与 FAB） | `AppRouter.swift` / `AppEnvironment.swift` / `RootView.swift` / `ConversationListView.swift` / `TaskBoardView.swift` |
| 2 | 待批收件箱数据链：Conversation 增 pending 计数投影；`ApprovalsModel` + 决议卡协议化复用 + 落印 | `RemoteConversationStore.swift` / `Models.swift` / 新 `Features/Approvals/` |
| 3 | 更改 sheet（会话级 Diff + 三写面）+ 文件树/预览挂会话；拆任务 tab（TaskOutput 挂 ⋯ 菜单）；`showsDiffActionBar`/`DiffActionBar` 撤除 | `ChatView.swift` / `DiffReviewView.swift` / `RootView.swift` |
| 4 | 二级模型选择器 `ModelPickerSheet`（含 others 回退修复 + 搜索 + 最近） | 新 `Features/Conversations/ModelPickerSheet.swift`；`ChatView`/`NewConversationSheet` 接线 |
| 5 | 待办统一（`ToolPlanAdapter` 移植 + plan 优先投影 + 胶囊/卡）+ keep-warm/LRU | `RemoteConversationStore.swift` / `MessageViews.swift` / 新 `ToolPlanAdapter.swift` |
| 6 | 连接/重连去技术化 + 令牌层清理（删紫/渐变/阴影 + 字阶收敛） | `ConnectingView.swift` / `ConnectFlowView.swift` / `SettingsView.swift` / `Theme.swift` |
| A-D（下阶段，Android） | 同 IA 三 tab / 待批收件箱 / 二级模型选择 / keep-warm+adoptCaches / 待办统一 / loadOlder 接 UI / en 缺译 30 键补齐 | Android 侧盘点证据已在 cleanup/gap 清单固化 |

---

## 5. 验收口径（本轮门槛）
1. iOS 单测全绿（基线 215 + 新增待办解析/模型分组/keep-warm）；构建 `MTT_BUILD_FORCE=1` 显式判定。
2. E2E：FeatureCompletion / Login / RelayLink / Language / LayoutAudit 全绿；Matrix 中受 tab 拆除影响用例改写，存量失败保持基线不误报。
3. 模拟器深链截图目检：三 tab / 待批（替身注水）/ 模型二级 / 待办卡 / 会话更改 sheet。
4. 真实桌面（在线窗）：新建测试会话携 ccgithub/deepseek-v4.1-flash 发一条消息 → ACK accepted + 芯片正确 → 归档测试会话。
5. `methodology_checks.py` 全 PASS；AGENTS/PARITY_BACKLOG 登记完成。

# 设计走查报告 — BiuZ（zcode_mobile iOS 遥控台）

**走查目标**：iOS App 全部 UI（4 Tab + 连接/登录/分享流程），对照 RPC 接口功能（`docs/协议接口文档.md` v1.10 + `Stores/Remote/*` 调用面）
**走查时间**：2026-10-06
**走查模式**：自动走查（Mode A，读取 SwiftUI 源码）+ 接口功能覆盖定向核对（用户指定关注点）
**上下文**：未读到 Brief / Stories / Journey，跳过「与 Brief 一致性」类别

## 总览

| 严重度 | 数量 |
| --- | --- |
| 🔴 Blocker | 0 |
| 🟠 Major | 4 |
| 🟡 Minor | 8 |
| ✅ Pass（无发现的类别） | 3（链路通畅性、视觉层级、与 Brief 一致性[跳过]） |

## 一、接口功能覆盖核对（核心结论：覆盖面合理，纪律性好）

### 已接且设计合理（接口 → UI 映射完整）

| 接口能力族 | UI 承载 | 评价 |
| --- | --- | --- |
| 会话列表/搜索/置顶/归档/改名/分组/派生/跨工作区（bootstrap.tasks） | ConversationListView | ✅ 完整，滑块+长按双通道 |
| 消息流（分页/停止/重试/编辑重发/👍👎/compact/提问卡快捷回复） | ChatView | ✅ 完整，发送失败有持久错误行+重试 |
| 审批六族（permission/question/elicitation/plan_approval/userInput/workspaceHookReview） | 常驻 composer 上方卡片 + 任务看板 ApprovalSheetView 双入口 | ✅ 2026-10-06 修复后常驻不滚动顶走，重连 resync 补齐 |
| 队列五件（全部 CAS + stale 重试） | QueueBarView | ✅ 完整 |
| 模型/思考档/协作模式/投递模式 | Composer chips + state.modelSelection 三路同步 | ✅ 以服务端 state 为权威源，方向正确 |
| 额度（三元组匹配分窗）/OAuth 只读 | 设置用户卡 + UsageStatsView | ✅ 0–1 小数口径已修正 |
| workflow 面板/目标/后台任务/子代理 | SessionPanelsView 五面板单开 | ✅ 取消/设置取活 run，仲裁口径正确 |
| 文件树/预览/分页/二进制/搜索 + diff 审查 + 逐文件批准（stage/unstage）+ commit | FileTreeView / FilePreviewView / DiffReviewView / CommitSheet | ✅ 写面失败如实回传，不静默 |
| 设置读面族（记忆/技能/MCP/插件/自动化/工作流库/错峰/桌面设置）+ Bots | P2ExtrasViews / BotManagementView | ✅ 四态齐备，schema 不可解析时诚实空态 |
| 「无 RPC 实证不露出」纪律 | checkpoint、云端沙盒、反馈工单、语音/仓库 chip 均 HIDDEN | ✅ 值得保持的纪律 |

### 接口有、UI 未接（需产品裁决，非缺陷）

1. **git 高危写族**（discardPaths / push / switchBranch / createBranchAndSwitch）— ReadOnlyGate 已放行、UI 未接；接入时按 AGENTS.md 要求必须带确认弹层。
2. **会话级用量**（`usageStatsV4` / `conversationUsageV4`）— 会话内只有上下文用量条，无 token 成本明细入口。
3. **命令终态回查**（`queryConversationCommandsV4`）— 见 Finding M4。
4. `backgroundBashOutputV4`、附件 Share 读族、`getAppUsageStats` — 未接，影响小。

### UI 已接但接口证据等级为【宽容·未取证】（风险提示）

8 条扩容命令、附件上传事务、setFollowupMode、桌面设置双频道等——UI 的「失败如实上屏」设计是对的，但这批功能在真机联调前不应视为已验证；建议设置页/诊断页保留探针入口直到取证完成。

## 二、Findings（按严重度排序）

### 🟠 Major

**M1 [edge-states] 会话列表无拉取失败错误态，失败与真空不可区分**
- 出现位置：`Features/Conversations/ConversationListView.swift:147-149`（只有加载/空态两态；写操作有 toast，但列表拉取失败无任何提示）
- 影响：会话列表是 App 主入口；中继瞬断或订阅失败+兜底链也失败时，用户看到「空态+新建 CTA」，会误以为会话全丢了
- 修复建议：参照 DiffReviewView 的「整页错误+重试 / 旧数据横幅」双态（DiffReviewView.swift:166/:287）复用到列表；有缓存数据时显示旧数据+失败横幅，无缓存时整页错误

**M2 [edge-states] TaskOutputView 零状态处理**
- 出现位置：`Features/Tasks/TaskOutputView.swift`（全文 grep 无 error/isLoading/离线任何匹配）
- 影响：中继瞬断时输出流静默停更，用户无法区分「任务还在跑」与「连接已断」；停止任务失败也无提示
- 修复建议：加连接态指示（复用全局 ConnectionBanner 状态做页内角标）+ 停止操作失败错误行 + 流中断时的「等待重连」占位

**M3 [copy] DiffReviewView「回退此文件」文案/样式与接口语义错位**
- 出现位置：`Features/Files/DiffReviewView.swift:482-486`（destructive 红色按钮「回退此文件」）→ 实际执行 `git.unstagePaths`（**可逆**，RemoteFileStore.swift:605-606）；「已回退」红字卡 :535-541
- 影响：双重错位——① 可逆操作用 destructive 样式，用户误以为丢弃了改动；② 「未暂存」分段的文件本就不在 git index，对其 unstage 是语义空操作；③ 若用户真想要「丢弃改动」（discardPaths），UI 反而不提供
- 修复建议：分段感知文案——已暂存段：「取消暂存」（非 destructive）；未暂存段：不提供该操作，或正式接 `git.discardPaths`（真丢弃，必须确认弹层 + destructive 样式，与 web 对齐）

**M4 [feedback] 命令终态不可查，重连后发送状态不明**
- 出现位置：ChatView 发送链路；协议侧 `queryConversationCommandsV4` 未接（协议文档 §12）
- 影响：中继瞬断场景下，信封命令可能「已送达未回执」也可能「未送达」；UI 只有发送失败错误行，重连后用户无法确认上一条命令（尤其是 stop/审批应答这类时效命令）是否生效
- 修复建议：重连恢复后对 in-flight 命令走 `queryConversationCommandsV4` ACK 回查；消息行增加「发送中/已送达/已入队」状态指示（投递模式已有 queue 语义，可视化状态机已具备）

### 🟡 Minor

1. **[feedback] DiffActionBar「全部批准」无计数、无确认**（RootView.swift:373-458）— 全局悬浮在任意 Tab 上，设置页也可见，误触即批量 stage。建议：按钮带待批计数（与角标一致）+ 轻确认或完成后 toast 可撤销提示。
2. **[feedback] 断连时 composer 无显式禁用态** — 目前靠发送失败错误行事后提示。建议断连时 composer 置灰 + 占位文案「已断开，等待重连」，与新建会话 sheet 的「未连接错误行」（NewConversationSheet.swift:90）口径一致。
3. **[copy] 置灰 chip 无原因说明** — 新建会话 sheet 的仓库/语音等置灰入口没有任何「为什么不可用」的提示。建议加 hint：「桌面端能力，移动端暂不支持」，避免用户以为是 bug。
4. **[accessibility] 状态单一依赖颜色点** — 运行蓝点/待批橙角标/设备在线点等仅靠颜色传达（色盲用户不可辨）。建议颜色+图标/文字双通道（审批卡本身做得好，延伸到列表行）。
5. **[responsive] 小屏拥挤未验证** — ChatView 五面板 chips + composer 四 chips（模型/思考/协作/投递）+ 上下文用量条，在 iPhone SE（320pt）下存在换行/截断风险。建议 SE 模拟器实测，chips 区支持横向滚动或折叠为「⋯」。
6. **[feedback] 服务端搜索失败静默回退本地过滤**（ConversationListView.swift:89 注释明确「不崩」）— 用户搜不到远端结果时无感知。建议搜索框下加一行轻提示「仅本地结果（远端搜索不可用）」。
7. **[components] 新建会话 sheet「执行端单选卡」仅演示态存在** — 演示/生产两套结构易漂移。建议演示态走 mock store 而非独立 UI 分支。
8. **[ia]「任务」Tab 与「会话」Tab 概念重叠** — 任务看板与会话列表是同一批数据（bootstrap.tasks）的两种视图，新用户难区分「任务」和「会话」。建议在任务看板空态/头部加一句定位文案（如「跨会话的运行中任务一览」），或远期合并为单 Tab 双视图。

## 三、修复优先级建议

- **必须修复（影响主流程信任度）**：M1、M2、M3 — 共 3 项（都是「状态如实呈现」问题，与项目「写面禁止静默」纪律同源，补齐读面）
- **建议修复**：M4（需协议侧配合接 queryConversationCommandsV4）、Minor 1/2/4 — 共 4 项
- **可延后**：Minor 3/5/6/7/8 — 共 5 项

## 四、做得对、值得保持的设计决策

1. 「无 RPC 实证不露出」HIDDEN 纪律——接口宽容形态一律不在 UI 承诺，避免假功能
2. 审批卡常驻 composer 上方 + 重连 resync 补齐 pendingInteractions——抓住了遥控台的第一优先级场景
3. 全部 CAS 命令走 sendCASWithRetry + stale 原样重发——用户无感的一致性问题被 infrastructure 吸收，UI 不需要暴露 retry 概念
4. 写面失败如实上屏（列表 hint、diff 错误行、composer 错误行）——纪律统一；本次走查的 M1/M2 本质是同一纪律在读面的缺口

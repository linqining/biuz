# Web 源码一致性审查报告（接口用法 + UI 提审风险）

> 日期：2026-10-06 · 审查基准：`/tmp/webshell2.js`（/remote/v4 页面 bundle，4.8MB，与移动端同一条 WS 配对通道）
> 方法：4 路并行审计（v4 命令 / RPC 频道 / 传输层 / UI 提审）→ 每条高危发现由独立代理双侧重新取证复核。
> 复核结果：33 条核心发现，**32 条确认、1 条推翻**（§九），多处细化（已并入正文）。
> 结论口径：凡标注【实证】的 web 形状均出自 bundle 的 zod schema 与实调用点逆向，检索式可复现。

---

## 一、总览

| 维度 | 核对数 | 一致 | 偏差（高/中/低） | web 未用（不可实证） |
|---|---|---|---|---|
| v4 会话命令（sendConversationCommandV4） | 27+1 | 14 | 10（5/2/3）+1 细化 | 3 |
| RPC 频道方法 | 53 | 18 | 20（8/7/4，其中 1 条复核推翻→实际 19） | 15 |
| 传输层 zcode_type 链路 | 16 | 4 | 10（3/5/2） | 2（无功能后果） |
| UI 无接口/假功能（提审面） | 17 | — | 高 4 / 中 5 / 低 8 | — |

**根因模式**（全部与「实现时未对照 web 源码」直接相关）：
1. 协议文档中标注「未取证/宽容」的候选形状被当成事实实现（setAssistantFeedback 的 positive/negative 即典型）；
2. 读侧 schema 被臆测为写侧形状（attachmentChunkV4 的 `ref/offset` 抄自读侧 attachmentReadV4）；
3. 参数键名系统性偏差（`title`/`name`、`pluginName`/`name`、`like`/`positive`、`members` 顶层/组内）；
4. 写面错误几乎全部被 `try?`/`_ =` 静默吞掉，UI 无条件报成功 → **假成功泛滥**；
5. E2E 替身服务器不校验载荷形状 → 本地测试全绿、真机必坏。

---

## 二、必坏类偏差（高危：真机即坏或恒无效）

### A. v4 会话命令

**A-1 `setAssistantFeedback` feedback 值域错**【实证】
- app：`RemoteConversationStore.swift:2518` 发 `"positive"|"negative"|null`（值域唯一来源是协议文档 §7.2 标注「未取证」的候选行）。
- web：schema `feedback: La(["like","dislike"]).nullable()`，UI 回调同域（`r==="dislike"`）。
- 后果：点赞/点踩一律被 zod `invalid_enum_value` 拒收，**消息反馈功能全坏**（有兜底提示但永远失败）。
- 修复：值域改 `like|dislike`。

**A-2 `setFollowupMode`「立即」档枚举外**【实证】
- app：`ChatViewModel.swift:163-172` 三档 now/queue/guide 全部真实下发 `setFollowupMode`。
- web：schema 枚举仅 `queue|guide`；「立即」在 web 根本不是 followupMode 值——立即语义是逐消息 `requestedDelivery:"startNow"`（sendText 可选参数），且 web 默认 followupMode 为 `queue`。
- 后果：切回「立即」时命令必被拒，UI 报失败、状态不落。
- 修复：now 档不下发命令（仅本地态），只对 queue/guide 发送。

**A-3 `resolveInteraction` answer 形态整体错位（审批+提问双路径）**【实证】
- app：审批 `StoreProtocols.swift:329-339` answer=`{approved,scope}` 且把 answer 键平铺进 payload 顶层（`RemoteConversationStore.swift:1716-1732`）；提问 `:1403-1410` answer=裸字符串。
- web：answer **恒为对象**，按交互族分形：
  - 权限审批卡：`{optionId}`（按钮即选项：allowOnce/allowAlways/rejectOnce/rejectAlways，应答=选中项 optionId）；
  - 计划确认/阻塞提示类：`{action:"accept"|"decline"|"cancel", content?}`；
  - 提问：`{freeText}` 或 `{optionId}`。
- 后果：提问应答（聊天阻塞式问答）**必被拒**（zod expected object, received string）；审批的 approved/scope 是 wire 层不存在的键，被 strip 后 answer 退化 `{}` → 可能 accepted 但无决议内容（**假成功**，桌面交互不解除）。
- 附：协议文档 §7.2:394 记录的 `{approved,scope}` 形态**无【实证】标注且与 web 冲突**，需按 §八回写。
- 修复：权限卡按选项映射 `{optionId}`；计划确认 `{action}`；提问 `{freeText}`；删顶层平铺。

**A-4 附件上传事务全链构型错误（自造帧 + 缺 Begin + 键名错 + 分块规格异）**【实证】
- app：①自造传输帧 `zcode_type="attachmentPut"`（`ZCodeServerConnection.swift:768-791`，web 出站 zcode_type 全集 9 种**无此帧**——attachmentPut 在 web 是客户端高层函数名）；②Chunk 发 `{sessionId,workspacePath,workspaceIdentity?,ref,offset,dataBase64}`（`RemoteConversationStore.swift:3155-3172`）；③Commit 发 `{...,ref}`（`:3178-3191`）；④无 Begin、无 uploadId/chunkIndex/connectionId/checksum；⑤512KB/块、4MB 上限（`AttachmentUploadService.swift:55-57`）。
- web 真实事务（全部 `.strict()` schema）：
  1. `attachmentBeginV4({connectionId, uploadId, sessionId, fileName, mime, totalBytes, totalChunks, checksum:"sha256:"+64hex})`——uploadId 客户端生成 `upload-${uuid}`，回执为 state 判别联合：`{state:"staging",nextChunkIndex}` | `{state:"committed",nextChunkIndex,ref}`（committed 直接给 ref）；
  2. `attachmentChunkV4({connectionId, uploadId, sessionId, chunkIndex, dataBase64})`，**384KB/块**（`hy=384*1024`），进度判定=回执 `nextChunkIndex===n+1`；
  3. `attachmentCommitV4({connectionId, uploadId, sessionId})` → `{ref}`，ref 随 sendText attachments 携带；
  4. 失败 `attachmentAbortV4`（同形）。
  - 注：`connectionId` 为 schema 必填但 web 代码从不显式构造（channel/service 底层注入，bundle 不可见注入点）——实现时需在握手中取等价值。
- 后果：真机发送附件全链必坏——第一步自造帧大概率超时（约 21s 后报「上传会话未建立」），即便有响应 Chunk/Commit 也因 strict schema 缺 `uploadId`/`chunkIndex` 被拒。E2E 替身不校验形状，测不出。
- 修复：按 web 事务重写整条上传链（含 `sendText` 附件元素键名，见 B-1）。

### B. RPC 频道方法

**B-1 `sendText` attachments 元素键名错**【实证】
- app：`{ref, name, mediaType, size}`（`RemoteConversationStore.swift:1369-1378`）。
- web：三处独立构造均为 `{ref, fileName, mime, bytes}`。
- 后果：附件上传链修好后此键名错接棒——strict 则整条 sendText 被拒，strip 则附件显示名/类型/大小丢失。

**B-2 `zcode-task.listGroupedTaskViewStructure` 回执解析取错位置**【实证】
- app：`RemoteTaskStore.swift:415-419` 在**组对象内部**找 `tasks|taskIds|items|order`。
- web：回执为 `{groups:[{id,title,color,…}], members:[{groupId,workspacePath,workspaceIdentity,taskId}], topLevelOrders:[…]}`——**成员在顶层 members 数组**、按自带 groupId 归组，组对象内无任何成员键。
- 后果：每组 taskIDs 恒空 → `TaskBoardView.swift:94` 判定不成立 → **桌面分组恒回退本地四组** + 常驻「桌面分组不可用」提示。RPC 本身成功，纯静默假成功。
- 修复：解析改 groups[]+顶层 members[] 聚合（可加 topLevelOrders 排序）。

**B-3 `zcode-task.applyGroupedTaskViewOrder` 参数形状完全不同**【实证】
- app：`{groupId?, order:[{taskId, groupId|null}]}`（`RemoteConversationStore.swift:2913-2924`）；且 `ConversationListView.swift:196-200` 把**组名字符串当 groupId** 下发、丢弃 createTaskGroup 回执。
- web：`{workspaceScopes, topLevelNodes:[…], groups:[{groupId, taskRefs:[{workspacePath,workspaceIdentity,taskId}]}]}`。
- 后果：会话列表「移入分组」静默失败，任务永远进不了分组、无任何报错。
- 修复：按 web 形状重写；使用 createTaskGroup 回执的真实 groupId。

**B-4 `zcode-task.renameTaskGroup` 键名 `name`≠`title` 且缺 workspaceScopes**【实证】
- web：`{groupId, title, workspaceScopes}`。app 缺两项 → 重命名恒无效且无反馈。

**B-5 `usage-stats.getEntitlementSnapshot` 入参缺 5 键、回执解析整体落空**【实证】
- app：仅 `{preferredProviderId}`；解析 `entitlements[]|benefits[]|features[]|items[]` 列表（`AppSession.swift:817-862`）。
- web：入参 `{includeSubscription:true, preferredProviderId, accountAccess:{type:"zhipu-account",family,planKind}, allowDisabledPreferredProvider:true, requirePreferredProvider:true, allowEnvApiKey:false}`；回执 `{provider, authenticated?, unavailableReason?("no_plan"), quota:{level,limits}, subscription:{details:[{productName,…}]}, remaining?}`——**无任何列表键**。
- 后果：**套餐权益块在任何路径下都显示不出内容**（tier 仅 quota.level 兜底可能命中）。
- 修复：补齐入参；渲染改 web 口径（quota.level + subscription.details[].productName + remaining + no_plan 空态）。

**B-6 重置卡三连（requestCodingPlanResetOpportunity / useCodingPlanReset / markCodingPlanResetHistoryRead）scope 误解**【实证】
- app：`{scope:{workspaceKey,remoteSessionId,sessionId}, idempotencyKey, resetType}`（`AppSession.swift:894-930`）。
- web：平铺 `{preferredProviderId, accountAccess, idempotencyKey?, resetType?}`（scope 只是前端内存变量名/去重键，不是服务端结构）。
- 后果：**一键领取重置卡必败**（步骤①被 try? 吞、步骤②被拒报「领取失败」）。

**B-7 `settingService.update` 载荷形态错**【实证】
- app：`{key, value}`（`P2ExtrasViews.swift:2080-2084`）。
- web：`{设置名: 新值}` 单键补丁对象（如 `update({terminalFontFamily:t})`）。
- 后果：**桌面设置写面全坏**（读面 get 正常）。

**B-8 `zcode-agent.uninstallPlugin` 键名错 + 缺必填**【实证】
- app：`{name: row.title}`。
- web：`{workspacePath(必填，缺失时报「请先打开一个工作区」), workspaceIdentity?, pluginName, marketplace, scope:'user'}`。
- 后果：**卸载插件必败**。

**B-9 `zcode-agent.forkAssistant` 传输面用错**【实证】
- app：channel RPC `call("zcode-agent","forkAssistant",{sessionId,workspacePath,…})`（`RemoteConversationStore.swift:2853-2865`）。
- web：sendConversationCommandV4 命令，payload `{target:{rowId,entityId}}` + CAS 双字段（在 Jle/Yle 双词表内）。
- 后果：channel 路径或可当前可用（文档记实证），但与 web 不同面，桌面收紧即失效，且永远做不了「从某条消息 fork」。协议文档 §7.2:429 与 §9.1.7:510 **两节各记一个表面、互相矛盾**，且 §9.1.7 调用点指位失效（记 2093-2105，实际 2853-2865）。

### C. 传输层

（并入 A-4 附件事务；其余见 §三 C 组）

---

## 三、场景性偏差（中危：特定场景坏）

### v4 命令
- **C-1 行游标回退虚构**：app `row["entityId"] ?? row["turnId"]`（`RemoteConversationStore.swift:1132-1136`，toolCall 分支 :1034 同型）；web 语义是 entityId 缺失即不构造 target、不出入口。后果：行缺 entityId 时 app 出现「按钮在、点了必失败」的入口（turnId 冒充 entityId 被服务端拒）。
- **C-2 `editUserQuery` 可选键缺席**：web 显式带 `workspaceMode:"preserve"|"rewind"`（默认 preserve）与 `attachments?`；app 只发 `{target,newText}`（大概率等价，补 preserve 更稳）。

### RPC 频道
- **C-3 `updateTaskGroupColor`/`deleteTaskGroup` 缺 `workspaceScopes`**（web 全带，必填性未取证；建议直接补齐消除不确定性）。
- **C-4 `createTaskGroup` web 零参调用**（建组后走 renameTaskGroup 落名）；app 传 `{name,color}` → 组名/颜色可能不生效。
- **C-5 `git.generateCommitMessage`**：app `{workspacePath,paths}`；web `{workspacePath, locale, includeUnstaged, currentSessionFilePaths?, conversationContext?}`，回执 `{providerId,model,message}`。后果：paths 臆造 + 缺 locale/includeUnstaged → AI 提交信息范围可能与所选文件集不符（或 strict 拒未知键直接失败）。
- **C-6 `git.getCommitGraph`**：缺 `maxCount:50, skip:0`；条目时间键 `authoredAtMs` 未读（app 读 timestamp|authorDate 恒空）→ 提交日期恒空或整页失败。
- **C-7 `git.getDiff` staged 分支缺 `sourceId`**：app fetchPatch 不透传 sourceId（`RemoteFileStore.swift:410-422`）；web 恒带 `sourceId`。另 web 回执为结构化 `{availability,beforeContent,afterContent,patch?}`，app 只读 `["patch"]`——staged 页签 patch 口径错位/恒空风险。
- **C-8 `zcode-agent.listPlugins` 无参调用**：web 恒带 `{workspacePath, workspaceIdentity?, configScope?}` → workspace 级插件可能不出现在清单。
- **C-9（低）`zcode-agent.getSkillReferenceCatalog` 无参**：web 携 workspace 维度；有占位降级，影响小。

### 传输层
- **C-10 工作区切换流程与 web 不同构**：web 的 `workspace-reconnect-request` 仅 3 键 `{zcode_type,requestId,workspaceKey}`、**只用于侧栏「重连已断开的远程工作区」按钮**；切换工作区 = `workspace-bridge-open(新key)` + `mobile-view-state-update`，不发 reconnect。app 把 reconnect 前置到切换流程且多带 workspacePath/workspaceIdentity（`ZCodeServerConnection.swift:635-686`）。
- **C-11 `mobile-view-state-update` 从不发送**：web 在 bridge-open 成功/打开任务/切换工作区三场景发 `{zcode_type, viewState:{activeWorkspaceKey, activeTaskId?, updatedAt}, deviceInfo:{platform:"web",version:appVersion,…}}`；桌面 bootstrap 以 `mobileViewState?.activeWorkspaceKey ?? initialViewState?…` 决定落点（REST 面实证）。后果：手机切到工作区 B 后断线重连，**被拉回切换前的工作区 A**。
- **C-12 workspaceKey 反查兜底**：清单无 path 时以 path 充当 workspaceKey（`RelayChannelClient.swift:308-313`，未取证兜底）→ 可能发非法 key 切换失败。
- **C-13 `bridge-degraded` 完全未消费 + `checkReplayDeadline` 死代码**（`RelayTransport.swift:389-415` 无 case；:810-818 全仓 0 调用）：web 按 bridgeSessionId 匹配后 markDegraded 快速重建。后果：桌面宣告桥退化后 app 继续向死桥发 RPC，用户面对逐条 30s 超时。
- **C-14 错误帧被当成功响应**：web 对带 requestId 的 `app-error`/`workspace-bridge-error`（字段 `reason`+`error` 字符串）直接 reject 对应 waiter；app 的 `resolveAppRequest` 无条件按成功 resume（`RelayTransport.swift:417-424`），且 `switchRelayWorkspace` 只识别 `error` 字符串/`ok:false`（不识别 `reason`）→ 切换在途回错误帧被误判成功继续走流程。
- **C-15 bootstrap canBridge 门控缺失**：web 过滤 `kind!=='remote' || (workspaceIdentity && remoteSessionId)` 不可桥条目（不可桥不开桥、退 home-only）；app 无过滤 → 点击不可桥工作区后开桥失败。
- **C-16 附件分块规格**：app 512KB（依据读侧 chunkLimit≥512KB 推测）；web 384KB。读侧实证只证明上限 ≥512KB，若服务端写侧上限即 384KB 则首块即拒；4MB 总上限是 app 拍的（真实 `attachmentMaxBytes/attachmentUploadMaxChunks` 在 bundle 外，需探针）。

---

## 四、口径类（低危，无害或文案）

- `stop` 多带 `reason:"user-requested"`：web schema 非 strict，多余键被 strip（探针实证 accepted）——无害，建议清理。
- `sendGoalCommand` 误入 CAS：web CAS 词表（Jle 15 命令）确无它（属 input 类，baseRevision 可选）；app 无条件带双字段是超集做法，大概率无害（sendCASWithRetry 只对 stale 重试）。建议改普通 sendCommand。
- v1 条数套餐剩余百分比兜底公式（remaining/usage vs remaining/number）：仅 v1 且 percentage 缺席时数值不同——此前修的口径主体已正确。
- `AppSession.swift:651-659` 注释残留旧口径（0–1 vs 0–100），会误导下次改动；协议文档 §9.10 limits 行亦残旧窗口口径。
- `getCodingPlanUsageSnapshot` 的 accountAccess 形状：app 传 provider 注册表形状，web 恒传 `{type:'zhipu-account',family,planKind}`——当前桌面两种都收，未来收紧有落错面风险。

## 五、web 未使用（不可实证，接 UI 前必须真机探针）

| 接口 | web bundle | 风险 |
|---|---|---|
| `startSavedWorkflow` | **0 命中，且不在 web 命令 type 枚举内** | 最高：若桌面枚举与 web 同源，发送即被拒；payload `{workflowId,args}` 两键均无对照 |
| `resumeWorkflowRun` / `amendWorkflowRunSettings` | 0 命中 | app 注释声称「web v4-pane 同构造」不成立，建议修正注释 |
| `git-checkpoint.diffCheckpoints/createCheckpoint/restoreBetweenCheckpoints` | 0 命中，**协议文档亦零条目** | 恢复入口是确认弹层后的真写，形状全靠猜——全审计中最不可靠一环 |
| `feedback.list` | web feedbackService 仅 create/comment/upload 族，无 list | 双重未取证 |
| `git.getBranchComparison` | web 仅 mock 形态（baseRef/headRef/comparisonLabel/changes），与 app 解析的 ahead/behind/base/summary 无交集 | 对比行恒 nil（静默不显示，不误报） |
| `listSavedWorkflows` / `listMcpServerStatuses` / `listAutomations` 等 | web 未用 | 文档宽容条目兜底 |

## 六、UI 无接口支持/假功能清单（App Store 提审风险）

### 高风险（无外部依赖也无效或假数据呈现 —— 2.1 完整性直接风险）

**U-1 演示模式假数据全包（最高风险）**
- 位置：`ZCodeMobileApp.swift:106-122`（demo/connectFailed/disconnected/**connecting** 四态全换 Mock Store）+ `MockConversationStore.swift:49-107/230-283`。
- 形态：冷启动未配对即呈现 6 条假会话/假任务/假 diff；**发消息必回脚本化假 AI 流式回答**（假 git 工具调用、假 diff、假审批流转）；披露仅设置页脚一行小字，纯 demo 态无任何横幅，断线横幅不提「已回退演示数据」。
- 审核员无桌面端时看到 App「完整工作」——**最典型的 2.1 拒审形态**。
- 建议：提审版未配对态改连接引导页（禁假交互），或演示态 composer 禁发+显著水印+每页横幅。

**U-2 设置页演示态假清单**
- `SettingsView.swift:321-341` 入口假值（「4 个服务器已连接」「12 项已启用」「128 条」「12.4M tokens」）；`:375-427` 假 MCP 已连接状态、假技能启停、**带评分的假插件市场**（4.8/4.9/4.6 分）。
- 建议：演示态走诚实空态（「连接桌面端后同步」），删 GenericListPage 假数据分支。

**U-3 设置页用户卡假额度**
- `SettingsView.swift:171-176`（兜底 68%）、`:212`（「本月 500 条中的 340 条已使用，**9 月 2 日重置**」——已是过去日期）、`:221`（默认名「Zai 开发者」）；连接态无百分比时进度条也回落 68%。
- 付费承诺性假信息，比一般占位更敏感。

**U-4 任务审批 Sheet 授权范围三档单选是死控件**
- `ApprovalSheetView.swift:149-192` 选择被静默丢弃，`:207-242` 调 `approve(taskID:)` 无 scope 参数（协议面缺口，`StoreProtocols.swift:411`），`RemoteTaskStore.swift:184-191` 硬编码 `"once"`。会话内审批卡已正确传 scope——两路径不一致。
- 涉授权安全语义的假交互。修复：协议面加 scope 形参透传（照 ChatView:529 前例）。

### 中风险（依赖桌面端但空态/失败提示不清，或假成功）

- **U-5 任务审批假成功**：`RemoteTaskStore.swift:195-207` interactionId 取不到静默 return；`ApprovalSheetView.swift:209-237` 无条件弹「已批准执行/已拒绝」并关 sheet。
- **U-6 发送失败静默丢稿**：`ChatViewModel.swift:666-677` draft 先清空 → ack nil 撤销回显（`RemoteConversationStore.swift:1379-1388`）→ `ChatView.swift:1365-1370` 忽略 false 无提示。消息凭空消失+草稿丢失（兼具数据丢失类审核风险）。
- **U-7 未连接占位文案语义错误**：`P2ExtrasViews.swift:1179-1181` 未连接即 `.failed` → 渲染「**移动端读面尚未接入**」（:880-899）。影响三个已真接线的入口：**反馈工单**（feedback.list）、工作流库、错峰任务——用户（和审核员）会误以为功能是死的。改「未连接桌面端，连接后可查看」。
- **U-8 模型设置点选无效**（连接态）：`SettingsView.swift:529-611` 只写本地不下发 switchModelConfig，勾选不动无提示。
- **U-9 断线换回演示数据但横幅未声明**（`ZCodeMobileApp.swift:116-121` vs `RootView.swift:135-142`）。

### 低风险（文案性占位，合规，列举备查）

新建会话「仓库/语音」chip 置灰（NewConversationSheet.swift:295-332）；插件商店「市场安装后续版本提供」脚注（已如实标注）；composer 执行目标菜单「仅记录偏好」双重标注；会话列表「云端沙盒」chip 置灰；Diff 底栏「桌面端继续」实际 push 文件树（文案偏差）；文件审查「拒绝/回退此文件」实际 unstage（可逆，建议确认层注明）；设备页「云端沙盒规划中」；任务看板空态「云端沙盒执行」文案不准（实为桌面执行）。

---

## 七、修复优先级建议

1. **P0（真机必坏，用户高频路径）**：A-3 resolveInteraction（审批+问答）→ A-1 setAssistantFeedback → A-2 setFollowupMode「立即」→ A-4 附件上传事务重写（含 B-1 键名、C-16 分块规格）。
2. **P0（提审阻塞）**：U-1 演示模式 → U-2/U-3 设置假数据（同一根因，一次整改）→ U-4 scope 透传 → U-6 丢稿 → U-7 文案。
3. **P1（功能恒无效但静默）**：B-2/B-3/B-4 任务分组三连 → B-6 重置卡 → B-7 设置写面 → B-8 卸载插件 → B-5 权益块。
4. **P2（场景性）**：C-10/C-11/C-12 工作区切换+视图记忆 → C-13/C-14 桥退化与错误帧收口 → C-5/C-6/C-7 git 三项 → C-1 游标回退 → C-15 canBridge。
5. **P3**：低危口径清理 + §八文档回写。
6. 修完后统一安排一次**真机联调取证**（桌面端在线），把本次全部【实证】web 形状在桌面侧复核并回写证据等级。

## 八、协议文档需回写的矛盾点（待回写，避免与工作流回写冲突暂未改动本文档）

1. §7.2:394 resolveInteraction 的 `{approved,scope}`/裸字符串形态：**删或标注推翻**，改记 web 三族形态（optionId / action / freeText）。
2. §9.1.7 forkAssistant：与 §7.2:429 矛盾（channel vs v4 命令），调用点指位失效（2093-2105 → 2853-2865）。
3. §7.2 词表补：attachmentBeginV4/ChunkV4/CommitV4/AbortV4 的 strict 形状、384KB、nextChunkIndex、sha256 checksum；setAssistantFeedback 枚举 like/dislike；setFollowupMode 枚举 queue/guide。
4. §9.10 usage-stats：getEntitlementSnapshot 完整入参与回执；重置卡三连平铺参数；limits 行旧窗口口径。
5. 新增否定性结论：「attachmentPut 不是传输帧」「workspace-reconnect-request 不用于切换流程」。

## 九、复核推翻项与已确认一致面

- **推翻（1）**：`oauth.restoreCachedSessionState` ——原审计称 web 读 `e.result.status` 嵌套层；复核证实 `e.result` 是前端选项袋属性名而非协议信封，web service 契约返回顶层 `{status,userInfo}`，app 读法同层同词表，**无偏差**。
- **已确认一致（不需改动）**：信封层全部纪律（sessionId 恒在场/clientId/issuedAt 毫秒/workspace 信封/CAS 15 词表对齐）；createSession+modelSelection、pauseGoal/resumeGoal、队列五件、switchModelConfig、compact、snoozeInteractionAutoResolution、switchCollaborationMode；git.commit/stagePaths/unstagePaths/getChanges/getRepositorySummary；getCodingPlanUsageSnapshot 主体口径与 resetStatus；conversationRowsRangeV4、readSession、settingService.get；rpc-frame/ack 帧协议、workspace-bridge-open/ready、bootstrap/workspace-list 请求、workspace-list-updated 消费。

# Web Bundle 接口对齐盘点报告

> 日期：2026-10-06 · 版本 v1
> 数据源：① `/remote/v4` 页面 bundle（webshell2.js，4.8MB，与移动端同一条配对通道）；② 协议接口文档 §9（upstream 源码全量 9 频道 76 调用对）；③ 本项目全源码扫描（`connection.call` / `sendCommand` / `listen` / 传输层消息）。
> 说明：桌面完整 web bundle（index-BO-TaBle.js）已被临时目录清理，其独有面以协议文档 upstream 清单为准补充。

---

## 一、总览

| 维度 | Web 已用 | 移动端已接 | 差距 |
|---|---|---|---|
| v4 会话命令（envelope.type） | ~28 型 | 17 型 | ~11 型 |
| RPC 频道方法 | ~80+ 对 | 76 对中约 55 对在用 | 集中在管理/写面 |
| 传输层消息（zcode_type） | ~10 种 | 4 种 | 切换/记忆/平台代理未接 |
| 附件 | 上传+分段读 | 仅下载读 | 无上传通道 |

---

## 二、Web 已用、移动端未接的接口清单

### A. v4 会话命令（sendConversationCommandV4）

| 命令 | 语义 | 移动端现状 | 接入评估 |
|---|---|---|---|
| `switchCollaborationMode` | plan/build 模式切换 | 无 UI | **低**——信封链路现成，composer 加一个切换 |
| `setFollowupMode` | 投递模式（queue/guide/now） | sendText 已支持 requestedDelivery 参数，无切换 UI | **低** |
| `editUserQuery` | 编辑用户消息并 rewind 重发 | 只有 retryTurn（原样重试） | **中**——需行元数据游标 + 编辑 UI |
| `setAssistantFeedback` | 助手消息点赞/点踩 | 消息卡无反馈入口 | **低** |
| `sendGoalCommand` | 目标下发/更新 | goal 面板只有暂停/继续 | **中**——需目标编辑 UI |
| `compact` | 上下文压缩 | 无 | **低**——单命令按钮 |
| `snoozeInteractionAutoResolution` | 挂起交互延后 | 审批卡无"稍后"动作 | **低** |
| `startSavedWorkflow` | 启动已保存工作流 | 已列表（listSavedWorkflows）无启动 | **中**——需参数表单 |
| `respondWorkspaceHookReview` / `toggleWorkspaceHookReviewItem` | hook 审核应答 | 无 UI | **中**——需审核列表面 |
| `applyFileRewind` | 会话文件回退 | **有意拦截**（边界红线） | 维持拦截 |
| `createSelectionSideSession` | 选择侧会话（web 选择面板用） | 无对应 UI | 低优先 |

### B. RPC 频道方法

| 频道.方法 | 语义 | 接入评估 |
|---|---|---|
| `zcode-task.listGroupedTaskViewStructure` | 桌面分组视图结构（组/排序/折叠） | **中**——对齐桌面任务分组；当前移动端本地分组 |
| `zcode-task.onDynamicWorkspaceEvent` | 工作区级任务事件流 | **中**——可替代多 workspace 逐区订阅 |
| `zcode-task.restartWorkspaceProcess` | 重启工作区进程 | 低（设置/排障入口） |
| `zcode-agent.installPlugin` / `uninstallPlugin` / `updatePlugin` / `addPluginMarketplace` / `updatePluginMarketplace` / `getPluginsOverview` | 插件安装/市场管理 | **中**——gate 现拦；接 UI 需市场浏览面 |
| `zcode-agent.onDynamicCuaPermissionObservation` | CUA（computer use）权限实时观察 | 低（依赖 CUA 场景） |
| `zcode-agent.syncAppRuntimePreferences` | 运行时偏好同步 | 低 |
| `zcode-session.closeSession` | 关闭会话（区别于 deleteSession） | 低 |
| `zcode-session.readWorkspacePresentation` | 工作区展示信息 | 低 |
| `usage-stats.getEntitlementSnapshot` | 套餐权益快照（订阅档位/权益列表） | **低**——额度页补全 |
| `file.createScratchWorkspace` / `ensureConversationWorkspace` / `resolvePath` | 工作区/路径工具 | 低 |
| `git.getIdentity` | 仓库身份 | 低 |
| `git.commit` / `push` / `discardPaths` / `switchBranch` / `createBranchAndSwitch` / `generateCommitMessage` | 提交/推送/丢弃/分支（gate 已放开，**UI 未建**） | **中**——commit+generateCommitMessage 价值最高；discard/push 必须带确认 |
| `settingService.get/update` | 桌面设置同步读写 | 中（gate 现拦 settings 族） |
| `git-checkpoint.*`（createCheckpoint/restore…） | 检查点创建/恢复 | 中（安全网价值；写面需确认） |
| `terminal.*` | 终端流 | **高成本**——交互式流 + UI，建议缓 |

### C. 传输层消息（relay WS zcode_type，非 RPC channel）

| 消息 | 语义 | 移动端现状 |
|---|---|---|
| `workspace-list-updated` | 工作区清单变化推送 | transport 已收（resolveAppRequest），**无人消费** |
| `workspace-reconnect-request/response` | 切换工作区（复用连接） | 未接——多工作区切换器的前提 |
| `mobile-view-state-update` | 记忆 activeWorkspaceKey/activeTaskId | 未接 |
| `platform-request`（externalWebRemoteControlProxy） | 平台能力代理（web 打开本地目录等） | 未接 |
| `fileChanges` / `fileRewindPreview` | 会话文件变更/回退预览（review 流） | fileChanges 已接（conversationFileChangesV4）；rewind 预览未接 |
| `attachmentPut` / `attachmentReadRange` | 附件上传 / 分段读 | **未接**（只有 attachmentReadV4 下载） |

---

## 三、移动端 UI 无接口支持的面

| # | UI 面 | 现状 | 缺的接口 |
|---|---|---|---|
| 1 | 会话输入·附件 | 「附件」chip 置灰（"即将支持"）；引用文件只是拼路径文本 | `attachmentPut` + `attachmentChunkV4/CommitV4` 上传族 |
| 2 | 会话输入·模式 | 无 plan/build 切换、无投递模式切换 | `switchCollaborationMode` / `setFollowupMode` |
| 3 | 消息卡 | 无点赞/点踩 | `setAssistantFeedback` |
| 4 | 用户消息 | 长按无"编辑重发" | `editUserQuery` |
| 5 | goal 面板 | 只读展示 + 暂停/继续 | `sendGoalCommand`（下发/改目标） |
| 6 | 工作流页 | 已保存工作流只读列表 | `startSavedWorkflow` |
| 7 | 上下文 | 无压缩入口 | `compact` |
| 8 | 审批卡 | 无"稍后处理" | `snoozeInteractionAutoResolution` |
| 9 | 文件页 | 提交/推送/分支/丢弃全部无 UI（"分支由桌面端管理"只读声明） | git 写族（gate 已放开） |
| 10 | 任务分组 | 本地分组，与桌面分组结构不一致 | `listGroupedTaskViewStructure` + 分组写族对齐 |
| 11 | 终端 | 无终端页 | `terminal.*` 交互式流 |
| 12 | 设置·桌面同步 | 设置全本地，桌面设置不可读写 | `settingService.get/update` |
| 13 | 设置·插件 | 只有列表（listPlugins），无安装/市场 | `installPlugin` 族（gate 现拦） |
| 14 | 设置·MCP | 只读状态（listMcpServerStatuses），无增删 | MCP 写面 |
| 15 | 检查点 | 无 | `git-checkpoint.*` |
| 16 | 多工作区 | 清单合并只读，无切换器 | `workspace-reconnect-*` + `workspace-list-updated` 消费 |

---

## 四、建议优先级（供决策）

**P1——高频协同价值、接口现成、成本低**：
1. 附件上传（`attachmentPut` 族）——手机拍照/相册/文件发图，移动端独有价值最高的缺口
2. plan/build 模式 + 投递模式切换（composer 补齐，两条单命令）
3. 消息点赞/点踩 + 编辑重发（`setAssistantFeedback` / `editUserQuery`）

**P2——工作流闭环**：
4. `startSavedWorkflow`（已保存工作流一键启动，参数表单）
5. `sendGoalCommand`（目标下发）
6. `listGroupedTaskViewStructure`（任务分组对齐桌面）
7. `compact` + 审批卡"稍后"（snooze）

**P3——需 UI/确认设计（gate 已开或低风险）**：
8. commit + `generateCommitMessage`（文件页一站式提交）
9. `getEntitlementSnapshot`（额度页权益补全）
10. 多工作区切换器（workspace-reconnect + updated 消费）
11. checkpoint、插件安装、settings 同步

**边界外（维持现状）**：`applyFileRewind`（文件回退红线）、terminal 交互式流（成本/价值比差）、CUA 观察面。

---

## 五、数据可信度注记

- v4 命令集合以 bundle 内 CAS Set（`vle`/`yle`）+ 调用点双重取证；
- `startSavedWorkflow` 在 /remote/v4 bundle 中 0 次出现（移动端 web 未用），来源为桌面执行词表（协议文档 §7.2 upstream 记录）；
- 服务方法清单从 `zcodeTaskService`/`zcodeAgentService`/`zcodeSessionService`/`gitService`/`usageStatsService`/`fileService`/`settingService` 属性调用提取，可能遗漏仅在深层回退对象中定义的方法（缺漏不影响 P1–P3 结论）。

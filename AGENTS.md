# AGENTS.md — Agent 工作指引

面向在本仓库工作的 AI agent / 新成员。**开工前必读**；改协议、加接口、动文档前**必查** §4 文档规范。

---

## 1. 项目一句话

**BiuZ**（bundle `cn.biuz.mobile`）：ZCode 社区版桌面端的 iOS 移动遥控台。手机经局域网直连（`GET /ws?token=…`）或云端中继（`wss://zcode.z.ai/remote/v4`）与桌面端建立 RPC 通道，实现「会话浏览 / 消息与审批 / 文件与 Diff 只读 / workflow 面板 / 模型切换」等远控能力。产品边界：**手机可发命令、桌面代执行；手机不直写桌面文件**（`ReadOnlyGate` 在 RPC 出口强制）。

## 2. 目录导航

| 路径 | 内容 |
|---|---|
| `docs/协议接口文档.md` | **协议唯一权威参考**（帧协议、握手、信封纪律、全方法参考、行模型、state 投影） |
| `docs/立项报告.md` | 立项调研与证据存档（§7.2 协议分类表 ≈ line 386；§8.1 服务 116 方法清单 ≈ line 588）。**只追加不回改**历史章节 |
| `docs/relay-handoff.md` | 云中继对接交接材料（配对链接形态、探针事实） |
| `docs/acceptance-relay/` | 中继验收截图与探针记录 |
| `ios/ZCodeMobileApp/Sources/Services/RPC/` | channel 帧协议 + 序列化 + topic 帧（上游 `packages/rpc`、`zcode-protocol-v4` 的 Swift 移植） |
| `ios/ZCodeMobileApp/Sources/Services/Remote/` | 连接管理（`ZCodeServerConnection`）、装配（`AppSession`）、边界拦截（`ReadOnlyGate`）、URL 解析 |
| `ios/ZCodeMobileApp/Sources/Services/Relay/` | 云中继传输（rpc-frame 编解码/传输/信道客户端） |
| `ios/ZCodeMobileApp/Sources/Stores/Remote/` | 会话/文件/任务三个远端 Store（全部业务 RPC 调用面） |
| `ios/Tests/` | E2E（替身服务器验收） |

## 3. 构建与验证（常规收尾步骤）

```bash
cd ios && xcodebuild -project ZCodeMobile.xcodeproj -scheme ZCodeMobile \
  -destination 'platform=iOS Simulator,id=10501D0E-754E-466D-B290-FF2941B29CE9' \
  -derivedDataPath DerivedData build

xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/ZCodeMobile.app
xcrun simctl launch booted cn.biuz.mobile \
  -ZCodeOpenConversationId <完整 sessionId> -ZCodeDiagWorkflow
```

- 模拟器：`10501D0E-…`（iPhone 17，主力验证）；`6F5AD678-…` 可能被并行任务占用。
- 深链 sessionId 必须完整（截断被拒 `sessionNotFound`）。
- 诊断数据在 App 容器 UserDefaults plist，用 `python3 + plistlib` 读（plutil 会失败）；读前需 App 侧 `synchronize()`（simctl terminate=SIGKILL 不冲洗 cfprefsd）。
- 构建命令需要真机联调时先确认桌面端在线（局域网）或中继链接有效（重新生成即旧凭据失效）。

## 4. 文档规范（核心章节）

### 4.1 文档清单与职责

| 文档 | 职责 | 修改权限 |
|---|---|---|
| `docs/协议接口文档.md` | 协议事实的唯一权威：每个接口的功能、参数、回执、错误、调用点、证据 | **谁改协议谁回写**，与代码变更同一批提交 |
| `docs/立项报告.md` | 历史调研证据存档（file:line 级引用） | 只追加修订记录，**不回改**已有结论；纠错以「修订条目」形式追加 |
| `AGENTS.md`（本文件） | 工作指引 + 文档规范 | 规范变化时更新 |
| `docs/relay-handoff.md` 等专题文档 | 专题交接材料 | 对应专题变更时更新，并在文首加日期注记 |

### 4.2 触发器：什么时候必须写文档

| 代码动作 | 必须的文档动作 |
|---|---|
| 新增一个 RPC 调用（channel.method） | `协议接口文档.md` §9 增加方法条目（模板见 4.4），并自查 `ReadOnlyGate` 分类是否需更新 §8 |
| 修改参数形状 / 信封构造 | 更新对应条目的「参数」表；若推翻了某条【实证】纪律，在 §13 变更记录说明证据 |
| 新增 `sendConversationCommandV4` 的 type | 更新 §7.2 词表（含 payload 形状、是否 CAS） |
| 新增诊断 UserDefaults 键 | 更新 §11 诊断键表 |
| 发现新的服务端错误码 / 拒收行为 | 更新相关条目「错误」栏 + §13 变更记录 |
| 新的否定性结论（「此路不通」） | **必须记录**（§12 或条目内）——否定性结论和肯定性结论同等值钱，禁止删除 |
| 行模型 / state 投影新键 | 更新 §10 |

### 4.3 证据纪律（本文档体系的底线）

1. **标注证据等级**：每条协议事实必须带【实证】/【移植】/【宽容】之一（定义见协议文档 §1）。没有证据的新事实一律标【宽容】并写明「未取证」。
2. **禁止发明字段名**：参数/回执字段名只能来自 ① 真机回执取证、② 上游克隆件源码、③ Web bundle 逆向。猜的字段名必须显式标注「未取证，多形态兼容」。
3. **file:line 指位**：引用代码必须带 `file:line`；**移动该代码时必须同步更新指位**（评审时抽查指位有效性）。
4. **宽容解析 ≠ 协议事实**：客户端为了不崩而兼容的多种形态，只代表「服务端形态未冻结」，不得把某一个形态写成唯一真相。
5. **时间戳口径**：涉及毫秒/ISO 双形态的字段，必须写明两种形态与判定方式（先例：`lastActivityAt`）。
6. **引用上游证据**：上游克隆件（`/tmp/zcode-api-*`）是临时目录，引用时以「克隆件 file:line」形式固化到文档里，不依赖克隆件常在。

### 4.4 方法条目模板（§9 使用）

```markdown
#### 9.x.n `channel.method` 【证据等级】
- **功能**：一句话说明做什么、什么场景用。
- **参数**：`{字段: 类型, …}` —— 逐字段写意义；可选字段标 `?`；写明默认值/取值范围。
- **回执**：形状 + 宽容形态（若多形态，逐一列出）。
- **错误与兜底**：已知错误码/异常 + 客户端兜底行为。
- **调用点**：`file:line`。
- **证据**：来源（真机联调 / 克隆件 file:line / bundle 逆向）。
```

### 4.5 术语与命名约定

- 频道/命令/clientId 等协议标识一律**原文逐字**（`zcode-agent`、`sendConversationCommandV4`、`web-remote-replayable`），禁止翻译或改写。
- 「信封」= sendConversationCommandV4 的 `envelope` 对象；「workspace 信封」= 扁平 `workspacePath` + `workspaceIdentity` 字段；「水位」= `(logEpoch, seq)`；「CAS 类命令」= 需携 `baseRevision` + `baseLogEpoch` 双字段的命令（缺一被拒；实证 2026-10-05）。
- 中文文档、英文协议标识；代码注释风格与现有文件保持一致（协议注释带上游 file:line）。

### 4.6 诊断与临时代码的清理纪律

- `diag.*` UserDefaults 键、一次性取证代码（如 `workflowApiDiagDump`、`diag.ms.dump`）均为**验收后清理项**：新加诊断键必须在协议文档 §11 登记并注明用途与预期清理时点；工作流验收通过后统一清理并从 §11 移除。
- 诊断键不得承载业务逻辑（`wf.runs.mirror.*` 是唯一例外，已注明）。

## 5. 工程纪律（踩过的坑，违反即回归）

1. **信封纪律**（详见协议文档 §7.1，全部【实证】：嵌套形态、clientId 逐字相等、issuedAt 毫秒数、CAS 类带 baseRevision+baseLogEpoch（快照无 state 的会话先 resync 预种）、会话域必带 workspace 信封）。新写命令**必须走 `RemoteConversationStore.sendCommand`**，禁止自造信封（反面教材：`RemoteTaskStore.stop`）。
2. **帧 handler 先于 subscribe 注册**（中继快照帧先于回执到达，晚注册静默丢帧）。
3. **state/delta 全部「键级整体替换」**，绝不深合并（`workflowRuns`、`workspace-config` 同一口径）。
4. **所有调用走 `ZCodeServerConnection.call` 唯一出口**（ReadOnlyGate 拦截依赖它；绕过 = 边界失守）。
5. **订阅失败必须有兜底链**（conversation → readSession 对账；sessions-index → listSessions）。
6. **v4 订阅回执取不到 subscriptionId = 自愈链路失效**，视为错误处理。
7. base64 分块**各自带 padding**：整串解码失败按块独立解码拼接（附件链路实证）。
8. 中继瞬断：分块读/分页读首败 1.2s 退避重试一次。
9. 改协议相关代码后：构建 + 模拟器深链验证 + 截图目检（流程见 §3）。

## 6. 未决事项速查（接手先看）

- ~~`RemoteTaskStore.stop` 信封违规~~已修（2026-10-06：委托 `RemoteConversationStore.stopTurn` 走统一 `sendCommand`）。
- **信封 sessionId 键恒在场**（2026-10-06 探针实证）：createSession 传 null、其余传目标 id；键缺省被 zod 拒（曾致移动端 createSession 对真实桌面静默全失败）。新写命令一律走 `sendCommand`，禁止自造信封（该教训再次印证）。
- **CAS stale 重试**（2026-10-06 探针实证）：`proto.staleRevision`/status "stale" 时原样重发一次即命中（sendCASWithRetry）；队列五件/pauseGoal/resumeGoal/retryTurn 已接入，新 CAS 命令接入时沿用。
- **队列 CAS 已活体验证**（-ZCodeDiagQueueCASProbe 12 步全 accepted，置顶后队列顺序实际改变）；新建会话 createSession/modelSelection 载荷同轮活体验证（result.sessionId=sess_* 规范 id）；**stop 命令已活体验证**（-ZCodeDiagStopProbe，对运行中 turn accepted）。三探针保留可复跑。
- **PTY 阻塞根因确诊（2026-10-06）**：内核 PTY 池耗尽——`kern.tty.ptmx_max=511`，`pty.openpty()` 直接报 "out of pty devices"（expect/tmux 同样失败），而用户态仅 3 个 zsh 持有 ttys（其余为内核层泄漏——立项报告「PTY 泄漏」的确切机理）。**恢复办法**：`sudo sysctl -w kern.tty.ptmx_max=999`（临时）或重启（彻底），之后即可跑门禁 E2E（test08 已编译就绪：`xcodebuild test-without-building -only-testing:ZCodeMobileUITests/FeatureCompletionE2ETests/test08_newConversationModelSelectionCarriedInFirstInput`）。
- 探针残留：5 个标题带「探针」/「请慢慢数数」的会话仍投影在会话列表——deleteTask 只删 task-index，deleteSession 对有行会话报 sessionNotFound（仅 draft/空会话可回收）；残留为桌面真态，需桌面端侧删除。清理探针 -ZCodeDiagCleanupProbe 保留。
- ~~「加载更早消息」~~复验通过（2026-10-06 diag 实据：before=185 after=381，conversationRowsRangeV4 游标分页 hasMore=true 正常回收）。
- CAS 词表更新（2026-10-06）：switchModelConfig/pauseGoal/**队列四件 sendQueuedNow/editQueueItem/deleteQueueItem/reorderQueueItem**/setAutoDrain 已实证为 CAS 类（队列缺 revision 被拒 "CAS commands require baseRevision and baseLogEpoch"）；`conversationWorkflowRunsV4` 不带 limit 疑似服务端缺省 0（建议显式传）。
- 会话前模型选择（2026-10-06 已接）：createSession `firstInput.modelSelection = {providerId, modelId, options?:{reasoningLevel}}`（web `U7e` 形态，档位缺席略去 options）；移动端新建会话 sheet 已接线。
- **多 workspace 已解决（v1.5，2026-10-06）**：bootstrap.tasks 即跨工作区全量任务索引（实测 252 行/26 工作区，web「所有项目目录」同源）——会话列表经 setBootstrapTasks 合并直接呈现全部项目任务。workspace-list-request 只回当前打开工作区（非枚举源）；REST windows/bootstrap 对配对 sid 404（web 专用 remoteControlToken 族）——v1.3③ 的 listTaskList scope 限制本身仍成立，但枚举改走 bootstrap.tasks 后不再是用户可见缺口。探针保留（diag.remote.bootstrap / diag.bootstrap.tasks）。
- **审批卡可用性（2026-10-06）**：卡片固定 composer 上方常驻（不再随消息滚动顶走）；重连后快照缺 pendingInteractions 键 → base:null 全量 resync 补齐一次（根因：store 重建缓存空 + 增量恢复不补发未变化键）。
- **文件页「全部批准」语义（2026-10-06 修复空实现）**：本地已阅/保留标记（全部未决文件置已批准、角标清零）——无服务端逐文件批准接口，git stage 被 ReadOnlyGate 拦截，桌面实态不变；真正的权限审批走 resolveInteraction 卡（会话内「批准执行」）。
- workflow 多 run：取消/设置命令的 workId 必须取活 run（stale 活 run 发 cancel 得 `backgroundWorkCancelRejected.not_found`；仲裁口径见协议文档 §13 v1.1④）。
- `diag.*` 清理待工作流验收后统一执行。

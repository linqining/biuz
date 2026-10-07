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

1. **【优先级最高】自测后交付**：**除客观限制确实无法自测的以外，所有开发功能必须先自测再交付**——构建过 ≠ 验证过。最低门槛建立在 §3.9 之上：UI/交互改动必须**模拟器实跑**（深链冷启 + 截图目检；能点按验证的写 live/替身 XCUITest 点按验证，先例：`FeatureCompletionE2ETests.testLive_workflowPanelCollapseScrollAndKeyboard` 真实链路点按三联症验证）；数据链路改动必须拿到**真实回执证据**（diag 键/探针回执，先例：`diag.feedback.*` 行级反馈回读取证）。交付说明里注明自测方式与证据；「没测/只编译过」必须在交付说明里显式声明并说明不可测原因，禁止默认沉默当作已测。**构建结果判定禁止 `xcodebuild … | tail` 后接 `&&`**——管道退出码取自 tail 恒 0，编译失败被吞、后续 `test-without-building` 拿旧二进制跑「全绿」（2026-10-07 P1 修复轮实证：假绿测试 + 旧截图双误导）；必须 grep "TEST BUILD SUCCEEDED"/"error:" 显式判定。
2. **信封纪律**（详见协议文档 §7.1，全部【实证】：嵌套形态、clientId 逐字相等、issuedAt 毫秒数、CAS 类带 baseRevision+baseLogEpoch（快照无 state 的会话先 resync 预种）、会话域必带 workspace 信封）。新写命令**必须走 `RemoteConversationStore.sendCommand`**，禁止自造信封（反面教材：`RemoteTaskStore.stop`）。
3. **帧 handler 先于 subscribe 注册**（中继快照帧先于回执到达，晚注册静默丢帧）。
4. **state/delta 全部「键级整体替换」**，绝不深合并（`workflowRuns`、`workspace-config` 同一口径）。
5. **所有调用走 `ZCodeServerConnection.call` 唯一出口**（ReadOnlyGate 拦截依赖它；绕过 = 边界失守）。
6. **订阅失败必须有兜底链**（conversation → readSession 对账；sessions-index → listSessions）。
7. **v4 订阅回执取不到 subscriptionId = 自愈链路失效**，视为错误处理。
8. base64 分块**各自带 padding**：整串解码失败按块独立解码拼接（附件链路实证）。
9. 中继瞬断：分块读/分页读首败 1.2s 退避重试一次。
10. 改协议相关代码后：构建 + 模拟器深链验证 + 截图目检（流程见 §3）。
11. **接口形状以 web bundle 逆向为唯一准绳，实现前必须取证**（2026-10-06 对齐修复波次教训，`docs/web一致性审查报告.md` 33 条发现根因全是「实现时未对照 web 源码」）：官方无 API 文档、上游克隆件已失，`/tmp/webshell2.js`（/remote/v4 bundle）的 zod schema + 实调用点是参数键名/值域/回执形状的唯一权威。新接/改接口**先 `rg -o` 取证**（检索式随协议文档条目固化），禁止按读侧形状推写侧、禁止按文档【宽容】候选键当事实实现（反面教材：setAssistantFeedback 的 positive/negative、attachmentChunkV4 抄读侧 ref/offset、任务分组组内 members——全必坏）。bundle 是临时目录，被清理后以协议文档固化内容为准。
    - **【权威级证据源·2026-10-07 起生效（用户裁决「不懂的你就参考 …的代码实现，都是有的」）】上游开源仓 https://github.com/zai-org/ZCode 已开放，桌面端/web 完整实现俱在，证据等级高于 bundle 逆向（【实证·上游仓】）**：不确定的接口形状直接克隆取证——`git clone --depth 1 https://github.com/zai-org/ZCode /tmp/zcode-upstream`，再 `rg` 对应实现（先例：附件四方法的 `packages/ui/src/v4/attachmentUploadTransaction.ts` + `packages/shared/src/zcode-protocol-v4/{core,transport,command}.ts`：connectionId 由桌面 facade 注入、attachmentMaxBytes=20MB、attachmentUploadMaxChunks=64、createSession strict schema providerId min(1)、CAS 权威全集 `COMMANDS_REQUIRING_BASE_REVISION`）。克隆件是临时目录——引用时以「上游仓 file:line + 键名/值域」固化进协议文档，不依赖克隆件常在。
12. **写面禁止静默吞错**（2026-10-06 用户报障「点击确认全部没反应」直接教训）：`try?`/`_ =`/返回 false 不上屏 = 假成功泛滥（审查报告根因模式 4）。写命令失败必须把服务端 fault message 原文（`RPCError.message`，非 localizedDescription——RPCError 非 LocalizedError 会丢真实信息）组装进 UI 错误行/toast；读面失败按「last-good + 失败文案」三态呈现，不得折叠成空态。E2E 替身不校验载荷形状——本地全绿 ≠ 真机可用，写面形状靠 bundle 取证 + 真机探针双保险。
13. **Mock 演示数据仅测试用途**（用户裁决「Mock 假数据全部移除，仅测试用例允许」，2026-10-06 落地）：Mock 三件仅在启动参数 `-ZCodeDemoData`（E2E 演示开关，`AppSession.isDemoDataEnabled`，AppSession.swift:247-253）时装配；**正式用户路径永不装配**——未连接/断线/失败态一律 Empty*Store 空实现（读面空、写面如实失败，`StoreEnvironment.swift:14` 起）。禁止在任何正式路径恢复 Mock 装配或新增假数据分支；后续演进方向为移入测试 target/彻底删除。
14. **SwiftUI Menu 的 a11y identifier 纪律（2026-10-07 门禁五连跑实证，test12/test05 交付）**：①identifier 必须挂 **Menu 本体**——挂 label 内视图会被运行时逐层拼接成「id-id-…」，XCUI 精确匹配命中惰性子元素、tap 落空菜单永不弹出（ChatView.swift:1642 先例，chips 四胶囊同坑复修一次）；②Menu 的宿主行必须包 `.accessibilityElement(children: .contain)`——裸 HStack 的 identifier 会把整行合并成单 a11y 元素，Menu identifier 根本不出树（remoteChips 修前 XCUITest 永远找不到 05-chip-\*）；③菜单开弹判定用「菜单项出现」为证据（`openTargetMenu`：tap 后短轮询候选、有界重开），禁用 tapUntil——菜单开着时 trigger 被遮挡再 tap 会开合互打；④SwiftUI List 滚动查找必须加「元素帧落在应用界内」判定（离屏已物化行报 exists/isHittable=true 陈旧帧，只查 isHittable 会跳过滚动点空，test07 实证），且详情页滚动用「对消息区元素本身 swipe」（`05-message-scroll`）——坐标拖拽从屏幕中心起滑会被常驻审批卡吞手势。E2E 基建：`openConversation`（行导航三合一）、`scrollToHittable`（双向+帧界内+元素 swipe）、`isOnScreen`。
15. **自绘 sheet 确认回调禁回读宿主 @State（2026-10-07 重置卡回归教训，用户报障「改了样式什么都不能用了」）**：`.sheet(isPresented:)` 的确认按钮先 `dismiss()` 时，binding set(false) **先于** onConfirm 闭包执行——回调里回读 `pendingXxx` 已是 nil，写命令永不发出（假成功弹层关闭）。一律**呈现期捕获传参**：`if let value = pendingXxx { Sheet(value: value) { confirm in 执行(confirm); pendingXxx = nil } }`，确认按钮只回调不自 dismiss（关闭由 handler 置 nil 单路径）。回归门 test14（替身记账断言「确认必发、取消零发」）。
16. **xcodebuild 判定与 DerivedData 纪律（2026-10-07 全量门禁 8/8 假红教训）**：①构建结果判定**禁止 `xcodebuild | tail` 管道链**（管道退出码恒 0 吞编译失败）——显式 `echo exit=$?` + `grep -c "error:"` + 匹配 `** TEST BUILD SUCCEEDED **` 字面量；②`test-without-building` **必须显式 `-derivedDataPath`** 与 build-for-testing 同一路径——缺省时解析 `~/Library/Developer/Xcode/DerivedData` 的陈旧产品（旧 app 二进制 + 新 runner），连「登录后回主界面」级别的基础用例都全数假红（本机该目录曾现 10-03 的旧 ZCodeMobile 产品）；③模拟器多开（本机 5 台 Booted）下 XCUITest 间歇「Lost connection to application」/ 软键盘不出（typeInto 失败）——先单跑失败用例复判，非确定性症状归环境不归代码。

## 6. 未决事项速查（接手先看）

- **中继单连接槽 + 真机 -1001 超时（2026-10-07 用户报障，待取证）**：用户真机走云中继连接 `https://zcode.z.ai/ws?mid=…` 报 `NSURLErrorDomain -1001 请求超时`，同轮用户明示**「同一时间只有一个链接，多个没用」**——验证必须串行化：模拟器 E2E 走本地替身（127.0.0.1）不占真实槽，真机中继验证不得与其他客户端（web/另一模拟器/前会话残留）并行；真机复测前先确认桌面无其他活动远控客户端。根因（服务端单槽拒新/网络/端点形态）待桌面在线轮 diag.remote.* + 桌面日志取证。
- **新建会话带附件路径的 modelSelection 取舍（v1.18 如实声明）**：带附件开始任务 = draft 创建（createSession 不携 firstInput），modelSelection 无通道——会话内 chips 可再选；如需会话前选择，后续可在 draft 创建后补 switchModelConfig 一跳（现未做，避免多一次 CAS 写）。
- ~~`RemoteTaskStore.stop` 信封违规~~已修（2026-10-06：委托 `RemoteConversationStore.stopTurn` 走统一 `sendCommand`）。
- **信封 sessionId 键恒在场**（2026-10-06 探针实证）：createSession 传 null、其余传目标 id；键缺省被 zod 拒（曾致移动端 createSession 对真实桌面静默全失败）。新写命令一律走 `sendCommand`，禁止自造信封（该教训再次印证）。
- **CAS stale 重试**（2026-10-06 探针实证）：`proto.staleRevision`/status "stale" 时原样重发一次即命中（sendCASWithRetry）；队列五件/pauseGoal/resumeGoal/retryTurn 已接入，新 CAS 命令接入时沿用。**2026-10-07 三段硬化**：活跃会话单发必撞 stale（diag.wf.control.ui 实证 revisionAtDecision=12119）——重发再 stale 时清 revision 缓存 + resync 取权威 revision 后末次重发（`sendCASWithRetry` 统一承担，switchModelConfig/switchCollaborationMode/setFollowupMode 已接入）；替身 stale-once 绊线注意信封命令实经 `sendConversationCommandV4`（裸方法名分支永不匹配，test12 首版计数恒 0 教训）。
- **会话列表组织态权威源 = membership join（2026-10-07，用户报障「桌面置顶 4 项移动端只见 1 项」「归档第三次丢失」）**：上游 sessionSummarySchema【实证·上游仓】无 pinned/archived 字段——组织态读 `zcode-task` listPinnedTasks/listArchivedTasks 逐 scope 并发拉取后客户端 join（`refreshTaskMembership`，协议文档 §9.3/§10）；列表渲染三级合成 `本地 override ?? membership ?? summary.pinned`；setArchived 失败完整回滚 + fault 原文上屏。门禁 test12 回归。
- **队列 CAS 已活体验证**（-ZCodeDiagQueueCASProbe 12 步全 accepted，置顶后队列顺序实际改变）；新建会话 createSession/modelSelection 载荷同轮活体验证（result.sessionId=sess_* 规范 id）；**stop 命令已活体验证**（-ZCodeDiagStopProbe，对运行中 turn accepted）。三探针保留可复跑。
- **PTY 阻塞根因确诊（2026-10-06）**：内核 PTY 池耗尽——`kern.tty.ptmx_max=511`，`pty.openpty()` 直接报 "out of pty devices"（expect/tmux 同样失败），而用户态仅 3 个 zsh 持有 ttys（其余为内核层泄漏——立项报告「PTY 泄漏」的确切机理）。**恢复办法**：`sudo sysctl -w kern.tty.ptmx_max=999`（临时）或重启（彻底），之后即可跑门禁 E2E（test08 已编译就绪：`xcodebuild test-without-building -only-testing:ZCodeMobileUITests/FeatureCompletionE2ETests/test08_newConversationModelSelectionCarriedInFirstInput`）。
- 探针残留：5 个标题带「探针」/「请慢慢数数」的会话仍投影在会话列表——deleteTask 只删 task-index，deleteSession 对有行会话报 sessionNotFound（仅 draft/空会话可回收）；残留为桌面真态，需桌面端侧删除。清理探针 -ZCodeDiagCleanupProbe 保留。
- ~~「加载更早消息」~~复验通过（2026-10-06 diag 实据：before=185 after=381，conversationRowsRangeV4 游标分页 hasMore=true 正常回收）。
- CAS 词表更新（2026-10-06）：switchModelConfig/pauseGoal/**队列四件 sendQueuedNow/editQueueItem/deleteQueueItem/reorderQueueItem**/setAutoDrain 已实证为 CAS 类（队列缺 revision 被拒 "CAS commands require baseRevision and baseLogEpoch"）；`conversationWorkflowRunsV4` 不带 limit 疑似服务端缺省 0（建议显式传）。
- 会话前模型选择（2026-10-06 已接）：createSession `firstInput.modelSelection = {providerId, modelId, options?:{reasoningLevel}}`（web `U7e` 形态，档位缺席略去 options）；移动端新建会话 sheet 已接线。
- **多 workspace 已解决（v1.5，2026-10-06）**：bootstrap.tasks 即跨工作区全量任务索引（实测 252 行/26 工作区，web「所有项目目录」同源）——会话列表经 setBootstrapTasks 合并直接呈现全部项目任务。workspace-list-request 只回当前打开工作区（非枚举源）；REST windows/bootstrap 对配对 sid 404（web 专用 remoteControlToken 族）——v1.3③ 的 listTaskList scope 限制本身仍成立，但枚举改走 bootstrap.tasks 后不再是用户可见缺口。探针保留（diag.remote.bootstrap / diag.bootstrap.tasks）。
- **chips 模型同步（2026-10-06，用户报障「桌面改了手机不同步」）**：桌面 composer 变更落会话级 state.modelSelection（不走 workspace 级 getView/onDidChange）；手机以 state 为权威源覆盖 chips 显示（三路：load/observe 每事件/onDidChange 回流），词表/分组仍取 getView。详见协议文档 §10/v1.7。
- **额度窗口映射修正（2026-10-06，用户报障「33% 是 5 小时不是每周」）**：quota.limits 按 type+unit+number 三元组匹配（5小时=TOKENS_LIMIT/3/5、每周=TOKENS_LIMIT/6、工具调用=TIME_LIMIT/5/1、MCP=mcpQuota.aggregate），与数组顺序无关；percentage 是 0–1 已用小数（剩余=100−pct×100），v1 条数套餐用 currentValue/usage/remaining。详见协议文档 §9.10。
- **审批卡可用性（2026-10-06）**：卡片固定 composer 上方常驻（不再随消息滚动顶走）；重连后快照缺 pendingInteractions 键 → base:null 全量 resync 补齐一次（根因：store 重建缓存空 + 增量恢复不补发未变化键）。
- **git 写族已放开 + 逐文件批准接线（2026-10-06，用户裁决「和 web bundle 保持一致」）**：ReadOnlyGate git 频道写族全放行（桌面代执行，web 同参同面：`stagePaths {workspacePath, paths}`）；文件页批准=git.stagePaths、拒绝=git.unstagePaths（可逆）、全部批准=批量 stagePaths——桌面 git 实态随之变化（文件移入已暂存）。破坏性命令（discardPaths/push）网关已放行但 UI 未接，接入时必须带确认弹层；file 频道默认拒绝不变。~~v1.5 前的「本地已阅标记」过渡语义~~已废弃。
- workflow 多 run：取消/设置命令的 workId 必须取活 run（stale 活 run 发 cancel 得 `backgroundWorkCancelRejected.not_found`；仲裁口径见协议文档 §13 v1.1④）。
- **P1/P2/P3 接入波次（2026-10-06，桌面端不在线，协议文档 §13 v1.10）——新命令 CAS 证据情况**：发送层扩容 8 条信封命令，`switchCollaborationMode`/`setFollowupMode`/`setAssistantFeedback`/`editUserQuery` 依 CAS 权威全集（web vle/yle，bundle 取证【移植级】）走 `sendCASWithRetry`；`compact`/`snoozeInteractionAutoResolution`/`startSavedWorkflow` 不在全集按普通信封发；~~`sendGoalCommand` 按 CAS 预期发~~**已降普通信封直发**（2026-10-06 §四口径清理：web CAS 词表 Jle 15 命令确无它，属 input 类 baseRevision 可选）。git-commit/createCheckpoint/restoreBetweenCheckpoints/setting.update/uninstallPlugin 为频道直发命令（非信封），写级不自动重试（防双重提交/重复快照/双写）。
- **对齐修复波次回写（2026-10-06，协议文档 §13 v1.11；`docs/web一致性审查报告.md` 33 条发现全处置，`docs/web接口对齐盘点报告.md` v2 重扫）**：A-1~A-4/B-1~B-9/C-1~C-16/L-1/L-2 形状全部按 web bundle 逆向定形闭合（附件事务整链重写、resolveInteraction 三族、任务分组族、设置/权益/重置卡、git 族、传输层切换+错误帧收口）；U-1~U-9 降级 + H1-H7 隐藏入口落地；回归①-⑧修复。**全部【移植·bundle 逆向】形状零真机取证**。
- **真机探针结果（2026-10-06 本轮）**：`liveProbed=false`——目检代理失败（模型侧 WorkflowError: Subagent turn failed: Model creation failed），已降级为确定性实拍（simctl 安装/启动/截图 2 张），**截图未经判读**；gate 边界测试未尝试。全部新形状的真机取证顺延至桌面在线轮（§5-10 双保险的另一半）。
- **待真机验证清单（v1.11 后剩余，命中后回填协议文档升格【实证】）**：① 全部 bundle 逆向形状首击回执（扩容 8 命令 + hook 四命令 + 附件四条 + sendText attachments）；② 附件 connectionId=registeredClientId 等价性 + attachmentMaxBytes/UploadMaxChunks/ChunkMaxBytes 真值（bundle 外）；③ `setAssistantFeedback` 服务端回读字段（现仅会话内存）；④ userInput/assistantText 行 entityId 字段名（宽容键组）；⑤ getEntitlementSnapshot 回执复核（`diag.usage.entitlement` dump）+ remaining 单位语义；⑥ getBranchComparison 真实回执（web 仅 mock）；⑦ ~~workspace-reconnect 桥重开后是否要求重握手~~**已闭合（2026-10-07 真机桌面日志 + 上游仓双实证，协议文档 §5.3/v1.17）**：每次桥(重)开（首连/断线恢复/degraded/切换）桌面侧都是全新 scoped facade、`handshakeComplete` 实例态——**必须重做 hello→clientHello**，否则 assertReady 类调用（subscribe*/readSession/rowsRange）全线 `fault.connection.handshakeRequired`（sendConversationCommandV4 不受握手闸、照常 OK——「能发送、收不到」形态之一）；修复=握手单点接管于 `RelayChannelClient.openBridge`（onBridgeOpened 钩子，首连/重建/切换三路全汇入）+ 瞬态失败 1.2s 重试；剩余子项：workspace-list-updated 载荷；⑧ settingService/setting 真实频道名（双候选）；⑨ git commit hash 键名与 nothing-to-commit 拒收形态、getIdentity 回执字段；⑩ getPluginsOverview 单频道回执键完备性 + installPlugin scope:'user' 旧桌面拒认；⑪ provider-settings.getView providers[].accountState 词表；⑫ mobile-view-state-update platform 值域（现报 "ios"，viewState 不生效首查此值）；⑬ onDynamicWorkspaceEvent reason 词表（diag.task.wsEvent）；⑭ hook 审核 reviewItems 元素键与 workspaceHookAdmission 形状。**新登记诊断键 `diag.task.wsEvent`（+diag.archived 格式更新）已入协议文档 §11，真机验收后统一清理**。
- **会话域订阅/读面必须按会话归属 workspace 寻址（2026-10-07，用户报障「手机端没有回复」根因；协议文档 §9.1.1/v1.17）**：上游 `subscribeConversationV4` 由 `getReadOnlyClient(params)` 按 **params.workspacePath** 选 CLI 进程承接订阅——移动端 `applySessionTarget` 曾恒用连接 workspace，中继桥绑定桌面当前窗口 workspace 而会话建于他区时，**历史快照可读（全局 db 冷恢复）但运行中 turn 的行增量永不抵达**（伴生证据：同 sessionId 在两个 workspace 的 tasks-index 各落一行，同 traceId）。现订阅/退订/resync/rowsRange/readSession/附件族统一经 `workspaceTarget(for:)` 寻址（写面 v1.16 ① 同源）；resync 缺 workspace 信封会被上游 `resyncOwned` 以 `fault.subscription.notOwned` 拒（ownership 按 workspaceKey(params) 匹配）。门禁 test13 回归（替身 `subscribeTargets` 断言面）。
- **隐藏入口待恢复清单（恢复条件均为真机探针，HIDDEN 标记在位）**：H1 反馈工单（SettingsView.swift:396——feedback.list 双重未取证，恢复条件=回执成形）；H2 startSavedWorkflow 启动（P2ExtrasViews.swift:1091/:1125——不在 web 枚举，恢复条件=accepted）；H3 检查点入口（FileTreeView.swift:294/:168——三方法 web 0 命中，恢复条件=取证+协议文档立条）；H4 仓库/语音 chips 与 H5 composer 目标菜单（`AppSession.isDemoDataEnabled` gating 隐藏，E2E 依赖保留——常驻化属产品裁决）；设备页云端沙盒行（P2ExtrasViews.swift:728）。
- **接线遗留**：mobile-view-state-update 场景②（打开任务上报 activeTaskId）——连接层 API 已备（`ZCodeServerConnection.reportActiveTask`，:719 → `RelayChannelClient.updateActiveTask`，:439），触发点在 ChatViewModel/视图打开会话处待接（场景①③已闭环）。
- **跨工作区元数据写寻址未收口**：archiveTask/unarchiveTask 已按任务行归属工作区寻址；setTaskPinned/renameTask/setTaskUnread 仍发当前连接 workspace（web 语义同为行自带工作区），跨工作区行可能落错区——待同口径跟进（协议文档 §12-6）。
- **⚠ Localizable.xcstrings 事故（2026-10-06，实现者如实上报）**：一轮恢复文件格式时误执行 `git checkout -- ios/ZCodeMobileApp/Resources/Localizable.xcstrings`，抹掉工作区中**其他波次未提交的约 131 行 en 翻译条目**（未 staged，git 不可恢复）——受影响波次的新增文案在 en 语言态回退显示中文（zh-Hans 源语言不受影响，无编译/功能影响）；相关波次需按其新增 `String(localized:)` 字面量重补 en 条目。
- `diag.*` 清理待工作流验收后统一执行。

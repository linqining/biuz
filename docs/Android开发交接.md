# Android 开发交接文档

> **日期注记**：2026-10-09 创建。证据基础：本 workspace 全部 42 个主会话（`~/.zcode/cli/db/db.sqlite`，含 20 个百条以上长会话）逐会话考古 + `docs/` 全量复核 + 当前工作区代码状态核对。本文是**专题交接文档**（§4.1 口径）：协议事实的唯一权威仍是 `docs/协议接口文档.md`，不变量原理见 `docs/架构根因分析.md`，本文与两者冲突时以它们为准；本文独有的是「会话考古问题全集 + 产品裁决速查 + Android 移植路线」。

---

## 0. 一句话与红线

**BiuZ**（iOS bundle `cn.biuz.mobile`）：ZCode 社区版桌面端的移动遥控台。手机经局域网直连（`GET /ws?token=…`）或云中继（`wss://zcode.z.ai/remote/v4`）与桌面端建立 WebSocket RPC 通道，实现「会话浏览 / 消息与审批 / 文件与 Diff 只读 / workflow 面板 / 模型切换」。

**产品边界（用户原话裁决）**：「只读是指用户客户端依然是可以发送命令执行，只是执行修改的进程在电脑或者云端，手机不会编辑电脑或者云端中的文件」——手机可发命令、桌面代执行；`ReadOnlyGate` 在 RPC 出口拦截文件直写类（file 频道默认拒绝；git 频道写族已放行——批准=stagePaths、拒绝=unstagePaths，桌面代执行同 web）。

**Android 红线**（违者复刻 iOS 已付出的 P0 事故）：
1. 一切 RPC 走唯一出口（Connection.call），ReadOnlyGate 挂在出口上；
2. 一切写命令走单点信封 Factory + schema 校验，禁止手拼 payload 键名；
3. ACK 非 accepted 必上屏 reasonCode+message **原文**（非本地化描述）；
4. Mock/演示数据仅测试 target 装配，正式路径空实现如实失败；
5. 所有新功能先自测再交付（用户裁决的最高优先级纪律，iOS 侧 AGENTS.md §5-1）。

---

## 1. 必读文档地图（按阅读顺序）

| 顺序 | 文档 | 评级 | 对 Android 的价值 |
|---|---|---|---|
| 1 | `docs/协议接口文档.md`（1136 行，§9 含 34 方法条目、§13 含 v1.0→v1.36 共 37 条变更） | ⭐必读 | **实现接口形状只认此文档**：帧协议、握手、信封纪律、15 频道方法、行模型、state 投影。注意：头部版本戳 v1.26 落后于 §13 最新 v1.36，以 §13 为准 |
| 2 | `docs/架构根因分析.md`（187 行） | ⭐必读 | 15 条协议不变量（A1–A15，上游 file:line）+ 四大静默陷阱 + 官方五件套设计原理。Android 每条都必须等价镜像 |
| 3 | `AGENTS.md` §4.5 术语表 + §5 工程纪律 + §6 未决事项 + §7 总纲 | ⭐必读（选段） | §5-2/3/4/5/6/7/12/13 跨平台成立；§5-14~18 为 iOS 工具链专属可跳过；§6 = 已知 bug/待取证清单（Android 直接绕过这些坑） |
| 4 | `docs/web一致性审查报告.md`（220 行，33 条） | ⭐⭐选读 | 反面教材目录：每条「必坏类」都是 Android 写侧形状的对照检查项（见本文 §4 甲类表） |
| 5 | `docs/web接口对齐盘点报告.md`（109 行）+ `docs/对齐修复与降级体验设计.md`（489 行） | ⭐⭐选读 | 产品红线 skip 清单；未连接态四态呈现/降级交互规格（Compose 重做 UI 直接对照） |
| 6 | `docs/P1P2P3功能UI设计.md`（846 行） | ⭐⭐选读 | 11 项功能的状态矩阵与协议缺口；插入点 file:line 已随 iOS 演进漂移，勿按行号找 |
| 7 | `docs/relay-handoff.md`（29 行） | ⭐⭐选读 | 中继配对链接形态，细节以协议文档 §3.3/§9.15 为准 |
| 8 | `docs/立项报告.md`（1395 行，= `project-proposal.md` 完全重复，读其一） | ⭐⭐⭐可跳过 | 历史证据存档；早期结论部分已被推翻，仅考古时查 |
| 9 | `docs/qoder-comparison.md`（77 行） | ⭐⭐⭐可跳过 | 竞品交互提取，做 UI 差异化再翻 |

---

## 2. 可移植资产清单（iOS → Android）

iOS 侧共 59 个 Swift 文件 / 42,612 行。**约 1/3 是纯协议逻辑，可近乎直译 Kotlin**。

### 2.1 纯协议逻辑（零 UI 依赖，直译级）

| iOS 文件 | 行数 | 内容 | Android 对应 |
|---|---|---|---|
| `Services/RPC/V4Wire.swift` | 225 | RPC 帧编解码（13 字节帧头） | 纯 Kotlin |
| `Services/RPC/RPCSerialization.swift` | 377 | 值序列化（上游 serialization.ts 逐行移植） | 纯 Kotlin |
| `Services/RPC/ChannelClient.swift` | 375 | channel 层请求/响应管线 | 纯 Kotlin |
| `Services/RPC/ProtocolSchema.swift` | 315 | zod 语义引擎（参数 strict / payload strip / 可信字段黑名单） | 纯 Kotlin |
| `Services/RPC/CommandSchemas.swift` | 414 | 上游 zod schema 逐字段移植（CAS 词表/ACK 六态/22 命令/附件限额） | 纯 Kotlin |
| `Services/RPC/ConversationCommandFactory.swift` | 128 | 信封单点 builder（CAS 缺水位构造期即拒） | 纯 Kotlin |
| `Services/Relay/RelayFrameCodec.swift` | 246 | 中继 rpc-frame 编解码 | 纯 Kotlin |
| `Services/Relay/RelayTransport.swift` | 928 | 中继传输 | URLSession→OkHttp 替换点 |
| `Services/Relay/RelayChannelClient.swift` | 721 | 信道客户端 + 开桥重握手单点 | Kotlin 协程适配 |
| `Services/Relay/RelayLink.swift` | 105 | 配对链接解析 | 纯 Kotlin |
| `Services/Remote/ReadOnlyGate.swift` | 296 | 写边界策略表 | 纯 Kotlin |
| `Services/Remote/ConnectURLParser.swift` | 186 | 连接 URL 解析 | 纯 Kotlin |
| `Stores/Remote/AckActivationBarrier.swift` | 77 | 订阅回执前帧暂存屏障 | 纯 Kotlin |
| `Services/Attachments/AttachmentUploadService.swift` | 329 | 分块上传事务 | 纯 Kotlin |
| `Models/Models.swift` | 631 | 行模型/state 投影数据结构 | kotlinx.serialization |

### 2.2 逻辑同构、需适配并发模型（Swift 并发 → Kotlin 协程）

| iOS 文件 | 行数 | 说明 |
|---|---|---|
| `Services/Remote/ZCodeServerConnection.swift` | 1194 | 连接管理 + `call` 唯一出口 + 帧面多路挂载，**架构核心必须同构** |
| `Stores/Remote/RemoteConversationStore.swift` | 5646 | 全部会话域业务 RPC + 乐观回显/差分/CAS 自愈，**最大最核心移植对象** |
| `Stores/Remote/RemoteFileStore.swift` | 942 | 文件/Diff 只读链 |
| `Stores/Remote/RemoteTaskStore.swift` | 549 | 任务索引/membership join |
| `Services/Remote/AppSession.swift` | 1358 | 装配层，参考装配顺序即可 |
| `Stores/StoreProtocols.swift` | 831 | Store 协议面，接口定义可平移为 Kotlin interface + 默认空实现 |

### 2.3 UI 层重做（不移植，看设计资产）

`Features/` 约 19k 行 SwiftUI 重写为 Compose。平台专属替换：Keychain→Keystore/EncryptedSharedPreferences、`ASWebAuthenticationSession`→Chrome Custom Tabs、UNUserNotificationCenter→FCM。**注意移植 UI 时的行为规格**（不是像素）：审批卡常驻 composer 上方且**限高+内部滚动**（键盘在场时被盖是 iOS P0 事故，见 §4 丙-9）、消息 markdown >6000 字截断+展开收起、工具行/引用卡/计划卡的渲染层规则见协议文档 §10.1。

### 2.4 测试资产

- **可直接平移**：`ios/UnitTests/` 14 个文件——ProtocolSchemaTests(296 行)/CommandFactoryTests(225)/ReadOnlyGateTests(357)/AckActivationBarrierTests(81)/RelayLinkTests(347)/CompletenessUnitTests(418)/UserSelectionParsing/PlanToolCallParsing/ActorTranscriptMapping/ProviderDisplay/WorkflowActorBinding/DraftHandoffModelSelection/BackgroundWorkPanel/TaskNotificationContent。断言的是协议形状，Kotlin 测试逐用例平移。
- **高价值参考**：`ios/Tests/E2ELoginStubServer.swift`（3118 行）——本地替身服务器实现协议握手/快照/增量/命令回执伪造，**协议行为平台无关**，Android 用 Ktor/MockWebServer 同构实现。
- **场景规格**：FeatureCompletionE2ETests（2020 行 21 用例）当 Compose 测试的场景清单。

### 2.5 品牌与设计资产

- `branding/logo/`：6 个 SVG 源稿 + PNG 渲染件（iOS 尺寸；**Android 从 `biuz-mark.svg` 导 adaptive icon**）；`branding/naming.md` 商标避险报告——应用名 BiuZ，**商店 listing 禁现「ZCode」「Z.ai」字样**，用 "works with ZCode" 表达；上架前复核 Google Play 侧占用。
- `design/design-spec.md`（84KB）：设计令牌（色板 `#0a0b0d` 近黑 + `#32f08c` 强调绿、字号、间距→Compose Theme）、12 屏布局、组件规范。
- `design/*.html` 可交互样稿（视觉基准）；Pixso 设计基准 file key `AbZPdSg5AO6CqRDpr5RKwg` 画板 `5:3026`。

---

## 3. 协议层铁律（速查，权威见架构根因分析 §2）

**15 条不变量**：A1 信封 sessionId 键恒在场（createSession 传 null）｜A2 clientId 单一来源｜A3 CAS 命令双字段 `baseRevision+baseLogEpoch` 同发（兼容两代桌面）｜A4 参数层 strict / payload 层未知键静默剥离｜A5 hello→clientHello 顺序、能力键只回显 host 宣告过｜A6 桥重开=全新握手态、`sendConversationCommandV4` 不过握手闸｜A7 订阅按 params.workspacePath 选 CLI 进程、帧按 workspaceKey 分发、无跨区聚合｜A8 快照帧可先于订阅回执｜A9 atLogEpoch 不符整批作废｜A10 delta 键级整替不深合并｜A11 附件 20MB/64 块/512KiB（客户端分块 384KiB）、connectionId 服务端注入禁伪造｜A12 commandId 重试不变防双写｜A13 ACK 六态词表｜A14 确定性拒收终态不重试｜A15 pinned/archived 不在协议摘要、membership join 另源。

**四大静默陷阱**（无错误码，表象全是「点了没反应/没有回复」）：① payload 未知键静默剥离；② 桥重开后命令面无握手闸（发送成功/读面 handshakeRequired）；③ 订阅寻址错 workspace（快照可读/增量永不到达）；④ 帧 handler 晚注册（纯丢帧）。

**分诊口诀**：发送面查 ACK status 词表 → 读面查握手/寻址/帧序 → 三条都正常才归客户端工程。

**官方五件套的 Android 等价物必须落地**（Kotlin 形态）：① schema 同源校验器（kotlinx.serialization + 手写 strict/strip 校验，镜像 ProtocolSchema）；② 信封单点 Factory（CAS 词表缺水位构造期即拒）；③ ACK 激活屏障（帧暂存→subId 落库回放，overflow→全量 resync）；④ CAS 前置自愈内建于 sendCASWithRetry（revision 预取结构上不可能漏）；⑤ ACK 统一读面（六态+reasonCode/message 原文）。

---

## 4. 会话考古：问题全集（按根因分类，含 Android 预防）

> 双口径统计（50 会话 + 58 事故）：协议形状/信封错 ∪ 架构寻址/时序 ≈55–60% 且**全部 P0 在此簇**；客户端工程 ≈25%；工具链 ≈12%；服务端 ≈8%。定性：不是桌面架构有缺陷，而是「客户端未镜像官方客户端的架构保障」。

### 甲、协议形状/信封错（33 条 web 一致性审查 + 各轮补刀，精选必坏类）

| # | 症状 | iOS 犯过的错 | 正确形状（Android 直接抄） |
|---|---|---|---|
| 1 | 反馈点了没效果 | `setAssistantFeedback` 发 `positive/negative`（值来自文档「未取证」候选行） | 枚举 `like\|dislike`；行投影回读 `feedback` 字段 |
| 2 | 附件上传必坏 | 自造 `attachmentPut` 帧、抄读侧 `ref/offset` 键、无 Begin/checksum、多发 `connectionId` | `attachmentBeginV4`(sha256+totalChunks)→`attachmentChunkV4`(**chunkIndex** 非字节 offset)→`attachmentCommitV4`；参数仅 `workspace 信封+sessionId+uploadId`；**绝不发 connectionId**（facade 注入，多发即 Invalid params） |
| 3 | 审批/问答应答无效 | `{approved,scope}` 平铺+裸字符串 | 恒为对象三族：权限 `{optionId}`（allowOnce/allowAlways/rejectOnce/rejectAlways）、计划 `{action:"accept"/"decline"/"cancel"}`、提问 `{freeText}` |
| 4 | 带附件消息失败 | `{name,mediaType,size}` | sendText 附件 `{fileName,mime,bytes}` |
| 5 | 任务分组错乱 | 组对象内找 members、组名字符串当 groupId | members 在**顶层** `members[]` 按 groupId 归组 |
| 6 | 套餐重置无效 | 嵌套 `scope:{…}` | 平铺 `{preferredProviderId, accountAccess, idempotencyKey, resetType}`（**扣费接口，必须二次确认弹层**） |
| 7 | 设置写面无效 | `{key,value}` | `{设置名:新值}` 单键补丁对象 |
| 8 | 插件卸载失败 | `{name}` | `{workspacePath(必填), pluginName, marketplace, scope:"user"}` |
| 9 | 权益页空白 | 找不存在的 `entitlements[]` | `quota.level`+`subscription.details`+`remaining`；额度按 type+unit+number 三元组匹配 |
| 10 | 投递模式错 | 「立即」档也下发 | `setFollowupMode` 枚举仅 `queue\|guide`；立即=逐消息 `requestedDelivery:"startNow"` |
| 11 | createSession 静默全败 | 信封 sessionId 键缺省；providerId 空串裸发 | sessionId 键**恒在场**（createSession 传 null）；`providerId: trim().min(1)` 空串必被 zod 拒 |
| 12 | 会话域 RPC 被拒 | 嵌套 `workspace:{}` 对象（被网关参数规范化剥掉） | 扁平 `workspacePath`+`workspaceIdentity?` 字段 |
| 13 | 思考档必拒 | 静态兜底梯含 medium（GLM-5.3 词表只有 low/max/high） | 词表权威源 = getView `providers[].models[].config.optionSpecs.reasoningLevel.values`（models[] 是对象非字符串）；模型条目 value 是复合键 `providerId/modelId` |
| 14 | 工作区切换失效 | reconnect 键前置携带切换参数 | 切换=`workspace-bridge-open(新key)`+`mobile-view-state-update`；reconnect 仅 3 键且只用于重连按钮 |

**Android 预防**：写侧形状一律先取证——上游仓 `git clone --depth 1 https://github.com/zai-org/ZCode` rg 取证（>【实证·上游仓】）> `/remote/v4` bundle 的 zod schema + 实调用点（bundle 是临时目录，被清理以协议文档固化内容为准）> 真机探针回执；**禁止按读侧形状推写侧、禁止拿文档【宽容】候选键当事实实现**（上表 1/2/5 全灭于此）。

### 乙、架构寻址/时序/生命周期（全部 P0 级事故的另一半）

1. **订阅/读面/帧面/信封四面同 workspace**：会话行自带 workspacePath，一律按归属寻址（`workspaceTarget(for:)` 同构）；帧面 eventListen 要按 workspace **多路挂载**（上游按 workspaceKey 硬分发射帧，无跨区聚合）——只修订阅寻址不修帧面，照样「空会话+本地拒发」（v1.17→v1.23 两轮才闭环）。
2. **桥重开=全新握手态**：hello→clientHello 单点接管于桥打开钩子（首连/断线恢复/degraded/切换全路径），瞬态失败 1.2s 重试一次；`call` 出口捕获 `fault.connection.handshakeRequired` 自愈重试一次。
3. **快照帧可先于订阅回执到达**：帧 handler 先于 subscribe 注册 + ACK 激活屏障（暂存回放）；v4 回执取不到 subscriptionId = 错误路径（conversation→readSession 对账兜底）。
4. **CAS 全链**：revision 未就绪本地拒发（合成 `client.casRevisionUnavailable`，不空耗注定被拒的信封）；stale 三段自愈（原样重发回填 revisionAtDecision、commandId 不变 → 清缓存+resync 取权威 → 末次重发）。
5. **乐观回显消费**：服务端用户行携 `sourceCommandId`，与发送 commandId 精确匹配消费；回显消费走「中列 id 漂移→整表替换」而非 append；直发位点同步 `lastYieldedMessages` 基线（否则双气泡残留，v1.31/v1.32 两轮）。
6. **跨工作区幽灵行（上游 bug，至今存留）**：桌面 `seedTaskMetaIfMissing` 按订阅工作区给同一会话写多行 task-index（2026-10-09 实查 zcode_mobile 的 sess_cc65c6da 仍同时落在 mtt_mobile/poker_protocol）；移动端合并器**必须允许权威行纠正**（「只补缺不纠正」会让错误归属永久冻结）；行 workspacePath 缺失时禁止拿 workspaceId 当分组键；连接 scope 展示用真实路径末段非服务器名。
7. **store 换源必须重绑**：连接完成前进会话页的视图会永久抱空 store——Android 用对 store 实例 key 的重组/ViewModel 重建（iOS 教训：列表页修了 `.task(id:)` 会话页漏修，同型 bug 要全局排查）。
8. **凭据读取抖动自愈**：Keychain/Keystore 冷启动瞬时报错不可吞成「无凭据」静默进空态——UserDefaults/DataStore 镜像 + 读空回填（iOS 曾因此静默进演示模式）。
9. **时间字段双形态**：sessions-index 行 `lastActivityAt`（毫秒/ISO 双形态）vs 跨工作区 `bootstrap.tasks` 行只有 `updatedAt`/`createdAt`——多键回退、解析失败不渲染日期（曾出「2025 年前」）。
10. **缓存镜像 web**：30s keep-warm 会话租约池、订阅快照自带尾部行（跳过 rowsRange 双份拉取）、rowsRange 只向旧翻页、读列表不做 26 工作区串行预取（web 快的本质是不重拉）。

### 丙、客户端工程（吞错假成功 + UI 状态）

1. **写面禁静默吞错**（用户报障「点击确认全部没反应」直接教训）：写命令失败必须把服务端 fault message **原文**（`RPCError.message`，非 localizedDescription）组装进错误行/toast；读面失败按「last-good+失败文案」三态呈现，禁折叠成空态。
2. **确认弹层回调禁回读宿主状态**：iOS 的 `.sheet` 确认按钮先 dismiss 时 binding set 先于 onConfirm——回调里读 `pendingXxx` 已是 nil，写命令永不发出（假成功）。Android 同型：Dialog 的 confirm 回调里读 dismiss 后才清理的状态；一律**呈现期捕获传参、确认只回调、关闭单路径**。
3. **ACK 判定**：「收到回执」≠成功；六态词表非 accepted 一律失败上屏；`isAuthoritativeSuccess`（词表内非失败）才允许落盘持久化（协作模式 chip 教训：宽容判定误落盘后与桌面永久脱钩）。
4. **模型切换三防**：getView 失败时用会话级 `state.modelSelection.providerId` 兜底（**禁空串裸发**，provider 必填必拒）；同名模型跨套餐用「套餐|模型」复合键解析 providerId、下发 model 恒纯模型名；模型面板空清单给「重新获取」行（禁「拒开+一次性重试」死胡同）。
5. **视频链四件**：选择器 filter 含 video；缩略图组件有 video 分支（帧提取封面）；文件落**稳定缓存目录**（tmp/可清除缓存切后台即被系统回收）；播放器失败上屏（不支持格式黑屏无声是坑）；单一全屏播放器宿主。
6. **渲染层解析纪律**：userselect 尾块（`# userselect:` + ```userselect 围栏 + `[{text,path?}]`）与 ExitPlanMode 计划卡（inputText JSON 含非空 `plan` 字段形状门）都在**渲染层**解析——下沉 store 会破坏乐观回显去重（按原始文本匹配）；复制/编辑重发用原文。
7. **挂起交互归位**：ExitPlanMode 挂起交互 kind 恒 `"userInput"`、`plan_approval` 只在 `payload.schema.interaction`、正文在 `payload.input.plan`——按 schema 归位 kind。
8. **运行态双源**：task-index 行携 `status`（running/completed/error 三词表）、sessions-index 行携 `phase`——isRunning 双源合成（实时 phase 优先）。
9. **键盘/常驻卡**：输入区上方的常驻卡（审批卡等）必须限高+内部滚动——无界卡片+键盘（≈40% 屏）会把其上方 chips 物理顶到键盘底下（点击从未发出，表象「点了没反应」）；面板呈现前先撤焦。
10. **相机/阻塞调用**：`CameraX/Camera2` 的 open/bind 禁主线程同步等待（iOS `startRunning()` 主线程挂死、表象「触摸全失效而动画照跑」）；预览互操作视图（AndroidView）压住声明式兄弟视图触摸——预览层禁触摸。
11. **权限弹窗吞测试输入**：系统权限弹窗在 UI 测试的 App 树不可见、拦截期间一切合成 tap 被吞——测试前 `pm grant` 预授权 + 用例内轮询；授权按设备/AVD 隔离。
12. **i18n**：服务层/校验层错误串也要进资源（会经错误路径上屏）；批量包装后跑「源码位点 vs 资源键集」对账差集；测试断言勿依赖具体文案语言（断言 reasonCode 等结构信号）。

### 丁、工具链/环境（假绿假红教训）

1. **构建结果显式判定**：禁 `构建 | tail` 管道链（退出码恒 0 吞编译失败）；显式判 exit code + grep error 字面量。
2. **测试用刚构建的产物**：`test-without-building` 必须显式指定与 build 同一路径的输出目录——缺省解析到陈旧产品会全线假红。
3. **并行隔离**：同模拟器/AVD 并行构建互杀 runner；高载环境「偶过偶败」都会误导归因，换空闲设备复跑是定案手段；同仓并行会话注意文件冲突与 stash 事故。
4. **资源文件有并行未提交改动时禁 checkout/reset**（iOS xcstrings 事故：抹掉 22 键仅恢复 14，8 键永失）；恢复优先从编译产物反解。
5. **替身服务器必须校验载荷形状**：本地全绿≠真机可用（33 条偏差全因此潜伏）；替身命令路由要走信封处理器（裸方法名分支是死分支）；替身推送的帧要贴上游真实形态（用户行携 sourceCommandId、seq 单调递增、subscriptionId 一致）——替身「美化过的形态」会掩盖真实缺陷。
6. **诊断纪律**：诊断键（diag.*）登记+验收后清理；探针 sessionId 永不截断；持久化断言前考虑冲洗竞态（「发现脏态→二次冷启复核」自愈）。

### 戊、服务端/上游（非客户端可修，需防御）

1. **跨工作区幽灵行**（见乙-6，上游 bug，桌面 tasks-index 至今存留双行）。
2. **中继单连接槽**：同一时间只有一个链接有效（用户明示）——验证必须串行化；真机中继 `-1001 超时` 根因未定案（服务端单槽拒新/网络/端点形态），先确认桌面无其他活动远控客户端。
3. **桌面繁忙期 RPC 超时**：桌面跑重 turn 时单管道占满，附件 abort 等 30s 一次性超时如实提示即可，复测挑桌面空闲窗。
4. **旧桌面不推 workspace-config**（v3.14.4 实证「订阅静默失败」）——词表等权威源以 getView 为准。
5. **deleteSession 对有行会话报 sessionNotFound**（仅 draft/空会话可回收）；探针残留会话需桌面端侧删除。

---

## 5. 产品裁决速查（历史用户裁决，Android 必须遵守）

| 裁决 | 内容 |
|---|---|
| 原生非套壳 | 「我不是要求原生不是套壳么」——纯原生（Android 对应：Kotlin + Compose，禁 WebView 套壳） |
| 边界口径 | 手机可发命令、桌面代执行、不直写文件（§0） |
| 命名/品牌 | BiuZ；商店禁 ZCode/Z.ai 字样，用 "works with ZCode"；包名建议 `cn.biuz.android` 同口径 |
| Mock 仅测试 | 「Mock 假数据全部移除，仅测试用例允许」——正式路径永不装配假数据 |
| 隐藏入口 | 无接口支撑的入口隐藏非删除（H1 反馈工单/H2 startSavedWorkflow/H3 检查点/H4 仓库语音 chips/H5 composer 目标菜单/云端沙盒行），恢复条件=真机取证 |
| 开发者模式 | 连接页只留扫码主路径；手动输入/指引/令牌说明收进开发者模式（设置页品牌行**连点 7 次**切换 + 启动参数等效） |
| 交付纪律 | 「所有开发功能先自测再交付」（最高优先级）；「先测完再给我」 |
| 取证纪律 | 「不懂的你就参考 https://github.com/zai-org/ZCode 的代码实现，都是有的」 |
| 单连接槽 | 「同一时间只有一个链接，多个没用」——验证串行化 |
| 重置谨慎 | 「重置是一个扣费接口，谨慎操作」——二次确认弹层，E2E 只打替身 |
| 头像 | 会话 icon 用标题首字符（非 Z 图标） |
| 缓存 | 镜像 web 的 30s keep-warm 方案（「每次点击会话都要请求一次很慢，感觉很蠢」） |
| 后台面板 | 只显示运行中（「运行中的参考价值更大」），已结束折叠 |
| 模式文案 | build/edit/plan/yolo 中文：变更前确认/自动编辑/计划模式/完全访问 |
| 视频放开 | 允许免 E2E、编译过即可（但配套缺口必须补：缩略图 video 分支、预览块上限） |

---

## 6. 平台差异映射（iOS 踩坑 → Android 形态）

| iOS 坑 | Android 形态 |
|---|---|
| SwiftUI Menu a11y identifier 逐层拼接、tap 落空 | Compose 测试语义树无此拼接坑；但「滚动查找需帧落在屏内判定」同构（LazyColumn 未组合项不语义可达） |
| `if let` 影子绑定 + `.task` 同名赋值 = 编译期 crash | Kotlin 无；等价纪律：避免作用域影子变量 |
| LocalizedStringKey 参数位禁传 `String(localized:)` | 单一路径 `stringResource()`；但「服务层错误串进资源」「包装后对账」同构 |
| EnvironmentValues 未注入 trap / 嵌套 cover 不继承环境 | CompositionLocal 未提供 crash / Navigation destination 不自动继承 activity 作用域——导航层级显式传依赖 |
| cfprefsd 冲洗竞态（SIGKILL 不落盘） | SharedPreferences `apply()` 未落盘 + 进程被杀同理——「脏态→二次冷启复核」可移植 |
| xcodebuild 管道假绿 / 旧 DerivedData 假红 | Gradle 同理：显式 exit code、CI 用刚构建产物、同 AVD 并行冲突 |
| AVCaptureSession 主线程挂死 | CameraX/Camera2 open/bind 禁主线程同步等待；「触摸全失效而动画照跑→先诊断主线程」分诊口诀通用 |
| Keychain 模拟器 install 清空 | Keystore/设备凭据在 AVD 重置时同样丢——镜像自愈 |
| fullScreenCover 双遮蔽（播放器） | 单一全屏宿主（SingleActivity + Dialog/BottomSheet 合流）同构 |

---

## 7. 当前状态与未决事项交接（2026-10-09）

### 7.1 在途状态

- **【2026-10-09 注记·Android M1 工作流轮】M1 里程碑达成**（骨架随 `a547f6c`、protocol/teststub 实装随 `be25b65 feat: zcode mobile` 入库）：android/ 纯 JVM 五模块骨架就绪（protocol/relay/connection/store/teststub，`android/settings.gradle.kts`；relay/connection/store 为占位模块）。protocol 层直译完成——交接清单十二件中属协议层的十件全部落位：Models/RPCSerialization/V4Wire/ChannelClient/ProtocolSchema/CommandSchemas/ConversationCommandFactory/ReadOnlyGate/AckActivationBarrier/ConnectURLParser（`android/protocol/.../protocol/`）；RelayLink/RelayFrameCodec 两件属中继帧面（iOS Services/Relay 归属），随批落位 protocol 模块 `relay/` 子包。门禁（2026-10-09 复核实跑，经 `android/scripts/build-jvm.sh` 互斥锁入口、显式退出码 + JUnit XML 记账判定）：`:protocol:test --rerun-tasks` exit=0、tests=77/skipped=2/failures=0/errors=0——执行 75 例全绿、2 例 @Disabled 挂账（RelayLinkTests 的 relayLinkArgument/ServerConfig 解码，被测 API 在 connection 装配层，随 M2 回补）；`:teststub:test --rerun-tasks` exit=0、冒烟 3 例全绿（握手→订阅→sendText→ACK 帧序与替身记账、A5 能力越权+strict 信封拒收、CAS stale-once+写拦截+ACK 六态词表）。
  - **验收口径如实声明**：§8.3 的 M1 验收「148 用例同口径绿」未整达——iOS 148 例 = 协议层五套 77 例（ProtocolSchemaTests 18 + CommandFactoryTests 13 + ReadOnlyGateTests 22 + AckActivationBarrierTests 6 + RelayLinkTests 18）+ store/VM 层约 71 例；Android 协议层五套平移 76 例（缺 `testParseInteractionUpstreamExitPlanModeIsPlanApproval` 1 例：被测 `parseInteraction` 是 Store 层 API，protocol 无对应物，如实未平移）+ 骨架占位 BootstrapTest 1 例 = 记账 77 例；store/VM 层约 71 例依赖未移植代码，挂账至对应里程碑。
  - **独立复核**（独立复核员未参与移植，对照 iOS 原文 + 15 条不变量）发现 5 条、高优先级 0 条（未触发修复轮；5 条均 low 级，按「遗留观察」挂账供人工判断）：① `RPCSerialization.kt:56-62` intValue 对越 Int32 域 integral double 饱和截断返回错值而非 null（iOS 64 位 Int 精确保值；上游 `command.ts:437` revisionAtDecision 无界）——下游接 sendCASWithRetry 读 revisionAtDecision 必须走 doubleValue；② `CommandSchemas.kt:390-407` attachmentBegin 未镜像上游 superRefine 互斥校验（transport.ts:853-862；iOS 同缺，服务端 fail-closed 兜底）；③ `ProtocolSchema.kt:365-367` strictBase64 空串双端判无效、上游判 0 字节（transport.ts:869-870；合法流不可达）；④ `CommandSchemas.kt:199-200` subagentModel 缺上游 .max 长度上限（workflow-run-settings-command.ts:25-29；iOS 同缺）；⑤ A2/A5–A10/A14/A15 等接线面不变量在 M1 纯协议批内无对应物——接线里程碑逐条落位，非本批缺陷。
  - **残留 deviations 摘要**（逐条 file:line 固化于各文件 KDoc）：JSON 运行时自研（protocol 禁第三方 JSON 依赖，RPCSerialization.kt 手写 RFC 8259 JsonCodec）；JSONValue.Int=32 位值域（iOS Int 64 位），issuedAt/毫秒时间戳恒走 .double 整型字面量路、读毫秒禁 intValue；URLSessionWebSocketTask→WebSocketTransport 接口 + factory，实装归 connection/relay（上述 2 例 @Disabled 同因；RPCChannelTransport 接口预置于 ChannelClient.kt 勿重复定义）；AckActivationBarrier.stagingLimit=256 系 iOS 保守取值非上游原值（上游 host 1024 帧/32MB，zcodeAgentConnectionScope.ts:175-176；overflow 语义与上游逐字一致）；ReadOnlyGate draft 分类按 iOS 代码直译返回 .command（ReadOnlyGate.swift:293 注释与代码矛盾，上游无对应物可裁决）；CommandAck 包裹/顶层双形态「包裹层打底、顶层补缺」混合合并次序为实现自定非协议事实（上游仅定义顶层扁平形态，command.ts:430-443）；中继 rpc-frame 层不在上游开源仓（rg 全仓 0 命中），形状权威=iOS 原文+协议文档 §3.3，crc32 0xEDB88320/1MiB 物理帧/16MB 逻辑消息三处与上游 core.ts:65-66 一致；替身推帧形态按上游四处纠正 iOS 替身偏差（wireVersion=3 非 1、serverTime epoch 毫秒非 ISO、deliveryProfile="replayable"、accepted 恒带 revisionAtDecision），信封对拍复用 :protocol strict 校验（比上游 strip 更严，被拒回 rejected+错误原文）；build 依赖面：ktor-network 替代不存在的 ktor-server-net + 补 ktor-server-websockets/kotlinx-serialization 插件，gradlew 未执行（本地 /Users/mac/opt/gradle/gradle-8.14.3 同版实测）；单测面：issuedAt 断言 intValue→doubleValue、ReadOnlyGate 用例19环节①约减（ZCodeServerConnection 记账断言，connection 层未移植）、SessionSummary 断言以 Long 毫秒替代。**M2 下一步**：connection（ZCodeServerConnection 同构 + 局域网直连 + 握手单点）、store（RemoteTaskStore + sessions 列表 + bootstrap.tasks 多工作区）、替身扩展（附件四方法/乐观回显 sourceCommandId/CAS stale 注水）。
- **v1.35/v1.36 已入库**：ExitPlanMode 计划卡 + provider 兜底/键盘修复的代码与协议文档 §13 条目已随 `c2e2657 feat:plan,detail,workflow report`（2026-10-09 14:22）提交。**协议文档当前为最新版（§13 至 v1.36），Android 以工作区/HEAD 现状为准即可**。
- iOS 门禁终态：单测 148/148、FeatureCompletion 20/20 全绿（test03/05/07/12 环境抖动后单跑复判绿）。

### 7.2 待真机复测（iOS 侧挂账，Android 实现后同样要过这些场景）

带附件 `/plan`（revision 应就绪）；桌面窗口停别的 workspace 时手机发消息实时见回复（watchdog 兜底）；plan 审批弹卡（v1.31③ 真机首击）；协作模式 chip 切 plan 桌面实切+回流；新建会话选模型/思考强度（提交门）；视频点播/跨区行转圈/发送单气泡；键盘升起时点模型/思考胶囊（审批卡在场）；模型面板空态「重新获取」行。

### 7.3 待取证（桌面在线轮）

getView 失败真实诱因（`diag.ms.dump` 区分桌面繁忙超时 vs 形态漂移）；`diag.wf.control.ui` collabMode ack 原文（定案历史丢因）；中继 -1001 单槽根因；bundle 逆向形状清单中未升格【实证】的条目（协议文档 §12/待验证清单 ①–⑭）。

### 7.4 测试债（iOS 存量，Android 不必继承但要知道）

MatrixAcceptance 套件存量失败 15/27（基线归因非新回归，失败面在 SettingsView/P2ExtrasViews/AppSession/连接层）；LayoutAudit test01 失效（列表分组布局变更后断言需重写）；test09（G-025）预存失败（alert+拼音键盘吞点击）。

### 7.5 桌面侧待办（非客户端）

5 个探针残留会话需桌面端删除；跨工作区幽灵行清理（动库需备份、桌面退出时做）；上游幽灵行 bug 理想上报修复。

### 7.6 已知否定性结论（禁止再走这些死路）

- REST/windows/bootstrap 对配对 sid 404——移动形态只有 WS RPC 两条链路；
- `model-selection` 频道无写面（仅 getView+onDidChange）——设置页模型列表只读，切换只能会话内胶囊；
- MCP 手动重置接口不存在（web 重置族仅三方法）——只做 resetTime 展示；
- 嵌套 workspace 对象被网关剥掉；平铺信封兜底已移除（A12 双写隐患）；
- draft 会话真机收不到 sendText（`proto.sessionNotFound`）vs 上游 prewarm「首发复用」——draft 首发是协议主路径，模型选择随首条 sendText 下发；
- `zcode://` 自定义 scheme 做 OAuth redirect 不行（须注册过的 web 回调）。

---

## 8. 建议 Android 工程结构与移植顺序

### 8.1 模块分层

```
android/
├── protocol/        # 纯 Kotlin，零 Android 依赖（对应 §2.1 前六件 + Models）
├── relay/           # 中继传输（OkHttp WS；对应 Relay 四件）
├── connection/      # Connection.call 唯一出口 + ReadOnlyGate + AckActivationBarrier + AppSession 装配
├── store/           # 三大 Store + 协议面 interface + 空实现（未连接态）
├── app/             # Compose UI + 平台服务（扫码/Keychain/OAuth/通知）
└── test/            # 单测平移 + Ktor 替身服务器 + Compose E2E
```

### 8.2 技术选型建议

Kotlin + OkHttp WebSocket（帧编解码自研移植 V4Wire/RelayFrameCodec）+ kotlinx.serialization（JSON 宽容解析需自写多形态归一）+ Coroutines/Flow（替换 Swift 并发，Store 投影用 StateFlow）+ Compose + Hilt。JSON 解析注意：iOS 大量「宽容键组」解析（字段名未冻结多形态），平移时保留同一套宽容策略并在协议文档登记。

### 8.3 里程碑（每步先单测后 UI）

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 | protocol 六件 + 单测平移（ProtocolSchema/CommandFactory 先行） | 148 用例同口径绿 |
| M2 | 局域网直连 + 握手 + sessions 列表 + bootstrap.tasks 多工作区 | 替身服务器跑通；真机桌面直连目检 |
| M3 | 会话订阅（按归属 workspace 寻址 + 帧面多路 + 屏障）+ 行渲染 + 发送（ACK 判定 + 乐观回显消费） | 替身快照先于回执场景；跨 workspace 会话实收 |
| M4 | CAS 命令族 + 审批三族 + 队列 + 模型/模式切换（provider 兜底/复合键） | stale 三段自愈；空串拦截 |
| M5 | 附件四方法 + 视频 + 缓存（30s keep-warm） | 20MB/64 块/padding 按块解码 |
| M6 | 中继链路（Relay 四件 + 开桥重握手单点） | 真实中继链接连通 |
| M7 | 工作流面板/完成卡/产物详情 + 任务组织态（membership join）+ 设置/权益/i18n | 对齐 iOS FeatureCompletion 场景清单 |

### 8.4 移植时逐条对照协议文档 §13 纠错条目

v1.12（附件形状）、v1.17（订阅寻址）、v1.20（getView 词表）、v1.21（draft 交接/ACK）、v1.22（五件套）、v1.23（帧面多路/watchdog）、v1.27（复合键/回填）、v1.31–v1.36（回显消费/双源运行态/provider 兜底/渲染层卡）——这些是 iOS 踩过的雷的修复原理，比代码本身更值得先读。

---

## 9. 测试与验证策略（方法论移植）

1. **本地替身服务器**（Ktor 同构 E2ELoginStubServer）：实现握手/快照/增量/命令回执伪造 + OAuth 假授权回调；**必须校验载荷形状**（strict 对拍）+ 命令路由走信封处理器 + 推帧贴上游真实形态（用户行携 sourceCommandId、seq 单调、subId 一致）。
2. **闭环断言**：「操作→替身收到 wire 命令（计数+载荷记账）→UI 回执」三段断言；边界断言（ReadOnlyGate 拦截面）。
3. **诊断落盘**：diag.* 键（DataStore/SharedPreferences JSON）登记用途+验收后清理；深链/启动参数直达页面；探针 id 不截断。
4. **真机探针双保险**：形状取证 = 上游仓/bundle 静态证据 + 真机回执动态证据，缺一不可（替身全绿≠真机可用）。
5. **环境纪律**：构建显式判定退出码；失败先单跑复判再归因；换空闲设备定案；诊断键永不承载业务逻辑。

---

## 10. 交接核对清单（Android 开工前）

- [ ] 通读协议接口文档 §2–§8（传输/帧/握手/信封）+ 架构根因分析全文
- [ ] 克隆上游仓 `https://github.com/zai-org/ZCode` 到 `/tmp/zcode-upstream` 备查（临时目录，引用以协议文档固化的 file:line 为准）
- [ ] 核对协议文档为最新版（§13 至 v1.36，随 `c2e2657` 入库）
- [ ] 建模块骨架（§8.1），protocol 模块先行 + 单测平移
- [ ] Ktor 替身服务器开工（M1 同步进行，后续所有里程碑依赖它）
- [ ] 桌面端联调环境：局域网直连（`GET /ws?token=…`）+ 云中继链接（重新生成即旧凭据失效；单连接槽串行验证）
- [ ] 品牌资产转换（biuz-mark.svg → adaptive icon；design-spec 令牌 → Compose Theme）
- [ ] 商店合规：应用名 BiuZ、包名 `cn.biuz.android`、listing 禁 ZCode/Z.ai 字样、Google Play 占名复核

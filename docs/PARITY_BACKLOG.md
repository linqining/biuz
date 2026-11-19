# PARITY_BACKLOG — web ↔ App 未对齐项

> 登记规则（web-to-app `references/sync-workflow.md` / `acceptance.md`）：
> 自查发现的不一致**必须**登记在此，状态流转 `待修 → 修复中 → 已修+实拍验证`；
> 每条必须带**证据路径**（web 源码 `file:line` / 截图 / DOM / 网络回执），无证据不登记裁决。
> 刻意差异不写这里 → 走代码内 `APP-DIFF[D###]` 标记 + `docs/parity-diff-ledger.md`。

| 编号 | 波次 | 页面 key | 平台 | 问题 | 证据 | 状态 |
|---|---|---|---|---|---|---|
| P-001 | W1 | — | android | ~~无 Markdown 渲染~~ **已修**：新增 `MarkdownParser.kt`（块级：标题1-3/段落/列表/代码块/管道表格，容错未闭合围栏）+ `MarkdownText.kt`（Compose 渲染，行内 `**粗体**`/`*斜体*`/`` `code` ``），接入 `CollapsibleBodyText`；11 项单测 + 真机会话目检（标题/列表/行内代码/斜体均正确） | `android/.../ui/chat/MarkdownParser.kt`、`MarkdownText.kt` | 已完成（代码高亮仍归 P-005 级） |
| P-002 | W1 | — | android | 无统一空/加载/错误三态公共件，各屏各写（`UsageLoading` / `UsageEmptyError` / `NotConnectedEmpty`）。**公共件已落地** `ui/components/BiuzStateViews.kt`（EmptyState/LoadingState/InlineNotice，`compileDebugKotlin` 绿）；各屏尚未替换 | `android/.../ui/components/BiuzStateViews.kt`；各屏旧实现见 `ui/screen/` | 修复中 |
| P-003 | W1→单列 | 015-设置-外观 | android | **已修（2026-11-10）**：色板改为「调色板快照 + getter」——`Color.kt` 新增 `BiuZDarkPalette`/`BiuZLightPalette`（light 档逐值对齐 iOS `Theme.swift:68-95` 的 web 实测值）与 `BiuZColors.current`，原 `val BiuZ*` 全部改成读快照的 getter → **650 处引用点零改动**即可换肤；`BiuZTheme(darkTheme)` 在组合前写入快照并映射 M3 `lightColorScheme`；外观偏好 `AppAppearance{system,light,dark}`（缺省 system，同 iOS `AppearanceMode`）持久化于 `settings.appearance`，设置页「基础」组新增外观行 + `AppearanceScreen`（跟随系统 / Zai Light / Zai Dark + 勾选 + 说明）。**实拍**：切 Zai Light → 底色 #fafafa / 正文 #404040（像素采样）、会话页表格与 chips 正常；系统切深色（`cmd uimode night yes`）→ 底色 #161616，跟随系统双向即时生效。**行为变化需知**：此前主题恒深色（硬编码），默认档改为跟随系统后，浅色手机上将显示浅色主题（与 iOS/web 同口径）| `android/.../ui/theme/Color.kt`、`Theme.kt`、`AppearanceScreen.kt`、`app/settings/AppSettingsStore.kt`；单测 `AppearancePreferenceTest` 3 例 | 已修+实拍验证 |
| P-004 | W1 | — | android | 无 Tab 层与 deeplink scheme，单 NavController 单栈 | `android/.../ui/BiuZApp.kt:88`、`AndroidManifest.xml:30` | 待修 |
| P-005 | W1 | — | ios | **已修（2026-11-09）**：新增 `TinySyntax.swift` 轻量词法高亮（关键词/字符串/注释/数字四类），语言归一 swift/kotlin/java/ts/js/python/go/rust/shell/json/yaml/sql（SQL 关键词大小写不敏感 + `--` 注释），未知语言原样不高亮（不猜语义）；接入 `CodeBlockView`。与 web shiki 的差异：不做 AST、无主题变体、仅单行字符串/注释扫描——阅读级对齐，登记为刻意简化。原描述：Markdown 为轻量实现（`TinyMarkdown`），无代码高亮 | `ios/.../Components/TinySyntax.swift`；单测 `UnitTests/TinySyntaxTests.swift`（8 例）；UI 目检 `MatrixAcceptanceE2ETests/test29_codeBlockHighlightedInFilePreview` | 已修+实拍验证 |
| P-006 | W2 | 006-多实例选择 | ios/android | 同机多在线实例时无选择弹层 | web `webRemoteControlConnectionSheet.tsx:141`；双端无对应实现 | 待修 |
| P-007 | W2 | 008-版本提示 | ios/android | 桌面版本不匹配时的阻塞提示缺失 | web `webRemoteControlVersionNotice.tsx:6`；双端无 | 待修 |
| P-008 | W2 | 009-分享落地 | android | **已修（2026-11-09）**：manifest 补 https intent-filter（`zcode.z.ai` 的 `/share` 与 `/cn/share`，与既有 `zcode://share/<code>` 并存）；解析早已在 `SharePreviewClient.shareCodeFrom` 且有单测。**剩余（服务端资产）**：OS 级「点链接直接开 App」需 `zcode.z.ai/.well-known/assetlinks.json` + autoVerify，属服务端托管项。原描述：无 deeplink scheme，分享链接无法唤起 | `app/src/main/AndroidManifest.xml`；实测 `am start -a VIEW -d https://zcode.z.ai/share/TESTCODE9 -p cn.biuz.android` → 直达分享页并拿到真实 HTTP 404 | 已修 |
| P-009 | W5 | 018/024/026/027 设置页 | ios/android | **双端四页只读第一波已落（2026-11-10，上游取证后）**：027 个性化 = web 同口径「仅本地桌面端」指引页（PersonalizationSection.tsx:147-150 `if(!isDesktop)` 提前返回、零 RPC——编辑会写错机器）；024 钩子 = `hooks.loadHooks` 只读（hooks.ts:10-21，三分组 installed/plugin/legacy + 待信任徽标 requiresWorkspaceHookTrust 口径）；026 子智能体 = `subagents.list{provider:'glm',mode:'allRuntimeScopes'}` 只读（agents 实测混入 plugin 代理→滤 plugin 拼 pluginAgents+去重，SubagentsSection.tsx:115-119 投影口径）；018 项目 = `project.readTree{includeDeleted,includeDeletedTasks}` 只读（活跃卡+已移除区+目录缺失徽标）。iOS 门禁 = Matrix test30（替身 3 handler：loadHooks/list/readTree）+ 单测 5 例 175/175；Android 门禁 = 模拟器实连桌面 3.17.2 四页全验（13 项目/内置2+插件4/钩子空态如实/指引页）。**未完成（写面后续波次）**：hooks.saveHooks 整表覆盖+grantWorkspaceHookTrust（gate 已放行…注：gate 拦截中，恢复条件=写面取证波）；subagents createAgent/updateAgent/deleteAgent/setEnabled（gate 拦截）；project ~~deleteProject/restoreProject~~（**已接·写面波次① 2026-11-10**：Android 长按确认+已移除区恢复钮——**真机闭环**：移除 web_to_app→Removed projects (1)→恢复→桌面还原；iOS contextMenu+confirmationDialog+恢复钮，门禁 182/0，真机闭环待真机轮）/measureProjectData/deleteProjectData（破坏性，v1 不接）；014 通用页 Android 无 OS 通知基建（TaskNotificationContent 仅内容模型）如实不做。**传输层根修（同轮）**：中继客户端 arg 一律包层 Arr([arg])——多参服务位置数组被再包一层→桌面 spread 后 id=整个数组→SQLite "parameter 3"；约定改 arg=Arr=完整位置参数数组原样发（web toService 同形，零既有 Arr 调用点零回归），RelayChannelE2ETest 约定锁定测试 + E2EStubServer.RecordedCall 增 body；直连 ChannelClient 无包装的潜伏口径登记未修（zcode-agent 走自定义分发不受影响） | `ios/.../RemoteSettingsExtrasViews.swift`、`Tests/E2ELoginStubServer.swift`（StubRPC 增 .bool 保真口径）、`android/connection/Remote{Hooks,Subagents,Projects}.kt`、`android/relay/RelayChannelClient.kt`、`ios/.../Relay/RelayChannelClient.swift` 部分已修（读面全落；018 写面双端接真机闭环 ✅）。**写面波次②（2026-11-10）**：gate 放行
`hooks.saveHooks` + `subagents.setEnabled`（web 同参同面裁决，两端 ReadOnlyGate+单测，其余
createAgent/updateAgent/deleteAgent/set*ModelOverride/grantWorkspaceHookTrust 维持拦截）；
数据面 = `saveHooks`（HookEntry.raw 整条回传仅翻转 enabled/删除行——桌面侧 hooksService.ts:309-333
只落 editable!==false 且 source==="zcode" 条目）+ `setSubagentEnabled`（agents-state.json）；UI =
钩子 installed 行开关+🗑删除确认、子代理 user 行开关（web supportsEnabledToggle 同口径，非 user
行不给开关）。**门禁**：iOS Matrix test32 全绿（toggle→saveHooks 捕获体 enabled 翻转→删除→整表
余 2 条不含被删行→setEnabled {agentId, enabled:false}）+ 回归 186/0；Android 全量门禁绿 + 模拟器
冒烟（真桌面无 user 代理/无钩子——开关入口如实不可达，形状由 iOS test32 + 中继约定测试锁定） |
**写面波次③（2026-11-10）**：subagents CRUD 编辑器已接——gate 两端放行
createAgent/updateAgent/deleteAgent（set*ModelOverride 维持拦截需模型目录联动）；
数据面 config=编辑器三键（name/description/systemPrompt）+ raw preserve 族
（disallowedTools/skills/permissionMode/maxTurns/background/mcpServers 随 raw 原样，
web onSave 同构）+ deleteAgent {agentId, filePath=path}；UI=Android ＋新建/行点击
编辑/长按删除 + sheet 三键表单、iOS ＋按钮/Form sheet/contextMenu 删除；**iOS
Matrix test33 全绿**（create/update/delete 三捕获体逐字段断言：config.name+
systemPrompt+provider=glm+scope=user / agentId+oldFilePath+scope / agentId+
filePath）+ 回归 186/0 + Android 全量门禁绿。**测试坑固化（test33 两次返修）**：
① .sheet(isPresented:) 第二次呈现复用首帧 content 闭包——编辑保存走成 create
分支（update=0/create=2 实测），改 **.sheet(item:)**（identity 变化强制重建）；
② 替身 user 载荷必须带 systemPrompt/path——编辑器 canSave=false 时保存按钮
disabled，tap 静默无效（「点了没反应」新形态）。真桌面无 user 代理——真机路径
数据缺席如实，形状由替身与 iOS 锁定。
**写面波次④（2026-11-10）**：018 数据删除已接（measureProjectData/
deleteProjectData，双位置参数 wire=[ids, hostId]）——measure nil=服务不可用隐藏
「同时删除数据」选项（web :98/:120-123 同口径不给 0 值假象）；deleteProjectData
拒绝三态映射（web :158-160 原文：running/referenced/failed 任一>0 项目保持已移除
可重试）；UI=移除弹层呈现时测量 + Android Checkbox「同时删除数据（X）」+ iOS
confirmationDialog 双 destructive 按钮（不支持复选控件——iOS 标准双按钮模式）+
二段强确认「删除 X」。**Android 真机闭环 ✅**：长按 web_to_app → 勾选「Also
delete their ZCode data (530 KB)」（measure 真实大小）→ 二段「Delete 530 KB」→
桌面项目+数据彻底删除；**iOS test34 全绿**（measure 捕获体逐字段：ids 位置参数 +
hostId=local）。
**006 多实例（2026-11-10）**：iOS/Android 已实现（web groupWebRemoteControlDevices
直译，webRemoteControlDeviceGroups.ts:12-56）——纯函数 groupDevicesByMid（MID 聚合、
在线 SID 全保留不去重、离线不生成连接、排序 online→无名→name→mid→sid）+
needsInstanceSheet（≥2 在线实例弹 sheet，web 触发口径）；UI=Android 设备页在线组行
点击弹 ModalBottomSheet（web sheet 同文案「这台电脑上有 N 个 ZCode 在线」+ 实例行
SID 尾号 + 取消）、iOS OAuthResultView .selecting 分组渲染（同 MID ≥2 组头 +
006-row-sid a11y；单实例平铺不变）；**单测 5+4 例双端锁定**（聚合/不去重/离线/排序/
平台回退/sheet 触发阈值），Android 全量门禁绿、iOS 单测 188/0。**双实例真机验证
留待环境具备时补验**（需同机双 ZCode 实例在线）；真桌面单实例场景行为不变（不弹
sheet 直连——Android 模拟器实测单实例路径正常）
**写面波次⑤（2026-11-10）**：set*ModelOverride 已接——gate 两端放行
（subagents 黑名单清空）；数据面 setBuiltInModelOverride {agentName, modelSelection?} /
setPluginAgentModelOverride {agentId, modelSelection?}（web SubagentsSection.tsx:1607-1613
同参；model-selection.ts:4-15 strict 三键；modelSelection 缺省=继承默认）；
模型目录=既有 model-selection.getView 投影（iOS store.modelSelectionView()/
Android RemoteConversationStore.modelSelectionView()——chips 同词表，LaunchedEffect
预取）；UI=built-in/plugin 行内「模型覆盖」入口（user 行走编辑器——web 同位）+
继承默认/模型清单/模型→思考档二段选择；**iOS test35 全绿**（built-in 体 agentName+
modelSelection 三键 / plugin 体 agentId+继承默认缺省逐字段断言）+ 回归 191/0 +
Android 全量门禁绿。**测试坑固化**：行内 Button a11y id 含 entry.id 全文（含
"built-in:" 前缀）——断言用全 id；viewport 外入口先 snap 目检再修断言
**P-013 task-groups 数据源（2026-11-10）**：Android 已接——connection 模块
`RemoteTaskGroups.kt`（task-groups.read → TaskGroupStructure 解析：groups/members
宽容 + parseTaskGroupStructure 纯函数）；ConversationGrouping 加 taskGroupSections
（taskId↔sessionId 对位/组间 groups[] 声明序/组内 updatedAt 降序/未分配归尾区）；
SessionsScreen GROUPED 视图接入（加载/错误/空/列表四态 + 复用 SessionRow 渲染）；
TaskGroupSectionsTest 5 例（聚合/组序/尾区/全含/空组）全绿 + 全量门禁绿。
**iOS 同构待做**（AppSession.readTaskGroups + ConversationListView GROUPED 分支）；
小程序 capability 页可复用（task-groups.read 可加 kind）。
| P-010 | W6 | 036-白板 | ios/android | **双端已落（2026-11-10 第二轮）**：web 白板是**纯内存 zustand、零 RPC 自包含**（store/whiteboardStore.ts；断页即失、无持久化）——原生同构：模型 lib/whiteboard.ts:5-34 同构（1280×800 白底 #ffffff、默认墨 #111827、六色板 WhiteboardPane.tsx:28 原序、笔宽 2–18）、变更语义逐条对齐（addStroke=追加+清 redo 栈 / undo·redo 尾搬 / clear 双清 / rename trim 空忽略）、渲染 drawWhiteboardStroke 直译（eraser=画背景色、round cap/join、单点=实心圆 r=width/2）；「添加到聊天」**真对齐**：PNG 导出（CoreGraphics/bitmap 白底重绘）→ 直插 composer 附件（iOS uploads.add / Android enqueueAttachment，web dataURL 事件同效）。入口=会话页（iOS 头部涂鸦按钮 sheet / Android ⋯ 菜单浮层）；进程内存仓按 workspaceIdentity||path 键隔离。**iOS 门禁**：单测 7 例（含 PNG 导出非空+1280×800 断言）+ Matrix test31（真拖拽画 X 两笔→撤销/重做→添加到聊天→composer 白板.png chip）全绿；**Android 门禁**：单测 6 例 + 模拟器 adb swipe 真画验证。**已知差异**：web 多板 tab 系统（桌面驱动）未搬——移动端单板/workspace（v1 裁决）；PNG 命名 白板.png | `ios/.../Features/Whiteboard/`、`android/.../ui/whiteboard/` | 已修+实拍验证 |
| P-011 | W3 | 010-任务主页 | ios | **已修（2026-11-08）**：补设备切换行、已置顶文案、筛选视图四档、行严格对齐 web。剩余两项已裁决为刻意差异：常驻搜索框 = D008、来源 chips = D009 | web 基线 `.capture/sync/20261108-075048-010/`；iOS `ConversationListView.swift` | 已修+实拍验证 |
| P-012 | W3 | 010-任务主页 | android | **已修（2026-11-08）**：标题改「任务列表」、新增设备切换行、筛选视图四档（按项目/按分组/最近更新/按未读）、按项目改真项目分组（新增 `projectSections`/`timelineSections`/`unreadSections` + 3 项单测）、行严格对齐 web、`置顶`→`已置顶`。`compileDebugKotlin` 绿、`ConversationGroupingTest` 绿 | `android/.../ui/screen/{SessionsScreen,ConversationGrouping}.kt` | 已修（代码级验证） |
| P-013 | W3 | 010-任务主页 | ios/android/miniprogram | 「按分组」视图缺 task-groups 数据源接入（web `TaskGroupedView`）；三端当前都给如实提示 | web `project-sidebar/TaskGroupedView.tsx:72` | 部分已修（Android GROUPED 视图已接 task-groups.read + taskGroupSections 纯函数 5 例单测 + SessionsScreen 四态渲染；iOS 同构待做；写面 save/ungroup/move 不接——越界即失守） |
| P-014 | W2 | 001/003/007 已落 · 002/009 待做 | android | **阻塞已解除（2026-11-08 v1.40）**：OAuth 账户层（模型/凭据/客户端/状态编解码）+ Relay V2 传输（ws2 + 一次性 token + auth_init 无 role/秒级 + 心跳 5s±1s·15s）+ 设备目录自动落点 + Keystore 加密凭据已移植，单测全绿。**进展（同轮实测）**：001 登录（WebView 授权 + 回调拦截 + 取消顶栏）、003 设备列表（真实目录 + 断线重连）、007 切换账号（弹层 + 凭据清空）均已落地并在模拟器 + 真实中继上验通；002 由 WebView 回调拦截承担（无独立页，属平台差异）；**009 分享落地待做** | 协议文档 §13 v1.40；`android/connection/OAuth*.kt`、`android/relay/RelayV2.kt`、`app/ui/screen/LoginScreen.kt` | 已解除阻塞（001 已接） |
| P-015 | W2 | 006-多实例选择 | ios/android | **小程序已修（2026-11-08）**：`pages/devices` 内底部 sheet（同组在线 >1 触发），文案对齐 web，逻辑 3 项单测 + 实跑点击验证。**Android 待做**：设备目录按 deviceMid 归组（多 sid 同机）后同款 sheet；iOS 同 | web `webRemoteControlConnectionSheet.tsx:19-60`；`miniprogram/utils/device-model.js` | 修复中 |
| P-016 | W2 | 008-版本提示 | ios/miniprogram | **Android 已修（2026-11-08）**：`VersionGuard` 纯逻辑 + 阻断弹层 + 「重新加载」回写链接版本（避免旧链接重连死循环），单测 4 例 + 模拟器 E2E 双段验证 | `android/connection/VersionGuard.kt`；web `webRemoteControlVersionNotice.tsx:6` | 修复中（iOS/小程序待接） |
| P-017 | W2 | 005-连接失败 | android | **P-017 已接（2026-11-10）**：web 12 种全量文案表移植 ConnectFailureCopy.kt（badge/title/description/nextSteps/action 逐字段直译 + ConnectError→reason 映射 Http401→invalid-mobile-connection、Timeout→desktop-bootstrap-timeout、EmptyWorkspaces→workspace-closed、Transport→relay-unavailable、其余→unexpected-error）+ ConnectFailedCard 接入（web 同构 badge→title→description→nextSteps 渲染，保留既有 hint 为补充详情面） | `android/app/.../ConnectFailureCopy.kt` | 已修 |
| P-018 | W2 | 001-登录 | miniprogram | 登录页 UI + 文案已完成并实跑验证，但 OAuth 换取 code 的链路未接入（点击如实提示「登录待接入」） | `miniprogram/pages/login/login.js` | 待修（待连接层） |
| P-019 | W4 | 011-会话对话 | ios/miniprogram | **Android 已落（2026-11-09）**：`TaskChangeSummary` 模型 + `mapTask` 结构化解析（files 缺 path 行丢弃）+ 会话页折叠条（`N 个文件已更改` + `+A`/`-D`，展开列逐文件），单测 `mapTaskParsesStructuredChangeSummary`。**粒度差异（待裁决）**：web 为每条助手消息一条（per-turn snapshots），移动端数据源是任务级 `changeSummary` → 置顶常驻条；原描述：**消息变更摘要条**缺失（web `MessageChangeSummaryPanel.tsx:73-140`）：折叠头 = 箭头 + `N 个文件已更改` + `+A`(绿)/`-D`(红) + `撤销`，展开 = 逐文件 diff。证据：线上会话页基线 `.capture/sync/w4-chat/shots/web-011-chat.png` 里的「1 个文件已更改 +7 -4 ↺撤销」 | web 源码；三端无对应实现 | 待修 |
| P-019b | W4 | 011-会话对话 | android/ios/miniprogram | **per-turn 变更条：数据源实测缺席**。按用户裁决改为 per-turn 粒度并已实现渲染链（`turnHeader.fileChanges → ChatMessage.turnChanges`，单测 `turnHeaderFileChangesAttachedToAssistantMessagePerTurn` 覆盖挂载与不继承），但**当前桌面（3.17.2）的 row 快照里 turnHeader 不带 `fileChanges` 键**（实测 row=1348/1357/1380/1408 keys 无该键）→ 界面无数据可显。唯一替代源是持久化会话文件 `zcodeSessionFile.fileChanges[{turnIndex, snapshots[]}]`，但其 turnIndex 与 v4 行的 `turnId/productTurnId` 无直接映射（只能靠 turnHeader 出现次序推断），**未取证不实现** | `RemoteConversationStore.rebuildMessagesLocked`；上游 `zcode-protocol-v4/rows.ts:105-112` | 搁置（待数据源取证） |
| P-020 | W4 | 011-会话对话 | android/miniprogram | **消息动作行**：web 为 复制 / 赞 / 踩 / 展开 / 锚点 / 时间；iOS 已有 `MessageFeedbackRow` + 「复制文本」（`MessageViews.swift:143,168`），Android 已接（波次⑤ ChatScreen onFeedback + setAssistantFeedback 2026-11-10）；小程序已接（chat 页 👍/👎 按钮 + connection.setAssistantFeedback RPC 面 2026-11-10；entityId 数据面保留待富化——entityId 缺席按钮优雅降级不渲染） | web 基线截图；`ios/.../MessageViews.swift:143` | 待修 |
| P-021 | W4 | 011-会话对话 | ios/android/miniprogram | **会话页第二行**：`📁 项目名 ⋯ ⤡收起`（web 顶部第二行，用于切项目与收起侧栏）；三端均无 | web 基线截图 `web-011-chat.png` | 待修 |
| P-022 | W4 | 011-会话对话 | ios/miniprogram | **Android 摘要行已落（2026-11-09）**：会话页「工作流运行中 · {name}」+「N 个阶段 · M 个子代理工作中…」，点按展开既有 WorkflowPanelCard（阶段/子代理明细已在该面板）。**剩余**：web 的站点灯行 / 子代理头像行 / 「还有 N 个」在卡内直出，移动端收在面板里（放置差异，待裁决）。原描述：**工作流运行大卡**：web「工作流运行中 工作流脚本 / 10 个阶段 · 1 个子代理工作中…」+ 站点灯 `+4 ●●◐ ○○○` + 子代理行（含「还有 78 个」）+ 「收敛循环发现流/收敛进度」按钮；Android 只有迷你轨道与折叠面板，无此卡 | web 基线截图；`packages/ui/src/v4/highspeed` 同源 | 待修 |
| P-023 | W4 | 011/010 | android | **已修（2026-11-09）**：会话列表加订阅看门狗（15s 无首屏 → 常驻错误行 + 重试，与转圈互斥）。原描述：**桌面 v4 握手超时时会话列表永久转圈**：实测 `[relay-log][info] 桥内 v4 握手不可用（降级继续）：RPC 超时：zcode-agent.helloConversationV4` 后列表停在「Syncing sessions…」60s+ 无错误行、无超时兜底（`SessionsScreen` 的空行 + loadFailure==null 分支恒转圈）。真桌面繁忙时可复现 | 模拟器日志 + `.capture` 截图；`SessionsScreen.kt` 订阅看门狗 | 已修（超时分支待桌面繁忙时复现验证） |
| P-024 | W4 | 010/011 | android | **已修（2026-11-09）**：同一 socket 连续 3 次身份不匹配 → 判「桥身份过期」触发重建（每连接上限 2 次防抖）。原描述：中继桥退化帧被丢弃后订阅失败无自愈：实测 `[relay-log][info] 桥退化帧 bridgeSessionId 不匹配当前桥（忽略）` 连续出现 → 列表报「订阅会话列表失败 · NotInitialized」，force-stop 重启后恢复正常（说明是桥代际错配而非服务端拒绝） | `RelayTransport.handleBridgeDegradedFrameLocked` 自愈分支 | 已修（设备侧难复现，编译+relay 单测通过） |
| P-025 | W4 | 038-模型轨迹 | android | **屏 07 执行输出已落（2026-11-09）**：新增 `TaskOutputScreen.kt` + 路由 `taskOutput/{sessionId}` + 会话页顶栏常驻 `⋯` 入口（项目行是条件渲染，⊕ 放顶栏才在无项目会话可达）。轨迹 = 会话行投影三分类（思考/工具/回复，回复走 markdown 渲染）；只读元数据三读（getTaskModelSelection/getTaskTokenUsage/getTaskConfigOptions，`Model` 重复行已滤）；停止任务带确认、失败原文上屏。**剩余**：任务看板（board）与审批中心页（web `TaskBoardPane` / 审批面板）仍未建，审批现仅在会话页内联卡 | `android/.../ui/screen/TaskOutputScreen.kt`；iOS 对位 `Features/Tasks/TaskOutputView.swift` | 部分已修（轨迹页完成；看板/审批中心待建） |
| P-026 | W5 | 019-设置-归档 | android | **归档管理页已建（2026-11-09）**：新增 `ArchivesScreen`（web `archives.*` 文案全套：搜索/取消归档/删除/全部取消归档/全部删除 + 确认弹层 + 空态三态）+ 路由 `archives` + 设置页「数据与统计」组入口；会话行补**长按菜单**（归档 / 标记未读，失败原文常驻上屏）。**顺带修两处存量缺陷**：① 接口 `archivedConversations()` 只有默认空实现、Remote store 从未覆盖 → 归档页恒空；② 主投影 `toConversations()` 把归档行 filter 掉，归档读面无数据可拿 → 加 `includeArchived` 口径（单测 `ArchivedReadPathTest` 2 例锁住两口径互不串）。空态区分三态（未连接 / 索引未就绪+重试 / 真无归档，实测「桌面 v4 握手超时」时不再误报空）；**未完成**：web 的分组（按项目）头与批量结果计数（成功/失败/已变化跳过）未做 | `android/.../ui/screen/ArchivesScreen.kt`、`RemoteSessionMerge.kt:475-545`、`RemoteSessionsIndexStore.archivedConversations` | 部分已修（真机归档闭环待桌面 v4 握手恢复后复验） |
| P-027 | W5 | 020/021/022/025 设置子页 | android | **能力清单四页已建（2026-11-10）**：新增 `CapabilityListScreen` + `RemoteCapabilities.kt` 只读读面（记忆 `memory.listProjectMemories` / 技能 `zcode-agent.getSkillReferenceCatalog` / MCP `zcode-agent.listMcpServerStatuses` / 插件 `zcode-agent.listPlugins`），设置页「Agent 能力」组四行入口，四态（未连接/加载/列表/失败+重试）。**真机取证修正一处形状**：MCP 回执实际是 `{statuses: {id: {status, transport, toolCount}}}` （map 非数组，桌面 3.17.2 实测），原按 iOS 的 servers[]/mcpServers[] 宽容链会读成空 —— 已按真形状解析（旧数组分支保留）。**iOS 同缺陷同轮修复**（同一宽容链 → 同样读成空）：`P2ExtrasViews.swift` MCP 分支改按 `statuses` map 解析（编译过 + 170 单测绿；页面目检受「同一时间只有一个远控连接」限制——Android 模拟器占槽，未目检）；**未完成**：启停/卸载/安装等写面（移动端只读，越界即失守）；钩子/子代理/个性化三页（iOS 亦无，P-009） | `android/.../ui/screen/CapabilityListScreen.kt`、`connection/.../RemoteCapabilities.kt`；真机：技能 6+ 条带「已启用」、MCP documents/pdf/presentations `stdio · 15 个工具`、插件 visualize/node-repl-host/android-emulator 带版本号、记忆真空态如实 | 部分已修 |

| P-028 | W5 | 028-设置-桌面设置同步 | android | **已落（2026-11-10）**：`RemoteDesktopSettings.kt`（channel=setting【实证·上游仓 channels.ts:106】；get 1.2s 退避重试一次 / update 单键补丁不重试防双写 B-7）+ `DesktopSettingsScreen`（bool→toggle、string/number→编辑，一律确认弹层 §5-15 呈现期捕获；35 键中文名映射=上游 validationAppSettings.ts 全集；枚举值中文化）+ 设置页入口 + 6 项解析单测。**模拟器实连桌面验证**：真实清单渲染 + toggle 写链路（确认→下发→回执→重载翻转，已还原现场） | `android/connection/RemoteDesktopSettings.kt`、`app/.../DesktopSettingsScreen.kt`；截图 /tmp/028-desktop-settings.png | 已修+实拍验证 |
| P-029 | W5 | 023-设置-自动化 | android | **已落（2026-11-10）**：`RemoteAutomations.kt`（listAutomations/listAutomationRuns；时间双形态 double-first 口径——Kotlin Int 32 位、毫秒时间戳 wire 上恒 Double，intValue 大值钳位坑注释固化）+ `AutomationsScreen`（摘要行/卡片 cron/相对时间/启停徽标/展开运行历史上限 10/状态三档配色未知词透传）+ 入口路由 + 6 项单测。模拟器实连验证 13 真实定时任务 + 展开历史 | `android/connection/RemoteAutomations.kt`、`app/.../AutomationsScreen.kt`；截图 /tmp/023-runs.png | 已修+实拍验证 |
| P-030 | W5 | 014-设置-通用 | android | web GeneralSectionContent（SettingsPage.tsx:1798）移动端有效项仅「界面语言+通知开关」；语言已在设置页，通知开关依赖 OS 通知基建——Android 无（POST_NOTIFICATIONS/NotificationChannel 均无，`TaskNotificationContent` 仅 :store 内容模型），按交接 §7-A2「没有就如实不做」；**恢复条件 = Android 本地通知基建波次**（channel 注册 + POST_NOTIFICATIONS + 任务状态触达接线） | web `SettingsPage.tsx:1798`；Android Manifest 无 notification 权限 | 如实不做（待通知基建） |
| P-031 | W7 | 凭据存储安全（001/019 账户层） | ios/android/miniprogram | **已落（2026-11-19，对齐 web v3.18.2 `c5904686..fec97590` 凭证安全语义）**：三端「系统密钥不可用 / 密文损坏 / 本来没有」三分完成——iOS `CredentialReadPolicy.swift`（`errSecItemNotFound`→absent；`errSecDecode`/解码失败→corrupt；授权交互类→authFailed；其余一律 unavailable，**默认永不判损坏**）+ `AppSession.oauthCredentialFailure` 上屏 + `credentialUnavailableCard` 重试/清除；Android `OAuthCredentialReadFailure.kt`（sealed 6 态 + `CredentialReadPolicy.canPurge` 纯函数）+ `KeystoreOAuthCredentialPersistence.loadOutcome()` 把 `AEADBadTagException`→确坏、`KeyPermanentlyInvalidatedException`/`UnrecoverableKeyException`→密钥不可用（**原实现 catch 后无条件 `clear()` 已移除**），解密路径改 `existingSecretKey()` 只读取、绝不生成替代密钥；小程序 `credential-crypto.js` + `credential-store.js`（HMAC-SHA256 EtM 加密 + `local\n` 来源钉住 + 8 态原因），`biuz.jwt` / `biuz.desktop` 明文键迁入加密容器并在写成功后删除。**安全边界（如实挂账，未修）**：小程序无 OS keychain/Keystore 等价物，密钥与密文同存微信本地存储 → 只消除「凭据明文落盘 / 备份与日志可直接读到 JWT」，**不能**防同设备同微信账号下运行时代码读取；`randomBytes` **已于 2026-11-19 修复**：原实现把异步 `wx.getRandomValues` 当同步 ArrayBuffer 用，`new Uint8Array(Promise)` 得空数组后填 undefined，而长度校验仍成立 → **静默返回全 undefined**，使密钥等同全零、IV 固定（确定性加密，任何人可解）；`Math.random` 兜底从未执行。现改为「启动异步预取 CSPRNG 池 + 同步消费 + 池耗尽抛 `CREDENTIAL_SECURE_STORAGE_UNAVAILABLE`」，**绝不回退 Math.random**；写入面新增 `writeFieldsAsync`/`saveJwtAsync` 自动预取，同步 `writeFields` 池不足时返回 `entropy-unavailable` 且不落盘任何密钥/密文（APP-DIFF[D014]/[D015]）。**同日回归修复**：熵源改造曾让迁移仍走同步版，而冷启动时 `app.onLaunch` 的预取未完成、池必为空 → 旧明文 `biuz.jwt` 用户每次冷启动都表现为「未登录」且明文永不迁入（`issueCopy` 又缺该原因映射，连提示都没有）；已改 `migrateLegacyJwtAsync`（先 `prefillEntropy` 再写）、首屏先渲染后刷新、补 `entropy-unavailable` 上屏文案、已有可用密文时清理遗留明文，5 项回归用例锁死冷启动时序。**同日补齐桌面凭据迁移**：原文写「`biuz.jwt` / `biuz.desktop` 明文键迁入加密容器并删除」，实际只有 JWT 有迁移逻辑；`biuz.desktop`（含配对 token）仅在任意 `writeFields` 成功后 `removeRaw` ——是**删除而非迁移**，只连桌面不进登录页的用户明文 token 会一直留在本地，且某次写入清掉旧键后会**永久丢失已保存连接**。已补 `migrateLegacyDesktopAsync`（先读后写再清，兼容对象/JSON 字符串两种旧形态；已有密文只清残留不覆盖；无 CSPRNG 时保留明文），并在 `app.onLaunch` 与冷启动并行执行两类迁移（共享 `poolPending`，只取一次 CSPRNG）。**剩余**：小程序 AES/HMAC 为纯 JS 自实现（无平台认证密码学、无硬件隔离）；iOS/Android 尚未做「凭据跨端迁移/更换设备后可恢复」；三端未接 OAuth WebView 完整授权（P-018） | web `packages/shared/src/credential-security/systemCredentialCipher.ts:61-75,203-216,241-249,398-425`、`packages/shared/src/oauth.ts:39-56`；`ios/.../Keychain/CredentialReadPolicy.swift`、`android/.../connection/OAuthCredentialReadFailure.kt`、`miniprogram/utils/credential-store.js` | 部分已修（**熵源已修为平台 CSPRNG**；小程序整体安全等级仍低于原生——无 OS keychain/硬件隔离、纯 JS 密码学，差距登记 APP-DIFF[D014]/[D015]） |

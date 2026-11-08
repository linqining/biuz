# PARITY_BACKLOG — web ↔ App 未对齐项

> 登记规则（web-to-app `references/sync-workflow.md` / `acceptance.md`）：
> 自查发现的不一致**必须**登记在此，状态流转 `待修 → 修复中 → 已修+实拍验证`；
> 每条必须带**证据路径**（web 源码 `file:line` / 截图 / DOM / 网络回执），无证据不登记裁决。
> 刻意差异不写这里 → 走代码内 `APP-DIFF[D###]` 标记 + `docs/parity-diff-ledger.md`。

| 编号 | 波次 | 页面 key | 平台 | 问题 | 证据 | 状态 |
|---|---|---|---|---|---|---|
| P-001 | W1 | — | android | 无 Markdown 渲染，Agent 正文原样 `Text`，与 web（shiki + 自研渲染）不等价 | `android/.../ui/screen/ChatScreen.kt:710` 自注偏差 | 待修 |
| P-002 | W1 | — | android | 无统一空/加载/错误三态公共件，各屏各写（`UsageLoading` / `UsageEmptyError` / `NotConnectedEmpty`）。**公共件已落地** `ui/components/BiuzStateViews.kt`（EmptyState/LoadingState/InlineNotice，`compileDebugKotlin` 绿）；各屏尚未替换 | `android/.../ui/components/BiuzStateViews.kt`；各屏旧实现见 `ui/screen/` | 修复中 |
| P-003 | W1→单列 | 015-设置-外观 | android | 恒深色（`BiuZTheme` 只挂 `darkColorScheme`），浅色主题未接。**实测：直接色常量引用 650 处 vs `MaterialTheme.colorScheme` 仅 68 处**——不是加个 light scheme 就行，需先把色常量改为随主题解析（CompositionLocal 或 token 对象），属独立重构波次 | `android/.../ui/theme/Theme.kt:50`、全模块 `BiuZ*` 引用统计 | 待修（已重新定级） |
| P-004 | W1 | — | android | 无 Tab 层与 deeplink scheme，单 NavController 单栈 | `android/.../ui/BiuZApp.kt:88`、`AndroidManifest.xml:30` | 待修 |
| P-005 | W1 | — | ios | Markdown 为轻量实现（`TinyMarkdown`），无代码高亮，与 web shiki 不等价 | `ios/.../Components/TinyMarkdown.swift:4` | 待修 |
| P-006 | W2 | 006-多实例选择 | ios/android | 同机多在线实例时无选择弹层 | web `webRemoteControlConnectionSheet.tsx:141`；双端无对应实现 | 待修 |
| P-007 | W2 | 008-版本提示 | ios/android | 桌面版本不匹配时的阻塞提示缺失 | web `webRemoteControlVersionNotice.tsx:6`；双端无 | 待修 |
| P-008 | W2 | 009-分享落地 | android | 无 deeplink scheme，分享链接无法唤起 | `AndroidManifest.xml:30`（仅 LAUNCHER） | 待修 |
| P-009 | W5 | 018/024/026/027 设置页 | ios | 项目/钩子/子代理/个性化四页未建 | web `settingsPageConfig.ts:2172,2218,2194,2098` | 待修 |
| P-010 | W6 | 036-白板 | ios/android | Canvas2D 白板未移植 | web `WhiteboardPane.tsx:47` | 待修 |
| P-011 | W3 | 010-任务主页 | ios | **已修（2026-11-08）**：补设备切换行、已置顶文案、筛选视图四档、行严格对齐 web。剩余两项已裁决为刻意差异：常驻搜索框 = D008、来源 chips = D009 | web 基线 `.capture/sync/20261108-075048-010/`；iOS `ConversationListView.swift` | 已修+实拍验证 |
| P-012 | W3 | 010-任务主页 | android | **已修（2026-11-08）**：标题改「任务列表」、新增设备切换行、筛选视图四档（按项目/按分组/最近更新/按未读）、按项目改真项目分组（新增 `projectSections`/`timelineSections`/`unreadSections` + 3 项单测）、行严格对齐 web、`置顶`→`已置顶`。`compileDebugKotlin` 绿、`ConversationGroupingTest` 绿 | `android/.../ui/screen/{SessionsScreen,ConversationGrouping}.kt` | 已修（代码级验证） |
| P-013 | W3 | 010-任务主页 | ios/android/miniprogram | 「按分组」视图缺 task-groups 数据源接入（web `TaskGroupedView`）；三端当前都给如实提示 | web `project-sidebar/TaskGroupedView.tsx:72` | 待修 |
| P-014 | W2 | 001/002/003/007/009 | android | **阻塞已解除（2026-11-08 v1.40）**：OAuth 账户层（模型/凭据/客户端/状态编解码）+ Relay V2 传输（ws2 + 一次性 token + auth_init 无 role/秒级 + 心跳 5s±1s·15s）+ 设备目录自动落点 + Keystore 加密凭据已移植，单测全绿。**剩余**：002 回调页/003 设备列表/007 切换账号/009 分享落地的 Android 页面仍待做（001 登录 UI 已接：WebView 授权 + 回调拦截 + 失败五态上屏，代码级验证无模拟器） | 协议文档 §13 v1.40；`android/connection/OAuth*.kt`、`android/relay/RelayV2.kt`、`app/ui/screen/LoginScreen.kt` | 已解除阻塞（001 已接） |
| P-015 | W2 | 006-多实例选择 | ios/android | **小程序已修（2026-11-08）**：`pages/devices` 内底部 sheet（同组在线 >1 触发），文案对齐 web，逻辑 3 项单测 + 实跑点击验证。iOS/Android 仍缺 | web `webRemoteControlConnectionSheet.tsx:19-60`；`miniprogram/utils/device-model.js` | 修复中 |
| P-016 | W2 | 008-版本提示 | ios/android/miniprogram | 桌面版本不匹配的阻塞提示缺失（web `webRemoteControlVersionNotice.tsx:6`） | web 源码；三端无 | 待修 |
| P-017 | W2 | 005-连接失败 | android | 只有连接页内 `ConnectFailedCard`，没有 web 的 12 种 FailureReason 全量文案页 | web `webRemoteControlScreenCopy.ts:16-141`；Android `ConnectScreen.kt:696` | 待修 |
| P-018 | W2 | 001-登录 | miniprogram | 登录页 UI + 文案已完成并实跑验证，但 OAuth 换取 code 的链路未接入（点击如实提示「登录待接入」） | `miniprogram/pages/login/login.js` | 待修（待连接层） |

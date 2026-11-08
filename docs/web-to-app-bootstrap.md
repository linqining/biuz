# web-to-app 冷启动盘点 — BiuZ

> 2026-11-07 · 依据 `.agents/skills/web-to-app/references/bootstrap.md`
> 权威源：`ZCode/`（upstream `zai-org/ZCode` @ `a9fe401e`，v3.17.2）的 `packages/web` + `packages/ui`
> 目标：`ios/ZCodeMobileApp` + `android/`（复用）＋ `miniprogram/`（新建）

---

## 1. 一句话结论

web 端**本来就有一个专门为手机写的壳**——`packages/ui/src/WebRemoteControlMobileShell.tsx`（home / chat 两页 + 右侧覆盖抽屉），
它不是桌面布局的响应式降级。**这正是三端 parity 的唯一基准**，桌面分栏（SidePane 横向 28 个 tab、终端面板、窗口 chrome）
不在移植范围内。

现有双端已把「远控主链」做完大半（连接/会话/Diff/文件/设置），
真正的工作量分布是：**小程序从零起、Android 补一整块管理面、iOS 只补零星页 + 做 parity 对账**。

---

## 2. web 技术栈（决定换算与对接方式）

| 维度 | 事实 | 证据 |
|---|---|---|
| 框架 | React 19.2 + Vite，**无路由库** | `packages/web/src/main.tsx:1792` `bootstrapWebApp()` 手写顺序分发 |
| UI 主体 | `@zcode/ui`（与桌面 Electron 共享） | `packages/web/src/main.tsx` 只做入口编排 |
| 样式 | Tailwind 4 + shadcn + CSS 变量 token | `packages/ui/src/styles.css:1-8`、`ui/package.json` |
| 状态 | zustand 5 | `ui/package.json` |
| 动效 | `motion` 12、tw-animate-css | `ui/package.json`、`styles.css:2` |
| 移动断点 | **`(max-width: 767px)`**，由 `isWebRemoteControlShell` 门禁 | `app-shell/WorkspaceShellLayout.tsx:123,231-237,463` |
| 视口高度 | `dvh` 而非 `vh` | `root/RootShell.tsx:8-11` |
| Markdown | 自研渲染 + `shiki` 代码高亮 + `katex` | `ui/package.json` |
| 富预览 | `react-pdf`、`@extend-ai/react-docx|xlsx`、`lexical` | `ui/package.json` |
| 画布 | 白板 Canvas2D、引导页 WebGL、Rive | `WhiteboardPane.tsx:47`、`onboardingMeshRenderer.ts:33` |
| 协议 | 与移动端同一套 channel RPC / Relay V4（桌面 bridge） | `packages/web/src/web-remote-control/CONTRACT.md` |
| i18n | `ZCodeIntlProvider`（`@zcode/ui`） | `main.tsx` imports |

**移植换算口径**：web 手机壳的尺寸即「iPhone 逻辑像素」直读——Tailwind 的 `rem`（1rem=16px）与 iOS pt / Android dp 近似 1:1；
断点固定 767px，不做多档响应式（≤767 只有一种布局）。

---

## 3. 页面分层（详见 `config/page-index.json`，38 条）

| 层 | 内容 | 数量 |
|---|---|---|
| L0 路由层 | `main.tsx:1792` 四条判定：OAuth 回调 → 分享页 → 远控入口 → 兜底 Root。**全等匹配**（`shared/src/web-remote-control.ts:161`），非前缀 | 4 分支 |
| L1 顶层页 | 登录 / 回调 / 分享落地 / 设备列表 / 连接中 / 连接失败 / 多实例选择 / 切换账号 / 版本提示 | 9 |
| L2 主壳 | home（ProjectSidebar）/ chat（V4ChatPane→SessionPane）/ 侧边抽屉 / 设置（19 项有效） | 4 + 15 |
| L2 抽屉 pane | git / file-manager / code-viewer / plan-detail / subagent-session / bash-output / workflow-run / whiteboard / terminal / model-trajectory | 10 |

**明确不进移植面**（web 侧已禁用或桌面专属）：
`automations` 与 `plugin-store` 主视图（`WorkspaceShellLayout.tsx:2338,2385` 在远控壳下 `&& !isWebRemoteControlShell`）、
`remoteDevices` / `dataSync` / `computerUse` 设置（`settingsPageConfig.ts:259-266`）、
内嵌浏览器 `browser` / `browser-use`（依赖 Electron `<webview>`，`main.tsx:1620` 自身置 `supportsEmbeddedBrowser:false`）、
桌面窗口 chrome、`WebRemoteControlScanPage`（死代码，`ui/webRemoteControlScanPage.tsx` 无调用点）。

---

## 4. 公共件矩阵（每移植一页先查此表，缺了先补公共件）

| 公共件 | web 源 | iOS 现状 | Android 现状 | 状态 |
|---|---|---|---|---|
| Markdown 渲染 | `@zcode/ui` 自研 + shiki + katex | `Components/TinyMarkdown.swift:4`（标题/粗体/行内码/代码块/列表/table） | **无**，正文原样 `Text`（`ChatScreen.kt:710` 已标注偏差） | iOS 部分 / Android 缺 |
| 代码高亮 | shiki 4 | 无 | 无 | 缺 |
| 数学公式 | katex | 无 | 无 | 缺（低优先） |
| 弹层壳（sheet/dialog/toast） | radix + motion | `.sheet` / `ConfirmSheet`（`Connect/ConnectingView.swift:506`） | `ModalBottomSheet` / `AlertDialog` | 各自成型但**无统一 toast** |
| 空/加载/错误三态 | `RootStartupLoading` 等 | `Components.swift:175 EmptyStateView` / `:217 CenterLoadingView` | **无公共件**，各屏各写 | iOS 有 / Android 缺 |
| 头像 | `AgentAvatar` | `Components.swift:328` | `SessionPanels.kt:467` | 齐 |
| 状态 pill | `StatusPill` | `Components.swift:27` | `SessionPanels.kt:572` | 齐 |
| 设计 token | Tailwind CSS 变量 | `Theme/Theme.swift:50 T`（含 light/dark） | `theme/Color.kt` + `Dimens.kt` + `Type.kt`（**恒深色**） | Android 缺浅色 |
| 网络层 / RPC | `@zcode/client` | `Services/RPC|Remote|Relay` | `protocol` / `relay` / `connection` 模块 | 齐（同源直译） |
| 路由 / 深链 | 手写分发 + history | `AppRouter.swift`（4 Tab + 16 route） | `Routes.kt`（单栈 11 路由）| Android 无 Tab 层、无 deeplink |
| 差异标记 | — | 无 | 无 | **待建**（`APP-DIFF[D###]`） |

---

## 5. 三端缺口（parity 对照结果）

### iOS —— 主链齐、尾部零星缺
已覆盖：连接/扫码/服务器详情、OAuth 全链、会话列表、会话流（消息类型全集 + 审批/计划/提问/hook 卡 + 队列 + 工作流面板 + 附件）、
Diff/文件树/预览、设置 16 页、用量、设备、诊断、插件/MCP/技能、桌面设置同步、分享深链。
缺：`006-多实例选择`、`008-版本提示`、`018-设置-项目`、`024-设置-钩子`、`026-设置-子代理`、`027-设置-个性化`、`036-白板`。
弱：Markdown 为轻量实现，无代码高亮（与 web shiki 不等价）。

### Android —— 单栈九屏骨架，缺两大块
有：连接/扫码、会话列表、会话流、Diff/文件树/预览、设置（语言/关于/用量）。
缺：**任务域整块**（看板/详情/审批）、**管理面整块**（设备/IM Bot/自动化/工作流库/插件/桌面设置/诊断/服务器详情/提交图谱/分享）、
**OAuth 登录**、**markdown 与浅色主题**、Tab 层与 deeplink。

### 微信小程序 —— 从零
工程不存在（全仓 `wxml/app.json/project.config.json` 零命中）。需先定三件事（`platform-differences.md` §5）：
域名白名单（远控走 `wss://zcode.z.ai`，小程序要求 **wss + 备案域名 + 后台配置合法域名**）、rpx 750 基准换算、页面栈 10 层上限。

---

## 6. 分波次计划

不按「先 iOS 再 Android」切，按**能力块**切，每块三端同步收口：

| 波次 | 内容 | 交付判据 | 状态 |
|---|---|---|---|
| **W1 地基** | 小程序工程骨架（app.json/pages/project.config）＋ 三端公共件补齐 ＋ 验收缝落地 | 公共件矩阵无「缺」；`capture.py pages` 三端列全 | **进行中**：小程序骨架 ✅（10 页 + 3 公共件 + token，JSON/JS 校验通过）／Android 三态+行内提示公共件 ✅（`ui/components/BiuzStateViews.kt`，`:app:compileDebugKotlin` 绿）／iOS 验收缝 ✅（实测落页）／**待办**：Android markdown 渲染（P-001）、iOS 代码高亮（P-005）、Android 浅色主题（P-003，实测 650 处直接色常量引用，需独立重构波次）／Android 深链缝 |
| **W2 账号与配对** | 001–009（登录/回调/设备列表/连接中/失败/多实例/切账号/版本提示/分享落地） | 三端逐页实拍对比 + 逻辑对拍（连接状态机） | 未开始 |
| **W3 主壳** | 010–013（home/chat/侧边抽屉/设置）——**样板页：010 任务主页**必须走通 9 步全流水线 | 样板页 brief/file-map/popup-inventory/motion-spec/向量/实拍报告齐 | 未开始 |
| **W4 抽屉 pane** | 029–035、038（git/文件/预览/计划/子代理/后台/工作流/模型轨迹） | 逐 pane 对比 + 差异台账 | 未开始 |
| **W5 设置面** | 014–028（19 项有效设置） | 三端逐项对账 | 未开始 |
| **W6 长尾与异常** | 036 白板、连接失败 12 种 reason 文案、断线重连态、Android 任务域补全 | BACKLOG 无 P1 | 未开始 |

W1 完成前不进入 W2——公共件不齐会按页重复实现，返工成本最高。

---

## 7. 验收缝现状（bootstrap §5 要求的「直达入口」）

| 平台 | 现状 | 待补 |
|---|---|---|
| iOS | ✅ **已实现** `-acceptance-route <key>[:<arg>]`（`AppRouter.applyAcceptanceRoute`，`ZCodeMobileApp.swift:114` 解析）。支持 key：`010-任务主页`/`home`、`011-会话对话`/`chat:<id>`、`013-设置`/`settings[:<SettingsRoute>]`、`029-Diff/Git 面板`/`git`、`030-文件管理器`/`files`、`038-模型轨迹`/`tasks`。内核实测通过（`-ZCodeDemoData -acceptance-route 030-文件管理器` 落在工作区文件页）。 | 其余页面 key 按波次补 |
| Android | `Routes.kt` 11 条 route 可直达 `sessions` / `files` 等 | 全部 key 的深链映射 + `--es acceptance_route`；当前 Manifest 无 deeplink scheme |
| 小程序 | ✅ 页面路径直达（`pages/<slug>/<slug>`，`utils/nav.js` 的 `acceptanceRoute`） | automator 用例接线 |

**踩坑记录（iOS 缝）**：中文 key 经 `simctl launch` 传入时是 **NFD** 规范化形态，与源码里的 NFC 字面量
`==` 不相等——表现为「不落页也不告警」。`applyAcceptanceRoute` 已统一 `precomposedStringWithCanonicalMapping`
后再比较。**ASCII 别名是自动化场景的推荐写法**（`files` / `home` / `git` / `tasks` / `settings`）。

---

## 8. 环境与阻塞

1. **web 采证环境（已打通）**：`ZCode` 仓库 pnpm 安装在 **node 24.4.1** 上必崩（收尾阶段 `invalid array length`
   OOM，pnpm 9/10 同样表现，加堆内存无效——崩溃点在 crypto 一次性哈希的超大入参）。换 **node 24.14.0**
   （仓库 `engines` 要求值）后 `pnpm install --filter @zcode/web...` 54s 装完。本机 nvm 因 `~/.nvm/.cache`
   不可写装不了，改用官方二进制解到临时目录、把 `bin` 前置进 `PATH` 即可。
   起服务：`cd ZCode && pnpm --filter @zcode/web dev` → `http://localhost:5173`。
   **web 应用可单独跑**：`/api/v1/remote` 与 OAuth token 都代理到线上同源，不需要本地 `@zcode/server`。
2. **采证链路（已验证）**：`capture.py chrome` 起 9225 调试实例 → `capture.py shot 001-登录 --base local --dom`
   落图成功（`.capture/runs/20261108-073043-001/01-viewport.png`，稳定门 stable）。
3. **线上站点未知**：`web.deployed_base` 留空——线上一级域名未取证，采证以 local dev 为准。
4. **小程序 appid 未提供**：`app.miniprogram.appid` 留空，`project.config.json` 暂用 `touristappid`（游客模式）。
5. **登录态**：`003+` 页面需要先在该调试 Chrome 里登录一次（profile 持久化，登录后长期复用）。
6. **iOS 构建被 shim 缓存（2026-11-08 实证）**：`/usr/local/bin/xcodebuild` 是带代码指纹的包装脚本，
   源码改动后仍可能输出 `构建合流:代码状态未变,复用上次成功构建` 并返回 0 —— **看起来 BUILD SUCCEEDED，产物其实没更新**
   （dylib mtime 不变）。改完代码必须用 `MTT_XCODE_UNSHIMMED=1 xcodebuild …` 真编译，或用
   `strings <dylib> | grep <新标识符>` 反查产物。
7. **微信开发者工具（2026-11-08）**：已安装且已登录；CLI 需先在「设置 → 安全设置」开启服务端口。
   打开项目的命令：`cli open --project <abs>/miniprogram --port <端口>`。
   当前阻塞：`project.config.json` 的 `touristappid` 被拒（「不存在此 AppID」），需真实/测试 AppID。
8. **MCP（wechat-devtools）**：已注册 `cmd mcp add-json wechat-devtools '<json>' --scope user`，
   工具在**下一次会话启动**后才可用。

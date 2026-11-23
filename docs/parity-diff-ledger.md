# 差异台账（APP-DIFF）

> `web-to-app` 差异标记语言的登记册（`references/diff-language.md`）。
> 规则：各端与 web **默认必须一致**；确需不同时，**代码内标记 + 本台账登记，两级缺一不可**。
> 编号 `D###` 全局唯一、跨端共享（同一差异在 iOS 与 Android 复用同一编号）。
> kind 集合见 `config/web-to-app.yaml` 的 `diff_language.kinds`（platform / business / pending）。
> `pending` 类必须挂 `PARITY_BACKLOG` 编号，修完删标记与台账行。

代码内语法：

```swift
// APP-DIFF[D012] platform: 底部抽屉用 SwiftUI sheet 原生实现，web 为 fixed 定位 div，视觉规格已对齐
```

```kotlin
// APP-DIFF[D012] platform: 同上差异在 Android 侧的对应说明
```

## 台账

| 编号 | kind | 页面/模块 | web 实现 | App 实现 | 理由 | BACKLOG | 状态 |
|---|---|---|---|---|---|---|---|
| D001 | platform | 侧边抽屉（012） | 右侧覆盖抽屉 `min(88vw,28rem)`（`WebRemoteControlMobileShell.tsx:222-232`） | iOS `.sheet`（ChatView sheet 组，标记见 ChatView.swift）/ Android `ModalBottomSheet`（ChatScreen.kt:775/888）；**小程序无侧抽屉形态（功能未移植，非刻意差异，2026-11-20 核对）** | 原生弹层语义与手势、无障碍、焦点管理由系统承担，尺寸已对齐 88% 宽 | — | 生效 |
| D002 | platform | 页面返回（010↔011） | `history.pushState` + `popstate`（`WebRemoteControlMobileShell.tsx:28-57,133-147`） | iOS NavigationStack push / Android back dispatcher | 浏览器历史无原生对应，映射为系统返回语义 | — | 生效 |
| D003 | platform | 视口高度 | `dvh`（`RootShell.tsx:8-11`） | 安全区 inset (`safeAreaInset`) | 原生无地址栏收放，改用安全区 | — | 生效 |
| D004 | ~~business~~ **已作废** | 设计 token（三端） | Tailwind token：bg `#161616`、card `#171717`、surface `#262626`、正文 `#d4d4d4`(oklch .87)、次级 60%/最低 30% alpha、brand `#e5e5e5`(oklch .922)、destructive `#ff6467`、分隔线白 10%、输入白 15% | **2026-11-09 起三端完全对齐 web**（iOS `Theme.swift` / Android `Color.kt` / 小程序 `app.wxss` 同值） | 用户裁决「整个 app 与 ZCode web 设计样式保持一致，先不用之前设计的 UI，完全复刻」——原 qoder 色板（#080807/#fafafa）作废 | — | 作废（改为对齐 web） |
| D005 | platform | 返回语义（010↔011） | `history.pushState({zcodeMobilePage:"chat"})` + `popstate`（`WebRemoteControlMobileShell.tsx:28-57,133-147`） | 小程序 `navigateBack` + 自定义返回钮（`utils/nav.js`）；微信无浏览器历史 | 平台无对应机制 | — | 生效 |
| D006 | platform | 任务行动作（010） | hover 显现行内按钮：`task-files-<id>`（显示文件树）/`task-archive-<id>`（归档任务） | 三端均改为**长按行弹菜单**（小程序 `wx.showActionSheet`，iOS/Android 各自长按菜单） | 触屏无 hover；行内双按钮会挤压标题宽度 | — | 生效 |
| D007 | business | 任务行信息密度（010） | 图钉 + 标题 14px 单行 + 时间 12px + 工作流迷你轨道 | 三端同构（iOS/Android 已按用户裁决 2026-11-08 移除首字头像、状态胶囊、预览摘要、agents working） | 用户裁决「严格对齐 web」；运行/待确认态改由轨道点与无障碍锚点承载 | — | 生效 |
| D008 | platform | 搜索入口（010） | 列表头只有搜索图标按钮（点击开命令中心 ⌘K） | **仅 iOS** 保留常驻搜索输入框（`04-search`，ConversationListView.swift:429）；**Android/小程序无搜索入口（功能缺口非刻意差异，2026-11-20 全仓核对无 04-search/搜索态，待裁决是否入 BACKLOG）** | 移动端无键盘快捷键，常驻输入框是唯一可达且不牺牲输入法/焦点体验的形态；且有 10 处 E2E 断言依赖 `04-search` / `04-search-input` | — | 生效 |
| D009 | business | 来源筛选 chips（010） | 无来源概念（web home 恒为单源） | iOS 保留 `全部 / 我的 Mac` chips（`云端沙盒` 档隐藏） | iOS 同时支持 mac 与 cloud 会话来源，chips 是其筛选 affordance；Matrix test01 有 10 处断言依赖 | — | 生效 |
| D014 | platform | 凭据密钥来源钉住（iOS / Android / 小程序） | web 有「系统库 → 私有文件密钥」降级链，并用 `credential-source` 把来源钉为 `os\n`（`systemCredentialCipher.ts:23-24,241-249,276,305`） | iOS Keychain / Android Keystore 是**唯一**来源、不存在弱密钥降级路径 → 钉住条件恒成立，代码不引入冗余来源状态机；小程序无 OS 库，钉 `local\n`，且**只**在「无来源标记且无任何密文」时才新建密钥（有密文无标记 → `unavailable/no-source-marker` 拒绝写入，对齐 web `unbound` 纪律） | 移动端没有「密钥来源可切换」的物理前提，照抄来源状态机会制造永不触发的死代码；小程序侧另外**收紧**了 web `:418` 的硬白名单 `["os","env"]`——他端新增来源判 `unavailable`（保留数据）而非「损坏（可清）」 | P-031 | 生效 |
| D015 | platform | 小程序凭据加密原语 | web `aes-256-gcm`（`systemCredentialCipher.ts:76-96`，node crypto） | 小程序 JS 运行时**无** AES/webcrypto 原语 → 纯 JS 实现 HMAC-SHA256 计数器 keystream + HMAC-SHA256 认证 tag（encrypt-then-MAC），`iv.tag.bytes` 三段 base64url 与 web 同形；sha256/hmac/base64url 与 `node:crypto` 逐字节对拍 | 平台能力缺失。构造仍是标准 EtM（抗篡改与 GCM 同级），但**不是**平台认证的密码学实现，也无硬件隔离；密钥只能存同一本地存储 → 只防「明文落盘」不防「同设备同账号运行时读取」。**2026-11-19 补记**：原 `randomBytes` 误把异步 `wx.getRandomValues` 当同步 ArrayBuffer 用，静默产出全 undefined（密钥等同全零 + IV 固定 = 确定性加密）；已改为「异步预取 CSPRNG 池 + 同步消费 + 耗尽即拒绝」，绝不回退 `Math.random` | P-031 | 生效 |
| D016 | platform | 凭据读取失败语义的类型形状（三端） | `systemCredentialCipher.ts:61-75` 用两个稳定 code 区分「系统密钥不可用」与「密文损坏」（`CREDENTIAL_SECURE_STORAGE_UNAVAILABLE` / `ZCODE_CREDENTIAL_DECRYPT_FAILED`） | iOS `CredentialReadPolicy`（`itemNotFound/unavailable/authFailed/corrupt` 四分 + `CredentialRecovery`）、Android `OAuthCredentialReadFailure`（sealed 6 态 + `CredentialRecovery`）、小程序 `credential-store.js`（`OUTCOME_*` 四态 + `REASON_*` 8 态） | 各端存储原语的失败形态不同（`OSStatus` / Java 异常 / JSON 读写），无法共用一套枚举；**判定口径与清理白名单必须一致**（唯一可清理档 = 确坏），故只在类型命名与细分档位不同 | — | 生效 |
| D017 | platform | 凭据失败态上屏（iOS / Android / 小程序） | web 由设置页/登录流程消费错误码（`packages/shared/src/oauth.ts:42-56`） | iOS `credentialUnavailableCard`（重试读取 / 清除并重新登录）、Android `AppSession.oauthCredentialFailureMessage`（登录/诊断面消费）、小程序 `credentialIssue` 行（`01-note-credential-issue`） | web 是桌面设置页形态，移动端只有卡片/单行容器；文案按端能力裁剪，但必须与实际原因一一对应（禁止统一显示「未登录」） | — | 生效 |
| D018 | business | 连接主页信息架构（001-登录 / 004-连接中 / 005-连接失败） | web `/remote/v4` 无独立连接主页：无 JWT 直接进登录视图（`packages/web/src/web-remote-control/app/webRemoteControlEntry.ts:104-105`，provider 两选），登录后按设备目录自动落点；失败态 = 12 种 reason 的徽标 + 标题 + 说明 + mono 错误行 + nextSteps + 多个恢复按钮（`webRemoteControlScreenCopy.ts:16-141`） | Android `ui/screen/ConnectScreen.kt`：品牌区（主印 84dp）+ 任一时刻一张状态卡 + **仅两个按钮**（账号位「登录 Z.ai 账号」，已登录同位换「选择电脑」；连接位「重连〈电脑名〉」/「扫码连接」/「扫码重连」）；失败卡 = 一行结论 + 一句话（`ConnectFailureCopy.kt` 简化层）；技术原文（codeLine / connect.log）与排查行、删除连接记录仅开发者模式 | 用户裁决 2026-11-24「交互很糟糕…记住电脑后只要连接不要扫码（功能重复）；登录排首位（在不在电脑身边都可用）；改用局域网 / 删除记录普通用户看不懂，过于复杂」。扫码走向与 web 同构（扫到中继链接且未登录 → 登录 → 自动连上），未另造免登录路径 | — | 生效 |

## web 同步基线

| 字段 | 值 |
|---|---|
| web-sync-base | fec975905a2abd4c07e35c40d51fa097a2258ec9 (2026-11-19) |
| 上次同步范围 | logic, ui, api；webhook sync c590468..fec9759（PR #1、PR #2 已合并；PR #2 merge `d5db67ed`）；分支 sync/web-fec9759-20261119 |

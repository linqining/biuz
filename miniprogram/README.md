# BiuZ 微信小程序（W1 地基）

ZCode 桌面端远控的微信小程序端。web 权威源：`ZCode/packages/web` + `ZCode/packages/ui` 的
**手机远控壳**（`packages/ui/src/WebRemoteControlMobileShell.tsx`）——home / chat 两级主壳 +
右侧覆盖抽屉，不是桌面分栏布局。

> 当前状态：**骨架**。10 个页面均为占位（`.wxml` 里只有 key 与 web 源指位），
> 公共件与设计 token 已就位。按 `docs/web-to-app-bootstrap.md` 的波次推进。

## 打开方式

```bash
# 微信开发者工具 → 导入项目 → 选择本目录
open -a "wechatwebdevtools" miniprogram
```

`project.config.json` 里 `appid` 目前是 `touristappid`（游客模式，可跑通 UI 与本地逻辑）。
真机联调需要换成正式 appid 或测试号，并完成下方「合法域名」登记。

## 页面结构

```
pages/
├── sessions/     010-任务主页（home，入口页）
├── chat/         011-会话对话
├── settings/     013-设置
├── login/        001-登录
├── devices/      003-设备列表
├── connecting/   004-连接中
├── share/        009-分享落地
├── git/          029-Diff/Git 面板
├── files/        030-文件管理器
└── tasks/        038-模型轨迹
```

页面 key 与 web 路由、双端验收缝的对照见仓库根 `config/page-index.json`。
新增页面时**必须**同步更新该文件与本目录 `app.json` 的 `pages`。

## 公共件

| 组件 | 对位 |
|---|---|
| `components/empty-state` | iOS `Components.swift:175 EmptyStateView` / web Root 空态分支 |
| `components/loading-state` | iOS `Components.swift:217 CenterLoadingView` / web `RootStartupLoading` |
| `components/error-line` | iOS 内联失败横幅 / web 写面错误行 |

## 平台约束（开工前必读）

1. **合法域名白名单**：微信要求 `request` / `socket` / `uploadFile` / `downloadFile` 域名**分别登记**，
   且必须是 **https / wss + 已备案域名**。远控走 `wss://zcode.z.ai`，上线前需在公众平台后台登记。
   未登记时只能在开发者工具勾选「不校验合法域名」调试，真机必失败。
2. **rpx 换算**：750rpx = 屏宽。web 手机壳按 390pt 视口设计，故 **1px ≈ 1.923rpx**。
   token 已在 `app.wxss` 按此换好，逐页校准时以 web 实拍为基准（`references/ui-parity.md`）。
3. **页面栈上限 10 层**：超限 `navigateTo` 静默失败。统一走 `utils/nav.js` 的 `push()`，
   达阈值自动降级 `reLaunch`。
4. **无 DOM**：canvas 类页面（白板 036）只能截图对比；`wx.createSelectorQuery` 拿布局盒模型做测量。
5. **无系统级返回键语义**：web 用 `history.pushState` + `popstate` 表达 home↔chat，
   小程序对应 `navigateBack` / 自定义返回按钮，**不要**用页面栈隐式表达。

## 开发期域名校验

`project.config.json` 的 `setting.urlCheck = false`（等同开发者工具「不校验合法域名」勾选）——
否则 `wx.request` 直连 `zcode.z.ai` 会被拦成 `request:fail url not in domain list`。
**上线前必须**在公众平台后台登记 `request`/`socket` 合法域名，并把 `urlCheck` 改回 `true`。

## 测试缝

```js
// automator（miniprogram-automator）
await miniProgram.reLaunch('pages/chat/chat?sessionId=<id>')
// 或页面内：?acceptance=1 + 具体参数，见各页 onLoad 的 acceptanceQuery
```

验收缝值以 `config/page-index.json` 的 `app.miniprogram` 字段为准；
**seam 只解决「到页即得」，交互链验收必须补真实入口端到端**（`references/acceptance.md`）。

## 设计 token 差异

三端统一使用 qoder 参考色板，而非 web 的 Tailwind token —— 这是**已裁决的刻意差异**，
登记在 `docs/parity-diff-ledger.md` 的 `D004`（kind: business），代码内以 `APP-DIFF[D004]` 标记。

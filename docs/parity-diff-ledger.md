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
| D001 | platform | 侧边抽屉（012） | 右侧覆盖抽屉 `min(88vw,28rem)`（`WebRemoteControlMobileShell.tsx:222-232`） | iOS `.sheet` / Android `ModalBottomSheet` | 原生弹层语义与手势、无障碍、焦点管理由系统承担，尺寸已对齐 88% 宽 | — | 生效 |
| D002 | platform | 页面返回（010↔011） | `history.pushState` + `popstate`（`WebRemoteControlMobileShell.tsx:28-57,133-147`） | iOS NavigationStack push / Android back dispatcher | 浏览器历史无原生对应，映射为系统返回语义 | — | 生效 |
| D003 | platform | 视口高度 | `dvh`（`RootShell.tsx:8-11`） | 安全区 inset (`safeAreaInset`) | 原生无地址栏收放，改用安全区 | — | 生效 |
| D004 | business | 设计 token（三端） | Tailwind token：背景 `neutral-900 #171717`、品牌 `sky-500 #0ea5e9`（`styles.css:145 @theme` + `:343 .dark`） | qoder 参考色板：近黑 `#080807` + monochrome 主色 `#fafafa` + 独立状态色（iOS `Theme.swift:50` / Android `Color.kt` / 小程序 `app.wxss`） | 用户指定「双端中立近黑/近白主色 + 状态色」（AGENTS.md v1.39 ⑦）。视觉规格与 web 对齐，色值刻意不同 | — | 生效 |
| D005 | platform | 返回语义（010↔011） | `history.pushState({zcodeMobilePage:"chat"})` + `popstate`（`WebRemoteControlMobileShell.tsx:28-57,133-147`） | 小程序 `navigateBack` + 自定义返回钮（`utils/nav.js`）；微信无浏览器历史 | 平台无对应机制 | — | 生效 |
| D006 | platform | 任务行动作（010） | hover 显现行内按钮：`task-files-<id>`（显示文件树）/`task-archive-<id>`（归档任务） | 三端均改为**长按行弹菜单**（小程序 `wx.showActionSheet`，iOS/Android 各自长按菜单） | 触屏无 hover；行内双按钮会挤压标题宽度 | — | 生效 |
| D007 | business | 任务行信息密度（010） | 图钉 + 标题 14px 单行 + 时间 12px + 工作流迷你轨道 | 三端同构（iOS/Android 已按用户裁决 2026-11-08 移除首字头像、状态胶囊、预览摘要、agents working） | 用户裁决「严格对齐 web」；运行/待确认态改由轨道点与无障碍锚点承载 | — | 生效 |
| D008 | platform | 搜索入口（010） | 列表头只有搜索图标按钮（点击开命令中心 ⌘K） | iOS/Android 保留常驻搜索输入框 | 移动端无键盘快捷键，常驻输入框是唯一可达且不牺牲输入法/焦点体验的形态；且有 10 处 E2E 断言依赖 `04-search` / `04-search-input` | — | 生效 |
| D009 | business | 来源筛选 chips（010） | 无来源概念（web home 恒为单源） | iOS 保留 `全部 / 我的 Mac` chips（`云端沙盒` 档隐藏） | iOS 同时支持 mac 与 cloud 会话来源，chips 是其筛选 affordance；Matrix test01 有 10 处断言依赖 | — | 生效 |

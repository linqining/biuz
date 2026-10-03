# BiuZ 移动端客户端 · 设计规范（Design Spec）

> 版本 v2.4 · 2026-10-02 · 基准机型 390×844（iPhone 14/15 Pro 逻辑分辨率）
> v2.4 变更：**新增第 9 章「登录与连接」（本版唯一改动，只增不改），覆盖两条登录路径**——**路径 A · 账号 OAuth**（板 O1–O3：登录主页双路径入口 → **应用内授权 Sheet**（内嵌浏览器视图，不跳系统浏览器）→ 回调拦截 code+state 校验 → `POST /api/v1/oauth/token` 交换 tokenSet/userInfo → 成功（displayName/avatarUrl）/失败取消重试；BigModel 为高级选项 `{origin}/login?appId=zcode`）与**路径 B · 桌面配对**（板 L1–L5：iOS 连接对端为桌面机上的 **zcode-server 进程**（`zcode --web`，非桌面 Electron App），凭据传递（扫码/通用剪贴板/手动）→ `GET /api/server-info` 发现与鉴权 → `WS /ws?token=…`（web-remote-replayable）→ v4 握手（clientKind=mobileApp）→ 以 `workspaces[0]` 载入工作区）；两路径互相独立（账户层 vs 连接层），凭据均仅存 Keychain。新增配套专项稿 `design/login-design.html`（板 **O1–O3 + L1–L5**，含画布头部双路径流程模型图；L4 为双帧：分组 + 配置区——已登录账号退出登录 / 服务地址与令牌编辑 / 连接测试）；**既有令牌（2.1/2.2）、组件（第 5 章）与屏 01–14 规范全部保持 v2.3 口径不变**；testid 规约扩展 O/L 前缀（见 9.6）。**v2.4 评审修订（专项稿与 9 章内）**：补 `.todo-mini` 等新增组件在本稿的自包含定义；补 iOS 本地网络/相机/剪贴板三道系统权限的时机、用途文案与拒绝降级；补 `--no-token`（authRequired=false）令牌可选语义、L2「免鉴权」口径与表单校验态；地址栏支持粘贴完整链接自动拆解（scheme ∈ http/https/ws/wss，token 段自动剥离）；L3/L5 描边改走 `--red-line`/`.row-selected` 令牌（含 Light 映射）；L3 增「工作区列表为空」第四态；L2 头部首次连接以 host:port 占位并注明进度口径；L5 capabilities 双源口径区分；连接域页面边距统一 16px（sp-4）；Tab 栏选择器单源回归主稿 `12-tab-*`。**v2.4 二次修订（产品决策落稿）**：OAuth 授权方式由「跳系统浏览器」改为**应用内授权 Sheet**（用户已决策：应用内完成，不跳系统浏览器）——O2-A 重画为内嵌浏览器视图（顶部 ✕ 关闭按钮 + 加载进度条 + 授权页内容，深色跟随应用），O2-B「深链回调」改为「回调拦截」（redirect 导航在视图内被拦截）；移除 `.sysbar/.sys-body` 系统浏览器示意，新增 `.oauth-sheet/.sheet-grab/.wv-bar/.wv-progress/.wv-page/.perm-row` 组件定义；O2-A 发起参数卡移入稿内标注区（`o2-params-authorize` 保留）。**v2.4 三次修订（评审修复）**：①O2-A 页内区域改**远端授权页示意**口径（`.wv-mock-note` 虚线胶囊标注「真实授权页由 chat.z.ai 提供，外观与按钮不可控」）——「深色跟随应用」降级为**跟随系统**（ASWebAuthenticationSession 无外观控制 API，屏 12 强制主题时此页不随、待真机验证），两载体（ASWebAuthenticationSession/WKWebView）**均禁止注入自绘同意页**（9.7.10 固化注入边界），视觉/E2E 断言只针对 Sheet 容器与 chrome；②流程模型 A2 端点行补 `redirect_uri` 参数；③O3-B 主样卡改用户取消态（中性 mono「未产生 code · 未产生 error」，`error=access_denied` 归位「授权服务器返回 error」分支），9.4 对照修正为**五态**；④新增帧：**O2-A-M**（BigModel 高级入口 Sheet 变体）、**L2-N**（免鉴权 authRequired=false，第 2 步「免鉴权」直接打勾）、**L1-H**（连接帮助内嵌说明页），L4-B 补**已过期样例**（`l4-b-card-expired`：expiresAt 过期/401 → 重新登录，不做静默续期）；⑤令牌纪律：flow-step 强调描边收进 `.flow-step-hl`（含 Light 映射）、L5-S 模态遮罩收进 `.scrim`（设备级黑遮罩）、L4 额度条渐变端点改 `var(--accent-press)→var(--accent)`（基准稿屏 12 为内联 #1f9e5f，本稿按令牌纪律收编，Light 随 2.2 映射）；⑥可用性：键盘标注 pill 10→10.5px（2.3 Footnote 下限）、文字链接热区 43.8→45.8px；⑦L1-S-D 手输逃生口 testid 统一为 `l1-s-btn-manual`，确立**跨帧共用选择器**口径（同一语义控件在变体帧间共用，变体专属断言目标独立命名，见 9.6）。
> v2.3 变更：补 data-testid 三段式命名规约（5.10）并在设计稿内联 123 处关键选择器（Tab 项/工具卡头/diff 行/审批动作/发送键等）；补加载态统一口径（5.9，首屏居中 spinner / 增量行尾 spinner / diff 文件头 spinner，一律不用骨架屏）；字体栈以设计稿实现为准回填（补 BlinkMacSystemFont / JetBrains Mono / Liberation Mono）；Tab 栏注释统一为实测 95px 口径（清除 49px 旧基线残留）；Light 对比度注释统一为实算值（--accent-text 5.08:1、--text-3 修正为 5.78:1）；验收清单补非样张屏 Light 抽检项。
> 配套高保真设计稿：`design/zcode-mobile-design.html`（单文件自包含，浏览器直接打开）
> v2.2 变更：独立终审两项修复——①Light 档 `--add` 提亮为 #0a7340、`--del` 加深为 #b32e20：原 #0a7f3f/#c0392b 仅对白卡复合底达标（4.56/4.84），在代码块复合底（屏 05 工具卡 diff 片段实际渲染面）实算 4.25/4.53，前者低于 AA；新值按最差复合底实算 4.94/5.26，全场景 ≥4.5；②屏 12「自动化 Beta」徽章由内联 #c792ff 硬编码（Light 白底 2.34:1 违例）改为 `.pill tag` 令牌（Light #7c3aed 4.91:1）；全稿文字级内联 hex 清零（新增 `--on-accent` 令牌承载绿底深字，仅存装饰性头像白字）。
> v2.1 变更：审批授权默认值回归「仅本次」（样张与规范对齐）；清除 4 处硬编码色击穿 Light 映射（终端/代码块/用户卡渐变/徽章前景，新增 `--badge-fg` `--on-orange` 令牌）；搜索框与 API Key 输入补齐 16px；屏 08 与 Light-L2 补 Tab 栏；触控残留清零（⋯ 菜单/复制/重试/屏 11 按钮 44px）；Tab 徽章三色全渲染；Tab 栏高度改为实测口径 95px；新增 Android 面包屑规范；Light 对比度改为实算值（4.57/4.53/5.08）；屏 02 取消 FAB 缩进；设备在线改中性绿点；标注漂移修正。
> v2.0 变更：补齐「对话 Tab 根 / 通知中心+Live Activity / 设备与配对」3 屏（共 12 屏）、新增状态与次级页样张板与 Light 主题样张、全部可读色值通过 WCAG AA 验证、触控目标全面 ≥44px、消解 diff 横滚与左滑回退手势冲突、补键盘态折叠策略与审批退出路径。

> **引用来源说明**：文中形如 `DESIGN.md:15`、`AGENTS.md:62-63`、`v4/SessionPane.tsx:2079` 等仓库行级引用，均**转引自本次立项调研材料**；本设计工作区未包含 zcode 仓库源码，本会话无法复验其行号与取值。实现前请在真实仓库根目录核对（详见第 8 节）。

---

## 1. 定位与设计原则

仓库定位 desktop-first、web-compatible（DESIGN.md:22，转引自调研材料），官方移动路径是「手机远控桌面」：手机经 web-remote-replayable 链路复用桌面 Host，不另起 agent（AGENTS.md:62-63），或经 IM Bot 在微信/飞书/Lark/Telegram 中远程下达任务（WebRemoteControlDialog）。共享 UI 已预留 `isMobileViewport` 适配点（v4/SessionPane.tsx:2079、ConversationComposer.tsx:827、TaskList.tsx:365）。

因此本规范的总体定位是——

**手机 = 遥控与审批台，重活 = 云端沙盒 / 配对电脑执行。**

六条设计原则：

1. **遥控优先**：一切界面围绕「下发任务 → 盯执行 → 审批 → 验收产物」闭环，弱化桌面端的编辑/终端/键盘流。
2. **状态即界面**：任务状态沿用三色语义（运行中=蓝 / 待操作=橙 / 已完成=绿，失败=红），卡片列表前置状态胶囊与最近一条 Agent 摘要；紫（#c792ff）保留给能力/生态标签与轨迹/工作流语义，**不参与任务状态**。
3. **审批不可误触也不可被困**：关键操作经系统推送直达独立审批卡，授权范围为 52px 独立单选行；提供「稍后处理」显式出口，Android 返回键=稍后处理。
4. **小屏降维**：桌面三栏降维为「底部 Tab + 三层压栈 + 底部模态」；侧栏面板改为分段控件或底部抽屉。
5. **可用性硬指标**：可交互触控目标 ≥44×44px；可聚焦输入框字号 ≥16px（防 iOS 聚焦缩放，对齐仓库预留 token，DESIGN.md:15）；可读文字对比度 ≥ WCAG AA（4.5:1），全部色值**按最差复合底（含弱底叠加与代码块底）实算验证**（见 2.1/2.2 对比度列）；文字色一律走令牌，禁止内联 hex（装饰性头像字母白字除外）。
6. **深浅两套齐全**：深色为主默认，Light 为同结构令牌映射并给出实渲样张（屏 14）；「跟随系统」为默认档。

---

## 2. 设计令牌（Design Tokens）

### 2.1 色板 · 深色（默认）

对齐 TRAE 视觉语言：主色近黑 `#0a0b0d`、强调色高饱和亮绿 `#32f08c`（HSL≈148°）、「背景 → 卡片 → 浮层」三级递亮分层（背景 L≈4.5%、卡片 L≈6.5%、浮层 L≈8%）。状态色沿用 Qoder 语义：Running=蓝、Waiting=橙、Completed=绿。

| 令牌 | 值 | 用途 | 对比度（AA 验证） |
| --- | --- | --- | --- |
| `--bg` | `#0a0b0d` | 页面背景 | — |
| `--bg-card` | `#121316` | 卡片 | — |
| `--bg-elevated` | `#17181c` | 浮层 / Sheet / 抽屉 | — |
| `--bg-input` | `#1a1c21` | 输入框、分段控件底、进度槽 | — |
| `--bg-code` | `#0d0e10` | 代码块底 | — |
| `--bg-term` | `#050607` | 终端底 | — |
| `--border` | `#26282e` | 常规描边 | — |
| `--border-strong` | `#34373f` | 输入框/分段描边、抓手条 | — |
| `--text` | `#f5f9fe` | 主文字 | 15.9:1 ✓ |
| `--text-2` | `#a6aab5` | 次级文字 | 8.1:1 ✓ |
| `--text-3` | `#8a8f9b` | 说明/占位/时间戳/行号（最小说字） | 卡片上 5.79:1 · 背景上 5.93:1 ✓ |
| `--accent` | `#32f08c` | 强调绿：主按钮底、激活态、已完成（**深字 #04120a**） | 深字 12.7:1 ✓ |
| `--accent-text` | `#32f08c` | 绿色链接/强调文字 | 12.7:1 ✓ |
| `--accent-press` | `#28c974` | 强调绿按压态 | — |
| `--accent-dim` | `rgba(50,240,140,.12)` | 绿色弱底 | — |
| `--blue` | `#4da3ff` | 状态「运行中」 | 卡片上 7.1:1 ✓ |
| `--blue-dim` | `rgba(77,163,255,.14)` | 蓝色弱底 | — |
| `--orange` | `#ffb224` | 状态「待操作/待批准」（深字 `#1a0f00` 12.9:1 ✓） | 文字 9.8:1 ✓ |
| `--orange-dim` | `rgba(255,178,36,.14)` | 橙色弱底 | — |
| `--red` | `#ff5d5d` | 危险操作（拒绝/停止/失败） | 卡片上 6.1:1 ✓ |
| `--red-dim` | `rgba(255,93,93,.12)` | 红色弱底 | — |
| `--red-line` | `rgba(255,93,93,.55)` | 红色描边按钮 | — |
| `--violet` | `#c792ff` | **能力/生态标签、轨迹/工作流语义**（非任务状态） | — |
| `--violet-dim` | `rgba(199,146,255,.12)` | 紫色弱底 | — |
| `--add` / `--add-bg` | `#3ddc84` / `rgba(50,240,140,.10)` | diff 新增行文字/底色 | 8.9:1 ✓ |
| `--del` / `--del-bg` | `#ff7a7a` / `rgba(255,93,93,.10)` | diff 删除行文字/底色 | 6.4:1 ✓ |
| `--code-kw` | `#c792ff` | 代码关键字 | — |
| `--code-lab` / `--code-lab-dim` | `#8f9bdd` / `rgba(125,139,221,.08)` | 代码类型、终端日志标签、diff hunk 头 | — |
| `--tabbar-bg` | `rgba(13,14,16,.92)` | Tab 栏 / 输入栏毛玻璃底 | — |
| `--grad-bubble` / `--grad-avatar` / `--grad-usercard` | 渐变 | 用户气泡 / 头像 / 我的页用户卡（主题映射） | — |
| `--home-bg` / `--sb-fg-dim` | `rgba(245,249,254,.85)` / `rgba(245,249,254,.5)` | Home 指示条 / 状态栏电池 | — |
| `--badge-fg` | `#0d1500` | Tab 徽章前景（橙底 10.5:1 / 蓝底 7.3:1） | ✓ |
| `--on-orange` | `#1a0f00` | 橙色按钮前景（`--orange` 底上 10.5:1） | ✓ |

> 注：diff 新增/删除色对齐仓库 `DESIGN.md:118-119` 的 token 语义（转引自调研材料）；具体色值为本规范推导值，接入时以仓库 token 替换（第 8 节）。

### 2.2 色板 · Light 主题（跟随系统档 · 与深色同构映射）

同一套结构令牌整体覆盖（CSS 变量切换，不维护两套布局）；实渲样张见设计稿屏 14（任务看板 Light / Diff 审查 Light）。对比度均为 WCAG 相对亮度实算值，且以**最差复合底**为准：如 diff 新增行文字对比需在「代码块底 + 同色弱底」复合面上验证（屏 05 工具卡 diff 片段的实际渲染面），仅验白卡会高估。

| 令牌 | Light 值 | 对比度（AA 验证） |
| --- | --- | --- |
| `--bg` / `--bg-card` / `--bg-elevated` | `#f4f5f7` / `#ffffff` / `#ffffff` | — |
| `--bg-input` / `--bg-code` / `--bg-term` | `#eceef2` / `#f6f7f9` / `#fbfbfc` | — |
| `--border` / `--border-strong` | `#e3e5e9` / `#d2d5db` | — |
| `--text` / `--text-2` / `--text-3` | `#17181c` / `#4b5563` / `#5f6673` | 15.9 / 7.5 / 5.78:1 ✓（--text-3 白底实算值） |
| `--accent`（按钮底，深字 #04120a） | `#17b26a` | 6.7:1 ✓ |
| `--accent-text` | `#0a7f45` | 白底 5.08:1 ✓ |
| `--blue` / `--orange` / `--red` | `#1a6fd4` / `#9a5200` / `#c53030` | 4.6 / 5.5 / 5.1:1 ✓ |
| `--add` / `--del` | `#0a7340` / `#b32e20` | **按最差复合底实算**（代码块底+同色弱底）：增 4.94 / 删 5.26:1 ✓（白卡复合 5.31 / 5.62；纯白 5.93 / 6.31；回归判定以最差复合底 4.5 为线） |
| `--badge-fg` / `--on-orange` | `#ffffff` / `#ffffff` | 橙底 5.5:1 · 蓝底 4.6:1 ✓ |
| `--code-kw` / `--code-lab` | `#7c3aed` / `#1a6fd4` | 5.7 / 4.6:1 ✓ |
| `--orange` 按钮前景 | **白字 #ffffff**（深色档为深字 #1a0f00） | 5.5:1 ✓ |

「跟随系统」切换行为：默认监听系统深浅变化即时换肤；用户在屏 12「外观」可选 跟随系统 / Zai Light / Zai Dark，偏好经跨窗口广播同步并防回环（AGENTS.md:54，转引自调研材料）。

### 2.3 字体

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--font-ui` | `-apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", "HarmonyOS Sans SC", "MiSans", "Segoe UI", "Microsoft YaHei", sans-serif` | 全部界面文字（系统栈，零外部依赖；以设计稿 HTML 实现为准） |
| `--font-mono` | `ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, "Liberation Mono", monospace` | 路径、命令、diff、终端、行号（以设计稿 HTML 实现为准） |

字号阶梯（8px 栅格派生）：

| 层级 | 字号/行高 | 字重 | 场景 |
| --- | --- | --- | --- |
| Display | 22 / 28 | 800 | Tab 根页大标题 |
| Title | 17 / 24 | 700 | 导航栏标题、卡片标题、Sheet 标题 |
| Body | 14–15 / 22 | 400–600 | 正文、消息、列表行 |
| Caption | 12 / 18 | 400–600 | 摘要、标签 |
| Footnote | 10.5–11 / 16 | 500 | 时间戳、元信息（**必须使用 `--text-3` 以上对比度，禁止再叠 opacity**） |
| Code | **12–13** / 1.7–1.8 | 400 | diff/终端/代码块正文（对齐参考要点④；**行号等元信息 10.5px**） |

**iOS 输入防缩放**：一切可聚焦输入框 `font-size ≥ 16px`（会话输入栏、新建任务输入区、API Key 表单均已按此执行；DESIGN.md:15 转引自调研材料）。

### 2.4 圆角

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--r-s` | 8px | 小 chips、行内标签、todo 勾选框 |
| `--r-m` | 12px | 卡片、按钮、输入框、分段控件 |
| `--r-l` | 16px | 大输入区、设置分组容器 |
| 999px | 胶囊 | 状态胶囊、搜索框、发送键 |
| 24 / 48 / 56px | — | Sheet 顶角 24、屏幕圆角 48、手机外壳 56 |

### 2.5 间距（8px 增量栅格）

`sp-1` 4px 微间距 · `sp-2` 8px 组件内间距 · `sp-3` 12px 卡片内边距 · `sp-4` 16px 页面左右安全边距 · `sp-6` 24px 分区间隔 · `sp-8` 32px+ 区块留白。

### 2.6 阴影与高度

| 令牌 | 深色值（Light 覆盖见 2.2） | 用途 |
| --- | --- | --- |
| `--sh-card` | `0 8px 24px rgba(0,0,0,.45)` | 卡片、分段激活段（Light：`0 4px 16px rgba(23,25,35,.08)`） |
| `--sh-sheet` | `0 -12px 48px rgba(0,0,0,.6)` | 底部 Sheet / 抽屉 |
| FAB 光晕 | `0 10px 30px rgba(50,240,140,.35)` | 新建按钮 |
| Tab Bar / Composer | `--tabbar-bg` + `backdrop-filter: blur(20px)` | 底部毛玻璃 |
| 触觉反馈 | 轻 = 点选/展开；成功 = 批准；警告 = 拒绝/停止 | 交互反馈 |

### 2.7 动效

| 场景 | 曲线/时长 |
| --- | --- |
| Push 入栈 | 右缘滑入 `cubic-bezier(.32,.72,.36,1)` 320ms，背景页视差 -30% |
| 边缘右滑返回 | 交互式跟手，松手阈值 40% 屏宽 |
| Sheet 升起 | spring(stiffness≈380, damping≈34)，背景压暗 + 缩放 0.96 |
| 键盘避让 | Sheet/输入栏上缘对齐键盘顶，keyboardWillShow 通知驱动，250ms 与键盘同轨 |
| 流式输出 | 增量淡入 120ms；光标闪烁 steps(1) 1s |
| spinner | 1s 线性旋转（运行中蓝，底环 rgba(77,163,255,.28)） |
| 下拉刷新 | 阻尼拖拽，阈值 64px |

---

## 3. 导航模型

### 3.1 底部 Tab（4 个）

| Tab | 图标 | 内容 | 徽章 |
| --- | --- | --- | --- |
| 1 任务 | 收件箱 | 任务看板（状态三分组卡片流，屏 02） | 运行中任务数（蓝） |
| 2 对话 | 消息泡 | 最近会话流（屏 04），承接看板/通知进入 | Agent 提问未读（橙数字） |
| 3 审查 | git-compare | 待审 Diff / 审批聚合（屏 08） | 待审文件数（橙数字） |
| 4 我的 | 人像 | 账号、额度、设备配对（屏 11）、设置（屏 12） | — |

Tab 规格：图标 22px + 标签 10.5px，整体热区 **≥64×48px**；激活色强调绿。Tab 栏高度构成（390 基准实测）：顶部内边距 7px + Tab 热区 48px + 底部内边距 6px + 底部安全区 34px ≈ **总高 95px**；实现以系统安全区 API 动态计算，不按固定 49px 裁切。毛玻璃底。
徽章三色（16px 圆角、前景 `--badge-fg`）：**Tab1 蓝**（`--blue` 底）=运行中任务数；**Tab2 橙**（`--orange` 底）=Agent 提问未读；**Tab3 橙**=待审文件数。三个数字徽章在全部 Tab 根页常显（含激活 Tab）。

### 3.2 页面层级（三层压栈 + 模态 + 通知层）

```
Tab 根（L1）
 ├─ 02 任务看板 ─push─▶ 05 会话流（L2）─push─▶ 07 执行输出 / 09 产物预览（L3）
 │              ├─sheet─▶ 03 新建任务
 │              └─push─▶ 10 通知中心（铃铛入口）
 ├─ 04 会话列表（Tab2 根）─push─▶ 05 会话流
 ├─ 05 会话流 ─sheet─▶ 06 审批请求（稍后处理可退出）
 ├─ 08 Diff 审查（Tab3 根）─▶ 文件 ⋯ 菜单（回退/桌面打开/复制路径）
 └─ 12 我的（Tab4 根）─push─▶ 设置二级页 / 11 设备与配对
 └─ 01 登录配对（未登录拦截页，独立栈）─push─▶ API Key 表单（屏 13-⑥）
 样张：13 状态与次级页样张 · 14 Light 主题样张
```

- **返回**：iOS 边缘右滑；Android 系统返回 + 页内左上返回键（44px）。Push 层隐藏底部 Tab 栏（如屏 10/11）；Tab 根页（屏 02/04/08/12）必须保留 Tab 栏，底部动作栏叠于其上（如屏 08）。
- **Android 面包屑**（对齐参考要点②）：Android 侧在系统返回之外，二级/三级页标题区下方显示**可点面包屑细条**（12px、`--text-3`、分隔符 ›，如 `任务 › 重构会话持久层 › store.ts`），逐级可点回跳；Tab 根页不显示面包屑（仅当前 Tab 名）。iOS 不渲染面包屑（以边缘右滑返回）。
- **转场**：Tab 切换无动画直达（保留滚动位置）；Push/Pop 右缘滑入；Sheet 底部升起。
- **安全区**：顶部 54px（灵动岛）、底部 34px（Home 指示条）；全部可点元素距屏缘 ≥16px，底部内容一律使用 `--safe-bot` 变量，禁止硬编码。

### 3.3 手势冲突优先级（全局规则）

| 冲突 | 裁定 |
| --- | --- |
| 会话输入区横滑 / diff 横滚 vs iOS 边缘右滑 | 输入/横滚手势优先；返回仅边缘 20px 生效 |
| **diff 横滚 vs 文件卡左滑回退** | diff 展开态**禁用整卡左滑**，回退收进文件头「⋯」菜单（回退此文件/在桌面端打开/复制路径）；仅折叠态文件卡保留左滑回退（无横滚共存） |
| 语音按住说话 vs 列表滚动 | 按住即锁定手势 |
| Sheet 抓手下拉 vs 关闭 | 审批 Sheet 下拉不关闭（见 4.06）；其余 Sheet 可下拉关闭 |

---

## 4. 逐屏布局说明（12 屏 + 2 样张板）

### 01 登录与扫码配对（未登录拦截页）
品牌区（84px 渐变 Z 标）→ 主按钮「通过 Coding Plan 登录」（48px 绿）→「使用 API Key 登录」（描边）→ 分隔线 → 扫码配对卡 → 底部 Provider 说明与协议链接（**距底 `--safe-bot + 12px`**）。次级路径：API Key 表单为 Push 全屏页（Provider 选择 → Key 输入 16px → 安全区存储说明 → 保存，样张屏 13-⑥）；《用户协议》《隐私政策》以内嵌 WebView 打开（左上关闭）。对应 `WelcomeScreen` / `LoginApiKeyForm`（转引自调研材料）；扫码对应 WebRemoteControlDialog / web-remote 配对链路。异常态见屏 13-⑤。

### 02 任务看板（Tab 1 根 · 会话列表）
大标题问候区 → 搜索框（44px，16px 字号）→ 状态三分组卡片流（Qoder My Quests 范式，按紧急度排序）：待操作（橙，置顶）/ 进行中（蓝，任务卡 = 标题+胶囊+mono 工作目录+Agent 摘要 ≤2 行+todo 进度+工具标签）/ 已完成（绿，88% 透明度折叠近 3 条）。卡片不做 FAB 缩进——FAB 56px 悬浮于列表右下（标准悬浮范式），列表底部由滚动末端自然留白。「去审批」按钮 **44px**（前景 `--on-orange`）、追问按钮 44×44；FAB 56px。「查看全部」为 44px 热区文字钮。右上铃铛（44px 热区）→ Push 通知中心（屏 10）；头像 → 屏 12。失败态卡片见屏 13-④。对应 `TaskList / WorkspacePinnedTasksSection / WorkspaceTimelineTasksSection`。

### 03 新建任务 Sheet（模态）
抓手条 + 「新建任务 / 取消」头部（**sticky 常驻**）→ 大输入区（16px 字号，内嵌 @/附件/语音 **44×44px** 图标）→ 上下文 chips（交互 chips 44px）→ 执行端单选卡（云端沙盒 / 我的 Mac）→ 设置组（工作目录/模型与思考等级）→ 建议提示词 chips（44px）→「开始任务」主按钮 48px。

**键盘态折叠策略**（真机几何保证）：键盘弹起时①自动收起「试试这些」与「执行端」两区；②Sheet 上缘对齐键盘顶；③头部 sticky、内容区 `overflow-y:auto` 可滚；④输入框与主按钮始终可见；⑤「取消」键盘态变为「收起键盘」。由此内容总高（约 620px）恒小于键盘态可视区（约 508px 收起两区后约 430px）。配对电脑离线时执行端置灰（屏 13-③）。对应 `NewTaskButtonGroup / DirectoryBrowser / ModelConfigSelect / ThoughtLevelCycleControl`。

### 04 会话列表（Tab 2 根 · 最近会话）
大标题「对话」+ 右上新建（44px）→ 搜索（44px）→ 置顶 → 今天 → 昨天 时间线倒序（对应 `WorkspaceTimelineTasksSection`）。会话行 = 40px 头像（Agent 绿 / 工具类型灰图标 / 已完成绿勾）+ 任务标题 + 最近一条消息摘要 + 时间 + 未读徽章；运行中行附蓝色胶囊。点行 → Push 屏 05；左滑 → 置顶/归档/重命名。空态见屏 13-① 变体。

### 05 Agent 对话 · 会话流（Push L2）
导航区（返回 + 标题 + 运行中胶囊 + `todo 3/5 · 12 分钟`）+ 3px 进度细条（60%）。消息流：用户右侧气泡 → Agent 全宽 markdown（22px Z 头像）→ 工具调用卡（头 **min-height 44px**；kind mono 大写 + 路径 + 状态 + 展开箭头；bash 卡展开输出，edit 卡展开 unified diff 片段 12px + 「查看完整 Diff」44px 行）→ todo 拆解卡（3/5，done 划线 / now 蓝框 / todo 空框）→ Agent 提问卡（蓝描边 + 快捷回复 chips **44px**）→ 流式行（spinner + 光标）。底部常驻输入栏：工具行（模型 pill、思考 pill、上下文 64% 进度条）+ 输入行（输入框 **16px 字号**、44px 语音键、44px 圆形发送键）。对应 `SessionPane / ConversationTimeline / LexicalChatInput / ToolCallBlocks / todo / contextUsage`。

### 06 审批请求弹层（模态 · 关键操作）
背景压暗 + 顶部「任务在等待你的批准」悬浮胶囊。Sheet：盾图标（橙）+ 标题 → 命令卡（类型行 + mono 命令体 12px + 影响摘要）→ **授权范围：三行 52px 独立卡片单选**（默认仅本次；「始终允许」行尾红字「需谨慎」）→ 动作栏「拒绝（红描边）/ 批准执行（绿填充）」各 48px →「追问 Agent，再决定」44px 行 →「稍后处理」44px 行。推送直达本卡。

**退出路径**：下拉抓手不可关闭，但①「稍后处理」收起 Sheet，任务回到看板待操作分组与通知中心；②Android 系统返回 = 稍后处理（toast「已保留在待操作」，不产生决定）；③批准/拒绝后 Sheet 收起 → 返回会话页 + 结果 toast，结果写入会话流。常驻：iOS Live Activity / 锁屏（视觉见屏 10）。对应 `PermissionDialog / ElicitationDialog / V4InteractionDialogs`。

### 07 任务执行输出 · 终端（Push L3）
导航「执行输出」+ 分段控件（**44px**：「后台 Bash / 模型轨迹」）→ 状态条（spinner + 任务名 + 时长 + 运行中胶囊）→ 终端卡（`--bg-term` 底、头部 44px 含 **44px 复制热区**、等宽 **12px**、`$` 绿提示符、`[migrate]` 日志标签、✓ 绿结果、闪烁光标）→「复制全部 / 停止任务」44px → 模型轨迹与子智能体入口行。停止需二次确认。对应 `BackgroundBashOutputSidePane / ModelTrajectory* / SubagentSessionSidePane`；交互式终端不做。

### 08 Diff 审查 · 文件变更（Tab 3 根）
大标题「审查」+ 分支胶囊（`GitBranchSwitcher`）→ 统计行 → 文件卡列表：展开态 = 文件头（**44px**：图标+mono 路径+±统计+**⋯ 菜单**+折叠箭头）+ unified diff（**正文 12px**、行号 10.5px、`--text-3` 无附加透明度；hunk/删除/新增/上下文四类行）+ **44px 批准/拒绝真按钮**；折叠态 = 44px 单行卡。底部动作栏「全部批准 / 桌面端继续」**叠于 Tab 栏之上**（Tab 3 根必带 Tab 栏，审查激活+角标 2）。手势规则见 3.3（回退经 ⋯ 菜单——入口 44×44px——或折叠态左滑）。空态见屏 13-②。对应 `GitPane / GitPaneChangeCard / ConversationFileRewindDialog`；diff 色对齐仓库 token 语义（转引）。

### 09 产物预览 · 文件浏览（Push L3）
导航：文件名 + 大小 + 分享 → 分段控件（44px：预览/源码）→ markdown 渲染（标题/正文/列表/SQL 代码块 **12px** 带复制 44px 头部、提示卡）→ 底部「浏览工作区文件」抽屉入口。覆盖 PreviewPane 的 md/代码/图片/音视频/PDF/PPTX/Office 多格式；抽屉内搜索对应 `workspace-file-search`（.zcodeignore 范围）。此屏同时承载**知识中心/Repo Wiki 的移动形态**（只读文档浏览，见 6.7）。

### 10 通知中心 + Live Activity（Push）
分段（全部 / 待办 N）+ 三类聚合：需要处理（橙，权限/审批，卡内 44px「去审批」直达屏 06）、Agent 提问（蓝，快捷回复 44px）、已完成（绿，附变更统计与「待你审查」）。板内附 **Live Activity 锁屏卡**：任务名 + 进度 + 下一步 + 「批准/查看」直达；灵动岛挤压态显示百分比，任务结束自动收起。推送路由标注于屏内底部。入口：屏 02 铃铛。

### 11 设备与配对管理（Push）
两类执行端卡（在线状态用中性绿点，非状态胶囊）：云端沙盒（在线/区域/用量 + 用量账单/新建沙盒 44px）与配对电脑（在线/Host 版本/电量 + **文件夹级授权 chips**（目录·读写级别）+ 管理授权 44px + 解除配对红描边 44px 二次确认）→「扫码配对新设备」主按钮（内嵌取景器）→ IM Bot 行。设备离线联动：屏 03 执行端置灰（屏 13-③）。对应 `WebRemoteControlDialog / BotsDialog`、server/src/remote；SSH/WSL 向导留桌面。

### 12 我的 · 设置与设备（Tab 4 根）
用户卡（头像 + Coding Plan 徽章 + 剩余额度条 68%）→ 四分组（行高 48px，按使用频率排序，低频组允许滚动裁切）：设备与远控（配对设备→屏 11、IM Bot）/ 基础设置（模型设置、外观=跟随系统·Zai Light·Zai Dark、语言、通知）/ 数据与统计（用量统计近 30 天、记忆 128 条）/ Agent 能力（技能、MCP、插件商店「New」**紫 tag 徽章**、自动化 Beta）。分组对齐桌面 `settingsPageConfig` 三组并增补设备组。快捷键、电脑控制、终端设置等桌面项不出现。

### 13 状态与次级页样张（组件级板）
六张样张卡，每张含出现条件与恢复路径：
- ① 看板空态（首次使用，CTA → 屏 03）；
- ② 审查 Tab 空态（工作区 clean / 全部已审，角标消失）；
- ③ 桌面端离线（橙横幅 + 重试；执行端置灰禁用、本地缓存提示；自动重连指数退避）；
- ④ 任务失败态（红胶囊 + 最后日志 mono + 查看日志/重试，重试从最后快照续跑；进入通知中心）；
- ⑤ OAuth 回调失败（红错误条 + 重试登录 / 改用 API Key，表单态保留）；
- ⑥ API Key 表单页（Provider 选择、Key 输入 16px、安全区存储说明）。

### 14 Light 主题样张
任务看板 Light 与 Diff 审查 Light 两张实渲样张（与深色版同结构），验证三级分层、状态胶囊、diff 双色与按钮前景在 Light 下的可读性；机制与对比度数据见 2.2。其余屏的 Light 呈现由令牌表驱动。

---

## 5. 组件规范

### 5.1 状态胶囊 Pill
高 22px（紧凑 18px），字号 11px/600，圆角 999，左缀 6px 状态点。五态：`run`（蓝）、`wait`（橙）、`done`（绿）、`err`（红：失败/拒绝语义，用于任务失败卡、离线标记）、`tag`（紫：能力/生态标签如插件 New、自动化 Beta，**非任务状态**）。**凡能力/生态徽章一律使用 `.pill tag` 类（走 `--violet/--violet-dim` 令牌），禁止内联色值**——Light 档 `--violet` 为 #7c3aed（白底 4.91:1），硬编码深色值 #c792ff 在白底仅 2.34:1。胶囊本身不是按钮——凡可点击的动作一律用按钮样式（见 5.5）。**设备/连接的「在线」状态不复用任务状态胶囊**：以中性绿点（6px `--accent` 圆点）+ `--text-2` 文字表达，与蓝/橙/绿任务语义解耦（屏 11/12）。

### 5.2 任务卡
内边距 14px，纵向间距 9px；标题 15.5px/700 与胶囊同行；工作目录 11px mono（`--text-3`）单行截断；摘要 12.5px 两行截断；todo 进度条（4px、蓝填充）+ mono 计数；工具标签行。整卡可点（≥88px 高）；失败卡为红描边变体（屏 13-④）。

### 5.3 工具调用卡 ToolCallCard
头部 **min-height 44px**（padding 10px 12px）：kind（11px mono 大写灰）+ 对象路径（11.5px mono）+ 右侧状态区（spinner/✓ + 耗时 / ±行数 + 箭头）。展开体 12px mono 弱底。点击头部展开/收起；diff 片段尾部「查看完整 Diff」为 44px 行。

### 5.4 diff 行
正文等宽 **12px**（参考要点④）、行高 1.7、`white-space: pre` 禁折行、容器横滚；行号 10.5px、宽 32px 右对齐、`--text-3`（5.79:1，**不叠 opacity**）。四类行：`hunk`（`--code-lab` + `--code-lab-dim` 底）、`del`（红字红底 10%）、`add`（绿字绿底 10%）、`ctx`（`--text-2`）。文件级折叠 + 按 hunk 过滤；逐文件批准/拒绝用按钮（5.5）。

### 5.5 按钮（动作必须用按钮，不用胶囊/裸文字）
- 主按钮 48px 绿底深字；描边按钮 48px；危险按钮红描边。
- 逐文件批准/拒绝：**44px 独立按钮**，左右布局间距 10px。
- 授权范围单选：**三行 52px 卡片式 radio 行**（`.choice-row`），默认仅本次，「始终允许」红色提示。
- 文字链接类动作（追问 Agent / 稍后处理 / 查看全部 / 协议链接）：**min-height 44px 热区**（padding 外扩法）。
- 卡片内嵌按钮（去审批、回复、重试等）：44px。

### 5.6 输入栏 Composer（会话页常驻）
工具行（模型 pill、思考 pill、上下文进度条）+ 输入行（胶囊输入框 44px 高 **16px 字号** + 44px 语音键 + 44px 圆形发送键）。键盘避让同 2.7；@ 唤起文件选择器，斜杠命令同选择器呈现。

### 5.7 Tab Bar / FAB / 分段控件 / chips / 设置行
- Tab Bar：顶部内边距 7px + Tab 热区 48px + 底部内边距 6px + 安全区 34px ≈ 总高 95px（390 基准实测，按系统安全区动态计算），tab 热区 ≥64×48px，徽章 16px（三色语义见 3.1，前景 `--badge-fg`）；
- FAB：56px、18px 圆角、绿底深字；
- 分段控件：容器 `--bg-input` 12px 圆角，段内边距 11px（**高 ≥44px**），激活段 `--bg-elevated` + 卡片阴影；
- chips 二分法：**可交互 chips（`.chip-btn`）min-height 44px**（引用上下文、建议提示词、快捷回复）；**纯展示标签**（任务卡内 bash/store.ts、授权目录）不适用 44px 但不可点击（点击目标为其所在卡片/行）；
- 设置分组行：高 48px（≥44px 底线），左 30px 图标位，标题 14.5px + 副标题 11.5px（`--text-3`、单行截断），右侧值 + 16px 箭头；整行可点。

### 5.8 终端 / 代码块
底 `--bg-term` / `--bg-code`，12px 圆角；头部行 min-height 44px 含窗口三点（终端）与 **44px 复制热区**；内容等宽 **12px**、行高 1.75、横滚禁折行；`$` 绿、日志标签 `--code-lab`、成功 ✓ 绿、光标绿块闪烁。

### 5.9 空态 / 失败态 / 离线态 / 加载态
统一模式：居中图标（56px 弱底）+ 一句结论（14px/700）+ 一句解释（11.5px `--text-3`）+ 唯一主 CTA（44px）；列表型空态不显示骨架屏。横幅态（离线/OAuth 失败）为弱底 + 描边 + 图标 + 动作文。全部样张见屏 13。

**加载态统一口径**（全稿一律**不使用骨架屏**，与列表型空态的口径一致；spinner 复用 2.7 的运行中令牌，1s 线性旋转）：
- **首屏加载**（会话列表 / 任务看板 / 审查 Tab 首次进入）：居中 spinner（复用空态 56px 图标位）+ 一行 11.5px `--text-3` 文案（如「正在同步任务…」），不渲染任何卡片占位；选择器 `<屏号>-loading-center`；
- **翻页 / 增量加载**（列表尾部、会话历史向上加载）：行尾 16px 行内 spinner（复用空态图标位尺寸减半），不插入占位卡片；选择器 `<屏号>-loading-inline`；
- **Diff 懒加载**（屏 08 折叠文件卡展开时）：仅在文件头右侧状态区出现 spinner（同工具卡头状态位），正文区保持空白直至 diff 到达；选择器 `<屏号>-loading-diff`；
- **加载失败**：就地转为 5.9 对应的失败/离线态（不做超时重试动画叠加）。

### 5.10 data-testid 命名规约（E2E 选择器约定）

**三段式 `<屏号>-<组件>-<语义>`**，全小写 kebab-case；屏号为两位（01–12），样张板屏 14 的 Light 版以 `14-l1-` / `14-l2-` 为前缀。E2E 用例一律依赖 `data-testid`，**禁止依赖样式类名、内联样式、文案文本或结构序号**（文案随 i18n 与数据变化，类名随重构变化）。

命名细则：
- `<屏号>`：正片屏 01–12；样张 `13-*`、`14-l1-*`、`14-l2-*`；
- `<组件>`：交互原语小写，如 `tab`、`toolcard-head`、`toolcard-body`、`diffrow`、`filecard-head`、`choice`、`act`（动作栏按钮）、`composer`、`fab`、`seg`（分段控件段）、`chip`、`row`（设置行）、`card`、`loading`；
- `<语义>`：业务含义，如 `approve`、`reject`、`once/task/always`、`bash/edit/ask`、`add/del/ctx/hunk`（diff 行四类，同屏同类多行时追加 `-<序号>`）；
- 唯一性约束：同屏内唯一；跨屏由屏号区分（如 `05-diffrow-add-1` 与 `08-diffrow-add-1` 各自独立）。

**设计稿已标注的关键选择器**（`design/zcode-mobile-design.html` 内联，共 123 处，覆盖全部审查点名类别）：

| 类别 | 选择器示例 | 落点 |
| --- | --- | --- |
| Tab 项 | `02-tab-tasks` `04-tab-chat` `08-tab-review` `12-tab-me`（`14-l1/l2-tab-*` 同构） | 4 个 Tab 根页 + 屏 14 两版，全 24 项 |
| 工具卡头 / 卡体 | `05-toolcard-head-bash` `05-toolcard-head-edit` `05-toolcard-head-ask` `07-toolcard-head-trajectory` `07-toolcard-head-subagent` | 屏 05/07 |
| diff 行 | `05-diffrow-hunk-1` `08-diffrow-del-1` `08-diffrow-add-2` `14-l2-diffrow-ctx-1` | 屏 05/08/14-L2 三块，行级序号化 |
| 审批动作 | `06-act-approve` `06-act-reject` `06-act-followup` `06-act-later` `06-choice-once/task/always` `06-perm-cmd` | 屏 06 全动作与授权行 |
| 发送键 / 输入 | `05-composer-send` `05-composer-input` `05-composer-mic` `03-submit-start` | 屏 03/05 |
| 其余关键 | `01-login-oauth/apikey/scan`、`02-fab-newtask`、`02-taskcard-approve`、`03-exec-cloud/mac`、`07-act-stop/copyall`、`07-seg-bash/traj`、`08-branch-switcher`、`08-filecard-approve/reject`、`08-act-approve-all`、`09-seg-preview/source`、`09-code-copy`、`09-drawer-filetree`、`12-row-*`（12 个设置行）、`13-btn-retry`、`11-btn-scan` 等 | 各屏主交互 |

未标注的纯展示元素（状态胶囊、文本、图标）不参与 E2E 定位；新增交互组件时按本规约先行标注再接入用例。

---

## 6. 对 secondary / skip 模块的取舍说明

| 模块 | 优先级 | 移动端取舍 | 呈现位置 |
| --- | --- | --- | --- |
| 会话分享 | secondary | 保留**消费侧只读落地页**；生成入口收进会话「⋯」菜单 | 分享菜单 + 只读时间线 |
| 动态工作流 | secondary | 仅**运行状态查看**（阶段/提问/产物 chips，屏 07 轨迹入口承载）；编辑/保存不入移动端 | 屏 07 |
| 子智能体 | secondary | 只读运行状态（屏 07 轨迹卡） | 屏 07 |
| 插件商店 | secondary | 保留**商店浏览**；安装/卸载管理引导桌面端 | 屏 12「插件商店」 |
| IM Bot / 手机远控 | secondary | **配置精简但通道保留**：屏 11/12 展示绑定与活跃状态；扫码绑定/回复粒度配置引导桌面 BotsDialog | 屏 11 |
| 技能 / MCP / 命令 | secondary | 查看 + 启停（屏 12）；命令/钩子编辑 skip | 屏 12 |
| 自动化（beta） | secondary | 查看定时任务与运行状态 | 屏 12 |
| 记忆 | secondary | 查看与逐条删除；诊断工具 skip | 屏 12 |
| 用量统计 / Coding Plan 额度 | secondary | 额度条上屏（屏 12），30 天报表二级页；配额横幅复用会话页顶部胶囊 | 屏 12 |
| 集成终端 | secondary | 只读后台输出（屏 07）；交互式多标签终端 skip | 屏 07 |
| 远程工作区（SSH/WSL） | secondary | 作为执行端可选项 + 远程任务状态查看；向导引导桌面 | 屏 03/11 |
| **知识中心 / Repo Wiki** | secondary | **不单设 Tab**：以屏 09 产物预览（markdown 只读）+ 文件树抽屉承载结构化文档浏览；入口在工作区文件抽屉顶部「文档」分区 | 屏 09 |
| 画板 | secondary | 首版不做（低频），预留图片附件通道 | — |
| 浏览器控制 | secondary | 仅状态展示（工具卡体系出现 browser 卡） | 屏 05 |
| 新手引导 / 反馈 | secondary | 职业选择/迁移引导保留首启一次性流程；反馈入口在屏 12 页脚 | 首启 + 屏 12 |
| 通知中心 / Live Activity | 参考⑨⑪补强 | **已补**：三类聚合 + 锁屏实时活动 | 屏 10 |
| Computer Use | skip | 仅桌面注入且 fail-closed | 不出现 |
| 终端 TUI | skip | 无终端场景 | 不出现 |
| 键盘快捷键 / 命令面板 | skip | 以搜索框 + 分层导航替代 | 不出现 |
| Treemapping | skip | 以 todo 进度 + 工具 chips 替代 | 不出现 |
| 桌面壳 / 系统功能 | skip | 桌面专属 | 不出现 |

---

## 7. 可用性硬指标核对表

| 指标 | 标准 | 落点 |
| --- | --- | --- |
| 触控目标 | 可交互元素 ≥44×44px | 审批授权行 52px、逐文件批准/拒绝 44px、追问/稍后 44px 行、工具卡头 44px、分段控件 44px、搜索框 44px、去审批 44px、终端/代码块复制 44px、输入区内图标 44px、chip-btn 44px、diff 文件头 ⋯ 菜单 44×44、屏 11 卡片按钮 44px、文字链接 padding 外扩 44px |
| 输入字号 | 可聚焦输入框 ≥16px | 会话输入栏、新建任务输入区、API Key 表单（HTML 内 `.c-field` 等） |
| 对比度 | 文字 ≥4.5:1（WCAG AA） | `--text-3` 提亮为 #8a8f9b 并禁止叠 opacity；Light 全表见 2.2；diff 行号用 `--text-3` 实色 |
| 安全区 | 顶 54 / 底 34px | 全部底部内容用 `--safe-bot`（屏 01 协议区已由硬编码 26px 修正） |
| 键盘态 | 输入区与主操作始终可见 | 屏 03 折叠策略（4.03）；屏 05 输入栏避让 |
| 手势冲突 | 同轴手势唯一 | 3.3 裁定表（diff 横滚 vs 左滑回退已消解） |
| Android 面包屑 | 系统返回之外保留页内路径 | 3.2：二/三级页标题下 12px 可点面包屑，根页不显示 |
| 模态退出 | 无死锁模态 | 审批 Sheet「稍后处理」+ Android 返回语义（4.06） |
| 测试选择器 | E2E 只依赖 `data-testid`，禁依赖类名/文案/序号 | 5.10 三段式规约；设计稿已内联 123 处关键选择器 |
| 加载态 | 首屏居中 spinner / 增量行尾 spinner / diff 文件头 spinner，一律不用骨架屏 | 5.9 统一口径；加载失败就地转失败/离线态 |

---

## 8. 已知取舍与开放问题

1. **仓库引用可核验性**：`DESIGN.md`、`AGENTS.md`、`v4/*.tsx` 等行级引用转引自立项调研材料；本设计工作区（`/Users/mac/projects/zcode_mobile`）不含 zcode 仓库源码，本会话无法复验。**接入前须在真实仓库根目录复核**：DESIGN.md:15（iOS 输入防缩放 token 值）、DESIGN.md:118-119（diff 新增/删除色 token 值）、AGENTS.md:62-63（web-remote 链路）、v4/SessionPane.tsx:2079 与 ConversationComposer.tsx:827 与 TaskList.tsx:365（isMobileViewport 预留点）。`#3ddc84 / #ff7a7a` 等 diff 色值为推导实现值，以仓库 token 为准替换。
2. **TRAE 色值置信度**：`#0a0b0d / #32f08c / #f5f9fe / #a6aab5` 取自调研材料标注的第三方 DESIGN.md 聚合资料（非官方文档）；圆角 8/12/16 与 16px 卡片内边距为材料给出的设计建议值。
3. **Light 主题覆盖范围**：屏 14 给出看板与 Diff 两张最高频屏的实渲样张；其余屏由 2.2 令牌表驱动同构生成，未逐屏出 Light 稿。深浅两套的按钮前景策略不同（橙按钮深色档深字 / Light 档白字），实现时按令牌取值。**验收清单须为非样张屏保留 Light 抽检项**：按 2.2 的「最差复合底 4.5:1」回归判定逐屏抽查可读文字（优先屏 01/05/06 三张含复合底的屏）。
4. **执行端「云端沙盒」**：官方确认链路是复用桌面 Host（web-remote / IM Bot）；云端沙盒为产品化预留占位，需后端能力支持。
5. **横屏 / iPad**：仅覆盖竖屏 390×844；大 diff 的横屏/桌面接力以「桌面端继续」承载，未定义平板断点。
6. **知识中心**：移动端不设独立 Tab，以屏 09 只读文档浏览 + 文件树抽屉承载（6.10）；若后续产品要求独立知识中心，需新增 Tab 或并入「我的」。
7. **动态指标**：todo 进度、上下文百分比等演示数据在各屏间已对齐（同一任务统一为 todo 3/5 · 60%），接入时以会话实时数据为准。

---

## 9. 登录与连接（v2.4 新增章节）

> **章节依据**：2026-10-02 完成的 zcode 仓库接口调研（只读源码调研，未运行构建/测试；协议形状以 zod schema 与注释为准）。文中行级引用（`http.ts:*`、`runner.mjs:*`、`server-remote.ts:*`、`transport.ts:*`、`webZaiOAuthConfig.ts:*`、`zaiWebOAuthProvider.ts:*` 等）均**转引自该调研**，实现前须在真实仓库根目录复核（同第 8 节口径）。
> **配套高保真专项稿**：`design/login-design.html`（单文件自包含，板 **O1–O3（账号 OAuth）+ L1–L5（桌面配对）**，含画布头部双路径流程模型图）。本章只增不改：第 2 章令牌、第 5 章组件与屏 01–14 规范全部保持 v2.3 口径；组件一律复用既有类（`.card/.btn/.pill/.grow/.glist/.spin/.tool-chip` 等），本章新增组件类均在本稿 style 块自包含定义（`.step-row` 步骤行、`.todo-mini` 迷你进度、`.banner-clip` 剪贴板横幅、`.f-field/.f-label/.f-caption` 表单字段、`.field-err/.f-err/.f-ok` 校验态、`.card-err/.err-code` 错误卡、`.row-selected` 选中行、`.sample-card` 校验样例、`.dot` 中性探测点、`.kb*` 键盘示意、`.oauth-params` OAuth 参数卡、`.oauth-sheet/.sheet-grab/.wv-bar/.wv-progress/.wv-page/.wv-mock-note/.perm-row` 应用内授权 Sheet（页内为远端授权页示意）、`.scrim` 模态遮罩（设备级黑遮罩）、`.flow-step-hl` 流程强调描边、`.avatar` 头像位、`.adv-head` 高级选项头行），全部由既有令牌驱动（灰阶示意与设备级黑遮罩除外，其口径同手机外壳）。

### 9.0 与既有章节的关系（增量修正，不改原文）

- **连接对端修正**：iOS「连接桌面端」的真实对端是桌面机上运行的 **zcode-server 进程**（`zcode --web --host 0.0.0.0 [--port 3030]`，与桌面 App 共享 `~/.zcode` 数据目录但各自拉起 agent），**不是桌面 Electron App 本身**（桌面进程无面向客户端的 HTTP/WS server）。屏 01 配对卡与屏 11「扫码配对新设备」的桌面侧入口说明以此为准。
- **二维码现状**：仓库**无二维码渲染与 mDNS/Bonjour 发现组件**，`zcode --web` 仅把带令牌的 URL（`http://<LAN-IP>:<port>/?token=<token>`，非 loopback 时令牌 `randomBytes(24).toString("base64url")` 自动生成）逐个打印到终端。因此扫码对象为「桌面端出示的连接二维码」（需仓库外包装），**MVP 兜底 = 通用剪贴板一键填充 + 手动输入**。
- **OAuth / API Key 定位澄清**：产品级 OAuth（chat.z.ai，client_id=client_P8X5CMWmlaRO9gyO-KSqtg）服务于**云端分享 / Coding Plan 账户层**，不属于局域网连桌面的鉴权；API Key 为模型凭据。登录主页（板 O1，对应屏 01 的账户登录路径升级版）以「使用 Z.ai 账号登录」为主按钮、「连接桌面端」为次入口；登录后账户管理收进 L4「账户」行 → L4-B 配置区（头像 / 昵称 / 退出登录），屏 01 原文保留为账户登录路径的历史口径。
- **Bot 绑定码不可复用**：6 位 HEX / 30s TTL 的 `bind <code>` 是「聊天账号 ↔ 工作区」绑定，**禁止用作 WS 接入凭据**，登录/连接页不出现。
- **屏 12 增补分组**：「服务器与账户」插于用户卡后第一组（板 L4 + L4-B 配置区）；屏 13 状态样张之外新增连接域状态（板 L2/L3）与登录域状态（板 O2/O3）。

### 9.1 登录路径总览（双路径模型 · 登录主页 O1）

```
启动（未登录拦截）
 └─ O1 登录主页（屏 01 账户登录路径的完整版）
     ├─ 路径 A · 账号 OAuth（主按钮「使用 Z.ai 账号登录」）
     │    O2-A 应用内授权 Sheet（内嵌浏览器视图）→ 回调拦截（O2-B）→ O3-A 成功 / O3-B 失败·取消重试
     │    └─ 高级选项：BigModel 智能体平台登录（{origin}/login?appId=zcode）
     ├─ 路径 B · 桌面配对（次入口「连接桌面端」）→ L1 连接页 → L2 连接中 → L3 失败重试 → L5 详情
     └─ 两路径互相独立：登录为账户层（OAuth tokenSet），配对为连接层（服务器访问令牌）
          · 登录成功后仍需配对才能遥控桌面（O3-A「开始使用」→ L1 或看板）
          · 只配对不登录亦可使用连接功能（L1 直接进入）
```

- **登录主页职责**（板 O1）：品牌区（84px Z 标，同屏 01）→ 主按钮「使用 Z.ai 账号登录」48px → 次按钮「连接桌面端」48px 描边 → 高级选项折叠卡（BigModel 登录行 44px）→ 协议脚注（距底 `--safe-bot + 12px`，协议/隐私走内嵌 WebView）。
- **路径选择逻辑**：主按钮 = ZAI OAuth（默认 Provider，覆盖多数用户）；次入口 = 无 Z.ai 账号 / 仅局域网使用的用户；BigModel 折叠在高级选项（入口存在但不与主路径争夺视觉权重）。
- **两套凭据互不依赖**：OAuth `tokenSet` 与服务器访问令牌**各自独立存 Keychain**（9.5）；退出登录只清账户层，不动已保存的服务器（L4-B）。

### 9.2 账号 OAuth 路径（六步 · 对应板 O2/O3）

> **机制依据（转引，实现前须在真实仓库复核）**：`packages/web/src/auth/zaiWebOAuthProvider.ts` 与 `packages/web/src/auth/webZaiOAuthConfig.ts` 的逐行核实结论（2026-10-02 接口调研）。

| 步骤 | 事实/接口（转引） | UI 落点 | 失败处理 |
| --- | --- | --- | --- |
| ① 发起授权 | ZAI 默认入口 `{origin}/api/oauth/authorize?redirect_uri=…&response_type=code&client_id=…&state=…`，origin 缺省 `https://chat.z.ai`（webZaiOAuthConfig.ts:26-29、54-56；源码注释：ZAI 走 `/api/oauth` 前缀，`/auth/oauth` 是旧入口）；**BigModel 高级入口** `{origin}/login?redirect=…&appId=…&state=…`，appId 缺省 `"zcode"`（webZaiOAuthConfig.ts:39-47、59-61，buildAuthorizeUrl 分支） | O1 主按钮 / 高级选项行 → **O2-A 授权 Sheet**（发起参数卡在稿内标注区：授权端点与参数逐行 mono 可核，client_id 掩码；**参数卡同时呈现 BigModel 分支**——`?redirect=…&appId=zcode&state=…`，参数名 `redirect` ≠ `redirect_uri`，交换时 `provider=bigmodel`、响应取 `data.bigmodel.access_token`，防止照抄 ZAI 卡拼错参数） | redirect_uri / client_id 为移动端绑定假设（9.7 gaps） |
| ② 应用内授权 Sheet | **用户已决策：授权在应用内完成，不跳系统浏览器**——点击主按钮后授权 Sheet spring 升起（2.7 背景压暗 + 缩放 0.96），授权页由 `chat.z.ai`（或 BigModel）在**内嵌浏览器视图**中渲染。**App 可控边界**：Sheet 容器与 chrome（✕ 关闭 40×40 视觉 / 命中区 ≥44、域名行、加载进度条 2.5px）+ redirect 拦截；**授权同意页本身由授权服务器渲染、外观与按钮 App 不可控**——载体建议 `ASWebAuthenticationSession` 优先（chrome 由系统提供、页面即 chat.z.ai 真实网页、与 Safari 共享会话 Cookie、**无外观控制 API**），WKWebView 为备选（仅做容器 + redirect 拦截）；**两种载体均禁止注入自绘同意页**（伪造授权页 = 钓鱼反模式）；授权页深浅**跟随系统而非 App 设置**（屏 12 强制 Zai Dark/Light 时此页不随，待真机验证）；`state` 为一次性随机值，发起时生成并本地暂存 | **O2-A 授权 Sheet**（`.oauth-sheet` + `.wv-bar/.wv-progress/.wv-page`）：✕/域名/刷新工具行 → 2.5px 加载进度条 → **远端授权页示意**（`.wv-mock-note` 虚线胶囊标注「真实授权页由 chat.z.ai 提供，外观与按钮不可控」+ 站点 Z 标 + scope 列表 `.perm-row` + 「授权并继续」48px + 「取消」44px——均为信息层级示意，非 App 自绘 UI）；**O2-A-M BigModel 变体**（同一 Sheet 容器，域名行 `{origin}/login · BigModel 智能体平台`）；发起参数卡（`.oauth-params`，ZAI + BigModel 双分支逐行 mono）在稿内标注区 | 用户在 Sheet 内点取消 / 下拉抓手关闭 / ✕ 关闭 → O3-B 第一态（用户取消，中性结果非故障；ASWebAuthenticationSession 载体下页内「取消」即系统 chrome Cancel） |
| ③ 回调拦截 | 「授权并继续」后授权服务器 302 至 `redirect_uri`；**redirect 导航在内嵌视图内被拦截**（不真正加载、不离开 App），回调参数 `code`（或 `authCode`，双兼容）、`state`（**必填，缺失即报错**）、`error`（zaiWebOAuthProvider.ts parseCallbackParams） | O2-B 回调参数卡（`zcode://oauth/callback?code=…&state=…`） | `error` 参数 / state 缺失或不匹配 → O3-B（防 CSRF，不接受静默降级） |
| ④ 交换令牌 | `POST /api/v1/oauth/token`，JSON body `{provider, code, redirect_uri, state}`；响应 `{code:0, msg, data:{token(zcode JWT), zai:{access_token}, bigmodel:{access_token}, expires_in, user}}`（zaiWebOAuthProvider.ts exchangeToken/normalizeTokenResponse；tokenUrl 见 webZaiOAuthConfig.ts:56） | O2-B 四步进度之第 3 步 + `oauth.log` 终端条（复用 `.step-row`/`--bg-term`，code/token 一律 `***`） | 超时或响应 `code≠0` → O3-B 第四态 |
| ⑤ 凭据落地 | `tokenSet={accessToken(Provider access_token), zcodeJwtToken, expiresAt}`；`userInfo={id, username, displayName, avatarUrl?}`（toUserInfo 兼容 `user.id/user_id`、`name/email`、`avatar/avatarUrl` 与 `data:image` base64）；移动端存 **Keychain**（web 端为 localStorage/sessionStorage，browserOAuthCredentialRepo.ts:40-76，转引） | O2-B 第 4 步 → **O3-A 成功态**：displayName 主标题 + `@username/id` 次级 + avatarUrl 头像位（无图时渐变+首字母兜底）+ tokenSet 摘要（accessToken 掩码 / zcodeJwtToken / expiresAt） | Keychain 写入失败 → 就地错误态（5.9 模式） |
| ⑥ 携带凭据 | 后续请求 `Authorization: Bearer {accessToken}`（conversationSharePreviewClient.ts:145，转引） | O3-A 凭据摘要注（mono 行） | 后续请求 401 → 引导重新登录（O3-B） |

- **O2-B 四步进度口径**：接收回调 → 校验 state → 交换令牌 → 凭据写入 Keychain；完成=绿勾+耗时、进行中=蓝 spinner、未到=灰虚圈；计数含进行中（2 完成 + 1 进行中 = 3/4、条宽 75%），与 L2 五步、`.todo-mini` 同构；遵循 **5.9 加载态口径（无骨架屏）**。**无回调兜底**：授权页长时间无进展（加载失败/卡住，非用户主动取消——主动 ✕/页内取消直接转 O3-B 中性结果，不出引导）超过 **60s** 出现「授权页未响应？」引导条（`o2-card-nocallback`）+ 44px「关闭并重新发起」（`o2-act-restart`，重置一次性 state 重走 O1→O2-A）；「取消登录」始终可用。
- **失败/取消态（O3-B）**：错误卡（`.card-err` 红描边 + 一句结论 + mono `error=…`/`state=…`）+ **唯一主 CTA「重新登录」**（重试时保留 client_id/redirect_uri 静态参数、重置一次性 state）+ 次级动作「改用 BigModel 登录」/「跳过 · 连接桌面端」（各 44px，主路径不死路）+ **五态错误对照**（用户取消 / `error` 参数 / state 校验失败 / 交换失败 / **Keychain 写入失败**——与 9.2 ⑤ 失败处理一一对应）。用户取消为**中性结果非故障**——文案不指责、状态可恢复。

### 9.3 桌面配对路径 · 连接模型（六步 · 对应 L2 五步进度）

| 步骤 | 事实/接口（转引） | UI 落点 | 失败处理 |
| --- | --- | --- | --- |
| ① 桌面启动 | `zcode --web --host 0.0.0.0 [--port 3030]`；PORT 默认 3030；令牌自动生成或 `--token` 指定、`--no-token` 关闭（runner.mjs:105-107） | L1 帮助页命令卡（mono + 44px 复制） | — |
| ② 凭据传递 | 终端打印 `http://<LAN-IP>:<port>/?token=<token>`（runner.mjs:121-147）；`--no-token` 可关闭鉴权 | L1-A 扫码主 CTA → **全屏相机取景器（板 L1-S / L1-S-D，三态：正常取景 + 识别失败内联红字 + 相机权限拒绝引导）** / 剪贴板横幅一键填充 / L1-K 手动表单（支持粘贴完整链接自动拆解：地址取 `scheme://host:port`，`?token=` 段剥离入令牌栏；scheme ∈ http/https/ws/wss） | URL 格式校验失败：取景器内联红字（`l1-s-err-inline`，不弹系统弹窗）；相机拒绝：`l1-s-card-denied` 引导（去系统设置开启 + 改用手动输入连接，主 CTA 不死路）；表单错误：`.field-err` + `.f-err` 内联提示 |
| ③ 发现与鉴权 | `GET /api/server-info` → ServerRemoteInfo{serverId, name?, version, protocolVersion:1, authRequired, workspaces[{path,label,workspaceIdentity}], capabilities}（server-remote.ts:19-38）；保护路径 `/ws`、`/ws/*`、`/api/*`，首次带 `?token=` 即种会话 cookie `zcode_lite_token`（HttpOnly/SameSite=Lax），**原生客户端每次请求 query 携带 token**（http.ts:169-185,227-241,308-318） | L2 步骤 1–2；L5 字段卡连接确认 | 401 → L3「令牌不匹配」（重扫/更新令牌）；超时 3s 自动重试 1 次。**authRequired=false（`--no-token`）**：L2 第 2 步显示「免鉴权 · authRequired=false」直接打勾，不出现「校验通过」；L1-K 令牌留空合法 |
| ④ 建立 WS | `ws://<host>:<port>/ws?token=…`；服务端固定 clientMode=web-remote-replayable、role=terminal-client，旧提权头已废弃；provider-provisioning-target 频道被拒（http.ts:323-332,108-119；channels.ts:501） | L2 步骤 3（mono 回显 `web-remote-replayable`） | 升级失败/超时 5s → L3「无法连接」 |
| ⑤ v4 握手 | 服务端先推 `helloConversationV4`（HelloMessage{protocolVersion:3, connectionId, clientMode, deliveryProfile:"replayable", serverTime, capabilities, auth}）；客户端回 `initializeConversationV4`（ClientHello{protocolVersion:3, clientId, **clientKind:"mobileApp"**, appVersion, capabilities?}）；**单向 capabilities 规则：Host hello 未宣告的键，clientHello 不得携带，否则 strict 解析整条失败**（transport.ts:53-86；agentV4ConnectionHandshake.ts:42-67）。**双源口径**：`server-info.capabilities`（发现阶段）与 `HelloMessage.capabilities`（握手阶段）是两个对象——L5 chips 仅展示前者；`clientHello.capabilities` 以**后者已宣告键为上限取子集**填报，勿用 server-info 键直接照抄 | L2 步骤 4；L5 能力 chips（`.pill tag`，卡内注明双源） | 版本≠3 → L3「协议版本不匹配」（并列两端版本，提示升级），不静默降级 |
| ⑥ 工作区就绪 | 默认取 **workspaces[0]** 作为初始 workspaceId/path（与桌面 Web 端 main.tsx:370-388 同款逻辑，转引）；随后全部业务走 channel RPC（无 REST 业务 API） | L2 步骤 5；L5 工作区单选组 | workspaces 为空 → L3 引导在桌面端检查 `--workspace` |

**权限面**：web-remote-replayable 为降级身份——iOS 功能面按「**单 workspace 的会话/任务/文件**」设计；不支持页面内再开远程工作区（main.tsx:206-214 同款行为，转引）。

### 9.4 板 O1–O3 与 L1–L5 逐屏说明（`design/login-design.html`）

**通用口径**：连接域页面左右边距一律 **16px**（2.5 `sp-4`，不另设 20/24px 变体；O1 登录主页为品牌页，沿用屏 01 的 24px 边距）；新增组件（`.step-row/.banner-clip/.f-field/.kb/.todo-mini/.card-err/.row-selected/.field-err/.f-err/.f-ok/.oauth-params/.sysbar/.avatar/.adv-head`）全部在本稿 style 块自包含定义（单文件可独立打开），色值一律走令牌。

- **O1 登录主页**（未登录拦截栈）：双路径并列——主按钮「使用 Z.ai 账号登录」（`o1-btn-oauth`，48px 绿）/ 次按钮「连接桌面端」（`o1-btn-connect`，48px 描边）/ 高级选项折叠卡（`o1-card-adv`，BigModel 行 `o1-row-bigmodel` 44px 热区）；协议脚注同屏 01 口径。标注块固化 OAuth 双入口机制与**绑定假设**（redirect_uri 回调 scheme、client_id 环境变量来源，见 9.7）。
- **O2-A 应用内授权 Sheet**（用户已决策：应用内完成，不跳系统浏览器）：背景 = O1 登录页压暗 + 缩放 0.96（Sheet 升起转场，2.7）；Sheet（`.oauth-sheet`，顶角 24px、`--sh-sheet` 阴影、`--bg-elevated` 底）内为**内嵌浏览器视图**——抓手条（`.sheet-grab`）+ 工具行（`.wv-bar`：✕ 关闭 40×40 视觉 / 命中区外扩 ≥44 `o2-act-close` + 域名行 `.wv-domain`（盾标 + `chat.z.ai · Z.ai 账号授权`）+ 刷新 40px）+ **加载进度条**（`.wv-progress` 2.5px，随页面加载推进、完成后淡出，`o2-wv-progress`）+ **远端授权页示意**（`.wv-page`：顶部 `.wv-mock-note` 虚线胶囊「示意 · 真实授权页由 chat.z.ai 提供，外观与按钮不可控」+ 站点 Z 标 56px + scope 列表两行 `.perm-row`（读取账号信息 / Coding Plan 额度）+「授权并继续」48px `o2-act-approve` +「取消」44px `o2-act-cancel`——**示意仅表达信息层级，真实页面由授权服务器渲染、App 不可控**；视觉/E2E 断言只针对 Sheet 容器与 `.wv-bar/.wv-progress`）。发起请求参数卡（`.oauth-params` `o2-params-authorize`，ZAI 默认分支 + BigModel 高级分支逐行 mono，client_id 掩码）在稿内标注区（`.sample-card` 语义，机制可核性素材）。**关闭手势三等价**：✕ 点击 / Sheet 下拉抓手（本 Sheet 允许下拉关闭，不同于审批 Sheet 3.3 裁定）/ 页内「取消」——均按用户取消处理 → O3-B。
- **O2-A-M BigModel 高级入口 · Sheet 变体**（`o2-sheet-bigmodel`）：同一 Sheet 容器与 chrome（✕/进度条/关闭手势复用主帧选择器），仅域名行换 `{origin}/login · BigModel 智能体平台`、示意页换 B 标 +「BigModel 智能体平台 · 登录授权」+ 单行 scope（BigModel 模型调用凭据 · appId=zcode）——对应参数名 `redirect` ≠ `redirect_uri`、交换 `provider=bigmodel`、响应取 `data.bigmodel.access_token`。
- **O2-B 回调拦截与授权中**：回调头卡（「已拦截授权回调」+ redirect 拦截说明 + 授权中胶囊）+ `.oauth-params` 回调卡（code 掩码 / state 与发起值一致 ✓）+ **四步进度卡**（`o2-step-callback/state/exchange/keychain`，结构同 L2）+ `oauth.log` 终端条（`o2-log-term`，`--bg-term` + 12px mono + `[ok]`/`[..]` 标签 + `***` 掩码 + 闪烁光标）+ 无回调兜底引导条（`o2-card-nocallback`）+「取消登录」48px（清除暂存 state、中断交换）。
- **O3-A 登录成功**：绿勾成功标识 + 用户卡（`.avatar` 56px avatarUrl 位 + displayName 17px + `@username` + ZAI `.pill done` 渠道徽章 + Coding Plan `.pill tag` 能力徽章）+ **tokenSet 摘要组**（accessToken 掩码行 / zcodeJwtToken「已签发」行 / expiresAt 由 `expires_in` 换算行，各 44px 信息行）+ Bearer 说明注 +「开始使用」48px（→ L1 未配对 / 看板已配对）。
- **O3-B 失败/取消与重试**：主样卡为**用户取消态**（44px 红图标位 + 「你取消了本次授权」+ 中性 mono 徽标 `未产生 code · 未产生 error` `o3-err-code` + state 值——**取消态不出现 error 参数**，`error=access_denied` 属「授权服务器返回 error」分支由对照第 2 行承载）+ 唯一主 CTA「重新登录」48px + 次级「改用 BigModel 登录」/「跳过 · 连接桌面端」各 44px + **五态错误对照**（`o3-row-cancel/error-param/state/exchange/keychain`，glist 信息行）+ 底部脚注（重试重置 state / 登录非使用前提）。

- **L1 默认连接页**（独立拦截栈；四帧 + 帮助页共五帧：A 默认态 / K 键盘态 / **S 扫码取景器**+**S-D 权限拒绝** / **H 内嵌帮助页**）：回连优先——「最近连接」卡（名称 + mono 地址 + 中性探测点 + 44px「连接」）置顶，首启隐藏；主 CTA「扫码连接桌面端」48px、次 CTA「手动输入地址连接」48px；帮助行（mono `$ zcode --web --host 0.0.0.0`）→ 内嵌说明页；底部安全脚注（Keychain / 可信局域网 / 连接安全说明，距底 `--safe-bot + 12px`）。**剪贴板横幅**：检测到 `http://…/?token=…` 形态链接即出现，「一键填充」44px 解析后直接转 L2。**扫码取景器（L1-S，全屏模态非 Push）**：四角括号取景框 + 扫描线 + 目标格式 mono 提示（`http(s)://host:port/?token=…`，桌面端二维码属仓库外包装 gaps）；左上 ✕ 44px；**识别失败态**=取景框下内联红字 `l1-s-err-inline`（.f-err 令牌，不弹系统弹窗）；**相机权限拒绝态（L1-S-D）**=中央引导卡 `l1-s-card-denied`（「去系统设置开启」44px 主按钮 + 「改用手动输入连接」44px 逃生口，`l1-s-btn-manual` 两态共用，主 CTA 不死路）。**系统权限三闸（真机第一闸，稿内专设标注块）**：①本地网络（iOS 14+）首次请求必弹，用途文案「用于查找并连接同一局域网内的桌面端 Agent 服务（ZCode 社区版桌面端）」，拒绝后请求静默失败、与超时不可区分 → L3 超时对照固定引导「设置 → 隐私与安全性 → 本地网络」；②相机（扫码）拒绝后取景器转引导态（L1-S-D 视觉帧固化）；③剪贴板（iOS 16+）受系统「允许粘贴」约束，拒绝后横幅不出现，降级 = L1-K QuickType 建议 / 长按粘贴。
- **L1-H 连接帮助 · 内嵌说明页**（Push，自 L1 帮助行进入，右滑返回；`l1-h-*`）：mono 命令卡（`$ zcode --web --host 0.0.0.0 [--port 3030]` + 终端回显 LAN URL，44px 复制热区 `l1-h-act-copy`）+ 三步说明行（①桌面机启动局域网服务（Core 版仅 loopback，须走 `--web` 分发）②终端打印带令牌链接③手机扫码/复制/手输）+ **二维码指引卡**（桌面端出示二维码为后续能力——仓库无二维码渲染，当前复制终端链接兜底）+ **本地网络权限预说明卡**（用途文案 + 拒绝后静默失败预警）+「知道了」48px（`l1-h-btn-done`）。
- **L1-K 手动连接 · 键盘态**：地址栏（mono 16px、URL 键盘）支持**粘贴完整链接自动拆解**——`scheme://host:port` 填地址栏、`?token=` 段自动剥离填令牌栏（`f-ok` 绿提示）；scheme ∈ **http/https/ws/wss**（https/wss 走反向代理），仅输 `host:port` 缺省补 `http://`，不固定前缀。令牌栏（16px 安全输入、眼睛 44px 切明文、掩码展示末 4 位）pill 标注**「安全输入 · 可选」**：`--no-token` 部署（authRequired=false）留空合法。「连接」主按钮**常驻键盘上方**；键盘 250ms 与系统同轨上移（2.7）。**表单校验态**：提交前内联提示——错误字段套 `.field-err` 红描边 + `.f-err` 红字（必填 / 无法识别的地址（缺 scheme/端口）两种文案；令牌在 authRequired=true 时必填），稿内画有校验态样例卡；错误不打断输入。
- **L2 连接中**：目标服务器头（52px 图标 +「连接中」胶囊）——**头部时序**：首次连接 name? 未知，标题以 mono `host:port` 占位（第 1 步返回后升级为名称），回连显示已存名称 + **五步进度卡**（`GET /api/server-info` → 校验令牌 → WS → v4 握手 → workspaces[0]；完成=绿勾+耗时、进行中=蓝 spinner、未到=灰虚圈；**进度口径：计数含进行中，2 完成+1 进行中=3/5、条宽 60%**，`.todo-mini` 同构）+ **connect.log 终端条**（`--bg-term`、12px mono、[ok] 绿 / [..] `--code-lab`、令牌一律 `***`、光标闪烁）；authRequired=false 时第 2 步显示「免鉴权」直接打勾；右上 ✕ 与底部「取消连接」均 44px，立即中断不留半开连接。超时阈值 3s/5s/5s，自动重试 1 次（指数退避），仍失败转 L3（第 5 步 workspaces 空 → L3 第四态）；加载态遵循 **5.9 口径（无骨架屏）**。
- **L2-N 免鉴权变体**（`--no-token` 部署 · authRequired=false）：与 L2 同构五步，第 2 步行名「**免鉴权**」+ 副题 `--no-token · authRequired=false · 不校验令牌`，meta 列「免鉴权」——**不出现「校验通过」字样**；connect.log 第二行为 `authRequired=false · --no-token（免鉴权）`，WS 升级行标注「无 token 段」；`l2-n-card-steps / l2-n-step-auth`（免鉴权行独立选择器供 E2E 断言），取消按钮复用 `l2-act-cancel`；对应 L1-K 令牌栏留空合法。
- **L3 连接失败/错误与重试**：按 **5.9 失败态统一模式**——错误卡（44px 红图标位 + 一句结论 + `.err-code` mono 错误行 `HTTP 401 · /api/server-info` + token 掩码；描边走 `.card-err`→`--red-line` 令牌，Light 自动映射）+ **唯一主 CTA**（随错误类型切换：401→「重新扫码更新令牌」/ 超时→「原配置重试」/ 协议不符→升级指引）+ 次级按钮（原配置重试 / 手动更新令牌，44px）+ **常见错误对照四行**（401 令牌轮换 / 超时自查三件事：同一局域网、`zcode --web` 存活、**本地网络权限已允许**（被拒时请求静默失败）/ 协议 remote v1·v4 wire v3 并列两端版本 / **工作区列表为空 → 桌面端检查 `--workspace` / `ZCODE_SERVER_WORKSPACE`**）+「删除此服务器」红描边 44px（**二次确认**，清除 Keychain）。
- **L4 我的 · 服务器与账户分组**（Tab 4 根，屏 12 增量）：新分组插于用户卡后第一组（连接是根依赖），4 行 48px——服务器（副标题=名称+mono 地址+中性绿点在线，值「已连接 N」→ L5）/ 访问令牌（mono 掩码 + Keychain，值「更新」）/ 账户（Coding Plan，值「已登录」→ L4-B）/ 添加服务器（扫码/剪贴板/手动）；其余分组顺延，低频组滚动裁切（同屏 12 规则）；Tab 栏 95px 照常。
- **L4-B 服务器与账户配置区**（Push，自「服务器/账户」行进入）：**已登录账号卡**（avatarUrl 头像 48px + displayName + `@username` + ZAI 徽章 + 剩余额度 + **退出登录**红描边 44px + **二次确认**——仅清除账户层 tokenSet，不动已保存服务器与连接令牌；「刷新额度」44px；**账户三形态**：已登录主卡 + 未登录样例 `l4-b-card-login`（只配对不登录）+ **已过期样例 `l4-b-card-expired`**——`expiresAt` 已过或后续请求 401 时橙胶囊「登录已过期」+ mono `expiresAt=…` + 主按钮「重新登录」，仅账户层失效、已保存服务器不受影响，重登走 O1 重置一次性 state，**不做静默续期**（9.5 首发口径））+ **服务地址与令牌编辑组**（服务地址行 → L1-K 地址栏聚焦 / 访问令牌行 → 更新重扫，各 48px 整行可点）+ **连接测试卡**（`GET /api/server-info` 1.5s 超时：结果行=可达性+延迟+serverId+版本+authRequired+protocolVersion，「重新测试」44px accent 文字钮；**测试仅探测，不建立 WS 会话**）+ 底部脚注（退出登录与删除服务器的职责边界）。
- **L5 服务器详情**（Push L2，右滑返回；Android 面包屑 `我的 › 服务器与账户 › <name>`）：**连接确认职责**——server-info 只读回显卡（地址/serverId/版本/协议/鉴权）+ **工作区单选**（workspaces 顺序渲染，默认 `[0]`；选中行走 `.row-selected` 强调绿描边，深浅两档映射）+ 能力 chips（`.pill tag`；**双源口径**：chips=server-info.capabilities 仅展示，`clientHello.capabilities` 按握手 `HelloMessage.capabilities` 宣告键取子集，两源独立）+ 令牌与安全组（更新令牌 / 传输安全：`ws://` 无加密，可信局域网或反代 wss）+ 删除（红行，二次确认）+「立即连接」48px。

### 9.5 令牌、账户与安全策略

- **两层凭据模型**：账户层（OAuth `tokenSet`）与连接层（服务器访问令牌）**各自独立生成、独立存 Keychain、独立失效**——退出登录只清 tokenSet（L4-B），删除服务器只清连接令牌（L5）；互不牵连。
- **OAuth 凭据（tokenSet）**：accessToken（Provider access_token）/ zcodeJwtToken / expiresAt 三元组 + userInfo 仅存 Keychain（对应 web 端 localStorage/sessionStorage，browserOAuthCredentialRepo.ts:40-76，转引）；界面一律掩码（末 4 位），code/token/jwt 不进日志、不进错误回显（O2-B `oauth.log` 与 O3-B 错误卡均 `***` 处理）。**expiresAt 过期后的续期行为调研材料未定义（材料未提及 refresh token）**——首发口径：过期即要求重新登录（O1 主按钮直达），不做静默续期；**视觉落点 = L4-B 已过期样例**（`l4-b-card-expired`：橙胶囊「登录已过期」+ mono `expiresAt=…` + 主按钮「重新登录」，后续请求 401 同入口引导）。
- **state 防伪**：一次性、发起时生成并本地暂存、回调严格比对；**必填，缺失即报错**，不匹配不接受降级（O2-B/O3-B 固化）。
- **令牌 = 服务器密码**：仅存 Keychain；界面一律掩码展示（末 4 位），不进日志/截图/错误回显。令牌**无过期、无撤销接口，随桌面进程存活**（runner.mjs:105-107）→ 桌面重启后 401 属预期，**恢复路径 = 重新扫码/粘贴新链接**，无需删除服务器。
- **本地网络权限（连接前置闸）**：iOS 14+ 对局域网地址的请求触发系统授权，App 内用途文案固定为「用于查找并连接同一局域网内的桌面端 Agent 服务（ZCode 社区版桌面端）」，并在 L1 帮助页预说明；**拒绝后所有请求静默失败、与超时不可区分**——故全部超时类错误引导必须包含「设置 → 隐私与安全性 → 本地网络」检查项（L3 超时对照行已固化），禁止只给局域网/进程排查建议。
- **删除服务器** = 清除 Keychain 令牌 + 最近连接记录，二次确认后执行。
- **传输安全**：仓库无 TLS（`ws://` 明文）；连接页与详情页固定提示「仅在可信局域网使用，wss:// 需自备反向代理」；不做证书校验 UI。
- **在线点语义**：无 mDNS，不做常驻在线态——「最近连接/设置行」的圆点为**进入页面后台探测 server-info（1.5s 超时）**的结果（绿=可达 / 灰=不可达 / 半透明灰=未探测），附「上次连接」时间；遵循 **5.1 中性绿点解耦规则**，不复用任务状态胶囊。

### 9.6 组件与可用性硬指标补充

- **触控**：主按钮 48px；一键填充/连接/重试/更新令牌/眼睛/帮助行/删除/取消/关闭并重新发起/重新登录/换 Provider/跳过连接/退出登录/刷新额度/重新测试等 44px；O2-A 授权 Sheet 内 ✕/刷新按钮视觉 40×40、命中区外扩 ≥44px（「授权并继续」/「取消」为远端授权页元素示意，非 App 触控责任）；连接与登录步骤行 ≥44px（信息型不可点）；设置行 48px 整行可点（5.7）；O1 高级选项头行与 BigModel 行热区 ≥44px；L1-H 复制热区 44px。
- **边距**：连接域页面左右边距一律 **16px**（2.5 `sp-4`），不另设 20/24px 变体；组件内间距仍按 2.5 栅格。
- **输入**：可聚焦输入一律 16px 防缩放（2.3）；地址=URL 键盘（支持完整链接拆解）、令牌=安全输入（可选）；表单校验态=`.field-err` 红描边 + `.f-err` 内联红字，提交前即时提示、不打断输入；剪贴板解析只读展示、**不自动连接**（横幅需显式点击）。
- **键盘**：250ms 同轨避让（2.7）；表单对齐键盘顶可滚，主操作常驻键盘上方；键盘绘制为设备级灰阶（同手机外壳做法，不占 UI 令牌）。**O2-A 授权 Sheet 分两层**：Sheet 容器与 chrome（抓手/✕/域名行/进度条）为本 App 设计对象、走 UI 令牌；`.wv-page` 页内为**远端授权页示意**（`.wv-mock-note` 虚线标注），真实页面由授权服务器渲染、外观与按钮 App 不可控、深浅**跟随系统**（屏 12 强制主题时此页不随，待真机验证）。
- **加载/失败态**：5.9 口径延伸到连接域与 OAuth 流程——分步清单 + 单点 spinner（运行中蓝，1s 线性），无骨架屏；连接失败就地转 L3、登录失败就地转 O3-B，不叠加超时动画。
- **testid 扩展**：延用 **5.10 三段式**，板号用一位字母前缀 `o1-…o3-`（OAuth 路径）与 `l1-…l5-`（配对路径），本专项稿内联 **102** 处标注、**95** 个唯一选择器：O 板如 `o1-btn-oauth/connect`、`o1-card-adv`、`o1-row-bigmodel`、`o2-sheet`、`o2-act-close`、`o2-wv-progress`、`o2-act-approve`、`o2-act-cancel`、`o2-sheet-bigmodel`、`o2-params-authorize/callback`、`o2-step-callback/state/exchange/keychain`、`o2-log-term`、`o2-card-nocallback`、`o2-act-restart`、`o3-err-code`、`o3-btn-retry/switch-provider/skip`、`o3-row-cancel/error-param/state/exchange/keychain`；L 板如 `l1-btn-scan`、`l1-field-host`、`l1-act-paste-fill`、`l1-parse-ok`、`l1-h-act-copy`、`l1-h-btn-done`、`l2-step-discover/auth/ws/handshake/workspace`、`l2-n-card-steps`、`l2-n-step-auth`、`l2-log-term`、`l3-btn-rescan/retry/update-token/delete`、`l3-row-401/timeout/protocol/empty-ws`、`l4-row-server/token/account/add`、`l4-b-act-logout/refresh`、`l4-b-card-login/expired`、`l4-b-row-host/token`、`l4-b-test-result/act-test`、`l5-row-ws-0/1`、`l5-btn-connect`。**跨帧共用选择器**：同一语义控件在变体帧间共用一个选择器（`o2-act-close/wv-progress/act-approve/act-cancel` 于 O2-A 与 O2-A-M、`l1-s-btn-manual` 于 L1-S 与 L1-S-D、`l2-act-cancel` 于 L2 与 L2-N）——E2E 以「屏状态」限定作用域而非另起两套选择器（5.10 单源精神）；变体专属断言目标用独立选择器（`o2-sheet-bigmodel / l2-n-step-auth / l1-s-err-inline`）。**复用组件选择器单源**：Tab 栏与既有设置行（设备与远控等）为屏 12 复用组件，正式 testid 以主稿 `12-tab-*` / `12-row-*` 为准，专项稿**不另标**（避免同一组件两套正式选择器）。
- **Light 主题**：本专项稿未出 Light 实渲样张（同构令牌驱动，机制同 2.2/屏 14）；描边一律走令牌（`.card-err`→`--red-line`、`.row-selected` 深浅两档显式映射），禁内联 rgba/hex。**验收清单按 8.3 惯例保留 Light 抽检项**：优先抽 L1（剪贴板横幅/主按钮）、L3（错误卡红描边复合底）、O3-B（登录失败错误卡）三板。

### 9.7 已知取舍与开放问题（登录与连接）

1. **扫码的现实约束**：仓库无二维码渲染/mDNS——扫码为「有码可扫」时的主路径；MVP 兜底 = 通用剪贴板一键填充 + 手动输入。桌面侧「出示二维码」属仓库外包装（gaps 佐证），需后续在桌面端/CLI 补齐。
2. **relay 链路无桌面侧实现**：`/ws/host` + `POST /api/rpc-host-capability`（30s 一次性 capability）链路在仓库内存在，但桌面侧连接端与外部 relay 均不在开源仓库——MVP 不出任何 relay UI；协议同构（同一 channel RPC + v4），后续接入仅替换发现/鉴权层。
3. **协议未冻结**：`SERVER_REMOTE_PROTOCOL_VERSION=1`、`V4_WIRE_PROTOCOL_VERSION=3` 无兼容性承诺（core.ts 草稿声明）→ 客户端对 protocolVersion/capabilities **严格校验 + 失败提示升级**（L3 第三态），不做静默降级。
4. **桌面 Core 版端口限制**：`zcode-server-cli` Core 版仅允许 loopback（fail-closed，server-core/http.ts:126-132，转引）——局域网模式必须走 `zcode --web` 分发脚本；帮助页文案按此编写。
5. **本会话验证边界**：接口调研为只读（未运行构建/测试）；专项稿已通过 Chrome 无头渲染逐板截图核验布局（本设计会话内执行），未做真机/辅助功能/深浅切换回归。
6. **置顶/归档等会话写命令缺口**（gaps 遗留）不在连接域内，仍按第 8 节处理；本章不新增结论。
7. **移动端回调 redirect_uri 为绑定假设**：web 端用 webShareCallbackUrl（webZaiOAuthConfig.ts:62，转引）；移动端需自定义回调 scheme（稿内示例 `zcode://oauth/callback`），**scheme 注册名与是否改用 Universal Links 为绑定假设，需 iOS 实现前与部署方确认**；ZCode 仓库内无 iOS 原生实现可参照（clientKind `"mobileApp"/"mobileRemote"` 仅在 transport.ts:73 声明一次）。
8. **client_id 缺省值与可配置性为绑定假设**：web 端取自部署环境变量 `VITE_ZAI_OAUTH_CLIENT_ID`（转引），稿内 `client_P8X5CMW…` 为调研样例值；移动端的打包内置值、是否允许用户覆盖、以及 BigModel `appId` 的取值策略均未定义，需在接入时确认。
9. **令牌续期行为未定义**：调研材料未见 refresh token 机制，`expires_in` → `expiresAt` 之后的续期/重登策略为开放问题；本规范首发口径为「过期即重新登录」（9.5），如后续服务端提供 refresh 能力再修订。
10. **应用内授权的载体选型与注入边界**：用户已决策 OAuth **在应用内授权 Sheet 完成，不跳系统浏览器**（O2-A 固化）；实现载体建议 **`ASWebAuthenticationSession` 优先**——其呈现形态即系统级应用内 Sheet，自带加载进度/取消按钮、与 Safari 共享会话 Cookie（已登录用户免重复输密码），且 redirect 到自定义 scheme 时由框架回调交付（`callbackScheme` 需注册，与 redirect_uri 绑定假设 9.7.7 对齐）。**边界事实**：`ASWebAuthenticationSession` **无外观控制 API**——授权同意页是 chat.z.ai/BigModel 的真实网页，深浅由该页面与系统决定（App 强制 Zai Dark/Light 不生效，待真机验证），稿内 `.wv-page` 区域仅为该远端页面的**信息层级示意**；**两种载体（含 WKWebView 备选）均禁止注入自绘同意页或改写授权页内容**（伪造授权页 = 钓鱼反模式），WKWebView 备选仅做「容器 + redirect 拦截」，并自行承担 Cookie 隔离与外观不一致代价。无论哪种载体，「✕ / 下拉 / 页内取消 = 用户取消」语义一致（ASWebAuthenticationSession 下页内「取消」即系统 chrome Cancel）。

# 平台差异能力矩阵与封装（platform-differences）

> 解决的问题：安全区/IME/返回键/深链这类**平台机制差异**如果散落在各页面代码里，
> 同一件事每个页面写一遍、写法还不一样，之后没法审计。原则：**差异集中封装，
> 页面只调统一抽象**。

## 1. 能力矩阵（移植前逐项判定归属）

| 能力 | 典型差异 | 统一抽象建议 |
|---|---|---|
| 安全区（刘海/手势条） | web 用 env(safe-area-inset)，App 用系统 inset | 平台层提供 safeArea，页面不手算 |
| IME（键盘） | 键盘弹起遮挡/推动布局策略不同 | keyboardInsets + 遮挡回调 |
| 屏幕方向 | web 响应式自适应 vs App 锁向/自适应策略 | orientation 语义统一（跟随/锁定） |
| 返回 | 浏览器返回栈 vs 系统返回键/手势 | backDispatcher：可拦截返回语义（弹窗先关、表单确认） |
| 生命周期 | 页面隐藏/恢复事件模型不同 | lifecycle 事件统一映射 |
| 深链/URL scheme | web 路由即 URL vs App scheme + 路由表 | 统一路由表（page-index 为单一事实源） |
| 分享 | web share API vs 系统分享面板 | share(content) |
| 权限 | web 权限提示 vs 系统权限弹窗 | permission request 统一入口（含拒绝态 UI） |
| 存储 | localStorage/cookie vs 各端 KV/沙盒 | storage 抽象（键名与 web 对齐） |
| 字阶/动态字体 | web 固定字号 vs 系统字体缩放（小程序跟随微信内字体设置） | 字阶策略统一（跟随系统或锁定，二选一并全局一致） |
| 屏幕度量 | CSS px/vw vs dp/pt/rpx | 全部走 `ui-parity.md` 的换算，禁止页面内私算 |
| 网络请求 | web 任意域名 vs 小程序「合法域名」白名单硬校验 | 网络层统一出口；小程序侧白名单登记成为接线检查项（见 §5） |
| 页面栈/导航 | 浏览器历史栈 vs 各端路由栈（小程序 navigateTo 有 10 层上限） | 导航语义统一（push/pop/switchTab），深度与返回语义在抽象层抹平 |

## 2. Platform 包封装纪律

- 每端一个**平台能力包**（如 `ios/App/Platform/`、`android/.../platform/`），
  上述能力的实现只住在这里；Feature 层**只 import 抽象，不碰平台 API**。
- 依赖方向单向：`Platform ← Feature ← App 壳`。反向依赖出现即重构。
- **grep 门**（每次涉及平台差异的变更都要跑）：

```bash
# Feature 层不得直接出现平台机制 API（按各端技术栈列关键词，示例）：
grep -rn "safeAreaInsets\|KeyboardAnimator\|onBackPressed" <feature 目录> --include="*.swift" --include="*.kt"
# 小程序：pages/ 与 components/ 不得直接调平台机制 API，应走 utils/platform 适配层：
grep -rn "wx\.request\|wx\.setStorageSync\|wx\.login\|wx\.navigateTo" <miniprogram>/pages <miniprogram>/components
# 期望：0 命中；命中要么挪进 Platform 包/适配层，要么打 APP-DIFF 说明为什么例外
```

- 同一能力各端**命名对齐**（同一数据源、同一兜底语义），镜像实现时逐点核对。

## 3. 判定流程

遇到"web 这么做、App 做不了/不该这么做"时：

1. 是**能力缺口**吗（平台真没有）→ Platform 包封装最接近的等价件 + `APP-DIFF platform` 标记
2. 是**语义不同**吗（有但行为不同，如返回栈）→ 统一抽象里抹平语义 + 标记
3. 是**业务选择**吗（移动端入口策略/合规）→ `business` 标记
4. 都不是，只是没做 → BACKLOG 缺陷，不配打标记

## 4. 渲染引擎差异（canvas/WebGL 页）

web 用 canvas/WebGL/pixi 类引擎渲染的内容（图表/游戏/特效）：

- App 侧选型（各端原生渲染 / 统一跨端引擎 / WebView 局部承载）属**架构决策**，
  需用户确认后定，定完写进项目文档；每个页面不单独选型。
- 引擎内容**不适用** DOM 级采证与网格比例法：验收以截图帧互证 + 素材 URL 同源 +
  关键交互逻辑对拍（`logic-parity.md`）为主。
- 崩溃兜底：引擎层异常要有降级路径（占位/重进），不能整页黑屏白屏；
  兜底行为也要采证登记。

## 5. 微信小程序专项（platforms 含 miniprogram 时必读）

工程与配置：

- 页面四件套 `pages/<name>/<name>.{wxml,wxss,js,json}`；**新页面必须在 app.json 的
  `pages` 数组登记**（tab 页另配 `tabBar.list`，分包页配 `subpackages`），未登记无法
  跳转/直达——这是"路由接线"在小程序侧的形态。
- `project.config.json` 的 appid 取 `app.miniprogram.appid`；正式 appid 由用户提供。

**域名白名单（最高频的坑）**：`wx.request`/`wx.uploadFile`/`wx.downloadFile` 的目标
域名必须在小程序后台「开发管理 → 服务器域名」配置，**真机硬校验**；开发者工具勾选
「不校验合法域名」只在工具里生效。内嵌 web-view 页另走「业务域名」白名单。
接线新接口时：把域名加入白名单 = 一等公民步骤，忘记 = 真机全挂工具全绿（假绿）。

单位与换算：

- rpx 以 **750 设计基准**等比：`目标 rpx = web 采证视口内 px × 750 ÷ 采证视口宽`
  （如 390 视口下 ×750/390 ≈ ×1.923）。换算纪律同 `ui-parity.md`：先摸清 web 适配栈
  再换，禁止拿样式值直接当 rpx。
- 安全区：WXSS 支持 `env(safe-area-inset-*)`，与 web 同源语义。

平台语义差异（对照能力矩阵）：

- **页面栈**：`navigateTo` 有 10 层上限，深链多层跳转要 `reLaunch`/`redirect` 收敛；
  tabBar 页只能 `switchTab`（query 传参不生效，参数走 storage/全局态或用 `wx.setNavigationBarTitle` 场景）。
- **生命周期**：`onLoad/onShow/onReady/onHide/onUnload` 与 web 页面模型不同，
  返回刷新/缓存恢复语义在适配层统一。
- **登录态**：小程序无 cookie；`wx.login` + code2Session 换 token 存 storage。
  web 用 cookie 的接口在小程序侧要带 token 改造——这属于协议整形，进逻辑对拍。
- **网络**：`wx.request` 无跨域概念（只有白名单）；并发上限 10。
- **审核约束**：类目资质、iOS 侧虚拟支付限制等 → `APP-DIFF business` 差异的典型来源。

实拍与自动化（验收缝）：

- 直达页面：开发者工具「编译模式」自定义启动页面+参数，或
  `miniprogram-automator`：`automator.launch().then(mp => mp.reLaunch('pages/xx/xx?a=1'))`。
- 自动化截图：`miniProgram.screenshot({ path })`；元素定位 `page.$('selector')`、
  点击 `element.tap()`——**selector 是 WXML 结构**，与 web DOM 同源可比。
- 真机：预览/体验版 + vConsole；**实拍必须标注版本（开发版/体验版/正式版）**，
  三者行为与域名校验不同，跨版本截图不可比。
- 模拟器机型：开发者工具机型列表覆盖 375×667 / 375×812 / 411×823 即可对齐
  多机型矩阵的小屏/标准/大屏三档。

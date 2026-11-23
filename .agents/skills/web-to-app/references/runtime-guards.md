# 双端运行时护栏（代码审查清单）

> 编译器能过、运行必崩/必错的问题，挡在集成与验收之前。**代码审查必读 + 可执行静态门禁。**

## 0 使用方式

- 代码审查 / review / 集成前预防：逐条过一遍本文清单。
- 改布局、导航、列表、Canvas 之后：至少过 §1–§4。
- 可执行部分：`scripts/methodology_checks.py` 的 runtime 子命令（若未实现，按本文手工核对并在
  报告里注明检查项）。

## 1 布局参数非法（运行时崩溃，编译期不报）

| 端 | 陷阱 | 症状 |
|---|---|---|
| Compose | **负 padding / 负尺寸**：`padding(-2.dp)`、计算出的负值、`Modifier.size` 收到负数 | `IllegalArgumentException: Padding must be non-negative`，只在特定数据下崩 |
| Compose | `weight()` 用在没有 Row/Column 作用域的地方，或对 `Spacer` 之外的元素误用 | 编译期会报（作用域），但复制粘贴进 `Box` 子项时会变成运行期布局异常 |
| Compose | `LazyColumn` 的 `key` 重复或为 null | `IllegalArgumentException: Key was already used` |
| Compose | Canvas / `Modifier.drawBehind` 里对 0 或负 size 做除法、`scale(0)` | 画面空白或 NaN 传播 |
| SwiftUI | `.frame(width:height:)` 收到负数 / `NaN`；`GeometryReader` 尺寸为 0 时做除法 | 视图消失或布局抖动 |
| SwiftUI | `ForEach` 的 `id` 重复 | 视图错乱、动画跳变 |

**规则**：所有由数据派生的尺寸（头像直径、进度条宽度、图表高度）一律**先夹到合法区间**
（`coerceAtLeast(min)` / `max(0, …)`），并在边界数据（0、空、超长）下跑一次。

## 2 尺寸吞噬（容器把内容压没）

| 陷阱 | 说明 |
|---|---|
| `ScrollView` / `LazyColumn` 内的无限高子项 | 纵向滚动容器给子项**无限高**约束，内部再放「撑满」的容器会得到 0 或异常高度 |
| `weight(1f)` 的嵌套 | 外层 `weight` 已分配完，内层再 `fillMaxSize` 会收缩 |
| Canvas / 图片的固有尺寸 | 只钉宽度不钉高度时，高度取**固有像素高**，横图被拉成竖条（web 的 `w-38 h-auto` ≠ 只钉宽） |
| WebView 与 insets | WebView 被 padding/weight 收窄时，某些平台视口单位（`vh/dvh`）会算出 0 → 整页折叠白屏 |

**规则**：内嵌 WebView / Canvas 一律贴满宿主；insets 只作用于浮层自身（见
`platform-differences.md`）。

## 3 导航与生命周期

| 陷阱 | 说明 |
|---|---|
| 导航图未就绪时触发导航 | 在 `onCreate` 早期或首帧前导航会丢；用「就绪后再导航」或用空态占位 |
| 返回栈被清空后 pop | 清栈 + `pop()` 会退出应用/黑屏 |
| 单例状态跨页面复用 | 页面销毁后仍持有旧回调 / 旧 ViewModel，导致「改了没反应」或内存泄漏 |
| `body` 内创建有状态对象 | SwiftUI 父体每次求值都会重建；用 `@State`/`StateObject` 缓存 |

## 4 验收假绿（seam 掩盖真实路径）

- `--acceptance-*` / fixture 旗标进场的测试全绿 ≠ 真实启动可用。
- **规则**：验收后必须做一次**不带任何验收旗标的 cold boot**，断言无 fixture 文案、无测试
  按钮、无残留状态。
- 测试缝只从冷启参数/旗标进入，**不落持久存储**（否则下次真实启动会带着测试态）。
- 有显式关闭（X）+ 自动退出；E2E 断言「关闭后节点消失 + 下一个正常动作不重入」。

## 5 网络与权限

| 陷阱 | 说明 |
|---|---|
| 主线程网络 | 同步网络调用在主线程会抛 `NetworkOnMainThreadException` 或被冻结；一律切 IO 调度器 |
| 公共端点漏配免鉴权前缀 | 需要匿名访问的端点被鉴权拦截 → 被误判成「地区封锁 / 服务不可用」 |
| 权限弹窗吞掉合成点击 | 系统权限弹窗（相机/本地网络/通知）在应用窗口之上，期间注入的点击被静默吞掉——测试前先授予权限，或在用例内轮询系统弹窗 |
| 阻塞调用放主线程 | 相机会话 `startRunning()` 等阻塞 API 在主线程会挂起主队列：**表象是「UI 全失效但动画照跑」** |

**分诊口诀**：「按钮点了没反应」先 `sample` 主线程 / 抓主线程栈，再归因——不要先怀疑逻辑。

## 6 平台差异（web CSS ↔ 原生）

| web | 原生 | 说明 |
|---|---|---|
| 负 margin | 无对应 | 用 offset/padding 外扩替代，别硬换算 |
| `overflow: hidden` 裁切 | 各端默认不裁 | 显式 `clip` |
| `position: sticky` | 各有实现 | 列表吸顶要单独验证 |
| `vh/vw` | 视口语义不同 | 用容器尺寸而非视口单位 |
| `z-index` 层叠 | 各端层序规则不同 | 显式 zIndex / 声明顺序 |

# 交互态/弹窗态采证与动画验收（interaction-and-motion）

> `capture.py shot` 只采"到页即得"的态。弹窗、抽屉、Tab、空态、错误态、键盘态、
> 流程相位内的态都需要**触发**；动画还需要**取参数、验关键帧**。本文是这两件事的方法论。

## 1. 状态矩阵（采证前先列全）

对每个页面/改动列出要覆盖的状态，逐格采证：

| 状态 | 触发方式 | 已采 |
|---|---|---|
| default（到页即得） | shot 直接采 | |
| loading | 弱网模拟/断点 | |
| empty（空态） | 新账号/清数据/测试缝 | |
| error（错误行/失败态） | 断网/错误注入 | |
| popup/drawer（弹层） | 点击触发 | |
| keyboard（键盘态） | 聚焦输入框 | |
| 带参态（特定 id/筛选） | 带参 URL | |

**只有触发后才出现的 UI（错误行/禁用态/成功提示）漏采 = 漏验收。**

## 2. 触发方式优先级（稳定可复现优先）

1. **URL 参数**：带参路由直达（最稳定，可复现）
2. **测试缝**：dev-only 的状态注入（App 侧启动参数/深链 extra；web 侧 query 或
   localStorage）——必须零生产影响
3. **状态注入**：eval_js 改 store/localStorage 后刷新
4. **DOM/组件点击**：web 用 `cdp.click_text()`（对框架 handler 最可靠）；
   小程序用 automator `element.tap()`（selector 走 WXML 结构）
5. **坐标点击**：最后手段，坐标必须来自 `getBoundingClientRect`/布局 dump，
   不凭截图目测

未触发的态如实记 `NOT_FOUND`，不硬造（见 SKILL.md 原则 4）。

## 3. 稳定门（触发后什么时候拍）

触发后要等**各端等价的稳定信号**，任一未满足不拍：

- web：网络静默 + 图片 `complete && naturalWidth > 0` + 目标 DOM 可见
  （`el.getBoundingClientRect()` 有尺寸且不在视口外）+ 动画结束
  （`getAnimations().every(a => a.playState === 'finished')` 或 transitionrun/end 计数）
- iOS：UI 静止（无进行中的 animation/transition）、图片加载完成
- Android：`Espresso.idlingResources` 等价信号 或 固定 settle + 帧稳定
- 微信小程序：automator 下等价信号（`page.$$('image')` 数量稳定、setData 完成、
  动画结束回调），或连拍两帧 `mp.screenshot()` 比对一致

弹窗专项：开/关动画结束才拍；连拍两帧比对一致再采（复用 capture.py 像素门思路）。

## 4. 弹窗/浮层采证清单（填任务包 popup-inventory.md）

每条弹层记录：触发入口 ｜ 打开条件 ｜ 关闭条件（X/遮罩/返回/Esc/手势）｜
遮罩与层级 ｜ 滚动锁/焦点陷阱 ｜ 嵌套与链式顺序 ｜ 重复打开行为 ｜ 关闭后页面状态
（路由/滚动位置）｜ 直接深链打开是否可能 ｜ 动画参数 ｜ 证据路径。

常见坑：遮罩下页面仍可滚动（缺滚动锁）、关闭后滚动位置丢失、嵌套弹层返回顺序错、
快速开关出现重叠态、键盘弹起遮住输入框。

## 5. 动画验收（关键帧法）

**取参（web 源）**：从 CSS transition/keyframes、JS 动画库调用、Lottie json、
rAF 代码里提取：duration ｜ delay ｜ easing（cubic-bezier 控制点或命名曲线）｜
transform-origin ｜ stagger（交错间隔）｜ loop ｜ 中断行为 ｜ reduced-motion 策略。
填任务包 `motion-spec.md`。

**验收（各端）**：

- 关键帧比对：动画 0/25/50/75/100% 五帧截图，与 web 同相位帧并排比对
- duration 容差 **±5%**（短动画 <300ms 另加 ±1 帧）
- easing 按控制点核对，不只看"像不像"
- **中断/重入**：快速开关、返回手势、页面退出时动画立即结束/可重入，不卡死不闪烁
- 循环动画验周期稳定性
- `prefers-reduced-motion` / iOS Reduce Motion / Android 动画缩放的等价策略

录屏抽帧法（可选）：ffmpeg 对录屏按时间抽帧，测起止时间得 duration（±5% 判定）。

## 6. App 侧实拍的对应纪律

- 交互态实拍走测试缝直达（seam），**每个交互链至少补一次真实入口端到端**（real），
  证据 `seam` / `real` 分列——只交 seam 帧不得判该链通过。
- 坐标点击的坐标来自布局 dump（iOS accessibility 快照 / Android `uiautomator dump`
  的 bounds / 小程序 WXML 节点 rect），不凭截图目测；小程序优先 selector 定位
  （`page.$('view.foo')`）而非坐标。
- 假状态缝（seam 注入的演示数据）只用于视觉/交互核对，不能替代真实数据源；
  上线前逐条接线检查：seam 分支必须零用户影响，真实路径补验后销缝。

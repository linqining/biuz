# 布局结论的可反驳证据

> 「满宽 / 居中 / 无黑带 / 被裁 / 贴边 / 对齐 / 重叠」——这些词**没有目标元素的 bounds 就不能写**。
> 没有数值的布局结论等于没结论，而且会误导下一轮修复方向。

## 0 一句话规则

**目标元素 = 用户实际看到的那一层**（图片、卡面、按钮、文字），不是它的容器，也不是画布。

反例（真实误判路径）：
1. 第一次把「画布居中」当成「机身满宽」，缩小整图目测写「满宽无黑带」——实际机身被画布约束
   夹窄，右侧仍有黑带。
2. 第二次换 `wrapContentWidth`，第三次换 `requiredSize`，都没修对——因为从没量过目标元素。

## 1 先把结论改写成可反驳的断言

| 用户语言 | 可测断言 | 证据来源 |
|---|---|---|
| 满宽 / 无黑带 | `target.left == 0` 且 `target.right == screenW`，gap ≤ 2px | 目标元素 bounds |
| 居中 | `abs(target.centerX - screenW/2) ≤ 2px` | 目标元素 bounds |
| 偏左 / 偏右 | `leftGap - rightGap` 的符号与数值 | 目标元素 bounds |
| 被裁 | `target.width < expected.width` 或 `target.right < expected.right` | bounds + web rect |
| 比例不对 | `target.w/target.h` vs web `getBoundingClientRect` 比例，±2% | 三端同一节点 |
| 贴边 / 溢出 | `target.bottom - screenH` 的符号与数值 | 目标元素 bounds |
| 重叠 | 两个目标元素 rect 的交集面积 > 0 | 两个 bounds |

## 2 证据五项

每条布局结论必须报满五项：

```
target     目标元素的稳定标识（选择器 / testTag / accessibilityIdentifier）
expected   从 web 派生的期望（rect / 比例 / gap 规则）
measured   目标元素实测值（各端同一语义的测量口径）
tolerance  容差（像素或百分比）
verdict    pass / fail / unverified
```

**没量到只能写「目测 / 未验证」，禁止写「满宽 / 无黑带」。**

## 3 怎么采到目标元素

| 端 | 采法 |
|---|---|
| web | 同一 selector 的 `getBoundingClientRect()`（注意 `transform` 与 `zoom` 已计入） |
| Android | 给目标节点加 `contentDescription`/testTag 语义，`uiautomator dump` 读 bounds；Compose 可用 `onGloballyPositioned` 自测 |
| iOS | `accessibilityIdentifier` + `XCUIElement.frame`，或 `onGeometryChange` 自测 |

**注意容器陷阱**：父容器 / 画布的 bounds 不能代表子元素。若子元素被父约束夹窄，量的必须是子元素。

## 4 边缘类结论的额外要求

判「贴边 / 被裁 / 有黑带」时，光有数值还不够：

1. **1:1 裁切**：把边缘区域按原始分辨率裁出来看，不看缩小整图（缩小会把 2px 的缝抹平）。
2. **画期望线**：在裁切图上标注期望边界，肉眼能判断差多少。
3. **左右 gap 对称性**：单边贴边 vs 居中，`leftGap` 与 `rightGap` 必须同时给。
4. **排除遮挡**：确认不是被浮层/状态栏/手势条盖住造成的「看不到」——遮挡与布局是两回事。
5. **满出血声明**（`fullBleed`）必须同时给 `nativeBounds`，否则机检无从判断。

## 5 与机检的衔接

- 采证产物里把布局断言写进结构化字段（`claims[]`：`target/expected/measured/tolerance`），
  这样 `methodology_checks.py` 可以检查「有没有断言」与「断言是否填满五项」。
- 报告中 layout 类结论若只有文字没有五项，记为 `partial`。

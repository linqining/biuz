# 逻辑对拍（logic-parity）

> 解决的问题：视觉对比验不出**计算逻辑**的对错（金额/概率/状态机/排序/格式化）。
> 逻辑对拍 = 用 web 源的真实实现产出**金标向量**，各端跑同一输入比对输出，
> 机检对账。口号：**web 是金标，各端是考生，账本记覆盖**。

## 1. 五步闭环

1. **选单元**：从本次改动里挑"无 UI 的纯逻辑"——计算函数、格式化、状态转移、
   门控/守卫顺序、协议整形（请求/响应的映射与裁剪）。
2. **写契约**：每单元一份 JSON 契约——canonical 字段、容差（精确/±ε/相对）、
   三方 `file:line`（web 源 + 各端实现）。**测试先行**：契约在实现前写。
3. **web 出金标**：写 probe 调 web 真实函数（import 源码模块，或 dev server 上
   eval_js 调页面内函数），输入集 → 输出 JSON。**不要手算期望值**。
4. **各端跑实际输出**：各端写 runner（单测形态，调真实实现，不走 UI），
   同输入集 → 输出 JSON。允许 `nativeSkip` 字段声明本端不适用 + 理由。
5. **comparator 对账**：按契约容差比对三份 JSON，产出机检结论 + 写对拍账本。

## 2. 向量 schema（示例）

```json
{
  "unit": "wallet.formatAmount",
  "contract": {
    "inputs": ["12.5", "1234567.891", "0.004", "-0", null],
    "tolerance": "exact",
    "sources": {
      "web": "src/utils/money.ts:14",
      "ios": "ios/App/Domain/Money.swift:9",
      "android": "android/app/src/.../Money.kt:7",
      "miniprogram": "miniprogram/utils/money.js:5"
    }
  },
  "golden": [
    {"in": "12.5", "out": "12.50"},
    {"in": "1234567.891", "out": "1,234,567.89"},
    {"in": "0.004", "out": "0.00"},
    {"in": "-0", "out": "0.00"},
    {"in": null, "out": "--"}
  ],
  "runs": {
    "ios": {"out": ["12.50", "1,234,567.89", "0.00", "0.00", "--"], "status": "pass"},
    "android": {"out": ["12.50", "1,234,567.89", "0.00", "0.00", "--"], "status": "pass"}
  }
}
```

- 浮点类单元用相对容差（如 `rel:1e-9`），金额显示类用 exact（字符串逐字）。
- 状态机类：输入是**事件序列**，输出是状态轨迹（含 guard 拒绝分支）。
- 输入集必须含边界：空/null/极值/非法值/并发重入序列。

## 3. 必须对拍 / 可跳过

| 必须对拍 | 可跳过（logic=skipped + 理由） |
|---|---|
| 金额/积分/概率计算与格式化 | 纯颜色/间距/图片替换 |
| 状态机转移、门控顺序、弹窗链顺序 | 纯文案微调 |
| 排序/过滤/分组规则 | 动画时长/曲线（属 motion 验收） |
| 协议整形（请求参数组装/响应映射） | 静态资源增删 |
| 权限/可见性判定 | |

skip 纪律：`sync_state.py done` 时 `logic=skipped --skip-reason "..."`；
checklist §4 同步写理由。**不要为了走流程编造向量**。

## 4. 最小工具链（首次使用在项目内搭）

目录约定 `.capture/tools/`（一次性脚本的家，见 `capture-pipeline.md` §5）：

```text
.capture/tools/
├── logic_probe_web.py       # 调 web 函数出金标（import 源码或 CDP eval_js）
├── logic_vectors/<unit>.json # 契约+金标+各端输出（入库，作复现依据）
└── logic_vector_compare.py  # 对账器：--check-only 校验 schema；--require-run 全量比对
```

- 各端 runner 是各工程内的单测（iOS XCTest / Android instrumentation /
  微信小程序 jest + `miniprogram-simulate`（或直接 `require` 纯函数模块跑 node）/
  其他平台等价物），输出 JSON 落到向量文件的 `runs` 字段。
  小程序侧逻辑模块用 CommonJS 独立成文件（不 Page 内联）才可测——这本身就是好架构。
- 对账器很小（读 JSON、按容差比、exit code 表成功失败），首次写一次，之后只加向量。
- **崩溃门**：runner 跑挂（crash/exception）= fail，不是 skip。

## 5. 对拍账本与覆盖盘点

`docs/logic-parity/0-对拍账本.md`：每行一个单元（unit ｜ 契约 ｜ web/各端状态 ｜
最近运行 ｜ 备注）。收口标准：改动涉及的单元全部 `pass` 或显式豁免；
豁免必须写原因（如 `nativeSkip: 本端无此路径，UI 层已裁剪`）。

冷启动期可用账本做**覆盖盘点**：从 web 源码盘点全部纯逻辑模块，逐批补向量，
账本记录"总盘子/已覆盖/待补"。

## 6. 纪律

1. 金标只能来自 web 真实实现（probe 实跑），禁止手写期望值。
2. 向量文件入库；probe/runner 脚本可再生成，向量是复现依据。
3. 对拍失败先怀疑换算/精度/时区/语言环境，修完重跑；不许放宽容差凑绿。
4. 修 bug 时先把失败 case 加进向量（回归锚），再修实现。

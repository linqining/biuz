# web 变动同步与分支对比（sync-workflow）

> 输入两种口令：**"web 有变动，同步到 App"**（§A）与 **"实现 XX 分支 / 和 XX 分支对比差异"**（§B）。
> 两者共用同一条流水线：**冻结基线 → 采证 → 契约/骨架 → 逻辑实现 → 逻辑对拍 →
> UI 静态 → 弹窗/动效 → 实拍对比 → 验收/推进 commit**。
> 逻辑先行仍保留，但弹窗与动画从采证/契约阶段就进入，不要留到最后补。

## 0. 九步流水线（推荐顺序）

| 阶段 | 做什么 | 门禁 / 产出 |
|---|---|---|
| 0. Intake / Freeze | 记录 web 源 commit/branch/dirty；归类 diff；生成任务包 | `sync_state.py begin`、`sync_scaffold.py`、`brief.md` |
| 1. Evidence | 源码 `file:line`、DOM/网络、截图、弹窗状态帧、动画参数、文案、资源、接口契约 | 采证目录、`popup-inventory.md`、`motion-spec.md` |
| 2. Contract & Skeleton | 文件映射、状态机契约、动画规格、逻辑契约 + web 金标向量、测试缝、平台分支 | `file-map.json`、`state-machine.md`、`logic-vectors/` |
| 3. Logic 实现 | **同一个 agent 同时负责全部平台**；逻辑/状态机/门控/链式/触发条件 | 各端构建门 |
| 4. Logic Parity | 按变更类型分流；纯逻辑与弹窗状态机/门控/链式顺序对拍 | 对拍绿 |
| 5. UI 静态 | 布局、颜色、token、适配换算、平台能力、弹窗静态布局、资源/文案 | 构建门 + 适配判定表 |
| 6. Interaction & Motion | 弹窗开关/遮罩/层级/返回/滚动锁；入场/退场动画/缓动/中断/reduced-motion | 交互清单 + 动效规格落地 |
| 7. Capture / Compare / Adjust | 同 key 同状态多端成对、弹窗 before/after、动画关键帧、多机型、seam+real | 对比报告 + 修复闭环 |
| 8. Acceptance / State | 台账、差异标记、遗留项；**全门通过后才推进 sync commit** | `sync_state.py done`、状态文件 |

> 纯 UI 变更可把第 4 步标 `logic=skipped`，但必须在 checklist 写理由，并在
> `sync_state.py done` 传 `--skip-reason`。不要为了"走完流程"编造逻辑向量。

## 1. 冻结基线 + 生成任务包

```bash
# web 源仓库路径取 config 的 web.repo（支持 ~ 展开），勿写死
python3 <skill>/scripts/sync_state.py status
python3 <skill>/scripts/sync_state.py begin \
  --scope logic,ui,popup,animation --note "一句话说明本次同步"

python3 <skill>/scripts/sync_scaffold.py \
  --key <页面key> --title "人类可读标题" \
  --scope logic,ui,popup,animation \
  --routes /path/to/route \
  --web-source src/.../file.tsx \
  --app-target ios=ios/App/Features/.../XxxView.swift \
  --app-target android=android/app/src/.../XxxScreen.kt \
  --code
```

规则：

- `sync_state.py begin` **只写 in_progress，不推进 last_synced**；done 时才推进。
- web 源工作区 dirty 时默认拒绝 begin；确需继续加 `--allow-dirty`，状态里会记录
  `dirty=true`（可复现性下降，验收要说明）。
- 任务包落 `sync.scaffold_dir`（默认 `.capture/sync/<时间戳>-<slug>/`）；`--code`
  生成 `.template` 骨架，**不写业务源码、不生成假数据**。
- 基线状态落 `sync.state_file`，done 时同步镜像差异台账的 `ledger_key` 行。

## 2. 采证（含弹窗与动画）

页面级：

```bash
python3 <skill>/scripts/capture.py status
python3 <skill>/scripts/capture.py shot <key> --scrolls N --dom
```

- 页面细节：`capture-pipeline.md`；交互态/弹窗态：`interaction-and-motion.md`
- 弹窗：记录触发入口、打开/关闭条件、遮罩/层级、滚动锁/焦点、返回/Esc、嵌套/链式、
  直接深链打开；填 `popup-inventory.md`
- 动画：记录 duration/delay/easing/transform-origin/stagger/loop/中断/重入/
  reduced-motion；填 `motion-spec.md`
- 文案：从 web 源逐字核对（i18n 项目先确认权威语言包，见 `ui-parity.md` §4）
- 资源：同源 URL + 本地化文件名；不做近似重绘
- 接口：从 web 源 service 层读契约，必要时网络采证对账

**稳定门**：网络静默（含在途图片）、图片 `complete && naturalWidth > 0`、
目标 DOM 可见、动画结束、视口校验。没加载完的帧不得作为基线。

## 3. 契约与骨架

- `file-map.json`：web 源 ↔ 各端目标文件，逐条填；不要只写"参照 web"。
- `state-machine.md`：状态/事件/guard/action/转移；弹窗链顺序、关闭/返回、重复打开、竞态。
- `logic-vectors/`：按 `logic-parity.md` 五步闭环**先写契约与 web 金标**，
  再实现（测试先行），不要等代码写完再编断言。
- 测试缝：每页一个直达入口（iOS 启动参数 / Android 深链 extra），必须零用户影响。
- 平台差异：先判安全区/IME/方向/返回/生命周期/深链/权限/存储等归属，
  见 `platform-differences.md`。

## 4. 逻辑实现（全部平台同一 agent）

- **一个 agent 同时负责所有平台**；先修通一端，再逐点镜像其他端。
- 同一数据源、同一条件门、同一兜底命名、同一文案常量；禁止只改一端。
- 平台机制差异写清原因（notes 或差异标记 `platform` kind），不要默默分叉。
- 门禁：各端构建命令（首次使用时写进 checklist，后续照抄）。

## 5. 逻辑对拍（按变更类型分流）

- **必须对拍**：无 UI 纯逻辑、金额/格式化/状态机/门控、弹窗链顺序、协议整形。
- **可跳过**：纯颜色/间距/图片/动画时长；在 `checklist.md` 写理由，done 时传
  `logic=skipped --skip-reason ...`。
- 动画时长/曲线属于 **motion 验收**，不要硬塞进 logic vector；弹窗"是否可打开/
  守卫顺序/链式顺序"属于逻辑对拍。

## 6. UI 静态实现

- 布局/颜色/字号/间距按 `ui-parity.md` 判定；读 web 数值前**先摸清适配栈**，
  禁止拿 CSS 值直接除以屏幕宽。
- 平台差异走平台能力封装（`platform-differences.md`）。
- 弹窗/抽屉先完成静态布局（遮罩，圆角，层级，安全区，IME）。
- 资源/文案同源；新增页面同步更新页面索引与各端验收清单。

## 7. Interaction & Motion 实现

弹窗/浮层：

- 打开/关闭、遮罩点击、返回键/Esc/手势、滚动锁、焦点陷阱、嵌套层级、链式顺序、
  重复打开、关闭后页面状态。
- 平台等价件：iOS sheet/fullScreenCover/overlay；Android Dialog/BottomSheet；
  差异打差异标记。
- 有输入框时验证 IME/安全区；有列表时验证滚动锁与焦点。

动画：

- 按 `motion-spec.md` 逐项实现 duration/delay/easing/stagger/transform-origin。
- 验证中断/重入（快速开关、返回手势、页面退出）、循环动画、reduced-motion。
- 门禁：各端构建 + 交互/动效自检清单。

## 8. Capture / Compare / Adjust

- 同 key 同状态多端成对（web + 各端）；弹窗 before/after 成对。
- 动画关键帧 0/25/50/75/100% + duration/easing 表；duration 容差 ±5%
  （短动画另加 ±1 帧），easing 按控制点核对。
- 多机型矩阵：小屏/标准/大屏；`fontScale=1.3`；横屏；键盘；安全区。
- 稳定门 + `seam`/`real` 双验证；只交 seam 帧不得判交互链通过。
- 退出标准：P1/P2 清零，或 deferred 带堵点/复验条件；差异入 BACKLOG/差异台账。

## 9. Acceptance / 推进 commit

```bash
# 全门通过后（示例）
python3 <skill>/scripts/sync_state.py done \
  --gates build=pass,logic=pass,ui=pass,capture=pass \
  --note "同步完成；遗留项见 ..."
```

- `done` 会拒绝：门禁未 pass、web 源 HEAD 移动（除非 `--accept-head-move`）、
  dirty（除非 `--allow-dirty`）。
- `done` 成功才更新状态文件的 `last_synced` 和差异台账基线行；失败/部分完成不推进。
- 收尾：`docs/PARITY_BACKLOG.md`（缺陷）、差异台账（刻意差异）、对拍账本（逻辑）。

## §A. web 变动 → App 同步（快速版）

### A1. 定位变动

```bash
cd <web.repo>
git log --oneline -20
git diff --stat <上次同步的 commit>..HEAD
```

上次 commit 用 `sync_state.py status` 查看；没有基线就先 `begin` 并在本次完成后 `done`。

### A2. 变动归类（五类 + 四个横切面）

| 类别 | 识别 | App 侧动作 |
|---|---|---|
| 路由/页面组件 | 路由表、pages 目录、导航 map | 按页面索引定位各端页面，走完整流水线 |
| 静态资源 | CDN/静态目录、iconfont、图片 | 同源下载进各端素材目录，URL 与文件逐字对齐 |
| 接口/协议 | service 层、proto/接口定义 | 先对齐契约，再改数据层；生成文件禁手改 |
| 文案/i18n | locales、语言包 | 各端文案表同步；以权威语言包为准 |
| 纯逻辑/样式微调 | 组件内部 diff | 按变更类型走逻辑或 UI 分支 |

四个横切面：**弹窗/浮层、动画/动效、平台语义、渲染引擎差异（canvas/WebGL）**。
任何一项命中都要在任务包里显式登记，不能漏在"UI 微调"里。

### A3~A8

按上面第 2~9 步执行。§B 分支对比复用同一流水线。

## §B. 分支对比（"实现 XX 分支" / "和 XX 分支对比差异"）

### B1. diff 两个分支

```bash
cd <web.repo>
git diff --stat <base>..<target>
git diff --name-status <base>..<target>
git log --oneline <base>..<target>
```

### B2. 产出《分支差异清单》

按 §A2 归类，输出表格（给用户看的中间产物，也是实现清单）：

```markdown
## <base>..<target> 分支差异清单（YYYY-MM-DD）

| # | 类别 | web 文件 | 影响页面(key) | 差异摘要 | App 侧动作 |
|---|------|----------|---------------|----------|----------|
| 1 | 静态资源 | cdn/.../new-banner.webp | 001-首页 | 新增轮播图 | 同源下载 + 轮播数据接入 |
```

路由变化（新增/删除页面）同步更新页面索引 + 各端路由表与验收清单。

### B3. 采证两个版本

- 已部署：线上站直接采。
- 未部署/需前后对比：请用户在本地 dev 分别切 `<base>`、`<target>` 起服务，各采一轮
  （采证目录名区分 base/target），同 key 同规格可比。

### B4~B8

复用第 3~9 步；`sync_state.py begin` 的 base commit 用 `<target>`。

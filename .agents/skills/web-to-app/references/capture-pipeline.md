# 浏览器采证链路（capture-pipeline）

> 目标：从 web 源（已部署站或本地 dev）按**统一仿真规格**采集截图 / DOM / 网络证据，
> 作为 App 实现与验收的基线。所有证据可追溯（截图 + meta + 时间戳）。
> 工具：`<skill>/scripts/capture.py`（自包含 CDP 客户端，python3 + websocket-client，
> 不依赖 node；Pillow 可选，仅像素稳定门需要）。

## 1. 两个采集目标

地址一律取 `config/web-to-app.yaml` 的 `web.deployed_base` / `web.local_dev_base`：

| 目标 | CLI | 用途 | 登录态 |
|---|---|---|---|
| 已部署站 | `--base deployed`（默认） | 已部署版本的权威实拍；验收基线 | 持久 profile（`capture.chrome_profile_dir`），用户已登录 |
| 本地 dev | `--base local` | web 某分支的未部署改动；用户本地起的 dev server | 以用户当时登录态为准，采集前 `status` 确认 |

采集前先问自己：改动部署了吗？已部署 → 线上站采；未部署 → 请用户在本地 dev server
上切好分支再采（web 源是参照仓库，**agent 不在其上切分支**）。

## 2. Chrome 调试实例（端口取 `capture.chrome_debug_port`，默认 9224）

采证走**独立调试 Chrome 实例**，不与日常浏览器抢 profile：

- 持久 profile：`capture.chrome_profile_dir`（已 gitignore，登录态保存在里面）
- 拉起：`python3 capture.py chrome`（等价于手动：
  `open -na "Google Chrome" --args --remote-debugging-port=9224 --user-data-dir=<profile> --no-first-run`）
- 探活：`http://127.0.0.1:9224/json/version`

**与其他工具管理的浏览器的区别**：chrome-devtools MCP 等插件自带的浏览器用独立
profile，**不共享 9224 实例的登录态**。游客态快速看页面可用其他工具；
正式采证一律走 capture.py（本链路），保证登录态与规格一致、产物可追溯。

## 3. capture.py CLI

```bash
CAP="<skill>/scripts/capture.py"

python3 "$CAP" chrome                    # 确保调试 Chrome 在跑（没跑则拉起）
python3 "$CAP" status                    # 探活：Chrome 状态 / 当前页 / 登录态
python3 "$CAP" pages                     # 列出页面索引全部 key 与路由
python3 "$CAP" shot 001-首页             # 按 key 采证（已部署站）
python3 "$CAP" shot /items               # 按路由采证
python3 "$CAP" shot "https://example.com/items?id=7" --name item-7   # 按完整 URL
python3 "$CAP" shot 002-列表 --scrolls 3 --dom    # 滚动 3 屏 + 落 dom.html
python3 "$CAP" shot 002-列表 --base local         # 采本地 dev server
python3 "$CAP" shot 004-画布页 --dom-stable       # canvas/WebGL 页走 DOM 锚稳定
```

`shot` 的固定流程（规格统一，保证跨页/跨时间可比）：

1. 连接调试端口 → `Emulation.setDeviceMetricsOverride` 视口仿真
   （默认 390×844@3x + iPhone UA + 触控；`capture.viewport_*` 可改）
2. 注入**屏幕竖屏补丁**：rem 适配站点常按 `screen.*` 算根字号，宽窗口下会算错，
   必须把 `screen.*` 伪造为仿真视口尺寸（不用 rem 的站点无副作用）
3. 导航 → 等渲染稳定（三重门：网络静默含在途图片 → 慢滚触发 lazy → 图片就绪，
   最长 30s）
4. 截图前过**截图稳定门**：连拍像素比对收敛（防动画/行情流抖动）；canvas 页自动切
   DOM 锚框稳定；仍不收敛取差异最小帧并标 `unstable`
5. 截图：`01-viewport.png` → 可选 `--scrolls N` 逐屏滚动截图 → 回顶
6. 可选 `--dom` 落 `dom.html`（渲染后 innerHTML）
7. 落 `meta.json`（URL、时间戳、登录态、文本量、canvas 数、viewport 校验、稳定门
   结果、坏图数）+ `images-final.json`（逐图审计：rect/尺寸/完成态）

输出目录：`capture.output_dir`（默认 `.capture/runs/<YYYYMMDD-HHMMSS>-<名称>/`）。
meta.json 里 `status=UNVERIFIED`（重采后仍有坏图）或 `stability=unstable` 的采集
**不得作为验收基线**。

## 4. 交互流程采集（点击后状态、流程内态）

`capture.py shot` 只覆盖"到页即得"的状态。**点击后、提单后、流程相位内**的状态
用 capture.py 的 `CDP` 类交互驱动（可 import，无需额外依赖）：

```python
import sys
sys.path.insert(0, "<skill>/scripts")
import capture as cap

c = cap.CDP(); c.emulate()
c.navigate("https://example.com/items")
r = c.click_text("提交")          # DOM 级点击（对框架 handler 最可靠），返回 CLICKED/NOT_FOUND
c.shot(".capture/runs/submit-after.png")
print(c.state())                  # {u, iw, cw, cv, len, guest, conn}
```

- 一次性探查脚本一律落项目 `.capture/tools/`，不要散落 `.capture/` 根目录。
- 流程证据的标准件：交互后 `-after.png` + 步骤记录 json（每步 CLICKED/NOT_FOUND
  与网络增量）。**未复现的交互如实记录 NOT_FOUND，禁止造状态**。
- 弹窗/抽屉/Toast 的触发与稳定门：`interaction-and-motion.md`；
  动画参数采证同文档 §动画。

## 5. 输出目录约定

| 目录 | 内容 | 入库 |
|---|---|---|
| `.capture/runs/<ts>-<name>/` | 日常采证（capture.py 默认输出，**web 原始截图唯一入口**） | 否（gitignore） |
| `.capture/<专题>/` | 多步采证专题（如某流程状态机全集） | 否 |
| `.capture/tools/` | 采集/探查脚本，只进不出根目录 | 否 |
| `.capture/sync/<ts>-<slug>/` | 同步任务包（sync_scaffold.py 产出） | 按项目约定 |
| `<app.ios.dir>/artifacts/acceptance-run/` 等各端 | App 验收实拍 | 否 |

登记条目（BACKLOG / 差异台账）里引用的是这些路径；路径即出处。
含会话凭据的产物（network 抓包等）**绝不入库**。

## 6. 已知坑（按类型）

1. **屏幕补丁**：不注入时根字号错（rem 站点整页缩放漂移）。capture.py 已内建。
2. **canvas/WebGL 页**：图表/游戏内容不在 DOM，`--dom` 采不到画面，只能截图 +
   网络记录覆盖；长页采集用分段滚动；稳定门用 `--dom-stable`。
3. **登录态差异即页面差异**：同一页游客/登录/不同角色形态不同。采集时 meta 里记录
   登录态（`capture.login_hints` 配置判定正则）；裁决差异前先排除账号态因素。
4. **viewport 校验**：截图前核对 `innerWidth` 与配置视口误差 ≤1px，超差重仿真
   （capture.py 已内建；`sizeMismatch` 警告的图不可作基线）。
5. **点击优先 DOM 级**（`el.click()`）：CDP 触摸/鼠标事件对视口外元素与部分框架
   handler 不可靠。
6. **涉及真实扣款/下单/发送的最终确认步骤一律不执行**；需要该状态的采证走构造数据
   或测试环境，并向用户说明。
7. **在途图片不算网络活动**：网络静默 ≠ 图片加载完（`responseEnd=0` 的图片仍在下载）。
   截图前必须显式校验 `document.images` 全部 `complete && naturalWidth > 0`
   （capture.py 三重门已覆盖；自写脚本时要带上）。
8. **交互态/弹窗态不归 `shot` 管**：弹窗、抽屉、Tab、空态、错误态、键盘态需要
   触发/注入，统一按 `interaction-and-motion.md` 的状态矩阵与稳定门执行。
9. **测试缝假绿**：`acceptance_route` / 深链 extra 可以把页面钉在 demo/占位态，
   "通过"不等于真实路径可用；验收必须补真实入口端到端验证（`acceptance.md`）。

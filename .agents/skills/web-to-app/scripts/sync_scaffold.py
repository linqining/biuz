#!/usr/bin/env python3
"""生成一次 web 同步的任务包/脚手架（不写业务源码，避免空壳通过验收）。

用法：
  python3 sync_scaffold.py --key wallet-withdraw --title "钱包提现确认" \
      --scope logic,ui,popup,animation --routes /wallet \
      --web-source src/pages/wallet/withdraw.tsx \
      --app-target ios=ios/App/Features/Wallet/WithdrawView.swift \
      --app-target android=android/app/src/.../WithdrawScreen.kt \
      --code

默认输出：<项目根>/.capture/sync/<时间戳>-<slug>/
  brief.md / file-map.json / checklist.md / popup-inventory.md /
  motion-spec.md / state-machine.md / logic-vectors/README.md /
  acceptance.json / state.json
  --code 额外生成 code-skeleton/*.template（不参与编译，不生成假数据）
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import skill_common as common  # noqa: E402

SKILL = common.SKILL_DIR


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def parse_app_targets(values: list[str], platforms: list[str]) -> dict[str, list[str]]:
    """--app-target ios=path（可重复） → {platform: [paths...]}。"""
    out: dict[str, list[str]] = {}
    for raw in values:
        if "=" not in raw:
            raise SystemExit(f"--app-target 格式应为 platform=path：{raw}")
        plat, path = raw.split("=", 1)
        plat, path = plat.strip(), path.strip()
        if plat not in platforms:
            raise SystemExit(f"未知平台 {plat!r}（config app.platforms 目前是 {', '.join(platforms)}）")
        out.setdefault(plat, []).append(path)
    return out


def brief_md(args, info, scope, routes, web_sources, app_targets, platforms) -> str:
    map_header = "| web 源 | " + " | ".join(platforms) + " | 状态 |"
    map_rule = "|---|" + "---|" * (len(platforms) + 1)
    n = max([len(web_sources)] + [len(v) for v in app_targets.values()] + [1])
    rows = []
    for i in range(n):
        cells = [f"`{web_sources[i]}`" if i < len(web_sources) else "TODO"]
        for p in platforms:
            paths = app_targets.get(p) or []
            cells.append(f"`{paths[i]}`" if i < len(paths) else "TODO")
        cells.append("TODO")
        rows.append("| " + " | ".join(cells) + " |")
    return f"""# 同步任务包：{args.title}

> 由 `sync_scaffold.py` 生成。按 §0→§8 顺序执行；每完成一项在 `checklist.md` 勾选。
> 逻辑先行，但弹窗/动画从采证与契约阶段就进入，不要留到最后补。

## 基本信息

| 项 | 值 |
|---|---|
| key | `{args.key}` |
| web 源 commit | `{info['commit']}` |
| branch | `{info['branch']}` |
| dirty | `{info['dirty']}（{info['dirty_count']} 个文件） |
| 生成时间 | `{common.now_utc()}` |
| 范围 | `{', '.join(scope)}` |
| 路由 | `{', '.join(routes) or 'TODO'}` |

## 变更摘要（必填）

- TODO：用 web 源 diff / 源码 `file:line` 写清楚改了什么；不要只写“按 web 改”。

## 文件映射

{map_header}
{map_rule}
{chr(10).join(rows)}

## 弹窗 / 动画 / 平台

- 弹窗清单：`popup-inventory.md`（有弹窗/抽屉/Toast 时必填）
- 动效规格：`motion-spec.md`（有动画/转场/动效时必填）
- 状态机：`state-machine.md`（有开关/链式/门控时序时必填）
- 平台差异：见 `{SKILL}/references/platform-differences.md`

## 验收标准

- 逻辑：纯逻辑/状态机/门控/链式顺序按 `{SKILL}/references/logic-parity.md` 对拍；
  纯 UI 变更可 skipped，但 `checklist.md` 要写理由。
- UI：数值换算与测量容差按 `{SKILL}/references/ui-parity.md` 判定。
- 弹窗：before/after 帧、关闭/重开/嵌套、返回/Esc、滚动锁/焦点、平台等价件。
- 动画：关键帧 0/25/50/75/100%，duration ±5%（短动画另加 ±1 帧），easing 控制点核对，中断/重入。
- 实拍：多端同 key 同状态（web + 各端）+ 多机型矩阵 + seam/real 双验证 + 稳定门。
"""


def checklist_md(args, scope, routes, platforms) -> str:
    route = routes[0] if routes else "<route>"
    build_lines = "\n".join(
        f"- [ ] {p} 构建门：`<填写本项目的 {p} 构建命令>`（首次使用时写死到这里）"
        for p in platforms)
    impl_lines = "\n".join(
        f"- [ ] {p}：按 file-map 逐条实现" for p in platforms)
    return f"""# 同步检查清单：{args.title}

> 顺序执行；未通过的门不勾，不推进 commit。

## 0. 冻结基线

- [ ] `python3 {SKILL}/scripts/sync_state.py status`
- [ ] `python3 {SKILL}/scripts/sync_state.py begin --scope {','.join(scope)}`
- [ ] 确认 `state.json` 的 commit/branch/dirty 与本任务一致

## 1. 采证

- [ ] 源码 diff / `file:line` 写进 `brief.md`
- [ ] `python3 {SKILL}/scripts/capture.py status`
- [ ] `python3 {SKILL}/scripts/capture.py shot {route} --dom`
- [ ] 弹窗状态帧（默认/打开/嵌套/关闭）→ `popup-inventory.md`
- [ ] 动画参数（duration/delay/easing/stagger/loop/中断）→ `motion-spec.md`
- [ ] 文案：从 web 源逐字核对（i18n 项目先确认权威语言包来源）
- [ ] 资源 URL 同源记录；接口调用从源码 service 层 / 网络采证对齐（如适用）

## 2. 契约与骨架

- [ ] `file-map.json` 填完（web ↔ 各端）
- [ ] `state-machine.md` 填完（状态/事件/guard/action/转移）
- [ ] `logic-vectors/` 按 `{SKILL}/references/logic-parity.md` 建契约与 web 金标
- [ ] 测试缝/深链/启动参数确定（见 `{SKILL}/references/interaction-and-motion.md`）
- [ ] 平台差异判定（`{SKILL}/references/platform-differences.md`）

## 3. 逻辑实现（全部平台同一 agent 负责，逐点镜像）

{impl_lines}
{build_lines}

## 4. 逻辑对拍

- [ ] 逻辑/状态机变更：按 `{SKILL}/references/logic-parity.md` 跑对拍（工具在项目
      `.capture/tools/`；首次使用按该文档建最小工具链）
- [ ] 若 skipped：在下方写理由（`sync_state.py done` 需要 `--skip-reason`）
      - 理由：TODO

## 5. UI 静态实现

- [ ] 布局/颜色/字号/间距按 `{SKILL}/references/ui-parity.md` 判定（先摸清 web 适配栈再换算）
- [ ] 安全区/IME/方向/返回走平台能力封装（`{SKILL}/references/platform-differences.md`）
- [ ] 弹窗/抽屉静态布局（若有）
- [ ] 资源/文案同源
- [ ] 各端构建门

## 6. 交互与动效实现

- [ ] 弹窗/抽屉/Toast 开关、遮罩、层级、滚动锁、焦点、返回/Esc
- [ ] 嵌套/链式顺序、重复打开、关闭后状态
- [ ] 入场/退场动画、easing/duration、中断/重入
- [ ] reduced-motion / 系统动画缩放策略
- [ ] 各端构建门

## 7. 实拍对比与修复

- [ ] 稳定门：网络静默 + 图片 complete + DOM 可见 + 动画结束
- [ ] 多端同 key 同状态（web + 各端）
- [ ] 弹窗 before/after；动画 0/25/50/75/100% 帧
- [ ] 多机型矩阵（小屏/标准/大屏 + fontScale 1.3 + 横屏，见 acceptance.json）
- [ ] seam + real 双验证（防测试缝假绿）
- [ ] P1/P2 清零或 deferred 带堵点

## 8. 验收与基线

- [ ] 更新差异台账 / PARITY_BACKLOG / 差异标记（`{SKILL}/references/diff-language.md`）
- [ ] 全门通过后：
      `python3 {SKILL}/scripts/sync_state.py done --gates build=pass,logic=pass,ui=pass,capture=pass`
- [ ] `sync_state.py status` 确认 last_synced 已推进
"""


def popup_md() -> str:
    return """# 弹窗 / 浮层清单

> 有弹窗、抽屉、Toast、菜单、二次确认时必填。类型：modal / sheet / drawer / toast / action-sheet / dropdown / fullscreen。
> 详细采证与稳定门见 interaction-and-motion.md；动画参数见 motion-spec.md。

| 名称 | 类型 | 触发入口 | 打开条件 | 关闭条件 | 遮罩/层级 | 滚动锁/焦点 | 返回/Esc | 嵌套/链式 | 平台等价 | 动画 | 证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | iOS sheet / Android Dialog / 其他平台等价件 | 见 motion-spec | TODO |

## 状态机

- 状态：closed / opening / open / closing / error / submitting
- 事件：open / close / backdrop / back / submit-success / submit-fail / data-loaded
- 关闭后状态（页面/路由/滚动位置）：
- 重复打开 / 快速开关 / 链式弹窗：
- 直接深链打开：
"""


def motion_md() -> str:
    return """# 动效规格

> 有 CSS transition/keyframes、JS 动画库（GSAP 等）、rAF、Lottie、骨骼动画（Spine 等）、视频动效时必填。
> 验收方式：0/25/50/75/100% 关键帧 + duration/easing 表；duration ±5%（短动画 ±1 帧）。

| 元素/状态 | web 源 file:line | duration | delay | easing | transform-origin | stagger | loop | 中断/重入 | reduced-motion | 各端实现 | 验收帧 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | 0/25/50/75/100 |

## 核对要点

- duration/delay 单位统一到 ms；easing 记录 cubic-bezier 或命名曲线。
- 动画是否改变布局（width/height/top/left）→ 与 transform 动画区分。
- 快速开关/返回手势/页面退出时的中断行为。
- `prefers-reduced-motion` / 系统动画缩放设置的等价策略。
"""


def state_machine_md() -> str:
    return """# 交互状态机

> 适用于弹窗链、表单提交、页签切换、登录门等有状态时序的变更。

## 状态

- closed
- opening
- open
- submitting
- success
- error
- closing

## 事件

- open / close / backdrop / back / submit / success / fail / retry

## 转移表

| from | event | guard | action | to |
|---|---|---|---|---|
| closed | open | TODO | TODO | opening |
| opening | animation-end | TODO | TODO | open |
| open | submit | TODO | TODO | submitting |
| submitting | success | TODO | TODO | success |
| submitting | fail | TODO | TODO | error |
| open | close/back | TODO | TODO | closing |

## 关闭 / 返回 / 链式

- 返回键/Esc/手势：
- 关闭后页面与路由状态：
- 链式顺序与前置条件：
- 重复打开 / 快速开关：
- 失败重试 / 数据未加载：
"""


def logic_vectors_readme(args) -> str:
    return f"""# 逻辑对拍向量（{args.key}）

按 `{SKILL}/references/logic-parity.md` 的五步闭环：

1. 选单元（本次改动里的无 UI 纯逻辑函数）；
2. 写契约（canonical 字段 + 容差 + 各端 file:line）；
3. web 出金标（probe 调 web 函数，写 JSON）；
4. 各端跑实际输出；
5. comparator 对账。

本目录只放本次同步新增/修改的单元，不要复制已有向量。
若本次是纯 UI 变更、不涉及逻辑对拍：在 `checklist.md` §4 写理由，并在
`sync_state.py done` 时传 `logic=skipped --skip-reason ...`。
"""


def acceptance_json(args, info, scope) -> dict:
    return {
        "key": args.key,
        "title": args.title,
        "webCommit": info["commit"],
        "scope": scope,
        "states": ["default", "loading", "empty", "error", "popup", "drawer", "keyboard"],
        "devices": {
            "ios": ["小屏 375x667", "标准 390x844", "大屏 430x932"],
            "android": ["320dp", "411dp", "折叠屏"],
            "miniprogram": ["iPhone 6 375x667", "iPhone X 375x812", "安卓大屏 411x823"],
            "settings": ["fontScale 1.3", "landscape", "微信字体变大"],
        },
        "animation": {
            "method": "keyframes",
            "checkpoints": [0, 25, 50, 75, 100],
            "durationTolerance": "5%",
            "shortAnimationTolerance": "1 frame",
        },
        "evidence": [],
        "status": "pending",
    }


def scaffold_state(args, info, scope, routes) -> dict:
    return {
        "version": 1,
        "key": args.key,
        "title": args.title,
        "commit": info["commit"],
        "commit_short": info["commit_short"],
        "branch": info["branch"],
        "dirty": info["dirty"],
        "created_at": common.now_utc(),
        "scope": scope,
        "routes": routes,
        "gates": {g: "pending" for g in ("build", "logic", "ui", "capture")},
    }


# ---- 代码骨架（.template，不参与编译）--------------------------------------

def _swift_template(cls: str, args) -> str:
    return f"""// TODO: 按 web 源 {', '.join(args.web_source) or '<web file:line>'} 复刻
// 不要填假数据；接真实 Repository/ViewModel/路由后再进验收。
import SwiftUI

struct {cls}View: View {{
    var body: some View {{
        Text("TODO: {args.title}")
    }}
}}
"""


def _kotlin_template(cls: str, package: str, args) -> str:
    return f"""package {package}

import androidx.compose.runtime.Composable

// TODO: 按 web 源 {', '.join(args.web_source) or '<web file:line>'} 复刻
// 不要填假数据；接真实 Repository/ViewModel/路由后再进验收。
@Composable
fun {cls}Screen() {{
    // TODO
}}
"""


def _miniprogram_page_templates(slug: str, args) -> dict[str, str]:
    """微信小程序页面四件套（wxml/wxss/js/json）。"""
    web_src = ", ".join(args.web_source) or "<web file:line>"
    return {
        f"{slug}.wxml": f"""<!-- TODO: 按 web 源 {web_src} 复刻结构；不填假数据 -->
<view class="page">
  <text>TODO: {args.title}</text>
</view>
""",
        f"{slug}.wxss": f"""/* TODO: 样式按 ui-parity.md 从 web 换算。
   rpx 换算：目标 rpx = web 视口内 px × 750 ÷ 采证视口宽（如 390）。 */
.page {{
  /* TODO */
}}
""",
        f"{slug}.js": f"""// TODO: 按 web 源 {web_src} 复刻数据逻辑；不填假数据。
// 请求域名必须在小程序后台「合法域名」白名单内（否则真机失败，见 platform-differences.md §5）。
Page({{
  data: {{}},
  onLoad(query) {{
    // TODO: query 即页面参数（对应 web 路由的 query 部分）
  }},
}})
""",
        f"{slug}.json": """{
  "usingComponents": {},
  "navigationBarTitleText": "TODO"
}
""",
    }


def _miniprogram_popup_templates(slug: str, args) -> dict[str, str]:
    """微信小程序弹窗自定义组件四件套（components/<slug>-popup/）。"""
    return {
        "index.wxml": f"""<!-- TODO: 弹窗/抽屉组件骨架；补 trigger、关闭、遮罩、滚动锁、安全区。 -->
<view class="mask" wx:if="{{{{visible}}}}" bind:tap="onMaskTap">
  <view class="sheet" catch:tap="noop">
    <text>TODO: {args.title} 弹窗</text>
  </view>
</view>
""",
        "index.wxss": """/* TODO: 遮罩层级、圆角、安全区（env(safe-area-inset-*)）按 web 采证对齐。 */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); z-index: 100; }
.sheet { /* TODO */ }
""",
        "index.js": """// TODO: 弹窗组件骨架；补打开/关闭事件、嵌套层级、重复打开行为。
Component({
  properties: { visible: { type: Boolean, value: false } },
  methods: {
    onMaskTap() { this.triggerEvent('close'); },
    noop() {},
  },
})
""",
        "index.json": """{
  "component": true,
  "usingComponents": {}
}
""",
    }


def code_skeleton(root: Path, args, scope, app_targets, cfg) -> None:
    platforms = cfg["platforms"]
    cls = common.class_name(args.key, "") or common.class_name(args.title, "SyncPage")
    slug = common.slugify(args.key)
    write(root / "README.md", f"""# 代码骨架（不参与编译）

本次同步的页面/弹窗骨架，全部带 `TODO`，**没有假数据**。
复制到实际目录后，必须接入真实路由/数据/门控；不要用骨架直接过验收。

- iOS 页面：`ios/{cls}View.swift.template` → 复制到 config app.ios.dir 下的 Feature 目录
- Android 页面：`android/{cls}Screen.kt.template` → 复制到 config app.android.dir 对应包
- 微信小程序：`miniprogram/pages/{slug}/` 下 `{slug}.wxml/.wxss/.js/.json` 四件套模板 →
  复制到 config app.miniprogram.dir 下的 pages/{slug}/（去掉 .template 后缀），
  并在 app.json 的 pages 登记（见 RouteWiring.md）
- 其他平台：`<platform>/{cls}.txt.template`（TODO 清单，按平台栈自行起骨架）
- 路由接线：`RouteWiring.md`（必须手工接各端路由表）
""")
    for plat in platforms:
        if plat == "ios":
            write(root / "ios" / f"{cls}View.swift.template",
                  _swift_template(cls, args))
            if "popup" in scope:
                write(root / "ios" / f"{cls}Sheet.swift.template", f"""import SwiftUI

// TODO: 弹窗/抽屉骨架；补 trigger、关闭/返回、遮罩、滚动锁、安全区、IME。
struct {cls}Sheet: View {{
    var onDismiss: () -> Void = {{}}
    var body: some View {{
        Text("TODO: {args.title} sheet")
    }}
}}
""")
        elif plat == "android":
            pkg = (cfg["app"].get("android") or {}).get("package") or "com.example.app"
            write(root / "android" / f"{cls}Screen.kt.template",
                  _kotlin_template(cls, pkg, args))
            if "popup" in scope:
                write(root / "android" / f"{cls}Sheet.kt.template", f"""package {pkg}

import androidx.compose.runtime.Composable

// TODO: 弹窗/抽屉骨架；补 trigger、关闭/返回、遮罩、滚动锁、IME、insets。
@Composable
fun {cls}Sheet(onDismiss: () -> Unit = {{}}) {{
    // TODO
}}
""")
        elif plat == "miniprogram":
            page_dir = root / "miniprogram" / "pages" / slug
            for name, text in _miniprogram_page_templates(slug, args).items():
                write(page_dir / f"{name}.template", text)
            if "popup" in scope:
                comp_dir = root / "miniprogram" / "components" / f"{slug}-popup"
                for name, text in _miniprogram_popup_templates(slug, args).items():
                    write(comp_dir / f"{name}.template", text)
        else:
            write(root / plat / f"{cls}.txt.template",
                  f"TODO: 平台 {plat} 的 {args.title} 骨架 —— 按本平台技术栈创建，"
                  f"参照 web 源 {', '.join(args.web_source) or '<web file:line>'}；不要填假数据。\n")
    write(root / "RouteWiring.md", f"""# 路由接线（手工）

脚手架不自动改路由，避免破坏现有导航。

- 各端工程位置：查 config app.*.dir（ios: {(cfg['app'].get('ios') or {}).get('dir') or '未配置'}；
  android: {(cfg['app'].get('android') or {}).get('dir') or '未配置'}；
  miniprogram: {(cfg['app'].get('miniprogram') or {}).get('dir') or '未配置'}）
- 验收测试缝建议（每页一个直达入口，仅测试生效、零用户影响）：
  - iOS：启动参数 `-acceptance-route <key>`（tab 页用 `-acceptance-tab <n>`）
  - Android：深链 `<scheme>://app/<path>` + `--es acceptance_route <key>`
  - 微信小程序：页面路径直达 —— 开发者工具「编译模式」自定义启动页面+参数，
    或 automator `miniProgram.reLaunch('pages/<slug>/<slug>?<params>')`
  - 其他平台：等价的启动参数/环境变量直达
- 新增页面同步更新 config/page-index.json（pages.index）与各端验收清单：
  - 小程序新页面必须在 app.json 的 pages 数组登记（tab 页另需 tabBar.list），
    未登记的页面无法跳转/直达。
""")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="生成 web 同步任务包/脚手架")
    p.add_argument("--key", required=True, help="页面/改动 key，如 wallet-withdraw")
    p.add_argument("--title", default="", help="人类可读标题")
    p.add_argument("--scope", default="logic,ui", help="逻辑,ui,popup,animation,platform,i18n,assets,api")
    p.add_argument("--routes", default="", help="web 路由，可逗号分隔")
    p.add_argument("--commit", default="", help="web 源 commit；默认当前 HEAD")
    p.add_argument("--web-source", action="append", default=[], help="web 源 file:line，可重复")
    p.add_argument("--app-target", action="append", default=[],
                   help="端侧目标文件 platform=path，可重复（平台取自 config app.platforms）")
    p.add_argument("--out", default="", help="输出目录；默认 <项目根>/.capture/sync/<ts>-<slug>")
    p.add_argument("--code", action="store_true", help="额外生成 code-skeleton/*.template（不参与编译）")
    p.add_argument("--force", action="store_true", help="输出目录已存在时覆盖")
    p.add_argument("--repo", default="", help="覆盖 config web.repo")
    return p


def main() -> int:
    args = build_parser().parse_args()
    if not args.title:
        args.title = args.key
    args.web_source = common.parse_list(args.web_source)
    scope = common.parse_list(args.scope, "logic,ui")
    routes = common.parse_list(args.routes)

    cfg = common.load_config()
    web_repo = Path(args.repo) if args.repo else cfg["web_repo"]
    if not web_repo:
        raise SystemExit("config 缺少 web.repo（web 源仓库路径）；先在 config/web-to-app.yaml 填写。")
    info = common.head_info(web_repo)
    commit = args.commit.strip() or info["commit"]
    if not common.commit_exists(web_repo, commit):
        raise SystemExit(f"web 源 commit 不存在：{commit}")

    app_targets = parse_app_targets(args.app_target, cfg["platforms"])

    slug = common.slugify(args.key)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = Path(args.out) if args.out else cfg["scaffold_dir"] / f"{ts}-{slug}"
    if not out.is_absolute():
        out = cfg["repo_root"] / out
    if out.exists() and any(out.iterdir()) and not args.force:
        raise SystemExit(f"输出目录已存在且非空：{out}（加 --force 覆盖）")
    out.mkdir(parents=True, exist_ok=True)

    info_for_scaffold = {**info, "commit": commit, "commit_short": commit[:12]}
    write(out / "brief.md", brief_md(args, info_for_scaffold, scope, routes,
                                     args.web_source, app_targets, cfg["platforms"]))
    write(out / "checklist.md", checklist_md(args, scope, routes, cfg["platforms"]))
    write(out / "popup-inventory.md", popup_md())
    write(out / "motion-spec.md", motion_md())
    write(out / "state-machine.md", state_machine_md())
    write(out / "logic-vectors" / "README.md", logic_vectors_readme(args))
    common.write_json(out / "file-map.json", {
        "key": args.key,
        "commit": commit,
        "web_sources": args.web_source,
        "app_targets": app_targets,
        "notes": "",
    })
    common.write_json(out / "acceptance.json", acceptance_json(args, info_for_scaffold, scope))
    common.write_json(out / "state.json", scaffold_state(args, info_for_scaffold, scope, routes))
    if args.code:
        code_skeleton(out / "code-skeleton", args, scope, app_targets, cfg)

    print(f"同步任务包已生成：{out}")
    for name in ["brief.md", "file-map.json", "checklist.md", "popup-inventory.md", "motion-spec.md",
                 "state-machine.md", "logic-vectors/README.md", "acceptance.json", "state.json"]:
        print(f"  {name}")
    if args.code:
        print("  code-skeleton/（.template，不参与编译）")
    print("\n下一步：")
    print(f"  python3 {SKILL / 'scripts' / 'sync_state.py'} begin --scope {','.join(scope)}")
    print(f"  填写 {out / 'brief.md'} / file-map / popup-inventory / motion-spec / state-machine")
    print("  完成后：")
    print(f"  python3 {SKILL / 'scripts' / 'sync_state.py'} done \\")
    print("    --gates build=pass,logic=pass,ui=pass,capture=pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

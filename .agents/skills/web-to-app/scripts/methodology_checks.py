#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""methodology_checks.py — web-to-app 方法论机械检查（skill 工具，只读业务代码）。

对应 `references/source-to-contract.md` §5 的检查目录与
`docs/methodology-ledger.md` 的 ML 行。每个子命令都是可执行门禁，
失败时打印 `file:line` / 台账行 / 字段路径，退出码：
    0 = PASS（或仅有 REVIEW 且未 --strict）
    1 = FAIL（有硬失败，或 --strict 下 REVIEW 计入）
    2 = 环境/参数错误

子命令：
    baseline      ML-1  同步基线状态审计（可达性/审计字段/in_progress 脱管/门禁语义）
    state-ledger  ML-3  状态文件 last_synced ↔ 差异台账基线行镜像一致性
    diff-ledger   ML-7  代码 APP-DIFF 标记 ↔ 台账行双向对账 + kind 词表 + pending 挂账
    page-index    —     页面索引 JSON 结构对账（key 唯一/格式/平台覆盖）
    backlog       —     PARITY_BACKLOG 编号唯一/状态词表/证据非空
    selftest      M10   临时树正/负控自检（覆盖上述全部检测逻辑）

用法示例：
    python3 .agents/skills/web-to-app/scripts/methodology_checks.py baseline
    python3 .agents/skills/web-to-app/scripts/methodology_checks.py diff-ledger --strict
    python3 .agents/skills/web-to-app/scripts/methodology_checks.py selftest

约定：工具只读，不改业务代码；每个子命令结尾打印
`VERDICT <cmd>: PASS|FAIL (fail=N review=M pass=K)`，轮次报告/PR 必须能贴出该行。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import skill_common as common  # noqa: E402

SKILL_DIR = Path(__file__).resolve().parents[1]

# 扫描差异标记时跳过的目录（web 源仓子目录、构建产物、采证输出等）
PRUNE_DIRS = {
    ".git", "node_modules", "build", "Build", "DerivedData", "Pods",
    "gradle", ".gradle", "dist", ".capture", ".openidea", ".commandcode",
    "ZCode", "protogen", "__pycache__", "miniprogram_npm", ".idea",
    "ZCodeMobile.xcodeproj", "spark-output", "docs",
}

PLATFORM_EXTS = {
    "ios": (".swift",),
    "android": (".kt", ".java"),
    "miniprogram": (".js", ".ts", ".wxml"),
}
GENERIC_EXTS = (".swift", ".kt", ".java", ".js", ".ts", ".wxml")

# BACKLOG 状态词表（主干流转 + 合法衍生态，2026-11-20 随 methodology_checks 首跑正式化：
# 真实台账存在「已解除阻塞」「如实不做」，状态 cell 以词表任一词开头即合规，允许括号注记）
BACKLOG_STATUSES = ("待修", "修复中", "部分已修", "已修", "已完成", "已解除", "如实不做")


class Report:
    """单次检查的结论累积器。"""

    def __init__(self, strict: bool = False) -> None:
        self.fails: list[str] = []
        self.reviews: list[str] = []
        self.passes: list[str] = []
        self.strict = strict

    def fail(self, msg: str) -> None:
        self.fails.append(msg)

    def review(self, msg: str) -> None:
        self.reviews.append(msg)

    def ok(self, msg: str) -> None:
        self.passes.append(msg)

    def verdict(self, name: str) -> int:
        status = "FAIL" if self.fails else ("REVIEW" if self.reviews else "PASS")
        print(f"VERDICT {name}: {status} (fail={len(self.fails)} review={len(self.reviews)} pass={len(self.passes)})")
        for msg in self.fails:
            print(f"  FAIL  {msg}")
        for msg in self.reviews:
            print(f"  REVIEW {msg}")
        return 1 if (self.fails or (self.strict and self.reviews)) else 0


def load_state(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except Exception as exc:
        raise SystemExit(f"状态文件不可解析：{path}：{exc}")


def git_revparse(repo: Path, value: str) -> str:
    try:
        res = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "--verify", "--quiet", f"{value}^{{commit}}"],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
        )
        return res.stdout.strip()
    except Exception:
        return ""


# --------------------------------------------------------------------------
# baseline：同步基线状态审计（ML-1）
# --------------------------------------------------------------------------

def check_baseline(cfg: dict, rep: Report) -> int:
    state_path = Path(cfg["state_file"])
    if not state_path.is_file():
        rep.review(f"状态文件不存在（冷启动项目正常，首轮 done 后复跑）：{state_path}")
        return rep.verdict("baseline")

    state = load_state(state_path)
    last = state.get("last_synced")
    if not isinstance(last, dict):
        rep.review("尚无 last_synced 基线（冷启动项目正常；begin/done 后复跑）")
    else:
        sha = str(last.get("commit") or "")
        if not re.fullmatch(r"[0-9a-f]{7,40}", sha or ""):
            rep.fail(f"last_synced.commit 缺失或非 SHA：{sha!r}")
        else:
            web_repo = cfg.get("web_repo")
            if web_repo and Path(web_repo).is_dir():
                if git_revparse(Path(web_repo), sha):
                    rep.ok(f"基线 commit 在 web 源仓可达：{sha[:12]}")
                else:
                    rep.fail(f"基线 commit 在 web 源仓不可达：{sha[:12]}（web_repo={web_repo}）")
            else:
                rep.review(f"web.repo 未配置或不存在，跳过可达性校验（sha={sha[:12]}）")

        for field in ("commit_source", "source_head", "head_lagging"):
            if field in last:
                rep.ok(f"审计字段在位：{field}={last.get(field)!r}")
            else:
                rep.review(
                    f"审计字段缺失：{field}（2026-11-20 前的历史记录；下次 done 显式传 --commit 即补记，或 reconcile 自校正）"
                )

        gates = last.get("gates") or {}
        bad = {k: v for k, v in gates.items() if v not in ("pass", "skipped", "n/a")}
        if bad:
            rep.fail(f"基线门禁存在非通过项：{bad}（全门通过才允许推进基线）")
        elif gates:
            rep.ok(f"基线门禁全通过：{', '.join(f'{k}={v}' for k, v in gates.items())}")
        if gates.get("logic") in ("skipped", "n/a") and not str(last.get("skip_reason") or "").strip():
            rep.fail("logic 门禁为 skipped/n/a 但基线记录缺 skip_reason（违反 done 纪律）")

    current = state.get("in_progress")
    if current:
        started = str(current.get("started_at") or "")
        age_hours: float | None = None
        try:
            started_dt = datetime.fromisoformat(started.replace("Z", "+00:00"))
            age_hours = (datetime.now(timezone.utc) - started_dt).total_seconds() / 3600.0
        except Exception:
            pass
        if age_hours is None:
            rep.review(f"in_progress 残留且 started_at 不可解析：base={current.get('base_commit_short')}")
        elif age_hours > 24:
            rep.fail(
                f"in_progress 脱管：base={current.get('base_commit_short')} 已挂起 {age_hours:.0f} 小时"
                "（先 done 或 abort，避免基线状态悬置）"
            )
        else:
            rep.review(f"in_progress 进行中（{age_hours:.1f}h）：base={current.get('base_commit_short')}")
    else:
        rep.ok("无 in_progress 残留")

    return rep.verdict("baseline")


# --------------------------------------------------------------------------
# state-ledger：状态文件 ↔ 台账基线行镜像一致性（ML-3）
# --------------------------------------------------------------------------

LEDGER_BASE_ROW_RE = re.compile(r"^\|\s*(?P<key>[A-Za-z0-9_\u4e00-\u9fff-]+)\s*\|\s*(?P<value>[^|]+)\|", re.MULTILINE)


def read_ledger_base_row(ledger_path: Path, key: str) -> tuple[str, str]:
    """返回 (基线行里的首个 hex token, 上次同步范围行文本)；缺失返回空。"""
    if not ledger_path.is_file():
        return "", ""
    text = ledger_path.read_text(encoding="utf-8")
    base_sha, scope_line = "", ""
    for m in LEDGER_BASE_ROW_RE.finditer(text):
        if m.group("key").strip() == key:
            sha_m = re.search(r"[0-9a-f]{7,40}", m.group("value"))
            base_sha = sha_m.group(0) if sha_m else ""
        elif m.group("key").strip() == "上次同步范围":
            scope_line = m.group("value").strip()
    return base_sha, scope_line


def check_state_ledger(cfg: dict, rep: Report) -> int:
    ledger_path = Path(cfg["ledger"])
    key = str(cfg.get("ledger_key") or "web-sync-base")
    base_sha, scope_line = read_ledger_base_row(ledger_path, key)

    if not ledger_path.is_file():
        rep.fail(f"差异台账不存在：{ledger_path}")
        return rep.verdict("state-ledger")
    if not base_sha:
        rep.fail(f"台账缺 {key} 基线行（done 应镜像写入）：{ledger_path}")
        return rep.verdict("state-ledger")
    rep.ok(f"台账基线行在位：{key} = {base_sha[:12]}")

    state = load_state(Path(cfg["state_file"]))
    last = state.get("last_synced")
    if not isinstance(last, dict) or not last.get("commit"):
        rep.review("状态文件尚无 last_synced，跳过一致性比对（冷启动正常）")
    else:
        state_sha = str(last["commit"])
        if state_sha.startswith(base_sha) or base_sha.startswith(state_sha[:12]):
            rep.ok(f"状态文件与台账基线一致：{state_sha[:12]}")
        else:
            rep.fail(
                f"基线镜像脱钩：state={state_sha[:12]} ledger={base_sha[:12]}"
                "（sync_state.py done/reconcile 会同时镜像两处；手工改过其一即违反纪律）"
            )

    if scope_line:
        rep.ok(f"台账「上次同步范围」在位：{scope_line[:60]}…")
    else:
        rep.review("台账缺「上次同步范围」行（建议补记，便于追溯 scope）")

    return rep.verdict("state-ledger")


# --------------------------------------------------------------------------
# diff-ledger：代码 APP-DIFF 标记 ↔ 台账行双向对账（ML-7）
# --------------------------------------------------------------------------

def scan_code_markers(cfg: dict, prefix: str) -> dict[str, list[str]]:
    """扫描三端源码中的 `<prefix>[D###]` 标记，返回 {D###: [file:line, ...]}。"""
    marker_re = re.compile(rf"{re.escape(prefix)}\[D(\d+)\]")
    root = Path(cfg["repo_root"])
    found: dict[str, list[str]] = {}
    for plat in cfg.get("platforms") or ["ios", "android", "miniprogram"]:
        plat_cfg = (cfg.get("app") or {}).get(plat) or {}
        base = plat_cfg.get("dir") or root / plat
        base = Path(base)
        if not base.is_dir():
            continue
        exts = PLATFORM_EXTS.get(plat, GENERIC_EXTS)
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix not in exts:
                continue
            if any(part in PRUNE_DIRS for part in path.relative_to(root).parts[:-1]):
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except Exception:
                continue
            for lineno, line in enumerate(text.splitlines(), 1):
                m = marker_re.search(line)
                if m:
                    found.setdefault(f"D{m.group(1)}", []).append(f"{path.relative_to(root)}:{lineno}")
    return found


def parse_ledger_rows(ledger_path: Path) -> dict[str, dict]:
    """解析台账表：{D###: {kind, backlog, status, line}}。只认以 `| D###` 开头的行。"""
    rows: dict[str, dict] = {}
    if not ledger_path.is_file():
        return rows
    row_re = re.compile(r"^\|\s*(D\d+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|")
    for lineno, line in enumerate(ledger_path.read_text(encoding="utf-8").splitlines(), 1):
        m = row_re.match(line)
        if not m:
            continue
        kind_cell = m.group(2).strip()
        backlog_cell = m.group(7).strip()
        status_cell = m.group(8).strip()
        kind_m = re.match(r"~{0,2}([a-z-]+)", kind_cell)
        rows[m.group(1)] = {
            "kind": kind_m.group(1) if kind_m else "",
            "backlog": backlog_cell,
            "status": status_cell,
            "line": lineno,
            "raw": line.strip()[:120],
        }
    return rows


def check_diff_ledger(cfg: dict, rep: Report) -> int:
    ledger_path = Path(cfg["ledger"])
    prefix = str(cfg.get("diff_prefix") or "APP-DIFF")
    allowed_kinds = set(((cfg.get("raw") or {}).get("diff_language") or {}).get("kinds") or {"platform", "business", "pending"})

    rows = parse_ledger_rows(ledger_path)
    markers = scan_code_markers(cfg, prefix)
    if not rows:
        rep.fail(f"台账未解析到任何 D 行：{ledger_path}")
    if not markers:
        rep.review("三端源码未扫描到任何差异标记（若当前确无刻意差异则正常）")

    active_ids = {d for d, r in rows.items() if "作废" not in r["status"]}
    deprecated_ids = set(rows) - active_ids

    # 方向一：台账「生效」行必须在代码内有标记（两级缺一不可）
    for d in sorted(active_ids):
        row = rows[d]
        if d in markers:
            rep.ok(f"{d} 生效且代码有标记（{len(markers[d])} 处）")
        else:
            rep.fail(f"{d} 台账「生效」但代码无 {prefix} 标记（台账脱钩，{ledger_path.name}:{row['line']}）")
        if row["kind"] and row["kind"] not in allowed_kinds:
            rep.fail(f"{d} kind={row['kind']!r} 不在 diff_language.kinds（{sorted(allowed_kinds)}）")
        if row["kind"] == "pending" and not re.search(r"P-\d+", row["backlog"]):
            rep.fail(f"{d} kind=pending 但未挂 PARITY_BACKLOG 编号（BACKLOG 列={row['backlog']!r}）")

    # 方向二：代码标记必须在台账登记（任何状态）
    for d in sorted(markers):
        if d not in rows:
            rep.fail(f"{d} 代码有标记但台账未登记：{', '.join(markers[d][:3])}（两级缺一不可）")
        elif d in deprecated_ids:
            rep.review(f"{d} 台账已作废但代码仍有标记：{', '.join(markers[d][:3])}（确认是残留还是漏删）")

    rep.ok(f"扫描范围：三端 {len({p for p in (cfg.get('platforms') or [])})} 平台源码目录；台账 {len(rows)} 行；代码标记 {len(markers)} 个编号")
    return rep.verdict("diff-ledger")


# --------------------------------------------------------------------------
# page-index：页面索引结构对账
# --------------------------------------------------------------------------

def check_page_index(cfg: dict, rep: Report) -> int:
    index_path = Path(cfg.get("page_index") or (Path(cfg["repo_root"]) / "config/page-index.json"))
    try:
        data = json.loads(index_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        rep.fail(f"页面索引不存在：{index_path}")
        return rep.verdict("page-index")
    except Exception as exc:
        rep.fail(f"页面索引不可解析：{index_path}：{exc}")
        return rep.verdict("page-index")
    rep.ok(f"页面索引可解析：{index_path}")

    pages = data.get("pages") if isinstance(data, dict) else data
    if not isinstance(pages, list) or not pages:
        rep.fail("页面索引缺 pages 数组或为空")
        return rep.verdict("page-index")

    platforms = cfg.get("platforms") or []
    seen: dict[str, int] = {}
    missing_platform = 0
    for p in pages:
        if not isinstance(p, dict):
            rep.fail(f"pages 含非对象元素：{p!r}")
            continue
        key = str(p.get("key") or "")
        if key in seen:
            rep.fail(f"页面 key 重复：{key}（第 {seen[key]} 次已出现）")
        seen[key] = seen.get(key, 0) + 1
        if not re.match(r"^\d{3}-", key):
            rep.fail(f"页面 key 不符合 NNN-名称 约定：{key!r}")
        if not str(p.get("route") or "").strip():
            rep.fail(f"页面缺 route：{key}")
        app = p.get("app")
        if not isinstance(app, dict) or not any(str(v or "").strip() for v in app.values()):
            rep.fail(f"页面缺 app 验收缝映射：{key}")
        elif platforms and not any(plat in app and str(app[plat] or "").strip() for plat in platforms):
            rep.review(f"页面未覆盖全部配置平台 {platforms}：{key}（当前仅 {sorted(app)}）")
            missing_platform += 1

    rep.ok(f"页面共 {len(pages)} 个，key 唯一性/格式/路由/缝映射校验完成（未全平台覆盖 {missing_platform} 个）")
    return rep.verdict("page-index")


# --------------------------------------------------------------------------
# backlog：PARITY_BACKLOG 词表与结构
# --------------------------------------------------------------------------

def check_backlog(cfg: dict, rep: Report, backlog_path: Path | None = None) -> int:
    path = backlog_path or (Path(cfg["repo_root"]) / "docs/PARITY_BACKLOG.md")
    if not path.is_file():
        rep.review(f"PARITY_BACKLOG 不存在（尚无未对齐项登记）：{path}")
        return rep.verdict("backlog")

    text = path.read_text(encoding="utf-8")
    row_re = re.compile(r"^\|\s*(P-\d+)\s*\|(.*)$")
    ids: dict[str, int] = {}
    parsed = unparsed = 0
    for lineno, line in enumerate(text.splitlines(), 1):
        m = row_re.match(line)
        if not m:
            continue
        pid = m.group(1)
        if pid in ids:
            rep.fail(f"BACKLOG 编号重复：{pid}（首见第 {ids[pid]} 行，当前第 {lineno} 行）")
        ids[pid] = lineno
        cells = [c.strip() for c in m.group(2).split("|")]
        # 行尾 `|` 会产生尾随空 cell（md 表格语法）；先剥掉再数列，否则状态列判成空
        while cells and cells[-1] == "":
            cells.pop()
        # 行格式：波次|页面 key|平台|问题|证据|状态 → 至少 6 列；跨行单元格行可能列数不足
        if len(cells) >= 6:
            parsed += 1
            status = cells[-1]
            hit = next((s for s in BACKLOG_STATUSES if status.startswith(s)), "")
            if not status:
                rep.fail(f"{pid} 状态列为空（状态流转必须有值）")
            elif not hit:
                rep.fail(f"{pid} 状态不在词表 {BACKLOG_STATUSES}：{status!r}（{path.name}:{lineno}）")
            elif hit in ("如实不做", "部分已修") and len(status) <= len(hit):
                rep.fail(f"{pid} 状态={hit} 但未注记剩余部分/堵点/复验条件（脱管禁令）")
            evidence = cells[-2]
            if not evidence or evidence == "—":
                rep.fail(f"{pid} 证据列为空（登记规则：无证据不登记裁决）")
        else:
            unparsed += 1

    if unparsed:
        rep.review(f"{unparsed} 条 BACKLOG 行因表格跨行/列数不足未做列级校验（人工核对）")
    rep.ok(f"BACKLOG 共 {len(ids)} 条编号唯一；列级校验 {parsed} 条")
    return rep.verdict("backlog")


# --------------------------------------------------------------------------
# selftest：临时树正/负控（门自身先自证，M10）
# --------------------------------------------------------------------------

def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def run_selftest() -> int:
    checks: list[tuple[str, bool]] = []
    tmp = Path(tempfile.mkdtemp(prefix="wta-selftest-"))
    try:
        # --- 公共样本 ---
        _write(tmp / "config/web-to-app.yaml", (
            "web:\n  repo: webrepo\n"
            "diff_language:\n  prefix: APP-DIFF\n"
            "  kinds:\n    platform: x\n    business: x\n    pending: x\n"
            "sync:\n  state_file: docs/parity-sync-state.json\n  ledger: docs/parity-diff-ledger.md\n"
            "  ledger_key: web-sync-base\n"
            "pages:\n  index: config/page-index.json\n"
            "app:\n  platforms: ios,android\n"
        ))

        def make_cfg() -> dict:
            cfg = common.load_config(tmp)
            cfg["web_repo"] = tmp / "webrepo"
            cfg["repo_root"] = tmp
            return cfg

        # --- diff-ledger：负控（脱钩 + 未登记 + pending 未挂账 + 未知 kind） ---
        _write(tmp / "docs/parity-diff-ledger.md", (
            "# 差异台账\n\n## 台账\n\n| 编号 | kind | 页面 | web | App | 理由 | BACKLOG | 状态 |\n"
            "|---|---|---|---|---|---|---|---|\n"
            "| D001 | platform | a | w | i | r | — | 生效 |\n"
            "| D002 | pending | b | w | i | r | — | 生效 |\n"
            "| D003 | weird | c | w | i | r | — | 生效 |\n"
            "| D004 | platform | d | w | i | r | — | 作废 |\n"
        ))
        _write(tmp / "ios/A.swift", "// APP-DIFF[D001] platform: ok\n// APP-DIFF[D999] platform: 未登记\n")
        rep = Report()
        check_diff_ledger(make_cfg(), rep)
        checks.append(("diff-ledger 负控捕获脱钩/未登记/pending 未挂账/未知 kind", len(rep.fails) >= 4))

        # --- diff-ledger：正控（全部对齐） ---
        _write(tmp / "docs/parity-diff-ledger.md", (
            "# 差异台账\n\n## 台账\n\n| 编号 | kind | 页面 | web | App | 理由 | BACKLOG | 状态 |\n"
            "|---|---|---|---|---|---|---|---|\n"
            "| D001 | platform | a | w | i | r | — | 生效 |\n"
            "| D002 | pending | b | w | i | r | P-001 | 生效 |\n"
            "| D004 | platform | d | w | i | r | — | 作废 |\n"
        ))
        _write(tmp / "ios/A.swift", "// APP-DIFF[D001] platform: ok\n// APP-DIFF[D002] pending: ok (P-001)\n")
        rep = Report()
        check_diff_ledger(make_cfg(), rep)
        checks.append(("diff-ledger 正控（登记+标记+pending 挂账全对齐）", len(rep.fails) == 0))

        # --- page-index：负控（key 重复 + 缺 route）与正控 ---
        _write(tmp / "config/page-index.json", json.dumps({
            "pages": [
                {"key": "001-首页", "route": "/", "app": {"ios": "home", "android": "home"}},
                {"key": "001-首页", "route": "/", "app": {}},
            ]
        }, ensure_ascii=False))
        rep = Report()
        check_page_index(make_cfg(), rep)
        neg_page = len(rep.fails) >= 2
        _write(tmp / "config/page-index.json", json.dumps({
            "pages": [
                {"key": "001-首页", "route": "/", "app": {"ios": "home"}},
                {"key": "002-设置", "route": "/settings", "app": {"ios": "settings", "android": "settings"}},
            ]
        }, ensure_ascii=False))
        rep = Report()
        check_page_index(make_cfg(), rep)
        checks.append(("page-index 负控/正控", neg_page and len(rep.fails) == 0))

        # --- backlog：负控（重复编号 + 状态词表外 + 证据空 + 如实不做无复验）与正控 ---
        _write(tmp / "docs/PARITY_BACKLOG.md", (
            "| 编号 | 波次 | 页面 | 平台 | 问题 | 证据 | 状态 |\n|---|---|---|---|---|---|---|\n"
            "| P-001 | W1 | 001 | ios | a | x.png | 待修 |\n"
            "| P-001 | W1 | 001 | ios | dup | — | 待修 |\n"
            "| P-002 | W1 | 002 | ios | b | y.png | 莫名状态 |\n"
            "| P-003 | W1 | 003 | ios | c | z.png | 如实不做 |\n"
        ))
        rep = Report()
        check_backlog(make_cfg(), rep)
        neg_backlog = len(rep.fails) >= 4
        _write(tmp / "docs/PARITY_BACKLOG.md", (
            "| 编号 | 波次 | 页面 | 平台 | 问题 | 证据 | 状态 |\n|---|---|---|---|---|---|---|\n"
            "| P-001 | W1 | 001 | ios | a | x.png | 已修+实拍验证 |\n"
            "| P-002 | W1 | 002 | ios | b | y.png | 已解除阻塞（001 已接） |\n"
            "| P-003 | W1 | 003 | ios | c | z.png | 如实不做（待通知基建） |\n"
        ))
        rep = Report()
        check_backlog(make_cfg(), rep)
        checks.append(("backlog 负控/正控（含衍生态词表）", neg_backlog and len(rep.fails) == 0))

        # --- state-ledger：镜像一致 / 脱钩 ---
        sha = "a" * 40
        _write(tmp / "docs/parity-sync-state.json", json.dumps(
            {"version": 1, "last_synced": {"commit": sha, "gates": {"build": "pass"}}, "in_progress": None}))
        _write(tmp / "docs/parity-diff-ledger.md", f"# L\n\n## web 同步基线\n\n| 字段 | 值 |\n|---|---|\n| web-sync-base | {sha} (2026-11-20) |\n| 上次同步范围 | logic |\n")
        rep = Report()
        check_state_ledger(make_cfg(), rep)
        pos_mirror = len(rep.fails) == 0
        _write(tmp / "docs/parity-diff-ledger.md", f"# L\n\n## web 同步基线\n\n| 字段 | 值 |\n|---|---|\n| web-sync-base | {'b' * 40} (2026-11-20) |\n")
        rep = Report()
        check_state_ledger(make_cfg(), rep)
        checks.append(("state-ledger 一致/脱钩", pos_mirror and any("镜像脱钩" in f for f in rep.fails)))

        # --- baseline：负控（不可达 sha + 脱管 in_progress）与正控 ---
        subprocess.run(["git", "init", "-q", str(tmp / "webrepo")], check=True)
        subprocess.run(["git", "-C", str(tmp / "webrepo"), "-c", "user.email=t@t", "-c", "user.name=t",
                        "commit", "--allow-empty", "-qm", "init"], check=True)
        real_sha = subprocess.run(["git", "-C", str(tmp / "webrepo"), "rev-parse", "HEAD"],
                                  capture_output=True, text=True, check=True).stdout.strip()
        stale = (datetime.now(timezone.utc) - timedelta(hours=48)).isoformat()
        _write(tmp / "docs/parity-sync-state.json", json.dumps({
            "version": 1, "last_synced": {"commit": "c" * 40, "gates": {"build": "pass"},
                                          "commit_source": "explicit", "source_head": real_sha, "head_lagging": False},
            "in_progress": {"base_commit_short": "abc", "started_at": stale},
        }))
        rep = Report()
        check_baseline(make_cfg(), rep)
        neg_base = any("不可达" in f for f in rep.fails) and any("脱管" in f for f in rep.fails)
        _write(tmp / "docs/parity-sync-state.json", json.dumps({
            "version": 1, "last_synced": {"commit": real_sha, "gates": {"build": "pass", "logic": "pass"},
                                          "commit_source": "explicit", "source_head": real_sha, "head_lagging": False},
            "in_progress": None,
        }))
        rep = Report()
        check_baseline(make_cfg(), rep)
        checks.append(("baseline 负控（不可达+脱管）/正控", neg_base and len(rep.fails) == 0))

        passed = sum(1 for _, ok in checks if ok)
        for name, ok in checks:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}")
        print(f"SELFTEST {'PASS' if passed == len(checks) else 'FAIL'}: {passed}/{len(checks)} assertions")
        return 0 if passed == len(checks) else 1
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="web-to-app 方法论机械检查（只读）")
    parser.add_argument("--root", default="", help="仓库根目录（缺省按 skill 位置向上查找）")
    parser.add_argument("--strict", action="store_true", help="REVIEW 也计入失败")
    parser.add_argument("--backlog", default="", help="覆盖 PARITY_BACKLOG 路径")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("baseline", help="同步基线状态审计（ML-1）")
    sub.add_parser("state-ledger", help="状态文件 ↔ 台账基线行镜像一致性（ML-3）")
    sub.add_parser("diff-ledger", help="代码 APP-DIFF 标记 ↔ 台账双向对账（ML-7）")
    sub.add_parser("page-index", help="页面索引 JSON 结构对账")
    sub.add_parser("backlog", help="PARITY_BACKLOG 词表与结构")
    sub.add_parser("selftest", help="临时树正/负控自检")
    args = parser.parse_args()

    if args.cmd == "selftest":
        return run_selftest()

    cfg = common.load_config(args.root or None)
    rep = Report(strict=args.strict)
    if args.cmd == "baseline":
        return check_baseline(cfg, rep)
    if args.cmd == "state-ledger":
        return check_state_ledger(cfg, rep)
    if args.cmd == "diff-ledger":
        return check_diff_ledger(cfg, rep)
    if args.cmd == "page-index":
        return check_page_index(cfg, rep)
    if args.cmd == "backlog":
        return check_backlog(cfg, rep, Path(args.backlog) if args.backlog else None)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

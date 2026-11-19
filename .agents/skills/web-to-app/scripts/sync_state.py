#!/usr/bin/env python3
"""web-to-app 同步基线状态：记录/推进上次同步的 web 源 commit。

用法：
  python3 sync_state.py status [--json]
  python3 sync_state.py begin --scope logic,ui,popup,animation [--note ...] [--allow-dirty] \
      [--commit <本轮同步目标>]
  python3 sync_state.py abort
  python3 sync_state.py done --gates build=pass,logic=pass,ui=pass,capture=pass \
      [--skip-reason "UI-only 变更，逻辑对拍不适用"] [--note ...] [--allow-dirty] [--accept-head-move] \
      [--commit <本轮同步目标>]
  python3 sync_state.py reconcile --commit <本轮同步目标> [--reason ...] [--force]

设计：
  - begin 只记录 in_progress，不推进 last_synced；
  - done 校验门禁后才把 in_progress 提升为 last_synced，并镜像到差异台账；
  - 失败/部分完成不会推进基线，避免“同步了一半但基线已走”；
  - 纯 UI 变更可把 logic 标 skipped/n/a，但必须给 --skip-reason；
  - --commit 显式指定“本轮实际同步到的 web commit”，不依赖源仓本地 HEAD
    （源仓只读时 HEAD 可能滞后于 fetch 到的远端分支，直接记 HEAD 会把基线记错）；
  - reconcile 用于事后自校正：仅允许向前（旧基线是新目标的祖先），
    回退/无关 commit 需 --force；校正会同步镜像差异台账。
"""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import skill_common as common  # noqa: E402

DEFAULT_STATE = {"version": 1, "last_synced": None, "in_progress": None}
ALLOWED_GATE_STATUS = {"pass", "skipped", "n/a", "fail"}


def load_state(path: Path) -> dict:
    data = common.read_json(path, copy.deepcopy(DEFAULT_STATE))
    if not isinstance(data, dict):
        raise SystemExit(f"状态文件格式错误：{path}")
    data.setdefault("version", 1)
    data.setdefault("last_synced", None)
    data.setdefault("in_progress", None)
    return data


def save_state(path: Path, state: dict) -> None:
    common.write_json(path, state)


def update_ledger(path: Path, key: str, commit: str, scope: list[str], note: str = "") -> None:
    """把上次同步基线镜像进差异台账头部（台账不存在则建骨架）。"""
    date = common.now_utc()[:10]
    value = f"{commit} ({date})"
    scope_value = ", ".join(scope) if scope else "—"
    if note:
        scope_value = f"{scope_value}；{note}"
    row = f"| {key} | {value} |"
    scope_row = f"| 上次同步范围 | {scope_value} |"
    heading = "## web 同步基线"

    if path.is_file():
        text = path.read_text(encoding="utf-8")
    else:
        text = (f"# 刻意差异台账（{common.SKILL_DIR.name} 差异标记 Ledger）\n\n"
                f"{heading}\n\n"
                "| 字段 | 值 |\n|---|---|\n")
    lines = text.splitlines()
    key_re = re.compile(rf"^\|\s*{re.escape(key)}\s*\|")
    scope_re = re.compile(r"^\|\s*上次同步范围\s*\|")
    found_key = False
    found_scope = False
    for i, line in enumerate(lines):
        if key_re.match(line):
            lines[i] = row
            found_key = True
        elif scope_re.match(line):
            lines[i] = scope_row
            found_scope = True

    if not found_key:
        insert_at = None
        for i, line in enumerate(lines):
            if line.strip().startswith(heading):
                j = i + 1
                while j < len(lines) and not lines[j].strip().startswith("|"):
                    j += 1
                if j < len(lines):
                    k = j
                    while k < len(lines) and lines[k].strip().startswith("|"):
                        k += 1
                    insert_at = k
                else:
                    insert_at = i + 2
                break
        if insert_at is None:
            lines += ["", heading, "", "| 字段 | 值 |", "|---|---|"]
            insert_at = len(lines)
        lines.insert(insert_at, row)

    if not found_scope:
        # 新台账没有“上次同步范围”行：插在 base 行之后。
        for i, line in enumerate(lines):
            if key_re.match(line):
                lines.insert(i + 1, scope_row)
                break

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _web_repo(args, cfg) -> Path:
    repo = Path(args.repo) if args.repo else cfg["web_repo"]
    if not repo:
        raise SystemExit("config 缺少 web.repo（web 源仓库路径）；先在 config/web-to-app.yaml 填写。")
    return repo


def resolve_commit(repo: Path, value: str, flag: str = "--commit") -> str:
    """把 --commit 入参解析成完整 SHA，并确认对象存在于 web 源仓。

    显式 commit 是“本次实际同步的目标”，与 web 源仓本地 HEAD 解耦：
    源仓按纪律只读、HEAD 可能滞后于 fetch 到的远端分支。
    """
    value = (value or "").strip()
    if not value:
        raise SystemExit(f"{flag} 不能为空。")
    resolved = common.git(repo, "rev-parse", "--verify", "--quiet", f"{value}^{{commit}}", check=False)
    if not resolved:
        raise SystemExit(f"{flag} 指定 commit 在 web 源仓不可达：{value}")
    return resolved


def is_ancestor(repo: Path, older: str, newer: str) -> bool:
    import subprocess

    if not older or not newer:
        return False
    try:
        subprocess.run(
            ["git", "-C", str(repo), "merge-base", "--is-ancestor", older, newer],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return True
    except Exception:
        return False


def cmd_begin(args, cfg) -> int:
    repo = _web_repo(args, cfg)
    info = common.head_info(repo)
    source_head, source_head_short = info["commit"], info["commit_short"]
    target = resolve_commit(repo, args.commit) if getattr(args, "commit", "") else info["commit"]
    if target != source_head:
        info["commit"], info["commit_short"] = target, target[:12]
    if info["dirty"] and not args.allow_dirty:
        print(
            f"web 源仓库工作区有未提交改动（{info['dirty_count']} 个文件）。\n"
            "同步基线必须是可复现的 commit：先让 web 侧提交/清理，或加 --allow-dirty（会记录 dirty=true）。",
            file=sys.stderr,
        )
        return 2

    state_path = Path(args.state_file or cfg["state_file"])
    state = load_state(state_path)
    scope = common.parse_list(args.scope)
    if not scope:
        print("至少给一个 --scope，例如 --scope logic,ui,popup,animation", file=sys.stderr)
        return 2

    current = state.get("in_progress")
    if current:
        same = current.get("base_commit") == info["commit"]
        if same and not args.force:
            current.update({"scope": scope, "note": args.note or current.get("note", "")})
            save_state(state_path, state)
            print(f"已更新 in_progress（base={info['commit_short']}）scope={','.join(scope)}")
            return 0
        if not args.force:
            print(
                "已有 in_progress：\n"
                f"  base={current.get('base_commit_short')} scope={','.join(current.get('scope', []))}\n"
                f"当前 HEAD={info['commit_short']}。\n"
                "先 `sync_state.py done` 或 `sync_state.py abort`，或加 --force 覆盖。",
                file=sys.stderr,
            )
            return 2

    state["in_progress"] = {
        "base_commit": info["commit"],
        "base_commit_short": info["commit_short"],
        "branch": info["branch"],
        "dirty": info["dirty"],
        "dirty_count": info["dirty_count"],
        "dirty_files": info["dirty_files"],
        "started_at": common.now_utc(),
        "scope": scope,
        "note": args.note or "",
        "operator": common.operator(),
        "commit_source": "explicit" if getattr(args, "commit", "") else "head",
        "source_head": source_head,
        "source_head_short": source_head_short,
        "head_lagging": target != source_head and is_ancestor(repo, source_head, target),
    }
    save_state(state_path, state)
    print(f"已记录同步起点：{info['commit']} ({info['branch']})")
    if target != source_head:
        rel = "滞后于目标" if is_ancestor(repo, source_head, target) else "与目标不同"
        print(f"  （显式同步目标；web 源本地 HEAD={source_head_short}，{rel}）")
    print(f"  scope={','.join(scope)}  state={state_path}")
    print("下一步：生成同步任务包（sync_scaffold.py），完成后再 done。")
    return 0


def cmd_abort(args, cfg) -> int:
    state_path = Path(args.state_file or cfg["state_file"])
    state = load_state(state_path)
    current = state.get("in_progress")
    if not current:
        print("没有 in_progress。")
        return 0
    state["in_progress"] = None
    save_state(state_path, state)
    print(f"已放弃 in_progress（base={current.get('base_commit_short')}），last_synced 未变。")
    return 0


def _parse_gates(raw: str) -> dict[str, str]:
    gates: dict[str, str] = {}
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise SystemExit(f"门禁格式应为 key=status：{part}")
        key, status = part.split("=", 1)
        key, status = key.strip(), status.strip()
        if status not in ALLOWED_GATE_STATUS:
            raise SystemExit(f"未知门禁状态 {status}（允许：{', '.join(sorted(ALLOWED_GATE_STATUS))}）")
        gates[key] = status
    return gates


def cmd_done(args, cfg) -> int:
    state_path = Path(args.state_file or cfg["state_file"])
    state = load_state(state_path)
    current = state.get("in_progress")
    if not current:
        print("没有 in_progress；先运行 sync_state.py begin。", file=sys.stderr)
        return 2

    gates = _parse_gates(args.gates)
    required = cfg["required_gates"] or ["build", "logic", "ui", "capture"]
    skip_reason = args.skip_reason or ""
    for gate in required:
        status = gates.get(gate, "missing")
        if gate == "logic" and status in ("skipped", "n/a"):
            if not skip_reason:
                print(
                    "logic 被标记 skipped/n/a，但缺少 --skip-reason；请说明为什么本次不需要逻辑对拍。",
                    file=sys.stderr,
                )
                return 2
            continue
        if status != "pass":
            print(
                f"门禁未通过：{gate}={status}（要求 pass）。\n"
                f"完整门禁：{', '.join(f'{k}={v}' for k, v in gates.items())}",
                file=sys.stderr,
            )
            return 2

    repo = _web_repo(args, cfg)
    info = common.head_info(repo)
    source_head, source_head_short = info["commit"], info["commit_short"]
    # 显式 --commit 优先：它代表“本次实际同步到的 web commit”，
    # 不能用 web 源仓本地 HEAD 代替（源仓只读时 HEAD 可能滞后）。
    target = resolve_commit(repo, args.commit) if getattr(args, "commit", "") else info["commit"]
    base = current.get("base_commit")
    head_moved = target != base
    if head_moved and not args.accept_head_move and not getattr(args, "commit", ""):
        print(
            f"web 源 HEAD 已移动：base={str(base)[:12]} 当前={info['commit_short']}。\n"
            "为避免把未同步的提交记成基线：先重新 begin/同步，或确认已覆盖新提交后加 --accept-head-move"
            "（也可用 --commit <同步目标> 显式指定）。",
            file=sys.stderr,
        )
        return 2
    if info["dirty"] and not args.allow_dirty:
        print(
            f"web 源仓库工作区仍有未提交改动（{info['dirty_count']} 个文件）。\n"
            "基线要可复现：先提交/清理，或加 --allow-dirty（会记录 dirty=true）。",
            file=sys.stderr,
        )
        return 2

    scope = common.parse_list(current.get("scope"))
    last = {
        "commit": target,
        "commit_short": target[:12],
        "branch": info["branch"],
        "dirty": info["dirty"],
        "dirty_count": info["dirty_count"],
        "synced_at": common.now_utc(),
        "scope": scope,
        "gates": gates,
        "note": args.note or current.get("note", ""),
        "skip_reason": skip_reason,
        "operator": common.operator(),
        "head_moved": head_moved,
        "base_commit": base,
        "commit_source": "explicit" if getattr(args, "commit", "") else "head",
        "source_head": source_head,
        "source_head_short": source_head_short,
        "head_lagging": target != source_head and is_ancestor(repo, source_head, target),
    }
    state["last_synced"] = last
    state["in_progress"] = None
    save_state(state_path, state)

    ledger = Path(args.ledger or cfg["ledger"])
    update_ledger(ledger, cfg["ledger_key"], target, scope, args.note or "")
    suffix = ""
    if target != source_head:
        rel = "滞后于目标" if last["head_lagging"] else "与目标不同"
        suffix = f"（显式同步目标；web 源本地 HEAD={source_head_short}，{rel}）"
    print(f"同步基线已推进：{target} ({target[:12]}){suffix}")
    print(f"  state={state_path}")
    print(f"  ledger={ledger}")
    print(f"  gates={', '.join(f'{k}={v}' for k, v in gates.items())}")
    return 0


def cmd_reconcile(args, cfg) -> int:
    """基线自校正：把 last_synced 校正到实际同步目标 commit。

    只允许“向前”校正（旧基线是新目标的祖先）；回退或无关 commit 需 --force。
    典型场景：历史版本把 web 源本地 HEAD（滞后）记成了基线，而实际同步已完成到 after。
    """
    state_path = Path(args.state_file or cfg["state_file"])
    state = load_state(state_path)
    last = state.get("last_synced")
    if not isinstance(last, dict):
        print("尚无 last_synced 基线，无可校正；先 begin/done。", file=sys.stderr)
        return 2
    repo = _web_repo(args, cfg)
    target = resolve_commit(repo, args.commit)
    old = str(last.get("commit") or "")
    if old and target == old:
        print(f"基线已一致（{target[:12]}），无需校正。")
        return 0
    forward = is_ancestor(repo, old, target)
    backward = is_ancestor(repo, target, old)
    if not forward and not args.force:
        reason = "目标早于当前基线（回退）" if backward else "目标与当前基线无祖先关系"
        print(
            f"拒绝校正：{reason}：current={old[:12] or '无'} target={target[:12]}。\n"
            "确认无误后加 --force。",
            file=sys.stderr,
        )
        return 2

    info = common.head_info(repo)
    scope = common.parse_list(last.get("scope"))
    correction = (
        f"基线自校正：{old[:12] or '无'} → {target[:12]}；"
        f"{args.reason or '记录基线不是本次实际同步目标'}"
    )
    note = str(last.get("note") or "")
    last.update(
        {
            "commit": target,
            "commit_short": target[:12],
            "base_commit": target,
            "head_moved": False,
            "corrected_at": common.now_utc(),
            "corrected_from": old or None,
            "correction": correction,
            "commit_source": "explicit",
            "source_head": info["commit"],
            "source_head_short": info["commit_short"],
            "head_lagging": target != info["commit"] and is_ancestor(repo, info["commit"], target),
            "note": f"{note}；{correction}" if note else correction,
        }
    )
    state["last_synced"] = last
    save_state(state_path, state)
    ledger = Path(args.ledger or cfg["ledger"])
    update_ledger(ledger, cfg["ledger_key"], target, scope, correction)
    print(f"基线已自校正：{old[:12] or '无'} → {target[:12]}")
    print(f"  state={state_path}")
    print(f"  ledger={ledger}")
    return 0


def cmd_status(args, cfg) -> int:
    state_path = Path(args.state_file or cfg["state_file"])
    state = load_state(state_path)
    repo = None
    try:
        repo = _web_repo(args, cfg)
        info = common.head_info(repo)
    except SystemExit as exc:
        info = {"error": str(exc)}
    last = state.get("last_synced")
    pending = None
    if last and repo and info.get("commit"):
        pending = common.pending_count(repo, last["commit"], info["commit"])

    data = {
        "state_file": str(state_path),
        "last_synced": last,
        "in_progress": state.get("in_progress"),
        "web": info,
        "pending_commits_since_last_sync": pending,
    }
    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0

    if last:
        print(f"上次同步：{last.get('commit')} ({last.get('branch')}) @ {last.get('synced_at')}")
        print(f"  scope: {', '.join(last.get('scope', [])) or '—'}")
        print(f"  gates: {', '.join(f'{k}={v}' for k, v in (last.get('gates') or {}).items())}")
        if last.get("dirty"):
            print("  注意：该基线记录时 web 源是 dirty 状态，复现性受限。")
        if last.get("source_head") and last.get("source_head") != last.get("commit"):
            rel = "滞后于目标" if last.get("head_lagging") else "与目标不同"
            print(f"  基线来源：显式同步目标（web 源本地 HEAD={last.get('source_head_short')}，{rel}）")
        if last.get("corrected_at"):
            print(f"  已自校正 @ {last['corrected_at']}：{last.get('correction') or ''}")
    else:
        print("尚无同步基线（last_synced 为空）。")

    current = state.get("in_progress")
    if current:
        print(f"进行中：base={current.get('base_commit_short')} scope={','.join(current.get('scope', []))}")
    if info.get("error"):
        print(f"web 源: {info['error']}")
    else:
        print(f"web 源 HEAD: {info.get('commit_short')} ({info.get('branch')}) dirty={info.get('dirty')}")
        if pending is not None:
            print(f"距上次同步：{pending} 个提交")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="web-to-app 同步基线状态")
    p.add_argument("--state-file", help="覆盖 config sync.state_file")
    p.add_argument("--ledger", help="覆盖 config diff_language.ledger")
    p.add_argument("--repo", help="覆盖 config web.repo")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("status", help="显示上次同步/进行中/待同步提交数")
    s.add_argument("--json", action="store_true")

    b = sub.add_parser("begin", help="记录本次同步起点（只写 in_progress）")
    b.add_argument("--scope", action="append", default=[], help="变更范围，可重复或逗号分隔")
    b.add_argument("--note", default="")
    b.add_argument("--allow-dirty", action="store_true")
    b.add_argument("--force", action="store_true")
    b.add_argument("--commit", default="", help="显式同步目标 commit（从 web 源仓校验）；缺省用 web 源本地 HEAD")

    a = sub.add_parser("abort", help="放弃 in_progress，不推进基线")

    d = sub.add_parser("done", help="门禁通过后推进基线并镜像台账")
    d.add_argument("--gates", required=True, help="如 build=pass,logic=pass,ui=pass,capture=pass")
    d.add_argument("--skip-reason", default="", help="logic=skipped/n/a 时必填")
    d.add_argument("--note", default="")
    d.add_argument("--allow-dirty", action="store_true")
    d.add_argument("--accept-head-move", action="store_true")
    d.add_argument("--commit", default="", help="显式同步目标 commit；缺省用 web 源本地 HEAD")

    r = sub.add_parser("reconcile", help="基线自校正：把 last_synced 校正到实际同步目标")
    r.add_argument("--commit", required=True, help="本轮实际同步到的 web commit")
    r.add_argument("--reason", default="", help="校正原因，写入状态与台账")
    r.add_argument("--note", default="")
    r.add_argument("--force", action="store_true", help="允许回退到非后代 commit（危险）")
    return p


def main() -> int:
    args = build_parser().parse_args()
    cfg = common.load_config()
    if args.cmd == "status":
        return cmd_status(args, cfg)
    if args.cmd == "begin":
        return cmd_begin(args, cfg)
    if args.cmd == "abort":
        return cmd_abort(args, cfg)
    if args.cmd == "done":
        return cmd_done(args, cfg)
    if args.cmd == "reconcile":
        return cmd_reconcile(args, cfg)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

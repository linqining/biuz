#!/usr/bin/env python3
"""web-to-app 同步基线状态：记录/推进上次同步的 web 源 commit。

用法：
  python3 sync_state.py status [--json]
  python3 sync_state.py begin --scope logic,ui,popup,animation [--note ...] [--allow-dirty]
  python3 sync_state.py abort
  python3 sync_state.py done --gates build=pass,logic=pass,ui=pass,capture=pass \
      [--skip-reason "UI-only 变更，逻辑对拍不适用"] [--note ...] [--allow-dirty] [--accept-head-move]

设计：
  - begin 只记录 in_progress，不推进 last_synced；
  - done 校验门禁后才把 in_progress 提升为 last_synced，并镜像到差异台账；
  - 失败/部分完成不会推进基线，避免“同步了一半但基线已走”；
  - 纯 UI 变更可把 logic 标 skipped/n/a，但必须给 --skip-reason。
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


def cmd_begin(args, cfg) -> int:
    repo = _web_repo(args, cfg)
    info = common.head_info(repo)
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
    }
    save_state(state_path, state)
    print(f"已记录同步起点：{info['commit']} ({info['branch']})")
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
    base = current.get("base_commit")
    head_moved = info["commit"] != base
    if head_moved and not args.accept_head_move:
        print(
            f"web 源 HEAD 已移动：base={base[:12]} 当前={info['commit_short']}。\n"
            "为避免把未同步的提交记成基线：先重新 begin/同步，或确认已覆盖新提交后加 --accept-head-move。",
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
        "commit": info["commit"],
        "commit_short": info["commit_short"],
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
    }
    state["last_synced"] = last
    state["in_progress"] = None
    save_state(state_path, state)

    ledger = Path(args.ledger or cfg["ledger"])
    update_ledger(ledger, cfg["ledger_key"], info["commit"], scope, args.note or "")
    print(f"同步基线已推进：{info['commit']} ({info['commit_short']})")
    print(f"  state={state_path}")
    print(f"  ledger={ledger}")
    print(f"  gates={', '.join(f'{k}={v}' for k, v in gates.items())}")
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

    a = sub.add_parser("abort", help="放弃 in_progress，不推进基线")

    d = sub.add_parser("done", help="门禁通过后推进基线并镜像台账")
    d.add_argument("--gates", required=True, help="如 build=pass,logic=pass,ui=pass,capture=pass")
    d.add_argument("--skip-reason", default="", help="logic=skipped/n/a 时必填")
    d.add_argument("--note", default="")
    d.add_argument("--allow-dirty", action="store_true")
    d.add_argument("--accept-head-move", action="store_true")
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
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""web-to-app skill 共享工具（capture.py / sync_state.py / sync_scaffold.py 共用）。

只依赖 Python 标准库；未装 PyYAML 时用内置多级 YAML 子集解析（任意层级嵌套、
行内 ` # 注释`、引号去裹；不支持 `- item` 列表——列表一律写成逗号分隔字符串）。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parents[1]
CONFIG_NAME = "web-to-app.yaml"


def find_repo(start: str | None = None) -> Path:
    """定位使用本 skill 的项目根（App 工程所在仓库）：
    ① 从 cwd 向上找 config/web-to-app.yaml（skill 装在用户级目录时靠这条）；
    ② 从 skill 自身位置向上找（skill 装在 <repo>/.agents|.zcode/skills 内时）；
    ③ 从 cwd 向上找 .git 兜底。"""
    cur = Path(start or os.getcwd()).resolve()
    for base in (cur, *cur.parents):
        if (base / "config" / CONFIG_NAME).is_file():
            return base
    for base in SKILL_DIR.parents:
        if (base / "config" / CONFIG_NAME).is_file():
            return base
    for base in (cur, *cur.parents):
        if (base / ".git").exists():
            return base
    raise SystemExit(
        "找不到项目根（config/web-to-app.yaml / .git）。\n"
        f"先复制 {SKILL_DIR / 'assets' / 'config.sample.yaml'} 到项目 config/web-to-app.yaml 再使用。"
    )


def _strip_inline_comment(value: str) -> str:
    """去掉 ` # comment`，但保留 URL 里的 #fragment。"""
    m = re.search(r"\s+#", value)
    return value[: m.start()].strip() if m else value.strip()


def _mini_yaml(text: str) -> dict:
    """多级 YAML 子集解析（无 PyYAML 时的兜底）：缩进栈 + key: value。"""
    root: dict = {}
    stack: list[tuple[int, dict]] = [(-1, root)]
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        key, _, val = [s.strip() for s in line.strip().partition(":")]
        if " #" in val:
            val = val.split(" #")[0].rstrip()
        while len(stack) > 1 and indent <= stack[-1][0]:
            stack.pop()
        parent = stack[-1][1]
        if val == "":
            child: dict = {}
            parent[key] = child
            stack.append((indent, child))
        else:
            if len(val) >= 2 and val[0] in "\"'" and val[-1] == val[0]:
                val = val[1:-1]
            elif re.fullmatch(r"-?\d+", val):
                val = int(val)
            parent[key] = val
    return root


def load_config(repo: str | Path | None = None) -> dict:
    repo_path = Path(repo or find_repo()).resolve()
    config_path = repo_path / "config" / CONFIG_NAME
    raw: dict = {}
    if config_path.is_file():
        text = config_path.read_text(encoding="utf-8")
        try:
            import yaml  # type: ignore
            raw = yaml.safe_load(text) or {}
        except Exception:
            raw = _mini_yaml(text)

    def expanded(value, default: str = "") -> Path | None:
        value = default if value in (None, "") else str(value)
        if not value:
            return None
        p = Path(os.path.expanduser(os.path.expandvars(value)))
        return p if p.is_absolute() else repo_path / p

    web = raw.get("web") or {}
    cap = raw.get("capture") or {}
    diff = raw.get("diff_language") or {}
    syn = raw.get("sync") or {}
    pages = raw.get("pages") or {}
    app = raw.get("app") or {}

    platforms = parse_list(app.get("platforms"), "ios,android,miniprogram")
    app_cfg: dict = {"platforms": platforms}
    for plat in platforms:
        sec = app.get(plat) or {}
        if not isinstance(sec, dict):
            sec = {}
        # 透传标量键（module/package/appid 等），dir 单独展开为绝对路径
        plat_cfg = {k: str(v) for k, v in sec.items() if isinstance(v, (str, int, float))}
        plat_cfg["dir"] = expanded(sec.get("dir"))
        plat_cfg.setdefault("module", "")
        plat_cfg.setdefault("package", "")
        app_cfg[plat] = plat_cfg

    return {
        "repo_root": repo_path,
        "config_path": config_path,
        "web_repo": expanded(web.get("repo")),
        "deployed_base": (str(web["deployed_base"]) if web.get("deployed_base") else None),
        "local_dev_base": (str(web["local_dev_base"]) if web.get("local_dev_base") else None),
        "page_index": expanded(pages.get("index"), "config/page-index.json"),
        "capture": {
            "port": int(cap.get("chrome_debug_port") or 9224),
            "profile": expanded(cap.get("chrome_profile_dir"), ".capture/chrome-profile"),
            "runs": expanded(cap.get("output_dir"), ".capture/runs"),
            "width": int(cap.get("viewport_width") or 390),
            "height": int(cap.get("viewport_height") or 844),
            "dpr": int(cap.get("device_scale_factor") or 3),
            "ua": (str(cap["user_agent"]) if cap.get("user_agent") else ""),
            "login_hints": (str(cap["login_hints"]) if cap.get("login_hints") else ""),
        },
        "ledger": expanded(diff.get("ledger"), "docs/parity-diff-ledger.md"),
        "diff_prefix": str(diff.get("prefix") or "APP-DIFF"),
        "state_file": expanded(syn.get("state_file"), "docs/parity-sync-state.json"),
        "scaffold_dir": expanded(syn.get("scaffold_dir"), ".capture/sync"),
        "ledger_key": str(syn.get("ledger_key") or "web-sync-base"),
        "required_gates": [
            x.strip() for x in str(syn.get("required_gates") or "build,logic,ui,capture").split(",") if x.strip()
        ],
        "platforms": platforms,
        "app": app_cfg,
        "raw": raw,
    }


def resolve_repo_path(repo_root: Path, value: str | Path) -> Path:
    p = Path(os.path.expanduser(os.path.expandvars(str(value))))
    return p if p.is_absolute() else repo_root / p


def git(repo: str | Path, *args: str, check: bool = True) -> str:
    cmd = ["git", "-C", str(repo), *args]
    try:
        return subprocess.check_output(cmd, text=True, stderr=subprocess.PIPE).strip()
    except subprocess.CalledProcessError as exc:
        if check:
            raise SystemExit(f"git 失败：{' '.join(cmd)}\n{exc.stderr.strip()}")
        return ""


def head_info(repo: str | Path) -> dict:
    repo = Path(repo)
    if not repo.is_dir():
        raise SystemExit(f"web 源仓库不存在：{repo}（检查 config/web-to-app.yaml 的 web.repo）")
    if not (repo / ".git").exists() and not git(repo, "rev-parse", "--git-dir", check=False):
        raise SystemExit(f"不是 git 仓库：{repo}")
    commit = git(repo, "rev-parse", "HEAD")
    branch = git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    dirty_lines = [x for x in git(repo, "status", "--porcelain").splitlines() if x.strip()]
    return {
        "commit": commit,
        "commit_short": commit[:12],
        "branch": branch,
        "dirty": bool(dirty_lines),
        "dirty_files": dirty_lines[:50],
        "dirty_count": len(dirty_lines),
    }


def commit_exists(repo: str | Path, commit: str) -> bool:
    try:
        subprocess.run(
            ["git", "-C", str(repo), "cat-file", "-e", f"{commit}^{{commit}}"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        return True
    except Exception:
        return False


def pending_count(repo: str | Path, base: str, head: str = "HEAD") -> int | None:
    out = git(repo, "rev-list", "--count", f"{base}..{head}", check=False)
    try:
        return int(out)
    except ValueError:
        return None


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def read_json(path: str | Path, default):
    p = Path(path)
    if not p.is_file():
        return default
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"JSON 解析失败：{p}\n{exc}")


def write_json(path: str | Path, data) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_list(values, default: str = "") -> list[str]:
    if values is None:
        values = [default] if default else []
    if isinstance(values, str):
        values = [values]
    out: list[str] = []
    for value in values:
        for part in str(value).split(","):
            part = part.strip()
            if part and part not in out:
                out.append(part)
    return out


def operator() -> str:
    return os.environ.get("USER") or os.environ.get("LOGNAME") or "unknown"


def slugify(text: str, fallback: str = "sync") -> str:
    s = re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-._")
    return s or fallback


def class_name(text: str, fallback: str = "SyncPage") -> str:
    """标题/key → PascalCase 类名（骨架文件命名用）；非 ASCII 输入给 fallback。"""
    parts = re.findall(r"[A-Za-z0-9]+", text)
    name = "".join(p[:1].upper() + p[1:] for p in parts) or fallback
    return name if name[:1].isalpha() else fallback

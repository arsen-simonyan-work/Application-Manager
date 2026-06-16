import os
import shlex

from .constants import HOME
from .system_tools import run_cmd, which


def detect_pkg_manager() -> str | None:
    for pm in ("apt", "dnf", "pacman", "zypper"):
        if which(pm):
            return pm
    return "apt" if which("apt-get") else None


def package_owning_file(filepath: str) -> str | None:
    if not filepath:
        return None
    filepath = os.path.abspath(filepath)
    pm = detect_pkg_manager()
    if not pm:
        return None
    if pm == "apt" and which("dpkg"):
        code, out = run_cmd(["dpkg", "-S", filepath])
        if code == 0 and out.strip():
            return out.splitlines()[0].split(":", 1)[0].strip() or None
    if pm in ("dnf", "zypper") and which("rpm"):
        code, out = run_cmd(["rpm", "-qf", "--queryformat", "%{NAME}", filepath])
        if code == 0 and out.strip():
            return out.strip().splitlines()[0] or None
    if pm == "pacman" and which("pacman"):
        code, out = run_cmd(["pacman", "-Qo", filepath])
        if code == 0 and " is owned by " in out:
            return out.strip().split(" is owned by ", 1)[1].split(" ", 1)[0] or None
    return None


def uninstall_package(pkg: str) -> tuple[bool, str]:
    pm = detect_pkg_manager()
    if not pm:
        return False, "No package manager found."
    cmd_map = {
        "apt": ["pkexec", "apt", "remove", "-y", pkg],
        "dnf": ["pkexec", "dnf", "remove", "-y", pkg],
        "pacman": ["pkexec", "pacman", "-Rns", "--noconfirm", pkg],
        "zypper": ["pkexec", "zypper", "-n", "rm", pkg],
    }
    code, out = run_cmd(cmd_map[pm])
    return code == 0, out


def first_abs_executable_from_exec(exec_clean: str) -> str | None:
    if not exec_clean:
        return None
    try:
        tokens = shlex.split(exec_clean)
    except ValueError:
        tokens = exec_clean.split()
    i = 0
    if tokens and tokens[0] == "env":
        i = 1
        while i < len(tokens) and "=" in tokens[i] and not tokens[i].startswith("-"):
            i += 1
    for t in tokens[i:]:
        if t.startswith("/") and os.path.exists(t):
            return t
    return None


def snap_name_from(exec_clean: str, entry: str, path: str) -> str | None:
    if exec_clean:
        try:
            toks = shlex.split(exec_clean)
        except ValueError:
            toks = exec_clean.split()
        for i, t in enumerate(toks):
            if t == "snap" and i + 2 < len(toks) and toks[i + 1] == "run":
                return toks[i + 2]
    if entry.endswith(".snap"):
        return entry[:-5]
    if path.endswith(".desktop"):
        return os.path.basename(path).replace(".desktop", "")
    return None


def resolve_user_actions(exec_clean: str, path: str) -> list[str]:
    actions = []
    bin_path = first_abs_executable_from_exec(exec_clean)
    if bin_path and bin_path.startswith(HOME) and os.path.isfile(bin_path):
        actions.append(f"Remove app file: {bin_path}")
    if path and path.endswith(".desktop"):
        actions.append(f"Remove shortcut: {path}")
    return actions

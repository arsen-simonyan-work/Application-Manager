import os

from .constants import SEARCH_PATHS
from .desktop_parser import (
    clean_exec,
    detect_group,
    first_exec_token,
    parse_desktop_file,
    pick_localized,
    scan_desktop_files,
    should_show_desktop_entry,
)
from .icons import find_icon_file, find_icon_near_exec
from .system_tools import run_cmd, which


def collect_snap_list() -> list[tuple[str, str]]:
    if not which("snap"):
        return []
    code, out = run_cmd(["snap", "list"])
    if code != 0:
        return []
    result: list[tuple[str, str]] = []
    for line in out.strip().splitlines()[1:]:
        parts = line.split()
        if parts:
            result.append((parts[0], parts[1] if len(parts) > 1 else ""))
    return result


def collect_flatpak_list() -> list[str]:
    if not which("flatpak"):
        return []
    code, out = run_cmd(["flatpak", "list", "--app", "--columns=application"])
    if code != 0:
        return []
    return [ln.strip() for ln in out.splitlines() if ln.strip()]


def collect_wine_programs() -> list[str]:
    programs: list[str] = []
    home = os.path.expanduser("~")
    wine_prefix = os.environ.get("WINEPREFIX", os.path.join(home, ".wine"))
    for pf in ["Program Files", "Program Files (x86)"]:
        p = os.path.join(wine_prefix, "drive_c", pf)
        if os.path.isdir(p):
            for name in sorted(os.listdir(p)):
                full = os.path.join(p, name)
                if os.path.isdir(full):
                    programs.append(name)
    return sorted(set(programs))


def iter_desktop_records():
    desktop_files = scan_desktop_files(SEARCH_PATHS)
    for path in desktop_files:
        info = parse_desktop_file(path)
        if not should_show_desktop_entry(info):
            continue
        exec_clean = clean_exec(info.get("Exec", ""))
        launch_token = first_exec_token(exec_clean)
        if launch_token and launch_token.startswith("/") and not os.path.exists(launch_token):
            continue
        icon_path = find_icon_file(info.get("Icon", ""))
        if not icon_path:
            icon_path = find_icon_file(info.get("X-Icon-Path", ""))
        exec_icon_path = find_icon_near_exec(exec_clean)
        if not icon_path:
            icon_path = exec_icon_path
        elif exec_icon_path and os.path.splitext(icon_path)[1].lower() in {".svg", ".svgz"}:
            if os.path.splitext(exec_icon_path)[1].lower() in {".png", ".xpm", ".gif", ".ico"}:
                icon_path = exec_icon_path
        yield {
            "name": pick_localized(info, "Name") or os.path.basename(path),
            "grp": detect_group(path),
            "entry": os.path.basename(path),
            "exec": exec_clean,
            "path": path,
            "icon_path": icon_path,
        }

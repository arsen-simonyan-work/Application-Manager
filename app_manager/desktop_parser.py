import glob
import os
import shlex

from .constants import (
    PATH_FLATPAK_SYS,
    PATH_FLATPAK_USER,
    PATH_LOCAL_SYSTEM,
    PATH_SNAP,
    PATH_SYSTEM,
    PATH_USER,
    PATH_WINE_USER,
)


def parse_desktop_file(path: str) -> dict:
    info = {"Name": "", "Exec": "", "Comment": "", "Path": path, "Icon": ""}
    section = None
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if line.startswith("[") and line.endswith("]"):
                    section = line[1:-1].strip()
                    continue
                if section != "Desktop Entry" or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k, v = k.strip(), v.strip()
                if "[" in k and k.endswith("]"):
                    base_key = k.split("[", 1)[0]
                    info[k] = v
                    info.setdefault(base_key, v)
                else:
                    info[k] = v
    except Exception as e:
        info["Name"] = f"(parse error) {os.path.basename(path)}"
        info["Comment"] = str(e)
    if not info["Name"]:
        info["Name"] = os.path.basename(path)
    return info


def scan_desktop_files(paths: list[str]) -> list[str]:
    files: list[str] = []
    for base in paths:
        if os.path.isdir(base):
            files.extend(glob.glob(os.path.join(base, "*.desktop")))
    return sorted(set(files))


def clean_exec(exec_str: str) -> str:
    if not exec_str:
        return ""
    try:
        tokens = shlex.split(exec_str)
    except ValueError:
        tokens = exec_str.split()
    return " ".join([t for t in tokens if not t.startswith("%")])


def _bool_value(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def should_show_desktop_entry(info: dict) -> bool:
    entry_type = info.get("Type", "").strip()
    if entry_type and entry_type != "Application":
        return False
    if _bool_value(info.get("Hidden", "")):
        return False
    if _bool_value(info.get("NoDisplay", "")):
        return False
    return True


def first_exec_token(exec_str: str) -> str | None:
    if not exec_str:
        return None
    try:
        tokens = shlex.split(exec_str)
    except ValueError:
        tokens = exec_str.split()
    if not tokens:
        return None
    i = 0
    if tokens[0] == "env":
        i = 1
        while i < len(tokens) and "=" in tokens[i] and not tokens[i].startswith("-"):
            i += 1
    return tokens[i] if i < len(tokens) else None


def detect_group(path: str) -> str:
    if path.startswith(PATH_WINE_USER):
        return "Wine"
    if path.startswith(PATH_SNAP):
        return "Snap"
    if path.startswith(PATH_FLATPAK_SYS) or path.startswith(PATH_FLATPAK_USER):
        return "Flatpak"
    if path.startswith(PATH_USER):
        return "User"
    if path.startswith(PATH_SYSTEM) or path.startswith(PATH_LOCAL_SYSTEM):
        return "System"
    return "Other"


def pick_localized(info: dict, key: str) -> str:
    prefs = [os.environ.get("LANGUAGE"), os.environ.get("LC_ALL"), os.environ.get("LC_MESSAGES"), os.environ.get("LANG")]
    normalized: list[str] = []
    for p in [x for x in prefs if x]:
        p = p.split(".")[0]
        normalized.append(p)
        if "_" in p:
            normalized.append(p.split("_")[0])
    normalized += ["en_US", "en", "C"]
    seen = set()
    for lang in normalized:
        if not lang or lang in seen:
            continue
        seen.add(lang)
        lk = f"{key}[{lang}]"
        if info.get(lk):
            return info[lk]
    if info.get(key):
        return info[key]
    return ""

import base64
import glob
import math
import os
import shlex
import shutil
import subprocess
import tempfile
import tkinter as tk

from .constants import HOME, ICON_SEARCH_DIRS, ICON_SIZE

try:
    from PIL import Image, ImageTk  # type: ignore
    PIL_OK = True
except Exception:
    Image = None
    ImageTk = None
    PIL_OK = False

try:
    import cairosvg  # type: ignore
    CAIRO_OK = True
except Exception:
    CAIRO_OK = False

ICON_LOOKUP_CACHE: dict[str, str | None] = {}
KNOWN_ICON_EXTS = (".png", ".xpm", ".svg", ".svgz", ".gif", ".ico")

DEFAULT_ICON_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAAx0lEQVRIS+2UwQ3CMAxF"
    "z1C4B0M7Ggk0eJvCFoT8gHkzQv0eZ8gU1s7r7S5fZb3s0lmyQk8rUe0w0L3o0Owd4QxLM"
    "5S6D2bqYg5f2M2Cw6kQv4hD3oGxMAL2WmR3i2G7b2l1S0Lr6l0K2G8mJb6Q1bRzq2D59y"
    "R5l0U1bq3rJQxC5H0H1xQ5wS3cH8l9uXhE5E4l5c4q8d3v0j4V2mO3E/3xWk4n7H3pmJd"
    "7wZcH8r3lI7Hk0H3s0QHqfA2p9wQf2O1L7e4Q8hQ8sWk1mRr0zAAAAAElFTkSuQmCC"
)


def load_default_icon():
    # Draw a UI-friendly placeholder icon (no dependency on base64 decoding).
    img = tk.PhotoImage(width=ICON_SIZE, height=ICON_SIZE)
    bg = "#1f2124"
    border = "#4a4f57"
    glow = "#0f4c75"
    accent = "#2a7dc0"
    accent2 = "#1f6aa5"
    # base + border
    img.put(bg, to=(0, 0, ICON_SIZE, ICON_SIZE))
    img.put(border, to=(0, 0, ICON_SIZE, 1))
    img.put(border, to=(0, ICON_SIZE - 1, ICON_SIZE, ICON_SIZE))
    img.put(border, to=(0, 0, 1, ICON_SIZE))
    img.put(border, to=(ICON_SIZE - 1, 0, ICON_SIZE, ICON_SIZE))
    # inner "screen"
    img.put(glow, to=(4, 4, ICON_SIZE - 4, ICON_SIZE - 4))
    img.put(bg, to=(5, 5, ICON_SIZE - 5, ICON_SIZE - 5))
    # glyph: stylized "A"
    img.put(accent, to=(7, 16, 17, 18))   # base bar
    img.put(accent2, to=(9, 8, 11, 16))   # left leg
    img.put(accent2, to=(13, 8, 15, 16))  # right leg
    img.put(accent, to=(10, 12, 14, 13))  # crossbar
    return img


def _icon_score(path: str) -> tuple[int, int, int, int]:
    normalized = path.lower()
    ext = os.path.splitext(normalized)[1]
    size_score = 0
    for part in normalized.split("/"):
        if "x" in part:
            left, _, right = part.partition("x")
            if left.isdigit() and right.isdigit():
                size_score = max(size_score, min(int(left), int(right)))
    family_score = 3 if "/apps/" in normalized else 2 if "/devices/" in normalized else 1 if "/status/" in normalized else 0
    symbolic_penalty = 0 if "-symbolic" not in normalized and "/symbolic/" not in normalized else -1
    ext_score = {".png": 4, ".svg": 3, ".svgz": 2, ".xpm": 1, ".gif": 0, ".ico": 0}.get(ext, 0)
    return (family_score, size_score, symbolic_penalty, ext_score)


def _recursive_icon_try(name: str) -> str | None:
    roots = [
        "/usr/share/icons",
        os.path.join(HOME, ".local/share/icons"),
        os.path.join(HOME, ".icons"),
        "/var/lib/flatpak/exports/share/icons",
        os.path.join(HOME, ".local/share/flatpak/exports/share/icons"),
        "/var/lib/flatpak/app",
        os.path.join(HOME, ".local/share/flatpak/app"),
    ]
    matches: list[str] = []
    for root in roots:
        if not os.path.isdir(root):
            continue
        for p in glob.glob(os.path.join(root, "**", name), recursive=True):
            if os.path.isfile(p):
                matches.append(p)
    if not matches:
        return None
    return max(matches, key=_icon_score)


def find_icon_file(icon_field: str) -> str | None:
    if not icon_field:
        return None
    if icon_field in ICON_LOOKUP_CACHE:
        return ICON_LOOKUP_CACHE[icon_field]
    if os.path.isabs(icon_field) and os.path.isfile(icon_field):
        ICON_LOOKUP_CACHE[icon_field] = icon_field
        return icon_field

    base, ext = os.path.splitext(icon_field)
    if ext.lower() not in KNOWN_ICON_EXTS:
        base, ext = icon_field, ""
    for d in ICON_SEARCH_DIRS:
        if not os.path.isdir(d):
            continue
        if ext:
            p = os.path.join(d, icon_field)
            if os.path.isfile(p):
                ICON_LOOKUP_CACHE[icon_field] = p
                return p
        else:
            for e in KNOWN_ICON_EXTS:
                p = os.path.join(d, icon_field + e)
                if os.path.isfile(p):
                    ICON_LOOKUP_CACHE[icon_field] = p
                    return p

    if ext:
        p = _recursive_icon_try(icon_field)
        if p:
            ICON_LOOKUP_CACHE[icon_field] = p
            return p
    else:
        for e in KNOWN_ICON_EXTS:
            p = _recursive_icon_try(icon_field + e)
            if p:
                ICON_LOOKUP_CACHE[icon_field] = p
                return p
        for e in KNOWN_ICON_EXTS:
            p = _recursive_icon_try(f"{icon_field}-symbolic{e}")
            if p:
                ICON_LOOKUP_CACHE[icon_field] = p
                return p
        if os.path.basename(base).endswith("-symbolic"):
            plain = os.path.basename(base)[:-9]
            for e in KNOWN_ICON_EXTS:
                p = _recursive_icon_try(plain + e)
                if p:
                    ICON_LOOKUP_CACHE[icon_field] = p
                    return p

    ICON_LOOKUP_CACHE[icon_field] = None
    return None


def _first_exec_path(exec_clean: str) -> str | None:
    if not exec_clean:
        return None
    try:
        tokens = shlex.split(exec_clean)
    except ValueError:
        tokens = exec_clean.split()
    if not tokens:
        return None
    i = 0
    if tokens[0] == "env":
        i = 1
        while i < len(tokens) and "=" in tokens[i] and not tokens[i].startswith("-"):
            i += 1
    if i >= len(tokens):
        return None
    token = tokens[i]
    if token.startswith("/"):
        return token
    return None


def find_icon_near_exec(exec_clean: str) -> str | None:
    exec_path = _first_exec_path(exec_clean)
    if not exec_path or not os.path.isfile(exec_path):
        return None

    exec_dir = os.path.dirname(exec_path)
    exec_stem = os.path.splitext(os.path.basename(exec_path))[0]
    parent_dir = os.path.basename(exec_dir)
    names = ["icon", "app-icon", exec_stem, parent_dir]
    seen: set[str] = set()

    for name in names:
        if not name or name in seen:
            continue
        seen.add(name)
        for ext in (".png", ".svg", ".xpm", ".gif"):
            candidate = os.path.join(exec_dir, name + ext)
            if os.path.exists(candidate):
                return candidate
    return None


def _load_tk_scaled_image(icon_path: str, size: tuple[int, int]):
    try:
        src = tk.PhotoImage(file=icon_path)
        tw, th = size
        if src.width() <= 0 or src.height() <= 0:
            return None
        x_div = max(1, math.ceil(src.width() / tw))
        y_div = max(1, math.ceil(src.height() / th))
        if x_div > 1 or y_div > 1:
            src = src.subsample(x_div, y_div)
        canvas = tk.PhotoImage(width=tw, height=th)
        x = max(0, (tw - src.width()) // 2)
        y = max(0, (th - src.height()) // 2)
        canvas.tk.call(str(canvas), "copy", str(src), "-from", 0, 0, src.width(), src.height(), "-to", x, y)
        canvas._src_ref = src
        return canvas
    except Exception:
        return None


def _load_svg_with_convert(icon_path: str, size: tuple[int, int]):
    if not shutil.which("convert"):
        return None
    tw, th = size
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            temp_path = tmp.name
        subprocess.run(
            ["convert", icon_path, "-background", "none", "-resize", f"{tw}x{th}", temp_path],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return _load_tk_scaled_image(temp_path, size)
    except Exception:
        return None
    finally:
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass


def _load_raster_with_convert(icon_path: str, size: tuple[int, int]):
    if not shutil.which("convert"):
        return None
    tw, th = size
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            temp_path = tmp.name
        subprocess.run(
            ["convert", icon_path, "-background", "none", "-resize", f"{tw}x{th}", temp_path],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return _load_tk_scaled_image(temp_path, size)
    except Exception:
        return None
    finally:
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass


def load_icon_image(icon_path: str, size: tuple[int, int] = (ICON_SIZE, ICON_SIZE)):
    if not icon_path:
        return None
    ext = os.path.splitext(icon_path)[1].lower()
    try:
        if ext in (".svg", ".svgz"):
            if PIL_OK and CAIRO_OK:
                from io import BytesIO
                png_bytes = cairosvg.svg2png(url=icon_path)
                img = Image.open(BytesIO(png_bytes))
            else:
                return _load_svg_with_convert(icon_path, size)
        else:
            if not PIL_OK:
                if ext in (".png", ".gif", ".xpm"):
                    img = _load_tk_scaled_image(icon_path, size)
                    if img:
                        return img
                    return _load_raster_with_convert(icon_path, size)
                return None
            img = Image.open(icon_path)
        img = img.convert("RGBA")
        w, h = img.size
        tw, th = size
        if not w or not h:
            return None
        scale = min(tw / w, th / h)
        nw = max(1, int(round(w * scale)))
        nh = max(1, int(round(h * scale)))
        img = img.resize((nw, nh), Image.LANCZOS)
        canvas = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
        canvas.paste(img, ((tw - nw) // 2, (th - nh) // 2), img)
        return ImageTk.PhotoImage(canvas)
    except Exception:
        try:
            if ext in (".png", ".gif", ".xpm"):
                img = _load_tk_scaled_image(icon_path, size)
                if img:
                    return img
                return _load_raster_with_convert(icon_path, size)
            if ext in (".svg", ".svgz"):
                return _load_svg_with_convert(icon_path, size)
        except Exception:
            pass
        return None

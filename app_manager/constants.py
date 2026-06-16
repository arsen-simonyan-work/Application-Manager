import os
from pathlib import Path

HOME = str(Path.home())
ICON_SIZE = 48
TEXT_PAD = "   "
ROW_PAD = 6
GROUPS = ["System", "User", "Snap", "Flatpak", "Wine", "Other"]
UNIQUE_PRIORITY = {"System": 3, "Flatpak": 3, "Snap": 3, "User": 2, "Wine": 1, "Other": 0}

PATH_SYSTEM = "/usr/share/applications"
PATH_LOCAL_SYSTEM = "/usr/local/share/applications"
PATH_USER = os.path.join(HOME, ".local/share/applications")
PATH_SNAP = "/var/lib/snapd/desktop/applications"
PATH_FLATPAK_SYS = "/var/lib/flatpak/exports/share/applications"
PATH_FLATPAK_USER = os.path.join(HOME, ".local/share/flatpak/exports/share/applications")
PATH_WINE_USER = os.path.join(PATH_USER, "wine")

SEARCH_PATHS = [
    PATH_SYSTEM,
    PATH_LOCAL_SYSTEM,
    PATH_USER,
    PATH_SNAP,
    PATH_FLATPAK_SYS,
    PATH_FLATPAK_USER,
    PATH_WINE_USER,
]

ICON_SEARCH_DIRS = [
    "/usr/share/icons/hicolor/512x512/apps",
    "/usr/share/icons/hicolor/256x256/apps",
    "/usr/share/icons/hicolor/128x128/apps",
    "/usr/share/icons/hicolor/64x64/apps",
    "/usr/share/icons/hicolor/48x48/apps",
    "/usr/share/icons/hicolor/32x32/apps",
    "/usr/share/icons/hicolor/24x24/apps",
    "/usr/share/icons/hicolor/16x16/apps",
    "/usr/share/pixmaps",
    os.path.join(HOME, ".local/share/icons/hicolor/48x48/apps"),
    os.path.join(HOME, ".icons"),
]

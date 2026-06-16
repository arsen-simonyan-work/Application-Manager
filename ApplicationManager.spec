# -*- mode: python ; coding: utf-8 -*-

import sys
from pathlib import Path
from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs, collect_submodules

project_root = Path(globals().get("SPECPATH", Path.cwd())).resolve()
runtime_icon = project_root / "assets" / "icons" / "app-icon.png"
windows_icon = project_root / "assets" / "icons" / "app-icon.ico"
macos_icon = project_root / "assets" / "icons" / "app-icon.icns"

base_datas = [("VERSION", ".")]
base_datas += collect_data_files("cairosvg")
base_datas += collect_data_files("cairocffi")
base_datas += collect_data_files("cssselect2")
base_datas += collect_data_files("tinycss2")
base_datas += collect_data_files("defusedxml")

hiddenimports = []
hiddenimports += collect_submodules("cairosvg")
hiddenimports += collect_submodules("cairocffi")
hiddenimports += collect_submodules("cssselect2")
hiddenimports += collect_submodules("tinycss2")
hiddenimports += collect_submodules("defusedxml")

extra_binaries = []
extra_binaries += collect_dynamic_libs("cairocffi")

if runtime_icon.exists():
    base_datas.append((str(runtime_icon), "assets/icons"))
if windows_icon.exists():
    base_datas.append((str(windows_icon), "assets/icons"))


a = Analysis(
    ["main.py"],
    pathex=[str(project_root)],
    binaries=extra_binaries,
    datas=base_datas,
    hiddenimports=hiddenimports + [
        "cffi",
        "PIL",
        "PIL.Image",
        "PIL.ImageTk",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="ApplicationManager",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(windows_icon) if windows_icon.exists() else None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="ApplicationManager",
)

if sys.platform == "darwin":
    app = BUNDLE(
        coll,
        name="ApplicationManager.app",
        bundle_identifier="com.home.applicationmanager",
        icon=str(macos_icon) if macos_icon.exists() else None,
    )

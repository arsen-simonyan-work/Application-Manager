#!/usr/bin/env python3
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC_FILE = ROOT / "ApplicationManager.spec"
DIST_DIR = ROOT / "dist"
BUILD_DIR = ROOT / "build"
VENV_DIR = ROOT / ".venv"


def run(cmd: list[str]) -> None:
    print(">", " ".join(cmd))
    subprocess.run(cmd, check=True)


def venv_python() -> Path:
    if os.name == "nt":
        return VENV_DIR / "Scripts" / "python.exe"
    return VENV_DIR / "bin" / "python3"


def ensure_venv() -> Path:
    py = venv_python()
    if py.exists():
        return py
    print("Creating local virtualenv (.venv) ...")
    run([sys.executable, "-m", "venv", str(VENV_DIR)])
    if not py.exists():
        raise RuntimeError("Failed to create virtualenv (.venv).")
    return py


def main() -> None:
    os.chdir(ROOT)
    py = ensure_venv()
    run([str(py), "-m", "pip", "install", "--upgrade", "pip"])
    run([str(py), "-m", "pip", "install", "-r", str(ROOT / "requirements.txt"), "pyinstaller"])
    run([str(py), str(ROOT / "scripts" / "generate_icons.py")])
    run(
        [
            str(py),
            "-m",
            "PyInstaller",
            str(SPEC_FILE),
            "--noconfirm",
            "--clean",
        ]
    )
    print(f"Done. Binary: {DIST_DIR / 'ApplicationManager'}")
    print(f"Build artifacts: {BUILD_DIR}")
    print(f"Virtualenv: {VENV_DIR}")


if __name__ == "__main__":
    main()

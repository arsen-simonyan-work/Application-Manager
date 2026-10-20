# Application Manager — Compose Desktop

Native Kotlin/JVM application for **Linux**. Migrated from Python / Tkinter to **Jetpack Compose Desktop** with no Python runtime, pip, PyInstaller, or tkinter.

## Requirements

- JDK 17+
- Gradle 8.14.3 (or compatible Gradle 8.14.x)
- Linux desktop session (X11 or Wayland/XWayland)
- Internet access *for the first Gradle dependency resolution only*

## Run

```bash
gradle run
```

## Distribution

```bash
gradle packageDeb       # Ubuntu / Debian
gradle packageRpm       # Fedora / RPM-based distributions
```

The package tasks can require Linux packaging tools. Outputs are in `build/compose/binaries`.

## Features

- Scan Linux `.desktop` application launchers from system, per-user, Snap and Flatpak directories.
- Enumerate Snap, Flatpak and Wine applications (when installed).
- Search and browse **All**, **Unique**, **Duplicates**, and source groups.
- Select details, launch via argv, open desktop file / containing folder, uninstall via distribution package tools.
- Explicit confirmation before uninstall. User removal deletes *only* the `.desktop` launcher, not an executable binary.
- Dark Compose Desktop UI with lazy list rendering and nonblocking scan/uninstall tasks.

## Notes / limitations

- Designed for Linux; Windows/macOS are not supported because package discovery is Linux-specific.
- Snap / Flatpak uninstall uses their native CLIs. System package removal uses `pkexec` and the available package manager.
- `.desktop` Exec parsing covers basic quoting and field substitution; complex environment wrappers and Desktop Entry specification edge cases may require further work.
- Icon loading uses Java ImageIO (PNG/JPG/GIF); SVG/XPM icons fall back to a letter glyph.
- Requires an authenticated graphical desktop session for privileged package removal.
- Gradle dependencies were not available in the offline migration environment; compilation and interactive UI testing are **not verified**. Build locally with the commands above.

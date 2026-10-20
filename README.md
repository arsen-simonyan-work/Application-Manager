# Application Manager

Linux application manager built with Kotlin / Compose Desktop (JDK 17).

Run with `gradle run`, build with `gradle packageDeb` or `gradle packageRpm`.

Lists .desktop launchers, Snap and Flatpak apps. Wine apps are shown only when a .desktop launcher exists; directories in Wine's Program Files are intentionally ignored.

Uninstall operations require confirmation. User launchers are deleted without deleting installed software. System packages are left to the distribution package manager.

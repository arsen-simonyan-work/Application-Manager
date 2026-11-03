# Application Manager — Compose Desktop

Native Kotlin/JVM application for **Linux**, migrated from Python/Tkinter to Compose Desktop.

## Requirements

- JDK 17+
- Linux desktop session
- Internet access for first Gradle download and dependency resolution

## Run

```sh
./gradlew run
```

## Packaging

Build a local DEB (Linux, full JDK 17 with `jlink` and `jpackage`, `dpkg-deb`,
and `fakeroot`):

```sh
./gradlew buildRelease
```

Packaging uses the JDK 17 Gradle toolchain rather than the JDK selected by
`JAVA_HOME` to start Gradle.
The project's daemon JVM criteria also select JDK 17 for Gradle itself.

Output: `build/release/Application-Manager-1.0.1-<architecture>.deb`, for example
`build/release/Application-Manager-1.0.1-amd64.deb`. The version comes from
`VERSION`; the architecture comes from the package built locally by `jpackage`.
The package includes its own Java runtime.

The GNOME menu displays **Application Manager**. The window class, desktop entry,
stock icon, and AppStream metadata use `am.applicationmanager.ApplicationManager`
to identify the same application. The installer filename and AppStream name use
**Application-Manager**; Debian's internal package name remains `application-manager`.
The package owns its desktop entry in `/usr/share/applications`, and upgrading
removes the duplicate menu entry created by the older installer.

Standard Compose packaging tasks are also available:

```sh
./gradlew packageDeb
./gradlew packageRpm
```

The Gradle Wrapper downloads the declared Gradle version, so a global Gradle installation is not needed. The app detects .desktop launchers, Snap and Flatpak apps, and Wine applications registered with a .desktop launcher. It deliberately ignores non-application folders in Wine's Program Files. The user confirmation dialog is required before uninstalling.

## Navigation

The main window opens maximized and has three left-side sections:

- **Applications:** best-effort list of installed GUI applications. Entries from
  Flatpak, Snap, and desktop registrations are grouped by application identity.
  A command-line-only program without a desktop registration is not guaranteed
  to appear here.
- **Launchers:** individual `.desktop` shortcuts, including duplicate
  registrations. Deleting a user-owned launcher does **not** uninstall its app.
- **Services:** read-only systemd system/user `.service` units, including
  running and inactive services when accessible. Requires systemd/systemctl;
  running state is not a reliable indication of whether a service has a GUI.

System packages should only be removed after carefully reviewing the confirmation
dialog. Service start/stop/uninstall controls are intentionally not provided.


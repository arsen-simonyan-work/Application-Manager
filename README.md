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


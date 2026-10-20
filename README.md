# Application Manager

Linux application manager built with Kotlin / Compose Desktop (JDK 17 or newer).

## Launch

```sh
./gradlew run
```

The Gradle Wrapper downloads Gradle 8.14.3 on first launch, so a global Gradle installation is not required.

## Packages

```sh
./gradlew packageDeb
./gradlew packageRpm
```

The app lists installed desktop launchers, Snap and Flatpak apps. Only registered Wine desktop launchers are indexed; directories under Wine's `Program Files` are intentionally ignored.

Uninstall operations require confirmation. User launchers are deleted without deleting installed software. System packages must be removed using the distribution's package manager.

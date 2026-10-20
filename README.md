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

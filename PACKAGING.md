# Linux packaging and identification

Build locally with a full JDK 17 installed (selected automatically by Gradle toolchains):

```sh
./gradlew buildRelease
```

The result is `build/release/Application-Manager-[version]-[architecture].deb`.
The version is read from `VERSION`. See [BUILD.md](BUILD.md) for requirements.

| Purpose | Name |
| --- | --- |
| Installer filename prefix and AppStream display name | `Application-Manager` |
| GNOME menu | `Application Manager` |
| Window title | `Application Manager v[version]` (read from `VERSION`) |
| Internal Debian package name and installation directory | `application-manager` |
| Desktop ID, icon name and window class | `am.applicationmanager.ApplicationManager` |
| Executable | `/opt/application-manager/bin/Application-Manager` |

The internal package name uses lowercase letters as required by
[Debian Policy](https://www.debian.org/doc/debian-policy/ch-controlfields.html#source).
It also keeps upgrades associated with the existing installed package.

`packageDeb` and `packageReleaseDeb` both run `tools/fix-linux-deb.sh` after
`jpackage`. The script adds the desktop entry to `/usr/share/applications`,
the unchanged transparent PNG to `/usr/share/pixmaps`, and AppStream metadata
to `/usr/share/metainfo`. All three use the same application ID. The window's
`WM_CLASS` matches the desktop entry's `StartupWMClass`.

The package owns these integration files directly. The installation scripts
remove the old duplicate `application-manager-application-manager.desktop` and
refresh the desktop database after installation and removal.

The icon shown for a `.deb` file by a file manager is determined by the desktop's
package MIME type; the application icon is supplied for the launcher, window
and software-center metadata.

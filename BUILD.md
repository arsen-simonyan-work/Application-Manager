# Build

## Quick start

```bash
./build.sh
```

## Build a .deb package (Debian/Ubuntu)

1) Build the binary:

```bash
./build.sh
```

2) Build the package:

```bash
chmod +x ./scripts/build_deb.sh
./scripts/build_deb.sh
```

Environment variables (optional):
- `VERSION`: package version (default `0.1.0`)
- `MAINTAINER`: Maintainer string

## What the build does

- creates a local virtual environment `.venv` (if missing)
- installs dependencies from `requirements.txt` plus `pyinstaller` into `.venv`
- builds the GUI app into `dist/ApplicationManager`


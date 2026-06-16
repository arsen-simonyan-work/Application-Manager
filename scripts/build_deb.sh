#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NAME="application-manager"
DISPLAY_NAME="Application Manager"
ARCH="$(dpkg --print-architecture)"
VERSION="${VERSION:-0.1.0}"
MAINTAINER="${MAINTAINER:-ApplicationManager Builder <builder@localhost>}"

DIST_DIR="$ROOT/dist/ApplicationManager"
OUT_DIR="$ROOT/dist-deb"
STAGE_DIR="$ROOT/build/deb-root"

if ! command -v dpkg-deb >/dev/null 2>&1; then
  echo "dpkg-deb not found. Install: sudo apt install dpkg-dev"
  exit 1
fi

if [[ ! -d "$DIST_DIR" ]]; then
  echo "Missing '$DIST_DIR'. Build the binary first:"
  echo "  ./build.sh"
  exit 1
fi

rm -rf "$STAGE_DIR"
mkdir -p \
  "$STAGE_DIR/DEBIAN" \
  "$STAGE_DIR/opt/$NAME" \
  "$STAGE_DIR/usr/bin" \
  "$STAGE_DIR/usr/share/applications" \
  "$STAGE_DIR/usr/share/pixmaps"

# App payload (PyInstaller folder)
cp -a "$DIST_DIR/." "$STAGE_DIR/opt/$NAME/"

# Wrapper in PATH
cat >"$STAGE_DIR/usr/bin/$NAME" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exec "/opt/application-manager/ApplicationManager" "$@"
EOF
chmod 0755 "$STAGE_DIR/usr/bin/$NAME"

# Desktop entry
cat >"$STAGE_DIR/usr/share/applications/$NAME.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$DISPLAY_NAME
Exec=/usr/bin/$NAME
Icon=$NAME
StartupWMClass=Applicationmanager
Terminal=false
Categories=Utility;
EOF

# Icon
if [[ -f "$ROOT/assets/icons/app-icon.png" ]]; then
  install -m 0644 "$ROOT/assets/icons/app-icon.png" "$STAGE_DIR/usr/share/pixmaps/$NAME.png"
fi

# Control file
cat >"$STAGE_DIR/DEBIAN/control" <<EOF
Package: $NAME
Version: $VERSION
Section: utils
Priority: optional
Architecture: $ARCH
Maintainer: $MAINTAINER
Depends: bash
Description: GUI application manager
 Simple GUI to browse installed applications and uninstall where possible.
EOF

mkdir -p "$OUT_DIR"
DEB_PATH="$OUT_DIR/${NAME}_${VERSION}_${ARCH}.deb"
dpkg-deb --build "$STAGE_DIR" "$DEB_PATH"

echo "Готово: $DEB_PATH"
echo "Done: $DEB_PATH"
echo "Install: sudo dpkg -i \"$DEB_PATH\""
echo "Remove: sudo apt remove $NAME"

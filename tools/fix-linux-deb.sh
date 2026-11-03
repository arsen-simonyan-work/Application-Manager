#!/usr/bin/env bash
set -euo pipefail

deb_file=${1:?DEB file path is required}
icon_file=${2:?PNG icon path is required}
packaging_dir=${3:?Linux packaging directory is required}
app_version=${4:?Application version is required}
app_id=am.applicationmanager.ApplicationManager

test -f "$deb_file"
test -f "$icon_file"
test -d "$packaging_dir"
test "$(dpkg-deb --field "$deb_file" Package)" = application-manager
deb_version=$(dpkg-deb --field "$deb_file" Version)
case "$deb_version" in
    "$app_version"|"$app_version"-*) ;;
    *) echo "Unexpected package version: $deb_version (expected $app_version)" >&2; exit 1 ;;
esac

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
package_root="$work_dir/root"
dpkg-deb --raw-extract "$deb_file" "$package_root"

app_dir="$package_root/opt/application-manager"
test -x "$app_dir/bin/Application-Manager"

# Replace jpackage's private desktop entry with a single standard, dpkg-owned entry.
find "$app_dir/lib" -maxdepth 1 -type f -name '*.desktop' -delete
install -Dm644 "$packaging_dir/$app_id.desktop" \
    "$package_root/usr/share/applications/$app_id.desktop"
install -Dm644 "$packaging_dir/$app_id.metainfo.xml" \
    "$package_root/usr/share/metainfo/$app_id.metainfo.xml"

# A stock icon in pixmaps is available to GNOME and software centers at any size.
# Keep the supplied RGBA PNG byte-for-byte, including its transparent background.
install -Dm644 "$icon_file" "$package_root/usr/share/pixmaps/$app_id.png"
install -m644 "$icon_file" "$app_dir/lib/Application-Manager.png"

for script in postinst prerm postrm; do
    install -m755 "$packaging_dir/$script" "$package_root/DEBIAN/$script"
done

# Recreate md5sums after replacing generated files and adding desktop integration.
(
    cd "$package_root"
    find . -type f ! -path './DEBIAN/*' -printf '%P\0' | LC_ALL=C sort -z | \
        xargs -0 -r md5sum > DEBIAN/md5sums
)
installed_size=$(du -sk --exclude=DEBIAN "$package_root" | cut -f1)
sed -i "s/^Installed-Size:.*/Installed-Size: $installed_size/" "$package_root/DEBIAN/control"

dpkg-deb --build --root-owner-group "$package_root" "$work_dir/fixed.deb"
mv "$work_dir/fixed.deb" "$deb_file"

#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
bundle_dir=${1:-"$repo_dir/build/linux/x64/release/bundle"}
output_dir=${2:-"$repo_dir/build/linux/packages"}
version=0.0.6

if [[ ! -x "$bundle_dir/qnzapret" ]]; then
  echo "Linux release bundle not found: $bundle_dir" >&2
  echo "Run flutter build linux --release first." >&2
  exit 1
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/qnzapret-packaging.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT
stage_dir="$work_dir/stage"
mkdir -p "$stage_dir/opt/qnzapret"

cp -a "$bundle_dir/qnzapret" "$bundle_dir/data" "$bundle_dir/lib" \
  "$stage_dir/opt/qnzapret/"
install -Dm0755 "$bundle_dir/libexec/qnzapret-runtime" \
  "$stage_dir/usr/libexec/qnzapret-runtime"
install -Dm0755 "$bundle_dir/libexec/qnzapret-telegram-sidecar" \
  "$stage_dir/usr/libexec/qnzapret-telegram-sidecar"
mkdir -p "$stage_dir/usr/lib/qnzapret"
cp -a "$bundle_dir/runtime" "$stage_dir/usr/lib/qnzapret/runtime"
cp -a "$bundle_dir/telegram" "$stage_dir/usr/lib/qnzapret/telegram"
mkdir -p "$stage_dir/usr/bin"
ln -s ../../opt/qnzapret/qnzapret "$stage_dir/usr/bin/qnzapret"

install -Dm0644 "$repo_dir/packaging/linux/systemd/qnzapret-runtime.service" \
  "$stage_dir/usr/lib/systemd/system/qnzapret-runtime.service"
install -Dm0644 "$repo_dir/packaging/linux/systemd/qnzapret-telegram.service" \
  "$stage_dir/usr/lib/systemd/user/qnzapret-telegram.service"
install -Dm0644 "$repo_dir/packaging/linux/dbus/dev.qnzapret.Runtime1.service" \
  "$stage_dir/usr/share/dbus-1/system-services/dev.qnzapret.Runtime1.service"
install -Dm0644 "$repo_dir/packaging/linux/dbus/dev.qnzapret.Telegram1.service" \
  "$stage_dir/usr/share/dbus-1/services/dev.qnzapret.Telegram1.service"
install -Dm0644 "$repo_dir/packaging/linux/dbus/dev.qnzapret.Runtime1.conf" \
  "$stage_dir/usr/share/dbus-1/system.d/dev.qnzapret.Runtime1.conf"
install -Dm0644 "$repo_dir/packaging/linux/polkit/dev.qnzapret.runtime.policy" \
  "$stage_dir/usr/share/polkit-1/actions/dev.qnzapret.runtime.policy"
install -Dm0644 "$repo_dir/packaging/linux/sysusers/qnzapret.conf" \
  "$stage_dir/usr/lib/sysusers.d/qnzapret.conf"
install -Dm0644 "$repo_dir/packaging/linux/tmpfiles/qnzapret.conf" \
  "$stage_dir/usr/lib/tmpfiles.d/qnzapret.conf"
install -Dm0644 "$repo_dir/packaging/linux/dev.qnzapret.desktop" \
  "$stage_dir/usr/share/applications/dev.qnzapret.desktop"
install -Dm0644 "$repo_dir/packaging/linux/dev.qnzapret.metainfo.xml" \
  "$stage_dir/usr/share/metainfo/dev.qnzapret.metainfo.xml"
install -Dm0644 "$repo_dir/linux/runner/resources/app_icon.png" \
  "$stage_dir/usr/share/icons/hicolor/256x256/apps/dev.qnzapret.png"
install -Dm0644 "$repo_dir/docs/linux_runtime_handoff.md" \
  "$stage_dir/usr/share/doc/qnzapret/linux_runtime_handoff.md"
install -Dm0644 "$repo_dir/docs/linux_packaging.md" \
  "$stage_dir/usr/share/doc/qnzapret/linux_packaging.md"
install -Dm0644 \
  "$repo_dir/runtime/assets/qnzapret/THIRD_PARTY_NOTICES.md" \
  "$stage_dir/usr/share/doc/qnzapret/THIRD_PARTY_NOTICES.md"

mkdir -p "$output_dir"

deb_root="$work_dir/deb"
cp -a "$stage_dir/." "$deb_root/"
mkdir -p "$deb_root/DEBIAN"
installed_size=$(du -sk "$stage_dir" | awk '{print $1}')
cat >"$deb_root/DEBIAN/control" <<EOF
Package: qnzapret
Version: $version
Section: net
Priority: optional
Architecture: amd64
Maintainer: QNZapret <dev@qnix.dev>
Installed-Size: $installed_size
Depends: nftables, python3, python3-cryptography, systemd, polkitd | policykit-1, libgtk-3-0 | libgtk-3-0t64, libglib2.0-0, libstdc++6
Description: QNZapret desktop DPI-bypass client
 Unprivileged Flutter UI with a Polkit-authorized NFQUEUE runtime.
EOF
for script in postinst prerm postrm; do
  install -m0755 "$repo_dir/packaging/linux/scripts/$script" \
    "$deb_root/DEBIAN/$script"
done

deb_path="$output_dir/qnzapret_${version}_amd64.deb"
if command -v dpkg-deb >/dev/null 2>&1; then
  dpkg-deb --root-owner-group --build "$deb_root" "$deb_path"
else
  deb_build="$work_dir/deb-build"
  mkdir -p "$deb_build"
  printf '2.0\n' >"$deb_build/debian-binary"
  tar --owner=0 --group=0 -C "$deb_root/DEBIAN" -cJf \
    "$deb_build/control.tar.xz" .
  tar --owner=0 --group=0 --exclude=DEBIAN -C "$deb_root" -cJf \
    "$deb_build/data.tar.xz" .
  (
    cd "$deb_build"
    ar rcs "$deb_path" debian-binary control.tar.xz data.tar.xz
  )
fi

rpm_top="$work_dir/rpmbuild"
mkdir -p "$rpm_top"/{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}
tar --owner=0 --group=0 -C "$stage_dir" -czf \
  "$rpm_top/SOURCES/qnzapret-stage.tar.gz" .
cp "$repo_dir/packaging/linux/qnzapret.spec" "$rpm_top/SPECS/"
rpmbuild --define "_topdir $rpm_top" \
  -bb "$rpm_top/SPECS/qnzapret.spec"
find "$rpm_top/RPMS" -name '*.rpm' -exec cp {} "$output_dir/" \;

mapfile -d '' rpm_paths < <(
  find "$output_dir" -maxdepth 1 -type f \
    -name "qnzapret-${version}-*.rpm" -print0
)
if (( ${#rpm_paths[@]} == 0 )); then
  echo "RPM artifact was not created for version $version." >&2
  exit 1
fi
sha256sum "$deb_path" "${rpm_paths[@]}"

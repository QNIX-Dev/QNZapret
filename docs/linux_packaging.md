# Linux packaging

## Supported packages

The production pipeline generates:

- `.deb` for Debian 12 and Ubuntu 24.04+ (`amd64`);
- `.rpm` for Fedora 44 (`x86_64`);
- a relocatable Flutter bundle as the input to both packages.

Build:

```bash
packaging/linux/build_bundle_debian12.sh
bash packaging/linux/build_packages.sh
```

The bundle is built in a temporary source copy inside Debian 12 from a
read-only repository mount. Only the completed bundle is copied back. This
keeps glibc compatibility and prevents container paths from leaking into the
host `.dart_tool/package_config.json`; host `flutter analyze --no-pub` and
`flutter test --no-pub` remain usable immediately after the build.

Artifacts are written to `build/linux/packages/` and printed with SHA256.

## Installed layout

```text
/opt/qnzapret/
  qnzapret
  data/
  lib/
/usr/bin/qnzapret -> /opt/qnzapret/qnzapret
/usr/libexec/qnzapret-runtime
/usr/libexec/qnzapret-telegram-sidecar
/usr/lib/qnzapret/runtime/
  bin/nfqws2
  lua/
  lists/
  payloads/
  manifest.sha256
  provenance.json
/usr/lib/qnzapret/telegram/proxy/
/usr/lib/systemd/system/qnzapret-runtime.service
/usr/lib/systemd/user/qnzapret-telegram.service
/usr/share/dbus-1/system-services/dev.qnzapret.Runtime1.service
/usr/share/dbus-1/services/dev.qnzapret.Telegram1.service
/usr/share/dbus-1/system.d/dev.qnzapret.Runtime1.conf
/usr/share/polkit-1/actions/dev.qnzapret.runtime.policy
/usr/share/applications/dev.qnzapret.desktop
/usr/share/metainfo/dev.qnzapret.metainfo.xml
```

Runtime dependencies:

- systemd and D-Bus;
- nftables;
- Polkit;
- Python 3 and distribution `python3-cryptography` for the pinned official
  Telegram headless core;
- GTK/Flutter libraries bundled with the app.

Python was retained only for the upstream MIT Telegram protocol core: it avoids
a new clean-room MTProxy cryptography implementation and keeps behavior aligned
with the pinned upstream. There is no network install, virtualenv creation,
tray UI or interpreter download at runtime.

## Installation

Debian/Ubuntu:

```bash
sudo apt install ./build/linux/packages/qnzapret_0.0.6_amd64.deb
```

Fedora:

```bash
sudo dnf install ./build/linux/packages/qnzapret-0.0.6-1.*.x86_64.rpm
```

Package installation may ask for root. Normal app use must not.

## Package lifecycle

Post-install:

- creates locked system user `qnzapret-runtime`;
- creates `/run/qnzapret` and `/var/lib/qnzapret`;
- reloads systemd and active user managers, so an upgrade cannot retain an
  obsolete Telegram sandbox definition;
- leaves runtime stopped until D-Bus activation or user Start.

Remove:

- stops `qnzapret-runtime.service`;
- calls the QNZapret-only cleanup helper;
- attempts to stop active user sidecars;
- removes packaged files;
- preserves user XDG settings and Telegram secret.

Purge additionally removes the system user and empty system state directory.
Neither path deletes user preferences automatically or modifies foreign
firewall tables.

## Package QA

Static:

```bash
desktop-file-validate packaging/linux/dev.qnzapret.desktop
appstreamcli validate --no-net packaging/linux/dev.qnzapret.metainfo.xml
systemd-analyze verify packaging/linux/systemd/qnzapret-runtime.service
systemd-analyze --user verify \
  packaging/linux/systemd/qnzapret-telegram.service
```

Artifact inspection:

```bash
rpm -qpl build/linux/packages/*.rpm
rpm -qpR build/linux/packages/*.rpm
ar t build/linux/packages/*.deb
cd /usr/lib/qnzapret/runtime && sha256sum -c manifest.sha256
```

Manual clean-VM smoke must verify Start with Polkit, actual HTTP/HTTPS/QUIC,
target resources, Telegram setup/bridge, GUI restart snapshot restoration,
Stop, forced `nfqws2` crash, reboot recovery and uninstall cleanup.

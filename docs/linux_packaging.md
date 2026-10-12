# Linux: сборка и установка

Срез ветки `quriee`, 12 октября 2026 года. Packaging предназначен для Linux
x86_64 с systemd: DEB (Debian 12/Ubuntu 24.04+) и RPM (Fedora 44).
Текущая поставка проверена на Ubuntu 26.04; остальные clean-VM smoke — отдельно.

## Сборка

Локально:

```bash
flutter pub get
flutter build linux --release
bash packaging/linux/build_packages.sh
```

Нужны C++/clang, CMake, Ninja, pkg-config, GTK3/GIO development packages,
Python 3 и release Flutter SDK. Packaging script собирает **оба** формата:
требует `rpmbuild` даже если нужен только DEB. Для DEB используется `dpkg-deb`
или fallback ar/tar. Выход — `build/linux/packages/`, SHA256 выводятся в журнал.
Версия в script/spec/метаданных пакета сейчас `0.0.6`.

Локальный bundle наследует glibc baseline хоста. Для Debian 12-compatible
bundle используйте Podman и реальный путь к SDK:

```bash
export QNZAPRET_FLUTTER_DIR=/absolute/path/to/flutter
bash packaging/linux/build_bundle_debian12.sh
bash packaging/linux/build_packages.sh
```

По умолчанию script ищет SDK в `~/.dev/flutter`. Исходники монтируются read-only,
в Debian 12 создаётся временная копия без `.git`, `.dart_tool` и build; обратно
копируется bundle. Host package_config не заменяется контейнерными путями.
Нужны сеть и место для SDK/dependencies. Скрипт монтирует SDK в контейнер;
использовать рабочую копию SDK, которую допустимо обновлять Flutter tooling.

## Установленный состав

```text
/opt/qnzapret/{qnzapret,data/,lib/}
/usr/bin/qnzapret -> ../../opt/qnzapret/qnzapret
/usr/libexec/qnzapret-runtime
/usr/libexec/qnzapret-telegram-sidecar
/usr/libexec/qnzapret-component-updater.py
/usr/lib/qnzapret/runtime/{bin/,lua/,lists/,payloads/,manifest.sha256,provenance.json}
/usr/lib/qnzapret/telegram/proxy/
/usr/lib/qnzapret/telegram/adapter/qnzapret_telegram.py
/usr/lib/systemd/system/qnzapret-runtime.service
/usr/lib/systemd/user/qnzapret-telegram.service
/usr/share/dbus-1/system-services/dev.qnzapret.Runtime1.service
/usr/share/dbus-1/services/dev.qnzapret.Telegram1.service
/usr/share/dbus-1/system.d/dev.qnzapret.Runtime1.conf
/usr/share/polkit-1/actions/dev.qnzapret.runtime.policy
/usr/share/applications/dev.qnzapret.desktop
/usr/share/metainfo/dev.qnzapret.metainfo.xml
/usr/share/doc/qnzapret/
```

System state: `/run/qnzapret` (0750), `/var/lib/qnzapret` (0755), cache в
`/var/lib/qnzapret/components`. User state: systemd StateDirectory или XDG,
0700; secret и health — 0600. Обновления не переписывают package-owned `/usr/lib`.

Зависимости runtime: systemd, D-Bus, nftables, Polkit, Python 3, cryptography,
certifi, httpx, h2, ca-certificates, GTK3/GLib и стандартные runtime libraries.
Это системные пакеты, без pip install/venv/download interpreter на старте.
Точные DEB/RPM dependency expressions находятся в build script/spec.

## Установка и обновление пакета

```bash
sudo apt install ./build/linux/packages/qnzapret_0.0.6_amd64.deb
# Fedora:
sudo dnf install ./build/linux/packages/qnzapret-0.0.6-1.*.x86_64.rpm
```

GUI запускать обычным пользователем. Start/Stop используют Polkit; не выдавать
приложению постоянный sudo и не запускать Flutter GUI от root.

Post-install создаёт locked account `qnzapret-runtime`, state directories и
перечитывает systemd/system + активные user managers. Новая установка сама
не включает перехват. Snapshot активирует helpers через D-Bus; Start запускает
worker. Подробности: [Linux runtime](linux_runtime_handoff.md).

**DEB upgrade не перезапускает живые helpers автоматически**: prerm останавливает
их только при remove/purge, postinst выполняет daemon-reload. Для применения
нового кода: Stop в GUI, закрыть GUI, остановить старые helpers, затем открыть
новый GUI/Start. При остановленных сервисах:

```bash
sudo systemctl stop qnzapret-runtime.service
systemctl --user stop qnzapret-telegram.service
```

Это отличается от автообновления компонентов при Start; последнее не обновляет
GUI/system helper. RPM использует systemd post/preun/postun macros; его upgrade
нужно проверять отдельно на Fedora.

Remove останавливает system runtime, очищает свою nft table и пытается остановить
active user sidecars; user preferences/secret сохраняются. DEB purge удаляет
account и **только пустой** `/var/lib/qnzapret`: заполненный cache автоматически
не стирается. Чужой firewall, hosts и DNS не меняются.

## Проверки пакетов

```bash
bash packaging/linux/tests/verify_packaging.sh
desktop-file-validate packaging/linux/dev.qnzapret.desktop
appstreamcli validate --no-net packaging/linux/dev.qnzapret.metainfo.xml
systemd-analyze verify packaging/linux/systemd/qnzapret-runtime.service
systemd-analyze --user verify packaging/linux/systemd/qnzapret-telegram.service
dpkg-deb --info build/linux/packages/qnzapret_0.0.6_amd64.deb
rpm -qpl build/linux/packages/*.rpm
rpm -qpR build/linux/packages/*.rpm
(cd /usr/lib/qnzapret/runtime && sha256sum -c manifest.sha256)
```

Артефакт, собранный на новой Ubuntu, не объявлять проверенным для Debian 12.
Чистая VM должна подтвердить dependencies, Polkit Start, HTTP/HTTPS/QUIC,
Telegram setup/bridge, upgrade, crash, reboot и uninstall. Полный [QA](integration_workflow.md).

# QNZapret

QNZapret — Flutter-приложение для локального обхода региональных сетевых
ограничений. Android использует VPN-перенаправление без root, Linux —
привилегированный NFQUEUE runtime с отдельным непривилегированным интерфейсом.
Удалённый VPN-сервер для этих основных маршрутов не требуется.

Состояние документации сверено с веткой `quriee` **12 октября 2026 года**.
Реализованный механизм не гарантирует доступность каждого ресурса у любого
провайдера: работоспособность проверяется на конкретной сети.

## Платформы

| Платформа | Реализация | Проверки текущего состояния |
| --- | --- | --- |
| Android | Kotlin `VpnService`, `hev-socks5-tunnel`, локальный SOCKS5 strategy proxy, Kotlin MTProxy для Telegram | Release AAB собирается; новый прогон на телефоне требуется отдельно |
| Linux x86_64 | C++/GIO daemon, Polkit, nftables/NFQUEUE, `nfqws2`, пользовательский Python Telegram sidecar | DEB установлен и проверен на Ubuntu 26.04; HTTP/TLS, Telegram bridge и аварийная очистка проверены |
| Windows | Flutter runner и `StubProxyRuntime` | Реального обхода нет; сборка выполняется на Windows/CI |

Цели Linux-упаковки — Debian 12, Ubuntu 24.04+ и Fedora 44 с systemd.
Проверка текущей сборки в чистых Debian/Fedora ещё нужна перед публичным релизом.

## Устройство

```text
Flutter Home / Logs / Settings
  -> Riverpod RuntimeController
  -> ProxyRuntimeController / ProxyRuntime
     Android -> MethodChannel -> Kotlin VPN/TUN/SOCKS5 runtime
     Linux   -> async MethodChannel/EventChannel -> D-Bus -> daemon + sidecar
     Windows -> stub
```

В Android TUN включён по умолчанию. Исходящие соединения защищены
`VpnService.protect`, собственный пакет исключён из VPN. HTTP/TLS hostlists
включают действия обхода, а не запрещают остальной трафик. QUIC Initial
обрабатывается отдельно. Linux компилирует переносимый профиль в собственную
стратегию для `nfqws2`; полная эквивалентность Android socket-действий не заявляется.

Интерфейс содержит управление сервисами, журнал событий, системную/светлую/тёмную
тему и шесть палитр. Linux получает native-события; Android пока использует
snapshot и локальные события контроллера, а подробную диагностику пишет в logcat.

## Telegram и обновления Linux

Linux Start запрашивает Polkit, проверяет обновления компонентов, запускает
NFQUEUE и Telegram sidecar, затем передаёт Telegram профиль `127.0.0.1:1443`.
Первое подключение подтверждается штатным диалогом Telegram, в том числе до
входа в аккаунт. Кнопка «Настроить Telegram» повторяет передачу профиля.
«Подключён» на Linux означает живой MTProto+WS/H2 bridge, а не только listener.

В поставке: [zapret2 v1.0.5.2](https://github.com/bol-van/zapret2/releases/tag/v1.0.5.2)
и [tg-ws-proxy v1.11.2](https://github.com/Flowseal/tg-ws-proxy/releases/tag/v1.11.2).
Обновлятор проверяет официальные stable-релизы, целостность и совместимость,
публикует версии атомарно в `/var/lib/qnzapret/components`. Ошибка проверки
оставляет локальную версию. Он не обновляет GUI, Android, Windows или hostlists.
Подробности: [обновления Linux](docs/linux_component_updates.md).

Android имеет отдельную Kotlin-реализацию Telegram и не запускает Python/Go-core.
Её route-provider может обновлять данные маршрутов, но не код компонентов.

## Разработка

```bash
git clone --branch quriee --recurse-submodules https://github.com/QNIX-Dev/QNZapret.git
cd QNZapret
flutter pub get
flutter devices
flutter run -d <ANDROID_DEVICE_ID>
```

Для существующей копии: `git submodule update --init qndocs`.
Инструкции агента начинаются с [AGENTS.md](AGENTS.md). Подмодуль `qndocs`
может требовать отдельного доступа к репозиторию документации.

Linux shell можно запускать через `flutter run -d linux`, но полноценный runtime
требует установленного системного пакета. **Не запускайте GUI через sudo.**

```bash
flutter build linux --release
bash packaging/linux/build_packages.sh
sudo apt install ./build/linux/packages/qnzapret_0.0.6_amd64.deb
qnzapret
```

Для переносимого baseline Debian 12 используйте контейнерную сборку,
описанную в [Linux packaging](docs/linux_packaging.md), вместо локального bundle.
Windows запускается/собирается на Windows-хосте. Android release пока использует
debug signing и не готов к публикации в магазине.

## Проверки

```bash
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build linux --release
ctest --test-dir build/linux/x64/release --output-on-failure
bash packaging/linux/tests/verify_packaging.sh
sudo test/integration/linux_netns_runtime_test.sh
flutter build appbundle --release
```

Android JVM: из `android/` выполнить `./gradlew :app:testDebugUnitTest`
(Windows: `.\gradlew.bat :app:testDebugUnitTest`).
Полный порядок, условия smoke и ограничения — в
[процессе разработки и QA](docs/integration_workflow.md).

## Документация и ограничения

[Оглавление документации](docs/README.md) содержит контракты, платформенные
инструкции, известные ограничения и результаты последнего Linux-прогона.
Версии сейчас расходятся: Flutter/Linux — `0.0.6+6`, Android и подпись в UI —
`0.0.1f`, build `1`; это известный долг, а не единая версия релиза.

Лицензии сторонних компонентов и границы происхождения hostlists описаны в
[уведомлениях](runtime/assets/qnzapret/THIRD_PARTY_NOTICES.md).
Единая лицензия собственного кода в корне пока не объявлена.

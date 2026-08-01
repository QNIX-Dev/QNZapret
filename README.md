# QNZapret

QNZapret - кроссплатформенный клиент для локального перенаправления трафика и применения DPI-bypass стратегий.
Приложение собрано как Flutter-продукт с нативными Android и Linux runtime за стабильным Dart-контрактом.

Если совсем на пальцах: QNZapret поднимает локальный Android VPN, забирает трафик устройства, пропускает его через локальный strategy proxy, применяет правила обхода и открывает реальные сетевые соединения из приложения через защищенные Android sockets. Для основного Android-пути не нужен удаленный VPN-сервер.

## Что Делает Приложение

- Дает пользовательский Flutter-интерфейс на Material 3.
- Использует Android `VpnService` для локального перехвата трафика.
- Передает TUN-трафик в локальный SOCKS5 strategy proxy через `hev-socks5-tunnel`.
- Применяет HTTP, TLS и QUIC-ориентированные правила из общего `StrategyProfile`.
- Не блокирует весь трафик: если поток не попал под правила, он идет direct.
- Дает Android foreground notification, actions и Quick Settings tile для управления.
- Поднимает Telegram compatibility mode через локальный Kotlin MTProxy endpoint.

## Как Устроен Android Runtime

Основной Android-путь выглядит так:

```text
Flutter UI
  -> Dart ProxyRuntime contract
  -> Android MethodChannel
  -> QnzapretVpnService
  -> Android TUN fd
  -> hev-socks5-tunnel
  -> local strategy SOCKS5 proxy
  -> protected TCP/UDP sockets
```

Приложение исключает собственный пакет из VPN и использует `VpnService.protect(...)` для runtime-соединений. Это нужно, чтобы исходящий трафик самого QNZapret не зацикливался обратно в туннель.

Нативный runtime на Android написан на Kotlin. Компонент `hev-socks5-tunnel` используется как отдельный TUN-to-SOCKS слой: он переносит пакеты из Android TUN в локальный SOCKS5 proxy, а стратегия и lifecycle остаются в коде QNZapret.

## Telegram Mode

Telegram требует отдельного режима, потому что на некоторых сетях прямой TCP к Telegram DC может блокироваться еще до первого payload. В таком случае обычные payload-level fake/split стратегии просто не успевают включиться.

QNZapret использует compatibility mode:

```text
Telegram client
  -> local MTProxy 127.0.0.1:1443
  -> QNZapret Kotlin MTProxy bridge
  -> WSS /apiws route
  -> Telegram Web/DC endpoint
```

Android не позволяет другому приложению молча включить proxy внутри Telegram. Поэтому при первом подключении QNZapret открывает экран подтверждения proxy в Telegram с уже заполненными локальным endpoint и secret. Пользователь подтверждает один раз, после чего QNZapret может поднимать локальный proxy вместе с сервисом.

Маршруты Telegram могут приходить из:

- локального `telegram_compat.json` для dev/smoke или приватного route;
- cached public Flowseal-compatible Cloudflare route defaults;
- будущего signed QNZapret route config.

Для пользователя это локальный режим: ему не нужно владеть SOCKS/VPN-сервером.

## Strategy Layer

Дефолтная lightweight-стратегия описана общей Dart/Kotlin моделью:

- HTTP TCP/80 правила для доменов из списков;
- TLS TCP/443 правила с поддержкой TLS record split;
- QUIC UDP/443 обработка QUIC Initial пакетов;
- direct forwarding для трафика вне strategy lists.

Hostlists - это списки включения bypass-действий, а не allowlist всего соединения. Если TCP-поток не совпал с правилами, он должен пройти обычным protected socket без fake/split.

## UI И Продуктовый Слой

Flutter-часть дает:

- главный экран со статусами runtime и start/stop CTA;
- экран логов, готовый к native diagnostics stream;
- настройки темы, палитры и информации о приложении;
- Material 3 / Expressive визуальную систему;
- общий `ProxyRuntimeController`, через который UI общается с backend.

UI не вызывает Android-код напрямую. Экранный слой работает через `lib/core/backend/`, а Android/Linux/Windows детали остаются внутри platform adapters.

## Статус Платформ

| Платформа | Статус |
| --- | --- |
| Android | Нативный runtime path через `VpnService`, TUN-to-SOCKS, локальный strategy proxy и Telegram compatibility mode. |
| Linux | Production runtime для x86_64: непривилегированный GUI, system D-Bus daemon, Polkit, nftables/NFQUEUE, bundled `nfqws2` и user-session Telegram sidecar. |
| Windows | Flutter runner есть; реальный runtime adapter запланирован. |

## Карта Репозитория

```text
lib/
  app/                  Flutter app shell, navigation и theme
  core/backend/         общий runtime contract и Android/Linux adapters
  core/state/           Riverpod application state и runtime view models
  core/ui/              design tokens и общие UI components
  features/             Home, Logs и Settings screens

android/app/src/main/
  kotlin/dev/qnzapret/  Android runtime, VPN service, strategy proxy и Telegram mode
  jni/                  native hev-socks5-tunnel integration
runtime/assets/qnzapret/ canonical hostlists, payloads, nfqws2/Lua и provenance

linux/runtime/          system daemon, Telegram sidecar и strategy compiler
packaging/linux/        systemd, D-Bus, Polkit, AppStream, deb/rpm pipeline

docs/                   архитектура, runtime contracts и Android handoff
qndocs/                 branch-specific agent/workflow documentation
```

## Запуск И Сборка

Установите Flutter и Android tooling, затем подтяните зависимости:

```bash
flutter pub get
```

Запуск на Android:

```bash
flutter run -d android
```

Сборка Android artifacts:

```bash
flutter build apk --release
flutter build appbundle --release
```

Запуск desktop-shell:

```bash
flutter run -d linux
flutter run -d windows
```

Windows-сборку нужно делать на Windows-хосте.

Production Linux bundle и пакеты:

```bash
# Для glibc-портируемого production bundle используйте Debian 12:
bash packaging/linux/build_bundle_debian12.sh
bash packaging/linux/build_packages.sh
```

Поддерживаемый Linux production scope: x86_64, systemd, Fedora 44,
Debian 12 и Ubuntu 24.04+. Установленный GUI всегда запускается обычным
пользователем. `Start`/`Stop` обращаются к `dev.qnzapret.Runtime1`; Polkit
запрашивает административное подтверждение, а GUI не выполняет `sudo`,
`pkexec`, `nft` или `systemctl`.

Linux использует pinned zapret2 `v0.9.5.2`: HTTP/TLS/QUIC payload filters,
post-NAT NFQUEUE topology `101/-101`, mark/notrack protection and fail-open
`queue 200 bypass`. UI distinguishes queue registration, installed nft rules,
ready interception and Telegram degraded state; process existence alone is not
reported as a healthy connection.

Диагностика Linux:

```bash
systemctl status qnzapret-runtime.service
journalctl -u qnzapret-runtime.service
systemctl --user status qnzapret-telegram.service
journalctl --user -u qnzapret-telegram.service
nft list table inet qnzapret
```

## Проверки

Базовый набор для разработки:

```bash
flutter analyze
flutter test
ctest --test-dir build/linux/x64/release --output-on-failure
sudo test/integration/linux_netns_runtime_test.sh
cd android && ./gradlew :app:testDebugUnitTest
flutter build apk --release
```

Для Android runtime device smoke важен не меньше unit-тестов. Основные logcat-теги:

- `QNZapretService`
- `QNZapretTun`
- `QNZapretProxy`
- `QNZapretNetTest`
- `QNZapretTgCompat`

## Документация

Сначала сюда:

- `docs/project_brief.md` - общий обзор продукта и архитектуры.
- `docs/runtime_bridge_contract.md` - контракт между Flutter и native runtime.
- `docs/android_runtime_handoff.md` - состояние Android runtime и handoff.
- `docs/linux_runtime_handoff.md` - Linux privilege boundary, lifecycle и recovery.
- `docs/linux_packaging.md` - структура `.deb`/`.rpm`, установка и удаление.
- `docs/android_telegram_cloudflare_routes.md` - Telegram compatibility routing.
- `docs/integration_workflow.md` - workflow совместной frontend/backend разработки.

## Для Контрибьюторов

- Держите UI за Dart runtime contract; не вызывайте Android runtime classes из виджетов.
- Оставляйте Android-specific поведение внутри native adapter/service layer.
- Обновляйте docs при изменении runtime contracts, lifecycle semantics или платформенных зон ответственности.
- Не коммитьте приватные route domains, relay credentials и smoke-only secrets.
- Используйте `.sources/` как reference-зону, а не production-код.

QNZapret задуман как нормальный продукт, а не набор скриптов с кнопкой сверху. Кнопка, конечно, тоже есть. Просто под ней действительно живет runtime.

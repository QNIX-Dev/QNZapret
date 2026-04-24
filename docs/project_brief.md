# Project Brief

## Назначение документа

Этот файл нужен как короткая общая точка входа для frontend- и backend-разработки.
Он не заменяет рабочий регламент проекта.
Главные правила разработки, QA, архитектурные ожидания и соглашения для агентов задаются в корневом `AGENTS.md`.

## Что такое QNZapret

`QNZapret` - кроссплатформенный Flutter shell для клиента с нативным runtime/backend-контуром.
Android-направление строится как no-root VPN/proxy runtime: приложение получает трафик через `VpnService`, направляет его в локальный strategy proxy и применяет собственные DPI-bypass стратегии без встраивания чужого кода.

Поддерживаемые цели в репозитории сейчас:

- Android
- Linux
- Windows

Технологический стек:

- frontend: Flutter + Dart
- backend/runtime target: платформенный runtime-контур, подключаемый через adapters
- Android runtime target: `VpnService` + local strategy proxy + userspace TUN transport

## Текущая стадия

Проект находится на стадии брендированного frontend shell с Android runtime-интеграцией, доведенной до native strategy engine.

Что уже есть:

- минимальный Flutter bootstrap в `lib/main.dart`
- корневой `MaterialApp` в `lib/app/app.dart`
- продуктовая dark-mode-first тема в `lib/app/theme/app_theme.dart`
- стартовый экран в `lib/features/home/presentation/home_screen.dart`
- общий Dart-контракт runtime в `lib/core/backend/proxy_runtime.dart`
- frontend-friendly `ProxyRuntimeController` и barrel export `lib/core/backend/backend.dart`
- composition helper `createDefaultProxyRuntime()`, который подключает Android adapter на Android и stub-адаптеры на desktop
- serializable strategy profile model для HTTP/TLS/QUIC правил
- `UnmatchedTrafficPolicy.direct` для трафика вне hostlists: списки включают desync-обработку, а не ограничивают общий доступ
- `StubProxyRuntime` для состояния, где нативный runtime еще не подключен
- `AndroidProxyRuntime` как Dart-адаптер поверх `MethodChannel`
- Android bridge в `ProxyRuntimeBridge.kt`
- Android VPN foreground service в `QnzapretVpnService.kt`
- Android strategy runtime coordinator в `QnzapretAndroidRuntime.kt`
- Android local strategy proxy lifecycle в `LocalStrategyProxy.kt`
- native strategy engine для HTTP/TLS/QUIC decisions, lazy hostlists, payload blobs и L7 detectors
- IPv4/IPv6 UDP packet codec в `IpPacketCodec.kt`
- userspace forwarder core в `TunPacketForwarder.kt` с IPv4/IPv6 UDP relay через protected `DatagramSocket`
- TUN lifecycle guard в `TunTransport.kt`, который держит IPv4/IPv6 TUN routes выключенными до готовности полного TCP/UDP forwarder
- Android assets дефолтной lightweight стратегии в `android/app/src/main/assets/qnzapret/`
- проверка наличия strategy assets на старте runtime через `StrategyAssetVerifier.kt`
- Android runtime store для snapshot-состояния в `QnzapretVpnRuntimeStore.kt`
- базовые тесты сериализации и парсинга runtime-моделей

Что еще не подключено:

- реальная socket/proxy implementation для HTTP/TLS/QUIC потоков
- TCP userspace relay/state machine между TUN fd и protected sockets
- безопасное применение split/fake decisions к TCP stream forwarding
- QUIC host correlation для применения `udpFake` не только при заранее известном host target
- поток логов из backend
- desktop bridge implementations для Linux и Windows
- полноценные runtime-контролы, пресеты и профили стратегий

## Главная архитектурная идея

Целевая схема:

`UI -> shared Dart runtime contract -> platform adapter -> native strategy runtime`

Android target path:

`Android VpnService -> TUN transport -> userspace forwarder -> local strategy proxy -> protected sockets`

Hostlists в strategy profile трактуются как списки включения DPI-bypass действий.
Если поток не совпал со списками, production local strategy proxy должен пропускать его напрямую через protected socket без fake/split действий.

Обязательные принципы:

- UI не вызывает платформенный код напрямую
- экранный слой работает через общий Dart API из `lib/core/backend/`
- Android/Linux/Windows детали остаются внутри platform adapters
- расширение runtime-поведения сначала фиксируется в контракте, потом проводится через UI и native bridge

## Текущий runtime-контракт

Актуальный контракт описан в:

- `lib/core/backend/backend.dart`
- `lib/core/backend/proxy_runtime.dart`
- `lib/core/backend/android_proxy_runtime.dart`
- `lib/core/backend/proxy_runtime_controller.dart`
- `lib/core/backend/proxy_runtime_factory.dart`
- `docs/runtime_bridge_contract.md`

Текущий публичный Dart API строится вокруг `ProxyRuntime`:

- `prepare()`
- `getSnapshot()`
- `start(ProxyLaunchConfig config)`
- `stop()`

Состояние runtime возвращается как `ProxyRuntimeSnapshot`.
Для UI-команд и отображения состояния рекомендуется использовать `ProxyRuntimeController`: он предоставляет `initialize`, `prepare`, `start`, `stop`, `refresh`, `isBusy`, `canStart`, `canStop`, `needsPrepare` и `lastFailure`.
Android сейчас использует `MethodChannel` с именем `dev.qnzapret/proxy_runtime`.

## Что важно для совместной работы frontend/backend

Пользователь этого рабочего контекста ведет backend-направление.
UI и продуктовые экраны могут развиваться отдельно вторым участником, поэтому backend-изменения должны сохранять стабильную Dart-поверхность для UI.

При изменении backend-контракта нужно обновлять:

- код в `lib/core/backend/`
- соответствующий Android bridge, если меняется wire protocol
- `docs/runtime_bridge_contract.md`
- при заметном архитектурном изменении - `AGENTS.md`

## Текущее продуктовое поведение

Стартовый экран показывает:

- направление продукта
- поддерживаемые платформы
- состояние текущего runtime bridge
- ближайшие backend-интеграционные этапы

Composition root уже передает в экран `createDefaultProxyRuntime()`: на Android это реальный `AndroidProxyRuntime`, на desktop пока stub-адаптеры.

## Ближайшая цель следующей стадии

Главная ближайшая цель - довести Android runtime path до реального native runtime/backend-контура:

1. реализовать TCP userspace relay/state machine между TUN fd и protected sockets;
2. подключить TCP split actions и QUIC host correlation к существующему strategy engine;
3. добавить production log stream поверх текущего controller/snapshot слоя;
4. после Android закрепить equivalent contract для Linux и Windows.

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
- Android runtime target: `VpnService` + `hev-socks5-tunnel` + local strategy SOCKS5 proxy

## Текущая стадия

Проект находится на стадии объединенного frontend/backend среза: продуктовый Flutter shell уже подключен к общей Dart runtime-поверхности, а Android-направление доведено до native strategy engine, `hev-socks5-tunnel` TUN-to-SOCKS path, local strategy SOCKS5 proxy и QUIC host correlation.

Что уже есть:

- минимальный Flutter bootstrap в `lib/main.dart`
- корневой `MaterialApp` в `lib/app/app.dart`
- общий application shell в `lib/app/navigation/app_shell.dart` с вкладками "Главная" и "Логи"
- floating navigation island с tap/swipe навигацией между основными вкладками
- продуктовая Material 3 Expressive theme-система в `lib/app/theme/app_theme.dart`
- 6 палитр приложения с light/dark вариантами и сохранением выбранного режима
- стартовый экран в `lib/features/home/presentation/home_screen.dart`
- экран логов в `lib/features/logs/presentation/logs_screen.dart`
- экран настроек в `lib/features/settings/presentation/settings_screen.dart`
- адаптивное открытие настроек: полноценная page route на телефонах и dialog/panel на больших экранах
- общий Dart-контракт runtime в `lib/core/backend/proxy_runtime.dart`
- frontend-friendly `ProxyRuntimeController` и barrel export `lib/core/backend/backend.dart`
- composition helper `createDefaultProxyRuntime()`, который подключает Android adapter на Android и stub-адаптеры на desktop
- Riverpod application layer в `lib/core/state/runtime_controller.dart`, который адаптирует `ProxyRuntimeSnapshot` в UI-состояния, CTA, status chips и локальные diagnostic logs
- русские пользовательские подписи runtime-состояний, CTA, логов, Android foreground notification со state text/actions остановки/перезапуска и native Quick Settings Tile для запуска/остановки
- serializable strategy profile model для HTTP/TLS/QUIC правил
- `UnmatchedTrafficPolicy.direct` для трафика вне hostlists: списки включают desync-обработку, а не ограничивают общий доступ
- `StubProxyRuntime` для состояния, где нативный runtime еще не подключен
- `AndroidProxyRuntime` как Dart-адаптер поверх `MethodChannel`
- Android bridge в `ProxyRuntimeBridge.kt`
- Android VPN foreground service в `QnzapretVpnService.kt`
- Android strategy runtime coordinator в `QnzapretAndroidRuntime.kt`
- Android local strategy proxy lifecycle в `LocalStrategyProxy.kt`
- native strategy engine для HTTP/TLS/QUIC decisions, lazy hostlists, payload blobs и L7 detectors
- QUIC host correlation в `QuicHostCorrelation.kt`: UDP/53 DNS A/AAAA responses и уже распознанный TCP HTTP/TLS host связываются с destination IP, чтобы UDP/443 QUIC Initial мог получить `knownHost`; дефолтный QUIC rule не зависит от `knownHost`, потому что Android Private DNS/DoT часто скрывает DNS path
- IPv4/IPv6 UDP packet codec и TCP segment codec в `IpPacketCodec.kt`
- локальный strategy SOCKS5 proxy в `StrategySocks5Server.kt`: принимает CONNECT и UDP_ASSOCIATE от `hev-socks5-tunnel`, применяет `StrategyRuntimeEngine`, TLS record split, best-effort TCP fake с малым hop limit, UDP fake для QUIC, открывает исходящие sockets через `VpnService.protect`, логирует TCP/UDP timing, помечает Telegram endpoint candidates, выбирает `endpointPolicies` remote relay для Telegram до direct connect и сохраняет bounded Telegram pre-connect fallback для диагностики без relay
- remote relay/proxy маршрут для Telegram в `docs/android_telegram_remote_relay_contract.md`: текущий runtime поддерживает SOCKS5 remote relay через `StrategyProfile.endpointPolicies`, чтобы прозрачный VPN-перехват мог прокинуть Telegram DC TCP stream без ручной настройки proxy в приложении Telegram; HTTPS CONNECT и production secret storage остаются TODO
- Telegram compatibility mode без Go-core/JNA: локальный Kotlin MTProxy endpoint `127.0.0.1:1443`, открытие Telegram proxy confirmation screen, clean-room WSS `/apiws` transport, Cloudflare route candidates из локального `telegram_compat.json` и public MIT Flowseal upstream defaults, DNS fallback, fetch/decode/cache route-provider, active-domain/cooldown, EWMA route scoring и one-shot WSS pool для ускорения повторных text/media sessions
- TUN lifecycle в `TunTransport.kt`: default-route поднимается дефолтным Android launch config при `establishTunnel=true`, собственный пакет приложения исключается из VPN, TUN получает DNS из выбранной underlying-сети и сообщает ее через `setUnderlyingNetworks`; TUN fd передается в MIT `hev-socks5-tunnel`, который маршрутизирует TCP/UDP в локальный strategy SOCKS5 proxy. IPv6 route добавляется только если selected underlying network реально имеет usable IPv6 address и default IPv6 route.
- controlled Android network self-test в `AndroidNetworkSelfTest.kt`: из процесса приложения логирует `QNZapretNetTest` до старта runtime/TUN и после старта TUN, проверяя plain/protected/bound TCP и protected/bound UDP DNS probe для диагностики UID/app-network blocker
- Android assets дефолтной lightweight стратегии в `android/app/src/main/assets/qnzapret/`
- проверка наличия strategy assets на старте runtime через `StrategyAssetVerifier.kt`
- Android runtime store для snapshot-состояния в `QnzapretVpnRuntimeStore.kt`
- Android launcher icons через adaptive icon resources и desktop/window icons для Linux/Windows
- базовые тесты сериализации и парсинга runtime-моделей
- Android JVM unit tests для TCP relay state, IPv4/IPv6 TCP packet codec, TLS record split transform, QUIC host correlation, strategy engine QUIC decisions, `StrategyProfile` endpoint policy codec и SOCKS5 relay client handshake
- widget-тесты для home, settings и logs layout

Что еще не подключено:

- оставшийся hardening local SOCKS5 proxy: write backpressure, лимиты сессий, counters contract для честной скорости в notification и Android device smoke при `establishTunnel=true`
- raw TCP sequence/timestamp tricks в no-root socket mode; текущий TCP path безопасно применяет TLS record split для TLS, best-effort stream split для остальных TCP payload и пробует TCP fake только как low-hop-limit socket write с безопасным пропуском при недоступной TTL/hop-limit опции
- расширенная QUIC correlation для DoH/DoT, DNS cache misses и более сложных multi-IP сценариев; базовый UDP/53 + TCP HTTP/TLS correlation уже подключен
- production поток логов из backend; текущий экран логов уже показывает application/runtime-controller events, но еще не читает native log stream
- desktop bridge implementations для Linux и Windows
- полноценные runtime-контролы, пресеты и профили стратегий

## Главная архитектурная идея

Целевая схема:

`UI -> shared Dart runtime contract -> platform adapter -> native strategy runtime`

Android target path:

`Android VpnService -> TUN fd -> hev-socks5-tunnel -> local strategy SOCKS5 proxy -> protected sockets`

Архитектурный ориентир совпадает с проверенной схемой ByeByeDPI: Android VPN-режим используется только для локального перенаправления трафика, без удаленного VPN-сервера. При этом GPL-код Android-обвязки ByeByeDPI не копируется; в проекте остается собственная Kotlin-реализация lifecycle/strategy proxy, а сторонний TUN-to-SOCKS компонент ограничен MIT `hev-socks5-tunnel`.

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
- `docs/android_runtime_handoff.md`
- `docs/android_uid_network_blocker.md`

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

Текущий UI уже оформлен как пользовательский продукт, а не презентационный стенд.

Главная вкладка показывает:

- название QNZapret и короткое пользовательское описание
- компактные status chips по реальному `ProxyRuntimeSnapshot`
- CTA запуска/остановки сервисов
- честное состояние `serviceActive`, `strategyEngineReady`, `trafficForwarderReady` и `tunnelActive`
- иллюстрацию состояния соединения без прямой зависимости от Android/Kotlin деталей

Вкладка "Логи" показывает:

- человекочитаемые события application/runtime-controller слоя
- состояние автопрокрутки
- количество строк
- текущий runtime status
- терминальный блок, готовый к подключению production log stream из backend

Экран настроек показывает:

- бренд и версию приложения
- выбор режима темы: системная, светлая, темная
- выбор цветовой палитры
- блок "О приложении" и внешние CTA

Composition root создает `createDefaultProxyRuntime()` и передает его через `proxyRuntimeProvider`: на Android это реальный `AndroidProxyRuntime`, на desktop пока stub-адаптеры. UI работает через `RuntimeController` / `ProxyRuntimeController` и не вызывает Kotlin/Android детали напрямую.

## Ближайшая цель следующей стадии

Главная ближайшая цель - довести Android runtime path до реального native runtime/backend-контура:

1. добить оставшийся local proxy hardening: write backpressure, лимиты сессий, диагностика и Android device smoke при `establishTunnel=true`;
2. добавить production log stream поверх текущего controller/snapshot слоя;
3. расширить QUIC correlation за пределы UDP/53 и prior TCP HTTP/TLS hints;
4. после Android закрепить equivalent contract для Linux и Windows.

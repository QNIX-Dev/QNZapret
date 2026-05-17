# Project Structure

## Назначение документа

Этот файл описывает текущую рабочую структуру проекта.
Он должен помогать frontend- и backend-разработчикам быстро понять, где проходит ответственность каждого слоя.

## Общая карта

```text
docs/
  android_telegram_remote_relay_contract.md
  android_telegram_cloudflare_routes.md
  android_telegram_tg_ws_proxy_research.md
  android_runtime_handoff.md
  android_uid_network_blocker.md
  integration_workflow.md
  project_brief.md
  project_structure.md
  runtime_bridge_contract.md

lib/
  main.dart
  app/
    app.dart
    navigation/
      app_shell.dart
      floating_navigation_bar.dart
    routing/
      app_destination.dart
    theme/
      app_theme.dart
  core/
    app_metadata.dart
    backend/
      backend.dart
      android_proxy_runtime.dart
      proxy_runtime.dart
      proxy_runtime_controller.dart
      proxy_runtime_factory.dart
    motion/
      app_motion.dart
    persistence/
      shared_preferences_provider.dart
    state/
      app_settings_controller.dart
      package_info_provider.dart
      runtime_controller.dart
      runtime_view_models.dart
    ui/
      app_backdrop.dart
      design_tokens.dart
      components/
        connected_flow_illustration.dart
        palette_preview_card.dart
        premium_cta_button.dart
        settings_section_card.dart
        staggered_reveal.dart
        status_chip.dart
        terminal_illustration.dart
        terminal_surface.dart
  features/
    home/
      presentation/
        home_screen.dart
    logs/
      presentation/
        logs_screen.dart
    settings/
      presentation/
        settings_screen.dart

assets/
  branding/
    app_icon/
      qnzapret_app_icon_source.png
      generated/qnzapret_app_icon_fullbleed.png
    logo/
      qnzapret_logo.png

android/
  app/
    src/main/
      AndroidManifest.xml
      assets/qnzapret/
        lists/
          list-general.txt
          list-google.txt
          list-user.txt
        payloads/
          quic_initial_www_google_com.bin
          tls_clienthello_www_google_com.bin
      jni/
        Android.mk
        Application.mk
        hev-socks5-tunnel/
      kotlin/dev/qnzapret/
        AndroidNetworkSelfTest.kt
        HostlistMatcher.kt
        IpPacketCodec.kt
        L7Detectors.kt
        LocalStrategyProxy.kt
        MainActivity.kt
        ProxyRuntimeBridge.kt
        QuicHostCorrelation.kt
        QnzapretQuickSettingsTileService.kt
        QnzapretAndroidRuntime.kt
        QnzapretVpnRuntimeStore.kt
        QnzapretVpnService.kt
        StrategyAssetStore.kt
        StrategyAssetVerifier.kt
        StrategyProfile.kt
        StrategyProfileDevOverrides.kt
        StrategyRuntimeEngine.kt
        StrategyRuntimePlan.kt
        StrategySocks5Server.kt
        Socks5RelayClient.kt
        TcpRelayState.kt
        TelegramCloudflareResolver.kt
        TelegramCompatibilityProxyManager.kt
        TelegramMtProxyCrypto.kt
        TelegramRouteConfigProvider.kt
        TelegramWebSocketTransport.kt
        TProxyService.kt
        TlsRecordSplitTransform.kt
        TunTransport.kt
        UnderlyingNetworkSelector.kt
        VpnRuntimeConfig.kt
    src/test/
      kotlin/dev/qnzapret/
        IpPacketCodecTest.kt
        QuicHostCorrelationTest.kt
        Socks5RelayClientTest.kt
        StrategyProfileCodecTest.kt
        StrategyRuntimeEngineTest.kt
        TcpRelayStateTest.kt
        TelegramRouteDomainCodecTest.kt
        TlsRecordSplitTransformTest.kt

linux/
  runner/resources/app_icon.png
windows/
test/
```

## Слой `lib/main.dart`

Минимальный bootstrap приложения.
Здесь создается runtime через `createDefaultProxyRuntime()` и передается в приложение через Riverpod override `proxyRuntimeProvider`.
Также здесь поднимается `SharedPreferences` и передается через `sharedPreferencesProvider`.
Логика фич и platform-specific детали здесь жить не должны.

## Слой `lib/app/`

Назначение:

- app-level composition
- корневой `MaterialApp`
- общая тема приложения
- shell-навигация
- top-level routing между Home/Logs
- adaptive settings route

Текущие файлы:

- `lib/app/app.dart`
  Корневая конфигурация приложения.
  Читает настройки темы из `AppSettingsController` и открывает `AppShell`.
- `lib/app/navigation/app_shell.dart`
  Общий shell приложения: `PageView`, swipe/tap navigation между Home и Logs, settings route и safe-area/layout padding.
- `lib/app/navigation/floating_navigation_bar.dart`
  Плавающий navigation island с blur-surface, focus pill и haptic feedback.
- `lib/app/routing/app_destination.dart`
  Перечень основных вкладок приложения.
- `lib/app/theme/app_theme.dart`
  Общая визуальная система, 6 цветовых палитр, light/dark варианты, typography, Material 3 theme extensions.

Текущий UI намеренно оформлен как продуктовый shell, а не стандартное Flutter demo или презентационный стенд.
Без явной необходимости этот слой лучше не перестраивать, потому что UI-направление уже связано с runtime-состояниями и responsive layout.

## Слой `lib/core/backend/`

Это главная зона runtime-контрактов и platform adapters.

Файлы:

- `proxy_runtime.dart`
  Общий Dart-контракт runtime:
  - `ProxyPlatform`
  - `ProxyRuntimeState`
  - `ProxyPrepareResult`
  - `ProxyLaunchConfig`
  - `StrategyProfile`
  - `StrategyEndpointPolicy`
  - `StrategyEndpointRoute`
  - `StrategyRelayAuth`
  - `StrategyRule`
  - `StrategyAction`
  - `UnmatchedTrafficPolicy`
  - `ProxyRuntimeSnapshot`
  - `ProxyRuntime`
  - `StubProxyRuntime`
- `android_proxy_runtime.dart`
  Android adapter поверх `MethodChannel`.
- `proxy_runtime_controller.dart`
  Frontend-friendly controller над `ProxyRuntime`: хранит snapshot, busy/error state, default launch config и команды `initialize`, `prepare`, `start`, `stop`, `refresh`.
- `proxy_runtime_factory.dart`
  Composition helper, который выбирает Android adapter на Android и stub-реализации на desktop-платформах.
- `backend.dart`
  Barrel export для UI и application layer, чтобы фронтендеру не нужно было собирать backend imports по одному файлу.

Правила:

- UI должен зависеть от общего Dart API, а не от Kotlin/Android деталей.
- Новые platform adapters нужно добавлять за тем же контрактом или через его осознанное расширение.
- Если контракт меняется, одновременно обновляются код, `docs/runtime_bridge_contract.md` и при необходимости `AGENTS.md`.

## Слой `lib/core/state/`

Это application-state прослойка между runtime contract, persistence и UI.

Файлы:

- `app_settings_controller.dart`
  Хранит режим темы, выбранную палитру и последнюю открытую вкладку. Использует `SharedPreferences`.
- `runtime_controller.dart`
  Riverpod controller над `ProxyRuntimeController`: инициализирует runtime, запускает `prepare/start/stop/refresh`, собирает локальные diagnostic logs и адаптирует snapshot в состояние UI.
- `runtime_view_models.dart`
  UI-friendly модели: status item, log entry, tone, source labels, честные пользовательские подписи для `ProxyRuntimeState` и `ProxyRuntimeSnapshot`.
- `package_info_provider.dart`
  Дает UI доступ к версии приложения.

Правила:

- Presentation layer должен читать runtime через `runtimeControllerProvider`, а не создавать platform controller прямо в виджетах.
- User-facing подписи состояния должны жить здесь или в UI-компонентах, а не в Android/Kotlin wire protocol.
- `running` не должен трактоваться как fully active tunnel без проверки `tunnelActive` и `trafficForwarderReady`.

## Слой `lib/core/ui/`

Общие UI building blocks и design tokens.

Файлы:

- `design_tokens.dart`
  Breakpoints, spacing, radii, elevations.
- `app_backdrop.dart`
  Общий фон приложения, связанный с активной палитрой.
- `components/status_chip.dart`
  Runtime status chips для Home.
- `components/premium_cta_button.dart`
  CTA запуска/остановки сервисов.
- `components/connected_flow_illustration.dart`
  Иллюстрация состояния соединения на Home.
- `components/terminal_illustration.dart`
  Иллюстрация терминала для Logs.
- `components/terminal_surface.dart`
  UI терминала с логами, copy/clear/autoscroll.
- `components/staggered_reveal.dart`
  Viewport-based reveal-анимации.
- `components/palette_preview_card.dart`
  Preview карточки цветовых палитр.
- `components/settings_section_card.dart`
  Общая surface-обертка секций настроек.

## Слой `lib/core/motion/`

`app_motion.dart` содержит общие duration/easing tokens.
Новые анимации должны использовать эти токены, а не случайные локальные curve/duration.

## Слой `lib/features/home/`

Главная вкладка продукта.

Файл:

- `lib/features/home/presentation/home_screen.dart`

Экран показывает:

- брендированный первый экран QNZapret
- текущий runtime snapshot
- компактные runtime status chips
- CTA запуска/остановки сервисов
- честное состояние сервиса, ядра обхода, передачи и TUN
- иллюстрацию connected flow

Сейчас экран читает `runtimeControllerProvider`.
На Android под ним работает `AndroidProxyRuntime`; на desktop-платформах пока остаются stub-адаптеры того же контракта.

## Слой `lib/features/logs/`

Вкладка логов и диагностических событий.

Файл:

- `lib/features/logs/presentation/logs_screen.dart`

Экран показывает:

- human-readable runtime/controller events
- terminal-like surface
- количество строк
- состояние автопрокрутки
- текущий runtime status
- copy/clear controls

Важно: это уже продуктовый UI для логов, но источник событий пока application/runtime-controller слой. Production log stream из native backend еще нужно подключить отдельным контрактным изменением.

## Слой `lib/features/settings/`

Экран настроек.

Файл:

- `lib/features/settings/presentation/settings_screen.dart`

Экран показывает:

- бренд/логотип и версию приложения
- выбор режима темы: системная, светлая, темная
- выбор одной из 6 цветовых палитр
- блок "О приложении"
- внешние CTA

На телефонах настройки открываются как отдельная page route, на больших экранах - как dialog/panel.

## Слой `assets/branding/`

Брендовые ассеты продукта.

- `assets/branding/app_icon/qnzapret_app_icon_source.png`
  Исходник иконки приложения.
- `assets/branding/app_icon/generated/qnzapret_app_icon_fullbleed.png`
  Full-bleed источник для launcher icons.
- `assets/branding/logo/qnzapret_logo.png`
  Логотип для интерфейса.

Android launcher icons, Windows icon и Linux window icon должны генерироваться или обновляться из этих источников согласованно.

## Android слой

Текущий Android runner уже содержит начатую runtime-интеграцию.

Файлы приложения:

- `android/app/src/main/AndroidManifest.xml`
  Разрешения, activity, VPN service declaration и foreground service metadata. Для Android runtime важны `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, foreground service permissions и `BIND_VPN_SERVICE` на service.
- `android/app/src/main/assets/qnzapret/`
  APK-bundled assets для Android runtime: hostlists и binary payloads дефолтной lightweight стратегии.
- `android/app/src/main/jni/`
  Native-сборка `hev-socks5-tunnel` через Android NDK. Этот MIT-компонент получает TUN fd и перенаправляет TCP/UDP в локальный SOCKS5 proxy.
- `android/app/src/main/kotlin/dev/qnzapret/MainActivity.kt`
  Регистрирует `ProxyRuntimeBridge`, прокидывает `onActivityResult` для VPN prepare flow и запрашивает `POST_NOTIFICATIONS` на Android 13+, чтобы foreground notification было видно пользователю.
- `android/app/src/main/kotlin/dev/qnzapret/AndroidNetworkSelfTest.kt`
  Controlled network self-test из процесса приложения. Логирует `QNZapretNetTest` до старта VPN и после старта TUN: UID/package, selected network, capabilities, DNS/Private DNS и результаты plain/protected/bound TCP/UDP проб.
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt`
  `MethodChannel` bridge между Dart и Android runtime.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt`
  Foreground `VpnService`.
  Сейчас поднимает stateful foreground notification, обрабатывает actions `Остановить`/`Перезапустить` и стартует native strategy runtime.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretQuickSettingsTileService.kt`
  Native Quick Settings Tile. Читает Android runtime store, переключает запуск/остановку `QnzapretVpnService` с дефолтным Android-профилем и открывает `MainActivity`, если VPN permission еще не выдан.
- `android/app/src/main/kotlin/dev/qnzapret/VpnRuntimeConfig.kt`
  Android-представление `ProxyLaunchConfig`, включая strategy profile и TUN flags.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyProfile.kt`
  Kotlin-модель и codec для strategy profile payload, включая `endpointPolicies` для Telegram remote relay.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyProfileDevOverrides.kt`
  Локальный dev/smoke override `qnzapret/telegram_relay.json` для передачи Telegram relay endpoint policy без коммита реальных credentials.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetVerifier.kt`
  Проверяет наличие hostlists и payload blobs в Android assets перед запуском runtime.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetStore.kt`
  Загружает payload blobs и регистрирует lazy hostlist matchers для native strategy engine.
- `android/app/src/main/kotlin/dev/qnzapret/HostlistMatcher.kt`
  Нормализует hostlist entries и матчинг exact/suffix доменов.
- `android/app/src/main/kotlin/dev/qnzapret/L7Detectors.kt`
  Детектит HTTP Host, TLS ClientHello SNI и базовый QUIC Initial marker.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimeEngine.kt`
  Принимает flow probe и возвращает direct/desync decision по strategy profile, hostlists и payload assets.
- `android/app/src/main/kotlin/dev/qnzapret/IpPacketCodec.kt`
  Парсит IPv4/IPv6 UDP packets и TCP segments из TUN, собирает IPv4/IPv6 UDP/TCP response packets для записи обратно в TUN.
- `android/app/src/main/kotlin/dev/qnzapret/TcpRelayState.kt`
  Изолированная TCP client-side state machine для sequence accounting, duplicate/overlap retransmit handling, out-of-order ACK/drop и FIN progression.
- `android/app/src/main/kotlin/dev/qnzapret/TlsRecordSplitTransform.kt`
  No-root-safe transform для TLS ClientHello: разбивает первый TLS handshake record на два TLS records без raw TCP tricks.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimePlan.kt`
  Компилятор профиля в компактный runtime plan.
  План сохраняет `unmatchedTrafficPolicy`, чтобы local strategy proxy знал, что потоки вне hostlists нужно вести direct forwarding без desync-действий.
- `android/app/src/main/kotlin/dev/qnzapret/StrategySocks5Server.kt`
  Собственный локальный SOCKS5 proxy для strategy runtime. Принимает трафик от `hev-socks5-tunnel`, применяет HTTP/TLS/QUIC decisions, открывает исходящие protected TCP/UDP sockets, логирует TCP/UDP timing, YouTube DNS/UDP throughput diagnostics, помечает Telegram endpoint candidates, выбирает SOCKS5 remote relay policy до direct connect и делает bounded pre-connect fallback для Telegram без relay.
- `android/app/src/main/kotlin/dev/qnzapret/Socks5RelayClient.kt`
  Минимальный SOCKS5 client для remote relay: no-auth, username/password auth, CONNECT original target IPv4/IPv6/domain и стабильные error codes.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramCompatibilityProxyManager.kt`
  Lifecycle локального Kotlin MTProxy compatibility proxy: хранит локальный `dd` secret/port и health-based setup state, слушает `127.0.0.1:1443`, открывает Telegram proxy confirmation screen и останавливается вместе с `QnzapretVpnService`.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramSetupHealth.kt`
  Health-модель Telegram setup: fingerprint текущего `host:port:secret`, timestamp открытия setup screen, успешного MTProxy handshake и успешного WSS bridge.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramSetupActivity.kt`
  Transparent trampoline для notification action `Подключить Telegram`: открывает `tg://proxy` или fallback `https://t.me/proxy` как user-initiated Activity launch.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramMtProxyCrypto.kt`
  Clean-room MTProxy obfuscation/AES-CTR handshake. Извлекает logical/raw DC id, media DC flag и protocol marker из 64-byte init payload.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramRouteConfigProvider.kt`
  Route-provider для Telegram compatibility mode: локальный `telegram_compat.json`, cached/fresh public Flowseal domains, decode/cache TTL 12 часов, background CF probe и future placeholder для signed QNZapret route config.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramWebSocketTransport.kt`
  Clean-room WSS `/apiws` transport для Telegram compatibility proxy. Строит direct/Cloudflare route candidates, сохраняет active CF domain, учитывает 429 cooldown, держит TLS SNI/Host при connect к resolved IP, ведет EWMA route scoring, one-shot WSS pool и логирует route/session timing.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramCloudflareResolver.kt`
  Resolver для Telegram Cloudflare route: system/network DNS, DoH/UDP fallback, IPv4 preference на IPv4-only underlying-сетях и 5-минутный cache resolved IP.
- `android/app/src/main/kotlin/dev/qnzapret/TProxyService.kt`
  JNI-обертка над `hev-socks5-tunnel`: загрузка native-библиотеки, запуск, остановка и статистика TUN-to-SOCKS слоя.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt`
  Координатор Android runtime: компилирует профиль, проверяет assets, запускает local strategy proxy и TUN lifecycle.
- `android/app/src/main/kotlin/dev/qnzapret/LocalStrategyProxy.kt`
  Lifecycle локального strategy proxy и держатель native strategy engine.
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt`
  Lifecycle TUN transport. Дефолтный Android запуск использует `establishTunnel=true`, поднимает IPv4 default-route, добавляет IPv6 route только при рабочем IPv6 на selected underlying-сети, добавляет DNS из выбранной validated underlying-сети, сообщает ее через `Builder.setUnderlyingNetworks(...)`, исключает собственный пакет из VPN и передает fd в `hev-socks5-tunnel`; при явном `establishTunnel=false` оставляет default-route выключенным и сообщает capability flags.
- `android/app/src/main/kotlin/dev/qnzapret/UnderlyingNetworkSelector.kt`
  Выбирает validated unrestricted non-VPN сеть, ее DNS, Private DNS/link diagnostics и IPv6-route capability для TUN и protected sockets.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnRuntimeStore.kt`
  In-memory snapshot store для Android runtime-состояния.
- `android/app/src/main/kotlin/dev/qnzapret/QuicHostCorrelation.kt`
  Best-effort QUIC host correlation: парсит UDP/53 DNS A/AAAA responses, сохраняет CNAME-aware `IP -> host` mapping и принимает HTTP/TLS host hints для последующих UDP/443 QUIC decisions.

Channel:

- `dev.qnzapret/proxy_runtime`

Текущие native methods:

- `prepare`
- `getSnapshot`
- `start`
- `stop`

## Desktop слои

### `linux/`

Текущий Linux runner.
Реальный Linux bridge или process adapter еще не реализован.

### `windows/`

Текущий Windows runner.
Реальный Windows bridge или process adapter еще не реализован.

## Тесты

Текущие тесты находятся в:

- `test/core/backend/proxy_runtime_test.dart`
- `test/core/backend/proxy_runtime_controller_test.dart`
- `test/widget_test.dart`
- `android/app/src/test/kotlin/dev/qnzapret/TcpRelayStateTest.kt`
- `android/app/src/test/kotlin/dev/qnzapret/IpPacketCodecTest.kt`
- `android/app/src/test/kotlin/dev/qnzapret/QuicHostCorrelationTest.kt`
- `android/app/src/test/kotlin/dev/qnzapret/StrategyRuntimeEngineTest.kt`

При значимых изменениях runtime-контракта нужно запускать:

- `flutter analyze`
- `flutter test`
- `cd android && ./gradlew test` на Linux/macOS
- `cd android; .\gradlew.bat test` на Windows

## Границы ответственности

Frontend отвечает за:

- UI/UX
- theme
- presentation layer
- product flows
- адаптацию snapshot/log events в человекочитаемые состояния

Backend отвечает за:

- runtime contracts
- platform adapters
- запуск и остановку нативного runtime
- Android service lifecycle
- native logs/status/failure delivery

Shared responsibility:

- стабильность Dart runtime API
- lifecycle semantics
- перечень поддерживаемых платформ
- фиксация изменений в документации
- локализация user-facing сообщений, которые приходят из native runtime в UI

## Что нельзя делать

- вызывать local strategy proxy или другой platform runtime из виджетов напрямую
- смешивать platform-specific payloads с presentation layer
- разбрасывать backend-состояние по виджетам вместо расширения `ProxyRuntime`
- вручную править generated Flutter files без необходимости
- вносить production backend-код в `.sources/`

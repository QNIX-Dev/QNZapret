# Project Structure

## Назначение документа

Этот файл описывает текущую рабочую структуру проекта.
Он должен помогать frontend- и backend-разработчикам быстро понять, где проходит ответственность каждого слоя.

## Общая карта

```text
lib/
  main.dart
  app/
    app.dart
    theme/
      app_theme.dart
  core/
    backend/
      backend.dart
      android_proxy_runtime.dart
      proxy_runtime.dart
      proxy_runtime_controller.dart
      proxy_runtime_factory.dart
  features/
    home/
      presentation/
        home_screen.dart

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
      kotlin/dev/qnzapret/
        HostlistMatcher.kt
        IpPacketCodec.kt
        L7Detectors.kt
        LocalStrategyProxy.kt
        MainActivity.kt
        ProxyRuntimeBridge.kt
        QnzapretAndroidRuntime.kt
        QnzapretVpnRuntimeStore.kt
        QnzapretVpnService.kt
        StrategyAssetStore.kt
        StrategyAssetVerifier.kt
        StrategyProfile.kt
        StrategyRuntimeEngine.kt
        StrategyRuntimePlan.kt
        TcpRelayState.kt
        TunPacketForwarder.kt
        TunTransport.kt
        VpnRuntimeConfig.kt
    src/test/
      kotlin/dev/qnzapret/
        IpPacketCodecTest.kt
        TcpRelayStateTest.kt

linux/
windows/
test/
```

## Слой `lib/main.dart`

Минимальный bootstrap приложения.
Здесь создается runtime через `createDefaultProxyRuntime()` и передается в `QnzapretApp`.
Логика фич и platform-specific детали здесь жить не должны.

## Слой `lib/app/`

Назначение:

- app-level composition
- корневой `MaterialApp`
- общая тема приложения

Текущие файлы:

- `lib/app/app.dart`
  Корневая конфигурация приложения.
  Получает готовый `ProxyRuntime` и передает его в стартовый экран.
- `lib/app/theme/app_theme.dart`
  Общая визуальная система, цвета и типографика.

Текущий UI намеренно оформлен как продуктовый shell, а не стандартное Flutter demo.
Без явной необходимости этот слой лучше не перестраивать, потому что UI-направление развивается отдельно.

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

## Слой `lib/features/home/`

Текущий стартовый продуктовый экран.

Файл:

- `lib/features/home/presentation/home_screen.dart`

Экран показывает:

- брендированный первый экран QNZapret
- текущий runtime snapshot
- поддерживаемые платформы
- ближайшие backend milestones

Сейчас экран получает `ProxyRuntime` из composition root и оборачивает его в `ProxyRuntimeController`.
На Android это уже `AndroidProxyRuntime`; на desktop-платформах пока остаются stub-адаптеры того же контракта.

## Android слой

Текущий Android runner уже содержит начатую runtime-интеграцию.

Файлы приложения:

- `android/app/src/main/AndroidManifest.xml`
  Разрешения, activity, VPN service declaration и foreground service metadata.
- `android/app/src/main/assets/qnzapret/`
  APK-bundled assets для Android runtime: hostlists и binary payloads дефолтной lightweight стратегии.
- `android/app/src/main/kotlin/dev/qnzapret/MainActivity.kt`
  Регистрирует `ProxyRuntimeBridge` и прокидывает `onActivityResult` для VPN prepare flow.
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt`
  `MethodChannel` bridge между Dart и Android runtime.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt`
  Foreground `VpnService`.
  Сейчас поднимает notification и стартует native strategy runtime.
- `android/app/src/main/kotlin/dev/qnzapret/VpnRuntimeConfig.kt`
  Android-представление `ProxyLaunchConfig`, включая strategy profile и TUN flags.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyProfile.kt`
  Kotlin-модель и codec для strategy profile payload.
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
- `android/app/src/main/kotlin/dev/qnzapret/TunPacketForwarder.kt`
  Userspace forwarder core: читает TUN packets, умеет IPv4/IPv6 UDP relay через Android protected `DatagramSocket`, TCP relay/state machine через protected `Socket`, вызывает strategy engine перед отправкой UDP datagram и первого TCP payload chunk, чистит idle UDP/TCP sessions и ограничивает pending TCP payload до socket connect.
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimePlan.kt`
  Компилятор профиля в компактный runtime plan.
  План сохраняет `unmatchedTrafficPolicy`, чтобы future forwarder знал, что потоки вне hostlists нужно вести direct forwarding без desync-действий.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt`
  Координатор Android runtime: компилирует профиль, проверяет assets, запускает local strategy proxy и TUN lifecycle.
- `android/app/src/main/kotlin/dev/qnzapret/LocalStrategyProxy.kt`
  Lifecycle локального strategy proxy и держатель native strategy engine.
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt`
  Lifecycle TUN transport. При `establishTunnel=false` оставляет default-route выключенным и сообщает capability flags; при `establishTunnel=true` поднимает IPv4/IPv6 TUN routes/DNS и запускает forwarder только когда TCP/UDP capabilities готовы.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnRuntimeStore.kt`
  In-memory snapshot store для Android runtime-состояния.

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

При значимых изменениях runtime-контракта нужно запускать:

- `flutter analyze`
- `flutter test`
- `cd android; .\gradlew.bat :app:testDebugUnitTest`

## Границы ответственности

Frontend отвечает за:

- UI/UX
- theme
- presentation layer
- product flows

Backend отвечает за:

- runtime contracts
- platform adapters
- запуск и остановку нативного runtime
- Android service lifecycle
- future logs/status/failure delivery

Shared responsibility:

- стабильность Dart runtime API
- lifecycle semantics
- перечень поддерживаемых платформ
- фиксация изменений в документации

## Что нельзя делать

- вызывать local strategy proxy или другой platform runtime из виджетов напрямую
- смешивать platform-specific payloads с presentation layer
- разбрасывать backend-состояние по виджетам вместо расширения `ProxyRuntime`
- вручную править generated Flutter files без необходимости
- вносить production backend-код в `.sources/`

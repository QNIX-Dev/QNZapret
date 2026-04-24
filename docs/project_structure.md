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
      android_proxy_runtime.dart
      proxy_runtime.dart
  features/
    home/
      presentation/
        home_screen.dart

android/
  app/
    src/main/
      AndroidManifest.xml
      kotlin/dev/quriee/qnzapret/
        MainActivity.kt
        ProxyRuntimeBridge.kt
        QnzapretVpnRuntimeStore.kt
        QnzapretVpnService.kt

linux/
windows/
test/
```

## Слой `lib/main.dart`

Минимальный bootstrap приложения.
Здесь должен оставаться только запуск Flutter-приложения, без логики фич и runtime-интеграции.

## Слой `lib/app/`

Назначение:

- app-level composition
- корневой `MaterialApp`
- общая тема приложения

Текущие файлы:

- `lib/app/app.dart`
  Корневая конфигурация приложения.
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
  - `ProxyRuntimeSnapshot`
  - `ProxyRuntime`
  - `StubProxyRuntime`
- `android_proxy_runtime.dart`
  Android adapter поверх `MethodChannel`.

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

Сейчас экран использует `StubProxyRuntime`, поэтому фактический Android service еще не запускается из UI.
Это важно учитывать при backend-интеграции: наличие Android adapter в коде еще не означает, что экран уже переведен на production lifecycle.

## Android слой

Текущий Android runner уже содержит начатую runtime-интеграцию.

Файлы приложения:

- `android/app/src/main/AndroidManifest.xml`
  Разрешения, activity, VPN service declaration и foreground service metadata.
- `android/app/src/main/kotlin/dev/quriee/qnzapret/MainActivity.kt`
  Регистрирует `ProxyRuntimeBridge` и прокидывает `onActivityResult` для VPN prepare flow.
- `android/app/src/main/kotlin/dev/quriee/qnzapret/ProxyRuntimeBridge.kt`
  `MethodChannel` bridge между Dart и Android runtime base.
- `android/app/src/main/kotlin/dev/quriee/qnzapret/QnzapretVpnService.kt`
  База foreground `VpnService`.
  Сейчас поднимает service shell и notification, но реальный tunnel еще не строит.
- `android/app/src/main/kotlin/dev/quriee/qnzapret/QnzapretVpnRuntimeStore.kt`
  In-memory snapshot store для Android runtime-состояния.

Channel:

- `dev.quriee.qnzapret/proxy_runtime`

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
- `test/widget_test.dart`

При значимых изменениях runtime-контракта нужно запускать:

- `flutter analyze`
- `flutter test`

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

- вызывать `tgwsproxy` или другой platform runtime из виджетов напрямую
- смешивать platform-specific payloads с presentation layer
- разбрасывать backend-состояние по виджетам вместо расширения `ProxyRuntime`
- вручную править generated Flutter files без необходимости
- вносить production backend-код в `.sources/`

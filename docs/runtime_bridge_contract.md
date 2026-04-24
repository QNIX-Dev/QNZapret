# Runtime Bridge Contract

## Назначение документа

Этот файл фиксирует текущий контракт между Flutter frontend и platform/runtime implementations.

Источник кода:

- `lib/core/backend/proxy_runtime.dart`
- `lib/core/backend/android_proxy_runtime.dart`
- `android/app/src/main/kotlin/dev/quriee/qnzapret/ProxyRuntimeBridge.kt`
- `android/app/src/main/kotlin/dev/quriee/qnzapret/QnzapretVpnRuntimeStore.kt`
- `android/app/src/main/kotlin/dev/quriee/qnzapret/QnzapretVpnService.kt`

Если код и документ расходятся, источником правды считается код.
При любом существенном изменении контракта нужно обновлять и код, и этот документ в одном наборе изменений.

## Цель контракта

Сделать так, чтобы:

- frontend работал от стабильного Dart API
- Android/Linux/Windows adapters можно было развивать независимо от UI
- backend-состояние не расползалось по виджетам
- platform-specific детали не попадали напрямую в presentation layer

## Актуальный Dart API

```dart
abstract interface class ProxyRuntime {
  Future<ProxyPrepareResult> prepare();

  Future<ProxyRuntimeSnapshot> getSnapshot();

  Future<void> start(ProxyLaunchConfig config);

  Future<void> stop();
}
```

Текущие реализации:

- `StubProxyRuntime` - stub для состояния, где нативный backend еще не подключен.
- `AndroidProxyRuntime` - adapter поверх Android `MethodChannel`.

## Платформы

```dart
enum ProxyPlatform { android, linux, windows }
```

Текущий реальный adapter начат только для Android.
Linux и Windows должны по возможности прийти к той же Dart-поверхности runtime.

## Runtime states

```dart
enum ProxyRuntimeState { idle, starting, running, stopping, failed }
```

Интерпретация:

- `idle` - runtime не активен и готов к следующему действию
- `starting` - runtime находится в процессе запуска
- `running` - runtime service base активен
- `stopping` - runtime находится в процессе остановки
- `failed` - операция runtime завершилась ошибкой или runtime не может продолжать работу

Важно: сейчас `running` на Android означает, что foreground `VpnService` base активен.
Это еще не означает, что `tgwsproxy` и VPN tunnel полностью подключены.

## Модели

### `ProxyPrepareResult`

Назначение:

- результат подготовки platform runtime перед запуском
- на Android сейчас отражает результат VPN permission flow

Поля:

- `granted`
- `message`

Wire payload:

```dart
{
  'granted': true,
  'message': 'VPN permission granted. Android service base is ready to start.',
}
```

### `ProxyLaunchConfig`

Назначение:

- минимальная конфигурация запуска runtime
- передается из Dart в platform adapter

Поля:

- `localHost`
- `localPort`
- `poolSize`
- `cloudflareEnabled`
- `secret`

Wire payload:

```dart
{
  'localHost': '127.0.0.1',
  'localPort': 1080,
  'poolSize': 8,
  'cloudflareEnabled': true,
  'secret': 'token',
}
```

Текущий Android service читает эти значения и использует их только как runtime target metadata.
Реальное подключение к `tgwsproxy` еще не реализовано.

### `ProxyRuntimeSnapshot`

Назначение:

- единый snapshot текущего runtime-состояния для UI и application layer

Поля:

- `platform`
- `state`
- `message`
- `backendConnected`
- `vpnPermissionGranted`
- `serviceActive`

Wire payload:

```dart
{
  'platform': 'android',
  'state': 'running',
  'message': 'Android VPN service base is active.',
  'backendConnected': true,
  'vpnPermissionGranted': true,
  'serviceActive': true,
}
```

Семантика:

- `backendConnected` сейчас означает, что platform bridge base доступен, а не что `tgwsproxy` уже запущен.
- `vpnPermissionGranted` актуально для Android.
- `serviceActive` отражает активность Android service base.
- `message` должен быть пригоден для отображения в UI.

## Методы

### `prepare()`

Назначение:

- подготовить platform runtime перед запуском
- на Android запросить или проверить VPN permission

Android behavior:

- если `VpnService.prepare(activity)` возвращает `null`, permission уже доступен
- иначе открывается системный consent flow
- результат возвращается как `ProxyPrepareResult`

Возможная native error:

- `vpn_prepare_in_progress` - запрос разрешения уже выполняется

### `getSnapshot()`

Назначение:

- получить актуальный snapshot runtime-состояния

Android behavior:

- вызывает `QnzapretVpnRuntimeStore.snapshot(context)`
- синхронизирует текущее VPN permission state через `VpnService.prepare(context)`

### `start(ProxyLaunchConfig config)`

Назначение:

- запустить platform runtime с заданной конфигурацией

Android behavior:

- требует уже выданного VPN permission
- переводит store в `starting`
- запускает `QnzapretVpnService` как foreground service
- service переводит store в `running`, если base успешно поднят

Возможная native error:

- `vpn_permission_required` - запуск невозможен без VPN permission

Важно: `start()` возвращает `Future<void>`.
Для получения итогового состояния нужно вызвать `getSnapshot()` после native lifecycle update.

### `stop()`

Назначение:

- остановить platform runtime

Android behavior:

- переводит store в `stopping`
- вызывает `stopService`
- если service уже не был активен, store возвращается в `idle`
- `QnzapretVpnService.onDestroy()` также переводит store в `idle`

## Android MethodChannel contract

Channel:

```text
dev.quriee.qnzapret/proxy_runtime
```

Methods:

- `prepare`
- `getSnapshot`
- `start`
- `stop`

`start` arguments:

```dart
{
  'config': ProxyLaunchConfig(...).toMap(),
}
```

Native return values:

- `prepare` возвращает map с `granted` и `message`
- `getSnapshot` возвращает map для `ProxyRuntimeSnapshot.fromMap`
- `start` возвращает `null` при успешной отправке команды запуска
- `stop` возвращает `null` при успешной отправке команды остановки

## Lifecycle expectations

### Android prepare flow

1. UI/application layer вызывает `prepare()`.
2. Android bridge проверяет `VpnService.prepare`.
3. Если нужно разрешение, открывается системный consent screen.
4. Результат возвращается в Dart как `ProxyPrepareResult`.
5. `getSnapshot()` после prepare должен отражать актуальный `vpnPermissionGranted`.

### Android start flow

1. UI/application layer вызывает `start(config)`.
2. Если permission отсутствует, Android bridge возвращает ошибку `vpn_permission_required`.
3. Если permission есть, store получает `starting`.
4. Foreground `QnzapretVpnService` стартует.
5. Service получает config metadata и переводит store в `running`.
6. `getSnapshot()` возвращает актуальное состояние.

### Android stop flow

1. UI/application layer вызывает `stop()`.
2. Store получает `stopping`.
3. Android service останавливается.
4. Store возвращается в `idle`.

## Требования к будущей backend-реализации

- Не отдавать platform-specific payloads напрямую в UI.
- Не смешивать runtime handles с Flutter widgets.
- Все низкоуровневые события сначала адаптировать до Dart-моделей контракта.
- Сохранять единый runtime API для Android/Linux/Windows, насколько это возможно.
- Не расширять контракт без понятного UI/backend сценария.
- При добавлении logs/status streams зафиксировать их здесь до активного использования в UI.

## Что желательно согласовать до подключения `tgwsproxy`

- как Android будет запускать и контролировать `tgwsproxy`: process boundary, bundled binary, MethodChannel orchestration или другой механизм
- какие поля `ProxyLaunchConfig` останутся стабильными
- какие native error codes считаются публичными
- как отличать "Android service base running" от "`tgwsproxy` fully running"
- как будет устроен log stream
- нужен ли отдельный health-check или diagnostics snapshot
- какая форма desktop adapters нужна для Linux и Windows

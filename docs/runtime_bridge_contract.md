# Runtime Bridge Contract

## Назначение документа

Этот файл фиксирует текущий контракт между frontend и будущими backend/platform implementations.

Источник кода:

- `lib/core/backend/runtime_bridge.dart`
- `lib/core/backend/runtime_models.dart`

Если код и документ расходятся, источником правды считается код.
При любом существенном изменении контракта нужно обновлять и код, и этот документ в одном наборе изменений.

## Цель контракта

Сделать так, чтобы:

- frontend мог полноценно работать от стабильного абстрактного API
- backend мог развиваться независимо от UI
- замена `StubRuntimeBridge` на реальную реализацию не требовала переписывания экранов

## Сервисы

На текущем этапе frontend ожидает два runtime-сервиса:

- `BypassServiceType.nfqws`
- `BypassServiceType.telegramProxy`

Технические имена:

- `nfqws`
- `Proxy`

Публичные пользовательские подписи уже задаются на frontend-стороне:

- `Основной сервис обхода`
- `Telegram-портал`

## Обязательный интерфейс bridge

```dart
abstract interface class RuntimeBridge {
  RuntimeBridgeCapabilities get capabilities;

  Future<ServiceLaunchResult> startService(BypassServiceType serviceType);
  Future<CombinedRuntimeState> startAllServices();
  Future<CombinedRuntimeState> stopAllServices();
  Future<ServiceRuntimeStatus> getServiceStatus(BypassServiceType serviceType);
  Future<CombinedRuntimeState> getCombinedState();
  Future<RuntimeFailure?> getLatestFailure();

  Stream<CombinedRuntimeState> watchRuntimeState();
  Stream<RuntimeLogEntry> watchLogs();
  Stream<RuntimeFailure> watchFailures();

  void dispose();
}
```

## Что означают методы

### `startService`

Назначение:

- точечный запуск одного сервиса

Важно:

- сейчас UI в основном работает через `startAllServices()`
- метод всё равно нужен как отдельная точка входа и для будущих platform scenarios

### `startAllServices`

Назначение:

- запуск общего продукта одним действием пользователя

Ожидание frontend:

- оба сервиса должны начать lifecycle переход
- runtime state должен обновляться потоково
- каждый сервис может завершить запуск независимо

### `stopAllServices`

Назначение:

- общая команда остановки

Ожидание frontend:

- сервисы должны пройти через последовательный stop-flow
- статус не должен прыгать резко из `running` в `idle` без промежуточного `stopping`

### `getServiceStatus`

Назначение:

- единичный snapshot состояния одного сервиса

### `getCombinedState`

Назначение:

- получить целиком актуальное состояние runtime на текущий момент

### `getLatestFailure`

Назначение:

- отдать последнюю известную ошибку runtime

### `watchRuntimeState`

Главный поток состояний для UI.

Ожидание frontend:

- stream должен отдавать актуальное `CombinedRuntimeState`
- stream не должен требовать знания platform-specific деталей

### `watchLogs`

Поток логов для экрана логов.

Ожидание frontend:

- строки должны приходить как `RuntimeLogEntry`
- лог-стрим не должен блокировать UI

### `watchFailures`

Поток ошибок lifecycle-операций.

Ожидание frontend:

- ошибки старта и остановки должны приходить отдельно
- `serviceType` может быть как конкретным, так и `null`, если ошибка общесистемная

## Доменные статусы

```dart
enum ServiceRuntimeStatus {
  idle,
  starting,
  running,
  stopping,
  failed,
}
```

Интерпретация:

- `idle` — сервис не активен и готов к запуску
- `starting` — сервис находится в процессе запуска
- `running` — сервис успешно работает
- `stopping` — сервис находится в процессе остановки
- `failed` — сервис завершил операцию с ошибкой

## Модели

### `RuntimeFailure`

Назначение:

- нормализованная ошибка runtime-слоя

Поля:

- `code`
- `message`
- `commandType`
- `timestamp`
- `serviceType`
- `details`
- `recoverable`

Требование:

- `message` должен быть пригоден для пользовательского UI
- `details` может содержать более техническое описание

### `ServiceRuntimeState`

Назначение:

- snapshot состояния одного сервиса

Поля:

- `type`
- `status`
- `updatedAt`
- `failure`

### `ServiceLaunchResult`

Назначение:

- результат отдельной операции запуска

Поля:

- `serviceType`
- `status`
- `timestamp`
- `failure`

### `CombinedRuntimeState`

Назначение:

- общий runtime snapshot для всего UI

Поля:

- `services`
- `updatedAt`

Важные derived semantics на frontend:

- `isIdle`
- `isFullyRunning`
- `hasRunningServices`
- `hasFailure`
- `hasActiveServices`
- `isTransitioning`
- `hasPartialFailure`
- `summaryStatus`

### `RuntimeLogEntry`

Назначение:

- единичная запись лог-стрима

Поля:

- `id`
- `timestamp`
- `level`
- `message`
- `serviceType`

`RuntimeLogLevel`:

- `system`
- `info`
- `success`
- `warning`
- `error`

### `RuntimeBridgeCapabilities`

Назначение:

- зафиксировать возможности конкретной bridge-реализации

Поля:

- `supportedServices`
- `supportsLogStream`
- `supportsSimulationControls`

## Lifecycle expectations

## Успешный старт обоих сервисов

Ожидаемая последовательность:

1. Пользователь вызывает `startAllServices()`.
2. Bridge начинает отдавать `CombinedRuntimeState` со статусами `starting`.
3. Каждый сервис отдельно переходит в `running`.
4. Когда оба сервиса `running`, frontend считает систему активной.

## Частичный отказ

Ожидаемая последовательность:

1. Общий запуск начался.
2. Один сервис ушёл в `running`.
3. Второй сервис ушёл в `failed`.
4. Frontend показывает partial-failure flow.
5. `RuntimeController` планирует мягкий rollback через `stopAllServices()`.

Важно:

- этот сценарий уже встроен в frontend и должен учитываться backend-командой

## Остановка

Ожидаемая последовательность:

1. Пользователь вызывает `stopAllServices()`.
2. Активные сервисы переходят в `stopping`.
3. После завершения stop-flow все сервисы переходят в `idle`.

## Требования к реальной backend-реализации

- Не отдавать platform-specific payloads напрямую в UI.
- Не смешивать runtime handles с доменными моделями frontend.
- Все низкоуровневые события сначала адаптировать до моделей этого контракта.
- Поддерживать потоковое обновление runtime state и логов.
- Не требовать от UI знания Android/Linux/Windows-specific деталей.

## Временная реализация

Сейчас frontend использует `StubRuntimeBridge`.

Он умеет:

- успешно запускать оба сервиса
- симулировать запуск только `nfqws`
- симулировать запуск только `telegramProxy`
- публиковать статусные переходы
- публиковать логи
- публиковать ошибки

Эта реализация нужна только как demo bridge и не является production backend.

## Что желательно согласовать с backend до начала интеграции

- будет ли Android bridge идти через Kotlin + channels/Pigeon
- будет ли Linux/Windows bridge идти через FFI или через process boundary
- как backend будет формировать `RuntimeLogEntry.id`
- какие `RuntimeFailure.code` считаются стабильными
- есть ли необходимость в дополнительных командах вроде restart, health-check, diagnostics snapshot

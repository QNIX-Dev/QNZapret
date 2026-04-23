# Project Brief

## Назначение документа

Этот файл нужен как короткая общая точка входа для frontend- и backend-разработки.
Он не заменяет рабочий регламент проекта.
Главные правила разработки, QA, архитектурные ожидания и соглашения для агентов задаются в корневом `AGENTS.md`.

## Что такое QNZapret

`QNZapret` - кроссплатформенный Flutter shell для будущего клиента с нативным runtime/backend-контуром.
Proxy-часть планируется строить вокруг утилиты `tgwsproxy`, и именно эта утилита написана на Go.

Поддерживаемые цели в репозитории сейчас:

- Android
- Linux
- Windows

Технологический стек:

- frontend: Flutter + Dart
- backend/runtime target: платформенный runtime-контур, подключаемый через adapters
- proxy utility: `tgwsproxy` на Go

## Текущая стадия

Проект находится на стадии брендированного frontend shell с начатой Android backend-интеграцией.

Что уже есть:

- минимальный Flutter bootstrap в `lib/main.dart`
- корневой `MaterialApp` в `lib/app/app.dart`
- продуктовая dark-mode-first тема в `lib/app/theme/app_theme.dart`
- стартовый экран в `lib/features/home/presentation/home_screen.dart`
- общий Dart-контракт runtime в `lib/core/backend/proxy_runtime.dart`
- `StubProxyRuntime` для состояния, где нативный runtime еще не подключен
- `AndroidProxyRuntime` как Dart-адаптер поверх `MethodChannel`
- Android bridge в `ProxyRuntimeBridge.kt`
- Android VPN foreground service base в `QnzapretVpnService.kt`
- Android runtime store для snapshot-состояния в `QnzapretVpnRuntimeStore.kt`
- базовые тесты сериализации и парсинга runtime-моделей

Что еще не подключено:

- реальная proxy-утилита `tgwsproxy`
- реальное создание VPN tunnel
- production lifecycle нативных процессов
- поток логов из backend
- desktop bridge implementations для Linux и Windows
- полноценные runtime-контролы, пресеты и профили стратегий

## Главная архитектурная идея

Целевая схема:

`UI -> shared Dart runtime contract -> platform adapter -> native runtime`

Обязательные принципы:

- UI не вызывает платформенный код напрямую
- экранный слой работает через общий Dart API из `lib/core/backend/`
- Android/Linux/Windows детали остаются внутри platform adapters
- расширение runtime-поведения сначала фиксируется в контракте, потом проводится через UI и native bridge

## Текущий runtime-контракт

Актуальный контракт описан в:

- `lib/core/backend/proxy_runtime.dart`
- `lib/core/backend/android_proxy_runtime.dart`
- `docs/runtime_bridge_contract.md`

Текущий публичный Dart API строится вокруг `ProxyRuntime`:

- `prepare()`
- `getSnapshot()`
- `start(ProxyLaunchConfig config)`
- `stop()`

Состояние runtime возвращается как `ProxyRuntimeSnapshot`.
Android сейчас использует `MethodChannel` с именем `dev.quriee.qnzapret/proxy_runtime`.

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

Сейчас экран использует `StubProxyRuntime`, поэтому он не управляет настоящим Android service.
Реальный Android adapter уже есть в коде, но composition/UI еще не переведены на полноценный runtime lifecycle.

## Ближайшая цель следующей стадии

Главная ближайшая цель - довести Android runtime path до реального native runtime/backend-контура:

1. подключить `AndroidProxyRuntime` в composition root вместо stub-состояния, когда UI будет готов к реальному lifecycle;
2. связать Android service base с `tgwsproxy` и выбранным bridge/process-механизмом;
3. уточнить snapshot, ошибки и будущий log stream;
4. после Android закрепить equivalent contract для Linux и Windows.

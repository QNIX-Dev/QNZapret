# Project Structure

## Назначение документа

Этот файл описывает текущую рабочую структуру проекта после первой frontend-стадии.
Он нужен, чтобы frontend- и backend-разработчики одинаково понимали, где находится ответственность каждого слоя.

## Общая карта

```text
lib/
  app/
    app.dart
    navigation/
    routing/
    theme/
  core/
    app_metadata.dart
    backend/
    motion/
    persistence/
    state/
    ui/
  features/
    home/
    logs/
    settings/
  main.dart
```

## Слой `app/`

Назначение:

- app-level composition
- shell приложения
- общая навигация
- глобальная тема

Текущие файлы:

- `lib/app/app.dart`
  Корневая конфигурация `MaterialApp`, подключение темы и app shell.
- `lib/app/navigation/app_shell.dart`
  Общая оболочка с переключением вкладок и открытием settings screen.
- `lib/app/navigation/floating_navigation_bar.dart`
  Кастомная нижняя floating navigation bar.
- `lib/app/routing/app_destination.dart`
  Доменные destination для навигации.
- `lib/app/theme/app_theme.dart`
  Theme system, palette definitions и `ThemeExtension`.

## Слой `core/`

Назначение:

- общие модели
- backend bridge contracts
- shared state
- persistence
- design tokens и reusable UI

### `lib/core/backend/`

Зона интеграции frontend и backend.

Файлы:

- `runtime_models.dart`
  Доменные runtime-модели и статусы.
- `runtime_bridge.dart`
  Главный интерфейс bridge-слоя.
- `stub_runtime_bridge.dart`
  Временная demo-реализация до подключения реального backend.

Правило:

- любые реальные platform adapters должны соответствовать этим контрактам
- UI не должен импортировать platform-specific код

### `lib/core/state/`

Application/state layer.

Файлы:

- `runtime_controller.dart`
  Управляет combined runtime state, логами, ошибками и rollback flow.
- `app_settings_controller.dart`
  Управляет theme mode, palette и последней вкладкой.
- `package_info_provider.dart`
  Получает platform version info.

### `lib/core/persistence/`

- `shared_preferences_provider.dart`
  Точка доступа к локальному persistence-слою.

### `lib/core/motion/`

- `app_motion.dart`
  Общие motion tokens проекта.

### `lib/core/ui/`

Общие visual building blocks и design tokens.

Файлы:

- `design_tokens.dart`
- `app_backdrop.dart`
- `components/connected_flow_illustration.dart`
- `components/palette_preview_card.dart`
- `components/premium_cta_button.dart`
- `components/settings_section_card.dart`
- `components/staggered_reveal.dart`
- `components/status_chip.dart`
- `components/terminal_surface.dart`

## Слой `features/`

Вертикальная продуктовая организация по экранам.

### `lib/features/home/`

- `presentation/home_screen.dart`
  Главный экран, статусы сервисов, CTA запуска и expressive hero-композиция.

### `lib/features/logs/`

- `presentation/logs_screen.dart`
  Экран логов с live terminal surface и базовыми controls.

### `lib/features/settings/`

- `presentation/settings_screen.dart`
  Настройки темы, палитры, demo-сценариев bridge и блок "О приложении".

## Платформенные директории

### `android/`

Текущий Android runner и сборка.
Сюда позже встанут Android-specific точки интеграции с backend-слоем:

- foreground/background service concerns
- notifications
- lifecycle hooks
- Android bridge к Go runtime

### `linux/`

Linux desktop runner и release bundle.
Сюда позже встанет Linux-specific bridge или process adapter.

### `windows/`

Windows desktop runner.
Сюда позже встанет Windows-specific bridge или process adapter.

## Границы ответственности

Frontend отвечает за:

- UX
- visual states
- theme/motion/navigation
- application/state layer
- адаптацию runtime-данных к UI

Backend отвечает за:

- реальный lifecycle сервисов
- запуск и остановку runtime
- реальные runtime status transitions
- delivery логов и ошибок

Shared responsibility:

- согласование bridge contract
- фиксация допустимых статусов и lifecycle semantics
- тестовые сценарии частичного запуска и частичных отказов

## Что нельзя делать

- вызывать Go runtime из виджетов
- смешивать platform code и presentation layer
- зашивать в UI новые backend payloads без адаптации через `core/backend`
- вносить реальную backend-логику в `.sources` как production-код frontend

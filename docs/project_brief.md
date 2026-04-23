# Project Brief

## Назначение документа

Этот файл нужен как короткая общая точка входа для frontend- и backend-разработки.
Он не заменяет регламент проекта.
Главные правила разработки, QA и Git Flow по-прежнему задаются в `.docs/AGENTS.md`.

## Что такое QNZapret

`QNZapret` — мультиплатформенное приложение для обхода региональных ограничений в РФ.

Целевые платформы:

- Android
- Linux
- Windows

Технологический стек:

- frontend: Flutter
- backend: Golang

## Текущая стадия

На текущем этапе собран первый полноценный frontend slice.

Что уже реализовано:

- главный экран
- экран логов
- экран настроек
- кастомный app shell с нижней floating navigation bar
- theme system с 6 палитрами и light/dark режимами
- motion system с общими duration/easing токенами
- Riverpod state layer
- persistence пользовательских настроек
- backend bridge contracts
- `StubRuntimeBridge` для demo UX до подключения реального backend

Что ещё не подключено:

- реальный Go runtime
- platform-specific bridge implementations для Android/Linux/Windows
- реальные внешние ссылки Telegram/donate

## Главная архитектурная идея

Целевая схема:

`UI -> application/state layer -> backend bridge contracts -> platform implementation -> Go runtime`

Обязательный принцип:

- UI не вызывает runtime напрямую
- экраны не знают о деталях платформенного bridge
- backend-интеграция проходит только через общий Dart-контракт

## Что важно для совместной работы frontend/backend

Frontend уже работает от доменных моделей и контрактов bridge-слоя.
Значит, backend-команда может разрабатывать интеграцию независимо от UI, если соблюдает зафиксированный контракт.

Для этого в `docs/` добавлены отдельные документы:

- `runtime_bridge_contract.md` — источник правды по входным точкам и моделям
- `project_structure.md` — карта текущей структуры кода
- `integration_workflow.md` — как синхронизировать изменения между frontend и backend

## Текущее продуктовое поведение

Главный UX-сценарий сейчас такой:

1. Пользователь нажимает общую CTA-кнопку запуска.
2. Оба сервиса переходят в `starting`.
3. Каждый сервис отдельно может перейти в `running` или `failed`.
4. При полном успехе интерфейс фиксируется в активном состоянии.
5. При частичном отказе UI показывает понятный failure flow и мягко возвращает систему в `idle`.
6. Страница логов получает live stream через state layer.

## Ближайшая цель следующей стадии

Следующий этап — не переписывать UI, а заменить `StubRuntimeBridge` на реальные платформенные реализации, сохранив тот же публичный контракт для экранов и application layer.

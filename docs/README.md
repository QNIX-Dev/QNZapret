# Документация QNZapret

Срез: ветка `quriee`, 12 октября 2026 года. При расхождении технических
описаний с реализацией источником фактов служит код этой ветки; расхождение
нужно исправить вместе с изменением. Планы ниже не являются готовыми функциями.

## Начать здесь

- [Краткий обзор и текущие ограничения](project_brief.md).
- [Карта кода и ответственности](project_structure.md).
- [Контракт Flutter/runtime](runtime_bridge_contract.md).
- [Разработка, QA и Git](integration_workflow.md).
- [Вход для агентов](../AGENTS.md): выбирает регламент из подмодуля `qndocs`.

## Linux

- [Архитектура, Start/Stop, диагностика и восстановление](linux_runtime_handoff.md).
- [Сборка, установка, зависимости и удаление](linux_packaging.md).
- [Автообновления компонентов и модель доверия](linux_component_updates.md).
- [Исторический отчёт проверки 12 октября](linux-repair-2026-10-12.md):
  результаты конкретного прогона, не инструкция для будущей версии.

## Android

- [Следующий этап: обновление non-root и APK для смартфона](android_next_stage.md).

- [Архитектура, состояние и smoke](android_runtime_handoff.md).
- [Диагностика сети из UID приложения](android_uid_network_blocker.md).
- [Telegram routes, cache, pool и media](android_telegram_cloudflare_routes.md).
- [Опциональный remote SOCKS5 relay](android_telegram_remote_relay_contract.md).
- [Решение по tg-ws-proxy и ограничения transparent transport](android_telegram_tg_ws_proxy_research.md).

## Правила поддержки

Общие поля API описываются в контракте, platform lifecycle — в handoff,
команды проверки — в QA-документе. Не копировать один длинный список во все
файлы. Факт сборки, наличие кода и подтверждённая работа на устройстве/сети
фиксируются отдельно. Идентификаторы личных устройств, секреты, приватные
домены, логи аккаунтов и локальные пути разработчика не нужны в общих инструкциях.
Сторонние LICENSE и документация vendored-библиотек сохраняются без переписывания.

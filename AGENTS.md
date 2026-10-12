# Работа в QNZapret

## Обязательный вход

1. Выполнить `git branch --show-current` и `git status --short`.
2. Прочитать регламент текущего направления:
   - `quriee` — [qndocs/quriee/AGENTS.md](qndocs/quriee/AGENTS.md);
   - `nixf1ow` — [qndocs/nixf1ow/AGENTS.md](qndocs/nixf1ow/AGENTS.md).
   Для feature-ветки использовать направление задачи/исходной ветки; если оно
   не определено, уточнить его до изменения стандартов.
3. Если `qndocs` пуст, выполнить `git submodule update --init qndocs`.
   Если доступа нет, сообщить о недоступном регламенте; не выдумывать его.
4. Прочитать [оглавление](docs/README.md), обзор, структуру, контракт, workflow
   и handoff затронутой платформы. Проверить чужие незакоммиченные изменения.

Вся коммуникация/документация вне кода — на русском. Branch-регламент задаёт
рабочие стандарты; технические факты определяет код текущей ветки. Явные
указания пользователя имеют приоритет. Существенное расхождение назвать и
исправить документацию вместе с кодом, не менять runtime ради старого текста.

## Архитектура, которую нужно сохранять

`Flutter UI -> Riverpod application layer -> shared Dart API -> platform adapter`.
Android — Kotlin non-root VPN/TUN/SOCKS5, Linux — C++/GIO system daemon + user
sidecar и Python updater/Telegram adapter. Windows runtime — stub.
Подробные defaults/flags — в [контракте](docs/runtime_bridge_contract.md).

Не вызывать native runtime из widgets, не запускать Linux GUI от root, не
смешивать `.sources` с рабочим кодом, не менять чужие firewall/DNS/hosts.
Android TUN **уже включён по умолчанию**; старого запрета `establishTunnel=true`
нет. Изменение defaults требует явной задачи, runtime/traffic QA и актуальных docs.

## Git и верификация

Коммитить после проверки и разрешения пользователя; уже данное разрешение
достаточно, повторно его не запрашивать. Push/merge/history rewrite — только
в соответствующем разрешённом объёме. `main` изменяется через PR.
Порядок работы с `qndocs` и матрица QA —
[docs/integration_workflow.md](docs/integration_workflow.md).

Следующий приоритет после текущего среза —
[Android non-root](docs/android_next_stage.md), включая QS дефект, обновление
маршрутов, APK и подготовку проверки пользователем на смартфоне.

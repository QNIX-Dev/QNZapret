# Linux runtime: архитектура и эксплуатация

Сверено с веткой `quriee` 12 октября 2026 года. Текущий DEB проверен на Ubuntu
26.04 x86_64. Целевые Debian 12/Ubuntu 24.04+/Fedora 44 требуют отдельного
clean-VM smoke перед релизом. Linux использует NFQUEUE, не Android TUN.

## Граница привилегий

```text
Flutter GUI (обычный пользователь)
  -> async MethodChannel/EventChannel -> C++/GIO runner
  -> system D-Bus dev.qnzapret.Runtime1 -> Polkit
     -> root qnzapret-runtime -> nfqws2 -> inet qnzapret
  -> session D-Bus dev.qnzapret.Telegram1
     -> user qnzapret-telegram-sidecar -> Python headless Telegram core
```

GUI не выполняет sudo/pkexec/nft/systemctl/shell. Root нужен установленному
system daemon, а не Flutter-процессу. Название executable/application ID:
`qnzapret` / `dev.qnzapret`. D-Bus API version: `1.0.0`.

Read-only GetVersion/Prepare/GetSnapshot не требуют Polkit. Start/Stop
проверяют `dev.qnzapret.runtime.manage` по реальному unique sender system bus:
active session — `auth_admin_keep`, inactive — `auth_admin`, other — deny.
Владение активным runtime проверяется по UID; другой пользователь не может
заменить/остановить transaction через публичный API.

Синхронная Polkit-проверка выполняется в worker task, GUI D-Bus calls — async.
События используют кэш snapshot. Открытие GUI не ждёт `network-online.target`.

## Start

1. Runner вызывает system Start; daemon авторизует caller через Polkit.
2. Для остановленного runtime выполняется bounded проверка обновлений двух
   компонентов; выбирается trusted cache или встроенная поставка.
3. Preflight проверяет nft/netfilter, binary/version/Lua API, manifest,
   compiler/dry-run и отсутствие queue/table conflict.
4. Daemon берёт `/run/qnzapret/runtime.lock`, компилирует конкретный профиль,
   делает dry-run и запускает nfqws2 со сбросом UID в `qnzapret-runtime`.
5. До четырёх секунд асинхронно ждёт NFQUEUE 200, затем атомарно применяет
   собственную nft table. Публикует readiness и `running`.
6. Runner запускает user sidecar, ждёт loopback listener до пяти секунд.
   Отказ sidecar откатывает system runtime через Stop.
7. Runner доставляет profile URI в Telegram через D-Bus Open, с fallback
   стандартного tg:// handler. Пользователь подтверждает профиль штатным
   диалогом, в том числе до входа. Ошибка доставки предупреждает и оставляет
   сервисы работающими; кнопка «Настроить Telegram» повторяет действие.

Повторный Start активного system runtime того же UID идемпотентен и не
переписывает компоненты живого worker. Linux default включает sidecar через
`cloudflareEnabled=true`. Детали shared config и ограничения — в
[контракте](runtime_bridge_contract.md), обновления — в
[описании обновлятора](linux_component_updates.md).

## Compiler и firewall

Daemon повторно проверяет сериализованный профиль. Допустимы `direct`, HTTP/TLS
TCP 80/443, QUIC UDP 443, canonical lists/payloads, `fake`/`udpFake` и
`split(position=1)` с входным repeats 1. Произвольная shell-строка не принимается.
Linux endpointPolicies/remote relay не подключены.

Текущий compiler использует `out-range=-d5`, два fake повтора, HTTP/TLS
`tcp_ts=-100` + multisplit `seqovl=2,pos=midsld,tcp_ts_up`; TLS привязывает
`tls_google`, QUIC — `quic_google`, autottl `-1,3-10` и disordered UDP fragments
position 8. `--bind-fix4/6`, mark `0x40000000`. Полные аргументы —
`linux/runtime/runtime_contract.cc`; Android действия выполняются иначе.

Единственный принадлежащий приложению firewall-объект — `table inet qnzapret`.

| Chain | Hook/priority | Окно пакетов |
| --- | --- | --- |
| predefrag_nfqws | output `-401` | notrack для маркированных injected/fragment packets |
| postrouting | postrouting `101` | original TCP 1–20, UDP 1–5 |
| prerouting | prerouting `-101` | reply TCP 1–10, UDP 1–3 |

Правила IPv4/IPv6 имеют mark guard, post-NAT bit `0x20000000`, counters и
`queue num 200 bypass`. Нет flush ruleset, глобального sysctl или изменения
чужих firewalld/ufw tables. Наличие bypass — fail-open механизм очереди, не
доказательство успешного обхода DPI.

## Stop, crash и recovery

Stop сначала снимает свою table, затем SIGTERM worker; fallback force-exit —
через три секунды. Очищаются active-profile/lock, возвращается idle; runner
останавливает Telegram child. Повторный Stop безопасен. Отказ Polkit не должен
выключать только sidecar. Закрытие GUI не останавливает запущенный обход.

При неожиданном exit worker daemon снимает свою table, сбрасывает readiness,
освобождает lock и публикует `linux_nfqws_unexpected_exit`/failed.
`ExecStartPre`/`ExecStopPost` вызывают `qnzapret-runtime --cleanup`, который
удаляет только свою table и volatile files. Global firewall не очищается.

Ручное восстановление при остановленном/сломавшемся runtime:

```bash
sudo systemctl stop qnzapret-runtime.service
sudo /usr/libexec/qnzapret-runtime --cleanup
systemctl --user stop qnzapret-telegram.service
```

Следующее обращение GUI активирует D-Bus helpers. Перехват включится только
после Start. Эти команды останавливают обход, не являются read-only диагностикой.

## Telegram sidecar

Headless `Flowseal/tg-ws-proxy` слушает `127.0.0.1:1443` без upstream tray/GUI.
Адаптер задаёт direct WS redirects DC2/DC4 на `149.154.167.220`.
CDN203 не направляется на обычный DC2: `telegram_cdn.py` выбирает отдельный
CF gateway после nonce-matched req_pq и сравнения публичных CDN/master key sets.
Проверка не выполняет вход/создание аккаунта; действительную аутентификацию
CDN keys выполняет сам Telegram. Результат кэшируется 15 минут. Непроверенные
CDN gateways не используются; при отсутствии подходящего остаётся upstream
direct TCP fallback. Реальные данные идут по новому соединению после probe.
Медиа pool miss ждёт уже выполняющееся пополнение не более секунды перед
публичным fallback; отмена клиента не отменяет общий refill.
Отдельный адаптер читает `telegram.secret` из user state, маскирует secret/URI
и наблюдает WS/H2 bridge. Secret/health — 0600, state directory — 0700.
Systemd `STATE_DIRECTORY` имеет приоритет; fallback —
`${XDG_STATE_HOME:-~/.local/state}/qnzapret`. Лог — rotating `telegram.log`,
2 MiB + один backup.

Опциональный `telegram.transport.json` рядом с secret содержит только
`{"h2Media": false}` или `true`: переключает upstream H2 media fallback для
сравнения сетей. Без файла сохраняется upstream default (H2 включён). Файл
ограничен 4 KiB; неверная схема не останавливает proxy и не печатает содержимое.
После изменения нужен Retry sidecar. Это локальная настройка QNZapret, а не
глобальное изменение сети. Результаты текущего исправления —
[в media-отчёте](telegram-media-fix-2026-10-12.md).

Listener отражает `telegramSidecarState=running`; только живой bridge даёт
`telegramCompatibilityProxyReady=true`. При закрытии последнего bridge marker
возвращается к listener. Нет bridge — «Нужна настройка»/«Ожидает Telegram»,
есть — «Подключён». Это не доказательство доставки конкретного сообщения.

User unit учитывает AppArmor Ubuntu: нет `ProtectKernelModules=yes`, задан
`PrivateUsers=no`; filesystem sandbox всё ещё может отображать root как
overflow UID. Trusted component paths проверяются в том же namespace.
Глобальные AppArmor/userns настройки не изменяются.

System unit оставляет `MemoryDenyWriteExecute=no` для LuaJIT и caps
`CAP_NET_ADMIN CAP_NET_RAW CAP_SETUID CAP_SETGID CAP_KILL`. Новые binary dry-run
и Telegram import validation выполняются без root.

## Диагностика и релиз

```bash
systemctl status qnzapret-runtime.service
journalctl -u qnzapret-runtime.service
systemctl --user status qnzapret-telegram.service
journalctl --user -u qnzapret-telegram.service
gdbus call --system --dest dev.qnzapret.Runtime1 \
  --object-path /dev/qnzapret/Runtime1 --method dev.qnzapret.Runtime1.GetSnapshot
gdbus call --session --dest dev.qnzapret.Telegram1 \
  --object-path /dev/qnzapret/Telegram1 --method dev.qnzapret.Telegram1.GetSnapshot
sudo nft list table inet qnzapret
cat /proc/net/netfilter/nfnetlink_queue
```

Не публиковать GetSetupUri/secret и приватные данные логов. Версии/commit/SHA
поставки — `runtime/assets/qnzapret/provenance.json`; cache содержит отдельный
component.json и manifest. Политика доверия/rollback — в документе обновлятора.

[QA](integration_workflow.md) разделяет unit/netns/real-network проверки.
Последний [отчёт](linux-repair-2026-10-12.md) — результат конкретного прогона.

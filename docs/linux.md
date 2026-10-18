# Linux runtime и поставка

## Компоненты и граница прав

```text
Flutter GUI обычного пользователя
  → LinuxProxyRuntime → C++/GIO plugin
     → system D-Bus dev.qnzapret.Runtime1 → Polkit
        → root qnzapret-runtime → nfqws2 → nftables/NFQUEUE
     → session D-Bus dev.qnzapret.Telegram1
        → user qnzapret-telegram-sidecar → Python Telegram core
```

| Исходники | Ответственность |
| --- | --- |
| [linux/runner/linux_proxy_runtime_plugin.cc](../linux/runner/linux_proxy_runtime_plugin.cc) | Асинхронные RPC, сериализация профиля, объединение snapshots, Start/Stop/rollback, доставка Telegram setup |
| [linux/runtime/runtime_daemon.cc](../linux/runtime/runtime_daemon.cc) | Polkit, owner UID, preflight, обновлятор, lock, worker, readiness и cleanup |
| [linux/runtime/runtime_contract.cc](../linux/runtime/runtime_contract.cc) | Allowlist compiler профиля, аргументы nfqws2, nft rules, ошибки и redaction |
| [linux/runtime/telegram_sidecar.cc](../linux/runtime/telegram_sidecar.cc) | Пользовательский supervisor Python core, secret, listener/bridge health и Retry |
| [linux/runtime/component_paths.h](../linux/runtime/component_paths.h) | Выбор доверенного поколения из cache |
| [linux/runtime/telegram_state_directory.h](../linux/runtime/telegram_state_directory.h) | Выбор systemd/XDG state directory |
| [linux/components/](../linux/components/) | Updater, Telegram adapter, CDN validation и unit-тесты |
| [packaging/linux/](../packaging/linux/) | DEB/RPM, units, D-Bus activation, Polkit и install/remove scripts |

GUI не запускает `sudo`, `nft`, shell или `systemctl`. Привилегии получает
установленный системный daemon. Start/Stop проверяют Polkit action
`dev.qnzapret.runtime.manage` по unique sender system bus: active session —
`auth_admin_keep`, inactive — `auth_admin`, прочие — deny. Read-only методы
GetVersion/Prepare/GetSnapshot не требуют Polkit. Активный runtime принадлежит
UID инициатора; другой UID не может заменить или остановить его через API.

GUI использует асинхронные GIO calls; Polkit исполняется в worker task daemon.
Открытие интерфейса активирует helpers для snapshot, но не включает перехват
или скачивание обновлений. Закрытие GUI не останавливает уже запущенный обход.

## Start, readiness и Stop

1. Runner передаёт профиль системному daemon; тот проверяет Polkit.
2. Для остановленного runtime проверяются обновления компонентов и выбирается
   trusted cache либо встроенная поставка.
3. Preflight проверяет nft/netfilter, binary/version/Lua API 6, SHA256 manifest,
   compiler/dry-run и отсутствие конфликта NFQUEUE 200/таблицы `inet qnzapret`.
4. Daemon берёт `/run/qnzapret/runtime.lock`, компилирует конкретный профиль,
   делает dry-run и запускает `nfqws2 --user=qnzapret-runtime`.
5. До четырёх секунд ждёт регистрацию NFQUEUE, затем одной транзакцией
   устанавливает собственную nft table и публикует готовность перехвата.
6. При `cloudflareEnabled=true` runner запускает user Telegram sidecar;
   listener должен подняться за пять секунд. При отказе Start sidecar runner
   откатывает системный runtime через Stop.
7. Runner открывает Telegram setup через D-Bus Open или tg:// handler.
   Ошибка доставки профиля даёт предупреждение; сервисы остаются работающими.

Start уже активного system runtime того же UID идемпотентен. Повторного
обновления живого worker при этом нет. RPC timeout Start/Stop в plugin —
150 секунд, Telegram Start/Stop — 15 секунд.

Stop сначала удаляет собственную nft table, затем посылает worker SIGTERM,
при необходимости принудительно завершает через три секунды, очищает volatile
profile/lock и возвращает idle. После успешного system Stop runner останавливает
user sidecar. Отказ Polkit не должен выключать только Telegram.

Неожиданный выход worker сбрасывает readiness, снимает свою table и публикует
failed. `ExecStartPre`/`ExecStopPost` вызывают `--cleanup`. Этот режим удаляет
только объекты QNZapret. `queue bypass` позволяет пакетам пройти при отсутствии
обработчика очереди; это fail-open поведение, а не подтверждение обхода DPI.

## Compiler и firewall

Профиль не является командной строкой. Plugin сериализует его, daemon повторно
проверяет allowlist: `direct`, HTTP TCP/80, TLS TCP/443, QUIC UDP/443,
canonical hostlists и TLS/QUIC blobs, ожидаемый порядок actions, входной
`repeats=1`, `split(position=1)`. Непустые `endpointPolicies` отклоняются.
Формат wire и IPC описаны в [контракте](runtime_bridge_contract.md).

Compiler переводит разрешённый общий профиль в фиксированную Linux-стратегию:
`out-range=-d5`, HTTP/TLS fake с `tcp_ts=-100,repeats=2`, multisplit
`seqovl=2,pos=midsld,tcp_ts_up`; TLS использует `tls_google`. QUIC использует
`quic_google`, autottl `-1,3-10`, два fake и disordered UDP fragments position 8.
Это не буквальное выполнение каждого action из Dart. Полные аргументы находятся
в `AppendLinuxProductionStrategy` в `runtime_contract.cc`.

Для HTTP/TLS используются три canonical hostlists. Для QUIC compiler требует
пустой hostlist на входе общего профиля, но добавляет `list-google.txt` и
`list-user.txt` в аргументы nfqws2. Это ещё одно отличие от Android engine.

Единственный принадлежащий приложению firewall-объект — `table inet qnzapret`.

| Chain | Hook / priority | Обработка |
| --- | --- | --- |
| `predefrag` → `predefrag_nfqws` | output `-401` | notrack для маркированных injected/fragment packets |
| `postrouting` | postrouting `101` | TCP dport 80/443, original 1–20; UDP dport 443, original 1–5 |
| `prerouting` | prerouting `-101` | TCP sport 80/443, reply 1–10; UDP sport 443, reply 1–3 |

Используются NFQUEUE 200, desync mark `0x40000000`, post-NAT mark `0x20000000`,
IPv4/IPv6, counters и `queue bypass`. Нет общего `flush ruleset`, изменения
hosts/DNS или удаления таблиц ufw/firewalld.

## Telegram sidecar

Headless core находится в [runtime/telegram/flowseal/proxy/](../runtime/telegram/flowseal/proxy/).
Он не включает upstream GUI/tray/updater. Отдельный
[qnzapret_telegram.py](../linux/components/qnzapret_telegram.py) проверяет
совместимость core, читает secret из файла, маскирует secret/URI в логах и
наблюдает активные WS/H2 bridges. Adapter задаёт direct WS redirects DC2/DC4,
при media pool miss до секунды ждёт уже выполняющееся пополнение pool.

[telegram_cdn.py](../linux/components/telegram_cdn.py) проверяет CDN203 gateway
через nonce-matched `req_pq` и сравнение публичных CDN/master key sets. CDN203
не должен подменяться обычным DC2. Результат выбора кэшируется на 15 минут;
при отсутствии подходящего gateway остаётся upstream TCP fallback.
Окончательную аутентификацию CDN выполняет клиент Telegram.

Sidecar слушает `127.0.0.1:1443`. Его `running` означает готовый listener;
`telegramCompatibilityProxyReady=true` требует активного bridge. Когда последний
bridge закрывается, health marker возвращается в `listening`. Этот индикатор
не доказывает доставку конкретного сообщения или скорость загрузки файла.

State directory: сначала systemd `STATE_DIRECTORY`, затем
`${XDG_STATE_HOME}/qnzapret` при абсолютном XDG path, иначе `~/.local/state/qnzapret`.
Каталог имеет mode 0700, `telegram.secret` и health file — 0600.
`telegram.log` ротируется: 2 MiB + один backup. Опциональный
`telegram.transport.json` рядом с secret принимает только `{"h2Media": false}`
или `true`; без него действует upstream default. После изменения нужен Retry
sidecar. Это локальная настройка транспорта, не системного proxy.

System unit ограничивает capabilities до NET_ADMIN/NET_RAW/SETUID/SETGID/KILL;
`MemoryDenyWriteExecute=no` нужен LuaJIT. User unit учитывает ограничения
AppArmor: `PrivateUsers=no`, без `ProtectKernelModules=yes`. Проверка cache
сравнивает владельца с владельцем корня filesystem в том же namespace,
поскольку sandbox может отображать root как overflow UID.

## Обновление компонентов

[component_updater.py](../linux/components/component_updater.py) обновляет только
два фиксированных upstream: `bol-van/zapret2` и `Flowseal/tg-ws-proxy`.
Он не обновляет Flutter GUI, C++ helpers, стратегии, hostlists, Android или
Windows. Системный daemon запускает установленный root-owned updater после
Polkit на новом Start остановленного runtime. Бюджет каждого upstream —
20 секунд, всего subprocess — 45 секунд. Ошибка сети/совместимости сохраняет
локальную версию и записывается в лог.

Обновлятор принимает только фиксированные GitHub HTTPS hosts и проверяет
redirects, размеры и paths архива; пользовательский HTTP proxy не наследуется.
Для zapret2 сверяет SHA256 release asset из API. Для Telegram разрешает тег
в commit и сверяет Git blob hashes Python-файлов/LICENSE с tree API. Сохраняет
только разрешённое подмножество файлов. Это доверие upstream и GitHub API,
не независимая криптографическая подпись поставщика.

Manifest, Lua API/dry-run и Python adapter contract проверяются до активации;
исполнение стороннего validator/core идёт с UID `qnzapret-runtime`. Новые
зависимости через pip не ставятся. Проверенное поколение публикуется атомарным
symlink; старое сохраняется как `runtime.previous` / `telegram.previous`.
Неуспешный preflight или ранний отказ загруженного core включает fallback.

| Данные | Расположение |
| --- | --- |
| Встроенный runtime | `/usr/lib/qnzapret/runtime/` |
| Встроенный Telegram core/adapter | `/usr/lib/qnzapret/telegram/` |
| Загруженные поколения, manifests, `component.json` | `/var/lib/qnzapret/components/` |
| Активные symlinks поколений | Там же: `runtime`, `telegram` |
| Lock и active profile | `/run/qnzapret/` |
| Пользовательские secret/health/log | State directory sidecar |

Для возврата к встроенной поставке останавливают helpers, удаляют только
активные symlinks `runtime`/`telegram` из cache и перезапускают helpers.
Каталоги поколений и пользовательский state не требуется удалять.

## Сборка и пакеты

Поставка рассчитана на Linux x86_64 с systemd. В CI заданы Debian 12 bundle
и Fedora 44 packaging; совместимость с конкретным дистрибутивом подтверждается
отдельной установкой и smoke.

```bash
flutter pub get
flutter build linux --release
bash packaging/linux/build_packages.sh
```

Нужны clang/C++, CMake, Ninja, pkg-config, GTK3/GIO development packages,
Python 3. Script создаёт оба формата в `build/linux/packages/`, поэтому требует
`rpmbuild`; DEB создаёт через `dpkg-deb` либо ar/tar fallback. Сейчас package
version `0.0.6` отдельно задана в script/spec/метаданных.

Bundle наследует glibc baseline хоста. Для сборки в Debian 12 через Podman:

```bash
export QNZAPRET_FLUTTER_DIR=/absolute/path/to/flutter
bash packaging/linux/build_bundle_debian12.sh
bash packaging/linux/build_packages.sh
```

Исходники монтируются read-only, собирается временная копия, обратно копируется
bundle. Нужны сеть и место для SDK/dependencies; SDK монтируется отдельно.

Пакет размещает GUI в `/opt/qnzapret`, symlink в `/usr/bin/qnzapret`, helpers
в `/usr/libexec`, компоненты в `/usr/lib/qnzapret`, units/D-Bus/Polkit в
системных каталогах, документацию в `/usr/share/doc/qnzapret`.
Зависимости задают build script/spec: nftables, systemd, D-Bus/Polkit,
Python cryptography/certifi/httpx/h2, CA certificates, GTK3/GLib и runtime libs.
На старте не требуется pip/venv или загрузка интерпретатора.

```bash
sudo apt install ./build/linux/packages/qnzapret_0.0.6_amd64.deb
# Для Fedora — выбрать RPM своего дистрибутива:
sudo dnf install ./build/linux/packages/qnzapret-0.0.6-1.*.x86_64.rpm
```

GUI запускают обычным пользователем. Install создаёт locked account worker и
state directories, перечитывает systemd. Новая установка не включает перехват.
DEB upgrade выполняет daemon-reload, но не перезапускает живые helpers:
для применения нового кода нужны Stop/закрытие GUI и остановка старых helpers.
RPM использует systemd restart macros; upgrade проверяется отдельно.
Remove останавливает helpers и очищает свою table. DEB purge удаляет account
и только пустой `/var/lib/qnzapret`; cache и user state не стираются целиком.

## Диагностика и восстановление

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

Ручное восстановление останавливает обход и очищает его volatile state:

```bash
sudo systemctl stop qnzapret-runtime.service
sudo /usr/libexec/qnzapret-runtime --cleanup
systemctl --user stop qnzapret-telegram.service
```

Следующее обращение GUI снова активирует helpers; перехват начнётся после
Start. Проверки пакета, netns и реальной сети перечислены в
[development.md](development.md).

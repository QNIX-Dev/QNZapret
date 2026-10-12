# Контракт Flutter/runtime

Сверено с кодом ветки `quriee` 12 октября 2026 года.
Основные источники: `lib/core/backend/proxy_runtime.dart`, adapters в той же
директории, Android `ProxyRuntimeBridge.kt`, Linux runner и `runtime_contract.*`.

## Публичный Dart API

```dart
abstract interface class ProxyRuntime {
  ProxyPlatform get platform;
  Stream<ProxyRuntimeEvent> get events;
  Future<ProxyPrepareResult> prepare();
  Future<ProxyRuntimeSnapshot> getSnapshot();
  Future<void> start(ProxyLaunchConfig config);
  Future<void> stop();
}

abstract interface class TelegramProxySetupRuntime {
  Future<void> setupTelegramProxy();
}
```

`ProxyPlatform`: `android`, `linux`, `windows`.
`createDefaultProxyRuntime()` выбирает Android/Linux adapters; Windows — stub.
Optional capability `TelegramProxySetupRuntime` реализует только Linux adapter.
Android setup доступен через native flow/notification, а не через эту capability.

UI работает через Riverpod `RuntimeController` и `ProxyRuntimeController`:
initialize, prepare, start, stop, refresh, setupTelegramProxy. Контроллер
хранит snapshot/config/busy/failure, подписывается на events, после Start/Stop
обновляет snapshot и опрашивает переходное состояние до 30 раз с шагом 200 мс.
Это ограниченный опрос после команды, а не постоянный Android event stream.

`ProxyRuntimeFailure` преобразует PlatformException/MissingPluginException и
прочие ошибки. UI не разбирает platform payload и не вызывает native API напрямую.

## Команды и каналы

MethodChannel обеих платформ: `dev.qnzapret/proxy_runtime`.

| Метод | Аргументы | Результат |
| --- | --- | --- |
| `prepare` | нет | map `{granted: bool, message: String}` |
| `getSnapshot` | нет | map `ProxyRuntimeSnapshot` |
| `start` | `{'config': config.toMap()}` | null / platform error |
| `stop` | нет | null / platform error |
| `setupTelegram` | нет; только Linux | null / `linux_telegram_setup_failed` |

Android prepare запрашивает системное VPN consent. Linux prepare — read-only
preflight; он не выдаёт разрешение Polkit. Linux `vpnPermissionGranted=true`
не означает, что мутация уже авторизована: Polkit проверяется на Start/Stop.

Android Start отвечает после отправки команды foreground service; дальнейшую
готовность определяет snapshot. Linux Start ждёт завершения транзакции daemon
и listener sidecar; открытие setup передаёт профиль Telegram, но подтверждение
пользователем/живой bridge не являются условием завершения Start.

Linux EventChannel: `dev.qnzapret/proxy_runtime/events`.

```text
snapshot: {type: snapshot, snapshot: <map>}
log: {type: log, log: {
  timestampMillis: int, level: debug|info|warning|error,
  source: String, code: String, message: String
}}
```

Dart `ProxyRuntimeEvent` содержит kind и nullable snapshot/log.
Linux объединяет D-Bus события с кэшированными snapshot без вложенных RPC;
Android и Windows adapters сейчас возвращают `Stream.empty()`.
Application-журнал ограничен 240 событиями. Android logcat — отдельный
диагностический источник, пока не подключённый к этому stream.

## Launch config

`ProxyLaunchConfig.toMap()` передаёт:
`localHost`, `localPort`, `poolSize`, `cloudflareEnabled`, `secret`,
`strategyProfile`, `establishTunnel`, `tunnelMtu`.

| Параметр | Android default | Linux default/применение |
| --- | --- | --- |
| localHost/localPort | `127.0.0.1:1080` | поля не настраивают фиксированный Telegram endpoint |
| poolSize | `0` | не управляет upstream pool Linux |
| cloudflareEnabled | `false` | `true`: runner включает sidecar в transaction |
| secret | пустая строка | runner не задаёт secret sidecar; он хранится в user state |
| establishTunnel | `true` | игнорируется: Linux использует NFQUEUE, не TUN |
| tunnelMtu | `8500` | не применяется к NFQUEUE |
| strategyProfile | `defaultLightweight` | сериализуется и повторно проверяется daemon |

`cloudflareEnabled=false` на Android не выключает Kotlin Telegram proxy:
`QnzapretVpnService` запускает его отдельно. Это унаследованное поле config,
а не общий переключатель Telegram для всех платформ. Linux Prepare и snapshots
также проверяют sidecar; его исключение из Start не изолирует все диагностики.

При Android `establishTunnel=false` engine может быть готов, но перехвата нет.
IPv6 route включается только при usable IPv6/default route underlying-сети.
Не менять MTU/defaults без проверки реального трафика и lifecycle.

## StrategyProfile

Поля: `id`, `name`, `description`, `unmatchedTrafficPolicy`, `blobs`, `rules`,
optional `endpointPolicies`. Правило содержит порты, protocols, hostlists и
ordered actions; action — kind, optional position/blobKey, repeats.
Модели и enum сериализуются именами из `proxy_runtime.dart`.

Дефолтный переносимый профиль:

- HTTP TCP/80: TCP hostlists, `fake(repeats=1)`, `split(position=1)`.
- TLS TCP/443: те же hostlists, fake с `tls_google`, split.
- QUIC UDP/443: `udpFake(quic_google)`, без обязательного hostlist match.
- `unmatchedTrafficPolicy=direct`: не запрещать трафик вне списков.

Canonical lists/payloads находятся в `runtime/assets/qnzapret/`.
Android APK asset paths остаются `qnzapret/lists/...` и `qnzapret/payloads/...`.

Android использует `StrategyRuntimeEngine`: HTTP Host/TLS SNI, lazy exact/suffix
hostlists, payload blobs, TLS record split, best-effort low-hop-limit fake и
QUIC Initial. Корреляция DNS UDP/53/prior TCP не покрывает надёжно DoH/DoT.
Raw sequence/timestamp fooling и IP fragmentation этим no-root API не обещаются.

Linux поддерживает только ограниченный compiler allowlist. Входные actions
переводятся в Linux-specific HTTP/TLS/QUIC аргументы nfqws2, а не выполняются
буквально как Android writes. `endpointPolicies` runner не сериализует;
Linux remote relay этим полем не настраивается. Неподдержанный профиль должен
возвращать ошибку, не превращаться в shell text.

Android `endpointPolicies` поддерживает TCP `remoteRelay` через SOCKS5,
no-auth/username-password и original IPv4/IPv6/domain target. HTTPS CONNECT
только зарезервирован. Формат и ошибки — в
[relay-контракте](android_telegram_remote_relay_contract.md).

## Snapshot и состояния

`ProxyRuntimeState`: `idle`, `starting`, `running`, `stopping`, `failed`.
`running` — состояние lifecycle; готовность реального перехвата проверяется
отдельными flags. `TrafficInterceptionMode`: `none`, `androidVpnTun`, `linuxNfqueue`.

| Поля | Смысл |
| --- | --- |
| platform/state/message | Платформа, lifecycle, сообщение пользователю |
| backendConnected | Доступен platform bridge; не проверка внешнего интернета |
| vpnPermissionGranted | Android consent; на Linux совместимое поле всегда true |
| serviceActive | Активен Android service / Linux transaction |
| strategyEngineReady | Android engine/assets готовы; Linux worker/queue готовы |
| trafficForwarderReady | Android TUN-to-SOCKS готов; Linux nft transaction применена |
| tunnelActive | Только Android TUN, на Linux false |
| trafficInterceptionMode/Active | Общий механизм и факт перехвата |
| queueRegistered/nftRulesInstalled/interceptionReady | Linux NFQUEUE + rules; общий признак готовности |
| packetCodecReady/udpForwarderReady/tcpForwarderReady | Capabilities Android TUN/SOCKS5 слоя |
| ipv6PacketCodecReady/ipv6UdpForwarderReady | Поддержка IPv6 кодека/relay, не гарантия IPv6 внешней сети |
| activeProfileName/backendVersion/runtimeOwnerUid | Профиль, версия backend API, Linux UID владельца |
| telegramSidecarState | unavailable/idle/starting/running/stopping/failed |
| degraded/partialFailureCode/partialFailureMessage | Частичный отказ |
| telegramCompatibilityProxyReady/SetupRequired/Endpoint/Message | Платформенное состояние Telegram, см. ниже |

Последние четыре поля имеют полный prefix `telegramCompatibility`:
`telegramCompatibilityProxyReady`, `telegramCompatibilitySetupRequired`,
`telegramCompatibilityProxyEndpoint`, `telegramCompatibilityProxyMessage`.

Legacy Android payload без interception fields выводит mode/active из
`tunnelActive`, readiness — из `tunnelActive && trafficForwarderReady`.
Linux legacy queue/rules могут выводиться из engine/forwarder flags.
Преобразование выполняет `ProxyRuntimeSnapshot.fromMap`, не widgets.

### Различия Telegram, которые ещё нужно выровнять

| Поле | Android сейчас | Linux сейчас |
| --- | --- | --- |
| ProxyReady | Локальный Kotlin listener готов | Есть живой MTProto+WS/H2 bridge |
| SetupRequired | В текущем запуске нет успешного handshake+bridge для fingerprint | Listener есть, живого bridge сейчас нет |
| SidecarState | Dart может вывести его из legacy flags | Явное состояние supervisor |

Linux UI показывает «Нужна настройка»/«Ожидает Telegram» до bridge и
«Подключён» при bridge. Общий UI сейчас доверяет ProxyReady, поэтому Android
listener всё ещё может получить преждевременную успешную подпись. Это известное
расхождение реализации, не обещание подтверждённого Android-соединения.

## Linux D-Bus и lifecycle

| Bus | Object/interface | Методы |
| --- | --- | --- |
| system `dev.qnzapret.Runtime1` | `/dev/qnzapret/Runtime1`, `dev.qnzapret.Runtime1` | GetVersion, Prepare, GetSnapshot, Start(s profile), Stop |
| session `dev.qnzapret.Telegram1` | `/dev/qnzapret/Telegram1`, `dev.qnzapret.Telegram1` | Prepare, GetSnapshot, Start, Stop, Retry, GetSetupUri |

Оба публикуют `SnapshotChanged(a{sv})` и `LogEvent(a{sv})`.
Runtime API version — `1.0.0`, это не версия приложения или nfqws2.

Start/Stop авторизуются Polkit `dev.qnzapret.runtime.manage` по реальному
unique system-bus sender. UID клиента не принимается как доказательство.
Start: авторизация -> bounded component updates -> compiler/preflight ->
worker/NFQUEUE -> nft -> sidecar -> Telegram setup. Ошибка sidecar откатывает
system runtime; отказ открытия Telegram только предупреждает. Stop: удалить
свои nft rules -> остановить worker -> остановить sidecar. При отказе Polkit
не выключается только одна половина transaction.

`setupTelegram` получает URI по session D-Bus и предпочитает
`org.freedesktop.Application.Open`, иначе стандартный обработчик tg://.
Secret не логируется; первый native confirm Telegram обязателен.
GUI/daemon остаются отзывчивыми при ожидании D-Bus и Polkit.
Полные алгоритмы, compiler и recovery — в [Linux handoff](linux_runtime_handoff.md).

## Публичные ошибки

Android: `vpn_prepare_in_progress`, `vpn_permission_required`, `vpn_start_failed`.
Linux: `linux_backend_unavailable`, `linux_backend_version_mismatch`,
`linux_authorization_denied`, `linux_nft_unavailable`, `linux_nfqueue_unavailable`,
`linux_queue_conflict`, `linux_profile_invalid`, `linux_profile_parse_rejected`,
`linux_profile_dry_run_nonzero`, `linux_profile_dry_run_signaled`,
`linux_profile_dry_run_spawn_failed`, `linux_nfqws_spawn_failed`,
`linux_queue_registration_timeout`, `linux_nft_transaction_failed`,
`linux_nfqws_unexpected_exit`, `linux_runtime_owned_by_other_user`,
`linux_start_in_progress`, `linux_start_cancelled`,
`linux_telegram_unavailable`, `linux_telegram_start_failed`,
`linux_telegram_start_in_progress`, `linux_telegram_start_cancelled`,
`linux_telegram_setup_failed`, `linux_telegram_stop_failed`, `linux_stop_failed`.
GIO/D-Bus transport errors также возможны. Не проверять ошибки по тексту сообщения.

Изменение wire fields/methods требует обновления adapters, совместимости старых
payload, tests и этого документа в одном изменении; общий API не должен
обещать неподдержанные возможности платформы.

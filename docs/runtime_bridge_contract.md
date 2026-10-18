# Контракт Flutter/runtime

Источник Dart API — [proxy_runtime.dart](../lib/core/backend/proxy_runtime.dart).
Адаптеры — [android_proxy_runtime.dart](../lib/core/backend/android_proxy_runtime.dart)
и [linux_proxy_runtime.dart](../lib/core/backend/linux_proxy_runtime.dart).
Общие модели не означают одинаковую реализацию действий на разных платформах.

## API и каналы

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

`TelegramProxySetupRuntime` — optional capability Android/Linux adapters;
Windows stub её не реализует. Platform values: `android`, `linux`, `windows`.
Оба native adapters используют Standard Method/Event Codec:

| Канал / метод | Аргументы и результат |
| --- | --- |
| MethodChannel `dev.qnzapret/proxy_runtime` | Команды ниже |
| `prepare` | Без arguments → `{granted: bool, message: String}` |
| `getSnapshot` | Без arguments → map snapshot |
| `start` | `{config: <launch config map>}` → void |
| `stop` | Без arguments → void |
| `setupTelegram` | Без arguments → void, открывает подтверждение proxy в Telegram |
| EventChannel `dev.qnzapret/proxy_runtime/events` | Snapshot/log envelopes |

Android `prepare` может показать системный VPN consent. Linux `Prepare`
проверяет установленные backend/sidecar; разрешение привилегированных Start/Stop
проверяется отдельно через Polkit. UI автоматически вызывает prepare только
при необходимости Android VPN consent.

Android `start` возвращается после передачи intent foreground service.
Linux Start ожидает system readiness и, если включён, запуск Telegram listener.
В обоих случаях успешное завершение команды не заменяет проверку snapshot
и реального сетевого сценария.

## Launch config

| Поле | Default | Смысл |
| --- | --- | --- |
| `localHost` | `127.0.0.1` | Bind Android strategy SOCKS5 |
| `localPort` | `1080` | Порт Android strategy SOCKS5 |
| `poolSize` | `0` | Сохраняется в общем config; не является общим регулятором нативных pools |
| `cloudflareEnabled` | Android false, Linux true | На Linux определяет запуск Telegram sidecar; не выключает Android compatibility mode |
| `secret` | пустая строка | Поле общего config; не заменяет отдельный persisted Telegram secret |
| `strategyProfile` | `defaultLightweight` | Структурированный профиль ниже |
| `establishTunnel` | true | Поднимать ли Android TUN |
| `tunnelMtu` | 8500 | MTU Android TUN |

Linux plugin использует `strategyProfile` и `cloudflareEnabled`. Остальные
поля не управляют NFQUEUE, queue number или произвольными путями/командами.

### StrategyProfile

Поля профиля: `id`, `name`, `description`, `unmatchedTrafficPolicy`, `blobs`,
`rules`, optional `endpointPolicies`. Единственная unmatched policy — `direct`.
`blobs` — map ключа на asset path. Каждое правило содержит `id`, `name`,
`tcpPorts`, `udpPorts`, `protocols`, `hostlists`, `actions`.
Протоколы: `http`, `tls`, `quic`.

Action: `kind` (`split`, `fake`, `udpFake`), optional `position`, `repeats`
(default 1), optional `blobKey`. Пустой hostlist означает отсутствие фильтра
по домену, а не запрет всего трафика.

Дефолтный профиль `default-lightweight`:

| Правило | Условие | Actions |
| --- | --- | --- |
| `http-hostlist-fake-split` | HTTP TCP/80, три hostlists | `fake`, `split(position=1)` |
| `tls-hostlist-split` | TLS TCP/443, три hostlists | `fake(blobKey=tls_google)`, `split(position=1)` |
| `quic-initial-fake` | QUIC UDP/443, hostlists пусты | `udpFake(blobKey=quic_google)` |

Hostlists: `qnzapret/lists/list-general.txt`, `list-user.txt`, `list-google.txt`.
Blobs: `tls_google → qnzapret/payloads/tls_clienthello_www_google_com.bin`,
`quic_google → qnzapret/payloads/quic_initial_www_google_com.bin`.
Платформенные ограничения выполнения описаны в [Android](android.md) и
[Linux](linux.md).

Endpoint policy содержит `id`, `endpointClasses`, `transport=tcp`, `route`.
Route: `kind=remoteRelay`, `protocol=socks5|httpsConnect`, `host`, `port`, optional
`auth={username,password}`, `connectTimeoutMs=3000`,
`relayConnectTimeoutMs=5000`, `failureMode=failClosed`. Android transport
реализует SOCKS5 для распознанных Telegram endpoint classes и отклоняет
httpsConnect. Linux plugin отклоняет непустые endpoint policies. Credentials
не должны попадать в логи или checked-in profiles.

## Snapshot и готовность

Состояния runtime: `idle → starting → running → stopping → idle`; ошибки
публикуются как `failed`. Backend остаётся источником фактического состояния.

| Поля | Значение |
| --- | --- |
| `platform`, `state`, `message` | Платформа, lifecycle и описание |
| `backendConnected`, `backendVersion` | Связь с backend и его версия |
| `vpnPermissionGranted` | Android consent; Linux возвращает true как совместимость модели, это не Polkit approval |
| `serviceActive`, `activeProfileName` | Активность сервиса и профиль |
| `strategyEngineReady`, `trafficForwarderReady` | Готовность engine и передачи; для Linux — NFQUEUE worker и rules |
| `tunnelActive` | Legacy Android TUN flag; на Linux false |
| `trafficInterceptionMode` | `none`, `androidVpnTun`, `linuxNfqueue` |
| `trafficInterceptionActive`, `interceptionReady` | Активность и проверенная готовность перехвата |
| `queueRegistered`, `nftRulesInstalled` | Linux NFQUEUE и собственные firewall rules |
| `runtimeOwnerUid` | UID владельца Linux runtime, когда известен |
| `packetCodecReady`, `udpForwarderReady`, `ipv6PacketCodecReady`, `ipv6UdpForwarderReady`, `tcpForwarderReady` | Android capabilities; на Linux false, поскольку он не использует эти forwarders |
| `telegramSidecarState` | `unavailable`, `idle`, `starting`, `running`, `stopping`, `failed`; применяется и к Android local proxy |
| `telegramCompatibilityProxyReady`, `telegramCompatibilitySetupRequired` | Готовность Telegram и необходимость настройки; платформенное расхождение описано ниже |
| `telegramCompatibilityProxyEndpoint`, `telegramCompatibilityProxyMessage` | Локальный endpoint и описание |
| `degraded`, `partialFailureCode`, `partialFailureMessage` | Частичный сбой отдельно от основного lifecycle |

`ProxyRuntimeSnapshot.fromMap` сохраняет legacy-совместимость: при отсутствии
новых полей выводит Android interception mode из `tunnelActive`, а Linux queue
и rules — из старых ready flags. Если нет `interceptionReady`, вычисляет его
из interception/forwarder и, для Linux, queue+rules. Новые backend должны
передавать фактические flags, не полагаться на эти fallback.

View model считает перехват проверенным при `interceptionReady` и, на Linux,
одновременно `queueRegistered && nftRulesInstalled`. Статус «Защита активна»
требует `running`, `serviceActive`, проверенный перехват и отсутствие partial
failure. Это статус готовности компонентов, не доказательство обхода каждого
домена. Telegram `running` также отделён от живого bridge.

На Linux proxy ready требует живого WS/H2 bridge. В текущем Android manager
ready означает запущенный listener, а setupRequired отдельно учитывает
handshake/bridge. Поскольку общий UI приоритетно проверяет ready, он может
показать «Подключён» до подтверждённого bridge. Это незавершённая унификация
семантики; её нельзя скрывать общим названием поля.

## События и ошибки

```json
{"type":"snapshot","snapshot":{"platform":"linux","state":"starting"}}
{"type":"log","log":{"timestampMillis":0,"level":"info","source":"runtime","code":"runtime_event","message":"Описание события"}}
```

Первый пример показывает envelope с сокращённым snapshot. Log levels:
`debug`, `info`, `warning`, `error`; timestamp — Unix epoch milliseconds
(`timestamp` ISO string поддерживается Dart parser как fallback).
Android bridge публикует snapshots и lifecycle log при смене state;
это не трансляция всего logcat. Linux передаёт D-Bus SnapshotChanged/LogEvent.

Контроллер сохраняет `PlatformException.code/message/details`,
`MissingPluginException` превращает в `missing_plugin`, прочие исключения —
в `runtime_error`. Повторная параллельная команда получает `runtime_busy`.
Примеры native codes: Android `vpn_permission_required`, `vpn_start_failed`,
`android_telegram_unavailable`; Linux `linux_authorization_denied`,
`linux_runtime_owned_by_other_user`, `linux_queue_conflict`,
`linux_queue_registration_timeout`, `linux_telegram_start_failed`.
Список кодов расширяется вместе с реализацией; UI должен сохранять неизвестный
код и безопасный текст, а не терять ошибку из-за allowlist на экране.

## Linux D-Bus

| Bus | Name / interface | Object path |
| --- | --- | --- |
| system | `dev.qnzapret.Runtime1` | `/dev/qnzapret/Runtime1` |
| session | `dev.qnzapret.Telegram1` | `/dev/qnzapret/Telegram1` |

Runtime API version — `1.0.0`. System methods: `GetVersion() → s`,
`Prepare() → a{sv}`, `GetSnapshot() → a{sv}`, `Start(s profile)`, `Stop()`.
Session methods: `Prepare()`, `GetSnapshot()`, `Start()`, `Stop()`, `Retry()`,
`GetSetupUri() → s`. Оба публикуют `SnapshotChanged(a{sv})`, `LogEvent(a{sv})`.
GetSetupUri содержит secret и не предназначен для диагностических публикаций.

Runner объединяет system и Telegram snapshots. Failed/unavailable sidecar
даёт `degraded=true` и `linux_telegram_unavailable`; ошибка старта sidecar
после запуска NFQUEUE вызывает rollback. Ошибка открытия Telegram setup даёт
warning без rollback; явный `setupTelegram` возвращает ошибку.

Linux profile передаётся текстом, не shell arguments:

```text
profileId=default-lightweight
profileName=Default lightweight
unmatched=direct
blob=<key>|<asset path>
rule=<id>|<tcp ports CSV>|<udp ports CSV>|<protocols CSV>|<hostlists CSV>|<actions>
```

Action wire: `kind:blobKey:position:repeats`, actions разделяются `;`.
Пример: `fake:tls_google::1;split::1:1`. Compiler ограничивает payload 64 KiB,
разрешённые identifiers/paths и комбинации правил. Формат не является общим
API произвольных флагов nfqws2.

## Как менять контракт

В одном изменении обновляются Dart models/controller, затронутые adapters,
Android bridge/store и/или Linux plugin/daemon, их тесты и этот документ.
Новые состояния должны отдельно отражать permission, service, interception,
Telegram и partial failure. Платформенные детали остаются в backend;
экран не должен интерпретировать native exception вместо общего view model.

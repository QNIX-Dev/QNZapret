# Runtime Bridge Contract

## Назначение документа

Этот файл фиксирует текущий контракт между Flutter frontend и platform/runtime implementations.

Источник кода:

- `lib/core/backend/proxy_runtime.dart`
- `lib/core/backend/android_proxy_runtime.dart`
- `lib/core/backend/proxy_runtime_controller.dart`
- `lib/core/backend/proxy_runtime_factory.dart`
- `lib/core/backend/backend.dart`
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretQuickSettingsTileService.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnRuntimeStore.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt`
- `android/app/src/main/kotlin/dev/qnzapret/AndroidNetworkSelfTest.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TelegramCompatibilityProxyManager.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TelegramCloudflareResolver.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TelegramMtProxyCrypto.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TelegramRouteConfigProvider.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TelegramWebSocketTransport.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyProfile.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetStore.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetVerifier.kt`
- `android/app/src/main/kotlin/dev/qnzapret/HostlistMatcher.kt`
- `android/app/src/main/kotlin/dev/qnzapret/L7Detectors.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimeEngine.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimePlan.kt`
- `android/app/src/main/kotlin/dev/qnzapret/LocalStrategyProxy.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategySocks5Server.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TProxyService.kt`
- `android/app/src/main/kotlin/dev/qnzapret/IpPacketCodec.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QuicHostCorrelation.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TcpRelayState.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt`
- `android/app/src/main/kotlin/dev/qnzapret/UnderlyingNetworkSelector.kt`

Связанные handoff-документы:

- `docs/android_runtime_handoff.md`
- `docs/android_uid_network_blocker.md`
- `docs/android_telegram_remote_relay_contract.md`
- `docs/android_telegram_cloudflare_routes.md`
- `docs/android_telegram_tg_ws_proxy_research.md`

Если код и документ расходятся, источником правды считается код.
При любом существенном изменении контракта нужно обновлять и код, и этот документ в одном наборе изменений.

## Цель контракта

Сделать так, чтобы:

- frontend работал от стабильного Dart API
- Android/Linux/Windows adapters можно было развивать независимо от UI
- backend-состояние не расползалось по виджетам
- platform-specific детали не попадали напрямую в presentation layer

## Актуальный Dart API

```dart
abstract interface class ProxyRuntime {
  ProxyPlatform get platform;

  Future<ProxyPrepareResult> prepare();

  Future<ProxyRuntimeSnapshot> getSnapshot();

  Future<void> start(ProxyLaunchConfig config);

  Future<void> stop();
}
```

Текущие реализации:

- `StubProxyRuntime` - stub для состояния, где нативный backend еще не подключен.
- `AndroidProxyRuntime` - adapter поверх Android `MethodChannel`.
- `ProxyRuntimeController` - UI/application facade над `ProxyRuntime`, который хранит snapshot, busy/error state и default launch config.
- `createDefaultProxyRuntime()` - composition helper: на Android возвращает `AndroidProxyRuntime`, на Linux/Windows возвращает stub-адаптеры до появления desktop runtime.
- `RuntimeController` в `lib/core/state/runtime_controller.dart` - Riverpod application layer, который превращает `ProxyRuntimeController` в продуктовые CTA, status chips и локальные diagnostic logs.

Рекомендуемая точка входа для frontend:

```dart
import 'package:qnzapret/core/backend/backend.dart';

final controller = ProxyRuntimeController(runtime: runtime);
await controller.initialize();
await controller.prepare();
await controller.start();
await controller.stop();
```

## Платформы

```dart
enum ProxyPlatform { android, linux, windows }
```

Текущий реальный adapter начат только для Android.
Linux и Windows должны по возможности прийти к той же Dart-поверхности runtime.

## Runtime states

```dart
enum ProxyRuntimeState { idle, starting, running, stopping, failed }
```

Интерпретация:

- `idle` - runtime не активен и готов к следующему действию
- `starting` - runtime находится в процессе запуска
- `running` - Android foreground service активен; детали готовности engine/forwarder смотрятся в snapshot flags
- `stopping` - runtime находится в процессе остановки
- `failed` - операция runtime завершилась ошибкой или runtime не может продолжать работу

Важно: сейчас `running` на Android означает, что foreground `VpnService` активен и native runtime прошел стартовый lifecycle.
Это еще не означает, что TUN fd уже передан в `hev-socks5-tunnel` и связан с реальным локальным SOCKS5 proxy.
Для этого в snapshot есть отдельный флаг `trafficForwarderReady`.

## Модели

### `ProxyPrepareResult`

Назначение:

- результат подготовки platform runtime перед запуском
- на Android сейчас отражает результат VPN permission flow

Поля:

- `granted`
- `message`

Wire payload:

```dart
{
  'granted': true,
  'message': 'VPN-разрешение получено. Сервисы готовы к запуску.',
}
```

### `ProxyLaunchConfig`

Назначение:

- минимальная конфигурация запуска runtime
- передается из Dart в platform adapter

Поля:

- `localHost`
- `localPort`
- `poolSize`
- `cloudflareEnabled`
- `secret`
- `strategyProfile`
- `establishTunnel`
- `tunnelMtu`
- default preset: `ProxyLaunchConfig.defaultAndroidStrategy`

Wire payload:

```dart
{
  'localHost': '127.0.0.1',
  'localPort': 1080,
  'poolSize': 8,
  'cloudflareEnabled': true,
  'secret': 'token',
  'strategyProfile': StrategyProfile.defaultLightweight.toMap(),
  'establishTunnel': true,
  'tunnelMtu': 8500,
}
```

Текущий Android service читает эти значения, парсит `strategyProfile`, компилирует его в `StrategyRuntimePlan`, проверяет assets и поднимает local strategy SOCKS5 proxy с native strategy engine.
По умолчанию `establishTunnel` равен `true`, чтобы Android smoke и обычный запуск поднимали TUN default-route и передавали TUN fd в `hev-socks5-tunnel`, который перенаправляет TCP/UDP в локальный SOCKS5 proxy.
Если `establishTunnel` явно передать как `false`, Android layer стартует engine/service без `VpnService.Builder.establish()` и не перехватывает трафик.

### `StrategyProfile`

Назначение:

- описать no-root subset стратегии в переносимой форме
- отделить продуктовые пресеты от Android-specific implementation
- передать hostlists, L7 filters и поддерживаемые actions в platform adapter

Текущая дефолтная стратегия:

- HTTP TCP/80 hostlist rule с `fake(repeats: 1)` и `split(position: 1)`
- TLS TCP/443 hostlist rule с `fake(blobKey: tls_google)` и `split(position: 1)`
- QUIC UDP/443 rule с `udpFake(blobKey: quic_google)` для всех распознанных QUIC Initial datagrams
- `unmatchedTrafficPolicy: direct` - TCP-трафик, который не попал в hostlists, должен проходить обычным direct forwarding без desync actions

Дефолтные Android asset paths:

- `qnzapret/lists/list-general.txt`
- `qnzapret/lists/list-user.txt`
- `qnzapret/lists/list-google.txt`
- `qnzapret/payloads/tls_clienthello_www_google_com.bin`
- `qnzapret/payloads/quic_initial_www_google_com.bin`

Эти файлы лежат в `android/app/src/main/assets/qnzapret/` и попадают в APK как Android assets.
`QnzapretAndroidRuntime` проверяет их наличие через `StrategyAssetVerifier`, загружает payload blobs через `StrategyAssetStore`, регистрирует lazy hostlist matchers и добавляет результат в runtime message/snapshot.

Поддерживаемые Dart-модели:

- `StrategyProfile`
- `StrategyRule`
- `StrategyAction`
- `StrategyEndpointPolicy`
- `StrategyEndpointRoute`
- `StrategyRelayAuth`
- `StrategyProtocol`
- `StrategyActionKind`
- `StrategyEndpointTransport`
- `StrategyEndpointRouteKind`
- `StrategyRelayProtocol`
- `StrategyEndpointFailureMode`
- `UnmatchedTrafficPolicy`

Опциональное поле `endpointPolicies` описывает endpoint/pre-connect routing policies, которые применяются до L7 payload decision.
Если `endpointPolicies` отсутствует, старые профили парсятся как текущий `defaultLightweight`.
Поддержанный диагностический сценарий для endpoint/pre-connect уровня - Telegram TCP endpoints через удаленный SOCKS5 relay:

```json
{
  "endpointPolicies": [
    {
      "id": "telegram-remote-relay",
      "endpointClasses": ["telegram", "telegram_host", "mtproto_port"],
      "transport": "tcp",
      "route": {
        "kind": "remoteRelay",
        "protocol": "socks5",
        "host": "relay.example.net",
        "port": 1080,
        "auth": {
          "username": "relay-user",
          "password": "relay-password"
        },
        "connectTimeoutMs": 3000,
        "relayConnectTimeoutMs": 5000,
        "failureMode": "failClosed"
      }
    }
  ]
}
```

`auth` опционален; секреты не должны логироваться. Plain-text credentials допустимы только для локального smoke override до появления production secret storage.
Этот route не считается основным продуктовым путем для Telegram, потому что требует внешний relay. Основной Android fallback для пользователя - локальный Telegram compatibility proxy, который запускается вместе с QNZapret и открывает экран подтверждения proxy в Telegram.

Семантика hostlists:

- hostlists работают как списки включения TCP desync-обработки, а не как allowlist всего соединения
- если домен, SNI или HTTP Host не найден в TCP-списках, local strategy proxy должен форвардить поток без fake/split
- QUIC Initial в дефолтном профиле намеренно получает `udpFake` без hostlist-зависимости, потому что Android Private DNS/DoT и уже существующий DNS cache часто не дают надежного `knownHost`
- `direct` означает обычный исходящий путь через Android protected socket, чтобы соединение не возвращалось обратно в VPN
- отсутствие L7 host target на этапе детекта не должно само по себе блокировать поток; до появления отдельной политики блокировки безопасное поведение - direct forwarding

Native strategy engine сейчас умеет:

- лениво загружать hostlists из Android assets и матчить exact/suffix домены
- загружать бинарные payload blobs по ключам `tls_google` и `quic_google`
- детектить HTTP Host и TLS ClientHello SNI из первого payload chunk
- распознавать базовый QUIC Initial marker и применять `udpFake` по дефолтному QUIC rule даже без `knownHost`
- возвращать `DIRECT` для unmatched traffic, missing host и неподдержанного протокола
- возвращать `DESYNC` с resolved actions и payload bytes для matched HTTP/TLS правил

Local strategy proxy path сейчас умеет:

- принимать TCP CONNECT и UDP_ASSOCIATE через SOCKS5 от `hev-socks5-tunnel`
- открывать protected `Socket`/`DatagramSocket`, чтобы исходящий TCP/UDP не возвращался обратно в VPN
- использовать validated unrestricted underlying network для TCP/UDP sockets, если Android ее предоставляет
- для TCP upstream использовать порядок `Socket() -> VpnService.protect(socket) -> selectedNetwork.bindSocket(socket) -> connect(...)`; это сохраняет protected-семантику и отдельно проверяется self-test вариантом `protected_bind_selected_network`
- не тратить connect timeout на IPv6 destination, если выбранная underlying-сеть не имеет usable IPv6 address и default IPv6 route; в этом случае TCP/UDP upstream быстро получает controlled `NoRouteToHostException`
- если `endpointClass=telegram` и в `StrategyProfile.endpointPolicies` есть `remoteRelay` route с `protocol=socks5`, открывать protected TCP socket к удаленному relay, выполнять SOCKS5 CONNECT original target `host/ip:port` и только после успешного relay handshake отдавать stream в обычный TCP relay
- для `endpointClass=telegram` выполнять endpoint/pre-connect path до первого payload: классифицировать DC/IP до connect, пробовать ограниченный набор прямых кандидатов с коротким timeout, логировать `telegram preconnect ...`, а при полном исчерпании attempts писать `directBlockedBeforePayload=true`
- логировать timing-диагностику через `QNZapretProxy`: TCP connect start/ok/fail с `connectMs`, первый client payload, strategy decision с `decisionMs`, первый upstream byte, UDP send/receive, UDP RTT, MTU и first receive latency для DNS/QUIC/Telegram-кандидатов
- помечать диагностический `endpointClass` для известных Telegram DC диапазонов (`149.154.160.0/20`, `91.108.0.0/16`, `185.76.151.0/24`, `2001:67c:4e8::/48`) и MTProto-порта `5222`; это только диагностика, а не отдельное пользовательское proxy-направление
- вызывать `StrategyRuntimeEngine.evaluate()` на первом TCP payload chunk и на UDP datagram
- отправлять `udpFake` payload repeats перед реальным UDP datagram, если decision содержит такую action
- вести best-effort QUIC host correlation: UDP/53 DNS A/AAAA responses и первый TCP HTTP/TLS host hint сохраняют `destination IP -> host`, а UDP/443 QUIC Initial получает этот host как `knownHost` перед вызовом strategy engine
- писать YouTube-oriented UDP diagnostics без изменения Dart API: DNS correlation для `youtube/googlevideo/ytimg/ggpht`, per-second UDP receive throughput по known host, MTU, packets и first receive latency
- применять TCP `split` для TLS как no-root-safe TLS record split; для остальных TCP payload использовать best-effort stream write split
- применять TCP `fake` с resolved blob payload как best-effort: перед реальным TCP payload временно выставлять socket hop limit/TTL 8, отправлять fake payload через protected socket и возвращать дефолтный hop limit; если системная TTL/hop-limit опция недоступна, fake payload не отправляется
- останавливать `hev-socks5-tunnel`, закрывать TUN fd, временный YAML-config и локальный SOCKS5 proxy при stop/revoke/destroy lifecycle

Ограничения текущего path:

- TUN default-route включается в дефолтном Android strategy config, потому что `ProxyLaunchConfig.defaultAndroidStrategy.establishTunnel=true`; при явном `establishTunnel=false` runtime стартует без перехвата трафика
- TUN DNS берется из `LinkProperties` выбранной validated underlying-сети и добавляется в `VpnService.Builder`; hardcoded fallback используется только если системные DNS не удалось получить. Та же underlying-сеть передается в `Builder.setUnderlyingNetworks(...)`.
- IPv4 default-route добавляется всегда, а IPv6 address/`::/0` добавляются только когда выбранная underlying-сеть реально имеет usable IPv6 address и default IPv6 route. `ipv6PacketCodecReady` и `ipv6UdpForwarderReady` остаются capability flags TUN-to-SOCKS/proxy слоя, а не обещанием, что текущая мобильная сеть имеет рабочий IPv6 upstream.
- default MTU остается `8500`; снижать его до `1500` или `1280` можно только после device smoke с замерами YouTube/QUIC/TCP fallback, потому что MTU влияет на весь VPN path.
- пакет приложения исключается из VPN через `Builder.addDisallowedApplication(...)`; TUN выбирает валидированную unrestricted underlying network с `INTERNET` и без `TRANSPORT_VPN` через `ConnectivityManager`, передает ее в `Builder.setUnderlyingNetworks(...)` и берет DNS из ее `LinkProperties`; Android manifest должен содержать `ACCESS_NETWORK_STATE`, иначе чтение network state/DNS будет запрещено
- Android runtime запускает controlled network self-test из процесса приложения с лог-тегом `QNZapretNetTest`: один проход выполняется после foreground-start, но до старта runtime/TUN, второй - после старта runtime/TUN. Тест не меняет Dart API и не добавляет MethodChannel method.
- QUIC correlation является best-effort: она покрывает обычный UDP/53 DNS path и prior TCP HTTP/TLS host hints, но не видит DoH/DoT, DNS cache misses, pre-existing OS cache до старта VPN и сложные случаи, где QUIC IP отличается от уже связанного host. Поэтому дефолтный QUIC rule не требует hostlist match.
- TCP `fake` в no-root режиме остается best-effort socket-level действием: runtime не делает raw sequence/timestamp tricks, а только пробует low-hop-limit отправку blob payload. Если Android или сеть не дают надежно применить TTL/hop-limit, fake пропускается, чтобы не слать полноценный ложный ClientHello на настоящий сервер.
- TCP stream split через обычный socket остается best-effort: отдельные `OutputStream.write()` не гарантируют сохранение TCP packet boundaries на всех сетевых стеках. Для TLS используется более надежный TLS record split, который меняет границы TLS records без raw TCP tricks.
- Android foreground notification показывает состояния запуска, активной передачи, ошибки и остановки, а также actions `Остановить` и `Перезапустить`. На Android 13+ `MainActivity` запрашивает `POST_NOTIFICATIONS`, иначе система может блокировать видимость notification даже при активном foreground service. Скорость передачи в notification не отображается, пока нет стабильного counters contract из `TProxyService` stats или proxy relay counters.
- Android Quick Settings Tile является native control surface поверх того же runtime store и `QnzapretVpnService`: tile показывает active/inactive/unavailable state, запускает дефолтный Android runtime при выданном VPN permission, останавливает через service stop action и открывает `MainActivity`, если permission еще не выдан. Tile не добавляет MethodChannel method и не меняет Dart API.
- Remote relay/proxy route для Telegram реализован для SOCKS5 TCP relay как diagnostic/enterprise fallback. Конфиг идет через `StrategyProfile.endpointPolicies` или локальный Android smoke override, а relay получает original target `host/ip:port`. HTTPS CONNECT и production secret storage остаются TODO в `docs/android_telegram_remote_relay_contract.md`.
- Telegram compatibility mode реализован без Go-core/JNA: `QnzapretVpnService` поднимает локальный Kotlin MTProxy endpoint `127.0.0.1:1443`, генерирует локальный `dd` secret, открывает `tg://proxy`/`https://t.me/proxy` для пользовательского подтверждения в Telegram и останавливает proxy вместе с VPN runtime. После подтверждения Telegram подключается к локальному proxy, а Kotlin transport открывает protected WSS upstream `/apiws` к Telegram Web route candidates. Upstream MTProxy obfuscation init обязан сохранять protocol marker и signed DC id из клиентского handshake, включая media/negative DC.
- Cloudflare route layer для Telegram строит кандидаты из локального clean-room config: для каждого `cfDomain` пробуются `kwsN.<cfDomain>` и затем `kwsN-1.<cfDomain>`. Для direct Telegram Web media/negative DC сохраняется fallback на `kwsN-1.web.telegram.org`, но Cloudflare-домены начинают с `kwsN`, потому что public/own CF setups обычно публикуют именно эти host records. Media route не дает score sorting поднять Cloudflare `kwsN-1` выше `kwsN`; local CF domains остаются первыми, а public CF defaults идут после direct Web fallback, чтобы public edge throttling не держал media в долгом тупике. Если локальный strategy proxy уже поднят, direct Web candidates подключаются через него к resolved Telegram IP с сохранением TLS SNI/HTTP `Host`, чтобы использовать Telegram preconnect, same-DC alternatives, relay policy и first-payload desync вместо обхода всего runtime через protected raw socket. Если direct Web fallback получает timeout/DNS/TLS/WS failure, runtime ставит direct route для этого DC в cooldown и следующие media retries сразу переходят к CF/public candidates. Active CF domain сохраняется в prefs и пробуется первым внутри своего source priority bucket; HTTP 429 ставит домен в in-memory cooldown на 45 секунд.
- `TelegramRouteConfigProvider` собирает Telegram route domains в порядке local `qnzapret/telegram_compat.json` -> cached public Flowseal upstream domains -> fresh public Flowseal upstream fetch -> future signed QNZapret route config endpoint. Local domains всегда остаются выше public defaults.
- Public upstream defaults берутся из MIT Flowseal `https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt`, декодируются clean-room Flowseal-compatible decoder-ом, валидируются и кэшируются в app-private storage на 12 часов. При fetch failure используется cache; если cache пустой, остаются только local domains.
- Для clean-room smoke Cloudflare route domains передаются локальным файлом `qnzapret/telegram_compat.json` в app-private/external/cache files. Пример: `{"cfDomains":["cf-route.example.com"],"cfPriority":true,"tlsVerify":true}`. Реальные приватные домены не хардкодятся и не коммитятся.
- Provider запускает background domain probe для DC 2/4: успех - WSS `/apiws` HTTP 101, 429 - cooldown 45 секунд, 403/timeout/TLS/WS failures получают стабильный `errorCode`. Новые Telegram-сессии берут обновленный route config без перезапуска всего VPN.
- Telegram WSS transport сохраняет TLS SNI и HTTP `Host` равными route candidate host даже при connect к resolved IP. `tlsVerify` по умолчанию `true`; `false` допустим только в локальном smoke config и логируется как `securityWarning=tls_verify_disabled`.
- Telegram WSS transport использует короткие timeout только для DNS/TCP/TLS/HTTP 101 handshake. После успешного WebSocket upgrade socket read timeout сбрасывается в бесконечный stream mode; media idle/stall контролируется watchdog-ом и route scoring, а не 5-секундным socket timeout.
- Telegram WSS transport держит lightweight one-shot pool: ключ `dcId + mediaDc + route host`, соединение заранее доведено до HTTP 101, но relay init не отправлен; после выдачи реальной MTProxy-сессии WSS используется один раз и не возвращается в pool. Размер по умолчанию - 2 на observed key, максимум 4 total, idle age 60 секунд.
- Client-to-WSS relay не отправляет произвольные TCP read chunks как отдельные WebSocket messages. После расшифровки client MTProxy stream runtime собирает целые MTProto transport packets по `abridged`/`intermediate`/`padded_intermediate` length header и только затем шифрует/пишет packet-aligned WSS frames. Это критично для voice/circle/file upload: иначе большой upload может уходить upstream кусками, но Telegram Web endpoint не присылает ack/response.
- Telegram route scoring остается internal runtime state: EWMA по DNS/TCP/TLS/WS handshake, first upstream payload, session throughput и failures влияет на порядок кандидатов, но не является публичным Dart contract. Score применяется раньше active-domain tie-breaker, поэтому ранее активный домен может уступить более здоровому peer после failures. Долгие или live-застрявшие media-сессии с низким свежим progress/throughput помечаются как `low_media_throughput` и отправляют только этот CF domain в media-specific cooldown, чтобы text-сессии не штрафовались. Для итогового scoring используется общий media progress (`max(bytesUp, bytesDown)`), поэтому upload-heavy media sessions вроде voice/video messages не штрафуются только из-за малого downstream. Upload-heavy sessions, которые передали больше 128 KB upstream, но почти не получили upstream ack/response и перестали делать свежий progress, закрываются как `low_upload_ack`; для их CF domain ставится 5-минутный общий cooldown, чтобы следующий voice/circle/file upload не возвращался на тот же edge.
- Telegram compatibility diagnostics пишет session id, `dc/rawDc/mediaDc`, `text/media` flow, chosen route, pooled flag, DNS/TCP/TLS/WS timings, first payload в обе стороны, bytesUp/bytesDown, 1-секундный throughput window для media, watchdog close по `low_media_throughput`/`low_upload_ack` и 5-секундный window для text, close reason. Эти логи не должны содержать local secret.
- Неверные/устаревшие Telegram proxy entries могут генерировать много локальных bad-handshake попыток к `127.0.0.1:1443`; runtime не логирует каждую такую попытку, а пишет ограниченное число samples с безопасным marker hex и summary по suppressed count.
- `TelegramCloudflareResolver` сначала пробует system/network DNS, затем DoH и UDP DNS fallback с короткими timeout, предпочитает IPv4 на IPv4-only underlying-сети и кэширует resolved IP на 5 минут. Контрольные логи: `telegram cf dns start/ok/failed`, `telegram cf route start/attempt/ok/failed`.

Важно: эта модель намеренно описывает proxy/stream-oriented no-root subset.
Она не обещает полную семантику `nfqws2`, raw TCP sequence tricks, TCP timestamp fooling (`ts`), точный контроль TTL за пределами best-effort socket option или IP fragmentation.

### `ProxyRuntimeSnapshot`

Назначение:

- единый snapshot текущего runtime-состояния для UI и application layer

Поля:

- `platform`
- `state`
- `message`
- `backendConnected`
- `vpnPermissionGranted`
- `serviceActive`
- `strategyEngineReady`
- `trafficForwarderReady`
- `tunnelActive`
- `packetCodecReady`
- `udpForwarderReady`
- `ipv6PacketCodecReady`
- `ipv6UdpForwarderReady`
- `tcpForwarderReady`
- `activeProfileName`
- `telegramCompatibilityProxyReady`
- `telegramCompatibilitySetupRequired`
- `telegramCompatibilityProxyEndpoint`
- `telegramCompatibilityProxyMessage`

Wire payload:

```dart
{
  'platform': 'android',
  'state': 'running',
  'message': 'Ядро обхода активно.',
  'backendConnected': true,
  'vpnPermissionGranted': true,
  'serviceActive': true,
  'strategyEngineReady': true,
  'trafficForwarderReady': false,
  'tunnelActive': false,
  'packetCodecReady': true,
  'udpForwarderReady': true,
  'ipv6PacketCodecReady': true,
  'ipv6UdpForwarderReady': true,
  'tcpForwarderReady': true,
  'activeProfileName': 'Default lightweight',
  'telegramCompatibilityProxyReady': true,
  'telegramCompatibilitySetupRequired': false,
  'telegramCompatibilityProxyEndpoint': '127.0.0.1:1443',
  'telegramCompatibilityProxyMessage': 'Telegram compatibility proxy слушает 127.0.0.1:1443.',
}
```

Семантика:

- `backendConnected` сейчас означает, что platform bridge base доступен, а не что TUN-to-SOCKS слой уже полностью обрабатывает трафик.
- `vpnPermissionGranted` актуально для Android.
- `serviceActive` отражает активность Android foreground service.
- `strategyEngineReady` означает, что native strategy engine создан, payload blobs загружены, а hostlists зарегистрированы для lazy matching.
- `trafficForwarderReady` означает, что TUN fd передан в `hev-socks5-tunnel`, а локальный strategy SOCKS5 proxy готов принимать TCP/UDP. При `establishTunnel=false` остается `false`, даже если capability flags готовы.
- `tunnelActive` означает, что Android TUN fd реально установлен. При `establishTunnel=false` остается `false`; при `establishTunnel=true` становится `true`, если Android вернул TUN fd.
- `packetCodecReady` означает готовность TUN-to-SOCKS слоя принимать IPv4 трафик через `hev-socks5-tunnel`.
- `udpForwarderReady` означает готовность SOCKS5 UDP_ASSOCIATE и protected UDP relay.
- `ipv6PacketCodecReady` означает готовность TUN-to-SOCKS слоя принимать IPv6 трафик через `hev-socks5-tunnel`.
- `ipv6UdpForwarderReady` означает, что UDP relay умеет работать с IPv6 destination/source addresses.
- `tcpForwarderReady` означает готовность SOCKS5 CONNECT и protected TCP relay.
- `activeProfileName` дает UI человекочитаемое имя профиля, если runtime активен.
- `telegramCompatibilityProxyReady` означает, что встроенный Kotlin MTProxy endpoint для Telegram compatibility mode слушает локальный порт.
- `telegramCompatibilitySetupRequired` означает, что для текущего локального Telegram proxy fingerprint (`host:port:secret`) в текущем запуске еще не было подтвержденной живой MTProxy-сессии: успешный client handshake плюс старт WSS bridge. Факт открытия Telegram proxy confirmation screen больше не считается настройкой. Если пользователь удалил или выключил proxy в Telegram, после перезапуска QNZapret не увидит входящего handshake/bridge и снова покажет `setupRequired=true`.
- `telegramCompatibilityProxyEndpoint` - локальный endpoint, который передается в `tg://proxy` / `https://t.me/proxy`.
- `telegramCompatibilityProxyMessage` - человекочитаемый статус локального Telegram proxy.
- `message` должен быть пригоден для отображения в UI. На текущем Android path user-facing сообщения возвращаются на русском; технические имена flags остаются wire protocol.

### `ProxyRuntimeController`

Назначение:

- дать UI один объект для привязки кнопок, статуса и ошибок
- скрыть `PlatformException`, `MissingPluginException` и busy-state внутри backend layer
- держать default launch config рядом с runtime-контрактом
- обновлять snapshot после `prepare`, `start`, `stop` и ручного `refresh`

Публичные поля и команды:

- `snapshot`
- `launchConfig`
- `lastPrepareResult`
- `lastFailure`
- `isBusy`
- `needsPrepare`
- `canStart`
- `canStop`
- `isActive`
- `initialize()`
- `refresh()`
- `prepare()`
- `start([config])`
- `stop()`
- `updateLaunchConfig(config)`

UI должен предпочитать этот controller прямым вызовам `AndroidProxyRuntime`, если ему нужны кнопки, loading state и отображение ошибок.

### `RuntimeController`

Назначение:

- связать `ProxyRuntimeController` с Riverpod application state
- дать Home/Logs единый источник runtime-состояния
- собрать локальные diagnostic logs до появления production native log stream
- адаптировать технические snapshot flags в человекочитаемые русские подписи

Текущие потребители:

- `lib/features/home/presentation/home_screen.dart`
- `lib/features/logs/presentation/logs_screen.dart`

Важно: `RuntimeController` не заменяет backend contract. Если нужен новый native signal, сначала расширяется `ProxyRuntime` / `ProxyRuntimeSnapshot`, потом UI-слой использует новое поле.

## Методы

### `prepare()`

Назначение:

- подготовить platform runtime перед запуском
- на Android запросить или проверить VPN permission

Android behavior:

- если `VpnService.prepare(activity)` возвращает `null`, permission уже доступен
- иначе открывается системный consent flow
- результат возвращается как `ProxyPrepareResult`

Возможная native error:

- `vpn_prepare_in_progress` - запрос разрешения уже выполняется

### `getSnapshot()`

Назначение:

- получить актуальный snapshot runtime-состояния

Android behavior:

- вызывает `QnzapretVpnRuntimeStore.snapshot(context)`
- синхронизирует текущее VPN permission state через `VpnService.prepare(context)`

### `start(ProxyLaunchConfig config)`

Назначение:

- запустить platform runtime с заданной конфигурацией

Android behavior:

- требует уже выданного VPN permission
- переводит store в `starting`
- запускает `QnzapretVpnService` как foreground service
- service парсит strategy profile, компилирует runtime plan, проверяет assets и поднимает local strategy proxy с native strategy engine
- service может применить локальный dev/smoke override `qnzapret/telegram_relay.json` из app-private/external files, чтобы передать Telegram relay endpoint policy без коммита секретов в репозиторий
- service проверяет наличие hostlists и payload blobs в Android assets
- service сообщает capabilities `hev-socks5-tunnel` и local SOCKS5 relay через snapshot; `trafficForwarderReady` становится `true` только когда TUN fd реально передан в TUN-to-SOCKS слой
- service переводит store в `running`, если strategy engine успешно поднят
- если foreground service не удалось запустить, bridge возвращает `vpn_start_failed` и store получает `failed`
- если runtime внутри service упал при старте, service переводит store в `failed` и останавливается

Возможная native error:

- `vpn_permission_required` - запуск невозможен без VPN permission
- `vpn_start_failed` - Android service не удалось стартовать из bridge layer

Важно: `start()` возвращает `Future<void>`.
Для получения итогового состояния нужно вызвать `getSnapshot()` после native lifecycle update.

### `stop()`

Назначение:

- остановить platform runtime

Android behavior:

- переводит store в `stopping`
- вызывает `stopService`
- если service уже не был активен, store возвращается в `idle`
- `QnzapretVpnService.onDestroy()` также переводит store в `idle`

## Android internal diagnostics

`AndroidNetworkSelfTest` - внутренняя native-диагностика Android runtime. Она не является публичным Dart-контрактом и не добавляет MethodChannel method.

Когда запускается:

- `pre_vpn_start` - после перевода `QnzapretVpnService` в foreground, но до запуска `QnzapretAndroidRuntime`, local proxy и TUN.
- `post_tun_start` - после успешного старта `QnzapretAndroidRuntime` и попытки поднять TUN/TUN-to-SOCKS path.

Лог-тег:

```text
QNZapretNetTest
```

Каждый проход логирует UID процесса приложения, package name, selected network id, capabilities, DNS servers и Private DNS state из `LinkProperties`. Проверяемые варианты:

- plain `Socket` -> public IP `1.1.1.1:443`;
- `Socket` + `VpnService.protect(socket)` -> public IP `1.1.1.1:443`;
- `selectedNetwork.socketFactory.createSocket()` + `protect` -> public IP `1.1.1.1:443`;
- plain `Socket` + `protect` + `selectedNetwork.bindSocket(socket)` -> public IP `1.1.1.1:443`;
- `DatagramSocket` + `protect` + optional `selectedNetwork.bindSocket(socket)` -> selected DNS endpoint UDP/53 with a minimal `example.com` A-query.

`StrategySocks5Server` пишет internal runtime diagnostics в `QNZapretProxy`. Эти логи также не являются публичным Dart-контрактом:

- `socks tcp connect start/ok/failed` с target, `endpointClass`, selected network, IPv6 state и `connectMs`;
- `telegram relay connect start/ok/failed` для `remoteRelay` endpoint policy, включая `originalTarget`, sanitized `relay=host:port`, protocol, `endpointClass`, `dcClass`, `connectMs`, `relayHandshakeMs` и стабильный `errorCode`;
- `telegram relay first byte` после первого ответа upstream через relay, включая `sinceRelayConnectMs`;
- `telegram preconnect begin/attempt/ok/failed` с `originalTarget`, `targetIp`, `targetPort`, `dcClass`, `endpointClass`, `chosenAttempt`, candidate, source, timeout и ошибкой; итоговый fail получает `directBlockedBeforePayload=true`;
- `socks tcp first payload` и `socks tcp upstream first byte` для оценки времени между connect, первым payload и первым ответом upstream;
- `strategy socks transport=...` с `payloadBytes`, `decisionMs`, detected protocol/host, reason и actions;
- `socks udp send/receive` для DNS, QUIC UDP/443 и Telegram candidates, включая fake flag, MTU, UDP RTT и first receive latency;
- `socks udp dns correlation` и `socks udp throughput` для YouTube/Googlevideo host hints: эти логи нужны для smoke A/B MTU/QUIC и не расширяют публичный MethodChannel contract;
- `endpointClass=telegram` является диагностической меткой известных Telegram DC ranges/MTProto port, а не отдельным пользовательским proxy profile.
- `telegram transparent probe ...` включается только локальным flag-файлом `qnzapret/telegram_transparent_probe`, отдает early SOCKS success для Telegram endpoint, читает первый payload с коротким timeout и логирует только длину, `protoHint` и короткий redacted hex preview; это diagnostic probe, а не production transport.

`QnzapretQuickSettingsTileService` - внутренняя Android control surface. Она не меняет MethodChannel contract и не добавляет публичные Dart-поля. Для запуска из tile используется текущий дефолтный `VpnRuntimeConfig()`; для пользовательских профилей нужен будущий persistable runtime config contract.

## Android MethodChannel contract

Channel:

```text
dev.qnzapret/proxy_runtime
```

Methods:

- `prepare`
- `getSnapshot`
- `start`
- `stop`

`start` arguments:

```dart
{
  'config': ProxyLaunchConfig(...).toMap(),
}
```

Native return values:

- `prepare` возвращает map с `granted` и `message`
- `getSnapshot` возвращает map для `ProxyRuntimeSnapshot.fromMap`
- `start` возвращает `null` при успешной отправке команды запуска
- `stop` возвращает `null` при успешной отправке команды остановки

## Lifecycle expectations

### Android prepare flow

1. UI/application layer вызывает `prepare()`.
2. Android bridge проверяет `VpnService.prepare`.
3. Если нужно разрешение, открывается системный consent screen.
4. Результат возвращается в Dart как `ProxyPrepareResult`.
5. `getSnapshot()` после prepare должен отражать актуальный `vpnPermissionGranted`.

### Android start flow

1. UI/application layer вызывает `start(config)`.
2. Если permission отсутствует, Android bridge возвращает ошибку `vpn_permission_required`.
3. Если permission есть, store получает `starting`.
4. Foreground `QnzapretVpnService` стартует.
5. Service получает config, компилирует strategy profile и проверяет Android assets.
6. Service стартует local strategy SOCKS5 proxy, загружает strategy engine и поднимает TUN только при `establishTunnel=true` и готовом `hev-socks5-tunnel`.
7. Runtime message отражает `unmatchedTrafficPolicy`, чтобы было видно, что трафик вне hostlists не должен попадать под desync.
8. Service переводит store в `running`.
9. `getSnapshot()` возвращает актуальное состояние.

### Android stop flow

1. UI/application layer вызывает `stop()`.
2. Store получает `stopping`.
3. Android service останавливается.
4. Store возвращается в `idle`.

## Требования к будущей backend-реализации

- Не отдавать platform-specific payloads напрямую в UI.
- Не смешивать runtime handles с Flutter widgets.
- Все низкоуровневые события сначала адаптировать до Dart-моделей контракта.
- Сохранять единый runtime API для Android/Linux/Windows, насколько это возможно.
- Не расширять контракт без понятного UI/backend сценария.
- При добавлении logs/status streams зафиксировать их здесь до активного использования в UI.

## Что желательно согласовать до production runtime

- какие поля `ProxyLaunchConfig` останутся стабильными
- какие native error codes считаются публичными
- как отличать "Android service running" от "TUN-to-SOCKS fully connected"
- как будет устроен log stream
- нужен ли отдельный health-check или diagnostics snapshot
- как local strategy proxy будет читать hostlists/payload blobs из Android assets и пользовательского storage
- какие strategy actions остаются в no-root subset, а какие явно требуют root/raw packet mode
- какая форма desktop adapters нужна для Linux и Windows

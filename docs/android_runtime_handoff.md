# Android Runtime Handoff

## Назначение

Этот файл фиксирует состояние Android runtime после перехода на clean-room схему, взятую по архитектурной идее из ByeByeDPI, но без переноса GPL-кода Android-обвязки.

Документ нужен как короткий handoff для продолжения backend-работы: что уже собрано, какие проверки прошли, где искать ключевые файлы и какие hardening-блоки остаются.

## Текущая схема

Production Android path сейчас выглядит так:

```text
Flutter UI
  -> Dart ProxyRuntime contract
  -> Android MethodChannel bridge
  -> QnzapretVpnService
  -> Android TUN fd
  -> hev-socks5-tunnel
  -> local strategy SOCKS5 proxy
  -> protected TCP/UDP sockets
```

Telegram compatibility mode работает параллельно основному VPN path:

```text
Qnzapret Start
  -> QnzapretVpnService
  -> локальный Kotlin MTProxy 127.0.0.1:1443
  -> Telegram proxy confirmation screen
  -> Telegram client
  -> protected WSS к Telegram Web endpoint
```

Архитектурная идея совпадает с локальным VPN-redirect подходом ByeByeDPI: Android VPN используется не как удаленный VPN-сервер, а как no-root перехват трафика устройства с локальной обработкой.

Важно по лицензиям:

- GPL-код Android-обвязки ByeByeDPI в проект не переносился.
- Kotlin lifecycle, bridge, service, strategy proxy и стратегия написаны внутри QNZapret.
- Сторонний TUN-to-SOCKS слой оставлен как `hev-socks5-tunnel`, потому что он распространяется под MIT.

## Что уже сделано

- Дефолтный Android launch config поднимает TUN через `establishTunnel=true`.
- Старый самописный `TunPacketForwarder.kt` удален из production path.
- `TunTransport.kt` поднимает `VpnService.Builder`, добавляет IPv4 route всегда, включает IPv6 route только при рабочем IPv6 на selected underlying-сети, добавляет DNS из выбранной underlying-сети, исключает собственный пакет через `addDisallowedApplication(...)` и передает TUN fd в `hev-socks5-tunnel`.
- `UnderlyingNetworkSelector.kt` выбирает validated unrestricted non-VPN сеть, ее DNS, Private DNS/link diagnostics и IPv6-route capability.
- `TProxyService.kt` оборачивает JNI lifecycle `hev-socks5-tunnel`.
- `StrategySocks5Server.kt` принимает SOCKS5 CONNECT и UDP_ASSOCIATE от `hev-socks5-tunnel`.
- TCP/UDP sockets local proxy открываются через `VpnService.protect`, чтобы не возвращать исходящий трафик обратно в VPN; TCP upstream использует порядок `Socket() -> protect -> selectedNetwork.bindSocket -> connect`.
- `StrategySocks5Server.kt` пишет timing-логи `QNZapretProxy` для TCP connect, первого payload, strategy decision, первого upstream byte и UDP send/receive; Telegram endpoint candidates помечаются диагностическим `endpointClass`.
- Для YouTube/QUIC добавлены runtime-логи DNS correlation, UDP send/receive с MTU, first receive latency и per-second UDP throughput по host hints `youtube/googlevideo/ytimg/ggpht`.
- Для `endpointClass=telegram` добавлен endpoint/pre-connect branch: DC/IP классифицируется до connect, прямые кандидаты пробуются ограниченной серией с коротким timeout, а полное исчерпание attempts логируется как `directBlockedBeforePayload=true`.
- Для `endpointClass=telegram` перед direct/pre-connect branch теперь проверяется `StrategyProfile.endpointPolicies`: если есть `remoteRelay` route с `protocol=socks5`, runtime открывает protected TCP socket к удаленному relay, делает SOCKS5 CONNECT original Telegram target `host/ip:port` и отдает stream в обычный TCP relay без ручной настройки proxy в Telegram.
- Для dev/smoke relay config Android service читает локальный `qnzapret/telegram_relay.json` из app-private/external files и не требует коммитить реальные relay credentials в репозиторий.
- IPv6 upstream на IPv4-only underlying-сети быстро завершается controlled no-route, чтобы YouTube/Telegram не тратили время на мертвые IPv6 попытки.
- `AndroidNetworkSelfTest.kt` выполняет controlled app-process network self-test до старта runtime/TUN и после старта TUN, пишет результаты в `QNZapretNetTest`.
- `StrategyRuntimeEngine` остается центром принятия решений по HTTP/TLS/QUIC правилам.
- TLS split сделан как no-root-safe TLS record split через `TlsRecordSplitTransform.kt`.
- TCP fake оставлен best-effort: попытка low-hop-limit socket write, безопасный пропуск при недоступной TTL/hop-limit опции.
- QUIC получает `udpFake` для распознанного QUIC Initial даже без надежной DNS-корреляции.
- Stop lifecycle починен: команда остановки доставляется в service, service останавливает runtime, `hev-socks5-tunnel`, TUN fd и local SOCKS5 proxy.
- Foreground notification стала ongoing control surface: показывает запуск/активность/ошибку/остановку и содержит actions `Остановить`/`Перезапустить`. На Android 13+ `MainActivity` запрашивает `POST_NOTIFICATIONS`, иначе система блокирует видимость notification. Скорость не показывается до появления надежного counters contract.
- В notification добавлен action `Подключить Telegram`, когда локальный Telegram compatibility proxy слушает порт. Action открывает Telegram proxy confirmation screen через user-initiated `PendingIntent`/transparent Activity только по пользовательскому тапу и не пытается запускать Telegram из фонового QS-start автоматически.
- Добавлен native Quick Settings Tile для запуска/остановки через тот же `QnzapretVpnService`; при отсутствии VPN permission tile открывает `MainActivity` для штатного consent flow.
- Remote relay/proxy route для Telegram реализован в минимальном SOCKS5-варианте как diagnostic/enterprise fallback; production secret storage и HTTPS CONNECT остаются TODO в `docs/android_telegram_remote_relay_contract.md`.
- Добавлен clean-room Kotlin Telegram compatibility proxy без Go-core/JNA и без переноса GPL Android-кода: `TelegramCompatibilityProxyManager.kt` хранит локальный `dd` secret/port и health-based setup state, `TelegramMtProxyCrypto.kt` реализует MTProxy obfuscation AES-CTR handshake, `TelegramWebSocketTransport.kt` открывает protected TLS WebSocket `/apiws` к Telegram Web route candidates.
- Telegram setup state больше не считается выполненным по факту открытия Telegram confirmation screen. Runtime хранит `lastSetupOpenedAt`, `lastSuccessfulHandshakeAt`, `lastSuccessfulBridgeAt` и fingerprint текущего `host:port:secret`; `setupRequired=false` появляется только после успешного MTProxy handshake и старта WSS bridge в текущем запуске.
- Upstream MTProxy init для WSS route сохраняет protocol marker и signed DC id из Telegram client handshake; без этого WebSocket может подниматься, но Telegram остается в состоянии подключения proxy.
- После client MTProxy init runtime держит client-to-WSS packet splitter: расшифрованный поток собирается по MTProto transport length header (`abridged`/`intermediate`/`padded_intermediate`), а в WebSocket отправляются только целые upstream-зашифрованные packets. Это сохраняет message boundaries для Telegram Web endpoint и закрывает класс upload stalls, где voice/circle/file part давал большой `bytesUp`, но почти нулевой `bytesDown`.
- Bad-handshake попытки от старых/неверных Telegram proxy entries логируются rate-limited samples с marker hex и summary по suppressed count, чтобы не забивать logcat.
- Для clean-room Cloudflare smoke поддержан локальный route override `qnzapret/telegram_compat.json` в app-private/external/cache files с форматом `{"cfDomains":["cf-route.example.com"],"cfPriority":true,"tlsVerify":true}`. Список доменов не зашивается в репозиторий и не копируется из reference fork.
- `TelegramRouteConfigProvider.kt` загружает local `telegram_compat.json`, cached public Flowseal upstream domains, fresh public upstream domains и оставляет future placeholder для signed QNZapret route config. Public upstream берется из MIT Flowseal `cfproxy-domains.txt`, декодируется clean-room decoder-ом, валидируется и кэшируется на 12 часов.
- Telegram Cloudflare route layer строит `kwsN.<cfDomain>` и `kwsN-1.<cfDomain>` для каждого base domain, но для Cloudflare media/negative DC сначала пробует `kwsN.<cfDomain>`, потому что public/own CF setups обычно не публикуют `kwsN-1` records. Score sorting не поднимает CF `kwsN-1` выше `kwsN`. Direct Telegram Web fallback по-прежнему содержит `kwsN-1.web.telegram.org`; для media local CF domains остаются первыми, а public CF defaults идут после direct fallback, чтобы public edge throttling не удерживал media на медленных WSS-сессиях. Если local strategy proxy поднят, direct Web fallback идет через него к resolved Telegram IP с сохранением TLS SNI/HTTP `Host`; так direct media может использовать Telegram preconnect, same-DC alternatives, configured relay policy и first-payload desync. Если direct Web fallback получает timeout/DNS/TLS/WS failure, runtime ставит direct route для этого DC в cooldown, чтобы следующие media retries сразу переходили к CF/public candidates. Последний успешный CF domain сохраняется как active, HTTP 429 ставит домен в cooldown на 45 секунд, а долгий или watchdog-остановленный media-flow с низким свежим throughput ставит только media cooldown для этого домена.
- `TelegramWebSocketTransport.kt` ведет lightweight one-shot WSS pool: ключ `dcId + mediaDc + route host`, размер 2 на ключ и максимум 4 соединения всего, idle age 60 секунд. Соединение из pool используется один раз: relay init отправляется только после получения реальной MTProxy-сессии.
- После WSS HTTP 101 transport сбрасывает socket read timeout в stream mode. 5-секундные timeout применяются только к connect/TLS/handshake; иначе Telegram media/upload с обычными паузами между frames закрывались бы как `network_failed`.
- Для CF route selection добавлен in-memory EWMA score по DNS/TCP/TLS/WS handshake, first upstream payload, throughput и failures. Score применяется раньше active-domain tie-breaker, поэтому активный домен не держит приоритет после failures; плохие route не удаляются навсегда и могут восстановиться после cooldown/успешных сессий. Media watchdog смотрит на свежий 5-секундный progress после стартового окна, поэтому поток, который получил первый burst и затем завис, тоже закрывается как `low_media_throughput`. Итоговый low-throughput scoring смотрит на `max(bytesUp, bytesDown)`, чтобы не считать voice/circle upload плохим только из-за малого downstream. Для voice/circle/file uploads, которые проходят как upload-heavy text sessions, session watchdog закрывает route как `low_upload_ack`, если upstream получил больше 128 KB, но почти не вернул ack/response и свежий progress остановился; CF domain получает 5-минутный общий cooldown.
- Telegram compatibility proxy логирует session id, `dc/rawDc/mediaDc`, выбранный route, pooled hit/miss, DNS/TCP/TLS/WS timings, first client/ws payload, bytesUp/bytesDown, 1-секундный media throughput window, media/upload watchdog close reason, 5-секундный text throughput window и close reason.
- Route provider делает background probe DC 2/4; успех - WebSocket HTTP 101. Fetch/probe не выполняются на main thread, чтобы не блокировать Android service start, а новые Telegram-сессии берут обновленный config без рестарта VPN.
- `TelegramCloudflareResolver.kt` добавляет system/network DNS -> DoH -> UDP fallback, short timeout, 5-минутный cache и IPv4 preference на IPv4-only underlying-сети. При connect к resolved IP transport сохраняет TLS SNI и HTTP `Host` как исходный route host; `tlsVerify=false` разрешен только явно в локальном smoke config.
- Controlled self-test оставлен как диагностический инструмент для проверки UID/app-network blocker без изменения Dart API.

## Ключевые файлы

- `lib/core/backend/proxy_runtime.dart` - Dart runtime contract и default Android strategy config.
- `lib/core/backend/proxy_runtime_controller.dart` - UI/application facade для start/stop/status.
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt` - MethodChannel bridge.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt` - foreground `VpnService`.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretQuickSettingsTileService.kt` - native Quick Settings Tile.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt` - coordinator Android runtime.
- `android/app/src/main/kotlin/dev/qnzapret/AndroidNetworkSelfTest.kt` - controlled network self-test из UID приложения.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramCompatibilityProxyManager.kt` - lifecycle локального Kotlin MTProxy compatibility proxy и открытие Telegram setup link.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramCloudflareResolver.kt` - clean-room resolver для Cloudflare route: system/network DNS, DoH/UDP fallback, IPv4 preference и 5-минутный cache.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramMtProxyCrypto.kt` - clean-room MTProxy obfuscation handshake/ciphers.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramRouteConfigProvider.kt` - route-provider: local config, public upstream fetch/decode/cache, background CF probe и future signed route config placeholder.
- `android/app/src/main/kotlin/dev/qnzapret/TelegramWebSocketTransport.kt` - clean-room WebSocket-over-TLS stream к Telegram Web/Cloudflare candidates через protected sockets, active-domain/cooldown, resolved-IP connect с сохранением SNI/Host, one-shot WSS pool и route scoring.
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt` - TUN lifecycle и запуск `hev-socks5-tunnel`.
- `android/app/src/main/kotlin/dev/qnzapret/TProxyService.kt` - JNI wrapper для `hev-socks5-tunnel`.
- `android/app/src/main/kotlin/dev/qnzapret/StrategySocks5Server.kt` - local strategy SOCKS5 proxy.
- `android/app/src/main/kotlin/dev/qnzapret/UnderlyingNetworkSelector.kt` - выбор underlying network и DNS.
- `android/app/src/main/kotlin/dev/qnzapret/TlsRecordSplitTransform.kt` - TLS record split.
- `android/app/src/main/jni/` - NDK-сборка `hev-socks5-tunnel`.

## Проверенное поведение на устройстве

На устройстве `7e7464c7` подтверждено:

- APK устанавливается через `adb install -r`.
- VPN permission уже есть или проходит штатный prepare flow.
- Start поднимает service, local strategy SOCKS5 proxy и TUN-to-SOCKS слой.
- В логах есть старт SOCKS5 TCP/UDP relay и `tun2socks started proxy=127.0.0.1:1080`.
- Трафик доходит до local strategy proxy.
- Stop больше не зависает: service получает stop action, local SOCKS5 proxy останавливается, UI возвращается в готовое состояние.

Ожидаемые контрольные логи старта:

```text
QNZapretNetTest: self-test begin stage=pre_vpn_start uid=... package=dev.qnzapret selectedNetwork=...
QNZapretNetTest: self-test result stage=pre_vpn_start test=protected_socket endpoint=1.1.1.1:443 success=...
QNZapretProxy: socks udp relay listening 127.0.0.1:60005 network=... selectedIpv6=...
QNZapretProxy: socks5 strategy proxy listening 127.0.0.1:1080
QNZapretTun: tun establish dns=192.168.1.1 underlying=104 mtu=8500 ipv6Route=...
QNZapretTun: tun2socks started proxy=127.0.0.1:1080
QNZapretTgCompat: telegram kotlin proxy listening endpoint=127.0.0.1:1443 setupRequired=...
QNZapretTgCompat: telegram setup action received
QNZapretTgCompat: telegram setup open start endpoint=127.0.0.1:1443
QNZapretTgCompat: telegram setup open ok scheme=tg
QNZapretTgCompat: telegram route provider fetch start url=...
QNZapretTgCompat: telegram route provider fetch ok domains=...
QNZapretTgCompat: telegram cf probe ok domain=... host=... httpStatus=101
QNZapretTgCompat: telegram compatibility start client=127.0.0.1:... dc=... rawDc=... mediaDc=... proto=padded_intermediate
QNZapretTgCompat: telegram cf dns ok host=kws.... ip=... source=...
QNZapretTgCompat: telegram cf route ok dc=... mediaDc=... host=kws.... route=cloudflare dnsMs=... tcpConnectMs=... tlsMs=... wsHandshakeMs=... httpStatus=101
QNZapretTgCompat: telegram ws pool miss dc=... mediaDc=...
QNZapretTgCompat: telegram ws pool refill ok key=dc=.../media/kws...
QNZapretTgCompat: telegram ws pool hit key=dc=.../media/kws...
QNZapretTgCompat: telegram compatibility throughput session=... dc=... mediaDc=... upBps=... downBps=...
QNZapretProxy: socks udp dns correlation host=...googlevideo... address=... ttlSeconds=...
QNZapretProxy: socks udp throughput target=... knownHost=...googlevideo... mtu=... bytesPerSec=...
QNZapretNetTest: self-test result stage=post_tun_start test=protected_bind_selected_network endpoint=1.1.1.1:443 success=...
QNZapretProxy: socks tcp connect start target=... endpointClass=... network=... selectedIpv6=...
QNZapretProxy: socks tcp connect ok target=... connectMs=...
QNZapretProxy: strategy socks transport=... payloadBytes=... decisionMs=...
```

Ожидаемые контрольные логи остановки:

```text
QNZapretBridge: stop requested
QNZapretBridge: stop command delivered=true stopRequested=true
QNZapretService: stop action received
QNZapretTgCompat: telegram kotlin proxy stopped
QNZapretProxy: socks5 strategy proxy stopped
```

## Проверки

Последний известный зеленый набор:

- `flutter analyze --no-pub` - passed
- `flutter test --no-pub` - passed
- `cd android; .\gradlew.bat :app:testDebugUnitTest` - passed
- `cd android; .\gradlew.bat :app:assembleDebug` - passed
- `adb install -r build\app\outputs\apk\debug\app-debug.apk` - success

Перед финальным merge/push после новых правок документации стоит повторить хотя бы:

- `flutter analyze --no-pub`
- `flutter test --no-pub`
- `cd android; .\gradlew.bat :app:testDebugUnitTest`

## Что осталось

Исторический UID/app-network blocker отдельно описан в `docs/android_uid_network_blocker.md`; сейчас это regression checklist, а не главная рабочая гипотеза.

Ближайшие backend-задачи:

- завершить device smoke для Telegram, YouTube, background lifecycle и notification actions;
- выполнить Pixel 9 smoke Telegram compatibility mode: Start из UI, локальный `127.0.0.1:1443` слушает, открывается Telegram proxy confirmation screen, пользователь подтверждает, Telegram подключается через локальный proxy, Stop останавливает VPN и Kotlin MTProxy;
- проверить Telegram compatibility smoke без локального `telegram_compat.json`: public Flowseal upstream domains должны fetched/decoded/cached, CF probe должен найти HTTP 101 route или честно показать failed;
- проверить Telegram compatibility smoke с локальным clean-room `telegram_compat.json`, где задан свой рабочий CF base domain; local domain должен иметь priority выше public defaults;
- спроектировать production-канал поставки signed QNZapret route config, чтобы пользователю не нужно было вводить домены руками;
- добавить production log stream в Dart runtime contract;
- усилить local SOCKS5 proxy: write backpressure, лимиты сессий, counters contract и диагностика;
- расширить QUIC correlation для DoH/DoT, DNS cache misses и сложных multi-IP сценариев;
- позже описать equivalent bridge strategy для Linux и Windows.

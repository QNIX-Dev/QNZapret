# Android runtime

## Основные компоненты

Все Kotlin-классы ниже находятся в
[android/app/src/main/kotlin/dev/qnzapret/](../android/app/src/main/kotlin/dev/qnzapret/).
Общий API описан в [контракте](runtime_bridge_contract.md).
Незавершённые участки текущего checkout перечислены в
[руководстве разработчика](development.md#известные-проблемы-исходников).

| Компонент | Назначение |
| --- | --- |
| `MainActivity`, `ProxyRuntimeBridge` | Регистрация каналов, VPN consent, передача Start/Stop и Telegram setup |
| `QnzapretVpnService` | Foreground service, управление runtime и Telegram, notification actions, revoke/stop |
| `QnzapretVpnRuntimeStore`, `VpnConsentStore` | Snapshot сервиса и сохранение результата VPN consent |
| `QnzapretQuickSettingsTileService` | Управление из шторки; при отсутствии consent открывает приложение |
| `QnzapretAndroidRuntime` | Сборка и остановка strategy proxy + TUN transport |
| `TunTransport`, `TProxyService` | Настройка Android TUN, YAML для native core и JNI start/stop/isRunning |
| `LocalStrategyProxy`, `StrategySocks5Server` | Локальный SOCKS5 TCP CONNECT / UDP ASSOCIATE, protected upstream, relay |
| `StrategyProfile*`, `StrategyRuntimePlan`, `StrategyRuntimeEngine` | Модель профиля, compiler, распознавание и выбор действий |
| `StrategyAssetStore`, `StrategyAssetVerifier`, `HostlistMatcher`, `CompactHostlistIndex` | Assets, проверка наличия, сопоставление доменов и mmap-индексы |
| `L7Detectors`, `NonRootTcpTransform`, `TlsRecordSplitTransform`, `QuicHostCorrelation` | HTTP Host/TLS SNI/QUIC detection, безопасный TCP split, DNS/IP ↔ host correlation |
| `TcpRelayState`, `IpPacketCodec` | Вспомогательные состояния TCP relay и IP codecs; основной TUN TCP/IP stack находится в hev |
| `UnderlyingNetworkSelector`, `AndroidNetworkSelfTest` | Выбор физической сети/DNS/IPv6 и диагностика из UID приложения |
| `TelegramCompatibilityProxyManager`, `TelegramMtProxyCrypto` | Локальный MTProxy listener, handshake, шифры и двусторонний bridge |
| `TelegramRouteConfigProvider`, `TelegramCloudflareResolver`, `TelegramWebSocketTransport` | Источники маршрутов, DNS, TLS/WSS, pool, scoring и cooldown |
| `TelegramWebSocketReader`, `TelegramPendingResponse`, `TelegramGatewayProbe` | Потоковое чтение, состояние ожидаемого ответа и проверка identity gateway |
| `TelegramSetupActivity`, `TelegramSetupHealth` | Доставка proxy URI, fingerprint и история подтверждённого подключения |

## Запуск и остановка

```text
UI → AndroidProxyRuntime → ProxyRuntimeBridge
   → VpnService.prepare / системное подтверждение
   → foreground QnzapretVpnService
   → QnzapretAndroidRuntime
       → compile profile → verify assets → local SOCKS5
       → Android TUN → JNI hev-socks5-tunnel
   → Telegram compatibility proxy
   → snapshot → EventChannel → UI
```

`start` в bridge подтверждает передачу команды сервису. Готовность нужно читать
из snapshot. `prepare` работает через Activity result; кэш consent не заменяет
повторную проверку `VpnService.prepare` перед запуском.

`QnzapretAndroidRuntime` сначала запускает proxy, затем TUN transport; при
ошибке освобождает созданные ресурсы. При остановке порядок обратный: hev/TUN,
затем SOCKS5. Сервис дополнительно закрывает Telegram proxy. `onDestroy` и
`onRevoke` входят в lifecycle; revoke сбрасывает consent. Сервис возвращает
`START_NOT_STICKY`, always-on support в manifest выключен.

Закрытие Flutter Activity само по себе не является Stop VPN. Пользователь
может управлять сервисом через notification и Quick Settings. Конкретную
работоспособность текущего lifecycle нужно подтверждать сборкой и device smoke.

## TUN и сокеты

```text
Другое приложение → VpnService TUN fd → hev-socks5-tunnel
    → SOCKS5 127.0.0.1:1080 → StrategySocks5Server
    → VpnService.protect(socket) → выбранная физическая сеть → интернет
```

TUN получает `10.24.0.2/32`, маршрут `0.0.0.0/0`, MTU по умолчанию 8500.
IPv6 `fd00:24::2/128` и `::/0` добавляются только при наличии пригодного IPv6
маршрута выбранной underlying network. DNS берётся у неё, fallback — `1.1.1.1`.
Собственный пакет исключается через `addDisallowedApplication`.

SOCKS5 server защищает TCP/UDP upstream sockets и привязывает их к выбранной
сети, где это доступно. Это предотвращает возврат исходящих соединений в VPN.
UDP relay изолирует SOCKS5 associations, чтобы параллельные запросы к одному
адресу не перезаписывали получателя. DNS-ответы и замеченный TLS/HTTP host
используются для корреляции адресов QUIC.

`establishTunnel=false` оставляет локальный proxy без TUN. Наличие готового
codec или запущенного strategy engine при этом не означает перехват трафика.

## Реальные возможности стратегии

Engine распознаёт HTTP на TCP/80, TLS ClientHello/SNI на TCP/443 и QUIC Initial
на UDP/443. Выбирается первое подходящее правило. Неизвестный протокол,
отсутствующий host для hostlist-правила и отсутствие совпадения дают `direct`.
Домены нормализуются; hostlist распространяется и на поддомены.

| Действие | Android-реализация |
| --- | --- |
| HTTP `split` | Разделение первого payload на несколько записей в TCP socket |
| TLS `split` | Разделение первого handshake record и записей, включая границу внутри SNI |
| TCP `fake` | Пропускается: обычная запись fake в stream повредила бы HTTP/TLS; независимой raw-инъекции здесь нет |
| QUIC `udpFake` | Отдельные UDP payload перед исходным datagram; это не эквивалент Linux raw-packet desync |
| Остальной трафик | Обычная передача через protected sockets |

Для Telegram Web TLS предусмотрен отдельный split даже при direct-решении
общего engine. Наличие `fake` в общем профиле или `engineReady=true` не
доказывает, что Android выполнил все действия Linux zapret2.

Hostlists/payloads берутся из [runtime/assets/qnzapret/](../runtime/assets/qnzapret/).
Gradle запускает [build_hostlist_indexes.py](../packaging/android/build_hostlist_indexes.py):
индексы и manifest попадают в generated assets. Matcher проверяет размер/SHA256,
копирует индекс во внутренний `filesDir/hostlist-indexes` и открывает mmap.
При отсутствии индекса читает исходный список потоком с ограниченным LRU cache.
`StrategyAssetVerifier` проверяет наличие paths профиля; это не полная проверка
runtime manifest по SHA256.

## Telegram

Telegram может терять доступ к DC ещё на TCP connect, до отправки первого
payload. Поэтому отдельный MTProxy path нужен независимо от HTTP/TLS split:

```text
Telegram → локальный MTProxy, обычно 127.0.0.1:1443
    → Kotlin obfuscated MTProto handshake / re-encryption
    → WSS /apiws → Telegram Web endpoint или CF gateway
```

Manager генерирует и сохраняет secret, выбирает свободный порт и формирует
`dd` proxy URI. Если порт изменился, меняется fingerprint настройки.
`TelegramSetupActivity` открывает штатное подтверждение Telegram; приложение
не может подтвердить его за пользователя. Открытый диалог не равен успешному
handshake, а handshake не равен успешному bridge.

В текущем manager `ready=true` выставляется уже для работающего listener;
`setupRequired` отдельно учитывает handshake/bridge текущего запуска.
Поэтому Android `telegramCompatibilityProxyReady` пока нельзя трактовать
как индикатор живого bridge, в отличие от Linux. Общий UI проверяет ready
раньше setupRequired; это расхождение состояния требует исправления.

Route provider объединяет `qnzapret/telegram_compat.json` и public cache,
обновляет список из фиксированного Flowseal URL в фоне, декодирует домены и
проверяет WSS upgrade. Cache живёт во внутреннем filesDir, TTL — 12 часов;
при неудаче загрузки может использоваться устаревший cache. Локальный config
ищется по порядку в external files, filesDir, cacheDir и задаёт `cfDomains`,
`cfPriority`, `tlsVerify` (по умолчанию true). Signed config сейчас placeholder.
В assets есть public seed, но текущий provider напрямую его не загружает.

Transport различает обычные и media routes, локальные и публичные CF domains,
direct `kws*.web.telegram.org`, учитывает cooldown, задержки и throughput,
поддерживает pool соединений. Успешный HTTP 101 подтверждает WebSocket upgrade,
но ещё не правильность MTProto gateway. В коде есть отдельные gateway/reader
helpers; их наличие само по себе не доказывает подключение к live transport.
Например, текущий `routeCandidates` принимает DC 1–5; заявления старых отчётов
о завершённой поддержке CDN203 нельзя переносить на этот checkout.

Отдельно от compatibility mode существует route policy для TCP Telegram
endpoints: `endpointPolicies → remoteRelay`. `StrategyProfileDevOverrides`
читает `qnzapret/telegram_relay.json` из тех же локальных зон. Реализован SOCKS5
relay с optional username/password и `failClosed`; `httpsConnect` есть в модели,
но transport его отклоняет. У дефолтного профиля policies пусты. Это отдельный
путь для настроенного удалённого relay, не обязательный сервер основного VPN.

## Диагностика

| Тег logcat | Что искать |
| --- | --- |
| `QNZapretService`, `QNZapretBridge` | Consent, команды и lifecycle |
| `QNZapretTun` | Underlying network, DNS, IPv6 route, TUN и native start |
| `QNZapretProxy` | TCP connect/first byte, strategy decision, UDP, DNS correlation |
| `QNZapretNetTest` | Контрольные TCP/UDP соединения из UID приложения |
| `QNZapretTgCompat` | Route probe, MTProxy handshake, pool, transport, session watchdog |

```bash
adb devices -l
adb -s <DEVICE_ID> logcat -d -s QNZapretService QNZapretTun QNZapretProxy QNZapretNetTest QNZapretTgCompat
adb -s <DEVICE_ID> shell dumpsys connectivity
adb -s <DEVICE_ID> shell dumpsys netpolicy
```

Разбирать сбой нужно по цепочке: TUN → SOCKS5 → protected connect → первый
payload/ответ → стратегия → Telegram transport. Успех `adb shell` не доказывает
доступность сети из UID приложения. При отказе исходящего connect сначала
проверяют выбранную сеть, DNS/Private DNS, IPv6 и системные ограничения UID.
Матрица device smoke и команды тестов — в [development.md](development.md).

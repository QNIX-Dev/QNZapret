# Android non-root runtime

Сверено с кодом ветки `quriee` 12 октября 2026 года. Это описание реализации,
а не подтверждение полной готовности на текущем телефоне. Выполненные проверки
и ограничения — в [отчёте обновления](android-update-2026-10-12.md).
Критерии приёмки: [Android и подготовка тестирования](android_next_stage.md).

## Основной путь

```text
Flutter -> Android MethodChannel -> QnzapretVpnService
  -> TUN fd -> MIT hev-socks5-tunnel
  -> local StrategySocks5Server 127.0.0.1:1080
  -> protected TCP/UDP sockets -> реальная сеть
```

Kotlin содержит lifecycle, strategy engine и proxy. Go-core/JNA/GPL Android
обвязка стороннего fork не используются. JNI/NDK собирает отдельный MIT
TUN-to-SOCKS компонент, а не собственный raw-пакетный TCP stack приложения.
Он обновлён до официального 2.18.0 с bool Start/Stop и isRunning JNI;
commit и nested dependencies зафиксированы в Android jni/provenance.json.

Дефолты: `establishTunnel=true`, MTU 8500, `poolSize=0`,
`cloudflareEnabled=false`. Последний flag **не выключает** Kotlin Telegram
proxy: service запускает его отдельным маршрутом. Linux defaults отличаются.

Собственный пакет исключён из VPN. Upstream sockets используют protect и,
когда доступна подходящая сеть, selected-network bind перед connect.
Underlying network выбирается как validated/unrestricted/non-VPN, с DNS из
LinkProperties. IPv4 route добавляется всегда; IPv6 route — только при usable
IPv6/default route. При отсутствии DNS есть hardcoded fallback; отсутствие
IPv6 не должно держать долгие connect timeouts.

## Стратегия

`StrategyRuntimeEngine` загружает payload blobs, регистрирует lazy exact/suffix
hostlists и принимает L7 decisions по HTTP Host/TLS SNI/QUIC Initial.
Unmatched TCP идёт direct. TLS split меняет TLS record boundaries; обычный
TLS records отправляются отдельными writes с TCP_NODELAY и зазором 10 мс:
одно concatenated write сохраняло короткий ClientHello в одном TCP segment.
При доступном SNI его имя также разделяется между writes; handshake bytes
сохраняются, новые TLS record headers не входят в transcript.
Это улучшает разделение, но не гарантирует boundaries на всех сетях.
TCP FAKE пропускается с
`raw_injection_unavailable`: запись fake в обычный TCP socket расходует sequence
space и повторно передаётся, поэтому TTL не защищает реальный HTTP/TLS stream
от повреждения. Без root/raw injection это не эквивалент Linux fake. UDP fake
остаётся отдельной датаграммой. Raw seq/ts tricks и IP fragmentation не реализованы.

`QuicHostCorrelation` использует UDP/53 DNS A/AAAA и prior TCP host hints;
DoH/DoT/старый cache/multi-IP могут скрывать host. Поэтому дефолтный QUIC
Initial rule не требует hostlist match. Общие lists/payloads берутся из
`runtime/assets/qnzapret`, подключённого Gradle как `runtime/assets`.

## Telegram

```text
Telegram (подтверждённый локальный proxy profile)
  -> Kotlin MTProxy 127.0.0.1:1443
  -> protected WSS /apiws -> Telegram Web/DC route
```

Service поднимает Kotlin proxy независимо от Python/Linux sidecar.
MTProxy сохраняет signed DC/protocol marker, расшифровывает client stream и
отправляет packet-aligned upstream frames. Есть route-provider, DNS fallback,
scoring, cooldown, one-shot pool и media/upload watchdog.
[Подробности маршрутизации](android_telegram_cloudflare_routes.md).

Setup URI требует подтверждения Telegram. UI-start может открыть его через
native setup health policy; notification action «Подключить Telegram» —
user-initiated. QS-start не должен автоматически запускать Telegram из фона.
Fingerprint/успешные handshake+bridge, а не факт открытия окна, определяют
`setupRequired`. Android ProxyReady теперь требует active bridge, listener отражается отдельно
в SidecarState. UI и notification сохраняют setup до подтверждения; повторная
кнопка в Home открывает профиль через native Activity. [Контракт](runtime_bridge_contract.md).

Optional remote SOCKS5 relay через endpointPolicies остаётся отдельным
диагностическим маршрутом, требующим внешнего узла. Он не является default
Telegram setup. HTTPS CONNECT/production secret storage не реализованы.

## Lifecycle и диагностика

- Prepare вызывает `VpnService.prepare` и возвращает системный consent result.
- Start отправляет foreground intent, service компилирует профиль, поднимает
  local proxy/TUN/Telegram и обновляет in-memory store.
- `trafficForwarderReady` требует TUN-to-SOCKS, `tunnelActive` — TUN fd.
  `running` без этих flags не означает готовый перехват.
- Stop/action/revoke/destroy закрывают TUN, JNI transport, proxy и Telegram.
- Notification содержит Stop/Restart/Telegram setup; Android 13+ требует
  `POST_NOTIFICATIONS`. Скорость не отображается без надёжных counters.
- QS tile управляет тем же service; для старта берёт native default config.
- EventChannel публикует snapshots/lifecycle logs. Detailed network logs
  остаются в logcat.
- Snapshot/QS render не вызывают VpnService.prepare; last-known consent
  читается из preferences. Prepare/Start — только явные user actions.
- Startup выполняется в lifecycle executor с generation cancellation;
  native JNI health и ошибки не подменяются idle при onDestroy.

Контрольные теги: `QNZapretBridge`, `QNZapretService`, `QNZapretTun`,
`QNZapretProxy`, `QNZapretNetTest`, `QNZapretTgCompat`.
Self-test выполняется асинхронно после готовности (`runtime_ready`),
не блокирует Start и UI; он проверяет plain,
protected, selected-network-bound TCP и UDP DNS именно из UID приложения.
Смотрите [сетевую диагностику](android_uid_network_blocker.md).

## Проверено и что дальше

Ранее в handoff были успешные device проверки consent, Start/TUN/local proxy,
Stop и отдельный Pixel smoke. Их результаты относятся к прежнему окружению;
серийный номер и UID устройства не нужны для воспроизведения на новом телефоне.
12 октября собраны release APK/AAB и отдельный Preview APK для безопасной
параллельной установки при несовместимости старой подписи. `-PqnzPreview=true`
меняет только applicationId на `dev.qnzapret.preview` и подпись названия в меню/QS;
Kotlin/JNI namespace остаётся `dev.qnzapret`, runtime и данные изолированы Android.

Подписанный QNZapret route config, relay secret storage,
backpressure/лимиты и counters пока остаются открытыми направлениями.
Команды сборки/JVM tests/device smoke — в [QA](integration_workflow.md).

## Память hostlists

Canonical general list содержит около 1,29 млн записей. Android build генерирует
точный UTF-8 offset index через `packaging/android/build_hostlist_indexes.py`
(Python 3 нужен при разработческой сборке). Runtime копирует проверенный индекс
в private cache и читает через mmap/binary search, не строит миллионный HashSet
в Java heap. Exact/suffix semantics сохранены без probabilistic false positives;
для нестандартного asset есть bounded streaming fallback с LRU.

## UDP association

Каждый SOCKS5 UDP ASSOCIATE владеет отдельными loopback/upstream sockets до
закрытия управляющего TCP-канала. Общий relay мог путать адресатов параллельных
ответов DNS/QUIC к одному удалённому endpoint. Одновременно разрешено не более
64 association; Stop закрывает все их sockets, а ошибки освобождают permit.

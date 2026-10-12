# Android non-root runtime

Сверено с кодом ветки `quriee` 12 октября 2026 года. Это описание реализации,
а не подтверждение полной готовности на текущем телефоне. Последняя локальная
проверка — успешная release AAB-сборка; новый device smoke ещё нужен.
Следующий приоритет: [обновление Android и подготовка тестирования](android_next_stage.md).

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
TCP write split не гарантирует boundaries пакетов. Fake — best-effort write
с малым hop limit, пропускается при недоступной опции. Raw seq/ts tricks и
IP fragmentation этим маршрутом не реализованы.

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
`setupRequired`. Android `ProxyReady` сейчас всё ещё означает listener;
общий UI пока может преждевременно показать «Подключён». Это известный долг
[контракта](runtime_bridge_contract.md), который нужно закрыть следующим этапом.

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
- `AndroidProxyRuntime.events` пока пуст: detailed native logs идут в logcat.

Контрольные теги: `QNZapretBridge`, `QNZapretService`, `QNZapretTun`,
`QNZapretProxy`, `QNZapretNetTest`, `QNZapretTgCompat`.
Self-test выполняется до runtime/TUN и после его старта; он проверяет plain,
protected, selected-network-bound TCP и UDP DNS именно из UID приложения.
Смотрите [сетевую диагностику](android_uid_network_blocker.md).

## Проверено и что дальше

Ранее в handoff были успешные device проверки consent, Start/TUN/local proxy,
Stop и отдельный Pixel smoke. Их результаты относятся к прежнему окружению;
серийный номер и UID устройства не нужны для воспроизведения на новом телефоне.
12 октября Android release AAB собран, но Android runtime на телефоне не гонялся.

Следующий этап должен обновить компоненты/маршруты, проверить все основные
сценарии, собрать installable APK и передать пользователю smoke checklist.
Подписанный QNZapret route config, native log stream, relay secret storage,
backpressure/лимиты и counters пока остаются открытыми направлениями.
Команды сборки/JVM tests/device smoke — в [QA](integration_workflow.md).

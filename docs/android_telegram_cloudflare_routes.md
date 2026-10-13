# Android Telegram: маршруты, cache и media

Сверено с Kotlin route-provider/transport ветки `quriee` 12 октября 2026.
Это **Android** WSS-реализация; Linux tg-ws-proxy v1.11.2 имеет собственные
upstream defaults, HTTP/2 и pool, автоматически сюда не переносимые.

## Маршрут

`Telegram -> локальный MTProxy 127.0.0.1:1443 -> protected WSS /apiws -> DC`.
Профиль подтверждает пользователь Telegram. Клиентский signed DC/protocol
marker переносится в upstream obfuscation init. HTTP 101 сам по себе не
доказывает корректный MTProto bridge или передачу пользовательских данных.

Если direct TCP/WSS режется до payload, нужен альтернативный route. Public
CF defaults могут работать без собственного сервера/домена пользователя, но
зависят от внешнего источника, DNS, edge policy и сети.

## Источники данных

`TelegramRouteConfigProvider.kt` сначала собирает local config и cached public
список, при отсутствии cache использует поставляемый public seed из
Flowseal v1.11.2, затем обновляет public данные/пробует routes в background. Signed
QNZapret config пока только placeholder, не действующий источник.

Public URL, используемый **кодом**:
`https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt`.
Это внешний изменяемый endpoint, не закреплённая версия компонента. Перед
следующим Android релизом проверить его актуальность/формат и fallback.

Decoder для encoded `.com` entries считает буквы prefix, сдвигает латиницу
назад на это число и меняет suffix на `.co.uk`; результат нормализуется и
валидируется. Реализация и тесты — `TelegramRouteConfigProvider.kt` /
`TelegramRouteDomainCodecTest.kt`.

Cache: app-private `files/qnzapret/telegram_cf_domains_cache.json`, TTL 12 часов.
При fetch failure используется stale cache; без него используются bundled public seed, local domains и direct routes. Local domains не перетираются public fetch.

Для согласованного smoke локальный `qnzapret/telegram_compat.json` ищется в
app-private files/cache и external files dir:

```json
{
  "cfDomains": ["cf-route.example.com"],
  "cfPriority": true,
  "tlsVerify": true
}
```

Пример загрузки на явно выбранное устройство:

```bash
adb -s <DEVICE_ID> shell mkdir -p /sdcard/Android/data/dev.qnzapret/files/qnzapret
adb -s <DEVICE_ID> push telegram_compat.json /sdcard/Android/data/dev.qnzapret/files/qnzapret/telegram_compat.json
```

External path может ограничиваться версией Android/политикой устройства.
Приватные домены/credentials в Git не помещать. TLS verification включена;
`false` — только явный локальный diagnostic override с security warning.

## Выбор маршрута

Direct candidates: `kwsN.web.telegram.org`, legacy host и `kwsN-1.web.telegram.org`;
для media direct `kwsN-1` идёт первым. Для CF всегда сначала `kwsN.<base>`, затем
`kwsN-1.<base>`; score не поднимает второй variant выше первого.
CDN203 использует отдельный `kws203.<base>`, не alias обычного DC2:
анонимный req_pq показал, что такой alias отвечает master-DC ключами. Перед
использованием CDN gateway сравниваются nonce-matched публичные key sets CDN203
и DC2 этого gateway; они должны различаться и не пересекаться. Это проверка
маршрута, а криптографическую аутентификацию по официальным CDN keys продолжает
выполнять Telegram. Результат кэшируется 15 минут; параллельные клиенты делят
одну проверку. Probe использует отдельное соединение с deadline, для реальных
данных открывается свежее. CDN считается media flow, исходный signed DC marker
не меняется. Direct DC2/DC4 используют upstream redirect `149.154.167.220`,
с сохранением route hostname в SNI/Host и проверкой TLS.

При `cfPriority=true`:

- text: local CF -> public CF -> direct;
- media: local CF -> direct -> public CF.

При `cfPriority=false` direct идёт перед CF. Local/public ordering, active
host и EWMA score действуют внутри соответствующих buckets, а не заменяют
политику источников. Не все CF deployments публикуют `kwsN-1` records.

При подключении к resolved IP сохраняются route hostname в TLS SNI и HTTP Host.
Resolver: system/network DNS -> DoH -> UDP fallback, short timeouts, cache
5 минут, IPv4 preference на IPv4-only сети. Когда strategy proxy уже поднят,
direct Web route может идти через него, используя preconnect/relay/desync,
а не только raw protected socket.

## Health, cooldown, pool

Background probe проверяет DC 2/4, успех — HTTP 101. Последний успешный CF host
сохраняется active; score учитывает DNS/TCP/TLS/WS timings, first payload,
throughput и failures. HTTP 429 даёт cooldown 45 секунд. Media/direct route
failures и stalled media имеют отдельные cooldown, не обязательно мешающие text.

One-shot WSS pool: ключ `dcId + mediaDc + route host`, два соединения на observed
key, не более четырёх всего, idle age 60 секунд. Pool доводит socket до HTTP 101,
но отправляет relay init только для реальной MTProxy session; socket используется
один раз. После handshake read timeout снимается; stall контролирует watchdog.

Client-to-WSS не пересылает произвольные read chunks: transport собирает целые
abridged/intermediate/padded-intermediate packets и сохраняет frame boundaries.
Это существенно для voice/circle/file upload.

В обратном направлении MTProto — byte stream: WebSocket payload передаётся
порциями до 32 KiB сразу по мере прихода. Полный кадр не буферизуется; логический
лимит 16 MiB не означает выделение такого массива. Это устраняет ожидание конца
большого кадра и прежний отказ при размере чуть больше 1 MiB. Mask offset,
continuation и Ping/Pong сохраняются между чтениями.

Media watchdog закрывает только ожидающее ответа соединение без downstream
progress 30 секунд. Idle после маленького стикера и медленная продолжающаяся
загрузка не являются зависанием. Успешный маленький ответ с последующим idle
не штрафует домен по средней скорости за весь срок соединения. Для upload-heavy
text остаётся `low_upload_ack` и cooldown конкретного host; повторный маршрут
не меняет logical DC или исходный media marker.

## Проверки и ограничения

Проверять text/media/download/upload отдельно, а не только startup или HTTP 101.
Нужны cold/warm cache, отсутствующий local config, offline fetch, DNS/TLS errors,
403/429, pool miss/hit, Stop/restart и отсутствие secret в logs.
Полные сценарии — [следующий Android этап](android_next_stage.md).

Теги `QNZapretTgCompat`: route provider load/fetch, DNS/route attempt,
probe, handshake, pool, score, bytesUp/Down, watchdog/close reason.
Длинные private logs не публиковать без очистки.

Собственная edge/domain deployment — опциональный операторский путь. Старые
инструкции со статическими DC IP/SSL режимом убраны: актуальную внешнюю схему
проверять у официального upstream перед развёртыванием, не считать её частью
реализованной Kotlin-конфигурации. Подписанный канал данных QNZapret с TTL,
версиями и отзывом доменов остаётся будущей работой.

## Надёжность обновления

Seed находится в `runtime/assets/qnzapret/telegram/` с commit/SHA256 provenance.
Fetch ограничен 64 KiB и таймаутами, список нормализуется/проверяется; cache
пишется AtomicFile. Stop инвалидирует background generation и прерывает
дальнейшие probes; stale callback не публикует новое состояние старого запуска.
Это обновление данных routes, не скачивание исполняемого Kotlin/Native кода.

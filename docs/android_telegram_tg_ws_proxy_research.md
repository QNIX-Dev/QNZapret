# Android Telegram tg-ws-proxy Research

## Статус

Документ фиксирует исследование `.sources/tg-ws-proxy-android` для задачи transparent Telegram transport внутри Android VPN runtime QNZapret.

Вывод на текущем этапе: `tg-ws-proxy` нельзя считать готовым transparent TCP transport для потока `Telegram app -> VPN -> SOCKS CONNECT original DC`.
Его Go-core реализует локальный MTProto proxy endpoint, который ожидает Telegram proxy flow через `tg://proxy` и proxy secret.

Это не отменяет ценность reference-кода:

- WebSocket/TLS transport к `kws*.web.telegram.org`;
- Cloudflare fallback;
- pooling, keepalive и статистика;
- идеи по мобильной устойчивости.

Но перенос в QNZapret не выполняется: Go-core/JNA и GPL Android-код fork-а не используются в рабочем runtime. Telegram compatibility mode реализуется clean-room на Kotlin внутри существующего Android backend.

## Что делает tg-ws-proxy

Reference-схема из README:

```text
Telegram Android
  -> локальный MTProto proxy 127.0.0.1:1443
  -> TG WS Proxy
  -> WSS через Cloudflare или напрямую
  -> Telegram DC
```

Android UI запускает foreground `ProxyService`, а кнопка "Применить в Telegram" открывает `https://t.me/proxy?server=127.0.0.1&port=1443&secret=dd...`.
После подтверждения Telegram сам подключается не к DC endpoint, а к локальному MTProto proxy.

## Go-core: transport vs MTProto proxy handshake

Transport-часть:

- `wsConnectOnce`, `wsConnect`, `connectDirectWS` открывают TLS/WebSocket `/apiws`;
- `wsDomains` строит `kws<dc>.web.telegram.org` и `kws<dc>-1.web.telegram.org`;
- `cfproxyFallback` строит Cloudflare route `kws<dc>.<cf-domain>`;
- `bridgeWS` и `bridgeTCP` прокидывают поток и держат keepalive;
- `WsPool` прогревает и переиспользует WS connections.

Handshake-часть, без которой core не работает как transparent adapter:

- `handleClient` принимает локальный TCP-клиент и читает первые 64 байта MTProxy init;
- ключи `cltDecryptor`/`cltEncryptor` строятся из init bytes и `proxySecret`;
- после расшифровки core проверяет `protoTag` (`abridged`, `intermediate`, `padded intermediate`);
- `dcId` берется из расшифрованных байтов init;
- core генерирует новый `relayInit` для upstream Telegram WS/DC и перекодирует поток `client secret/session -> Telegram WS session`.

Следствие: знание original target `149.154.x.x:443` из VPN/SOCKS CONNECT помогает с DC selection, но не заменяет MTProxy init и secret-derived stream ciphers.

## Почему прямой Telegram stream не совместим напрямую

В QNZapret Telegram сейчас идет так:

```text
Telegram app -> Android VPN -> hev-socks5-tunnel -> StrategySocks5Server -> SOCKS CONNECT original DC ip:port
```

Telegram не находится в proxy mode, поэтому он не обязан генерировать MTProxy handshake с secret `dd...` или `ee...`, который ожидает `tg-ws-proxy`.
Обычный direct stream к DC может быть abridged/intermediate/obfuscated MTProto transport, но это не тот же локальный MTProxy protocol contract.

Если `StrategySocks5Server` просто ответит SOCKS success до upstream connect и передаст первый payload в текущий Go-core, core не сможет корректно:

- проверить secret;
- расшифровать MTProxy init;
- извлечь `protoTag`/`dcId` тем же способом;
- построить парные cipher streams для перекодирования клиента в Telegram WS route.

Поэтому минимальная transparent-интеграция требует не "подключить Go-core", а написать отдельный Telegram-aware bridge, который умеет принимать direct MTProto transport от клиента и формировать совместимый upstream WS transport. Это отдельная криптографическая/протокольная реализация и не должна делаться как быстрый патч.

## Диагностический probe в QNZapret

В `StrategySocks5Server` добавлен выключенный по умолчанию probe для проверки первого Telegram payload без direct upstream connect.

Probe активируется только при наличии одного из локальных flag-файлов:

```text
/sdcard/Android/data/dev.qnzapret/files/qnzapret/telegram_transparent_probe
files/qnzapret/telegram_transparent_probe
cache/qnzapret/telegram_transparent_probe
```

Поведение:

- применяется только к `endpointClass=telegram`, `telegram_host`, `mtproto_port`;
- отправляет SOCKS success сразу после CONNECT request;
- читает первые байты от Telegram app с коротким timeout;
- логирует только длину, `protoHint` и короткий hex preview первых 16 байт;
- закрывает connection и не запускает transport.

Контрольные логи:

```text
QNZapretProxy: telegram transparent probe start originalTarget=... dcClass=... endpointClass=... mode=early_socks_success timeoutMs=...
QNZapretProxy: telegram transparent first payload originalTarget=... bytes=... protoHint=... hexPreview=... elapsedMs=...
QNZapretProxy: telegram transparent probe complete originalTarget=... result=payload_captured_transport_not_started
```

Probe не является production transport и не должен оставаться включенным при обычной проверке YouTube/Telegram.

## Лицензия

Локальный reference `.sources/tg-ws-proxy-android` содержит GPLv3 `LICENSE` для Android fork и отдельный MIT block для Flowseal upstream.

Практическое правило для QNZapret:

- не переносить Kotlin/Android-код fork-а в production без отдельного решения о GPLv3;
- transport-идеи использовать только как reference;
- Go-core/JNA не добавлять в QNZapret runtime;
- предпочтительный путь для product code: собственная Kotlin-реализация по публичному протоколу и official Telegram transport docs.

## Kotlin compatibility mode в QNZapret

После решения не использовать Go-core добавлен clean-room Kotlin path:

```text
QNZapret Start
  -> QnzapretVpnService
  -> TelegramCompatibilityProxyManager
  -> локальный MTProxy endpoint 127.0.0.1:1443
  -> tg://proxy / https://t.me/proxy confirmation screen
  -> Telegram client after user confirmation
  -> protected WSS /apiws к Telegram Web DC hostname
```

Компоненты:

- `TelegramCompatibilityProxyManager.kt` - lifecycle, secret/port persistence, setup link и notification action.
- `TelegramMtProxyCrypto.kt` - `dd` MTProxy secret, 64-byte obfuscation init, AES-CTR ciphers и DC/protocol extraction.
- `TelegramWebSocketTransport.kt` - minimal WebSocket-over-TLS client с `Sec-WebSocket-Protocol: binary`, protected raw socket, optional selected-network bind, WSS stream framing, Cloudflare route ordering и HTTP status logging.
- `TelegramCloudflareResolver.kt` - clean-room resolver для route hosts: system/network DNS, DoH/UDP fallback, IPv4 preference на IPv4-only underlying-сети и 5-минутный cache.
- `TelegramRouteConfigProvider.kt` - route-provider для local domains, cached/fresh public Flowseal upstream domains и будущего signed QNZapret route config.

Важно для совместимости: upstream WSS obfuscation init должен переносить protocol marker и signed DC id из клиентского MTProxy handshake. Если оставить DC bytes случайными, WebSocket `/apiws` может успешно вернуть HTTP 101 и первый ответ, но Telegram остается в состоянии подключения proxy.

Ограничения текущего clean-room этапа:

- реализованы route candidates `kwsN.web.telegram.org`, legacy `pluto|venus|aurora|vesta|flora.web.telegram.org` и `kwsN-1.web.telegram.org`;
- поддержан локальный clean-room route override `qnzapret/telegram_compat.json` с полями `cfDomains: string[]`, `cfPriority: bool` и `tlsVerify: bool`; runtime строит candidates `kwsN.<cf-domain>` и `kwsN-1.<cf-domain>` без зашивания чужого списка в репозиторий;
- public defaults берутся из MIT upstream Flowseal `cfproxy-domains.txt`: provider fetch-ит raw URL, декодирует encoded `.com` entries в base domains, валидирует и кэширует результат в app-private storage на 12 часов;
- если fetch не удался, используется cache; если cache пустой, остаются только local domains;
- route source priority: local config -> cached public upstream -> fresh public upstream -> future signed QNZapret route config placeholder;
- background probe проверяет DC 2/4 и сохраняет active domain при HTTP 101;
- для media/negative DC route ordering предпочитает `kwsN-1`, иначе сначала пробует `kwsN`; последний успешный CF domain сохраняется как active и HTTP 429 ставит домен в cooldown на 45 секунд;
- transport подключается к resolved IP, но сохраняет TLS SNI и HTTP `Host` равными route host; `tlsVerify=false` можно включить только явно в локальном smoke config, и это логируется как security warning;
- bad-handshake попытки от старых/неверных Telegram proxy entries логируются rate-limited samples, без секретов;
- встроенный Cloudflare fallback без конфигурации доменов пока не включен, потому нельзя переносить доменные списки/алгоритм из GPL reference fork без отдельного решения;
- Android не может silently включить proxy в Telegram, поэтому первый запуск требует ручного подтверждения в Telegram confirmation screen;
- последующие старты QNZapret просто поднимают локальный proxy на сохраненном endpoint, если Telegram уже сохранил proxy.

Smoke config кладется в external files dir приложения:

```bash
adb shell mkdir -p /sdcard/Android/data/dev.qnzapret/files/qnzapret
adb push telegram_compat.json /sdcard/Android/data/dev.qnzapret/files/qnzapret/telegram_compat.json
```

Формат:

```json
{
  "cfDomains": ["cf-route.example.com"],
  "cfPriority": true,
  "tlsVerify": true
}
```

Реальные CF base domains не коммитятся, если они не предназначены для публичного репозитория.

## Следующий короткий production path

Без прямого DC connect остаются два реалистичных пути:

1. Собственный transparent Telegram bridge.
   Нужно исследовать direct first payload, реализовать поддержку нужных MTProto transports, сформировать upstream WS transport к Telegram web/DC и покрыть это device smoke.

2. Compatibility mode с встроенным локальным Kotlin MTProxy.
   QNZapret запускает локальный MTProto proxy и открывает Telegram confirmation screen.
   Это не полностью transparent VPN-цель, но это самый короткий пользовательский fallback, если direct TCP к DC режется до payload и transparent bridge слишком дорогой.

Generic external SOCKS/HTTPS relay остается диагностическим/enterprise fallback, но не основным продуктовым решением QNZapret.

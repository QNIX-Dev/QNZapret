# Android Telegram Remote Relay Contract

## Статус

Этот документ фиксирует production-дизайн remote relay/proxy маршрута для Telegram.
Текущий runtime-код уже реализует минимальный SOCKS5 remote relay path для TCP Telegram endpoints через `StrategyProfile.endpointPolicies`.
После исследования `tg-ws-proxy` этот route считается не основным продуктовым решением "одной кнопкой", а диагностическим/enterprise fallback: он требует внешнего доверенного узла и поэтому не заменяет transparent Telegram transport внутри QNZapret.
Основной research-документ по transparent tg-ws-proxy направлению: `docs/android_telegram_tg_ws_proxy_research.md`.

Реализовано:

- Dart/Kotlin codec для `endpointPolicies`;
- выбор Telegram relay policy в `StrategySocks5Server` до direct/pre-connect attempts;
- protected socket к удаленному relay через `VpnService.protect`;
- `selectedNetwork.bindSocket(socket)` до connect, если Android дал underlying network;
- SOCKS5 CONNECT original target `host/ip:port`;
- no-auth и username/password auth;
- IPv4, IPv6 и domain target в SOCKS5 request;
- стабильные log error codes.

Остается TODO:

- HTTPS CONNECT runtime path;
- production secret storage/secret refs вместо plain-text smoke credentials;
- device smoke с реальным удаленным relay.

Причина появления документа: Telegram на РФ-сети может ломаться до первого payload, на этапе прямого TCP connect к DC endpoint. В таком состоянии payload-level fake/split/desync не помогает, потому что `StrategyRuntimeEngine` получает первый payload только после успешного upstream connect.

## Текущая диагностическая логика

`StrategySocks5Server` классифицирует Telegram endpoints до TCP connect по:

- IPv4 ranges: `149.154.160.0/20`, `91.108.0.0/16`, `185.76.151.0/24`;
- IPv6 range: `2001:67c:4e8::/48`;
- Telegram host hints: `telegram`, `t.me`;
- MTProto-кандидатам портов `443`, `80`, `5222`, `5223`.

Для `endpointClass=telegram` вместо одного 10-секундного connect используется bounded pre-connect path:

- исходный endpoint;
- ограниченные альтернативы внутри локально описанной подсети `149.154.167.0/24`;
- альтернативные Telegram-порты `443`, `80`, `5222`, `5223`;
- короткий timeout на попытку.

Контрольные логи:

```text
QNZapretProxy: telegram preconnect begin originalTarget=... targetIp=... targetPort=... dcClass=... endpointClass=... attempts=... timeoutMs=...
QNZapretProxy: telegram preconnect attempt originalTarget=... targetIp=... targetPort=... dcClass=... endpointClass=... chosenAttempt=... candidate=... source=... timeoutMs=...
QNZapretProxy: telegram preconnect ok originalTarget=... candidate=... chosenAttempt=... connectMs=... totalMs=...
QNZapretProxy: telegram preconnect failed originalTarget=... candidate=... chosenAttempt=... elapsedMs=... error=...
QNZapretProxy: socks tcp connect failed target=... endpointClass=telegram dcClass=... attempts=... directBlockedBeforePayload=true error=...
```

Если все direct attempts падают timeout/no-route до первого payload, вывод считается архитектурным: для этой сети нужен remote relay/proxy route, а не payload-level стратегия.

## Целевая схема relay

```text
Telegram app
  -> Android VPN
  -> TUN fd
  -> hev-socks5-tunnel
  -> StrategySocks5Server
  -> Telegram endpoint policy
  -> protected TCP socket to remote relay
  -> remote SOCKS5 или HTTPS CONNECT relay
  -> original Telegram DC ip:port
```

Пользователь не настраивает proxy в приложении Telegram.
Перехват остается прозрачным через Android VPN.
Внутренний `127.0.0.1:1080` остается только SOCKS endpoint для `hev-socks5-tunnel` и не является продуктовым Telegram proxy.

## Поля StrategyProfile

`endpointPolicies` является опциональным полем `StrategyProfile`.
Если поле отсутствует, старые профили продолжают работать как `defaultLightweight`.

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

Минимальные поддерживаемые relay-протоколы:

- `socks5`: реализован, relay получает original target как SOCKS5 CONNECT `host/ip:port`;
- `httpsConnect`: зарезервирован в контракте, runtime path пока TODO и будет завершаться `relay_protocol_error`.

## Dev/smoke override

Чтобы проверить relay без хардкода секретов в репозитории, Android service может подхватить локальный JSON-файл:

```text
/sdcard/Android/data/dev.qnzapret/files/qnzapret/telegram_relay.json
```

Также проверяются app-private paths:

```text
files/qnzapret/telegram_relay.json
cache/qnzapret/telegram_relay.json
```

Формат файла совпадает с объектом выше: либо `{ "endpointPolicies": [...] }`, либо один policy object с полем `route`.
Если файл найден и policies распарсились, они заменяют `endpointPolicies` профиля на время запуска service.
Логи показывают только path, число policies и `protocol@host:port`; credentials не логируются.

## Failure modes

- `relay_unconfigured`: Telegram endpoint matched, но relay route отсутствует.
- `relay_auth_failed`: relay отклонил credentials.
- `relay_connect_failed`: protected connect к relay не завершился.
- `relay_target_failed`: relay не смог открыть original Telegram target.
- `relay_protocol_error`: relay вернул некорректный SOCKS5/HTTP CONNECT ответ.
- `relay_tls_failed`: зарезервировано для будущего HTTPS CONNECT с TLS.

Для Telegram в РФ безопасный production default должен быть `failClosed`: если прямой TCP доказанно блокируется до payload, silent direct fallback будет выглядеть для пользователя как зависание Telegram и маскировать проблему.

## Логи

Нельзя логировать credentials, tokens и полные secret refs.

Обязательные runtime-логи:

```text
QNZapretProxy: telegram relay connect start originalTarget=... relay=host:port protocol=... endpointClass=... dcClass=...
QNZapretProxy: telegram relay connect ok originalTarget=... relay=host:port connectMs=... relayHandshakeMs=...
QNZapretProxy: telegram relay connect failed originalTarget=... relay=host:port errorCode=... error=...
QNZapretProxy: telegram relay first byte originalTarget=... sinceRelayConnectMs=...
```

## Security constraints

- Relay должен быть удаленным узлом вне блокирующей сети; localhost relay в UI не решает проблему Telegram.
- Plain-text `auth.username/password` допустимы только для локального dev/smoke override. Для production-настройки нужен storage/secret-ref слой.
- Для HTTPS CONNECT с TLS нужен `serverName` и системная проверка сертификата; pinning можно добавить через `caPins`.
- Логи должны редактировать auth и не раскрывать relay credentials.
- Relay является доверенной стороной для Telegram metadata: как минимум видит original target и timing.

## Definition of Done для реализации relay

- `StrategyProfile` и Dart/Kotlin codecs поддерживают endpoint policy без ломки старых профилей.
- `StrategySocks5Server` выбирает relay route до direct connect для `endpointClass=telegram`.
- SOCKS5 relay передает stream без ручной настройки proxy в Telegram.
- Ошибки relay мапятся в стабильные log codes.
- Direct pre-connect diagnostics остаются доступными для сравнения.
- Device smoke должен подтвердить, что Telegram работает через VPN-перехват без настройки proxy в приложении Telegram.

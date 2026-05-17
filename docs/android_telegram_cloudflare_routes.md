# Android Telegram Cloudflare Routes

## Статус

Telegram compatibility mode в QNZapret работает как локальный Kotlin MTProxy endpoint:

```text
Telegram -> 127.0.0.1:1443 -> QNZapret Kotlin MTProxy -> WSS /apiws -> Telegram DC
```

На РФ-сетях прямой TCP к Telegram DC и прямой WSS к `kws*.web.telegram.org` может падать до первого payload. Для этого path нужен Cloudflare/edge route: пользователю не нужен свой SOCKS/VPN-сервер, нужен только домен, обслуживаемый Cloudflare.

MTProxy bridge должен переносить requested DC из клиентского handshake в upstream WSS obfuscation init. Если WSS handshake получает HTTP 101, но upstream init содержит случайный DC, Telegram может оставаться в состоянии "подключение proxy" несмотря на живой WebSocket.

## Public Defaults

QNZapret не хранит приватные домены в репозитории. Для smoke runtime умеет получать public defaults из MIT upstream Flowseal:

```text
https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt
```

Список в upstream хранится в encoded-виде. Kotlin provider декодирует его clean-room тем же публично описанным способом из MIT upstream:

- если entry заканчивается на `.com`, берется prefix без `.com`;
- считается количество букв в prefix;
- каждая латинская буква сдвигается назад на это количество позиций по алфавиту;
- suffix заменяется на `.co.uk`;
- результат валидируется как домен.

Источник нестабилен: public domains могут упереться в лимиты Cloudflare, получить 403/429, смениться или перестать работать. Поэтому они подходят для smoke/fallback, но не должны быть единственным production-механизмом.

## Runtime Provider

`TelegramRouteConfigProvider.kt` собирает route domains в таком порядке:

1. Локальный `qnzapret/telegram_compat.json` из app-private/external files.
2. Cached public upstream domains.
3. Fresh public upstream fetch, если cache пустой или старше TTL.
4. Будущий signed QNZapret route config endpoint.

Локальные домены всегда выше public defaults и не перетираются fetch-ем.

Cache:

- путь: `files/qnzapret/telegram_cf_domains_cache.json`;
- TTL: 12 часов;
- при fetch failure используется существующий cache, включая stale cache;
- если cache пустой и fetch не удался, остаются только local domains.

Локальный smoke config:

```bash
adb shell mkdir -p /sdcard/Android/data/dev.qnzapret/files/qnzapret
adb push telegram_compat.json /sdcard/Android/data/dev.qnzapret/files/qnzapret/telegram_compat.json
```

Формат:

```json
{
  "cfDomains": ["example-cloudflare-domain.com"],
  "cfPriority": true,
  "tlsVerify": true
}
```

`tlsVerify=false` допустим только для локального smoke и логируется как security warning.

## Domain Probing

Перед активным использованием provider запускает background probe:

- строит `kws<dc>.<domain>` и `kws<dc>-1.<domain>`;
- проверяет DC 2 и DC 4;
- успехом считается WebSocket HTTP `101`;
- HTTP `429` ставит домен в cooldown на 45 секунд;
- `403`, timeout, TLS failure и WS failure логируются стабильными `errorCode`;
- успешный домен сохраняется как active domain и будет пробоваться первым внутри своего source priority bucket.

Контрольные логи:

```text
QNZapretTgCompat: telegram route provider load source=local/cache/upstream count=...
QNZapretTgCompat: telegram route provider fetch start url=...
QNZapretTgCompat: telegram route provider fetch ok domains=...
QNZapretTgCompat: telegram route provider decode failed ...
QNZapretTgCompat: telegram cf probe start domain=... dc=...
QNZapretTgCompat: telegram cf probe ok domain=... host=... httpStatus=101
QNZapretTgCompat: telegram cf probe failed domain=... errorCode=...
QNZapretTgCompat: telegram cf active domain saved domain=...
```

## Route Scoring And WSS Pool

После первого успешного route runtime не открывает новый TLS/WSS path вслепую для каждой MTProxy-сессии.

`TelegramWebSocketTransport.kt` ведет internal score по `host + mediaDc`:

- DNS ms;
- TCP connect ms;
- TLS handshake ms;
- WebSocket handshake ms;
- time to first WSS payload;
- session throughput;
- failures и HTTP 429 cooldown.

Score используется только внутри Android runtime и не является Dart/API contract. Local domains остаются выше public defaults, active domain пробуется первым внутри своего source priority bucket, а остальные route упорядочиваются по EWMA score.

Для ускорения Telegram media добавлен one-shot WSS pool:

- ключ: `dcId + mediaDc + route host`;
- соединение в pool уже доведено до HTTP `101`, но MTProxy relay init еще не отправлен;
- при новой MTProxy-сессии runtime берет готовый WSS, отправляет relay init и использует соединение один раз;
- соединение не возвращается в pool после bridge;
- размер по умолчанию: 2 на observed key, максимум 4 total;
- idle age: 60 секунд.

Контрольные логи:

```text
QNZapretTgCompat: telegram ws pool warm start key=...
QNZapretTgCompat: telegram ws pool refill ok key=...
QNZapretTgCompat: telegram ws pool hit key=...
QNZapretTgCompat: telegram ws pool miss dc=... mediaDc=...
QNZapretTgCompat: telegram route score update host=... mediaDc=... throughputBps=...
QNZapretTgCompat: telegram compatibility throughput session=... dc=... mediaDc=... upBps=... downBps=...
```

## Own Cloudflare Domain

Для стабильного production path нужен свой домен в Cloudflare. Отдельный сервер не нужен.

Минимальная схема по Flowseal docs:

1. Добавить домен в Cloudflare full setup: купить домен через Cloudflare или сменить nameservers у регистратора.
2. В `SSL/TLS -> Overview` выставить режим `Flexible`.
3. В `DNS -> Records` добавить proxied `A` records:

```text
kws1   -> 149.154.175.50
kws2   -> 149.154.167.51
kws3   -> 149.154.175.100
kws4   -> 149.154.167.91
kws5   -> 149.154.171.5
kws203 -> 91.105.192.100
```

Все записи должны быть proxied через Cloudflare.

После этого base domain добавляется в `telegram_compat.json`:

```json
{
  "cfDomains": ["your-domain.example"],
  "cfPriority": true,
  "tlsVerify": true
}
```

Для production пользователь не должен вводить домен руками. Нужен обновляемый signed QNZapret route config endpoint:

- доставляет список base domains и metadata;
- подписан ключом QNZapret;
- имеет TTL/версию;
- умеет отзывать домены;
- не содержит пользовательских секретов;
- local override остается выше signed/public defaults для dev/smoke.

## Источники

- Flowseal `tg-ws-proxy` upstream: MIT License.
- Flowseal `docs/CfProxy.md`: Cloudflare setup и A-records.
- Flowseal `.github/cfproxy-domains.txt`: public encoded defaults.
- Cloudflare DNS full setup docs: добавление домена через смену nameservers.

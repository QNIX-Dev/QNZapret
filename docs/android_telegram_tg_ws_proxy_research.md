# Решение по Telegram transport и tg-ws-proxy

Состояние ветки `quriee`, 12 октября 2026 года. Это фиксация архитектурного
решения; актуальная реализация Android описана в [handoff](android_runtime_handoff.md).

## Вывод исследования

Исторический `.sources/tg-ws-proxy-android` — reference, не production-код.
Его локальный MTProto proxy ожидает client init, полученный из Telegram proxy
mode с согласованным secret. Поток `VPN -> SOCKS CONNECT original DC` не
гарантирует такой handshake. Просто передать direct Telegram stream в этот
proxy недостаточно для прозрачного transport.

Сохраняются две отдельные задачи:

- Реализованный compatibility mode: Kotlin MTProxy `127.0.0.1:1443`, native
  Telegram confirmation, WSS `/apiws`, routes/cache/scoring/pool.
- Будущий transparent Telegram-aware transport: отдельное исследование direct
  MTProto, cipher/init/DC semantics и device tests. Готовым он не считается.

Optional remote SOCKS5 relay перенаправляет original DC stream прозрачно,
но требует внешнего доверенного узла; это диагностический/enterprise fallback,
а не замена локальному compatibility mode.

## Код и происхождение

Android использует собственные `TelegramCompatibilityProxyManager`,
`TelegramMtProxyCrypto`, `TelegramWebSocketTransport`, `TelegramCloudflareResolver`
и `TelegramRouteConfigProvider`. Go-core/JNA и GPL Android-код fork не подключены.
Public Flowseal route data декодируются Kotlin provider и кэшируются;
[Cloudflare/routes](android_telegram_cloudflare_routes.md) описывает фактический
порядок, а не прежний запрет на все public defaults.

Linux отдельно поставляет MIT Python headless core Flowseal без изменений
upstream и с отдельным адаптером QNZapret. Обновление этого core не обновляет
Kotlin-реализацию Android автоматически.

## Диагностический transparent probe

`StrategySocks5Server` содержит выключенный по умолчанию probe. Его включает
локальный файл `qnzapret/telegram_transparent_probe` в app-private files/cache
или external files dir приложения. Он отдаёт early SOCKS success, читает
первый payload, логирует длину/protoHint/короткий hex preview и закрывает stream.
Это **не** транспорт и не рабочий обход. Не оставлять flag включённым при smoke;
не публиковать payload preview или личный трафик как fixture.

## Дальнейшее применение

При [обновлении Android](android_next_stage.md) сравнить текущий upstream с
собственной Kotlin-реализацией, отдельно проверить лицензии и зависимости,
маршруты, protocol boundaries, media/upload и teardown. Не обещать transparent
режим или замену архитектуры без реализации и доказательств на устройстве.

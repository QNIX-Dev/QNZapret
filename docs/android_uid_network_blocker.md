# Android: диагностика сети из UID приложения

Сверено с Android self-test и proxy diagnostics в ветке `quriee` 12 октября 2026.
Имя файла сохранено для ссылок: прежний UID-blocker — исторический симптом,
а не установленная причина любой сегодняшней ошибки Telegram/YouTube.

После перехода на схему `VpnService -> TUN fd -> hev-socks5-tunnel -> local strategy SOCKS5 proxy -> protected sockets` VPN path поднимается и трафик доходит до нашего local strategy proxy.

Этот документ начинался как описание UID/app-network blocker. После controlled `QNZapretNetTest`, fresh install и release-smoke на Pixel 9 blocker больше не является главной рабочей гипотезой: protected/bound TCP из процесса приложения проходит, YouTube открывается через VPN path. Документ оставлен как regression checklist: если self-test снова покажет timeout до публичного IP из UID приложения, нужно сначала вернуться к этому уровню, а не переписывать TUN/strategy.

## Симптом

Исторический симптом на устройстве `7e7464c7`:

- приложение запускается;
- VPN service поднимается;
- `hev-socks5-tunnel` стартует;
- SOCKS5 TCP/UDP relay слушает локально;
- трафик попадает в `StrategySocks5Server`;
- дальше protected TCP connect из процесса приложения уходит в timeout;
- эффекта обхода нет, потому что local strategy proxy не может открыть исходящее соединение к реальной сети.

Типовой лог:

```text
TUN -> hev-socks5-tunnel -> локальный SOCKS5 -> protected upstream -> protocol/DPI
```

В ранних проверках TUN и SOCKS5 работали, но protected TCP connect из UID
приложения уходил в timeout. Успех `adb shell` тогда не доказывал доступность
сети из UID приложения. Более поздний Pixel smoke показывал работающий
protected/bound connect; текущий телефон нужно проверить заново.

## Порядок проверки

1. Получить `QNZapretNetTest` на стадии `runtime_ready` (асинхронно после старта); для A/B отдельно
   зафиксировать доступность без VPN и с ним.
2. Сравнить plain TCP, protected socket, selected-network socket factory,
   protect+bind+connect и protected/bound UDP DNS.
3. Проверить выбранную сеть, INTERNET/validation/unrestricted/non-VPN,
   DNS/Private DNS, IPv6 address/default route.
4. Если self-test падает, проверить netpolicy/data saver/background/battery,
   always-on/lockdown VPN и права manifest. Не переписывать стратегию до
   выяснения, может ли сам процесс открыть обычное исходящее соединение.
5. Если connect успешен, разбирать TCP first payload/upstream byte, UDP/QUIC,
   TLS, Telegram route/handshake и protocol-specific failures.

```bash
adb devices -l
adb -s <DEVICE_ID> logcat -d -s QNZapretNetTest QNZapretProxy QNZapretTun
adb -s <DEVICE_ID> shell dumpsys connectivity
adb -s <DEVICE_ID> shell dumpsys netpolicy
```

Вывод может содержать сетевые/персональные сведения: не публиковать его целиком.
Не сбрасывать данные приложения, сеть или аккаунты автоматически.

`run-as dev.qnzapret` работает только для debuggable сборки; release APK с debug
подписью от этого не становится debuggable. UID определять для текущей установки,
а не использовать старый числовой UID. Не предполагать, что роутер находится
по 192.168.1.1 или содержит открытый порт 80.

## Что логирует runtime

- `socks tcp connect start/ok/failed`, endpointClass, network, connectMs.
- `socks tcp first payload`, strategy decision и `upstream first byte`.
- `socks udp send/receive`, RTT, MTU, throughput и DNS host correlation.
- `telegram preconnect ... directBlockedBeforePayload=true`: отказ до L7,
  fake/split ещё не могли помочь.
- `telegram relay ...`: отдельный configured remote relay path.
- `QNZapretTgCompat`: local handshake, DNS/TCP/TLS/WS HTTP 101,
  route/cooldown/pool, bytesUp/Down, watchdog close reasons.

- `INTERNET` выдан;
- `ACCESS_NETWORK_STATE` выдан;
- UID приложения входит в сетевую группу `inet`;
- `cmd connectivity get-package-networking-enabled dev.qnzapret` возвращает allow;
- `cmd connectivity get-background-networking-enabled-for-uid 10416` возвращает allow.

## Почему это важно

Это объясняло состояние, когда VPN "поднят", но видимого эффекта нет.

Цепочка до стратегии работает:

```text
TUN fd -> hev-socks5-tunnel -> StrategySocks5Server -> StrategyRuntimeEngine
```

Если blocker возвращается, следующая часть цепочки ломается:

```text
StrategySocks5Server -> protected Socket/DatagramSocket -> external network
```

То есть правила могут применяться к трафику, но реальный upstream connect не завершается.

## Текущая интерпретация

Сейчас фокус сместился выше по стеку: Telegram и медленный YouTube нужно диагностировать по реальным endpoints, TCP/UDP timing, QUIC/TCP fallback, IPv6 route availability и strategy decisions в `QNZapretProxy`.

`QNZapretNetTest` остается обязательной контрольной точкой. Если pre/post self-test начинает падать на plain/protected/bound TCP, blocker снова считается актуальным.

## Следующие проверки

Рекомендуемый порядок:

1. Снять `QNZapretNetTest` из приложения: controlled self-test выполняется в `QnzapretVpnService` до старта runtime/TUN и после старта TUN. Он проверяет plain TCP, protected TCP, selected-network socket factory, protected+selected-network bind и protected/bound UDP DNS probe из процесса приложения.
2. Снять `adb shell dumpsys connectivity` во время активного VPN и отдельно после stop, чтобы видеть selected underlying network, DNS, Private DNS и наличие IPv6 default route.
3. Если self-test падает, проверить `adb shell dumpsys netpolicy`, background/data saver/battery saver, always-on VPN и lockdown VPN.
4. Если self-test успешен, анализировать `QNZapretProxy`: TCP connect timeout, UDP/QUIC send/receive, IPv6 no-route, DNS/Private DNS, first payload, strategy decision и upstream first byte.

## Важное замечание для следующей итерации

Не стоит снова переписывать TUN layer или стратегию, если UID приложения не умеет открывать обычные исходящие соединения вне VPN.

Минимальный критерий для возвращения к DPI-логике:

```text
из процесса приложения protected TCP connect до 192.168.1.1:80 или публичного IP:443 стабильно завершается успешно
```

Только после этого имеет смысл измерять, насколько fake/split/udpFake реально влияют на YouTube, Telegram или другие цели.

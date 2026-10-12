# Android: диагностика сети из UID приложения

Сверено с Android self-test и proxy diagnostics в ветке `quriee` 12 октября 2026.
Имя файла сохранено для ссылок: прежний UID-blocker — исторический симптом,
а не установленная причина любой сегодняшней ошибки Telegram/YouTube.

## Разделять уровни

```text
TUN -> hev-socks5-tunnel -> локальный SOCKS5 -> protected upstream -> protocol/DPI
```

В ранних проверках TUN и SOCKS5 работали, но protected TCP connect из UID
приложения уходил в timeout. Успех `adb shell` тогда не доказывал доступность
сети из UID приложения. Более поздний Pixel smoke показывал работающий
protected/bound connect; текущий телефон нужно проверить заново.

## Порядок проверки

1. Получить `QNZapretNetTest` на `pre_vpn_start` и `post_tun_start`.
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

Системное разрешение VPN, ready flags и внешний connect проверяются отдельно.
Self-test сам выполняет сетевые probes, поэтому не считать его чтением только
локального состояния. Методики и критерии — в [Android handoff](android_runtime_handoff.md)
и [следующем этапе](android_next_stage.md).

# Android UID Network Blocker

## Коротко

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
QNZapretProxy: socks tcp connect failed target=... error=SocketTimeoutException: failed to connect ...
```

## Что проверено

Проверки показали разницу между обычным `adb shell` и UID приложения.

Из UID приложения `10416` соединения не проходят:

```powershell
adb shell run-as dev.qnzapret /system/bin/nc -z -w 3 192.168.1.1 80
adb shell run-as dev.qnzapret /system/bin/nc -z -w 3 172.67.199.162 443
```

Обе проверки уходили в timeout.

Из обычного `adb shell` те же сетевые направления доступны:

```powershell
adb shell /system/bin/nc -z -w 3 192.168.1.1 80
adb shell /system/bin/nc -z -w 3 172.67.199.162 443
```

Права и базовые Android-флаги при этом выглядят корректно:

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

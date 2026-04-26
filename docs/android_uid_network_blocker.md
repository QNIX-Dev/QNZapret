# Android UID Network Blocker

## Коротко

После перехода на схему `VpnService -> TUN fd -> hev-socks5-tunnel -> local strategy SOCKS5 proxy -> protected sockets` VPN path поднимается и трафик доходит до нашего local strategy proxy.

Текущий оставшийся блокер не выглядит как ошибка TUN, `hev-socks5-tunnel` или правил стратегии. По проверкам на устройстве исходящие соединения не проходят именно из UID приложения, даже если приложение исключено из VPN и сокеты открываются как protected.

## Симптом

На устройстве `7e7464c7`:

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

Это объясняет, почему VPN "поднят", но видимого эффекта нет.

Цепочка до стратегии работает:

```text
TUN fd -> hev-socks5-tunnel -> StrategySocks5Server -> StrategyRuntimeEngine
```

Но следующая часть цепочки сейчас ломается:

```text
StrategySocks5Server -> protected Socket/DatagramSocket -> external network
```

То есть правила могут применяться к трафику, но реальный upstream connect не завершается.

## Текущая гипотеза

Наиболее вероятно, что это ограничение Android/OEM-прошивки, политики безопасности, профиля пользователя, app network policy, firewall/VPN-lockdown path или специфической связки `run-as`/UID на устройстве.

Пока это не доказано до конца, не нужно считать проблему закрытой. Но текущие факты сильнее указывают на UID/OEM network block, чем на ошибку стратегии или TUN-to-SOCKS архитектуры.

## Следующие проверки

Рекомендуемый порядок:

1. Проверить приложение на другом Android-устройстве или эмуляторе без OEM-ограничений.
2. Снять `adb shell dumpsys connectivity` во время активного VPN и отдельно после stop.
3. Проверить `adb shell dumpsys netpolicy` и ограничения для UID `10416`.
4. Проверить режимы battery saver, data saver, private DNS, always-on VPN и lockdown VPN.
5. Проверить, меняется ли поведение после полной переустановки приложения с очисткой данных.
6. Добавить в приложение временный controlled network self-test из самого процесса: connect к router IP, public IP и DNS endpoint до старта VPN и после старта VPN.
7. Проверить, может ли `Network.openConnection()` через выбранную underlying network выйти наружу из процесса приложения.

## Важное замечание для следующей итерации

Не стоит снова переписывать TUN layer или стратегию, пока не доказано, что UID приложения умеет открывать обычные исходящие соединения вне VPN.

Минимальный критерий для возвращения к DPI-логике:

```text
из процесса приложения protected TCP connect до 192.168.1.1:80 или публичного IP:443 стабильно завершается успешно
```

Только после этого имеет смысл измерять, насколько fake/split/udpFake реально влияют на YouTube или другие цели.

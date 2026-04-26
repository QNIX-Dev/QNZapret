# Android Runtime Handoff

## Назначение

Этот файл фиксирует состояние Android runtime после перехода на clean-room схему, взятую по архитектурной идее из ByeByeDPI, но без переноса GPL-кода Android-обвязки.

Документ нужен как короткий handoff для продолжения backend-работы: что уже собрано, какие проверки прошли, где искать ключевые файлы и какой блокер остался на устройстве.

## Текущая схема

Production Android path сейчас выглядит так:

```text
Flutter UI
  -> Dart ProxyRuntime contract
  -> Android MethodChannel bridge
  -> QnzapretVpnService
  -> Android TUN fd
  -> hev-socks5-tunnel
  -> local strategy SOCKS5 proxy
  -> protected TCP/UDP sockets
```

Архитектурная идея совпадает с локальным VPN-redirect подходом ByeByeDPI: Android VPN используется не как удаленный VPN-сервер, а как no-root перехват трафика устройства с локальной обработкой.

Важно по лицензиям:

- GPL-код Android-обвязки ByeByeDPI в проект не переносился.
- Kotlin lifecycle, bridge, service, strategy proxy и стратегия написаны внутри QNZapret.
- Сторонний TUN-to-SOCKS слой оставлен как `hev-socks5-tunnel`, потому что он распространяется под MIT.

## Что уже сделано

- Дефолтный Android launch config поднимает TUN через `establishTunnel=true`.
- Старый самописный `TunPacketForwarder.kt` удален из production path.
- `TunTransport.kt` поднимает `VpnService.Builder`, добавляет IPv4/IPv6 routes, DNS из выбранной underlying-сети, исключает собственный пакет через `addDisallowedApplication(...)` и передает TUN fd в `hev-socks5-tunnel`.
- `UnderlyingNetworkSelector.kt` выбирает validated unrestricted non-VPN сеть и ее DNS.
- `TProxyService.kt` оборачивает JNI lifecycle `hev-socks5-tunnel`.
- `StrategySocks5Server.kt` принимает SOCKS5 CONNECT и UDP_ASSOCIATE от `hev-socks5-tunnel`.
- TCP/UDP sockets local proxy открываются через `VpnService.protect`, чтобы не возвращать исходящий трафик обратно в VPN.
- `StrategyRuntimeEngine` остается центром принятия решений по HTTP/TLS/QUIC правилам.
- TLS split сделан как no-root-safe TLS record split через `TlsRecordSplitTransform.kt`.
- TCP fake оставлен best-effort: попытка low-hop-limit socket write, безопасный пропуск при недоступной TTL/hop-limit опции.
- QUIC получает `udpFake` для распознанного QUIC Initial даже без надежной DNS-корреляции.
- Stop lifecycle починен: команда остановки доставляется в service, service останавливает runtime, `hev-socks5-tunnel`, TUN fd и local SOCKS5 proxy.
- Временные диагностические пробники из production path удалены.

## Ключевые файлы

- `lib/core/backend/proxy_runtime.dart` - Dart runtime contract и default Android strategy config.
- `lib/core/backend/proxy_runtime_controller.dart` - UI/application facade для start/stop/status.
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt` - MethodChannel bridge.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt` - foreground `VpnService`.
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt` - coordinator Android runtime.
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt` - TUN lifecycle и запуск `hev-socks5-tunnel`.
- `android/app/src/main/kotlin/dev/qnzapret/TProxyService.kt` - JNI wrapper для `hev-socks5-tunnel`.
- `android/app/src/main/kotlin/dev/qnzapret/StrategySocks5Server.kt` - local strategy SOCKS5 proxy.
- `android/app/src/main/kotlin/dev/qnzapret/UnderlyingNetworkSelector.kt` - выбор underlying network и DNS.
- `android/app/src/main/kotlin/dev/qnzapret/TlsRecordSplitTransform.kt` - TLS record split.
- `android/app/src/main/jni/` - NDK-сборка `hev-socks5-tunnel`.

## Проверенное поведение на устройстве

На устройстве `7e7464c7` подтверждено:

- APK устанавливается через `adb install -r`.
- VPN permission уже есть или проходит штатный prepare flow.
- Start поднимает service, local strategy SOCKS5 proxy и TUN-to-SOCKS слой.
- В логах есть старт SOCKS5 TCP/UDP relay и `tun2socks started proxy=127.0.0.1:1080`.
- Трафик доходит до local strategy proxy.
- Stop больше не зависает: service получает stop action, local SOCKS5 proxy останавливается, UI возвращается в готовое состояние.

Ожидаемые контрольные логи старта:

```text
QNZapretProxy: socks udp relay listening 127.0.0.1:60005
QNZapretProxy: socks5 strategy proxy listening 127.0.0.1:1080
QNZapretTun: tun establish dns=192.168.1.1 underlying=104
QNZapretTun: tun2socks started proxy=127.0.0.1:1080
```

Ожидаемые контрольные логи остановки:

```text
QNZapretBridge: stop requested
QNZapretBridge: stop command delivered=true stopRequested=true
QNZapretService: stop action received
QNZapretProxy: socks5 strategy proxy stopped
```

## Проверки

Последний известный зеленый набор:

- `flutter analyze --no-pub` - passed
- `flutter test --no-pub` - passed
- `cd android; .\gradlew.bat :app:testDebugUnitTest` - passed
- `cd android; .\gradlew.bat :app:assembleDebug` - passed
- `adb install -r build\app\outputs\apk\debug\app-debug.apk` - success

Перед финальным merge/push после новых правок документации стоит повторить хотя бы:

- `flutter analyze --no-pub`
- `flutter test --no-pub`
- `cd android; .\gradlew.bat :app:testDebugUnitTest`

## Что осталось

Главный текущий блокер отдельно описан в `docs/android_uid_network_blocker.md`.

После него ближайшие backend-задачи:

- понять, почему Android/OEM режет сеть именно UID приложения;
- добавить production log stream в Dart runtime contract;
- усилить local SOCKS5 proxy: write backpressure, лимиты сессий, диагностика;
- расширить QUIC correlation для DoH/DoT, DNS cache misses и сложных multi-IP сценариев;
- позже описать equivalent bridge strategy для Linux и Windows.

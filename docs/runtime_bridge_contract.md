# Runtime Bridge Contract

## Назначение документа

Этот файл фиксирует текущий контракт между Flutter frontend и platform/runtime implementations.

Источник кода:

- `lib/core/backend/proxy_runtime.dart`
- `lib/core/backend/android_proxy_runtime.dart`
- `lib/core/backend/proxy_runtime_controller.dart`
- `lib/core/backend/proxy_runtime_factory.dart`
- `lib/core/backend/backend.dart`
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnRuntimeStore.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyProfile.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetStore.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetVerifier.kt`
- `android/app/src/main/kotlin/dev/qnzapret/HostlistMatcher.kt`
- `android/app/src/main/kotlin/dev/qnzapret/L7Detectors.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimeEngine.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimePlan.kt`
- `android/app/src/main/kotlin/dev/qnzapret/LocalStrategyProxy.kt`
- `android/app/src/main/kotlin/dev/qnzapret/IpPacketCodec.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TunPacketForwarder.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt`

Если код и документ расходятся, источником правды считается код.
При любом существенном изменении контракта нужно обновлять и код, и этот документ в одном наборе изменений.

## Цель контракта

Сделать так, чтобы:

- frontend работал от стабильного Dart API
- Android/Linux/Windows adapters можно было развивать независимо от UI
- backend-состояние не расползалось по виджетам
- platform-specific детали не попадали напрямую в presentation layer

## Актуальный Dart API

```dart
abstract interface class ProxyRuntime {
  ProxyPlatform get platform;

  Future<ProxyPrepareResult> prepare();

  Future<ProxyRuntimeSnapshot> getSnapshot();

  Future<void> start(ProxyLaunchConfig config);

  Future<void> stop();
}
```

Текущие реализации:

- `StubProxyRuntime` - stub для состояния, где нативный backend еще не подключен.
- `AndroidProxyRuntime` - adapter поверх Android `MethodChannel`.
- `ProxyRuntimeController` - UI/application facade над `ProxyRuntime`, который хранит snapshot, busy/error state и default launch config.
- `createDefaultProxyRuntime()` - composition helper: на Android возвращает `AndroidProxyRuntime`, на Linux/Windows возвращает stub-адаптеры до появления desktop runtime.

Рекомендуемая точка входа для frontend:

```dart
import 'package:qnzapret/core/backend/backend.dart';

final controller = ProxyRuntimeController(runtime: runtime);
await controller.initialize();
await controller.prepare();
await controller.start();
await controller.stop();
```

## Платформы

```dart
enum ProxyPlatform { android, linux, windows }
```

Текущий реальный adapter начат только для Android.
Linux и Windows должны по возможности прийти к той же Dart-поверхности runtime.

## Runtime states

```dart
enum ProxyRuntimeState { idle, starting, running, stopping, failed }
```

Интерпретация:

- `idle` - runtime не активен и готов к следующему действию
- `starting` - runtime находится в процессе запуска
- `running` - Android foreground service активен; детали готовности engine/forwarder смотрятся в snapshot flags
- `stopping` - runtime находится в процессе остановки
- `failed` - операция runtime завершилась ошибкой или runtime не может продолжать работу

Важно: сейчас `running` на Android означает, что foreground `VpnService` активен и native runtime прошел стартовый lifecycle.
Это еще не означает, что TUN fd уже связан с production TCP/UDP userspace forwarder и реальным socket proxy.
Для этого в snapshot есть отдельный флаг `trafficForwarderReady`.

## Модели

### `ProxyPrepareResult`

Назначение:

- результат подготовки platform runtime перед запуском
- на Android сейчас отражает результат VPN permission flow

Поля:

- `granted`
- `message`

Wire payload:

```dart
{
  'granted': true,
  'message': 'VPN permission granted. Android strategy runtime is ready to start.',
}
```

### `ProxyLaunchConfig`

Назначение:

- минимальная конфигурация запуска runtime
- передается из Dart в platform adapter

Поля:

- `localHost`
- `localPort`
- `poolSize`
- `cloudflareEnabled`
- `secret`
- `strategyProfile`
- `establishTunnel`
- `tunnelMtu`
- default preset: `ProxyLaunchConfig.defaultAndroidStrategy`

Wire payload:

```dart
{
  'localHost': '127.0.0.1',
  'localPort': 1080,
  'poolSize': 8,
  'cloudflareEnabled': true,
  'secret': 'token',
  'strategyProfile': StrategyProfile.defaultLightweight.toMap(),
  'establishTunnel': false,
  'tunnelMtu': 8500,
}
```

Текущий Android service читает эти значения, парсит `strategyProfile`, компилирует его в `StrategyRuntimePlan`, проверяет assets и поднимает local strategy proxy с native strategy engine.
По умолчанию `establishTunnel` равен `false`, чтобы приложение не перехватывало весь трафик без явного включения TUN default-route.
Если `establishTunnel` передать как `true`, Android layer вызывает `VpnService.Builder.establish()` только когда TCP/UDP forwarder capabilities готовы.

### `StrategyProfile`

Назначение:

- описать no-root subset стратегии в переносимой форме
- отделить продуктовые пресеты от Android-specific implementation
- передать hostlists, L7 filters и поддерживаемые actions в platform adapter

Текущая дефолтная стратегия:

- HTTP TCP/80 hostlist rule с `fake(repeats: 1)` и `split(position: 1)`
- TLS TCP/443 hostlist rule с `fake(blobKey: tls_google)` и `split(position: 1)`
- QUIC UDP/443 hostlist rule с `udpFake(blobKey: quic_google)`
- `unmatchedTrafficPolicy: direct` - трафик, который не попал в hostlists, должен проходить обычным direct forwarding без desync actions

Дефолтные Android asset paths:

- `qnzapret/lists/list-general.txt`
- `qnzapret/lists/list-user.txt`
- `qnzapret/lists/list-google.txt`
- `qnzapret/payloads/tls_clienthello_www_google_com.bin`
- `qnzapret/payloads/quic_initial_www_google_com.bin`

Эти файлы лежат в `android/app/src/main/assets/qnzapret/` и попадают в APK как Android assets.
`QnzapretAndroidRuntime` проверяет их наличие через `StrategyAssetVerifier`, загружает payload blobs через `StrategyAssetStore`, регистрирует lazy hostlist matchers и добавляет результат в runtime message/snapshot.

Поддерживаемые Dart-модели:

- `StrategyProfile`
- `StrategyRule`
- `StrategyAction`
- `StrategyProtocol`
- `StrategyActionKind`
- `UnmatchedTrafficPolicy`

Семантика hostlists:

- hostlists работают как списки включения desync-обработки, а не как allowlist всего соединения
- если домен, SNI, HTTP Host или QUIC target не найден в списках, local strategy proxy должен форвардить поток без fake/split/udpFake
- `direct` означает обычный исходящий путь через Android protected socket, чтобы соединение не возвращалось обратно в VPN
- отсутствие L7 host target на этапе детекта не должно само по себе блокировать поток; до появления отдельной политики блокировки безопасное поведение - direct forwarding

Native strategy engine сейчас умеет:

- лениво загружать hostlists из Android assets и матчить exact/suffix домены
- загружать бинарные payload blobs по ключам `tls_google` и `quic_google`
- детектить HTTP Host и TLS ClientHello SNI из первого payload chunk
- распознавать базовый QUIC Initial marker, но без DNS/SNI correlation пока не применяет hostlist desync к QUIC, если host заранее неизвестен
- возвращать `DIRECT` для unmatched traffic, missing host и неподдержанного протокола
- возвращать `DESYNC` с resolved actions и payload bytes для matched HTTP/TLS правил

Userspace forwarding foundation сейчас умеет:

- парсить IPv4 и IPv6 packets из TUN buffer
- выделять UDP datagrams и flow tuple
- открывать protected `DatagramSocket`, чтобы исходящий IPv4/IPv6 UDP не возвращался обратно в VPN
- синтезировать IPv4/IPv6 UDP response packets и писать их обратно в TUN output
- вызывать `StrategyRuntimeEngine.evaluate()` перед отправкой UDP datagram
- отправлять `udpFake` payload repeats перед реальным datagram, если decision уже содержит такую action
- выделять TCP segments и flow tuple
- отвечать на TCP SYN из TUN через synthetic SYN/ACK, вести client/server sequence numbers, ACK, FIN и RST
- открывать protected `Socket`, чтобы исходящий IPv4/IPv6 TCP stream не возвращался обратно в VPN
- прокидывать TCP payload между TUN flow и protected socket, синтезируя TCP response packets обратно в TUN output
- вызывать `StrategyRuntimeEngine.evaluate()` на первом TCP payload chunk
- применять TCP `split` как best-effort stream write split; TCP `fake` в no-root socket mode намеренно не отправляется в реальный socket

Ограничения текущего foundation:

- TCP relay/state machine является foundation-реализацией без полноценного retransmit, congestion control, backpressure и долгого idle cleanup
- TUN default-route не включается по умолчанию, потому что `ProxyLaunchConfig.defaultAndroidStrategy.establishTunnel=false`; при явном `establishTunnel=true` он включается только если TCP/UDP capabilities готовы
- IPv6 extension headers пока не разворачиваются, foundation обрабатывает прямой IPv6 UDP/TCP `nextHeader`
- QUIC host detection пока не связывает QUIC Initial с доменом без внешней DNS/SNI correlation, поэтому hostlist-based `udpFake` обычно не сработает сам по себе
- TCP `fake` поверх обычного protected socket не считается безопасным no-root действием, потому что без raw fooling пакет увидит настоящий сервер
- TCP `split` через обычный socket является best-effort: отдельные `OutputStream.write()` не гарантируют сохранение TCP packet boundaries на всех сетевых стеках

Важно: эта модель намеренно описывает proxy/stream-oriented no-root subset.
Она не обещает полную семантику `nfqws2`, raw TCP sequence tricks, TCP timestamp fooling (`ts`), TTL tricks или IP fragmentation.

### `ProxyRuntimeSnapshot`

Назначение:

- единый snapshot текущего runtime-состояния для UI и application layer

Поля:

- `platform`
- `state`
- `message`
- `backendConnected`
- `vpnPermissionGranted`
- `serviceActive`
- `strategyEngineReady`
- `trafficForwarderReady`
- `tunnelActive`
- `packetCodecReady`
- `udpForwarderReady`
- `ipv6PacketCodecReady`
- `ipv6UdpForwarderReady`
- `tcpForwarderReady`
- `activeProfileName`

Wire payload:

```dart
{
  'platform': 'android',
  'state': 'running',
  'message': 'Android VPN strategy engine is active.',
  'backendConnected': true,
  'vpnPermissionGranted': true,
  'serviceActive': true,
  'strategyEngineReady': true,
  'trafficForwarderReady': false,
  'tunnelActive': false,
  'packetCodecReady': true,
  'udpForwarderReady': true,
  'ipv6PacketCodecReady': true,
  'ipv6UdpForwarderReady': true,
  'tcpForwarderReady': true,
  'activeProfileName': 'Default lightweight',
}
```

Семантика:

- `backendConnected` сейчас означает, что platform bridge base доступен, а не что production userspace forwarder уже полностью обрабатывает трафик.
- `vpnPermissionGranted` актуально для Android.
- `serviceActive` отражает активность Android foreground service.
- `strategyEngineReady` означает, что native strategy engine создан, payload blobs загружены, а hostlists зарегистрированы для lazy matching.
- `trafficForwarderReady` означает, что TUN fd связан с полным TCP/UDP userspace forwarding layer. При `establishTunnel=false` остается `false`, даже если capability flags готовы.
- `tunnelActive` означает, что Android TUN fd реально установлен. При `establishTunnel=false` остается `false`; при `establishTunnel=true` становится `true`, если Android вернул TUN fd.
- `packetCodecReady` означает, что Android runtime умеет парсить IPv4/IPv6 UDP packets/TCP segments и собирать UDP/TCP response packets.
- `udpForwarderReady` означает, что UDP relay core готов использовать protected `DatagramSocket` и писать ответы обратно в TUN.
- `ipv6PacketCodecReady` означает, что IPv6 packet parsing и UDP/TCP response builders включены в foundation.
- `ipv6UdpForwarderReady` означает, что UDP relay core умеет работать с IPv6 destination/source addresses.
- `tcpForwarderReady` означает готовность TCP userspace relay/state machine.
- `activeProfileName` дает UI человекочитаемое имя профиля, если runtime активен.
- `message` должен быть пригоден для отображения в UI.

### `ProxyRuntimeController`

Назначение:

- дать UI один объект для привязки кнопок, статуса и ошибок
- скрыть `PlatformException`, `MissingPluginException` и busy-state внутри backend layer
- держать default launch config рядом с runtime-контрактом
- обновлять snapshot после `prepare`, `start`, `stop` и ручного `refresh`

Публичные поля и команды:

- `snapshot`
- `launchConfig`
- `lastPrepareResult`
- `lastFailure`
- `isBusy`
- `needsPrepare`
- `canStart`
- `canStop`
- `isActive`
- `initialize()`
- `refresh()`
- `prepare()`
- `start([config])`
- `stop()`
- `updateLaunchConfig(config)`

UI должен предпочитать этот controller прямым вызовам `AndroidProxyRuntime`, если ему нужны кнопки, loading state и отображение ошибок.

## Методы

### `prepare()`

Назначение:

- подготовить platform runtime перед запуском
- на Android запросить или проверить VPN permission

Android behavior:

- если `VpnService.prepare(activity)` возвращает `null`, permission уже доступен
- иначе открывается системный consent flow
- результат возвращается как `ProxyPrepareResult`

Возможная native error:

- `vpn_prepare_in_progress` - запрос разрешения уже выполняется

### `getSnapshot()`

Назначение:

- получить актуальный snapshot runtime-состояния

Android behavior:

- вызывает `QnzapretVpnRuntimeStore.snapshot(context)`
- синхронизирует текущее VPN permission state через `VpnService.prepare(context)`

### `start(ProxyLaunchConfig config)`

Назначение:

- запустить platform runtime с заданной конфигурацией

Android behavior:

- требует уже выданного VPN permission
- переводит store в `starting`
- запускает `QnzapretVpnService` как foreground service
- service парсит strategy profile, компилирует runtime plan, проверяет assets и поднимает local strategy proxy с native strategy engine
- service проверяет наличие hostlists и payload blobs в Android assets
- service сообщает capabilities packet codec, UDP relay и TCP relay через snapshot; `trafficForwarderReady` становится `true` только когда TUN fd реально связан с forwarder
- service переводит store в `running`, если strategy engine успешно поднят
- если foreground service не удалось запустить, bridge возвращает `vpn_start_failed` и store получает `failed`
- если runtime внутри service упал при старте, service переводит store в `failed` и останавливается

Возможная native error:

- `vpn_permission_required` - запуск невозможен без VPN permission
- `vpn_start_failed` - Android service не удалось стартовать из bridge layer

Важно: `start()` возвращает `Future<void>`.
Для получения итогового состояния нужно вызвать `getSnapshot()` после native lifecycle update.

### `stop()`

Назначение:

- остановить platform runtime

Android behavior:

- переводит store в `stopping`
- вызывает `stopService`
- если service уже не был активен, store возвращается в `idle`
- `QnzapretVpnService.onDestroy()` также переводит store в `idle`

## Android MethodChannel contract

Channel:

```text
dev.qnzapret/proxy_runtime
```

Methods:

- `prepare`
- `getSnapshot`
- `start`
- `stop`

`start` arguments:

```dart
{
  'config': ProxyLaunchConfig(...).toMap(),
}
```

Native return values:

- `prepare` возвращает map с `granted` и `message`
- `getSnapshot` возвращает map для `ProxyRuntimeSnapshot.fromMap`
- `start` возвращает `null` при успешной отправке команды запуска
- `stop` возвращает `null` при успешной отправке команды остановки

## Lifecycle expectations

### Android prepare flow

1. UI/application layer вызывает `prepare()`.
2. Android bridge проверяет `VpnService.prepare`.
3. Если нужно разрешение, открывается системный consent screen.
4. Результат возвращается в Dart как `ProxyPrepareResult`.
5. `getSnapshot()` после prepare должен отражать актуальный `vpnPermissionGranted`.

### Android start flow

1. UI/application layer вызывает `start(config)`.
2. Если permission отсутствует, Android bridge возвращает ошибку `vpn_permission_required`.
3. Если permission есть, store получает `starting`.
4. Foreground `QnzapretVpnService` стартует.
5. Service получает config, компилирует strategy profile и проверяет Android assets.
6. Service стартует local strategy proxy, загружает strategy engine и поднимает TUN только при `establishTunnel=true` и готовых TCP/UDP forwarder capabilities.
7. Runtime message отражает `unmatchedTrafficPolicy`, чтобы было видно, что трафик вне hostlists не должен попадать под desync.
8. Service переводит store в `running`.
9. `getSnapshot()` возвращает актуальное состояние.

### Android stop flow

1. UI/application layer вызывает `stop()`.
2. Store получает `stopping`.
3. Android service останавливается.
4. Store возвращается в `idle`.

## Требования к будущей backend-реализации

- Не отдавать platform-specific payloads напрямую в UI.
- Не смешивать runtime handles с Flutter widgets.
- Все низкоуровневые события сначала адаптировать до Dart-моделей контракта.
- Сохранять единый runtime API для Android/Linux/Windows, насколько это возможно.
- Не расширять контракт без понятного UI/backend сценария.
- При добавлении logs/status streams зафиксировать их здесь до активного использования в UI.

## Что желательно согласовать до production runtime

- какие поля `ProxyLaunchConfig` останутся стабильными
- какие native error codes считаются публичными
- как отличать "Android service running" от "userspace forwarder fully connected"
- как будет устроен log stream
- нужен ли отдельный health-check или diagnostics snapshot
- как local strategy proxy будет читать hostlists/payload blobs из Android assets и пользовательского storage
- какие strategy actions остаются в no-root subset, а какие явно требуют root/raw packet mode
- какая форма desktop adapters нужна для Linux и Windows

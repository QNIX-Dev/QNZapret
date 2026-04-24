# Integration Workflow

## Назначение документа

Этот файл описывает рекомендуемый процесс совместной работы frontend- и backend-разработки на следующей стадии проекта.

Цель:

- сократить число расхождений между UI и runtime-реализацией
- сделать handoff между frontend и backend предсказуемым
- не ломать экранную логику при подключении реального Android strategy runtime
- сохранять Android/Linux/Windows adapters за общей Dart-поверхностью

## Главный принцип

Работа идет по схеме `contract first`.

Порядок такой:

1. Сначала уточняется `ProxyRuntime` contract.
2. Потом backend/platform layer реализует его в adapter.
3. Потом UI или composition root переключается со stub/runtime placeholder на реальную реализацию.
4. Любое изменение lifecycle semantics сначала фиксируется в контракте, потом в коде.

## Роли

### Frontend

Отвечает за:

- UI/UX
- theme
- presentation layer
- адаптацию runtime snapshot в экранное поведение

### Backend

Отвечает за:

- запуск и остановку реального runtime
- platform-specific bridge implementation
- Android service lifecycle
- runtime status
- будущий поток логов
- ошибки и recoverability semantics

### Совместная зона

- `docs/runtime_bridge_contract.md`
- `lib/core/backend/backend.dart`
- `lib/core/backend/proxy_runtime.dart`
- `lib/core/backend/android_proxy_runtime.dart`
- `lib/core/backend/proxy_runtime_controller.dart`
- `lib/core/backend/proxy_runtime_factory.dart`
- `android/app/src/main/kotlin/dev/qnzapret/ProxyRuntimeBridge.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnRuntimeStore.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QnzapretAndroidRuntime.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetStore.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyAssetVerifier.kt`
- `android/app/src/main/kotlin/dev/qnzapret/HostlistMatcher.kt`
- `android/app/src/main/kotlin/dev/qnzapret/L7Detectors.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimeEngine.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyProfile.kt`
- `android/app/src/main/kotlin/dev/qnzapret/StrategyRuntimePlan.kt`
- `android/app/src/main/kotlin/dev/qnzapret/LocalStrategyProxy.kt`
- `android/app/src/main/kotlin/dev/qnzapret/IpPacketCodec.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QuicHostCorrelation.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TcpRelayState.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TunPacketForwarder.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt`

Именно эти артефакты должны рассматриваться как shared contract surface для текущей Android-интеграции.

## Практический цикл работы

### Этап 1. Согласование контракта

Перед изменением backend-интеграции нужно зафиксировать:

- какие методы `ProxyRuntime` обязательны
- какие поля snapshot UI реально использует
- какие состояния backend может гарантировать
- какие native errors считаются публичными
- как отличать foreground service от fully connected userspace forwarder
- какие части strategy profile являются стабильным no-root subset

Результат:

- обновленный `docs/runtime_bridge_contract.md`
- при необходимости обновленный `proxy_runtime.dart`
- при необходимости обновленный Android bridge/wire protocol

### Этап 2. Backend делает adapter

Backend реализует platform adapter так, чтобы наружу он соответствовал Dart-контракту.

Важно:

- platform-specific детали остаются внутри adapter
- UI не должен разбирать native payload вручную
- Android bridge должен возвращать payload, совместимый с `ProxyRuntimeSnapshot.fromMap`
- ошибки должны иметь стабильные коды, если UI начинает на них опираться

### Этап 3. Подключение в composition/UI

Стартовый экран получает `ProxyRuntime` из composition root и работает с ним через `ProxyRuntimeController`.
Фронтендеру не нужно вызывать `MethodChannel` или Android classes напрямую.

Ожидаемый принцип:

- `main.dart` создает runtime через `createDefaultProxyRuntime()`
- `QnzapretApp` принимает `ProxyRuntime`
- UI создает или получает `ProxyRuntimeController`
- кнопки и индикаторы используют `initialize`, `prepare`, `start`, `stop`, `refresh`, `isBusy`, `needsPrepare`, `canStart`, `canStop`, `lastFailure`

Если контракт не сломан, экранный слой не должен знать Kotlin/Android details.

### Этап 4. Совместная проверка сценариев

Минимальный набор сценариев для проверки Android-интеграции:

- snapshot до выдачи VPN permission
- успешный `prepare()`
- отказ пользователя в VPN permission
- повторный `prepare()`, когда permission уже выдан
- `start(config)` без permission должен вернуть контролируемую ошибку
- `start(config)` после permission должен поднять Android foreground service
- `getSnapshot()` после старта должен показать `running` и `serviceActive`
- snapshot/message после старта должен отражать выбранный strategy profile
- snapshot/message после старта должен отражать наличие или отсутствие нужных strategy assets
- snapshot после старта должен различать `strategyEngineReady`, `trafficForwarderReady`, `tunnelActive`, `packetCodecReady`, `udpForwarderReady`, `ipv6PacketCodecReady`, `ipv6UdpForwarderReady` и `tcpForwarderReady`
- при `establishTunnel=false` snapshot должен показывать готовые capability flags, но `trafficForwarderReady=false` и `tunnelActive=false`
- при `establishTunnel=true` Android должен поднимать TUN fd только если TCP/UDP forwarder capabilities готовы
- домен вне hostlists должен проходить direct forwarding без fake/split/udpFake действий
- TCP hostlist match должен применять `split` как best-effort stream write split и не отправлять небезопасный TCP `fake` в protected socket mode
- TCP relay должен игнорировать full duplicate retransmits, форвардить только новый tail при overlap retransmit, ACK/drop out-of-order payload без продвижения окна и чистить idle sessions
- QUIC hostlist match должен применять `udpFake`, когда UDP/443 Initial получает `knownHost` из UDP/53 DNS response или prior TCP HTTP/TLS host correlation
- `stop()` после старта должен вернуть runtime в `idle`
- revoke permission должен вернуть понятное состояние

## Правила изменения контракта

Любое изменение runtime contract считается заметным интеграционным изменением, если оно затрагивает:

- сигнатуры методов `ProxyRuntime`
- поля `ProxyPrepareResult`
- поля `ProxyLaunchConfig`
- поля `ProxyRuntimeSnapshot`
- набор значений `ProxyRuntimeState`
- Android MethodChannel name
- имена native methods
- форму payload для `prepare`, `getSnapshot`, `start`, `stop`

Тогда нужно сделать сразу три вещи:

1. Обновить код контракта и adapters.
2. Обновить `docs/runtime_bridge_contract.md`.
3. Коротко зафиксировать impact на UI и backend в PR/описании изменений.

Если изменение влияет на архитектуру, платформенный охват, структуру папок, зависимости или командные соглашения, нужно также обновить `AGENTS.md`.

## Что лучше не делать

- не обсуждать контракт только устно
- не менять wire payload "по ходу" без фиксации
- не отдавать UI временные backend payloads "пока так"
- не добавлять platform-specific логику в presentation layer
- не расширять модели без понятного сценария использования
- не считать `running` fully connected traffic forwarding без отдельной семантики

## Рекомендуемый handoff между коллегами

Когда backend будет готов к следующей интеграции, удобно передавать задачу в таком формате.

### От frontend к backend

- актуальный `runtime_bridge_contract.md`
- какие поля snapshot реально отображаются на экране
- какие UI-сценарии должны быть устойчивыми
- ограничения по тому, что нельзя менять в presentation layer

### От backend к frontend

- статус готовности adapter по платформам
- список реально поддержанных lifecycle-сценариев
- список известных ограничений
- список native error codes
- список временно неподдержанных возможностей

## Definition of Done для первой Android-интеграции

Интеграция считается удачной, когда:

- Android adapter реализует текущий `ProxyRuntime` contract
- composition root передает Android `AndroidProxyRuntime` на Android и stub-адаптеры на desktop
- UI может работать через `ProxyRuntimeController` без platform-specific кода
- VPN permission flow стабильно проходит через `prepare()`
- Android foreground service стартует и останавливается через Dart API
- `getSnapshot()` отражает реальные Android lifecycle transitions
- ошибки permission/start/stop не ломают UI
- strategy profile передается из Dart в Android bridge
- hostlists и payload blobs дефолтной стратегии упакованы в Android assets
- native strategy engine загружает payload blobs, регистрирует hostlists и возвращает direct/desync decisions
- IPv4/IPv6 packet codec, UDP relay core и TCP relay/state machine готовы; TUN default-route включается только при явном `establishTunnel=true`
- hostlists используются как включение desync-правил, а unmatched traffic сохраняет политику `direct`
- local strategy proxy и TUN transport подключены за Android service
- userspace forwarder передает трафик из TUN fd в локальный strategy proxy
- Android JVM tests для TCP relay state и packet codec проходят через Gradle
- `flutter analyze` и `flutter test` проходят

## Предлагаемый порядок следующих задач

1. Добить оставшийся TCP hardening: out-of-order buffering, write backpressure, расширенная диагностика и Android device smoke при `establishTunnel=true`.
2. Добавить production log stream поверх текущего `ProxyRuntimeController`.
3. Расширить QUIC correlation для DoH/DoT, DNS cache misses и сложных multi-IP сценариев.
4. Расширить diagnostics snapshot, если UI понадобится больше runtime health-полей.
5. После Android описать equivalent bridge strategy для Linux и Windows.

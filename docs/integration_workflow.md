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
- продуктовый Home/Logs/Settings shell
- локализацию user-facing runtime/status/log сообщений

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
- `android/app/src/main/kotlin/dev/qnzapret/StrategySocks5Server.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TProxyService.kt`
- `android/app/src/main/kotlin/dev/qnzapret/IpPacketCodec.kt`
- `android/app/src/main/kotlin/dev/qnzapret/QuicHostCorrelation.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TcpRelayState.kt`
- `android/app/src/main/kotlin/dev/qnzapret/TunTransport.kt`
- `android/app/src/main/kotlin/dev/qnzapret/UnderlyingNetworkSelector.kt`

Именно эти артефакты должны рассматриваться как shared contract surface для текущей Android-интеграции.

## Практический цикл работы

### Этап 1. Согласование контракта

Перед изменением backend-интеграции нужно зафиксировать:

- какие методы `ProxyRuntime` обязательны
- какие поля snapshot UI реально использует
- какие состояния backend может гарантировать
- какие native errors считаются публичными
- как отличать foreground service от fully connected TUN-to-SOCKS stack
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

Composition root создает `ProxyRuntime` и передает его через Riverpod provider.
Экранный слой работает через `RuntimeController`, который внутри использует `ProxyRuntimeController`.
Фронтендеру не нужно вызывать `MethodChannel` или Android classes напрямую.

Ожидаемый принцип:

- `main.dart` создает runtime через `createDefaultProxyRuntime()`
- `main.dart` переопределяет `proxyRuntimeProvider`
- `QnzapretApp` открывает `AppShell`
- `AppShell` управляет Home/Logs навигацией и settings route
- UI получает runtime state через `runtimeControllerProvider`
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
- при дефолтном `establishTunnel=true` Android должен поднимать TUN fd только если `hev-socks5-tunnel` и local strategy SOCKS5 proxy готовы
- собственный пакет приложения должен быть исключен из VPN, а runtime UDP/TCP sockets local proxy должны проходить через `VpnService.protect`; manifest должен содержать `ACCESS_NETWORK_STATE`, чтобы TUN мог выбрать underlying network и ее DNS
- `QNZapretNetTest` должен отработать до старта runtime/TUN и после старта TUN, чтобы отдельно видеть доступность plain/protected/bound TCP и protected/bound UDP DNS probe из UID приложения
- TUN должен добавлять DNS из выбранной underlying-сети и передавать эту сеть в `Builder.setUnderlyingNetworks(...)`, чтобы DNS внутри VPN совпадал с реальной сетью устройства
- на IPv4-only underlying-сети TUN не должен объявлять `::/0`, а protected TCP/UDP upstream до IPv6 destination должен быстро логировать controlled no-route вместо длинного timeout
- при явном `establishTunnel=false` snapshot должен показывать готовые capability flags, но `trafficForwarderReady=false` и `tunnelActive=false`
- домен вне TCP hostlists должен проходить direct forwarding без fake/split действий
- TCP hostlist match должен применять TLS `split` как TLS record split, для остальных TCP payload использовать best-effort stream write split, а TCP `fake` отправлять только как low-hop-limit socket write с безопасным пропуском, если Android не дает применить TTL/hop-limit
- TCP relay в local SOCKS5 proxy должен корректно закрывать обе стороны потока, не держать stop lifecycle и применять strategy actions только к первому payload chunk
- QUIC Initial должен применять `udpFake` по дефолтному QUIC rule даже без `knownHost`, потому что Private DNS/DoT может скрыть DNS-корреляцию
- logcat для Android smoke должен включать timing-события `QNZapretProxy`: TCP connect start/ok/fail, first payload, strategy decision, upstream first byte, UDP send/receive; Telegram endpoint candidates должны быть видны как диагностика, а не как пользовательский proxy endpoint
- для Telegram smoke нужно отдельно проверять `telegram relay connect start/ok/failed` при наличии `StrategyProfile.endpointPolicies`; если relay не настроен, проверять `telegram preconnect begin/attempt/ok/failed`, а при `directBlockedBeforePayload=true` не считать payload-level fake/split/desync подходящим fix
- для Telegram compatibility mode нужно проверять `QNZapretTgCompat`: локальный `telegram kotlin proxy listening`, route provider `load/fetch/decode/cache`, CF probe `start/ok/failed`, открытие Telegram setup screen, MTProxy handshake `telegram compatibility start`, DNS `telegram cf dns start/ok/failed`, route `telegram cf route start/attempt/ok/failed`, HTTP status 101/403/429 и корректный stop `telegram kotlin proxy stopped`
- foreground notification должна быть ongoing, показывать состояния запуска/активности/ошибки/остановки и давать actions `Остановить`/`Перезапустить`; на Android 13+ приложение должно запросить `POST_NOTIFICATIONS`; скорость можно показывать только после появления честного counters contract
- Quick Settings Tile должен переключать тот же Android runtime: при выданном VPN permission запускать дефолтный профиль, при активном runtime останавливать через service stop action, а без permission открывать `MainActivity` для consent flow
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

Текущее состояние Android runtime зафиксировано в:

- `docs/android_runtime_handoff.md`
- `docs/android_uid_network_blocker.md`

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
- UI может работать через `RuntimeController` / `ProxyRuntimeController` без platform-specific кода
- Home показывает русские status chips и CTA запуска/остановки сервисов на основе snapshot
- Logs показывает runtime-controller events и готов к будущему native log stream
- Settings сохраняет theme mode, palette и не зависит от backend lifecycle
- VPN permission flow стабильно проходит через `prepare()`
- Android foreground service стартует и останавливается через Dart API
- `getSnapshot()` отражает реальные Android lifecycle transitions
- ошибки permission/start/stop не ломают UI
- strategy profile передается из Dart в Android bridge
- hostlists и payload blobs дефолтной стратегии упакованы в Android assets
- native strategy engine загружает payload blobs, регистрирует hostlists и возвращает direct/desync decisions
- `hev-socks5-tunnel`, SOCKS5 UDP relay и TCP relay готовы; TUN default-route включается дефолтным Android launch config при `establishTunnel=true`
- IPv6 route в TUN включается только при реально доступном IPv6 на selected underlying network
- Android foreground notification имеет actions stop/restart и не показывает fake speed
- hostlists используются как включение desync-правил, а unmatched traffic сохраняет политику `direct`
- local strategy proxy и TUN transport подключены за Android service
- `hev-socks5-tunnel` передает трафик из TUN fd в локальный strategy SOCKS5 proxy
- Android JVM tests для TCP relay state и packet codec проходят через Gradle
- `flutter analyze` и `flutter test` проходят

## Предлагаемый порядок следующих задач

1. Добить оставшийся local proxy hardening: write backpressure, лимиты сессий, расширенная диагностика и Android device smoke при `establishTunnel=true`.
2. Добавить Android production log stream по уже используемой Linux-модели
   `ProxyRuntimeLogEvent`.
3. Расширить QUIC correlation для DoH/DoT, DNS cache misses и сложных multi-IP сценариев.
4. Расширить diagnostics snapshot, если UI понадобится больше runtime health-полей.
5. После Android/Linux описать equivalent bridge strategy для Windows.

## Linux integration workflow

Linux GUI нельзя запускать через `sudo`. Проверка production path выполняется
только после установки `.deb` или `.rpm`:

1. `prepare()` проверяет system daemon, версию, checksums, nft/NFQUEUE,
   `nfqws2 --version`, compiler dry-run и Telegram sidecar.
2. Пользовательский `Start` активирует user sidecar и вызывает system D-Bus
   `Start`; Polkit показывает штатный prompt.
3. Daemon берёт lock, проверяет conflict, компилирует Linux-specific
   Fedora-smoke-verified HTTP/TLS/QUIC profile для pinned binary, выполняет
   `nfqws2 --dry-run`, асинхронно ждёт queue 200 и только затем атомарно
   применяет `inet qnzapret`.
4. При смерти `nfqws2` daemon немедленно удаляет только `inet qnzapret`,
   очищает queue/rules/interception readiness и публикует `failed`.
5. `Stop` сначала снимает собственную table, затем останавливает `nfqws2` и
   user sidecar. Повторный `Stop` безопасен.

Проверки:

```bash
ctest --test-dir build/linux/x64/release --output-on-failure
sudo test/integration/linux_netns_runtime_test.sh
flutter build linux --release
bash packaging/linux/build_packages.sh
```

Network namespace test не должен выполняться без root: в таком окружении он
завершается кодом `77` и считается явно пропущенным, а не успешно пройденным.
С root тест создаёт отдельные client/server netns, проводит реальные HTTP/TLS
payloads, проверяет counters/L7 diagnostics и автоматический crash cleanup
daemon, сохраняя foreign nft table.

Если deterministic netns-проверка проходит, но реальный провайдерский DPI всё
ещё блокирует TLS после ClientHello, фиксированная root-only матрица запускается
только при остановленном QNZapret:

```bash
sudo test/integration/linux_real_strategy_matrix.sh
```

Матрица не принимает пользовательские аргументы стратегии, использует только
встроенный allowlist кандидатов, меняет исключительно `table inet qnzapret` и
после каждого кандидата завершает свой worker. Найденный кандидат не считается
production-стратегией до переноса в compiler, пересборки пакета и повторного
one-button smoke.

Кандидат `friend-pc` дал HTTP 200 для Google и YouTube, успешный TLS для
`i.ytimg.com`, загрузил 10 MiB через SNI `test.googlevideo.com`, прошёл YouTube
HTTP/3 и увеличил nft counters с 0 до 232. После переноса кандидата матрица
получает аргументы непосредственно из production compiler через
`--print-profile`, поэтому последующие прогоны проверяют уже поставляемый
профиль. Это Linux-only результат; Android no-root runtime продолжает
использовать собственную стратегию.

Установленный Fedora RPM `0.0.4-1.fc44` затем прошёл one-button smoke:
`nfqws2` работал от `qnzapret-runtime`, YouTube воспроизводился в браузере,
Telegram Desktop 7.0.1 подключился к `127.0.0.1:1443`, а sidecar подтвердил
MTProto handshake и живой upstream WS bridge. В ходе smoke выявлено, что
generic GIO activation запускал Telegram, но не всегда доставлял proxy URI.
В `0.0.5` доставка была исправлена прямым
`org.freedesktop.Application.Open`. Начиная с `0.0.6`, обычный Start больше
не запускает Telegram автоматически: sidecar работает в фоне, а сохранённый
proxy-профиль подключается при уже запущенном или вручную открытом клиенте.

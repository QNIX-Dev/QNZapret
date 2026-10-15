# Разработка, QA и Git

Вход для агента — [AGENTS.md](../AGENTS.md).

## Порядок изменения

1. Проверить текущую ветку и незакоммиченные изменения.
2. Изучить [обзор](project_brief.md), [структуру](project_structure.md),
   [контракт](runtime_bridge_contract.md) и соответствующий platform handoff.
3. Сначала зафиксировать изменение API/lifecycle, затем реализовать adapter и
   application layer. Widgets не вызывают native code напрямую.
4. Сохранить работу второго участника и платформенную независимость UI.
5. Проверить затронутые сценарии, обновить документацию, сообщить ограничения.

Frontend отвечает за presentation, тему, motion, layout и удобство работы с
runtime-состояниями; backend — за контракты, процессы, transport, права и
диагностику. Конкретная просьба пользователя определяет объём текущей задачи.

## Среда и версии

- Dart constraint: `^3.11.4`, зависимости — `pubspec.yaml`/`pubspec.lock`.
- Последняя локальная проверка: Flutter 3.47.6 / Dart 3.13.5, Linux x86_64.
- Android build требует Python 3 для generated hostlist indexes.
- Android: Java/Kotlin target 17, Gradle 8.14, AGP 8.11.1, Kotlin 2.2.20;
  compile/target/min SDK и NDK берутся из Flutter tooling.
- `android.builtInKotlin=false`, `android.newDsl=false` добавлены Flutter
  migrator для текущего Android build. Предупреждения о будущей поддержке
  Gradle/AGP/Kotlin не равны ошибке прошедшей сборки.
- Android release пока подписывается debug key. Новый Android build берёт
  versionName/versionCode из Flutter (`0.0.6+7`); UI использует package info.
- Gradle ограничен 3 GiB heap, двумя workers; Kotlin компилируется in-process.
  На 16 GiB хосте тяжёлые сборки и emulator smoke выполнять последовательно.
- Linux build: clang/C++, CMake, Ninja, pkg-config, GTK3/GIO development
  packages и Python 3. Packaging требует `dpkg-deb` или fallback ar/tar и
  `rpmbuild`; скрипт сейчас собирает оба формата, а не только DEB.

## Проверки кода

Из корня проекта:

```bash
flutter pub get
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build linux --release
ctest --test-dir build/linux/x64/release --output-on-failure
bash packaging/linux/tests/verify_packaging.sh
python3 -B -m unittest discover -s linux/components -p '*_test.py'
```

Android:

```bash
cd android
./gradlew :app:testDebugUnitTest
cd ..
flutter build appbundle --release
# Для установки на устройство дополнительно:
flutter build apk --release
```

Для согласованной параллельной установки с несовместимой старой подписью:
`cd android` и `./gradlew :app:assembleRelease -PqnzPreview=true`.
Пакет `dev.qnzapret.preview` и настройки отделены от обычного приложения;
старую версию/данные не удалять. JNI namespace сохраняется.

PowerShell использует `.\gradlew.bat :app:testDebugUnitTest` из `android/`.
`flutter run -d <DEVICE_ID>` требует ID из `flutter devices`; не предполагать,
что устройство имеет идентификатор `android`.

Windows: `flutter build windows --release` на Windows-хосте. Workflow
`.github/workflows/windows-release.yml` проверяет analyze/test/release build.
Linux workflow строит Debian 12 bundle и Fedora package. Наличие workflow
не означает, что он запущен или прошёл для текущего commit.

Для документационных правок без изменения кода проверяются ссылки, пути,
примеры, версии и соответствие implementation. Прежние build/smoke результаты
не выдаются за новый запуск. Для изменений кода выполняются relevant tests и
release-сборки затронутых платформ; недоступную платформу проверить в CI/на
нативном хосте либо явно отметить непроверенной перед релизом.

## Linux smoke

GUI проверяется обычным пользователем после установки DEB/RPM. Открытие GUI
активирует D-Bus helpers для snapshot, но не включает перехват и обновления.

```bash
sudo test/integration/linux_netns_runtime_test.sh
```

Без root скрипт возвращает **77 (skip)**. С root он создаёт отдельные netns,
проводит HTTP/TLS, проверяет counters/L7, аварийную очистку и чужую nft table.
Не считать этот тест проверкой ISP DPI, QUIC или текущих Telegram endpoints.

Реальная проверка установленного пакета:

- Start/отмена Polkit/повторный Start без подвисания UI.
- Сеть без обновлений и с обновлениями, недоступный GitHub, повреждённый или
  несовместимый компонент; сохранение локальной версии и fallback.
- NFQUEUE + собственная nft table + реальные HTTP/HTTPS/QUIC и целевые ресурсы.
- Telegram setup до входа, штатный confirm, live bridge; listener без клиента
  должен показывать необходимость настройки, а не подтверждённый успех.
- Ошибка sidecar и rollback, Stop/повторный Stop, worker crash, GUI restart.
- Upgrade/reboot/uninstall на чистых Debian/Ubuntu/Fedora перед релизом.

`linux_real_strategy_matrix.sh` — отдельный root-тест в реальной сети при
остановленном QNZapret. Он использует встроенный allowlist; не запускается
автоматически при каждой документационной правке.

## Android smoke

На устройстве проверить VPN consent (успех/отказ), Start/Stop/revoke,
TUN/forwarder flags, HTTP/TLS/QUIC, DNS/Private DNS, IPv4-only и IPv6 сети,
background/notification/QS controls. Не удалять приложение/данные ради теста
без согласования; `adb install -r` использовать только для нужного устройства.

Telegram: local listener отдельно от успешных handshake/bridge; UI-start
и notification action; public routes без локального config; local override;
text/media/voice/video/file upload, cooldown, pool, watchdog и Stop. Личные
аккаунты и сообщения не используются автоматически как тестовые fixtures.
Логи: `QNZapretService`, `QNZapretTun`, `QNZapretProxy`, `QNZapretNetTest`,
`QNZapretTgCompat`; секреты и приватные домены не публиковать.

Автоматические account-free сценарии только для отдельного эмулятора:

```bash
python3 test/integration/android_qs_vpn_regression.py --serial emulator-5554 --apk <APK>
python3 test/integration/android_runtime_smoke.py --serial emulator-5554 --apk <APK>
python3 test/integration/android_telegram_smoke.py --serial emulator-5554
```

Они отвергают физический телефон, меняют тестовые appops/QS и используют
отдельный fixture APK. Проверяются competing VPN, JNI/TUN, UDP association
isolation, HTTPS отдельного UID и Stop. Сеть хоста/его обход могут влиять на
результат; это не независимое подтверждение DPI/media на смартфоне.

## Git и сабмодуль hev-socks5-tunnel

Работать в пользовательской ветке, сохранять чужие изменения. Коммит — после
верификации и разрешения пользователя; уже данное разрешение не спрашивать
повторно. Push/PR — когда нужна публикация. В `main` изменения только через PR;
merge и переписывание истории требуют отдельного разрешения.

`hev-socks5-tunnel` (`android/app/src/main/jni/hev-socks5-tunnel`) — Git submodule,
закреплен на теге `2.19.0` вместе с вложенными сабмодулями. Чтобы обновить:

1. Перейти в сабмодуль и сделать `git checkout <tag>`.
2. Сверить `src/hev-jni.c` с Kotlin-контрактом `TProxyService.kt`.
3. Выполнить `git add` сабмодуля, затем `git submodule update --init --recursive`.
4. Обновить `REV_ID` в `android/app/build.gradle.kts` и `android/app/src/main/jni/provenance.json`.

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
2. Добавить production log stream поверх текущего `ProxyRuntimeController`.
3. Расширить QUIC correlation для DoH/DoT, DNS cache misses и сложных multi-IP сценариев.
4. Расширить diagnostics snapshot, если UI понадобится больше runtime health-полей.
5. После Android описать equivalent bridge strategy для Linux и Windows.

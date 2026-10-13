# Карта проекта

Сверено с веткой `quriee` 12 октября 2026 года. Подробные поля API находятся в
[runtime-контракте](runtime_bridge_contract.md), команды — в [QA](integration_workflow.md).

## Основные каталоги

```text
lib/
  main.dart                     preferences + runtime composition
  app/                          MaterialApp, theme, navigation, routing
  features/{home,logs,settings}/ presentation
  core/backend/                 shared API, adapters, controller
  core/state/                   Riverpod, view models, persistence state
  core/persistence/             SharedPreferences provider
  core/motion/                  motion tokens, smooth desktop scroll
  core/ui/                      tokens, backdrop, reusable components
android/app/src/main/
  kotlin/dev/qnzapret/           Kotlin VPN/strategy/Telegram runtime
  jni/                          MIT hev-socks5-tunnel + vendored dependencies
linux/
  runner/                       GTK runner + async D-Bus bridge
  runtime/                      system daemon, user sidecar, compiler, tests
  components/                   updater, Telegram adapter, Python tests
runtime/
  assets/qnzapret/               lists, payloads, Linux binary/Lua, provenance
  telegram/flowseal/proxy/       unmodified upstream Python headless core
packaging/linux/                 DEB/RPM, systemd, D-Bus, Polkit, lifecycle
windows/                        Flutter C++ runner; no real runtime adapter
test/                          Dart/widget tests + Linux integration scripts
docs/                          technical documentation and QA report
qndocs/                        separate Git submodule: branch regulations/prompts
.github/workflows/              Linux packaging and Windows build checks
```

## Flutter

`main.dart` поднимает preferences и выбирает runtime через
`createDefaultProxyRuntime()`: Android/Linux adapters, Windows stub.
`app.dart` читает тему; `app_shell.dart` содержит PageView и адаптивные Settings.

| Зона | Ответственность |
| --- | --- |
| `proxy_runtime.dart` | DTO, strategy profile, events, `ProxyRuntime`, optional `TelegramProxySetupRuntime`, stub |
| `android_proxy_runtime.dart` | Android MethodChannel/EventChannel; snapshots и lifecycle logs |
| `linux_proxy_runtime.dart` | Linux MethodChannel/EventChannel и команда настройки Telegram |
| `proxy_runtime_controller.dart` | busy/error, snapshot, initialize/prepare/start/stop/refresh/setup |
| `runtime_controller.dart` | Riverpod, UI-команды, статусы, журнал на 240 записей |
| `runtime_view_models.dart` | Человекочитаемые состояния и честная готовность перехвата |
| `app_settings_controller.dart` | Режим темы, палитра, последняя вкладка |
| `app_theme.dart`, `app_motion.dart`, `design_tokens.dart` | Цвета, типографика, motion, размеры |

Home читает provider и вызывает application-команды; прямых D-Bus/platform
вызовов в widgets быть не должно. Logs объединяет controller events и Linux
native events. Settings использует системную тему по умолчанию, light/dark
варианты шести палитр и отдельную страницу на телефонах/панель на desktop.
Брендинг находится в `assets/branding/`; platform icons — в ресурсах runners.

## Android

| Файлы/группа в `kotlin/dev/qnzapret` | Назначение |
| --- | --- |
| `MainActivity`, `ProxyRuntimeBridge` | VPN consent, notifications permission, MethodChannel |
| `QnzapretVpnService`, `QnzapretVpnRuntimeStore`, `VpnRuntimeConfig` | Foreground lifecycle, state, config |
| `QnzapretAndroidRuntime`, `LocalStrategyProxy`, `StrategySocks5Server` | Strategy runtime и protected SOCKS5 relay |
| `StrategyProfile`, `StrategyRuntimePlan`, `StrategyRuntimeEngine` | Codec, plan, L7 decisions |
| `StrategyAssetStore`, `StrategyAssetVerifier`, `HostlistMatcher`, `CompactHostlistIndex`, `L7Detectors` | Assets, memory-mapped exact index и detection |
| `TunTransport`, `TProxyService`, `UnderlyingNetworkSelector` | TUN, JNI, исходящая сеть/DNS/IPv6 |
| `TlsRecordSplitTransform`, `QuicHostCorrelation` | TLS split, DNS/prior-TCP correlation |
| `TelegramCompatibilityProxyManager`, `TelegramMtProxyCrypto`, `TelegramSetupHealth`, `TelegramSetupActivity` | MTProxy, setup и notification action |
| `TelegramWebSocketTransport`, `TelegramRouteConfigProvider`, `TelegramCloudflareResolver` | WSS routes, cache, scoring, pool, DNS |
| `TelegramWebSocketReader`, `TelegramPendingResponse` | Потоковое чтение больших WS frames и watchdog без ложных idle timeout |
| `TelegramGatewayProbe` | Анонимная проверка публичных key sets CDN/master без входа и без изменения auth клиента |
| `Socks5RelayClient`, `StrategyProfileDevOverrides` | Optional remote relay и локальный smoke config |
| `AndroidNetworkSelfTest` | Фоновая диагностика из UID приложения после runtime ready |
| `QnzapretQuickSettingsTileService` | Native QS start/stop |

`IpPacketCodec` и `TcpRelayState` сохраняются с unit-тестами, но активный TUN
переносит `hev-socks5-tunnel`; они не заменяют его production path.
Android assets монтируются из `runtime/assets` в Gradle. Сгенерированные
registrants и временные build-каталоги вручную не редактируются.

## Linux

- `runner/linux_proxy_runtime_plugin.cc`: async GIO, кэш snapshot,
  Start/Stop transaction, Telegram setup через D-Bus Open/URI fallback.
- `runtime/runtime_daemon.cc`: Polkit, root runtime, обновления, NFQUEUE,
  nft transaction, worker watch, cleanup и component dry-run validation.
- `runtime/runtime_contract.*`: allowlist compiler, nft rules, redaction.
- `runtime/telegram_sidecar.cc`: непривилегированный supervisor и health.
- `runtime/component_paths.h`: trusted component path с учётом user namespace.
- `runtime/telegram_state_directory.h`: systemd/XDG state path.
- `components/component_updater.py`: проверенная загрузка двух upstream.
- `components/qnzapret_telegram.py`: secret-file, redaction, WS/H2 health
  отдельно от unmodified `runtime/telegram/flowseal/proxy/`.
- `components/telegram_cdn.py`: выбор отдельного CDN203 gateway по публичным
  nonce-matched replies; не выполняет аутентификацию за клиента.

CMake собирает GUI, два supervisor executable и native tests; CTest также
запускает Python tests. Packaging размещает bundle в `/opt/qnzapret`, helpers
в `/usr/libexec`, assets в `/usr/lib/qnzapret`; обновления — в `/var/lib`.

## Тесты и источники

- `test/`: Dart API/controller/view model, widget/theme/motion regressions.
- `linux/runtime/*test.cc`: compiler/nft/state paths.
- `linux/components/*_test.py`: update trust/integrity/fallback/redaction.
- `test/integration/linux_netns_runtime_test.sh`: изолированный root netns test.
- `test/integration/linux_real_strategy_matrix.sh`: отдельный root real-network
  поиск стратегии; требует остановленного QNZapret.
- `android/app/src/test/`: Kotlin/JVM codec, relay, transport и health tests.
- `runtime/assets/qnzapret/provenance.json`, `manifest.sha256`: происхождение
  и целостность поставки; сторонние LICENSE не переписываются.

`packaging/android/build_hostlist_indexes.py` генерирует compact hostlist assets
в build/app/generated/compactHostlists; они не являются второй ручной копией
canonical списков. SHA256 manifest связывает generated index и source list.

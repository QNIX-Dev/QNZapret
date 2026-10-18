# Разработка и проверки

## Подготовка

Правила репозитория — в [AGENTS.md](../AGENTS.md), карта кода — в
[architecture.md](architecture.md). Работайте в feature-ветке; commit/push
только по просьбе пользователя. Сначала меняется контракт, затем native adapter,
состояние и UI. При работе с GitHub используйте `gh`.

```bash
git submodule update --init --recursive
flutter pub get
flutter devices
```

Dart constraint — `^3.11.4`, точные Dart-зависимости фиксирует `pubspec.lock`.
Android использует Java/Kotlin target 17, Gradle 8.14, AGP 8.11.1, Kotlin
2.2.20; SDK/NDK versions берутся из Flutter. Нужен Python 3 для генерации
hostlist-индексов. Gradle ограничен heap 3 GiB и двумя workers; тяжёлые сборки
и emulator smoke на ограниченной памяти лучше выполнять последовательно.

На Windows Flutter plugins требуют symlink support. При соответствующей
ошибке откройте `Start-Process 'ms-settings:developers'`, включите режим
разработчика и повторите pub get. Уведомления о доступных новых пакетах сами
по себе не означают ошибку разрешения зависимостей.

JNI submodule `hev-socks5-tunnel` закреплён на `2.19.0`. Не правьте исходники
внутри него: изменения интегрируются через fork/обновление pin. При смене pin
сверяют JNI с `TProxyService.kt`, вложенные submodules, `REV_ID` в Gradle и
[JNI provenance](../android/app/src/main/jni/provenance.json).

## Команды сборки и тестов

Обязательная базовая проверка из корня:

```bash
flutter analyze
flutter test
```

Для изменений Dart дополнительно проверьте форматирование:

```bash
dart format --output=none --set-exit-if-changed lib test
```

Android (при наличии Gradle wrapper):

```bash
cd android
./gradlew :app:testDebugUnitTest
cd ..
flutter build apk --release
flutter build appbundle --release
flutter run -d <DEVICE_ID>
```

В PowerShell из `android/`: `.\gradlew.bat :app:testDebugUnitTest`.
Файлы wrapper исключены текущим `android/.gitignore` и могут отсутствовать в
свежем checkout: сначала восстановите tooling, затем выполняйте команды.
Не считайте отсутствие wrapper результатом Kotlin-тестов.

Release пока подписывается debug key. Для отдельной установки без удаления
старого приложения есть Preview: `./gradlew :app:assembleRelease -PqnzPreview=true`.
Пакет `dev.qnzapret.preview` и его данные отделены от `dev.qnzapret`.
Не удаляйте рабочую установку и её данные ради несовместимой подписи сборки.

Linux — на Linux-хосте:

```bash
python3 -B -m unittest discover -s linux/components -p '*_test.py'
flutter build linux --release
ctest --test-dir build/linux/x64/release --output-on-failure
bash packaging/linux/tests/verify_packaging.sh
```

Python updater тесты используют POSIX-модули (`fcntl`, `pwd`), поэтому весь
этот suite не предназначен для нативного Windows Python. Пакеты и зависимости
сборки описаны в [linux.md](linux.md#сборка-и-пакеты).
Windows shell: `flutter build windows --release` на Windows-хосте; сетевого
runtime это не добавляет.

## Что покрывают тесты

| Область | Проверки |
| --- | --- |
| `test/core/backend/` | Сериализация, adapters с подменёнными каналами, события, lifecycle polling, ошибки |
| `test/core/state/` | Вычисление статусов, partial failure и readiness |
| `test/widget_test.dart`, `test/app/`, `test/core/ui/`, `test/core/motion/` | Экраны и размеры, темы, анимации, reduced motion, прокрутка |
| `android/app/src/test/` | Profile/engine, compact indexes, codecs, split/relay, Telegram crypto/transport/setup helpers |
| `linux/runtime/*test.cc` | Compiler/firewall contract и state directory |
| `linux/components/*_test.py` | Updater, Telegram adapter и CDN validation |
| `packaging/linux/tests/verify_packaging.sh` | Units, paths, lifecycle scripts, package versions и native state-directory test |

Flutter tests с подменённым MethodChannel не компилируют Kotlin и не доказывают
работу native VPN. Unit-тесты также не заменяют проверку DPI в реальной сети.

## Android smoke

При изменении runtime нужен device smoke: consent успех/отказ, Start/Stop/revoke,
конкурирующий VPN, TUN/forwarder readiness, DNS/Private DNS, IPv4-only/IPv6,
HTTP/TLS/QUIC, background, notification и Quick Settings.

Для отдельного эмулятора есть account-free сценарии:

```bash
python3 test/integration/android_qs_vpn_regression.py --serial emulator-5554 --apk <APK>
python3 test/integration/android_runtime_smoke.py --serial emulator-5554 --apk <APK>
python3 test/integration/android_telegram_smoke.py --serial emulator-5554
```

Они используют отдельный [VPN fixture](../test/integration/android_vpn_fixture/README.md),
отвергают физический телефон и меняют тестовые appops/QS. HTTPS из отдельного
UID проверяет `android_device_https_smoke.py`; физическое устройство требует
его явного opt-in. Для установки/adb всегда указывайте нужный serial.

Telegram проверяется по уровням: listener, setup delivery/confirm, handshake,
живой bridge, text, media download, voice/video и file upload. Отдельно нужны
public routes без override, local config, cooldown/pool/watchdog и Stop.
Не используйте личные аккаунты/сообщения как автоматические fixtures.
Логи и порядок диагностики — в [android.md](android.md#диагностика).

## Linux smoke и пакеты

```bash
sudo test/integration/linux_netns_runtime_test.sh
```

Скрипт требует установленного worker account, собранного runtime и Linux
network tools. Без нужных условий возвращает 77 (skip). В отдельных netns
проверяет HTTP/TLS, counters/L7, аварийную очистку и сохранение чужой nft table.
Он не подтверждает ISP DPI, QUIC или доступность текущих Telegram endpoints.
`linux_real_strategy_matrix.sh` — отдельная проверка в реальной сети; требует
пакет, свежие native tests, root через sudo и остановленный runtime.

На установленном DEB/RPM обычным пользователем проверяют Start, отмену Polkit,
повторные команды, fallback при недоступном GitHub/несовместимом обновлении,
queue+rules, реальные HTTP/HTTPS/QUIC, Telegram setup и live bridge, rollback
sidecar, worker crash, GUI restart, upgrade/reboot/uninstall.

Проверки содержимого и metadata пакетов:

```bash
desktop-file-validate packaging/linux/dev.qnzapret.desktop
appstreamcli validate --no-net packaging/linux/dev.qnzapret.metainfo.xml
systemd-analyze verify packaging/linux/systemd/qnzapret-runtime.service
systemd-analyze --user verify packaging/linux/systemd/qnzapret-telegram.service
dpkg-deb --info build/linux/packages/qnzapret_0.0.6_amd64.deb
rpm -qpl build/linux/packages/*.rpm
rpm -qpR build/linux/packages/*.rpm
(cd /usr/lib/qnzapret/runtime && sha256sum -c manifest.sha256)
```

Bundle с новой glibc нельзя объявлять проверенным для Debian 12 без отдельной
сборки/установки. Clean-VM smoke нужен для каждого заявленного дистрибутива.

## CI и изменение документации

[Linux workflow](../.github/workflows/linux-release.yml) собирает Debian 12
bundle, запускает Dart/native/Python tests и создаёт пакеты в Fedora container.
[Windows workflow](../.github/workflows/windows-release.yml) запускает
analyze/test/release build. Оба имеют path filters и workflow_dispatch;
наличие workflow не означает успешный прогон текущего commit. Отдельного
Android CI workflow сейчас нет.

При правках документации сверяйте утверждения с исходниками, локальные ссылки,
paths/команды и ссылки из packaging/systemd. Новые отчёты о разовых прогонах
не нужны: результат проверки указывается в PR/описании изменения. Не выдавайте
исторический smoke за новый. Недоступные проверки отмечайте явно.

## Известные проблемы исходников

При ревизии документации 18 октября 2026 года в Android обнаружены
незавершённые изменения. Это вывод из исходников, не результат Kotlin compiler:

- [QnzapretVpnService.kt](../android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnService.kt)
  содержит ссылки на необъявленные `operationGeneration`, `runtimeLock`,
  `lifecycleExecutor`, `diagnosticsExecutor`, `healthHandler`, `healthCheck`,
  `diagnosticTask`; внутри `buildString` остался второй блок запуска с
  `generation` и `return@execute` без соответствующего executor lambda.
- [QnzapretVpnRuntimeStore.kt](../android/app/src/main/kotlin/dev/qnzapret/QnzapretVpnRuntimeStore.kt)
  использует необъявленные `applicationContext`, `telegramSidecarState` и
  `publish`; bridge вызывает отсутствующие `addListener`/`removeListener`.
- Telegram reader/gateway helpers и public route seed присутствуют, но это
  не подтверждает их интеграцию в live path. Текущий transport ограничивает
  route candidates DC 1–5; старые отчёты о CDN203 не описывают этот код.
- Android manager выдаёт ready уже для listener, Linux — для живого bridge.
  Общий UI приоритетно проверяет ready, поэтому Android может преждевременно
  показывать «Подключён», несмотря на setupRequired.

Эти участки требуют отдельного исправления с Kotlin build и device smoke.
Переработка документации не меняет runtime и не устраняет эти дефекты.

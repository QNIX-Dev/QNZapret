# Разработка, QA и Git

Срез ветки `quriee`, 12 октября 2026 года. Вход для агента — [AGENTS.md](../AGENTS.md).

## Порядок изменения

1. Определить ветку, прочитать её регламент и проверить незакоммиченные изменения.
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
- Android: Java/Kotlin target 17, Gradle 8.14, AGP 8.11.1, Kotlin 2.2.20;
  compile/target/min SDK и NDK берутся из Flutter tooling.
- `android.builtInKotlin=false`, `android.newDsl=false` добавлены Flutter
  migrator для текущего Android build. Предупреждения о будущей поддержке
  Gradle/AGP/Kotlin не равны ошибке прошедшей сборки.
- Android release пока подписывается debug key. Версии Android/UI (`0.0.1f`,
  build 1) и Flutter/Linux (`0.0.6+6`) ещё не согласованы.
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

## Git и подмодуль

Работать в пользовательской ветке, сохранять чужие изменения. Коммит — после
верификации и разрешения пользователя; уже данное разрешение не спрашивать
повторно. Push/PR — когда нужна публикация. В `main` изменения только через PR;
merge и переписывание истории требуют отдельного разрешения.

`qndocs` — самостоятельный Git-репозиторий. При его изменении:

1. Создать/выбрать рабочую ветку в подмодуле, не коммитить detached HEAD.
2. Проверить и закоммитить docs в подмодуле.
3. Закоммитить его новый pointer в основном проекте.
4. До публикации основного commit обеспечить доступность commit подмодуля
   на remote; для docs `main` предпочитать branch + PR.

Локальный commit подмодуля без push допустим для локальной работы, но другой
clone не сможет получить его, пока он не опубликован. Это нужно сообщать в
handoff. Не подменять обновление pointer копированием qndocs в обычный каталог.

# QNZapret: обзор проекта

Состояние ветки `quriee` на 12 октября 2026 года.

## Цель

Приложение для локального обхода региональных ограничений с понятным запуском,
диагностикой и современным Flutter-интерфейсом. Обход зависит от сети, протокола
и правил провайдера. Состояние процесса не подменяет готовность перехвата и
доступность конкретного ресурса.

## Что реализовано

- Общий Dart API и Riverpod application layer для Home, Logs и Settings.
- Android: Kotlin `VpnService`, TUN-to-SOCKS через MIT `hev-socks5-tunnel`,
  локальный strategy SOCKS5 proxy, protected TCP/UDP, TLS record split,
  best-effort fake/QUIC correlation, notification actions и QS tile.
- Linux: асинхронный C++/GIO bridge, system D-Bus daemon с Polkit, NFQUEUE 200,
  атомарная `inet qnzapret`, непривилегированный Telegram sidecar, DEB/RPM,
  обновление официальных zapret2/tg-ws-proxy при Start.
- Telegram: отдельный Kotlin MTProxy/WSS на Android и headless Flowseal
  Python core на Linux. Локальный профиль требует штатного подтверждения в
  Telegram; Linux умеет доставлять его и до входа в аккаунт.
- Windows: Flutter runner, но runtime остаётся stub и обход не выполняет.
- Системная/светлая/тёмная тема, палитры Monokai, Gruvbox, Catppuccin, Nord,
  Everforest, Rose of Dune; настройки и последняя вкладка сохраняются.
- Linux native-события попадают в UI-журнал; Android подробные логи пока
  доступны через logcat, `AndroidProxyRuntime.events` пуст.

## Архитектура и ответственность

`UI -> RuntimeController -> ProxyRuntimeController -> ProxyRuntime -> platform adapter`.

Android backend написан на Kotlin, Linux daemon/sidecar supervisor — на C++,
загрузчик и Telegram-адаптер — на Python. Go-backend к приложению не подключён.
Исторические `.sources` не являются production-кодом.

`runtime/assets/qnzapret` — общий источник hostlists/payloads, а также поставки
Linux binary/Lua. Android Gradle подключает `runtime/assets` без второй копии
asset tree. Linux имеет собственный allowlist compiler; Android socket-стратегия
не считается эквивалентом raw-пакетной обработки nfqws2.

## Подтверждено и не подтверждено

12 октября Linux DEB проверен на Ubuntu 26.04: Polkit, NFQUEUE, Google/YouTube
HTTP 200, MTProto+WS bridge, загрузка компонентов и аварийная очистка в netns.
Linux и Android release собираются. Результаты — в
[отчёте проверки](linux-repair-2026-10-12.md).

Старые handoff содержали Android/Fedora smoke. Это исторические результаты;
они не подтверждают текущий пакет на этих устройствах. Нужны новые Android
device smoke, Windows build и clean-VM smoke Debian/Fedora перед релизом.

## Следующий этап

После фиксации текущего среза начинается [обновление Android non-root](android_next_stage.md):
полный цикл доработки, сборка APK/AAB и подготовка тестирования на смартфоне.
Первый известный дефект — открытие шторки с QS плиткой завершает другой VPN
даже без нажатия. Это пользовательское наблюдение старой сборки, которое
нужно воспроизвести; причина пока не установлена.

## Оставшаяся работа

- Android: device smoke text/media/upload/background/notification, native log
  stream, backpressure/лимиты/counters, диагностика QUIC и production signing.
- Telegram: подписанный канал данных маршрутов пока не реализован; публичные
  Android CF defaults зависят от внешнего upstream и могут перестать работать.
- Android `telegramCompatibilityProxyReady` ещё означает listener; Linux —
  живой bridge. Общая UI-подпись требует дальнейшего выравнивания Android
  семантики, чтобы не принимать listener за подтверждённое соединение.
- Remote relay: SOCKS5 есть, HTTPS CONNECT и production secret storage — планы.
- Linux: clean-VM/upgrade/reboot/uninstall/QUIC smoke; отдельная проверка новых
  поколений компонентов на реальных сетях. Обновлятор не заменяет QA релиза.
- Windows: нативный adapter и упаковка runtime.
- Согласовать версии: `pubspec.yaml` и Linux packages — `0.0.6`, Android
  metadata и `app_metadata.dart` — `0.0.1f`, build `1`.
- Определить лицензию собственного кода и уточнить происхождение старых списков.

Эти пункты описывают оставшуюся работу, а не поручение начать её автоматически.

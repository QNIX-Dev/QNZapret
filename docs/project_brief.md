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
  локальный strategy SOCKS5 proxy, protected TCP/UDP,
  TLS record split/UDP fake/QUIC correlation, notification actions и QS tile.
- Linux: асинхронный C++/GIO bridge, system D-Bus daemon с Polkit, NFQUEUE 200,
  атомарная `inet qnzapret`, непривилегированный Telegram sidecar, DEB/RPM,
  обновление официальных zapret2/tg-ws-proxy при Start.
- Telegram: отдельный Kotlin MTProxy/WSS на Android и headless Flowseal
  Python core на Linux. Локальный профиль требует штатного подтверждения в
  Telegram; Linux умеет доставлять его и до входа в аккаунт.
- Windows: Flutter runner, но runtime остаётся stub и обход не выполняет.
- Системная/светлая/тёмная тема, палитры Monokai, Gruvbox, Catppuccin, Nord,
  Everforest, Rose of Dune; настройки и последняя вкладка сохраняются.
- Linux native-события попадают в UI-журнал; Android snapshot/lifecycle события также поступают в UI; подробные сетевые
  логи доступны через logcat.

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

[Обновление Android non-root](android_next_stage.md) выполняется после Linux-среза:
доработаны lifecycle, JNI, события, Telegram setup и память hostlists, собраны APK/AAB.
Причина дефекта шторки найдена: чтение snapshot вызывало `VpnService.prepare`,
способный отзывать чужой VPN. В read-only пути теперь только cached consent;
конкурирующий VPN сохраняется при отрисовке QS и открытии GUI в QA эмуляторе.

## Оставшаяся работа

- Android: device smoke text/media/upload/background/notification,
  backpressure/лимиты/counters, диагностика QUIC и production signing.
- Telegram: подписанный канал данных маршрутов пока не реализован; публичные
  Android CF defaults зависят от внешнего upstream и могут перестать работать.
- Android Telegram readiness выровнена с Linux по живому bridge; необходим
  device smoke actual text/media/upload на смартфоне.
- Remote relay: SOCKS5 есть, HTTPS CONNECT и production secret storage — планы.
- Linux: clean-VM/upgrade/reboot/uninstall/QUIC smoke; отдельная проверка новых
  поколений компонентов на реальных сетях. Обновлятор не заменяет QA релиза.
- Windows: нативный adapter и упаковка runtime.
- Версии нового Android build берутся из Flutter metadata (`0.0.6+7`);
  подпись пока debug. Согласовать production signing/upgrade перед публикацией.
- Определить лицензию собственного кода и уточнить происхождение старых списков.

Эти пункты описывают оставшуюся работу, а не поручение начать её автоматически.

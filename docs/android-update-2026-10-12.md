# Android non-root: обновление 12 октября 2026

Исходная точка `8c18269`, ветка `quriee`. Этот отчёт описывает конкретный
локальный прогон, а не универсальную гарантию обхода на всех сетях.
Android-изменения после исходной точки пока находятся в рабочем дереве.

## Изменения

- Устранён побочный эффект чтения snapshot: `VpnService.prepare` вызывался при
  отрисовке QS/GUI и мог отзывать другой VPN. В read-only пути используется
  последнее известное согласие; право перепроверяется при явном запуске.
- Startup перенесён в lifecycle executor с отменой устаревших операций.
  Self-test выполняется после готовности асинхронно; Stop/revoke отменяют его.
  Ошибку запуска больше не затирает успешное `idle` из onDestroy.
- HEV обновлён с 2.14.4 до официального MIT 2.18.0; JNI Start/Stop/isRunning
  согласованы с Kotlin, добавлена проверка живого worker. Полное происхождение
  исходников и nested dependencies — `android/app/src/main/jni/provenance.json`.
- Android EventChannel передаёт snapshot/lifecycle events в Flutter. Подробная
  сетевая диагностика остаётся в logcat.
- Telegram setup открывается повторной кнопкой Home и notification action.
  «Подключён» требует живого bridge, а не одного listener/handshake. Route data
  ограничены по размеру и времени загрузки, cache сохраняется атомарно;
  bundled bootstrap взят из Flowseal v1.11.2 с provenance/SHA256. Linux Python
  core не выдаётся за обновлённый Kotlin transport. Произвольный исполняемый
  код на Android не скачивается; JNI обновляется со сборкой APK.
- General hostlist с 1,29 млн записей теперь читается как точный UTF-8 mmap
  index: runtime не создаёт миллионный Java HashSet. Python 3 нужен только
  при разработческой сборке индекса, проверяется integrity private cache.
- UDP association получили отдельные sockets: ответы параллельных DNS/QUIC
  к одному endpoint больше не должны перезаписывать получателя друг друга.
  Лимит — 64 association, lifetime задаётся управляющим SOCKS5 TCP-каналом.
- Найдена причина зависаний HTTPS: TCP FAKE записывался перед реальными данными
  в тот же stream. Низкий TTL не отменяет TCP sequence/retransmission semantics
  ([RFC 9293](https://www.rfc-editor.org/rfc/rfc9293.html#section-3.10.8)).
  Удалена некорректная stream injection; TLS record split сохранён,
  неподдержанный TCP FAKE явно пропускается с `raw_injection_unavailable`.
  Records отправляются раздельно, с TCP_NODELAY и зазором 10 мс, вместо
  объединённого write, сохранявшего короткий ClientHello в одном TCP segment.
  Дополнительно SNI делится между writes без изменения handshake bytes;
  одной границы после handshake type недостаточно для некоторых фильтров.
- Android version metadata использует Flutter `0.0.6+6`. Gradle ограничен
  3 GiB heap и двумя workers, Kotlin compiler работает внутри того же процесса.

## Проверено

- Flutter analyze: без замечаний; Flutter tests: 43 passed.
- Android JVM: 57 tests, 0 failures/errors/skips.
- Shared Linux release build и 3 CTest: passed.
- Release universal APK и AAB собраны; arm64-v8a/armeabi-v7a/x86_64,
  minSdk 24/targetSdk 36; APK v2 signature проверена.
- Отдельный Preview APK имеет applicationId `dev.qnzapret.preview`, название
  «QNZapret Preview». Kotlin/JNI namespace остаётся `dev.qnzapret`.
- Финальный APK сохранил конкурирующий VPN после фактической отрисовки QS
  (`ACTIVE`). GUI snapshot проходил на промежуточном APK; повтор финального
  GUI прерван падением графики эмулятора, поэтому итоговый QS-прогон явно
  использует `--skip-gui` и не объявляет этот сценарий повторно проверенным.
- Финальный release TUN/JNI стартовал за 2,1 с в ограниченном QA эмуляторе.
  Две SOCKS5 UDP association получили свои ответы от одного endpoint в
  обратном порядке. HTTPS из отдельного UID: example.com, Google, YouTube —
  HTTP 200 с чтением body. Stop освободил VPN, после 2 с он не воскрес.
- Native notification action передал валидный `tg://proxy` URI тестовому
  receiver. Синтетический MTProxy init для DC 2 установил WSS bridge без
  аккаунта/сообщений. Это подтверждает setup/handshake, а не text/media/upload.
- С разрешения пользователя Preview установлен рядом со старой версией на
  Pixel 9, запуск Activity подтверждён. Старая версия/данные сохранены; её
  подпись отличается от локальной, обычное обновление невозможно без ключа.
  С отдельного явного разрешения проведён краткий HTTPS smoke: Google ответил
  HTTP 200, YouTube получил deadline timeout. Preview остановлен кнопкой UI,
  `Active vpn type: -1` подтверждён; временный HTTPS-only probe удалён.
  Личные Telegram-аккаунты не использовались. Последний SNI-вариант отдельно
  проверяется на эмуляторе; результат прежнего APK ему не приписывается.

Окончательные результаты и границы применимости фиксируются в `test-results.json`
рядом с артефактами. Физический телефон на момент финальной установки был
отключён: промежуточный Preview установлен, финальный APK готов, подтверждение
его установки отдельно хранится в `android-phone-install.json` при доступном ADB.
На этом хосте QA эмулятор нестабилен: графический процесс падает с SIGSEGV
при обновлении GLES context. Ошибка эмулятора не считается успешным тестом
приложения. Один ранний прогон при прежнем большом Gradle heap вызвал общий
OOM; heap уменьшен, новые эмуляторы ограничены отдельным cgroup.

## Установка и ручная приёмка

Артефакты находятся вне Git: `../QNZapret-artifacts/android-2026-10-12/`.
Там APK/AAB, Preview APK, SHA256SUMS, build-info и инструкция проверки.
Использовать Preview при несовместимой подписи; старое приложение не удалять.
Preview имеет отдельные настройки. Старую QS плитку нужно убрать вручную:
её код остаётся старым и сохраняет дефект; добавить плитку именно Preview.

Пользователю проверить Start/Stop/restart, другой VPN при открытии шторки,
обычные сайты/Google/YouTube, Telegram setup до/после входа, text/photo/video/
voice/circle/file upload, Wi-Fi/mobile, IPv6/Private DNS и background/notification.
Android допускает только один обычный VPN: явный Start может заменить другой.
Эмулятор использует сеть хоста с действующим Linux обходом, поэтому даже
успешный datapath не доказывает независимый обход DPI на мобильной сети.

## Оставшиеся ограничения

Production signing, подписанный канал route config, полноценные counters/
backpressure и проверка всех сценариев на реальных сетях ещё нужны.
WSS routes зависят от публичного upstream; text/media/upload и voice нельзя
объявлять проверенными по одному соединению или факту сборки.
Debug signature в release APK не предназначена для публикации в магазине.
Локальный commit qndocs `a194b14` и parent `8c18269` не опубликованы;
перед публикацией parent требуется доступность commit подмодуля.

Сценарии: [план и критерии](android_next_stage.md),
[handoff](android_runtime_handoff.md), [QA](integration_workflow.md).

## Git handoff

Android-исправления, HEV vendor update/provenance, mmap index builder,
регрессионные JVM/Dart/device fixtures и актуальные Android docs сохранены
незакоммиченными в `quriee`. Предлагаемые сообщения следующих локальных commits:
для `qndocs` — `docs: синхронизировать регламенты с Android non-root`, затем
для parent — `fix: обновить Android non-root runtime и подготовить APK`.
Перед parent commit сначала фиксируется новый commit подмодуля и его pointer;
публикация/push не выполнялись и требуют отдельной задачи.

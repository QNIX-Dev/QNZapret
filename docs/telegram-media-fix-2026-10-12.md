# Telegram media: Linux и Android, 12 октября 2026

Ветка `quriee`, исходная точка `8c18269`, новые изменения не закоммичены.
Android: `0.0.6+7`, обычный пакет и совместимый Preview. Этот срез дополняет
[предыдущее обновление Android](android-update-2026-10-12.md).

## Симптом и установленные причины

Пользователь подтвердил проблему Android в Wi-Fi и мобильной сети. На Linux
после первых исправлений стикеры/воспроизведение улучшились, но сохранение
полного файла оставалось медленным. Эти сценарии проверяются раздельно.

- Android не принимал logical DC203. Это отдельный CDN, а не обычный DC2.
- WS reader ждал весь кадр и отклонял размер больше 1 MiB. Файловый ответ с
  MTProto framing может превысить этот порог; медленный приём не отражался
  в progress до окончания кадра.
- Watchdog мог закрывать idle/медленную media session через 10 секунд,
  переключать signed media marker и штрафовать успешный маленький стикер
  по средней скорости вместе с последующим idle.
- Нормальный Close/EOF upstream мог оставлять локальный TCP открытым без
  читающего upstream worker; закрытие самим клиентом ошибочно ухудшало score.
- Проверка HTTP 101/открытого bridge не обнаруживала неверный DC. Wire probe
  без аккаунта показал одинаковые master RSA fingerprints при DC2 и DC203
  через alias `kws2.web.telegram.org`, но другой набор через `kws203.<base>`.
  Такое сопоставление CDN с обычным DC исключено на обеих платформах.

Основание для разделения серверов: [Telegram CDN](https://core.telegram.org/cdn).
Для проверки используется только [req_pq_multi/ResPQ](https://core.telegram.org/mtproto/auth_key),
без завершения DH/auth key, логина, сообщений или доступа к файлам аккаунта.

## Окончательная реализация

Android `TelegramWebSocketReader` передаёт данные порциями до 32 KiB, не ждёт
целого кадра; лимит логического кадра 16 MiB не выделяется целиком. Сохранены
mask offset, continuation и Ping/Pong. Idle и продолжающаяся загрузка не
прерываются из-за скорости; watchdog относится к unanswered request 30 секунд.
Исходный signed DC marker не переписывается. Upstream EOF закрывает клиентский
TCP для retry; нормальное завершение клиентом не считается ошибкой шлюза.

CDN203 направляется на отдельный CF gateway. Анонимные ответы CDN/master
сравниваются по nonce и непересечению public key sets. Это **проверка
согласованности маршрута**, не замена аутентификации: официальные CDN RSA keys
по-прежнему проверяет Telegram. Probe использует отдельное соединение;
реальные данные никогда не получают уже использованный cipher stream.
На Android проверки одного host дедуплицированы, ограничены deadline и
кэшируются 15 минут. TLS verification сохраняется. Direct redirects DC2/DC4
сохраняют SNI/Host и используют TLS split для Telegram Web endpoints.

Linux `telegram_cdn.py` выбирает gateway с отдельными CDN keys, не отдаёт
непроверенные CDN routes и делит background probe между клиентами. При
отсутствии подходящего шлюза остаётся upstream raw TCP fallback. Warm WS pool
для обычных media ждётся не более секунды; отмена клиента не отменяет refill.
Upstream `proxy/` не изменялся, latest release остаётся [v1.11.2](https://github.com/Flowseal/tg-ws-proxy/releases/tag/v1.11.2).

Локальная настройка `telegram.transport.json` позволяет сравнить H2 media
fallback с WS, без изменения общей сети и secret. На текущем хосте проводилось
сравнение `h2Media=false`, затем восстановлена исходная настройка H2;
сравнение не получило подтверждённого результата и не доказывает превосходство
WS во всех сетях.
Текущее состояние и пользовательский результат отражаются в итоговом отчёте
артефактов. [Настройка и lifecycle](linux_runtime_handoff.md).

## Проверки

- Android JVM: 75 tests, 0 failures/errors; большие/частичные WS frames,
  mask/control frames, watchdog, сохранение marker, CDN route и ResPQ parser.
- Python: 32 tests, включая правильный CDN selection, bounds/nonce,
  отмену shared refill и настройки H2.
- Flutter analyze без замечаний. Полный Flutter suite: 42 passed, 1 failed:
  theme test не нашёл Manrope при запрещённой runtime-загрузке. Theme source
  и сам тест совпадают с HEAD; Telegram-код их не изменял. Это ограничение
  текущего QA, не объявленное успешным прохождение всего Flutter suite.
- Linux release и packaging прошли, CTest: 3 passed. Изолированный netns:
  counters 0 → 53, HTTP/TLS/L7, cleanup после crash и сохранение чужой table.
- Эмулятор: TUN/JNI, разделение UDP association, HTTPS к example.com,
  Google и YouTube прошли; остановленный service не воскресает.
- Telegram на эмуляторе: DC2, signed media -DC2 и CDN203 вернули nonce-matched
  ResPQ; CDN203 использует отдельные fingerprints. Аккаунт не использовался.
  Финальный прогон использовал сохранённый proxy profile после обновления;
  setup UI повторно не подтверждён из-за нестабильной автоматизации SystemUI.
  Реальные результаты — `test-results.json` артефактов.
- Установленный Linux proxy отвечает корректным nonce для DC2, -DC2 и DC203;
  у CDN203 теперь отдельный набор fingerprints. Секрет не выводился.

Проверка большого кадра в JVM — не замена измерению скорости настоящего файла.
Нужны повторные реальные download/playback/stickers и Android Wi-Fi/mobile,
а также upload/voice/background. Общие публичные gateways могут ограничивать
пропускную способность; код не обещает скорость, которой у них нет.

## Установка и handoff

Изменённый adapter и новый helper установлены на Linux с резервными копиями
в user state repair directory; основной NFQUEUE runtime не перезапускался.
Telegram secret/profile сохранены. Android артефакты находятся в локальном
`../QNZapret-artifacts/android-2026-10-12-telegram-media/`: обычный APK/AAB,
Preview APK, SHA256 и фактические результаты. Подпись остаётся локальной debug,
для прежней установки используется `dev.qnzapret.preview` без удаления данных.
Факт установки build 7 на телефон фиксируется отдельно; отсутствие ADB не
должно выдаваться за завершённое обновление.
На момент финальной проверки телефон отсутствовал в ADB; build 7 на него
не установлен. Preview APK подготовлен для обновления с сохранением данных.

Накопленные незакоммиченные изменения включают предыдущее Android runtime/
HEV/mmap обновление, эти media/CDN исправления, fixtures и актуальную документацию.
Предлагаемый parent commit: `fix: стабилизировать Android и Telegram media/CDN`.
Перед ним сначала фиксируются изменения `qndocs` и обновляется pointer;
push/публикация не выполнялись.

# QNZapret: восстановление Linux, 12 октября 2026

Исторический отчёт локального прогона до фиксации изменений.
Рабочая ветка: `quriee`, исходный commit `5b36133`. На момент прогона
коммит и публикация не выполнялись; актуальную историю смотреть в Git. Прежние локальные изменения `settings_screen.dart` и
`pubspec.lock` сохранены; Flutter автоматически добавил настройки миграции
анализатора и Android Gradle.

## Причины и изменения

- Telegram user unit на Ubuntu 26.04 завершался с `218/CAPABILITIES` до exec.
  Журнал AppArmor подтвердил запрещённый setpcap в unprivileged user namespace.
  Убран несовместимый `ProtectKernelModules`, явно задан `PrivateUsers=no`.
  Глобальная защита системы не менялась.
- GTK runner синхронно создавал D-Bus proxy, запрашивал snapshot/preflight и
  повторял RPC внутри обработчиков сигналов. Этот путь переведён на async GIO;
  события объединяют кэш snapshot. Polkit вынесен в отдельную задачу daemon.
- Убрана зависимость активации GUI/runtime от `network-online.target`.
- Встроены официальные zapret2 `v1.0.5.2` и tg-ws-proxy `v1.11.2`.
  Telegram `proxy/` совпадает с upstream побайтно; секреты и health обслуживает
  отдельный адаптер QNZapret, включая WS/H2 fallback.
- Добавлены автоматические обновления двух компонентов после Polkit при Start:
  HTTPS allowlist, SHA256/Git blob verification, лимиты загрузки и времени,
  проверка совместимости без root, atomic generations, сохранение предыдущего
  поколения и fallback к встроенной версии. Поставка `/usr/lib` не переписывается.
- Выбор кэша учитывает отображение root UID внутри user namespace sidecar.
- После запуска Telegram получает setup URI через стандартный D-Bus Open;
  есть fallback обработчика tg:// и кнопка «Настроить Telegram». Передача
  профиля работает на экране входа. Стандартное подтверждение Telegram
  сохраняется; личные данные не требуются и авторизация аккаунта не выполнялась.
- Listener больше не означает «Активен»: без bridge показывается «Нужна
  настройка»/«Ожидает Telegram», с живым bridge — «Подключён». После закрытия
  последнего bridge health возвращается к listener.

## Проверено

- `dart format`, `flutter analyze`: без ошибок.
- `flutter test`: 41 тест прошёл, включая статусы и optional setup capability.
- Python: 18 тестов URL trust, integrity, archive traversal, atomic install,
  rollback при отказе валидации, offline handling, запрета downgrade и
  маскирования secret/setup URI.
- CTest: все 3 проверки прошли — runtime contract, state paths, component updater.
- Release-сборки Linux и Android appbundle успешны.
- DEB и RPM собраны; DEB установлен на этой Ubuntu. Это локальная сборка,
  её glibc baseline соответствует хосту, а не Debian 12.
- Проверка systemd units и packaging прошла.
- Изолированный NFQUEUE test: HTTP/TLS проходят; counters `0 -> 53`, L7
  наблюдается; после аварии nfqws удалена только своя table, foreign table сохранена.
- GUI Start показал штатный Polkit prompt; runtime и sidecar запустились.
- С активным обходом Google и YouTube ответили HTTP 200.
- MTProto handshake и upstream WS bridge подтверждены синтетическим probe;
  отдельно профиль передан Telegram до входа, выполнен штатный Connect Proxy.
- Оба компонента реально скачаны, проверены и опубликованы в отдельном smoke
  хранилище за 4,79 с. Повторная проверка в системном кэше — 3,91 с.
- Новый daemon запущен обычным пользователем через Polkit за 5,29 с вместе с
  проверкой актуальности; owner UID — 1000. Фактический nfqws executable
  находится в `/var/lib/qnzapret/components/zapret2-v1.0.5.2-fb3bcf69e7d8`.
- Фактический PYTHONPATH sidecar указывает на
  `/var/lib/qnzapret/components/tg-ws-proxy-v1.11.2-db9048b56faa`; live bridge
  подтвердился после перезапуска с этим поколением.

Журналы локальной проверки: `build/verification/` (не входят в Git).
Резервная копия первоначально установленной версии:
`~/.local/state/qnzapret-repair/20261012-022007/installed-before.tar.gz`.

## Ограничения

Windows build на Linux недоступен; проверка Windows и clean-VM smoke
Fedora/Debian остаются условиями публичного релиза. Android собирается, но
проверки его runtime на телефоне в этой задаче не выполнялись.

Сообщение Flutter о выборе Impeller информационное. GTK также выдаёт
предупреждение о пустом имени cursor из Flutter; отказ запуска с ним не связан.

Автообновление доверяет официальному upstream и GitHub HTTPS/API; независимой
криптографической подписи авторов оно не добавляет. Несовместимый Lua API или
новые отсутствующие Python-зависимости приводят к пропуску обновления, а не к
установке непроверенного компонента. Подробности и восстановление описаны в
`linux_component_updates.md`.

Текущие инструкции и дальнейший Android этап находятся в [оглавлении](README.md).

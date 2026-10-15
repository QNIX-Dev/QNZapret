# Обновление компонентов Linux

## Границы

Обновляются `bol-van/zapret2` и headless `Flowseal/tg-ws-proxy`. Flutter GUI,
system daemon, стратегии, hostlists и настройки приложения
этот механизм не обновляет. Android и Windows не подключены к обновлятору
Linux; публичный Dart API не изменился.

Встроенные версии на 12 октября 2026: zapret2 `v1.0.5.2`, tg-ws-proxy
`v1.11.2`. Точные commit и SHA256 находятся в `provenance.json`.

## Запуск и отзывчивость

Runner использует только асинхронные GIO D-Bus вызовы. Сигналы обновляют
кэш snapshot; обработчик сигнала не вызывает вложенный синхронный RPC.
Polkit выполняется в отдельной задаче daemon: GetSnapshot доступен и во время
ожидания авторизации. GUI никогда не запускается от root.

Start активного runtime идемпотентен. Для остановленного runtime порядок такой:

1. Polkit проверяет реального unique sender system D-Bus.
2. Daemon асинхронно запускает установленный root-owned обновлятор.
3. Обновлятор проверяет stable latest release каждого фиксированного upstream.
4. Новые файлы загружаются во временную директорию, проверяются и тестируются.
5. Проверенная версия публикуется атомарной заменой symlink.
6. Daemon выполняет preflight, запускает NFQUEUE и собственную nft table.
7. Runner запускает Telegram sidecar. При отказе вызывает Stop system runtime.
8. Runner передаёт локальный профиль в Telegram через D-Bus Open (с fallback
   стандартного обработчика tg://). Пользователь подтверждает «Connect Proxy».
   Это работает на экране входа; личные данные и номер телефона не требуются.
   Для повторной передачи есть кнопка «Настроить Telegram».

Проверка каждого upstream ограничена 20 секундами; daemon дополнительно
ограничивает весь процесс 45 секундами. Нет сети, HTTP 429, неподдерживаемый
релиз, испорченный архив или провал проверки совместимости означают запись в
лог и запуск локальной версии. GUI продолжает обрабатывать ввод и анимации.
Каждый новый Start проверяет обновления; простое открытие GUI их не скачивает.

## Доверие и совместимость

Код обновлятора устанавливает системный пакет. GUI не может передать ему URL,
команду или путь. Разрешены только HTTPS и конкретные GitHub-хосты; redirect
проверяется тем же allowlist. Пользовательский HTTP proxy не наследуется.
Ограничены размеры архивов и распакованных данных, запрещены обходы путей;
файлы извлекаются вручную, архивные permissions и symlinks не применяются.

Для zapret2 обязательный SHA256 release asset берётся из официального GitHub
API и сверяется до распаковки. Копируются только x86_64 nfqws2, Lua-модули и
лицензия. Hostlists и payloads сохраняются из поставки QNZapret. Запуск
`--validate-component` выполняется с UID `qnzapret-runtime`, без root:
проверяются manifest, Lua API 6 и dry-run действительного production profile.

Для Telegram тег разрешается в commit, архив запрашивается по этому commit;
Git blob hash каждого Python-файла и LICENSE сверяется с GitHub tree API.
Копируется только `proxy/*.py`, без tray, GUI, setup scripts и upstream updater.
Проверяются синтаксис и контракт нашего отдельного адаптера, также без root.
Новые Python-зависимости автоматически через pip не устанавливаются: если
обновлению нужны отсутствующие зависимости, оно будет отклонено.

Это доверие официальным upstream и GitHub HTTPS/API, а не независимая подпись
авторов. Компрометация upstream или GitHub находится за границей этой модели.

## Хранение и восстановление

Поставка в `/usr/lib/qnzapret` остаётся неизменной. Загруженные поколения
находятся в root-owned `/var/lib/qnzapret/components/`; `runtime` и `telegram`
указывают на активные поколения. Manifest и `component.json` сохраняют состав,
версию, upstream и контрольные суммы. Предыдущий активный symlink сохраняется
как `runtime.previous` / `telegram.previous`.

При провале preflight загруженного runtime daemon использует встроенный.
После отказа регистрации NFQUEUE загруженное поколение не используется при
следующем Start в этом daemon. При раннем завершении загруженного Telegram
core sidecar повторяет запуск со встроенным core. Новый путь поколения снова
допускается к проверке. Неизвестный Lua API отклоняется до активации.

Для ручного возврата к поставке остановить сервисы, удалить только symlink
`runtime` и `telegram` в этой директории и перезапустить system daemon и user
sidecar. Каталоги поколений можно оставить для диагностики. Не удалять
`~/.local/state/qnzapret`: там находятся пользовательский secret и логи.

## Ubuntu и Telegram

На проверенной Ubuntu 26.04 с AppArmor `ProtectKernelModules=yes` в user unit приводит к
`218/CAPABILITIES` ещё до exec sidecar: запрещается setpcap в unprivileged
user namespace. В user unit явно установлен `PrivateUsers=no` и убран только
этот несовместимый параметр. Глобальные настройки AppArmor/userns не меняются;
остальные проверенные ограничения unit сохраняются.

systemd всё ещё может применять отдельный user namespace для filesystem
изоляции. В нём host root отображается overflow UID. Проверка владельца кэша
сравнивает его с владельцем корня filesystem в том же namespace, проверяет
закрытость записи для группы/остальных и ограничивает realpath системной
директорией компонентов. Это проверено в таком же user unit, как sidecar.

`qnzapret_telegram.py` отдельно читает secret file, маскирует secret/setup URI
в логах и пишет health marker. Upstream Python-код не патчится. Listener и
подтверждённый MTProto+WS/H2 bridge остаются разными состояниями. Зелёная
подпись «Подключён» требует живого bridge; при закрытии последнего соединения
health marker возвращается к listener. До подключения показывается «Нужна
настройка». Подключение профиля Telegram требует первоначального согласия пользователя в Telegram.

## Проверки

```
python3 -B -m unittest discover -s linux/components -p '*_test.py'
bash packaging/linux/tests/verify_packaging.sh
flutter analyze
flutter test
flutter build linux --release
ctest --test-dir build/linux/x64/release --output-on-failure
sudo test/integration/linux_netns_runtime_test.sh
```

Нативная сборка Windows и clean-VM smoke Fedora/Debian нужны отдельно перед
публичным релизом. Локальный DEB собирается под установленную Ubuntu; для
совместимости с Debian 12 использовать `build_bundle_debian12.sh`.

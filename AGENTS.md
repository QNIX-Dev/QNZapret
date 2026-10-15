# AGENTS.md

Инструкции для агентов и разработчиков в репозитории QNZapret.

## Что это за проект

QNZapret - кроссплатформенный Flutter-клиент (Android, Linux, Windows) для no-root перенаправления трафика и применения DPI-bypass стратегий. Основной путь - Android: локальный `VpnService` -> TUN -> `hev-socks5-tunnel` -> локальный strategy SOCKS5 proxy -> protected sockets. Удаленный VPN-сервер не нужен. Linux и Windows пока имеют только Flutter-раннеры и stub-адаптеры.

Общий обзор: `README.md`, `docs/project_brief.md`.

## Язык

Коммуникация, документация и комментарии вне кода - на русском. Идентификаторы и сообщения в коде - на английском, как сейчас.

## Структура

```text
lib/app/              app shell, навигация, тема
lib/core/backend/     Dart runtime contract (ProxyRuntime) и platform adapters
lib/core/state/       Riverpod state и view models
lib/core/ui/          design tokens и общие компоненты
lib/features/         экраны Home, Logs, Settings
android/app/src/main/kotlin/dev/qnzapret/   Android runtime, VPN service, strategy proxy, Telegram mode
android/app/src/main/jni/                   NDK-сборка; hev-socks5-tunnel - git submodule (MIT, закреплен на 2.14.4)
android/app/src/main/assets/qnzapret/       hostlists и strategy payloads
docs/                 архитектура, runtime contracts, handoff
test/                 Dart-тесты; Kotlin JVM-тесты лежат в android/app/src/test
```

## Архитектурные правила

- Работа идет по схеме contract first: сначала меняется `ProxyRuntime` contract, потом adapter, потом UI.
- UI не вызывает Android/Kotlin код напрямую. Экраны работают через `lib/core/backend/` и Riverpod-слой.
- Платформенные детали остаются внутри platform adapters; Android-специфика - в Kotlin service/bridge слое.
- Hostlists включают bypass-действия, а не ограничивают доступ: трафик вне списков идет direct через protected socket.
- Исходящие runtime-соединения Android открываются через `VpnService.protect(...)`, собственный пакет исключен из VPN, иначе трафик зациклится.
- Не переносить GPL-код (например, Android-обвязку ByeByeDPI). Сторонний код допустим только с совместимой лицензией (`hev-socks5-tunnel` - MIT).
- Не коммитить приватные route-домены, relay credentials и smoke-only secrets. Локальные конфиги (`telegram_compat.json`, `telegram_relay.json`) живут вне репозитория.
- Исходники `hev-socks5-tunnel` в репозиторий не вендорятся: правки вносим только через форк/обновление пина сабмодуля, а не правкой файлов внутри него.
- `.sources/` - reference-зона для исследований, не production-код и не в git.

## Изменение контракта

Если меняется wire protocol или lifecycle semantics, в одном изменении обновляются:

- код в `lib/core/backend/`;
- Android bridge (`ProxyRuntimeBridge.kt` и связанные классы);
- `docs/runtime_bridge_contract.md`;
- при необходимости `docs/android_runtime_handoff.md` и `docs/project_brief.md`.

## Команды

```bash
git submodule update --init --recursive   # hev-socks5-tunnel и его вложенные сабмодули
flutter pub get
flutter analyze
flutter test
cd android && ./gradlew :app:testDebugUnitTest
flutter build apk --release
flutter run -d android   # или linux / windows
```

Линтер: `flutter_lints` (`analysis_options.yaml`). Перед завершением работы запускайте `flutter analyze` и `flutter test`; при правках Kotlin - еще и `:app:testDebugUnitTest`.

Для изменений Android runtime юнит-тестов недостаточно: нужен device smoke. Logcat-теги: `QNZapretService`, `QNZapretTun`, `QNZapretProxy`, `QNZapretNetTest`, `QNZapretTgCompat`.

## Стиль

- Пишите в стиле окружающего кода: тот же уровень комментариев, именование и идиомы.
- Не добавляйте зависимости без необходимости; новые - только с обоснованием.
- Пользовательские строки UI, статусы и логи - на русском.
- Большие сгенерированные файлы (`assets/qnzapret/lists/*.txt`, `.bin` payloads) не редактируйте вручную.

## Git

- Коммитьте и пушьте только по явной просьбе.
- Основная ветка - `main`; работа идет в feature-ветках через PR.
- Не переписывайте историю общих веток.

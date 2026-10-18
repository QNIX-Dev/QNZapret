# QNZapret

Flutter-приложение для управления локальным обходом DPI на Android и Linux.
Общий интерфейс управляет нативными backend через Dart-контракт `ProxyRuntime`.
Удалённый VPN-сервер для основного пути не нужен.

| Платформа | Реализация |
| --- | --- |
| Android | `VpnService` → TUN → `hev-socks5-tunnel` → Kotlin SOCKS5 strategy proxy → protected sockets; отдельный Telegram MTProxy |
| Linux | C++/D-Bus daemon → nftables/NFQUEUE → `nfqws2`; отдельный пользовательский Telegram sidecar, DEB/RPM |
| Windows | Flutter-интерфейс и stub runtime; сетевой backend не реализован |

Telegram использует локальный proxy с подтверждением в клиенте и внешние
WebSocket/Cloudflare-маршруты. Hostlists включают действия обхода, а трафик вне
соответствующих правил проходит обычным путём.

## Документация

Начните с [архитектуры](docs/architecture.md): слои, пути трафика, карта исходников
и состав поставки. Остальные документы:

- [Android runtime](docs/android.md)
- [Linux runtime, обновления и пакеты](docs/linux.md)
- [Контракт Flutter/runtime](docs/runtime_bridge_contract.md)
- [Подготовка, сборка, тесты и известные проблемы](docs/development.md)

Документация описывает текущую реализацию, включая незавершённые участки.
Готовность конкретной платформы подтверждается её сборкой и smoke в реальной
среде; Flutter unit-тесты не проверяют нативный сетевой путь.

## Начало работы

```bash
git submodule update --init --recursive
flutter pub get
flutter analyze
flutter test
flutter devices
flutter run -d <DEVICE_ID>
```

Android требует SDK/NDK, Java и Python; Linux — нативный Linux toolchain.
Подробные команды и требования находятся в [руководстве разработчика](docs/development.md).
Правила внесения изменений — в [AGENTS.md](AGENTS.md), сторонние компоненты и
лицензионные ограничения — в
[THIRD_PARTY_NOTICES.md](runtime/assets/qnzapret/THIRD_PARTY_NOTICES.md).

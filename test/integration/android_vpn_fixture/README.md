# QS/VPN regression fixture

Минимальный отдельный VPN для изолированного Android эмулятора. Он поднимает
TUN только для тестовой подсети 10.253.0.0/24 и записывает ACTIVE/REVOKED/STOPPED;
не подключается к удалённому серверу и не обрабатывает личный трафик.

Из корня проекта:

```bash
python3 test/integration/android_qs_vpn_regression.py \
  --serial emulator-5554 --apk build/app/outputs/flutter-apk/app-debug.apk
```

Нужны Android SDK 36/build-tools, Java, существующий Android debug keystore,
запущенный эмулятор и свежий APK QNZapret. Скрипт отвергает физические устройства:
он меняет appops и layout QS только в тестовом эмуляторе. Проверяется сохранение
чужого VPN при отрисовке плитки и чтении GUI snapshot без user Start.
Fixture после теста останавливается, APK/журнал сохраняются в build/verification.

`android_runtime_smoke.py` проверяет release TUN/JNI, два независимых SOCKS5
UDP association к одному echo endpoint (ответы намеренно переставлены), HTTPS
из отдельного UID и Stop без самоперезапуска. Echo listener существует только
во время прогона, получает фиксированные тестовые строки. `NetworkProbeActivity`
открывает Google/YouTube без аккаунта; `TelegramUriReceiverActivity` принимает
только тестовый proxy URI, проверяет его поля и не выводит secret в журнал.
Эти инструменты предназначены исключительно для отдельного QA эмулятора.
`android_telegram_smoke.py` дополнительно проверяет native notification setup
и синтетический MTProxy/WSS handshake для DC 2, без аккаунта/сообщений; ему
нужен Python `cryptography`. Secret остаётся в private fixture storage и не
печатается. Эмулятор должен иметь уже установленный fixture, VPN — остановлен.

`android_device_https_smoke.py --serial <PHONE> --allow-physical-vpn` — отдельная
проверка физического телефона только после явного согласия и ручного Start
Preview. Она создаёт временный `dev.qnzapret.networkprobe` с единственным HTTPS
Activity, без VPN service/Telegram handler, отказывается перезаписывать уже
существующий package, затем удаляет только свой probe. Два URL ограничены
15 сек каждый; аккаунты не используются. Stop Preview выполняется отдельно
через UI. `--baseline` допустим только при уже остановленных VPN.

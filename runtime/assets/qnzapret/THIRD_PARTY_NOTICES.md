# Сторонние компоненты QNZapret

Состав поставки сверён 12 октября 2026 года. Точные версии, commits и SHA256
находятся в `provenance.json`, целостность файлов — в `manifest.sha256`.
Сторонние LICENSE сохранены в исходном виде.

## zapret2

Поставляются официальный Linux x86_64 `nfqws2` и Lua-модули
[bol-van/zapret2 v1.0.5.2](https://github.com/bol-van/zapret2/releases/tag/v1.0.5.2),
commit `6b6c63e3385fa73f8af3be4a69171e947f5a319d`.
Лицензия MIT: [полный текст](third_party/zapret2-LICENSE.txt).
Основной профиль загружает zapret-lib/zapret-antidpi; остальные Lua-модули
сохранены как часть upstream-поставки для совместимых поколений.

## tg-ws-proxy

Linux поставляет только headless `proxy/*.py` из
[Flowseal/tg-ws-proxy v1.11.2](https://github.com/Flowseal/tg-ws-proxy/releases/tag/v1.11.2),
commit `307a384ab7f522e34bb1a045884d0bd90f40bee5`.
Лицензия MIT: [полный текст](third_party/tg-ws-proxy-LICENSE.txt).
Upstream Python-файлы не изменены; upstream GUI/tray/updater не поставляются.
Отдельный `linux/components/qnzapret_telegram.py` читает secret-file,
маскирует secret/setup URI и наблюдает health WS/H2 bridges.
Автообновления Linux описаны в `docs/linux.md` основного проекта.

## Android TUN-to-SOCKS

Android NDK собирает MIT `hev-socks5-tunnel`, подключённый как git submodule в
`android/app/src/main/jni/hev-socks5-tunnel/`; его лицензия и лицензии вложенных
зависимостей находятся рядом с исходниками. Kotlin lifecycle/strategy/MTProxy
являются кодом QNZapret; GPL Android-обвязка reference fork не подключена.
Обновление Linux Python core не обновляет Kotlin автоматически.

## Старые strategy lists и payloads

Hostlists/payloads появились в QNZapret commit
`de2051edc395086a0ef1515f28b8a6eec566a014`; последняя зафиксированная исходная
правка общего списка — `4e9f15d15175aeee430de426a5e2dfca5ceba6f9`.
История не содержит исходного внешнего URL/отдельной лицензии этих файлов.
Не приписывать им автоматически MIT-лицензию соседних компонентов; перед
публичным распространением нужно уточнить provenance/права.

Единая лицензия собственного кода QNZapret в корне пока не объявлена;
эти уведомления не подменяют её и не лицензируют чужие материалы заново.

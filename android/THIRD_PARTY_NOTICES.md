# Сторонние компоненты и лицензии — Igareck AntiDPI для Android

Igareck AntiDPI для Android v1.0. Copyright © 2026 Igor Krauch (github.com/igareck).
Приложение для Android, интеграция каталога стратегий и адаптеры TAP/SOCKS
распространяются по лицензии [GPL-3.0-or-later](LICENSE).
Оформление проекта включает значок приложения, круглую кнопку
«Запустить / применить» и поясняющие надписи главного экрана. Интерфейс
и инструкции доступны на русском и английском языках.

Уведомления и полные тексты лицензий доступны в разделе «Логи и лицензии».
Архив соответствующих исходников приложен к APK на [странице релиза v1.2](https://github.com/igareck/anti-dpi-for-russia/releases/tag/v1.2).
Порядок сборки приложения и нативных библиотек описан в [BUILDING.md](BUILDING.md).

## Сетевой движок и каталог стратегий

| Компонент | Версия или коммит | Лицензия и исходный код |
| --- | --- | --- |
| **nfqws2 из bol-van/zapret2** | `038c97e5819df10f8e8c22a2f1fffe57f2f7379b` | [MIT](app/src/main/assets/licenses/Zapret2-MIT.txt). [Исходники](vendor/nfqws2/) содержат изменения для Android. Движок собирается вместе с [адаптерами TAP/SOCKS](native/nfqws2/). |
| **Lua от Lua.org, PUC-Rio** | 5.4.9 | [MIT](app/src/main/assets/licenses/Lua-MIT.txt). [Исходники Lua](vendor/lua/). |
| **lwIP** | 2.2.1 (`STABLE-2_2_1_RELEASE`) | [BSD-3-Clause](app/src/main/assets/licenses/lwIP-BSD.txt). [Исходники](vendor/lwip/) и [оригинальный файл COPYING](vendor/lwip/COPYING). |
| **kavl**, **uthash** и вспомогательный код из **musl** | В составе исходников nfqws2 | [kavl MIT](app/src/main/assets/licenses/kavl-MIT.txt), [uthash BSD-1-Clause](app/src/main/assets/licenses/uthash-BSD.txt) и [musl MIT](app/src/main/assets/licenses/musl-MIT.txt). |
| **Каталог стратегий Igareck и проверка совместимости ActualTLS** | Коммит `67c57d9e332b224c57c4f27c3087e3fcdc9f0482`, каталог `20261023_public_v1` | [Исходная ревизия Igareck AntiDPI](https://github.com/igareck/anti-dpi-for-russia/tree/67c57d9e332b224c57c4f27c3087e3fcdc9f0482). Версии архивов исходников и изменения для Android перечислены в [PACKET_SOURCE_LOCK.json](PACKET_SOURCE_LOCK.json). |

На Android используется реализация Lua 5.4.9 от Lua.org, PUC-Rio.
LuaJIT применяется в выпусках для Windows и Linux. Уведомление LuaJIT
и сведения об авторстве приведены в [лицензиях Windows и Linux](https://github.com/igareck/anti-dpi-for-russia/blob/v1.2/THIRD_PARTY_NOTICES.md#5-luajit-и-lua).

## Активация на Android и TLS

| Компонент | Версия или коммит | Лицензия и исходный код |
| --- | --- | --- |
| **Фрагмент инициализации Android-контекста из Shizuku-API** | Copyright (c) 2021 RikkaW | [MIT](app/src/main/assets/licenses/Shizuku-API-MIT.txt). [Исходный проект](https://github.com/RikkaApps/Shizuku-API). |
| **android-testtap-egress** | `8147d45f75a01f06afa2a6a0f9a22cec6d639ba9` | [MIT](app/src/main/assets/licenses/TestTap-MIT.txt). |
| **AndroidX Annotations** | 1.3.0 | [Apache-2.0](app/src/main/assets/licenses/AndroidX-Apache-2.0.txt). JAR с исходниками находится в [vendor/androidx-sources/](vendor/androidx-sources/). |
| **libadb-android** | 3.1.1 (`c849886ebc6d48e7b46d967e78a6bb65c90c3b74`) | [GPL-3.0-or-later или Apache-2.0](app/src/main/assets/licenses/libadb-LICENSE.txt). В [изменённых исходниках](vendor/libadb-android/) сохранены уведомления [BSD-3-Clause](app/src/main/assets/licenses/libadb-BSD-3-Clause.txt) и [MIT](app/src/main/assets/licenses/libadb-MIT.txt) для включённого кода. |
| **SPAKE2 Java и JNI** | 2.2.1 (`7615ddd680b990e14513ebb66eac4cb0dbf82464`), spake2-c (`0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`) | [LGPL-3.0](app/src/main/assets/licenses/SPAKE2-LGPL-3.0.txt) и [текст GPL-3.0](app/src/main/assets/licenses/GPL-3.0.txt). [Исходники](vendor/spake2-java/) включают [уведомления о нативном коде](app/src/main/assets/licenses/SPAKE2-native-NOTICE.txt), в том числе [текст LGPL-2.1](app/src/main/assets/licenses/LGPL-2.1.txt) для кода, который на него ссылается. |
| **Conscrypt Android** и **BoringSSL** | Conscrypt Android 2.5.3 | [Apache-2.0](app/src/main/assets/licenses/Conscrypt-Apache-2.0.txt), [уведомление Conscrypt](app/src/main/assets/licenses/Conscrypt-NOTICE.txt) и [лицензия BoringSSL](app/src/main/assets/licenses/BoringSSL-LICENSE.txt). [Исходники Conscrypt 2.5.3](https://github.com/google/conscrypt/tree/2.5.3). |
| **Bouncy Castle** (bcprov, bcpkix, bcutil) | 1.81 | [MIT](app/src/main/assets/licenses/BouncyCastle-MIT.txt). [Исходники Bouncy Castle](https://github.com/bcgit/bc-java/tree/r1rv81). |

Файл [DEPENDENCIES.json](DEPENDENCIES.json) содержит версии, адреса загрузки
и суммы SHA-256 зависимостей. Полные тексты лицензий, включённые в APK,
находятся в [app/src/main/assets/licenses/](app/src/main/assets/licenses/).
Karing, Exclave, v2rayNG, FlClash, Orbot и InviZible Pro устанавливаются
отдельно. Их настройка описана в приложении.

## Сборка с изменённой библиотекой SPAKE2

[Руководство по сборке](BUILDING.md#сборка-с-изменённой-библиотекой-spake2)
описывает, как собрать приложение с изменёнными исходниками Java и JNI
библиотеки SPAKE2. Полученный APK можно подписать своим ключом и установить
на чистое устройство или эмулятор.

# Changelog / История изменений

## 1.2 — Android v1.0 / Windows v1.0 / Linux v1.1

| English | Русский |
| --- | --- |
| First public release for Android. | Первый публичный выпуск для Android. |
| The v1.2 release also includes Windows v1.0 and Linux v1.1. The installers for both platforms are carried over from the v1.1 release. | В релиз v1.2 также входят Windows v1.0 и Linux v1.1. Установочные файлы обеих платформ совпадают с опубликованными в релизе v1.1. |
| Activate the network module over Wi-Fi or over USB from a computer. | Активация сетевого модуля по Wi-Fi или через USB с компьютера. |
| The USB screen shows the connection status and commands for Windows, Linux, and macOS. | Отдельный экран USB с текущим статусом и командами для Windows, Linux и macOS. |
| Local SOCKS5 for VPN and Tor clients at `127.0.0.1:1080` by default. | Локальный SOCKS5 для VPN/Tor-клиентов. Адрес по умолчанию — `127.0.0.1:1080`. |
| Select, start, apply, and stop TCP, QUIC, and Discord/STUN strategies. | Выбор/запуск/применение/остановка TCP/UDP/Discord стратегий. |
| 484 TCP strategies (45 Golden and 439 Silver), QUIC Q1–Q5, and Discord/STUN D1–D8. | 484 TCP-стратегии (45 Golden и 439 Silver), QUIC Q1–Q5 и Discord/STUN D1–D8. |
| TCP strategy checks for YouTube, Discord, LinkedIn, Facebook, X, and Threads with (pass) and (unstable) results. | Проверки TCP-стратегий для YouTube, Discord, LinkedIn, Facebook, X и Threads с метками (pass) и (unstable). |
| Russian and English interfaces, VPN and Tor client guides, connection diagnostics, and log export. | Русский и английский интерфейсы, инструкции для VPN/Tor-клиентов, диагностика соединений и экспорт журналов. |
| Light and dark themes, with a switch in Logs and licenses. | Светлая и тёмная темы с переключателем в «Логах и лицензиях». |
| One signed APK for Android 11 and later on arm64-v8a, armeabi-v7a, and x86\_64. | Один подписанный APK для Android 11+ с arm64-v8a, armeabi-v7a и x86\_64. |

## 1.1 — Linux

| English | Русский |
| --- | --- |
| Linux is updated to v1.1. Windows remains at v1.0. | Linux обновлён до v1.1. Windows остаётся на v1.0. |
| Run from an existing terminal without requiring GNOME Terminal, including KDE and SSH sessions. | Запуск из текущего терминала без обязательного GNOME Terminal, в том числе в KDE и через SSH. |
| Desktop launchers can use Konsole, Xfce Terminal, MATE Terminal, XTerm, or the system terminal alternative. | Ярлыки могут использовать Konsole, Xfce Terminal, MATE Terminal, XTerm или системную альтернативу терминала. |
| Fall back to the current terminal if the GNOME artwork window cannot be prepared. Application failures never trigger a second launch. | При ошибке подготовки окна GNOME используется текущий терминал. Ошибки самой программы не вызывают повторный запуск. |
| Detect ready dependencies automatically on other distributions; retain the --skip-deps option. | Автоматическое определение готовых зависимостей в других дистрибутивах; параметр --skip-deps сохранён. |
| Select a mapped non-root UID/GID in containers and report missing privileges before strategy validation. | Выбор доступного непривилегированного UID/GID в контейнерах и диагностика недостающих прав до проверки стратегий. |
| Print tracebacks and save private error logs. | Вывод traceback и сохранение подробных журналов ошибок с ограниченными правами доступа. |
| Keep all 484 strategies and the pinned compatible Zapret2 commit unchanged. | Все 484 стратегии и закреплённый совместимый коммит Zapret2 сохранены. |
| Include the changes contributed by @egorguslyan in PR #2. | Включены изменения @egorguslyan из PR #2. |

## 1.0

| English | Русский |
| --- | --- |
| First public release of Igareck AntiDPI CLI. | Первый публичный выпуск Igareck AntiDPI CLI. |
| Support for Windows 10/11 x64 and Ubuntu/Debian Linux x64. | Поддержка Windows 10/11 x64 и Linux Ubuntu/Debian x64. |
| 484 strategies: 45 Golden and 439 Silver. | 484 стратегии: 45 Golden и 439 Silver. |
| Switch and restart strategies, and change the network adapter. | Переключение и перезапуск стратегий, смена сетевого адаптера. |
| Built-in YouTube availability check. | Встроенная проверка доступности YouTube. |
| Program installation and uninstallation. | Установка и удаление программы. |

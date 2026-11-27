@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "IGARECK_USB_ADB="
if exist "%~dp0platform-tools\adb.exe" set "IGARECK_USB_ADB=%~dp0platform-tools\adb.exe"
if not defined IGARECK_USB_ADB if exist "%~dp0adb.exe" set "IGARECK_USB_ADB=%~dp0adb.exe"
if not defined IGARECK_USB_ADB if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "IGARECK_USB_ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined IGARECK_USB_ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "IGARECK_USB_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined IGARECK_USB_ADB for /f "delims=" %%I in ('where.exe adb.exe 2^>nul') do if not defined IGARECK_USB_ADB set "IGARECK_USB_ADB=%%I"
if not defined IGARECK_USB_ADB goto missing_adb

echo Подключите и разблокируйте планшет. Подтвердите запрос USB-отладки на его экране.
echo.
"%IGARECK_USB_ADB%" devices -l
"%IGARECK_USB_ADB%" -d get-state
if errorlevel 1 (
 echo.
 echo Для запуска нужен один доступный USB-планшет со статусом device.
 echo При unauthorized подтвердите USB-отладку на планшете и снова запустите этот файл.
 pause
 exit /b 2
)

echo.
echo В Igareck откройте «Сетевой модуль» ^> «Активация через USB (если нет Wi-Fi, при наличии ПК)» ^> «Запуск через USB».
echo Дождитесь статуса «Ожидается USB-команда» и нажмите любую клавишу здесь.
pause >nul
"%IGARECK_USB_ADB%" -d shell "content read --uri content://com.igareck.antidpi.usb/start | sh"
if errorlevel 1 (
 echo Запуск прервался. Проверьте USB-подключение и снова откройте ожидание USB-команды.
 pause
 exit /b 3
)
echo Подтверждение запуска на планшете — «Сетевой модуль активен».
pause
exit /b 0

:missing_adb
echo Не найден adb.exe.
echo Скачайте Android SDK Platform-Tools для Windows с официальной страницы.
echo https://developer.android.com/tools/releases/platform-tools
echo Распакуйте архив и положите всю папку platform-tools рядом с этим файлом.
pause
exit /b 1

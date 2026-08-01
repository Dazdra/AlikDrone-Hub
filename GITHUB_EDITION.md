# AlikDrone Hub — GitHub edition

Это отдельная публичная конфигурация AlikDrone Hub для широкой аудитории. Она
сохраняет бренд, данные автора, пожертвования, Taskbar, сведения о пульте и
локальную установку APK. От полной сборки отличается только сокращённым
сетевым каталогом.

## Каталог

| Приложение | Формат | Источник |
|---|---|---|
| Telegram | APK | зеркало `alik-drone.ru` |
| Discord | XAPK | зеркало `alik-drone.ru` |
| YouTube | APK | зеркало `alik-drone.ru` |
| RUTUBE | APK | зеркало `alik-drone.ru` |
| Cromite | APK | зеркало `alik-drone.ru` |
| Happ | APK | зеркало `alik-drone.ru` |
| SHAREit | APK | зеркало `alik-drone.ru` |
| Windy | APK | зеркало `alik-drone.ru` |

AstraS, ScreenStream и QuickTiles в публичный каталог не входят. Произвольная
загрузка APK по удалённой ссылке отключена. Локальные APK с карты памяти
по-прежнему можно открыть через штатный Android Package Installer.

APK/XAPK не хранятся в Git-репозитории. Исходный код содержит URL зеркала,
версию, SHA-256 файла и отпечаток подписи. Совпадение этих данных подтверждает
целостность файла, но не заменяет независимый аудит безопасности.

## Команды

```powershell
Copy-Item local.properties.example local.properties
# Укажите sdk.dir в local.properties
.\gradlew.bat --no-daemon testGithubUnitTest lintGithub assembleGithub
```

Артефакт: `app/build/outputs/apk/github/app-github.apk`.

Обычная серверная сборка не меняется и по-прежнему создаётся командами
`assembleDebug` или `assembleRelease`.

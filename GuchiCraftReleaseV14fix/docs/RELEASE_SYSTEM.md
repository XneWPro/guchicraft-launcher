# Система релизов ГУЧИКРАФТ Launcher

## Что создаёт `build-release.bat`

После запуска появляются:

- `release/output/GuchicraftLauncher-X.Y.Z-full.zip` — полный переносимый лаунчер вместе со встроенной Java 21;
- `release/output/GuchicraftLauncher-X.Y.Z-update.zip` — компактное обновление без runtime;
- `release/output/launcher-update.json` — метаданные для проверки обновления.

Компактный update ZIP намеренно не содержит папку `runtime`. Благодаря этому внешний Updater не пытается перезаписать Java, через которую он сам запущен.

## Сборка

1. Закрой запущенный Launcher.
2. Запусти `build-release.bat`.
3. Введи версию, например `1.0.0`.
4. Введи описание изменений.
5. Дождись открытия `release/output`.

Если Maven не установлен в PATH, скрипт пытается найти встроенный Maven IntelliJ IDEA. При необходимости запусти PowerShell вручную и передай путь:

```powershell
powershell -ExecutionPolicy Bypass -File tools/build-release.ps1 `
  -Version "1.0.0" `
  -Changelog "Первый релиз" `
  -MavenPath "C:\Program Files\JetBrains\IntelliJ IDEA\plugins\maven\lib\maven3\bin\mvn.cmd"
```

## Первая установка

Распакуй `GuchicraftLauncher-X.Y.Z-full.zip` и запусти:

```text
GuchicraftLauncher\GuchicraftLauncher.exe
```

Для игроков позже будет создан отдельный установщик `.exe`.

## Публикация обновления

1. В репозитории `XneWPro/guchicraft-launcher-files` создай GitHub Release с тегом:

```text
launcher-vX.Y.Z
```

2. Прикрепи к Release файл:

```text
GuchicraftLauncher-X.Y.Z-update.zip
```

3. Скопируй созданный `launcher-update.json` в:

```text
launcher/launcher-update.json
```

4. Сделай Commit и Push.

Ссылка в JSON уже соответствует имени тега и файла, созданным скриптом.

## Тест обновления

Для реального теста нужны две версии:

1. Собери и распакуй `1.0.0-full`.
2. Собери `1.0.1`, опубликуй `1.0.1-update.zip` и новый `launcher-update.json`.
3. Запусти установленный `1.0.0`.
4. Нажми кнопку обновления.
5. Launcher скачает ZIP, проверит SHA-256 и запустит внешний Updater.
6. Updater заменит `GuchicraftLauncher.exe` и папку `app`, затем перезапустит Launcher.
7. В корне приложения `version.txt` должно стать `1.0.1`.

Лог Updater находится здесь:

```text
%APPDATA%\Guchicraft\updater\updater.log
```

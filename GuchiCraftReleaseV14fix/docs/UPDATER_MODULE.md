# GUCHICRAFT Updater

Модуль `updater` — отдельный процесс без JavaFX и сторонних библиотек. Он нужен потому, что запущенный `Launcher.exe` не может надёжно заменить собственные файлы.

## Сборка

Из корня проекта:

```bat
mvn -pl updater package
```

Результат:

```text
updater/target/guchicraft-updater.jar
```

## Как работает полное обновление

1. Launcher скачивает ZIP из `launcher-update.json`.
2. Launcher проверяет размер и SHA-256.
3. Launcher запускает Updater отдельным процессом.
4. Launcher закрывается.
5. Updater ждёт завершения Launcher.
6. Updater безопасно распаковывает ZIP во временную папку.
7. Updater копирует новые файлы в папку приложения.
8. Updater удаляет временные файлы и снова запускает Launcher.

Журнал Updater:

```text
%APPDATA%\Guchicraft\updater\updater.log
```

## Где Launcher ищет updater JAR

По порядку:

1. системное свойство `-Dguchicraft.updater.jar=...`;
2. `<папка приложения>/updater/guchicraft-updater.jar`;
3. `<папка приложения>/app/guchicraft-updater.jar`;
4. `updater/target/guchicraft-updater.jar` для разработки.

Для итогового portable-релиза нужно положить JAR сюда:

```text
GuchicraftLauncher/updater/guchicraft-updater.jar
```

Updater запускается через Java из `%APPDATA%\Guchicraft\runtime\bin\java.exe`, которую Launcher уже устанавливает для Minecraft.

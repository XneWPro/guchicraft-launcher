# ГУЧИКРАФТ — единый проект

Многомодульный Maven-проект:

- `common` — общий формат manifest v2 и SHA-1/SHA-256;
- `builder` — проверка сборки, генерация manifest и публикация через Git;
- `launcher` — обновление клиента, установка Java/Minecraft/Fabric и запуск игры;
- `docs` — документация проекта.

## Запуск

Из корня проекта сначала выполните:

```bat
mvn clean install
```

Builder:

```bat
mvn -pl builder -am javafx:run
```

Launcher:

```bat
mvn -pl launcher -am javafx:run
```

В IntelliJ откройте именно корневую папку `guchicraft`, где лежит общий `pom.xml`, и нажмите **Load Maven Project**.

## Launcher: настройки и автоподключение

Launcher сохраняет ник, память и настройку скрытия окна в `%APPDATA%\\Guchicraft\\launcher.properties`.
Выбранная память реально передаётся Minecraft через `-Xmx`, а после запуска используется
`--quickPlayMultiplayer <host>:<port>` для прямого подключения к серверу. Журнал запуска:
`%APPDATA%\\Guchicraft\\game\\logs\\launcher-game.log`.

## Launcher v11 updater

Документация: `docs/LAUNCHER_V11_UPDATER.md`.

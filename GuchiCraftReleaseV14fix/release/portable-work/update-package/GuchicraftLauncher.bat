@echo off
setlocal
cd /d "%~dp0"

if not exist "runtime\bin\javaw.exe" (
    echo Embedded Java runtime is missing.
    echo Expected: %CD%\runtime\bin\javaw.exe
    pause
    exit /b 1
)

start "" "runtime\bin\javaw.exe" ^
  --module-path "javafx" ^
  --add-modules javafx.controls ^
  -Dfile.encoding=UTF-8 ^
  -Dguchicraft.launcher.version=1.0.15 ^
  -Dguchicraft.launcher.root="%CD%" ^
  -Dguchicraft.updater.jar="%CD%\updater\guchicraft-updater.jar" ^
  -cp "app\*" ^
  ru.ezcraft.launcher.LauncherBootstrap

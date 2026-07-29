@echo off
setlocal
cd /d "%~dp0.."

echo Starting GUCHICRAFT Launcher in diagnostic mode...
echo.

"runtime\bin\java.exe" ^
  --module-path "javafx" ^
  --add-modules javafx.controls ^
  -Dfile.encoding=UTF-8 ^
  -Dguchicraft.launcher.version=1.0.16 ^
  -Dguchicraft.launcher.root="%CD%" ^
  -Dguchicraft.updater.jar="%CD%\updater\guchicraft-updater.jar" ^
  -cp "app\*" ^
  ru.ezcraft.launcher.LauncherBootstrap

set "EXIT_CODE=%ERRORLEVEL%"

echo.
echo Launcher exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%

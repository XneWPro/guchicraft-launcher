@echo off
cd /d "%~dp0"
call mvn -pl updater package
if errorlevel 1 pause & exit /b 1
echo.
echo Updater готов: updater\target\guchicraft-updater.jar
pause

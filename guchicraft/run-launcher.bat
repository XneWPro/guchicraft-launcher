@echo off
cd /d "%~dp0"
call mvn -pl launcher -am install -DskipTests
if errorlevel 1 (
  pause
  exit /b 1
)
call mvn -pl launcher javafx:run
pause

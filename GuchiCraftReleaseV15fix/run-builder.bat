@echo off
cd /d "%~dp0"
call mvn -pl builder -am install -DskipTests
if errorlevel 1 (
  pause
  exit /b 1
)
call mvn -pl builder javafx:run
pause

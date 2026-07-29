@echo off
setlocal
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-release.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
  echo Release build failed with exit code %EXIT_CODE%.
  pause
  exit /b %EXIT_CODE%
)

echo Release build completed successfully.
pause

# Launcher v11 — самообновление

## Что работает

- проверка `launcher/launcher-update.json` при старте;
- сравнение версий вида `1.2.3`;
- кнопка обновления только при наличии более новой версии;
- загрузка ZIP во временный `.part`;
- проверка размера и SHA-256;
- в packaged `.exe` — запуск PowerShell-помощника, закрытие приложения, замена файлов и перезапуск;
- при запуске через IntelliJ — безопасный тест загрузки без попытки заменить IDE/Java.

## Где лаунчер ищет метаданные

`https://raw.githubusercontent.com/XneWPro/guchicraft-launcher-files/main/launcher/launcher-update.json`

## Важно

Для реальной автозамены сначала нужно собрать переносимое Windows-приложение (`app-image`/`.exe`). При запуске через Maven лаунчер только скачивает и проверяет обновление.

## Формат launcher-update.json

```json
{
  "schemaVersion": 1,
  "version": "1.0.1",
  "downloadUrl": "https://github.com/XneWPro/guchicraft-launcher-files/releases/download/launcher-v1.0.1/GuchicraftLauncher-1.0.1.zip",
  "sha256": "...",
  "size": 12345678,
  "mandatory": false,
  "changelog": "Исправления"
}
```

ZIP лучше публиковать как asset в GitHub Release, а маленький `launcher-update.json` — в ветке `main`.

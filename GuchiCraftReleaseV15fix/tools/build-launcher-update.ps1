param(
    [Parameter(Mandatory=$true)][string]$AppImage,
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$DownloadUrl,
    [string]$OutputDirectory = "release",
    [switch]$Mandatory,
    [string]$Changelog = "Обновление лаунчера"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $root $OutputDirectory
}
$source = (Resolve-Path $AppImage).Path
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$zipName = "GuchicraftLauncher-$Version.zip"
$zipPath = Join-Path $OutputDirectory $zipName
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

# В ZIP кладётся одна корневая папка приложения. Updater корректно её распознаёт.
Compress-Archive -Path $source -DestinationPath $zipPath -CompressionLevel Optimal
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash.ToLowerInvariant()
$size = (Get-Item $zipPath).Length

$metadata = [ordered]@{
    schemaVersion = 1
    version = $Version
    downloadUrl = $DownloadUrl
    sha256 = $hash
    size = $size
    mandatory = [bool]$Mandatory
    changelog = $Changelog
}
$metadataPath = Join-Path $OutputDirectory "launcher-update.json"
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8

Write-Host "Готово:" -ForegroundColor Green
Write-Host "  ZIP: $zipPath"
Write-Host "  JSON: $metadataPath"
Write-Host "  SHA-256: $hash"

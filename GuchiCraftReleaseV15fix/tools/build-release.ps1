[CmdletBinding()]
param(
    [string]$Version,

    [string]$Changelog = "Обновление лаунчера",
    [switch]$Mandatory,
    [string]$MavenPath,
    [string]$JpackagePath,
    [string]$OutputDirectory
)

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = Read-Host 'Release version (example: 1.0.0)'
}
if ($Version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "Invalid release version: $Version. Expected format: 1.0.0"
}
if (-not $PSBoundParameters.ContainsKey('Changelog')) {
    $enteredChangelog = Read-Host 'Short changelog (press Enter for default)'
    if (-not [string]::IsNullOrWhiteSpace($enteredChangelog)) {
        $Changelog = $enteredChangelog
    }
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $Root 'release\output'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$Work = Join-Path $Root 'release\work'
$InputDir = Join-Path $Work 'input'
$AppImageDest = Join-Path $Work 'app-image'
$AppName = 'GuchicraftLauncher'
$MainClass = 'ru.ezcraft.launcher.LauncherApplication'

function Write-Step([string]$Text) {
    Write-Host "`n==> $Text" -ForegroundColor Cyan
}

function Find-Maven {
    param([string]$Configured)
    if ($Configured -and (Test-Path $Configured)) { return (Resolve-Path $Configured).Path }
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @()
    if ($env:ProgramFiles) {
        $candidates += Get-ChildItem "$env:ProgramFiles\JetBrains" -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
        $candidates += Get-ChildItem "$env:ProgramFiles\Apache\maven*\bin" -Filter mvn.cmd -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    }
    if ($env:LOCALAPPDATA) {
        $candidates += Get-ChildItem "$env:LOCALAPPDATA\JetBrains" -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    }
    $found = $candidates | Select-Object -First 1
    if ($found) { return $found }
    throw "Maven не найден. Установи Apache Maven или передай -MavenPath 'C:\путь\mvn.cmd'."
}

function Find-Jpackage {
    param([string]$Configured)
    if ($Configured -and (Test-Path $Configured)) { return (Resolve-Path $Configured).Path }
    $cmd = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $roots = @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Filter jpackage.exe -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
        if ($found) { return $found }
    }
    throw "jpackage.exe не найден. Нужен JDK 21+; можно передать -JpackagePath 'C:\...\jpackage.exe'."
}

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    Write-Host "$File $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Команда завершилась с кодом ${LASTEXITCODE}: ${File}"
    }
}

function Compress-DirectoryContent {
    param([string]$Source, [string]$Destination, [switch]$IncludeRoot)
    if (Test-Path $Destination) { Remove-Item $Destination -Force }
    if ($IncludeRoot) {
        Compress-Archive -Path $Source -DestinationPath $Destination -CompressionLevel Optimal
    } else {
        Compress-Archive -Path (Join-Path $Source '*') -DestinationPath $Destination -CompressionLevel Optimal
    }
}

$Maven = Find-Maven $MavenPath
$Jpackage = Find-Jpackage $JpackagePath
Write-Host "Maven:   $Maven"
Write-Host "jpackage: $Jpackage"

Write-Step 'Очистка временных папок'
Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
New-Item $InputDir -ItemType Directory -Force | Out-Null
New-Item $AppImageDest -ItemType Directory -Force | Out-Null
New-Item $OutputDirectory -ItemType Directory -Force | Out-Null

Write-Step 'Сборка Maven-модулей'
Push-Location $Root
try {
    Invoke-Checked $Maven @('-B', '-DskipTests=false', 'clean', 'package')

    Write-Step 'Копирование runtime-зависимостей Launcher'
    Invoke-Checked $Maven @(
        '-B', '-pl', 'launcher',
        'dependency:copy-dependencies',
        '-DincludeScope=runtime',
        "-DoutputDirectory=$InputDir"
    )
} finally {
    Pop-Location
}

$LauncherJar = Join-Path $Root 'launcher\target\guchicraft-launcher.jar'
$UpdaterJar = Join-Path $Root 'updater\target\guchicraft-updater.jar'
if (-not (Test-Path $LauncherJar)) { throw "Не найден $LauncherJar" }
if (-not (Test-Path $UpdaterJar)) { throw "Не найден $UpdaterJar" }
Copy-Item $LauncherJar (Join-Path $InputDir 'guchicraft-launcher.jar') -Force
Copy-Item $UpdaterJar (Join-Path $InputDir 'guchicraft-updater.jar') -Force

Write-Step 'Создание переносимого Windows-приложения'
$Modules = 'java.base,java.desktop,java.logging,java.naming,java.net.http,java.xml,jdk.crypto.ec,jdk.unsupported'
Invoke-Checked $Jpackage @(
    '--type', 'app-image',
    '--name', $AppName,
    '--dest', $AppImageDest,
    '--input', $InputDir,
    '--main-jar', 'guchicraft-launcher.jar',
    '--main-class', $MainClass,
    '--app-version', ($Version.Split('-+')[0]),
    '--vendor', 'ГУЧИКРАФТ',
    '--description', 'Лаунчер сервера ГУЧИКРАФТ',
    '--add-modules', $Modules,
    '--java-options', "-Dguchicraft.launcher.version=$Version",
    '--java-options', '-Dfile.encoding=UTF-8'
)

$AppImage = Join-Path $AppImageDest $AppName
if (-not (Test-Path (Join-Path $AppImage "$AppName.exe"))) {
    throw "jpackage не создал $AppName.exe"
}

# Updater expects the JAR under app/. copy-dependencies may already place it there,
# but this explicit copy makes the release layout deterministic.
Copy-Item $UpdaterJar (Join-Path $AppImage 'app\guchicraft-updater.jar') -Force
Set-Content (Join-Path $AppImage 'version.txt') -Value $Version -Encoding UTF8

Write-Step 'Создание полного архива для первого скачивания'
$FullZip = Join-Path $OutputDirectory "$AppName-$Version-full.zip"
Compress-DirectoryContent -Source $AppImage -Destination $FullZip -IncludeRoot

Write-Step 'Создание компактного архива обновления'
$UpdatePayload = Join-Path $Work 'update-payload'
$UpdateRoot = Join-Path $UpdatePayload $AppName
New-Item $UpdateRoot -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $AppImage "$AppName.exe") $UpdateRoot -Force
Copy-Item (Join-Path $AppImage 'app') $UpdateRoot -Recurse -Force
Copy-Item (Join-Path $AppImage 'version.txt') $UpdateRoot -Force
$UpdateZip = Join-Path $OutputDirectory "$AppName-$Version-update.zip"
Compress-DirectoryContent -Source $UpdateRoot -Destination $UpdateZip -IncludeRoot

$Hash = (Get-FileHash $UpdateZip -Algorithm SHA256).Hash.ToLowerInvariant()
$Size = (Get-Item $UpdateZip).Length
$DownloadUrl = "https://github.com/XneWPro/guchicraft-launcher-files/releases/download/launcher-v$Version/$AppName-$Version-update.zip"
$Manifest = [ordered]@{
    schemaVersion = 1
    version = $Version
    downloadUrl = $DownloadUrl
    sha256 = $Hash
    size = $Size
    mandatory = [bool]$Mandatory
    changelog = $Changelog
}
$ManifestPath = Join-Path $OutputDirectory 'launcher-update.json'
$Manifest | ConvertTo-Json -Depth 5 | Set-Content $ManifestPath -Encoding UTF8

Write-Step 'Релиз готов'
Write-Host "Приложение: $AppImage" -ForegroundColor Green
Write-Host "Полный ZIP: $FullZip" -ForegroundColor Green
Write-Host "Update ZIP: $UpdateZip" -ForegroundColor Green
Write-Host "Manifest:   $ManifestPath" -ForegroundColor Green
Write-Host "SHA-256:    $Hash"
Write-Host "Размер:     $Size байт"

Start-Process explorer.exe $OutputDirectory

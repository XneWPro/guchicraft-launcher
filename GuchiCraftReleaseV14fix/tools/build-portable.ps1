[CmdletBinding()]
param(
          [string]$Version,
          [string]$Changelog,
          [string]$DownloadUrl,
          [string]$MavenPath,
          [string]$JavaHome,
          [string]$Launch4jPath,
          [string]$OutputDirectory
)

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ------------------------------------------------------------
# ПАРАМЕТРЫ РЕЛИЗА
# ------------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = Read-Host 'Portable version (example: 1.0.1)'
}

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid version: $Version. Expected format: 1.0.1"
}

if ([string]::IsNullOrWhiteSpace($Changelog)) {
    $Changelog = Read-Host 'Short changelog (press Enter for default)'

    if ([string]::IsNullOrWhiteSpace($Changelog)) {
        $Changelog = "GUCHICRAFT Launcher update $Version"
    }
}

if ([string]::IsNullOrWhiteSpace($DownloadUrl)) {
    $DownloadUrl =
        "https://github.com/XneWPro/guchicraft-launcher-files/releases/download/launcher-v$Version/GuchicraftLauncher-$Version-update.zip"
}

# ------------------------------------------------------------
# ПУТИ
# ------------------------------------------------------------

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $Root 'release\portable-output'
}

$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)

$Work = Join-Path $Root 'release\portable-work'
$Dependencies = Join-Path $Work 'dependencies'
$UpdateRoot = Join-Path $Work 'update-package'

$PortableName = "GuchicraftLauncher-$Version-portable"
$PortableRoot = Join-Path $OutputDirectory $PortableName

$AppDir = Join-Path $PortableRoot 'app'
$JavaFxDir = Join-Path $PortableRoot 'javafx'
$RuntimeDir = Join-Path $PortableRoot 'runtime'
$UpdaterDir = Join-Path $PortableRoot 'updater'

$PortableZip = Join-Path $OutputDirectory "$PortableName.zip"
$UpdateZip = Join-Path $OutputDirectory "GuchicraftLauncher-$Version-update.zip"
$UpdateManifestPath = Join-Path $OutputDirectory 'launcher-update.json'
$HashesPath = Join-Path $OutputDirectory 'SHA256.txt'

$MainClass = 'ru.ezcraft.launcher.LauncherBootstrap'

# ------------------------------------------------------------
# ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
# ------------------------------------------------------------

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Find-Maven([string]$Configured) {
    if ($Configured -and (Test-Path $Configured)) {
        return (Resolve-Path $Configured).Path
    }

    foreach ($name in @('mvn.cmd', 'mvn')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue

        if ($command) {
            return $command.Source
        }
    }

    $searchRoots = @(
        "$env:ProgramFiles\JetBrains",
        "$env:LOCALAPPDATA\JetBrains",
        'D:\IntelliJ IDEA 2026.2.0.1'
    ) | Where-Object {
        $_ -and (Test-Path $_)
    }

    foreach ($searchRoot in $searchRoots) {
        $found = Get-ChildItem `
                $searchRoot `
                -Filter mvn.cmd `
                -Recurse `
                -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName

        if ($found) {
            return $found
        }
    }

    throw "Maven was not found. Pass -MavenPath 'D:\path\mvn.cmd'."
}

function Test-JdkHome([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) {
        return $false
    }

    return Test-Path (Join-Path $Candidate 'bin\java.exe')
}

function Find-Jdk([string]$Configured) {
    if (Test-JdkHome $Configured) {
        return (Resolve-Path $Configured).Path
    }

    if (Test-JdkHome $env:JAVA_HOME) {
        return (Resolve-Path $env:JAVA_HOME).Path
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue

    if ($javaCommand) {
        $candidate = Split-Path (
            Split-Path $javaCommand.Source -Parent
        ) -Parent

        if (Test-JdkHome $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    $roots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft"
    ) | Where-Object {
        $_ -and (Test-Path $_)
    }

    foreach ($root in $roots) {
        $candidates = Get-ChildItem `
                $root `
                -Filter java.exe `
                -Recurse `
                -ErrorAction SilentlyContinue |
            Where-Object {
                $_.FullName -match '\\bin\\java\.exe$'
            } |
            ForEach-Object {
                Split-Path (
                    Split-Path $_.FullName -Parent
                ) -Parent
            } |
            Select-Object -Unique

        $preferred = $candidates |
            Where-Object {
                $_ -match '(?i)(jdk|temurin)[-_ ]?21'
            } |
            Select-Object -First 1

        if ($preferred) {
            return $preferred
        }

        $fallback = $candidates | Select-Object -First 1

        if ($fallback) {
            return $fallback
        }
    }

    throw "JDK 21+ was not found. Pass -JavaHome 'C:\path\jdk-21'."
}

function Find-Launch4j([string]$Configured) {
    $candidates = @()

    if (-not [string]::IsNullOrWhiteSpace($Configured)) {
        $candidates += $Configured
    }

    $candidates += @(
        'D:\Launch4j\launch4jc.exe',
        'D:\Launch4j\launch4j\launch4jc.exe',
        "$env:ProgramFiles\Launch4j\launch4jc.exe",
        "$env:LOCALAPPDATA\Launch4j\launch4jc.exe"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $command = Get-Command launch4jc.exe -ErrorAction SilentlyContinue

    if ($command) {
        return $command.Source
    }

    throw @"
Launch4j was not found.

Expected file:
D:\Launch4j\launch4jc.exe

Or pass:
-Launch4jPath 'D:\path\launch4jc.exe'
"@
}

function Invoke-Checked(
    [string]$File,
    [string[]]$Arguments
) {
    Write-Host "$File $($Arguments -join ' ')" -ForegroundColor DarkGray

    & $File @Arguments

    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: ${File}"
    }
}

function Write-Utf8WithoutBom(
    [string]$Path,
    [string]$Text
) {
    $encoding = [System.Text.UTF8Encoding]::new($false)

    [System.IO.File]::WriteAllText(
        $Path,
        $Text,
        $encoding
    )
}

function Copy-UpdateItem(
    [string]$Name
) {
    $source = Join-Path $PortableRoot $Name
    $destination = Join-Path $UpdateRoot $Name

    if (-not (Test-Path $source)) {
        throw "Update item was not found: $source"
    }

    Copy-Item `
        -Path $source `
        -Destination $destination `
        -Recurse `
        -Force
}

# ------------------------------------------------------------
# ПОИСК MAVEN И JAVA
# ------------------------------------------------------------

$Maven = Find-Maven $MavenPath
$Jdk = Find-Jdk $JavaHome
$Launch4j = Find-Launch4j $Launch4jPath

Write-Host "Maven:   $Maven"
Write-Host "JDK:     $Jdk"
Write-Host "Launch4j: $Launch4j"
Write-Host "Version: $Version"
Write-Host "Update URL: $DownloadUrl"

# ------------------------------------------------------------
# ОЧИСТКА ПАПОК
# ------------------------------------------------------------

Write-Step 'Cleaning portable work directories'

Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $PortableRoot -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $PortableZip -Force -ErrorAction SilentlyContinue
Remove-Item $UpdateZip -Force -ErrorAction SilentlyContinue
Remove-Item $UpdateManifestPath -Force -ErrorAction SilentlyContinue
Remove-Item $HashesPath -Force -ErrorAction SilentlyContinue

New-Item $Dependencies -ItemType Directory -Force | Out-Null
New-Item $AppDir -ItemType Directory -Force | Out-Null
New-Item $JavaFxDir -ItemType Directory -Force | Out-Null
New-Item $UpdaterDir -ItemType Directory -Force | Out-Null
New-Item $UpdateRoot -ItemType Directory -Force | Out-Null
New-Item $OutputDirectory -ItemType Directory -Force | Out-Null

# ------------------------------------------------------------
# СБОРКА MAVEN
# ------------------------------------------------------------

Write-Step 'Building Maven modules'

Push-Location $Root

try {
    Invoke-Checked $Maven @(
        '-B',
        '-DskipTests=false',
        'clean',
        'package'
    )

    Invoke-Checked $Maven @(
        '-B',
        '-pl',
        'launcher',
        'dependency:copy-dependencies',
        '-DincludeScope=runtime',
        "-DoutputDirectory=$Dependencies"
    )
} finally {
    Pop-Location
}

$LauncherJar = Join-Path `
    $Root `
    'launcher\target\guchicraft-launcher.jar'

$UpdaterJar = Join-Path `
    $Root `
    'updater\target\guchicraft-updater.jar'

if (-not (Test-Path $LauncherJar)) {
    throw "Missing launcher JAR: $LauncherJar"
}

if (-not (Test-Path $UpdaterJar)) {
    throw "Missing updater JAR: $UpdaterJar"
}

# ------------------------------------------------------------
# ПОДГОТОВКА БИБЛИОТЕК
# ------------------------------------------------------------

Write-Step 'Separating JavaFX modules and application libraries'

Get-ChildItem $Dependencies -File | ForEach-Object {
    if ($_.Name -match '^javafx-.*-win\.jar$') {
        Copy-Item `
            $_.FullName `
            (Join-Path $JavaFxDir $_.Name) `
            -Force
    } elseif ($_.Name -notmatch '^javafx-.*\.jar$') {
        Copy-Item `
            $_.FullName `
            (Join-Path $AppDir $_.Name) `
            -Force
    }
}

Copy-Item `
    $LauncherJar `
    (Join-Path $AppDir 'guchicraft-launcher.jar') `
    -Force

Copy-Item `
    $UpdaterJar `
    (Join-Path $UpdaterDir 'guchicraft-updater.jar') `
    -Force

$RequiredJavaFx = @(
    'javafx-base',
    'javafx-controls',
    'javafx-graphics'
)

foreach ($module in $RequiredJavaFx) {
    $moduleJar = Get-ChildItem `
        $JavaFxDir `
        -Filter "$module-*-win.jar" `
        -ErrorAction SilentlyContinue

    if (-not $moduleJar) {
        throw "Missing Windows JavaFX module: $module"
    }
}

# ------------------------------------------------------------
# ПОЛНАЯ JAVA
# ------------------------------------------------------------

Write-Step 'Copying full Java runtime'

if (-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {
    throw "Java executable was not found in JDK: $Jdk"
}

Remove-Item $RuntimeDir -Recurse -Force -ErrorAction SilentlyContinue

New-Item `
    $RuntimeDir `
    -ItemType Directory `
    -Force |
    Out-Null

Copy-Item `
    -Path (Join-Path $Jdk '*') `
    -Destination $RuntimeDir `
    -Recurse `
    -Force

if (-not (Test-Path (Join-Path $RuntimeDir 'bin\java.exe'))) {
    throw 'Full Java runtime was not copied correctly.'
}

if (-not (Test-Path (Join-Path $RuntimeDir 'bin\javaw.exe'))) {
    throw 'javaw.exe is missing from the copied Java runtime.'
}

# ------------------------------------------------------------
# BAT-ФАЙЛЫ ЗАПУСКА
# ------------------------------------------------------------

Write-Step 'Creating launcher start files'

$LauncherBat = @'
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
  -Dguchicraft.launcher.version=__VERSION__ ^
  -Dguchicraft.launcher.root="%CD%" ^
  -Dguchicraft.updater.jar="%CD%\updater\guchicraft-updater.jar" ^
  -cp "app\*" ^
  ru.ezcraft.launcher.LauncherBootstrap
'@.Replace('__VERSION__', $Version)

Set-Content `
    (Join-Path $PortableRoot 'GuchicraftLauncher.bat') `
    -Value $LauncherBat `
    -Encoding ASCII

$LauncherVbs = @'
Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")

launcherRoot = fileSystem.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = launcherRoot

command = Chr(34) & launcherRoot & "\runtime\bin\javaw.exe" & Chr(34) & _
  " --module-path " & Chr(34) & launcherRoot & "\javafx" & Chr(34) & _
  " --add-modules javafx.controls" & _
  " -Dfile.encoding=UTF-8" & _
  " -Dguchicraft.launcher.version=__VERSION__" & _
  " -Dguchicraft.launcher.root=" & Chr(34) & launcherRoot & Chr(34) & _
  " -Dguchicraft.updater.jar=" & Chr(34) & launcherRoot & "\updater\guchicraft-updater.jar" & Chr(34) & _
  " -cp " & Chr(34) & launcherRoot & "\app\*" & Chr(34) & _
  " ru.ezcraft.launcher.LauncherBootstrap"

shell.Run command, 0, False
'@.Replace('__VERSION__', $Version)

Set-Content `
    (Join-Path $PortableRoot 'GuchicraftLauncher.vbs') `
    -Value $LauncherVbs `
    -Encoding ASCII

$DebugBat = @'
@echo off
setlocal
cd /d "%~dp0"

echo Starting GUCHICRAFT Launcher in diagnostic mode...
echo.

"runtime\bin\java.exe" ^
  --module-path "javafx" ^
  --add-modules javafx.controls ^
  -Dfile.encoding=UTF-8 ^
  -Dguchicraft.launcher.version=__VERSION__ ^
  -Dguchicraft.launcher.root="%CD%" ^
  -Dguchicraft.updater.jar="%CD%\updater\guchicraft-updater.jar" ^
  -cp "app\*" ^
  ru.ezcraft.launcher.LauncherBootstrap

set "EXIT_CODE=%ERRORLEVEL%"

echo.
echo Launcher exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%
'@.Replace('__VERSION__', $Version)

Set-Content `
    (Join-Path $PortableRoot 'GuchicraftLauncher-debug.bat') `
    -Value $DebugBat `
    -Encoding ASCII

# ------------------------------------------------------------
# VERSION И README
# ------------------------------------------------------------
# ------------------------------------------------------------
# LAUNCH4J EXE
# ------------------------------------------------------------

Write-Step 'Creating GuchicraftLauncher.exe with Launch4j'

$VersionParts = $Version.Split('.')

$ExeVersion = '{0}.{1}.{2}.0' -f `
    $VersionParts[0], `
    $VersionParts[1], `
    $VersionParts[2]

$Launch4jConfigPath = Join-Path $Work 'launch4j-config.xml'
$LauncherExe = Join-Path $PortableRoot 'GuchicraftLauncher.exe'

$LauncherIcon = Join-Path `
    $Root `
    'tools\launch4j\GuchicraftLauncher.ico'

    if (-not (Test-Path $LauncherIcon)) {
        throw "Launcher icon not found: $LauncherIcon"
    }

$Launch4jConfig = @"
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
    <dontWrapJar>true</dontWrapJar>
    <headerType>gui</headerType>

    <outfile>$LauncherExe</outfile>

    <icon>$LauncherIcon</icon>

    <jar></jar>

    <errTitle>ГУЧИКРАФТ Launcher</errTitle>
    <chdir>.</chdir>
    <priority>normal</priority>
    <stayAlive>false</stayAlive>
    <restartOnCrash>false</restartOnCrash>

    <classPath>
        <mainClass>ru.ezcraft.launcher.LauncherBootstrap</mainClass>
        <cp>app/*</cp>
    </classPath>

    <jre>
        <path>runtime</path>
        <requiresJdk>false</requiresJdk>
        <requires64Bit>true</requires64Bit>
        <minVersion>21</minVersion>

        <opt>--module-path=%EXEDIR%\javafx</opt>
        <opt>--add-modules=javafx.controls</opt>
        <opt>-Dfile.encoding=UTF-8</opt>
        <opt>-Dguchicraft.launcher.version=$Version</opt>
        <opt>-Dguchicraft.launcher.root="%EXEDIR%"</opt>
        <opt>-Dguchicraft.updater.jar="%EXEDIR%\updater\guchicraft-updater.jar"</opt>
    </jre>

    <versionInfo>
        <fileVersion>$ExeVersion</fileVersion>
        <txtFileVersion>$Version</txtFileVersion>
        <fileDescription>ГУЧИКРАФТ Launcher</fileDescription>
        <copyright>ГУЧИКРАФТ</copyright>
        <productVersion>$ExeVersion</productVersion>
        <txtProductVersion>$Version</txtProductVersion>
        <productName>ГУЧИКРАФТ Launcher</productName>
        <companyName>ГУЧИКРАФТ</companyName>
        <internalName>GuchicraftLauncher</internalName>
        <originalFilename>GuchicraftLauncher.exe</originalFilename>
        <language>RUSSIAN</language>
    </versionInfo>

    <messages>
        <startupErr>Не удалось запустить ГУЧИКРАФТ Launcher.</startupErr>
        <jreNotFoundErr>Встроенная Java не найдена. Распакуйте архив полностью.</jreNotFoundErr>
        <jreVersionErr>Для запуска требуется Java 21 или новее.</jreVersionErr>
        <launcherErr>Не удалось запустить Java-приложение.</launcherErr>
    </messages>
</launch4jConfig>
"@

Write-Utf8WithoutBom `
    $Launch4jConfigPath `
    $Launch4jConfig

Push-Location $PortableRoot

try {
    Invoke-Checked `
        $Launch4j `
        @($Launch4jConfigPath)
} finally {
    Pop-Location
}

if (-not (Test-Path $LauncherExe)) {
    throw "Launch4j did not create EXE: $LauncherExe"
}

Write-Host "EXE created: $LauncherExe" -ForegroundColor Green
Set-Content `
    (Join-Path $PortableRoot 'version.txt') `
    -Value $Version `
    -Encoding ASCII

$Readme = @"
GUCHICRAFT Launcher Portable $Version

1. Start GuchicraftLauncher.bat.
2. If nothing opens, start GuchicraftLauncher-debug.bat.
3. Do not move individual files out of this folder.
4. IntelliJ, Maven and separately installed Java are not required.
5. Launcher updates are installed automatically through the included Updater.

Version: $Version
"@

Write-Utf8WithoutBom `
    (Join-Path $PortableRoot 'README-PORTABLE.txt') `
    $Readme

# ------------------------------------------------------------
# ПОЛНЫЙ PORTABLE ZIP
# ------------------------------------------------------------

Write-Step 'Creating full portable ZIP'

Compress-Archive `
    -Path $PortableRoot `
    -DestinationPath $PortableZip `
    -CompressionLevel Optimal

# ------------------------------------------------------------
# UPDATE ZIP БЕЗ RUNTIME
# ------------------------------------------------------------

Write-Step 'Preparing update package'


Copy-UpdateItem 'app'
Copy-UpdateItem 'javafx'
Copy-UpdateItem 'updater'
Copy-UpdateItem 'GuchicraftLauncher.exe'
Copy-UpdateItem 'GuchicraftLauncher.vbs'
Copy-UpdateItem 'GuchicraftLauncher.bat'
Copy-UpdateItem 'GuchicraftLauncher-debug.bat'
Copy-UpdateItem 'version.txt'
Copy-UpdateItem 'README-PORTABLE.txt'

Write-Step 'Creating update ZIP'

# Внутри ZIP сразу будут app, javafx, updater и version.txt,
# без дополнительной внешней папки.
Compress-Archive `
    -Path (Join-Path $UpdateRoot '*') `
    -DestinationPath $UpdateZip `
    -CompressionLevel Optimal

if (-not (Test-Path $UpdateZip)) {
    throw "Update ZIP was not created: $UpdateZip"
}

# ------------------------------------------------------------
# SHA-256 И РАЗМЕР
# ------------------------------------------------------------

Write-Step 'Calculating SHA-256'

$PortableHash = (
    Get-FileHash `
        $PortableZip `
        -Algorithm SHA256
).Hash.ToLowerInvariant()

$UpdateHash = (
    Get-FileHash `
        $UpdateZip `
        -Algorithm SHA256
).Hash.ToLowerInvariant()

$UpdateSize = (Get-Item $UpdateZip).Length
$PortableSize = (Get-Item $PortableZip).Length

$HashesText = @"
GUCHICRAFT Launcher $Version

Portable:
File: $(Split-Path $PortableZip -Leaf)
Size: $PortableSize
SHA-256: $PortableHash

Update:
File: $(Split-Path $UpdateZip -Leaf)
Size: $UpdateSize
SHA-256: $UpdateHash
"@

Write-Utf8WithoutBom $HashesPath $HashesText

# ------------------------------------------------------------
# LAUNCHER-UPDATE.JSON
# ------------------------------------------------------------

Write-Step 'Creating launcher-update.json'

$UpdateManifest = [ordered]@{
    schemaVersion = 1
    version       = $Version
    downloadUrl   = $DownloadUrl
    sha256        = $UpdateHash
    size          = $UpdateSize
    mandatory     = $false
    changelog     = $Changelog
}

$UpdateManifestJson = $UpdateManifest |
    ConvertTo-Json -Depth 5

Write-Utf8WithoutBom `
    $UpdateManifestPath `
    $UpdateManifestJson

# ------------------------------------------------------------
# ФИНАЛЬНАЯ ПРОВЕРКА
# ------------------------------------------------------------

Write-Step 'Validating generated release'

$RequiredPortableFiles = @(
    'GuchicraftLauncher.exe',
    'GuchicraftLauncher.vbs',
    'GuchicraftLauncher.bat',
    'GuchicraftLauncher-debug.bat',
    'version.txt',
    'app\guchicraft-launcher.jar',
    'updater\guchicraft-updater.jar',
    'runtime\bin\java.exe',
    'runtime\bin\javaw.exe'
)

foreach ($relativePath in $RequiredPortableFiles) {
    $fullPath = Join-Path $PortableRoot $relativePath

    if (-not (Test-Path $fullPath)) {
        throw "Portable validation failed. Missing: $fullPath"
    }
}

$RequiredUpdateFiles = @(
    'GuchicraftLauncher.exe',
    'GuchicraftLauncher.vbs',
    'GuchicraftLauncher.bat',
    'GuchicraftLauncher-debug.bat',
    'version.txt',
    'app\guchicraft-launcher.jar',
    'updater\guchicraft-updater.jar'
)

foreach ($relativePath in $RequiredUpdateFiles) {
    $fullPath = Join-Path $UpdateRoot $relativePath

    if (-not (Test-Path $fullPath)) {
        throw "Update package validation failed. Missing: $fullPath"
    }
}

# ------------------------------------------------------------
# РЕЗУЛЬТАТ
# ------------------------------------------------------------

Write-Step 'Portable release is ready'

Write-Host "Version:       $Version" -ForegroundColor Green
Write-Host "Folder:        $PortableRoot" -ForegroundColor Green
Write-Host "Portable ZIP:  $PortableZip" -ForegroundColor Green
Write-Host "Update ZIP:    $UpdateZip" -ForegroundColor Green
Write-Host "Update JSON:   $UpdateManifestPath" -ForegroundColor Green
Write-Host "SHA-256 file:  $HashesPath" -ForegroundColor Green
Write-Host ""
Write-Host "Update SHA-256: $UpdateHash" -ForegroundColor Yellow
Write-Host "Update size:    $UpdateSize bytes" -ForegroundColor Yellow
Write-Host ""
Write-Host "GitHub Release tag: launcher-v$Version" -ForegroundColor Cyan
Write-Host "Upload file: $(Split-Path $UpdateZip -Leaf)" -ForegroundColor Cyan

Start-Process explorer.exe $OutputDirectory
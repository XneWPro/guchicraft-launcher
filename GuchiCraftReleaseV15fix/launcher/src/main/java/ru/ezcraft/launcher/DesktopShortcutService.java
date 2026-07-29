package ru.ezcraft.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

public final class DesktopShortcutService {

    private static final String SHORTCUT_NAME =
            "ГУЧИКРАФТ!.lnk";

    private DesktopShortcutService() {
    }

    /**
     * Создаёт ярлык на рабочем столе, если его ещё нет.
     *
     * @param launcherExe путь к GuchicraftLauncher.exe
     */
    public static void createShortcutIfMissing(
            Path launcherExe
    ) {
        if (!isWindows()) {
            return;
        }

        if (launcherExe == null) {
            return;
        }

        Path normalizedExe = launcherExe
                .toAbsolutePath()
                .normalize();

        if (!Files.isRegularFile(normalizedExe)) {
            return;
        }

        Path workingDirectory = normalizedExe.getParent();

        if (workingDirectory == null) {
            return;
        }

        try {
            createWindowsShortcut(
                    normalizedExe,
                    workingDirectory
            );
        } catch (Exception exception) {
            writeErrorLog(exception);
        }
    }

    private static void createWindowsShortcut(
            Path launcherExe,
            Path workingDirectory
    ) throws IOException, InterruptedException {

        String shortcutName = escapePowerShell(
                SHORTCUT_NAME
        );

        String executablePath = escapePowerShell(
                launcherExe.toString()
        );

        String workingDirectoryPath = escapePowerShell(
                workingDirectory.toString()
        );

        String script = """
                $ErrorActionPreference = 'Stop'

                $desktop = [Environment]::GetFolderPath(
                    [Environment+SpecialFolder]::Desktop
                )

                if ([string]::IsNullOrWhiteSpace($desktop)) {
                    throw 'Не удалось определить рабочий стол'
                }

                $shortcutPath = Join-Path $desktop '%s'

                if (Test-Path -LiteralPath $shortcutPath) {
                    exit 0
                }

                $shell = New-Object -ComObject WScript.Shell
                $shortcut = $shell.CreateShortcut($shortcutPath)

                $shortcut.TargetPath = '%s'
                $shortcut.WorkingDirectory = '%s'
                $shortcut.IconLocation = '%s,0'
                $shortcut.Description = 'Запустить ГУЧИКРАФТ!'

                $shortcut.Save()

                if (-not (Test-Path -LiteralPath $shortcutPath)) {
                    throw 'Ярлык не был создан'
                }
                """.formatted(
                shortcutName,
                executablePath,
                workingDirectoryPath,
                executablePath
        );

        String encodedCommand = Base64
                .getEncoder()
                .encodeToString(
                        script.getBytes(
                                StandardCharsets.UTF_16LE
                        )
                );

        Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encodedCommand
        )
                .redirectErrorStream(true)
                .start();

        String processOutput;

        try (var input = process.getInputStream()) {
            processOutput = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException(
                    "PowerShell завершился с кодом "
                            + exitCode
                            + ": "
                            + processOutput
            );
        }
    }

    private static boolean isWindows() {
        return System.getProperty(
                        "os.name",
                        ""
                )
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }

    private static String escapePowerShell(
            String value
    ) {
        return value.replace("'", "''");
    }

    private static void writeErrorLog(
            Exception exception
    ) {
        try {
            String appData = System.getenv("APPDATA");

            Path logDirectory;

            if (appData != null && !appData.isBlank()) {
                logDirectory = Path.of(appData)
                        .resolve("Guchicraft");
            } else {
                logDirectory = Path.of(
                        System.getProperty("user.home"),
                        ".guchicraft"
                );
            }

            Files.createDirectories(logDirectory);

            Files.writeString(
                    logDirectory.resolve("shortcut-error.log"),
                    exception.getClass().getName()
                            + ": "
                            + exception.getMessage()
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignored) {
            // Ошибку ярлыка не позволяем сломать Launcher.
        }
    }
}
package ru.guchicraft.updater;

import java.io.File;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class UpdaterMain {

    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long PROCESS_WAIT_TIMEOUT_MILLIS = 30_000;
    private static final long PROCESS_CHECK_INTERVAL_MILLIS = 250;

    private UpdaterMain() {
    }

    public static void main(String[] args) {
        Map<String, String> arguments = parseArguments(args);

        String sourceValue = arguments.get("source");
        String targetValue = arguments.get("target");
        String pidValue = arguments.get("pid");

        if (sourceValue == null || targetValue == null) {
            printUsage();
            System.exit(2);
            return;
        }

        Path sourceZip = Path.of(sourceValue)
                .toAbsolutePath()
                .normalize();

        Path targetDirectory = Path.of(targetValue)
                .toAbsolutePath()
                .normalize();

        Path workDirectory = targetDirectory.resolveSibling(
                targetDirectory.getFileName() + "-update-work"
        );

        Path backupDirectory = workDirectory.resolve("backup");
        Path extractedDirectory = workDirectory.resolve("extracted");
        Path logFile = workDirectory.resolve("updater.log");

        try {
            Files.createDirectories(workDirectory);

            log(logFile, "Updater started");
            log(logFile, "Source: " + sourceZip);
            log(logFile, "Target: " + targetDirectory);
            log(logFile, "Launcher PID: " + pidValue);

            validatePaths(sourceZip, targetDirectory);

            /*
             * При реальном обновлении Launcher передаст свой PID.
             * Updater подождёт закрытия Launcher и только после этого
             * начнёт заменять его файлы.
             *
             * При ручном тесте аргумент --pid можно не передавать.
             */
            waitForProcess(pidValue, logFile);

            deleteDirectoryIfExists(backupDirectory);
            deleteDirectoryIfExists(extractedDirectory);

            Files.createDirectories(backupDirectory);
            Files.createDirectories(extractedDirectory);

            log(logFile, "Extracting update archive");
            extractZip(sourceZip, extractedDirectory);

            validateExtractedUpdate(extractedDirectory);

            log(logFile, "Creating backup");
            copyDirectory(targetDirectory, backupDirectory);

            try {
                log(logFile, "Installing update");
                copyDirectory(extractedDirectory, targetDirectory);

                validateInstalledUpdate(targetDirectory);

                log(logFile, "Update completed successfully");

                try {
                    Files.deleteIfExists(sourceZip);
                    log(logFile, "Downloaded update archive deleted");
                } catch (IOException cleanupException) {
                    log(
                            logFile,
                            "Could not delete update archive: "
                                    + cleanupException.getMessage()
                    );
                }

                launchUpdatedLauncher(
                        targetDirectory,
                        logFile
                );

                try {
                    deleteDirectoryIfExists(extractedDirectory);
                    log(logFile, "Extracted update files deleted");
                } catch (IOException cleanupException) {
                    log(
                            logFile,
                            "Could not clean extracted files: "
                                    + cleanupException.getMessage()
                    );
                }

                System.out.println();
                System.out.println("ОБНОВЛЕНИЕ УСПЕШНО УСТАНОВЛЕНО");
                System.out.println("Папка: " + targetDirectory);
                System.out.println("Лог: " + logFile);
            } catch (Exception installationException) {
                log(
                        logFile,
                        "Installation failed: " + installationException
                );

                log(logFile, "Restoring backup");
                restoreBackup(backupDirectory, targetDirectory);
                log(logFile, "Backup restored successfully");

                throw new IOException(
                        "Обновление не установлено. "
                                + "Старая версия восстановлена.",
                        installationException
                );
            }
        } catch (Exception exception) {
            try {
                log(logFile, "Updater failed: " + exception);
            } catch (IOException ignored) {
                // Не удалось записать сообщение в лог.
            }

            System.err.println();
            System.err.println("ОШИБКА ОБНОВЛЕНИЯ");
            System.err.println(exception.getMessage());
            exception.printStackTrace(System.err);
            System.err.println();
            System.err.println("Лог: " + logFile);
            System.err.println("Нажми Enter для выхода.");

            try {
                System.in.read();
            } catch (IOException ignored) {
                // Ничего не делаем.
            }

            System.exit(1);
        }
    }

    /**
     * Ожидает завершения старого процесса Launcher.
     *
     * При отсутствии аргумента --pid ожидание пропускается,
     * что удобно для ручного локального теста.
     */
    private static void waitForProcess(
            String pidValue,
            Path logFile
    ) throws IOException, InterruptedException {
        if (pidValue == null || pidValue.isBlank()) {
            log(logFile, "Launcher PID was not provided");
            return;
        }

        long pid;

        try {
            pid = Long.parseLong(pidValue);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Некорректный PID лаунчера: " + pidValue,
                    exception
            );
        }

        if (pid <= 0) {
            throw new IOException(
                    "PID лаунчера должен быть положительным: " + pid
            );
        }

        log(logFile, "Waiting for launcher process: " + pid);

        ProcessHandle process = ProcessHandle
                .of(pid)
                .orElse(null);

        if (process == null || !process.isAlive()) {
            log(logFile, "Launcher process is already closed");
            return;
        }

        long startedAt = System.currentTimeMillis();

        while (process.isAlive()) {
            long elapsed = System.currentTimeMillis() - startedAt;

            if (elapsed > PROCESS_WAIT_TIMEOUT_MILLIS) {
                throw new IOException(
                        "Лаунчер не завершился за 30 секунд. PID: " + pid
                );
            }

            Thread.sleep(PROCESS_CHECK_INTERVAL_MILLIS);
        }

        log(logFile, "Launcher process closed");
    }

    /**
     * Запускает обновлённый Launcher после успешной замены файлов.
     */
    private static void launchUpdatedLauncher(
            Path targetDirectory,
            Path logFile
    ) throws IOException {
        Path javaw = targetDirectory
                .resolve("runtime")
                .resolve("bin")
                .resolve("javaw.exe")
                .toAbsolutePath()
                .normalize();

        Path javaFxDirectory = targetDirectory
                .resolve("javafx")
                .toAbsolutePath()
                .normalize();

        Path appDirectory = targetDirectory
                .resolve("app")
                .toAbsolutePath()
                .normalize();

        Path updaterJar = targetDirectory
                .resolve("updater")
                .resolve("guchicraft-updater.jar")
                .toAbsolutePath()
                .normalize();

        Path versionFile = targetDirectory
                .resolve("version.txt")
                .toAbsolutePath()
                .normalize();

        if (!Files.isRegularFile(javaw)) {
            throw new IOException(
                    "Не найдена встроенная Java: " + javaw
            );
        }

        if (!Files.isDirectory(javaFxDirectory)) {
            throw new IOException(
                    "Не найдена папка JavaFX: " + javaFxDirectory
            );
        }

        if (!Files.isDirectory(appDirectory)) {
            throw new IOException(
                    "Не найдена папка приложения: " + appDirectory
            );
        }

        if (!Files.isRegularFile(updaterJar)) {
            throw new IOException(
                    "Не найден Updater JAR: " + updaterJar
            );
        }

        if (!Files.isRegularFile(versionFile)) {
            throw new IOException(
                    "Не найден version.txt: " + versionFile
            );
        }

        String installedVersion = Files.readString(
                versionFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!installedVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IOException(
                    "Некорректная установленная версия: "
                            + installedVersion
            );
        }

        log(
                logFile,
                "Starting updated launcher directly with javaw.exe"
        );

        ProcessBuilder processBuilder = new ProcessBuilder(
                javaw.toString(),

                "--module-path",
                javaFxDirectory.toString(),

                "--add-modules",
                "javafx.controls",

                "-Dfile.encoding=UTF-8",

                "-Dguchicraft.launcher.version="
                        + installedVersion,

                "-Dguchicraft.launcher.root="
                        + targetDirectory
                        .toAbsolutePath()
                        .normalize(),

                "-Dguchicraft.updater.jar="
                        + updaterJar,

                "-cp",
                appDirectory.toString() + File.separator + "*",

                "ru.ezcraft.launcher.LauncherBootstrap"
        );

        processBuilder
                .directory(targetDirectory.toFile())
                .start();

        log(
                logFile,
                "Updated launcher process started successfully"
        );
    }

    private static void validatePaths(
            Path sourceZip,
            Path targetDirectory
    ) throws IOException {
        if (!Files.isRegularFile(sourceZip)) {
            throw new IOException(
                    "Архив обновления не найден: " + sourceZip
            );
        }

        if (!sourceZip.getFileName()
                .toString()
                .toLowerCase()
                .endsWith(".zip")) {
            throw new IOException(
                    "Файл обновления должен иметь расширение .zip"
            );
        }

        if (!Files.isDirectory(targetDirectory)) {
            throw new IOException(
                    "Целевая папка не найдена: " + targetDirectory
            );
        }

        if (!Files.isRegularFile(
                targetDirectory.resolve("version.txt")
        )) {
            throw new IOException(
                    "В целевой папке отсутствует version.txt"
            );
        }
    }

    private static void validateExtractedUpdate(
            Path extractedDirectory
    ) throws IOException {
        Path versionFile = extractedDirectory.resolve("version.txt");

        if (!Files.isRegularFile(versionFile)) {
            throw new IOException(
                    "В архиве обновления отсутствует version.txt"
            );
        }

        String version = Files.readString(
                versionFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IOException(
                    "Некорректная версия в архиве: " + version
            );
        }

        if (!Files.isDirectory(
                extractedDirectory.resolve("app")
        )) {
            throw new IOException(
                    "В архиве обновления отсутствует папка app"
            );
        }
    }

    private static void validateInstalledUpdate(
            Path targetDirectory
    ) throws IOException {
        Path versionFile = targetDirectory.resolve("version.txt");

        if (!Files.isRegularFile(versionFile)) {
            throw new IOException(
                    "После обновления отсутствует version.txt"
            );
        }

        String installedVersion = Files.readString(
                versionFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!installedVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IOException(
                    "После обновления записана некорректная версия: "
                            + installedVersion
            );
        }
    }

    /**
     * Распаковывает ZIP с защитой от Zip Slip:
     * файл внутри архива не сможет записаться вне целевой папки.
     */
    private static void extractZip(
            Path zipFile,
            Path destination
    ) throws IOException {
        try (ZipInputStream input = new ZipInputStream(
                Files.newInputStream(zipFile)
        )) {
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                Path output = destination
                        .resolve(entry.getName())
                        .normalize();

                if (!output.startsWith(destination)) {
                    throw new IOException(
                            "Опасный путь внутри ZIP: "
                                    + entry.getName()
                    );
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();

                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    Files.copy(
                            input,
                            output,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }

                input.closeEntry();
            }
        }
    }

    private static void copyDirectory(
            Path source,
            Path destination
    ) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        Path relative = source.relativize(directory);

                        Files.createDirectories(
                                destination.resolve(relative)
                        );

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        Path relative = source.relativize(file);

                        Files.copy(
                                file,
                                destination.resolve(relative),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES
                        );

                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }

    private static void restoreBackup(
            Path backupDirectory,
            Path targetDirectory
    ) throws IOException {
        deleteDirectoryContents(targetDirectory);
        copyDirectory(backupDirectory, targetDirectory);
    }

    private static void deleteDirectoryContents(
            Path directory
    ) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            paths.sorted(
                            (first, second) ->
                                    second.getNameCount()
                                            - first.getNameCount()
                    )
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause()
                    instanceof IOException ioException) {
                throw ioException;
            }

            throw exception;
        }
    }

    private static void deleteDirectoryIfExists(
            Path directory
    ) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            paths.sorted(
                            (first, second) ->
                                    second.getNameCount()
                                            - first.getNameCount()
                    )
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause()
                    instanceof IOException ioException) {
                throw ioException;
            }

            throw exception;
        }
    }

    private static Map<String, String> parseArguments(
            String[] args
    ) {
        Map<String, String> result = new HashMap<>();

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];

            if (!argument.startsWith("--")) {
                continue;
            }

            String key = argument.substring(2);

            if (index + 1 < args.length
                    && !args[index + 1].startsWith("--")) {
                result.put(key, args[++index]);
            } else {
                result.put(key, "true");
            }
        }

        return result;
    }

    private static void printUsage() {
        System.out.println("""
            Использование:

            java -jar guchicraft-updater.jar ^
              --source "D:\\Update\\GuchicraftLauncher-1.0.6-update.zip" ^
              --target "D:\\GuchicraftLauncher" ^
              --pid 12345

            Для ручного тестирования аргумент --pid можно не указывать.
            """);
    }

    private static void log(
            Path logFile,
            String message
    ) throws IOException {
        Files.createDirectories(logFile.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            writer.write(
                    "[" + LocalDateTime.now().format(LOG_TIME) + "] "
                            + message
            );

            writer.newLine();
        }
    }
}
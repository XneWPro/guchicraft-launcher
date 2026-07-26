package ru.guchicraft.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Маленький внешний процесс, который обновляет закрытый Launcher.
 * Не зависит от JavaFX и сторонних библиотек.
 */
public final class UpdaterMain {
    private static final Duration PROCESS_WAIT_TIMEOUT = Duration.ofMinutes(2);

    private UpdaterMain() {
    }

    public static void main(String[] args) {
        try {
            Arguments options = Arguments.parse(args);
            Path logFile = options.workDirectory().resolve("updater.log");
            Files.createDirectories(options.workDirectory());

            log(logFile, "Updater started");
            log(logFile, "Waiting for launcher PID " + options.launcherPid());
            waitForProcess(options.launcherPid(), PROCESS_WAIT_TIMEOUT);

            Path staging = options.workDirectory().resolve("staging-" + System.currentTimeMillis());
            deleteTree(staging);
            Files.createDirectories(staging);

            log(logFile, "Extracting " + options.updateZip());
            extractZipSafely(options.updateZip(), staging);
            Path sourceRoot = detectPayloadRoot(staging);

            log(logFile, "Installing into " + options.targetDirectory());
            Files.createDirectories(options.targetDirectory());
            copyTree(sourceRoot, options.targetDirectory());

            Files.deleteIfExists(options.updateZip());
            deleteTree(staging);

            log(logFile, "Starting " + options.launcherExecutable());
            new ProcessBuilder(options.launcherExecutable().toString())
                    .directory(options.targetDirectory().toFile())
                    .start();
            log(logFile, "Update completed successfully");
        } catch (Exception exception) {
            exception.printStackTrace();
            try {
                Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "guchicraft-updater-error.log");
                Files.writeString(fallback, Instant.now() + " " + exception + System.lineSeparator());
            } catch (IOException ignored) {
                // Nothing else can be done here.
            }
            System.exit(1);
        }
    }

    private static void waitForProcess(long pid, Duration timeout) throws InterruptedException {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handle.isAlive() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(250);
        }
        if (handle.isAlive()) {
            throw new IllegalStateException("Launcher did not close within " + timeout.toSeconds() + " seconds");
        }
        TimeUnit.MILLISECONDS.sleep(700);
    }

    private static void extractZipSafely(Path zip, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (InputStream input = Files.newInputStream(zip);
             ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                Path output = normalizedDestination.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(archive, output, StandardCopyOption.REPLACE_EXISTING);
                }
                archive.closeEntry();
            }
        }
    }

    private static Path detectPayloadRoot(Path staging) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(staging)) {
            Path only = null;
            int count = 0;
            for (Path child : stream) {
                only = child;
                count++;
                if (count > 1) {
                    return staging;
                }
            }
            return count == 1 && Files.isDirectory(only) ? only : staging;
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void log(Path logFile, String message) throws IOException {
        String line = Instant.now() + "  " + message + System.lineSeparator();
        Files.writeString(logFile, line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private record Arguments(
            long launcherPid,
            Path updateZip,
            Path targetDirectory,
            Path launcherExecutable,
            Path workDirectory
    ) {
        static Arguments parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (!argument.startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("Expected --name value, got: " + argument);
                }
                values.put(argument.substring(2), args[++i]);
            }
            return new Arguments(
                    Long.parseLong(require(values, "pid")),
                    normalized(require(values, "zip")),
                    normalized(require(values, "target")),
                    normalized(require(values, "executable")),
                    normalized(require(values, "work"))
            );
        }

        private static String require(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + name);
            }
            return value;
        }

        private static Path normalized(String value) {
            return Path.of(value).toAbsolutePath().normalize();
        }
    }
}

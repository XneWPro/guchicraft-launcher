package ru.ezcraft.launcher.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.guchicraft.common.hash.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public final class LauncherUpdateService {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public LauncherUpdateManifest load(
            String url
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header(
                        "User-Agent",
                        "Guchicraft-Launcher-Updater"
                )
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() == 404) {
            throw new UpdateNotConfiguredException(
                    "Файл launcher-update.json ещё не опубликован"
            );
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException(
                    "HTTP "
                            + response.statusCode()
                            + " при проверке обновления лаунчера"
            );
        }

        LauncherUpdateManifest manifest = mapper.readValue(
                response.body(),
                LauncherUpdateManifest.class
        );

        validate(manifest);
        return manifest;
    }

    public boolean isNewer(
            String remote,
            String current
    ) {
        List<Integer> remoteParts = parseVersion(remote);
        List<Integer> currentParts = parseVersion(current);

        int count = Math.max(
                remoteParts.size(),
                currentParts.size()
        );

        for (int index = 0; index < count; index++) {
            int remotePart = index < remoteParts.size()
                    ? remoteParts.get(index)
                    : 0;

            int currentPart = index < currentParts.size()
                    ? currentParts.get(index)
                    : 0;

            if (remotePart != currentPart) {
                return remotePart > currentPart;
            }
        }

        return false;
    }

    public Path download(
            LauncherUpdateManifest manifest,
            Path downloadsDirectory,
            BiConsumer<Long, Long> progress
    ) throws IOException, InterruptedException {
        Files.createDirectories(downloadsDirectory);

        Path target = downloadsDirectory.resolve(
                "guchicraft-launcher-"
                        + manifest.version()
                        + "-update.zip"
        );

        Path part = target.resolveSibling(
                target.getFileName() + ".part"
        );

        Files.deleteIfExists(target);
        Files.deleteIfExists(part);

        IOException lastFailure = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return downloadOnce(
                        manifest,
                        target,
                        part,
                        progress,
                        attempt
                );
            } catch (IOException exception) {
                lastFailure = exception;

                Files.deleteIfExists(part);
                Files.deleteIfExists(target);

                if (attempt >= 2) {
                    break;
                }

                /*
                 * Небольшая пауза перед повторной загрузкой.
                 */
                Thread.sleep(1500);
            }
        }

        throw new IOException(
                "Не удалось скачать обновление после двух попыток. "
                        + readableDownloadFailure(lastFailure),
                lastFailure
        );
    }

    private Path downloadOnce(
            LauncherUpdateManifest manifest,
            Path target,
            Path part,
            BiConsumer<Long, Long> progress,
            int attempt
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(manifest.downloadUrl())
                )
                .timeout(Duration.ofMinutes(10))
                .header(
                        "User-Agent",
                        "Guchicraft-Launcher-Updater"
                )
                .header(
                        "Cache-Control",
                        "no-cache"
                )
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() / 100 != 2) {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Ничего не делаем.
            }

            throw new IOException(
                    "HTTP "
                            + response.statusCode()
                            + " при загрузке обновления"
            );
        }

        long total = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(manifest.size());

        long completed = 0;

        try (
                InputStream input = response.body();
                var output = Files.newOutputStream(part)
        ) {
            byte[] buffer = new byte[128 * 1024];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                completed += read;

                if (progress != null) {
                    progress.accept(completed, total);
                }
            }
        } catch (IOException exception) {
            Files.deleteIfExists(part);

            throw new IOException(
                    "Попытка "
                            + attempt
                            + ": соединение прервано во время загрузки",
                    exception
            );
        }

        if (!Files.isRegularFile(part)) {
            throw new IOException(
                    "Попытка "
                            + attempt
                            + ": временный файл обновления не создан"
            );
        }

        long downloadedSize = Files.size(part);

        if (manifest.size() > 0
                && downloadedSize != manifest.size()) {
            Files.deleteIfExists(part);

            throw new IOException(
                    "Попытка "
                            + attempt
                            + ": загружено "
                            + downloadedSize
                            + " байт вместо "
                            + manifest.size()
            );
        }

        String actualHash;

        try {
            actualHash = Hashing.sha256(part);
        } catch (Exception exception) {
            Files.deleteIfExists(part);

            throw new IOException(
                    "Попытка "
                            + attempt
                            + ": не удалось вычислить SHA-256",
                    exception
            );
        }

        if (!actualHash.equalsIgnoreCase(manifest.sha256())) {
            Files.deleteIfExists(part);

            throw new IOException(
                    "Попытка "
                            + attempt
                            + ": контрольная сумма обновления не совпадает"
            );
        }

        try {
            Files.move(
                    part,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicMoveException) {
            Files.move(
                    part,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }

        return target;
    }

    private String readableDownloadFailure(
            IOException exception
    ) {
        if (exception == null) {
            return "Причина неизвестна.";
        }

        Throwable current = exception;
        String message = null;

        while (current != null) {
            if (current.getMessage() != null
                    && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }

            current = current.getCause();
        }

        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        if (message.contains("BUFFER_UNDERFLOW")
                || message.contains("EOF")) {
            return "GitHub преждевременно завершил передачу файла.";
        }

        return message;
    }
    /**
     * Ищет корень portable-лаунчера.
     *
     * В корне должны находиться:
     * version.txt, runtime, app и стартовый BAT-файл.
     */
    public Path resolveLauncherRoot() {
        String configuredRoot = System.getProperty(
                "guchicraft.launcher.root",
                ""
        ).trim();

        List<Path> candidates = new ArrayList<>();

        if (!configuredRoot.isBlank()) {
            candidates.add(Path.of(configuredRoot));
        }

        Path currentDirectory = Path.of("")
                .toAbsolutePath()
                .normalize();

        candidates.add(currentDirectory);
        candidates.add(currentDirectory.getParent());

        Path javaHome = Path.of(System.getProperty("java.home"))
                .toAbsolutePath()
                .normalize();

        /*
         * Для portable-сборки:
         *
         * launcherRoot/runtime/bin/java.exe
         *
         * java.home обычно равен launcherRoot/runtime.
         */
        candidates.add(javaHome.getParent());

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            Path normalized = candidate
                    .toAbsolutePath()
                    .normalize();

            if (isLauncherRoot(normalized)) {
                return normalized;
            }
        }

        throw new IllegalStateException(
                "Не удалось определить корневую папку "
                        + "portable-лаунчера. "
                        + "Не найден version.txt."
        );
    }

    private boolean isLauncherRoot(Path directory) {
        return Files.isDirectory(directory)
                && Files.isRegularFile(
                directory.resolve("version.txt")
        )
                && Files.isDirectory(
                directory.resolve("runtime")
        )
                && Files.isDirectory(
                directory.resolve("app")
        );
    }

    public Path resolveUpdaterJar(Path launcherRoot) {
        String configured = System.getProperty(
                "guchicraft.updater.jar",
                ""
        ).trim();

        List<Path> candidates = new ArrayList<>();

        if (!configured.isBlank()) {
            candidates.add(Path.of(configured));
        }

        candidates.add(
                launcherRoot
                        .resolve("updater")
                        .resolve("guchicraft-updater.jar")
        );

        candidates.add(
                launcherRoot
                        .resolve("app")
                        .resolve("guchicraft-updater.jar")
        );

        /*
         * Пути для запуска из IntelliJ.
         */
        candidates.add(
                Path.of(
                        "updater",
                        "target",
                        "guchicraft-updater.jar"
                )
        );

        candidates.add(
                Path.of(
                        "..",
                        "updater",
                        "target",
                        "guchicraft-updater.jar"
                )
        );

        for (Path candidate : candidates) {
            Path normalized = candidate
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }

        throw new IllegalStateException(
                "Не найден guchicraft-updater.jar. "
                        + "Собери модуль updater и затем "
                        + "пересобери portable-версию."
        );
    }

    public Path resolveUpdaterJava(Path launcherRoot) {
        List<Path> candidates = List.of(
                launcherRoot
                        .resolve("runtime")
                        .resolve("bin")
                        .resolve("java.exe"),

                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        "java.exe"
                )
        );

        for (Path candidate : candidates) {
            Path normalized = candidate
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }

        throw new IllegalStateException(
                "Не найдена Java для запуска Updater"
        );
    }

    /**
     * Запускает отдельный Updater.
     *
     * Updater получает PID текущего Launcher,
     * ждёт его завершения, заменяет файлы,
     * а затем запускает новый Launcher.
     */
    public void schedulePortableUpdater(
            Path updaterJava,
            Path updaterJar,
            Path updateZip,
            Path launcherRoot,
            Path updaterWorkDirectory
    ) throws IOException {
        if (!Files.isRegularFile(updaterJava)) {
            throw new IOException(
                    "Java Updater не найдена: " + updaterJava
            );
        }

        if (!Files.isRegularFile(updaterJar)) {
            throw new IOException(
                    "Updater JAR не найден: " + updaterJar
            );
        }

        if (!Files.isRegularFile(updateZip)) {
            throw new IOException(
                    "Архив обновления не найден: " + updateZip
            );
        }

        if (!Files.isDirectory(launcherRoot)) {
            throw new IOException(
                    "Корневая папка Launcher не найдена: "
                            + launcherRoot
            );
        }

        Files.createDirectories(updaterWorkDirectory);

        Path runnableUpdater = updaterWorkDirectory.resolve(
                "guchicraft-updater-run-"
                        + ProcessHandle.current().pid()
                        + ".jar"
        );

        Files.copy(
                updaterJar,
                runnableUpdater,
                StandardCopyOption.REPLACE_EXISTING
        );

        long launcherPid = ProcessHandle.current().pid();

        /*
         * Сам Updater также запускаем через javaw.exe,
         * чтобы не появлялась консоль.
         */
        Path javaw = updaterJava
                .resolveSibling("javaw.exe")
                .toAbsolutePath()
                .normalize();

        Path updaterExecutable = Files.isRegularFile(javaw)
                ? javaw
                : updaterJava;

        ProcessBuilder processBuilder = new ProcessBuilder(
                updaterExecutable.toString(),
                "-jar",
                runnableUpdater.toString(),

                "--source",
                updateZip
                        .toAbsolutePath()
                        .normalize()
                        .toString(),

                "--target",
                launcherRoot
                        .toAbsolutePath()
                        .normalize()
                        .toString(),

                "--pid",
                Long.toString(launcherPid)
        );

        processBuilder
                .directory(updaterWorkDirectory.toFile())
                .redirectOutput(
                        updaterWorkDirectory
                                .resolve("updater-console.log")
                                .toFile()
                )
                .redirectErrorStream(true)
                .start();
    }

    private void validate(
            LauncherUpdateManifest manifest
    ) {
        if (manifest == null) {
            throw new IllegalArgumentException(
                    "Пустой launcher-update.json"
            );
        }

        if (manifest.schemaVersion() != 1) {
            throw new IllegalArgumentException(
                    "Неподдерживаемый schemaVersion обновления"
            );
        }

        if (manifest.version() == null
                || manifest.version().isBlank()) {
            throw new IllegalArgumentException(
                    "Не указана версия лаунчера"
            );
        }

        if (!manifest.version()
                .matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException(
                    "Некорректная версия лаунчера: "
                            + manifest.version()
            );
        }

        if (manifest.downloadUrl() == null
                || manifest.downloadUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Не указана ссылка обновления"
            );
        }

        if (manifest.sha256() == null
                || !manifest.sha256()
                .matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Некорректный SHA-256 обновления"
            );
        }

        if (manifest.size() < 0) {
            throw new IllegalArgumentException(
                    "Размер обновления не может быть отрицательным"
            );
        }
    }

    private List<Integer> parseVersion(String value) {
        String normalized = value == null
                ? "0"
                : value.trim().replaceFirst("^[vV]", "");

        String numeric = normalized.split("[-+]", 2)[0];

        List<Integer> parts = new ArrayList<>();

        for (String part : numeric.split("\\.")) {
            String digits = part.replaceAll("[^0-9]", "");

            if (digits.isBlank()) {
                parts.add(0);
                continue;
            }

            try {
                parts.add(Integer.parseInt(digits));
            } catch (NumberFormatException ignored) {
                parts.add(0);
            }
        }

        return parts;
    }

    public static final class UpdateNotConfiguredException
            extends IOException {

        public UpdateNotConfiguredException(String message) {
            super(message);
        }
    }
}
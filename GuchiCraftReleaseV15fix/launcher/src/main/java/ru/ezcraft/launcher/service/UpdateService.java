package ru.ezcraft.launcher.service;

import ru.guchicraft.common.manifest.ClientManifest;
import ru.guchicraft.common.hash.Hashing;
import ru.ezcraft.launcher.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class UpdateService {
    private static final int BUFFER_SIZE = 128 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public LoadedManifest load(String url) throws Exception {
        URI source = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(source)
                .header("User-Agent", "GuchicraftLauncher/6")
                .header("Cache-Control", "no-cache")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("manifest.json недоступен: HTTP " + response.statusCode());
        }
        ClientManifest manifest = Json.MAPPER.readValue(response.body(), ClientManifest.class);
        validateManifest(manifest);
        return new LoadedManifest(source, manifest);
    }

    public SyncResult sync(
            LoadedManifest loaded,
            Path gameDirectory,
            Consumer<Progress> progressConsumer
    ) throws Exception {
        ClientManifest manifest = loaded.manifest();
        Files.createDirectories(gameDirectory);

        Set<Path> expectedFiles = new HashSet<>();
        long totalBytes = manifest.files().stream().mapToLong(entry -> Math.max(1, entry.size())).sum();
        long completedBytes = 0;
        int downloadedFiles = 0;
        int validFiles = 0;
        int removedFiles = 0;

        for (int index = 0; index < manifest.files().size(); index++) {
            ClientManifest.FileEntry file = manifest.files().get(index);
            Path output = safeResolve(gameDirectory, file.path());
            expectedFiles.add(output.normalize());

            boolean valid = Files.isRegularFile(output)
                    && Hashing.sha256(output).equalsIgnoreCase(file.sha256());

            if (valid) {
                validFiles++;
                completedBytes += Math.max(1, file.size());
                progressConsumer.accept(new Progress(
                        Phase.CHECKING,
                        file.path(),
                        completedBytes,
                        totalBytes,
                        0,
                        index + 1,
                        manifest.files().size()
                ));
                continue;
            }

            Files.createDirectories(output.getParent());
            Path temporary = output.resolveSibling(output.getFileName() + ".part");
            Files.deleteIfExists(temporary);
            URI fileUri = resolveFileUri(loaded.sourceUri(), file);

            HttpRequest request = HttpRequest.newBuilder(fileUri)
                    .header("User-Agent", "GuchicraftLauncher/6")
                    .timeout(Duration.ofMinutes(10))
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new IllegalStateException("Не удалось скачать " + file.path() + ": HTTP " + response.statusCode());
            }

            long fileDownloaded = 0;
            long started = System.nanoTime();
            try (InputStream input = response.body();
                 OutputStream outputStream = Files.newOutputStream(
                         temporary,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE
                 )) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    outputStream.write(buffer, 0, read);
                    fileDownloaded += read;
                    double seconds = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0);
                    long speed = (long) (fileDownloaded / seconds);
                    progressConsumer.accept(new Progress(
                            Phase.DOWNLOADING,
                            file.path(),
                            completedBytes + Math.min(fileDownloaded, Math.max(1, file.size())),
                            totalBytes,
                            speed,
                            index + 1,
                            manifest.files().size()
                    ));
                }
            } catch (Exception exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }

            String actualHash = Hashing.sha256(temporary);
            if (!actualHash.equalsIgnoreCase(file.sha256())) {
                Files.deleteIfExists(temporary);
                throw new SecurityException("Неверный SHA-256 у файла: " + file.path());
            }

            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }

            downloadedFiles++;
            completedBytes += Math.max(1, file.size());
        }

        if (manifest.removeUnknownFiles()) {
            removedFiles = cleanupManagedDirectories(gameDirectory, manifest, expectedFiles, progressConsumer);
        }

        progressConsumer.accept(new Progress(
                Phase.COMPLETE,
                "",
                totalBytes,
                totalBytes,
                0,
                manifest.files().size(),
                manifest.files().size()
        ));
        return new SyncResult(downloadedFiles, validFiles, removedFiles, totalBytes);
    }

    private int cleanupManagedDirectories(
            Path gameDirectory,
            ClientManifest manifest,
            Set<Path> expectedFiles,
            Consumer<Progress> progressConsumer
    ) throws IOException {
        int removed = 0;
        for (String managedDirectory : manifest.managedDirectories()) {
            Path root = safeResolve(gameDirectory, managedDirectory);
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    if (path.getFileName().toString().endsWith(".part")) {
                        Files.deleteIfExists(path);
                        continue;
                    }
                    if (!expectedFiles.contains(path.normalize())) {
                        String relative = gameDirectory.relativize(path).toString().replace('\\', '/');
                        progressConsumer.accept(new Progress(Phase.REMOVING, relative, 0, 0, 0, 0, 0));
                        Files.deleteIfExists(path);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private void validateManifest(ClientManifest manifest) {
        if (manifest.manifestVersion() != 1 && manifest.manifestVersion() != 2) {
            throw new IllegalStateException("Неподдерживаемая версия manifest.json: " + manifest.manifestVersion());
        }
        for (ClientManifest.FileEntry file : manifest.files()) {
            if (file.path() == null || file.path().isBlank()) {
                throw new IllegalStateException("В manifest.json найден пустой путь файла");
            }
            if (file.sha256() == null || !file.sha256().matches("[0-9a-fA-F]{64}")) {
                throw new IllegalStateException("Некорректный SHA-256: " + file.path());
            }
            if (file.size() < 0) {
                throw new IllegalStateException("Некорректный размер файла: " + file.path());
            }
        }
    }

    private URI resolveFileUri(URI manifestUri, ClientManifest.FileEntry file) {
        String url = file.url();
        if (url == null || url.isBlank()) {
            return manifestUri.resolve("files/" + file.path().replace('\\', '/'));
        }
        return manifestUri.resolve(url);
    }

    private Path safeResolve(Path gameDirectory, String relativePath) {
        Path normalizedGame = gameDirectory.toAbsolutePath().normalize();
        Path output = normalizedGame.resolve(relativePath).normalize();
        if (!output.startsWith(normalizedGame)) {
            throw new SecurityException("Недопустимый путь в manifest.json: " + relativePath);
        }
        return output;
    }

    public enum Phase { CHECKING, DOWNLOADING, REMOVING, COMPLETE }

    public record Progress(
            Phase phase,
            String file,
            long completedBytes,
            long totalBytes,
            long bytesPerSecond,
            int fileIndex,
            int fileCount
    ) {
        public double fraction() {
            return totalBytes <= 0 ? 0 : Math.min(1, completedBytes / (double) totalBytes);
        }
    }

    public record SyncResult(int downloadedFiles, int validFiles, int removedFiles, long totalBytes) {
    }

    public record LoadedManifest(URI sourceUri, ClientManifest manifest) {
    }
}

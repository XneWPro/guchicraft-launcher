package ru.ezcraft.launcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import ru.guchicraft.common.hash.Hashing;
import ru.ezcraft.launcher.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Находит или автоматически устанавливает отдельную Eclipse Temurin Java для Minecraft.
 * Runtime хранится в %APPDATA%/Guchicraft/runtime и не меняет системный JAVA_HOME/PATH.
 */
public final class RuntimeManager {
    private static final String ADOPTIUM_ASSETS =
            "https://api.adoptium.net/v3/assets/latest/%d/hotspot" +
            "?architecture=x64&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public Path ensureRuntime(
            int requiredMajor,
            Path launcherRoot,
            Consumer<String> status,
            DoubleConsumer progress
    ) throws Exception {
        if (requiredMajor < 8) {
            throw new IllegalArgumentException("Некорректная требуемая версия Java: " + requiredMajor);
        }

        Path runtimeDirectory = launcherRoot.resolve("runtime");
        Path bundledJava = runtimeDirectory.resolve("bin").resolve("java.exe");
        if (isSuitableJava(bundledJava, requiredMajor)) {
            status.accept("Встроенная Java " + requiredMajor + " готова");
            progress.accept(1.0);
            return bundledJava;
        }

        Path installed = findInstalledJava(requiredMajor);
        if (installed != null) {
            status.accept("Найдена Java " + detectJavaMajor(installed) + " на компьютере");
            progress.accept(1.0);
            return installed;
        }

        status.accept("Получение данных Eclipse Temurin Java " + requiredMajor + "…");
        progress.accept(0.03);
        RuntimePackage runtimePackage = resolveLatestPackage(requiredMajor);

        Files.createDirectories(launcherRoot.resolve("downloads"));
        Path archive = launcherRoot.resolve("downloads").resolve("temurin-" + requiredMajor + "-windows-x64.zip");
        status.accept("Скачивание Java " + requiredMajor + "…");
        download(runtimePackage.link(), archive, runtimePackage.size(), status, progress);

        if (runtimePackage.sha256() != null && !runtimePackage.sha256().isBlank()) {
            status.accept("Проверка Java по SHA-256…");
            String actual = Hashing.sha256(archive);
            if (!actual.equalsIgnoreCase(runtimePackage.sha256())) {
                Files.deleteIfExists(archive);
                throw new IllegalStateException("Контрольная сумма загруженной Java не совпала. Попробуй ещё раз.");
            }
        }
        progress.accept(0.78);

        status.accept("Распаковка Java " + requiredMajor + "…");
        Path staging = launcherRoot.resolve("runtime-installing");
        deleteRecursively(staging);
        Files.createDirectories(staging);
        unzipSecurely(archive, staging, fraction -> progress.accept(0.78 + fraction * 0.18));

        Path extractedHome = locateJavaHome(staging);
        if (extractedHome == null) {
            deleteRecursively(staging);
            throw new IllegalStateException("В архиве Java не найден bin\\java.exe");
        }

        deleteRecursively(runtimeDirectory);
        Files.createDirectories(runtimeDirectory.getParent());
        moveDirectoryContents(extractedHome, runtimeDirectory);
        deleteRecursively(staging);
        Files.deleteIfExists(archive);

        if (!isSuitableJava(bundledJava, requiredMajor)) {
            throw new IllegalStateException("Java распакована, но проверка версии не прошла: " + bundledJava);
        }

        Files.writeString(runtimeDirectory.resolve("runtime-version.txt"),
                "provider=Eclipse Temurin\nmajor=" + detectJavaMajor(bundledJava) + "\n");
        status.accept("Java " + requiredMajor + " установлена");
        progress.accept(1.0);
        return bundledJava;
    }

    private RuntimePackage resolveLatestPackage(int major) throws Exception {
        String url = ADOPTIUM_ASSETS.formatted(major);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json")
                .header("User-Agent", "GuchicraftLauncher/8.0")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Adoptium API вернул HTTP " + response.statusCode());
        }

        JsonNode root = Json.MAPPER.readTree(response.body());
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("Для Java " + major + " не найдена Windows x64 сборка Eclipse Temurin");
        }
        JsonNode binary = root.get(0).path("binary");
        JsonNode pkg = binary.path("package");
        String link = pkg.path("link").asText("");
        if (link.isBlank()) {
            throw new IllegalStateException("Adoptium API не вернул ссылку на архив Java");
        }
        return new RuntimePackage(
                link,
                pkg.path("checksum").asText(""),
                pkg.path("size").asLong(-1)
        );
    }

    private void download(
            String url,
            Path target,
            long expectedSize,
            Consumer<String> status,
            DoubleConsumer progress
    ) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temporary);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "GuchicraftLauncher/8.0")
                .GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Не удалось скачать Java: HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(expectedSize);
        long completed = 0;
        long started = System.nanoTime();
        try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count == 0) continue;
                output.write(buffer, 0, count);
                completed += count;
                if (total > 0) progress.accept(0.05 + Math.min(0.70, 0.70 * completed / total));
                long elapsedNanos = Math.max(1, System.nanoTime() - started);
                long bytesPerSecond = (long) (completed * 1_000_000_000.0 / elapsedNanos);
                status.accept("Скачивание Java: " + formatBytes(completed) +
                        (total > 0 ? " / " + formatBytes(total) : "") +
                        " • " + formatBytes(bytesPerSecond) + "/с");
            }
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void unzipSecurely(Path archive, Path target, DoubleConsumer progress) throws IOException {
        long archiveSize = Math.max(1, Files.size(archive));
        long compressedRead = 0;
        try (InputStream raw = Files.newInputStream(archive);
             CountingInputStream counting = new CountingInputStream(raw);
             ZipInputStream zip = new ZipInputStream(counting)) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                Path output = target.resolve(entry.getName()).normalize();
                if (!output.startsWith(target)) {
                    throw new IOException("Опасный путь в ZIP: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (var stream = Files.newOutputStream(output)) {
                        for (int count; (count = zip.read(buffer)) >= 0; ) {
                            if (count > 0) stream.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
                compressedRead = counting.count();
                progress.accept(Math.min(1.0, compressedRead / (double) archiveSize));
            }
        }
    }

    private Path locateJavaHome(Path staging) throws IOException {
        Path direct = staging.resolve("bin").resolve("java.exe");
        if (Files.isRegularFile(direct)) return staging;
        try (var paths = Files.walk(staging, 4)) {
            return paths.filter(path -> path.getFileName().toString().equalsIgnoreCase("java.exe"))
                    .filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .map(path -> path.getParent().getParent())
                    .findFirst().orElse(null);
        }
    }

    private void moveDirectoryContents(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var children = Files.list(source)) {
            for (Path child : children.toList()) {
                Files.move(child, destination.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path findInstalledJava(int requiredMajor) {
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome, "bin", "java.exe"));
        candidates.add(Path.of(System.getProperty("java.home"), "bin", "java.exe"));
        addInstalledJdks(candidates, Path.of("C:\\Program Files\\Eclipse Adoptium"));
        addInstalledJdks(candidates, Path.of("C:\\Program Files\\Java"));
        addInstalledJdks(candidates, Path.of("C:\\Program Files\\Microsoft"));
        return candidates.stream().filter(path -> isSuitableJava(path, requiredMajor)).findFirst().orElse(null);
    }

    private void addInstalledJdks(java.util.List<Path> candidates, Path root) {
        if (!Files.isDirectory(root)) return;
        try (var directories = Files.list(root)) {
            directories.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(directory -> candidates.add(directory.resolve("bin").resolve("java.exe")));
        } catch (Exception ignored) {
        }
    }

    public int detectJavaMajor(Path javaExecutable) throws Exception {
        Process process = new ProcessBuilder(javaExecutable.toString(), "-version")
                .redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining(" "));
        }
        process.waitFor();
        Matcher matcher = Pattern.compile("version \\\"(\\d+)").matcher(output);
        if (!matcher.find()) throw new IllegalStateException("Не удалось определить версию Java: " + output);
        return Integer.parseInt(matcher.group(1));
    }

    private boolean isSuitableJava(Path executable, int requiredMajor) {
        try {
            return Files.isRegularFile(executable) && detectJavaMajor(executable) >= requiredMajor;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f МБ", mb);
        return String.format(Locale.ROOT, "%.2f ГБ", mb / 1024.0);
    }

    private record RuntimePackage(String link, String sha256, long size) {
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;
        private CountingInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) count += read;
            return read;
        }
        long count() { return count; }
    }
}

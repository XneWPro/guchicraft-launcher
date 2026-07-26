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
import java.util.Locale;
import java.util.function.BiConsumer;

public final class LauncherUpdateService {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public LauncherUpdateManifest load(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Guchicraft-Launcher-Updater")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new UpdateNotConfiguredException("Файл launcher-update.json ещё не опубликован");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " при проверке обновления лаунчера");
        }
        LauncherUpdateManifest manifest = mapper.readValue(response.body(), LauncherUpdateManifest.class);
        validate(manifest);
        return manifest;
    }

    public boolean isNewer(String remote, String current) {
        List<Integer> remoteParts = parseVersion(remote);
        List<Integer> currentParts = parseVersion(current);
        int count = Math.max(remoteParts.size(), currentParts.size());
        for (int i = 0; i < count; i++) {
            int left = i < remoteParts.size() ? remoteParts.get(i) : 0;
            int right = i < currentParts.size() ? currentParts.get(i) : 0;
            if (left != right) return left > right;
        }
        return false;
    }

    public Path download(LauncherUpdateManifest manifest, Path downloadsDirectory,
                         BiConsumer<Long, Long> progress) throws IOException, InterruptedException {
        Files.createDirectories(downloadsDirectory);
        Path target = downloadsDirectory.resolve("guchicraft-launcher-" + manifest.version() + ".zip");
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(part);

        HttpRequest request = HttpRequest.newBuilder(URI.create(manifest.downloadUrl()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Guchicraft-Launcher-Updater")
                .GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " при загрузке обновления");
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(manifest.size());
        long completed = 0;
        try (InputStream input = response.body(); var output = Files.newOutputStream(part)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                completed += read;
                progress.accept(completed, total);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(part);
            throw exception;
        }
        if (manifest.size() > 0 && Files.size(part) != manifest.size()) {
            Files.deleteIfExists(part);
            throw new IOException("Размер скачанного обновления не совпадает с launcher-update.json");
        }
        String actual;
        try {
            actual = Hashing.sha256(part);
        } catch (Exception exception) {
            Files.deleteIfExists(part);
            throw new IOException("Не удалось вычислить SHA-256 обновления", exception);
        }
        if (!actual.equalsIgnoreCase(manifest.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException("SHA-256 обновления не совпадает");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    public boolean isPackagedApplication() {
        String command = ProcessHandle.current().info().command().orElse("").toLowerCase(Locale.ROOT);
        return command.endsWith(".exe") && !command.endsWith("java.exe") && !command.endsWith("javaw.exe");
    }

    public void scheduleWindowsPortableUpdate(Path zip, Path applicationDirectory, Path executable) throws IOException {
        if (!isPackagedApplication()) {
            throw new IllegalStateException("Автозамена доступна после сборки лаунчера в приложение .exe");
        }
        Path script = zip.resolveSibling("apply-launcher-update.ps1");
        long pid = ProcessHandle.current().pid();
        String ps = """
                $ErrorActionPreference = 'Stop'
                $pidToWait = %d
                $zip = '%s'
                $target = '%s'
                $exe = '%s'
                try { Wait-Process -Id $pidToWait -Timeout 60 -ErrorAction SilentlyContinue } catch {}
                Start-Sleep -Milliseconds 800
                $staging = Join-Path $env:TEMP ('GuchicraftUpdate-' + [guid]::NewGuid())
                New-Item -ItemType Directory -Force -Path $staging | Out-Null
                Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force
                $root = Get-ChildItem -LiteralPath $staging | Select-Object -First 1
                if ($root.PSIsContainer) { $source = $root.FullName } else { $source = $staging }
                Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force
                Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
                Start-Process -FilePath $exe
                Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
                """.formatted(pid, escapePs(zip), escapePs(applicationDirectory), escapePs(executable));
        Files.writeString(script, ps);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                .start();
    }

    private static String escapePs(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private void validate(LauncherUpdateManifest manifest) {
        if (manifest.schemaVersion() != 1) throw new IllegalArgumentException("Неподдерживаемый schemaVersion обновления");
        if (manifest.version() == null || manifest.version().isBlank()) throw new IllegalArgumentException("Не указана версия лаунчера");
        if (manifest.downloadUrl() == null || manifest.downloadUrl().isBlank()) throw new IllegalArgumentException("Не указана ссылка обновления");
        if (manifest.sha256() == null || !manifest.sha256().matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("Некорректный SHA-256 обновления");
    }

    private List<Integer> parseVersion(String value) {
        String normalized = value == null ? "0" : value.trim().replaceFirst("^[vV]", "");
        String numeric = normalized.split("[-+]", 2)[0];
        List<Integer> parts = new ArrayList<>();
        for (String part : numeric.split("\\.")) {
            try { parts.add(Integer.parseInt(part.replaceAll("[^0-9]", ""))); }
            catch (NumberFormatException ignored) { parts.add(0); }
        }
        return parts;
    }

    public static final class UpdateNotConfiguredException extends IOException {
        public UpdateNotConfiguredException(String message) { super(message); }
    }
}

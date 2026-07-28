package ru.ezcraft.launcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ru.guchicraft.common.hash.Hashing;
import ru.ezcraft.launcher.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Устанавливает официальный клиент Minecraft и профиль Fabric в отдельную папку лаунчера.
 * Формат файлов соответствует метаданным Mojang/Piston и Fabric Meta API.
 */
public final class MinecraftInstallationService {
    private static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_PROFILE =
            "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";
    private static final String ASSET_OBJECT =
            "https://resources.download.minecraft.net/%s/%s";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(25))
            .build();

    public InstalledMinecraft install(
            String minecraftVersion,
            String fabricLoaderVersion,
            Path gameDirectory,
            Consumer<String> status,
            DoubleConsumer progress
    ) throws Exception {
        Path metadataDir = gameDirectory.resolve("launcher-meta");
        Path librariesDir = gameDirectory.resolve("libraries");
        Path assetsDir = gameDirectory.resolve("assets");
        Path versionsDir = gameDirectory.resolve("versions");
        Path nativesDir = gameDirectory.resolve("natives").resolve(minecraftVersion + "-fabric");
        Files.createDirectories(metadataDir);
        Files.createDirectories(librariesDir);
        Files.createDirectories(assetsDir);
        Files.createDirectories(versionsDir);
        Files.createDirectories(nativesDir);

        status.accept("Получение данных Minecraft " + minecraftVersion + "…");
        JsonNode manifest = readJson(VERSION_MANIFEST);
        JsonNode versionEntry = findVersion(manifest.path("versions"), minecraftVersion);
        String versionJsonUrl = requiredText(versionEntry, "url");
        JsonNode vanilla = readJson(versionJsonUrl);
        progress.accept(0.05);

        status.accept("Получение профиля Fabric " + fabricLoaderVersion + "…");
        String fabricUrl = FABRIC_PROFILE.formatted(minecraftVersion, fabricLoaderVersion);
        JsonNode fabric = readJson(fabricUrl);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                metadataDir.resolve("minecraft-" + minecraftVersion + ".json").toFile(), vanilla);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                metadataDir.resolve("fabric-" + fabricLoaderVersion + ".json").toFile(), fabric);
        progress.accept(0.10);

        status.accept("Загрузка клиента Minecraft…");
        JsonNode client = vanilla.path("downloads").path("client");
        Path clientJar = versionsDir.resolve(minecraftVersion).resolve(minecraftVersion + ".jar");
        downloadVerified(requiredText(client, "url"), clientJar, client.path("sha1").asText(""));
        progress.accept(0.18);

        List<Path> classpath = new ArrayList<>();
        Set<String> downloadedLibraries = new HashSet<>();
        List<JsonNode> allLibraries = new ArrayList<>();
        vanilla.path("libraries").forEach(allLibraries::add);
        fabric.path("libraries").forEach(allLibraries::add);

        int libraryIndex = 0;
        int libraryCount = Math.max(1, allLibraries.size());
        for (JsonNode library : allLibraries) {
            libraryIndex++;
            if (!rulesAllow(library.path("rules"))) {
                continue;
            }
            status.accept("Библиотеки: " + library.path("name").asText("файл") + "…");
            Path artifact = installLibraryArtifact(library, librariesDir, downloadedLibraries);
            if (artifact != null) {
                classpath.add(artifact);
            }
            installAndExtractNative(library, librariesDir, nativesDir, downloadedLibraries);
            progress.accept(0.18 + 0.36 * libraryIndex / libraryCount);
        }
        classpath.add(clientJar);

        status.accept("Загрузка списка ресурсов…");
        JsonNode assetIndex = vanilla.path("assetIndex");
        String assetId = requiredText(assetIndex, "id");
        Path assetIndexFile = assetsDir.resolve("indexes").resolve(assetId + ".json");
        downloadVerified(requiredText(assetIndex, "url"), assetIndexFile, assetIndex.path("sha1").asText(""));
        JsonNode assetObjects = Json.MAPPER.readTree(assetIndexFile.toFile()).path("objects");
        int totalAssets = Math.max(1, assetObjects.size());
        int currentAsset = 0;
        var fields = assetObjects.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            currentAsset++;
            String hash = requiredText(entry.getValue(), "hash");
            Path objectFile = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!isValidSha1(objectFile, hash)) {
                status.accept("Ресурсы Minecraft: " + currentAsset + " / " + totalAssets);
                downloadVerified(ASSET_OBJECT.formatted(hash.substring(0, 2), hash), objectFile, hash);
            }
            if (currentAsset % 25 == 0 || currentAsset == totalAssets) {
                progress.accept(0.54 + 0.42 * currentAsset / totalAssets);
            }
        }

        status.accept("Подготовка запуска Fabric…");
        String mainClass = requiredText(fabric, "mainClass");
        int requiredJava = vanilla.path("javaVersion").path("majorVersion").asInt(8);
        List<String> jvmArguments = collectArguments(vanilla.path("arguments").path("jvm"));
        List<String> gameArguments = collectGameArguments(vanilla);
        progress.accept(1.0);

        return new InstalledMinecraft(
                minecraftVersion,
                fabric.path("id").asText("fabric-loader-" + fabricLoaderVersion + "-" + minecraftVersion),
                mainClass,
                requiredJava,
                assetId,
                assetsDir,
                nativesDir,
                classpath,
                jvmArguments,
                gameArguments
        );
    }

    private JsonNode findVersion(JsonNode versions, String id) {
        for (JsonNode version : versions) {
            if (id.equals(version.path("id").asText())) {
                return version;
            }
        }
        throw new IllegalStateException("Версия Minecraft " + id + " отсутствует в официальном списке Mojang");
    }

    private Path installLibraryArtifact(JsonNode library, Path librariesDir, Set<String> downloaded) throws Exception {
        JsonNode artifact = library.path("downloads").path("artifact");
        if (!artifact.isMissingNode() && artifact.hasNonNull("url")) {
            Path output = librariesDir.resolve(requiredText(artifact, "path"));
            downloadOnce(artifact, output, downloaded);
            return output;
        }

        String name = library.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }
        String path = mavenPath(name, null);
        String baseUrl = library.path("url").asText("https://libraries.minecraft.net/");
        Path output = librariesDir.resolve(path);
        downloadOnce(baseUrl + path.replace('\\', '/'), output, "", downloaded);
        return output;
    }

    private void installAndExtractNative(
            JsonNode library,
            Path librariesDir,
            Path nativesDir,
            Set<String> downloaded
    ) throws Exception {
        String classifierName = library.path("natives").path("windows").asText("");
        if (classifierName.isBlank()) {
            return;
        }
        classifierName = classifierName.replace("${arch}", System.getProperty("os.arch").contains("64") ? "64" : "32");
        JsonNode classifier = library.path("downloads").path("classifiers").path(classifierName);
        Path nativeJar;
        if (!classifier.isMissingNode() && classifier.hasNonNull("url")) {
            nativeJar = librariesDir.resolve(requiredText(classifier, "path"));
            downloadOnce(classifier, nativeJar, downloaded);
        } else {
            String name = library.path("name").asText("");
            if (name.isBlank()) return;
            String path = mavenPath(name, classifierName);
            String baseUrl = library.path("url").asText("https://libraries.minecraft.net/");
            nativeJar = librariesDir.resolve(path);
            downloadOnce(baseUrl + path.replace('\\', '/'), nativeJar, "", downloaded);
        }
        extractNatives(nativeJar, nativesDir, library.path("extract").path("exclude"));
    }

    private void extractNatives(Path jar, Path nativesDir, JsonNode excludes) throws IOException {
        Set<String> excluded = new HashSet<>();
        if (excludes.isArray()) excludes.forEach(node -> excluded.add(node.asText()));
        Map<String, String> env = new HashMap<>();
        env.put("create", "false");
        try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), env)) {
            for (Path root : zip.getRootDirectories()) {
                try (var paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile).forEach(source -> {
                        String relative = root.relativize(source).toString().replace('\\', '/');
                        if (relative.startsWith("META-INF/") || excluded.stream().anyMatch(relative::startsWith)) return;
                        Path target = nativesDir.resolve(relative).normalize();
                        if (!target.startsWith(nativesDir)) return;
                        try {
                            Files.createDirectories(target.getParent());
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
                }
            }
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException io) throw io;
            throw exception;
        }
    }

    private List<String> collectArguments(JsonNode arguments) {
        List<String> result = new ArrayList<>();
        if (!arguments.isArray()) return result;
        for (JsonNode argument : arguments) {
            if (argument.isTextual()) {
                result.add(argument.asText());
            } else if (argument.isObject() && rulesAllow(argument.path("rules"))) {
                JsonNode value = argument.path("value");
                if (value.isTextual()) result.add(value.asText());
                else if (value.isArray()) value.forEach(node -> result.add(node.asText()));
            }
        }
        return result;
    }

    private List<String> collectGameArguments(JsonNode vanilla) {
        JsonNode modern = vanilla.path("arguments").path("game");
        if (modern.isArray()) return collectArguments(modern);
        String legacy = vanilla.path("minecraftArguments").asText("");
        if (legacy.isBlank()) return new ArrayList<>();
        return new ArrayList<>(List.of(legacy.split("\\s+")));
    }

    private boolean rulesAllow(JsonNode rules) {
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonNode rule : rules) {
            if (!ruleMatches(rule)) continue;
            allowed = "allow".equals(rule.path("action").asText());
        }
        return allowed;
    }

    private boolean ruleMatches(JsonNode rule) {
        JsonNode os = rule.path("os");
        if (!os.isMissingNode() && os.has("name") && !"windows".equals(os.path("name").asText())) return false;
        JsonNode features = rule.path("features");
        if (features.isObject()) {
            // Пока специальные launcher-функции отключены: demo, custom resolution и Quick Play.
            var iterator = features.fields();
            while (iterator.hasNext()) {
                var feature = iterator.next();
                boolean actual = false;
                if (actual != feature.getValue().asBoolean()) return false;
            }
        }
        return true;
    }

    private String mavenPath(String coordinate, String classifier) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) throw new IllegalArgumentException("Некорректная Maven-координата: " + coordinate);
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String extension = parts.length >= 4 && !parts[3].isBlank() ? parts[3] : "jar";
        String effectiveClassifier = classifier;
        if ((effectiveClassifier == null || effectiveClassifier.isBlank()) && parts.length >= 5) {
            effectiveClassifier = parts[4];
        }
        String filename = artifact + "-" + version +
                (effectiveClassifier == null || effectiveClassifier.isBlank() ? "" : "-" + effectiveClassifier) +
                "." + extension;
        return group + "/" + artifact + "/" + version + "/" + filename;
    }

    private JsonNode readJson(String url) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " при загрузке " + url);
        }
        return Json.MAPPER.readTree(response.body());
    }

    private void downloadOnce(JsonNode metadata, Path output, Set<String> downloaded) throws Exception {
        downloadOnce(requiredText(metadata, "url"), output, metadata.path("sha1").asText(""), downloaded);
    }

    private void downloadOnce(String url, Path output, String sha1, Set<String> downloaded) throws Exception {
        String key = output.toAbsolutePath().normalize().toString();
        if (!downloaded.add(key)) return;
        downloadVerified(url, output, sha1);
    }

    private void downloadVerified(
            String url,
            Path output,
            String sha1
    ) throws Exception {
        if (isValidSha1(output, sha1)) {
            return;
        }

        Files.createDirectories(output.getParent());

        Path temporary = output.resolveSibling(
                output.getFileName() + ".part"
        );

        Exception lastFailure = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            Files.deleteIfExists(temporary);

            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(url)
                        )
                        .timeout(Duration.ofMinutes(10))
                        .header("User-Agent", "Guchicraft-Launcher")
                        .header("Cache-Control", "no-cache")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = http.send(
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
                                    + " при загрузке "
                                    + url
                    );
                }

                try (
                        InputStream input = response.body();
                        var outputStream = Files.newOutputStream(temporary)
                ) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;

                    while ((read = input.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                }

                if (!Files.isRegularFile(temporary)) {
                    throw new IOException(
                            "Временный файл не был создан: "
                                    + temporary
                    );
                }

                if (sha1 != null
                        && !sha1.isBlank()
                        && !Hashing.sha1(temporary)
                        .equalsIgnoreCase(sha1)) {
                    throw new SecurityException(
                            "Контрольная сумма файла не совпала: "
                                    + output.getFileName()
                    );
                }

                try {
                    Files.move(
                            temporary,
                            output,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (IOException ignored) {
                    Files.move(
                            temporary,
                            output,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }

                return;
            } catch (Exception exception) {
                lastFailure = exception;
                Files.deleteIfExists(temporary);

                if (attempt < 3) {
                    Thread.sleep(1500L * attempt);
                }
            }
        }

        String reason = readableDownloadFailure(lastFailure);

        throw new IOException(
                "Не удалось скачать файл после трёх попыток: "
                        + output.getFileName()
                        + ". "
                        + reason,
                lastFailure
        );
    }

    private String readableDownloadFailure(
            Exception exception
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
            return "Сервер преждевременно завершил передачу файла.";
        }

        return message;
    }

    private boolean isValidSha1(Path file, String sha1) {
        if (!Files.isRegularFile(file)) return false;
        if (sha1 == null || sha1.isBlank()) return true;
        try {
            return Hashing.sha1(file).equalsIgnoreCase(sha1);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalStateException("В метаданных отсутствует поле: " + field);
        return value;
    }

    public record InstalledMinecraft(
            String minecraftVersion,
            String versionName,
            String mainClass,
            int requiredJavaMajor,
            String assetIndex,
            Path assetsDirectory,
            Path nativesDirectory,
            List<Path> classpath,
            List<String> jvmArguments,
            List<String> gameArguments
    ) {
    }
}

package ru.guchicraft.builder;

import ru.guchicraft.common.manifest.ClientManifest;
import ru.guchicraft.common.hash.Hashing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class ManifestBuilder {
    private static final Set<String> ALLOWED_ROOTS = Set.of("mods", "config", "resourcepacks");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public BuildResult build(
            Path repositoryRoot,
            String buildVersion,
            String minecraftVersion,
            String fabricLoaderVersion,
            int javaMajorVersion,
            int minimumMemoryMb,
            int defaultMemoryMb,
            int maximumMemoryMb,
            String serverAddress,
            boolean removeUnknownFiles
    ) throws IOException {
        Path launcherDir = repositoryRoot.resolve("launcher").normalize();
        Path filesRoot = launcherDir.resolve("files").normalize();

        if (!Files.isDirectory(filesRoot)) {
            throw new IOException("Не найдена папка: " + filesRoot);
        }

        List<ClientManifest.FileEntry> entries = new ArrayList<>();
        try (var stream = Files.walk(filesRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredFile(path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> entries.add(createEntry(filesRoot, path)));
        }

        List<String> managedDirectories = entries.stream()
                .map(ClientManifest.FileEntry::path)
                .map(value -> value.replace('\\', '/'))
                .map(value -> value.contains("/") ? value.substring(0, value.indexOf('/')) : value)
                .filter(ALLOWED_ROOTS::contains)
                .distinct()
                .sorted()
                .toList();

        ClientManifest document = new ClientManifest(
                2,
                clean(buildVersion, "1.0.0"),
                clean(minecraftVersion, "26.2"),
                clean(fabricLoaderVersion, "0.19.3"),
                new ClientManifest.JavaConfiguration(javaMajorVersion),
                new ClientManifest.LaunchConfiguration(minimumMemoryMb, defaultMemoryMb, maximumMemoryMb),
                clean(serverAddress, "guchicraft.peniscraft.pro"),
                managedDirectories,
                removeUnknownFiles,
                entries
        );

        Files.createDirectories(launcherDir);
        Path output = launcherDir.resolve("manifest.json");
        Files.writeString(output, gson.toJson(document));

        long totalBytes = entries.stream().mapToLong(ClientManifest.FileEntry::size).sum();
        long mods = entries.stream().filter(e -> e.path().startsWith("mods/")).count();
        long configs = entries.stream().filter(e -> e.path().startsWith("config/")).count();
        long resourcepacks = entries.stream().filter(e -> e.path().startsWith("resourcepacks/")).count();
        return new BuildResult(output, entries.size(), totalBytes, mods, configs, resourcepacks);
    }

    private ClientManifest.FileEntry createEntry(Path filesRoot, Path file) {
        try {
            String relative = filesRoot.relativize(file).toString().replace('\\', '/');
            String url = "files/" + relative;
            return new ClientManifest.FileEntry(relative, url, Hashing.sha256(file), Files.size(file));
        } catch (Exception exception) {
            throw new ManifestBuildRuntimeException(exception);
        }
    }

    private static boolean isIgnoredFile(Path path) {
        String name = path.getFileName().toString();
        return name.equalsIgnoreCase(".gitkeep")
                || name.equalsIgnoreCase(",gitkeep")
                || name.equalsIgnoreCase(".DS_Store")
                || name.equalsIgnoreCase("Thumbs.db");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record BuildResult(Path output, long totalFiles, long totalBytes, long mods, long configs, long resourcepacks) {
    }

    private static final class ManifestBuildRuntimeException extends RuntimeException {
        private ManifestBuildRuntimeException(Exception cause) {
            super(cause);
        }
    }
}

package ru.guchicraft.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class BuildValidator {
    private static final Set<String> MANAGED = Set.of("mods", "config", "resourcepacks");
    private static final Pattern VERSION_TOKEN = Pattern.compile("(?i)(?:[-_. ](?:v)?\\d+(?:[._-]\\d+)*(?:[-_.]?(?:alpha|beta|rc)\\d*)?)$");

    public ValidationResult validate(Path repositoryRoot) throws IOException {
        List<ValidationIssue> issues = new ArrayList<>();
        Path launcher = repositoryRoot.resolve("launcher");
        Path filesRoot = launcher.resolve("files");

        if (!Files.isDirectory(launcher)) {
            issues.add(error("Не найдена папка launcher: " + launcher));
            return empty(issues);
        }
        if (!Files.isDirectory(filesRoot)) {
            issues.add(error("Не найдена папка launcher/files: " + filesRoot));
            return empty(issues);
        }

        for (String folder : MANAGED) {
            Path dir = filesRoot.resolve(folder);
            if (!Files.isDirectory(dir)) {
                issues.add(warning("Отсутствует папка launcher/files/" + folder));
            }
        }

        List<Path> files;
        try (var stream = Files.walk(filesRoot)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase(".gitkeep"))
                    .sorted()
                    .toList();
        }

        long bytes = 0;
        long mods = 0;
        long configs = 0;
        long resourcepacks = 0;
        Map<String, List<Path>> hashes = new LinkedHashMap<>();
        Map<String, List<Path>> modFamilies = new TreeMap<>();
        boolean fabricApiFound = false;

        for (Path file : files) {
            bytes += Files.size(file);
            String relative = filesRoot.relativize(file).toString().replace('\\', '/');
            String root = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
            if (!MANAGED.contains(root)) {
                issues.add(warning("Файл находится вне управляемых папок и всё равно попадёт в manifest: " + relative));
            }

            if (relative.startsWith("mods/")) {
                mods++;
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    issues.add(warning("В папке mods найден не JAR-файл: " + relative));
                } else {
                    validateJar(file, relative, issues);
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("fabric-api")) fabricApiFound = true;
                    modFamilies.computeIfAbsent(modFamily(name), ignored -> new ArrayList<>()).add(file);
                }
            } else if (relative.startsWith("config/")) {
                configs++;
            } else if (relative.startsWith("resourcepacks/")) {
                resourcepacks++;
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".zip") || lower.endsWith(".json") || lower.endsWith(".png") || lower.endsWith(".mcmeta"))) {
                    issues.add(warning("Необычный файл в resourcepacks: " + relative));
                }
            }

            String hash = sha256(file);
            hashes.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(file);
        }

        if (mods == 0) {
            issues.add(error("Папка launcher/files/mods не содержит модов."));
        } else if (!fabricApiFound) {
            issues.add(warning("Fabric API не найден. Большинство Fabric-модов без него не запустятся."));
        }
        if (configs == 0) {
            issues.add(info("Папка config пустая. Это допустимо, если модам не нужны заранее подготовленные настройки."));
        }

        hashes.values().stream().filter(list -> list.size() > 1).forEach(list -> {
            String joined = list.stream().map(filesRoot::relativize).map(Path::toString).map(v -> v.replace('\\', '/')).reduce((a,b) -> a + ", " + b).orElse("");
            issues.add(warning("Найдены полностью одинаковые файлы: " + joined));
        });

        modFamilies.forEach((family, list) -> {
            if (!family.isBlank() && list.size() > 1) {
                String joined = list.stream().map(path -> path.getFileName().toString()).reduce((a,b) -> a + ", " + b).orElse("");
                issues.add(warning("Возможны несколько версий одного мода [" + family + "]: " + joined));
            }
        });

        if (issues.stream().noneMatch(i -> i.severity() != ValidationIssue.Severity.INFO)) {
            issues.add(info("Критических ошибок и предупреждений не найдено."));
        }
        return new ValidationResult(List.copyOf(issues), files.size(), bytes, mods, configs, resourcepacks);
    }

    private static void validateJar(Path file, String relative, List<ValidationIssue> issues) {
        try (JarFile jar = new JarFile(file.toFile(), true)) {
            if (jar.size() == 0) {
                issues.add(error("Пустой JAR-файл: " + relative));
                return;
            }
            boolean hasFabricMetadata = jar.getEntry("fabric.mod.json") != null;
            if (!hasFabricMetadata) {
                issues.add(warning("В JAR не найден fabric.mod.json — возможно, это не Fabric-мод: " + relative));
            }
        } catch (IOException exception) {
            issues.add(error("Повреждённый или некорректный JAR: " + relative));
        }
    }

    private static String modFamily(String filename) {
        String base = filename.replaceFirst("(?i)\\.jar$", "")
                .replaceAll("(?i)(?:[-_. ](?:fabric|quilt|forge|neoforge))(?=[-_. ]|$)", "")
                .replaceAll("(?i)(?:[-_. ](?:mc)?\\d+(?:[._-]\\d+)+)", "");
        String previous;
        do {
            previous = base;
            base = VERSION_TOKEN.matcher(base).replaceFirst("");
        } while (!base.equals(previous));
        return base.replaceAll("[-_. ]+", "-").replaceAll("^-|-$", "").toLowerCase(Locale.ROOT);
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
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int read; (read = in.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IllegalStateException(e);
        }
    }

    private static ValidationResult empty(List<ValidationIssue> issues) {
        return new ValidationResult(List.copyOf(issues), 0, 0, 0, 0, 0);
    }
    private static ValidationIssue error(String text) { return new ValidationIssue(ValidationIssue.Severity.ERROR, text); }
    private static ValidationIssue warning(String text) { return new ValidationIssue(ValidationIssue.Severity.WARNING, text); }
    private static ValidationIssue info(String text) { return new ValidationIssue(ValidationIssue.Severity.INFO, text); }
}

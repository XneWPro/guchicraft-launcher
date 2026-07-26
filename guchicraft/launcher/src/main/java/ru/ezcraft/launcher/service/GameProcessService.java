package ru.ezcraft.launcher.service;

import ru.ezcraft.launcher.auth.OfflineAccount;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GameProcessService {
    public Process launch(
            Path gameDirectory,
            Path javaExecutable,
            int memoryMb,
            OfflineAccount account,
            String serverAddress,
            MinecraftInstallationService.InstalledMinecraft installation,
            Consumer<String> logConsumer
    ) throws Exception {
        int installedJava = detectJavaMajor(javaExecutable);
        if (installedJava < installation.requiredJavaMajor()) {
            throw new IllegalStateException(
                    "Для Minecraft " + installation.minecraftVersion() + " нужна Java " +
                            installation.requiredJavaMajor() + ", а найдена Java " + installedJava +
                            ". Установи JDK " + installation.requiredJavaMajor() +
                            " и укажи её через JAVA_HOME либо дождись этапа со встроенной Java."
            );
        }

        Files.createDirectories(gameDirectory);
        String classpath = installation.classpath().stream()
                .map(Path::toString)
                .collect(Collectors.joining(System.getProperty("path.separator")));

        Map<String, String> variables = new HashMap<>();
        variables.put("${auth_player_name}", account.username());
        variables.put("${version_name}", installation.versionName());
        variables.put("${game_directory}", gameDirectory.toString());
        variables.put("${assets_root}", installation.assetsDirectory().toString());
        variables.put("${assets_index_name}", installation.assetIndex());
        variables.put("${auth_uuid}", account.uuid().toString().replace("-", ""));
        variables.put("${auth_access_token}", "0");
        variables.put("${auth_session}", "0");
        variables.put("${user_type}", "legacy");
        variables.put("${version_type}", "Guchicraft");
        variables.put("${user_properties}", "{}");
        variables.put("${clientid}", "");
        variables.put("${auth_xuid}", "");
        variables.put("${natives_directory}", installation.nativesDirectory().toString());
        variables.put("${launcher_name}", "guchicraft-launcher");
        variables.put("${launcher_version}", "4.0");
        variables.put("${classpath}", classpath);
        variables.put("${classpath_separator}", System.getProperty("path.separator"));
        variables.put("${library_directory}", gameDirectory.resolve("libraries").toString());

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xms512M");
        command.add("-Xmx" + memoryMb + "M");
        command.add("-Dfile.encoding=UTF-8");

        for (String argument : installation.jvmArguments()) {
            String resolved = substitute(argument, variables);
            if (!resolved.isBlank() && !isMemoryArgument(resolved)) command.add(resolved);
        }
        if (command.stream().noneMatch(value -> value.equals("-cp") || value.equals("-classpath"))) {
            command.add("-cp");
            command.add(classpath);
        }

        command.add(installation.mainClass());
        for (String argument : installation.gameArguments()) {
            String resolved = substitute(argument, variables);
            if (!resolved.isBlank()) command.add(resolved);
        }
        // Современный аргумент Mojang: после загрузки клиент сразу подключается к серверу.
        if (serverAddress != null && !serverAddress.isBlank()) {
            command.add("--quickPlayMultiplayer");
            // Передаём Minecraft адрес ровно в том виде, как он записан в manifest.
            // Если это SRV-домен, клиент сам найдёт фактический порт.
            command.add(serverAddress.trim());
        }

        Path logsDirectory = gameDirectory.resolve("logs");
        Files.createDirectories(logsDirectory);
        Path launcherLog = logsDirectory.resolve("launcher-game.log");
        Files.writeString(
                launcherLog,
                "=== GUCHICRAFT launch " + Instant.now() + " ===" + System.lineSeparator()
                        + "Java: " + javaExecutable + System.lineSeparator()
                        + "Memory: " + memoryMb + " MB" + System.lineSeparator()
                        + "Server: " + (serverAddress == null ? "" : serverAddress) + System.lineSeparator()
                        + "Command: " + command.stream().map(this::quoteForLog).collect(Collectors.joining(" "))
                        + System.lineSeparator() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        Process process = new ProcessBuilder(command)
                .directory(gameDirectory.toFile())
                .redirectErrorStream(true)
                .start();

        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var writer = Files.newBufferedWriter(launcherLog, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    logConsumer.accept(line);
                }
            } catch (Exception ignored) {
            }
        }, "minecraft-output");
        logThread.setDaemon(true);
        logThread.start();
        return process;
    }

    private String quoteForLog(String value) {
        if (value == null) return "";
        return value.contains(" ") || value.contains("\t") ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
    }

    private int detectJavaMajor(Path javaExecutable) throws Exception {
        Process process = new ProcessBuilder(javaExecutable.toString(), "-version")
                .redirectErrorStream(true)
                .start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining(" "));
        }
        process.waitFor();
        var matcher = java.util.regex.Pattern.compile("version \\\"(\\d+)").matcher(output);
        if (!matcher.find()) throw new IllegalStateException("Не удалось определить версию Java: " + output);
        return Integer.parseInt(matcher.group(1));
    }

    private boolean isMemoryArgument(String value) {
        return value.startsWith("-Xmx") || value.startsWith("-Xms");
    }

    private String substitute(String value, Map<String, String> variables) {
        String result = value;
        for (var entry : variables.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }
}

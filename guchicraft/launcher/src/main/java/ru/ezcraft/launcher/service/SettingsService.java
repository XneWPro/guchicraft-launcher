package ru.ezcraft.launcher.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public final class SettingsService {
    private static final String DEFAULT_NICKNAME = "Player";
    private static final int DEFAULT_MEMORY_MB = 4096;
    private static final boolean DEFAULT_HIDE_AFTER_START = false;

    private final Path settingsFile;
    private final Properties properties = new Properties();

    public SettingsService(Path settingsFile) {
        this.settingsFile = settingsFile;
        load();
    }

    public String getNickname() {
        return properties.getProperty("nickname", DEFAULT_NICKNAME);
    }

    public int getMemoryMb() {
        try {
            int value = Integer.parseInt(properties.getProperty("memoryMb", String.valueOf(DEFAULT_MEMORY_MB)));
            return Math.max(2048, Math.min(16384, value));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MEMORY_MB;
        }
    }

    public boolean isHideAfterStart() {
        return Boolean.parseBoolean(properties.getProperty(
                "hideAfterStart",
                String.valueOf(DEFAULT_HIDE_AFTER_START)
        ));
    }

    public void save(String nickname, int memoryMb, boolean hideAfterStart) {
        properties.setProperty("nickname", nickname);
        properties.setProperty("memoryMb", String.valueOf(memoryMb));
        properties.setProperty("hideAfterStart", String.valueOf(hideAfterStart));

        try {
            Files.createDirectories(settingsFile.getParent());
            try (OutputStream output = Files.newOutputStream(
                    settingsFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                properties.store(output, "Guchicraft Launcher settings");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить настройки лаунчера", exception);
        }
    }

    private void load() {
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }

        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
        } catch (IOException ignored) {
            properties.clear();
        }
    }
}

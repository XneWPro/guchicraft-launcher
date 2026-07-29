package ru.ezcraft.launcher.update;

public record LauncherUpdateManifest(
        int schemaVersion,
        String version,
        String downloadUrl,
        String sha256,
        long size,
        boolean mandatory,
        String changelog
) { }

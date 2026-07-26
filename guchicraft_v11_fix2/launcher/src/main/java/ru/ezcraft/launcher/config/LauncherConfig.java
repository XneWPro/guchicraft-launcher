package ru.ezcraft.launcher.config;

public record LauncherConfig(
        String launcherName,
        String launcherVersion,
        String serverAddress,
        String manifestUrl,
        String updateManifestUrl,
        String dataDirectory
) {
    public static LauncherConfig defaults() {
        return new LauncherConfig(
                "ГУЧИКРАФТ!",
                "1.0.0",
                "guchicraft.peniscraft.pro",
                "https://raw.githubusercontent.com/XneWPro/guchicraft-launcher-files/main/launcher/manifest.json",
                "https://raw.githubusercontent.com/XneWPro/guchicraft-launcher-files/main/launcher/launcher-update.json",
                "Guchicraft"
        );
    }
}

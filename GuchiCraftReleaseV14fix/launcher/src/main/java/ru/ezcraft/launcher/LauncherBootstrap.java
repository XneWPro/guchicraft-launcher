package ru.ezcraft.launcher;

/**
 * Ordinary Java entry point used by portable/release launchers.
 * Keeping this class separate from JavaFX Application avoids the special
 * Java launcher check that otherwise reports "JavaFX runtime components are missing"
 * before our configured module path is processed.
 */
public final class LauncherBootstrap {
    private LauncherBootstrap() {
    }

    public static void main(String[] args) {
        LauncherApplication.main(args);
    }
}

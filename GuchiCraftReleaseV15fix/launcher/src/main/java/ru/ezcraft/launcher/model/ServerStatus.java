package ru.ezcraft.launcher.model;

public record ServerStatus(boolean online, int onlinePlayers, int maxPlayers, long latencyMs, String description) {
    public static ServerStatus offline() {
        return new ServerStatus(false, 0, 0, -1, "Сервер недоступен");
    }
}

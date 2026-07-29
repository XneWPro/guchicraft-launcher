package ru.ezcraft.launcher.auth;
import java.nio.charset.StandardCharsets;import java.util.UUID;
public record OfflineAccount(String username,UUID uuid){ public static OfflineAccount of(String name){if(!name.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("Ник: 3–16 символов, только латиница, цифры и _");return new OfflineAccount(name,UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)));}}

package ru.guchicraft.common.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {
    }

    public static String sha256(Path path) throws IOException {
        return digest(path, "SHA-256");
    }

    public static String sha1(Path path) throws IOException {
        return digest(path, "SHA-1");
    }

    private static String digest(Path path, String algorithm) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Алгоритм хеширования недоступен: " + algorithm, exception);
        }

        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }
}

package ru.ezcraft.launcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import ru.ezcraft.launcher.model.ServerStatus;
import ru.ezcraft.launcher.util.Json;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;

/**
 * Запрашивает Minecraft Server List Ping.
 *
 * <p>Если адрес не содержит явного порта, сначала проверяется DNS SRV-запись
 * _minecraft._tcp.&lt;домен&gt;. Это позволяет корректно работать с адресами,
 * которые игрок вводит в Minecraft без порта.</p>
 */
public final class MinecraftServerStatusService {
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int TIMEOUT_MS = 3500;

    public ServerStatus query(String address) {
        if (address == null || address.isBlank()) {
            return ServerStatus.offline();
        }

        try {
            ServerEndpoint endpoint = resolve(address.trim());
            return ping(endpoint);
        } catch (Exception ignored) {
            // Статус сервера является только информационным и никогда не должен
            // блокировать обновление модпака или запуск игры.
            return ServerStatus.offline();
        }
    }

    private ServerStatus ping(ServerEndpoint endpoint) throws Exception {
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.connectHost(), endpoint.connectPort()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());

            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, -1); // status ping не зависит от конкретной версии протокола
            writeString(handshake, endpoint.handshakeHost());
            handshake.writeShort(endpoint.connectPort());
            writeVarInt(handshake, 1);
            writePacket(output, handshakeBytes.toByteArray());

            writePacket(output, new byte[]{0x00});

            readVarInt(input); // packet length
            int packetId = readVarInt(input);
            if (packetId != 0x00) {
                throw new IllegalStateException("Неожиданный пакет статуса: " + packetId);
            }

            int jsonLength = readVarInt(input);
            byte[] jsonBytes = input.readNBytes(jsonLength);
            if (jsonBytes.length != jsonLength) {
                throw new IllegalStateException("Ответ сервера получен не полностью");
            }

            JsonNode root = Json.MAPPER.readTree(new String(jsonBytes, StandardCharsets.UTF_8));
            JsonNode players = root.path("players");
            int online = players.path("online").asInt(0);
            int max = players.path("max").asInt(0);
            String description = extractDescription(root.path("description"));
            long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            return new ServerStatus(true, online, max, latency, description);
        }
    }

    private ServerEndpoint resolve(String address) {
        ExplicitAddress explicit = parseExplicitAddress(address);
        if (explicit.port() != null) {
            return new ServerEndpoint(explicit.host(), explicit.host(), explicit.port());
        }

        ServerEndpoint srv = resolveSrv(explicit.host());
        if (srv != null) {
            return srv;
        }
        return new ServerEndpoint(explicit.host(), explicit.host(), DEFAULT_MINECRAFT_PORT);
    }

    private ExplicitAddress parseExplicitAddress(String address) {
        // Поддержка IPv6 в квадратных скобках: [::1]:25565
        if (address.startsWith("[")) {
            int closing = address.indexOf(']');
            if (closing > 0) {
                String host = address.substring(1, closing);
                if (closing + 1 < address.length() && address.charAt(closing + 1) == ':') {
                    return new ExplicitAddress(host, parsePort(address.substring(closing + 2)));
                }
                return new ExplicitAddress(host, null);
            }
        }

        int firstColon = address.indexOf(':');
        int lastColon = address.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon && lastColon < address.length() - 1) {
            Integer port = parsePort(address.substring(lastColon + 1));
            if (port != null) {
                return new ExplicitAddress(address.substring(0, lastColon), port);
            }
        }
        return new ExplicitAddress(address, null);
    }

    private Integer parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ServerEndpoint resolveSrv(String originalHost) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "1500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");

        try {
            InitialDirContext context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes("_minecraft._tcp." + originalHost, new String[]{"SRV"});
            Attribute records = attributes.get("SRV");
            if (records == null || records.size() == 0) {
                return null;
            }

            List<SrvRecord> parsed = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                String[] parts = String.valueOf(records.get(i)).trim().split("\\s+");
                if (parts.length != 4) continue;
                try {
                    int priority = Integer.parseInt(parts[0]);
                    int weight = Integer.parseInt(parts[1]);
                    int port = Integer.parseInt(parts[2]);
                    String target = stripTrailingDot(parts[3]);
                    if (!target.isBlank() && port >= 1 && port <= 65535) {
                        parsed.add(new SrvRecord(priority, weight, port, target));
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            return parsed.stream()
                    .min(Comparator.comparingInt(SrvRecord::priority)
                            .thenComparing(Comparator.comparingInt(SrvRecord::weight).reversed()))
                    .map(record -> new ServerEndpoint(originalHost, record.target(), record.port()))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private String extractDescription(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.has("text")) return node.path("text").asText("");
        return "";
    }

    private void writePacket(DataOutputStream output, byte[] payload) throws Exception {
        writeVarInt(output, payload.length);
        output.write(payload);
        output.flush();
    }

    private void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private void writeVarInt(DataOutputStream output, int value) throws Exception {
        do {
            byte temp = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) temp |= (byte) 0x80;
            output.writeByte(temp);
        } while (value != 0);
    }

    private int readVarInt(DataInputStream input) throws Exception {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = input.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position >= 32) throw new IllegalStateException("VarInt слишком большой");
        } while ((current & 0x80) != 0);
        return value;
    }

    private record ExplicitAddress(String host, Integer port) {}
    private record ServerEndpoint(String handshakeHost, String connectHost, int connectPort) {}
    private record SrvRecord(int priority, int weight, int port, String target) {}
}

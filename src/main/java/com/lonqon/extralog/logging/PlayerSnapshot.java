package com.lonqon.extralog.logging;

import com.lonqon.extralog.config.LogDetails;
import com.lonqon.extralog.config.PluginSettings;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PlayerSnapshot(
        String name,
        UUID uuid,
        String ip,
        String world,
        String xyz,
        String gamemode,
        String ping,
        String client,
        String version,
        String platform,
        String hostname
) {
    public static PlayerSnapshot from(Player player, PluginSettings settings) {
        LogDetails details = settings.logDetails();
        InetSocketAddress address = player.getAddress();
        String ip = settings.ipLoggingEnabled() && address != null && address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : settings.ipLoggingEnabled() ? "unknown" : "disabled";
        String world = details.location() ? player.getWorld().getName() : null;
        String xyz = details.location() ? (int) Math.floor(player.getLocation().getX()) + "," + (int) Math.floor(player.getLocation().getY()) + "," + (int) Math.floor(player.getLocation().getZ()) : null;
        GameMode gameMode = player.getGameMode();
        String gamemode = details.gamemode() && gameMode != null ? gameMode.name() : null;
        String ping = details.ping() ? String.valueOf(player.getPing()) : null;
        String client = details.clientInfo() ? invokeString(player, "getClientBrandName", "unknown") : null;
        String version = details.clientInfo() ? invokeString(player, "getProtocolVersion", "unknown") : null;
        String platform = details.clientInfo() ? isFloodgatePlayer(player.getUniqueId()) ? "BEDROCK" : "JAVA" : null;
        String hostname = details.hostname() ? virtualHost(player) : null;
        return new PlayerSnapshot(player.getName(), player.getUniqueId(), ip, world, xyz, gamemode, ping, client, version, platform, hostname);
    }

    public Map<String, String> fields() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("ip", ip);
        if (world != null) {
            fields.put("world", world);
        }
        if (xyz != null) {
            fields.put("xyz", xyz);
        }
        if (gamemode != null) {
            fields.put("gm", gamemode);
        }
        if (ping != null) {
            fields.put("ping", ping);
        }
        if (client != null) {
            fields.put("client", client);
        }
        if (version != null) {
            fields.put("ver", version);
        }
        if (platform != null) {
            fields.put("platform", platform);
        }
        if (hostname != null) {
            fields.put("host", hostname);
        }
        return fields;
    }

    private static String invokeString(Player player, String method, String fallback) {
        try {
            Object value = player.getClass().getMethod(method).invoke(player);
            return value == null ? fallback : String.valueOf(value);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static String virtualHost(Player player) {
        try {
            Object value = player.getClass().getMethod("getVirtualHost").invoke(player);
            if (value instanceof InetSocketAddress address) {
                return address.getHostString();
            }
            return value == null ? "unknown" : String.valueOf(value);
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static boolean isFloodgatePlayer(UUID uuid) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
            return result instanceof Boolean value && value;
        } catch (Exception exception) {
            return false;
        }
    }
}

package com.lonqon.extralog.config;

import java.util.List;
import java.util.Map;

public record DiscordSettings(
        boolean enabled,
        Map<String, String> webhooks,
        Map<String, List<String>> routing,
        Map<String, Integer> embedColors,
        String serverName,
        boolean playerHeadThumbnail,
        int queueCapacity,
        int shutdownTimeoutMillis,
        DiscordBotSettings bot
) {
    public DiscordSettings {
        webhooks = Map.copyOf(webhooks);
        routing = routing.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        embedColors = Map.copyOf(embedColors);
    }

    public static DiscordSettings defaults() {
        return new DiscordSettings(
                false,
                Map.of("general", "", "staff", "", "security", ""),
                Map.of("join", List.of("general", "staff"), "quit", List.of("general"), "player-commands", List.of("staff"), "console-commands", List.of("staff"), "vpn-suspected", List.of("security", "staff")),
                Map.of("join", 65280, "quit", 8421504, "command", 3447003, "vpn", 16753920),
                "My Server",
                false,
                1000,
                5000,
                DiscordBotSettings.defaults()
        );
    }
}

package com.lonqon.extralog.config;

import java.util.Set;

public record DiscordBotSettings(
        boolean enabled,
        String token,
        String guildId,
        Set<String> allowedUserIds,
        Set<String> allowedRoleIds
) {
    public DiscordBotSettings {
        allowedUserIds = Set.copyOf(allowedUserIds);
        allowedRoleIds = Set.copyOf(allowedRoleIds);
    }

    public static DiscordBotSettings defaults() {
        return new DiscordBotSettings(false, "", "", Set.of(), Set.of());
    }
}

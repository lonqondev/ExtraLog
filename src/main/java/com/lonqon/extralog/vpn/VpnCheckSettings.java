package com.lonqon.extralog.vpn;

import java.util.Set;

public record VpnCheckSettings(
        boolean enabled,
        VpnProvider provider,
        String apiKey,
        int cacheDays,
        int timeoutMillis,
        Set<String> whitelistIps,
        Set<String> whitelistPlayers,
        String action,
        String securityWebhookUrl
) {
    public VpnCheckSettings {
        whitelistIps = Set.copyOf(whitelistIps);
        whitelistPlayers = Set.copyOf(whitelistPlayers);
    }

    public static VpnCheckSettings defaults() {
        return new VpnCheckSettings(
                false,
                VpnProvider.PROXYCHECK,
                "",
                14,
                3000,
                Set.of(),
                Set.of(),
                "WARN",
                ""
        );
    }

    public VpnCheckSettings withWhitelist(Set<String> ips, Set<String> players) {
        return new VpnCheckSettings(
                enabled,
                provider,
                apiKey,
                cacheDays,
                timeoutMillis,
                ips,
                players,
                action,
                securityWebhookUrl
        );
    }
}

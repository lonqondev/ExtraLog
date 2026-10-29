package com.lonqon.extralog.vpn;

import java.util.UUID;

public record VpnLookupOutcome(
        Status status,
        String ip,
        String playerName,
        UUID uuid,
        VpnProviderResult result,
        String message,
        boolean fromCache,
        long checkedAt
) {
    public enum Status {
        SKIPPED,
        CLEAN,
        SUSPECTED,
        UNAVAILABLE
    }
}

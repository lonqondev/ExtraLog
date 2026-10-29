package com.lonqon.extralog.vpn;

public record VpnCacheEntry(
        boolean vpn,
        String providerName,
        String country,
        String risk,
        long checkedAt
) {
}

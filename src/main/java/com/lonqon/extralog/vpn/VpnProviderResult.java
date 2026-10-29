package com.lonqon.extralog.vpn;

public record VpnProviderResult(
        boolean vpn,
        String providerName,
        String country,
        String risk
) {
}

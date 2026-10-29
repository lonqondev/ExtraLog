package com.lonqon.extralog.vpn;

import java.util.Locale;

public enum VpnProvider {
    PROXYCHECK,
    IPHUB,
    IPQUALITYSCORE;

    public static VpnProvider parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return value.trim().toUpperCase(Locale.ROOT).equals("IPQUALITYSCORE")
                    ? IPQUALITYSCORE
                    : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public boolean requiresApiKey() {
        return this != PROXYCHECK;
    }
}

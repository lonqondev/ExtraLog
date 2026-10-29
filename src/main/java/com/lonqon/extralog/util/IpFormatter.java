package com.lonqon.extralog.util;

public final class IpFormatter {
    private IpFormatter() {
    }

    public static String maskForPublicDiscord(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String[] parts = address.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".xx.xx";
        }
        if (address.contains(":")) {
            int firstSeparator = address.indexOf(':');
            int secondSeparator = address.indexOf(':', firstSeparator + 1);
            if (secondSeparator > 0) {
                return address.substring(0, secondSeparator) + ":xxxx:xxxx";
            }
            return "xxxx:xxxx";
        }
        return "masked";
    }

    public static String formatForDestination(String address, boolean maskForPublicDiscord) {
        if (address == null || address.equalsIgnoreCase("disabled") || address.equalsIgnoreCase("unknown")) {
            return address == null ? "unknown" : address;
        }
        return maskForPublicDiscord ? maskForPublicDiscord(address) : address;
    }
}

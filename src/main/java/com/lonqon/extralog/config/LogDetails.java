package com.lonqon.extralog.config;

public record LogDetails(
        boolean location,
        boolean gamemode,
        boolean ping,
        boolean clientInfo,
        boolean hostname
) {
    public static LogDetails defaults() {
        return new LogDetails(true, true, true, true, true);
    }
}

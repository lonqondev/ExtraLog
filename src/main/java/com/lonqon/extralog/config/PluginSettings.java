package com.lonqon.extralog.config;

import com.lonqon.extralog.vpn.VpnCheckSettings;

import java.util.Set;

public record PluginSettings(
        boolean ipLoggingEnabled,
        boolean publicDiscordMask,
        String logDirectory,
        long flushIntervalMillis,
        int queueCapacity,
        long maxFileSizeBytes,
        boolean joinLoggingEnabled,
        boolean quitLoggingEnabled,
        boolean playerCommandLoggingEnabled,
        boolean consoleCommandLoggingEnabled,
        Set<String> argumentBlacklist,
        Set<String> contentHiddenCommands,
        VpnCheckSettings vpnCheck,
        String language,
        LogDetails logDetails,
        String logFormat,
        boolean compressOldLogs,
        int retentionDays,
        DiscordSettings discord
) {
    public PluginSettings {
        argumentBlacklist = Set.copyOf(argumentBlacklist);
        contentHiddenCommands = Set.copyOf(contentHiddenCommands);
    }

    public static PluginSettings defaults() {
        return new PluginSettings(
                true,
                true,
                "logs",
                1000L,
                10000,
                10485760L,
                true,
                true,
                true,
                true,
                Set.of("login", "l", "register", "reg", "password", "pass", "changepassword", "changepass", "unregister", "unreg"),
                Set.of("msg", "tell", "w", "r", "whisper"),
                VpnCheckSettings.defaults(),
                "en",
                LogDetails.defaults(),
                "TEXT",
                true,
                30,
                DiscordSettings.defaults()
        );
    }

    public PluginSettings withVpnCheck(VpnCheckSettings value) {
        return new PluginSettings(
                ipLoggingEnabled,
                publicDiscordMask,
                logDirectory,
                flushIntervalMillis,
                queueCapacity,
                maxFileSizeBytes,
                joinLoggingEnabled,
                quitLoggingEnabled,
                playerCommandLoggingEnabled,
                consoleCommandLoggingEnabled,
                argumentBlacklist,
                contentHiddenCommands,
                value,
                language,
                logDetails,
                logFormat,
                compressOldLogs,
                retentionDays,
                discord
        );
    }
}

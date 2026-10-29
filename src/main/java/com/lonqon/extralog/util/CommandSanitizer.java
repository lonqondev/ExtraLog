package com.lonqon.extralog.util;

import com.lonqon.extralog.config.PluginSettings;

import java.util.Locale;

public final class CommandSanitizer {
    private CommandSanitizer() {
    }

    public static String sanitize(String rawCommand, PluginSettings settings) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return "unknown";
        }
        String commandLine = rawCommand.trim();
        String commandToken = commandLine.split("\\s+", 2)[0];
        String commandName = normalize(commandToken);
        String displayName = commandName.isEmpty() ? "unknown" : "/" + commandName;
        if (settings.argumentBlacklist().contains(commandName) || settings.contentHiddenCommands().contains(commandName)) {
            return displayName;
        }
        return commandLine.startsWith("/") ? commandLine : "/" + commandLine;
    }

    public static String normalize(String token) {
        String command = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        int separator = command.lastIndexOf(':');
        if (separator >= 0 && separator + 1 < command.length()) {
            command = command.substring(separator + 1);
        }
        return command;
    }
}

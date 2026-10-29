package com.lonqon.extralog.listener;

import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.config.PluginSettings;
import com.lonqon.extralog.logging.LogEvent;
import com.lonqon.extralog.logging.PlayerSnapshot;
import com.lonqon.extralog.util.CommandSanitizer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public final class CommandListener implements Listener {
    private final ExtraLogPlugin plugin;

    public CommandListener(ExtraLogPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        try {
            PluginSettings settings = plugin.getConfigManager().getSettings();
            if (!settings.playerCommandLoggingEnabled()) {
                return;
            }
            PlayerSnapshot snapshot = PlayerSnapshot.from(event.getPlayer(), settings);
            Map<String, String> fields = new LinkedHashMap<>(snapshot.fields());
            fields.remove("ip");
            fields.put("command", CommandSanitizer.sanitize(event.getMessage(), settings));
            plugin.getFileLogger().log(LogEvent.create("PLAYER_COMMAND", snapshot.name(), fields, snapshot.uuid()));
            plugin.getWebhookService().enqueueEvent("player-commands", "player-command", snapshot.name(), snapshot.uuid(), null, fields);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, plugin.getConfigManager().getMessages().get("listener.player-command-error"), exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        try {
            PluginSettings settings = plugin.getConfigManager().getSettings();
            if (!settings.consoleCommandLoggingEnabled()) {
                return;
            }
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("world", "console");
            fields.put("xyz", "0,0,0");
            fields.put("command", CommandSanitizer.sanitize(event.getCommand(), settings));
            plugin.getFileLogger().log(LogEvent.create("CONSOLE_COMMAND", "Console", fields, null));
            plugin.getWebhookService().enqueueEvent("console-commands", "console-command", "Console", null, null, fields);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, plugin.getConfigManager().getMessages().get("listener.console-command-error"), exception);
        }
    }
}

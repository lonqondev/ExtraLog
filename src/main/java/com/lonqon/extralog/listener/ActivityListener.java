package com.lonqon.extralog.listener;

import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.config.PluginSettings;
import com.lonqon.extralog.logging.LogEvent;
import com.lonqon.extralog.logging.PlayerSnapshot;
import com.lonqon.extralog.vpn.VpnCheckModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ActivityListener implements Listener {
    private final ExtraLogPlugin plugin;
    private final VpnCheckModule vpnCheckModule;
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    public ActivityListener(ExtraLogPlugin plugin, VpnCheckModule vpnCheckModule) {
        this.plugin = plugin;
        this.vpnCheckModule = vpnCheckModule;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
            PluginSettings settings = plugin.getConfigManager().getSettings();
            PlayerSnapshot snapshot = PlayerSnapshot.from(player, settings);
            if (settings.joinLoggingEnabled()) {
                plugin.getFileLogger().log(LogEvent.create("JOIN", snapshot.name(), snapshot.fields(), snapshot.uuid()));
                plugin.getWebhookService().enqueueEvent("join", "join", snapshot.name(), snapshot.uuid(), snapshot.ip(), withoutIp(snapshot.fields()));
            }
            vpnCheckModule.onJoin(player);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, plugin.getConfigManager().getMessages().get("listener.join-error"), exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            Player player = event.getPlayer();
            Long joinedAt = joinTimes.remove(player.getUniqueId());
            long sessionSeconds = joinedAt == null ? 0L : Math.max(0L, (System.currentTimeMillis() - joinedAt) / 1000L);
            PluginSettings settings = plugin.getConfigManager().getSettings();
            if (!settings.quitLoggingEnabled()) {
                return;
            }
            PlayerSnapshot snapshot = PlayerSnapshot.from(player, settings);
            LinkedHashMap<String, String> fields = new LinkedHashMap<>(snapshot.fields());
            fields.put("session_seconds", String.valueOf(sessionSeconds));
            fields.put("reason", "disconnect");
            plugin.getFileLogger().log(LogEvent.create("QUIT", snapshot.name(), fields, snapshot.uuid()));
            LinkedHashMap<String, String> webhookFields = new LinkedHashMap<>(fields);
            webhookFields.remove("ip");
            plugin.getWebhookService().enqueueEvent("quit", "quit", snapshot.name(), snapshot.uuid(), snapshot.ip(), webhookFields);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, plugin.getConfigManager().getMessages().get("listener.quit-error"), exception);
        }
    }

    private Map<String, String> withoutIp(Map<String, String> fields) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(fields);
        result.remove("ip");
        return result;
    }
}

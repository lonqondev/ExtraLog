package com.lonqon.extralog.vpn;

import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.config.ConfigManager;
import com.lonqon.extralog.discord.WebhookService;
import com.lonqon.extralog.i18n.MessageManager;
import com.lonqon.extralog.logging.LogEvent;
import com.lonqon.extralog.util.AsyncFileLogger;
import com.lonqon.extralog.util.IpFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VpnCheckModule {
    private final ExtraLogPlugin plugin;
    private final ConfigManager configManager;
    private final AsyncFileLogger fileLogger;
    private final WebhookService webhookService;
    private final ExecutorService workflowExecutor = Executors.newSingleThreadExecutor(runnable -> namedThread(runnable, "ExtraLog-VpnWorkflow"));
    private final java.net.http.HttpClient httpClient;
    private final VpnProviderClient providerClient;
    private final AtomicBoolean unavailableWarningShown = new AtomicBoolean(false);
    private volatile VpnCacheStorage cacheStorage;

    public VpnCheckModule(ExtraLogPlugin plugin, ConfigManager configManager, AsyncFileLogger fileLogger, WebhookService webhookService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.fileLogger = fileLogger;
        this.webhookService = webhookService;
        this.httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.providerClient = new VpnProviderClient(httpClient);
        reload();
    }

    public void reload() {
        if (configManager.getSettings().vpnCheck().enabled()) {
            ensureStarted();
        } else {
            closeStorage();
        }
    }

    public void onJoin(Player player) {
        VpnCheckSettings settings = configManager.getSettings().vpnCheck();
        if (!settings.enabled()) {
            return;
        }
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return;
        }
        lookup(address.getAddress().getHostAddress(), player.getName(), player.getUniqueId())
                .thenAcceptAsync(this::handleAutomaticOutcome, workflowExecutor);
    }

    public void check(CommandSender sender, String target) {
        VpnCheckSettings settings = configManager.getSettings().vpnCheck();
        if (!settings.enabled()) {
            sendLines(sender, List.of(plugin.getConfigManager().getMessages().component("command.vpn-disabled")));
            return;
        }
        Player player = plugin.getServer().getPlayerExact(target);
        if (player == null) {
            player = plugin.getServer().getPlayer(target);
        }
        if (player != null) {
            InetSocketAddress address = player.getAddress();
            if (address == null || address.getAddress() == null) {
                sendLines(sender, List.of(plugin.getConfigManager().getMessages().component("command.player-ip-unavailable")));
                return;
            }
            lookup(address.getAddress().getHostAddress(), player.getName(), player.getUniqueId())
                    .thenAcceptAsync(outcome -> {
                        handleAutomaticOutcome(outcome);
                        sendOutcome(sender, outcome);
                    }, workflowExecutor);
            return;
        }
        lookup(target.trim(), null, null).thenAcceptAsync(outcome -> {
            handleAutomaticOutcome(outcome);
            sendOutcome(sender, outcome);
        }, workflowExecutor);
    }

    public CompletableFuture<String> checkForDiscord(String target) {
        CompletableFuture<String> completion = new CompletableFuture<>();
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            if (target == null || target.isBlank()) {
                completion.complete(plugin.getConfigManager().getMessages().get("command.vpncheck-usage"));
                return;
            }
            Player player = plugin.getServer().getPlayerExact(target);
            if (player == null) {
                player = plugin.getServer().getPlayer(target);
            }
            CompletableFuture<VpnLookupOutcome> lookup;
            if (player != null) {
                InetSocketAddress address = player.getAddress();
                if (address == null || address.getAddress() == null) {
                    completion.complete(plugin.getConfigManager().getMessages().get("command.player-ip-unavailable"));
                    return;
                }
                lookup = lookup(address.getAddress().getHostAddress(), player.getName(), player.getUniqueId());
            } else {
                lookup = lookup(target.trim(), null, null);
            }
            lookup.thenAcceptAsync(outcome -> {
                handleAutomaticOutcome(outcome);
                completion.complete(formatDiscordOutcome(outcome));
            }, workflowExecutor).exceptionally(error -> {
                completion.complete(plugin.getConfigManager().getMessages().get("discord.bot.command-failed"));
                return null;
            });
        });
        return completion;
    }

    public boolean updateWhitelist(boolean ip, String value, boolean add) {
        return configManager.updateVpnWhitelist(ip, value, add);
    }

    public void close() {
        closeStorage();
        workflowExecutor.shutdownNow();
    }

    private CompletableFuture<VpnLookupOutcome> lookup(String ip, String playerName, UUID uuid) {
        VpnCheckSettings settings = configManager.getSettings().vpnCheck();
        if (!settings.enabled()) {
            return CompletableFuture.completedFuture(new VpnLookupOutcome(VpnLookupOutcome.Status.SKIPPED, ip, playerName, uuid, null, "vpn.result.disabled", false, 0L));
        }
        ensureStarted();
        return CompletableFuture.supplyAsync(() -> {
            if (ip == null || ip.isBlank()) {
                return unavailable(ip, playerName, uuid, "invalid-ip");
            }
            if (isPrivateOrLocal(ip)) {
                return new VpnLookupOutcome(VpnLookupOutcome.Status.SKIPPED, ip, playerName, uuid, null, "vpn.result.skipped-local", false, 0L);
            }
            if (settings.whitelistIps().contains(ip) || playerName != null && settings.whitelistPlayers().contains(playerName.toLowerCase(Locale.ROOT))) {
                return new VpnLookupOutcome(VpnLookupOutcome.Status.SKIPPED, ip, playerName, uuid, null, "vpn.result.whitelisted", false, 0L);
            }
            return null;
        }, workflowExecutor).thenCompose(precheck -> {
            if (precheck != null) {
                return CompletableFuture.completedFuture(precheck);
            }
            String hash = sha256(ip);
            long minimumCheckedAt = Instant.now().minusSeconds(settings.cacheDays() * 86400L).toEpochMilli();
            VpnCacheStorage storage = cacheStorage;
            if (storage == null) {
                return CompletableFuture.completedFuture(unavailable(ip, playerName, uuid, "cache-unavailable"));
            }
            return storage.find(hash, settings.provider().name(), minimumCheckedAt).thenCompose(cached -> {
                if (cached.isPresent()) {
                    VpnCacheEntry entry = cached.get();
                    VpnProviderResult result = new VpnProviderResult(entry.vpn(), entry.providerName(), entry.country(), entry.risk());
                    return CompletableFuture.completedFuture(resultToOutcome(ip, playerName, uuid, result, true, entry.checkedAt()));
                }
                VpnCheckSettings currentSettings = configManager.getSettings().vpnCheck();
                if (!currentSettings.enabled()) {
                    return CompletableFuture.completedFuture(new VpnLookupOutcome(VpnLookupOutcome.Status.SKIPPED, ip, playerName, uuid, null, "vpn.result.disabled", false, 0L));
                }
                if (currentSettings.provider().requiresApiKey() && currentSettings.apiKey().isBlank()) {
                    return CompletableFuture.completedFuture(unavailable(ip, playerName, uuid, "api-key-missing"));
                }
                return providerClient.lookup(currentSettings.provider(), currentSettings.apiKey(), ip, currentSettings.timeoutMillis())
                        .thenCompose(result -> storage.save(hash, currentSettings.provider(), result)
                                .thenApply(ignored -> resultToOutcome(ip, playerName, uuid, result, false, System.currentTimeMillis())))
                        .exceptionally(throwable -> unavailable(ip, playerName, uuid, safeMessage(throwable)));
            }).exceptionally(throwable -> unavailable(ip, playerName, uuid, safeMessage(throwable)));
        });
    }

    private void handleAutomaticOutcome(VpnLookupOutcome outcome) {
        if (outcome.status() == VpnLookupOutcome.Status.SUSPECTED) {
            VpnProviderResult result = outcome.result();
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("ip", outcome.ip());
            fields.put("provider", result.providerName());
            fields.put("country", result.country());
            fields.put("risk", result.risk());
            fileLogger.log(LogEvent.create("VPN_SUSPECTED", outcome.playerName() == null ? "-" : outcome.playerName(), fields, outcome.uuid()));
            webhookService.enqueueEvent("vpn-suspected", "vpn-suspected", outcome.playerName(), outcome.uuid(), outcome.ip(), Map.of("provider", result.providerName(), "country", result.country(), "risk", result.risk()));
        } else if (outcome.status() == VpnLookupOutcome.Status.UNAVAILABLE) {
            fileLogger.log(LogEvent.create("VPN_CHECK_UNAVAILABLE", outcome.playerName() == null ? "-" : outcome.playerName(), Map.of("ip", outcome.ip() == null ? "unknown" : outcome.ip(), "reason", "unavailable"), outcome.uuid()));
            warnUnavailableOnce(outcome.message());
        }
    }

    private VpnLookupOutcome resultToOutcome(String ip, String playerName, UUID uuid, VpnProviderResult result, boolean fromCache, long checkedAt) {
        return new VpnLookupOutcome(result.vpn() ? VpnLookupOutcome.Status.SUSPECTED : VpnLookupOutcome.Status.CLEAN, ip, playerName, uuid, result, result.vpn() ? "vpn.result.suspected" : "vpn.result.not-detected", fromCache, checkedAt);
    }

    private VpnLookupOutcome unavailable(String ip, String playerName, UUID uuid, String reason) {
        warnUnavailableOnce(reason);
        return new VpnLookupOutcome(VpnLookupOutcome.Status.UNAVAILABLE, ip, playerName, uuid, null, "vpn.result.unavailable", false, 0L);
    }

    private void warnUnavailableOnce(String reason) {
        if (unavailableWarningShown.compareAndSet(false, true)) {
            plugin.getLogger().warning(plugin.getConfigManager().getMessages().get("vpn.console-unavailable", Map.of("reason", plugin.getConfigManager().getMessages().get("vpn.result.unavailable"))));
        }
    }

    private void sendOutcome(CommandSender sender, VpnLookupOutcome outcome) {
        MessageManager messages = plugin.getConfigManager().getMessages();
        Component header = messages.component("command.vpncheck-header");
        if (outcome.status() == VpnLookupOutcome.Status.SKIPPED || outcome.status() == VpnLookupOutcome.Status.UNAVAILABLE) {
            sendLines(sender, List.of(header, messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-result"), "value", messages.get(outcome.message())))));
            return;
        }
        String visibleIp = IpFormatter.formatForDestination(outcome.ip(), !sender.hasPermission("extralog.viewip"));
        VpnProviderResult result = outcome.result();
        String source = outcome.fromCache() ? messages.get("command.vpncheck-cache-value") : messages.get("command.vpncheck-fresh");
        String checkedAt = outcome.checkedAt() <= 0L ? "-" : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(outcome.checkedAt()).atZone(ZoneId.systemDefault()));
        String resultText = messages.get(outcome.message());
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(header);
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-ip"), "value", visibleIp)));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-result"), "value", resultText)));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-provider"), "value", configManager.getSettings().vpnCheck().provider().name())));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-country"), "value", result.country())));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-isp"), "value", result.providerName())));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-risk"), "value", result.risk())));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-cache"), "value", source)));
        lines.add(messages.component("command.vpncheck-line", Map.of("label", messages.get("command.vpncheck-checked-at"), "value", checkedAt)));
        sendLines(sender, lines);
    }

    private String formatDiscordOutcome(VpnLookupOutcome outcome) {
        MessageManager messages = plugin.getConfigManager().getMessages();
        String resultText = messages.get(outcome.message());
        if (outcome.status() == VpnLookupOutcome.Status.SKIPPED || outcome.status() == VpnLookupOutcome.Status.UNAVAILABLE) {
            return resultText;
        }
        VpnProviderResult result = outcome.result();
        String source = outcome.fromCache() ? messages.get("discord.bot.vpn-source-cache") : messages.get("discord.bot.vpn-source-fresh");
        String checkedAt = outcome.checkedAt() <= 0L ? "-" : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(outcome.checkedAt()).atZone(ZoneId.systemDefault()));
        return messages.get("discord.bot.vpn-result", Map.of(
                "result", resultText,
                "ip", IpFormatter.formatForDestination(outcome.ip(), true),
                "provider", configManager.getSettings().vpnCheck().provider().name(),
                "country", safeValue(result.country()),
                "isp", safeValue(result.providerName()),
                "risk", safeValue(result.risk()),
                "source", source,
                "checked", checkedAt
        ));
    }

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void sendLines(CommandSender sender, List<Component> lines) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> lines.forEach(sender::sendMessage));
    }

    private void ensureStarted() {
        if (cacheStorage != null) {
            return;
        }
        synchronized (this) {
            if (cacheStorage == null) {
                VpnCacheStorage storage = new VpnCacheStorage(plugin);
                cacheStorage = storage;
                storage.start();
            }
        }
    }

    private void closeStorage() {
        VpnCacheStorage storage = cacheStorage;
        cacheStorage = null;
        if (storage != null) {
            storage.close();
        }
    }

    private boolean isPrivateOrLocal(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length != 4) {
                return false;
            }
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 10 || first == 127 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
        } catch (Exception exception) {
            return false;
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("IP hash oluşturulamadı.", exception);
        }
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(false);
        return thread;
    }
}

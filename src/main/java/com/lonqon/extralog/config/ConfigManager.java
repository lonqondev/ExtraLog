package com.lonqon.extralog.config;

import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.i18n.MessageManager;
import com.lonqon.extralog.vpn.VpnCheckSettings;
import com.lonqon.extralog.vpn.VpnProvider;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class ConfigManager {
    private final ExtraLogPlugin plugin;
    private final MessageManager messageManager;
    private final ExecutorService configIoExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ExtraLog-ConfigIO");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean oldSecurityWebhookWarning = new AtomicBoolean(false);
    private volatile PluginSettings settings = PluginSettings.defaults();

    public ConfigManager(ExtraLogPlugin plugin) {
        this.plugin = plugin;
        this.messageManager = new MessageManager(plugin);
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        messageManager.reload(plugin.getConfig().getString("language", "en"));
        settings = readSettings();
    }

    public PluginSettings getSettings() {
        return settings;
    }

    public MessageManager getMessages() {
        return messageManager;
    }

    private PluginSettings readSettings() {
        PluginSettings defaults = PluginSettings.defaults();
        boolean ipLoggingEnabled = readBoolean("ip-logging.enabled", defaults.ipLoggingEnabled());
        boolean publicDiscordMask = readBoolean("ip-logging.public-discord-mask", defaults.publicDiscordMask());
        String logDirectory = readDirectory("file-logging.directory", defaults.logDirectory());
        long flushIntervalMillis = readLong("file-logging.flush-interval-millis", defaults.flushIntervalMillis(), 100L, 60000L);
        int queueCapacity = readInt("file-logging.queue-capacity", defaults.queueCapacity(), 100L, Integer.MAX_VALUE);
        long maxFileSizeBytes = readLong("file-logging.max-file-size-bytes", defaults.maxFileSizeBytes(), 0L, Long.MAX_VALUE);
        boolean joinLoggingEnabled = readBoolean("events.join", defaults.joinLoggingEnabled());
        boolean quitLoggingEnabled = readBoolean("events.quit", defaults.quitLoggingEnabled());
        boolean playerCommandLoggingEnabled = readBoolean("events.player-commands", defaults.playerCommandLoggingEnabled());
        boolean consoleCommandLoggingEnabled = readBoolean("events.console-commands", defaults.consoleCommandLoggingEnabled());
        Set<String> argumentBlacklist = readCommandSet("commands.argument-blacklist", defaults.argumentBlacklist());
        Set<String> contentHiddenCommands = readCommandSet("commands.content-hidden", defaults.contentHiddenCommands());
        VpnCheckSettings vpnCheck = readVpnCheck(defaults.vpnCheck());
        String language = plugin.getConfig().getString("language", defaults.language());
        LogDetails logDetails = new LogDetails(
                readBoolean("log-details.location", defaults.logDetails().location()),
                readBoolean("log-details.gamemode", defaults.logDetails().gamemode()),
                readBoolean("log-details.ping", defaults.logDetails().ping()),
                readBoolean("log-details.client-info", defaults.logDetails().clientInfo()),
                readBoolean("log-details.hostname", defaults.logDetails().hostname())
        );
        String logFormat = plugin.getConfig().getString("file-logging.format", defaults.logFormat());
        if (logFormat == null || !(logFormat.equalsIgnoreCase("TEXT") || logFormat.equalsIgnoreCase("JSONL"))) {
            reportInvalid("file-logging.format", "TEXT veya JSONL", logFormat);
            logFormat = defaults.logFormat();
        } else {
            logFormat = logFormat.toUpperCase(Locale.ROOT);
        }
        boolean compressOldLogs = readBoolean("file-logging.compress-old-logs", defaults.compressOldLogs());
        int retentionDays = readInt("file-logging.retention-days", defaults.retentionDays(), 0L, 3650);
        DiscordSettings discord = readDiscord(defaults.discord());

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
                vpnCheck,
                language,
                logDetails,
                logFormat,
                compressOldLogs,
                retentionDays,
                discord
        );
    }

    public synchronized boolean updateVpnWhitelist(boolean ip, String value, boolean add) {
        String normalized = ip ? value.trim() : value.trim().toLowerCase(Locale.ROOT);
        VpnCheckSettings current = settings.vpnCheck();
        Set<String> ips = new HashSet<>(current.whitelistIps());
        Set<String> players = new HashSet<>(current.whitelistPlayers());
        Set<String> target = ip ? ips : players;
        boolean changed = add ? target.add(normalized) : target.remove(normalized);
        if (!changed) {
            return false;
        }
        settings = settings.withVpnCheck(current.withWhitelist(ips, players));
        configIoExecutor.execute(() -> persistVpnWhitelist(ips, players));
        return true;
    }

    public void close() {
        configIoExecutor.shutdown();
    }

    private boolean readBoolean(String path, boolean fallback) {
        Object value = plugin.getConfig().get(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        reportInvalid(path, "true veya false", value);
        return fallback;
    }

    private long readLong(String path, long fallback, long minimum, long maximum) {
        Object value = plugin.getConfig().get(path);
        if (!(value instanceof Number number)) {
            reportInvalid(path, "sayısal bir değer", value);
            return fallback;
        }
        long parsed = number.longValue();
        if (parsed < minimum || parsed > maximum) {
            reportInvalid(path, minimum + " ile " + maximum + " arasında bir değer", value);
            return fallback;
        }
        return parsed;
    }

    private int readInt(String path, int fallback, long minimum, int maximum) {
        return (int) readLong(path, fallback, minimum, maximum);
    }

    private String readDirectory(String path, String fallback) {
        String value = plugin.getConfig().getString(path);
        if (value == null || value.isBlank()) {
            reportInvalid(path, "boş olmayan göreli bir klasör adı", value);
            return fallback;
        }
        boolean absolute;
        try {
            absolute = Path.of(value).isAbsolute();
        } catch (RuntimeException exception) {
            reportInvalid(path, "geçerli, göreli ve üst klasöre çıkmayan bir klasör adı", value);
            return fallback;
        }
        if (absolute || value.contains("..")) {
            reportInvalid(path, "göreli ve üst klasöre çıkmayan bir klasör adı", value);
            return fallback;
        }
        return value.replace('\\', '/');
    }

    private Set<String> readCommandSet(String path, Set<String> fallback) {
        if (!plugin.getConfig().isList(path)) {
            reportInvalid(path, "komut isimlerinden oluşan bir liste", plugin.getConfig().get(path));
            return fallback;
        }
        List<String> values = plugin.getConfig().getStringList(path);
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String command = normalizeCommand(value);
            if (!command.isEmpty()) {
                normalized.add(command);
            }
        }
        return normalized;
    }

    private VpnCheckSettings readVpnCheck(VpnCheckSettings fallback) {
        boolean enabled = readBoolean("vpn-check.enabled", fallback.enabled());
        String providerValue = plugin.getConfig().getString("vpn-check.provider");
        VpnProvider provider = VpnProvider.parse(providerValue);
        if (provider == null) {
            reportInvalid("vpn-check.provider", "PROXYCHECK, IPHUB veya IPQUALITYSCORE", providerValue);
            provider = fallback.provider();
        }
        String apiKey = plugin.getConfig().getString("vpn-check.api-key", fallback.apiKey());
        int cacheDays = readInt("vpn-check.cache-days", fallback.cacheDays(), 0L, 3650);
        int timeoutMillis = readInt("vpn-check.timeout-ms", fallback.timeoutMillis(), 100L, 60000);
        Set<String> whitelistIps = readStringSet("vpn-check.whitelist-ips", fallback.whitelistIps(), false);
        Set<String> whitelistPlayers = readStringSet("vpn-check.whitelist-players", fallback.whitelistPlayers(), true);
        String action = plugin.getConfig().getString("vpn-check.action", fallback.action());
        if (action == null || !action.equalsIgnoreCase("WARN")) {
            reportInvalid("vpn-check.action", "WARN", action);
            action = fallback.action();
        } else {
            action = "WARN";
        }
        String oldSecurityWebhookUrl = plugin.getConfig().getString("vpn-check.security-webhook-url", "");
        String securityWebhookUrl = oldSecurityWebhookUrl;
        String configuredSecurityWebhook = plugin.getConfig().getString("discord.webhooks.security", "");
        if (configuredSecurityWebhook != null && !configuredSecurityWebhook.isBlank()) {
            securityWebhookUrl = configuredSecurityWebhook;
        }
        return new VpnCheckSettings(
                enabled,
                provider,
                apiKey == null ? "" : apiKey.trim(),
                cacheDays,
                timeoutMillis,
                whitelistIps,
                whitelistPlayers,
                action,
                securityWebhookUrl == null ? "" : securityWebhookUrl.trim()
        );
    }

    private DiscordSettings readDiscord(DiscordSettings fallback) {
        boolean enabled = readBoolean("discord.enabled", fallback.enabled());
        java.util.Map<String, String> webhooks = new java.util.LinkedHashMap<>();
        for (String channel : List.of("general", "staff", "security")) {
            String url = plugin.getConfig().getString("discord.webhooks." + channel, fallback.webhooks().getOrDefault(channel, ""));
            webhooks.put(channel, validateWebhook(channel, url));
        }
        String oldSecurityWebhookUrl = plugin.getConfig().getString("vpn-check.security-webhook-url", "");
        if (webhooks.get("security").isBlank() && oldSecurityWebhookUrl != null && !oldSecurityWebhookUrl.isBlank()) {
            webhooks.put("security", validateWebhook("security", oldSecurityWebhookUrl));
            if (oldSecurityWebhookWarning.compareAndSet(false, true)) {
                plugin.getLogger().warning(messageManager.get("config.old-security-webhook"));
            }
        }
        java.util.Map<String, java.util.List<String>> routing = new java.util.LinkedHashMap<>();
        for (String event : List.of("join", "quit", "player-commands", "console-commands", "vpn-suspected")) {
            routing.put(event, readRouting("discord.routing." + event, fallback.routing().getOrDefault(event, List.of())));
        }
        java.util.Map<String, Integer> colors = new java.util.LinkedHashMap<>();
        colors.put("join", readColor("discord.embed-colors.join", fallback.embedColors().get("join")));
        colors.put("quit", readColor("discord.embed-colors.quit", fallback.embedColors().get("quit")));
        colors.put("command", readColor("discord.embed-colors.command", fallback.embedColors().get("command")));
        colors.put("vpn", readColor("discord.embed-colors.vpn", fallback.embedColors().get("vpn")));
        String serverName = plugin.getConfig().getString("discord.server-name", fallback.serverName());
        boolean thumbnail = readBoolean("discord.player-head-thumbnail", fallback.playerHeadThumbnail());
        int queueCapacity = readInt("discord.queue-capacity", fallback.queueCapacity(), 10L, 100000);
        int shutdownTimeout = readInt("discord.shutdown-timeout-ms", fallback.shutdownTimeoutMillis(), 100L, 60000);
        DiscordBotSettings bot = readDiscordBot(fallback.bot());
        return new DiscordSettings(enabled, webhooks, routing, colors, serverName == null || serverName.isBlank() ? fallback.serverName() : serverName, thumbnail, queueCapacity, shutdownTimeout, bot);
    }

    private DiscordBotSettings readDiscordBot(DiscordBotSettings fallback) {
        boolean enabled = readBoolean("discord.bot.enabled", fallback.enabled());
        String token = plugin.getConfig().getString("discord.bot.token", fallback.token());
        String guildId = plugin.getConfig().getString("discord.bot.guild-id", fallback.guildId());
        Set<String> userIds = readStringSet("discord.bot.allowed-user-ids", fallback.allowedUserIds(), false);
        Set<String> roleIds = readStringSet("discord.bot.allowed-role-ids", fallback.allowedRoleIds(), false);
        return new DiscordBotSettings(enabled, token == null ? "" : token.trim(), guildId == null ? "" : guildId.trim(), userIds, roleIds);
    }

    private String validateWebhook(String channel, String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        boolean valid = false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String normalized = url.toLowerCase(Locale.ROOT);
            valid = "https".equalsIgnoreCase(uri.getScheme()) && (normalized.startsWith("https://discord.com/api/webhooks/") || normalized.startsWith("https://discordapp.com/api/webhooks/"));
        } catch (Exception ignored) {
            valid = false;
        }
        if (!valid) {
            plugin.getLogger().warning(messageManager.get("config.invalid-webhook", Map.of("channel", channel, "url", maskWebhook(url))));
            return "";
        }
        return url;
    }

    private String maskWebhook(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= url.length()) {
            return "<hidden>";
        }
        return url.substring(0, Math.min(slash + 1, 40)) + "...";
    }

    private List<String> readRouting(String path, List<String> fallback) {
        if (!plugin.getConfig().isList(path)) {
            reportInvalid(path, "general, staff veya security listesinden oluşan bir liste", plugin.getConfig().get(path));
            return fallback;
        }
        List<String> result = new java.util.ArrayList<>();
        for (String value : plugin.getConfig().getStringList(path)) {
            String channel = value.toLowerCase(Locale.ROOT).trim();
            if (List.of("general", "staff", "security").contains(channel)) {
                result.add(channel);
            } else {
                reportInvalid(path, "general, staff veya security", value);
            }
        }
        return result;
    }

    private int readColor(String path, int fallback) {
        String value = plugin.getConfig().getString(path);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "green" -> 65280;
            case "gray", "grey" -> 8421504;
            case "blue" -> 3447003;
            case "orange" -> 16753920;
            default -> {
                reportInvalid(path, "green, gray, blue veya orange", value);
                yield fallback;
            }
        };
    }

    private Set<String> readStringSet(String path, Set<String> fallback, boolean lowerCase) {
        if (!plugin.getConfig().isList(path)) {
            reportInvalid(path, "metinlerden oluşan bir liste", plugin.getConfig().get(path));
            return fallback;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : plugin.getConfig().getStringList(path)) {
            String normalized = lowerCase ? value.trim().toLowerCase(Locale.ROOT) : value.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private void persistVpnWhitelist(Set<String> ips, Set<String> players) {
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configFile);
            configuration.set("vpn-check.whitelist-ips", ips.stream().sorted().toList());
            configuration.set("vpn-check.whitelist-players", players.stream().sorted().toList());
            configuration.save(configFile);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, messageManager.get("config.whitelist-save-failed"), exception);
        }
    }

    private String normalizeCommand(String value) {
        String command = value.trim().toLowerCase(Locale.ROOT);
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        int separator = command.lastIndexOf(':');
        if (separator >= 0 && separator + 1 < command.length()) {
            command = command.substring(separator + 1);
        }
        return command;
    }

    private void reportInvalid(String path, String expected, Object actual) {
        plugin.getLogger().severe(messageManager.get("config.invalid", Map.of("path", path, "expected", localizeExpected(expected), "actual", actual == null ? "null" : actual)));
    }

    private String localizeExpected(String expected) {
        if (expected.contains("true veya false")) {
            return messageManager.get("config.expected-boolean");
        }
        if (expected.contains("sayısal") || expected.contains("arasında bir değer")) {
            return messageManager.get("config.expected-number");
        }
        if (expected.contains("göreli") || expected.contains("boş olmayan")) {
            return messageManager.get("config.expected-path");
        }
        if (expected.contains("komut isimlerinden") || expected.contains("metinlerden")) {
            return messageManager.get("config.expected-list");
        }
        if (expected.contains("PROXYCHECK")) {
            return messageManager.get("config.expected-provider");
        }
        if (expected.equals("WARN")) {
            return messageManager.get("config.expected-warn");
        }
        if (expected.contains("general, staff")) {
            return messageManager.get("config.expected-channel-list");
        }
        if (expected.contains("green, gray")) {
            return messageManager.get("config.expected-color");
        }
        return expected;
    }
}

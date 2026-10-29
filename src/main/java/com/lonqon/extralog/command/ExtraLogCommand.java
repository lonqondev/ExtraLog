package com.lonqon.extralog.command;

import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.i18n.MessageManager;
import com.lonqon.extralog.vpn.VpnCheckModule;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExtraLogCommand implements CommandExecutor, TabCompleter {
    private final ExtraLogPlugin plugin;
    private final VpnCheckModule vpnCheckModule;

    public ExtraLogCommand(ExtraLogPlugin plugin, VpnCheckModule vpnCheckModule) {
        this.plugin = plugin;
        this.vpnCheckModule = vpnCheckModule;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageManager messages = plugin.getConfigManager().getMessages();
        if (args.length >= 1 && args[0].equalsIgnoreCase("vpncheck")) {
            if (!hasAdmin(sender)) {
                return true;
            }
            if (args.length != 2) {
                reply(sender, messages.component("command.vpncheck-usage"));
                return true;
            }
            vpnCheckModule.check(sender, args[1]);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("vpnwhitelist")) {
            if (!hasAdmin(sender)) {
                return true;
            }
            if (args.length != 3 || !(args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                reply(sender, messages.component("command.vpnwhitelist-usage"));
                return true;
            }
            boolean add = args[1].equalsIgnoreCase("add");
            boolean ip = looksLikeIp(args[2]);
            boolean changed = vpnCheckModule.updateWhitelist(ip, args[2], add);
            if (!changed) {
                reply(sender, messages.component("command.vpnwhitelist-unchanged"));
                return true;
            }
            String type = messages.get(ip ? "command.vpnwhitelist-ip" : "command.vpnwhitelist-player");
            String action = messages.get(add ? "command.vpnwhitelist-add" : "command.vpnwhitelist-remove");
            reply(sender, messages.component("command.vpnwhitelist-changed", Map.of("type", type, "action", action)));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("testwebhook")) {
            if (!hasAdmin(sender)) {
                return true;
            }
            if (args.length > 2) {
                reply(sender, messages.component("command.testwebhook-usage"));
                return true;
            }
            String channel = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "general";
            if (!List.of("general", "staff", "security").contains(channel)) {
                reply(sender, messages.component("command.testwebhook-usage"));
                return true;
            }
            plugin.getWebhookService().test(channel).whenComplete((ignored, error) -> plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                MessageManager currentMessages = plugin.getConfigManager().getMessages();
                if (error == null) {
                    sender.sendMessage(currentMessages.component("discord.test-success", Map.of("channel", channel)));
                } else {
                    String reason = safeMessage(error);
                    String localizedReason = reason.equals("discord-disabled")
                            ? currentMessages.get("discord.test-disabled")
                            : reason.equals("webhook-not-configured")
                            ? currentMessages.get("discord.webhook-not-configured", Map.of("channel", channel))
                            : currentMessages.get("discord.send-failed");
                    sender.sendMessage(currentMessages.component("discord.test-failure", Map.of("channel", channel, "reason", localizedReason)));
                }
            }));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("extralog.reload")) {
                reply(sender, messages.component("command.no-permission"));
                return true;
            }
            plugin.reloadPluginConfig();
            reply(sender, plugin.getConfigManager().getMessages().component("command.reload-success"));
            return true;
        }
        reply(sender, messages.component("command.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("reload", "vpncheck", "vpnwhitelist", "testwebhook").stream().filter(item -> item.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("vpnwhitelist")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("add", "remove").stream().filter(item -> item.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("testwebhook")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("general", "staff", "security").stream().filter(item -> item.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("vpncheck")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream().map(player -> player.getName()).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }

    private boolean hasAdmin(CommandSender sender) {
        if (!sender.hasPermission("extralog.admin")) {
            reply(sender, plugin.getConfigManager().getMessages().component("command.no-permission"));
            return false;
        }
        return true;
    }

    private void reply(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    private boolean looksLikeIp(String value) {
        return value.matches("[0-9a-fA-F:.]+") && (value.contains(".") || value.contains(":"));
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

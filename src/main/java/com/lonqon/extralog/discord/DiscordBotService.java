package com.lonqon.extralog.discord;

import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.config.DiscordBotSettings;
import com.lonqon.extralog.i18n.MessageManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.util.List;
import java.util.Map;

public final class DiscordBotService {
    private final ExtraLogPlugin plugin;
    private JDA jda;
    private String activeToken = "";
    private String activeGuildId = "";

    public DiscordBotService(ExtraLogPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        reload();
    }

    public synchronized void reload() {
        DiscordBotSettings settings = plugin.getConfigManager().getSettings().discord().bot();
        if (!settings.enabled()) {
            stop();
            return;
        }
        if (settings.token().isBlank()) {
            stop();
            plugin.getLogger().warning(plugin.getConfigManager().getMessages().get("discord.bot.startup-failed"));
            return;
        }
        if (jda != null && settings.token().equals(activeToken) && settings.guildId().equals(activeGuildId)) {
            registerCommands(jda);
            return;
        }
        stop();
        activeToken = settings.token();
        activeGuildId = settings.guildId();
        try {
            jda = JDABuilder.createDefault(settings.token())
                    .addEventListeners(new BotListener())
                    .build();
        } catch (Exception exception) {
            jda = null;
            plugin.getLogger().warning(plugin.getConfigManager().getMessages().get("discord.bot.startup-failed"));
        }
    }

    public synchronized void close() {
        stop();
    }

    private synchronized void stop() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
        activeToken = "";
        activeGuildId = "";
    }

    private void registerCommands(JDA current) {
        List<CommandData> commands = List.of(
                Commands.slash("status", message("discord.bot.command-status")),
                Commands.slash("vpncheck", message("discord.bot.command-vpncheck"))
                        .addOptions(new OptionData(OptionType.STRING, "target", message("discord.bot.option-target"), true)),
                Commands.slash("reload", message("discord.bot.command-reload"))
        );
        DiscordBotSettings settings = plugin.getConfigManager().getSettings().discord().bot();
        if (settings.guildId().isBlank()) {
            current.updateCommands().addCommands(commands).queue();
            return;
        }
        Guild guild = current.getGuildById(settings.guildId());
        if (guild == null) {
            plugin.getLogger().warning(message("discord.bot.guild-not-found"));
            return;
        }
        guild.updateCommands().addCommands(commands).queue();
    }

    private String message(String key) {
        return plugin.getConfigManager().getMessages().get(key);
    }

    private boolean authorized(SlashCommandInteractionEvent event) {
        DiscordBotSettings settings = plugin.getConfigManager().getSettings().discord().bot();
        if (settings.allowedUserIds().contains(event.getUser().getId())) {
            return true;
        }
        if (event.getMember() == null) {
            return false;
        }
        return event.getMember().getRoles().stream().anyMatch(role -> settings.allowedRoleIds().contains(role.getId()));
    }

    private void defer(SlashCommandInteractionEvent event, java.util.function.Consumer<InteractionHook> action) {
        event.deferReply(true).queue(action);
    }

    private final class BotListener extends ListenerAdapter {
        @Override
        public void onReady(ReadyEvent event) {
            plugin.getLogger().info(message("discord.bot.connected"));
            registerCommands(event.getJDA());
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            if (!authorized(event)) {
                event.reply(message("discord.bot.no-permission")).setEphemeral(true).queue();
                return;
            }
            switch (event.getName()) {
                case "status" -> defer(event, hook -> plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    String vpn = plugin.getConfigManager().getSettings().vpnCheck().enabled()
                            ? message("discord.bot.enabled")
                            : message("discord.bot.disabled");
                    hook.editOriginal(plugin.getConfigManager().getMessages().get("discord.bot.status", Map.of(
                            "online", plugin.getServer().getOnlinePlayers().size(),
                            "max", plugin.getServer().getMaxPlayers(),
                            "vpn", vpn
                    ))).queue();
                }));
                case "reload" -> defer(event, hook -> hook.editOriginal(message("discord.bot.reload-success")).queue(ignored -> plugin.getServer().getGlobalRegionScheduler().execute(plugin, plugin::reloadPluginConfig)));
                case "vpncheck" -> {
                    String target = event.getOption("target") == null ? "" : event.getOption("target").getAsString();
                    defer(event, hook -> plugin.getVpnCheckModule().checkForDiscord(target).whenComplete((result, error) -> hook.editOriginal(error == null ? result : message("discord.bot.command-failed")).queue()));
                }
                default -> event.reply(message("discord.bot.command-failed")).setEphemeral(true).queue();
            }
        }
    }
}

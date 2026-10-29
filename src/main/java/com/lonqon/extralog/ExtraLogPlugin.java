package com.lonqon.extralog;

import com.lonqon.extralog.command.ExtraLogCommand;
import com.lonqon.extralog.config.ConfigManager;
import com.lonqon.extralog.discord.DiscordBotService;
import com.lonqon.extralog.discord.WebhookService;
import com.lonqon.extralog.listener.ActivityListener;
import com.lonqon.extralog.listener.CommandListener;
import com.lonqon.extralog.util.AsyncFileLogger;
import com.lonqon.extralog.vpn.VpnCheckModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExtraLogPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private AsyncFileLogger fileLogger;
    private WebhookService webhookService;
    private DiscordBotService discordBotService;
    private VpnCheckModule vpnCheckModule;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        fileLogger = new AsyncFileLogger(this, configManager::getSettings, configManager::getMessages);
        fileLogger.start();
        webhookService = new WebhookService(this);
        webhookService.start();
        vpnCheckModule = new VpnCheckModule(this, configManager, fileLogger, webhookService);
        discordBotService = new DiscordBotService(this);
        discordBotService.start();

        ActivityListener activityListener = new ActivityListener(this, vpnCheckModule);
        CommandListener commandListener = new CommandListener(this);
        getServer().getPluginManager().registerEvents(activityListener, this);
        getServer().getPluginManager().registerEvents(commandListener, this);

        PluginCommand command = getCommand("extralog");
        if (command == null) {
            getLogger().severe(configManager.getMessages().get("plugin.command-missing"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ExtraLogCommand extraLogCommand = new ExtraLogCommand(this, vpnCheckModule);
        command.setExecutor(extraLogCommand);
        command.setTabCompleter(extraLogCommand);
        getLogger().info(configManager.getMessages().get("plugin.enabled"));
    }

    @Override
    public void onDisable() {
        if (discordBotService != null) {
            discordBotService.close();
        }
        if (vpnCheckModule != null) {
            vpnCheckModule.close();
        }
        if (webhookService != null) {
            webhookService.close();
        }
        if (fileLogger != null) {
            fileLogger.close();
        }
        if (configManager != null) {
            configManager.close();
        }
        if (configManager != null) {
            getLogger().info(configManager.getMessages().get("plugin.disabled"));
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public AsyncFileLogger getFileLogger() {
        return fileLogger;
    }

    public VpnCheckModule getVpnCheckModule() {
        return vpnCheckModule;
    }

    public WebhookService getWebhookService() {
        return webhookService;
    }

    public DiscordBotService getDiscordBotService() {
        return discordBotService;
    }

    public void reloadPluginConfig() {
        configManager.load();
        vpnCheckModule.reload();
        discordBotService.reload();
    }
}

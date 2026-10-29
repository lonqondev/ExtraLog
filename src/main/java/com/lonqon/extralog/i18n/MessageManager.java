package com.lonqon.extralog.i18n;

import com.lonqon.extralog.ExtraLogPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class MessageManager {
    private final ExtraLogPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, String> english = new ConcurrentHashMap<>();
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warnedKeys = new ConcurrentHashMap<>();
    private volatile String language = "en";

    public MessageManager(ExtraLogPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void reload(String requestedLanguage) {
        ensureLanguageFile("en");
        ensureLanguageFile("tr");
        english.clear();
        active.clear();
        loadInto("en", english);
        String normalized = requestedLanguage == null ? "en" : requestedLanguage.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("en") && !normalized.equals("tr")) {
            plugin.getLogger().warning(get("config.language-invalid"));
            normalized = "en";
        }
        language = normalized;
        if (normalized.equals("en")) {
            active.putAll(english);
        } else {
            loadInto(normalized, active);
        }
    }

    public String get(String key, Map<String, ?> placeholders) {
        String template = template(key);
        if (template == null) {
            return key;
        }
        return replace(template, placeholders);
    }

    public String get(String key) {
        return get(key, Map.of());
    }

    public Component component(String key, Map<String, ?> placeholders) {
        String template = template(key);
        if (template == null) {
            return Component.text(key);
        }
        Map<String, String> values = new LinkedHashMap<>();
        placeholders.forEach((name, value) -> values.put(name, String.valueOf(value)));
        TagResolver[] resolvers = values.entrySet().stream().map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue())).toArray(TagResolver[]::new);
        String miniMessageTemplate = template;
        for (String name : values.keySet()) {
            miniMessageTemplate = miniMessageTemplate.replace("{" + name + "}", "<" + name + ">");
        }
        return miniMessage.deserialize(miniMessageTemplate, TagResolver.resolver(resolvers));
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public String getLanguage() {
        return language;
    }

    private String template(String key) {
        String template = active.get(key);
        if (template == null) {
            template = english.get(key);
            if (warnedKeys.putIfAbsent(key, true) == null) {
                String warning = english.get("language.key-missing");
                if (warning == null) {
                    warning = "Language key missing: " + key + ". Falling back to en.";
                } else {
                    warning = replace(warning, Map.of("key", key));
                }
                plugin.getLogger().warning(warning);
            }
        }
        return template;
    }

    private String replace(String template, Map<String, ?> placeholders) {
        String value = template;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return value.replaceAll("<[^>]+>", "");
    }

    private void ensureLanguageFile(String code) {
        try {
            File directory = new File(plugin.getDataFolder(), "lang");
            Files.createDirectories(directory.toPath());
            File target = new File(directory, code + ".yml");
            if (!target.exists()) {
                try (InputStream input = plugin.getResource("lang/" + code + ".yml")) {
                    if (input == null) {
                        throw new IllegalStateException("Missing bundled language resource: " + code);
                    }
                    Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "ExtraLog language file could not be created: " + code, exception);
        }
    }

    private void loadInto(String code, Map<String, String> target) {
        try {
            File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            flatten(configuration.getValues(true), target);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "ExtraLog language file could not be loaded: " + code, exception);
        }
    }

    private void flatten(Map<String, Object> values, Map<String, String> target) {
        values.forEach((key, value) -> {
            if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                target.put(key, String.valueOf(value));
            }
        });
    }
}

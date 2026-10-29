package com.lonqon.extralog.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.config.DiscordSettings;
import com.lonqon.extralog.config.PluginSettings;
import com.lonqon.extralog.i18n.MessageManager;
import com.lonqon.extralog.util.IpFormatter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class WebhookService {
    private final ExtraLogPlugin plugin;
    private final HttpClient httpClient;
    private final BlockingQueue<WebhookMessage> queue;
    private final ExecutorService dispatcher;
    private final ScheduledExecutorService retryExecutor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong lastOverflowWarning = new AtomicLong();
    private final AtomicLong lastDeliveryWarning = new AtomicLong();

    public WebhookService(ExtraLogPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.queue = new LinkedBlockingQueue<>(plugin.getConfigManager().getSettings().discord().queueCapacity());
        this.dispatcher = Executors.newSingleThreadExecutor(runnable -> namedThread(runnable, "ExtraLog-WebhookQueue"));
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> namedThread(runnable, "ExtraLog-WebhookRetry"));
    }

    public void start() {
        dispatcher.submit(this::dispatchLoop);
    }

    public void enqueueEvent(String route, String embedKind, String playerName, UUID uuid, String ip, Map<String, String> fields) {
        PluginSettings settings = plugin.getConfigManager().getSettings();
        if (!settings.discord().enabled()) {
            return;
        }
        for (String channel : settings.discord().routing().getOrDefault(route, List.of())) {
            if (settings.discord().webhooks().getOrDefault(channel, "").isBlank()) {
                continue;
            }
            LinkedHashMap<String, String> embedFields = new LinkedHashMap<>();
            if (playerName != null) {
                embedFields.put("player", playerName);
            }
            if (uuid != null) {
                embedFields.put("uuid", uuid.toString());
            }
            if (ip != null) {
                boolean mask = channel.equals("general") && settings.publicDiscordMask();
                embedFields.put("ip", IpFormatter.formatForDestination(ip, mask));
            }
            fields.forEach((key, value) -> {
                if (!key.equals("ip")) {
                    embedFields.put(key, value);
                }
            });
            offer(new WebhookMessage(channel, embedKind, embedFields, uuid, null, 0));
        }
    }

    public CompletableFuture<Void> test(String channel) {
        PluginSettings settings = plugin.getConfigManager().getSettings();
        if (!settings.discord().enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("discord-disabled"));
        }
        if (!settings.discord().webhooks().containsKey(channel) || settings.discord().webhooks().get(channel).isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("webhook-not-configured"));
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        offer(new WebhookMessage(channel, "test", new LinkedHashMap<>(), null, completion, 0));
        return completion;
    }

    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        dispatcher.shutdown();
        try {
            if (!dispatcher.awaitTermination(plugin.getConfigManager().getSettings().discord().shutdownTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                dispatcher.shutdownNow();
            }
        } catch (InterruptedException exception) {
            dispatcher.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            retryExecutor.shutdownNow();
        }
    }

    private void offer(WebhookMessage message) {
        if (!queue.offer(message)) {
            long now = System.currentTimeMillis();
            if (now - lastOverflowWarning.get() > 60000L && lastOverflowWarning.compareAndSet(lastOverflowWarning.get(), now)) {
                plugin.getLogger().warning(plugin.getConfigManager().getMessages().get("discord.queue-overflow", Map.of("event", message.embedKind())));
            }
            if (message.completion() != null) {
                message.completion().completeExceptionally(new IllegalStateException("queue-overflow"));
            }
        }
    }

    private void dispatchLoop() {
        Map<String, ArrayDeque<WebhookMessage>> buckets = new LinkedHashMap<>();
        while (accepting.get() || !queue.isEmpty()) {
            try {
                WebhookMessage first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first != null) {
                    buckets.computeIfAbsent(first.channel(), ignored -> new ArrayDeque<>()).add(first);
                    WebhookMessage next;
                    while ((next = queue.poll()) != null) {
                        buckets.computeIfAbsent(next.channel(), ignored -> new ArrayDeque<>()).add(next);
                    }
                }
                for (ArrayDeque<WebhookMessage> bucket : buckets.values()) {
                    if (!bucket.isEmpty()) {
                        List<WebhookMessage> batch = new ArrayList<>(10);
                        while (batch.size() < 10 && !bucket.isEmpty()) {
                            batch.add(bucket.removeFirst());
                        }
                        sendBatch(batch, 0);
                    }
                }
                buckets.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, plugin.getConfigManager().getMessages().get("discord.send-failed"), exception);
            }
        }
        for (ArrayDeque<WebhookMessage> bucket : buckets.values()) {
            if (!bucket.isEmpty()) {
                sendBatch(new ArrayList<>(bucket), 0);
            }
        }
    }

    private void sendBatch(List<WebhookMessage> batch, int attempt) {
        if (batch.isEmpty()) {
            return;
        }
        PluginSettings settings = plugin.getConfigManager().getSettings();
        String url = settings.discord().webhooks().getOrDefault(batch.get(0).channel(), "");
        if (url.isBlank()) {
            complete(batch, new IllegalStateException("webhook-not-configured"));
            return;
        }
        JsonArray embeds = new JsonArray();
        for (WebhookMessage message : batch) {
            embeds.add(toEmbed(message, settings));
        }
        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null) {
                retry(batch, attempt, error);
                return;
            }
            if (response.statusCode() == 429) {
                long retryAfter = retryAfterMillis(response, attempt);
                warnDelivery("seconds", String.valueOf(Math.max(1L, retryAfter / 1000L)));
                retryExecutor.schedule(() -> sendBatch(batch, attempt + 1), retryAfter, TimeUnit.MILLISECONDS);
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                retry(batch, attempt, new IllegalStateException("HTTP " + response.statusCode()));
                return;
            }
            complete(batch, null);
        });
    }

    private void retry(List<WebhookMessage> batch, int attempt, Throwable error) {
        warnDelivery("reason", error.getClass().getSimpleName());
        long delay = Math.min(60000L, 1000L * (1L << Math.min(attempt, 6)));
        retryExecutor.schedule(() -> sendBatch(batch, attempt + 1), delay, TimeUnit.MILLISECONDS);
    }

    private long retryAfterMillis(HttpResponse<?> response, int attempt) {
        try {
            String value = response.headers().firstValue("Retry-After").orElse("");
            if (!value.isBlank()) {
                return Math.min(60000L, Math.max(1000L, (long) (Double.parseDouble(value) * 1000D)));
            }
        } catch (Exception ignored) {
        }
        return Math.min(60000L, 1000L * (1L << Math.min(attempt, 6)));
    }

    private void complete(List<WebhookMessage> batch, Throwable error) {
        for (WebhookMessage message : batch) {
            if (message.completion() == null) {
                continue;
            }
            if (error == null) {
                message.completion().complete(null);
            } else {
                message.completion().completeExceptionally(error);
            }
        }
    }

    private void warnDelivery(String placeholder, String value) {
        long now = System.currentTimeMillis();
        long previous = lastDeliveryWarning.get();
        if (now - previous > 60000L && lastDeliveryWarning.compareAndSet(previous, now)) {
            String key = placeholder.equals("seconds") ? "discord.rate-limited" : "discord.send-failed";
            plugin.getLogger().warning(plugin.getConfigManager().getMessages().get(key, Map.of(placeholder, value)));
        }
    }

    private JsonObject toEmbed(WebhookMessage message, PluginSettings settings) {
        MessageManager messages = plugin.getConfigManager().getMessages();
        DiscordSettings discord = settings.discord();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", messages.get("discord.embed." + message.embedKind() + ".title"));
        String descriptionKey = "discord.embed." + message.embedKind() + ".description";
        String description = messages.get(descriptionKey);
        if (!description.equals(descriptionKey)) {
            embed.addProperty("description", description);
        }
        embed.addProperty("color", discord.embedColors().getOrDefault(colorKey(message.embedKind()), discord.embedColors().get("command")));
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray fields = new JsonArray();
        JsonObject serverField = new JsonObject();
        serverField.addProperty("name", messages.get("discord.embed.server"));
        serverField.addProperty("value", discord.serverName());
        serverField.addProperty("inline", true);
        fields.add(serverField);
        message.fields().forEach((key, value) -> {
            JsonObject field = new JsonObject();
            field.addProperty("name", messages.get("discord.embed.fields." + fieldKey(key)));
            field.addProperty("value", value == null || value.isBlank() ? "-" : value);
            field.addProperty("inline", true);
            fields.add(field);
        });
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", messages.get("discord.embed.footer"));
        embed.add("footer", footer);
        if (discord.playerHeadThumbnail() && message.uuid() != null) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", "https://mc-heads.net/avatar/" + message.uuid() + "/64");
            embed.add("thumbnail", thumbnail);
        }
        return embed;
    }

    private String colorKey(String embedKind) {
        return switch (embedKind) {
            case "join" -> "join";
            case "quit" -> "quit";
            case "vpn-suspected" -> "vpn";
            default -> "command";
        };
    }

    private String fieldKey(String key) {
        return switch (key) {
            case "gm" -> "gamemode";
            case "ver" -> "version";
            case "session_seconds" -> "session";
            default -> key;
        };
    }

    private Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(false);
        return thread;
    }

    private record WebhookMessage(String channel, String embedKind, Map<String, String> fields, UUID uuid, CompletableFuture<Void> completion, int attempt) {
    }
}

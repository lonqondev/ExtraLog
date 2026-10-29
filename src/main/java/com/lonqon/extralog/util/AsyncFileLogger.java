package com.lonqon.extralog.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lonqon.extralog.ExtraLogPlugin;
import com.lonqon.extralog.config.PluginSettings;
import com.lonqon.extralog.i18n.MessageManager;
import com.lonqon.extralog.logging.LogEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

public final class AsyncFileLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter HEADER_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final ExtraLogPlugin plugin;
    private final Supplier<PluginSettings> settingsSupplier;
    private final Supplier<MessageManager> messagesSupplier;
    private final BlockingQueue<LogEvent> queue;
    private final ExecutorService writerExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Gson gson = new Gson();

    public AsyncFileLogger(ExtraLogPlugin plugin, Supplier<PluginSettings> settingsSupplier, Supplier<MessageManager> messagesSupplier) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.messagesSupplier = messagesSupplier;
        this.queue = new LinkedBlockingQueue<>(settingsSupplier.get().queueCapacity());
        this.writerExecutor = Executors.newSingleThreadExecutor(runnable -> namedThread(runnable, "ExtraLog-FileLogger"));
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> namedThread(runnable, "ExtraLog-LogMaintenance"));
    }

    public void start() {
        writerExecutor.submit(this::runWriter);
        maintenanceExecutor.submit(this::maintainLogs);
        maintenanceExecutor.scheduleAtFixedRate(this::maintainLogs, 24, 24, TimeUnit.HOURS);
    }

    public void log(LogEvent event) {
        if (!accepting.get()) {
            return;
        }
        if (!queue.offer(event)) {
            plugin.getLogger().warning(messagesSupplier.get().get("logger.queue-overflow", Map.of("event", event.eventType())));
        }
    }

    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        queue.offer(LogEvent.shutdownEvent());
        writerExecutor.shutdown();
        maintenanceExecutor.shutdownNow();
        try {
            if (!writerExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            writerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runWriter() {
        BufferedWriter writer = null;
        Path currentPath = null;
        LocalDate currentDate = null;
        long currentSize = 0L;
        int rotation = 0;
        try {
            while (accepting.get() || !queue.isEmpty()) {
                PluginSettings settings = settingsSupplier.get();
                ArrayList<LogEvent> batch = new ArrayList<>();
                LogEvent first = queue.poll(settings.flushIntervalMillis(), TimeUnit.MILLISECONDS);
                if (first != null && !first.shutdownMarker()) {
                    batch.add(first);
                    queue.drainTo(batch);
                    batch.removeIf(LogEvent::shutdownMarker);
                }
                if (batch.isEmpty()) {
                    if (writer != null) {
                        writer.flush();
                    }
                    continue;
                }
                for (LogEvent event : batch) {
                    LocalDate date = LocalDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault()).toLocalDate();
                    if (writer == null || !date.equals(currentDate) || !sameDirectory(currentPath, settings)) {
                        closeWriter(writer);
                        currentDate = date;
                        rotation = findRotation(settings, date);
                        currentPath = filePath(settings, date, rotation);
                        currentSize = Files.exists(currentPath) ? Files.size(currentPath) : 0L;
                        writer = openWriter(currentPath, currentSize == 0L, date, settings);
                        if (currentSize == 0L) {
                            currentSize = headerBytes(settings, date).length;
                        }
                    }
                    String rendered = render(event, settings);
                    byte[] bytes = (rendered + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                    if (settings.maxFileSizeBytes() > 0 && currentSize > 0 && currentSize + bytes.length > settings.maxFileSizeBytes()) {
                        closeWriter(writer);
                        rotation++;
                        currentPath = filePath(settings, date, rotation);
                        currentSize = Files.exists(currentPath) ? Files.size(currentPath) : 0L;
                        writer = openWriter(currentPath, currentSize == 0L, date, settings);
                        if (currentSize == 0L) {
                            currentSize = headerBytes(settings, date).length;
                        }
                    }
                    writer.write(rendered);
                    writer.newLine();
                    currentSize += bytes.length;
                }
                writer.flush();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            drainQueue(writer, settingsSupplier.get());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, messagesSupplier.get().get("logger.writer-error"), exception);
        } finally {
            closeWriter(writer);
        }
    }

    private void drainQueue(BufferedWriter writer, PluginSettings settings) {
        try {
            Path path = filePath(settings, LocalDate.now(), 0);
            if (writer == null) {
                writer = openWriter(path, !Files.exists(path), LocalDate.now(), settings);
            }
            LogEvent event;
            while ((event = queue.poll()) != null) {
                if (!event.shutdownMarker()) {
                    writer.write(render(event, settings));
                    writer.newLine();
                }
            }
            writer.flush();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, messagesSupplier.get().get("logger.flush-error"), exception);
        } finally {
            closeWriter(writer);
        }
    }

    private BufferedWriter openWriter(Path path, boolean writeHeader, LocalDate date, PluginSettings settings) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (writeHeader) {
            writer.write(header(settings, date));
            writer.newLine();
            writer.flush();
        }
        return writer;
    }

    private String header(PluginSettings settings, LocalDate date) {
        if (settings.logFormat().equals("JSONL")) {
            JsonObject header = new JsonObject();
            header.addProperty("type", "header");
            header.addProperty("plugin", "ExtraLog 1.x");
            header.addProperty("date", HEADER_DATE_FORMAT.format(date));
            header.addProperty("server", settings.discord().serverName());
            header.addProperty("timezone", ZoneId.systemDefault().toString());
            return gson.toJson(header);
        }
        String dateText = HEADER_DATE_FORMAT.format(date);
        String server = clean(settings.discord().serverName());
        String timezone = ZoneId.systemDefault().toString();
        return String.join(System.lineSeparator(),
                "# ================================================================================",
                "# ExtraLog 1.x | " + dateText + " | " + server + " | " + timezone,
                "# Daily activity log",
                "# -------------------------------------------------------------------------------",
                "# timestamp                 | event           | player | details                       | uuid",
                "# ================================================================================");
    }

    private byte[] headerBytes(PluginSettings settings, LocalDate date) {
        return (header(settings, date) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private String render(LogEvent event, PluginSettings settings) {
        if (settings.logFormat().equals("JSONL")) {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", event.timestamp().toString());
            json.addProperty("event", event.eventType());
            json.addProperty("player", event.playerName());
            event.fields().forEach(json::addProperty);
            json.addProperty("uuid", event.uuid() == null ? "-" : event.uuid().toString());
            return gson.toJson(json);
        }
        StringBuilder line = new StringBuilder();
        line.append(event.timestamp().atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMAT));
        line.append(" | ").append(String.format("%-15s", clean(event.eventType())));
        line.append(" | ").append(clean(event.playerName()));
        event.fields().forEach((key, value) -> line.append(" | ").append(clean(key)).append("=").append(clean(value)));
        line.append(" | uuid=").append(event.uuid() == null ? "-" : event.uuid());
        return line.toString();
    }

    private int findRotation(PluginSettings settings, LocalDate date) {
        int rotation = 0;
        while (Files.exists(filePath(settings, date, rotation)) && settings.maxFileSizeBytes() > 0) {
            try {
                if (Files.size(filePath(settings, date, rotation)) < settings.maxFileSizeBytes()) {
                    return rotation;
                }
            } catch (IOException exception) {
                return rotation;
            }
            rotation++;
        }
        return rotation;
    }

    private Path filePath(PluginSettings settings, LocalDate date, int rotation) {
        Path directory = plugin.getDataFolder().toPath().resolve(settings.logDirectory()).normalize();
        String fileName = HEADER_DATE_FORMAT.format(date) + (rotation == 0 ? "" : "-" + rotation) + ".log";
        return directory.resolve(fileName);
    }

    private boolean sameDirectory(Path path, PluginSettings settings) {
        return path != null && path.getParent().equals(plugin.getDataFolder().toPath().resolve(settings.logDirectory()).normalize());
    }

    private void closeWriter(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException exception) {
            plugin.getLogger().warning(messagesSupplier.get().get("logger.close-error"));
        }
    }

    private void maintainLogs() {
        try {
            PluginSettings settings = settingsSupplier.get();
            Path directory = plugin.getDataFolder().toPath().resolve(settings.logDirectory()).normalize();
            if (!Files.exists(directory)) {
                return;
            }
            LocalDate today = LocalDate.now();
            try (var paths = Files.list(directory)) {
                paths.sorted(Comparator.comparing(Path::toString)).forEach(path -> maintainFile(path, today, settings));
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, messagesSupplier.get().get("logger.maintenance-error"), exception);
        }
    }

    private void maintainFile(Path path, LocalDate today, PluginSettings settings) {
        String name = path.getFileName().toString();
        if (!(name.endsWith(".log") || name.endsWith(".log.gz")) || name.length() < 14) {
            return;
        }
        try {
            LocalDate date = LocalDate.parse(name.substring(0, 10), HEADER_DATE_FORMAT);
            if (!date.isBefore(today)) {
                return;
            }
            if (settings.retentionDays() > 0 && date.isBefore(today.minusDays(settings.retentionDays()))) {
                Files.deleteIfExists(path);
                return;
            }
            if (settings.compressOldLogs() && name.endsWith(".log")) {
                compress(path);
            }
        } catch (Exception ignored) {
        }
    }

    private void compress(Path source) throws IOException {
        Path target = source.resolveSibling(source.getFileName() + ".gz");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)); var input = Files.newInputStream(source)) {
            input.transferTo(output);
        }
        Files.deleteIfExists(source);
    }

    private Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(false);
        return thread;
    }

    private String clean(String value) {
        return value == null ? "-" : value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}

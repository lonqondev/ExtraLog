package com.lonqon.extralog.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

public record LogEvent(
        Instant timestamp,
        String eventType,
        String playerName,
        Map<String, String> fields,
        UUID uuid,
        boolean shutdownMarker
) {
    public LogEvent {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static LogEvent create(String eventType, String playerName, Map<String, String> fields, UUID uuid) {
        return new LogEvent(Instant.now(), eventType, playerName, fields, uuid, false);
    }

    public static LogEvent shutdownEvent() {
        return new LogEvent(Instant.EPOCH, "", "", Map.of(), null, true);
    }
}

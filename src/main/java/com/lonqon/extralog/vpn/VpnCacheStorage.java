package com.lonqon.extralog.vpn;

import com.lonqon.extralog.ExtraLogPlugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class VpnCacheStorage {
    private final ExtraLogPlugin plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ExtraLog-VpnSQLite");
        thread.setDaemon(false);
        return thread;
    });
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private Connection connection;

    public VpnCacheStorage(ExtraLogPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        executor.execute(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                Path database = plugin.getDataFolder().toPath().resolve("extralog.db");
                connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS vpn_cache (ip_hash TEXT NOT NULL, is_vpn INTEGER NOT NULL, provider_name TEXT NOT NULL, country TEXT, risk TEXT, checked_at INTEGER NOT NULL, PRIMARY KEY (ip_hash, provider_name))");
                }
                ready.complete(null);
            } catch (Exception exception) {
                ready.completeExceptionally(exception);
                plugin.getLogger().log(Level.SEVERE, plugin.getConfigManager().getMessages().get("vpn.sqlite-start-failed"), exception);
            }
        });
    }

    public CompletableFuture<Optional<VpnCacheEntry>> find(String ipHash, String providerName, long minimumCheckedAt) {
        return ready.thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT is_vpn, provider_name, country, risk, checked_at FROM vpn_cache WHERE ip_hash = ? AND provider_name = ? AND checked_at >= ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ipHash);
                statement.setString(2, providerName);
                statement.setLong(3, minimumCheckedAt);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new VpnCacheEntry(
                            resultSet.getInt("is_vpn") == 1,
                            resultSet.getString("provider_name"),
                            resultSet.getString("country"),
                            resultSet.getString("risk"),
                            resultSet.getLong("checked_at")
                    ));
                }
            } catch (SQLException exception) {
                throw new VpnStorageException("VPN cache okunamadı.", exception);
            }
        }, executor));
    }

    public CompletableFuture<Void> save(String ipHash, VpnProvider provider, VpnProviderResult result) {
        return ready.thenCompose(ignored -> CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO vpn_cache (ip_hash, is_vpn, provider_name, country, risk, checked_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(ip_hash, provider_name) DO UPDATE SET is_vpn = excluded.is_vpn, country = excluded.country, risk = excluded.risk, checked_at = excluded.checked_at";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ipHash);
                statement.setInt(2, result.vpn() ? 1 : 0);
                statement.setString(3, provider.name());
                statement.setString(4, result.country());
                statement.setString(5, result.risk());
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new VpnStorageException("VPN cache yazılamadı.", exception);
            }
        }, executor));
    }

    public void close() {
        executor.execute(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException exception) {
                    plugin.getLogger().log(Level.WARNING, plugin.getConfigManager().getMessages().get("vpn.sqlite-close-failed"), exception);
                }
            }
        });
        executor.shutdown();
    }

    private static final class VpnStorageException extends RuntimeException {
        private VpnStorageException(String message) {
            super(message);
        }

        private VpnStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.github.cinnaio.essentialengine.core.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * SQLite / MySQL 共用的实现。
 *
 * <p>表结构刻意做得很简单：复杂字段（家、邮件、屏蔽名单……）统一序列化成 JSON
 * 存在 {@code data} 列里，只把 {@code name} 和 {@code balance} 单独拆出来，
 * 因为这两个需要被查询（按名字找人、余额排行榜）。
 * 这样既不用写迁移脚本，也保证和 YAML 后端的数据结构完全一致。</p>
 */
public abstract class SqlStorage implements StorageProvider {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final Gson GSON = new Gson();

    protected final Plugin plugin;
    protected final String prefix;

    private Driver driver;
    private Connection connection;

    protected SqlStorage(Plugin plugin, String tablePrefix) {
        this.plugin = plugin;
        this.prefix = tablePrefix == null || tablePrefix.isEmpty() ? "ee_" : tablePrefix;
    }

    protected abstract Driver createDriver() throws Exception;

    protected abstract String jdbcUrl();

    protected abstract Properties connectionProperties();

    protected abstract String createUsersTableSql();

    protected abstract String createGlobalsTableSql();

    protected abstract String upsertUserSql();

    protected abstract String upsertGlobalSql();

    protected String usersTable() {
        return prefix + "users";
    }

    protected String globalsTable() {
        return prefix + "globals";
    }

    @Override
    public void init() throws Exception {
        this.driver = createDriver();
        try (Connection conn = openConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate(createUsersTableSql());
            statement.executeUpdate(createGlobalsTableSql());
        }
        // 连接保持复用
        this.connection = openConnection();
    }

    private Connection openConnection() throws SQLException {
        Properties properties = connectionProperties();
        Connection conn = driver.connect(jdbcUrl(), properties);
        if (conn == null) {
            throw new SQLException("驱动拒绝了连接串，请检查 storage 配置: " + jdbcUrl());
        }
        return conn;
    }

    protected synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(3)) {
            closeQuietly();
            connection = openConnection();
        }
        return connection;
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }

    @Override
    public void shutdown() {
        closeQuietly();
    }

    @Override
    public synchronized Map<String, Object> loadUser(UUID uuid) throws Exception {
        String sql = "SELECT data FROM " + usersTable() + " WHERE uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String json = rs.getString("data");
                if (json == null || json.isEmpty()) {
                    return new LinkedHashMap<>();
                }
                Map<String, Object> map = GSON.fromJson(json, MAP_TYPE);
                return map == null ? new LinkedHashMap<>() : map;
            }
        }
    }

    @Override
    public synchronized void saveUser(UUID uuid, String name, double balance, Map<String, Object> data)
            throws Exception {
        try (PreparedStatement statement = connection().prepareStatement(upsertUserSql())) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name == null ? "" : name);
            statement.setDouble(3, balance);
            statement.setString(4, GSON.toJson(data));
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized UUID lookupUuid(String name) throws Exception {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String sql = "SELECT uuid FROM " + usersTable() + " WHERE LOWER(name) = ? LIMIT 1";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, name.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                try {
                    return UUID.fromString(rs.getString("uuid"));
                } catch (IllegalArgumentException error) {
                    return null;
                }
            }
        }
    }

    @Override
    public synchronized List<UUID> allUsers() throws Exception {
        List<UUID> result = new ArrayList<>();
        String sql = "SELECT uuid FROM " + usersTable();
        try (PreparedStatement statement = connection().prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                try {
                    result.add(UUID.fromString(rs.getString("uuid")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return result;
    }

    @Override
    public synchronized LinkedHashMap<String, Double> topBalances(int limit) throws Exception {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        String sql = "SELECT name, balance FROM " + usersTable()
                + " WHERE name <> '' ORDER BY balance DESC LIMIT ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("name"), rs.getDouble("balance"));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized Map<String, Object> loadGlobal(String key) throws Exception {
        String sql = "SELECT value FROM " + globalsTable() + " WHERE id = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String json = rs.getString("value");
                if (json == null || json.isEmpty()) {
                    return new LinkedHashMap<>();
                }
                Map<String, Object> map = GSON.fromJson(json, MAP_TYPE);
                return map == null ? new LinkedHashMap<>() : map;
            }
        }
    }

    @Override
    public synchronized void saveGlobal(String key, Map<String, Object> value) throws Exception {
        try (PreparedStatement statement = connection().prepareStatement(upsertGlobalSql())) {
            statement.setString(1, key);
            statement.setString(2, GSON.toJson(value));
            statement.executeUpdate();
        }
    }
}

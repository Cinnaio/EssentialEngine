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

    protected String transactionsTable() {
        return prefix + "transactions";
    }

    /** 流水表建表语句。两种方言的自增主键写法不同，交给子类。 */
    protected abstract String createTransactionsTableSql();

    /**
     * 建表之后要额外执行的索引语句。
     *
     * <p>MySQL 不支持 {@code CREATE INDEX IF NOT EXISTS}，所以它把索引写在建表语句里、
     * 这里返回空数组；SQLite 支持，就在这里单独建。</p>
     */
    protected String[] extraIndexSql() {
        return new String[0];
    }

    @Override
    public void init() throws Exception {
        this.driver = createDriver();
        try (Connection conn = openConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate(createUsersTableSql());
            statement.executeUpdate(createGlobalsTableSql());
            statement.executeUpdate(createTransactionsTableSql());
            for (String sql : extraIndexSql()) {
                statement.executeUpdate(sql);
            }
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

    // ------------------------------------------------------------------ 经济流水

    @Override
    public synchronized void appendTransactions(List<TransactionRecord> records) throws Exception {
        if (records == null || records.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + transactionsTable()
                + " (ts, uuid, name, type, amount, balance_after, source, detail)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        Connection conn = connection();
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            for (TransactionRecord record : records) {
                statement.setLong(1, record.timestamp());
                statement.setString(2, record.uuid().toString());
                statement.setString(3, record.name() == null ? "" : record.name());
                statement.setString(4, record.type());
                statement.setDouble(5, record.amount());
                statement.setDouble(6, record.balanceAfter());
                statement.setString(7, record.source() == null ? "" : record.source());
                statement.setString(8, record.detail() == null ? "" : record.detail());
                statement.addBatch();
            }
            statement.executeBatch();
            conn.commit();
        } catch (Exception error) {
            conn.rollback();
            throw error;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    @Override
    public synchronized List<TransactionRecord> recentTransactions(UUID player, int limit) throws Exception {
        List<TransactionRecord> result = new ArrayList<>();
        String sql = "SELECT ts, uuid, name, type, amount, balance_after, source, detail FROM "
                + transactionsTable()
                + (player == null ? "" : " WHERE uuid = ?")
                + " ORDER BY ts DESC LIMIT ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            int index = 1;
            if (player != null) {
                statement.setString(index++, player.toString());
            }
            statement.setInt(index, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString("uuid"));
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    result.add(new TransactionRecord(
                            rs.getLong("ts"), uuid, rs.getString("name"), rs.getString("type"),
                            rs.getDouble("amount"), rs.getDouble("balance_after"),
                            rs.getString("source"), rs.getString("detail")));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized List<SourceVolume> volumeBySource(long since) throws Exception {
        List<SourceVolume> result = new ArrayList<>();
        // 用 CASE 在库里直接算进出，省得把整段流水拉回内存
        String sql = "SELECT source,"
                + " SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE 0 END) AS inflow,"
                + " SUM(CASE WHEN type = 'WITHDRAW' THEN amount ELSE 0 END) AS outflow,"
                + " COUNT(*) AS cnt"
                + " FROM " + transactionsTable() + " WHERE ts >= ?"
                + " GROUP BY source ORDER BY cnt DESC";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setLong(1, since);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new SourceVolume(rs.getString("source"),
                            rs.getDouble("inflow"), rs.getDouble("outflow"), rs.getLong("cnt")));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized int pruneTransactions(long before) throws Exception {
        String sql = "DELETE FROM " + transactionsTable() + " WHERE ts < ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setLong(1, before);
            return statement.executeUpdate();
        }
    }

    @Override
    public synchronized EconomySummary economySummary() throws Exception {
        String sql = "SELECT COUNT(*) AS accounts, COALESCE(SUM(balance), 0) AS total,"
                + " COALESCE(MAX(balance), 0) AS richest FROM " + usersTable();
        try (PreparedStatement statement = connection().prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return EconomySummary.EMPTY;
            }
            long accounts = rs.getLong("accounts");
            double total = rs.getDouble("total");
            return new EconomySummary(accounts, total,
                    accounts == 0 ? 0 : total / accounts, rs.getDouble("richest"));
        }
    }
}

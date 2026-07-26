package com.github.cinnaio.essentialengine.core.storage;

import org.bukkit.plugin.Plugin;

import java.sql.Driver;
import java.util.Properties;

/** MySQL / MariaDB 后端，适合多服共享同一份玩家数据。 */
public class MysqlStorage extends SqlStorage {

    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String GROUP = "com.mysql";
    private static final String ARTIFACT = "mysql-connector-j";

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String parameters;
    private final String version;
    private final String repository;

    public MysqlStorage(Plugin plugin, String tablePrefix, String host, int port, String database,
                        String username, String password, String parameters,
                        String version, String repository) {
        super(plugin, tablePrefix);
        this.host = host == null || host.isEmpty() ? "127.0.0.1" : host;
        this.port = port <= 0 ? 3306 : port;
        this.database = database == null || database.isEmpty() ? "minecraft" : database;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.parameters = parameters == null ? "" : parameters;
        this.version = version == null || version.isEmpty() ? "9.1.0" : version;
        this.repository = repository;
    }

    @Override
    public String getName() {
        return "MySQL";
    }

    @Override
    protected Driver createDriver() throws Exception {
        return DependencyLoader.loadDriver(plugin, repository, GROUP, ARTIFACT, version, DRIVER_CLASS);
    }

    @Override
    protected String jdbcUrl() {
        String suffix = parameters.isEmpty() ? "" : (parameters.startsWith("?") ? parameters : "?" + parameters);
        return "jdbc:mysql://" + host + ":" + port + "/" + database + suffix;
    }

    @Override
    protected Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return properties;
    }

    @Override
    protected String createUsersTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + usersTable() + " ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "name VARCHAR(32) NOT NULL DEFAULT '',"
                + "balance DOUBLE NOT NULL DEFAULT 0,"
                + "data MEDIUMTEXT NOT NULL,"
                + "INDEX idx_name (name),"
                + "INDEX idx_balance (balance)"
                + ") DEFAULT CHARSET=utf8mb4";
    }

    @Override
    protected String createGlobalsTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + globalsTable() + " ("
                + "id VARCHAR(64) NOT NULL PRIMARY KEY,"
                + "value MEDIUMTEXT NOT NULL"
                + ") DEFAULT CHARSET=utf8mb4";
    }

    @Override
    protected String createTransactionsTableSql() {
        // MySQL 不支持 CREATE INDEX IF NOT EXISTS，索引直接写在建表语句里
        return "CREATE TABLE IF NOT EXISTS " + transactionsTable() + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "ts BIGINT NOT NULL,"
                + "uuid VARCHAR(36) NOT NULL,"
                + "name VARCHAR(32) NOT NULL DEFAULT '',"
                + "type VARCHAR(16) NOT NULL,"
                + "amount DOUBLE NOT NULL DEFAULT 0,"
                + "balance_after DOUBLE NOT NULL DEFAULT 0,"
                + "source VARCHAR(64) NOT NULL DEFAULT '',"
                + "detail VARCHAR(128) NOT NULL DEFAULT '',"
                + "INDEX idx_tx_ts (ts),"
                + "INDEX idx_tx_uuid (uuid)"
                + ") DEFAULT CHARSET=utf8mb4";
    }

    @Override
    protected String upsertUserSql() {
        return "INSERT INTO " + usersTable() + " (uuid, name, balance, data) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), balance = VALUES(balance), data = VALUES(data)";
    }

    @Override
    protected String upsertGlobalSql() {
        return "INSERT INTO " + globalsTable() + " (id, value) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE value = VALUES(value)";
    }
}

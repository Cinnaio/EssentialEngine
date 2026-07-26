package com.github.cinnaio.essentialengine.core.storage;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Driver;
import java.util.Properties;

/** SQLite 后端：单文件数据库，驱动首次使用时自动下载到 plugins/EssentialEngine/libs。 */
public class SqliteStorage extends SqlStorage {

    private static final String DRIVER_CLASS = "org.sqlite.JDBC";
    private static final String GROUP = "org.xerial";
    private static final String ARTIFACT = "sqlite-jdbc";

    private final String version;
    private final String repository;
    private final File databaseFile;

    public SqliteStorage(Plugin plugin, String tablePrefix, String fileName, String version, String repository) {
        super(plugin, tablePrefix);
        this.version = version == null || version.isEmpty() ? "3.47.1.0" : version;
        this.repository = repository;
        this.databaseFile = new File(plugin.getDataFolder(),
                fileName == null || fileName.isEmpty() ? "database.db" : fileName);
    }

    @Override
    public String getName() {
        return "SQLite";
    }

    @Override
    protected Driver createDriver() throws Exception {
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + parent.getAbsolutePath());
        }
        return DependencyLoader.loadDriver(plugin, repository, GROUP, ARTIFACT, version, DRIVER_CLASS);
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:sqlite:" + databaseFile.getAbsolutePath();
    }

    @Override
    protected Properties connectionProperties() {
        return new Properties();
    }

    @Override
    protected String createUsersTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + usersTable() + " ("
                + "uuid TEXT NOT NULL PRIMARY KEY,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "balance REAL NOT NULL DEFAULT 0,"
                + "data TEXT NOT NULL DEFAULT '{}'"
                + ")";
    }

    @Override
    protected String createGlobalsTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + globalsTable() + " ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "value TEXT NOT NULL DEFAULT '{}'"
                + ")";
    }

    @Override
    protected String createTransactionsTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + transactionsTable() + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"
                + "uuid TEXT NOT NULL,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "type TEXT NOT NULL,"
                + "amount REAL NOT NULL DEFAULT 0,"
                + "balance_after REAL NOT NULL DEFAULT 0,"
                + "source TEXT NOT NULL DEFAULT '',"
                + "detail TEXT NOT NULL DEFAULT ''"
                + ")";
    }

    @Override
    protected String[] extraIndexSql() {
        // 流水的两种查询模式：按时间倒序翻页、按玩家过滤
        return new String[]{
                "CREATE INDEX IF NOT EXISTS idx_" + transactionsTable() + "_ts ON "
                        + transactionsTable() + " (ts)",
                "CREATE INDEX IF NOT EXISTS idx_" + transactionsTable() + "_uuid ON "
                        + transactionsTable() + " (uuid)"
        };
    }

    @Override
    protected String upsertUserSql() {
        return "INSERT INTO " + usersTable() + " (uuid, name, balance, data) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, "
                + "balance = excluded.balance, data = excluded.data";
    }

    @Override
    protected String upsertGlobalSql() {
        return "INSERT INTO " + globalsTable() + " (id, value) VALUES (?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET value = excluded.value";
    }
}

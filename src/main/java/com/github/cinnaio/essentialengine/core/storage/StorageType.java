package com.github.cinnaio.essentialengine.core.storage;

import java.util.Locale;

/** 支持的存储后端。 */
public enum StorageType {

    /** 每个玩家一个 yml 文件，零外部依赖，适合中小型服务器。 */
    YAML,
    /** 单文件数据库，玩家量大时性能更好，驱动首次使用时自动下载。 */
    SQLITE,
    /** 适合多服共享数据，驱动首次使用时自动下载。 */
    MYSQL;

    public static StorageType parse(String raw) {
        if (raw == null) {
            return YAML;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "SQLITE", "SQLITE3", "SQL_LITE" -> SQLITE;
            case "MYSQL", "MARIADB", "MY_SQL" -> MYSQL;
            default -> YAML;
        };
    }
}

package com.github.cinnaio.essentialengine.core.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 存储后端统一接口。
 *
 * <p>玩家数据一律以 {@code Map<String, Object>} 的形式进出，
 * YAML 直接写成配置节点，SQLite / MySQL 则序列化成 JSON 存进一个 TEXT 字段。
 * 这样三种后端共用同一套序列化逻辑，互相迁移时结构完全一致。</p>
 *
 * <p>所有方法都可能阻塞（磁盘 / 网络），调用方必须在异步线程执行。</p>
 */
public interface StorageProvider {

    /** 后端名称，用于日志展示。 */
    String getName();

    void init() throws Exception;

    void shutdown();

    /** 读取玩家数据，不存在时返回 null。 */
    Map<String, Object> loadUser(UUID uuid) throws Exception;

    /** 写入玩家数据。name 与 balance 单独存一份，便于按名字查询和排行榜。 */
    void saveUser(UUID uuid, String name, double balance, Map<String, Object> data) throws Exception;

    /** 按玩家名反查 UUID（不区分大小写），查不到返回 null。 */
    UUID lookupUuid(String name) throws Exception;

    /** 全部已记录玩家的 UUID。 */
    List<UUID> allUsers() throws Exception;

    /** 余额排行榜，返回顺序即名次。key 为玩家名。 */
    LinkedHashMap<String, Double> topBalances(int limit) throws Exception;

    /** 读取全局数据（如 warps、spawn），不存在时返回 null。 */
    Map<String, Object> loadGlobal(String key) throws Exception;

    /** 写入全局数据。 */
    void saveGlobal(String key, Map<String, Object> value) throws Exception;
}

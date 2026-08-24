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

    /**
     * 按名字片段搜索玩家（含离线），不区分大小写，按名字排序。
     *
     * <p>{@code query} 为空时返回空列表——「列出全部玩家」对老服来说是几万条，
     * 没有分页的接口不该提供这种能力。</p>
     */
    default List<UserSummary> searchUsers(String query, int limit) throws Exception {
        return List.of();
    }

    /** 读取全局数据（如 warps、spawn），不存在时返回 null。 */
    Map<String, Object> loadGlobal(String key) throws Exception;

    /** 写入全局数据。 */
    void saveGlobal(String key, Map<String, Object> value) throws Exception;

    // ------------------------------------------------------------------ 经济流水
    //
    // 默认实现全部是空操作，这样新后端不实现也能跑（只是没有流水统计）。
    // 内置的三个后端都做了真正的实现。

    /** 批量追加流水。调用方会攒一批再写，不要在每笔交易时单独调用。 */
    default void appendTransactions(List<TransactionRecord> records) throws Exception {
    }

    /** 最近的流水，按时间倒序。{@code player} 为 null 表示查全服。 */
    default List<TransactionRecord> recentTransactions(UUID player, int limit) throws Exception {
        return List.of();
    }

    /** 按来源汇总 {@code since} 之后的资金进出，按笔数倒序。 */
    default List<SourceVolume> volumeBySource(long since) throws Exception {
        return List.of();
    }

    /** 删除 {@code before} 之前的流水，返回删除条数。 */
    default int pruneTransactions(long before) throws Exception {
        return 0;
    }

    /** 全服余额总览。 */
    default EconomySummary economySummary() throws Exception {
        return EconomySummary.EMPTY;
    }

    // ------------------------------------------------------------------ 监控数据
    //
    // 性能采样与监控事件（卡顿、启动/关闭、异常退出……）。默认实现全部是空操作，
    // 新后端不实现也能跑（只是没有监控历史）；内置的三个后端都做了真正的实现。

    /** 批量追加监控事件。调用方会攒一批再写，不要在每笔事件时单独调用。 */
    default void appendMonitorEvents(List<MonitorEvent> records) throws Exception {
    }

    /** 批量追加性能采样。 */
    default void appendMonitorSamples(List<PerfSample> records) throws Exception {
    }

    /** 最近的事件，按时间倒序。{@code type} 为空表示不限类型；{@code since} 为 0 表示不限起点。 */
    default List<MonitorEvent> recentMonitorEvents(int limit, String type, long since) throws Exception {
        return List.of();
    }

    /** {@code since} 之后的性能采样，按时间正序，最多 {@code limit} 条。 */
    default List<PerfSample> recentMonitorSamples(long since, int limit) throws Exception {
        return List.of();
    }

    /** 删除 {@code before} 之前的监控事件，返回删除条数。 */
    default int pruneMonitorEvents(long before) throws Exception {
        return 0;
    }

    /** 删除 {@code before} 之前的性能采样，返回删除条数。 */
    default int pruneMonitorSamples(long before) throws Exception {
        return 0;
    }
}

package com.github.cinnaio.essentialengine.core.storage;

import java.util.Map;

/**
 * 一条监控事件记录。
 *
 * <p>{@code type} 是结构化的事件类型（如 {@code lag}、{@code server_stop}、
 * {@code abnormal_shutdown}），{@code message} 是给人看的中文描述，
 * {@code data} 携带结构化字段（TPS、阈值、原因等），供 API 调用方
 * （如 AstrBot、网页面板）自行解析。</p>
 *
 * <p>随 {@code storage.type} 持久化：YAML 后端写成 JSONL 文件
 * （{@code data/monitor_events.jsonl}），SQLite / MySQL 存进
 * {@code ee_monitor_events} 表，追加是 O(1)，查询按时间倒序扫。</p>
 */
public record MonitorEvent(long timestamp, String type, String message, Map<String, Object> data) {
}

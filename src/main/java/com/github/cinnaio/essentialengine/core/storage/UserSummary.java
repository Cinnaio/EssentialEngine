package com.github.cinnaio.essentialengine.core.storage;

import java.util.UUID;

/**
 * 玩家的一行摘要，用于搜索结果列表。
 *
 * <p>只带名字和余额这两个索引里现成就有的字段——搜索结果可能有几十条，
 * 逐个把完整档案读出来会把 YAML 后端拖垮。要看详情再单独查一个人。</p>
 */
public record UserSummary(UUID uuid, String name, double balance) {
}

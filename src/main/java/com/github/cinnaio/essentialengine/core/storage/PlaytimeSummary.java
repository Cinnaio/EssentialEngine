package com.github.cinnaio.essentialengine.core.storage;

import java.util.UUID;

/**
 * 玩家游玩时长排行榜的一行摘要。
 *
 * <p>排行榜只需要这些字段，不把完整玩家档案带出存储层，避免把家、邮件、
 * 惩罚等与排行无关的数据暴露给调用方。</p>
 */
public record PlaytimeSummary(
        UUID uuid,
        String name,
        long playtimeMs,
        long firstJoin,
        long lastSeen
) {
}

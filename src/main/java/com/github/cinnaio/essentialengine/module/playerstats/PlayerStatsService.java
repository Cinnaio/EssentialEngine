package com.github.cinnaio.essentialengine.module.playerstats;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.storage.PlaytimeSummary;
import com.github.cinnaio.essentialengine.core.user.UserData;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家中心需要的统计查询。
 *
 * <p>它只依赖 {@link EssentialEngine#storage()} 和 {@link UserData}，不把 Web API
 * 或页面逻辑塞进存储后端。在线玩家会覆盖存储里的旧快照，排行榜因此不会等到
 * 下一次自动保存才反映当前会话。</p>
 */
public class PlayerStatsService {

    private final EssentialEngine plugin;

    private volatile List<PlaytimeSummary> leaderboardCache = List.of();
    private volatile long leaderboardCacheAt;

    public PlayerStatsService(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    /** 玩家中心的完整统计档案。 */
    public Map<String, Object> profile(UserData data) throws Exception {
        data.checkpointSession();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", data.getUuid().toString());
        result.put("name", data.getName());
        result.put("nickname", data.getNickname());
        result.put("firstJoin", data.getFirstJoin());
        result.put("lastLogin", data.getLastLogin());
        result.put("lastSeen", data.getLastSeen());
        result.put("playtimeMs", data.getTotalPlaytime());
        result.put("activityByDay", new LinkedHashMap<>(data.getActivityByDay()));
        result.put("activeDays", data.getActivityByDay().size());
        result.put("online", Bukkit.getPlayer(data.getUuid()) != null);
        result.put("afk", data.isAfk());
        result.put("playtimeRank", rankOf(data.getUuid()));
        return result;
    }

    /** 返回按累计游玩时长排序的全服排行榜。 */
    public List<Map<String, Object>> leaderboard(int limit) throws Exception {
        List<PlaytimeSummary> summaries = allPlaytime();
        int count = Math.min(Math.max(1, limit), summaries.size());
        List<Map<String, Object>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            PlaytimeSummary summary = summaries.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", i + 1);
            entry.put("uuid", summary.uuid().toString());
            entry.put("name", summary.name());
            entry.put("playtimeMs", currentPlaytime(summary));
            entry.put("firstJoin", summary.firstJoin());
            entry.put("lastSeen", summary.lastSeen());
            entry.put("online", Bukkit.getPlayer(summary.uuid()) != null);
            result.add(entry);
        }
        return result;
    }

    /** 查询玩家在累计游玩时长榜中的名次，未收录时返回 0。 */
    public int rankOf(UUID uuid) throws Exception {
        List<PlaytimeSummary> summaries = allPlaytime();
        for (int i = 0; i < summaries.size(); i++) {
            if (summaries.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }
        return 0;
    }

    private long currentPlaytime(PlaytimeSummary summary) {
        UserData online = plugin.users().getIfLoaded(summary.uuid());
        return online == null ? summary.playtimeMs() : online.getTotalPlaytime();
    }

    /**
     * 读取并合并存储快照与在线内存数据。
     *
     * <p>默认存储接口为了兼容三种后端会扫描玩家档案；短缓存避免玩家中心同时
     * 打开多个请求时重复扫描。配置为 0 可关闭缓存。</p>
     */
    private List<PlaytimeSummary> allPlaytime() throws Exception {
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(0L, plugin.getConfig()
                .getLong("modules.playerstats.leaderboard-cache-seconds", 15)) * 1000L;
        List<PlaytimeSummary> cached = leaderboardCache;
        if (cacheMillis > 0 && !cached.isEmpty() && now - leaderboardCacheAt < cacheMillis) {
            return cached;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            cached = leaderboardCache;
            if (cacheMillis > 0 && !cached.isEmpty() && now - leaderboardCacheAt < cacheMillis) {
                return cached;
            }

            Map<UUID, PlaytimeSummary> merged = new LinkedHashMap<>();
            for (PlaytimeSummary summary : plugin.storage().topPlaytime(Integer.MAX_VALUE)) {
                merged.put(summary.uuid(), summary);
            }
            for (UserData data : plugin.users().getCached()) {
                data.checkpointSession();
                if (data.getName() == null || data.getName().isEmpty()) {
                    continue;
                }
                merged.put(data.getUuid(), new PlaytimeSummary(
                        data.getUuid(), data.getName(), data.getTotalPlaytime(),
                        data.getFirstJoin(), data.getLastSeen()));
            }

            List<PlaytimeSummary> sorted = new ArrayList<>(merged.values());
            sorted.sort(Comparator.comparingLong(PlaytimeSummary::playtimeMs).reversed()
                    .thenComparing(PlaytimeSummary::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(summary -> summary.uuid().toString()));
            leaderboardCache = List.copyOf(sorted);
            leaderboardCacheAt = now;
            return leaderboardCache;
        }
    }
}

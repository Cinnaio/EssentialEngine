package com.github.cinnaio.essentialengine.module.papi;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 余额排行榜的内存快照。
 *
 * <p>计分板与 Tab 会把 {@code %ee_baltop_name_1%} 这类占位符按秒级频率反复查询，
 * 而排行榜要走存储层（可能是 MySQL），在查询线程上直接读会拖垮主线程。
 * 因此这里只返回内存快照：发现快照过期就顺手触发一次<b>异步</b>刷新，
 * 当次查询仍返回旧值，下一次查询就能拿到新数据。</p>
 */
public class BalTopCache {

    /** 榜上的一条记录。 */
    public record Entry(String name, double balance) {
    }

    private final EssentialEngine plugin;
    private final int size;
    private final long ttlMillis;

    private volatile List<Entry> snapshot = List.of();
    private volatile Map<String, Integer> ranks = Map.of();
    private volatile long refreshedAt;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public BalTopCache(EssentialEngine plugin, int size, int ttlSeconds) {
        this.plugin = plugin;
        this.size = Math.max(1, size);
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
    }

    /** 第 {@code rank} 名（从 1 开始）；名次不存在时返回 null。 */
    public Entry get(int rank) {
        List<Entry> current = touch();
        return rank < 1 || rank > current.size() ? null : current.get(rank - 1);
    }

    /** 某玩家的名次（从 1 开始）；不在缓存的前 N 名内返回 0。 */
    public int rankOf(String name) {
        touch();
        if (name == null) {
            return 0;
        }
        Integer rank = ranks.get(name.toLowerCase(Locale.ROOT));
        return rank == null ? 0 : rank;
    }

    public int cachedSize() {
        return touch().size();
    }

    /** 取快照，顺便在过期时触发一次异步刷新。 */
    private List<Entry> touch() {
        if (System.currentTimeMillis() - refreshedAt >= ttlMillis) {
            refresh();
        }
        return snapshot;
    }

    /** 异步重建快照。已有刷新在跑时直接跳过，避免堆积任务。 */
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        SchedulerCompat.runAsync(plugin, () -> {
            try {
                LinkedHashMap<String, Double> top = plugin.storage().topBalances(size);
                List<Entry> entries = new ArrayList<>(top.size());
                Map<String, Integer> rankMap = new LinkedHashMap<>();
                int rank = 1;
                for (Map.Entry<String, Double> entry : top.entrySet()) {
                    entries.add(new Entry(entry.getKey(), entry.getValue()));
                    rankMap.put(entry.getKey().toLowerCase(Locale.ROOT), rank++);
                }
                this.snapshot = List.copyOf(entries);
                this.ranks = Map.copyOf(rankMap);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "刷新 PlaceholderAPI 余额排行榜缓存失败", error);
            } finally {
                // 无论成功与否都推迟到下个周期再试，避免存储异常时每次查询都重试
                this.refreshedAt = System.currentTimeMillis();
                refreshing.set(false);
            }
        });
    }
}

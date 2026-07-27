package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家页的「最近离线」名单。
 *
 * <p>面板想把离线玩家也列出来，但两条现成的路都不能走：存储层刻意不提供
 * 「列出全部玩家」（老服几万条，没有分页的接口就是事故）；Bukkit 的
 * {@code getOfflinePlayers()} 则要扫整个 playerdata 目录，还可能逐个读 NBT。
 * 这里换个思路：玩家<b>退出时</b>顺手记进一个封顶的名单并异步写进全局存储，
 * 重启不丢，天然有界，读取零开销。</p>
 *
 * <p>代价是名单从部署这一刻起才开始积累——之前就再没上线过的老玩家不会
 * 出现在列表里，要查他们走搜索框。</p>
 */
final class RecentPlayers implements Listener {

    private static final String STORE_KEY = "panel_recent_players";
    private static final int CAP = 30;

    private final EssentialEngine plugin;
    private final Object lock = new Object();
    /** uuid -> 条目。插入序即时间序：最新退出的在末尾。 */
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();

    private record Entry(String name, long lastSeen, double balance) {
    }

    RecentPlayers(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    /** 从全局存储恢复名单。会阻塞，请在异步线程调用。 */
    void load() {
        Map<String, Object> raw;
        try {
            raw = plugin.storage().loadGlobal(STORE_KEY);
        } catch (Exception error) {
            plugin.getLogger().warning("[Panel] 读取最近离线名单失败: " + error.getMessage());
            return;
        }
        if (raw == null || !(raw.get("players") instanceof List<?> items)) {
            return;
        }
        synchronized (lock) {
            for (Object element : items) {
                if (!(element instanceof Map<?, ?> map)) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(String.valueOf(map.get("uuid")));
                    long lastSeen = map.get("lastSeen") instanceof Number number ? number.longValue() : 0L;
                    double balance = map.get("balance") instanceof Number number ? number.doubleValue() : 0D;
                    entries.put(uuid, new Entry(String.valueOf(map.get("name")), lastSeen, balance));
                } catch (IllegalArgumentException ignored) {
                    // 单条损坏就丢掉这一条，别让整个名单报废
                }
            }
            trim();
        }
    }

    /**
     * 退出即记录。跑在事件线程上，只做内存操作；落盘丢给异步线程。
     * 余额记的是退出瞬间的快照——列表展示够用，点开详情看的是实时值。
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        UserData data = plugin.users().getIfLoaded(uuid);
        Entry entry = new Entry(event.getPlayer().getName(), System.currentTimeMillis(),
                data == null ? 0D : data.getBalance());
        synchronized (lock) {
            entries.remove(uuid);   // 先移除再放回，把这个人挪到「最新」的末尾
            entries.put(uuid, entry);
            trim();
        }
        SchedulerCompat.runAsync(plugin, this::persist);
    }

    /** 调用方需持有 {@link #lock}。 */
    private void trim() {
        while (entries.size() > CAP) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    /** 名单快照，最新退出的在前；仍在线的（退出后又回来了）不算离线，排除。 */
    List<Map<String, Object>> snapshot(Set<UUID> online) {
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
                if (online.contains(mapEntry.getKey())) {
                    continue;
                }
                Entry entry = mapEntry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.name());
                item.put("uuid", mapEntry.getKey().toString());
                item.put("balance", entry.balance());
                item.put("lastSeen", entry.lastSeen());
                item.put("lastSeenText", entry.lastSeen() <= 0 ? "-" : TimeUtil.formatDate(entry.lastSeen()));
                result.add(item);
            }
        }
        Collections.reverse(result);   // 存储序是旧→新，展示要新→旧
        return result;
    }

    /**
     * 写回全局存储。会阻塞。
     *
     * <p>正常情况下每次退出都异步落一次盘；关服时最后一批退出排的异步任务
     * 可能来不及跑，所以模块 shutdown 时会再同步调一次兜底。</p>
     */
    void persist() {
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("uuid", mapEntry.getKey().toString());
                item.put("name", mapEntry.getValue().name());
                item.put("lastSeen", mapEntry.getValue().lastSeen());
                item.put("balance", mapEntry.getValue().balance());
                list.add(item);
            }
        }
        try {
            plugin.storage().saveGlobal(STORE_KEY, Map.of("players", list));
        } catch (Exception error) {
            plugin.getLogger().warning("[Panel] 保存最近离线名单失败: " + error.getMessage());
        }
    }
}

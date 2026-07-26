package com.github.cinnaio.essentialengine.core.user;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 玩家数据的缓存与读写调度。
 *
 * <p>在线玩家的数据常驻内存，命令读取零延迟；写入走异步，
 * 离线玩家按需异步加载。所有落盘操作都不在主线程。</p>
 */
public class UserManager {

    /** 离线数据在内存里保留多久没人访问就淘汰。 */
    private static final long OFFLINE_TTL_MILLIS = 5 * 60 * 1000L;
    /** 离线缓存的条数上限，防止批量给离线玩家发钱时把内存撑爆。 */
    private static final int OFFLINE_MAX = 500;

    private final EssentialEngine plugin;
    private final Map<UUID, UserData> cache = new ConcurrentHashMap<>();
    /**
     * 离线玩家数据的短期缓存。
     *
     * <p>它的作用不是提速，而是<b>保证同一个 UUID 始终对应同一个 {@link UserData} 实例</b>。
     * 没有它的话，每次读离线玩家都会从存储新建一个对象：两次连续的离线转账各自读到
     * 同一份旧余额，后写的那次直接覆盖前一次，钱就凭空少了。有了稳定实例，
     * {@code UserData} 上的锁才真正锁得住这个账户。</p>
     */
    private final Map<UUID, OfflineEntry> offline = new ConcurrentHashMap<>();
    private Object autoSaveHandle;

    /** 带访问时间的离线缓存条目。 */
    private static final class OfflineEntry {
        final UserData data;
        volatile long touched;

        OfflineEntry(UserData data) {
            this.data = data;
            this.touched = System.currentTimeMillis();
        }
    }

    public UserManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    private StorageProvider storage() {
        return plugin.storage();
    }

    public void startAutoSave() {
        int minutes = Math.max(1, plugin.getConfig().getInt("storage.auto-save-minutes", 5));
        long ticks = minutes * 60L * 20L;
        autoSaveHandle = SchedulerCompat.runTimerAsync(plugin, this::flushDirty, ticks, ticks);
    }

    public void stop() {
        SchedulerCompat.cancel(autoSaveHandle);
        autoSaveHandle = null;
        saveAllBlocking();
        cache.clear();
    }

    // ------------------------------------------------------------------ 读取

    public UserData getIfLoaded(UUID uuid) {
        return cache.get(uuid);
    }

    public Collection<UserData> getCached() {
        return cache.values();
    }

    /** 取在线玩家的数据。正常情况下登录时已预载，这里只是兜底。 */
    public UserData get(Player player) {
        UserData data = cache.get(player.getUniqueId());
        if (data != null) {
            data.setName(player.getName());
            return data;
        }
        plugin.getLogger().warning("玩家 " + player.getName() + " 的数据未预载，正在同步读取（可能造成瞬时卡顿）");
        return loadIntoCache(player.getUniqueId(), player.getName());
    }

    /**
     * 从存储读取并放进缓存。会阻塞，请在异步线程调用
     * （{@code AsyncPlayerPreLoginEvent} 本身就是异步的，可直接调用）。
     */
    public UserData loadIntoCache(UUID uuid, String name) {
        // 离线期间可能已经有改动挂在内存里还没落盘（比如刚给他转了账）。
        // 这时必须沿用同一个实例，否则重新读盘会把那笔改动直接抹掉。
        OfflineEntry pending = offline.remove(uuid);
        UserData data = pending != null ? pending.data : readFromStorage(uuid);
        if (data == null) {
            data = new UserData(uuid, name);
        } else if (name != null) {
            data.setName(name);
        }
        cache.put(uuid, data);
        return data;
    }

    /**
     * 读取离线玩家数据。会阻塞。
     *
     * <p>同一个 UUID 反复调用返回的是<b>同一个实例</b>（见 {@link #offline}），
     * 调用方因此可以安全地在它上面做原子的读-改-写。</p>
     */
    public UserData loadOffline(UUID uuid) {
        UserData cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        // computeIfAbsent 保证并发调用只会读一次盘、只会产生一个实例。
        // 里面带阻塞 IO 不理想，但这是低频路径，换来的是不会读出两个副本。
        OfflineEntry entry = offline.computeIfAbsent(uuid, key -> {
            UserData loaded = readFromStorage(key);
            return loaded == null ? null : new OfflineEntry(loaded);
        });
        if (entry == null) {
            return null;
        }
        entry.touched = System.currentTimeMillis();
        return entry.data;
    }

    private UserData readFromStorage(UUID uuid) {
        try {
            Map<String, Object> raw = storage().loadUser(uuid);
            return raw == null ? null : UserData.deserialize(uuid, raw);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "读取玩家数据失败: " + uuid, error);
            return null;
        }
    }

    /** 按名字解析 UUID：先在线玩家，再查存储索引。会阻塞。 */
    public UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (UserData data : cache.values()) {
            if (data.getName().equalsIgnoreCase(name)) {
                return data.getUuid();
            }
        }
        try {
            return storage().lookupUuid(name);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "按名字查询玩家失败: " + name, error);
            return null;
        }
    }

    /**
     * 按名字取玩家数据（在线直接取缓存，离线异步加载），随后回到主线程执行 action。
     * 玩家不存在时给发送者一条提示。
     */
    public void lookup(CommandSender feedback, String name, Consumer<UserData> action) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            action.accept(get(online));
            return;
        }
        SchedulerCompat.runAsync(plugin, () -> {
            UUID uuid = resolveUuid(name);
            UserData data = uuid == null ? null : loadOffline(uuid);
            SchedulerCompat.runGlobal(plugin, () -> {
                if (data == null) {
                    plugin.messages().send(feedback, "general.player-not-found", "player", name);
                    return;
                }
                try {
                    action.accept(data);
                } catch (Exception error) {
                    plugin.messages().send(feedback, "general.internal-error");
                    plugin.getLogger().log(Level.WARNING, "处理玩家 " + data.getName() + " 的数据时出错", error);
                }
                if (data.isDirty()) {
                    saveAsync(data);
                }
            });
        });
    }

    // ------------------------------------------------------------------ 写入

    public void saveAsync(UserData data) {
        if (data == null) {
            return;
        }
        SchedulerCompat.runAsync(plugin, () -> saveBlocking(data));
    }

    public void saveBlocking(UserData data) {
        if (data == null) {
            return;
        }
        try {
            storage().saveUser(data.getUuid(), data.getName(), data.getBalance(), data.serialize());
            data.clearDirty();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "保存玩家数据失败: " + data.getName(), error);
        }
    }

    /** 只写有改动的数据，自动保存任务用。 */
    public void flushDirty() {
        int count = 0;
        for (UserData data : cache.values()) {
            if (data.isDirty()) {
                saveBlocking(data);
                count++;
            }
        }
        count += sweepOffline();
        if (count > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("自动保存了 " + count + " 份玩家数据");
        }
    }

    /**
     * 落盘并淘汰离线缓存。
     *
     * <p>有改动的先写盘、这一轮不淘汰，等下一轮确认干净了再移除，
     * 所以不会出现「还没保存就被丢掉」。移除走 {@code computeIfPresent}，
     * 与 {@link #loadOffline} 的插入互斥，不会把别人刚放进去的实例挤掉。</p>
     *
     * @return 这一轮写盘的份数
     */
    private int sweepOffline() {
        long cutoff = System.currentTimeMillis() - OFFLINE_TTL_MILLIS;
        long[] stamps = offline.values().stream().mapToLong(entry -> entry.touched).sorted().toArray();
        int excess = stamps.length - OFFLINE_MAX;
        if (excess > 0) {
            // 超量时把淘汰线抬到「第 excess 老」那条，多出来的一起清掉
            cutoff = Math.max(cutoff, stamps[excess]);
        }
        long deadline = cutoff;

        int saved = 0;
        for (Map.Entry<UUID, OfflineEntry> entry : offline.entrySet()) {
            if (entry.getValue().data.isDirty()) {
                saveBlocking(entry.getValue().data);
                saved++;
                continue;
            }
            if (entry.getValue().touched < deadline) {
                offline.computeIfPresent(entry.getKey(), (uuid, current) ->
                        current.data.isDirty() || current.touched >= deadline ? current : null);
            }
        }
        return saved;
    }

    /** 关服时同步写盘。 */
    public void saveAllBlocking() {
        List<UserData> snapshot = new ArrayList<>(cache.values());
        for (UserData data : snapshot) {
            data.endSession();
            saveBlocking(data);
        }
        // 离线缓存里可能压着还没落盘的改动，不写就真丢了
        for (OfflineEntry entry : new ArrayList<>(offline.values())) {
            if (entry.data.isDirty()) {
                saveBlocking(entry.data);
            }
        }
        offline.clear();
    }

    /** 玩家退出：结算在线时长、写盘、移出缓存。 */
    public void unload(UUID uuid) {
        UserData data = cache.remove(uuid);
        if (data == null) {
            return;
        }
        data.endSession();
        data.setLastSeen(System.currentTimeMillis());
        // 刚退出的玩家仍可能马上被转账 / 发奖励。先留在离线缓存里保持实例不变，
        // 否则异步写盘还没落地就被重新读盘，这笔改动会被旧数据覆盖掉。
        offline.put(uuid, new OfflineEntry(data));
        saveAsync(data);
    }
}

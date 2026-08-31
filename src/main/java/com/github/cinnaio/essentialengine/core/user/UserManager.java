package com.github.cinnaio.essentialengine.core.user;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
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
    /**
     * 在线 / 离线两张表的唯一管理者，保证同一 UUID 至多一个活实例
     * ——{@link UserData} 上的锁只有在实例唯一时才锁得住账户。
     * 登录 / 退出 / 离线读取之间的互斥都在它内部，这里只负责接上存储和调度。
     */
    private final UserCache users = new UserCache();
    private Object autoSaveHandle;

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
        users.clearOnline();
    }

    // ------------------------------------------------------------------ 读取

    public UserData getIfLoaded(UUID uuid) {
        return users.getOnline(uuid);
    }

    public Collection<UserData> getCached() {
        return users.onlineValues();
    }

    /** 取在线玩家的数据。正常情况下登录时已预载，这里只是兜底。 */
    public UserData get(Player player) {
        UserData data = users.getOnline(player.getUniqueId());
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
        return users.promote(uuid, name, this::readFromStorage);
    }

    /**
     * 读取离线玩家数据。会阻塞。
     *
     * <p>同一个 UUID 反复调用返回的是<b>同一个实例</b>，调用方因此可以
     * 安全地在它上面做原子的读-改-写。拿到的引用用完即弃，不要长期持有——
     * 五分钟没人访问后实例会被淘汰，长期持有的旧引用上的改动会丢。</p>
     */
    public UserData loadOffline(UUID uuid) {
        return users.loadOffline(uuid, System.currentTimeMillis(), this::readFromStorage);
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
        for (UserData data : users.onlineValues()) {
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
        // 任何显式保存都顺手结算当前会话，避免经济 / 面板等保存路径把
        // 最近一段在线时间留到下一轮自动保存才落盘。
        data.checkpointSession();
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
        for (UserData data : users.onlineValues()) {
            // 结算本次会话的增量后再判断 dirty：这样总时长和每日活跃统计会
            // 随自动保存落盘，服务器异常退出时最多损失一个自动保存周期。
            data.checkpointSession();
            if (data.isDirty()) {
                saveBlocking(data);
                count++;
            }
        }
        // 有改动的离线条目先写盘、这一轮不淘汰，下一轮确认干净了再移除，
        // 不会出现「还没保存就被丢掉」
        long deadline = sweepDeadline();
        count += users.sweepOffline(deadline, data -> {
            saveBlocking(data);
            return true;
        });
        if (count > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("自动保存了 " + count + " 份玩家数据");
        }
    }

    /** 淘汰线：正常按 TTL，缓存超限时抬高到能把多出来的都清掉。 */
    private long sweepDeadline() {
        long cutoff = System.currentTimeMillis() - OFFLINE_TTL_MILLIS;
        long[] stamps = users.offlineTouchTimes();
        int excess = stamps.length - OFFLINE_MAX;
        if (excess > 0) {
            cutoff = Math.max(cutoff, stamps[excess]);
        }
        return cutoff;
    }

    /** 关服时同步写盘。 */
    public void saveAllBlocking() {
        for (UserData data : new ArrayList<>(users.onlineValues())) {
            data.endSession();
            saveBlocking(data);
        }
        // 离线缓存里可能压着还没落盘的改动，不写就真丢了
        for (UserData data : users.drainOffline()) {
            if (data.isDirty()) {
                saveBlocking(data);
            }
        }
    }

    /** 玩家退出：结算在线时长、写盘、转入离线缓存。 */
    public void unload(UUID uuid) {
        // 刚退出的玩家仍可能马上被转账 / 发奖励。转入离线缓存保持实例不变，
        // 否则异步写盘还没落地就被重新读盘，这笔改动会被旧数据覆盖掉。
        UserData data = users.demote(uuid, System.currentTimeMillis());
        if (data == null) {
            return;
        }
        data.endSession();
        data.setLastSeen(System.currentTimeMillis());
        saveAsync(data);
    }
}

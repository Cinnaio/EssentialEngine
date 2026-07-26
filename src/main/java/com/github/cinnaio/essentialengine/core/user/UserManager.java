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

    private final EssentialEngine plugin;
    private final Map<UUID, UserData> cache = new ConcurrentHashMap<>();
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
        UserData data = readFromStorage(uuid);
        if (data == null) {
            data = new UserData(uuid, name);
        } else if (name != null) {
            data.setName(name);
        }
        cache.put(uuid, data);
        return data;
    }

    /** 读取离线玩家数据（不进缓存）。会阻塞。 */
    public UserData loadOffline(UUID uuid) {
        UserData cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        return readFromStorage(uuid);
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
        if (count > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("自动保存了 " + count + " 份玩家数据");
        }
    }

    /** 关服时同步写盘。 */
    public void saveAllBlocking() {
        List<UserData> snapshot = new ArrayList<>(cache.values());
        for (UserData data : snapshot) {
            data.endSession();
            saveBlocking(data);
        }
    }

    /** 玩家退出：结算在线时长、写盘、移出缓存。 */
    public void unload(UUID uuid) {
        UserData data = cache.remove(uuid);
        if (data == null) {
            return;
        }
        data.endSession();
        data.setLastSeen(System.currentTimeMillis());
        saveAsync(data);
    }
}

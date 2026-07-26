package com.github.cinnaio.essentialengine.module.teleport;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.LocationUtil;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 传送逻辑中枢：吟唱倒计时、冷却、tpa 请求、地标与出生点。
 */
public class TeleportManager {

    public static final String BYPASS_WARMUP = "essentialengine.teleport.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "essentialengine.teleport.bypass.cooldown";
    private static final String HOMES_PREFIX = "essentialengine.homes.";
    private static final String WARPS_KEY = "warps";
    private static final String SPAWN_KEY = "spawn";

    /** 一次进行中的吟唱。 */
    public static class Warmup {
        final Location origin;
        final Location destination;
        Object handle;

        Warmup(Location origin, Location destination) {
            this.origin = origin;
            this.destination = destination;
        }

        public Location getOrigin() {
            return origin;
        }
    }

    /** 一条待处理的传送请求。 */
    public record TpaRequest(UUID sender, UUID target, boolean here, long expiry) {
        public boolean expired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    private final EssentialEngine plugin;
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, TpaRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> warps = new ConcurrentHashMap<>();
    private volatile Map<String, Object> spawn;

    public TeleportManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ 数据装载

    @SuppressWarnings("unchecked")
    public void load() {
        SchedulerCompat.runAsync(plugin, () -> {
            try {
                Map<String, Object> stored = plugin.storage().loadGlobal(WARPS_KEY);
                if (stored != null) {
                    warps.clear();
                    for (Map.Entry<String, Object> entry : stored.entrySet()) {
                        if (entry.getValue() instanceof Map<?, ?> value) {
                            warps.put(entry.getKey().toLowerCase(Locale.ROOT), (Map<String, Object>) value);
                        }
                    }
                }
                Map<String, Object> storedSpawn = plugin.storage().loadGlobal(SPAWN_KEY);
                if (storedSpawn != null && !storedSpawn.isEmpty()) {
                    spawn = storedSpawn;
                }
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "加载地标 / 出生点数据失败", error);
            }
        });
    }

    private void persistWarps() {
        Map<String, Object> snapshot = new LinkedHashMap<>(warps);
        SchedulerCompat.runAsync(plugin, () -> {
            try {
                plugin.storage().saveGlobal(WARPS_KEY, snapshot);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "保存地标失败", error);
            }
        });
    }

    private void persistSpawn() {
        Map<String, Object> snapshot = spawn == null ? new LinkedHashMap<>() : new LinkedHashMap<>(spawn);
        SchedulerCompat.runAsync(plugin, () -> {
            try {
                plugin.storage().saveGlobal(SPAWN_KEY, snapshot);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "保存出生点失败", error);
            }
        });
    }

    // ------------------------------------------------------------------ 地标

    public Set<String> warpNames() {
        return new TreeSet<>(warps.keySet());
    }

    public Location getWarp(String name) {
        Map<String, Object> raw = warps.get(name.toLowerCase(Locale.ROOT));
        return raw == null ? null : LocationUtil.deserialize(raw);
    }

    public boolean warpExists(String name) {
        return warps.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** 地标所在世界名（世界未加载时用于提示）。 */
    public String warpWorld(String name) {
        return LocationUtil.worldNameOf(warps.get(name.toLowerCase(Locale.ROOT)));
    }

    public void setWarp(String name, Location location) {
        Map<String, Object> serialized = LocationUtil.serialize(location);
        if (serialized == null) {
            throw new CommandError("general.internal-error");
        }
        warps.put(name.toLowerCase(Locale.ROOT), serialized);
        persistWarps();
    }

    public boolean deleteWarp(String name) {
        boolean removed = warps.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            persistWarps();
        }
        return removed;
    }

    // ------------------------------------------------------------------ 出生点

    public Location getSpawn() {
        if (spawn != null) {
            Location location = LocationUtil.deserialize(spawn);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    public void setSpawn(Location location) {
        this.spawn = LocationUtil.serialize(location);
        persistSpawn();
    }

    // ------------------------------------------------------------------ 家数量上限

    /** 该玩家可拥有的家数量：取权限 essentialengine.homes.<数字> 的最大值，没有就用配置默认值。 */
    public int maxHomes(Player player) {
        if (player.hasPermission(HOMES_PREFIX + "unlimited")) {
            return Integer.MAX_VALUE;
        }
        int best = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (node.startsWith(HOMES_PREFIX)) {
                try {
                    best = Math.max(best, Integer.parseInt(node.substring(HOMES_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (best >= 0) {
            return best;
        }
        return plugin.getConfig().getInt("modules.teleport.max-homes", 3);
    }

    // ------------------------------------------------------------------ 冷却

    public void checkCooldown(Player player, String type) {
        if (player.hasPermission(BYPASS_COOLDOWN)) {
            return;
        }
        Map<String, Long> map = cooldowns.get(player.getUniqueId());
        if (map == null) {
            return;
        }
        Long until = map.get(type);
        if (until != null && until > System.currentTimeMillis()) {
            throw new CommandError("teleport.cooldown",
                    "time", TimeUtil.duration(until - System.currentTimeMillis()));
        }
    }

    public void applyCooldown(Player player, String type) {
        int seconds = plugin.getConfig().getInt("modules.teleport.cooldown-seconds", 5);
        if (seconds <= 0 || player.hasPermission(BYPASS_COOLDOWN)) {
            return;
        }
        cooldowns.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>())
                .put(type, System.currentTimeMillis() + seconds * 1000L);
    }

    // ------------------------------------------------------------------ 传送

    /** 带吟唱的传送。目的地为 null 时给出错误提示。 */
    public void teleport(Player player, Location destination, String type) {
        if (destination == null || destination.getWorld() == null) {
            throw new CommandError("teleport.world-not-loaded");
        }
        checkCooldown(player, type);

        int warmup = plugin.getConfig().getInt("modules.teleport.warmup-seconds", 3);
        if (warmup <= 0 || player.hasPermission(BYPASS_WARMUP)) {
            performTeleport(player, destination);
            applyCooldown(player, type);
            return;
        }

        cancelWarmup(player.getUniqueId(), false);
        plugin.messages().send(player, "teleport.warmup", "seconds", String.valueOf(warmup));

        Warmup pending = new Warmup(player.getLocation().clone(), destination);
        warmups.put(player.getUniqueId(), pending);
        pending.handle = SchedulerCompat.runGlobalLater(plugin, () -> {
            Warmup current = warmups.get(player.getUniqueId());
            if (current != pending) {
                return;
            }
            warmups.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            performTeleport(player, destination);
            applyCooldown(player, type);
        }, warmup * 20L);
    }

    /** 立即传送，并记录 /back 用的回程点。 */
    public void performTeleport(Player player, Location destination) {
        if (destination == null || destination.getWorld() == null) {
            plugin.messages().send(player, "teleport.world-not-loaded");
            return;
        }
        UserData data = plugin.users().get(player);
        data.setLastLocation(player.getLocation());
        player.teleportAsync(destination);
        plugin.messages().send(player, "teleport.teleported");
    }

    /** 不记录回程点的传送（例如 /back 自己）。 */
    public void performTeleportRaw(Player player, Location destination) {
        if (destination == null || destination.getWorld() == null) {
            plugin.messages().send(player, "teleport.world-not-loaded");
            return;
        }
        player.teleportAsync(destination);
    }

    public boolean hasWarmup(UUID uuid) {
        return warmups.containsKey(uuid);
    }

    public Warmup getWarmup(UUID uuid) {
        return warmups.get(uuid);
    }

    /** 取消吟唱。notify 为 true 时给玩家发提示。 */
    public void cancelWarmup(UUID uuid, boolean notify) {
        Warmup warmup = warmups.remove(uuid);
        if (warmup == null) {
            return;
        }
        SchedulerCompat.cancel(warmup.handle);
        if (notify) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                plugin.messages().send(player, "teleport.warmup-cancelled");
            }
        }
    }

    // ------------------------------------------------------------------ tpa

    public void addRequest(Player sender, Player target, boolean here) {
        int expire = plugin.getConfig().getInt("modules.teleport.request-expire-seconds", 60);
        requests.put(target.getUniqueId(), new TpaRequest(sender.getUniqueId(), target.getUniqueId(),
                here, System.currentTimeMillis() + expire * 1000L));
    }

    public TpaRequest getRequest(UUID target) {
        TpaRequest request = requests.get(target);
        if (request == null) {
            return null;
        }
        if (request.expired()) {
            requests.remove(target);
            return null;
        }
        return request;
    }

    public TpaRequest consumeRequest(UUID target) {
        TpaRequest request = getRequest(target);
        if (request != null) {
            requests.remove(target);
        }
        return request;
    }

    /** 取消某人发出的所有请求，返回被取消的数量。 */
    public int cancelRequestsFrom(UUID sender) {
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, TpaRequest> entry : requests.entrySet()) {
            if (entry.getValue().sender().equals(sender)) {
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(requests::remove);
        return toRemove.size();
    }

    public void clearPlayer(UUID uuid) {
        cancelWarmup(uuid, false);
        requests.remove(uuid);
        cancelRequestsFrom(uuid);
        cooldowns.remove(uuid);
    }
}

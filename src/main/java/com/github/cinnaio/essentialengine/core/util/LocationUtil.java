package com.github.cinnaio.essentialengine.core.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 坐标序列化与安全落点计算。
 *
 * <p>序列化后的结构是纯 Map，YAML 与 JSON（SQLite / MySQL）共用同一套格式。</p>
 */
public final class LocationUtil {

    private LocationUtil() {
    }

    /** 坐标 -> Map。世界同时记录 UUID 与名称，改名后仍能按名称找回。 */
    public static Map<String, Object> serialize(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", location.getWorld().getName());
        map.put("world-uuid", location.getWorld().getUID().toString());
        map.put("x", location.getX());
        map.put("y", location.getY());
        map.put("z", location.getZ());
        map.put("yaw", (double) location.getYaw());
        map.put("pitch", (double) location.getPitch());
        return map;
    }

    /** Map -> 坐标。世界不存在时返回 null。 */
    @SuppressWarnings("unchecked")
    public static Location deserialize(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> map = toStringMap((Map<Object, Object>) raw);
        World world = null;
        Object worldUuid = map.get("world-uuid");
        if (worldUuid != null) {
            try {
                world = Bukkit.getWorld(UUID.fromString(String.valueOf(worldUuid)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (world == null && map.get("world") != null) {
            world = Bukkit.getWorld(String.valueOf(map.get("world")));
        }
        if (world == null) {
            return null;
        }
        return new Location(world,
                asDouble(map.get("x"), 0),
                asDouble(map.get("y"), 64),
                asDouble(map.get("z"), 0),
                (float) asDouble(map.get("yaw"), 0),
                (float) asDouble(map.get("pitch"), 0));
    }

    /** 反序列化时使用的世界名（世界没加载时用来给出提示）。 */
    @SuppressWarnings("unchecked")
    public static String worldNameOf(Object raw) {
        if (!(raw instanceof Map)) {
            return "未知";
        }
        Object name = ((Map<Object, Object>) raw).get("world");
        return name == null ? "未知" : String.valueOf(name);
    }

    private static Map<String, Object> toStringMap(Map<Object, Object> input) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<Object, Object> entry : input.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /** 人类可读的坐标描述。 */
    public static String describe(Location location) {
        if (location == null) {
            return "未知";
        }
        return String.format("%s %.1f, %.1f, %.1f",
                location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    /**
     * 从给定坐标往上找一个不会卡墙 / 不会掉虚空的落点。
     * 找不到就返回原坐标（调用方自行决定是否继续）。
     *
     * <p>注意：会读取方块，必须在对应区域线程上调用。</p>
     */
    public static Location findSafe(Location origin) {
        if (origin == null || origin.getWorld() == null) {
            return origin;
        }
        World world = origin.getWorld();
        if (isSafe(origin)) {
            return origin;
        }
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 2;
        for (int dy = 0; dy <= 8; dy++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int y = origin.getBlockY() + dy * sign;
                if (y < minY || y > maxY) {
                    continue;
                }
                Location candidate = new Location(world, baseX + 0.5, y, baseZ + 0.5,
                        origin.getYaw(), origin.getPitch());
                if (isSafe(candidate)) {
                    return candidate;
                }
            }
        }
        return origin;
    }

    /** 判断脚下有支撑、身体两格是空气。 */
    public static boolean isSafe(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        if (!passable(feet) || !passable(head)) {
            return false;
        }
        if (isDangerous(ground) || isDangerous(feet) || isDangerous(head)) {
            return false;
        }
        return ground.getType().isSolid();
    }

    private static boolean passable(Block block) {
        Material type = block.getType();
        return type.isAir() || !type.isSolid();
    }

    private static boolean isDangerous(Block block) {
        Material type = block.getType();
        return type == Material.LAVA || type == Material.FIRE || type == Material.CAMPFIRE
                || type == Material.SOUL_FIRE || type == Material.MAGMA_BLOCK || type == Material.CACTUS
                || type == Material.SWEET_BERRY_BUSH || type == Material.POWDER_SNOW;
    }

    /** 顶部安全落点（/top 使用）。 */
    public static Location highestAt(Location origin) {
        if (origin == null || origin.getWorld() == null) {
            return origin;
        }
        World world = origin.getWorld();
        int y = world.getHighestBlockYAt(origin.getBlockX(), origin.getBlockZ());
        return new Location(world, origin.getBlockX() + 0.5, y + 1.0, origin.getBlockZ() + 0.5,
                origin.getYaw(), origin.getPitch());
    }
}

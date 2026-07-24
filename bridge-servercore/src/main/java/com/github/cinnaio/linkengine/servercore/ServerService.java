package com.github.cinnaio.linkengine.servercore;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service layer wrapping Bukkit API for server information and operations.
 */
public class ServerService {

    private final Plugin plugin;

    public ServerService(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get server status information.
     */
    public Map<String, Object> getServerStatus() {
        Server server = Bukkit.getServer();
        Map<String, Object> status = new HashMap<>();

        status.put("version", server.getVersion());
        status.put("bukkitVersion", server.getBukkitVersion());
        status.put("minecraftVersion", server.getMinecraftVersion());
        status.put("name", server.getName());
        status.put("onlinePlayers", server.getOnlinePlayers().size());
        status.put("maxPlayers", server.getMaxPlayers());
        status.put("motd", server.getMotd());
        status.put("isFolia", isFolia());

        // Online player names
        List<String> playerNames = new ArrayList<>();
        for (Player p : server.getOnlinePlayers()) {
            playerNames.add(p.getName());
        }
        status.put("playerList", playerNames);

        // TPS (Paper API)
        try {
            double[] tps = server.getTPS();
            Map<String, Double> tpsMap = new HashMap<>();
            tpsMap.put("1min", tps[0]);
            tpsMap.put("5min", tps[1]);
            tpsMap.put("15min", tps[2]);
            status.put("tps", tpsMap);
        } catch (Exception e) {
            status.put("tps", "unavailable");
        }

        // Memory info
        Runtime runtime = Runtime.getRuntime();
        Map<String, Long> memory = new HashMap<>();
        memory.put("maxMB", runtime.maxMemory() / 1024 / 1024);
        memory.put("totalMB", runtime.totalMemory() / 1024 / 1024);
        memory.put("freeMB", runtime.freeMemory() / 1024 / 1024);
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        status.put("memory", memory);

        // Uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        status.put("uptimeMs", uptimeMs);
        status.put("uptimeFormatted", formatUptime(uptimeMs));

        return status;
    }

    /**
     * Get list of online players.
     */
    public List<Map<String, Object>> getOnlinePlayers() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", player.getName());
            info.put("uuid", player.getUniqueId().toString());
            info.put("health", player.getHealth());
            info.put("maxHealth", player.getMaxHealth());
            info.put("level", player.getLevel());
            info.put("gameMode", player.getGameMode().name());
            info.put("world", player.getWorld().getName());
            info.put("isOp", player.isOp());
            info.put("ping", player.getPing());
            players.add(info);
        }
        return players;
    }

    /**
     * Get detailed info for a specific player by name.
     */
    public Map<String, Object> getPlayerInfo(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            return null;
        }

        Map<String, Object> info = new HashMap<>();
        info.put("name", player.getName());
        info.put("uuid", player.getUniqueId().toString());
        info.put("displayName", player.getDisplayName());
        info.put("health", player.getHealth());
        info.put("maxHealth", player.getMaxHealth());
        info.put("level", player.getLevel());
        info.put("exp", player.getExp());
        info.put("gameMode", player.getGameMode().name());
        info.put("world", player.getWorld().getName());
        info.put("location", Map.of(
                "x", player.getLocation().getX(),
                "y", player.getLocation().getY(),
                "z", player.getLocation().getZ(),
                "yaw", player.getLocation().getYaw(),
                "pitch", player.getLocation().getPitch()
        ));
        info.put("isOp", player.isOp());
        info.put("ping", player.getPing());
        info.put("isFlying", player.isFlying());
        info.put("foodLevel", player.getFoodLevel());
        return info;
    }

    /**
     * Execute a server command as console.
     * Returns the command result message.
     */
    public Map<String, Object> executeCommand(String command) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            result.put("command", command);
            result.put("success", success);
            result.put("message", success ? "Command executed" : "Command failed or not found");
        } catch (Exception e) {
            result.put("command", command);
            result.put("success", false);
            result.put("message", "Error: " + e.getMessage());
        }
        return result;
    }

    /**
     * Get list of installed plugins.
     */
    public List<Map<String, Object>> getPlugins() {
        List<Map<String, Object>> plugins = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", p.getName());
            info.put("version", p.getDescription().getVersion());
            info.put("enabled", p.isEnabled());
            info.put("description", p.getDescription().getDescription());
            info.put("authors", p.getDescription().getAuthors());
            plugins.add(info);
        }
        return plugins;
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%dd %dh %dm %ds", days, hours, minutes, secs);
    }
}

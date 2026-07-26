package com.github.cinnaio.essentialengine.module.webapi.endpoint;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务器状态相关接口。
 *
 * <p>所有 Bukkit 调用都通过 {@link MainThread} 回到主线程执行，
 * 保证在 Folia 上也是线程安全的。</p>
 */
public class ServerEndpoint {

    private static final String MODULE = "server";

    private final EssentialEngine plugin;

    public ServerEndpoint(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void register(Router router) {
        router.get("/api/server/status", (session, params) ->
                ApiResponse.ok(MODULE, MainThread.call(plugin, this::status, new HashMap<>())));

        router.get("/api/server/players", (session, params) ->
                ApiResponse.ok(MODULE, MainThread.call(plugin, this::onlinePlayers, new ArrayList<>())));

        router.get("/api/server/players/{name}", (session, params) -> {
            String name = params.get("name");
            Map<String, Object> info = MainThread.call(plugin, () -> playerInfo(name), null);
            return info == null
                    ? ApiResponse.error(MODULE, "玩家不存在或不在线: " + name)
                    : ApiResponse.ok(MODULE, info);
        });

        router.get("/api/server/plugins", (session, params) ->
                ApiResponse.ok(MODULE, MainThread.call(plugin, this::plugins, new ArrayList<>())));

        router.post("/api/server/command", (session, params) -> {
            JsonObject json = Router.readJson(session);
            if (!json.has("command")) {
                return ApiResponse.error(MODULE, "请求体缺少 command 字段");
            }
            String command = json.get("command").getAsString();
            if (!isAllowed(command)) {
                return ApiResponse.error(MODULE, "该命令不在白名单中: " + command);
            }
            Map<String, Object> result = MainThread.call(plugin, () -> {
                Map<String, Object> data = new LinkedHashMap<>();
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                data.put("command", command);
                data.put("success", success);
                data.put("message", success ? "命令已执行" : "命令执行失败或不存在");
                return data;
            }, null);
            if (result == null) {
                return ApiResponse.error(MODULE, "命令执行超时");
            }
            return Boolean.TRUE.equals(result.get("success"))
                    ? ApiResponse.ok(MODULE, result, "命令已执行")
                    : ApiResponse.error(MODULE, String.valueOf(result.get("message")));
        });
    }

    private boolean isAllowed(String command) {
        List<String> allowed = plugin.getConfig().getStringList("modules.webapi.allowed-commands");
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        String base = command.split(" ")[0].toLowerCase(Locale.ROOT).replace("/", "");
        return allowed.stream().anyMatch(entry -> entry.equalsIgnoreCase(base));
    }

    private Map<String, Object> status() {
        Server server = Bukkit.getServer();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("version", server.getVersion());
        status.put("bukkitVersion", server.getBukkitVersion());
        status.put("minecraftVersion", server.getMinecraftVersion());
        status.put("name", server.getName());
        status.put("motd", server.getMotd());
        status.put("onlinePlayers", server.getOnlinePlayers().size());
        status.put("maxPlayers", server.getMaxPlayers());
        status.put("isFolia", SchedulerCompat.isFolia());

        List<String> names = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            names.add(player.getName());
        }
        status.put("playerList", names);

        try {
            double[] tps = server.getTPS();
            Map<String, Double> tpsMap = new LinkedHashMap<>();
            tpsMap.put("1min", tps[0]);
            tpsMap.put("5min", tps[1]);
            tpsMap.put("15min", tps[2]);
            status.put("tps", tpsMap);
        } catch (Throwable ignored) {
            status.put("tps", "unavailable");
        }

        Runtime runtime = Runtime.getRuntime();
        Map<String, Long> memory = new LinkedHashMap<>();
        memory.put("maxMB", runtime.maxMemory() / 1024 / 1024);
        memory.put("totalMB", runtime.totalMemory() / 1024 / 1024);
        memory.put("freeMB", runtime.freeMemory() / 1024 / 1024);
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        status.put("memory", memory);

        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        status.put("uptimeMs", uptime);
        // 跟随 config 的 language，而不是 TimeUtil 里写死的中文
        status.put("uptimeFormatted", plugin.messages().resolve(null,
                com.github.cinnaio.essentialengine.core.util.TimeUtil.duration(uptime)));
        status.put("activeModules", plugin.modules().getActiveIds());
        return status;
    }

    private List<Map<String, Object>> onlinePlayers() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(basicInfo(player));
        }
        return players;
    }

    private Map<String, Object> playerInfo(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            return null;
        }
        Map<String, Object> info = basicInfo(player);
        info.put("displayName", com.github.cinnaio.essentialengine.core.util.PlayerUtil.display(player));
        info.put("exp", player.getExp());
        info.put("foodLevel", player.getFoodLevel());
        info.put("isFlying", player.isFlying());
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("world", player.getWorld().getName());
        location.put("x", player.getLocation().getX());
        location.put("y", player.getLocation().getY());
        location.put("z", player.getLocation().getZ());
        location.put("yaw", player.getLocation().getYaw());
        location.put("pitch", player.getLocation().getPitch());
        info.put("location", location);
        return info;
    }

    private Map<String, Object> basicInfo(Player player) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", player.getName());
        info.put("uuid", player.getUniqueId().toString());
        info.put("health", player.getHealth());
        info.put("maxHealth", player.getMaxHealth());
        info.put("level", player.getLevel());
        info.put("gameMode", player.getGameMode().name());
        info.put("world", player.getWorld().getName());
        info.put("isOp", player.isOp());
        info.put("ping", player.getPing());
        return info;
    }

    private List<Map<String, Object>> plugins() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Plugin item : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", item.getName());
            info.put("version", item.getDescription().getVersion());
            info.put("enabled", item.isEnabled());
            info.put("description", item.getDescription().getDescription());
            info.put("authors", item.getDescription().getAuthors());
            result.add(info);
        }
        return result;
    }
}

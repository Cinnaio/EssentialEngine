package com.github.cinnaio.essentialengine.module.webapi.endpoint;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.LocationUtil;
import com.github.cinnaio.essentialengine.module.teleport.TeleportManager;
import com.github.cinnaio.essentialengine.module.teleport.TeleportModule;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;

/**
 * EssentialEngine 自身数据的接口：玩家档案、家、地标、经济、广播。
 *
 * <p>这些接口读的是插件存储（YAML / SQLite / MySQL），因此离线玩家也能查询。</p>
 */
public class EssentialsEndpoint {

    private static final String MODULE = "essentials";

    private final EssentialEngine plugin;

    public EssentialsEndpoint(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void register(Router router) {
        router.get("/api/essentials/players/{name}", (session, params) -> {
            UserData data = resolve(params.get("name"));
            return data == null
                    ? ApiResponse.error(MODULE, "找不到玩家: " + params.get("name"))
                    : ApiResponse.ok(MODULE, profile(data));
        });

        router.get("/api/essentials/homes/{name}", (session, params) -> {
            UserData data = resolve(params.get("name"));
            if (data == null) {
                return ApiResponse.error(MODULE, "找不到玩家: " + params.get("name"));
            }
            List<Map<String, Object>> homes = new ArrayList<>();
            for (String home : data.getHomeNames()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", home);
                Location location = data.getHome(home);
                entry.put("location", location == null ? null : LocationUtil.describe(location));
                homes.add(entry);
            }
            return ApiResponse.ok(MODULE, homes);
        });

        router.get("/api/essentials/warps", (session, params) -> {
            TeleportManager teleport = teleportManager();
            if (teleport == null) {
                return ApiResponse.error(MODULE, "传送模块未启用");
            }
            List<Map<String, Object>> warps = new ArrayList<>();
            for (String name : teleport.warpNames()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("world", teleport.warpWorld(name));
                Location location = teleport.getWarp(name);
                entry.put("location", location == null ? null : LocationUtil.describe(location));
                warps.add(entry);
            }
            return ApiResponse.ok(MODULE, warps);
        });

        router.get("/api/essentials/economy/top", (session, params) -> {
            int limit = 10;
            String raw = session.getParms().get("limit");
            if (raw != null) {
                try {
                    limit = Math.max(1, Math.min(100, Integer.parseInt(raw)));
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                return ApiResponse.ok(MODULE, plugin.storage().topBalances(limit));
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "读取排行榜失败: " + error.getMessage());
            }
        });

        router.post("/api/essentials/economy/{name}", (session, params) -> {
            UserData data = resolve(params.get("name"));
            if (data == null) {
                return ApiResponse.error(MODULE, "找不到玩家: " + params.get("name"));
            }
            JsonObject json = Router.readJson(session);
            if (!json.has("action") || !json.has("amount")) {
                return ApiResponse.error(MODULE, "请求体需要 action 与 amount 字段");
            }
            double amount = json.get("amount").getAsDouble();
            String action = json.get("action").getAsString().toLowerCase(Locale.ROOT);
            DoubleUnaryOperator change = switch (action) {
                case "give", "add" -> current -> current + amount;
                case "take", "remove" -> current -> Math.max(0, current - amount);
                case "set" -> current -> amount;
                default -> null;
            };
            if (change == null) {
                return ApiResponse.error(MODULE, "action 只能是 give / take / set");
            }
            // 这个处理器跑在 HTTP 工作线程上，和游戏内的扣款是真并发，
            // 必须走 EconomyManager 的原子操作，不能自己读一次再写回去
            UserData.BalanceChange result = plugin.economy()
                    .apply(data, change, "webapi " + action);
            plugin.users().saveBlocking(data);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("player", data.getName());
            body.put("balance", result.after());
            return ApiResponse.ok(MODULE, body, "余额已更新");
        });

        router.post("/api/essentials/broadcast", (session, params) -> {
            JsonObject json = Router.readJson(session);
            if (!json.has("message")) {
                return ApiResponse.error(MODULE, "请求体缺少 message 字段");
            }
            String message = json.get("message").getAsString();
            Boolean done = MainThread.call(plugin, () -> {
                // 广播前缀跟随各接收者的语言（chat.format-broadcast）
                for (Player player : Bukkit.getOnlinePlayers()) {
                    plugin.messages().send(player, "chat.format-broadcast", "message", message);
                }
                return true;
            }, false);
            return Boolean.TRUE.equals(done)
                    ? ApiResponse.ok(MODULE, Map.of("message", message), "已广播")
                    : ApiResponse.error(MODULE, "广播失败");
        });

        router.post("/api/essentials/message", (session, params) -> {
            JsonObject json = Router.readJson(session);
            if (!json.has("player") || !json.has("message")) {
                return ApiResponse.error(MODULE, "请求体需要 player 与 message 字段");
            }
            String name = json.get("player").getAsString();
            String message = json.get("message").getAsString();
            Boolean sent = MainThread.call(plugin, () -> {
                Player target = Bukkit.getPlayerExact(name);
                if (target == null) {
                    return false;
                }
                plugin.messages().sendRaw(target, message);
                return true;
            }, false);
            return Boolean.TRUE.equals(sent)
                    ? ApiResponse.ok(MODULE, Map.of("player", name), "消息已发送")
                    : ApiResponse.error(MODULE, "玩家不在线: " + name);
        });
    }

    private TeleportManager teleportManager() {
        if (plugin.modules().get("teleport") instanceof TeleportModule module && module.isEnabled()) {
            return module.getManager();
        }
        return null;
    }

    /** 名字或 UUID 都能解析；离线玩家会从存储读取。 */
    private UserData resolve(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return null;
        }
        UUID uuid = null;
        try {
            uuid = UUID.fromString(identifier);
        } catch (IllegalArgumentException ignored) {
            uuid = plugin.users().resolveUuid(identifier);
        }
        return uuid == null ? null : plugin.users().loadOffline(uuid);
    }

    private Map<String, Object> profile(UserData data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", data.getUuid().toString());
        map.put("name", data.getName());
        map.put("nickname", data.getNickname());
        map.put("balance", data.getBalance());
        map.put("firstJoin", data.getFirstJoin());
        map.put("lastSeen", data.getLastSeen());
        map.put("playtimeMs", data.getTotalPlaytime());
        map.put("homes", new ArrayList<>(data.getHomeNames()));
        map.put("online", Bukkit.getPlayer(data.getUuid()) != null);
        map.put("afk", data.isAfk());
        map.put("vanished", data.isVanished());
        map.put("banned", data.isBanned());
        map.put("banReason", data.getBanReason());
        map.put("muted", data.isMuted());
        map.put("muteReason", data.getMuteReason());
        return map;
    }
}

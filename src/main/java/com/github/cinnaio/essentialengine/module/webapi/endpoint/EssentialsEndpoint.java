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
import fi.iki.elonen.NanoHTTPD;
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
    private static final String ECONOMY_REQUESTS_KEY = "essentialengine-economy-requests";
    private static final int MAX_ECONOMY_REQUESTS = 10_000;

    private final EssentialEngine plugin;
    private final Object economyRequestLock = new Object();

    public EssentialsEndpoint(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void register(Router router) {
        router.post("/api/essentials/economy/{name}/withdraw",
                (session, params) -> economyMutation(session, params, false));

        router.post("/api/essentials/economy/{name}/deposit",
                (session, params) -> economyMutation(session, params, true));

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

    /**
     * 给外部活动系统使用的原子经济变更接口。
     *
     * <p>旧的通用 take 接口是管理用途：它会把余额钳制到 0，不能表达「余额不足」。
     * 活动报名必须使用这里的 try-withdraw 语义，并通过 requestId 处理 HTTP 重试。</p>
     */
    private ApiResponse economyMutation(NanoHTTPD.IHTTPSession session,
                                        Map<String, String> params,
                                        boolean deposit) {
        UserData data = resolve(params.get("name"));
        if (data == null) {
            return ApiResponse.error(MODULE, "找不到玩家: " + params.get("name"));
        }

        JsonObject json = Router.readJson(session);
        if (!json.has("amount") || !json.has("requestId")) {
            return ApiResponse.error(MODULE, "请求体需要 amount 与 requestId 字段");
        }

        double amount;
        try {
            amount = UserData.roundMoney(json.get("amount").getAsDouble());
        } catch (RuntimeException error) {
            return ApiResponse.error(MODULE, "amount 必须是有效数字");
        }
        String requestId;
        String detail;
        try {
            requestId = json.get("requestId").getAsString().trim();
            detail = json.has("detail") ? json.get("detail").getAsString().trim() : "webapi economy";
        } catch (RuntimeException error) {
            return ApiResponse.error(MODULE, "requestId 与 detail 必须是字符串");
        }
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000_000D) {
            return ApiResponse.error(MODULE, "amount 必须大于 0 且不超过 1000000000000");
        }
        if (requestId.length() < 8 || requestId.length() > 80) {
            return ApiResponse.error(MODULE, "requestId 长度必须在 8 到 80 个字符之间");
        }
        if (detail.length() > 160) {
            detail = detail.substring(0, 160);
        }

        synchronized (economyRequestLock) {
            try {
                Map<String, Object> previous = loadEconomyRequest(requestId);
                if (previous != null) {
                    if (!sameEconomyRequest(previous, data.getName(), amount, deposit)) {
                        return ApiResponse.error(MODULE, "requestId 已被另一笔经济操作使用");
                    }
                    return ApiResponse.ok(MODULE, previous, "重复请求，返回已处理结果");
                }

                UserData.BalanceChange change;
                if (deposit) {
                    change = plugin.economy().apply(data, current -> current + amount,
                            detail.isEmpty() ? "webapi deposit" : detail);
                } else {
                    change = plugin.economy().apply(data,
                            current -> current >= amount ? current - amount : current,
                            detail.isEmpty() ? "webapi withdraw" : detail);
                    if (change.delta() == 0D) {
                        return ApiResponse.error(MODULE, "余额不足");
                    }
                }

                plugin.users().saveBlocking(data);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("player", data.getName());
                result.put("operation", deposit ? "deposit" : "withdraw");
                result.put("amount", amount);
                result.put("balance", change.after());
                result.put("requestId", requestId);
                saveEconomyRequest(requestId, result);
                return ApiResponse.ok(MODULE, result, deposit ? "余额已增加" : "余额已扣除");
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "经济操作失败: " + error.getMessage());
            }
        }
    }

    private Map<String, Object> loadEconomyRequest(String requestId) throws Exception {
        Map<String, Object> all = plugin.storage().loadGlobal(ECONOMY_REQUESTS_KEY);
        if (all == null || !(all.get(requestId) instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private void saveEconomyRequest(String requestId, Map<String, Object> result) throws Exception {
        Map<String, Object> all = plugin.storage().loadGlobal(ECONOMY_REQUESTS_KEY);
        if (all == null) {
            all = new LinkedHashMap<>();
        }
        all.put(requestId, result);
        while (all.size() > MAX_ECONOMY_REQUESTS) {
            all.remove(all.keySet().iterator().next());
        }
        plugin.storage().saveGlobal(ECONOMY_REQUESTS_KEY, all);
    }

    private boolean sameEconomyRequest(Map<String, Object> previous, String player,
                                       double amount, boolean deposit) {
        String operation = String.valueOf(previous.getOrDefault("operation", ""));
        String recordedPlayer = String.valueOf(previous.getOrDefault("player", ""));
        Object rawAmount = previous.get("amount");
        if (!(rawAmount instanceof Number) && !(rawAmount instanceof String)) {
            return false;
        }
        double recordedAmount;
        try {
            recordedAmount = Double.parseDouble(String.valueOf(rawAmount));
        } catch (NumberFormatException error) {
            return false;
        }
        return recordedPlayer.equalsIgnoreCase(player)
                && operation.equals(deposit ? "deposit" : "withdraw")
                && Double.compare(recordedAmount, amount) == 0;
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
        Location lastLocation = data.getLogoutLocation();
        if (lastLocation == null) {
            // 兼容尚未记录退出坐标的旧档案，尽量提供已有的上一位置。
            lastLocation = data.getLastLocation();
        }
        map.put("lastLocation", lastLocation == null ? null : LocationUtil.describe(lastLocation));
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

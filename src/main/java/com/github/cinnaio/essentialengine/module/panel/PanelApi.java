package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 面板的后端接口。
 *
 * <p>所有 Bukkit 调用都通过 {@link MainThread} 回到主线程，Folia 上同样安全。</p>
 */
public class PanelApi {

    private static final String MODULE = "panel";
    /** Minecraft 用户名字符集。用它做白名单，玩家名就不可能夹带额外的命令参数。 */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final EssentialEngine plugin;
    private final SessionStore sessions;
    private final ConfigService config;

    public PanelApi(EssentialEngine plugin, SessionStore sessions, ConfigService config) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.config = config;
    }

    public void register(Router router) {
        // ---- 公开 ----
        router.get("/api/ping", (session, params) -> ApiResponse.ok(MODULE, Map.of("ok", true)));

        router.post("/api/login", (session, params) -> {
            String ip = PanelServer.clientIp(session);
            if (sessions.isLocked(ip)) {
                return ApiResponse.error(MODULE,
                        "尝试次数过多，请 " + sessions.lockRemainingSeconds(ip) + " 秒后再试");
            }
            JsonObject body = Router.readJson(session);
            String password = body.has("password") ? body.get("password").getAsString() : "";
            String token = sessions.login(password, ip);
            if (token == null) {
                return ApiResponse.error(MODULE, "密码错误");
            }
            return ApiResponse.ok(MODULE, Map.of("token", token), "登录成功");
        });

        // ---- 需要登录 ----
        router.post("/api/logout", (session, params) -> {
            sessions.logout(PanelServer.bearer(session));
            return ApiResponse.ok(MODULE, Map.of("ok", true), "已退出登录");
        });

        router.get("/api/overview", (session, params) ->
                ApiResponse.ok(MODULE, MainThread.call(plugin, this::overview, Map.of())));

        router.get("/api/players", (session, params) ->
                ApiResponse.ok(MODULE, MainThread.call(plugin, this::players, List.of())));

        router.post("/api/players/{name}/action", (session, params) ->
                playerAction(params.get("name"), Router.readJson(session)));

        router.get("/api/config", (session, params) -> ApiResponse.ok(MODULE, config.read()));

        router.post("/api/config", (session, params) -> {
            JsonObject body = Router.readJson(session);
            if (!body.has("updates") || !body.get("updates").isJsonObject()) {
                return ApiResponse.error(MODULE, "请求体缺少 updates 对象");
            }
            Map<String, Object> updates = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> field : body.getAsJsonObject("updates").entrySet()) {
                updates.put(field.getKey(), unwrap(field.getValue()));
            }
            try {
                ConfigService.SaveResult result = config.save(updates);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("changed", result.changed());
                data.put("rejected", result.rejected());
                return ApiResponse.ok(MODULE, data,
                        result.changed() == 0 ? "没有需要保存的改动" : "已保存 " + result.changed() + " 项，重载后生效");
            } catch (Exception error) {
                return ApiResponse.error(MODULE, error.getMessage());
            }
        });

        router.post("/api/reload", (session, params) -> {
            // 重载会停掉面板自己的 HTTP 服务，必须等这次响应发完再执行；
            // 留 2 秒也是给监听端口留出释放时间，免得重新绑定时撞上「端口被占用」
            SchedulerCompat.runGlobalLater(plugin, () -> {
                try {
                    plugin.reloadAll();
                } catch (Throwable error) {
                    plugin.getLogger().severe("[Panel] 重载失败: " + error);
                }
            }, 40L);
            return ApiResponse.ok(MODULE, Map.of("ok", true), "正在重载，面板将在几秒后恢复");
        });

    }

    // ------------------------------------------------------------------ 概览

    private Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pluginVersion", plugin.getDescription().getVersion());
        data.put("serverVersion", Bukkit.getVersion());
        data.put("minecraftVersion", Bukkit.getMinecraftVersion());
        data.put("folia", SchedulerCompat.isFolia());
        data.put("motd", Bukkit.getMotd());
        data.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        data.put("maxPlayers", Bukkit.getMaxPlayers());
        data.put("storage", plugin.storage() == null ? "-" : plugin.storage().getName());
        data.put("vaultHooked", plugin.isVaultRegistered());
        data.put("sessions", sessions.activeSessions());

        try {
            double[] tps = Bukkit.getTPS();
            data.put("tps", Math.min(20D, tps[0]));
        } catch (Throwable ignored) {
            data.put("tps", -1);
        }

        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMB", used);
        memory.put("maxMB", runtime.maxMemory() / 1024 / 1024);
        data.put("memory", memory);

        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        data.put("uptimeMs", uptime);
        data.put("uptime", TimeUtil.formatDuration(uptime));

        List<Map<String, Object>> modules = new ArrayList<>();
        for (EngineModule module : plugin.modules().getAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", module.getId());
            item.put("name", module.getDisplayName());
            item.put("enabled", module.isEnabled());
            item.put("available", module.isAvailable());
            modules.add(item);
        }
        data.put("modules", modules);
        return data;
    }

    // ------------------------------------------------------------------ 玩家

    private List<Map<String, Object>> players() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", player.getName());
            item.put("uuid", player.getUniqueId().toString());
            item.put("ping", player.getPing());
            item.put("gamemode", player.getGameMode().name());
            item.put("world", player.getWorld().getName());
            item.put("health", Math.round(player.getHealth()));
            item.put("op", player.isOp());

            UserData data = plugin.users().getIfLoaded(player.getUniqueId());
            if (data != null) {
                item.put("balance", data.getBalance());
                item.put("afk", data.isAfk());
                item.put("vanished", data.isVanished());
                item.put("muted", data.isMuted());
                item.put("banned", data.isBanned());
                item.put("playtime", TimeUtil.formatDuration(data.getTotalPlaytime()));
                item.put("nickname", data.getNickname());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 对某个玩家执行管理操作。
     *
     * <p>这些动作全部转成插件自己的命令交给控制台执行，而不是在这里重新实现一遍
     * 封禁 / 禁言的逻辑——广播、踢下线、权限豁免等副作用才不会和命令版本走偏。
     * 玩家名先用字符集白名单卡死，理由里的换行也会被清掉，
     * 因此拼出来的命令行不可能被塞进额外的参数。</p>
     */
    private ApiResponse playerAction(String name, JsonObject body) {
        if (name == null || !USERNAME.matcher(name).matches()) {
            return ApiResponse.error(MODULE, "玩家名不合法");
        }
        String action = body.has("action") ? body.get("action").getAsString() : "";
        String reason = sanitizeLine(body.has("reason") ? body.get("reason").getAsString() : "");
        String duration = sanitizeLine(body.has("duration") ? body.get("duration").getAsString() : "");
        String amount = sanitizeLine(body.has("amount") ? body.get("amount").getAsString() : "");

        String command = switch (action) {
            case "kick" -> "kick " + name + (reason.isEmpty() ? "" : " " + reason);
            case "ban" -> "ban " + name + (reason.isEmpty() ? "" : " " + reason);
            case "unban" -> "unban " + name;
            case "mute" -> "mute " + name + (reason.isEmpty() ? "" : " " + reason);
            case "unmute" -> "unmute " + name;
            case "tempban" -> duration.isEmpty() ? null
                    : "tempban " + name + " " + duration + (reason.isEmpty() ? "" : " " + reason);
            case "tempmute" -> duration.isEmpty() ? null
                    : "tempmute " + name + " " + duration + (reason.isEmpty() ? "" : " " + reason);
            case "setbalance" -> amount.isEmpty() ? null : "eco set " + name + " " + amount;
            default -> null;
        };
        if (command == null) {
            return ApiResponse.error(MODULE, "不支持的操作或缺少参数: " + action);
        }

        Boolean ok = MainThread.call(plugin,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command), null);
        if (ok == null) {
            return ApiResponse.error(MODULE, "操作超时");
        }
        return ok
                ? ApiResponse.ok(MODULE, Map.of("command", command), "操作已执行")
                : ApiResponse.error(MODULE, "操作失败，请查看控制台日志");
    }

    // ------------------------------------------------------------------ 工具

    /** 去掉换行与控制字符，避免拼出多行命令或污染日志。 */
    private static String sanitizeLine(String input) {
        if (input == null) {
            return "";
        }
        String cleaned = input.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    /** 把 Gson 的值转成 Java 基本类型，交给 ConfigService 按原类型收敛。 */
    private static Object unwrap(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            List<String> list = new ArrayList<>();
            element.getAsJsonArray().forEach(item -> list.add(item.getAsString()));
            return list;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return element.getAsString();
    }
}

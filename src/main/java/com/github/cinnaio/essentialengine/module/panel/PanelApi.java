package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.EconomySummary;
import com.github.cinnaio.essentialengine.core.storage.SourceVolume;
import com.github.cinnaio.essentialengine.core.storage.TransactionRecord;
import com.github.cinnaio.essentialengine.core.storage.UserSummary;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import com.github.cinnaio.essentialengine.module.economy.EconomyModule;
import com.github.cinnaio.essentialengine.module.monitor.MonitorModule;
import com.github.cinnaio.essentialengine.module.monitor.MonitorService;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    /** 未启用 OAuth 登录时为 null。 */
    private final OidcClient oidc;
    /** 头像来源模板（{name} 占位），由 {@link PanelModule} 解析配置后传入。 */
    private final List<String> avatarSources;
    private final RecentPlayers recentPlayers;

    public PanelApi(EssentialEngine plugin, SessionStore sessions, ConfigService config,
                    OidcClient oidc, List<String> avatarSources, RecentPlayers recentPlayers) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.config = config;
        this.oidc = oidc;
        this.avatarSources = avatarSources;
        this.recentPlayers = recentPlayers;
    }

    public void register(Router router) {
        // ---- 公开 ----
        // 登录页据此决定显示密码框、OAuth 按钮，还是两个都显示。
        // avatars 也从这里带下去：只是几个公开图床的地址模板，不涉密。
        router.get("/api/ping", (session, params) -> ApiResponse.ok(MODULE, Map.of(
                "ok", true,
                "password", sessions.hasPassword(),
                "oauth", oidc != null,
                "oauthLabel", plugin.getConfig().getString(
                        "modules.panel.oauth.button-text", "使用 OAuth 登录"),
                "avatars", avatarSources,
                "monitorRefreshSeconds", Math.max(0, plugin.getConfig().getInt(
                        "modules.panel.monitor-refresh-seconds", 1)))));

        router.get("/api/oauth/start", (session, params) -> {
            if (oidc == null) {
                return ApiResponse.error(MODULE, "未启用 OAuth 登录");
            }
            try {
                return ApiResponse.ok(MODULE, Map.of("url", oidc.authorizationUrl()));
            } catch (Exception error) {
                plugin.getLogger().warning("[Panel] 构造 OAuth 授权地址失败: " + error.getMessage());
                return ApiResponse.error(MODULE, "无法连接授权服务器，请检查 issuer 配置");
            }
        });

        router.post("/api/login", (session, params) -> {
            if (!sessions.hasPassword()) {
                return ApiResponse.error(MODULE, "本面板未启用密码登录");
            }
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
                ApiResponse.ok(MODULE, MainThread.call(plugin, this::players,
                        Map.of("online", List.of(), "offline", List.of()))));

        router.post("/api/players/{name}/action", (session, params) ->
                playerAction(params.get("name"), Router.readJson(session)));

        // 下面三个都会读存储（可能是网络上的 MySQL），但它们跑在 HTTP 工作线程上，
        // 不碰 Bukkit API，所以不需要也不应该回主线程
        router.get("/api/players/search", (session, params) -> {
            String query = session.getParms().getOrDefault("q", "");
            if (query.isBlank()) {
                return ApiResponse.ok(MODULE, List.of());
            }
            try {
                return ApiResponse.ok(MODULE, searchPlayers(query));
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "搜索玩家失败: " + error.getMessage());
            }
        });

        router.get("/api/players/{name}/detail", (session, params) -> {
            try {
                Map<String, Object> detail = playerDetail(params.get("name"));
                return detail == null
                        ? ApiResponse.error(MODULE, "找不到玩家: " + params.get("name"))
                        : ApiResponse.ok(MODULE, detail);
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "读取玩家数据失败: " + error.getMessage());
            }
        });

        router.get("/api/players/{name}/ledger", (session, params) -> {
            try {
                List<Map<String, Object>> ledger = playerLedger(params.get("name"),
                        parseLimit(session.getParms().get("limit"), 50));
                return ledger == null
                        ? ApiResponse.error(MODULE, "找不到玩家: " + params.get("name"))
                        : ApiResponse.ok(MODULE, ledger);
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "读取流水失败: " + error.getMessage());
            }
        });

        router.get("/api/economy", (session, params) -> {
            try {
                return ApiResponse.ok(MODULE, economy());
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "读取经济数据失败: " + error.getMessage());
            }
        });

        router.get("/api/monitor", (session, params) -> {
            try {
                return ApiResponse.ok(MODULE, monitor());
            } catch (Exception error) {
                return ApiResponse.error(MODULE, error.getMessage());
            }
        });

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
        data.put("oauth", oidc != null);

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
        // 走 lang 的 time.* 键，跟随 config 的 language；直接用 TimeUtil.formatDuration 会永远是中文
        data.put("uptime", plugin.messages().resolve(null, TimeUtil.duration(uptime)));

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

    // ------------------------------------------------------------------ 性能监控

    /**
     * 监控页数据：当前状态 + 最近采样（画曲线）+ 最近事件 + 会话记录。
     *
     * <p>存储查询不碰 Bukkit API，直接在 HTTP 线程上跑；先刷一次队列，
     * 让面板看到的是已经落盘的最新数据。</p>
     */
    private Map<String, Object> monitor() throws Exception {
        MonitorModule module = plugin.modules() != null && plugin.modules().isActive("monitor")
                ? (MonitorModule) plugin.modules().get("monitor") : null;
        if (module == null || module.service() == null || !module.service().isRunning()) {
            throw new IllegalStateException("性能监控模块未启用（config.yml 的 modules.monitor.enabled）");
        }
        MonitorService service = module.service();
        service.flush();

        Map<String, Object> data = service.status();
        data.put("sampleHistory", service.samples(120, 240));
        data.put("events", service.events(60, null, 0));
        data.put("sessions", service.sessions(10));
        return data;
    }

    // ------------------------------------------------------------------ 经济

    /**
     * 全服经济数据。走的是存储层的聚合查询，因此在 HTTP 线程上直接跑就行——
     * 它不碰 Bukkit API，不需要回主线程。
     */
    private Map<String, Object> economy() throws Exception {
        int days = Math.max(1, plugin.getConfig().getInt("modules.economy.stats-window-days", 7));
        long since = System.currentTimeMillis() - days * 86_400_000L;

        // 把内存里还没落盘的流水先刷下去，免得面板看到的数据比实际少一截
        EconomyModule module = plugin.modules() != null && plugin.modules().isActive("economy")
                ? (EconomyModule) plugin.modules().get("economy") : null;
        if (module != null && module.getLedger() != null) {
            module.getLedger().flush();
        }

        EconomySummary summary = plugin.storage().economySummary();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accounts", summary.accounts());
        data.put("total", summary.total());
        data.put("average", summary.average());
        data.put("richest", summary.richest());
        data.put("windowDays", days);
        data.put("symbol", plugin.economy() == null ? "" : plugin.economy().symbol());
        data.put("tracking", module != null && module.getLedger() != null
                && module.getLedger().isEnabled());

        List<Map<String, Object>> sources = new ArrayList<>();
        double inflow = 0;
        double outflow = 0;
        for (SourceVolume volume : plugin.storage().volumeBySource(since)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", volume.source() == null || volume.source().isEmpty() ? "-" : volume.source());
            item.put("in", volume.in());
            item.put("out", volume.out());
            item.put("count", volume.count());
            sources.add(item);
            inflow += volume.in();
            outflow += volume.out();
        }
        data.put("sources", sources);
        data.put("inflow", inflow);
        data.put("outflow", outflow);

        List<Map<String, Object>> recent = new ArrayList<>();
        for (TransactionRecord record : plugin.storage().recentTransactions(null, 30)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ts", record.timestamp());
            item.put("time", TimeUtil.formatDate(record.timestamp()));
            item.put("player", record.name());
            item.put("type", record.type());
            item.put("amount", record.amount());
            item.put("balanceAfter", record.balanceAfter());
            item.put("source", record.source());
            item.put("detail", record.detail());
            recent.add(item);
        }
        data.put("recent", recent);
        data.put("topBalances", plugin.storage().topBalances(10));
        return data;
    }

    // ------------------------------------------------------------------ 玩家

    private Map<String, Object> players() {
        List<Map<String, Object>> online = new ArrayList<>();
        Set<UUID> onlineIds = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineIds.add(player.getUniqueId());
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
                item.put("playtime",
                        plugin.messages().resolve(null, TimeUtil.duration(data.getTotalPlaytime())));
                item.put("nickname", data.getNickname());
            }
            online.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", online);
        result.put("offline", recentPlayers.snapshot(onlineIds));
        return result;
    }

    // ------------------------------------------------------------------ 离线玩家

    /**
     * 按名字解析 UUID，在线离线都能查到。
     *
     * <p>先查存储的名字索引（纯 IO，可以在 HTTP 线程上跑），查不到再回主线程问一次
     * 在线列表——刚进服还没写过盘的新人只存在于内存里。顺序反过来的话，
     * 每次搜索都要往主线程排队，面板一刷新就会拖慢游戏。</p>
     */
    private UUID resolvePlayer(String name) throws Exception {
        if (name == null || !USERNAME.matcher(name).matches()) {
            return null;
        }
        UUID uuid = plugin.storage().lookupUuid(name);
        if (uuid != null) {
            return uuid;
        }
        return MainThread.call(plugin, () -> {
            Player online = Bukkit.getPlayerExact(name);
            return online == null ? null : online.getUniqueId();
        }, null);
    }

    private List<Map<String, Object>> searchPlayers(String query) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserSummary summary : plugin.storage().searchUsers(query, 30)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", summary.name());
            item.put("uuid", summary.uuid().toString());
            item.put("balance", summary.balance());
            // 在线状态由前端拿玩家列表自行标注，这里不为每条结果去挤主线程
            result.add(item);
        }
        return result;
    }

    /** 单个玩家的完整档案，离线玩家会从存储里读出来。 */
    private Map<String, Object> playerDetail(String name) throws Exception {
        UUID uuid = resolvePlayer(name);
        if (uuid == null) {
            return null;
        }
        UserData data = plugin.users().loadOffline(uuid);
        if (data == null) {
            return null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", data.getName());
        item.put("uuid", uuid.toString());
        item.put("nickname", data.getNickname());
        item.put("balance", data.getBalance());
        item.put("firstJoin", data.getFirstJoin());
        item.put("firstJoinText", data.getFirstJoin() <= 0 ? "-" : TimeUtil.formatDate(data.getFirstJoin()));
        item.put("lastSeen", data.getLastSeen());
        item.put("lastSeenText", data.getLastSeen() <= 0 ? "-" : TimeUtil.formatDate(data.getLastSeen()));
        item.put("playtime", plugin.messages().resolve(null, TimeUtil.duration(data.getTotalPlaytime())));
        item.put("homes", data.getHomeCount());

        item.put("banned", data.isBanned());
        item.put("banReason", data.getBanReason());
        item.put("banSource", data.getBanSource());
        item.put("banExpiry", data.getBanExpiry());
        item.put("banExpiryText", banExpiryText(data.isBanned(), data.getBanExpiry()));
        item.put("muted", data.isMuted());
        item.put("muteReason", data.getMuteReason());
        item.put("muteSource", data.getMuteSource());
        item.put("muteExpiry", data.getMuteExpiry());
        item.put("muteExpiryText", banExpiryText(data.isMuted(), data.getMuteExpiry()));

        // 在线信息只有主线程才拿得到；玩家不在线时这一趟直接返回 null
        Map<String, Object> live = MainThread.call(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return null;
            }
            Map<String, Object> online = new LinkedHashMap<>();
            online.put("ping", player.getPing());
            online.put("world", player.getWorld().getName());
            online.put("gamemode", player.getGameMode().name());
            online.put("health", Math.round(player.getHealth()));
            online.put("op", player.isOp());
            return online;
        }, null);
        item.put("online", live != null);
        item.put("live", live);
        return item;
    }

    /** 封禁 / 禁言到期时间的展示文案。 */
    private String banExpiryText(boolean active, long expiry) {
        if (!active) {
            return "-";
        }
        return expiry <= 0 ? "永久" : TimeUtil.formatDate(expiry);
    }

    /** 某个玩家的经济流水。 */
    private List<Map<String, Object>> playerLedger(String name, int limit) throws Exception {
        UUID uuid = resolvePlayer(name);
        if (uuid == null) {
            return null;
        }
        // 内存里攒着的那批还没落盘，不先刷一下的话最近几笔看不到
        EconomyModule module = plugin.modules() != null && plugin.modules().isActive("economy")
                ? (EconomyModule) plugin.modules().get("economy") : null;
        if (module != null && module.getLedger() != null) {
            module.getLedger().flush();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (TransactionRecord record : plugin.storage().recentTransactions(uuid, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ts", record.timestamp());
            item.put("time", TimeUtil.formatDate(record.timestamp()));
            item.put("type", record.type());
            item.put("amount", record.amount());
            item.put("balanceAfter", record.balanceAfter());
            item.put("source", record.source());
            item.put("detail", record.detail());
            result.add(item);
        }
        return result;
    }

    private static int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException error) {
            return fallback;
        }
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

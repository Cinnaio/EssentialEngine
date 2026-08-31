package com.github.cinnaio.essentialengine.module.papi;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.Text;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import com.github.cinnaio.essentialengine.module.economy.EconomyModule;
import com.github.cinnaio.essentialengine.module.economy.KitManager;
import com.github.cinnaio.essentialengine.module.husktowns.HuskTownsModule;
import com.github.cinnaio.essentialengine.module.teleport.TeleportModule;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EssentialEngine 的 PlaceholderAPI 变量。
 *
 * <p>同一份实现会用 {@code essentialengine} 与 {@code ee} 两个标识各注册一次，
 * 因此 {@code %ee_balance%} 和 {@code %essentialengine_balance%} 等价。</p>
 *
 * <p><b>性能约定</b>：占位符会被计分板 / Tab 按秒级频率反复查询，因此这里
 * 只读内存——玩家数据走 {@link com.github.cinnaio.essentialengine.core.user.UserManager#getIfLoaded}
 * （不落盘、不阻塞），排行榜走 {@link BalTopCache} 的异步快照。
 * 离线且不在缓存里的玩家，与数据相关的变量一律返回空字符串。</p>
 *
 * <p><b>语言</b>：时长、日期与各种标签按<b>被查询玩家</b>的客户端语言渲染，
 * 与插件内消息保持一致；颜色统一输出成传统 {@code §} 代码，
 * 这样不认识 MiniMessage 的计分板 / Tab 插件也能正确显示。</p>
 */
public class EnginePlaceholders extends PlaceholderExpansion {

    private final EssentialEngine plugin;
    private final String identifier;
    private final BalTopCache balTop;

    public EnginePlaceholders(EssentialEngine plugin, String identifier, BalTopCache balTop) {
        this.plugin = plugin;
        this.identifier = identifier;
        this.balTop = balTop;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        List<String> authors = plugin.getDescription().getAuthors();
        return authors.isEmpty() ? "Cinnaio" : String.join(", ", authors);
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 保持注册状态，PlaceholderAPI 重载时不需要本插件重新注册。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String key = params.toLowerCase(Locale.ROOT);

        // 服务器级变量不需要玩家上下文，先处理
        String global = server(key);
        if (global != null) {
            return global;
        }
        if (player == null) {
            return "";
        }

        Player online = player.getPlayer();

        switch (key) {
            case "name" -> {
                return player.getName() == null ? "" : player.getName();
            }
            case "uuid" -> {
                return player.getUniqueId().toString();
            }
            case "is_online" -> {
                return bool(online != null);
            }
            default -> {
                // 继续往下走
            }
        }

        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data == null) {
            // 离线且未缓存：不做阻塞读取，返回空串而不是 null，避免界面上出现裸露的 %ee_xxx%
            return "";
        }
        return user(online, data, key);
    }

    // ------------------------------------------------------------------ 服务器级

    /** 与玩家无关的变量；不匹配时返回 null 交给后面的分支。 */
    private String server(String key) {
        switch (key) {
            case "version" -> {
                return plugin.getDescription().getVersion();
            }
            case "storage" -> {
                return plugin.storage() == null ? "" : plugin.storage().getName();
            }
            case "modules" -> {
                return plugin.modules() == null ? "" : String.join(", ", plugin.modules().getActiveIds());
            }
            case "online", "online_visible" -> {
                // 排除隐身玩家，和 /list 一类的展示保持一致
                return String.valueOf(countOnline(false));
            }
            case "online_total" -> {
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            }
            case "vanished_count" -> {
                return String.valueOf(countOnline(true));
            }
            case "afk_count" -> {
                return String.valueOf(countAfk());
            }
            case "max_players" -> {
                return String.valueOf(Bukkit.getMaxPlayers());
            }
            case "tps" -> {
                return tps(0);
            }
            case "tps_1m" -> {
                return tps(0);
            }
            case "tps_5m" -> {
                return tps(1);
            }
            case "tps_15m" -> {
                return tps(2);
            }
            case "warps" -> {
                TeleportModule teleport = teleport();
                return teleport == null ? "" : String.valueOf(teleport.getManager().warpNames().size());
            }
            case "warps_list" -> {
                TeleportModule teleport = teleport();
                return teleport == null ? "" : String.join(", ", teleport.getManager().warpNames());
            }
            default -> {
                // 继续判断带参数的前缀型变量
            }
        }

        if (key.startsWith("module_")) {
            return plugin.modules() == null ? "false"
                    : bool(plugin.modules().isActive(key.substring("module_".length())));
        }
        // 顺序要紧：formatted 的前缀比 baltop_balance_ 更长，必须先判断
        if (key.startsWith("baltop_balance_formatted_")) {
            BalTopCache.Entry entry = balTop.get(rank(key, "baltop_balance_formatted_"));
            return entry == null ? "" : symbol() + money(entry.balance());
        }
        if (key.startsWith("baltop_balance_")) {
            BalTopCache.Entry entry = balTop.get(rank(key, "baltop_balance_"));
            return entry == null ? "" : money(entry.balance());
        }
        if (key.startsWith("baltop_name_")) {
            BalTopCache.Entry entry = balTop.get(rank(key, "baltop_name_"));
            return entry == null ? "" : entry.name();
        }
        return null;
    }

    // ------------------------------------------------------------------ 玩家级

    /**
     * 玩家级变量。{@code viewer} 就是被查询的那名玩家（PAPI 每次解析都针对一个人），
     * 语言、权限与 HuskTowns 查询都走它；玩家离线时为 null，此时时长按控制台语言渲染，
     * 需要活着的 Player 才能算的变量返回空串。
     */
    private String user(Player viewer, UserData data, String key) {
        switch (key) {
            // ---- 身份 ----
            case "displayname" -> {
                return Text.legacy(data.getDisplayName());
            }
            case "nickname" -> {
                return data.getNickname() == null ? none(viewer) : Text.legacy(data.getNickname());
            }
            case "nickname_raw" -> {
                return data.getNickname() == null ? "" : Text.legacy(data.getNickname());
            }
            case "has_nickname" -> {
                return bool(data.getNickname() != null);
            }
            case "locale" -> {
                return data.getClientLocale() == null ? "" : data.getClientLocale();
            }

            // ---- 经济 ----
            case "balance" -> {
                return money(data.getBalance());
            }
            case "balance_formatted" -> {
                return symbol() + money(data.getBalance());
            }
            case "balance_commas" -> {
                return String.format(Locale.US, "%,.2f", data.getBalance());
            }
            case "currency_symbol" -> {
                return symbol();
            }
            case "currency_name" -> {
                return plugin.economy() == null ? "" : plugin.economy().currencyName();
            }
            case "baltop_position" -> {
                return String.valueOf(balTop.rankOf(data.getName()));
            }

            // ---- 时间 ----
            case "playtime" -> {
                return duration(viewer, data.getTotalPlaytime());
            }
            case "playtime_hours" -> {
                return String.valueOf(data.getTotalPlaytime() / 3_600_000L);
            }
            case "playtime_minutes" -> {
                return String.valueOf(data.getTotalPlaytime() / 60_000L);
            }
            case "playtime_seconds" -> {
                return String.valueOf(data.getTotalPlaytime() / 1000L);
            }
            case "session" -> {
                return duration(viewer, sessionMillis(data));
            }
            case "session_seconds" -> {
                return String.valueOf(sessionMillis(data) / 1000L);
            }
            case "idle" -> {
                return duration(viewer, idleMillis(data));
            }
            case "idle_seconds" -> {
                return String.valueOf(idleMillis(data) / 1000L);
            }
            case "firstjoin" -> {
                return date(viewer, data.getFirstJoin());
            }
            case "lastlogin" -> {
                return date(viewer, data.getLastLogin());
            }
            case "lastseen" -> {
                return date(viewer, data.getLastSeen());
            }
            case "lastseen_ago" -> {
                return plugin.messages().resolve(viewer, TimeUtil.ago(data.getLastSeen()));
            }

            // ---- 状态开关 ----
            case "afk" -> {
                return bool(data.isAfk());
            }
            case "afk_display" -> {
                return data.isAfk() ? tag(viewer, "papi.afk-display") : "";
            }
            case "vanished" -> {
                return bool(data.isVanished());
            }
            case "vanish_display" -> {
                return data.isVanished() ? tag(viewer, "papi.vanish-display") : "";
            }
            case "god" -> {
                return bool(data.isGodMode());
            }
            case "fly" -> {
                return bool(data.isFlightEnabled());
            }
            case "socialspy" -> {
                return bool(data.isSocialSpy());
            }
            case "msgtoggle" -> {
                return bool(data.isAcceptingMessages());
            }

            // ---- 惩罚 ----
            case "muted" -> {
                return bool(data.isMuted());
            }
            case "mute_display" -> {
                return data.isMuted() ? tag(viewer, "papi.mute-display") : "";
            }
            case "mute_reason" -> {
                return data.isMuted() ? Text.legacy(data.getMuteReason()) : "";
            }
            case "mute_source" -> {
                return data.isMuted() ? data.getMuteSource() : "";
            }
            case "mute_expiry" -> {
                return data.isMuted() ? expiry(viewer, data.getMuteExpiry()) : "";
            }
            case "mute_remaining" -> {
                return data.isMuted() ? remaining(viewer, data.getMuteExpiry()) : "";
            }
            case "banned" -> {
                return bool(data.isBanned());
            }
            case "ban_display" -> {
                return data.isBanned() ? tag(viewer, "papi.ban-display") : "";
            }
            case "ban_reason" -> {
                return data.isBanned() ? Text.legacy(data.getBanReason()) : "";
            }
            case "ban_source" -> {
                return data.isBanned() ? data.getBanSource() : "";
            }
            case "ban_expiry" -> {
                return data.isBanned() ? expiry(viewer, data.getBanExpiry()) : "";
            }
            case "ban_remaining" -> {
                return data.isBanned() ? remaining(viewer, data.getBanExpiry()) : "";
            }

            // ---- 家 ----
            case "homes" -> {
                return String.valueOf(data.getHomeCount());
            }
            case "homes_list" -> {
                return String.join(", ", data.getHomeNames());
            }
            case "homes_max" -> {
                return maxHomes(viewer);
            }
            case "homes_free" -> {
                return freeHomes(viewer, data);
            }

            // ---- 邮件与屏蔽 ----
            case "mails" -> {
                return String.valueOf(data.getMails().size());
            }
            case "has_mail" -> {
                return bool(!data.getMails().isEmpty());
            }
            case "ignored" -> {
                return String.valueOf(data.getIgnored().size());
            }

            // ---- HuskTowns ----
            case "has_town" -> {
                return bool(townSummary(viewer) != null);
            }
            case "town" -> {
                Map<String, Object> town = townSummary(viewer);
                return town == null ? none(viewer) : String.valueOf(town.get("name"));
            }
            case "town_role" -> {
                Map<String, Object> town = townSummary(viewer);
                return town == null ? "" : String.valueOf(town.get("role"));
            }
            case "town_level" -> {
                Map<String, Object> town = townSummary(viewer);
                return town == null ? "" : String.valueOf(town.get("level"));
            }
            case "town_members" -> {
                Map<String, Object> town = townSummary(viewer);
                return town == null ? "" : String.valueOf(town.get("members"));
            }
            case "town_money" -> {
                Map<String, Object> town = townSummary(viewer);
                return town == null ? "" : String.valueOf(town.get("money"));
            }
            default -> {
                // 落到下面的前缀型变量
            }
        }

        if (key.startsWith("has_home_")) {
            return bool(data.hasHome(key.substring("has_home_".length())));
        }
        if (key.startsWith("kit_ready_")) {
            return kitReady(data, key.substring("kit_ready_".length()));
        }
        if (key.startsWith("kit_cooldown_")) {
            return kitCooldown(viewer, data, key.substring("kit_cooldown_".length()));
        }
        return null;
    }

    // ------------------------------------------------------------------ 套装

    private String kitReady(UserData data, String name) {
        KitManager kits = kits();
        if (kits == null || !kits.exists(name)) {
            return "";
        }
        return bool(kitRemaining(kits, data, name) <= 0);
    }

    private String kitCooldown(Player viewer, UserData data, String name) {
        KitManager kits = kits();
        if (kits == null || !kits.exists(name)) {
            return "";
        }
        long remaining = kitRemaining(kits, data, name);
        if (remaining < 0) {
            // 只能领一次且已领过
            return tag(viewer, "papi.kit-once");
        }
        return remaining == 0 ? tag(viewer, "papi.kit-ready") : duration(viewer, remaining);
    }

    /** 套装剩余冷却毫秒；0 表示现在可领，-1 表示一次性且已领过。 */
    private long kitRemaining(KitManager kits, UserData data, String name) {
        long used = data.getKitUsed(name);
        long cooldown = kits.cooldownOf(name);
        if (used <= 0 || cooldown == 0) {
            return 0;
        }
        if (cooldown < 0) {
            return -1;
        }
        return Math.max(0, used + cooldown * 1000L - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ 家数量

    private String maxHomes(Player viewer) {
        TeleportModule teleport = teleport();
        if (teleport == null || viewer == null) {
            return "";
        }
        int max = teleport.getManager().maxHomes(viewer);
        return max == Integer.MAX_VALUE ? tag(viewer, "papi.unlimited") : String.valueOf(max);
    }

    private String freeHomes(Player viewer, UserData data) {
        TeleportModule teleport = teleport();
        if (teleport == null || viewer == null) {
            return "";
        }
        int max = teleport.getManager().maxHomes(viewer);
        if (max == Integer.MAX_VALUE) {
            return String.valueOf(Integer.MAX_VALUE);
        }
        return String.valueOf(Math.max(0, max - data.getHomeCount()));
    }

    // ------------------------------------------------------------------ 模块查找
    //
    // 这些模块可能被配置关掉，或（HuskTowns）前置插件根本没装。
    // 一律先问 ModuleManager 再碰对应的类，模块没启用时相关类不会被加载，
    // 因此缺少前置插件时也不会抛 NoClassDefFoundError。

    private TeleportModule teleport() {
        if (plugin.modules() == null || !plugin.modules().isActive("teleport")) {
            return null;
        }
        return (TeleportModule) plugin.modules().get("teleport");
    }

    private KitManager kits() {
        if (plugin.modules() == null || !plugin.modules().isActive("economy")) {
            return null;
        }
        return ((EconomyModule) plugin.modules().get("economy")).getKits();
    }

    /** 玩家所属城镇的简要信息；模块未启用、玩家离线或不在城镇时返回 null。 */
    private Map<String, Object> townSummary(Player online) {
        if (online == null || plugin.modules() == null || !plugin.modules().isActive("husktowns")) {
            return null;
        }
        try {
            HuskTownsModule module = (HuskTownsModule) plugin.modules().get("husktowns");
            return module.getService().summaryOf(online);
        } catch (Throwable error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private String symbol() {
        return plugin.economy() == null ? "" : plugin.economy().symbol();
    }

    private static String bool(boolean value) {
        return value ? "true" : "false";
    }

    /** 两位小数。DecimalFormat 不是线程安全的，而占位符可能来自异步线程，故用 String.format。 */
    private static String money(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private long sessionMillis(UserData data) {
        return data.getSessionPlaytime();
    }

    private long idleMillis(UserData data) {
        return Math.max(0, System.currentTimeMillis() - data.getLastActivity());
    }

    /** 按查看者语言渲染的时长，例如「3天2小时」/「3d 2h」。 */
    private String duration(Player viewer, long millis) {
        return Text.legacy(plugin.messages().resolve(viewer, TimeUtil.duration(millis)));
    }

    private String date(Player viewer, long timestamp) {
        return Text.legacy(plugin.messages().resolve(viewer, TimeUtil.date(timestamp)));
    }

    /** 封禁 / 禁言到期时间；0 表示永久。 */
    private String expiry(Player viewer, long timestamp) {
        return timestamp <= 0
                ? tag(viewer, "general.permanent")
                : date(viewer, timestamp);
    }

    private String remaining(Player viewer, long timestamp) {
        return timestamp <= 0
                ? tag(viewer, "general.permanent")
                : duration(viewer, Math.max(0, timestamp - System.currentTimeMillis()));
    }

    /** 取一条语言文件里的短标签，转成传统 § 颜色；键缺失时返回空串。 */
    private String tag(Player viewer, String key) {
        return Text.legacy(plugin.messages().rawOr(viewer, key, ""));
    }

    private String none(Player viewer) {
        return Text.legacy(plugin.messages().rawOr(viewer, "general.none", ""));
    }

    private static int rank(String key, String prefix) {
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String tps(int index) {
        try {
            double[] values = Bukkit.getTPS();
            return index >= values.length ? "" : String.format(Locale.US, "%.2f", Math.min(20D, values[index]));
        } catch (Throwable error) {
            // Folia 不提供 TPS
            return "";
        }
    }

    private int countOnline(boolean vanishedOnly) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UserData data = plugin.users().getIfLoaded(player.getUniqueId());
            boolean vanished = data != null && data.isVanished();
            if (vanished == vanishedOnly) {
                count++;
            }
        }
        return count;
    }

    private int countAfk() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UserData data = plugin.users().getIfLoaded(player.getUniqueId());
            if (data != null && data.isAfk()) {
                count++;
            }
        }
        return count;
    }
}

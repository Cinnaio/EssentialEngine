package com.github.cinnaio.essentialengine.module.admin;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.LocationUtil;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.Text;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * 管理与惩罚模块：踢出、封禁、禁言、隐身、背包查看等。
 *
 * <p>封禁 / 禁言记录保存在插件自己的玩家数据里（而不是原版 banned-players.json），
 * 因此支持临时封禁、跨存储后端同步，并且能和 MySQL 多服共用。</p>
 */
public class AdminModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";
    public static final String BAN_EXEMPT = "essentialengine.ban.exempt";
    public static final String KICK_EXEMPT = "essentialengine.kick.exempt";

    public AdminModule(EssentialEngine plugin) {
        super(plugin, "admin", "管理与惩罚");
    }

    @Override
    protected void setup() {
        listener(new AdminListener(plugin));

        command("kick").permission(PERM + "kick").description("踢出玩家")
                .usage("/kick <玩家> [理由]").minArgs(1).handler(this::kick)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("ban").permission(PERM + "ban").description("永久封禁")
                .usage("/ban <玩家> [理由]").minArgs(1)
                .handler((sender, label, args) -> ban(sender, args, 0, 1))
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("tempban").aliases("tban").permission(PERM + "tempban").description("临时封禁")
                .usage("/tempban <玩家> <时长> [理由]").minArgs(2)
                .handler(this::tempBan)
                .completer((sender, args) -> args.length <= 1
                        ? PlayerUtil.visibleNames(sender) : List.of("10m", "1h", "1d", "7d", "30d"));

        command("unban").aliases("pardon").permission(PERM + "unban").description("解除封禁")
                .usage("/unban <玩家>").minArgs(1).handler(this::unban);

        command("mute").permission(PERM + "mute").description("禁言")
                .usage("/mute <玩家> [理由]").minArgs(1)
                .handler((sender, label, args) -> mute(sender, args, 0, 1))
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("tempmute").aliases("tmute").permission(PERM + "tempmute").description("临时禁言")
                .usage("/tempmute <玩家> <时长> [理由]").minArgs(2)
                .handler(this::tempMute)
                .completer((sender, args) -> args.length <= 1
                        ? PlayerUtil.visibleNames(sender) : List.of("10m", "1h", "1d", "7d"));

        command("unmute").permission(PERM + "unmute").description("解除禁言")
                .usage("/unmute <玩家>").minArgs(1).handler(this::unmute)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("vanish").aliases("v").permission(PERM + "vanish").description("切换隐身")
                .usage("/vanish [玩家]").handler(this::vanish)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("invsee").aliases("inv").playerOnly().permission(PERM + "invsee")
                .description("查看他人背包").usage("/invsee <玩家>").minArgs(1)
                .handler(this::invsee)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("clearinventory").aliases("ci", "clear").permission(PERM + "clearinventory")
                .description("清空背包").usage("/clearinventory [玩家]").handler(this::clearInventory)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("seen").permission(PERM + "seen").description("查看玩家最后在线时间")
                .usage("/seen <玩家>").minArgs(1).handler(this::seen)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("whois").aliases("playerinfo", "pinfo").permission(PERM + "whois")
                .description("查看玩家详细信息").usage("/whois <玩家>").minArgs(1)
                .handler(this::whois)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));
    }

    // ------------------------------------------------------------------ 踢出

    private void kick(CommandSender sender, String label, String[] args) {
        Player target = PlayerUtil.requireOnline(sender, args[0]);
        if (target.hasPermission(KICK_EXEMPT)) {
            throw new CommandError("admin.target-exempt", "player", target.getName());
        }
        String reason = args.length > 1 ? join(args, 1) : plugin.messages().raw("admin.default-reason");
        String message = plugin.messages().raw("admin.kick-screen", "reason", reason,
                "operator", nameOf(sender));
        SchedulerCompat.runForEntity(plugin, target, () -> target.kickPlayer(Text.legacy(message)));
        announce("admin.kick-broadcast", "player", target.getName(),
                "operator", nameOf(sender), "reason", reason);
    }

    // ------------------------------------------------------------------ 封禁

    private void tempBan(CommandSender sender, String label, String[] args) {
        long duration = TimeUtil.parseDuration(args[1]);
        if (duration < 0) {
            throw new CommandError("admin.invalid-duration", "input", args[1]);
        }
        ban(sender, args, duration, 2);
    }

    private void ban(CommandSender sender, String[] args, long duration, int reasonIndex) {
        String reason = args.length > reasonIndex
                ? join(args, reasonIndex) : plugin.messages().raw("admin.default-reason");
        long expiry = duration <= 0 ? 0 : System.currentTimeMillis() + duration;

        plugin.users().lookup(sender, args[0], data -> {
            Player online = Bukkit.getPlayer(data.getUuid());
            if (online != null && online.hasPermission(BAN_EXEMPT)) {
                plugin.messages().send(sender, "admin.target-exempt", "player", data.getName());
                return;
            }
            data.setBan(reason, nameOf(sender), expiry);
            plugin.users().saveAsync(data);

            String duration_text = expiry <= 0 ? "永久" : TimeUtil.formatDuration(expiry - System.currentTimeMillis());
            if (online != null) {
                String message = plugin.messages().raw("admin.ban-screen",
                        "reason", reason, "operator", nameOf(sender), "duration", duration_text);
                SchedulerCompat.runForEntity(plugin, online, () -> online.kickPlayer(Text.legacy(message)));
            }
            announce("admin.ban-broadcast", "player", data.getName(),
                    "operator", nameOf(sender), "reason", reason, "duration", duration_text);
        });
    }

    private void unban(CommandSender sender, String label, String[] args) {
        plugin.users().lookup(sender, args[0], data -> {
            if (!data.isBanned()) {
                plugin.messages().send(sender, "admin.not-banned", "player", data.getName());
                return;
            }
            data.clearBan();
            plugin.users().saveAsync(data);
            plugin.messages().send(sender, "admin.unbanned", "player", data.getName());
        });
    }

    // ------------------------------------------------------------------ 禁言

    private void tempMute(CommandSender sender, String label, String[] args) {
        long duration = TimeUtil.parseDuration(args[1]);
        if (duration < 0) {
            throw new CommandError("admin.invalid-duration", "input", args[1]);
        }
        mute(sender, args, duration, 2);
    }

    private void mute(CommandSender sender, String[] args, long duration, int reasonIndex) {
        String reason = args.length > reasonIndex
                ? join(args, reasonIndex) : plugin.messages().raw("admin.default-reason");
        long expiry = duration <= 0 ? 0 : System.currentTimeMillis() + duration;

        plugin.users().lookup(sender, args[0], data -> {
            data.setMute(reason, nameOf(sender), expiry);
            plugin.users().saveAsync(data);
            String durationText = expiry <= 0 ? "永久" : TimeUtil.formatDuration(expiry - System.currentTimeMillis());

            Player online = Bukkit.getPlayer(data.getUuid());
            if (online != null) {
                plugin.messages().send(online, "admin.muted-notify",
                        "reason", reason, "duration", durationText, "operator", nameOf(sender));
            }
            plugin.messages().send(sender, "admin.muted", "player", data.getName(),
                    "duration", durationText, "reason", reason);
        });
    }

    private void unmute(CommandSender sender, String label, String[] args) {
        plugin.users().lookup(sender, args[0], data -> {
            if (!data.isMuted()) {
                plugin.messages().send(sender, "admin.not-muted", "player", data.getName());
                return;
            }
            data.clearMute();
            plugin.users().saveAsync(data);
            plugin.messages().send(sender, "admin.unmuted", "player", data.getName());
            Player online = Bukkit.getPlayer(data.getUuid());
            if (online != null) {
                plugin.messages().send(online, "admin.unmuted-notify");
            }
        });
    }

    // ------------------------------------------------------------------ 隐身

    private void vanish(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission(PERM + "vanish.others")) {
                throw new CommandError("general.no-permission", "permission", PERM + "vanish.others");
            }
            target = PlayerUtil.requireOnline(sender, args[0]);
        } else {
            target = PlayerUtil.requirePlayer(sender);
        }
        UserData data = plugin.users().get(target);
        boolean enable = !data.isVanished();
        data.setVanished(enable);
        VanishHelper.apply(plugin, target, enable);
        plugin.messages().send(target, enable ? "admin.vanish-on" : "admin.vanish-off");
        if (!target.equals(sender)) {
            plugin.messages().send(sender, enable ? "admin.vanish-on-other" : "admin.vanish-off-other",
                    "player", target.getName());
        }
    }

    // ------------------------------------------------------------------ 背包

    private void invsee(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        Player target = PlayerUtil.requireOnline(sender, args[0]);
        if (target.equals(player)) {
            throw new CommandError("admin.invsee-self");
        }
        player.openInventory(target.getInventory());
    }

    private void clearInventory(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission(PERM + "clearinventory.others")) {
                throw new CommandError("general.no-permission", "permission", PERM + "clearinventory.others");
            }
            target = PlayerUtil.requireOnline(sender, args[0]);
        } else {
            target = PlayerUtil.requirePlayer(sender);
        }
        SchedulerCompat.runForEntity(plugin, target, () -> {
            target.getInventory().clear();
            target.getInventory().setArmorContents(null);
        });
        plugin.messages().send(target, "admin.inventory-cleared");
        if (!target.equals(sender)) {
            plugin.messages().send(sender, "admin.inventory-cleared-other", "player", target.getName());
        }
    }

    // ------------------------------------------------------------------ 查询

    private void seen(CommandSender sender, String label, String[] args) {
        plugin.users().lookup(sender, args[0], data -> {
            Player online = Bukkit.getPlayer(data.getUuid());
            if (online != null && PlayerUtil.canSee(sender, online)) {
                plugin.messages().send(sender, "admin.seen-online",
                        "player", data.getName(),
                        "time", TimeUtil.formatDuration(data.getTotalPlaytime()));
            } else {
                plugin.messages().send(sender, "admin.seen-offline",
                        "player", data.getName(),
                        "time", TimeUtil.formatDate(data.getLastSeen()),
                        "ago", TimeUtil.formatAgo(data.getLastSeen()));
            }
        });
    }

    private void whois(CommandSender sender, String label, String[] args) {
        plugin.users().lookup(sender, args[0], data -> {
            Player online = Bukkit.getPlayer(data.getUuid());
            plugin.messages().send(sender, "admin.whois-header", "player", data.getName());
            line(sender, "UUID", data.getUuid().toString());
            line(sender, "昵称", data.getNickname() == null ? "无" : data.getNickname());
            line(sender, "首次登录", TimeUtil.formatDate(data.getFirstJoin()));
            line(sender, "最后在线", online != null ? "&a当前在线" : TimeUtil.formatDate(data.getLastSeen()));
            line(sender, "累计在线", TimeUtil.formatDuration(data.getTotalPlaytime()));
            line(sender, "余额", String.format("%.2f", data.getBalance()));
            line(sender, "家数量", String.valueOf(data.getHomeCount()));
            if (online != null) {
                line(sender, "位置", LocationUtil.describe(online.getLocation()));
                line(sender, "延迟", online.getPing() + "ms");
                line(sender, "游戏模式", online.getGameMode().name());
            }
            if (data.isBanned()) {
                line(sender, "&c封禁", data.getBanReason() + " &7(由 " + data.getBanSource() + ")");
            }
            if (data.isMuted()) {
                line(sender, "&c禁言", data.getMuteReason() + " &7(由 " + data.getMuteSource() + ")");
            }
        });
    }

    private void line(CommandSender sender, String key, String value) {
        plugin.messages().sendRaw(sender, "&8 - &7{key}: &f{value}", "key", key, "value", value);
    }

    // ------------------------------------------------------------------ 工具

    private void announce(String key, Object... placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.messages().send(player, key, placeholders);
        }
        plugin.messages().send(Bukkit.getConsoleSender(), key, placeholders);
    }

    private String nameOf(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "控制台";
    }

    private String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }
}

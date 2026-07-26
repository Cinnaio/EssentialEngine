package com.github.cinnaio.essentialengine.core.util;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.user.UserData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 玩家查找与可见性判断。 */
public final class PlayerUtil {

    /** 能看见隐身玩家的权限。 */
    public static final String SEE_VANISHED = "essentialengine.vanish.see";

    private PlayerUtil() {
    }

    /** 精确匹配优先，其次前缀匹配。找不到返回 null。 */
    public static Player matchOnline(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Player best = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                if (best == null || player.getName().length() < best.getName().length()) {
                    best = player;
                }
            }
        }
        return best;
    }

    /** 找不到（或对发送者不可见）时抛出可读错误。 */
    public static Player requireOnline(CommandSender viewer, String name) {
        Player player = matchOnline(name);
        if (player == null || !canSee(viewer, player)) {
            throw new CommandError("general.player-not-found", "player", name);
        }
        return player;
    }

    public static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new CommandError("general.player-only");
    }

    /** 发送者是否能看见目标（隐身处理）。 */
    public static boolean canSee(CommandSender viewer, Player target) {
        if (target == null) {
            return false;
        }
        if (viewer == null || viewer == target || !(viewer instanceof Player)) {
            return true;
        }
        EssentialEngine plugin = EssentialEngine.get();
        if (plugin == null) {
            return true;
        }
        UserData data = plugin.users().getIfLoaded(target.getUniqueId());
        if (data == null || !data.isVanished()) {
            return true;
        }
        return viewer.hasPermission(SEE_VANISHED);
    }

    /** Tab 补全用的在线玩家名列表，自动过滤隐身玩家。 */
    public static List<String> visibleNames(CommandSender viewer) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(viewer, player)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    /** 展示名：优先昵称。 */
    public static String display(Player player) {
        EssentialEngine plugin = EssentialEngine.get();
        if (plugin != null) {
            UserData data = plugin.users().getIfLoaded(player.getUniqueId());
            if (data != null && data.getNickname() != null) {
                return data.getNickname();
            }
        }
        return player.getName();
    }

    /** 解析开 / 关 / 切换。 */
    public static boolean parseToggle(String raw, boolean current) {
        if (raw == null) {
            return !current;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "开", "开启" -> true;
            case "off", "false", "disable", "关", "关闭" -> false;
            default -> !current;
        };
    }
}

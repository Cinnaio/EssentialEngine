package com.github.cinnaio.essentialengine.module.chat;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 私聊、AFK 与监听（socialspy）的公共逻辑。
 */
public class ChatManager {

    public static final String SPY_PERMISSION = "essentialengine.socialspy";
    public static final String COLOR_PERMISSION = "essentialengine.chat.color";

    private final EssentialEngine plugin;
    private Object afkTask;

    public ChatManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int timeout = plugin.getConfig().getInt("modules.chat.afk-timeout-seconds", 300);
        if (timeout <= 0) {
            return;
        }
        afkTask = SchedulerCompat.runTimer(plugin, this::checkAfk, 200L, 100L);
    }

    public void stop() {
        SchedulerCompat.cancel(afkTask);
        afkTask = null;
    }

    private void checkAfk() {
        long timeout = plugin.getConfig().getInt("modules.chat.afk-timeout-seconds", 300) * 1000L;
        if (timeout <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UserData data = plugin.users().getIfLoaded(player.getUniqueId());
            if (data == null || data.isAfk()) {
                continue;
            }
            if (now - data.getLastActivity() > timeout) {
                setAfk(player, data, true, null);
            }
        }
    }

    /** 记录一次活动；玩家若处于 AFK 则自动解除。 */
    public void markActive(Player player) {
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data == null) {
            return;
        }
        data.touchActivity();
        if (data.isAfk()) {
            setAfk(player, data, false, null);
        }
    }

    public void setAfk(Player player, UserData data, boolean afk, String reason) {
        data.setAfk(afk);
        data.touchActivity();
        if (!plugin.getConfig().getBoolean("modules.chat.afk-broadcast", true)) {
            plugin.messages().send(player, afk ? "chat.afk-on" : "chat.afk-off");
            return;
        }
        String key = afk
                ? (reason == null || reason.isEmpty() ? "chat.afk-broadcast" : "chat.afk-broadcast-reason")
                : "chat.afk-return-broadcast";
        for (Player online : Bukkit.getOnlinePlayers()) {
            plugin.messages().send(online, key,
                    "player", PlayerUtil.display(player),
                    "reason", reason == null ? "" : reason);
        }
    }

    // ------------------------------------------------------------------ 私聊

    public void sendPrivate(CommandSender from, Player target, String message) {
        // 控制台名称按各接收者的语言渲染
        Object senderName = from instanceof Player player
                ? PlayerUtil.display(player) : MessageManager.localized("general.console");

        if (from instanceof Player player) {
            UserData senderData = plugin.users().get(player);
            if (senderData.isMuted()) {
                throw new CommandError("admin.you-are-muted",
                        "reason", senderData.getMuteReason(),
                        "time", senderData.getMuteExpiry() <= 0
                                ? MessageManager.localized("general.permanent")
                                : TimeUtil.duration(senderData.getMuteExpiry() - System.currentTimeMillis()));
            }
            UserData targetData = plugin.users().get(target);
            if (targetData.isIgnoring(player.getUniqueId()) && !player.hasPermission("essentialengine.ignore.bypass")) {
                throw new CommandError("chat.target-ignoring", "player", target.getName());
            }
            if (!targetData.isAcceptingMessages() && !player.hasPermission("essentialengine.msgtoggle.bypass")) {
                throw new CommandError("chat.target-messages-off", "player", target.getName());
            }
            senderData.setReplyTarget(target.getUniqueId());
        }

        UserData targetData = plugin.users().get(target);
        targetData.setReplyTarget(from instanceof Player player ? player.getUniqueId() : null);

        String colored = colorize(from, message);
        plugin.messages().send(from, "chat.format-private-sender",
                "target", PlayerUtil.display(target), "message", colored);
        plugin.messages().send(target, "chat.format-private-target",
                "sender", senderName, "message", colored);

        if (targetData.isAfk()) {
            plugin.messages().send(from, "chat.target-afk", "player", target.getName());
        }
        broadcastSpy(from, senderName, target.getName(), colored);
    }

    private void broadcastSpy(CommandSender from, Object senderName, String targetName, String message) {
        for (Player spy : Bukkit.getOnlinePlayers()) {
            if (spy.equals(from) || spy.getName().equals(targetName)) {
                continue;
            }
            UserData data = plugin.users().getIfLoaded(spy.getUniqueId());
            if (data == null || !data.isSocialSpy() || !spy.hasPermission(SPY_PERMISSION)) {
                continue;
            }
            plugin.messages().send(spy, "chat.format-socialspy",
                    "sender", senderName, "target", targetName, "message", message);
        }
    }

    /** 有权限的玩家才能在消息里使用颜色代码。 */
    public String colorize(CommandSender sender, String message) {
        if (sender.hasPermission(COLOR_PERMISSION)) {
            return message;
        }
        return message.replace('&', '＆').replace('§', '＆');
    }
}

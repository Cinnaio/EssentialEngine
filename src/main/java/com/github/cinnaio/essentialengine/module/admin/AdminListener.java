package com.github.cinnaio.essentialengine.module.admin;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.Text;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 登录封禁拦截与隐身状态恢复。
 */
public class AdminListener implements Listener {

    private final EssentialEngine plugin;

    public AdminListener(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        UserData data = plugin.users().getIfLoaded(event.getPlayer().getUniqueId());
        if (data == null || !data.isBanned()) {
            return;
        }
        String duration = data.getBanExpiry() <= 0
                ? "永久"
                : TimeUtil.formatDuration(data.getBanExpiry() - System.currentTimeMillis());
        String message = plugin.messages().raw("admin.ban-screen",
                "reason", data.getBanReason(),
                "operator", data.getBanSource(),
                "duration", duration);
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, Text.legacy(message));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());

        VanishHelper.refreshFor(plugin, player);

        if (data != null && data.isVanished()) {
            if (!player.hasPermission("essentialengine.command.vanish")) {
                data.setVanished(false);
                return;
            }
            VanishHelper.apply(plugin, player, true);
            event.joinMessage(null);
            plugin.messages().send(player, "admin.vanish-still-on");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        UserData data = plugin.users().getIfLoaded(event.getPlayer().getUniqueId());
        if (data != null && data.isVanished()) {
            event.quitMessage(null);
        }
    }
}

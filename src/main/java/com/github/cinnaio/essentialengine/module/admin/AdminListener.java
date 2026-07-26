package com.github.cinnaio.essentialengine.module.admin;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.user.UserData;
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
        Object duration = data.getBanExpiry() <= 0
                ? MessageManager.localized("general.permanent")
                : TimeUtil.duration(data.getBanExpiry() - System.currentTimeMillis());
        String reason = data.getBanReason();
        // 封禁画面按被拦玩家的客户端语言渲染
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                plugin.messages().get(event.getPlayer(), "admin.ban-screen",
                        "reason", reason == null || reason.isEmpty()
                                ? MessageManager.localized("admin.default-reason") : reason,
                        "operator", data.getBanSource(),
                        "duration", duration));
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

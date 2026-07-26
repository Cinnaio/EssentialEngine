package com.github.cinnaio.essentialengine.module.chat;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.Text;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 聊天拦截（禁言 / 屏蔽 / 格式化）与 AFK 活动检测。
 */
public class ChatListener implements Listener {

    private final EssentialEngine plugin;
    private final ChatManager manager;

    public ChatListener(EssentialEngine plugin, ChatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data == null) {
            return;
        }

        // 禁言拦截
        if (data.isMuted()) {
            event.setCancelled(true);
            plugin.messages().send(player, "admin.you-are-muted",
                    "reason", data.getMuteReason(),
                    "time", data.getMuteExpiry() <= 0 ? "永久"
                            : TimeUtil.formatDuration(data.getMuteExpiry() - System.currentTimeMillis()));
            return;
        }

        // 屏蔽名单：把屏蔽了发言者的玩家从接收者里剔除
        event.viewers().removeIf(audience -> {
            if (!(audience instanceof Player viewer)) {
                return false;
            }
            UserData viewerData = plugin.users().getIfLoaded(viewer.getUniqueId());
            return viewerData != null && viewerData.isIgnoring(player.getUniqueId());
        });

        // 自定义聊天格式
        if (plugin.getConfig().getBoolean("modules.chat.enable-chat-format", false)) {
            String format = plugin.getConfig().getString("modules.chat.chat-format", "&7{player}&8: &f{message}");
            event.renderer(new FormatRenderer(format));
        }

        manager.markActive(player);
    }

    /** 把配置里的 {message} 位置替换成真正的消息组件，保留原始颜色与点击事件。 */
    private static class FormatRenderer implements io.papermc.paper.chat.ChatRenderer {

        private final String format;

        FormatRenderer(String format) {
            this.format = format;
        }

        @Override
        public Component render(Player source, Component sourceDisplayName, Component message, Audience viewer) {
            String display = PlayerUtil.display(source);
            String[] parts = format.split("\\{message}", -1);
            Component result = Text.parse(Text.replace(parts[0],
                    "player", display, "name", source.getName(), "world", source.getWorld().getName()));
            result = result.append(message);
            for (int i = 1; i < parts.length; i++) {
                result = result.append(Text.parse(Text.replace(parts[i],
                        "player", display, "name", source.getName(), "world", source.getWorld().getName())));
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ AFK 活动检测

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        manager.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        manager.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        manager.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data != null && data.getNickname() != null) {
            player.displayName(Text.parse(data.getNickname()));
            player.playerListName(Text.parse(data.getNickname()));
        }
    }
}

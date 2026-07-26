package com.github.cinnaio.essentialengine.module.player;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 无敌模式拦截与飞行状态恢复。
 */
public class PlayerListener implements Listener {

    private final EssentialEngine plugin;

    public PlayerListener(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data != null && data.isGodMode()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data != null && data.isGodMode()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data == null || !data.isFlightEnabled()) {
            return;
        }
        if (!player.hasPermission("essentialengine.command.fly")) {
            data.setFlightEnabled(false);
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(true);
        }
    }
}

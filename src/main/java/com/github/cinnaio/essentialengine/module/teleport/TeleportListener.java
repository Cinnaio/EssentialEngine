package com.github.cinnaio.essentialengine.module.teleport;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 传送相关事件：移动 / 受伤打断吟唱、死亡记录回程点、退出清理状态。
 */
public class TeleportListener implements Listener {

    private final EssentialEngine plugin;
    private final TeleportManager manager;

    public TeleportListener(EssentialEngine plugin, TeleportManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("modules.teleport.cancel-on-move", true)) {
            return;
        }
        Player player = event.getPlayer();
        TeleportManager.Warmup warmup = manager.getWarmup(player.getUniqueId());
        if (warmup == null) {
            return;
        }
        Location to = event.getTo();
        Location origin = warmup.getOrigin();
        if (to == null || origin == null || to.getWorld() == null || origin.getWorld() == null) {
            return;
        }
        if (!to.getWorld().equals(origin.getWorld()) || to.distanceSquared(origin) > 1.0D) {
            manager.cancelWarmup(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getConfig().getBoolean("modules.teleport.cancel-on-damage", true)) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            manager.cancelWarmup(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("modules.teleport.back-on-death", true)) {
            return;
        }
        Player player = event.getEntity();
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data != null) {
            data.setLastLocation(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.clearPlayer(event.getPlayer().getUniqueId());
    }
}

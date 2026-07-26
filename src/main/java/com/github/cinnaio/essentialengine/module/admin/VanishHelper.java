package com.github.cinnaio.essentialengine.module.admin;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** 隐身状态的可见性同步。 */
public final class VanishHelper {

    private VanishHelper() {
    }

    /** 让所有没有 {@code essentialengine.vanish.see} 权限的玩家看不见 / 重新看见目标。 */
    public static void apply(EssentialEngine plugin, Player target, boolean vanished) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }
            if (vanished && !viewer.hasPermission(PlayerUtil.SEE_VANISHED)) {
                viewer.hidePlayer(plugin, target);
            } else {
                viewer.showPlayer(plugin, target);
            }
        }
    }

    /** 新玩家进服时，把当前所有隐身玩家对他隐藏。 */
    public static void refreshFor(EssentialEngine plugin, Player viewer) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer)) {
                continue;
            }
            UserData data = plugin.users().getIfLoaded(other.getUniqueId());
            if (data != null && data.isVanished() && !viewer.hasPermission(PlayerUtil.SEE_VANISHED)) {
                viewer.hidePlayer(plugin, other);
            }
        }
    }
}

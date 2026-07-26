package com.github.cinnaio.essentialengine.core;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/**
 * 玩家数据生命周期：登录前预载、进服记账、退出写盘。
 *
 * <p>预载放在 {@link AsyncPlayerPreLoginEvent}（本身就在异步线程），
 * 这样后面所有模块在主线程读数据都是内存操作，不会卡服。</p>
 */
public class CoreListener implements Listener {

    private final EssentialEngine plugin;

    public CoreListener(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UserData data = plugin.users().loadIntoCache(event.getUniqueId(), event.getName());
        data.setName(event.getName());
        data.setLastLogin(System.currentTimeMillis());
        if (data.getFirstJoin() <= 0) {
            data.setFirstJoin(System.currentTimeMillis());
        }
    }

    /**
     * 登录被拒绝（封禁、白名单、满员……）时，把刚才预载的数据清出缓存，
     * 否则反复尝试登录的玩家会一直堆在内存里。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            plugin.users().unload(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UserData data = plugin.users().get(player);
        data.setName(player.getName());
        data.startSession();
        data.setAfk(false);
    }

    /**
     * 记录客户端语言。设置在进服后由客户端主动同步（也随玩家改语言实时触发），
     * 存进玩家数据后，下次登录时的封禁画面等「设置还没同步」的场景就能用上。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        UserData data = plugin.users().getIfLoaded(event.getPlayer().getUniqueId());
        if (data != null) {
            data.setClientLocale(tagOf(event.locale()));
        }
    }

    private String tagOf(Locale locale) {
        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry();
        return country.isEmpty() ? lang : lang + "_" + country.toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UserData data = plugin.users().getIfLoaded(player.getUniqueId());
        if (data != null) {
            data.setLogoutLocation(player.getLocation());
        }
        plugin.users().unload(player.getUniqueId());
    }
}

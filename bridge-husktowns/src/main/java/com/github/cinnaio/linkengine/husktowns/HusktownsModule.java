package com.github.cinnaio.linkengine.husktowns;

import com.github.cinnaio.linkengine.core.http.Router;
import com.github.cinnaio.linkengine.core.module.EndpointModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * HuskTowns module - provides town management REST endpoints.
 * Requires the HuskTowns plugin to be installed on the server.
 */
public class HusktownsModule implements EndpointModule {

    private final Plugin plugin;
    private TownEndpoint endpoint;

    public HusktownsModule(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "husktowns";
    }

    @Override
    public boolean isAvailable() {
        // Check if HuskTowns plugin is installed
        return Bukkit.getPluginManager().getPlugin("HuskTowns") != null;
    }

    @Override
    public void registerRoutes(Router router) {
        endpoint = new TownEndpoint(plugin);
        endpoint.registerRoutes(router);
    }

    @Override
    public void onEnable() {
        plugin.getLogger().info("[HuskTowns] Module initialized - HuskTowns API hooked");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("[HuskTowns] Module disabled");
    }
}

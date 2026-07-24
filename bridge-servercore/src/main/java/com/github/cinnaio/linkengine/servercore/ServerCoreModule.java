package com.github.cinnaio.linkengine.servercore;

import com.github.cinnaio.linkengine.core.http.Router;
import com.github.cinnaio.linkengine.core.module.EndpointModule;
import org.bukkit.plugin.Plugin;

/**
 * ServerCore module - provides server status, player info, and command execution endpoints.
 * This module is always available as it only depends on the Bukkit API.
 */
public class ServerCoreModule implements EndpointModule {

    private final Plugin plugin;
    private ServerEndpoint endpoint;

    public ServerCoreModule(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "servercore";
    }

    @Override
    public boolean isAvailable() {
        // Always available - only needs Bukkit API
        return true;
    }

    @Override
    public void registerRoutes(Router router) {
        endpoint = new ServerEndpoint(plugin);
        endpoint.registerRoutes(router);
    }

    @Override
    public void onEnable() {
        plugin.getLogger().info("[ServerCore] Module initialized");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("[ServerCore] Module disabled");
    }
}

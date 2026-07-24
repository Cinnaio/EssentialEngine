package com.github.cinnaio.linkengine;

import com.github.cinnaio.linkengine.core.http.AuthMiddleware;
import com.github.cinnaio.linkengine.core.http.HttpServer;
import com.github.cinnaio.linkengine.core.http.Router;
import com.github.cinnaio.linkengine.core.module.ModuleRegistry;
import com.github.cinnaio.linkengine.husktowns.HusktownsModule;
import com.github.cinnaio.linkengine.servercore.ServerCoreModule;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.logging.Level;

/**
 * LinkEngine - Main plugin class.
 * Provides a modular REST API bridge between Minecraft server and external bots/services.
 * Compatible with Paper and Folia (1.21.4+).
 */
public class LinkEngine extends JavaPlugin {

    private HttpServer httpServer;
    private ModuleRegistry moduleRegistry;
    private Router router;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();

        // Read configuration
        String bindAddress = getConfig().getString("api.bind-address", "0.0.0.0");
        int port = getConfig().getInt("api.port", 8192);
        String apiKey = getConfig().getString("api.api-key", "change-me");

        if ("change-me-to-a-random-key".equals(apiKey) || "change-me".equals(apiKey)) {
            getLogger().warning("========================================");
            getLogger().warning("  Please set a secure API key in config.yml!");
            getLogger().warning("  Current key is the default and insecure.");
            getLogger().warning("========================================");
        }

        // Initialize core components
        router = new Router();
        AuthMiddleware auth = new AuthMiddleware(apiKey);
        moduleRegistry = new ModuleRegistry(getLogger());

        // Register modules
        registerModules();

        // Initialize modules (check availability, register routes)
        moduleRegistry.initialize(router);

        // Start HTTP server
        try {
            httpServer = new HttpServer(bindAddress, port, router, auth, getLogger());
            httpServer.startServer();
            getLogger().info("LinkEngine enabled successfully!");
            getLogger().info("Active modules: " + moduleRegistry.getActiveModuleNames());
            getLogger().info("API listening on " + bindAddress + ":" + port);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start HTTP server on port " + port, e);
            getLogger().severe("Please check if the port is available and config.yml is correct.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // Shutdown modules
        if (moduleRegistry != null) {
            moduleRegistry.shutdown();
        }
        // Stop HTTP server
        if (httpServer != null) {
            httpServer.stopServer();
        }
        getLogger().info("LinkEngine disabled.");
    }

    /**
     * Register all available endpoint modules.
     * Add new modules here as they are developed.
     */
    private void registerModules() {
        // ServerCore module - always available
        if (getConfig().getBoolean("modules.servercore.enabled", true)) {
            moduleRegistry.register(new ServerCoreModule(this));
        }

        // HuskTowns module - requires HuskTowns plugin
        if (getConfig().getBoolean("modules.husktowns.enabled", true)) {
            moduleRegistry.register(new HusktownsModule(this));
        }

        // Future modules can be registered here:
        // moduleRegistry.register(new LuckPermsModule(this));
        // moduleRegistry.register(new HuskHomesModule(this));
    }

    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    public Router getRouter() {
        return router;
    }
}

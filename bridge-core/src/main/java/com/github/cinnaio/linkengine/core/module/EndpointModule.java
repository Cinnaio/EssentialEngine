package com.github.cinnaio.linkengine.core.module;

import com.github.cinnaio.linkengine.core.http.Router;

/**
 * Interface for all bridge endpoint modules.
 * Each module provides a set of REST API endpoints for a specific MC plugin.
 */
public interface EndpointModule {

    /**
     * Module name, e.g. "servercore", "husktowns".
     */
    String getName();

    /**
     * Check if the required dependency plugin is available on the server.
     * If false, the module will be skipped during registration.
     */
    boolean isAvailable();

    /**
     * Register HTTP routes for this module.
     */
    void registerRoutes(Router router);

    /**
     * Called when the module is enabled.
     */
    default void onEnable() {}

    /**
     * Called when the module is disabled.
     */
    default void onDisable() {}
}

package com.github.cinnaio.linkengine.core.module;

import com.github.cinnaio.linkengine.core.http.Router;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages registration and lifecycle of endpoint modules.
 */
public class ModuleRegistry {

    private final List<EndpointModule> modules = new ArrayList<>();
    private final List<EndpointModule> activeModules = new ArrayList<>();
    private final Logger logger;

    public ModuleRegistry(Logger logger) {
        this.logger = logger;
    }

    /**
     * Register a module. Call this before initialize().
     */
    public void register(EndpointModule module) {
        modules.add(module);
    }

    /**
     * Initialize all registered modules: check availability, register routes, enable.
     */
    public void initialize(Router router) {
        for (EndpointModule module : modules) {
            try {
                if (!module.isAvailable()) {
                    logger.info("[LinkEngine] Module '" + module.getName()
                            + "' skipped: dependency not available");
                    continue;
                }
                module.registerRoutes(router);
                module.onEnable();
                activeModules.add(module);
                logger.info("[LinkEngine] Module '" + module.getName() + "' loaded successfully");
            } catch (Exception e) {
                logger.severe("[LinkEngine] Failed to load module '"
                        + module.getName() + "': " + e.getMessage());
            }
        }
    }

    /**
     * Disable all active modules.
     */
    public void shutdown() {
        for (EndpointModule module : activeModules) {
            try {
                module.onDisable();
            } catch (Exception e) {
                logger.severe("[LinkEngine] Error disabling module '"
                        + module.getName() + "': " + e.getMessage());
            }
        }
        activeModules.clear();
    }

    /**
     * Get list of active module names.
     */
    public List<String> getActiveModuleNames() {
        List<String> names = new ArrayList<>();
        for (EndpointModule m : activeModules) {
            names.add(m.getName());
        }
        return Collections.unmodifiableList(names);
    }

    public List<EndpointModule> getActiveModules() {
        return Collections.unmodifiableList(activeModules);
    }
}

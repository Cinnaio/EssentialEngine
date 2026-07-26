package com.github.cinnaio.essentialengine.core.module;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.EngineCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 模块生命周期管理：按配置启用、注册命令与监听器、关服时逆序卸载。
 */
public class ModuleManager {

    private final EssentialEngine plugin;
    private final Map<String, EngineModule> modules = new LinkedHashMap<>();
    private final List<EngineModule> active = new ArrayList<>();

    public ModuleManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void register(EngineModule module) {
        modules.put(module.getId(), module);
    }

    public EngineModule get(String id) {
        return modules.get(id);
    }

    public boolean isActive(String id) {
        EngineModule module = modules.get(id);
        return module != null && module.isEnabled();
    }

    public List<EngineModule> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    public List<EngineModule> getActive() {
        return Collections.unmodifiableList(active);
    }

    public List<String> getActiveIds() {
        List<String> ids = new ArrayList<>();
        for (EngineModule module : active) {
            ids.add(module.getId());
        }
        return ids;
    }

    /** 按配置启用全部模块。 */
    public void enableAll() {
        for (EngineModule module : modules.values()) {
            if (!plugin.getConfig().getBoolean("modules." + module.getId() + ".enabled", true)) {
                plugin.getLogger().info("模块 " + module.getId() + " 已在配置中关闭，跳过。");
                continue;
            }
            if (!module.isAvailable()) {
                plugin.getLogger().info("模块 " + module.getId() + " 跳过加载：" + module.getUnavailableReason());
                continue;
            }
            try {
                module.clearDeclarations();
                module.setup();
                int commandCount = registerCommands(module);
                registerListeners(module);
                module.setEnabled(true);
                active.add(module);
                plugin.getLogger().info("模块 " + module.getId() + " 已加载（命令 " + commandCount
                        + " 条，监听器 " + module.getListeners().size() + " 个）");
            } catch (Throwable error) {
                module.setEnabled(false);
                plugin.getLogger().log(Level.SEVERE, "模块 " + module.getId() + " 加载失败", error);
            }
        }
        plugin.commands().syncCommands();
    }

    private int registerCommands(EngineModule module) {
        int count = 0;
        for (EngineCommand.Builder builder : module.getCommandBuilders()) {
            String name = builder.getName();
            if (plugin.commands().isDisabled(name)) {
                plugin.getLogger().info("命令 /" + name + " 已在配置中禁用，跳过注册。");
                continue;
            }
            builder.addAliases(plugin.commands().extraAliases(name));
            plugin.commands().register(builder.build());
            count++;
        }
        return count;
    }

    private void registerListeners(EngineModule module) {
        for (Listener listener : module.getListeners()) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    /** 关服 / 重载时卸载全部模块。 */
    public void disableAll() {
        for (int i = active.size() - 1; i >= 0; i--) {
            EngineModule module = active.get(i);
            try {
                for (Listener listener : module.getListeners()) {
                    HandlerList.unregisterAll(listener);
                }
                module.shutdown();
            } catch (Throwable error) {
                plugin.getLogger().log(Level.WARNING, "模块 " + module.getId() + " 卸载时出错", error);
            }
            module.setEnabled(false);
        }
        active.clear();
        plugin.commands().unregisterAll();
    }
}

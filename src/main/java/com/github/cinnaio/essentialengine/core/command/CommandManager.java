package com.github.cinnaio.essentialengine.core.command;

import com.github.cinnaio.essentialengine.EssentialEngine;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 命令动态注册器。
 *
 * <p>通过反射拿到服务端的 {@link CommandMap} 来注册 / 注销命令，
 * 好处是模块可以在运行时开关，且不需要在 plugin.yml 里堆几十条命令定义。
 * Paper、Spigot、Folia 都可用。</p>
 *
 * <p>服主可以在 config.yml 里禁用某条命令，或者给它加自定义别名，
 * 用来避开与其它插件（例如原版 /tp、其它经济插件）的冲突。</p>
 */
public class CommandManager {

    private static final String FALLBACK_PREFIX = "essentialengine";

    private final EssentialEngine plugin;
    private final List<EngineCommand> registered = new ArrayList<>();
    private CommandMap commandMap;

    public CommandManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        this.commandMap = resolveCommandMap();
        if (commandMap == null) {
            plugin.getLogger().severe("无法获取服务端 CommandMap，命令注册失败。请确认服务端为 Paper / Spigot / Folia。");
            return false;
        }
        return true;
    }

    private CommandMap resolveCommandMap() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getCommandMap");
            method.setAccessible(true);
            return (CommandMap) method.invoke(Bukkit.getServer());
        } catch (Exception ignored) {
            // 继续尝试字段方式
        }
        try {
            Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            return (CommandMap) field.get(Bukkit.getServer());
        } catch (Exception error) {
            return null;
        }
    }

    /** 该命令是否被服主在 config.yml 里禁用。 */
    public boolean isDisabled(String name) {
        List<String> disabled = plugin.getConfig().getStringList("commands.disabled");
        for (String entry : disabled) {
            if (entry != null && entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** config.yml 中为该命令配置的额外别名。 */
    public List<String> extraAliases(String name) {
        return plugin.getConfig().getStringList("commands.aliases." + name.toLowerCase(Locale.ROOT));
    }

    public void register(EngineCommand command) {
        if (commandMap == null) {
            return;
        }
        boolean primaryLabel = commandMap.register(FALLBACK_PREFIX, command);
        registered.add(command);
        if (!primaryLabel) {
            // 命令名被其它插件占用了：本插件的版本仍然可用，只是要写全名
            plugin.getLogger().info("/" + command.getName() + " 已被其它插件占用，"
                    + "本插件的版本请使用 /" + FALLBACK_PREFIX + ":" + command.getName()
                    + "（或在 config.yml 的 commands.aliases 里另配别名）");
        }
    }

    public List<EngineCommand> getRegistered() {
        return registered;
    }

    /**
     * 把 config.yml 的 commands.override 名单里的命令强制夺回主命令名。
     *
     * <p>命令注册是「谁先谁占」：本插件的命令若被别的插件抢了名，就只能用
     * {@code /essentialengine:heal} 全名调用。这里在所有插件加载完成后
     * （由 {@code ServerLoadEvent} 触发）把名单里的命令重新指回本插件的版本，
     * 让玩家直接输入 {@code /heal}、{@code /god} 就走本插件。</p>
     */
    public void applyOverrides() {
        if (commandMap == null) {
            return;
        }
        List<String> override = plugin.getConfig().getStringList("commands.override");
        if (override.isEmpty()) {
            return;
        }
        Map<String, Command> known = knownCommands();
        if (known == null) {
            plugin.getLogger().warning("无法获取命令表，commands.override 未生效。");
            return;
        }
        boolean changed = false;
        for (String name : override) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            EngineCommand mine = findRegistered(name);
            if (mine == null) {
                // 命令没注册（模块关了 / 命令被 disabled），跳过即可，不必报错
                continue;
            }
            String label = name.toLowerCase(Locale.ROOT);
            Command current = known.get(label);
            if (current == mine) {
                continue;
            }
            known.put(label, mine);
            // 让本插件的别名前缀名也指回来，避免残留旧插件的映射
            known.put(FALLBACK_PREFIX + ":" + label, mine);
            changed = true;
            plugin.getLogger().info("/" + label + " 已被 commands.override 夺回，现在由本插件处理。");
        }
        if (changed) {
            syncCommands();
        }
    }

    private EngineCommand findRegistered(String name) {
        for (EngineCommand command : registered) {
            if (command.getName().equalsIgnoreCase(name)) {
                return command;
            }
        }
        return null;
    }

    /** 注销本插件注册过的全部命令。 */
    public void unregisterAll() {
        if (commandMap == null) {
            return;
        }
        Map<String, Command> known = knownCommands();
        for (EngineCommand command : registered) {
            command.unregister(commandMap);
            if (known != null) {
                Set<String> labels = new HashSet<>();
                labels.add(command.getName());
                labels.add(FALLBACK_PREFIX + ":" + command.getName());
                for (String alias : command.getAliases()) {
                    labels.add(alias);
                    labels.add(FALLBACK_PREFIX + ":" + alias);
                }
                for (String label : labels) {
                    Command current = known.get(label);
                    if (current == command) {
                        known.remove(label);
                    }
                }
            }
        }
        registered.clear();
        syncCommands();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands() {
        try {
            Method method = commandMap.getClass().getMethod("getKnownCommands");
            method.setAccessible(true);
            return (Map<String, Command>) method.invoke(commandMap);
        } catch (Exception ignored) {
        }
        try {
            Field field = commandMap.getClass().getDeclaredField("knownCommands");
            field.setAccessible(true);
            return (Map<String, Command>) field.get(commandMap);
        } catch (Exception error) {
            return null;
        }
    }

    /** 让在线玩家的客户端命令列表刷新（Paper 提供）。 */
    public void syncCommands() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.updateCommands();
            }
        } catch (Throwable ignored) {
            // 老版本或非 Paper 服务端没有这个方法，忽略即可
        }
    }
}

package com.github.cinnaio.essentialengine.core.module;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.EngineCommand;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * 功能模块基类。
 *
 * <p>所有功能（传送、聊天、经济……）都以模块形式存在于同一个插件里，
 * 通过 config.yml 的 {@code modules.<id>.enabled} 独立开关。
 * 模块只需要在 {@link #setup()} 里声明自己的命令和监听器，注册与注销由
 * {@link ModuleManager} 统一处理。</p>
 */
public abstract class EngineModule {

    protected final EssentialEngine plugin;
    private final String id;
    private final String displayName;

    private final List<EngineCommand.Builder> commandBuilders = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean enabled;

    protected EngineModule(EssentialEngine plugin, String id, String displayName) {
        this.plugin = plugin;
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** 依赖是否满足（例如 HuskTowns 模块需要 HuskTowns 插件）。 */
    public boolean isAvailable() {
        return true;
    }

    /** 依赖不满足时展示给服主的原因。 */
    public String getUnavailableReason() {
        return "前置插件未安装";
    }

    /** 声明命令与监听器。由 ModuleManager 在启用时调用。 */
    protected abstract void setup();

    /** 模块关闭时的清理工作。 */
    protected void shutdown() {
    }

    /** 声明一条命令。返回的构建器会在 setup() 结束后统一注册。 */
    protected EngineCommand.Builder command(String name) {
        EngineCommand.Builder builder = EngineCommand.builder(plugin, name);
        commandBuilders.add(builder);
        return builder;
    }

    /** 声明一个事件监听器。 */
    protected void listener(Listener listener) {
        listeners.add(listener);
    }

    List<EngineCommand.Builder> getCommandBuilders() {
        return commandBuilders;
    }

    List<Listener> getListeners() {
        return listeners;
    }

    void clearDeclarations() {
        commandBuilders.clear();
        listeners.clear();
    }

    // ---------------------------------------------------------------- 配置读取快捷方法

    /** 模块自己的配置路径前缀：{@code modules.<id>.} */
    protected String path(String key) {
        return "modules." + id + "." + key;
    }

    protected boolean cfgBool(String key, boolean fallback) {
        return plugin.getConfig().getBoolean(path(key), fallback);
    }

    protected int cfgInt(String key, int fallback) {
        return plugin.getConfig().getInt(path(key), fallback);
    }

    protected double cfgDouble(String key, double fallback) {
        return plugin.getConfig().getDouble(path(key), fallback);
    }

    protected String cfgString(String key, String fallback) {
        return plugin.getConfig().getString(path(key), fallback);
    }

    protected List<String> cfgList(String key) {
        return plugin.getConfig().getStringList(path(key));
    }
}

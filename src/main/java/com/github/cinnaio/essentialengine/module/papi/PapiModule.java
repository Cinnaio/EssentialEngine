package com.github.cinnaio.essentialengine.module.papi;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * PlaceholderAPI 对接模块。
 *
 * <p>未安装 PlaceholderAPI 时整个模块会被跳过，与之相关的类一个都不会加载。
 * 同一份变量实现按两个标识各注册一次：{@code %essentialengine_xxx%} 与
 * 更好写的 {@code %ee_xxx%}。</p>
 */
public class PapiModule extends EngineModule {

    private final List<Object> expansions = new ArrayList<>();
    private BalTopCache balTop;

    public PapiModule(EssentialEngine plugin) {
        super(plugin, "papi", "PlaceholderAPI 变量");
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @Override
    public String getUnavailableReason() {
        return "papi.not-installed";
    }

    @Override
    protected void setup() {
        this.balTop = new BalTopCache(plugin,
                cfgInt("baltop-size", 10),
                cfgInt("baltop-cache-seconds", 60));
        // 预热一次，避免服务器刚起来时排行榜占位符是空的
        balTop.refresh();

        register("essentialengine");
        if (cfgBool("short-alias", true)) {
            register("ee");
        }
    }

    private void register(String identifier) {
        try {
            EnginePlaceholders expansion = new EnginePlaceholders(plugin, identifier, balTop);
            if (expansion.register()) {
                expansions.add(expansion);
            } else {
                plugin.getLogger().warning("PlaceholderAPI 变量 %" + identifier + "_...% 注册失败。");
            }
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, "注册 PlaceholderAPI 变量 %" + identifier + "_...% 时出错", error);
        }
    }

    @Override
    protected void shutdown() {
        for (Object expansion : expansions) {
            try {
                ((EnginePlaceholders) expansion).unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI 已经卸载时忽略
            }
        }
        expansions.clear();
        balTop = null;
    }
}

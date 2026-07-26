package com.github.cinnaio.essentialengine.core;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 插件自身的管理命令：{@code /essentialengine}（别名 /ee、/ess）。
 */
public class CoreModule extends EngineModule {

    private static final String ADMIN = "essentialengine.admin";

    public CoreModule(EssentialEngine plugin) {
        super(plugin, "core", "核心");
    }

    @Override
    protected void setup() {
        command("essentialengine")
                .aliases("ee", "ess")
                .permission(ADMIN)
                .description("EssentialEngine 管理命令")
                .usage("/ee <reload|info|modules|save>")
                .handler(this::handle)
                .completer(this::complete);
    }

    private void handle(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                long start = System.currentTimeMillis();
                plugin.reloadAll();
                plugin.messages().send(sender, "core.reloaded",
                        "ms", String.valueOf(System.currentTimeMillis() - start));
            }
            case "save" -> {
                SchedulerCompat.runAsync(plugin, () -> {
                    plugin.users().flushDirty();
                    SchedulerCompat.runGlobal(plugin, () -> plugin.messages().send(sender, "core.saved"));
                });
            }
            case "modules" -> {
                plugin.messages().send(sender, "core.module-header");
                plugin.modules().getAll().forEach(module -> {
                    String state;
                    if (module.isEnabled()) {
                        state = "&a已启用";
                    } else if (!module.isAvailable()) {
                        state = "&7不可用（" + module.getUnavailableReason() + "）";
                    } else {
                        state = "&c已关闭";
                    }
                    plugin.messages().sendRaw(sender, "&8 - &f{name} &7({id}) {state}",
                            "name", module.getDisplayName(), "id", module.getId(), "state", state);
                });
            }
            default -> {
                plugin.messages().send(sender, "core.info-header",
                        "version", plugin.getDescription().getVersion());
                plugin.messages().sendRaw(sender, "&8 - &7存储后端: &f{value}",
                        "value", plugin.storage().getName());
                plugin.messages().sendRaw(sender, "&8 - &7服务端: &f{value}",
                        "value", Bukkit.getVersion() + (SchedulerCompat.isFolia() ? " &b(Folia)" : ""));
                plugin.messages().sendRaw(sender, "&8 - &7已启用模块: &f{value}",
                        "value", String.join(", ", plugin.modules().getActiveIds()));
                plugin.messages().sendRaw(sender, "&8 - &7已注册命令: &f{value}",
                        "value", String.valueOf(plugin.commands().getRegistered().size()));
                plugin.messages().sendRaw(sender, "&8 - &7缓存玩家: &f{value}",
                        "value", String.valueOf(plugin.users().getCached().size()));
            }
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return new ArrayList<>(List.of("reload", "info", "modules", "save"));
        }
        return List.of();
    }

    /** 便于其它地方复用的颜色化输出。 */
    public static String colored(String raw) {
        return Text.legacy(raw);
    }
}

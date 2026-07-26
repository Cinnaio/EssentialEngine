package com.github.cinnaio.essentialengine.core;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
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
                    Object state;
                    if (module.isEnabled()) {
                        state = MessageManager.localized("core.module-state-on");
                    } else if (!module.isAvailable()) {
                        // getUnavailableReason 可返回语言键或字面文本，两者都兼容
                        state = MessageManager.localized("core.module-state-unavailable", "reason",
                                MessageManager.localizedOr(module.getUnavailableReason(),
                                        module.getUnavailableReason()));
                    } else {
                        state = MessageManager.localized("core.module-state-off");
                    }
                    plugin.messages().send(sender, "core.module-entry",
                            "name", MessageManager.localizedOr(
                                    "core.module-name-" + module.getId(), module.getDisplayName()),
                            "id", module.getId(), "state", state);
                });
            }
            default -> {
                plugin.messages().send(sender, "core.info-header",
                        "version", plugin.getDescription().getVersion());
                plugin.messages().send(sender, "core.info-storage",
                        "value", plugin.storage().getName());
                plugin.messages().send(sender, "core.info-server",
                        "value", Bukkit.getVersion() + (SchedulerCompat.isFolia() ? " (Folia)" : ""));
                plugin.messages().send(sender, "core.info-modules",
                        "value", String.join(", ", plugin.modules().getActiveIds()));
                plugin.messages().send(sender, "core.info-commands",
                        "value", String.valueOf(plugin.commands().getRegistered().size()));
                plugin.messages().send(sender, "core.info-players",
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

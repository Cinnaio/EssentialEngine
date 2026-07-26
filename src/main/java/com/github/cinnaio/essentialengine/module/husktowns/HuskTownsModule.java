package com.github.cinnaio.essentialengine.module.husktowns;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HuskTowns 对接模块。
 *
 * <p>原来这是一个独立的 Gradle 子项目，现在收编成插件内的一个普通模块：
 * 未安装 HuskTowns 时自动跳过，不影响其它功能。</p>
 */
public class HuskTownsModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";
    private TownService service;

    public HuskTownsModule(EssentialEngine plugin) {
        super(plugin, "husktowns", "HuskTowns 对接");
    }

    public TownService getService() {
        return service;
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("HuskTowns") != null;
    }

    @Override
    public String getUnavailableReason() {
        return "服务器未安装 HuskTowns";
    }

    @Override
    protected void setup() {
        this.service = new TownService();
        service.initialize();

        command("eetown").aliases("towninfo").permission(PERM + "town")
                .description("查看城镇信息").usage("/eetown <list|info> [名称]")
                .handler(this::town)
                .completer(this::complete);
    }

    private void town(CommandSender sender, String label, String[] args) {
        if (!service.isReady()) {
            throw new CommandError("husktowns.not-ready");
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            List<String> names = service.getTownNames();
            if (names.isEmpty()) {
                plugin.messages().send(sender, "husktowns.no-towns");
                return;
            }
            plugin.messages().send(sender, "husktowns.town-list",
                    "count", String.valueOf(names.size()),
                    "towns", String.join("&7, &f", names));
            return;
        }
        if (args.length < 2) {
            throw new CommandError("general.usage", "usage", "/eetown info <名称>");
        }
        Map<String, Object> town = service.getTown(args[1]);
        if (town == null) {
            throw new CommandError("husktowns.town-not-found", "name", args[1]);
        }
        plugin.messages().send(sender, "husktowns.town-header", "name", String.valueOf(town.get("name")));
        line(sender, "等级", String.valueOf(town.get("level")));
        line(sender, "成员数", String.valueOf(town.get("memberCount")));
        line(sender, "金库", String.valueOf(town.get("money")));
        line(sender, "欢迎语", String.valueOf(town.get("greeting")));
    }

    private void line(CommandSender sender, String key, String value) {
        plugin.messages().sendRaw(sender, "&8 - &7{key}: &f{value}", "key", key, "value", value);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return new ArrayList<>(List.of("list", "info"));
        }
        return service != null && service.isReady() ? service.getTownNames() : List.of();
    }
}

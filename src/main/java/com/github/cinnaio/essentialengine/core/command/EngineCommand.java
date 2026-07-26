package com.github.cinnaio.essentialengine.core.command;

import com.github.cinnaio.essentialengine.EssentialEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * 插件内所有命令的统一实现。
 *
 * <p>命令不写进 plugin.yml，而是在模块启用时动态注册到服务端 CommandMap，
 * 这样关掉某个模块，它的命令就真的不存在（不会占用命令名、也不会出现在 Tab 补全里）。</p>
 *
 * <p>用法：</p>
 * <pre>
 * command("home").aliases("h")
 *     .permission("essentialengine.command.home")
 *     .playerOnly()
 *     .usage("/home [名称]")
 *     .handler((sender, label, args) -&gt; { ... })
 *     .completer((sender, args) -&gt; ...);
 * </pre>
 */
public class EngineCommand extends Command implements PluginIdentifiableCommand {

    @FunctionalInterface
    public interface Handler {
        void handle(CommandSender sender, String label, String[] args);
    }

    @FunctionalInterface
    public interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    private final EssentialEngine plugin;
    private final String permissionNode;
    private final boolean playerOnly;
    private final int minArgs;
    private final String usageText;
    private final Handler handler;
    private final Completer completer;

    private EngineCommand(Builder builder) {
        super(builder.name);
        this.plugin = builder.plugin;
        this.permissionNode = builder.permission;
        this.playerOnly = builder.playerOnly;
        this.minArgs = builder.minArgs;
        this.usageText = builder.usage == null ? "/" + builder.name : builder.usage;
        this.handler = builder.handler;
        this.completer = builder.completer;

        setAliases(new ArrayList<>(builder.aliases));
        setDescription(builder.description == null ? "" : builder.description);
        setUsage(this.usageText);
    }

    public static Builder builder(EssentialEngine plugin, String name) {
        return new Builder(plugin, name);
    }

    public String getPermissionNode() {
        return permissionNode;
    }

    @Override
    public Plugin getPlugin() {
        return plugin;
    }

    @Override
    public boolean testPermissionSilent(CommandSender target) {
        return permissionNode == null || target.hasPermission(permissionNode);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        try {
            if (playerOnly && !(sender instanceof Player)) {
                throw new CommandError("general.player-only");
            }
            if (permissionNode != null && !sender.hasPermission(permissionNode)) {
                throw new CommandError("general.no-permission", "permission", permissionNode);
            }
            if (args.length < minArgs) {
                plugin.messages().send(sender, "general.usage", "usage", usageText);
                return true;
            }
            handler.handle(sender, label, args);
        } catch (CommandError error) {
            plugin.messages().send(sender, error.getKey(), error.getPlaceholders());
        } catch (NumberFormatException error) {
            plugin.messages().send(sender, "general.invalid-number", "input", String.valueOf(error.getMessage()));
        } catch (Exception error) {
            plugin.messages().send(sender, "general.internal-error");
            plugin.getLogger().log(Level.WARNING, "执行命令 /" + getName() + " 时出错", error);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (completer == null) {
            return Collections.emptyList();
        }
        if (permissionNode != null && !sender.hasPermission(permissionNode)) {
            return Collections.emptyList();
        }
        try {
            List<String> raw = completer.complete(sender, args);
            if (raw == null || raw.isEmpty()) {
                return Collections.emptyList();
            }
            String current = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String option : raw) {
                if (option != null && option.toLowerCase(Locale.ROOT).startsWith(current)) {
                    result.add(option);
                }
            }
            Collections.sort(result);
            return result;
        } catch (Exception error) {
            return Collections.emptyList();
        }
    }

    /** 命令构建器。 */
    public static class Builder {

        private final EssentialEngine plugin;
        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private String permission;
        private boolean playerOnly;
        private int minArgs;
        private String usage;
        private String description;
        private Handler handler = (sender, label, args) -> {
        };
        private Completer completer;

        private Builder(EssentialEngine plugin, String name) {
            this.plugin = plugin;
            this.name = name.toLowerCase(Locale.ROOT);
        }

        public String getName() {
            return name;
        }

        public Builder aliases(String... values) {
            aliases.addAll(Arrays.asList(values));
            return this;
        }

        public Builder addAliases(List<String> values) {
            if (values != null) {
                aliases.addAll(values);
            }
            return this;
        }

        public Builder permission(String node) {
            this.permission = node;
            return this;
        }

        public Builder playerOnly() {
            this.playerOnly = true;
            return this;
        }

        public Builder minArgs(int value) {
            this.minArgs = value;
            return this;
        }

        public Builder usage(String value) {
            this.usage = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder handler(Handler value) {
            this.handler = value;
            return this;
        }

        public Builder completer(Completer value) {
            this.completer = value;
            return this;
        }

        public EngineCommand build() {
            return new EngineCommand(this);
        }
    }
}

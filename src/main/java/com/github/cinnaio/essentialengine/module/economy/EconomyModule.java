package com.github.cinnaio.essentialengine.module.economy;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 经济与套装模块。
 */
public class EconomyModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";

    private EconomyManager economy;
    private KitManager kits;
    /** /pay 冷却，仅运行期有效，重启即清空。 */
    private final Map<UUID, Long> payCooldowns = new ConcurrentHashMap<>();

    public EconomyModule(EssentialEngine plugin) {
        super(plugin, "economy", "经济与套装");
    }

    public EconomyManager getEconomy() {
        return economy;
    }

    public KitManager getKits() {
        return kits;
    }

    @Override
    protected void setup() {
        // 经济管理器在插件 onLoad 阶段就已创建（Vault 注册也在那时完成），
        // 这里直接复用，避免 /ee reload 时把已经注册出去的服务换成另一个实例。
        this.economy = plugin.economy();
        this.kits = new KitManager(plugin);
        kits.reload();
        listener(new StartingBalanceListener());

        command("balance").aliases("bal", "money").permission(PERM + "balance")
                .description("查看余额").usage("/balance [玩家]").handler(this::balance)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("pay").permission(PERM + "pay").playerOnly()
                .description("转账给其他玩家").usage("/pay <玩家> <金额>").minArgs(2)
                .handler(this::pay)
                .completer((sender, args) -> args.length <= 1 ? PlayerUtil.visibleNames(sender) : List.of());

        command("eco").aliases("economy").permission(PERM + "eco")
                .description("管理玩家余额").usage("/eco <give|take|set|reset> <玩家> [金额]").minArgs(2)
                .handler(this::eco)
                .completer((sender, args) -> args.length <= 1
                        ? List.of("give", "take", "set", "reset") : PlayerUtil.visibleNames(sender));

        command("baltop").aliases("balancetop", "moneytop").permission(PERM + "baltop")
                .description("余额排行榜").usage("/baltop [数量]").handler(this::balTop);

        command("kit").aliases("kits").permission(PERM + "kit")
                .description("领取套装").usage("/kit [名称|list|create|delete]").handler(this::kit)
                .completer(this::kitComplete);
    }

    // Vault 注册跟随插件生命周期（onLoad 注册、onDisable 注销），
    // 不随模块开关变动，因此这里不需要 shutdown()。

    // ------------------------------------------------------------------ 余额

    private void balance(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            Player player = PlayerUtil.requirePlayer(sender);
            UserData data = plugin.users().get(player);
            plugin.messages().send(sender, "economy.balance-self",
                    "balance", economy.format(data.getBalance()));
            return;
        }
        if (!sender.hasPermission(PERM + "balance.others")) {
            throw new CommandError("general.no-permission", "permission", PERM + "balance.others");
        }
        plugin.users().lookup(sender, args[0], data ->
                plugin.messages().send(sender, "economy.balance-other",
                        "player", data.getName(), "balance", economy.format(data.getBalance())));
    }

    private void pay(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        double amount = EconomyManager.round(Double.parseDouble(args[1]));
        if (amount <= 0) {
            throw new CommandError("economy.amount-positive");
        }
        double minimum = cfgDouble("minimum-pay", 0.01D);
        if (amount < minimum) {
            throw new CommandError("economy.amount-too-small", "min", economy.format(minimum));
        }
        double maximum = cfgDouble("maximum-pay", 0D);
        if (maximum > 0 && amount > maximum) {
            throw new CommandError("economy.amount-too-large", "max", economy.format(maximum));
        }
        int cooldown = cfgInt("pay-cooldown-seconds", 0);
        if (cooldown > 0) {
            Long until = payCooldowns.get(player.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) {
                throw new CommandError("economy.pay-cooldown",
                        "time", TimeUtil.duration(until - System.currentTimeMillis()));
            }
        }
        UserData self = plugin.users().get(player);
        if (self.getBalance() < amount) {
            throw new CommandError("economy.not-enough", "balance", economy.format(self.getBalance()));
        }

        plugin.users().lookup(sender, args[0], target -> {
            if (target.getUuid().equals(player.getUniqueId())) {
                plugin.messages().send(sender, "economy.pay-self");
                return;
            }
            if (self.getBalance() < amount) {
                plugin.messages().send(sender, "economy.not-enough",
                        "balance", economy.format(self.getBalance()));
                return;
            }
            double maxBalance = cfgDouble("max-balance", 0D);
            if (maxBalance > 0 && target.getBalance() + amount > maxBalance) {
                plugin.messages().send(sender, "economy.target-balance-full",
                        "player", target.getName(), "max", economy.format(maxBalance));
                return;
            }
            self.setBalance(self.getBalance() - amount);
            target.setBalance(target.getBalance() + amount);
            plugin.users().saveAsync(self);
            plugin.users().saveAsync(target);
            if (cooldown > 0) {
                payCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
            }

            plugin.messages().send(sender, "economy.pay-sent",
                    "amount", economy.format(amount), "player", target.getName());
            Player online = Bukkit.getPlayer(target.getUuid());
            if (online != null) {
                plugin.messages().send(online, "economy.pay-received",
                        "amount", economy.format(amount), "player", player.getName());
            }
        });
    }

    private void eco(CommandSender sender, String label, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        double amount = 0;
        if (!action.equals("reset")) {
            if (args.length < 3) {
                throw new CommandError("general.usage", "usage",
                        MessageManager.localizedOr("usage.eco", "/eco <give|take|set|reset> <player> [amount]"));
            }
            amount = EconomyManager.round(Double.parseDouble(args[2]));
            if (amount < 0) {
                throw new CommandError("economy.amount-positive");
            }
        }
        final double value = amount;

        plugin.users().lookup(sender, args[1], data -> {
            switch (action) {
                case "give", "add" -> data.setBalance(data.getBalance() + value);
                case "take", "remove" -> data.setBalance(Math.max(0, data.getBalance() - value));
                case "set" -> data.setBalance(value);
                case "reset" -> data.setBalance(economy.startingBalance());
                default -> {
                    plugin.messages().send(sender, "general.usage", "usage",
                            MessageManager.localizedOr("usage.eco", "/eco <give|take|set|reset> <player> [amount]"));
                    return;
                }
            }
            plugin.users().saveAsync(data);
            plugin.messages().send(sender, "economy.eco-done",
                    "player", data.getName(), "balance", economy.format(data.getBalance()));
            Player online = Bukkit.getPlayer(data.getUuid());
            if (online != null && online != sender) {
                plugin.messages().send(online, "economy.balance-changed",
                        "balance", economy.format(data.getBalance()));
            }
        });
    }

    private void balTop(CommandSender sender, String label, String[] args) {
        int limit = cfgInt("baltop-default-size", 10);
        if (args.length > 0) {
            try {
                limit = Math.max(1, Math.min(cfgInt("baltop-max-size", 50), Integer.parseInt(args[0])));
            } catch (NumberFormatException ignored) {
            }
        }
        final int size = limit;
        SchedulerCompat.runAsync(plugin, () -> {
            LinkedHashMap<String, Double> top;
            try {
                top = plugin.storage().topBalances(size);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "读取余额排行榜失败", error);
                top = new LinkedHashMap<>();
            }
            LinkedHashMap<String, Double> result = top;
            SchedulerCompat.runGlobal(plugin, () -> {
                if (result.isEmpty()) {
                    plugin.messages().send(sender, "economy.baltop-empty");
                    return;
                }
                plugin.messages().send(sender, "economy.baltop-header", "count", String.valueOf(result.size()));
                int rank = 1;
                for (var entry : result.entrySet()) {
                    plugin.messages().send(sender, "economy.baltop-entry",
                            "rank", String.valueOf(rank++),
                            "player", entry.getKey(),
                            "balance", economy.format(entry.getValue()));
                }
            });
        });
    }

    // ------------------------------------------------------------------ 套装

    private void kit(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<String> available = new ArrayList<>();
            for (String name : kits.kitNames()) {
                if (sender.hasPermission(kits.permissionOf(name))) {
                    available.add(name);
                }
            }
            if (available.isEmpty()) {
                plugin.messages().send(sender, "economy.kit-none");
                return;
            }
            plugin.messages().send(sender, "economy.kit-list",
                    "count", String.valueOf(available.size()),
                    "kits", String.join("&#5C6370, &#E8EAED", available));
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("create") || action.equals("save")) {
            Player player = PlayerUtil.requirePlayer(sender);
            if (!sender.hasPermission(PERM + "kit.admin")) {
                throw new CommandError("general.no-permission", "permission", PERM + "kit.admin");
            }
            if (args.length < 2) {
                throw new CommandError("general.usage", "usage",
                        MessageManager.localizedOr("usage.kit-create", "/kit create <name> [cooldown seconds]"));
            }
            long cooldown = args.length > 2 ? Long.parseLong(args[2]) : 0L;
            kits.createFromInventory(player, args[1], cooldown);
            plugin.messages().send(sender, "economy.kit-created", "name", args[1]);
            return;
        }
        if (action.equals("delete") || action.equals("remove")) {
            if (!sender.hasPermission(PERM + "kit.admin")) {
                throw new CommandError("general.no-permission", "permission", PERM + "kit.admin");
            }
            if (args.length < 2) {
                throw new CommandError("general.usage", "usage",
                        MessageManager.localizedOr("usage.kit-delete", "/kit delete <name>"));
            }
            if (!kits.delete(args[1])) {
                throw new CommandError("economy.kit-not-found", "name", args[1]);
            }
            plugin.messages().send(sender, "economy.kit-deleted", "name", args[1]);
            return;
        }
        if (action.equals("reload")) {
            if (!sender.hasPermission(PERM + "kit.admin")) {
                throw new CommandError("general.no-permission", "permission", PERM + "kit.admin");
            }
            kits.reload();
            plugin.messages().send(sender, "economy.kit-reloaded");
            return;
        }

        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        kits.give(player, data, args[0]);
        plugin.messages().send(sender, "economy.kit-given", "name", args[0].toLowerCase(Locale.ROOT));
    }

    private List<String> kitComplete(CommandSender sender, String[] args) {
        if (args.length > 1) {
            return new ArrayList<>(kits.kitNames());
        }
        List<String> options = new ArrayList<>(kits.kitNames());
        options.add("list");
        if (sender.hasPermission(PERM + "kit.admin")) {
            options.add("create");
            options.add("delete");
            options.add("reload");
        }
        return options;
    }

    // ------------------------------------------------------------------ 新玩家初始余额

    private class StartingBalanceListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            UserData data = plugin.users().getIfLoaded(event.getPlayer().getUniqueId());
            if (data == null) {
                return;
            }
            double starting = economy.startingBalance();
            if (starting <= 0 || data.getBalance() > 0) {
                return;
            }
            // 只给刚创建档案的新玩家发初始资金。宽限期太短的话，
            // 服务器卡顿或档案被别的功能提前建好，新人就永远拿不到这笔钱。
            long grace = Math.max(1, cfgInt("starting-balance-grace-seconds", 300)) * 1000L;
            if (System.currentTimeMillis() - data.getFirstJoin() < grace) {
                data.setBalance(starting);
                plugin.messages().send(event.getPlayer(), "economy.starting-balance",
                        "balance", economy.format(starting));
            }
        }
    }
}

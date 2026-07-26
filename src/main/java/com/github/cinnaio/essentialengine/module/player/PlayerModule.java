package com.github.cinnaio.essentialengine.module.player;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.LocationUtil;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 玩家实用指令模块。
 */
public class PlayerModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";

    public PlayerModule(EssentialEngine plugin) {
        super(plugin, "player", "玩家指令");
    }

    @Override
    protected void setup() {
        listener(new PlayerListener(plugin));

        command("heal").permission(PERM + "heal").description("恢复生命与饱食度")
                .usage("/heal [玩家]").handler(this::heal)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("feed").aliases("eat").permission(PERM + "feed").description("恢复饱食度")
                .usage("/feed [玩家]").handler(this::feed)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("fly").permission(PERM + "fly").description("切换飞行")
                .usage("/fly [玩家]").handler(this::fly)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("god").aliases("godmode").permission(PERM + "god").description("切换无敌")
                .usage("/god [玩家]").handler(this::god)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("speed").permission(PERM + "speed").description("设置移动 / 飞行速度")
                .usage("/speed [walk|fly] <0-10> [玩家]").minArgs(1).handler(this::speed)
                .completer((sender, args) -> args.length <= 1 ? List.of("walk", "fly", "1", "2", "5") : List.of());

        command("gamemode").aliases("gm").permission(PERM + "gamemode").description("切换游戏模式")
                .usage("/gamemode <模式> [玩家]").minArgs(1)
                .handler((sender, label, args) -> gamemode(sender, parseMode(args[0]),
                        args.length > 1 ? args[1] : null))
                .completer((sender, args) -> args.length <= 1
                        ? List.of("survival", "creative", "adventure", "spectator")
                        : PlayerUtil.visibleNames(sender));

        registerModeShortcut("gms", GameMode.SURVIVAL, "生存");
        registerModeShortcut("gmc", GameMode.CREATIVE, "创造");
        registerModeShortcut("gma", GameMode.ADVENTURE, "冒险");
        registerModeShortcut("gmsp", GameMode.SPECTATOR, "旁观");

        command("repair").aliases("fix").playerOnly().permission(PERM + "repair")
                .description("修复手持物品").usage("/repair [all]").handler(this::repair)
                .completer((sender, args) -> List.of("all"));

        command("hat").aliases("head").playerOnly().permission(PERM + "hat")
                .description("把手持物品戴到头上").usage("/hat").handler(this::hat);

        command("workbench").aliases("wb", "craft").playerOnly().permission(PERM + "workbench")
                .description("打开工作台").usage("/workbench").handler(this::workbench);

        command("enderchest").aliases("ec", "echest").playerOnly().permission(PERM + "enderchest")
                .description("打开末影箱").usage("/enderchest [玩家]").handler(this::enderChest)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("top").aliases("jump").playerOnly().permission(PERM + "top")
                .description("传送到当前位置的最高点").usage("/top").handler(this::top);

        command("suicide").aliases("kill-self").playerOnly().permission(PERM + "suicide")
                .description("自杀").usage("/suicide").handler(this::suicide);

        command("near").aliases("nearby").playerOnly().permission(PERM + "near")
                .description("查看附近的玩家").usage("/near [半径]").handler(this::near);

        command("ping").permission(PERM + "ping").description("查看延迟")
                .usage("/ping [玩家]").handler(this::ping)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("playtime").aliases("onlinetime", "ptime").permission(PERM + "playtime")
                .description("查看在线时长").usage("/playtime [玩家]").handler(this::playtime)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));
    }

    private void registerModeShortcut(String name, GameMode mode, String label) {
        command(name).permission(PERM + "gamemode").description("切换为" + label + "模式")
                .usage("/" + name + " [玩家]")
                .handler((sender, cmdLabel, args) -> gamemode(sender, mode, args.length > 0 ? args[0] : null))
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));
    }

    // ------------------------------------------------------------------ 目标解析

    /** 无参数时作用于自己，有参数时需要 .others 权限。 */
    private Player resolveTarget(CommandSender sender, String name, String permissionBase) {
        if (name == null) {
            return PlayerUtil.requirePlayer(sender);
        }
        if (!sender.hasPermission(permissionBase + ".others")) {
            throw new CommandError("general.no-permission", "permission", permissionBase + ".others");
        }
        return PlayerUtil.requireOnline(sender, name);
    }

    private void notifyOther(CommandSender sender, Player target, String key, Object... placeholders) {
        if (!target.equals(sender)) {
            Object[] merged = new Object[placeholders.length + 2];
            System.arraycopy(placeholders, 0, merged, 0, placeholders.length);
            merged[placeholders.length] = "player";
            merged[placeholders.length + 1] = target.getName();
            plugin.messages().send(sender, key + "-other", merged);
        }
    }

    // ------------------------------------------------------------------ 命令实现

    private void heal(CommandSender sender, String label, String[] args) {
        Player target = resolveTarget(sender, args.length > 0 ? args[0] : null, PERM + "heal");
        SchedulerCompat.runForEntity(plugin, target, () -> {
            target.setHealth(target.getMaxHealth());
            target.setFoodLevel(20);
            target.setSaturation(20F);
            target.setFireTicks(0);
        });
        plugin.messages().send(target, "player.healed");
        notifyOther(sender, target, "player.healed");
    }

    private void feed(CommandSender sender, String label, String[] args) {
        Player target = resolveTarget(sender, args.length > 0 ? args[0] : null, PERM + "feed");
        SchedulerCompat.runForEntity(plugin, target, () -> {
            target.setFoodLevel(20);
            target.setSaturation(20F);
        });
        plugin.messages().send(target, "player.fed");
        notifyOther(sender, target, "player.fed");
    }

    private void fly(CommandSender sender, String label, String[] args) {
        Player target = resolveTarget(sender, args.length > 0 ? args[0] : null, PERM + "fly");
        UserData data = plugin.users().get(target);
        boolean enable = !target.getAllowFlight();
        target.setAllowFlight(enable);
        target.setFlying(enable && target.isFlying());
        data.setFlightEnabled(enable);
        plugin.messages().send(target, enable ? "player.fly-on" : "player.fly-off");
        notifyOther(sender, target, enable ? "player.fly-on" : "player.fly-off");
    }

    private void god(CommandSender sender, String label, String[] args) {
        Player target = resolveTarget(sender, args.length > 0 ? args[0] : null, PERM + "god");
        UserData data = plugin.users().get(target);
        boolean enable = !data.isGodMode();
        data.setGodMode(enable);
        plugin.messages().send(target, enable ? "player.god-on" : "player.god-off");
        notifyOther(sender, target, enable ? "player.god-on" : "player.god-off");
    }

    private void speed(CommandSender sender, String label, String[] args) {
        int index = 0;
        String type = null;
        if (args[0].equalsIgnoreCase("walk") || args[0].equalsIgnoreCase("fly")) {
            type = args[0].toLowerCase(Locale.ROOT);
            index = 1;
        }
        if (args.length <= index) {
            throw new CommandError("general.usage", "usage",
                    MessageManager.localizedOr("usage.speed", "/speed [walk|fly] <0-10> [player]"));
        }
        float value = Float.parseFloat(args[index]);
        if (value < 0 || value > 10) {
            throw new CommandError("player.speed-range");
        }
        Player target = resolveTarget(sender, args.length > index + 1 ? args[index + 1] : null, PERM + "speed");
        boolean flying = type == null ? target.isFlying() || target.getAllowFlight() : type.equals("fly");
        float normalized = Math.max(0F, Math.min(1F, value / 10F));
        if (flying) {
            target.setFlySpeed(normalized);
        } else {
            target.setWalkSpeed(normalized);
        }
        Object typeName = MessageManager.localized(flying ? "player.speed-type-fly" : "player.speed-type-walk");
        plugin.messages().send(target, "player.speed-set",
                "type", typeName, "value", String.valueOf(value));
        notifyOther(sender, target, "player.speed-set",
                "type", typeName, "value", String.valueOf(value));
    }

    private GameMode parseMode(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival", "生存" -> GameMode.SURVIVAL;
            case "1", "c", "creative", "创造" -> GameMode.CREATIVE;
            case "2", "a", "adventure", "冒险" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator", "旁观" -> GameMode.SPECTATOR;
            default -> throw new CommandError("player.gamemode-unknown", "mode", raw);
        };
    }

    private void gamemode(CommandSender sender, GameMode mode, String targetName) {
        Player target = resolveTarget(sender, targetName, PERM + "gamemode");
        SchedulerCompat.runForEntity(plugin, target, () -> target.setGameMode(mode));
        plugin.messages().send(target, "player.gamemode-set", "mode", modeName(mode));
        notifyOther(sender, target, "player.gamemode-set", "mode", modeName(mode));
    }

    private Object modeName(GameMode mode) {
        return MessageManager.localized("player.gamemode-" + mode.name().toLowerCase(Locale.ROOT));
    }

    private void repair(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
        if (all && !sender.hasPermission(PERM + "repair.all")) {
            throw new CommandError("general.no-permission", "permission", PERM + "repair.all");
        }
        int repaired = 0;
        if (all) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (repairItem(item)) {
                    repaired++;
                }
            }
            for (ItemStack item : player.getInventory().getArmorContents()) {
                if (repairItem(item)) {
                    repaired++;
                }
            }
        } else if (repairItem(player.getInventory().getItemInMainHand())) {
            repaired = 1;
        }
        if (repaired == 0) {
            throw new CommandError("player.repair-nothing");
        }
        plugin.messages().send(sender, "player.repaired", "count", String.valueOf(repaired));
    }

    private boolean repairItem(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getType().getMaxDurability() <= 0) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable && damageable.hasDamage()) {
            damageable.setDamage(0);
            item.setItemMeta(meta);
            return true;
        }
        return false;
    }

    private void hat(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (hand.getType().isAir()) {
            throw new CommandError("player.hat-empty");
        }
        ItemStack helmet = inventory.getHelmet();
        inventory.setHelmet(hand.clone());
        inventory.setItemInMainHand(helmet == null ? new ItemStack(org.bukkit.Material.AIR) : helmet);
        plugin.messages().send(sender, "player.hat-set");
    }

    private void workbench(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        player.openWorkbench(null, true);
    }

    private void enderChest(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        if (args.length == 0) {
            player.openInventory(player.getEnderChest());
            return;
        }
        if (!sender.hasPermission(PERM + "enderchest.others")) {
            throw new CommandError("general.no-permission", "permission", PERM + "enderchest.others");
        }
        Player target = PlayerUtil.requireOnline(sender, args[0]);
        player.openInventory(target.getEnderChest());
        plugin.messages().send(sender, "player.enderchest-opened", "player", target.getName());
    }

    private void top(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        Location origin = player.getLocation();
        SchedulerCompat.runForRegion(plugin, origin, () -> {
            Location target = LocationUtil.highestAt(origin);
            SchedulerCompat.runGlobal(plugin, () -> {
                player.teleportAsync(target);
                plugin.messages().send(sender, "teleport.teleported");
            });
        });
    }

    private void suicide(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        SchedulerCompat.runForEntity(plugin, player, () -> player.setHealth(0D));
        plugin.messages().send(sender, "player.suicide");
    }

    private void near(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        int radius = args.length > 0 ? Integer.parseInt(args[0]) : cfgInt("near-radius", 100);
        radius = Math.max(1, Math.min(1000, radius));
        List<String> found = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player) || !PlayerUtil.canSee(sender, other)) {
                continue;
            }
            double distance = other.getLocation().distance(player.getLocation());
            if (distance <= radius) {
                found.add(other.getName() + "&#5C6370(" + (int) distance + "m)&#E8EAED");
            }
        }
        if (found.isEmpty()) {
            plugin.messages().send(sender, "player.near-none", "radius", String.valueOf(radius));
            return;
        }
        plugin.messages().send(sender, "player.near-list",
                "radius", String.valueOf(radius),
                "count", String.valueOf(found.size()),
                "players", String.join("&#5C6370, &#E8EAED", found));
    }

    private void ping(CommandSender sender, String label, String[] args) {
        Player target = args.length > 0
                ? PlayerUtil.requireOnline(sender, args[0]) : PlayerUtil.requirePlayer(sender);
        plugin.messages().send(sender, "player.ping",
                "player", target.getName(), "ping", String.valueOf(target.getPing()));
    }

    private void playtime(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            Player player = PlayerUtil.requirePlayer(sender);
            UserData data = plugin.users().get(player);
            plugin.messages().send(sender, "player.playtime",
                    "player", data.getName(), "time", TimeUtil.duration(data.getTotalPlaytime()));
            return;
        }
        plugin.users().lookup(sender, args[0], data ->
                plugin.messages().send(sender, "player.playtime",
                        "player", data.getName(),
                        "time", TimeUtil.duration(data.getTotalPlaytime())));
    }
}

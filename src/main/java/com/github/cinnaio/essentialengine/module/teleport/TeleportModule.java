package com.github.cinnaio.essentialengine.module.teleport;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.LocationUtil;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 传送模块：家、地标、出生点、传送请求、返回、随机传送。
 */
public class TeleportModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";
    private TeleportManager manager;

    public TeleportModule(EssentialEngine plugin) {
        super(plugin, "teleport", "传送");
    }

    public TeleportManager getManager() {
        return manager;
    }

    @Override
    protected void setup() {
        this.manager = new TeleportManager(plugin);
        manager.load();
        listener(new TeleportListener(plugin, manager));

        // ---------------- 家 ----------------
        command("home").aliases("h").playerOnly().permission(PERM + "home")
                .description("传送到你的家").usage("/home [名称]")
                .handler(this::home).completer((sender, args) -> homeNames(sender));

        command("sethome").aliases("createhome").playerOnly().permission(PERM + "sethome")
                .description("设置一个家").usage("/sethome [名称]")
                .handler(this::setHome);

        command("delhome").aliases("removehome", "remhome").playerOnly().permission(PERM + "delhome")
                .description("删除一个家").usage("/delhome <名称>")
                .handler(this::delHome).completer((sender, args) -> homeNames(sender));

        command("homes").aliases("listhomes").permission(PERM + "homes")
                .description("查看家列表").usage("/homes [玩家]")
                .handler(this::homes)
                .completer((sender, args) -> args.length <= 1 ? PlayerUtil.visibleNames(sender) : List.of());

        // ---------------- 地标 ----------------
        command("warp").permission(PERM + "warp")
                .description("传送到地标").usage("/warp <名称> [玩家]")
                .playerOnly().minArgs(1)
                .handler(this::warp)
                .completer((sender, args) -> args.length <= 1
                        ? new ArrayList<>(manager.warpNames()) : PlayerUtil.visibleNames(sender));

        command("setwarp").aliases("createwarp").playerOnly().permission(PERM + "setwarp")
                .description("设置地标").usage("/setwarp <名称>").minArgs(1)
                .handler(this::setWarp);

        command("delwarp").aliases("removewarp", "remwarp").permission(PERM + "delwarp")
                .description("删除地标").usage("/delwarp <名称>").minArgs(1)
                .handler(this::delWarp)
                .completer((sender, args) -> new ArrayList<>(manager.warpNames()));

        command("warps").aliases("listwarps").permission(PERM + "warps")
                .description("查看地标列表").usage("/warps")
                .handler(this::warps);

        // ---------------- 出生点 ----------------
        command("spawn").permission(PERM + "spawn")
                .description("回到出生点").usage("/spawn [玩家]")
                .handler(this::spawn)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("setspawn").playerOnly().permission(PERM + "setspawn")
                .description("设置出生点").usage("/setspawn")
                .handler(this::setSpawn);

        // ---------------- 传送请求 ----------------
        command("tpa").aliases("call", "tpask").playerOnly().permission(PERM + "tpa")
                .description("请求传送到某人").usage("/tpa <玩家>").minArgs(1)
                .handler((sender, label, args) -> request(sender, args, false))
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("tpahere").aliases("tpask-here").playerOnly().permission(PERM + "tpahere")
                .description("请求某人传送到你这").usage("/tpahere <玩家>").minArgs(1)
                .handler((sender, label, args) -> request(sender, args, true))
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("tpaccept").aliases("tpyes", "tpac").playerOnly().permission(PERM + "tpaccept")
                .description("接受传送请求").usage("/tpaccept")
                .handler(this::acceptRequest);

        command("tpdeny").aliases("tpno", "tpdecline").playerOnly().permission(PERM + "tpdeny")
                .description("拒绝传送请求").usage("/tpdeny")
                .handler(this::denyRequest);

        command("tpacancel").aliases("tpcancel").playerOnly().permission(PERM + "tpacancel")
                .description("取消自己发出的传送请求").usage("/tpacancel")
                .handler(this::cancelRequest);

        // ---------------- 直接传送 ----------------
        command("back").aliases("return").playerOnly().permission(PERM + "back")
                .description("返回上一个位置").usage("/back")
                .handler(this::back);

        command("tp").aliases("tport", "teleport").permission(PERM + "tp")
                .description("直接传送").usage("/tp <玩家|x y z> [目标玩家]").minArgs(1)
                .handler(this::tp)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("tphere").aliases("s", "tpohere").playerOnly().permission(PERM + "tphere")
                .description("把某人传送到自己身边").usage("/tphere <玩家>").minArgs(1)
                .handler(this::tpHere)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        if (cfgBool("rtp.enabled", true)) {
            command("rtp").aliases("wild", "randomtp").playerOnly().permission(PERM + "rtp")
                    .description("随机传送到野外").usage("/rtp")
                    .handler(this::rtp);
        }
    }

    @Override
    protected void shutdown() {
        if (manager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                manager.cancelWarmup(player.getUniqueId(), false);
            }
        }
    }

    // ------------------------------------------------------------------ 家

    private List<String> homeNames(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        UserData data = plugin.users().get(player);
        return new ArrayList<>(data.getHomeNames());
    }

    private void home(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        Set<String> names = data.getHomeNames();
        if (names.isEmpty()) {
            throw new CommandError("teleport.no-homes");
        }
        String name;
        if (args.length == 0) {
            name = names.contains("home") ? "home" : names.iterator().next();
        } else {
            name = args[0].toLowerCase(Locale.ROOT);
        }
        if (!data.hasHome(name)) {
            throw new CommandError("teleport.home-not-found", "name", name);
        }
        Location location = data.getHome(name);
        if (location == null) {
            throw new CommandError("teleport.world-not-loaded");
        }
        manager.teleport(player, location, "home");
    }

    private void setHome(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        String name = (args.length == 0 ? "home" : args[0]).toLowerCase(Locale.ROOT);
        if (!name.matches("[\\w\\u4e00-\\u9fa5-]{1,24}")) {
            throw new CommandError("teleport.invalid-name", "name", name);
        }
        int max = manager.maxHomes(player);
        if (!data.hasHome(name) && data.getHomeCount() >= max) {
            throw new CommandError("teleport.home-limit", "max", String.valueOf(max));
        }
        data.setHome(name, player.getLocation());
        plugin.users().saveAsync(data);
        plugin.messages().send(sender, "teleport.home-set", "name", name);
    }

    private void delHome(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        if (args.length == 0) {
            throw new CommandError("general.usage", "usage",
                    MessageManager.localizedOr("usage.delhome", "/delhome <name>"));
        }
        UserData data = plugin.users().get(player);
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!data.removeHome(name)) {
            throw new CommandError("teleport.home-not-found", "name", name);
        }
        plugin.users().saveAsync(data);
        plugin.messages().send(sender, "teleport.home-deleted", "name", name);
    }

    private void homes(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            Player player = PlayerUtil.requirePlayer(sender);
            showHomes(sender, plugin.users().get(player));
            return;
        }
        if (!sender.hasPermission(PERM + "homes.others")) {
            throw new CommandError("general.no-permission", "permission", PERM + "homes.others");
        }
        plugin.users().lookup(sender, args[0], data -> showHomes(sender, data));
    }

    private void showHomes(CommandSender sender, UserData data) {
        if (data.getHomeNames().isEmpty()) {
            plugin.messages().send(sender, "teleport.no-homes-of", "player", data.getName());
            return;
        }
        plugin.messages().send(sender, "teleport.home-list",
                "player", data.getName(),
                "count", String.valueOf(data.getHomeCount()),
                "homes", String.join("&#5C6370, &#E8EAED", data.getHomeNames()));
    }

    // ------------------------------------------------------------------ 地标

    private void warp(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!manager.warpExists(name)) {
            throw new CommandError("teleport.warp-not-found", "name", name);
        }
        if (cfgBool("per-warp-permission", false)) {
            String node = "essentialengine.warp." + name;
            if (!player.hasPermission(node)) {
                throw new CommandError("general.no-permission", "permission", node);
            }
        }
        Location location = manager.getWarp(name);
        if (location == null) {
            throw new CommandError("teleport.world-not-loaded-named", "world", manager.warpWorld(name));
        }
        if (args.length > 1 && sender.hasPermission(PERM + "warp.others")) {
            Player target = PlayerUtil.requireOnline(sender, args[1]);
            manager.performTeleport(target, location);
            plugin.messages().send(sender, "teleport.warp-sent", "player", target.getName(), "name", name);
            return;
        }
        manager.teleport(player, location, "warp");
    }

    private void setWarp(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!name.matches("[\\w\\u4e00-\\u9fa5-]{1,24}")) {
            throw new CommandError("teleport.invalid-name", "name", name);
        }
        manager.setWarp(name, player.getLocation());
        plugin.messages().send(sender, "teleport.warp-set", "name", name);
    }

    private void delWarp(CommandSender sender, String label, String[] args) {
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!manager.deleteWarp(name)) {
            throw new CommandError("teleport.warp-not-found", "name", name);
        }
        plugin.messages().send(sender, "teleport.warp-deleted", "name", name);
    }

    private void warps(CommandSender sender, String label, String[] args) {
        Set<String> names = manager.warpNames();
        if (names.isEmpty()) {
            plugin.messages().send(sender, "teleport.no-warps");
            return;
        }
        plugin.messages().send(sender, "teleport.warp-list",
                "count", String.valueOf(names.size()),
                "warps", String.join("&#5C6370, &#E8EAED", names));
    }

    // ------------------------------------------------------------------ 出生点

    private void spawn(CommandSender sender, String label, String[] args) {
        Location location = manager.getSpawn();
        if (location == null) {
            World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (world == null) {
                throw new CommandError("teleport.spawn-not-set");
            }
            location = world.getSpawnLocation();
        }
        if (args.length > 0 && sender.hasPermission(PERM + "spawn.others")) {
            Player target = PlayerUtil.requireOnline(sender, args[0]);
            manager.performTeleport(target, location);
            plugin.messages().send(sender, "teleport.spawn-sent", "player", target.getName());
            return;
        }
        Player player = PlayerUtil.requirePlayer(sender);
        manager.teleport(player, location, "spawn");
    }

    private void setSpawn(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        manager.setSpawn(player.getLocation());
        plugin.messages().send(sender, "teleport.spawn-set",
                "location", LocationUtil.describe(player.getLocation()));
    }

    // ------------------------------------------------------------------ 传送请求

    private void request(CommandSender sender, String[] args, boolean here) {
        Player player = PlayerUtil.requirePlayer(sender);
        Player target = PlayerUtil.requireOnline(sender, args[0]);
        if (target.equals(player)) {
            throw new CommandError("teleport.self");
        }
        manager.checkCooldown(player, "tpa");
        manager.addRequest(player, target, here);
        manager.applyCooldown(player, "tpa");

        plugin.messages().send(sender, "teleport.request-sent", "player", target.getName());
        plugin.messages().send(target, here ? "teleport.request-received-here" : "teleport.request-received",
                "player", PlayerUtil.display(player));
    }

    private void acceptRequest(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        TeleportManager.TpaRequest request = manager.consumeRequest(player.getUniqueId());
        if (request == null) {
            throw new CommandError("teleport.request-none");
        }
        Player other = Bukkit.getPlayer(request.sender());
        if (other == null) {
            throw new CommandError("teleport.request-expired");
        }
        plugin.messages().send(sender, "teleport.request-accepted", "player", other.getName());
        plugin.messages().send(other, "teleport.request-accepted-target", "player", player.getName());
        if (request.here()) {
            manager.teleport(player, other.getLocation(), "tpa");
        } else {
            manager.teleport(other, player.getLocation(), "tpa");
        }
    }

    private void denyRequest(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        TeleportManager.TpaRequest request = manager.consumeRequest(player.getUniqueId());
        if (request == null) {
            throw new CommandError("teleport.request-none");
        }
        plugin.messages().send(sender, "teleport.request-denied");
        Player other = Bukkit.getPlayer(request.sender());
        if (other != null) {
            plugin.messages().send(other, "teleport.request-denied-target", "player", player.getName());
        }
    }

    private void cancelRequest(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        int count = manager.cancelRequestsFrom(player.getUniqueId());
        if (count == 0) {
            throw new CommandError("teleport.request-none");
        }
        plugin.messages().send(sender, "teleport.request-cancelled", "count", String.valueOf(count));
    }

    // ------------------------------------------------------------------ 直接传送

    private void back(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        Location target = data.getLastLocation();
        if (target == null) {
            throw new CommandError("teleport.back-none");
        }
        manager.checkCooldown(player, "back");
        data.setLastLocation(player.getLocation());
        manager.performTeleportRaw(player, target);
        manager.applyCooldown(player, "back");
        plugin.messages().send(sender, "teleport.teleported");
    }

    private void tp(CommandSender sender, String label, String[] args) {
        // /tp <x> <y> <z>
        if (args.length >= 3 && isNumber(args[0]) && isNumber(args[1]) && isNumber(args[2])) {
            Player player = PlayerUtil.requirePlayer(sender);
            Location location = new Location(player.getWorld(),
                    Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]),
                    player.getLocation().getYaw(), player.getLocation().getPitch());
            manager.performTeleport(player, location);
            return;
        }
        Player first = PlayerUtil.requireOnline(sender, args[0]);
        if (args.length == 1) {
            Player player = PlayerUtil.requirePlayer(sender);
            manager.performTeleport(player, first.getLocation());
            return;
        }
        if (!sender.hasPermission(PERM + "tp.others")) {
            throw new CommandError("general.no-permission", "permission", PERM + "tp.others");
        }
        Player second = PlayerUtil.requireOnline(sender, args[1]);
        manager.performTeleport(first, second.getLocation());
        plugin.messages().send(sender, "teleport.tp-success",
                "player", first.getName(), "target", second.getName());
        plugin.messages().send(first, "teleport.tp-target", "player",
                sender instanceof Player p ? PlayerUtil.display(p)
                        : MessageManager.localized("general.console"));
    }

    private void tpHere(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        Player target = PlayerUtil.requireOnline(sender, args[0]);
        manager.performTeleport(target, player.getLocation());
        plugin.messages().send(sender, "teleport.tp-success",
                "player", target.getName(), "target", player.getName());
        plugin.messages().send(target, "teleport.tp-target", "player", PlayerUtil.display(player));
    }

    // ------------------------------------------------------------------ 随机传送

    private void rtp(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        manager.checkCooldown(player, "rtp");

        String worldName = cfgString("rtp.world", "");
        World world = worldName == null || worldName.isEmpty()
                ? player.getWorld() : Bukkit.getWorld(worldName);
        if (world == null) {
            world = player.getWorld();
        }
        plugin.messages().send(sender, "teleport.rtp-searching");
        attemptRandom(player, world, 0);
    }

    private void attemptRandom(Player player, World world, int attempt) {
        int maxAttempts = cfgInt("rtp.max-attempts", 8);
        if (attempt >= maxAttempts) {
            plugin.messages().send(player, "teleport.rtp-failed");
            return;
        }
        int min = cfgInt("rtp.min-radius", 500);
        int max = Math.max(min + 1, cfgInt("rtp.max-radius", 5000));

        Location center = world.getSpawnLocation();
        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        double radius = min + ThreadLocalRandom.current().nextDouble() * (max - min);
        int x = (int) (center.getX() + Math.cos(angle) * radius);
        int z = (int) (center.getZ() + Math.sin(angle) * radius);
        Location probe = new Location(world, x + 0.5, center.getY(), z + 0.5,
                player.getLocation().getYaw(), player.getLocation().getPitch());

        SchedulerCompat.runForRegion(plugin, probe, () -> {
            Location candidate = LocationUtil.highestAt(probe);
            if (LocationUtil.isSafe(candidate)) {
                SchedulerCompat.runGlobal(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    manager.performTeleport(player, candidate);
                    manager.applyCooldown(player, "rtp");
                });
            } else {
                SchedulerCompat.runGlobal(plugin, () -> attemptRandom(player, world, attempt + 1));
            }
        });
    }

    private boolean isNumber(String raw) {
        try {
            Double.parseDouble(raw);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }
}

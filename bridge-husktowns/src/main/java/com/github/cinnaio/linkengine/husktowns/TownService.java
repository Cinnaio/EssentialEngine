package com.github.cinnaio.linkengine.husktowns;

import net.william278.husktowns.api.BukkitHuskTownsAPI;
import net.william278.husktowns.town.Member;
import net.william278.husktowns.town.Role;
import net.william278.husktowns.town.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service layer wrapping HuskTowns API for town operations.
 * Uses BukkitHuskTownsAPI for Bukkit-specific convenience methods.
 */
public class TownService {

    private final Plugin plugin;
    private BukkitHuskTownsAPI api;

    public TownService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.api = BukkitHuskTownsAPI.getInstance();
    }

    public List<Map<String, Object>> getAllTowns() {
        List<Map<String, Object>> towns = new ArrayList<>();
        for (Town town : api.getTowns()) {
            towns.add(townToMap(town));
        }
        return towns;
    }

    public Map<String, Object> getTown(String name) {
        Optional<Town> town = api.getTown(name);
        return town.map(this::townToMap).orElse(null);
    }

    public List<Map<String, Object>> getTownMembers(String townName) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) return null;

        Town town = townOpt.get();
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : town.getMembers().entrySet()) {
            Map<String, Object> member = new HashMap<>();
            member.put("uuid", entry.getKey().toString());
            member.put("roleWeight", entry.getValue());
            Player player = Bukkit.getPlayer(entry.getKey());
            member.put("name", player != null ? player.getName() : entry.getKey().toString());
            member.put("online", player != null);
            members.add(member);
        }
        return members;
    }

    /**
     * Add a member to a town.
     * Town.addMember(UUID, Role) requires a Role object.
     */
    public Map<String, Object> addMember(String townName, UUID playerUuid, String roleName) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return errorResult("Town not found: " + townName);
        }

        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return errorResult("Player not online: " + playerUuid);
        }

        Optional<Member> existingTown = api.getUserTown(player);
        if (existingTown.isPresent()) {
            return errorResult("Player is already in town: " + existingTown.get().town().getName());
        }

        Player actor = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (actor == null) {
            return errorResult("No online player available to perform this action");
        }

        try {
            // Get a Role from an existing town member, or use default Role
            Town town = townOpt.get();
            Role role = null;
            for (Map.Entry<UUID, Integer> entry : town.getMembers().entrySet()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null) {
                    Optional<Member> m = api.getUserTown(p);
                    if (m.isPresent()) {
                        role = m.get().role();
                        break;
                    }
                }
            }
            if (role == null) {
                return errorResult("No online town member available to determine role. At least one town member must be online.");
            }
            final Role finalRole = role;
            api.editTown(actor, townName, t -> t.addMember(playerUuid, finalRole));
            Map<String, Object> result = new HashMap<>();
            result.put("town", townName);
            result.put("player", player.getName());
            result.put("role", role.getName());
            result.put("message", "Player added to town");
            return result;
        } catch (Exception e) {
            return errorResult("Failed to add member: " + e.getMessage());
        }
    }

    public Map<String, Object> removeMember(String townName, UUID playerUuid) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return errorResult("Town not found: " + townName);
        }

        Town town = townOpt.get();
        if (!town.getMembers().containsKey(playerUuid)) {
            return errorResult("Player is not a member of this town");
        }

        Player actor = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (actor == null) {
            return errorResult("No online player available to perform this action");
        }

        try {
            api.editTown(actor, townName, t -> t.removeMember(playerUuid));
            Map<String, Object> result = new HashMap<>();
            result.put("town", townName);
            result.put("playerUuid", playerUuid.toString());
            result.put("message", "Player removed from town");
            return result;
        } catch (Exception e) {
            return errorResult("Failed to remove member: " + e.getMessage());
        }
    }

    public CompletableFuture<Map<String, Object>> createTown(String townName, UUID ownerUuid) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner == null) {
            future.complete(errorResult("Owner player not online: " + ownerUuid));
            return future;
        }

        Optional<Member> existing = api.getUserTown(owner);
        if (existing.isPresent()) {
            future.complete(errorResult("Player is already in town: " + existing.get().town().getName()));
            return future;
        }

        api.createTown(owner, townName).thenAccept(town -> {
            Map<String, Object> result = new HashMap<>();
            result.put("town", town.getName());
            result.put("owner", owner.getName());
            result.put("message", "Town created successfully");
            future.complete(result);
        }).exceptionally(throwable -> {
            future.complete(errorResult("Failed to create town: " + throwable.getMessage()));
            return null;
        });

        return future;
    }

    public Map<String, Object> deleteTown(String townName) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return errorResult("Town not found: " + townName);
        }

        Player actor = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (actor == null) {
            return errorResult("No online player available to perform this action");
        }

        try {
            api.deleteTown(actor, townOpt.get());
            Map<String, Object> result = new HashMap<>();
            result.put("town", townName);
            result.put("message", "Town deleted successfully");
            return result;
        } catch (Exception e) {
            return errorResult("Failed to delete town: " + e.getMessage());
        }
    }

    public Map<String, Object> getPlayerTown(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return errorResult("Player not online: " + playerUuid);
        }

        Optional<Member> memberOpt = api.getUserTown(player);
        if (memberOpt.isEmpty()) {
            return errorResult("Player is not in any town");
        }

        Member member = memberOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("town", townToMap(member.town()));
        result.put("role", member.role().getName());
        result.put("player", player.getName());
        return result;
    }

    private Map<String, Object> townToMap(Town town) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", town.getId());
        map.put("name", town.getName());
        map.put("greeting", town.getGreeting().orElse(""));
        map.put("farewell", town.getFarewell().orElse(""));
        map.put("memberCount", town.getMembers().size());
        map.put("level", town.getLevel());
        map.put("money", town.getMoney());

        List<Map<String, Object>> members = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : town.getMembers().entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("uuid", entry.getKey().toString());
            m.put("roleWeight", entry.getValue());
            Player p = Bukkit.getPlayer(entry.getKey());
            m.put("name", p != null ? p.getName() : entry.getKey().toString());
            members.add(m);
        }
        map.put("members", members);
        return map;
    }

    private Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}

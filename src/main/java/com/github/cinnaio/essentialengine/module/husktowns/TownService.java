package com.github.cinnaio.essentialengine.module.husktowns;

import net.william278.husktowns.api.BukkitHuskTownsAPI;
import net.william278.husktowns.town.Member;
import net.william278.husktowns.town.Role;
import net.william278.husktowns.town.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HuskTowns API 封装。
 *
 * <p>由原 bridge-husktowns 子项目迁移而来，现在作为 EssentialEngine 的一个内置模块，
 * 既给 REST API 提供数据，也给插件内的城镇命令使用。</p>
 */
public class TownService {

    private BukkitHuskTownsAPI api;

    public void initialize() {
        this.api = BukkitHuskTownsAPI.getInstance();
    }

    public boolean isReady() {
        return api != null;
    }

    public List<Map<String, Object>> getAllTowns() {
        List<Map<String, Object>> towns = new ArrayList<>();
        for (Town town : api.getTowns()) {
            towns.add(townToMap(town));
        }
        return towns;
    }

    public List<String> getTownNames() {
        List<String> names = new ArrayList<>();
        for (Town town : api.getTowns()) {
            names.add(town.getName());
        }
        return names;
    }

    public Map<String, Object> getTown(String name) {
        Optional<Town> town = api.getTown(name);
        return town.map(this::townToMap).orElse(null);
    }

    public List<Map<String, Object>> getTownMembers(String townName) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return null;
        }
        Town town = townOpt.get();
        Map<Integer, String> roleNames = buildRoleNameMap();
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : town.getMembers().entrySet()) {
            members.add(memberToMap(entry.getKey(), entry.getValue(), roleNames));
        }
        return members;
    }

    /**
     * 往城镇里加人。HuskTowns 的 {@code Town#addMember} 需要一个 Role 对象，
     * 这里从城镇现有成员身上取一个可用的 Role。
     */
    public Map<String, Object> addMember(String townName, UUID playerUuid, String roleName) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return error("找不到城镇: " + townName);
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return error("玩家不在线: " + playerUuid);
        }
        Optional<Member> existing = api.getUserTown(player);
        if (existing.isPresent()) {
            return error("玩家已经在城镇 " + existing.get().town().getName() + " 中");
        }
        Player actor = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (actor == null) {
            return error("没有在线玩家可用于执行该操作");
        }
        try {
            Town town = townOpt.get();
            Role role = null;
            for (Map.Entry<UUID, Integer> entry : town.getMembers().entrySet()) {
                Player member = Bukkit.getPlayer(entry.getKey());
                if (member != null) {
                    Optional<Member> found = api.getUserTown(member);
                    if (found.isPresent()) {
                        role = found.get().role();
                        break;
                    }
                }
            }
            if (role == null) {
                return error("无法确定成员身份，至少需要一名城镇成员在线");
            }
            final Role finalRole = role;
            api.editTown(actor, townName, target -> target.addMember(playerUuid, finalRole));

            Map<String, Object> result = new HashMap<>();
            result.put("town", townName);
            result.put("player", player.getName());
            result.put("role", role.getName());
            result.put("message", "玩家已加入城镇");
            return result;
        } catch (Exception e) {
            return error("加入城镇失败: " + e.getMessage());
        }
    }

    public Map<String, Object> removeMember(String townName, UUID playerUuid) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return error("找不到城镇: " + townName);
        }
        if (!townOpt.get().getMembers().containsKey(playerUuid)) {
            return error("该玩家不是这个城镇的成员");
        }
        Player actor = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (actor == null) {
            return error("没有在线玩家可用于执行该操作");
        }
        try {
            api.editTown(actor, townName, target -> target.removeMember(playerUuid));
            Map<String, Object> result = new HashMap<>();
            result.put("town", townName);
            result.put("playerUuid", playerUuid.toString());
            result.put("message", "玩家已被移出城镇");
            return result;
        } catch (Exception e) {
            return error("移出成员失败: " + e.getMessage());
        }
    }

    public CompletableFuture<Map<String, Object>> createTown(String townName, UUID ownerUuid) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner == null) {
            future.complete(error("城主不在线: " + ownerUuid));
            return future;
        }
        Optional<Member> existing = api.getUserTown(owner);
        if (existing.isPresent()) {
            future.complete(error("该玩家已经在城镇 " + existing.get().town().getName() + " 中"));
            return future;
        }
        api.createTown(owner, townName).thenAccept(town -> {
            Map<String, Object> result = new HashMap<>();
            result.put("town", town.getName());
            result.put("owner", owner.getName());
            result.put("message", "城镇创建成功");
            future.complete(result);
        }).exceptionally(throwable -> {
            future.complete(error("创建城镇失败: " + throwable.getMessage()));
            return null;
        });
        return future;
    }

    public Map<String, Object> deleteTown(String townName) {
        Optional<Town> townOpt = api.getTown(townName);
        if (townOpt.isEmpty()) {
            return error("找不到城镇: " + townName);
        }
        Player actor = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (actor == null) {
            return error("没有在线玩家可用于执行该操作");
        }
        try {
            api.deleteTown(actor, townOpt.get());
            Map<String, Object> result = new HashMap<>();
            result.put("town", townName);
            result.put("message", "城镇已删除");
            return result;
        } catch (Exception e) {
            return error("删除城镇失败: " + e.getMessage());
        }
    }

    public Map<String, Object> getPlayerTown(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            Optional<Member> memberOpt = api.getUserTown(player);
            if (memberOpt.isPresent()) {
                Member member = memberOpt.get();
                Map<String, Object> result = new HashMap<>();
                result.put("town", townToMap(member.town()));
                result.put("role", member.role().getName());
                result.put("player", player.getName());
                return result;
            }
        }

        // Town#getMembers 保存的是完整成员 UUID，不依赖 Bukkit 的在线玩家对象，
        // 因此离线玩家也可以通过这里找到所属城镇。
        Optional<TownMembership> membership = findTownMembership(playerUuid, api.getTowns());
        if (membership.isEmpty()) {
            return error("该玩家不属于任何城镇");
        }

        TownMembership found = membership.get();
        Map<String, Object> result = new HashMap<>();
        result.put("town", townToMap(found.town()));
        result.put("role", buildRoleNameMap().getOrDefault(found.roleWeight(), "Member"));
        result.put("player", player != null ? player.getName() : playerUuid.toString());
        return result;
    }

    /**
     * 玩家所属城镇的简要信息（name / role / level / members / money）。
     *
     * <p>只返回基础类型，不暴露任何 HuskTowns 的类——这样 PlaceholderAPI 之类的
     * 调用方即使在没装 HuskTowns 的服务器上也不会触发类加载错误。
     * API 未就绪或玩家不在城镇时返回 null。</p>
     */
    public Map<String, Object> summaryOf(Player player) {
        if (api == null || player == null) {
            return null;
        }
        Optional<Member> memberOpt = api.getUserTown(player);
        if (memberOpt.isEmpty()) {
            return null;
        }
        Member member = memberOpt.get();
        Town town = member.town();
        Map<String, Object> summary = new HashMap<>();
        summary.put("name", town.getName());
        summary.put("role", member.role().getName());
        summary.put("level", town.getLevel());
        summary.put("members", town.getMembers().size());
        summary.put("money", town.getMoney());
        return summary;
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 在城镇成员表中按 UUID 查找玩家。
     *
     * <p>不要用 {@code Bukkit.getPlayer(UUID)} 替代这里的查找：Bukkit 只返回在线玩家，
     * 而 HuskTowns 的城镇成员表同时包含在线和离线成员。</p>
     */
    static Optional<TownMembership> findTownMembership(UUID playerUuid, Iterable<Town> towns) {
        if (playerUuid == null || towns == null) {
            return Optional.empty();
        }
        for (Town town : towns) {
            if (town == null) {
                continue;
            }
            Integer roleWeight = town.getMembers().get(playerUuid);
            if (roleWeight != null) {
                return Optional.of(new TownMembership(town, roleWeight));
            }
        }
        return Optional.empty();
    }

    record TownMembership(Town town, int roleWeight) {
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

        Map<Integer, String> roleNames = buildRoleNameMap();
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : town.getMembers().entrySet()) {
            members.add(memberToMap(entry.getKey(), entry.getValue(), roleNames));
        }
        map.put("members", members);
        return map;
    }

    private Map<String, Object> memberToMap(UUID uuid, int weight, Map<Integer, String> roleNames) {
        Map<String, Object> member = new HashMap<>();
        Player player = Bukkit.getPlayer(uuid);
        member.put("uuid", uuid.toString());
        member.put("roleWeight", weight);
        member.put("role", roleNames.getOrDefault(weight, "Member"));
        member.put("name", player != null ? player.getName() : uuid.toString());
        member.put("online", player != null);
        return member;
    }

    /**
     * 建立“权重 -> 身份名”映射。
     * 优先反射读取 HuskTowns 的 Roles 配置，失败时退回默认映射。
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, String> buildRoleNameMap() {
        Map<Integer, String> map = new HashMap<>();
        try {
            Plugin huskTowns = Bukkit.getPluginManager().getPlugin("HuskTowns");
            if (huskTowns != null) {
                Method getRolesConfig = huskTowns.getClass().getMethod("getRoles");
                Object rolesConfig = getRolesConfig.invoke(huskTowns);
                Method getRolesList = rolesConfig.getClass().getMethod("getRoles");
                List<Role> roles = (List<Role>) getRolesList.invoke(rolesConfig);
                for (Role role : roles) {
                    map.put(role.getWeight(), role.getName());
                }
            }
        } catch (Exception ignored) {
            // 使用默认映射
        }
        if (map.isEmpty()) {
            map.put(3, "Mayor");
            map.put(2, "Trustee");
            map.put(1, "Resident");
        }
        return map;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}

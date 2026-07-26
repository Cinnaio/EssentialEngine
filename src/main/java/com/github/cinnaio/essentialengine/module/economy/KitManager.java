package com.github.cinnaio.essentialengine.module.economy;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * 套装（kit）管理。
 *
 * <p>物品既可以用 {@code /kit create <名称>} 直接把当前背包存下来
 * （用 Bukkit 自带的 ItemStack 序列化，附魔、自定义名称都会保留），
 * 也可以在 kits.yml 里手写 {@code "STONE_SWORD 1 name:&a新手剑"} 这种简写。</p>
 */
public class KitManager {

    private final EssentialEngine plugin;
    private final File file;
    private YamlConfiguration config;

    public KitManager(EssentialEngine plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kits.yml");
    }

    public void reload() {
        if (!file.exists() && plugin.getResource("kits.yml") != null) {
            plugin.saveResource("kits.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            config.save(file);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "保存 kits.yml 失败", error);
        }
    }

    public Set<String> kitNames() {
        ConfigurationSection section = config.getConfigurationSection("kits");
        return section == null ? new LinkedHashSet<>() : new LinkedHashSet<>(section.getKeys(false));
    }

    public boolean exists(String name) {
        return config.isConfigurationSection("kits." + name.toLowerCase(Locale.ROOT));
    }

    public String permissionOf(String name) {
        String custom = config.getString("kits." + name.toLowerCase(Locale.ROOT) + ".permission", "");
        return custom == null || custom.isEmpty() ? "essentialengine.kit." + name.toLowerCase(Locale.ROOT) : custom;
    }

    /** 冷却秒数，0 表示无冷却，负数表示只能领一次。 */
    public long cooldownOf(String name) {
        return config.getLong("kits." + name.toLowerCase(Locale.ROOT) + ".cooldown", 0L);
    }

    /** 领取套装；冷却未到会抛出可读错误。 */
    public void give(Player player, UserData data, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!exists(name)) {
            throw new CommandError("economy.kit-not-found", "name", rawName);
        }
        if (!player.hasPermission(permissionOf(name))) {
            throw new CommandError("general.no-permission", "permission", permissionOf(name));
        }

        long cooldown = cooldownOf(name);
        long used = data.getKitUsed(name);
        if (used > 0 && cooldown != 0) {
            if (cooldown < 0) {
                throw new CommandError("economy.kit-once", "name", name);
            }
            long ready = used + cooldown * 1000L;
            if (System.currentTimeMillis() < ready) {
                throw new CommandError("economy.kit-cooldown",
                        "name", name, "time", TimeUtil.duration(ready - System.currentTimeMillis()));
            }
        }

        List<ItemStack> items = itemsOf(name);
        if (items.isEmpty()) {
            throw new CommandError("economy.kit-empty", "name", name);
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        data.markKitUsed(name);
    }

    /** 读取套装物品，兼容序列化对象与简写字符串两种写法。 */
    public List<ItemStack> itemsOf(String name) {
        List<ItemStack> result = new ArrayList<>();
        List<?> raw = config.getList("kits." + name.toLowerCase(Locale.ROOT) + ".items");
        if (raw == null) {
            return result;
        }
        for (Object entry : raw) {
            if (entry instanceof ItemStack item) {
                result.add(item.clone());
            } else if (entry instanceof String text) {
                ItemStack parsed = parse(text);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        }
        return result;
    }

    /** 解析 {@code MATERIAL 数量 name:显示名} 形式的简写。 */
    private ItemStack parse(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        Material material = Material.matchMaterial(parts[0]);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("kits.yml 中无法识别的物品: " + parts[0]);
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        ItemStack item = new ItemStack(material, amount);
        for (String part : parts) {
            if (part.toLowerCase(Locale.ROOT).startsWith("name:")) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(com.github.cinnaio.essentialengine.core.util.Text
                            .parse(part.substring(5).replace('_', ' ')));
                    item.setItemMeta(meta);
                }
            }
        }
        return item;
    }

    /** 用玩家当前背包创建 / 覆盖一个套装。 */
    public void createFromInventory(Player player, String rawName, long cooldownSeconds) {
        String name = rawName.toLowerCase(Locale.ROOT);
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        if (items.isEmpty()) {
            throw new CommandError("economy.kit-inventory-empty");
        }
        config.set("kits." + name + ".cooldown", cooldownSeconds);
        config.set("kits." + name + ".permission", "");
        config.set("kits." + name + ".items", items);
        save();
    }

    public boolean delete(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!exists(name)) {
            return false;
        }
        config.set("kits." + name, null);
        save();
        return true;
    }
}

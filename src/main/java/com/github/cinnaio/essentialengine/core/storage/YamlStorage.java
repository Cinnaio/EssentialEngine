package com.github.cinnaio.essentialengine.core.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML 存储后端。
 *
 * <pre>
 * plugins/EssentialEngine/
 *   userdata/&lt;uuid&gt;.yml     每个玩家一份
 *   data/warps.yml           全局数据
 *   data/usercache.yml       玩家名 -&gt; UUID 索引
 * </pre>
 */
public class YamlStorage implements StorageProvider {

    private final Plugin plugin;
    private final File userFolder;
    private final File dataFolder;
    private final File cacheFile;

    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameByUuid = new ConcurrentHashMap<>();
    private volatile boolean cacheDirty;

    public YamlStorage(Plugin plugin) {
        this.plugin = plugin;
        this.userFolder = new File(plugin.getDataFolder(), "userdata");
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        this.cacheFile = new File(dataFolder, "usercache.yml");
    }

    @Override
    public String getName() {
        return "YAML";
    }

    @Override
    public void init() throws Exception {
        if (!userFolder.exists() && !userFolder.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + userFolder.getAbsolutePath());
        }
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + dataFolder.getAbsolutePath());
        }
        loadUserCache();
    }

    private void loadUserCache() {
        if (cacheFile.exists()) {
            YamlConfiguration cache = YamlConfiguration.loadConfiguration(cacheFile);
            for (String name : cache.getKeys(false)) {
                String raw = cache.getString(name);
                if (raw == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(raw);
                    nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
                    nameByUuid.put(uuid, name);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return;
        }
        // 首次启动或缓存丢失：扫描 userdata 目录重建
        File[] files = userFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                UUID uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                String name = config.getString("name");
                if (name != null && !name.isEmpty()) {
                    nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
                    nameByUuid.put(uuid, name);
                }
            } catch (Exception ignored) {
            }
        }
        cacheDirty = true;
        flushCache();
    }

    private synchronized void flushCache() {
        if (!cacheDirty) {
            return;
        }
        YamlConfiguration cache = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : nameByUuid.entrySet()) {
            cache.set(entry.getValue(), entry.getKey().toString());
        }
        try {
            cache.save(cacheFile);
            cacheDirty = false;
        } catch (Exception error) {
            plugin.getLogger().warning("保存 usercache.yml 失败: " + error.getMessage());
        }
    }

    @Override
    public void shutdown() {
        flushCache();
    }

    private File userFile(UUID uuid) {
        return new File(userFolder, uuid + ".yml");
    }

    @Override
    public Map<String, Object> loadUser(UUID uuid) {
        File file = userFile(uuid);
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        return sectionToMap(config);
    }

    @Override
    public void saveUser(UUID uuid, String name, double balance, Map<String, Object> data) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            config.set(entry.getKey(), entry.getValue());
        }
        config.save(userFile(uuid));

        if (name != null && !name.isEmpty()) {
            String previous = nameByUuid.put(uuid, name);
            if (previous != null && !previous.equalsIgnoreCase(name)) {
                nameIndex.remove(previous.toLowerCase(Locale.ROOT));
            }
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
            cacheDirty = true;
            flushCache();
        }
    }

    @Override
    public UUID lookupUuid(String name) {
        if (name == null) {
            return null;
        }
        return nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<UUID> allUsers() {
        List<UUID> result = new ArrayList<>();
        File[] files = userFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return result;
        }
        for (File file : files) {
            try {
                result.add(UUID.fromString(file.getName().substring(0, file.getName().length() - 4)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    @Override
    public LinkedHashMap<String, Double> topBalances(int limit) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>();
        for (UUID uuid : allUsers()) {
            Map<String, Object> data = loadUser(uuid);
            if (data == null) {
                continue;
            }
            Object name = data.get("name");
            Object balance = data.get("balance");
            if (name == null) {
                continue;
            }
            double value = balance instanceof Number number ? number.doubleValue() : 0D;
            entries.add(Map.entry(String.valueOf(name), value));
        }
        entries.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : entries) {
            if (result.size() >= limit) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @Override
    public Map<String, Object> loadGlobal(String key) {
        File file = new File(dataFolder, key + ".yml");
        if (!file.exists()) {
            return null;
        }
        return sectionToMap(YamlConfiguration.loadConfiguration(file));
    }

    @Override
    public void saveGlobal(String key, Map<String, Object> value) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            config.set(entry.getKey(), entry.getValue());
        }
        config.save(new File(dataFolder, key + ".yml"));
    }

    /** 把配置节点递归转换成普通 Map，保持和 JSON 后端一致的结构。 */
    private Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                map.put(key, sectionToMap(child));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }
}

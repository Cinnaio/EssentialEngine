package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * config.yml 的结构化读写。
 *
 * <p>读：把配置摊平成「分组 → 若干条目」，每条带上类型、当前值和 YAML 里的注释，
 * 前端据此渲染成开关 / 数字框 / 文本框，而不是让人对着一大坨纯文本编辑。</p>
 *
 * <p>写：始终重新从磁盘加载一份 {@link YamlConfiguration} 再改再存——
 * 这样 Bukkit 能把注释原样写回去（1.18+ 支持注释往返），
 * 也不会把运行期内存里的默认值一并落盘。</p>
 */
public class ConfigService {

    /**
     * 不下发给浏览器的敏感字段。
     *
     * <p>面板已经要登录才能进，但没必要把数据库密码、API Key、面板自己的密码
     * 明文渲染到页面上——浏览器缓存、截图、旁人一眼扫过都是泄露途径。
     * 这些字段读取时返回空串并标记 {@code sensitive}，保存时留空即视为「不修改」。</p>
     */
    private static final Set<String> SENSITIVE = Set.of(
            "modules.webapi.api-key",
            "modules.panel.password",
            "modules.panel.oauth.client-secret",
            "storage.mysql.password");

    private final EssentialEngine plugin;

    public ConfigService(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    // ------------------------------------------------------------------ 读取

    /** 整份配置，按分组返回。 */
    public Map<String, Object> read() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile());
        List<Map<String, Object>> groups = new ArrayList<>();
        collect(config, config, "", groups);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        return result;
    }

    /**
     * 递归收集分组。一个配置节只要直接包含标量 / 列表，就单独成组；
     * 它下面的子节各自再成组，所以 {@code storage} 与 {@code storage.mysql} 是两组。
     */
    private void collect(YamlConfiguration root, ConfigurationSection section,
                         String prefix, List<Map<String, Object>> groups) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<ConfigurationSection> children = new ArrayList<>();

        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                children.add(section.getConfigurationSection(key));
                continue;
            }
            entries.add(entry(root, path, key, section.get(key)));
        }

        if (!entries.isEmpty()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("path", prefix);
            group.put("label", prefix.isEmpty() ? "常规" : prefix);
            group.put("comment", comment(root, prefix));
            group.put("entries", entries);
            groups.add(group);
        }
        for (ConfigurationSection child : children) {
            String childPrefix = prefix.isEmpty()
                    ? child.getName() : prefix + "." + child.getName();
            collect(root, child, childPrefix, groups);
        }
    }

    private Map<String, Object> entry(YamlConfiguration root, String path, String key, Object value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        boolean sensitive = SENSITIVE.contains(path);
        entry.put("path", path);
        entry.put("key", key);
        entry.put("type", typeOf(value));
        entry.put("comment", comment(root, path));
        entry.put("sensitive", sensitive);
        if (sensitive) {
            // 只告诉前端「有没有设置过」，不给出内容
            entry.put("value", "");
            entry.put("hasValue", value != null && !String.valueOf(value).isEmpty());
        } else {
            entry.put("value", value);
        }
        return entry;
    }

    /** 取某个路径在 YAML 里的注释（1.18+ 才有，取不到就返回空串）。 */
    private String comment(YamlConfiguration root, String path) {
        try {
            List<String> lines = path.isEmpty() ? root.getComments("") : root.getComments(path);
            if (lines == null || lines.isEmpty()) {
                return "";
            }
            List<String> cleaned = new ArrayList<>();
            for (String line : lines) {
                String text = line.trim();
                // 分隔线之类的装饰对表单没有意义
                if (text.isEmpty() || text.chars().allMatch(c -> c == '-' || c == '=')) {
                    continue;
                }
                cleaned.add(text);
            }
            return String.join("\n", cleaned);
        } catch (Throwable error) {
            return "";
        }
    }

    private static String typeOf(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List) {
            return "list";
        }
        return "string";
    }

    // ------------------------------------------------------------------ 写入

    /** 一次保存的结果。 */
    public record SaveResult(int changed, List<String> rejected) {
    }

    /**
     * 按路径批量写入。
     *
     * <p>只接受<b>已经存在于 config.yml 里的标量路径</b>：既挡住了往配置里注入任意键，
     * 也避免把某个配置节整个覆盖掉。类型跟随原值——原来是整数就存整数，
     * 不会因为前端传了字符串就把 YAML 里的类型改掉。</p>
     */
    public SaveResult save(Map<String, Object> updates) {
        File file = configFile();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        List<String> rejected = new ArrayList<>();
        int changed = 0;

        for (Map.Entry<String, Object> update : updates.entrySet()) {
            String path = update.getKey();
            if (!config.contains(path) || config.isConfigurationSection(path)) {
                rejected.add(path);
                continue;
            }
            Object current = config.get(path);
            Object coerced = coerce(current, update.getValue());
            if (coerced == null) {
                rejected.add(path);
                continue;
            }
            if (SENSITIVE.contains(path) && String.valueOf(coerced).isEmpty()) {
                // 敏感字段留空表示「保持原值」，不是「清空」
                continue;
            }
            if (!coerced.equals(current)) {
                config.set(path, coerced);
                changed++;
            }
        }

        if (changed > 0) {
            try {
                config.save(file);
            } catch (Exception error) {
                throw new IllegalStateException("写入 config.yml 失败: " + error.getMessage(), error);
            }
        }
        return new SaveResult(changed, rejected);
    }

    /** 把前端传来的值转换成与原值一致的类型；无法转换返回 null。 */
    private Object coerce(Object current, Object incoming) {
        if (incoming == null) {
            return null;
        }
        String text = String.valueOf(incoming).trim();
        try {
            if (current instanceof Boolean) {
                if (incoming instanceof Boolean bool) {
                    return bool;
                }
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.equals("true") || lower.equals("false")) {
                    return Boolean.parseBoolean(lower);
                }
                return null;
            }
            if (current instanceof Integer) {
                return (int) Double.parseDouble(text);
            }
            if (current instanceof Long) {
                return (long) Double.parseDouble(text);
            }
            if (current instanceof Number) {
                return Double.parseDouble(text);
            }
            if (current instanceof List) {
                List<String> list = new ArrayList<>();
                if (incoming instanceof List<?> raw) {
                    for (Object item : raw) {
                        String value = String.valueOf(item).trim();
                        if (!value.isEmpty()) {
                            list.add(value);
                        }
                    }
                } else {
                    // 前端用换行或逗号分隔的文本框编辑列表
                    for (String item : text.split("[,\\n]")) {
                        String value = item.trim();
                        if (!value.isEmpty()) {
                            list.add(value);
                        }
                    }
                }
                return list;
            }
            return text;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}

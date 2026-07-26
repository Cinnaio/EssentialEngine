package com.github.cinnaio.essentialengine.core.config;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 多语言消息管理。
 *
 * <p>语言文件放在 {@code plugins/EssentialEngine/messages_<语言>.yml}。
 * 玩家自定义的文件里如果缺少某个键，会自动回落到插件内置的同名文件，
 * 因此插件更新新增消息后不需要删档重建。</p>
 */
public class MessageManager {

    private static final String DEFAULT_LANGUAGE = "zh_CN";

    private final EssentialEngine plugin;
    private YamlConfiguration messages;
    private String prefix = "";

    public MessageManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        String language = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        // 内置语言文件先释放到插件目录，方便服主直接改
        saveIfAbsent("messages_zh_CN.yml");
        saveIfAbsent("messages_en_US.yml");

        File file = new File(plugin.getDataFolder(), "messages_" + language + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("找不到语言文件 messages_" + language + ".yml，已回退到 " + DEFAULT_LANGUAGE);
            file = new File(plugin.getDataFolder(), "messages_" + DEFAULT_LANGUAGE + ".yml");
            language = DEFAULT_LANGUAGE;
        }
        this.messages = YamlConfiguration.loadConfiguration(file);

        // 缺键回落：以内置文件作为默认值
        InputStream builtin = plugin.getResource("messages_" + language + ".yml");
        if (builtin == null) {
            builtin = plugin.getResource("messages_" + DEFAULT_LANGUAGE + ".yml");
        }
        if (builtin != null) {
            this.messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(builtin, StandardCharsets.UTF_8)));
        }
        this.prefix = messages.getString("general.prefix", "&8[&bEssentialEngine&8] &r");
    }

    private void saveIfAbsent(String name) {
        File target = new File(plugin.getDataFolder(), name);
        if (!target.exists() && plugin.getResource(name) != null) {
            plugin.saveResource(name, false);
        }
    }

    public String prefix() {
        return prefix;
    }

    /** 取原始消息文本（未解析颜色）。找不到时返回带键名的提示，方便排查。 */
    public String raw(String key, Object... placeholders) {
        if (messages == null) {
            return key;
        }
        String value;
        if (messages.isList(key)) {
            value = String.join("\n", messages.getStringList(key));
        } else {
            value = messages.getString(key);
        }
        if (value == null) {
            return "&c缺少消息键: " + key;
        }
        return Text.replace(value, mergePrefix(placeholders));
    }

    /** 该消息是否被服主清空（清空表示不发送）。 */
    public boolean isSilent(String key) {
        if (messages == null) {
            return false;
        }
        if (messages.isList(key)) {
            List<String> list = messages.getStringList(key);
            return list.isEmpty();
        }
        String value = messages.getString(key);
        return value != null && value.isEmpty();
    }

    public Component get(String key, Object... placeholders) {
        return Text.parse(raw(key, placeholders));
    }

    /** 发送一条消息；消息被清空时静默跳过。多行消息按行发送。 */
    public void send(CommandSender target, String key, Object... placeholders) {
        if (target == null || isSilent(key)) {
            return;
        }
        String text = raw(key, placeholders);
        for (String line : text.split("\n")) {
            target.sendMessage(Text.parse(line));
        }
    }

    /** 直接发送一段原始文本（自动处理 {prefix} 与颜色）。 */
    public void sendRaw(CommandSender target, String text, Object... placeholders) {
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        String result = Text.replace(text, mergePrefix(placeholders));
        for (String line : result.split("\n")) {
            target.sendMessage(Text.parse(line));
        }
    }

    private Object[] mergePrefix(Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return new Object[]{"prefix", prefix};
        }
        Object[] merged = new Object[placeholders.length + 2];
        System.arraycopy(placeholders, 0, merged, 0, placeholders.length);
        merged[placeholders.length] = "prefix";
        merged[placeholders.length + 1] = prefix;
        return merged;
    }
}

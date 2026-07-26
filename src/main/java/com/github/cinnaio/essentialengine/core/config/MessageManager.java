package com.github.cinnaio.essentialengine.core.config;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.util.Text;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多语言消息管理。
 *
 * <p>语言文件放在 {@code plugins/EssentialEngine/lang/<语言>.yml}，内置
 * {@code zh_CN} 与 {@code en_US}。玩家看到的消息<b>自动跟随其客户端语言</b>：
 * 中文客户端（zh_*）使用 zh_CN，其余语言回退到 en_US；服主往 lang/ 目录里
 * 添加 {@code ja_JP.yml} 之类的文件后，对应客户端会自动匹配，无需配置。
 * 控制台使用 config.yml 的 {@code language}。</p>
 *
 * <p>服主自定义的文件里如果缺少某个键，会依次回落到插件内置的同名文件、
 * en_US，因此插件更新新增消息后不需要删档重建。</p>
 *
 * <p>占位符的值除了字符串，还可以传 {@link Localized}（按接收者语言查另一个键）
 * 与 {@link TimeUtil.Duration} 等时间标记，发送时逐个接收者解析——同一条广播
 * 里的「永久」「控制台」也会跟随各自的语言。</p>
 */
public class MessageManager {

    /** 非中文客户端的回退语言。 */
    private static final String CLIENT_FALLBACK = "en_us";
    /** 内置语言文件（resources/lang/ 下）。 */
    private static final String[] BUILTIN = {"zh_CN", "en_US"};

    /**
     * 一个「按接收者语言解析」的占位符值：发送时用接收者的语言查 {@code key}，
     * 两份语言文件都没有该键时退回 {@code fallback}（为 null 则显示键名）。
     * 通过 {@link #localized} / {@link #localizedOr} 创建。
     */
    public record Localized(String key, Object[] args, String fallback) {
    }

    public static Localized localized(String key, Object... args) {
        return new Localized(key, args, null);
    }

    public static Localized localizedOr(String key, String fallback, Object... args) {
        return new Localized(key, args, fallback);
    }

    private final EssentialEngine plugin;
    /** 键为小写语言标签（zh_cn），值为该语言的配置（缺键回落到内置同名文件）。 */
    private final Map<String, YamlConfiguration> locales = new LinkedHashMap<>();
    private String consoleLocale = "zh_cn";

    public MessageManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        locales.clear();

        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建语言目录 " + dir.getAbsolutePath());
        }
        for (String builtin : BUILTIN) {
            saveIfAbsent("lang/" + builtin + ".yml");
        }
        warnLegacyFiles();

        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 4);
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                InputStream builtin = plugin.getResource("lang/" + name + ".yml");
                if (builtin != null) {
                    config.setDefaults(YamlConfiguration.loadConfiguration(
                            new InputStreamReader(builtin, StandardCharsets.UTF_8)));
                }
                locales.put(name.toLowerCase(Locale.ROOT), config);
            }
        }
        // 磁盘文件被删光时兜底加载内置文件，保证消息系统始终可用
        for (String builtin : BUILTIN) {
            String key = builtin.toLowerCase(Locale.ROOT);
            if (!locales.containsKey(key)) {
                InputStream stream = plugin.getResource("lang/" + builtin + ".yml");
                if (stream != null) {
                    locales.put(key, YamlConfiguration.loadConfiguration(
                            new InputStreamReader(stream, StandardCharsets.UTF_8)));
                }
            }
        }

        String configured = plugin.getConfig().getString("language", "zh_CN");
        this.consoleLocale = configured.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!locales.containsKey(consoleLocale)) {
            plugin.getLogger().warning("找不到语言文件 lang/" + configured + ".yml，控制台已回退到 en_US");
            this.consoleLocale = locales.containsKey(CLIENT_FALLBACK)
                    ? CLIENT_FALLBACK
                    : locales.keySet().stream().findFirst().orElse(CLIENT_FALLBACK);
        }
    }

    private void saveIfAbsent(String path) {
        File target = new File(plugin.getDataFolder(), path);
        if (!target.exists() && plugin.getResource(path) != null) {
            plugin.saveResource(path, false);
        }
    }

    /** 旧版（2.0 之前）语言文件已不再读取，提示服主可以删掉。 */
    private void warnLegacyFiles() {
        for (String old : new String[]{"messages_zh_CN.yml", "messages_en_US.yml"}) {
            if (new File(plugin.getDataFolder(), old).exists()) {
                plugin.getLogger().info("检测到旧版语言文件 " + old
                        + "，已改用 lang/ 目录，该文件不再生效，可自行删除。");
            }
        }
    }

    // ------------------------------------------------------------------ 语言解析

    /**
     * 解析接收者应使用的语言：玩家按客户端语言（精确匹配 → zh_* 归 zh_CN →
     * 同语言前缀 → en_US），控制台与命令方块用配置的 language。
     *
     * <p>优先使用玩家数据里记录的客户端语言——登录拦截（封禁画面）发生在
     * 客户端设置同步之前，此时 {@code player.locale()} 还是默认值，
     * 而上次会话存下来的语言是准确的。</p>
     */
    public String localeOf(CommandSender target) {
        if (!(target instanceof Player player)) {
            return consoleLocale;
        }
        String stored = storedLocaleOf(player);
        if (stored != null) {
            return resolveTag(stored);
        }
        Locale locale = player.locale();
        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry();
        return resolveTag(country.isEmpty() ? lang : lang + "_" + country.toLowerCase(Locale.ROOT));
    }

    private String storedLocaleOf(Player player) {
        if (plugin.users() == null) {
            return null;
        }
        var data = plugin.users().getIfLoaded(player.getUniqueId());
        return data == null ? null : data.getClientLocale();
    }

    /** 把 zh_cn 之类的语言标签解析到一个已加载的语言文件。 */
    private String resolveTag(String tag) {
        String full = tag.toLowerCase(Locale.ROOT).replace('-', '_');
        if (locales.containsKey(full)) {
            return full;
        }
        int split = full.indexOf('_');
        String lang = split < 0 ? full : full.substring(0, split);
        if (lang.equals("zh") && locales.containsKey("zh_cn")) {
            return "zh_cn";
        }
        // 服主自行添加的语言：同语言前缀即可匹配（fr_CA → fr_FR）
        String best = null;
        for (String key : locales.keySet()) {
            if (key.equals(lang) || key.startsWith(lang + "_")) {
                if (best == null || key.compareTo(best) < 0) {
                    best = key;
                }
            }
        }
        if (best != null) {
            return best;
        }
        return locales.containsKey(CLIENT_FALLBACK) ? CLIENT_FALLBACK : consoleLocale;
    }

    /** 取某语言下的消息模板；缺键依次回落：磁盘文件 → 内置同名文件 → en_US。 */
    private String template(String localeKey, String key) {
        String value = valueIn(locales.get(localeKey), key);
        if (value == null && !localeKey.equals(CLIENT_FALLBACK)) {
            value = valueIn(locales.get(CLIENT_FALLBACK), key);
        }
        return value;
    }

    private String valueIn(YamlConfiguration config, String key) {
        if (config == null) {
            return null;
        }
        if (config.isList(key)) {
            List<String> list = config.getStringList(key);
            return String.join("\n", list);
        }
        return config.getString(key);
    }

    /** 供聊天渲染器等需要「模板原文」的地方使用；找不到返回 null。 */
    public String templateFor(CommandSender target, String key) {
        return template(localeOf(target), key);
    }

    // ------------------------------------------------------------------ 渲染

    /** 按接收者语言取消息文本（颜色未解析），占位符已替换。 */
    public String raw(CommandSender target, String key, Object... placeholders) {
        return rawFor(localeOf(target), key, placeholders);
    }

    /** 同 {@link #raw}，但键不存在时返回 {@code fallback} 而不是错误提示。 */
    public String rawOr(CommandSender target, String key, String fallback, Object... placeholders) {
        String localeKey = localeOf(target);
        String template = template(localeKey, key);
        return template == null ? fallback : render(localeKey, template, placeholders);
    }

    /**
     * 把 {@link Localized} 与时间标记按接收者语言渲染成一段纯字符串。
     *
     * <p>给 PlaceholderAPI 这类「只要一个字符串、不需要整条消息」的外部集成用：
     * 同一个 {@code %ee_playtime%} 在中文客户端上是「3天2小时」，
     * 在英文客户端上是「3d 2h」。</p>
     */
    public String resolve(CommandSender target, Object value) {
        return stringify(localeOf(target), value);
    }

    private String rawFor(String localeKey, String key, Object[] placeholders) {
        String template = template(localeKey, key);
        if (template == null) {
            return "<#E06C75>missing message key: " + key;
        }
        return render(localeKey, template, placeholders);
    }

    /**
     * 把模板里的 {key} 占位符替换成解析后的值。模板是 MiniMessage 时，
     * 普通字符串值会经 {@link Text#miniValue} 转换：& 颜色代码转成标签、
     * 裸露的标签被转义——玩家输入无法注入 click 事件等 MiniMessage 结构。
     * {@link Localized} 与时间标记来自语言文件（服主可控），视为受信任，
     * 原样插入，因此它们可以携带自己的颜色标签。
     */
    private String render(String localeKey, String template, Object[] placeholders) {
        if (placeholders == null || placeholders.length < 2) {
            return template;
        }
        boolean mini = Text.isMini(template);
        String result = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]);
            Object raw = placeholders[i + 1];
            String value = stringify(localeKey, raw);
            boolean trusted = raw instanceof Localized || raw instanceof TimeUtil.Duration
                    || raw instanceof TimeUtil.DateTime || raw instanceof TimeUtil.Ago;
            result = result.replace("{" + key + "}", mini && !trusted ? Text.miniValue(value) : value);
        }
        return result;
    }

    /** 把占位符的值转成字符串；Localized 与时间标记按语言解析（可嵌套）。 */
    private String stringify(String localeKey, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Localized localized) {
            String template = template(localeKey, localized.key());
            if (template == null) {
                template = localized.fallback() != null ? localized.fallback() : localized.key();
            }
            // 子模板视为纯文本片段，占位符不做 MiniMessage 转义，由外层模板统一处理
            String result = template;
            Object[] args = localized.args();
            if (args != null) {
                for (int i = 0; i + 1 < args.length; i += 2) {
                    result = result.replace("{" + args[i] + "}", stringify(localeKey, args[i + 1]));
                }
            }
            return result;
        }
        if (value instanceof TimeUtil.Duration duration) {
            return formatDuration(localeKey, duration.millis());
        }
        if (value instanceof TimeUtil.DateTime dateTime) {
            return dateTime.timestamp() <= 0
                    ? stringify(localeKey, localized("time.never"))
                    : TimeUtil.formatDate(dateTime.timestamp());
        }
        if (value instanceof TimeUtil.Ago ago) {
            if (ago.timestamp() <= 0) {
                return stringify(localeKey, localized("time.never"));
            }
            return stringify(localeKey, localized("time.ago",
                    "duration", TimeUtil.duration(System.currentTimeMillis() - ago.timestamp())));
        }
        return String.valueOf(value);
    }

    /** 按语言把毫秒渲染成可读时长，单位词取自语言文件的 time 段。 */
    private String formatDuration(String localeKey, long millis) {
        String day = unit(localeKey, "time.day", "d");
        String hour = unit(localeKey, "time.hour", "h");
        String minute = unit(localeKey, "time.minute", "m");
        String second = unit(localeKey, "time.second", "s");
        String separator = unit(localeKey, "time.separator", "");

        if (millis <= 0) {
            return "0" + second;
        }
        long remaining = millis;
        long days = remaining / 86_400_000L;
        remaining %= 86_400_000L;
        long hours = remaining / 3_600_000L;
        remaining %= 3_600_000L;
        long minutes = remaining / 60_000L;
        remaining %= 60_000L;
        long seconds = remaining / 1000L;

        List<String> parts = new ArrayList<>(4);
        if (days > 0) {
            parts.add(days + day);
        }
        if (hours > 0) {
            parts.add(hours + hour);
        }
        if (minutes > 0) {
            parts.add(minutes + minute);
        }
        if (seconds > 0 && days == 0) {
            parts.add(seconds + second);
        }
        return parts.isEmpty() ? "0" + second : String.join(separator, parts);
    }

    private String unit(String localeKey, String key, String fallback) {
        String value = template(localeKey, key);
        return value == null ? fallback : value;
    }

    // ------------------------------------------------------------------ 发送

    /** 按接收者语言取消息组件（不拆行，换行符保留，适合踢出/封禁画面）。 */
    public Component get(CommandSender target, String key, Object... placeholders) {
        String localeKey = localeOf(target);
        String template = template(localeKey, key);
        if (template == null) {
            return Component.text("missing message key: " + key);
        }
        return Text.parseAs(Text.isMini(template), render(localeKey, template, placeholders));
    }

    /** 发送一条消息；服主把该键改成空字符串时静默跳过。多行消息按行发送。 */
    public void send(CommandSender target, String key, Object... placeholders) {
        if (target == null) {
            return;
        }
        String localeKey = localeOf(target);
        String template = template(localeKey, key);
        if (template == null) {
            target.sendMessage(Text.parse("<#E06C75>missing message key: " + key));
            return;
        }
        if (template.isEmpty()) {
            return;
        }
        boolean mini = Text.isMini(template);
        String text = render(localeKey, template, placeholders);
        for (String line : text.split("\n")) {
            target.sendMessage(Text.parseAs(mini, line));
        }
    }

    /** 直接发送一段模板文本（API / 配置驱动的内容），占位符规则与 send 相同。 */
    public void sendRaw(CommandSender target, String text, Object... placeholders) {
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        boolean mini = Text.isMini(text);
        String result = render(localeOf(target), text, placeholders);
        for (String line : result.split("\n")) {
            target.sendMessage(Text.parseAs(mini, line));
        }
    }
}

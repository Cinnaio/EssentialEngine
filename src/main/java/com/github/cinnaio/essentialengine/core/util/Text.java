package com.github.cinnaio.essentialengine.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * 文本 / 颜色处理工具。
 *
 * <p>同时兼容两种写法：</p>
 * <ul>
 *     <li>传统颜色代码：{@code &a你好}、{@code &#FF00FF十六进制色}</li>
 *     <li>MiniMessage：{@code <green>你好</green>}、{@code <#FF00FF>十六进制色}</li>
 * </ul>
 *
 * <p>解析模式按<b>模板</b>判断（出现成对尖括号即视为 MiniMessage），而不是按替换完
 * 占位符之后的最终字符串判断，避免玩家输入 {@code <3} 之类的内容意外改变整条消息
 * 的解析方式。同一条消息不要混用两种写法。</p>
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** 传统 & 颜色代码，同时支持 {@code &#RRGGBB} 十六进制。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Text() {
    }

    /** 模板是否按 MiniMessage 解析（含成对尖括号）。 */
    public static boolean isMini(String raw) {
        if (raw == null) {
            return false;
        }
        int open = raw.indexOf('<');
        return open >= 0 && raw.indexOf('>', open) > open;
    }

    /**
     * 把占位符的值转换成可以安全嵌入 MiniMessage 模板的形式：
     * 值里的传统 {@code &} 颜色代码会转换成对应标签，
     * 而裸露的 {@code <} 会被转义成字面文本——玩家无法通过输入
     * {@code <click:...>} 之类的标签注入点击事件或样式。
     */
    public static String miniValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return MINI.serialize(LEGACY.deserialize(value));
    }

    /** 按指定模式解析。MiniMessage 解析失败时回退到传统颜色代码。 */
    public static Component parseAs(boolean mini, String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (mini) {
            try {
                return MINI.deserialize(raw);
            } catch (Exception ignored) {
                // 解析失败时退回传统颜色代码
            }
        }
        return LEGACY.deserialize(raw);
    }

    /** 把原始字符串解析成 Adventure 组件，模式自动判断。 */
    public static Component parse(String raw) {
        return parseAs(isMini(raw), raw);
    }

    /** 解析并替换占位符，占位符写法为 {key}。 */
    public static Component parse(String raw, Object... placeholders) {
        return parse(replace(raw, placeholders));
    }

    /** 纯字符串占位符替换，占位符成对传入：{@code replace(s, "player", "Steve")}。 */
    public static String replace(String raw, Object... placeholders) {
        if (raw == null) {
            return "";
        }
        if (placeholders == null || placeholders.length < 2) {
            return raw;
        }
        String result = raw;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]);
            String value = placeholders[i + 1] == null ? "" : String.valueOf(placeholders[i + 1]);
            result = result.replace("{" + key + "}", value);
        }
        return result;
    }

    /** 去掉所有颜色，返回可读纯文本。 */
    public static String plain(String raw) {
        return PLAIN.serialize(parse(raw));
    }

    /** 组件转纯文本（与 {@link #plain(String)} 区分开，避免重载歧义）。 */
    public static String plainOf(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    /** 转成带 § 的传统字符串，用于必须使用 String 的旧 API（物品名、计分板等）。 */
    public static String legacy(String raw) {
        return SECTION.serialize(parse(raw));
    }

    /** 发送一条已解析好颜色的消息。 */
    public static void send(CommandSender target, String raw) {
        if (target == null || raw == null || raw.isEmpty()) {
            return;
        }
        target.sendMessage(parse(raw));
    }

    public static void send(CommandSender target, Component component) {
        if (target == null || component == null) {
            return;
        }
        target.sendMessage(component);
    }
}

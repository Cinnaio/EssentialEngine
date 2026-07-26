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
 *     <li>传统颜色代码：{@code &a你好}、{@code &#FF00FF渐变色}</li>
 *     <li>MiniMessage：{@code <green>你好</green>}、{@code <gradient:#ff0000:#00ff00>标题</gradient>}</li>
 * </ul>
 * 只要字符串里出现了成对的尖括号就按 MiniMessage 解析，否则按传统颜色代码解析。
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Text() {
    }

    /** 把原始字符串解析成 Adventure 组件。 */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (looksLikeMiniMessage(raw)) {
            try {
                return MINI.deserialize(raw);
            } catch (Exception ignored) {
                // 解析失败时退回传统颜色代码
            }
        }
        return LEGACY.deserialize(raw);
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

    private static boolean looksLikeMiniMessage(String raw) {
        int open = raw.indexOf('<');
        return open >= 0 && raw.indexOf('>', open) > open;
    }
}

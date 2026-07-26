package com.github.cinnaio.essentialengine.core.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时长解析与格式化工具。
 *
 * <p>支持 {@code 7d}、{@code 12h30m}、{@code 1w2d3h4m5s} 这样的写法，
 * 也支持中文单位 {@code 7天}、{@code 30分钟}。</p>
 */
public final class TimeUtil {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(\\d+)\\s*(y|year|年|mo|month|月|w|week|周|星期|d|day|天|h|hour|小时|时|m|min|minute|分钟|分|s|sec|second|秒)");

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    private TimeUtil() {
    }

    // ------------------------------------------------------------------ 本地化占位符
    //
    // 时长 / 时间放进消息占位符时不要直接传格式化好的字符串（那样只有一种语言），
    // 而是传下面这些标记对象，由 MessageManager 在发送时按接收者的语言渲染。

    /** 一段时长，按接收者语言渲染成「3天2小时」或「3d 2h」。 */
    public record Duration(long millis) {
    }

    /** 一个时间点；无效（&le;0）时渲染成语言文件里的 time.never。 */
    public record DateTime(long timestamp) {
    }

    /** 距今多久之前，按接收者语言渲染成「3小时前」或「3h ago」。 */
    public record Ago(long timestamp) {
    }

    public static Duration duration(long millis) {
        return new Duration(millis);
    }

    public static DateTime date(long timestamp) {
        return new DateTime(timestamp);
    }

    public static Ago ago(long timestamp) {
        return new Ago(timestamp);
    }

    /**
     * 解析时长字符串，返回毫秒数。
     *
     * @return 解析失败返回 -1；输入 {@code perm}/{@code permanent}/{@code 永久} 返回 0（表示永久）
     */
    public static long parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            return -1;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("forever") || value.equals("永久")) {
            return 0;
        }
        Matcher matcher = PATTERN.matcher(value);
        long total = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            total += amount * unitMillis(unit);
        }
        if (!matched) {
            // 纯数字按分钟处理
            try {
                return Long.parseLong(value) * MINUTE;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return total;
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "y", "year", "年" -> YEAR;
            case "mo", "month", "月" -> MONTH;
            case "w", "week", "周", "星期" -> WEEK;
            case "d", "day", "天" -> DAY;
            case "h", "hour", "小时", "时" -> HOUR;
            case "s", "sec", "second", "秒" -> SECOND;
            default -> MINUTE;
        };
    }

    /**
     * 把毫秒格式化成中文可读时长，例如 {@code 3天2小时5分钟}。
     *
     * <p>注意：发给玩家的消息不要用这个（只有中文），请改传 {@link #duration(long)}。</p>
     */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "0秒";
        }
        long remaining = millis;
        StringBuilder sb = new StringBuilder();
        long days = remaining / DAY;
        remaining %= DAY;
        long hours = remaining / HOUR;
        remaining %= HOUR;
        long minutes = remaining / MINUTE;
        remaining %= MINUTE;
        long seconds = remaining / SECOND;

        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0) {
            sb.append(minutes).append("分钟");
        }
        if (seconds > 0 && days == 0) {
            sb.append(seconds).append("秒");
        }
        return sb.length() == 0 ? "0秒" : sb.toString();
    }

    /** 格式化时间戳，例如 {@code 2026-07-26 14:03:11}。 */
    public static String formatDate(long timestamp) {
        if (timestamp <= 0) {
            return "从未";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    /** 距离现在多久（过去），例如 {@code 3小时前}。 */
    public static String formatAgo(long timestamp) {
        if (timestamp <= 0) {
            return "从未";
        }
        return formatDuration(System.currentTimeMillis() - timestamp) + "前";
    }
}

package com.github.cinnaio.essentialengine.module.world;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 世界控制模块：时间与天气。
 *
 * <p>{@code /time} 支持原版风格的关键字（day/night/noon/midnight）与
 * {@code set <刻>}、{@code add <刻>}、{@code query}；{@code /weather} 支持
 * clear/rain/thunder，并可附带持续时长。玩家执行默认作用于自己所在世界，
 * 控制台或带 {@code [世界]} 参数时作用于指定世界。</p>
 *
 * <p>时间与天气都是世界级状态，在 Folia 上统一走全局区域线程修改。</p>
 */
public class WorldModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";

    /** 原版风格的时间关键字（刻）。 */
    private static final long TICK_DAY = 1000L;
    private static final long TICK_NOON = 6000L;
    private static final long TICK_SUNSET = 12000L;
    private static final long TICK_NIGHT = 13000L;
    private static final long TICK_MIDNIGHT = 18000L;
    private static final long TICK_SUNRISE = 23000L;

    private enum Weather {
        CLEAR("world.weather-clear"),
        RAIN("world.weather-rain"),
        THUNDER("world.weather-thunder");

        final String key;

        Weather(String key) {
            this.key = key;
        }
    }

    public WorldModule(EssentialEngine plugin) {
        super(plugin, "world", "世界控制");
    }

    @Override
    protected void setup() {
        command("time").permission(PERM + "time").description("查看 / 设置世界时间")
                .usage("/time <day|night|noon|midnight|set <刻>|add <刻>|query> [世界]")
                .handler(this::time)
                .completer(this::completeTime);

        command("weather").aliases("wea").permission(PERM + "weather").description("设置世界天气")
                .usage("/weather <clear|rain|thunder> [时长] [世界]").minArgs(1)
                .handler(this::weather)
                .completer(this::completeWeather);
    }

    // ------------------------------------------------------------------ /time

    private void time(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            queryTime(sender, resolveWorld(sender, null));
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "query" -> queryTime(sender, resolveWorld(sender, args.length > 1 ? args[1] : null));
            case "set" -> {
                if (args.length < 2) {
                    throw usageError();
                }
                long ticks = parseTimeValue(args[1]);
                applyTime(sender, resolveWorld(sender, args.length > 2 ? args[2] : null),
                        ticks, false, String.valueOf(ticks));
            }
            case "add" -> {
                if (args.length < 2) {
                    throw usageError();
                }
                long ticks = Long.parseLong(stripTicks(args[1]));
                applyTime(sender, resolveWorld(sender, args.length > 2 ? args[2] : null),
                        ticks, true, "+" + ticks);
            }
            // 简化写法：/time day、/time 6000
            default -> {
                long ticks = parseTimeValue(action);
                applyTime(sender, resolveWorld(sender, args.length > 1 ? args[1] : null),
                        ticks, false, String.valueOf(ticks));
            }
        }
    }

    private void queryTime(CommandSender sender, World world) {
        SchedulerCompat.runGlobal(plugin, () ->
                plugin.messages().send(sender, "world.time-query",
                        "world", world.getName(), "time", String.valueOf(world.getTime())));
    }

    private void applyTime(CommandSender sender, World world, long ticks, boolean add, String display) {
        SchedulerCompat.runGlobal(plugin, () -> {
            if (add) {
                world.setFullTime(world.getFullTime() + ticks);
            } else {
                world.setTime(ticks);
            }
            plugin.messages().send(sender, "world.time-set", "world", world.getName(), "time", display);
        });
    }

    /** 关键字或纯数字（可带尾缀 t）→ 一天内的刻数。无法识别时抛错。 */
    private long parseTimeValue(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "day", "白天", "早上" -> TICK_DAY;
            case "noon", "中午", "正午" -> TICK_NOON;
            case "sunset", "日落", "黄昏" -> TICK_SUNSET;
            case "night", "夜晚", "晚上" -> TICK_NIGHT;
            case "midnight", "午夜", "半夜" -> TICK_MIDNIGHT;
            case "sunrise", "日出", "黎明" -> TICK_SUNRISE;
            default -> {
                try {
                    yield Long.parseLong(stripTicks(raw));
                } catch (NumberFormatException error) {
                    throw new CommandError("world.time-unknown", "value", raw);
                }
            }
        };
    }

    private String stripTicks(String raw) {
        String value = raw.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith("t")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    // ------------------------------------------------------------------ /weather

    private void weather(CommandSender sender, String label, String[] args) {
        Weather weather = parseWeather(args[0]);
        int durationTicks = args.length > 1 ? parseWeatherDuration(args[1]) : 0;
        World world = resolveWorld(sender, args.length > 2 ? args[2] : null);
        SchedulerCompat.runGlobal(plugin, () -> {
            switch (weather) {
                case CLEAR -> {
                    world.setStorm(false);
                    world.setThundering(false);
                    if (durationTicks > 0) {
                        world.setWeatherDuration(durationTicks);
                    }
                }
                case RAIN -> {
                    world.setStorm(true);
                    world.setThundering(false);
                    if (durationTicks > 0) {
                        world.setWeatherDuration(durationTicks);
                    }
                }
                case THUNDER -> {
                    world.setStorm(true);
                    world.setThundering(true);
                    if (durationTicks > 0) {
                        world.setWeatherDuration(durationTicks);
                        world.setThunderDuration(durationTicks);
                    }
                }
            }
            plugin.messages().send(sender, "world.weather-set",
                    "world", world.getName(), "weather", MessageManager.localized(weather.key));
        });
    }

    private Weather parseWeather(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "clear", "sun", "sunny", "晴", "晴天" -> Weather.CLEAR;
            case "rain", "rainy", "雨", "下雨" -> Weather.RAIN;
            case "thunder", "storm", "thunderstorm", "雷", "雷暴" -> Weather.THUNDER;
            default -> throw new CommandError("world.weather-unknown", "value", raw);
        };
    }

    /** 天气时长：支持 {@code 5m}/{@code 1h} 这类时长串，或纯数字（按秒算）。 */
    private int parseWeatherDuration(String raw) {
        long millis = TimeUtil.parseDuration(raw);
        if (millis > 0) {
            return (int) Math.min(Integer.MAX_VALUE, millis / 50L);
        }
        // TimeUtil 拒绝纯数字，这里把它当作秒
        return Integer.parseInt(raw.trim()) * 20;
    }

    // ------------------------------------------------------------------ 工具

    private World resolveWorld(CommandSender sender, String name) {
        if (name != null) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                throw new CommandError("world.world-not-found", "world", name);
            }
            return world;
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            throw new CommandError("world.world-not-found", "world", "?");
        }
        return worlds.get(0);
    }

    private CommandError usageError() {
        return new CommandError("general.usage", "usage",
                MessageManager.localizedOr("usage.time",
                        "/time <day|night|noon|midnight|set <刻>|add <刻>|query> [世界]"));
    }

    private List<String> completeTime(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return new ArrayList<>(List.of(
                    "day", "night", "noon", "midnight", "sunrise", "sunset", "set", "add", "query"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) {
            return List.of("0", "1000", "6000", "13000", "18000");
        }
        return worldNames();
    }

    private List<String> completeWeather(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return new ArrayList<>(List.of("clear", "rain", "thunder"));
        }
        if (args.length == 2) {
            return List.of("30", "300", "5m", "10m");
        }
        return worldNames();
    }

    private List<String> worldNames() {
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            names.add(world.getName());
        }
        return names;
    }
}

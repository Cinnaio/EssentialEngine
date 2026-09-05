package com.github.cinnaio.essentialengine.module.monitor;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.MonitorEvent;
import com.github.cinnaio.essentialengine.core.storage.PerfSample;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 性能监控模块。
 *
 * <p>定时采样 TPS / 内存 / 在线人数，自动记录性能事件（严重卡顿、TPS 恢复、
 * 内存占用过高、启动 / 关闭 / 重载、异常退出），并把数据提供给：
 * <ul>
 *   <li>{@code /eemonitor} 命令（管理员在游戏内 / 控制台查看）</li>
 *   <li>webapi 模块的 {@code /api/monitor/*} 接口（为 AstrBot 等外部程序预留）</li>
 *   <li>网页管理面板的「监控」页</li>
 * </ul>
 * 模块关闭时命令与接口都会一并消失。</p>
 */
public class MonitorModule extends EngineModule {

    private static final String PERM = "essentialengine.command.eemonitor";

    private MonitorService service;

    public MonitorModule(EssentialEngine plugin) {
        super(plugin, "monitor", "性能监控");
    }

    @Override
    protected void setup() {
        MonitorConfig config = new MonitorConfig(
                Math.max(2, cfgInt("sample-interval-seconds", 10)),
                Math.max(5, cfgInt("flush-seconds", 15)),
                Math.max(1, cfgInt("retention-days", 7)),
                Math.max(64, cfgInt("memory-samples", 8640)),
                cfgBool("record-lag", true),
                Math.max(1, cfgDouble("lag-threshold-tps", 15)),
                Math.max(10, cfgInt("lag-cooldown-seconds", 60)),
                Math.max(50, cfgDouble("lag-tick-threshold-ms", 100)),
                Math.max(1, cfgInt("lag-recovery-ticks", 40)),
                cfgBool("capture-thread-stacks", true),
                Math.max(50, cfgInt("thread-stack-sample-interval-ms", 100)),
                Math.max(4, Math.min(16, cfgInt("thread-stack-depth", 8))),
                cfgBool("integrate-spark", true),
                cfgBool("record-memory", true),
                Math.max(1, Math.min(99, cfgInt("memory-warning-percent", 90))),
                Math.max(1, cfgInt("memory-cooldown-minutes", 5)),
                cfgBool("record-start-stop", true),
                cfgBool("allow-custom-events", true));

        if (service == null) {
            service = new MonitorService(plugin, config);
            service.start();
            plugin.getLogger().info("[Monitor] 性能监控已启动，采样间隔 "
                    + config.sampleIntervalSeconds() + " 秒，卡顿阈值 TPS "
                    + String.format("%.1f", config.lagThresholdTps()));
        } else {
            // /ee reload：shutdown() 已经记过 reload 事件，这里换配置并恢复运行
            service.restart(config);
        }

        listener(new MonitorListener());

        command("eemonitor")
                .permission(PERM)
                .description("查看性能监控与事件记录")
                .usage("/eemonitor <status|events|samples|sessions|record>")
                .handler(this::handle)
                .completer(this::complete);
    }

    @Override
    protected void shutdown() {
        if (service != null) {
            service.stop(MonitorService.isServerStopping());
        }
    }

    /** 供 webapi 模块挂载 /api/monitor/* 接口。 */
    public MonitorService service() {
        return service;
    }

    // ------------------------------------------------------------------ 命令

    private void handle(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender);
            case "events" -> events(sender, args);
            case "samples" -> samples(sender, args);
            case "sessions" -> sessions(sender);
            case "record" -> record(sender, args);
            default -> throw new CommandError("general.usage", "usage",
                    MessageManager.localizedOr("usage.eemonitor",
                            "/eemonitor <status|events|samples|sessions|record>"));
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return new ArrayList<>(List.of("status", "events", "samples", "sessions", "record"));
        }
        return List.of();
    }

    private void status(CommandSender sender) {
        Map<String, Object> status = service.status();
        plugin.messages().send(sender, "monitor.header");
        @SuppressWarnings("unchecked")
        Map<String, Object> tps = (Map<String, Object>) status.get("tps");
        plugin.messages().send(sender, "monitor.tps",
                "now", String.valueOf(tps.get("now")),
                "tps1m", String.valueOf(tps.get("1m")),
                "tps5m", String.valueOf(tps.get("5m")),
                "tps15m", String.valueOf(tps.get("15m")));
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) status.get("memory");
        long used = ((Number) memory.get("usedMB")).longValue();
        long max = ((Number) memory.get("maxMB")).longValue();
        int percent = max <= 0 ? 0 : (int) Math.round(used * 100.0 / max);
        plugin.messages().send(sender, "monitor.memory",
                "used", String.valueOf(used),
                "max", String.valueOf(max),
                "percent", String.valueOf(percent));
        plugin.messages().send(sender, "monitor.online",
                "online", String.valueOf(status.get("online")));
        @SuppressWarnings("unchecked")
        Map<String, Object> tick = (Map<String, Object>) status.get("tick");
        if (tick != null) {
            plugin.messages().send(sender, "monitor.tick",
                    "last", String.valueOf(tick.get("lastDurationMs")),
                    "max", String.valueOf(tick.get("maxDurationMs")),
                    "threshold", String.valueOf(tick.get("thresholdMs")));
        }
        plugin.messages().send(sender, "monitor.uptime",
                "uptime", TimeUtil.duration(service.uptimeMs()));
        @SuppressWarnings("unchecked")
        Map<String, Object> counters = (Map<String, Object>) status.get("counters");
        long total = counters.values().stream().mapToLong(value -> ((Number) value).longValue()).sum();
        plugin.messages().send(sender, "monitor.totals",
                "samples", String.valueOf(status.get("samples")),
                "events", String.valueOf(total));
    }

    private void events(CommandSender sender, String[] args) {
        int limit = args.length > 1 ? parseInt(args[1], 15) : 15;
        SchedulerCompat.runAsync(plugin, () -> {
            List<MonitorEvent> events = service.events(limit, null, 0);
            SchedulerCompat.runGlobal(plugin, () -> {
                if (events.isEmpty()) {
                    plugin.messages().send(sender, "monitor.no-data");
                    return;
                }
                plugin.messages().send(sender, "monitor.events-header", "count", String.valueOf(events.size()));
                for (MonitorEvent event : events) {
                    plugin.messages().send(sender, "monitor.events-entry",
                            "time", TimeUtil.formatDate(event.timestamp()),
                            "type", event.type(),
                            "message", event.message(),
                            "detail", eventDetail(event));
                }
            });
        });
    }

    private void samples(CommandSender sender, String[] args) {
        int minutes = args.length > 1 ? Math.max(1, parseInt(args[1], 10)) : 10;
        SchedulerCompat.runAsync(plugin, () -> {
            List<PerfSample> samples = service.samples(minutes, 15);
            SchedulerCompat.runGlobal(plugin, () -> {
                if (samples.isEmpty()) {
                    plugin.messages().send(sender, "monitor.no-data");
                    return;
                }
                plugin.messages().send(sender, "monitor.samples-header",
                        "minutes", String.valueOf(minutes), "count", String.valueOf(samples.size()));
                for (PerfSample sample : samples) {
                    plugin.messages().send(sender, "monitor.samples-entry",
                            "time", TimeUtil.formatDate(sample.timestamp()),
                            "tps", String.format("%.1f", sample.tps()),
                            "used", String.valueOf(sample.usedMB()),
                            "online", String.valueOf(sample.online()));
                }
            });
        });
    }

    private void sessions(CommandSender sender) {
        SchedulerCompat.runAsync(plugin, () -> {
            List<Map<String, Object>> sessions = service.sessions(10);
            SchedulerCompat.runGlobal(plugin, () -> {
                if (sessions.isEmpty()) {
                    plugin.messages().send(sender, "monitor.no-data");
                    return;
                }
                plugin.messages().send(sender, "monitor.sessions-header", "count", String.valueOf(sessions.size()));
                for (Map<String, Object> session : sessions) {
                    long start = ((Number) session.get("start")).longValue();
                    long stop = ((Number) session.get("stop")).longValue();
                    boolean running = Boolean.TRUE.equals(session.get("running"));
                    // Localized 对象会按接收者语言解析，因此这里不做 String 转换
                    Object abnormal = Boolean.TRUE.equals(session.get("abnormal"))
                            ? MessageManager.localizedOr("monitor.sessions-abnormal", "，异常退出") : "";
                    Object stopText = running
                            ? MessageManager.localizedOr("monitor.sessions-running", "（进行中）")
                            : TimeUtil.formatDate(stop);
                    String duration = stop > start
                            ? TimeUtil.formatDuration(stop - start) : "";
                    plugin.messages().send(sender, "monitor.sessions-entry",
                            "start", TimeUtil.formatDate(start),
                            "stop", stopText,
                            "duration", duration,
                            "abnormal", abnormal);
                }
            });
        });
    }

    private void record(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new CommandError("general.usage", "usage",
                    MessageManager.localizedOr("usage.eemonitor",
                            "/eemonitor <status|events|samples|sessions|record>"));
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        String message = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : type;
        if (!service.recordCustomEvent(type, message, Map.of("source", sender.getName()))) {
            plugin.messages().send(sender, "monitor.custom-disabled");
            return;
        }
        plugin.messages().send(sender, "monitor.recorded", "type", type);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    /** 命令行只展示短摘要，完整堆栈与 Spark 指标仍通过面板 / REST API 查看。 */
    private static String eventDetail(MonitorEvent event) {
        Map<String, Object> data = event.data();
        if (data == null || data.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addDetail(parts, data, "trigger", "触发");
        addDetail(parts, data, "durationMs", "持续", true);
        addDetail(parts, data, "tickDurationMs", "Tick", true);
        addDetail(parts, data, "maxTickDurationMs", "最高Tick", true);
        addDetail(parts, data, "minTps", "最低TPS", false);
        Object cause = data.get("suspectedCause");
        if (cause != null) {
            parts.add("疑似原因 " + causeLabel(cause));
        }
        Object diagnosis = data.get("diagnosis");
        if (diagnosis instanceof Map<?, ?> diagnosisMap
                && diagnosisMap.get("evidence") instanceof List<?> evidence
                && !evidence.isEmpty()) {
            parts.add("证据 " + evidence.get(0));
        }
        addDetail(parts, data, "gcPauseMs", "GC暂停", true);
        return String.join("，", parts);
    }

    private static String causeLabel(Object cause) {
        return switch (String.valueOf(cause)) {
            case "gc_pause" -> "GC 暂停";
            case "main_thread_blocking" -> "主线程阻塞 / IO";
            case "world_or_chunk_work" -> "世界 / 区块计算";
            case "entity_tick_work" -> "实体 Tick";
            case "cpu_pressure" -> "CPU 压力";
            case "unknown" -> "暂未确定";
            default -> String.valueOf(cause);
        };
    }

    private static void addDetail(List<String> parts, Map<String, Object> data,
                                  String key, String label) {
        Object value = data.get(key);
        if (value != null) {
            parts.add(label + " " + value);
        }
    }

    private static void addDetail(List<String> parts, Map<String, Object> data,
                                  String key, String label, boolean milliseconds) {
        Object value = data.get(key);
        if (!(value instanceof Number number)) {
            return;
        }
        parts.add(label + " " + String.format("%.1f", number.doubleValue())
                + (milliseconds ? "ms" : ""));
    }

    // ------------------------------------------------------------------ 事件监听
    //
    // 只维护在线人数计数（采样需要），不记录进出服事件——自动事件仅限性能相关，
    // 玩家进出服的记录量大且与性能无关，按需求不纳入。

    private class MonitorListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            service.playerJoined();
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            service.playerLeft();
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onTickStart(ServerTickStartEvent event) {
            service.tickStarted(event.getTickNumber());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onTickEnd(ServerTickEndEvent event) {
            service.tickEnded(event.getTickNumber(), event.getTickDuration());
        }
    }
}

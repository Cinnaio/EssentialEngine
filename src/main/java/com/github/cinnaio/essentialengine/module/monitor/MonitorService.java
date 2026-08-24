package com.github.cinnaio.essentialengine.module.monitor;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.MonitorEvent;
import com.github.cinnaio.essentialengine.core.storage.PerfSample;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 性能监控与事件记录的核心服务。
 *
 * <p><b>采样不打扰主线程</b>：采样定时任务跑在异步线程，TPS 读的是 Paper 服务端缓存的
 * 读数、内存读 {@link Runtime}、在线人数由进出服事件计数器维护——主线程即使已经卡死，
 * 采样器也依然能记录下当时的 TPS，这正是排查卡顿需要的数据。</p>
 *
 * <p><b>事件只入队不落盘</b>：事件可能在主线程（监听器）或异步线程（采样）产生，
 * 记录只往内存队列里塞，由定时任务批量异步写存储（与 {@code EconomyLedger} 同一套
 * 设计），队列有上限，写满了宁可丢最旧的记录也不拖慢服务器。</p>
 *
 * <p><b>持久化跟随 storage.type</b>：YAML 后端写成 JSONL 文件，SQLite / MySQL 建
 * {@code ee_monitor_events} / {@code ee_monitor_samples} 表。会话状态
 * （上次启动时间、是否正常关闭）走存储的全局键 {@code monitor_state}，
 * 用于在下次启动时识别「上次是不是异常退出」。</p>
 */
public class MonitorService {

    /** 事件类型常量（对外公开，AstrBot 等调用方按这些值过滤）。 */
    public static final String EVENT_SERVER_START = "server_start";
    public static final String EVENT_SERVER_STOP = "server_stop";
    public static final String EVENT_ABNORMAL_SHUTDOWN = "abnormal_shutdown";
    public static final String EVENT_RELOAD = "reload";
    public static final String EVENT_LAG = "lag";
    public static final String EVENT_LAG_RECOVERED = "lag_recovered";
    public static final String EVENT_MEMORY_HIGH = "memory_high";

    /** 存储里的会话状态全局键。 */
    private static final String STATE_KEY = "monitor_state";

    /** 事件队列上限。写满时丢弃最旧的记录。 */
    private static final int EVENT_QUEUE_LIMIT = 20_000;
    private static final int SAMPLE_QUEUE_LIMIT = 20_000;

    private final EssentialEngine plugin;

    private MonitorConfig config;

    /** 内存里保留的最近采样（环形缓冲，头部是最旧的），status / 面板快速查询用。 */
    private final Deque<PerfSample> recentSamples = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<MonitorEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PerfSample> pendingSamples = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingEventCount = new AtomicInteger();
    private final AtomicInteger pendingSampleCount = new AtomicInteger();
    /** 事件类型 -> 本次运行累计条数（status 接口用）。 */
    private final Map<String, AtomicLong> eventCounters = new ConcurrentHashMap<>();
    /** 本次运行的采样总数。 */
    private final AtomicLong sampleCount = new AtomicLong();

    /** 当前在线人数，由进出服监听器维护（采样线程不需要碰主线程）。 */
    private final AtomicInteger onlineCount = new AtomicInteger();
    /** 本次会话的启动时间。 */
    private final AtomicLong startTime = new AtomicLong();
    /** 上一次卡顿事件的时间，用于去重。 */
    private final AtomicLong lastLagEvent = new AtomicLong();
    /** 上一次内存告警事件的时间，用于去重。 */
    private final AtomicLong lastMemoryEvent = new AtomicLong();
    /** 卡顿是否处于未恢复状态（用于 TPS 恢复时补记 lag_recovered）。 */
    private volatile boolean lagActive;

    private Object sampleHandle;
    private Object flushHandle;
    private Object pruneHandle;

    public MonitorService(EssentialEngine plugin, MonitorConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isRunning() {
        return sampleHandle != null;
    }

    public MonitorConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 首次启用：补记异常退出、记录本次启动、开始采样。
     *
     * <p>异常退出识别：上次会话结束时如果既没有记录 server_stop、也没有记录 reload
     * （即 {@code stopRecorded == false}），说明进程没有走完关服流程（崩溃 / 强杀），
     * 在本次启动时补记一条 {@link #EVENT_ABNORMAL_SHUTDOWN}。</p>
     */
    public void start() {
        long now = System.currentTimeMillis();
        SessionState state = loadState();
        if (config.recordStartStop()) {
            if (state.lastStart() > 0 && !state.stopRecorded()) {
                recordEvent(EVENT_ABNORMAL_SHUTDOWN,
                        "上次会话异常退出（该会话自 " + TimeUtil.formatDate(state.lastStart()) + " 开始）",
                        Map.of("startedAt", state.lastStart()));
                plugin.getLogger().warning("[Monitor] 检测到上次会话异常退出，已记录异常关闭事件");
            }
            recordEvent(EVENT_SERVER_START, "服务器启动", Map.of(
                    "version", plugin.getDescription().getVersion(),
                    "server", safeServerVersion()));
        }
        saveState(new SessionState(now, false));
        startTime.set(now);

        // 热重载插件时把已经在线的人数补进来（事件监听器在 enableAll 之后才生效，
        // 可能错过启用瞬间的登录）
        onlineCount.set(Math.max(0, MainThread.call(plugin, () -> Bukkit.getOnlinePlayers().size(), 0)));

        startTimers();
    }

    /**
     * 热重载：替换配置、重启定时任务。
     *
     * <p>{@code shutdown()} 已经记过 reload 事件并更新了会话状态，这里只恢复运行。</p>
     */
    public void restart(MonitorConfig newConfig) {
        this.config = newConfig;
        stopTimers();
        startTimers();
    }

    /**
     * 停止（关服 / 重载 / 模块卸载都会走到这里）。
     *
     * <p>服务端真正关停时记录 {@code server_stop}，重载则记录 {@code reload}——
     * 两者都会把会话状态标记为「正常结束」，避免下次启动误报异常退出。
     * 结束时同步落盘一次，把队列里攒的数据全部写下去。</p>
     */
    public void stop(boolean serverStopping) {
        stopTimers();
        if (config.recordStartStop()) {
            if (serverStopping) {
                recordEvent(EVENT_SERVER_STOP, "服务器关闭", Map.of("uptimeMs", uptimeMs()));
            } else {
                recordEvent(EVENT_RELOAD, "插件配置重载", Map.of());
            }
        }
        saveState(new SessionState(startTime.get(), true));
        flush();
    }

    private void stopTimers() {
        SchedulerCompat.cancel(sampleHandle);
        SchedulerCompat.cancel(flushHandle);
        SchedulerCompat.cancel(pruneHandle);
        sampleHandle = null;
        flushHandle = null;
        pruneHandle = null;
    }

    private void startTimers() {
        long interval = Math.max(1, config.sampleIntervalSeconds()) * 20L;
        sampleHandle = SchedulerCompat.runTimerAsync(plugin, this::sample, interval, interval);
        long flush = Math.max(1, config.flushSeconds()) * 20L;
        flushHandle = SchedulerCompat.runTimerAsync(plugin, this::flush, flush, flush);
        long hour = 20L * 60 * 60;
        pruneHandle = SchedulerCompat.runTimerAsync(plugin, this::prune, hour, hour);
    }

    // ------------------------------------------------------------------ 采样与告警

    /** 采集一次性能采样，并按阈值产生卡顿 / 内存告警事件。在异步线程运行。 */
    private void sample() {
        double tps = readTps();
        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMB = runtime.maxMemory() / 1024 / 1024;
        int online = Math.max(0, onlineCount.get());

        PerfSample sample = new PerfSample(System.currentTimeMillis(), tps, usedMB, maxMB, online);
        sampleCount.incrementAndGet();
        synchronized (recentSamples) {
            recentSamples.addLast(sample);
            while (recentSamples.size() > Math.max(64, config.memorySamples())) {
                recentSamples.pollFirst();
            }
        }
        enqueueSample(sample);

        checkLag(tps, online, usedMB, maxMB);
        checkMemory(usedMB, maxMB);
    }

    /**
     * 卡顿检测：TPS 跌破阈值记 {@code lag}，恢复回阈值 + 2 记 {@code lag_recovered}。
     * 同一次卡顿用冷却时间去重，避免 TPS 在阈值附近抖动时刷屏。
     */
    private void checkLag(double tps, int online, long usedMB, long maxMB) {
        if (!config.recordLag() || tps <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!lagActive && tps < config.lagThresholdTps()) {
            long cooldown = Math.max(10, config.lagCooldownSeconds()) * 1000L;
            if (now - lastLagEvent.get() >= cooldown && lastLagEvent.compareAndSet(lastLagEvent.get(), now)) {
                recordEvent(EVENT_LAG,
                        "TPS 跌至 " + String.format("%.1f", tps)
                                + "（阈值 " + String.format("%.1f", config.lagThresholdTps()) + "）",
                        Map.of("tps", tps, "threshold", config.lagThresholdTps(),
                                "online", online, "usedMB", usedMB, "maxMB", maxMB));
                plugin.getLogger().warning("[Monitor] 检测到严重卡顿：TPS " + String.format("%.1f", tps));
            }
            lagActive = true;
        } else if (lagActive && tps >= config.lagThresholdTps() + 2) {
            lagActive = false;
            recordEvent(EVENT_LAG_RECOVERED,
                    "TPS 恢复至 " + String.format("%.1f", tps),
                    Map.of("tps", tps));
            plugin.getLogger().info("[Monitor] 卡顿已恢复：TPS " + String.format("%.1f", tps));
        }
    }

    /** 内存告警：已用内存占比超过阈值时记 {@code memory_high}，按冷却去重。 */
    private void checkMemory(long usedMB, long maxMB) {
        if (!config.recordMemory() || maxMB <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (usedMB * 100 >= maxMB * config.memoryWarningPercent()) {
            long cooldown = Math.max(60, config.memoryCooldownMinutes()) * 60_000L;
            if (now - lastMemoryEvent.get() >= cooldown
                    && lastMemoryEvent.compareAndSet(lastMemoryEvent.get(), now)) {
                recordEvent(EVENT_MEMORY_HIGH,
                        "内存占用过高：" + usedMB + "MB / " + maxMB + "MB（"
                                + Math.round(usedMB * 100.0 / maxMB) + "%）",
                        Map.of("usedMB", usedMB, "maxMB", maxMB,
                                "percent", Math.round(usedMB * 100.0 / maxMB)));
                plugin.getLogger().warning("[Monitor] 内存占用过高：" + usedMB + "MB / " + maxMB + "MB");
            }
        }
    }

    /** 读 TPS。Paper 上这是服务端缓存的读数，任何线程都能安全读取。 */
    private double readTps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return tps != null && tps.length > 0 ? tps[0] : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 事件记录

    /**
     * 记录一条事件。可能在主线程 / 异步线程 / HTTP 线程被调用，因此只入队不落盘。
     *
     * <p>这是对外预留的 Java 接口：其它模块或未来的 AstrBot 桥接插件
     * 可以直接调用它写入自定义事件。</p>
     */
    public void recordEvent(String type, String message, Map<String, Object> data) {
        if (type == null || type.isBlank()) {
            return;
        }
        while (pendingEventCount.get() >= EVENT_QUEUE_LIMIT) {
            if (pendingEvents.poll() == null) {
                break;
            }
            pendingEventCount.decrementAndGet();
        }
        pendingEvents.add(new MonitorEvent(System.currentTimeMillis(), type,
                message == null ? "" : message, data));
        pendingEventCount.incrementAndGet();
        eventCounters.computeIfAbsent(type, key -> new AtomicLong()).incrementAndGet();
    }

    /** 进出服监听器维护在线人数用（只改计数，不记录事件）。 */
    public void playerJoined() {
        onlineCount.incrementAndGet();
    }

    public void playerLeft() {
        onlineCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    /** 记录一条外部写入的自定义事件（AstrBot 预留接口），未开启时返回 false。 */
    public boolean recordCustomEvent(String type, String message, Map<String, Object> data) {
        if (!config.allowCustomEvents()) {
            return false;
        }
        recordEvent(type, message, data);
        return true;
    }

    // ------------------------------------------------------------------ 落盘与清理

    private void enqueueSample(PerfSample sample) {
        while (pendingSampleCount.get() >= SAMPLE_QUEUE_LIMIT) {
            if (pendingSamples.poll() == null) {
                break;
            }
            pendingSampleCount.decrementAndGet();
        }
        pendingSamples.add(sample);
        pendingSampleCount.incrementAndGet();
    }

    /** 把队列里攒的采样与事件批量写进存储。可能在异步线程或 HTTP 线程调用。 */
    public void flush() {
        List<MonitorEvent> events = new ArrayList<>();
        MonitorEvent event;
        while ((event = pendingEvents.poll()) != null) {
            pendingEventCount.decrementAndGet();
            events.add(event);
        }
        List<PerfSample> samples = new ArrayList<>();
        PerfSample sample;
        while ((sample = pendingSamples.poll()) != null) {
            pendingSampleCount.decrementAndGet();
            samples.add(sample);
        }
        if (events.isEmpty() && samples.isEmpty()) {
            return;
        }
        try {
            if (!events.isEmpty()) {
                plugin.storage().appendMonitorEvents(events);
            }
            if (!samples.isEmpty()) {
                plugin.storage().appendMonitorSamples(samples);
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "写入监控数据失败，本批记录已丢弃", error);
        }
    }

    /** 清理超期数据。每小时跑一次。 */
    private void prune() {
        long cutoff = System.currentTimeMillis() - Math.max(1, config.retentionDays()) * 86_400_000L;
        try {
            int events = plugin.storage().pruneMonitorEvents(cutoff);
            int samples = plugin.storage().pruneMonitorSamples(cutoff);
            if ((events > 0 || samples > 0) && plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("清理了 " + events + " 条监控事件、" + samples + " 条性能采样");
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "清理监控数据失败", error);
        }
    }

    // ------------------------------------------------------------------ 会话状态

    private record SessionState(long lastStart, boolean stopRecorded) {
    }

    private SessionState loadState() {
        try {
            Map<String, Object> map = plugin.storage().loadGlobal(STATE_KEY);
            if (map == null || map.isEmpty()) {
                return new SessionState(0, true);
            }
            long lastStart = map.get("lastStart") instanceof Number number ? number.longValue() : 0;
            boolean stopRecorded = map.get("stopRecorded") instanceof Boolean bool && bool;
            return new SessionState(lastStart, stopRecorded);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "读取监控会话状态失败", error);
            return new SessionState(0, true);
        }
    }

    private void saveState(SessionState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("lastStart", state.lastStart());
        map.put("stopRecorded", state.stopRecorded());
        try {
            plugin.storage().saveGlobal(STATE_KEY, map);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "保存监控会话状态失败", error);
        }
    }

    // ------------------------------------------------------------------ 查询接口
    //
    // 这些方法会被 REST API（HTTP 线程）与 /eemonitor 命令、网页面板调用，
    // 查询前先把队列里攒的数据落盘，保证结果是最新的。

    /** 当前状态快照（status 接口）。 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptimeMs", uptimeMs());
        result.put("startedAt", startTime.get());
        result.put("online", Math.max(0, onlineCount.get()));
        result.put("running", isRunning());

        PerfSample last = lastSample();
        Map<String, Object> tps = new LinkedHashMap<>();
        tps.put("now", last != null ? round1(last.tps()) : -1);
        double[] live = readLiveTps();
        if (live != null) {
            tps.put("1m", round1(live[0]));
            tps.put("5m", round1(live[1]));
            tps.put("15m", round1(live[2]));
        } else {
            tps.put("1m", -1);
            tps.put("5m", -1);
            tps.put("15m", -1);
        }
        result.put("tps", tps);

        Runtime runtime = Runtime.getRuntime();
        Map<String, Long> memory = new LinkedHashMap<>();
        memory.put("maxMB", runtime.maxMemory() / 1024 / 1024);
        memory.put("totalMB", runtime.totalMemory() / 1024 / 1024);
        memory.put("freeMB", runtime.freeMemory() / 1024 / 1024);
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        result.put("memory", memory);

        Map<String, Long> counters = new LinkedHashMap<>();
        eventCounters.forEach((type, count) -> counters.put(type, count.get()));
        result.put("counters", counters);
        result.put("samples", sampleCount.get());

        Map<String, Object> session = new LinkedHashMap<>();
        SessionState state = loadState();
        session.put("lastStart", state.lastStart());
        session.put("stopRecorded", state.stopRecorded());
        result.put("session", session);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("sampleIntervalSeconds", config.sampleIntervalSeconds());
        settings.put("lagThresholdTps", config.lagThresholdTps());
        settings.put("memoryWarningPercent", config.memoryWarningPercent());
        settings.put("recordLag", config.recordLag());
        settings.put("recordMemory", config.recordMemory());
        settings.put("allowCustomEvents", config.allowCustomEvents());
        result.put("config", settings);
        return result;
    }

    private double[] readLiveTps() {
        return MainThread.call(plugin, () -> {
            try {
                return Bukkit.getServer().getTPS();
            } catch (Throwable error) {
                return null;
            }
        }, null);
    }

    private PerfSample lastSample() {
        synchronized (recentSamples) {
            return recentSamples.peekLast();
        }
    }

    /** 最近 {@code minutes} 分钟的采样，按时间正序，最多 {@code limit} 条（超出则抽稀）。 */
    public List<PerfSample> samples(int minutes, int limit) {
        long since = System.currentTimeMillis() - Math.max(1, minutes) * 60_000L;
        int cap = Math.max(1, Math.min(5000, limit));
        List<PerfSample> all;
        try {
            // 多取一些再抽稀：采样间隔比查询窗口小得多时，库里同窗口的数据可能远超 cap
            all = plugin.storage().recentMonitorSamples(since, Math.max(cap * 2, 5000));
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "读取性能采样失败", error);
            all = new ArrayList<>();
        }
        return downsample(all, cap);
    }

    /** 抽稀：超出上限时按步长均匀采样，保留首尾。 */
    private List<PerfSample> downsample(List<PerfSample> all, int cap) {
        if (all.size() <= cap) {
            return all;
        }
        List<PerfSample> result = new ArrayList<>(cap);
        double stride = (double) (all.size() - 1) / (cap - 1);
        for (int i = 0; i < cap; i++) {
            result.add(all.get((int) Math.round(i * stride)));
        }
        return result;
    }

    /** 最近的事件，按时间倒序。type 为空查全部，since 为 0 不限起点。 */
    public List<MonitorEvent> events(int limit, String type, long since) {
        try {
            return plugin.storage().recentMonitorEvents(Math.max(1, Math.min(5000, limit)),
                    type, since);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "读取监控事件失败", error);
            return List.of();
        }
    }

    /**
     * 会话记录：把 server_start / server_stop / abnormal_shutdown 事件配对成
     * {@code {start, stop, durationMs, abnormal}}，按开始时间倒序，最多 {@code limit} 条。
     */
    public List<Map<String, Object>> sessions(int limit) {
        List<MonitorEvent> all = events(5000, null, 0);
        List<Map<String, Object>> sessions = new ArrayList<>();
        long openStart = -1;
        // 存储返回的是倒序，配对必须按时间正序走
        for (int i = all.size() - 1; i >= 0; i--) {
            MonitorEvent event = all.get(i);
            switch (event.type()) {
                case EVENT_SERVER_START -> openStart = event.timestamp();
                case EVENT_ABNORMAL_SHUTDOWN -> {
                    if (openStart > 0) {
                        sessions.add(sessionOf(openStart, event.timestamp(), true));
                        openStart = -1;
                    }
                }
                case EVENT_SERVER_STOP -> {
                    if (openStart > 0) {
                        sessions.add(sessionOf(openStart, event.timestamp(), false));
                        openStart = -1;
                    }
                }
                default -> {
                }
            }
        }
        // 最近一次会话可能还在进行中（只有 start 没有 stop）
        if (openStart > 0) {
            Map<String, Object> open = sessionOf(openStart, 0, false);
            open.put("running", true);
            sessions.add(open);
        }
        int cap = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> result = new ArrayList<>(Math.min(cap, sessions.size()));
        for (int i = sessions.size() - 1; i >= 0 && result.size() < cap; i--) {
            result.add(sessions.get(i));
        }
        return result;
    }

    private Map<String, Object> sessionOf(long start, long stop, boolean abnormal) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("start", start);
        session.put("stop", stop);
        session.put("durationMs", stop > start ? stop - start : 0);
        session.put("abnormal", abnormal);
        return session;
    }

    // ------------------------------------------------------------------ 工具

    public long uptimeMs() {
        long started = startTime.get();
        return started > 0 ? System.currentTimeMillis() - started : 0;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private String safeServerVersion() {
        try {
            return Bukkit.getServer().getVersion();
        } catch (Throwable error) {
            return "";
        }
    }

    /**
     * 服务端是否正在关停。Paper 1.21+ 的 {@code Server#isStopping()} 在插件
     * onDisable 阶段已经为 true，因此能区分「真关服」与「插件重载」；
     * 拿不到该方法（非 Paper）时按重载处理，避免误记关闭事件。
     */
    static boolean isServerStopping() {
        try {
            return Bukkit.getServer().isStopping();
        } catch (Throwable ignored) {
            return false;
        }
    }
}

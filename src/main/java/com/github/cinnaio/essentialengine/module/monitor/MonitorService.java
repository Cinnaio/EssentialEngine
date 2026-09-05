package com.github.cinnaio.essentialengine.module.monitor;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.MonitorEvent;
import com.github.cinnaio.essentialengine.core.storage.PerfSample;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import com.sun.management.GarbageCollectionNotificationInfo;
import org.bukkit.Bukkit;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>Paper 的 {@code ServerTickStartEvent}/{@code ServerTickEndEvent} 提供逐 Tick 耗时；
 * 卡顿段结束时再关联 JVM GC 通知、主线程低频堆栈采样与可选的 Spark 公共健康指标，
 * 形成一条带证据的结构化诊断事件。</p>
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

    private volatile MonitorConfig config;
    private final SparkIntegration sparkIntegration;

    /** 内存里保留的最近采样（环形缓冲，头部是最旧的），status / 面板快速查询用。 */
    private final Deque<PerfSample> recentSamples = new ArrayDeque<>();
    /** 主线程堆栈采样环形缓冲，只在卡顿事件结束时抽取对应时间段。 */
    private final Deque<StackSample> threadStackSamples = new ArrayDeque<>();
    /** GC 通知环形缓冲，不把每次 GC 都单独写成事件，避免产生噪声。 */
    private final Deque<GcEvent> gcEvents = new ArrayDeque<>();
    private final List<NotificationEmitter> gcEmitters = new ArrayList<>();
    private final NotificationListener gcListener = this::onGcNotification;
    private final Object lagLock = new Object();
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

    /** 由 Paper ServerTickStart/EndEvent 提供的当前 Tick 起点，用于关联堆栈采样。 */
    private volatile long tickStartNanos;
    private volatile long tickStartWallMillis;
    private volatile int tickStartNumber = -1;
    private volatile boolean tickHookSeen;
    private volatile Thread monitoredThread;
    private volatile double lastTickDurationMs = -1;
    private volatile double maxTickDurationMs;
    private volatile int lastTickNumber = -1;
    /** 当前正在汇总的卡顿段；所有访问都经过 lagLock。 */
    private LagEpisode lagEpisode;

    private Object sampleHandle;
    private Object flushHandle;
    private Object pruneHandle;
    private Object stackSampleHandle;
    private Object sparkLoadHandle;

    public MonitorService(EssentialEngine plugin, MonitorConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.sparkIntegration = new SparkIntegration(plugin);
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
        monitoredThread = Thread.currentThread();
        startGcMonitor();
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
        startSparkIntegration();
    }

    /**
     * 热重载：替换配置、重启定时任务。
     *
     * <p>{@code shutdown()} 已经记过 reload 事件并更新了会话状态，这里只恢复运行。</p>
     */
    public void restart(MonitorConfig newConfig) {
        this.config = newConfig;
        stopTimers();
        startGcMonitor();
        startTimers();
        startSparkIntegration();
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
        finishLagIncident(serverStopping ? "server_stop" : "reload", false,
                System.currentTimeMillis(), System.nanoTime(), lastTickNumber);
        if (config.recordStartStop()) {
            if (serverStopping) {
                recordEvent(EVENT_SERVER_STOP, "服务器关闭", Map.of("uptimeMs", uptimeMs()));
            } else {
                recordEvent(EVENT_RELOAD, "插件配置重载", Map.of());
            }
        }
        saveState(new SessionState(startTime.get(), true));
        flush();
        stopGcMonitor();
    }

    private void stopTimers() {
        SchedulerCompat.cancel(sampleHandle);
        SchedulerCompat.cancel(flushHandle);
        SchedulerCompat.cancel(pruneHandle);
        SchedulerCompat.cancel(stackSampleHandle);
        SchedulerCompat.cancel(sparkLoadHandle);
        sampleHandle = null;
        flushHandle = null;
        pruneHandle = null;
        stackSampleHandle = null;
        sparkLoadHandle = null;
    }

    private void startTimers() {
        long interval = Math.max(1, config.sampleIntervalSeconds()) * 20L;
        sampleHandle = SchedulerCompat.runTimerAsync(plugin, this::sample, interval, interval);
        long flush = Math.max(1, config.flushSeconds()) * 20L;
        flushHandle = SchedulerCompat.runTimerAsync(plugin, this::flush, flush, flush);
        long hour = 20L * 60 * 60;
        pruneHandle = SchedulerCompat.runTimerAsync(plugin, this::prune, hour, hour);
        if (config.captureThreadStacks() && !SchedulerCompat.isFolia()) {
            long stackTicks = Math.max(1L, (config.threadStackSampleIntervalMs() + 49L) / 50L);
            stackSampleHandle = SchedulerCompat.runTimerAsync(plugin, this::sampleMainThreadStack,
                    stackTicks, stackTicks);
        }
    }

    /** Paper 默认可能在服务器启动完成后才启用内置 Spark，因此这里允许延迟重试。 */
    private void startSparkIntegration() {
        if (!config.integrateSpark()) {
            return;
        }
        sparkIntegration.tryLoad();
        if (!sparkIntegration.isAvailable()) {
            sparkLoadHandle = SchedulerCompat.runTimer(plugin, this::tryLoadSpark,
                    20L, 100L);
        }
        SchedulerCompat.runAsync(plugin, sparkIntegration::refresh);
    }

    private void tryLoadSpark() {
        sparkIntegration.tryLoad();
        if (sparkIntegration.isAvailable()) {
            SchedulerCompat.cancel(sparkLoadHandle);
            sparkLoadHandle = null;
            SchedulerCompat.runAsync(plugin, sparkIntegration::refresh);
        }
    }

    // ------------------------------------------------------------------ 采样与告警

    /** 采集一次性能采样，并按阈值产生卡顿 / 内存告警事件。在异步线程运行。 */
    private void sample() {
        double tps = readTps();
        if (config.integrateSpark()) {
            sparkIntegration.refresh();
        }
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
     * 由 Paper 的 Tick 事件提供逐 Tick 耗时。监听器只更新有界的内存状态，
     * 在卡顿段边界入队一条摘要；不查询世界、实体，也不执行 IO。
     */
    public void tickStarted(int tickNumber) {
        tickHookSeen = true;
        tickStartNumber = tickNumber;
        tickStartNanos = System.nanoTime();
        tickStartWallMillis = System.currentTimeMillis();
    }

    public void tickEnded(int tickNumber, double tickDurationMs) {
        tickHookSeen = true;
        lastTickNumber = tickNumber;
        if (Double.isFinite(tickDurationMs) && tickDurationMs >= 0) {
            lastTickDurationMs = tickDurationMs;
            maxTickDurationMs = Math.max(maxTickDurationMs, tickDurationMs);
        }
        if (!config.recordLag() || !Double.isFinite(tickDurationMs) || tickDurationMs < 0) {
            return;
        }

        long endNanos = System.nanoTime();
        long startNanos = tickStartNumber == tickNumber && tickStartNanos > 0
                ? tickStartNanos : endNanos - (long) (tickDurationMs * 1_000_000D);
        long startWallMillis = tickStartNumber == tickNumber && tickStartWallMillis > 0
                ? tickStartWallMillis
                : System.currentTimeMillis() - Math.round(tickDurationMs);
        Map<String, Object> startData = null;
        LagEpisode completed = null;
        long completedAt = System.currentTimeMillis();
        long completedNanos = endNanos;

        synchronized (lagLock) {
            if (tickDurationMs >= config.lagTickThresholdMs()) {
                if (lagEpisode == null && canStartLagIncident(completedAt)) {
                    lagEpisode = new LagEpisode(
                            incidentId(startNanos, tickNumber), startWallMillis, startNanos,
                            tickNumber, "slow_tick");
                    startData = lagStartData(lagEpisode, tickDurationMs, -1,
                            tickNumber, "slow_tick");
                }
                if (lagEpisode != null) {
                    lagEpisode.observeTick(tickDurationMs, true);
                    lagEpisode.normalTicks = 0;
                }
            } else if (lagEpisode != null) {
                lagEpisode.observeTick(tickDurationMs, false);
                lagEpisode.normalTicks++;
                if (lagEpisode.normalTicks >= config.lagRecoveryTicks()) {
                    completed = lagEpisode;
                    lagEpisode = null;
                }
            }
        }

        if (startData != null) {
            recordEvent(EVENT_LAG,
                    "检测到慢 Tick：" + String.format("%.1f", tickDurationMs) + "ms",
                    startData);
            plugin.getLogger().warning("[Monitor] 检测到慢 Tick："
                    + String.format("%.1f", tickDurationMs) + "ms");
        }
        if (completed != null) {
            recordLagCompletion(completed, true, "normal_ticks", completedAt, completedNanos, tickNumber);
        }
    }

    /**
     * TPS 采样是逐 Tick 监控的补充：它能发现短暂没有收到 Tick 事件的情况，
     * 也能在 Paper 缓存的 TPS 跌破阈值时开始记录一次事件段。
     */
    private void checkLag(double tps, int online, long usedMB, long maxMB) {
        if (!config.recordLag() || tps <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> startData = null;
        LagEpisode completed = null;
        long completedNanos = System.nanoTime();

        synchronized (lagLock) {
            if (tps < config.lagThresholdTps()) {
                if (lagEpisode == null && canStartLagIncident(now)) {
                    lagEpisode = new LagEpisode(
                            incidentId(completedNanos, -1), now, completedNanos,
                            -1, "low_tps");
                    startData = lagStartData(lagEpisode, -1, tps, -1, "low_tps");
                }
                if (lagEpisode != null) {
                    lagEpisode.observeTps(tps);
                }
            } else if (!tickHookSeen && lagEpisode != null
                    && tps >= config.lagThresholdTps() + 2) {
                completed = lagEpisode;
                lagEpisode = null;
            }
        }

        if (startData != null) {
            recordEvent(EVENT_LAG,
                    "TPS 跌至 " + String.format("%.1f", tps)
                            + "（阈值 " + String.format("%.1f", config.lagThresholdTps()) + "）",
                    startData);
            plugin.getLogger().warning("[Monitor] 检测到严重卡顿：TPS " + String.format("%.1f", tps));
        }
        if (completed != null) {
            recordLagCompletion(completed, true, "tps_recovered", now, completedNanos, -1);
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

    /** 开始监听 JVM 的 GC 通知；只保留内存中的小窗口，等卡顿结束时再关联。 */
    private void startGcMonitor() {
        if (!config.recordLag()) {
            stopGcMonitor();
            return;
        }
        if (!gcEmitters.isEmpty()) {
            return;
        }
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(bean instanceof NotificationEmitter emitter)) {
                continue;
            }
            try {
                emitter.addNotificationListener(gcListener, null, bean.getName());
                gcEmitters.add(emitter);
            } catch (Exception error) {
                plugin.getLogger().fine("[Monitor] 无法监听 GC：" + bean.getName());
            }
        }
    }

    private void stopGcMonitor() {
        for (NotificationEmitter emitter : gcEmitters) {
            try {
                emitter.removeNotificationListener(gcListener);
            } catch (Exception ignored) {
                // JVM 正在退出或监听器已经被移除
            }
        }
        gcEmitters.clear();
    }

    private void onGcNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                .equals(notification.getType())) {
            return;
        }
        Object userData = notification.getUserData();
        if (!(userData instanceof CompositeData composite)) {
            return;
        }
        try {
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(composite);
            long nowNanos = System.nanoTime();
            GcEvent event = new GcEvent(nowNanos, System.currentTimeMillis(),
                    Math.max(0, info.getGcInfo().getDuration()),
                    info.getGcName(), info.getGcAction(), info.getGcCause());
            synchronized (gcEvents) {
                gcEvents.addLast(event);
                while (gcEvents.size() > 256) {
                    gcEvents.pollFirst();
                }
            }
        } catch (Exception ignored) {
            // 不让单次格式异常影响服务器的 GC 线程
        }
    }

    /**
     * 按 Spark 的思路在后台对主线程做低频采样。采样永远只进环形缓冲，
     * 不在主线程执行，也不为每一次采样写磁盘。
     */
    private void sampleMainThreadStack() {
        if (!config.captureThreadStacks() || SchedulerCompat.isFolia()) {
            return;
        }
        Thread thread = monitoredThread;
        if (thread == null || !thread.isAlive()) {
            return;
        }
        try {
            long capturedNanos = System.nanoTime();
            StackTraceElement[] trace = thread.getStackTrace();
            if (trace.length == 0) {
                return;
            }
            int depth = Math.min(config.threadStackDepth(), trace.length);
            List<String> frames = new ArrayList<>(depth);
            for (int i = 0; i < depth; i++) {
                frames.add(trace[i].toString());
            }
            StackSample sample = new StackSample(capturedNanos, System.currentTimeMillis(), frames);
            synchronized (threadStackSamples) {
                threadStackSamples.addLast(sample);
                while (threadStackSamples.size() > 512) {
                    threadStackSamples.pollFirst();
                }
            }
        } catch (SecurityException ignored) {
            // 安全策略禁止读取线程堆栈时，其他指标仍然正常工作
        }
    }

    private boolean canStartLagIncident(long now) {
        long cooldown = Math.max(10, config.lagCooldownSeconds()) * 1000L;
        long last = lastLagEvent.get();
        if (last > 0 && now - last < cooldown) {
            return false;
        }
        lastLagEvent.set(now);
        return true;
    }

    private static String incidentId(long startNanos, int tickNumber) {
        return Long.toUnsignedString(startNanos, 36) + "-" + tickNumber;
    }

    private Map<String, Object> lagStartData(LagEpisode episode, double tickDurationMs,
                                             double tps, int tickNumber, String trigger) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", episode.id);
        data.put("phase", "start");
        data.put("trigger", trigger);
        data.put("startedAt", episode.startTimestamp);
        data.put("tickNumber", tickNumber);
        data.put("tickThresholdMs", config.lagTickThresholdMs());
        data.put("tpsThreshold", config.lagThresholdTps());
        if (Double.isFinite(tickDurationMs) && tickDurationMs >= 0) {
            data.put("tickDurationMs", tickDurationMs);
        }
        if (Double.isFinite(tps) && tps > 0) {
            data.put("tps", tps);
        }
        data.put("online", Math.max(0, onlineCount.get()));
        Runtime runtime = Runtime.getRuntime();
        data.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        data.put("maxMB", runtime.maxMemory() / 1024 / 1024);
        if (config.integrateSpark()) {
            data.put("spark", sparkIntegration.snapshot());
        }
        return data;
    }

    private void finishLagIncident(String reason, boolean recovered, long endTimestamp,
                                   long endNanos, int endTick) {
        LagEpisode completed;
        synchronized (lagLock) {
            if (lagEpisode == null) {
                return;
            }
            completed = lagEpisode;
            lagEpisode = null;
        }
        recordLagCompletion(completed, recovered, reason, endTimestamp, endNanos, endTick);
    }

    private void recordLagCompletion(LagEpisode episode, boolean recovered, String reason,
                                     long endTimestamp, long endNanos, int endTick) {
        long durationMs = Math.max(0, Math.round((endNanos - episode.startNanos) / 1_000_000D));
        Map<String, Object> gc = gcSummary(episode.startNanos, endNanos);
        Map<String, Object> stacks = stackSummary(episode.startNanos, endNanos);
        Map<String, Object> spark = config.integrateSpark()
                ? sparkIntegration.snapshot() : Map.of("available", false);
        Map<String, Object> diagnosis = diagnose(episode, gc, stacks, spark);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", episode.id);
        data.put("phase", "end");
        data.put("recovered", recovered);
        data.put("closeReason", reason);
        data.put("trigger", episode.trigger);
        data.put("startedAt", episode.startTimestamp);
        data.put("endedAt", endTimestamp);
        data.put("durationMs", durationMs);
        data.put("startTick", episode.startTick);
        data.put("endTick", endTick);
        data.put("observedTicks", episode.observedTicks);
        data.put("slowTicks", episode.slowTicks);
        data.put("minTps", episode.minTps);
        data.put("minTickDurationMs", finiteOrZero(episode.minTickDurationMs));
        data.put("maxTickDurationMs", episode.maxTickDurationMs);
        data.put("averageTickDurationMs", episode.observedTicks == 0
                ? 0 : episode.totalTickDurationMs / episode.observedTicks);
        data.put("tpsSamples", episode.tpsSamples);
        data.put("tickThresholdMs", config.lagTickThresholdMs());
        data.put("tpsThreshold", config.lagThresholdTps());
        data.put("online", Math.max(0, onlineCount.get()));
        data.put("gc", gc);
        data.put("gcPauseMs", gc.get("totalPauseMs"));
        data.put("mainThread", stacks);
        data.put("diagnosis", diagnosis);
        data.put("suspectedCause", diagnosis.get("suspectedCause"));
        data.put("confidence", diagnosis.get("confidence"));
        data.put("spark", spark);
        if (Boolean.TRUE.equals(spark.get("available"))) {
            data.put("sparkProfilerHint", "/spark profiler start --only-ticks-over "
                    + Math.max(50, Math.round(config.lagTickThresholdMs()))
                    + " --timeout 30");
        }

        String cause = String.valueOf(diagnosis.get("suspectedCause"));
        String message = recovered
                ? "卡顿已恢复：持续 " + formatMs(durationMs) + "，最高 Tick "
                + String.format("%.1f", episode.maxTickDurationMs) + "ms，疑似 " + cause
                : "卡顿记录结束（" + reason + "）：持续 " + formatMs(durationMs)
                + "，疑似 " + cause;
        recordEvent(EVENT_LAG_RECOVERED, message, data);
    }

    private Map<String, Object> gcSummary(long startNanos, long endNanos) {
        List<GcEvent> selected = new ArrayList<>();
        synchronized (gcEvents) {
            for (GcEvent event : gcEvents) {
                if (event.timestampNanos >= startNanos && event.timestampNanos <= endNanos) {
                    selected.add(event);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        long total = selected.stream().mapToLong(event -> event.durationMs).sum();
        long max = selected.stream().mapToLong(event -> event.durationMs).max().orElse(0);
        result.put("count", selected.size());
        result.put("totalPauseMs", total);
        result.put("maxPauseMs", max);
        List<Map<String, Object>> details = new ArrayList<>();
        for (GcEvent event : selected) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("timestamp", event.timestampMillis);
            detail.put("durationMs", event.durationMs);
            detail.put("name", event.name);
            detail.put("action", event.action);
            detail.put("cause", event.cause);
            details.add(detail);
        }
        result.put("events", details);
        return result;
    }

    private Map<String, Object> stackSummary(long startNanos, long endNanos) {
        List<StackSample> selected = new ArrayList<>();
        synchronized (threadStackSamples) {
            for (StackSample sample : threadStackSamples) {
                if (sample.timestampNanos >= startNanos && sample.timestampNanos <= endNanos) {
                    selected.add(sample);
                }
            }
            if (selected.isEmpty()) {
                StackSample nearest = null;
                for (StackSample sample : threadStackSamples) {
                    if (sample.timestampNanos <= endNanos
                            && endNanos - sample.timestampNanos <= 1_000_000_000L) {
                        nearest = sample;
                    }
                }
                if (nearest != null) {
                    selected.add(nearest);
                }
            }
        }

        Map<String, Integer> frameCounts = new LinkedHashMap<>();
        for (StackSample sample : selected) {
            for (String frame : sample.frames) {
                frameCounts.merge(frame, 1, Integer::sum);
            }
        }
        List<String> dominantFrames = frameCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(12)
                .map(entry -> entry.getKey() + " ×" + entry.getValue())
                .toList();

        List<Map<String, Object>> samples = new ArrayList<>();
        int sampleLimit = Math.min(8, selected.size());
        for (int i = 0; i < sampleLimit; i++) {
            int index = sampleLimit == 1 ? 0
                    : (int) Math.round(i * (selected.size() - 1D) / (sampleLimit - 1D));
            StackSample sample = selected.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", sample.timestampMillis);
            item.put("frames", sample.frames);
            samples.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", selected.size());
        result.put("dominantFrames", dominantFrames);
        result.put("samples", samples);
        return result;
    }

    private Map<String, Object> diagnose(LagEpisode episode, Map<String, Object> gc,
                                         Map<String, Object> stacks, Map<String, Object> spark) {
        List<String> evidence = new ArrayList<>();
        String cause = "unknown";
        double confidence = 0.15;

        long gcPause = numberLong(gc.get("totalPauseMs"));
        if (gcPause >= Math.max(50, Math.round(episode.maxTickDurationMs * 0.25))) {
            cause = "gc_pause";
            confidence = 0.85;
            evidence.add("卡顿时间段内检测到 " + gc.get("count") + " 次 GC，共暂停 "
                    + gcPause + "ms");
        } else {
            String blockingFrame = findFrame(stacks, "wait(", "park(", "sleep(",
                    "futuretask.get", "completablefuture.join", "java.sql.", "jdbc",
                    "files.", "socket", "urlconnection", "httpclient");
            if (blockingFrame != null) {
                cause = "main_thread_blocking";
                confidence = 0.70;
                evidence.add("主线程采样出现阻塞或 IO 调用：" + blockingFrame);
            } else {
                String worldFrame = findFrame(stacks, "worldgen", "chunk", "pathfinding");
                if (worldFrame != null) {
                    cause = "world_or_chunk_work";
                    confidence = 0.60;
                    evidence.add("主线程采样集中在区块 / 世界计算：" + worldFrame);
                } else {
                    String entityFrame = findFrame(stacks,
                            "entity.entity.tick", "entity.tick", "ticknonpassenger");
                    if (entityFrame != null) {
                        cause = "entity_tick_work";
                        confidence = 0.55;
                        evidence.add("主线程采样集中在实体 Tick：" + entityFrame);
                    }
                }
            }
        }

        Object cpu = spark.get("cpuProcess10s");
        if ("unknown".equals(cause) && cpu instanceof Number number
                && number.doubleValue() >= 90) {
            cause = "cpu_pressure";
            confidence = 0.55;
            evidence.add("Spark 进程 CPU 约 " + String.format("%.1f", number.doubleValue()) + "%");
        }
        if (episode.maxTickDurationMs >= config.lagTickThresholdMs()) {
            evidence.add("最大 Tick 耗时 " + String.format("%.1f", episode.maxTickDurationMs) + "ms");
        }
        if ("unknown".equals(cause)) {
            evidence.add("当前采样不足以确认具体调用方，需要 Spark profiler 火焰图");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suspectedCause", cause);
        result.put("confidence", Math.round(confidence * 100) / 100.0);
        result.put("method", "heuristic");
        result.put("evidence", evidence);
        return result;
    }

    private String findFrame(Map<String, Object> stacks, String... needles) {
        Object value = stacks.get("dominantFrames");
        if (!(value instanceof List<?> frames)) {
            return null;
        }
        for (Object frame : frames) {
            String text = String.valueOf(frame);
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            for (String needle : needles) {
                if (lower.contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                    return text;
                }
            }
        }
        return null;
    }

    private static long numberLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    private static String formatMs(long milliseconds) {
        return String.format("%.1f秒", milliseconds / 1000.0);
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

    /** 一段卡顿从开始到恢复之间的轻量汇总状态。只在 lagLock 内修改。 */
    private static final class LagEpisode {

        private final String id;
        private final long startTimestamp;
        private final long startNanos;
        private final int startTick;
        private final String trigger;
        private double minTps = -1;
        private int tpsSamples;
        private double minTickDurationMs = Double.POSITIVE_INFINITY;
        private double maxTickDurationMs;
        private double totalTickDurationMs;
        private long observedTicks;
        private long slowTicks;
        private int normalTicks;

        private LagEpisode(String id, long startTimestamp, long startNanos,
                           int startTick, String trigger) {
            this.id = id;
            this.startTimestamp = startTimestamp;
            this.startNanos = startNanos;
            this.startTick = startTick;
            this.trigger = trigger;
        }

        private void observeTick(double durationMs, boolean slow) {
            if (!Double.isFinite(durationMs) || durationMs < 0) {
                return;
            }
            observedTicks++;
            totalTickDurationMs += durationMs;
            minTickDurationMs = Math.min(minTickDurationMs, durationMs);
            maxTickDurationMs = Math.max(maxTickDurationMs, durationMs);
            if (slow) {
                slowTicks++;
            }
        }

        private void observeTps(double tps) {
            if (!Double.isFinite(tps) || tps <= 0) {
                return;
            }
            tpsSamples++;
            minTps = minTps < 0 ? tps : Math.min(minTps, tps);
        }
    }

    private record StackSample(long timestampNanos, long timestampMillis, List<String> frames) {
    }

    private record GcEvent(long timestampNanos, long timestampMillis, long durationMs,
                           String name, String action, String cause) {
    }

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

        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("supported", tickHookSeen);
        tick.put("lastNumber", lastTickNumber);
        tick.put("lastDurationMs", round1(lastTickDurationMs));
        tick.put("maxDurationMs", round1(maxTickDurationMs));
        tick.put("thresholdMs", config.lagTickThresholdMs());
        synchronized (lagLock) {
            tick.put("lagActive", lagEpisode != null);
            if (lagEpisode != null) {
                tick.put("incidentId", lagEpisode.id);
                tick.put("incidentStartedAt", lagEpisode.startTimestamp);
                tick.put("trigger", lagEpisode.trigger);
            }
        }
        result.put("tick", tick);
        result.put("spark", config.integrateSpark()
                ? sparkIntegration.snapshot() : Map.of("available", false));

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
        settings.put("lagTickThresholdMs", config.lagTickThresholdMs());
        settings.put("lagRecoveryTicks", config.lagRecoveryTicks());
        settings.put("captureThreadStacks", config.captureThreadStacks());
        settings.put("threadStackSampleIntervalMs", config.threadStackSampleIntervalMs());
        settings.put("threadStackDepth", config.threadStackDepth());
        settings.put("integrateSpark", config.integrateSpark());
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

    /**
     * 最近 {@code minutes} 分钟的采样，按时间正序，最多 {@code limit} 条（超出则抽稀）。
     *
     * <p><b>优先走内存缓冲</b>：请求窗口若完全落在环形缓冲内（默认缓冲 8640 条采样
     * ≈ 24 小时），直接从内存取，不碰存储——面板与 AstrBot 的高频轮询因此几乎零开销，
     * 不会每次请求都全量扫描 JSONL / 查库。只有查很长的历史窗口时才回落到存储。</p>
     */
    public List<PerfSample> samples(int minutes, int limit) {
        int cap = Math.max(1, Math.min(5000, limit));
        long since = System.currentTimeMillis() - Math.max(1, minutes) * 60_000L;

        synchronized (recentSamples) {
            int intervalMillis = Math.max(1, config.sampleIntervalSeconds()) * 1000;
            int windowSamples = (int) Math.ceil(Math.max(1, minutes) * 60_000.0 / intervalMillis);
            if (recentSamples.size() >= windowSamples) {
                List<PerfSample> fromMemory = new ArrayList<>(recentSamples);
                fromMemory.removeIf(sample -> sample.timestamp() < since);
                return downsample(fromMemory, cap);
            }
        }

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

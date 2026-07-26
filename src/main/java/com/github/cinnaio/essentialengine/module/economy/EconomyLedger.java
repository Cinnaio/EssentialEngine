package com.github.cinnaio.essentialengine.module.economy;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.TransactionRecord;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 经济流水记账。
 *
 * <p>因为本插件是 Vault 的经济提供者，商店 / 职业 / 任务这些插件的每一笔扣款、发钱
 * 最终都会走到 {@link EconomyManager} 的 withdraw / deposit / set，所以在那里挂上钩子
 * 就能把<b>全服所有</b>资金变动都记下来，而不只是本插件自己的命令。</p>
 *
 * <p><b>不阻塞</b>：交易可能发生在主线程（玩家点一下商店），所以这里只往内存队列里塞，
 * 由定时任务批量异步落盘。队列有上限，写满了宁可丢弃最旧的记录也不拖慢服务器。</p>
 *
 * <p><b>来源识别</b>：Vault 的接口没有「谁发起的」这个参数，只能从调用栈上找——
 * 沿栈往外走，第一个不属于本插件的类，用 {@code getProvidingPlugin} 反查它属于哪个插件。
 * 结果按类缓存，同一个商店插件只解析一次。</p>
 */
public class EconomyLedger {

    /** 本插件自己触发的交易统一记这个来源。 */
    public static final String SELF = "EssentialEngine";
    private static final String OWN_PACKAGE = "com.github.cinnaio.essentialengine";

    private final EssentialEngine plugin;
    private final ConcurrentLinkedQueue<TransactionRecord> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingSize = new AtomicInteger();
    /** class -> 插件名。空串表示「查过了，不属于任何插件」。 */
    private final Map<Class<?>, String> sourceCache = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final boolean trackSources;
    private final int queueLimit;
    private final int retentionDays;

    private Object flushHandle;
    private Object pruneHandle;

    /**
     * 显式来源。本插件内部调用经济接口前先设好，扣完再清掉——
     * 这样 {@code /pay} 记的是「pay」而不是靠栈猜出来的「EssentialEngine」。
     */
    private static final ThreadLocal<String> EXPLICIT = new ThreadLocal<>();

    public EconomyLedger(EssentialEngine plugin, boolean enabled, boolean trackSources,
                         int queueLimit, int retentionDays) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.trackSources = trackSources;
        this.queueLimit = Math.max(64, queueLimit);
        this.retentionDays = Math.max(1, retentionDays);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ 显式来源

    /**
     * 在 {@code action} 执行期间，把记账来源固定成 {@code detail}。
     *
     * <p>用 try/finally 保证异常时也会还原，避免来源「粘」在这个线程上污染后续交易。</p>
     */
    public static void withSource(String detail, Runnable action) {
        String previous = EXPLICIT.get();
        EXPLICIT.set(detail);
        try {
            action.run();
        } finally {
            if (previous == null) {
                EXPLICIT.remove();
            } else {
                EXPLICIT.set(previous);
            }
        }
    }

    // ------------------------------------------------------------------ 记账

    /** 记一笔流水。可能在主线程调用，因此只入队不落盘。 */
    public void record(UUID uuid, String name, String type, double amount, double balanceAfter) {
        if (!enabled || uuid == null || amount <= 0) {
            return;
        }
        String detail = EXPLICIT.get();
        String source = detail != null ? SELF : detectSource();

        // 队列满了就丢最旧的：宁可少几条统计，也不能让经济操作变慢或把内存撑爆
        while (pendingSize.get() >= queueLimit) {
            if (pending.poll() == null) {
                break;
            }
            pendingSize.decrementAndGet();
        }
        pending.add(new TransactionRecord(System.currentTimeMillis(), uuid,
                name == null ? "" : name, type, amount, balanceAfter,
                source, detail == null ? "" : detail));
        pendingSize.incrementAndGet();
    }

    /**
     * 顺着调用栈找出是哪个插件在动这笔钱。
     *
     * <p>跳过本插件、JDK、以及 Vault 的动态代理类，取第一个能反查到插件的类。
     * 找不到（例如控制台直接调用）就归到本插件名下。</p>
     */
    private String detectSource() {
        if (!trackSources) {
            return SELF;
        }
        try {
            String found = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(frames -> frames
                            .map(StackWalker.StackFrame::getDeclaringClass)
                            .filter(type -> {
                                String name = type.getName();
                                return !name.startsWith(OWN_PACKAGE)
                                        && !name.startsWith("java.")
                                        && !name.startsWith("jdk.")
                                        && !name.startsWith("sun.")
                                        && !Proxy.isProxyClass(type);
                            })
                            .map(this::pluginNameOf)
                            .filter(name -> name != null && !name.isEmpty())
                            .findFirst()
                            .orElse(null));
            return found == null ? SELF : found;
        } catch (Throwable error) {
            return SELF;
        }
    }

    private String pluginNameOf(Class<?> type) {
        String cached = sourceCache.get(type);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String name = "";
        try {
            var owner = JavaPlugin.getProvidingPlugin(type);
            if (owner != null) {
                name = owner.getName();
            }
        } catch (Throwable ignored) {
            // 不是插件里的类（Bukkit 内部、NMS 等），记成空串免得下次再查
        }
        sourceCache.put(type, name);
        return name.isEmpty() ? null : name;
    }

    // ------------------------------------------------------------------ 落盘与清理

    public void start(int flushSeconds) {
        if (!enabled) {
            return;
        }
        long ticks = Math.max(1, flushSeconds) * 20L;
        flushHandle = SchedulerCompat.runTimerAsync(plugin, this::flush, ticks, ticks);
        // 清理每小时跑一次就够了，顺便把启动后攒的第一批也带下去
        long hour = 20L * 60 * 60;
        pruneHandle = SchedulerCompat.runTimerAsync(plugin, this::prune, hour, hour);
    }

    public void stop() {
        SchedulerCompat.cancel(flushHandle);
        SchedulerCompat.cancel(pruneHandle);
        flushHandle = null;
        pruneHandle = null;
        flush();
    }

    /** 把队列里攒的流水批量写进存储。必须在异步线程调用。 */
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<TransactionRecord> batch = new ArrayList<>();
        TransactionRecord record;
        while ((record = pending.poll()) != null) {
            pendingSize.decrementAndGet();
            batch.add(record);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            plugin.storage().appendTransactions(batch);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "写入经济流水失败，本批 " + batch.size() + " 条已丢弃", error);
        }
    }

    private void prune() {
        try {
            long before = System.currentTimeMillis() - retentionDays * 86_400_000L;
            int removed = plugin.storage().pruneTransactions(before);
            if (removed > 0 && plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("清理了 " + removed + " 条过期经济流水");
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "清理经济流水失败", error);
        }
    }
}

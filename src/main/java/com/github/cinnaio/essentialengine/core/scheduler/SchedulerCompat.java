package com.github.cinnaio.essentialengine.core.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Folia / Paper / Spigot 通用调度器兼容层。
 *
 * <p>Folia 采用区域化多线程，{@code Bukkit.getScheduler()} 在 Folia 上不可用，
 * 必须改用 {@code GlobalRegionScheduler}、{@code AsyncScheduler}、{@code RegionScheduler}
 * 以及实体自身的 {@code EntityScheduler}。这里在运行时探测服务端类型后自动选择。</p>
 */
public final class SchedulerCompat {

    private static final boolean FOLIA = detectFolia();

    private SchedulerCompat() {
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** 在主线程（Folia 为全局区域线程）执行。 */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 延迟若干 tick 后在主线程执行。
     *
     * @return 可传给 {@link #cancel(Object)} 的任务句柄
     */
    public static Object runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        long ticks = Math.max(1L, delayTicks);
        if (FOLIA) {
            return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), ticks);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /** 异步执行。 */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /** 延迟异步执行。 */
    public static void runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        long millis = Math.max(1L, delayTicks * 50L);
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), millis, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(1L, delayTicks));
        }
    }

    /** 在实体所属区域线程执行（Folia 必须这样操作实体）。 */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** 在某个坐标所属区域线程执行（Folia 上修改世界方块必须这样做）。 */
    public static void runForRegion(Plugin plugin, Location location, Runnable task) {
        if (FOLIA) {
            Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 注册一个重复任务。
     *
     * @return 可传给 {@link #cancel(Object)} 的任务句柄
     */
    public static Object runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            long delayMs = Math.max(1L, delayTicks * 50L);
            long periodMs = Math.max(1L, periodTicks * 50L);
            return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(),
                    delayMs, periodMs, TimeUnit.MILLISECONDS);
        }
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** 注册一个在主线程运行的重复任务。 */
    public static Object runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** 取消由本类创建的任务。 */
    public static void cancel(Object handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.getClass().getMethod("cancel").invoke(handle);
        } catch (Exception ignored) {
            // 任务已结束
        }
    }

    /** 取消本插件的全部任务（关服时调用）。 */
    public static void cancelAll(Plugin plugin) {
        try {
            if (FOLIA) {
                Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
                Bukkit.getAsyncScheduler().cancelTasks(plugin);
            } else {
                Bukkit.getScheduler().cancelTasks(plugin);
            }
        } catch (Exception ignored) {
        }
    }
}

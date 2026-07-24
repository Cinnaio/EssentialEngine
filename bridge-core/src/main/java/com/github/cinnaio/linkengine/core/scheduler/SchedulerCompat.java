package com.github.cinnaio.linkengine.core.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler compatible layer for Folia and Paper/Spigot.
 * Automatically detects the server type at runtime and uses the appropriate scheduler.
 *
 * Folia uses regionized multithreading, so Bukkit.getScheduler() is not available.
 * Instead, Folia provides region-aware schedulers via Bukkit.getGlobalRegionScheduler(),
 * Bukkit.getAsyncScheduler(), and entity.getScheduler().
 */
public final class SchedulerCompat {

    private static final boolean IS_FOLIA = detectFolia();

    private SchedulerCompat() {}

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * Run a task on the main thread (global region in Folia).
     */
    public static void runTask(Plugin plugin, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task asynchronously.
     */
    public static void runTaskAsync(Plugin plugin, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Run a task later on the main thread (global region in Folia).
     * @param delayTicks delay in ticks
     */
    public static void runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            // Folia uses milliseconds for delayed tasks
            long delayMs = Math.max(1, delayTicks * 50);
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayMs);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * Run a task later asynchronously.
     * @param delayTicks delay in ticks
     */
    public static void runTaskLaterAsync(Plugin plugin, Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            long delayMs = Math.max(1, delayTicks * 50);
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    /**
     * Run a task tied to an entity's region (Folia) or main thread (Paper/Spigot).
     */
    public static void runEntityTask(Plugin plugin, Entity entity, Runnable task) {
        if (IS_FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a repeating task.
     * @param delayTicks initial delay in ticks
     * @param periodTicks period in ticks
     */
    public static Object runTaskTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            long delayMs = Math.max(1, delayTicks * 50);
            long periodMs = Math.max(1, periodTicks * 50);
            return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    t -> task.run(), delayMs, periodMs);
        } else {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }
}

package com.github.cinnaio.essentialengine.core.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 从其它线程安全地取用 Bukkit API。
 *
 * <p>REST API 的请求跑在 HTTP 线程上，直接调用 Bukkit 既不安全、在 Folia 上还会直接抛异常。
 * 这里把调用丢回主线程 / 全局区域线程执行并等待结果。</p>
 */
public final class MainThread {

    private MainThread() {
    }

    /** 在主线程执行并返回结果；超时或出错返回 fallback。 */
    public static <T> T call(Plugin plugin, Supplier<T> supplier, T fallback, long timeoutMillis) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return supplier.get();
            } catch (Throwable error) {
                return fallback;
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        SchedulerCompat.runGlobal(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            return fallback;
        }
    }

    public static <T> T call(Plugin plugin, Supplier<T> supplier, T fallback) {
        return call(plugin, supplier, fallback, 5000L);
    }
}

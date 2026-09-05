package com.github.cinnaio.essentialengine.module.monitor;

import com.github.cinnaio.essentialengine.EssentialEngine;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spark 的可选健康指标适配器。
 *
 * <p>这里只读取 Spark 公开 API 暴露的健康指标，不依赖 Spark 的内部 profiler
 * 实现，也不把 Spark 打进 EssentialEngine。没有可用 Spark 或 API 尚未就绪时，
 * 监控模块继续使用自己的 Paper / JMX 采集能力。</p>
 */
final class SparkIntegration {

    private static final String PROVIDER_CLASS = "me.lucko.spark.api.SparkProvider";

    private final EssentialEngine plugin;

    /** Spark API 实例可能来自 Spark 插件或 Paper 内置类加载器，故这里故意不声明 Spark 类型。 */
    private volatile Object spark;
    private volatile Map<String, Object> lastSnapshot = unavailable(null);

    SparkIntegration(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    /** 在主线程尝试加载，避免从 HTTP / 异步线程触碰 Bukkit 插件管理器。 */
    synchronized void tryLoad() {
        if (spark != null) {
            return;
        }

        Plugin sparkPlugin = Bukkit.getPluginManager().getPlugin("spark");
        List<ClassLoader> candidates = new ArrayList<>();
        if (sparkPlugin != null && sparkPlugin.isEnabled()) {
            candidates.add(sparkPlugin.getClass().getClassLoader());
        }
        candidates.add(SparkIntegration.class.getClassLoader());
        try {
            candidates.add(Bukkit.getServer().getClass().getClassLoader());
        } catch (Throwable ignored) {
            // 服务端实例不可用时仍可尝试本插件 / 上下文类加载器
        }
        candidates.add(Thread.currentThread().getContextClassLoader());

        Throwable lastError = null;
        Set<ClassLoader> tried = new HashSet<>();
        for (ClassLoader loader : candidates) {
            if (loader == null || !tried.add(loader)) {
                continue;
            }
            try {
                Class<?> provider = Class.forName(PROVIDER_CLASS, true, loader);
                Method get = provider.getMethod("get");
                spark = get.invoke(null);
                lastSnapshot = unavailable(null);
                plugin.getLogger().info("[Monitor] 已连接 Spark 公共健康指标 API");
                return;
            } catch (Throwable error) {
                lastError = error;
            }
        }
        spark = null;
        lastSnapshot = unavailable("Spark API 不可用");
        if (lastError != null) {
            plugin.getLogger().fine("[Monitor] Spark API 未就绪：" + lastError.getMessage());
        }
    }

    boolean isAvailable() {
        return spark != null;
    }

    /**
     * 异步刷新一次 Spark 健康指标。Spark profiler 的启动 / 停止不在这里做，
     * 避免绑定不稳定的内部 API；需要火焰图时仍由 Spark 自己生成 profile。
     */
    void refresh() {
        Object api = spark;
        if (api == null) {
            return;
        }
        try {
            lastSnapshot = readSnapshot(api);
        } catch (Throwable error) {
            lastSnapshot = unavailable("读取 Spark 指标失败");
        }
    }

    /** 返回当前快照的浅拷贝，避免调用方意外修改后台缓存。 */
    Map<String, Object> snapshot() {
        return new LinkedHashMap<>(lastSnapshot);
    }

    private Map<String, Object> readSnapshot(Object api) throws ReflectiveOperationException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);

        addDoubleStatistic(result, api, "cpuProcess", "cpuProcess10s", 0);
        addDoubleStatistic(result, api, "cpuSystem", "cpuSystem10s", 0);
        addDoubleStatistic(result, api, "tps", "tps5s", 0);
        addDoubleStatistic(result, api, "tps", "tps10s", 1);
        addDoubleStatistic(result, api, "tps", "tps1m", 2);

        Object msptStatistic = invoke(api, "mspt");
        Object msptInfo = arrayElement(invoke(msptStatistic, "poll"), 0);
        if (msptInfo != null) {
            Map<String, Object> mspt = new LinkedHashMap<>();
            addNumber(mspt, msptInfo, "mean");
            addNumber(mspt, msptInfo, "min");
            addNumber(mspt, msptInfo, "max");
            addNumber(mspt, msptInfo, "percentile95th");
            result.put("mspt10s", mspt);
        }

        Object rawGc = invoke(api, "gc");
        if (rawGc instanceof Map<?, ?> gcMap) {
            List<Map<String, Object>> collectors = new ArrayList<>();
            for (Object value : gcMap.values()) {
                if (value == null) {
                    continue;
                }
                Map<String, Object> collector = new LinkedHashMap<>();
                addString(collector, value, "name");
                addNumber(collector, value, "totalCollections");
                addNumber(collector, value, "totalTime");
                addNumber(collector, value, "avgTime");
                addNumber(collector, value, "avgFrequency");
                collectors.add(collector);
            }
            result.put("gc", collectors);
        }
        return result;
    }

    private void addDoubleStatistic(Map<String, Object> result, Object api,
                                    String methodName, String key, int index)
            throws ReflectiveOperationException {
        Object statistic = invoke(api, methodName);
        if (statistic == null) {
            return;
        }
        Object value = arrayElement(invoke(statistic, "poll"), index);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            result.put(key, number.doubleValue());
        }
    }

    private void addNumber(Map<String, Object> result, Object target, String methodName)
            throws ReflectiveOperationException {
        Object value = invoke(target, methodName);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            result.put(methodName, number.doubleValue());
        }
    }

    private void addString(Map<String, Object> result, Object target, String methodName)
            throws ReflectiveOperationException {
        Object value = invoke(target, methodName);
        if (value != null) {
            result.put(methodName, String.valueOf(value));
        }
    }

    private static Object arrayElement(Object value, int index) {
        if (value == null || !value.getClass().isArray() || index < 0
                || index >= Array.getLength(value)) {
            return null;
        }
        return Array.get(value, index);
    }

    private static Object invoke(Object target, String methodName)
            throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = target.getClass().getMethod(methodName);
        if (!method.canAccess(target)) {
            method.trySetAccessible();
        }
        try {
            return method.invoke(target);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw error;
        }
    }

    private static Map<String, Object> unavailable(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        if (reason != null && !reason.isBlank()) {
            result.put("reason", reason);
        }
        return result;
    }
}

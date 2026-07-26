package com.github.cinnaio.essentialengine.module.economy;

import com.github.cinnaio.essentialengine.EssentialEngine;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vault 经济接口对接。
 *
 * <p>这里没有把 VaultAPI 作为编译依赖，而是用动态代理在运行时实现
 * {@code net.milkbowl.vault.economy.Economy}。好处是：</p>
 * <ul>
 *     <li>不装 Vault 的服务器完全不受影响，也不会因为缺 jar 报错；</li>
 *     <li>构建时不需要额外的 jitpack 仓库，Vault 接口以后有增删也不会导致编译失败。</li>
 * </ul>
 * 对商店类插件而言，效果和常规实现完全一样。
 */
public final class VaultHook {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";
    private static final String RESPONSE_CLASS = "net.milkbowl.vault.economy.EconomyResponse";
    private static final String RESPONSE_TYPE_CLASS = "net.milkbowl.vault.economy.EconomyResponse$ResponseType";

    private VaultHook() {
    }

    public static boolean isVaultPresent() {
        return Bukkit.getPluginManager().getPlugin("Vault") != null;
    }

    /**
     * 注册为 Vault 的经济服务提供者，成功返回 true。
     *
     * <p>这个方法会在插件的 {@code onLoad()} 里被调用，也就是**所有插件 onEnable 之前**，
     * 保证商店 / 职业 / 任务这类插件在自己的 onEnable 里能立刻查到经济提供者。</p>
     *
     * @param priorityName 服务优先级（Lowest / Low / Normal / High / Highest）。
     *                     服务器上同时装了别的经济插件时，用它决定谁生效。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean register(EssentialEngine plugin, EconomyManager economy, String priorityName) {
        if (!isVaultPresent()) {
            return false;
        }
        ServicePriority priority = parsePriority(priorityName);
        try {
            Class<?> economyClass = Class.forName(ECONOMY_CLASS);
            Class<?> responseClass = Class.forName(RESPONSE_CLASS);
            Class<?> responseTypeClass = Class.forName(RESPONSE_TYPE_CLASS);

            Object proxy = Proxy.newProxyInstance(economyClass.getClassLoader(),
                    new Class<?>[]{economyClass},
                    new Handler(plugin, economy, responseClass, responseTypeClass));

            Bukkit.getServicesManager().register((Class) economyClass, proxy, plugin, priority);
            plugin.getLogger().info("已注册到 Vault 经济服务（优先级 " + priority.name() + "）。");
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("对接 Vault 失败: " + error.getMessage());
            return false;
        }
    }

    private static ServicePriority parsePriority(String raw) {
        if (raw == null || raw.isEmpty()) {
            return ServicePriority.Normal;
        }
        for (ServicePriority priority : ServicePriority.values()) {
            if (priority.name().equalsIgnoreCase(raw.trim())) {
                return priority;
            }
        }
        return ServicePriority.Normal;
    }

    public static void unregister(EssentialEngine plugin) {
        try {
            Bukkit.getServicesManager().unregisterAll(plugin);
        } catch (Throwable ignored) {
        }
    }

    private static class Handler implements InvocationHandler {

        private final EssentialEngine plugin;
        private final EconomyManager economy;
        private final Class<?> responseClass;
        private final Class<?> responseTypeClass;

        Handler(EssentialEngine plugin, EconomyManager economy,
                Class<?> responseClass, Class<?> responseTypeClass) {
            this.plugin = plugin;
            this.economy = economy;
            this.responseClass = responseClass;
            this.responseTypeClass = responseTypeClass;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object[] arguments = args == null ? new Object[0] : args;

            switch (name) {
                case "isEnabled":
                    return true;
                case "getName":
                    return "EssentialEngine";
                case "hasBankSupport":
                    return false;
                case "fractionalDigits":
                    return 2;
                case "format":
                    return economy.format(firstDouble(arguments));
                case "currencyNamePlural":
                case "currencyNameSingular":
                    return economy.currencyName();
                case "hasAccount":
                case "createPlayerAccount":
                    return resolve(arguments) != null;
                case "getBalance": {
                    UUID uuid = resolve(arguments);
                    return uuid == null ? 0D : economy.getBalance(uuid);
                }
                case "has": {
                    UUID uuid = resolve(arguments);
                    return uuid != null && economy.has(uuid, firstDouble(arguments));
                }
                case "withdrawPlayer": {
                    UUID uuid = resolve(arguments);
                    if (uuid == null) {
                        return response(0, 0, "FAILURE", "找不到对应的玩家账户");
                    }
                    double amount = firstDouble(arguments);
                    if (amount < 0) {
                        return response(0, economy.getBalance(uuid), "FAILURE", "金额不能为负数");
                    }
                    if (!economy.withdraw(uuid, amount)) {
                        return response(0, economy.getBalance(uuid), "FAILURE", "余额不足");
                    }
                    return response(amount, economy.getBalance(uuid), "SUCCESS", null);
                }
                case "depositPlayer": {
                    UUID uuid = resolve(arguments);
                    if (uuid == null) {
                        return response(0, 0, "FAILURE", "找不到对应的玩家账户");
                    }
                    double amount = firstDouble(arguments);
                    if (amount < 0) {
                        return response(0, economy.getBalance(uuid), "FAILURE", "金额不能为负数");
                    }
                    economy.deposit(uuid, amount);
                    return response(amount, economy.getBalance(uuid), "SUCCESS", null);
                }
                case "getBanks":
                    return new ArrayList<String>();
                case "equals":
                    return proxy == arguments[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "EssentialEngine-VaultEconomy";
                default:
                    break;
            }

            // 银行相关及其它未实现方法
            Class<?> returnType = method.getReturnType();
            if (responseClass.isAssignableFrom(returnType)) {
                return response(0, 0, "NOT_IMPLEMENTED", "EssentialEngine 不支持银行功能");
            }
            if (returnType == boolean.class || returnType == Boolean.class) {
                return false;
            }
            if (returnType == double.class || returnType == Double.class) {
                return 0D;
            }
            if (returnType == int.class || returnType == Integer.class) {
                return 0;
            }
            if (returnType == String.class) {
                return "";
            }
            if (List.class.isAssignableFrom(returnType)) {
                return new ArrayList<String>();
            }
            return null;
        }

        private Object response(double amount, double balance, String type, String error) throws Exception {
            Object typeValue = null;
            Object[] constants = responseTypeClass.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (constant instanceof Enum<?> value && value.name().equals(type)) {
                        typeValue = constant;
                        break;
                    }
                }
                if (typeValue == null && constants.length > 0) {
                    typeValue = constants[0];
                }
            }
            Constructor<?> constructor = responseClass.getConstructor(
                    double.class, double.class, responseTypeClass, String.class);
            return constructor.newInstance(amount, balance, typeValue, error);
        }

        private UUID resolve(Object[] args) {
            for (Object arg : args) {
                if (arg instanceof OfflinePlayer offlinePlayer) {
                    return offlinePlayer.getUniqueId();
                }
            }
            for (Object arg : args) {
                if (arg instanceof String text && !text.isEmpty()) {
                    UUID uuid = plugin.users().resolveUuid(text);
                    if (uuid != null) {
                        return uuid;
                    }
                }
            }
            return null;
        }

        private UUID requireUuid(Object[] args) {
            UUID uuid = resolve(args);
            if (uuid == null) {
                throw new IllegalArgumentException("找不到对应的玩家账户");
            }
            return uuid;
        }

        private double firstDouble(Object[] args) {
            for (Object arg : args) {
                if (arg instanceof Number number) {
                    return number.doubleValue();
                }
            }
            return 0D;
        }
    }
}

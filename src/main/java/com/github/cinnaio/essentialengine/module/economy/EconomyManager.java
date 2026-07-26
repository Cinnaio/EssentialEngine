package com.github.cinnaio.essentialengine.module.economy;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.storage.TransactionRecord;
import com.github.cinnaio.essentialengine.core.user.UserData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 内置经济系统。
 *
 * <p>余额直接存在玩家数据里，所以自动跟随 config 里选择的存储后端
 * （YAML / SQLite / MySQL）；MySQL 时还能多服共享同一份余额。</p>
 */
public class EconomyManager {

    private final EssentialEngine plugin;
    /** 流水记账。经济模块启用后由它注入；为 null 时所有记账调用都是空操作。 */
    private volatile EconomyLedger ledger;

    public EconomyManager(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void setLedger(EconomyLedger ledger) {
        this.ledger = ledger;
    }

    public EconomyLedger ledger() {
        return ledger;
    }

    private void log(UUID uuid, UserData data, String type, double amount) {
        EconomyLedger current = ledger;
        if (current != null) {
            current.record(uuid, data.getName(), type, amount, data.getBalance());
        }
    }

    public String symbol() {
        return plugin.getConfig().getString("modules.economy.currency-symbol", "$");
    }

    public String currencyName() {
        return plugin.getConfig().getString("modules.economy.currency-name", "金币");
    }

    public double startingBalance() {
        return plugin.getConfig().getDouble("modules.economy.starting-balance", 100D);
    }

    public String format(double amount) {
        return symbol() + round(amount);
    }

    public static double round(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 取得玩家数据。在线玩家走内存缓存；离线玩家会读一次存储，
     * 因此这个方法不要在主线程的高频循环里调用。
     */
    public UserData resolve(UUID uuid) {
        UserData cached = plugin.users().getIfLoaded(uuid);
        return cached != null ? cached : plugin.users().loadOffline(uuid);
    }

    public double getBalance(UUID uuid) {
        UserData data = resolve(uuid);
        return data == null ? 0D : round(data.getBalance());
    }

    public boolean has(UUID uuid, double amount) {
        return getBalance(uuid) >= round(amount);
    }

    public boolean withdraw(UUID uuid, double amount) {
        UserData data = resolve(uuid);
        if (data == null || data.getBalance() < round(amount)) {
            return false;
        }
        data.setBalance(round(data.getBalance() - amount));
        persist(uuid, data);
        log(uuid, data, TransactionRecord.WITHDRAW, round(amount));
        return true;
    }

    public void deposit(UUID uuid, double amount) {
        UserData data = resolve(uuid);
        if (data == null) {
            return;
        }
        data.setBalance(round(data.getBalance() + amount));
        persist(uuid, data);
        log(uuid, data, TransactionRecord.DEPOSIT, round(amount));
    }

    public void set(UUID uuid, double amount) {
        UserData data = resolve(uuid);
        if (data == null) {
            return;
        }
        double before = data.getBalance();
        data.setBalance(round(amount));
        persist(uuid, data);
        // SET 记的是变动幅度，方向不确定，因此不计入进出统计
        log(uuid, data, TransactionRecord.SET, Math.abs(round(amount) - before));
    }

    /** 离线玩家改完余额要立刻落盘，在线玩家交给自动保存即可。 */
    private void persist(UUID uuid, UserData data) {
        if (plugin.users().getIfLoaded(uuid) == null) {
            plugin.users().saveAsync(data);
        }
    }
}

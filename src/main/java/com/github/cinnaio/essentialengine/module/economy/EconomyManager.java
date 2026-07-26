package com.github.cinnaio.essentialengine.module.economy;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.storage.TransactionRecord;
import com.github.cinnaio.essentialengine.core.user.UserData;

import java.util.UUID;
import java.util.function.DoubleUnaryOperator;

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

    /** 记一笔流水。{@code after} 必须是本次变动的结果快照，不能改完再查一次。 */
    private void log(UserData data, String type, double amount, double after) {
        EconomyLedger current = ledger;
        if (current != null) {
            current.record(data.getUuid(), data.getName(), type, amount, after);
        }
    }

    /** 同上，但显式指定来源说明（本插件自己的命令用）。 */
    private void logAs(UserData data, String type, double amount, double after, String detail) {
        EconomyLedger current = ledger;
        if (current != null) {
            EconomyLedger.withSource(detail, () ->
                    current.record(data.getUuid(), data.getName(), type, amount, after));
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
        return UserData.roundMoney(amount);
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
        if (data == null) {
            return false;
        }
        // 「够不够」和「扣掉」必须是一步完成的，分成两步就能被并发调用刷出负数
        UserData.BalanceChange change = data.tryWithdraw(amount);
        if (change == null) {
            return false;
        }
        persist(uuid, data);
        log(data, TransactionRecord.WITHDRAW, round(amount), change.after());
        return true;
    }

    public void deposit(UUID uuid, double amount) {
        UserData data = resolve(uuid);
        if (data == null) {
            return;
        }
        UserData.BalanceChange change = data.updateBalance(current -> current + amount);
        persist(uuid, data);
        log(data, TransactionRecord.DEPOSIT, round(amount), change.after());
    }

    public void set(UUID uuid, double amount) {
        UserData data = resolve(uuid);
        if (data == null) {
            return;
        }
        UserData.BalanceChange change = data.updateBalance(current -> amount);
        persist(uuid, data);
        // SET 记的是变动幅度，方向不确定，因此不计入进出统计
        log(data, TransactionRecord.SET, Math.abs(change.delta()), change.after());
    }

    /**
     * 原子地按 operator 改余额并记一笔流水。
     *
     * <p>给 {@code /eco}、REST 接口这类「要先读当前值才能算出新值」的场景用，
     * 免得每个调用点各自实现一遍读-改-写——那正是并发出问题的地方。
     * 流水类型按变动方向自动判定。</p>
     *
     * @param detail 来源说明，会写进流水的来源字段
     */
    public UserData.BalanceChange apply(UserData data, DoubleUnaryOperator operator, String detail) {
        UserData.BalanceChange change = data.updateBalance(operator);
        persist(data.getUuid(), data);
        double delta = change.delta();
        if (delta != 0) {
            logAs(data, delta > 0 ? TransactionRecord.DEPOSIT : TransactionRecord.WITHDRAW,
                    Math.abs(delta), change.after(), detail);
        }
        return change;
    }

    /** 转账结果。 */
    public enum Transfer {
        OK,
        /** 付款方余额不足。 */
        NOT_ENOUGH,
        /** 收款方余额会超过配置的上限。 */
        TARGET_FULL
    }

    /**
     * 原子转账。
     *
     * <p>两个账户各有各的锁，这里<b>按 UUID 排序后再依次加锁</b>：
     * 否则 A 给 B 转账的同时 B 也在给 A 转账，两条线程会各持一把锁等对方的锁，
     * 直接把服务器主线程锁死。</p>
     *
     * @param maxBalance 收款方余额上限，{@code <= 0} 表示不限制
     */
    public Transfer transfer(UserData from, UserData to, double amount, double maxBalance, String detail) {
        double value = round(amount);
        UserData first = from.getUuid().compareTo(to.getUuid()) <= 0 ? from : to;
        UserData second = first == from ? to : from;

        double fromAfter;
        double toAfter;
        synchronized (first) {
            synchronized (second) {
                if (from.getBalance() < value) {
                    return Transfer.NOT_ENOUGH;
                }
                if (maxBalance > 0 && to.getBalance() + value > maxBalance) {
                    return Transfer.TARGET_FULL;
                }
                from.setBalance(from.getBalance() - value);
                to.setBalance(to.getBalance() + value);
                fromAfter = from.getBalance();
                toAfter = to.getBalance();
            }
        }

        // 玩家之间的转账是明面上的资产变动，不等自动保存，立刻排队落盘
        plugin.users().saveAsync(from);
        plugin.users().saveAsync(to);
        logAs(from, TransactionRecord.WITHDRAW, value, fromAfter, detail + " → " + to.getName());
        logAs(to, TransactionRecord.DEPOSIT, value, toAfter, detail + " ← " + from.getName());
        return Transfer.OK;
    }

    /** 离线玩家改完余额要立刻落盘，在线玩家交给自动保存即可。 */
    private void persist(UUID uuid, UserData data) {
        if (plugin.users().getIfLoaded(uuid) == null) {
            plugin.users().saveAsync(data);
        }
    }
}

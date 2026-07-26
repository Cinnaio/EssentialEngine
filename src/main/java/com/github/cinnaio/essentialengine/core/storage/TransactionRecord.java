package com.github.cinnaio.essentialengine.core.storage;

import java.util.UUID;

/**
 * 一条经济流水。
 *
 * <p>放在 {@code core.storage} 而不是经济模块里，是为了让 {@link StorageProvider}
 * 能直接引用它——否则 core 层就要反过来依赖 module 层。</p>
 *
 * @param timestamp    发生时间
 * @param uuid         账户所属玩家
 * @param name         当时的玩家名（冗余存一份，方便离线查询与导出）
 * @param type         {@code DEPOSIT} / {@code WITHDRAW} / {@code SET}
 * @param amount       变动金额，恒为正数；方向由 {@code type} 决定
 * @param balanceAfter 变动后的余额
 * @param source       来源：其它插件的名字，或本插件的 {@code EssentialEngine}
 * @param detail       细节：命令名、对方玩家、套装名等，可为空串
 */
public record TransactionRecord(
        long timestamp,
        UUID uuid,
        String name,
        String type,
        double amount,
        double balanceAfter,
        String source,
        String detail) {

    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String SET = "SET";

    /** 这笔流水是否让玩家的钱变多。SET 视为中性，不计入进出统计。 */
    public boolean isInflow() {
        return DEPOSIT.equals(type);
    }

    public boolean isOutflow() {
        return WITHDRAW.equals(type);
    }
}

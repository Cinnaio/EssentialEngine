package com.github.cinnaio.essentialengine.core.storage;

/**
 * 全服经济总览。
 *
 * @param accounts 有记录的账户数
 * @param total    流通总量（所有账户余额之和）
 * @param average  人均余额
 * @param richest  最高余额
 */
public record EconomySummary(long accounts, double total, double average, double richest) {

    public static final EconomySummary EMPTY = new EconomySummary(0, 0, 0, 0);
}

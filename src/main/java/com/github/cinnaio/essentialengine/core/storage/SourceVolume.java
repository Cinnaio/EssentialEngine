package com.github.cinnaio.essentialengine.core.storage;

/**
 * 某个来源在一段时间内的资金进出汇总。
 *
 * @param source 来源名（插件名，或 EssentialEngine 自己的命令）
 * @param in     该来源给玩家发出去的总额
 * @param out    该来源从玩家身上收走的总额
 * @param count  流水笔数
 */
public record SourceVolume(String source, double in, double out, long count) {

    /** 净流入（正数表示这个来源在往经济系统里注入货币）。 */
    public double net() {
        return in - out;
    }
}

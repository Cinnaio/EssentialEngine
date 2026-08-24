package com.github.cinnaio.essentialengine.core.storage;

/**
 * 一次性能采样：某一时刻的 TPS、内存占用与在线人数。
 *
 * <p>采样完全在异步线程完成（TPS 是 Paper 服务端缓存的读数，内存来自 {@link Runtime}，
 * 在线人数由进出服事件计数器维护），因此<b>采样本身不会打扰主线程</b>——
 * 哪怕主线程已经卡死，采样器依然能记录下当时的 TPS，这正是排查卡顿需要的。</p>
 */
public record PerfSample(long timestamp, double tps, long usedMB, long maxMB, int online) {
}

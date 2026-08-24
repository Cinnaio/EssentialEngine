package com.github.cinnaio.essentialengine.module.monitor;

/**
 * 监控模块的运行时配置快照。
 *
 * <p>模块在 {@code setup()} 时从 config.yml 读取一次，之后整个运行周期用这份快照；
 * 热重载（{@code /ee reload}）时会重新读取并替换。</p>
 */
public record MonitorConfig(
        /** 性能采样间隔（秒）。 */
        int sampleIntervalSeconds,
        /** 采样与事件攒多少秒批量落盘一次。 */
        int flushSeconds,
        /** 采样与事件保留天数，超期的每小时清理一次。 */
        int retentionDays,
        /** 内存里保留最近多少条采样，供快速查询（历史数据在存储里）。 */
        int memorySamples,
        /** 是否记录严重卡顿事件（TPS 低于阈值）与恢复事件。 */
        boolean recordLag,
        /** 卡顿判定阈值：TPS 低于该值记为一次卡顿事件。 */
        double lagThresholdTps,
        /** 同一时段卡顿事件的去重间隔（秒）。 */
        int lagCooldownSeconds,
        /** 是否记录内存占用过高告警事件。 */
        boolean recordMemory,
        /** 内存告警阈值：已用内存占比超过该百分比时告警。 */
        int memoryWarningPercent,
        /** 同一时段内存告警的去重间隔（分钟）。 */
        int memoryCooldownMinutes,
        /** 是否记录服务器启动 / 关闭 / 重载事件（异常退出在下次启动时补记）。 */
        boolean recordStartStop,
        /** 是否允许外部程序（如 AstrBot）通过 API 写入自定义事件。 */
        boolean allowCustomEvents) {
}

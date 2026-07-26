package com.github.cinnaio.essentialengine.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeUtilTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Nested
    @DisplayName("带单位的时长")
    class WithUnit {

        @Test
        void 单个单位() {
            assertEquals(30 * SECOND, TimeUtil.parseDuration("30s"));
            assertEquals(30 * MINUTE, TimeUtil.parseDuration("30m"));
            assertEquals(2 * HOUR, TimeUtil.parseDuration("2h"));
            assertEquals(7 * DAY, TimeUtil.parseDuration("7d"));
        }

        @Test
        void 组合单位相加() {
            assertEquals(12 * HOUR + 30 * MINUTE, TimeUtil.parseDuration("12h30m"));
            assertEquals(DAY + 2 * HOUR + 3 * MINUTE + 4 * SECOND,
                    TimeUtil.parseDuration("1d2h3m4s"));
        }

        @Test
        void 中文单位() {
            assertEquals(7 * DAY, TimeUtil.parseDuration("7天"));
            assertEquals(30 * MINUTE, TimeUtil.parseDuration("30分钟"));
            assertEquals(2 * HOUR + 30 * MINUTE, TimeUtil.parseDuration("2小时30分钟"));
        }

        @Test
        void 大小写与空格都能接受() {
            assertEquals(7 * DAY, TimeUtil.parseDuration("7D"));
            assertEquals(2 * HOUR, TimeUtil.parseDuration(" 2 H "));
        }
    }

    @Nested
    @DisplayName("永久")
    class Permanent {

        @ParameterizedTest
        @ValueSource(strings = {"perm", "PERM", "permanent", "forever", "永久"})
        void 返回零表示永久(String input) {
            assertEquals(0, TimeUtil.parseDuration(input));
        }
    }

    @Nested
    @DisplayName("非法输入")
    class Invalid {

        /**
         * 回归测试：裸数字必须被拒绝。
         *
         * <p>曾经把无单位的数字当分钟处理，于是 {@code /tempban 某人 7} 会静默地
         * 封 7 分钟，而管理员以为封了 7 天——命令不报错，没有任何提示。
         * 这是这条用例存在的全部理由，改动解析逻辑时不要顺手放宽。</p>
         */
        @ParameterizedTest
        @ValueSource(strings = {"7", "0", "100", " 42 "})
        void 无单位的数字一律拒绝(String input) {
            assertEquals(-1, TimeUtil.parseDuration(input),
                    "无单位数字必须返回 -1，否则会被静默当成分钟");
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "d", "很久", "-"})
        void 无法识别的输入(String input) {
            assertEquals(-1, TimeUtil.parseDuration(input));
        }

        @Test
        void 空值() {
            assertEquals(-1, TimeUtil.parseDuration(null));
            assertEquals(-1, TimeUtil.parseDuration(""));
        }
    }

    @Nested
    @DisplayName("格式化")
    class Format {

        @Test
        void 按大到小拼接() {
            assertEquals("3天2小时5分钟", TimeUtil.formatDuration(3 * DAY + 2 * HOUR + 5 * MINUTE));
            assertEquals("2小时", TimeUtil.formatDuration(2 * HOUR));
            assertEquals("45秒", TimeUtil.formatDuration(45 * SECOND));
        }

        @Test
        void 有天数时不再显示秒() {
            // 「3天0小时0分钟12秒」这种精度对玩家没有意义
            assertEquals("3天", TimeUtil.formatDuration(3 * DAY + 12 * SECOND));
        }

        @Test
        void 非正数() {
            assertEquals("0秒", TimeUtil.formatDuration(0));
            assertEquals("0秒", TimeUtil.formatDuration(-1));
        }

        @Test
        void 解析与格式化能往返() {
            long millis = TimeUtil.parseDuration("3d2h5m");
            assertEquals("3天2小时5分钟", TimeUtil.formatDuration(millis));
        }
    }
}

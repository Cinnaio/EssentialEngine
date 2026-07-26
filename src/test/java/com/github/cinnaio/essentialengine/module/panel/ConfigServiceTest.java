package com.github.cinnaio.essentialengine.module.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 面板保存配置时的类型关口。
 *
 * <p>前端传上来的一律是字符串，这里要按 config.yml 里原值的类型转回去。
 * 转错了不会报错，只会静默把 YAML 里的类型改掉——比如把整数端口写成
 * {@code "8193"}，下次读配置就拿不到数字了。</p>
 */
class ConfigServiceTest {

    @Nested
    @DisplayName("布尔")
    class Booleans {

        @Test
        void 接受字符串与布尔两种形态() {
            assertEquals(true, ConfigService.coerce(false, "true"));
            assertEquals(false, ConfigService.coerce(true, "FALSE"));
            assertEquals(true, ConfigService.coerce(false, true));
        }

        @Test
        void 拒绝不是布尔的值() {
            // 「1」「yes」都不接受：宁可拒绝，也不要猜服主的意思
            assertNull(ConfigService.coerce(false, "1"));
            assertNull(ConfigService.coerce(false, "yes"));
            assertNull(ConfigService.coerce(false, ""));
        }
    }

    @Nested
    @DisplayName("数字")
    class Numbers {

        @Test
        void 整数保持整数类型() {
            Object result = ConfigService.coerce(8193, "9000");
            assertInstanceOf(Integer.class, result, "原值是整数，写回去也必须是整数");
            assertEquals(9000, result);
        }

        @Test
        void 小数保持小数类型() {
            Object result = ConfigService.coerce(0.5D, "2.25");
            assertInstanceOf(Double.class, result);
            assertEquals(2.25, result);
        }

        @Test
        void 给整数字段填小数会截断而不是报错() {
            // 表单里手滑填了 30.9，落进整数字段取 30——这是当前行为，改动前先想清楚
            assertEquals(30, ConfigService.coerce(10, "30.9"));
        }

        @Test
        void 拒绝非数字() {
            assertNull(ConfigService.coerce(10, "abc"));
            assertNull(ConfigService.coerce(0.5D, "很多"));
        }
    }

    @Nested
    @DisplayName("列表")
    class Lists {

        @Test
        void 换行或逗号分隔的文本都能拆() {
            Object byComma = ConfigService.coerce(List.of("a"), "x, y ,z");
            assertEquals(List.of("x", "y", "z"), byComma);

            Object byNewline = ConfigService.coerce(List.of("a"), "x\ny\nz");
            assertEquals(List.of("x", "y", "z"), byNewline);
        }

        @Test
        void 跳过空项() {
            assertEquals(List.of("x", "y"), ConfigService.coerce(List.of("a"), "x,,  ,y"));
        }

        @Test
        void 直接传数组也接受() {
            assertEquals(List.of("x", "y"), ConfigService.coerce(List.of("a"), List.of("x", " y ")));
        }

        @Test
        void 空文本得到空列表() {
            assertEquals(List.of(), ConfigService.coerce(List.of("a"), ""));
        }
    }

    @Nested
    @DisplayName("字符串")
    class Strings {

        @Test
        void 会去掉首尾空白() {
            assertEquals("hello", ConfigService.coerce("old", "  hello  "));
        }

        @Test
        void 允许写成空串() {
            // 敏感字段留空表示「保持原值」，那层判断在 save 里，不在这里
            assertEquals("", ConfigService.coerce("old", "   "));
        }
    }

    @Test
    void null一律拒绝() {
        assertNull(ConfigService.coerce("old", null));
        assertNull(ConfigService.coerce(10, null));
    }
}

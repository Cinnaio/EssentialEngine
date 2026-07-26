package com.github.cinnaio.essentialengine.module.teleport;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分类型冷却 / 吟唱的取值规则。
 *
 * <p>要钉住的关键点是<b>兼容性</b>：没写 cooldowns / warmups 段的老配置，
 * 行为必须和引入该功能之前一模一样。</p>
 */
class TeleportTimingTest {

    private YamlConfiguration load(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test
    void 老配置没有覆写段_全部走全局值() throws Exception {
        var config = load("""
                modules:
                  teleport:
                    warmup-seconds: 3
                    cooldown-seconds: 5
                """);
        for (String type : new String[]{"home", "warp", "spawn", "tpa", "back", "rtp"}) {
            assertEquals(5, TeleportManager.secondsFor(config, "cooldowns", type, "cooldown-seconds", 5));
            assertEquals(3, TeleportManager.secondsFor(config, "warmups", type, "warmup-seconds", 3));
        }
    }

    @Test
    void 配了覆写的类型用覆写_其余仍走全局() throws Exception {
        var config = load("""
                modules:
                  teleport:
                    cooldown-seconds: 5
                    cooldowns:
                      rtp: 300
                      back: 10
                """);
        assertEquals(300, TeleportManager.secondsFor(config, "cooldowns", "rtp", "cooldown-seconds", 5));
        assertEquals(10, TeleportManager.secondsFor(config, "cooldowns", "back", "cooldown-seconds", 5));
        assertEquals(5, TeleportManager.secondsFor(config, "cooldowns", "home", "cooldown-seconds", 5),
                "没覆写的类型必须继续走全局值");
    }

    @Test
    void 覆写为零表示这一类不限制_而不是回落全局() throws Exception {
        var config = load("""
                modules:
                  teleport:
                    cooldown-seconds: 5
                    cooldowns:
                      home: 0
                """);
        assertEquals(0, TeleportManager.secondsFor(config, "cooldowns", "home", "cooldown-seconds", 5),
                "0 是显式配置，不能因为它不是正数就回落到全局的 5 秒");
    }

    @Test
    void 冷却与吟唱的覆写互不影响() throws Exception {
        var config = load("""
                modules:
                  teleport:
                    warmup-seconds: 3
                    cooldown-seconds: 5
                    cooldowns:
                      rtp: 300
                """);
        assertEquals(300, TeleportManager.secondsFor(config, "cooldowns", "rtp", "cooldown-seconds", 5));
        assertEquals(3, TeleportManager.secondsFor(config, "warmups", "rtp", "warmup-seconds", 3),
                "只覆写了冷却时，吟唱必须仍走全局值");
    }

    @Test
    void 连全局值都没有时用代码默认() throws Exception {
        var config = load("modules:\n  teleport:\n    enabled: true\n");
        assertEquals(5, TeleportManager.secondsFor(config, "cooldowns", "home", "cooldown-seconds", 5));
        assertEquals(3, TeleportManager.secondsFor(config, "warmups", "home", "warmup-seconds", 3));
    }
}

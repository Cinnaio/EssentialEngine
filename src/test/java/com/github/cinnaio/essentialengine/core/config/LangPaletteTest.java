package com.github.cinnaio.essentialengine.core.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言文件的结构与配色约定。
 *
 * <p>两份语言文件必须键集合完全一致（缺键会静默回落到另一种语言，玩家看到中英混杂），
 * 颜色只允许约定的原版 Minecraft 标签——加新消息时顺手用了 &lt;gold&gt; 或十六进制色，
 * 整体观感就又开始漂移，这正是上一版被换掉的原因。</p>
 */
class LangPaletteTest {

    /** 约定的原版配色：正文 white / 次要 gray / 弱化 dark_gray / 强调 green red yellow aqua。 */
    private static final Set<String> ALLOWED_COLORS = Set.of(
            "white", "gray", "dark_gray", "green", "red", "yellow", "aqua");

    /** 原版全部 16 色。出现在这里但不在 ALLOWED_COLORS 里的就是约定外用色。 */
    private static final Set<String> ALL_VANILLA = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red",
            "light_purple", "yellow", "white");

    private static final Pattern TAG = Pattern.compile("<(/?)([a-z_#0-9]+)>");

    private Map<String, Object> load(String name) throws Exception {
        Path path = Path.of("src", "main", "resources", "lang", name);
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    private void flatten(Map<String, Object> node, String prefix,
                         Set<String> keys, List<String> values) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) child;
                flatten(typed, path, keys, values);
            } else {
                keys.add(path);
                if (entry.getValue() instanceof String text) {
                    values.add(text);
                }
            }
        }
    }

    @Test
    void 两份语言文件的键集合完全一致() throws Exception {
        Set<String> zh = new TreeSet<>();
        Set<String> en = new TreeSet<>();
        flatten(load("zh_CN.yml"), "", zh, new ArrayList<>());
        flatten(load("en_US.yml"), "", en, new ArrayList<>());
        assertEquals(zh, en, "缺键会静默回落，玩家会看到中英混杂的消息");
    }

    @Test
    void 不再残留十六进制颜色标签() throws Exception {
        for (String file : new String[]{"zh_CN.yml", "en_US.yml"}) {
            List<String> values = new ArrayList<>();
            flatten(load(file), "", new TreeSet<>(), values);
            for (String value : values) {
                assertTrue(!value.contains("<#"),
                        file + " 仍有十六进制色: " + value);
            }
        }
    }

    @Test
    void 颜色只用约定内的原版标签() throws Exception {
        for (String file : new String[]{"zh_CN.yml", "en_US.yml"}) {
            List<String> values = new ArrayList<>();
            flatten(load(file), "", new TreeSet<>(), values);
            for (String value : values) {
                Matcher matcher = TAG.matcher(value);
                while (matcher.find()) {
                    String name = matcher.group(2);
                    // 只审查真的是颜色的标签；<玩家>、<give|take> 这类用法提示是字面文本
                    if (ALL_VANILLA.contains(name)) {
                        assertTrue(ALLOWED_COLORS.contains(name),
                                file + " 用了约定外的颜色 <" + name + ">: " + value);
                    }
                }
            }
        }
    }
}

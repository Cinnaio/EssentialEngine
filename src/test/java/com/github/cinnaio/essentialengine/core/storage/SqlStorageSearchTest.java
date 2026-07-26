package com.github.cinnaio.essentialengine.core.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 玩家搜索的 LIKE 模式拼装。
 *
 * <p>不连数据库，只验证转义——这里出错不会报错，只会让搜索悄悄多返回或少返回人。</p>
 */
class SqlStorageSearchTest {

    @Test
    void 普通名字两边加通配符() {
        assertEquals("%alex%", SqlStorage.likePattern("Alex"));
    }

    @Test
    void 统一转小写以配合LOWER比较() {
        assertEquals("%cinnaio%", SqlStorage.likePattern("CinnAIo"));
    }

    @Test
    void 去掉首尾空格() {
        assertEquals("%alex%", SqlStorage.likePattern("  Alex  "));
    }

    /**
     * 回归测试：下划线必须转义。
     *
     * <p>{@code _} 是 LIKE 的单字符通配符，而它在 Minecraft 用户名里完全合法。
     * 不转义的话，搜 {@code Steve_01} 会把 {@code SteveX01} 也一起捞出来——
     * 管理员看到两个长得像的名字，很可能对错人执行封禁。</p>
     */
    @Test
    void 下划线被转义而不是当通配符() {
        assertEquals("%steve!_01%", SqlStorage.likePattern("Steve_01"));
    }

    @Test
    void 百分号被转义() {
        // 用户名里出现不了，但搜索框是自由输入，%% 会匹配到所有人
        assertEquals("%a!%b%", SqlStorage.likePattern("a%b"));
    }

    @Test
    void 转义符自身先被转义() {
        // 必须最先替换 !，否则后面转义产生的 ! 会被重复处理
        assertEquals("%a!!b%", SqlStorage.likePattern("a!b"));
        assertEquals("%!!!_%", SqlStorage.likePattern("!_"));
    }
}

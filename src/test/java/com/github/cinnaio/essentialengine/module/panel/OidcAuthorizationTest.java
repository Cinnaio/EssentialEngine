package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.module.panel.OidcClient.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth 登录后的准入判断。
 *
 * <p>这是面板唯一的授权关口：签名验过之后，能不能进面板全看这里。
 * 放宽一点就等于把服务端配置和经济数据交出去，所以每条分支都要钉住。</p>
 */
class OidcAuthorizationTest {

    private static final List<String> NO_LIST = List.of();

    private Identity user(String username, boolean admin) {
        return new Identity("sub-" + username, username,
                "uuid-" + username, admin, 0);
    }

    private Identity banned(String username) {
        return new Identity("sub-" + username, username, "uuid-" + username, true, -1);
    }

    @Nested
    @DisplayName("没有名单时按管理员标志判断")
    class WithoutAllowlist {

        @Test
        void 要求管理员时只放行管理员() {
            assertTrue(OidcClient.isAllowed(user("admin", true), true, NO_LIST));
            assertFalse(OidcClient.isAllowed(user("player", false), true, NO_LIST));
        }

        @Test
        void 不要求管理员时任何人都能进() {
            assertTrue(OidcClient.isAllowed(user("player", false), false, NO_LIST));
        }

        @Test
        void 名单为null等同于没有名单() {
            assertTrue(OidcClient.isAllowed(user("admin", true), true, null));
            assertFalse(OidcClient.isAllowed(user("player", false), true, null));
        }
    }

    @Nested
    @DisplayName("名单")
    class Allowlist {

        @Test
        void 可以按角色名匹配且不分大小写() {
            assertTrue(OidcClient.isAllowed(user("Cinnaio", false), false, List.of("cinnaio")));
            assertTrue(OidcClient.isAllowed(user("Cinnaio", false), false, List.of("CINNAIO")));
        }

        @Test
        void 也可以按uuid或sub匹配() {
            Identity identity = user("Cinnaio", false);
            assertTrue(OidcClient.isAllowed(identity, false, List.of("uuid-Cinnaio")));
            assertTrue(OidcClient.isAllowed(identity, false, List.of("sub-Cinnaio")));
        }

        @Test
        void 不在名单里就拒绝() {
            assertFalse(OidcClient.isAllowed(user("Someone", true), false, List.of("cinnaio")));
        }

        @Test
        void 名单里的空项会被跳过而不是放行所有人() {
            List<String> messy = Arrays.asList("", "   ", null, "cinnaio");
            assertTrue(OidcClient.isAllowed(user("Cinnaio", false), false, messy));
            assertFalse(OidcClient.isAllowed(user("Someone", false), false, messy));
        }

        /**
         * 命中名单会<b>覆盖</b> require-admin —— 这是有意为之，不是漏判。
         *
         * <p>填名单是替换判断依据，不是在 {@code require-admin} 之上再收窄一层。
         * 之所以不改成「两个条件同时满足」：那样一来 {@code require-admin: true} 时
         * 名单只能是管理员的子集，「放行一个非管理员的副手」这种最常见的诉求就没法表达了；
         * 而且改了会让现在靠名单进面板的非管理员在升级后突然被挡在外面，
         * 报错还只说「无权访问」，没人猜得到是插件语义变了。</p>
         *
         * <p>真正的风险是这条规则不够显眼，所以由 {@code PanelModule.announceAccessPolicy()}
         * 在开服时把实际生效的规则打进日志，而不是靠改语义来解决。</p>
         *
         * <p>这条用例是为了让将来想改的人先看到它失败并主动决策，而不是无声地改变授权口径。</p>
         */
        @Test
        void 命中名单会覆盖管理员要求() {
            assertTrue(OidcClient.isAllowed(user("Cinnaio", false), true, List.of("cinnaio")));
        }
    }

    @Nested
    @DisplayName("封禁")
    class Banned {

        @Test
        void 皮肤站封禁的账号一律拒绝() {
            assertFalse(OidcClient.isAllowed(banned("Cinnaio"), false, NO_LIST));
        }

        @Test
        void 即使是管理员且在名单里也拒绝() {
            assertFalse(OidcClient.isAllowed(banned("Cinnaio"), false, List.of("cinnaio")),
                    "封禁的优先级必须高于名单");
        }
    }

    @Test
    void 没有身份就拒绝() {
        assertFalse(OidcClient.isAllowed(null, false, NO_LIST));
    }
}

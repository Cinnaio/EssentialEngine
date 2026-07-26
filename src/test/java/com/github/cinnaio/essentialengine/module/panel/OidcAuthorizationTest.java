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
         * 钉住当前行为：命中名单会<b>覆盖</b> require-admin。
         *
         * <p>也就是说同时配了名单和 {@code require-admin: true} 时，语义是
         * 「在名单里就行」而不是「既在名单里又是管理员」。这不见得符合直觉，
         * 如果哪天要改成两个条件同时满足，改的人应该看到这条用例失败并主动决策，
         * 而不是无声地改变授权口径。</p>
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

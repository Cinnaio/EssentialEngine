package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.module.webapi.http.HttpLogging;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网页管理面板模块。
 *
 * <p>默认<b>关闭</b>，且默认只监听 {@code 127.0.0.1}——面板能改配置、能管玩家，
 * 直接暴露到公网风险太大。需要远程访问时，推荐的做法是保持只监听本机，
 * 再用 SSH 端口转发或前置 Nginx 加 HTTPS，而不是把 bind-address 改成 0.0.0.0。</p>
 *
 * <p>没有设置密码时模块会拒绝启动，不存在「空密码进后台」的窗口。</p>
 */
public class PanelModule extends EngineModule {

    private PanelServer server;
    private SessionStore sessions;
    private OidcClient oidc;
    private RecentPlayers recentPlayers;

    public PanelModule(EssentialEngine plugin) {
        super(plugin, "panel", "网页管理面板");
    }

    @Override
    protected void setup() {
        String password = cfgString("password", "");
        this.sessions = new SessionStore(password,
                cfgInt("session-minutes", 120),
                cfgInt("max-login-attempts", 5),
                cfgInt("lockout-minutes", 10));
        setupOidc();

        // 两种登录方式至少要有一种可用，否则面板起来了也没人进得去
        if (!sessions.hasPassword() && oidc == null) {
            throw new IllegalStateException(
                    "既没有设置面板密码，也没有配好 OAuth 登录，已拒绝启动。"
                            + "请填 modules.panel.password，或配置 modules.panel.oauth。");
        }

        String bindAddress = cfgString("bind-address", "127.0.0.1");
        int port = cfgInt("port", 8193);

        if (!bindAddress.equals("127.0.0.1") && !bindAddress.equalsIgnoreCase("localhost")) {
            plugin.getLogger().warning("========================================");
            plugin.getLogger().warning("  管理面板正监听 " + bindAddress + "，可被外部网络访问！");
            plugin.getLogger().warning("  面板没有 HTTPS，密码会以明文在网络上传输。");
            plugin.getLogger().warning("  建议改回 127.0.0.1，再用 SSH 隧道或 Nginx 反代加 HTTPS。");
            plugin.getLogger().warning("========================================");
        }

        // 「最近离线」名单：退出时记录，供玩家页展示。历史名单异步恢复，
        // 恢复完成前面板只是暂时看不到离线区，不值得为它拖住开服
        this.recentPlayers = new RecentPlayers(plugin);
        listener(recentPlayers);
        SchedulerCompat.runAsync(plugin, recentPlayers::load);

        List<String> avatarSources = avatarSources();
        Router router = new Router();
        new PanelApi(plugin, sessions, new ConfigService(plugin), oidc, avatarSources, recentPlayers)
                .register(router);

        HttpLogging.quietClientDisconnects(() -> plugin.getConfig().getBoolean("debug", false));
        server = new PanelServer(bindAddress, port, router, sessions,
                plugin.getLogger(), cfgBool("log-requests", false),
                oidc == null ? null : this::handleOidcCallback,
                avatarOrigins(avatarSources));
        try {
            server.startServer();
        } catch (IOException error) {
            server = null;
            throw new IllegalStateException("管理面板启动失败，端口 " + port + " 可能已被占用", error);
        }
    }

    /**
     * 玩家头像的来源模板（{@code {name}} 会被前端替换成玩家名）。
     *
     * <p>没写这个键的老配置用内置默认（皮肤站优先，正版头像兜底）；
     * <b>显式写了空列表</b>则视为「不要头像」，两种情况要区分开。</p>
     */
    private List<String> avatarSources() {
        if (!plugin.getConfig().contains(path("avatar-sources"))) {
            return List.of(
                    "https://skin.mscraft.uk/avatar/player/{name}",
                    "https://mc-heads.net/avatar/{name}/100");
        }
        return plugin.getConfig().getStringList(path("avatar-sources")).stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .toList();
    }

    /** 头像模板对应的源（scheme://host[:port]），逐个放进 CSP 的 img-src。 */
    private static List<String> avatarOrigins(List<String> templates) {
        return templates.stream()
                .map(template -> {
                    try {
                        // {name} 不是合法的 URI 字符，先替换掉再解析
                        java.net.URI uri = java.net.URI.create(template.replace("{name}", "x"));
                        if (uri.getScheme() == null || uri.getHost() == null) {
                            return null;
                        }
                        return uri.getScheme() + "://" + uri.getHost()
                                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                    } catch (Exception error) {
                        return null;
                    }
                })
                .filter(origin -> origin != null)
                .distinct()
                .toList();
    }

    /** 按配置装配 OIDC 客户端；没开或配不全就保持 null。 */
    private void setupOidc() {
        if (!plugin.getConfig().getBoolean(path("oauth.enabled"), false)) {
            return;
        }
        OidcClient client = new OidcClient(
                cfgString("oauth.issuer", ""),
                cfgString("oauth.client-id", ""),
                cfgString("oauth.client-secret", ""),
                cfgString("oauth.redirect-uri", ""),
                cfgString("oauth.scopes", "openid profile"));
        if (!client.isConfigured()) {
            plugin.getLogger().warning(
                    "[Panel] OAuth 登录已开启但配置不完整（issuer / client-id / client-secret / redirect-uri "
                            + "都必须填写），本次跳过。");
            return;
        }
        this.oidc = client;
        plugin.getLogger().info("[Panel] OAuth 登录已启用，回调地址: " + client.redirectUri());
        announceAccessPolicy();

        // 后台预热发现文档与 JWKS：让第一个登录的人不必等这两趟出站请求，
        // 也能在开服日志里就发现 issuer 配错 / 皮肤站没起来，而不是等有人登录才暴露。
        // 放异步是因为 provider 无响应时这里会阻塞到超时，绝不能拖住开服。
        SchedulerCompat.runAsync(plugin, () -> {
            try {
                client.warmUp();
                plugin.getLogger().info("[Panel] OAuth 授权服务器连接正常");
            } catch (Exception error) {
                plugin.getLogger().warning("[Panel] 连接 OAuth 授权服务器失败，登录时会重试: " + error.getMessage());
            }
        });
    }

    /**
     * 开服时把「谁能进面板」这条结论直接打进日志。
     *
     * <p>{@code allowed-users} 一旦非空就以名单为准，{@code require-admin} 不再参与判断。
     * 这个语义在 config.yml 的注释里写了，但人配完就不会再回头看注释——
     * 尤其容易踩的是：以为填名单是在 {@code require-admin} 的基础上「再收窄一层」，
     * 实际上是把它整个替换掉了，于是名单里的非管理员也能进。</p>
     *
     * <p>与其改语义（那会让现在靠名单进面板的非管理员在升级后突然被挡在外面，
     * 而且报错只说「无权访问」，没人猜得到是插件语义变了），不如在开服这一刻
     * 把实际生效的规则说清楚，配错的人当场就能发现。</p>
     */
    private void announceAccessPolicy() {
        List<String> allowlist = plugin.getConfig().getStringList(path("oauth.allowed-users"));
        long named = allowlist.stream().filter(entry -> entry != null && !entry.isBlank()).count();
        boolean requireAdmin = cfgBool("oauth.require-admin", true);

        if (named > 0) {
            plugin.getLogger().info("[Panel] 准入规则：仅 allowed-users 名单内的 " + named + " 人可登录"
                    + (requireAdmin ? "；已配置名单，require-admin 不再生效" : ""));
        } else if (requireAdmin) {
            plugin.getLogger().info("[Panel] 准入规则：仅身份源中的管理员可登录");
        } else {
            plugin.getLogger().warning("[Panel] 准入规则：身份源里的任何账号都能登录面板。"
                    + "若非本意，请开启 require-admin 或填写 allowed-users。");
        }
    }

    /**
     * 处理授权服务器的回调：换取身份、判断是否有权进面板、签发会话。
     *
     * <p>会话 token 放在 URL 的 fragment 里带回前端——fragment 不会发给服务端，
     * 也不会进代理和服务器的访问日志，比放 query string 安全；前端拿到后会立刻清掉。</p>
     */
    private String handleOidcCallback(String code, String state, String error) {
        if (error != null && !error.isEmpty()) {
            return "/#error=" + encode("授权被拒绝: " + error);
        }
        try {
            OidcClient.Identity identity = oidc.exchange(code, state);
            boolean allowed = OidcClient.isAllowed(identity,
                    cfgBool("oauth.require-admin", true),
                    plugin.getConfig().getStringList(path("oauth.allowed-users")));
            String who = OidcClient.describe(identity);
            if (!allowed) {
                plugin.getLogger().warning("[Panel] 拒绝 OAuth 登录（无权限）: " + who);
                return "/#error=" + encode("你的账号没有访问这个面板的权限");
            }
            plugin.getLogger().info("[Panel] OAuth 登录成功: " + who);
            return "/#token=" + encode(sessions.issue(who));
        } catch (Exception failure) {
            plugin.getLogger().warning("[Panel] OAuth 登录失败: " + failure.getMessage());
            return "/#error=" + encode(failure.getMessage() == null ? "登录失败" : failure.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @Override
    protected void shutdown() {
        if (recentPlayers != null) {
            // 关服前最后一批退出排的异步落盘可能来不及跑，这里同步兜底一次
            recentPlayers.persist();
            recentPlayers = null;
        }
        if (server != null) {
            server.stopServer();
            server = null;
        }
        if (sessions != null) {
            sessions.clear();
            sessions = null;
        }
        oidc = null;
    }
}

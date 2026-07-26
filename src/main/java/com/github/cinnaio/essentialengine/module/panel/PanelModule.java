package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

        Router router = new Router();
        new PanelApi(plugin, sessions, new ConfigService(plugin), oidc).register(router);

        server = new PanelServer(bindAddress, port, router, sessions,
                plugin.getLogger(), cfgBool("log-requests", false),
                oidc == null ? null : this::handleOidcCallback);
        try {
            server.startServer();
        } catch (IOException error) {
            server = null;
            throw new IllegalStateException("管理面板启动失败，端口 " + port + " 可能已被占用", error);
        }
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

package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;

import java.io.IOException;

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

        if (!sessions.hasPassword()) {
            throw new IllegalStateException(
                    "未设置面板密码，已拒绝启动。请在 config.yml 的 modules.panel.password 填一个密码。");
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
        new PanelApi(plugin, sessions, new ConfigService(plugin)).register(router);

        server = new PanelServer(bindAddress, port, router, sessions,
                plugin.getLogger(), cfgBool("log-requests", false));
        try {
            server.startServer();
        } catch (IOException error) {
            server = null;
            throw new IllegalStateException("管理面板启动失败，端口 " + port + " 可能已被占用", error);
        }
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
    }
}

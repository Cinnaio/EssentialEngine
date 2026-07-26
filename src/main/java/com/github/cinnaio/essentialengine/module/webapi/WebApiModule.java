package com.github.cinnaio.essentialengine.module.webapi;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.module.husktowns.HuskTownsModule;
import com.github.cinnaio.essentialengine.module.webapi.endpoint.EssentialsEndpoint;
import com.github.cinnaio.essentialengine.module.webapi.endpoint.ServerEndpoint;
import com.github.cinnaio.essentialengine.module.webapi.endpoint.TownEndpoint;
import com.github.cinnaio.essentialengine.module.webapi.http.AuthMiddleware;
import com.github.cinnaio.essentialengine.module.webapi.http.HttpServer;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;

import java.io.IOException;

/**
 * REST API 模块（原 LinkEngine 的核心能力）。
 *
 * <p>在服务器上开一个带 API Key 鉴权的 HTTP 接口，让外部程序（QQ 机器人、Discord Bot、
 * 网页后台、监控面板等）能读取服务器状态、查询玩家数据、发广播、执行控制台命令。
 * 默认关闭，需要时在 config.yml 里打开并设置一个足够随机的 api-key。</p>
 */
public class WebApiModule extends EngineModule {

    private HttpServer server;

    public WebApiModule(EssentialEngine plugin) {
        super(plugin, "webapi", "REST API");
    }

    @Override
    protected void setup() {
        String bindAddress = cfgString("bind-address", "127.0.0.1");
        int port = cfgInt("port", 8192);
        String apiKey = cfgString("api-key", "");
        boolean logRequests = cfgBool("log-requests", false);

        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("change-me")) {
            plugin.getLogger().warning("========================================");
            plugin.getLogger().warning("  REST API 的 api-key 还是默认值！");
            plugin.getLogger().warning("  请在 config.yml 的 modules.webapi.api-key 设置一个随机密钥，");
            plugin.getLogger().warning("  否则任何人都能通过该接口控制你的服务器。");
            plugin.getLogger().warning("========================================");
        }

        Router router = new Router();
        new ServerEndpoint(plugin).register(router);
        new EssentialsEndpoint(plugin).register(router);

        if (plugin.modules().get("husktowns") instanceof HuskTownsModule module && module.isEnabled()) {
            new TownEndpoint(plugin, module.getService()).register(router);
            plugin.getLogger().info("[WebAPI] 已挂载 HuskTowns 城镇接口");
        }

        AuthMiddleware auth = new AuthMiddleware(apiKey);
        server = new HttpServer(bindAddress, port, router, auth, plugin.getLogger(), logRequests,
                cfgString("cors-origin", ""));
        try {
            server.startServer();
            plugin.getLogger().info("[WebAPI] 共注册 " + router.size() + " 条接口");
        } catch (IOException error) {
            server = null;
            throw new IllegalStateException("HTTP 接口启动失败，端口 " + port + " 可能已被占用", error);
        }
    }

    @Override
    protected void shutdown() {
        if (server != null) {
            server.stopServer();
            server = null;
        }
    }
}

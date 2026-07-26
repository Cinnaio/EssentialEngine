package com.github.cinnaio.essentialengine.module.webapi.http;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * 内嵌 HTTP 服务（基于 NanoHTTPD），负责鉴权、路由分发与 CORS。
 */
public class HttpServer extends NanoHTTPD {

    private final Router router;
    private final AuthMiddleware auth;
    private final Logger logger;
    private final boolean logRequests;
    private final String corsOrigin;

    public HttpServer(String hostname, int port, Router router, AuthMiddleware auth,
                      Logger logger, boolean logRequests, String corsOrigin) {
        super(hostname, port);
        this.router = router;
        this.auth = auth;
        this.logger = logger;
        this.logRequests = logRequests;
        this.corsOrigin = corsOrigin == null ? "" : corsOrigin.trim();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        String uri = session.getUri();

        if (logRequests) {
            logger.info("[WebAPI] " + method + " " + uri);
        }

        if ("OPTIONS".equalsIgnoreCase(method)) {
            return cors(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"));
        }

        if (!auth.isAuthenticated(session)) {
            ApiResponse error = ApiResponse.error("未授权：API Key 缺失或不正确");
            return cors(newFixedLengthResponse(Response.Status.UNAUTHORIZED,
                    "application/json; charset=utf-8", error.toJson()));
        }

        try {
            ApiResponse response = router.dispatch(method, uri, session);
            if (response == null) {
                ApiResponse notFound = ApiResponse.error("接口不存在: " + uri);
                return cors(newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "application/json; charset=utf-8", notFound.toJson()));
            }
            Response.Status status = response.isSuccess() ? Response.Status.OK : Response.Status.BAD_REQUEST;
            return cors(newFixedLengthResponse(status, "application/json; charset=utf-8", response.toJson()));
        } catch (Exception error) {
            logger.severe("[WebAPI] 处理请求出错: " + error.getMessage());
            ApiResponse response = ApiResponse.error("服务器内部错误: " + error.getMessage());
            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json; charset=utf-8", response.toJson()));
        }
    }

    /**
     * 按配置发 CORS 头。
     *
     * <p>{@code cors-origin} 留空时<b>完全不发</b> CORS 头，浏览器里的第三方页面就调不到
     * 这个接口——服务端到服务端的调用（机器人、面板后端）不受影响，因为 CORS 只约束浏览器。
     * 需要网页前端直连时再填具体来源，不建议填 {@code *}。</p>
     */
    private Response cors(Response response) {
        if (corsOrigin.isEmpty()) {
            return response;
        }
        response.addHeader("Access-Control-Allow-Origin", corsOrigin);
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        if (!corsOrigin.equals("*")) {
            // 来源随配置变化，得让缓存和代理知道
            response.addHeader("Vary", "Origin");
        }
        return response;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        logger.info("[WebAPI] HTTP 接口已启动: " + getHostname() + ":" + getListeningPort());
    }

    public void stopServer() {
        stop();
        logger.info("[WebAPI] HTTP 接口已停止");
    }
}

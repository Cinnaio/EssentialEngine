package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 面板的内嵌 HTTP 服务。
 *
 * <p>和 {@code webapi} 模块的 {@code HttpServer} 分开实现，因为两者的鉴权模型不同：
 * REST API 用固定 API Key，面板用登录后签发的会话 token，而且面板还得能在
 * <b>未登录</b>的情况下把登录页发出去。</p>
 *
 * <p>页面是打进 jar 里的单个自包含 HTML（样式与脚本全部内联，不引用任何外部 CDN），
 * 因为服务器多半没有外网出口，而且这样也不必开放额外的静态资源路径。</p>
 */
public class PanelServer extends NanoHTTPD {

    private static final String PAGE_RESOURCE = "panel/index.html";
    private static final String LOGO_RESOURCE = "panel/logo.png";

    /** OIDC 回调的处理器，由 {@link PanelModule} 注入。 */
    @FunctionalInterface
    public interface CallbackHandler {
        /**
         * 处理授权服务器的回调。
         *
         * @return 浏览器要跳转到的地址；成功时形如 {@code /#token=...}，失败时 {@code /#error=...}
         */
        String handle(String code, String state, String error);
    }

    private final Router router;
    private final SessionStore sessions;
    private final Logger logger;
    private final boolean logRequests;
    private final byte[] page;
    private final byte[] logo;
    private final CallbackHandler oidcCallback;

    public PanelServer(String hostname, int port, Router router, SessionStore sessions,
                       Logger logger, boolean logRequests, CallbackHandler oidcCallback) {
        super(hostname, port);
        this.router = router;
        this.sessions = sessions;
        this.logger = logger;
        this.logRequests = logRequests;
        this.oidcCallback = oidcCallback;
        this.page = loadResource(PAGE_RESOURCE,
                "<h1>panel/index.html 缺失，请重新构建插件</h1>".getBytes(StandardCharsets.UTF_8));
        this.logo = loadResource(LOGO_RESOURCE, new byte[0]);
    }

    /** 从 jar 里读一份资源，读不到就用兜底内容。 */
    private byte[] loadResource(String path, byte[] fallback) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                return fallback;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            stream.transferTo(buffer);
            return buffer.toByteArray();
        } catch (IOException error) {
            return fallback;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        String uri = session.getUri();
        if (uri == null || uri.isEmpty()) {
            uri = "/";
        }
        if (logRequests) {
            logger.info("[Panel] " + method + " " + uri);
        }

        // 面板是同源单页应用，不需要跨域；直接拒掉预检，避免被别的站点当接口用
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return harden(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""));
        }

        // 页面本身公开，否则没人能看到登录框
        if (uri.equals("/") || uri.equals("/index.html")) {
            Response response = newFixedLengthResponse(
                    Response.Status.OK, "text/html; charset=utf-8", new java.io.ByteArrayInputStream(page), page.length);
            return harden(response);
        }

        // 站点图标。登录页要用，所以同样不能要求会话
        if (uri.equals("/logo.png")) {
            if (logo.length == 0) {
                return json(Response.Status.NOT_FOUND, ApiResponse.error("logo.png 缺失"));
            }
            Response response = newFixedLengthResponse(Response.Status.OK, "image/png",
                    new java.io.ByteArrayInputStream(logo), logo.length);
            harden(response);
            // 图片基本不变，允许缓存；harden 里的 no-store 对它没意义
            response.addHeader("Cache-Control", "public, max-age=86400");
            return response;
        }

        // OIDC 回调：授权服务器把浏览器重定向回来，必须回 302 而不是 JSON。
        // 这一步天然是未登录状态，因此不能要求会话。
        if (uri.equals("/oauth/callback")) {
            if (oidcCallback == null) {
                return json(Response.Status.NOT_FOUND, ApiResponse.error("未启用 OAuth 登录"));
            }
            Map<String, String> query = session.getParms();
            String target = oidcCallback.handle(
                    query.get("code"), query.get("state"), query.get("error"));
            Response redirect = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
            redirect.addHeader("Location", target);
            return harden(redirect);
        }

        if (!uri.startsWith("/api/")) {
            return json(Response.Status.NOT_FOUND, ApiResponse.error("页面不存在: " + uri));
        }

        // 登录、探活与发起 OAuth 是仅有的免鉴权接口
        boolean publicRoute = uri.equals("/api/login")
                || uri.equals("/api/ping")
                || uri.equals("/api/oauth/start");
        if (!publicRoute && !sessions.validate(bearer(session))) {
            return json(Response.Status.UNAUTHORIZED, ApiResponse.error("未登录或会话已过期"));
        }

        try {
            ApiResponse response = router.dispatch(method, uri, session);
            if (response == null) {
                return json(Response.Status.NOT_FOUND, ApiResponse.error("接口不存在: " + uri));
            }
            return json(response.isSuccess() ? Response.Status.OK : Response.Status.BAD_REQUEST, response);
        } catch (Exception error) {
            logger.severe("[Panel] 处理请求出错: " + error);
            return json(Response.Status.INTERNAL_ERROR, ApiResponse.error("服务器内部错误"));
        }
    }

    /** 取出 Authorization 头里的会话 token。 */
    static String bearer(IHTTPSession session) {
        String header = session.getHeaders().get("authorization");
        if (header == null || header.isEmpty()) {
            return null;
        }
        return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    }

    /** 客户端 IP，用于登录限流。 */
    static String clientIp(IHTTPSession session) {
        String remote = session.getRemoteIpAddress();
        return remote == null || remote.isEmpty() ? "unknown" : remote;
    }

    private Response json(Response.Status status, ApiResponse body) {
        return harden(newFixedLengthResponse(status, "application/json; charset=utf-8", body.toJson()));
    }

    /**
     * 统一的安全响应头。
     *
     * <p>页面把样式和脚本全部内联，所以 CSP 必须放行 {@code 'unsafe-inline'}；
     * 但 {@code default-src 'self'} 仍然挡住了一切外部请求，
     * 配合 {@code frame-ancestors 'none'} 也无法被别的站点嵌进 iframe 里点击劫持。</p>
     */
    private Response harden(Response response) {
        response.addHeader("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        logger.info("[Panel] 管理面板已启动: http://" + getHostname() + ":" + getListeningPort());
    }

    public void stopServer() {
        stop();
        logger.info("[Panel] 管理面板已停止");
    }
}

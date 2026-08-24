package com.github.cinnaio.essentialengine.module.webapi.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import fi.iki.elonen.NanoHTTPD;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简易路由，支持 {@code /api/towns/{name}} 这样的路径参数。
 */
public class Router {

    private static final Gson GSON = new Gson();

    @FunctionalInterface
    public interface RouteHandler {
        ApiResponse handle(NanoHTTPD.IHTTPSession session, Map<String, String> pathParams);
    }

    /** 直接返回 HTTP 响应的处理器（用于文件下载、打包等二进制内容）。 */
    @FunctionalInterface
    public interface RawHandler {
        NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, Map<String, String> pathParams) throws Exception;
    }

    private static class Route {
        final String method;
        final Pattern pattern;
        final List<String> paramNames;
        final RouteHandler handler;

        Route(String method, String path, RouteHandler handler) {
            this.method = method;
            this.handler = handler;
            this.paramNames = new ArrayList<>();

            Matcher matcher = Pattern.compile("\\{([^}]+)}").matcher(path);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                paramNames.add(matcher.group(1));
                matcher.appendReplacement(sb, "([^/]+)");
            }
            matcher.appendTail(sb);
            this.pattern = Pattern.compile("^" + sb + "$");
        }

        Map<String, String> match(String method, String path) {
            if (!this.method.equalsIgnoreCase(method)) {
                return null;
            }
            Matcher matcher = pattern.matcher(path);
            if (!matcher.matches()) {
                return null;
            }
            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), matcher.group(i + 1));
            }
            return params;
        }
    }

    /** 原始响应路由，匹配逻辑与 JSON 路由相同。 */
    private static class RawRoute extends Route {
        final RawHandler rawHandler;

        RawRoute(String method, String path, RawHandler handler) {
            super(method, path, null);
            this.rawHandler = handler;
        }
    }

    private final List<Route> routes = new ArrayList<>();
    private final List<RawRoute> rawRoutes = new ArrayList<>();

    public void get(String path, RouteHandler handler) {
        routes.add(new Route("GET", path, handler));
    }

    public void post(String path, RouteHandler handler) {
        routes.add(new Route("POST", path, handler));
    }

    public void put(String path, RouteHandler handler) {
        routes.add(new Route("PUT", path, handler));
    }

    public void delete(String path, RouteHandler handler) {
        routes.add(new Route("DELETE", path, handler));
    }

    /** 注册一条返回原始 HTTP 响应的 GET 路由（二进制下载等）。 */
    public void getRaw(String path, RawHandler handler) {
        rawRoutes.add(new RawRoute("GET", path, handler));
    }

    public int size() {
        return routes.size() + rawRoutes.size();
    }

    /** 分发请求；没有匹配路由时返回 null。 */
    public ApiResponse dispatch(String method, String path, NanoHTTPD.IHTTPSession session) {
        for (Route route : routes) {
            Map<String, String> params = route.match(method, path);
            if (params != null) {
                return route.handler.handle(session, params);
            }
        }
        return null;
    }

    /** 分发原始响应请求；没有匹配路由时返回 null。 */
    public NanoHTTPD.Response dispatchRaw(String method, String path, NanoHTTPD.IHTTPSession session) throws Exception {
        for (RawRoute route : rawRoutes) {
            Map<String, String> params = route.match(method, path);
            if (params != null) {
                return route.rawHandler.handle(session, params);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 请求体工具

    /**
     * 读取请求体。使用 NanoHTTPD 的 parseBody，而不是直接读 InputStream
     * （后者在 keep-alive 连接上会一直阻塞到超时）。
     */
    public static String readBody(NanoHTTPD.IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String data = files.get("postData");
            if (data != null && !data.isEmpty()) {
                return data;
            }
            String queryParam = session.getParms().get("postData");
            return queryParam == null ? "{}" : queryParam;
        } catch (Exception error) {
            return "{}";
        }
    }

    /** 读取请求体并解析成 JSON 对象，失败返回空对象。 */
    public static JsonObject readJson(NanoHTTPD.IHTTPSession session) {
        try {
            JsonObject json = GSON.fromJson(readBody(session), JsonObject.class);
            return json == null ? new JsonObject() : json;
        } catch (Exception error) {
            return new JsonObject();
        }
    }
}

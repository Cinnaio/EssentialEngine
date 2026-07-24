package com.github.cinnaio.linkengine.core.http;

import fi.iki.elonen.NanoHTTPD;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple HTTP router that supports path parameters like /api/towns/{name}.
 */
public class Router {

    @FunctionalInterface
    public interface RouteHandler {
        ApiResponse handle(NanoHTTPD.IHTTPSession session, Map<String, String> pathParams);
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

            // Convert path template to regex: /api/towns/{name} -> /api/towns/([^/]+)
            String regex = path;
            Matcher m = Pattern.compile("\\{([^}]+)}").matcher(path);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                paramNames.add(m.group(1));
                m.appendReplacement(sb, "([^/]+)");
            }
            m.appendTail(sb);
            this.pattern = Pattern.compile("^" + sb + "$");
        }

        Map<String, String> match(String method, String path) {
            if (!this.method.equalsIgnoreCase(method)) return null;
            Matcher m = pattern.matcher(path);
            if (!m.matches()) return null;
            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), m.group(i + 1));
            }
            return params;
        }
    }

    private final List<Route> routes = new ArrayList<>();

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

    /**
     * Dispatch a request to the matching route handler.
     * Returns null if no route matches.
     */
    public ApiResponse dispatch(String method, String path, NanoHTTPD.IHTTPSession session) {
        for (Route route : routes) {
            Map<String, String> params = route.match(method, path);
            if (params != null) {
                return route.handler.handle(session, params);
            }
        }
        return null;
    }
}

package com.github.cinnaio.linkengine.core.http;

import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Embedded HTTP server based on NanoHTTPD.
 * Handles authentication, routing, and CORS.
 */
public class HttpServer extends NanoHTTPD {

    private final Router router;
    private final AuthMiddleware auth;
    private final Logger logger;

    public HttpServer(String hostname, int port, Router router, AuthMiddleware auth, Logger logger) {
        super(hostname, port);
        this.router = router;
        this.auth = auth;
        this.logger = logger;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        String uri = session.getUri();

        logger.info("[LinkEngine] " + method + " " + uri);

        // Handle CORS preflight
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"));
        }

        // Authentication check
        if (!auth.isAuthenticated(session)) {
            ApiResponse error = ApiResponse.error("Unauthorized: Invalid or missing API key");
            return corsResponse(newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json", error.toJson()));
        }

        // Route dispatch
        try {
            ApiResponse response = router.dispatch(method, uri, session);
            if (response == null) {
                ApiResponse notFound = ApiResponse.error("Not Found: " + uri);
                return corsResponse(newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "application/json", notFound.toJson()));
            }

            Response.Status status = response.isSuccess()
                    ? Response.Status.OK
                    : Response.Status.BAD_REQUEST;
            return corsResponse(newFixedLengthResponse(status, "application/json", response.toJson()));

        } catch (Exception e) {
            logger.severe("[LinkEngine] Error handling request: " + e.getMessage());
            ApiResponse error = ApiResponse.error("Internal Server Error: " + e.getMessage());
            return corsResponse(newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json", error.toJson()));
        }
    }

    private Response corsResponse(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        return response;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        logger.info("[LinkEngine] HTTP API server started on " + getHostname() + ":" + getListeningPort());
    }

    public void stopServer() {
        stop();
        logger.info("[LinkEngine] HTTP API server stopped");
    }
}

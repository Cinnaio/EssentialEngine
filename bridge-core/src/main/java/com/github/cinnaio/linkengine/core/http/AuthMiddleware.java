package com.github.cinnaio.linkengine.core.http;

import fi.iki.elonen.NanoHTTPD;

/**
 * API Key authentication middleware.
 * Validates the Authorization: Bearer <api-key> header.
 */
public class AuthMiddleware {

    private final String apiKey;

    public AuthMiddleware(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Check if the request is authenticated.
     */
    public boolean isAuthenticated(NanoHTTPD.IHTTPSession session) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return false;
        }
        // Support "Bearer <key>" format
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            return token.equals(apiKey);
        }
        // Also support raw key
        return authHeader.equals(apiKey);
    }
}

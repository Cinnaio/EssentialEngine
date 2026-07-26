package com.github.cinnaio.essentialengine.module.webapi.http;

import fi.iki.elonen.NanoHTTPD;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * API Key 鉴权：校验 {@code Authorization: Bearer <api-key>} 请求头。
 */
public class AuthMiddleware {

    private final String apiKey;

    public AuthMiddleware(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public boolean isAuthenticated(NanoHTTPD.IHTTPSession session) {
        String header = session.getHeaders().get("authorization");
        if (header == null || header.isEmpty()) {
            return false;
        }
        String token = header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
        return constantTimeEquals(token, apiKey);
    }

    /** 定长比较，避免通过响应时间反推 key。 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

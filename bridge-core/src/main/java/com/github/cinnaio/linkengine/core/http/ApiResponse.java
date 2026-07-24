package com.github.cinnaio.linkengine.core.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;

/**
 * Unified API response format.
 */
public class ApiResponse {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final boolean success;
    private final String module;
    private final Object data;
    private final String message;

    private ApiResponse(boolean success, String module, Object data, String message) {
        this.success = success;
        this.module = module;
        this.data = data;
        this.message = message;
    }

    public static ApiResponse ok(String module, Object data) {
        return new ApiResponse(true, module, data, "ok");
    }

    public static ApiResponse ok(String module, Object data, String message) {
        return new ApiResponse(true, module, data, message);
    }

    public static ApiResponse error(String module, String message) {
        return new ApiResponse(false, module, null, message);
    }

    public static ApiResponse error(String message) {
        return new ApiResponse(false, null, null, message);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getModule() {
        return module;
    }

    public Object getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}
